package com.acendas.androiddebugger.plans

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * v1.7.1 M5 — 3-stage force-abort tests.
 *
 * The full force-abort path requires a live JDI VirtualMachine. These tests exercise
 * the pure decision logic via [PlanExecutor.ForceAbortStateMachine]: stage transitions
 * are independent of coroutine scheduling and provable by table-driven check.
 *
 * Live coverage of the actual coroutine path (cooperative cancel timeout, force
 * release, force complete) lives in the smoke gate where a real VM can simulate a
 * wedged executor.
 */
class PlanExecutorM5ForceAbortTest {

    @Test
    fun stage_transition_cooperative_to_done_when_naturally_completes() {
        val next = PlanExecutor.ForceAbortStateMachine.next(
            PlanExecutor.ForceAbortStateMachine.Stage.Cooperative,
            naturallyCompleted = true,
        )
        assertEquals(PlanExecutor.ForceAbortStateMachine.Stage.Done, next)
    }

    @Test
    fun stage_transition_cooperative_to_force_release_when_stuck() {
        val next = PlanExecutor.ForceAbortStateMachine.next(
            PlanExecutor.ForceAbortStateMachine.Stage.Cooperative,
            naturallyCompleted = false,
        )
        assertEquals(PlanExecutor.ForceAbortStateMachine.Stage.ForceRelease, next)
    }

    @Test
    fun stage_transition_force_release_to_done_when_naturally_completes() {
        val next = PlanExecutor.ForceAbortStateMachine.next(
            PlanExecutor.ForceAbortStateMachine.Stage.ForceRelease,
            naturallyCompleted = true,
        )
        assertEquals(PlanExecutor.ForceAbortStateMachine.Stage.Done, next)
    }

    @Test
    fun stage_transition_force_release_to_force_complete_when_still_stuck() {
        val next = PlanExecutor.ForceAbortStateMachine.next(
            PlanExecutor.ForceAbortStateMachine.Stage.ForceRelease,
            naturallyCompleted = false,
        )
        assertEquals(PlanExecutor.ForceAbortStateMachine.Stage.ForceComplete, next)
    }

    @Test
    fun stage_force_complete_is_terminal() {
        val next = PlanExecutor.ForceAbortStateMachine.next(
            PlanExecutor.ForceAbortStateMachine.Stage.ForceComplete,
            naturallyCompleted = false,
        )
        // Once ForceComplete fires, the deferred is force-completed unconditionally;
        // the next state must be Done so callers stop escalating.
        assertEquals(PlanExecutor.ForceAbortStateMachine.Stage.Done, next)
    }

    @Test
    fun stage_done_is_absorbing() {
        val next = PlanExecutor.ForceAbortStateMachine.next(
            PlanExecutor.ForceAbortStateMachine.Stage.Done,
            naturallyCompleted = false,
        )
        assertEquals(PlanExecutor.ForceAbortStateMachine.Stage.Done, next)
    }

    @Test
    fun force_reason_string_is_descriptive() {
        // The synthetic reason in the partial report must say "force-killed" so the
        // agent can distinguish from a normal abort_when / pause_plan call. Locked
        // in here as part of the M5 contract.
        val reason = PlanExecutor.ForceAbortStateMachine.FORCE_REASON
        assertTrue(reason.contains("force-killed"), "reason should mention force-killed; got: $reason")
        assertTrue(reason.contains("cooperative cancel"), "reason should mention cooperative cancel")
    }

    @Test
    fun force_reason_is_non_empty() {
        // Defensive: the reason field surfaces in PlanReport.reason — empty would
        // confuse the agent looking for `reason: contains "force"`.
        assertNotEquals("", PlanExecutor.ForceAbortStateMachine.FORCE_REASON)
    }
}
