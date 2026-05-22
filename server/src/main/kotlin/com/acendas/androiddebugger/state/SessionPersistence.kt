package com.acendas.androiddebugger.state

import com.acendas.androiddebugger.breakpoints.BreakpointKind
import com.acendas.androiddebugger.breakpoints.BreakpointMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent state across `detach` — per Phase B of the v1.3 plan.
 *
 * The agent-driven workflow rarely keeps one continuous debug session: reproducing a
 * bug usually means attach → run → fail → detach → relaunch → re-attach. Without
 * persistence the agent has to re-add every breakpoint and watch on each cycle,
 * which is cheap-per-call but adds up to dozens of tool round-trips per debug.
 *
 * v1.3 saves breakpoint + watch state on `detach` (unless `persist: false`) and
 * rehydrates on `attach` to the same `(serial, package)` pair. Original ids are
 * preserved so the agent's prior `remove_breakpoint(id)` calls keep working
 * across the cycle.
 *
 * **Storage**: `$CLAUDE_PLUGIN_DATA/android-debugger/sessions/<serial>_<package>.json`
 * (atomic write via temp + rename; mode 0600 on POSIX). If `CLAUDE_PLUGIN_DATA` is
 * unset (older Claude Code versions that don't propagate it to MCP server
 * subprocesses), persistence is silently disabled — we log a single warning the
 * first time and `save`/`load` no-op thereafter.
 *
 * **Schema version**: bumped on incompatible changes. Loaders for older schemas
 * are not provided in v1.3 (would land in v1.x as needed); a mismatched version
 * is treated as no saved state.
 */
object SessionPersistence {

    private const val CURRENT_SCHEMA_VERSION: Int = 1

    private val warnedAboutMissingDataDir: AtomicBoolean = AtomicBoolean(false)

    private val log = org.slf4j.LoggerFactory.getLogger("android-debugger.state")

