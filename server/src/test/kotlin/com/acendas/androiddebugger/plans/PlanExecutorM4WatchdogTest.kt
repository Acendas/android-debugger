package com.acendas.androiddebugger.plans

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v1.7.1 M4 — stuck-watchdog tests.
 *
 * The live watchdog runs as a sibling coroutine to the executor's run loop and
 * requires Session/VM wiring. These tests cover the pure decision functions in
 * [PlanExecutor.Watchdog] — poll-interval scaling, signal gating, and reason
 * string selection — which together fully describe the watchdog's behavior given
 * any clock + last-event-time.
 *
 * The integration assertion ("the plan does NOT auto-abort") is proven by the
 * static shape of [PlanExecutor.runWatchdog]: it never calls terminate() or any
 * other state-changing primitive; only [emitProgress]. That guarantee is preserved
 * by the code review, not a runtime test.
 */
class PlanExecutorM4WatchdogTest {

    @Test
    fun poll_interval_scales_down_for_small_threshold() {
        // stuck_detect_ms=500 → poll every ~125ms so the test gets ≥3 ticks during
        // its window. Floor is 50ms to avoid CPU thrash.
        val poll = PlanExecutor.Watchdog.pollIntervalForThreshold(500)
        assertTrue(poll in 50L..500L, "poll interval out of bounds: $poll")
        assertTrue(poll <= 200L, "poll interval should be tight for small threshold; got $poll")
    }

    @Test
    fun poll_interval_caps_at_500_for_production_threshold() {
        // stuck_detect_ms=90_000 (default) → poll every 500ms cap so we don't waste
        // CPU. A 90s threshold polled at 500ms still gives 180 chances to catch.
        val poll = PlanExecutor.Watchdog.pollIntervalForThreshold(90_000L)
        assertEquals(500L, poll)
    }

    @Test
    fun poll_interval_floor_at_50ms_for_tiny_threshold() {
        // Defensive: a misconfigured stuck_detect_ms=100 would otherwise produce a
        // 25ms poll → CPU thrash. Floor at 50ms.
        val poll = PlanExecutor.Watchdog.pollIntervalForThreshold(100L)
        assertEquals(50L, poll)
    }

    @Test
    fun should_signal_false_when_event_age_below_threshold() {
        // Event 200ms ago, threshold 500ms → no signal yet.
        val signal = PlanExecutor.Watchdog.shouldSignal(
            now = 10_000,
            lastEventMs = 9_800,
            thresholdMs = 500,
            lastSignaledAt = 0,
        )
        assertFalse(signal)
    }

    @Test
    fun should_signal_true_when_event_age_exceeds_threshold_and_no_recent_signal() {
        // Event 600ms ago, threshold 500ms, never signaled → fire.
        val signal = PlanExecutor.Watchdog.shouldSignal(
            now = 10_000,
            lastEventMs = 9_400,
            thresholdMs = 500,
            lastSignaledAt = 0,
        )
        assertTrue(signal)
    }

    @Test
    fun should_signal_false_when_recently_signaled() {
        // Event old but we just signaled — don't spam.
        val signal = PlanExecutor.Watchdog.shouldSignal(
            now = 10_000,
            lastEventMs = 8_000,
            thresholdMs = 500,
            lastSignaledAt = 9_700, // 300ms ago, < 500ms threshold for re-signal
        )
        assertFalse(signal)
    }

    @Test
    fun should_signal_true_again_after_threshold_window_passes() {
        // After threshold worth of silence post last signal, re-fire so the agent
        // gets a steady heartbeat that nothing's progressing.
        val signal = PlanExecutor.Watchdog.shouldSignal(
            now = 10_000,
            lastEventMs = 8_000,
            thresholdMs = 500,
            lastSignaledAt = 9_400, // 600ms ago, >= 500ms threshold
        )
        assertTrue(signal)
    }

    @Test
    fun reason_says_no_events_received_when_handled_is_zero() {
        // Distinct from "no new events" so the agent can tell setup-never-fired
        // (events_handled=0) from "plan went silent" (events_handled>0).
        val reason = PlanExecutor.Watchdog.reasonForState(eventsHandled = 0L)
        assertEquals("no events received since plan start", reason)
    }

    @Test
    fun reason_says_no_new_events_when_handled_is_positive() {
        val reason = PlanExecutor.Watchdog.reasonForState(eventsHandled = 5L)
        assertEquals("no new events", reason)
    }

    // ---------------- Plan model field test ----------------

    @Test
    fun plan_carries_stuck_detect_ms_optional() {
        // The Plan data class needs the optional stuck_detect_ms field for the
        // executor to read. Default null = use Plan.DEFAULT_STUCK_DETECT_MS.
        val def = Plan(name = "t", timeoutMs = 1000, maxEvents = 1)
        assertEquals(null, def.stuckDetectMs)

        val custom = Plan(name = "t", timeoutMs = 1000, maxEvents = 1, stuckDetectMs = 500)
        assertEquals(500L, custom.stuckDetectMs)
    }

    @Test
    fun default_stuck_detect_ms_is_90_seconds() {
        // Sanity check on the production default — 90s is generous for slow Android
        // attach + first BP hit, but tight enough to surface a misconfigured pattern
        // before the agent loses attention. Locked here so a future change to the
        // constant has to update this test deliberately.
        assertEquals(90_000L, Plan.DEFAULT_STUCK_DETECT_MS)
    }

    @Test
    fun stuck_progress_event_carries_required_fields() {
        // The watchdog emits a synthetic plan_progress with last_event_age_ms,
        // events_handled, and reason. Lock in the shape the agent reads.
        val ev = com.acendas.androiddebugger.events.DebugEvent.PlanProgress(
            planId = "p1",
            seq = 4,
            subtype = "stuck",
            data = kotlinx.serialization.json.buildJsonObject {
                put("last_event_age_ms", kotlinx.serialization.json.JsonPrimitive(750L))
                put("events_handled", kotlinx.serialization.json.JsonPrimitive(0L))
                put("reason", kotlinx.serialization.json.JsonPrimitive("no events received since plan start"))
            },
        )
        val json = ev.toJson()
        assertEquals("\"stuck\"", json["subtype"].toString())
        assertEquals("750", json["last_event_age_ms"].toString())
        assertEquals("0", json["events_handled"].toString())
        assertTrue(json["reason"].toString().contains("no events"))
    }
}
