package com.acendas.androiddebugger.plans

/**
 * Stable handle on an in-flight plan execution. The Plan Executor implements this and
 * publishes it via [com.acendas.androiddebugger.Session.activePlan] so [com.acendas.androiddebugger.tools.PlanTools]
 * can drive `pause_plan` / `abort_plan` without having a direct reference to the executor.
 *
 * Decoupling rationale: keeping the public surface narrow (4 methods) makes the executor
 * easy to swap or wrap in tests without touching the tools layer.
 */
interface PlanRunHandle {
    /** Stable id of this plan run. Same value as Session.activePlanId. */
    val planId: String

    /** Plan name from [Plan.name]. */
    val planName: String

    /** True if the plan has reached a terminal state (completed/aborted/yielded/timeout/error). */
    val isTerminal: Boolean

    /**
     * Hand control back to the agent. VM stays paused at the last handled event.
     * Returns the partial report. Idempotent if already terminal.
     */
    suspend fun pause(reason: String): PlanReport

    /**
     * Clean shutdown. Plan-owned breakpoints removed, VM resumes. Returns the partial
     * report. Idempotent if already terminal.
     */
    suspend fun abort(reason: String): PlanReport

    /**
     * Wait for natural termination (completion / yield / abort / timeout / error).
     * Returns the final report. Multiple callers may join — all see the same report.
     */
    suspend fun awaitTerminal(): PlanReport
}
