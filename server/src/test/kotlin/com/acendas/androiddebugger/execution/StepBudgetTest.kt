package com.acendas.androiddebugger.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per Story 7.1.4: feed synthetic step events and assert the budget cap fires.
 *
 * The budget allows up to [StepBudget.limit] same-method consecutive steps. The 51st
 * step in the same method should be refused.
 */
class StepBudgetTest {

    @Test
    fun fifty_consecutive_same_method_steps_are_allowed() {
        val sb = StepBudget()
        val tid = 1L
        repeat(sb.limit) {
            assertTrue(sb.tryStep(tid, "Foo#bar"), "step ${it + 1} should be allowed")
        }
        assertEquals(sb.limit, sb.consecutiveSteps(tid))
    }

    @Test
    fun fifty_first_consecutive_step_is_refused() {
        val sb = StepBudget()
        val tid = 1L
        repeat(sb.limit) { sb.tryStep(tid, "Foo#bar") }
        // The 51st should be refused.
        assertFalse(sb.tryStep(tid, "Foo#bar"))
        assertTrue(sb.isExhausted(tid))
    }

    @Test
    fun changing_method_resets_consecutive_counter() {
        val sb = StepBudget()
        val tid = 1L
        repeat(sb.limit) { sb.tryStep(tid, "Foo#bar") }
        // Switch to a different method — counter resets to 1, allowed.
        assertTrue(sb.tryStep(tid, "Foo#baz"))
        assertEquals(1, sb.consecutiveSteps(tid))
        assertFalse(sb.isExhausted(tid))
    }

    @Test
    fun reset_clears_state_for_thread() {
        val sb = StepBudget()
        val tid = 1L
        repeat(sb.limit + 1) { sb.tryStep(tid, "Foo#bar") }
        assertTrue(sb.isExhausted(tid))
        sb.reset(tid)
        assertFalse(sb.isExhausted(tid))
        assertEquals(0, sb.consecutiveSteps(tid))
        // Fresh again: 50 more allowed.
        repeat(sb.limit) {
            assertTrue(sb.tryStep(tid, "Foo#bar"))
        }
    }

    @Test
    fun threads_have_independent_budgets() {
        val sb = StepBudget()
        val a = 1L
        val b = 2L
        repeat(sb.limit) { sb.tryStep(a, "X#y") }
        // Thread A is at the cap. Thread B starts fresh.
        assertFalse(sb.tryStep(a, "X#y"))
        assertTrue(sb.tryStep(b, "X#y"))
        assertFalse(sb.isExhausted(b))
    }

    @Test
    fun clear_resets_all_threads() {
        val sb = StepBudget()
        repeat(sb.limit + 1) {
            sb.tryStep(1L, "X#y")
            sb.tryStep(2L, "X#y")
        }
        sb.clear()
        assertFalse(sb.isExhausted(1L))
        assertFalse(sb.isExhausted(2L))
    }

    @Test
    fun exhausted_state_is_sticky_until_reset() {
        // Per Story 7.1.4: budget exhausts deterministically — re-trying the same step
        // doesn't quietly re-allow because we never auto-reset.
        val sb = StepBudget()
        val tid = 1L
        repeat(sb.limit) { sb.tryStep(tid, "X#y") }
        assertFalse(sb.tryStep(tid, "X#y"))
        assertFalse(sb.tryStep(tid, "X#y"))
        assertFalse(sb.tryStep(tid, "X#y"))
        // Method change resets via the natural same-method tracking.
        assertTrue(sb.tryStep(tid, "X#z"))
    }
}
