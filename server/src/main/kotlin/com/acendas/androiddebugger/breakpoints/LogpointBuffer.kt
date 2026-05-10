package com.acendas.androiddebugger.breakpoints

import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * One rendered logpoint hit. Pushed by [LogpointBuffer.push] when a logpoint breakpoint
 * fires. Phase 6 logcat tooling will read these out via `list_logpoint_entries` and
 * eventually fold them into the `read_logcat` stream as synthetic entries.
 */
data class LogpointEntry(
    /** Wall-clock time the logpoint fired. */
    val timestamp: Instant,
    /** Monotonic id so callers can poll with `since` semantics. */
    val seq: Long,
    /** Thread name we paused/observed on. */
    val threadName: String?,
    /** Source file from `Location.sourceName()` (null if `AbsentInformationException`). */
    val file: String?,
    /** Source line number. */
    val line: Int,
    /** Breakpoint id that produced this entry — useful for correlating with `list_breakpoints`. */
    val breakpointId: Int,
    /** Rendered message after `{expr}` substitution. */
    val rendered: String,
)

/**
 * Thread-safe ring buffer (capacity = 1000) of [LogpointEntry] records. Singleton so the
 * event-loop thread (writer) and any tool thread (reader) share state.
 *
 * Per Task 3.1.4.1 — this is the buffer logpoints push into; Phase 6 logcat will read it.
 */
object LogpointBuffer {

    private const val CAPACITY = 1000

    private val lock = Any()
    private val deque: ArrayDeque<LogpointEntry> = ArrayDeque(CAPACITY)
    private val seqCounter = AtomicLong(0L)

    /** Append a new entry, dropping the oldest if at capacity. Returns the assigned seq. */
    fun push(
        threadName: String?,
        file: String?,
        line: Int,
        breakpointId: Int,
        rendered: String,
    ): Long {
        val seq = seqCounter.incrementAndGet()
        val entry = LogpointEntry(
            timestamp = Instant.now(),
            seq = seq,
            threadName = threadName,
            file = file,
            line = line,
            breakpointId = breakpointId,
            rendered = rendered,
        )
        synchronized(lock) {
            if (deque.size >= CAPACITY) deque.pollFirst()
            deque.addLast(entry)
        }
        return seq
    }

    /** Snapshot of all currently-buffered entries (oldest first). */
    fun snapshot(): List<LogpointEntry> = synchronized(lock) { deque.toList() }

    /** Snapshot of entries with `seq > since`. Use to poll incrementally. */
    fun since(since: Long): List<LogpointEntry> = synchronized(lock) {
        if (since <= 0L) return deque.toList()
        deque.filter { it.seq > since }
    }

    /** Drop everything. Called from `Session.reset()` so a re-attach starts clean. */
    fun clear() {
        synchronized(lock) { deque.clear() }
        // Don't reset seqCounter — keep it monotonic across reattaches so a stale `since`
        // from the agent doesn't surface old data again.
    }

    /** Current count, cheap read for diagnostics. */
    fun size(): Int = synchronized(lock) { deque.size }
}
