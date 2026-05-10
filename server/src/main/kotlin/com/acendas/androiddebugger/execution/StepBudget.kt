package com.acendas.androiddebugger.execution

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-thread step counter that caps step-into loops at [LIMIT] consecutive steps inside
 * the same method. Beyond that the agent is most likely stuck in a loop and would benefit
 * from re-orienting (set a different breakpoint, run_to_line, etc.) — keeping it stepping
 * just burns tokens. Per Story 7.1.4.
 *
 * Reset on resume / run_to_line / breakpoint hit / frame change. Thread-safe under
 * concurrent access from MCP tool coroutines and the JDI event loop.
 */
class StepBudget {

    /** After this many consecutive same-method steps, the next step is refused. */
    val limit: Int = 50

    private data class State(var consecutiveSteps: Int, var lastSeenMethod: String?)

    private val stateByThread: ConcurrentHashMap<Long, State> = ConcurrentHashMap()

    /**
     * Record an attempt to step on [threadId], in [currentMethod] (`Class.method`).
     * Returns `true` if the step is allowed; `false` if the budget is exhausted.
     *
     * On exhaustion the state is **not** auto-reset — the caller must explicitly invoke
     * [reset] (typically via `resume`, `run_to_line`, or after a frame change). This way
     * re-calling the step tool returns the same exhausted result deterministically until
     * the user re-orients.
     */
    fun tryStep(threadId: Long, currentMethod: String): Boolean {
        val s = stateByThread.compute(threadId) { _, prior ->
            val st = prior ?: State(0, null)
            if (st.lastSeenMethod == currentMethod) {
                st.consecutiveSteps += 1
            } else {
                st.consecutiveSteps = 1
                st.lastSeenMethod = currentMethod
            }
            st
        }!!
        return s.consecutiveSteps <= limit
    }

    /** True iff the budget is currently exhausted on [threadId]. */
    fun isExhausted(threadId: Long): Boolean {
        val s = stateByThread[threadId] ?: return false
        return s.consecutiveSteps > limit
    }

    /** The method we last saw step inside on [threadId], or null if untracked. */
    fun lastMethod(threadId: Long): String? = stateByThread[threadId]?.lastSeenMethod

    /** The current consecutive-step count for [threadId]. */
    fun consecutiveSteps(threadId: Long): Int = stateByThread[threadId]?.consecutiveSteps ?: 0

    /** Reset the budget for [threadId] (e.g. on resume / run_to_line / bp hit). */
    fun reset(threadId: Long) {
        stateByThread.remove(threadId)
    }

    /** Reset all per-thread budgets — used on detach. */
    fun clear() {
        stateByThread.clear()
    }
}
