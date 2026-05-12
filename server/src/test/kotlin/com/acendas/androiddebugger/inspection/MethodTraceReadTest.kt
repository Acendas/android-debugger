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
import kotlinx.serialization.json.intOrNull
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
import kotlin.test.assertNotNull

/**
 * v1.6 — `read_method_trace` drains events from an agent-side buffer. Tests
 * cover passthrough of `args`/`return` (whatever JSON shape the agent sent),
 * dropped-since-last-read accounting, and unknown buffer_id error mapping.
 */
class MethodTraceReadTest {

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
    }

    @AfterTest
    fun teardown() {
        Session.vm = null
        Session.capabilities = null
        Session.agentClient = null
        Session.agentState = null
    }

    @Test
    fun events_include_args_and_return_passthrough() = runTest {
        fakeClient.stub("agent.method_trace_read", buildJsonObject {
            put("buffer_id", "mt-buf-1")
            put("events", buildJsonArray {
                // Entry event with args.
                add(buildJsonObject {
                    put("kind", "entry")
                    put("class", "Lcom/example/Foo;")
                    put("method", "bar")
                    put("thread", "main")
                    put("nano_time", 12345L)
                    put("depth", 1)
                    put("args", buildJsonArray {
                        add(buildJsonObject { put("value", "alpha"); put("type", "string") })
                        add(buildJsonObject { put("value", "42"); put("type", "int") })
                    })
                })
                // Exit event with return.
                add(buildJsonObject {
                    put("kind", "exit")
                    put("class", "Lcom/example/Foo;")
                    put("method", "bar")
                    put("thread", "main")
                    put("nano_time", 22345L)
                    put("depth", 1)
                    put("elapsed_ns", 10000L)
                    put("return", buildJsonObject { put("value", "true"); put("type", "boolean") })
                })
            })
            put("buffered", 0L)
            put("dropped_since_last_read", 5L)
            put("dropped_total", 17L)
        })

        val response = V16TestHelpers.invokeTool(server, "read_method_trace", buildJsonObject {
            put("buffer_id", "mt-buf-1")
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(5L, response["dropped_since_last_read"]?.jsonPrimitive?.longOrNull)
        assertEquals(17L, response["dropped_total"]?.jsonPrimitive?.longOrNull)
        val events = response["events"]?.jsonArray
        assertNotNull(events)
        assertEquals(2, events.size)
        // Entry event — args present as a JSON array passthrough.
        val entry = events[0].jsonObject
        assertEquals("entry", entry["kind"]?.jsonPrimitive?.contentOrNull)
        val args = entry["args"]?.jsonArray
        assertNotNull(args)
        assertEquals(2, args.size)
        assertEquals("alpha", args[0].jsonObject["value"]?.jsonPrimitive?.contentOrNull)
        // Exit event — return present as a JSON object passthrough.
        val exit = events[1].jsonObject
        assertEquals("exit", exit["kind"]?.jsonPrimitive?.contentOrNull)
        val ret = exit["return"]?.jsonObject
        assertNotNull(ret)
        assertEquals("true", ret["value"]?.jsonPrimitive?.contentOrNull)
        assertEquals(10000L, exit["elapsed_ns"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun unknown_buffer_id_surfaces_invalid_target() = runTest {
        fakeClient.stubError(
            "agent.method_trace_read",
            rpcMessage = "unknown_buffer_id",
            data = buildJsonObject { put("buffer_id", "mt-buf-xyz") },
        )
        val response = V16TestHelpers.invokeTool(server, "read_method_trace", buildJsonObject {
            put("buffer_id", "mt-buf-xyz")
        })
        assertEquals(false, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("invalid_target", response["code"]?.jsonPrimitive?.contentOrNull)
    }
}
