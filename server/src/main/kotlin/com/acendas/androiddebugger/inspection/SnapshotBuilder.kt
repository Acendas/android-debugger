package com.acendas.androiddebugger.inspection

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference

/**
 * Builds a [FrameSnapshot] from a paused thread. Walks up to [depth] frames, renders
 * locals via [ValueRenderer], catches [AbsentInformationException] per-frame so a
 * release-build frame doesn't fail the whole snapshot. Per Tasks 2.1.1.2 and 2.1.1.4.
 *
 * Per Story 5.1.1, the builder also evaluates registered watches against the top frame
 * via the session's `watchManager`. The `watchEvaluator` lambda is injectable so unit
 * tests don't need a live JDI VM.
 */
class SnapshotBuilder(
    /**
     * Hook to evaluate registered watches against the paused thread + frame index.
     * Default routes to the singleton `Session.watchManager`. Best-effort: errors get
     * captured per-watch; the snapshot is never failed by a busted watch.
     */
    private val watchEvaluator: (ThreadReference, Int) -> List<WatchValue> = { thread, frameIdx ->
        com.acendas.androiddebugger.Session.watchManager.evaluateAll(thread, frameIdx)
    },
) {

    fun build(
        thread: ThreadReference,
        depth: Int = 5,
        event: String = "PAUSED",
        pausedReason: String? = null,
    ): FrameSnapshot {
        val frameCount = try { thread.frameCount() } catch (_: Throwable) { 0 }
        val n = minOf(depth, frameCount)
        val frames = (0 until n).map { idx ->
            buildFrame(thread, idx, thread.frame(idx))
        }
        // Per Story 5.1.1: watches are evaluated against the top frame. Wrap in
        // runCatching so a totally-busted evaluator (e.g., the Evaluator's executor
        // shut down) can never poison the read path — empty list is the safe default.
        val watches: List<WatchValue> = if (frames.isNotEmpty()) {
            runCatching { watchEvaluator(thread, 0) }.getOrDefault(emptyList())
        } else emptyList()
        return FrameSnapshot(
            event = event,
            thread = ThreadInfo(
                id = thread.uniqueID(),
                name = thread.name(),
                status = threadStatus(thread),
            ),
            frames = frames,
            watches = watches,
            pausedReason = pausedReason,
        )
    }

    private fun buildFrame(thread: ThreadReference, idx: Int, frame: StackFrame): FrameInfo {
        val location = frame.location()
        val method = location.method()
        val (locals, unavailable) = try {
            val vars = frame.visibleVariables()
            val rendered = vars.associate { v ->
                val value = try { frame.getValue(v) } catch (_: Throwable) { null }
                v.name() to ValueRenderer.render(value)
            }
            rendered to false
        } catch (_: AbsentInformationException) {
            null to true
        } catch (_: Throwable) {
            null to true
        }
        val thisRef = try { frame.thisObject() } catch (_: Throwable) { null }
        val thisId = thisRef?.let { ObjectIdMint.registerObject(it) }
        val sourceFile = try { location.sourceName() } catch (_: AbsentInformationException) { null }
        return FrameInfo(
            index = idx,
            frameId = ObjectIdMint.frameId(thread, idx),
            method = method.name(),
            className = location.declaringType().name(),
            sourceFile = sourceFile,
            lineNumber = location.lineNumber(),
            locals = locals,
            localsUnavailable = unavailable,
            thisId = thisId,
        )
    }

    private fun threadStatus(thread: ThreadReference): String = try {
        when (thread.status()) {
            ThreadReference.THREAD_STATUS_UNKNOWN -> "unknown"
            ThreadReference.THREAD_STATUS_ZOMBIE -> "zombie"
            ThreadReference.THREAD_STATUS_RUNNING -> "running"
            ThreadReference.THREAD_STATUS_SLEEPING -> "sleeping"
            ThreadReference.THREAD_STATUS_MONITOR -> "monitor"
            ThreadReference.THREAD_STATUS_WAIT -> "wait"
            ThreadReference.THREAD_STATUS_NOT_STARTED -> "not_started"
            else -> "?"
        }
    } catch (_: Throwable) {
        "?"
    }
}