    private val json: Json = Json {
        prettyPrint = false
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @Serializable
    data class SavedBreakpoint(
        val id: Int,
        val kind: String,
        val file: String? = null,
        val line: Int? = null,
        val condition: String? = null,
        val hitCount: Int? = null,
        val logMessage: String? = null,
        val exceptionClass: String? = null,
        val caught: Boolean = false,
        val uncaught: Boolean = false,
        val methodClass: String? = null,
        val methodName: String? = null,
        val fieldClass: String? = null,
        val fieldName: String? = null,
        val classPattern: String? = null,
        val enabled: Boolean = true,
    )

    @Serializable
    data class SavedWatch(val id: Int, val expr: String)

    @Serializable
    data class SavedSession(
        val version: Int = CURRENT_SCHEMA_VERSION,
        val savedAt: String,
        val serial: String,
        val packageName: String,
        val breakpoints: List<SavedBreakpoint> = emptyList(),
        val watches: List<SavedWatch> = emptyList(),
    )

    /**
     * Save the current breakpoint + watch state for `(serial, package)`. No-ops if
     * `CLAUDE_PLUGIN_DATA` is unset (older Claude Code versions don't propagate it).
     * Returns the path written on success, `null` on no-op or failure (failures are
     * logged but never propagated — losing persistence is non-fatal).
     */
    fun save(
        serial: String,
        packageName: String,
        breakpoints: List<BreakpointMeta>,
        watches: List<Pair<Int, String>>,
    ): Path? {
        val dir = sessionsDir() ?: return null
        return runCatching {
            Files.createDirectories(dir)
            // v1.7: plan-owned breakpoints (planId != null) are scoped to a single
            // Debug Plan run and torn down on plan completion / abort / yield (or by
            // detach, which ends the plan). They are intentionally NOT persisted across
            // detach — a fresh attach starts with a clean plan slate.
            val savedBreakpoints = breakpoints.filter { it.planId == null }.map { it.toSaved() }
            val savedWatches = watches.map { (id, expr) -> SavedWatch(id, expr) }
            val payload = SavedSession(
                savedAt = Instant.now().toString(),
                serial = serial,
                packageName = packageName,
                breakpoints = savedBreakpoints,
                watches = savedWatches,
            )
            val text = json.encodeToString(payload)
            val target = dir.resolve(filenameFor(serial, packageName))
            atomicWrite(target, text)
            target
        }.onFailure {
            log.warn("Failed to save session state for $serial/$packageName: ${it.message}")
        }.getOrNull()
    }

    /**
     * Load the saved state for `(serial, package)`, or `null` if none exists / data
     * dir unset / schema mismatch. Errors are logged + treated as "no saved state".
     */
    fun load(serial: String, packageName: String): SavedSession? {
        val dir = sessionsDir() ?: return null
        val file = dir.resolve(filenameFor(serial, packageName))
        if (!Files.exists(file)) return null
        return runCatching {
            val text = Files.readString(file)
            val loaded = json.decodeFromString(SavedSession.serializer(), text)
            if (loaded.version != CURRENT_SCHEMA_VERSION) {
                log.info(
                    "Ignoring saved session at $file: schema version ${loaded.version}, " +
                        "expected $CURRENT_SCHEMA_VERSION",
                )
                return@runCatching null
            }
            loaded
        }.onFailure {
            log.warn("Failed to load session state from $file: ${it.message}")
        }.getOrNull()
    }

    /** Remove the saved state for `(serial, package)`. Idempotent; never throws. */
    fun clear(serial: String, packageName: String) {
        val dir = sessionsDir() ?: return
        val file = dir.resolve(filenameFor(serial, packageName))
        runCatching { Files.deleteIfExists(file) }
    }

    /**
     * Test-only override for the data dir. Production code never sets this — it stays
     * null and resolution falls through to `CLAUDE_PLUGIN_DATA`. We allow this rather
     * than fighting JDK 17's module-access restrictions on `ProcessEnvironment` so
     * tests stay cross-platform.
     */
    @Volatile
    internal var dataDirOverrideForTest: Path? = null

    /**
     * Resolve the sessions directory from the test override (if set) or
     * `CLAUDE_PLUGIN_DATA`. Returns null (and logs a single warning the first time)
     * if neither is available.
     */
    private fun sessionsDir(): Path? {
        val override = dataDirOverrideForTest
        if (override != null) return override.resolve("android-debugger").resolve("sessions")
        val base = System.getenv("CLAUDE_PLUGIN_DATA")
        if (base.isNullOrBlank()) {
            if (warnedAboutMissingDataDir.compareAndSet(false, true)) {
                log.info(
                    "CLAUDE_PLUGIN_DATA is unset; v1.3 session persistence is disabled. " +
                        "Update Claude Code to a version that propagates CLAUDE_PLUGIN_DATA to MCP subprocesses.",
                )
            }
            return null
        }
        return Paths.get(base, "android-debugger", "sessions")
    }

    private fun filenameFor(serial: String, packageName: String): String {
        val safeSerial = sanitize(serial)
        val safePkg = sanitize(packageName)
        return "${safeSerial}_${safePkg}.json"
    }

    /** Replace characters that have special meaning on any common filesystem. */
    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    /** Atomic-write to `path`: write to `.tmp` then `Files.move` with ATOMIC_MOVE. */
    private fun atomicWrite(path: Path, content: String) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(
            tmp,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        applyPosix0600(tmp)
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Throwable) {
            // Some Windows filesystems can refuse ATOMIC_MOVE; fall back to plain replace.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Best-effort mode-0600 on POSIX; ignored on Windows. */
    private fun applyPosix0600(path: Path) {
        runCatching {
            val view = Files.getFileAttributeView(
                path,
                java.nio.file.attribute.PosixFileAttributeView::class.java,
            ) ?: return
            view.setPermissions(
                PosixFilePermissions.fromString("rw-------").toMutableSet().apply {
                    // The fromString call already produces the exact perm set; just normalize.
                    retainAll(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                },
            )
        }
    }

    /** Test hook — resets the "warned once" flag so a fresh test starts clean. */
    internal fun resetWarningForTest() {
        warnedAboutMissingDataDir.set(false)
    }
}

/** Serialize a BreakpointMeta to its persistable form. */
private fun BreakpointMeta.toSaved(): SessionPersistence.SavedBreakpoint = SessionPersistence.SavedBreakpoint(
    id = id,
    kind = kind.name,
    file = file,
    line = line,
    condition = condition,
    hitCount = hitCount,
    logMessage = logMessage,
    exceptionClass = exceptionClass,
    caught = caught,
    uncaught = uncaught,
    methodClass = methodClass,
    methodName = methodName,
    fieldClass = fieldClass,
    fieldName = fieldName,
    classPattern = classPattern,
    enabled = enabled,
)

/** Parse a saved kind string back to the enum; returns null if unknown. */
fun parseBreakpointKind(kind: String): BreakpointKind? =
    runCatching { BreakpointKind.valueOf(kind) }.getOrNull()
