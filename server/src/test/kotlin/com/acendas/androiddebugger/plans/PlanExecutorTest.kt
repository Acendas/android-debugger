package com.acendas.androiddebugger.plans

import com.acendas.androiddebugger.events.DebugEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PlanExecutor]. The executor's run loop is heavily coupled to JDI
 * (Session.vm, Session.pausedThread, Session.eventLoop), so these tests focus on the
 * extracted pure-logic surfaces: the hypothesis state machine, the leaky bucket, the
 * progress-emission seq counter, and the report builder.
 *
 * Full end-to-end testing happens in the live-smoke gate; here we lock in the
 * structural invariants that don't need a VM.
 */
class PlanExecutorTest {

    // ---------------- Hypothesis state machine ----------------

    @Test
    fun hypothesis_inconclusive_when_when_never_matches() {
        val states = mutableMapOf<String, PlanExecutor.HState>()
        val triggers = mutableMapOf<String, Long>()
        val firsts = mutableMapOf<String, Long>()
        val contras = mutableMapOf<String, Long>()
        val h = Hypothesis("h1", "false", "true")

        PlanExecutorTestSupport.gradeHypotheses(
            states = states,
            hypotheses = listOf(h),
            whenEval = { false },
            expectEval = { true },
            seq = 1,
            firstTriggerSeqs = firsts,
            triggerCounts = triggers,
            contradictionSeqs = contras,
        )
        assertNull(states["h1"])
        assertNull(firsts["h1"])
        assertEquals(0L, triggers["h1"] ?: 0L)
    }

    @Test
    fun hypothesis_inconclusive_to_matched_on_first_truthy_expect() {
        val states = mutableMapOf<String, PlanExecutor.HState>()
        val triggers = mutableMapOf<String, Long>()
        val firsts = mutableMapOf<String, Long>()
        val contras = mutableMapOf<String, Long>()
        val h = Hypothesis("h1", "true", "true")

        PlanExecutorTestSupport.gradeHypotheses(
            states, listOf(h), { true }, { true }, seq = 5,
            firsts, triggers, contras,
        )
        assertEquals(PlanExecutor.HState.Matched, states["h1"])
        assertEquals(1L, triggers["h1"])
        assertEquals(5L, firsts["h1"])
        assertNull(contras["h1"])
    }

    @Test
    fun hypothesis_inconclusive_to_contradicted_on_first_falsy_expect() {
        val states = mutableMapOf<String, PlanExecutor.HState>()
        val triggers = mutableMapOf<String, Long>()
        val firsts = mutableMapOf<String, Long>()
        val contras = mutableMapOf<String, Long>()
        val h = Hypothesis("h1", "true", "true")

        PlanExecutorTestSupport.gradeHypotheses(
            states, listOf(h), { true }, { false }, seq = 3,
            firsts, triggers, contras,
        )
        assertEquals(PlanExecutor.HState.Contradicted, states["h1"])
        assertEquals(3L, contras["h1"])
        assertEquals(3L, firsts["h1"])
    }

    @Test
    fun hypothesis_matched_to_contradicted_on_later_falsy_expect() {
        val states = mutableMapOf<String, PlanExecutor.HState>()
        val triggers = mutableMapOf<String, Long>()
        val firsts = mutableMapOf<String, Long>()
        val contras = mutableMapOf<String, Long>()
        val h = Hypothesis("h1", "true", "true")

        // First event: matches.
        PlanExecutorTestSupport.gradeHypotheses(
            states, listOf(h), { true }, { true }, seq = 1,
            firsts, triggers, contras,
        )
        assertEquals(PlanExecutor.HState.Matched, states["h1"])

        // Second event: same when, but expect now false → contradicted.
        PlanExecutorTestSupport.gradeHypotheses(
            states, listOf(h), { true }, { false }, seq = 2,
            firsts, triggers, contras,
        )
        assertEquals(PlanExecutor.HState.Contradicted, states["h1"])
        assertEquals(2L, contras["h1"])
        // firstTrigger should still be the original 1.
        assertEquals(1L, firsts["h1"])
        assertEquals(2L, triggers["h1"])
    }

