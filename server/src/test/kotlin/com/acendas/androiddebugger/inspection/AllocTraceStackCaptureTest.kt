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
import kotlin.test.assertTrue

/**
 * v1.6 — `start_alloc_trace` validates `capture_stack_depth` is 0..10 (the
 * agent's hard cap). Tests cover:
 *   - depth=0 → events arrive with an empty `stack` array
 *   - depth=5 → events arrive with `stack` frames populated
 *   - depth=11 → InvalidTarget pre-transmission
 */
class AllocTraceStackCaptureTest {

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
    fun depth_zero_yields_empty_stack_arrays_on_read() = runTest {
        fakeClient.stub("agent.alloc_trace_start", buildJsonObject {
            put("buffer_id", "alloc-buf-1")
            put("started_at_ms", 1700000000000L)
            put("resolved_classes", 1)
            put("unresolved_classes", buildJsonArray { })
        })
        // The start call records what the tool sent — assert capture_stack_depth=0.
        val startResp = V16TestHelpers.invokeTool(server, "start_alloc_trace", buildJsonObject {
            put("class_signatures", buildJsonArray { add("Lcom/example/Foo;") })
            put("capture_stack_depth", 0)
        })
        assertEquals(true, startResp["ok"]?.jsonPrimitive?.booleanOrNull)
        // Inspect the params the wrapper sent to the agent — must echo 0.
        val sentParams = fakeClient.callLog.first { it.first == "agent.alloc_trace_start" }.second
        assertNotNull(sentParams)
        assertEquals(0, sentParams["capture_stack_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())

        // Now exercise the read path; the agent returns events with empty stack arrays.
        fakeClient.stub("agent.alloc_trace_read", buildJsonObject {
            put("buffer_id", "alloc-buf-1")
            put("events", buildJsonArray {
                add(buildJsonObject {
                    put("class", "Lcom/example/Foo;")
                    put("thread", "main")
                    put("nano_time", 12345L)
                    put("size_bytes", 64L)
                    put("stack", buildJsonArray { })
                })
            })
            put("buffered", 0L)
            put("dropped_since_last_read", 0L)
            put("dropped_total", 0L)
        })
        val readResp = V16TestHelpers.invokeTool(server, "read_alloc_trace", buildJsonObject {
            put("buffer_id", "alloc-buf-1")
        })
        assertEquals(true, readResp["ok"]?.jsonPrimitive?.booleanOrNull)
        val events = readResp["events"]?.jsonArray
        assertNotNull(events)
        assertEquals(1, events.size)
        val stack = events[0].jsonObject["stack"]?.jsonArray
        assertNotNull(stack)
        assertTrue(stack.isEmpty(), "capture_stack_depth=0 must yield empty stack arrays")
    }

    @Test
    fun depth_five_yields_populated_stack_frames() = runTest {
        fakeClient.stub("agent.alloc_trace_start", buildJsonObject {
            put("buffer_id", "alloc-buf-1")
            put("started_at_ms", 1700000000000L)
            put("resolved_classes", 1)
            put("unresolved_classes", buildJsonArray { })
        })
        V16TestHelpers.invokeTool(server, "start_alloc_trace", buildJsonObject {
            put("class_signatures", buildJsonArray { add("Lcom/example/Foo;") })
            put("capture_stack_depth", 5)
        })
        // Read returns events with 5 frames.
        fakeClient.stub("agent.alloc_trace_read", buildJsonObject {
            put("buffer_id", "alloc-buf-1")
            put("events", buildJsonArray {
                add(buildJsonObject {
                    put("class", "Lcom/example/Foo;")
                    put("thread", "main")
                    put("nano_time", 12345L)
                    put("size_bytes", 64L)
                    put("stack", buildJsonArray {
                        add(buildJsonObject {
                            put("class", "Lcom/example/Foo;")
                            put("method", "<init>")
                            put("line", 11)
                        })
                        add(buildJsonObject {
                            put("class", "Lcom/example/Caller;")
                            put("method", "make")
                            put("line", 22)
                        })
                        add(buildJsonObject {
                            put("class", "Lcom/example/MainActivity;")
                            put("method", "onCreate")
                            put("line", 33)
                        })
                        add(buildJsonObject {
                            put("class", "Landroid/app/Activity;")
                            put("method", "performCreate")
                            put("line", 44)
                        })
                        add(buildJsonObject {
                            put("class", "Landroid/app/Instrumentation;")
                            put("method", "callActivityOnCreate")
                            put("line", 55)
                        })
                    })
                })
            })
            put("buffered", 0L)
            put("dropped_since_last_read", 0L)
            put("dropped_total", 0L)
        })
        val readResp = V16TestHelpers.invokeTool(server, "read_alloc_trace", buildJsonObject {
            put("buffer_id", "alloc-buf-1")
        })
        assertEquals(true, readResp["ok"]?.jsonPrimitive?.booleanOrNull)
        val events = readResp["events"]?.jsonArray
        assertNotNull(events)
        val stack = events[0].jsonObject["stack"]?.jsonArray
        assertNotNull(stack)
        assertEquals(5, stack.size, "depth=5 must yield 5 stack frames")
        assertEquals("<init>", stack[0].jsonObject["method"]?.jsonPrimitive?.contentOrNull)
        assertEquals(55L, stack[4].jsonObject["line"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun depth_eleven_returns_invalid_target_pre_transmission() = runTest {
        val response = V16TestHelpers.invokeTool(server, "start_alloc_trace", buildJsonObject {
            put("class_signatures", buildJsonArray { add("Lcom/example/Foo;") })
            put("capture_stack_depth", 11)
        })
        assertEquals(false, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("invalid_target", response["code"]?.jsonPrimitive?.contentOrNull)
        // No RPC fired — validation refused.
        assertEquals(0, fakeClient.callLog.size)
    }
}
