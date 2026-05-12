package com.acendas.androiddebugger.inspection

import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.jvmti.AgentState
import com.acendas.androiddebugger.tools.FakeAgentClient
import com.acendas.androiddebugger.tools.MethodTraceTools
import com.acendas.androiddebugger.tools.V16TestHelpers
import com.sun.jdi.VirtualMachine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * v1.6 — `stop_method_trace` terminates a buffer, returns tail events, and
 * removes the buffer id from `Session.methodTraceBufferIds` so the detach
 * path doesn't double-stop it.
 */
class MethodTraceStopTest {

    private lateinit var server: io.modelcontextprotocol.kotlin.sdk.server.Server
    private lateinit var fakeClient: FakeAgentClient

    @BeforeTest
    fun setup() {
        server = V16TestHelpers.newServer()
        MethodTraceTools.register(server)
        val vm = Mockito.mock(VirtualMachine::class.java)
        Session.vm = vm
        Session.capabilities = buildJsonObject { put("get_instance_info", true) }
        fakeClient = FakeAgentClient()
        Session.agentClient = fakeClient
        Session.agentState = AgentState(
            protocolVersion = 2,
            agentVersion = "1.6.0",
            capabilities = buildJsonObject {
                put("can_generate_method_entry_events", true)
                put("can_generate_method_exit_events", true)
            },
            attachPath = "/data/local/tmp/libamdb_agent.so",
            hostSocketPath = Path.of("/tmp/fake.sock"),
            abstractNamespace = "android-debugger-com.x",
            attachPid = 1234,
            attachedAt = Instant.now(),
        )
        Session.methodTraceBufferIds.clear()
        Session.methodTraceBufferIds.add("mt-buf-1")
        Session.methodTraceBufferIds.add("mt-buf-2")
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
    fun stop_removes_from_session_and_returns_tail_events() = runTest {
        fakeClient.stub("agent.method_trace_stop", buildJsonObject {
            put("buffer_id", "mt-buf-1")
            put("stopped_at_ms", 1700000000000L)
            put("total_events", 100L)
            put("dropped_total", 2L)
            put("tail_events", buildJsonArray {
                add(buildJsonObject {
                    put("kind", "entry")
                    put("class", "Lcom/example/Foo;")
                    put("method", "bar")
                    put("thread", "main")
                    put("nano_time", 99L)
                    put("depth", 2)
                })
                add(buildJsonObject {
                    put("kind", "exit")
                    put("class", "Lcom/example/Foo;")
                    put("method", "bar")
                    put("thread", "main")
                    put("nano_time", 100L)
                    put("depth", 2)
                })
            })
        })
        val response = V16TestHelpers.invokeTool(server, "stop_method_trace", buildJsonObject {
            put("buffer_id", "mt-buf-1")
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("mt-buf-1", response["buffer_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(100L, response["total_events"]?.jsonPrimitive?.longOrNull)
        val tail = response["tail_events"]?.jsonArray
        assertNotNull(tail)
        assertEquals(2, tail.size)
        assertEquals("entry", tail[0].jsonObject["kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals("exit", tail[1].jsonObject["kind"]?.jsonPrimitive?.contentOrNull)

        // Session bookkeeping: stopped buffer is gone, other buffer untouched.
        assertFalse(Session.methodTraceBufferIds.contains("mt-buf-1"))
        assertEquals(setOf("mt-buf-2"), Session.methodTraceBufferIds.toSet())
    }
}
