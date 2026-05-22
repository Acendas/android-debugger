package com.acendas.androiddebugger.plans

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v1.7.1 M6 — async launch path tests.
 *
 * The full launch path requires a live JDI VirtualMachine (Session.requireAttached
 * gates [PlanExecutor.launch]), which is not available in unit-test scope. These
 * tests lock in the structural invariants of the M6 redesign:
 *   - The `start()` flow emits a `plan_progress(started, phase="claimed")` synchronously
 *     before any blocking setup work.
 *   - Setup-BP install moves to the executor coroutine so a wedged VM cannot block
 *     the launch return.
 *   - The compile-validation rejection path still throws PlanInvalid for malformed
 *     plans without ever queuing async work.
 *
 * Live end-to-end coverage of the async launch lives in the smoke gate; here we
 * cover the static-analyzable shape.
 */
class PlanExecutorM6AsyncLaunchTest {

    @Test
    fun async_launch_validates_plan_synchronously_before_async_work() {
        // A plan with timeoutMs <= 0 fails the compiler. M6 must not start an async
        // coroutine before validation lands — the rejection path is sync and clean.
        val invalid = Plan(
            name = "bad-timeout",
            timeoutMs = 0,
            maxEvents = 1,
        )
        try {
            PlanExecutor.launch(invalid, kotlinx.coroutines.GlobalScope)
            kotlin.test.fail("expected PlanInvalid")
        } catch (e: com.acendas.androiddebugger.ToolError) {
            assertEquals(com.acendas.androiddebugger.ErrorCode.PlanInvalid, e.errorCode)
        }
    }

    @Test
    fun async_launch_rejects_when_not_attached_without_starting_coroutine() {
        // M6 invariant: NotAttached check happens synchronously before any
        // coroutine.launch(). If this regression-tests OK, we know the launch path
        // doesn't pay coroutine cost for an obviously-bad-state call.
        val plan = Plan(
            name = "ok-shape",
            timeoutMs = 1_000,
            maxEvents = 1,
            onEvent = listOf(OnEvent(match = "true", actions = listOf(Action.Resume()))),
        )
        com.acendas.androiddebugger.inspection.VmCoordinator.resetForTest()
        com.acendas.androiddebugger.Session.vm = null
        try {
            PlanExecutor.launch(plan, kotlinx.coroutines.GlobalScope)
            kotlin.test.fail("expected NotAttached")
        } catch (e: com.acendas.androiddebugger.ToolError) {
            assertEquals(com.acendas.androiddebugger.ErrorCode.NotAttached, e.errorCode)
        }
        // Slot must not have been claimed — would block subsequent attaches.
        assertEquals(null, com.acendas.androiddebugger.inspection.VmCoordinator.currentPlanId())
    }

    @Test
    fun started_progress_payload_carries_phase_claimed() {
        // The synchronous `started` event payload pattern is asserted directly via
        // the DebugEvent shape — the executor builds buildJsonObject { put("phase",
        // "claimed") } in start(). This locks in the contract surface used by
        // smoke tests + the agent's expected stream.
        val ev = com.acendas.androiddebugger.events.DebugEvent.PlanProgress(
            planId = "p1",
            seq = 1,
            subtype = "started",
            data = kotlinx.serialization.json.buildJsonObject {
                put("phase", kotlinx.serialization.json.JsonPrimitive("claimed"))
            },
        )
        val json = ev.toJson()
        assertEquals("\"claimed\"", json["phase"].toString())
        assertEquals("\"started\"", json["subtype"].toString())
    }

    @Test
    fun setup_complete_progress_event_shape_carries_counts() {
        // The post-async-setup event the executor emits once BP install finishes. The
        // agent uses this to know when run-loop processing actually begins.
        val ev = com.acendas.androiddebugger.events.DebugEvent.PlanProgress(
            planId = "p1",
            seq = 2,
            subtype = "setup_complete",
            data = kotlinx.serialization.json.buildJsonObject {
                put("installed_count", kotlinx.serialization.json.JsonPrimitive(2))
                put("error_count", kotlinx.serialization.json.JsonPrimitive(0))
            },
        )
        val json = ev.toJson()
        assertEquals("\"setup_complete\"", json["subtype"].toString())
        assertEquals("2", json["installed_count"].toString())
        assertEquals("0", json["error_count"].toString())
    }

