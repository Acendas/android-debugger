package com.acendas.androiddebugger.android

import com.acendas.androiddebugger.adb.Adb
import com.acendas.androiddebugger.adb.StreamHandle
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Per Story 6.1.1: server-side `adb logcat -v threadtime` reader with ring-buffered
 * storage, optional regex filter, and three MCP tools (`tail_logcat`, `read_logcat`,
 * `stop_logcat`).
 *
 * One [LogcatBuffer] = one running `adb logcat` subprocess + one bounded ring of parsed
 * entries. Buffers are owned by [LogcatRegistry] (held on the [Session]) so detach can
 * stop them all in one shot.
 */

/** Parsed threadtime line. */
data class LogcatEntry(
    /** Raw timestamp string as adb emitted it (e.g. `09-15 14:23:45.123`). */
    val ts: String,
    val pid: Int,
    val tid: Int,
    /** Single character: V/D/I/W/E/F/A. */
    val level: String,
    val tag: String,
    val message: String,
    /** Sequence number — monotonic per buffer; used by `read_logcat({ since })`. */
    val seq: Long,
)

/** Tools-facing settings snapshot of one buffer. */
data class LogcatSnapshot(
    val bufferId: Int,
    val createdAtMs: Long,
    val sizeNow: Int,
    val maxBuffer: Int,
    val nextSeq: Long,
    val alive: Boolean,
    val filter: String?,
    val regex: String?,
)

/**
 * Threadtime line parser. Format adb emits with `-v threadtime`:
 *
 *     MM-DD HH:MM:SS.mmm  PID  TID L tag: message
 *
 * Real-world examples (note variable spacing):
 *
 *     09-15 14:23:45.123   1234  5678 D MyTag: hello world
 *     09-15 14:23:45.123 25342 25342 W ActivityManager: lots of details
 *     09-15 14:23:45.123  1234  5678 E AndroidRuntime: FATAL EXCEPTION: main
 *
 * The tag may itself contain spaces (rare) but the trailing `: message` separator is
 * the disambiguator. We use a regex on `ts pid tid level` then split the remainder by
 * `:` once.
 *
 * Lines like `--------- beginning of crash` (logcat headers) are returned as null.
 */
internal object ThreadtimeParser {

    // ts is "MM-DD HH:MM:SS.mmm" -> 18 chars; tolerate optional leading year prefix
    // ("YYYY-MM-DD HH:MM:SS.mmm") which appears on some Android versions when -v
    // year is set even without us asking.
    private val LINE_REGEX: Pattern = Pattern.compile(
        "^(\\S+\\s+\\S+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.+)$",
    )

    fun parse(line: String, seq: Long): LogcatEntry? {
        if (line.isEmpty()) return null
        if (line.startsWith("---------")) return null
        if (line.startsWith("--------- beginning")) return null
        val m = LINE_REGEX.matcher(line)
        if (!m.matches()) return null
        val ts = m.group(1)
        val pid = m.group(2).toIntOrNull() ?: return null
        val tid = m.group(3).toIntOrNull() ?: return null
        val level = m.group(4)
        val rest = m.group(5)
        // Split on first ": " — tags don't contain that pair.
        val idx = rest.indexOf(": ")
        val (tag, message) = if (idx >= 0) {
            rest.substring(0, idx).trim() to rest.substring(idx + 2)
        } else {
            // Fallback: tag with no message, or unusual line — keep tag, empty message.
            rest.trim() to ""
        }
        return LogcatEntry(
            ts = ts,
            pid = pid,
            tid = tid,
            level = level,
            tag = tag,
            message = message,
            seq = seq,
        )
    }
}

/**
 * One running logcat reader. Backed by a single `adb logcat` subprocess; entries are
 * pushed into a bounded [ringBuffer] of size [maxBuffer]. Read from the buffer with
 * [readSince].
 *
 * Lifecycle:
 *   - `start()` is called by the registry on creation; spawns the adb subprocess.
 *   - `stop()` is idempotent — kills the subprocess + drops the handle. Called by
 *     `stop_logcat` MCP tool and from `Session.detach()` via `LogcatRegistry.stopAll()`.
 */
