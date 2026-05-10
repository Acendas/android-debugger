package com.acendas.androiddebugger.events

import kotlinx.coroutines.channels.Channel
import org.mockito.Mockito
import com.sun.jdi.ThreadReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the ANR watchdog state machine. Per Story 7.1.3 / Task 7.1.3.3.
 *
 * The watchdog's [AnrWatchdog.inspect] method is a pure state-machine step we drive with
 * a synthetic clock — no real `delay()`, no live JDI VM. Mockito mirrors the JDI
 * `ThreadReference` so we can control `suspendCount()`.
 *
 * The acceptance criteria are:
 *   - main thread suspended < 2s → no warning
 *   - main thread suspended >= 2s, < 5s → emit `Warning(severity="warning")`
 *   - main thread suspended >= 5s → escalate to `Warning(severity="critical")`
 *   - dedup: a single suspended-run produces one warning + one critical, not a flood
 *   - reset: when suspendCount drops to 0, state resets so the next suspension can re-fire
 */
class AnrWatchdogTest {

    private fun watchdog(
        thread: ThreadReference,
        clock: () -> Long,
        warning: Long = 2_000L,
        critical: Long = 5_000L,
    ): AnrWatchdog {
        // Channel never drained in these unit tests — they call inspect() directly,
        // not the coroutine loop. The channel is required by ctor but unused.
        val ch = Channel<DebugEvent>(capacity = 16)
        return AnrWatchdog(
            mainThread = thread,
            channel = ch,
            clock = clock,
            pollIntervalMs = 100L,
            warningThresholdMs = warning,
            criticalThresholdMs = critical,
        )
    }

    private fun mockSuspended(count: Int): ThreadReference {
        val t = Mockito.mock(ThreadReference::class.java)
        Mockito.`when`(t.suspendCount()).thenReturn(count)
        return t
    }

    @Test
    fun running_thread_does_not_emit_a_warning() {
        val thread = mockSuspended(0)
        var now = 1000L
        val w = watchdog(thread, clock = { now })
        val r = w.inspect(prevSuspendStartMs = null, prevWarningEmitted = false, prevCriticalEmitted = false)
        assertNull(r.warning)
        assertNull(r.suspendStartMs) // not suspended → no run started
    }

    @Test
    fun records_suspend_start_on_first_observation() {
        val thread = mockSuspended(1)
        var now = 1000L
        val w = watchdog(thread, clock = { now })
        val r = w.inspect(prevSuspendStartMs = null, prevWarningEmitted = false, prevCriticalEmitted = false)
        assertEquals(1000L, r.suspendStartMs)
        assertNull(r.warning) // 0ms elapsed — below the 2s threshold
    }

    @Test
    fun emits_warning_when_suspended_for_two_seconds() {
        val thread = mockSuspended(1)
        var now = 1000L
        val w = watchdog(thread, clock = { now })
        // Tick 1: t=1000, start of suspension, no warning.
        var r = w.inspect(null, false, false)
        assertEquals(1000L, r.suspendStartMs)
        assertNull(r.warning)
        // Tick 2: t=3000, 2s elapsed, warning fires.
        now = 3000L
        r = w.inspect(r.suspendStartMs, r.warningEmitted, r.criticalEmitted)
        val warn = assertNotNull(r.warning)
        assertEquals("anr_risk", warn.warningType)
        assertEquals("warning", warn.severity)
        assertEquals(2000L, warn.extra["suspended_ms"])
    }

    @Test
    fun escalates_to_critical_at_five_seconds() {
        val thread = mockSuspended(1)
        var now = 1000L
        val w = watchdog(thread, clock = { now })
        // Skip the warning tick, jump straight to critical (the watchdog handles this case).
        now = 6000L
        val r = w.inspect(prevSuspendStartMs = 1000L, prevWarningEmitted = false, prevCriticalEmitted = false)
        val warn = assertNotNull(r.warning)
        assertEquals("anr_risk", warn.warningType)
        assertEquals("critical", warn.severity)
        assertEquals(5000L, warn.extra["suspended_ms"])
        assertTrue(r.criticalEmitted)
    }

    @Test
    fun does_not_re_emit_warning_within_same_suspension_run() {
        val thread = mockSuspended(1)
        var now = 1000L
        val w = watchdog(thread, clock = { now })
        // Reach the warning state.
        now = 3000L
        val r1 = w.inspect(prevSuspendStartMs = 1000L, prevWarningEmitted = false, prevCriticalEmitted = false)
        assertNotNull(r1.warning)
        assertTrue(r1.warningEmitted)
        // One poll later (still < 5s), warning shouldn't re-fire.
        now = 3500L
        val r2 = w.inspect(r1.suspendStartMs, r1.warningEmitted, r1.criticalEmitted)
        assertNull(r2.warning) // dedup
    }

    @Test
    fun emits_critical_only_once_within_same_suspension_run() {
        val thread = mockSuspended(1)
        var now = 6000L
        val w = watchdog(thread, clock = { now })
        val r1 = w.inspect(prevSuspendStartMs = 1000L, prevWarningEmitted = true, prevCriticalEmitted = false)
        assertEquals("critical", r1.warning?.severity)
        // Next tick: still suspended, well past critical, but already emitted. No re-fire.
        now = 7000L
        val r2 = w.inspect(r1.suspendStartMs, r1.warningEmitted, r1.criticalEmitted)
        assertNull(r2.warning)
    }

    @Test
    fun resets_state_when_thread_resumes() {
        val thread = mockSuspended(0) // not suspended
        var now = 6000L
        val w = watchdog(thread, clock = { now })
        // Even with prior suspendStart + emissions, a non-suspended thread resets.
        val r = w.inspect(prevSuspendStartMs = 1000L, prevWarningEmitted = true, prevCriticalEmitted = true)
        assertNull(r.suspendStartMs)
        assertEquals(false, r.warningEmitted)
        assertEquals(false, r.criticalEmitted)
        assertNull(r.warning)
    }

    @Test
    fun new_suspension_after_resume_can_fire_warning_again() {
        var suspendCount = 0
        val thread = Mockito.mock(ThreadReference::class.java)
        Mockito.`when`(thread.suspendCount()).thenAnswer { suspendCount }
        var now = 1000L
        val w = watchdog(thread, clock = { now })
        // Suspend → warning.
        suspendCount = 1
        now = 3000L
        val r1 = w.inspect(prevSuspendStartMs = 1000L, prevWarningEmitted = false, prevCriticalEmitted = false)
        assertNotNull(r1.warning)
        // Resume.
        suspendCount = 0
        now = 4000L
        val r2 = w.inspect(r1.suspendStartMs, r1.warningEmitted, r1.criticalEmitted)
        assertNull(r2.warning)
        assertNull(r2.suspendStartMs)
        // Re-suspend → warning re-fires after 2s.
        suspendCount = 1
        now = 5000L
        val r3 = w.inspect(r2.suspendStartMs, r2.warningEmitted, r2.criticalEmitted)
        assertEquals(5000L, r3.suspendStartMs)
        assertNull(r3.warning) // 0ms in new run
        now = 7000L
        val r4 = w.inspect(r3.suspendStartMs, r3.warningEmitted, r3.criticalEmitted)
        val w4 = assertNotNull(r4.warning)
        assertEquals("warning", w4.severity)
    }
}