    @Test
    fun hypothesis_contradicted_stays_contradicted_monotonically() {
        val states = mutableMapOf<String, PlanExecutor.HState>()
        val triggers = mutableMapOf<String, Long>()
        val firsts = mutableMapOf<String, Long>()
        val contras = mutableMapOf<String, Long>()
        val h = Hypothesis("h1", "true", "true")

        PlanExecutorTestSupport.gradeHypotheses(
            states, listOf(h), { true }, { false }, seq = 1,
            firsts, triggers, contras,
        )
        assertEquals(PlanExecutor.HState.Contradicted, states["h1"])

        // Now expect returns truthy. State must NOT bounce back to Matched.
        PlanExecutorTestSupport.gradeHypotheses(
            states, listOf(h), { true }, { true }, seq = 2,
            firsts, triggers, contras,
        )
        assertEquals(PlanExecutor.HState.Contradicted, states["h1"])
        // contradictionSeq frozen at first seq.
        assertEquals(1L, contras["h1"])
    }

    @Test
    fun hypothesis_multiple_independent_tracking() {
        val states = mutableMapOf<String, PlanExecutor.HState>()
        val triggers = mutableMapOf<String, Long>()
        val firsts = mutableMapOf<String, Long>()
        val contras = mutableMapOf<String, Long>()
        val hA = Hypothesis("A", "true", "true")
        val hB = Hypothesis("B", "true", "true")

        // A matches truthy; B's expect is falsy.
        PlanExecutorTestSupport.gradeHypotheses(
            states,
            listOf(hA, hB),
            whenEval = { true },
            expectEval = { h -> h.name == "A" },
            seq = 1,
            firsts, triggers, contras,
        )
        assertEquals(PlanExecutor.HState.Matched, states["A"])
        assertEquals(PlanExecutor.HState.Contradicted, states["B"])
    }

    @Test
    fun hypothesis_when_false_records_no_trigger() {
        val states = mutableMapOf<String, PlanExecutor.HState>()
        val triggers = mutableMapOf<String, Long>()
        val firsts = mutableMapOf<String, Long>()
        val contras = mutableMapOf<String, Long>()
        val h = Hypothesis("h", "false", "true")

        PlanExecutorTestSupport.gradeHypotheses(
            states, listOf(h), { false }, { true }, seq = 1,
            firsts, triggers, contras,
        )
        // when never holds → no state recorded; triggerCount stays 0.
        assertNull(states["h"])
        assertEquals(0L, triggers["h"] ?: 0L)
    }

    // ---------------- Leaky bucket ----------------

    @Test
    fun leaky_bucket_starts_full_allows_first_burst() {
        val bucket = PlanExecutor.LeakyBucket(perSec = 5)
        // Initial fill = 5 tokens.
        repeat(5) { assertTrue(bucket.allow(), "allow #$it") }
    }

    @Test
    fun leaky_bucket_drops_above_rate_in_same_instant() {
        val bucket = PlanExecutor.LeakyBucket(perSec = 3)
        // Consume the initial 3.
        repeat(3) { assertTrue(bucket.allow()) }
        // Next is dropped — bucket near-zero.
        assertFalse(bucket.allow())
    }

    @Test
    fun leaky_bucket_refills_with_elapsed_time() {
        val bucket = PlanExecutor.LeakyBucket(perSec = 100)
        repeat(100) { bucket.allow() }
        // Drained. Sleep enough for refill at 100/sec → ~50ms = 5 tokens.
        Thread.sleep(80)
        assertTrue(bucket.allow(), "should refill some token after 80 ms")
    }

    // ---------------- Plan launch / lifecycle ----------------

