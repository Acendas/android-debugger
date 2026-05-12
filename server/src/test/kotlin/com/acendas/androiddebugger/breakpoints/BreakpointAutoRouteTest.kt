package com.acendas.androiddebugger.breakpoints

import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.jvmti.AgentState
import com.acendas.androiddebugger.tools.BreakpointTools
import com.acendas.androiddebugger.tools.FakeAgentClient
import com.acendas.androiddebugger.tools.V16TestHelpers
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.EventRequestManager
import com.sun.jdi.request.MethodEntryRequest
import com.sun.jdi.request.MethodExitRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.6 — `add_method_breakpoint` auto-routes to JVMTI when:
 *   1. The agent is loaded AND
 *   2. `can_generate_method_entry_events=true` AND
 *   3. `log_message` is set AND
 *   4. `condition` is null
 *
 * Otherwise it falls through to the JDI path. Each branch is verified by
 * inspecting the `backend` field of the response + the BreakpointManager
 * meta's `jvmtiTraceBufferId` field for the JVMTI route.
 */
class BreakpointAutoRouteTest {

    private lateinit var server: io.modelcontextprotocol.kotlin.sdk.server.Server
    private lateinit var vm: VirtualMachine
    private lateinit var erm: EventRequestManager
    private lateinit var fakeClient: FakeAgentClient

    @BeforeTest
    fun setup() {
        server = V16TestHelpers.newServer()
        BreakpointTools.register(server)

        // Mock VM well enough that the JDI path's `vm.eventRequestManager()` returns
        // an event request manager whose createMethodEntryRequest / createMethodExitRequest
        // produce mocked requests we can inspect.
        vm = Mockito.mock(VirtualMachine::class.java)
        erm = Mockito.mock(EventRequestManager::class.java)
        Mockito.`when`(vm.eventRequestManager()).thenReturn(erm)
        val entryReq = Mockito.mock(MethodEntryRequest::class.java)
        val exitReq = Mockito.mock(MethodExitRequest::class.java)
        Mockito.`when`(erm.createMethodEntryRequest()).thenReturn(entryReq)
        Mockito.`when`(erm.createMethodExitRequest()).thenReturn(exitReq)
        Session.vm = vm
        Session.capabilities = buildJsonObject { }

        fakeClient = FakeAgentClient()
        // Note: Session.v16PollScope intentionally left null so the JVMTI path doesn't
        // spawn a poll coroutine (test runs in `runTest` which would notice the leak).
        Session.v16PollScope = null
        BreakpointManager.clear()
    }

    @AfterTest
    fun teardown() {
        Session.vm = null
        Session.capabilities = null
        Session.agentClient = null
        Session.agentState = null
        Session.v16PollScope = null
        Session.methodTraceBufferIds.clear()
        BreakpointManager.clear()
    }

    private fun wireAgentWithCaps(canMethodEntry: Boolean) {
        Session.agentClient = fakeClient
        Session.agentState = AgentState(
            protocolVersion = 2,
            agentVersion = "1.6.0",
            capabilities = buildJsonObject {
                put("can_generate_method_entry_events", canMethodEntry)
                put("can_generate_method_exit_events", true)
            },
            attachPath = "/data/local/tmp/libamdb_agent.so",
            hostSocketPath = Path.of("/tmp/fake.sock"),
            abstractNamespace = "android-debugger-com.x",
            attachPid = 1234,
            attachedAt = Instant.now(),
        )
    }

    @Test
    fun log_message_without_condition_with_capable_agent_routes_to_jvmti() = runTest {
        wireAgentWithCaps(canMethodEntry = true)
        fakeClient.stub("agent.method_trace_start", buildJsonObject {
            put("buffer_id", "mt-bp-1")
            put("started_at_ms", 1700000000000L)
            put("filter_kind", "methods")
            put("estimated_match_count", 1)
        })
        val response = V16TestHelpers.invokeTool(server, "add_method_breakpoint", buildJsonObject {
            put("class", "com.example.Foo")
            put("method", "bar")
            put("kind", "entry")
            put("log_message", "hit Foo.bar")
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("jvmti", response["backend"]?.jsonPrimitive?.contentOrNull)
        assertEquals("mt-bp-1", response["buffer_id"]?.jsonPrimitive?.contentOrNull)
        // BreakpointManager: meta must carry jvmtiTraceBufferId.
        val id = response["id"]?.jsonPrimitive?.intOrNull
        assertNotNull(id)
        val meta = BreakpointManager.get(id)
        assertNotNull(meta)
        assertEquals("mt-bp-1", meta.jvmtiTraceBufferId)
        assertTrue(Session.methodTraceBufferIds.contains("mt-bp-1"))
        // JDI path must NOT have been touched.
        Mockito.verify(erm, Mockito.never()).createMethodEntryRequest()
    }

    @Test
    fun log_message_with_condition_set_falls_through_to_jdi() = runTest {
        wireAgentWithCaps(canMethodEntry = true)
        // Even with a capable agent + log_message, a non-null condition forces JDI.
        // BreakpointInstaller.installMethodEntry invokes vm.eventRequestManager and
        // createMethodEntryRequest — both mocked.
        val response = V16TestHelpers.invokeTool(server, "add_method_breakpoint", buildJsonObject {
            put("class", "com.example.Foo")
            put("method", "bar")
            put("kind", "entry")
            put("log_message", "hit Foo.bar")
            put("condition", "items.size() > 0")
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("jdi", response["backend"]?.jsonPrimitive?.contentOrNull)
        // JDI's createMethodEntryRequest must have been called.
        Mockito.verify(erm).createMethodEntryRequest()
        // The agent must NOT have been hit.
        assertEquals(0, fakeClient.callLog.size)
    }

    @Test
    fun no_log_message_routes_to_jdi() = runTest {
        wireAgentWithCaps(canMethodEntry = true)
        val response = V16TestHelpers.invokeTool(server, "add_method_breakpoint", buildJsonObject {
            put("class", "com.example.Foo")
            put("method", "bar")
            put("kind", "entry")
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("jdi", response["backend"]?.jsonPrimitive?.contentOrNull)
        Mockito.verify(erm).createMethodEntryRequest()
        assertEquals(0, fakeClient.callLog.size)
    }

    @Test
    fun agent_not_loaded_routes_to_jdi_regardless_of_log_message() = runTest {
        // Don't set agent state. log_message would normally trigger JVMTI but no agent.
        Session.agentClient = null
        Session.agentState = null
        val response = V16TestHelpers.invokeTool(server, "add_method_breakpoint", buildJsonObject {
            put("class", "com.example.Foo")
            put("method", "bar")
            put("kind", "entry")
            put("log_message", "hit Foo.bar")
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("jdi", response["backend"]?.jsonPrimitive?.contentOrNull)
        Mockito.verify(erm).createMethodEntryRequest()
    }
}
