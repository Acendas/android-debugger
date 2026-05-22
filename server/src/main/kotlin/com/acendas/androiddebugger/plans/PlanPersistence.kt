package com.acendas.androiddebugger.plans

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent storage for v1.7 Debug Plans.
 *
 * Saves Plan JSON to `$CLAUDE_PLUGIN_DATA/android-debugger/plans/<name>.json` so the
 * agent can compose a plan once and re-run it across attach cycles. Mirrors the
 * pattern in [com.acendas.androiddebugger.state.SessionPersistence] — atomic write
 * via temp + `Files.move(ATOMIC_MOVE)`, POSIX mode 0600 best-effort, single-warning
 * fallback when `CLAUDE_PLUGIN_DATA` is unset.
 *
 * **Why a separate object** (vs. extending SessionPersistence): Plan storage is a
 * distinct lifecycle — plans outlive any particular `(serial, package)` pair, and
 * the API surface (list / save / load / delete by name) differs enough that
 * sharing internals would require ugly parameterization.
 *
 * **Schema versioning**: on-disk JSON carries `version`. A mismatch is treated as
 * "no saved state" (logged at info), never thrown. The current schema version is
 * pinned to [Plan.version]'s default; on-disk parses use a tolerant pre-pass to
 * read just `version` before attempting a full deserialize.
 *
 * **Serialization**: uses [PlanJson.json] so on-disk files round-trip the same
 * shape as the wire protocol (discriminator `kind`, `ignoreUnknownKeys`).
 */
object PlanPersistence {

    /** Persisted file schema version. Bump only on incompatible changes. */
    const val CURRENT_SCHEMA_VERSION: Int = 1

    @Volatile
    internal var dataDirOverrideForTest: Path? = null

    private val warnedAboutMissingDataDir: AtomicBoolean = AtomicBoolean(false)

    private val log = org.slf4j.LoggerFactory.getLogger("android-debugger.plans")

    /** Lenient JSON used only for reading the `version` field before full parse. */
    private val versionPeekJson: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Save [plan] under [name]. Returns path on success, null if persistence disabled / failed. */
    fun save(name: String, plan: Plan): Path? {
        require(name.isNotBlank()) { "Plan name must not be blank" }
        val dir = plansDir() ?: return null
        return runCatching {
            Files.createDirectories(dir)
            val text = PlanJson.json.encodeToString(Plan.serializer(), plan)
            val target = dir.resolve(filenameFor(name))
            atomicWrite(target, text)
            target
        }.onFailure {
            log.warn("Failed to save plan '$name': ${it.message}")
        }.getOrNull()
    }

    /** Load plan by name. Null if missing / disabled / schema mismatch / parse error. */
    fun load(name: String): Plan? {
        require(name.isNotBlank()) { "Plan name must not be blank" }
        val dir = plansDir() ?: return null
        val file = dir.resolve(filenameFor(name))
        if (!Files.exists(file)) return null
        return runCatching {
            val text = Files.readString(file)
            val schemaVersion = peekVersion(text)
            if (schemaVersion != null && schemaVersion != CURRENT_SCHEMA_VERSION) {
                log.info(
                    "Ignoring saved plan at $file: schema version $schemaVersion, " +
                        "expected $CURRENT_SCHEMA_VERSION",
                )
                return@runCatching null
            }
            PlanJson.json.decodeFromString(Plan.serializer(), text)
        }.onFailure {
            log.warn("Failed to load plan from $file: ${it.message}")
        }.getOrNull()
    }

    /** Delete the saved plan. Returns true if a file was removed, false otherwise. */
    fun delete(name: String): Boolean {
        require(name.isNotBlank()) { "Plan name must not be blank" }
        val dir = plansDir() ?: return false
        val file = dir.resolve(filenameFor(name))
        return runCatching { Files.deleteIfExists(file) }.getOrDefault(false)
    }