class LogcatBuffer internal constructor(
    val id: Int,
    val maxBuffer: Int,
    val filter: String?,
    val regexPattern: String?,
    val sinceArg: String?,
    val serial: String?,
    val pidFilter: Int?,
    private val adb: Adb,
) {
    val createdAtMs: Long = System.currentTimeMillis()

    private val regex: Regex? = regexPattern?.let { Regex(it) }
    private val tagFilter: String? = filter?.takeIf { it.isNotBlank() }

    private val ring: ArrayDeque<LogcatEntry> = ArrayDeque(maxBuffer)
    private val seqCounter: AtomicInteger = AtomicInteger(0)
    private val lock: Any = Any()

    @Volatile private var handle: StreamHandle? = null
    @Volatile private var stopped: Boolean = false

    fun start() {
        val args = buildList {
            if (serial != null) { add("-s"); add(serial) }
            add("logcat")
            add("-v"); add("threadtime")
            if (sinceArg != null) { add("-T"); add(sinceArg) }
            if (pidFilter != null) { add("--pid"); add(pidFilter.toString()) }
        }
        handle = adb.runStream(args) { line -> onLine(line) }
    }

    private fun onLine(rawLine: String) {
        val seq = seqCounter.incrementAndGet().toLong()
        val entry = ThreadtimeParser.parse(rawLine, seq) ?: return
        if (tagFilter != null && entry.tag != tagFilter) return
        if (regex != null && !regex.containsMatchIn(entry.message)) return
        synchronized(lock) {
            if (ring.size >= maxBuffer) ring.pollFirst()
            ring.addLast(entry)
        }
    }

    /** Snapshot of all entries with `seq > sinceSeq`. */
    fun readSince(sinceSeq: Long): List<LogcatEntry> = synchronized(lock) {
        if (sinceSeq <= 0L) return ring.toList()
        ring.filter { it.seq > sinceSeq }
    }

    fun snapshot(): LogcatSnapshot = LogcatSnapshot(
        bufferId = id,
        createdAtMs = createdAtMs,
        sizeNow = synchronized(lock) { ring.size },
        maxBuffer = maxBuffer,
        nextSeq = seqCounter.get().toLong() + 1L,
        alive = !stopped && (handle?.alive ?: false),
        filter = filter,
        regex = regexPattern,
    )

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { handle?.stop() }
        handle = null
    }

    /** Test-only direct insertion path so the parser/filter logic can be exercised. */
    internal fun pushForTest(rawLine: String) = onLine(rawLine)
}

/**
 * Per-session registry of [LogcatBuffer]s. Mints sequential ids, holds them in a
 * concurrent map, and exposes [stopAll] for `Session.detach()`.
 */
class LogcatRegistry {
    private val nextId: AtomicInteger = AtomicInteger(1)
    private val buffers: MutableMap<Int, LogcatBuffer> = ConcurrentHashMap()

    fun create(
        adb: Adb,
        serial: String?,
        pidFilter: Int?,
        filter: String?,
        regex: String?,
        sinceArg: String?,
        maxBuffer: Int,
    ): LogcatBuffer {
        val id = nextId.getAndIncrement()
        val buf = LogcatBuffer(
            id = id,
            maxBuffer = maxBuffer.coerceIn(50, 50_000),
            filter = filter,
            regexPattern = regex,
            sinceArg = sinceArg,
            serial = serial,
            pidFilter = pidFilter,
            adb = adb,
        )
        buffers[id] = buf
        runCatching { buf.start() }
        return buf
    }

    fun get(id: Int): LogcatBuffer? = buffers[id]

    fun stop(id: Int): Boolean {
        val b = buffers.remove(id) ?: return false
        b.stop()
        return true
    }

    fun list(): List<LogcatSnapshot> = buffers.values.sortedBy { it.id }.map { it.snapshot() }

    fun stopAll() {
        val snap = buffers.values.toList()
        buffers.clear()
        snap.forEach { runCatching { it.stop() } }
    }
}
