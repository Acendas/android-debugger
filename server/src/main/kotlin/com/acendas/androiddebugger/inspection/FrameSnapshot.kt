package com.acendas.androiddebugger.inspection

/**
 * Structured paused-state snapshot the agent reads after every pause. The whole point
 * of bundling frames + locals + watches into one payload is to defeat the canonical
 * AI-debugger anti-pattern of re-reading the same frame N times. Per Story 2.1.1.
 */
data class FrameSnapshot(
    val event: String,           // BREAKPOINT, STEP, EXCEPTION, PAUSED
    val thread: ThreadInfo,
    val frames: List<FrameInfo>,
    val watches: List<WatchValue>,
    val pausedReason: String?,
)

data class ThreadInfo(
    val id: Long,
    val name: String,
    val status: String,
)

data class FrameInfo(
    val index: Int,
    val frameId: String,
    val method: String,
    val className: String,
    val sourceFile: String?,
    val lineNumber: Int,
    val locals: Map<String, RenderedValue>?, // null if AbsentInformationException
    val localsUnavailable: Boolean,
    val thisId: String?,
)

data class WatchValue(
    val expr: String,
    val value: RenderedValue?,
    val error: String?,
    val stale: Boolean = false,
)