    /**
     * List all saved plans. Returns metadata (name, version, SHA-256 of bytes,
     * size, mtime) so callers can render a list without re-reading every body.
     * Ordered mtime-descending (most-recently-modified first). Files that fail
     * to parse are skipped, not propagated.
     */
    fun list(): List<PlanMetadata> {
        val dir = plansDir() ?: return emptyList()
        if (!Files.exists(dir)) return emptyList()
        return runCatching {
            val files: List<Path> = Files.list(dir).use { stream ->
                val acc = mutableListOf<Path>()
                stream.forEach { p ->
                    val nm = p.fileName.toString()
                    if (Files.isRegularFile(p) && nm.endsWith(".json") && !nm.endsWith(".tmp.json")) {
                        acc.add(p)
                    }
                }
                acc
            }
            files
                .mapNotNull { readMetadata(it) }
                .sortedByDescending { it.mtimeIso }
        }.onFailure {
            log.warn("Failed to list plans: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /** Metadata returned by [list]. */
    data class PlanMetadata(
        val name: String,
        val version: Int,
        val sha256: String,
        val sizeBytes: Long,
        val mtimeIso: String,
    )

    /** Test hook — reset warned-once flag so tests start clean. */
    internal fun resetWarningForTest() {
        warnedAboutMissingDataDir.set(false)
    }

    // --------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------

    private fun readMetadata(file: Path): PlanMetadata? = runCatching {
        val bytes = Files.readAllBytes(file)
        val text = String(bytes, Charsets.UTF_8)
        // Validate the file at least parses as JSON; skip otherwise (corruption).
        val parsed = runCatching { versionPeekJson.parseToJsonElement(text) }.getOrNull()
            ?: return@runCatching null
        if (parsed !is JsonObject) return@runCatching null
        // Missing `version` field means default (kotlinx-serialization omits defaults when
        // `encodeDefaults=false`); treat as the current schema version.
        val version = (parsed["version"] as? JsonPrimitive)?.intOrNull ?: CURRENT_SCHEMA_VERSION
        val mtime = Files.getLastModifiedTime(file).toInstant()
        val nameFromFile = file.fileName.toString().removeSuffix(".json")
        PlanMetadata(
            name = nameFromFile,
            version = version,
            sha256 = sha256Hex(bytes),
            sizeBytes = bytes.size.toLong(),
            mtimeIso = mtime.atOffset(java.time.ZoneOffset.UTC).toString(),
        )
    }.getOrNull()

    private fun peekVersion(text: String): Int? = runCatching {
        val obj = versionPeekJson.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
        (obj["version"] as? JsonPrimitive)?.intOrNull
    }.getOrNull()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(Character.forDigit((b.toInt() ushr 4) and 0xF, 16))
            sb.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return sb.toString()
    }

    /**
     * Resolve the plans directory from the test override (if set) or
     * `CLAUDE_PLUGIN_DATA`. Returns null (and logs a single warning the first
     * time) if neither is available.
     */
    private fun plansDir(): Path? {
        val override = dataDirOverrideForTest
        if (override != null) return override.resolve("android-debugger").resolve("plans")
        val base = System.getenv("CLAUDE_PLUGIN_DATA")
        if (base.isNullOrBlank()) {
            if (warnedAboutMissingDataDir.compareAndSet(false, true)) {
                log.info(
                    "CLAUDE_PLUGIN_DATA is unset; v1.7 plan persistence is disabled. " +
                        "Update Claude Code to a version that propagates CLAUDE_PLUGIN_DATA to MCP subprocesses.",
                )
            }
            return null
        }
        return Paths.get(base, "android-debugger", "plans")
    }

    /** Sanitize a plan name for filesystem safety. Mirrors SessionPersistence. */
    private fun filenameFor(name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        // Defensive: collapse runs of dots that could climb the tree post-sanitize.
        val noDotDot = safe.replace("..", "__")
        return "$noDotDot.json"
    }

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
                    retainAll(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                },
            )
        }
    }

    /** Touch the last-modified time. Used by tests to force ordering. */
    @Suppress("unused")
    internal fun touchForTest(name: String, instant: Instant) {
        val dir = plansDir() ?: return
        val file = dir.resolve(filenameFor(name))
        if (Files.exists(file)) {
            runCatching {
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(instant))
            }
        }
    }
}
