package com.acendas.androiddebugger.inspection

import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.jvmti.AgentState
import com.acendas.androiddebugger.tools.AllocTraceTools
import com.acendas.androiddebugger.tools.FakeAgentClient
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
 * v1.6 — `start_alloc_trace` requires a non-empty `class_signatures` list
 * (otherwise `InvalidTarget`), validates JVM internal signature form, and
 * surfaces `no_classes_loaded` from the agent when nothing matched.
 */
class AllocTraceClassAllowlistTest {

    private lateinit var server: io.modelcontextprotocol.kotlin.sdk.server.Server
    private lateinit var fakeClient: FakeAgentClient

    @BeforeTest
    fun setup() {
        server = V16TestHelpers.newServer()
        AllocTraceTools.register(server)
        val vm = Mockito.mock(VirtualMachine::class.java)
        Session.vm = vm
        Session.capabilities = buildJsonObject { put("get_instance_info", true) }
        fakeClient = FakeAgentClient()
        Session.agentClient = fakeClient
        Session.agentState = AgentState(
            protocolVersion = 2,
            agentVersion = "1.6.0",
            capabilities = buildJsonObject {
                put("can_generate_vm_object_alloc_events", true)
                put("can_tag_objects", true)
            },
            attachPath = "/data/local/tmp/libamdb_agent.so",
            hostSocketPath = Path.of("/tmp/fake.sock"),
            abstractNamespace = "android-debugger-com.x",
            attachPid = 1234,
            attachedAt = Instant.now(),
        )
        Session.allocTraceBufferIds.clear()
    }

    @AfterTest
    fun teardown() {
        Session.vm = null
        Session.capabilities = null
        Session.agentClient = null
        Session.agentState = null
        Session.allocTraceBufferIds.clear()
    }

    @Test
    fun empty_class_signatures_returns_invalid_target() = runTest {
        val response = V16TestHelpers.invokeTool(server, "start_alloc_trace", buildJsonObject {
            put("class_signatures", buildJsonArray { })
        })
        assertEquals(false, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("invalid_target", response["code"]?.jsonPrimitive?.contentOrNull)
        // No RPC should fire — validation refuses before transmission.
        assertEquals(0, fakeClient.callLog.size)
    }

    @Test
    fun no_classes_loaded_from_agent_surfaces_with_hint() = runTest {
        fakeClient.stubError(
            "agent.alloc_trace_start",
            rpcMessage = "no_classes_loaded",
        )
        val response = V16TestHelpers.invokeTool(server, "start_alloc_trace", buildJsonObject {
            put("class_signatures", buildJsonArray { add("Lcom/example/Foo;") })
        })
        assertEquals(false, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("invalid_target", response["code"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            (response["hint"]?.jsonPrimitive?.contentOrNull ?: "").contains("Trigger the code path"),
            "expected hint to mention triggering code path; got ${response["hint"]}",
        )
    }

    @Test
    fun happy_path_returns_buffer_id_and_resolved_count() = runTest {
        fakeClient.stub("agent.alloc_trace_start", buildJsonObject {
            put("buffer_id", "alloc-buf-1")
            put("started_at_ms", 1700000000000L)
            put("resolved_classes", 2)
            put("unresolved_classes", buildJsonArray { add("Lcom/example/NotYetLoaded;") })
        })
        val response = V16TestHelpers.invokeTool(server, "start_alloc_trace", buildJsonObject {
            put("class_signatures", buildJsonArray {
                add("Lcom/example/Foo;")
                add("Lcom/example/Bar;")
                add("Lcom/example/NotYetLoaded;")
            })
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("alloc-buf-1", response["buffer_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, response["resolved_classes"]?.jsonPrimitive?.intOrNull)
        assertTrue(Session.allocTraceBufferIds.contains("alloc-buf-1"))
    }
}
