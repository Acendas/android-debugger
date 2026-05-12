package com.acendas.androiddebugger.inspection

import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.jvmti.AgentState
import com.acendas.androiddebugger.tools.FakeAgentClient
import com.acendas.androiddebugger.tools.MethodTraceTools
import com.acendas.androiddebugger.tools.V16TestHelpers
import com.sun.jdi.VirtualMachine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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
import kotlin.test.assertTrue

/**
 * v1.6 — `start_method_trace` validates filter shape + ART capability before
 * transmitting to the agent. Tests cover:
 *   - exactly-one-filter-kind rule (conflicting fields → InvalidTarget)
 *   - `can_generate_method_entry_events` gate
 *   - happy path returns buffer_id + adds to Session.methodTraceBufferIds
 */
class MethodTraceStartTest {

    private lateinit var server: io.modelcontextprotocol.kotlin.sdk.server.Server
    private lateinit var fakeClient: FakeAgentClient

    private fun wireSession(caps: kotlinx.serialization.json.JsonObject) {
        val vm = Mockito.mock(VirtualMachine::class.java)
        Session.vm = vm
        Session.capabilities = buildJsonObject { put("get_instance_info", true) }
        fakeClient = FakeAgentClient()
        Session.agentClient = fakeClient
        Session.agentState = AgentState(
            protocolVersion = 2,
            agentVersion = "1.6.0",
            capabilities = caps,
            attachPath = "/data/local/tmp/libamdb_agent.so",
            hostSocketPath = Path.of("/tmp/fake.sock"),
            abstractNamespace = "android-debugger-com.x",
            attachPid = 1234,
            attachedAt = Instant.now(),
        )
    }

    @BeforeTest
    fun setup() {
        server = V16TestHelpers.newServer()
        MethodTraceTools.register(server)
        Session.methodTraceBufferIds.clear()
    }

    @AfterTest
    fun teardown() {
        Session.vm = null
        Session.capabilities = null
        Session.agentClient = null
        Session.agentState = null
        Session.methodTraceBufferIds.clear()
    }

    @Test
    fun filter_kind_methods_with_class_pattern_set_returns_invalid_target() = runTest {
        wireSession(buildJsonObject {
            put("can_generate_method_entry_events", true)
            put("can_generate_method_exit_events", true)
        })
        val response = V16TestHelpers.invokeTool(server, "start_method_trace", buildJsonObject {
            put("filter_kind", "methods")
            // Conflict: filter_kind=methods but class_pattern also set.
            put("methods", buildJsonArray { add("Lcom/example/Foo;bar") })
            put("class_pattern", "com.example.*")
        })
        assertEquals(false, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("invalid_target", response["code"]?.jsonPrimitive?.contentOrNull)
        // No RPC should have been sent — validation refuses pre-transmission.
        assertEquals(0, fakeClient.callLog.size)
    }

    @Test
    fun capability_gate_method_entry_false_returns_capability_unavailable() = runTest {
        wireSession(buildJsonObject {
            put("can_generate_method_entry_events", false)
            put("can_generate_method_exit_events", true)
        })
        val response = V16TestHelpers.invokeTool(server, "start_method_trace", buildJsonObject {
            put("filter_kind", "methods")
            put("methods", buildJsonArray { add("Lcom/example/Foo;bar") })
        })
        assertEquals(false, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("capability_unavailable", response["code"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            (response["message"]?.jsonPrimitive?.contentOrNull ?: "").contains("can_generate_method_entry_events"),
            "expected message to mention capability; got ${response["message"]}",
        )
        assertEquals(0, fakeClient.callLog.size)
    }

    @Test
    fun happy_path_returns_buffer_id_and_registers_in_session() = runTest {
        wireSession(buildJsonObject {
            put("can_generate_method_entry_events", true)
            put("can_generate_method_exit_events", true)
        })
        fakeClient.stub("agent.method_trace_start", buildJsonObject {
            put("buffer_id", "mt-buf-1")
            put("started_at_ms", 1700000000000L)
            put("filter_kind", "methods")
            put("estimated_match_count", 1)
        })
        val response = V16TestHelpers.invokeTool(server, "start_method_trace", buildJsonObject {
            put("filter_kind", "methods")
            put("methods", buildJsonArray { add("Lcom/example/Foo;bar") })
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("mt-buf-1", response["buffer_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1700000000000L, response["started_at_ms"]?.jsonPrimitive?.longOrNull)
        assertEquals("methods", response["filter_kind"]?.jsonPrimitive?.contentOrNull)
        // Session-side: the buffer id must be tracked for detach drain.
        assertTrue(
            Session.methodTraceBufferIds.contains("mt-buf-1"),
            "Session.methodTraceBufferIds must contain mt-buf-1; got ${Session.methodTraceBufferIds}",
        )
        // Exactly one RPC.
        assertEquals(1, fakeClient.callLog.size)
        assertEquals("agent.method_trace_start", fakeClient.callLog[0].first)
    }
}