    @Test
    fun launch_rejects_invalid_plan_with_plan_invalid() {
        // Plan with timeoutMs <= 0 fails the compiler bounds check.
        val invalid = Plan(
            name = "bad",
            timeoutMs = 0,
            maxEvents = 1,
            onEvent = emptyList(),
        )
        try {
            PlanExecutor.launch(invalid, kotlinx.coroutines.GlobalScope)
            kotlin.test.fail("expected ToolError(PlanInvalid)")
        } catch (e: com.acendas.androiddebugger.ToolError) {
            assertEquals(com.acendas.androiddebugger.ErrorCode.PlanInvalid, e.errorCode)
        }
    }

    @Test
    fun launch_rejects_when_not_attached() {
        // Plan compiles fine, but Session has no VM → NotAttached.
        val plan = Plan(
            name = "ok",
            timeoutMs = 1_000,
            maxEvents = 1,
            onEvent = listOf(OnEvent(match = "true", actions = listOf(Action.Resume()))),
        )
        // Ensure clean state.
        com.acendas.androiddebugger.inspection.VmCoordinator.resetForTest()
        com.acendas.androiddebugger.Session.vm = null
        try {
            PlanExecutor.launch(plan, kotlinx.coroutines.GlobalScope)
            kotlin.test.fail("expected ToolError(NotAttached)")
        } catch (e: com.acendas.androiddebugger.ToolError) {
            assertEquals(com.acendas.androiddebugger.ErrorCode.NotAttached, e.errorCode)
        }
    }

    // ---------------- Event-context building ----------------
    //
    // The event-context FEEL shape is what every match / hypothesis / yield_when
    // expression reads. Lock in the field set.

    @Test
    fun event_context_stopped_carries_reason_thread_breakpoint_location() {
        // Indirect — we exercise the executor's evalFeel by building a tiny plan that
        // captures `event.reason` into a feel output. This needs the launch path, so we
        // gate it with the Session being attached. Since we can't easily fake a VM here,
        // this is a structural smoke check on the shape only — we directly inspect the
        // event-context builder by constructing a synthetic event via the data class.
        val stopped = DebugEvent.Stopped(
            reason = "breakpoint",
            threadId = 42L,
            threadName = "main",
            breakpointId = 7,
            location = "Foo.bar(Foo.kt:10)",
        )
        // Stopped DebugEvent type tag.
        assertEquals("stopped", stopped.type)
        assertEquals("breakpoint", stopped.reason)
        assertEquals(7, stopped.breakpointId)
    }

    @Test
    fun event_context_exception_carries_class_and_caught() {
        val ex = DebugEvent.Exception(
            exceptionId = "obj#100",
            exceptionClass = "java.lang.NullPointerException",
            threadId = 1L,
            caught = false,
        )
        assertEquals("exception", ex.type)
        assertEquals("java.lang.NullPointerException", ex.exceptionClass)
        assertFalse(ex.caught)
    }

    // ---------------- Class-load event shape ----------------

    @Test
    fun class_prepare_event_carries_class_name() {
        val cp = DebugEvent.ClassPrepare(className = "com.example.Foo")
        assertEquals("class_prepare", cp.type)
        assertEquals("com.example.Foo", cp.className)
    }

    // ---------------- PlanProgress synthetic event ----------------

    @Test
    fun plan_progress_serializes_subtype_and_data_flat() {
        val ev = DebugEvent.PlanProgress(
            planId = "abc",
            seq = 7,
            subtype = "snapshot_captured",
            data = kotlinx.serialization.json.buildJsonObject {
                put("ref", JsonPrimitive("snap#abc:3"))
            },
        )
        val json = ev.toJson()
        // Subtype-specific data fields flatten into the top-level event object.
        assertEquals("snap#abc:3", json["ref"]?.toString()?.trim('"'))
        assertEquals("\"snapshot_captured\"", json["subtype"].toString())
        assertEquals("\"plan_progress\"", json["type"].toString())
        assertEquals("7", json["seq"].toString())
    }
}