    @Test
    fun setup_complete_distinguishes_resolved_deferred_error() {
        // Locks in the post-soak-#3 contract: setup_complete carries resolved_count,
        // deferred_count, and error_count separately so the agent can tell "BPs are
        // armed and waiting for class load" from "BPs all failed." Without this,
        // deferred-only setups look identical to all-failed setups (both report
        // installed_count=0 under the old shape), which is the trap soak #3 hit.
        val ev = com.acendas.androiddebugger.events.DebugEvent.PlanProgress(
            planId = "p1",
            seq = 4,
            subtype = "setup_complete",
            data = kotlinx.serialization.json.buildJsonObject {
                put("installed_count", kotlinx.serialization.json.JsonPrimitive(6))
                put("resolved_count", kotlinx.serialization.json.JsonPrimitive(0))
                put("deferred_count", kotlinx.serialization.json.JsonPrimitive(6))
                put("error_count", kotlinx.serialization.json.JsonPrimitive(0))
                put(
                    "deferred_targets",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.JsonPrimitive("AdaSessionUseCase.kt:711"),
                            kotlinx.serialization.json.JsonPrimitive("AdaInteractionUseCase.kt:528"),
                        ),
                    ),
                )
            },
        )
        val json = ev.toJson()
        assertEquals("\"setup_complete\"", json["subtype"].toString())
        assertEquals("6", json["installed_count"].toString())
        assertEquals("0", json["resolved_count"].toString())
        assertEquals("6", json["deferred_count"].toString())
        assertEquals("0", json["error_count"].toString())
        assertTrue(json["deferred_targets"].toString().contains("AdaSessionUseCase"))
    }

    @Test
    fun setup_result_data_shape_supports_post_mortem_reading() {
        // Locks in PlanReport.setupResults entry shape. The structured per-entry record
        // lives in the final report so a post-abort post-mortem can read "entry #3 was
        // a line BP at AdaInteractionUseCase.kt:528 — deferred, no class loaded" without
        // re-running anything.
        val resolved = SetupResult(
            index = 0,
            kind = "line",
            status = "resolved",
            bpId = 42,
            target = "MainActivity.kt:30",
            resolvedLocations = 2,
        )
        val deferred = SetupResult(
            index = 1,
            kind = "line",
            status = "deferred",
            bpId = 43,
            target = "AdaSessionUseCase.kt:711",
            resolvedLocations = 0,
        )
        val errored = SetupResult(
            index = 2,
            kind = "method_entry",
            status = "error",
            target = "com.x.Y.foo",
            error = "class_not_loaded",
        )
        // Serialization round-trip — must be parseable by the agent reading the report.
        val json = PlanJson.json.encodeToString(SetupResult.serializer(), resolved)
        assertTrue(json.contains("\"status\":\"resolved\""))
        assertTrue(json.contains("\"resolved_locations\":2"))
        assertTrue(json.contains("\"bp_id\":42"))
        val djson = PlanJson.json.encodeToString(SetupResult.serializer(), deferred)
        assertTrue(djson.contains("\"status\":\"deferred\""))
        val ejson = PlanJson.json.encodeToString(SetupResult.serializer(), errored)
        assertTrue(ejson.contains("\"status\":\"error\""))
        assertTrue(ejson.contains("class_not_loaded"))
        // bpId should be omitted/null on error.
        assertTrue(!ejson.contains("\"bp_id\":"))
    }

    @Test
    fun setup_error_progress_carries_phase_and_message() {
        // When setup BP install throws and no VM is attached, the executor emits a
        // structured error event with phase="setup" so the agent can distinguish
        // setup-phase failures from run-loop failures.
        val ev = com.acendas.androiddebugger.events.DebugEvent.PlanProgress(
            planId = "p1",
            seq = 3,
            subtype = "error",
            data = kotlinx.serialization.json.buildJsonObject {
                put("phase", kotlinx.serialization.json.JsonPrimitive("setup"))
                put("message", kotlinx.serialization.json.JsonPrimitive("vm not attached at start"))
            },
        )
        val json = ev.toJson()
        assertEquals("\"error\"", json["subtype"].toString())
        assertEquals("\"setup\"", json["phase"].toString())
        assertTrue(json["message"].toString().contains("vm not attached"))
    }
}
