package com.acendas.androiddebugger.inspection

import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.jvmti.AgentState
import com.acendas.androiddebugger.tools.FakeAgentClient
import com.acendas.androiddebugger.tools.V16TestHelpers
import com.acendas.androiddebugger.tools.WatchTools
import com.sun.jdi.VirtualMachine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.mockito.Mockito
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v1.6 spec §6 — `count_instances` auto-routes to JVMTI when the agent is
 * loaded AND `can_tag_objects=true`. This test exercises the three branches:
 *
 *   1. agent == null → JDI path (we set `Session.capabilities` so the JDI
 *      capability gate passes; the underlying VM stub returns no instances).
 *   2. agent != null AND can_tag_objects=true → wrapper called, `backend:"jvmti"`.
 *   3. agent != null but can_tag_objects=false → JDI fallback, `backend:"jdi"`.
 *
 * Each test verifies the wrapper IS or IS NOT invoked via the [FakeAgentClient]
 * call log — that's the load-bearing assertion.
 */
class HeapToolBackendDispatchTest {

    private lateinit var server: io.modelcontextprotocol.kotlin.sdk.server.Server

    @BeforeTest
    fun setupSession() {
        // Build a fresh server with WatchTools registered.
        server = V16TestHelpers.newServer()
        WatchTools.register(server)
        // Tools require an attached session. Mock the VM and wire it onto Session.
        val vm = Mockito.mock(VirtualMachine::class.java)
        Mockito.`when`(vm.classesByName(Mockito.anyString())).thenReturn(emptyList())
        Session.vm = vm
        Session.capabilities = buildJsonObject {
            // JDI side reports get_instance_info available so the JDI fallback's
            // capability gate (`Capability.requireCapability(GET_INSTANCE_INFO)`)
            // doesn't short-circuit before the test sees the routing decision.
            put("get_instance_info", true)
        }
    }

    @AfterTest
    fun resetSession() {
        Session.vm = null
        Session.capabilities = null
        Session.agentClient = null
        Session.agentState = null
    }

    @Test
    fun agent_null_falls_through_to_jdi_and_does_not_call_wrapper() = runTest {
        // No agent client — routing must go to JDI.
        Session.agentClient = null
        Session.agentState = null

        val response = V16TestHelpers.invokeTool(server, "count_instances", buildJsonObject {
            put("class", "android.graphics.Bitmap")
        })
        // The JDI path with no loaded classes returns InvalidTarget. We expect ok=false
        // here, but the load-bearing assertion is the *routing*: no wrapper invocation
        // could have happened because there's no agent — which the response surfaces by
        // *not* including `backend:"jvmti"`.
        val ok = response["ok"]?.jsonPrimitive?.booleanOrNull
        assertNotNull(ok, "response must have ok flag")
        // Either ok=true (with backend:jdi) or ok=false from InvalidTarget — but
        // either way, backend must be absent or "jdi".
        if (ok) {
            assertEquals("jdi", response["backend"]?.jsonPrimitive?.contentOrNull,
                "without an agent the only backend that can appear is `jdi`")
        }
    }

    @Test
    fun agent_with_can_tag_objects_true_routes_to_jvmti_wrapper() = runTest {
        val fake = FakeAgentClient()
        fake.stub("agent.heap_count_instances", buildJsonObject {
            put("class_signature", "Landroid/graphics/Bitmap;")
            put("count", 42L)
            put("sample_size_bytes", 1024L)
        })
        Session.agentClient = fake
        Session.agentState = AgentState(
            protocolVersion = 2,
            agentVersion = "1.6.0",
            capabilities = buildJsonObject { put("can_tag_objects", true) },
            attachPath = "/data/local/tmp/libamdb_agent.so",
            hostSocketPath = Path.of("/tmp/fake.sock"),
            abstractNamespace = "android-debugger-com.x",
            attachPid = 1234,
            attachedAt = Instant.now(),
        )

        val response = V16TestHelpers.invokeTool(server, "count_instances", buildJsonObject {
            put("class", "android.graphics.Bitmap")
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("jvmti", response["backend"]?.jsonPrimitive?.contentOrNull)
        assertEquals(42L, response["count"]?.jsonPrimitive?.longOrNull)
        assertEquals(1024L, response["sample_size_bytes"]?.jsonPrimitive?.longOrNull)
        // Wrapper *was* called — exactly once, with the JVM signature form of the FQN.
        assertEquals(1, fake.callLog.size)
        assertEquals("agent.heap_count_instances", fake.callLog[0].first)
        val params = fake.callLog[0].second
        assertNotNull(params)
        assertEquals(
            "Landroid/graphics/Bitmap;",
            params["class_signature"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun agent_loaded_but_can_tag_objects_false_falls_back_to_jdi() = runTest {
        val fake = FakeAgentClient()
        // Wire a poison responder — if the dispatch routes wrongly we see it fire.
        fake.stub("agent.heap_count_instances", buildJsonObject {
            put("class_signature", "should never be returned")
            put("count", 999L)
            put("sample_size_bytes", 0L)
        })
        Session.agentClient = fake
        Session.agentState = AgentState(
            protocolVersion = 2,
            agentVersion = "1.6.0",
            capabilities = buildJsonObject { put("can_tag_objects", false) },
            attachPath = "/data/local/tmp/libamdb_agent.so",
            hostSocketPath = Path.of("/tmp/fake.sock"),
            abstractNamespace = "android-debugger-com.x",
            attachPid = 1234,
            attachedAt = Instant.now(),
        )

        val response = V16TestHelpers.invokeTool(server, "count_instances", buildJsonObject {
            put("class", "android.graphics.Bitmap")
        })
        // The JDI fallback path triggers `classesByName` (empty list from the mock)
        // and emits InvalidTarget. Either way, the routing decision is what we test:
        // the wrapper should NOT have been called.
        assertEquals(0, fake.callLog.size,
            "agent.heap_count_instances must NOT be called when can_tag_objects=false; got: ${fake.callLog}")
        // And no `backend:"jvmti"` echo.
        val backend = response["backend"]?.jsonPrimitive?.contentOrNull
        assertTrue(backend == null || backend == "jdi",
            "backend should be absent or 'jdi'; got: $backend")
    }
}
