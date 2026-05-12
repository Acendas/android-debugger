package com.acendas.androiddebugger

import com.acendas.androiddebugger.jvmti.AgentClient
import com.acendas.androiddebugger.jvmti.AgentRpcError
import com.acendas.androiddebugger.jvmti.AgentState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v1.6 — `DebugSession.reset()` must drain every active method/alloc trace
 * on the agent before nulling out the client. This verifies:
 *
 *   1. The single `agent.stop_all_traces` RPC fires when either set is non-empty.
 *   2. After reset both `methodTraceBufferIds` and `allocTraceBufferIds` are empty.
 *   3. The mocked agent client gets closed.
 *   4. No RPC fires when both sets are empty (no point waking the agent).
 *
 * Uses a fresh [DebugSession] subclass rather than the singleton to avoid
 * cross-test contamination of the JVM-wide [Session] state.
 */
class SessionDetachCleansTracesTest {

    /** Stand-in [AgentClient] that records `request` invocations + close. */
    private class RecordingAgentClient :
        AgentClient(Path.of("/tmp/recording.sock"), reconnect = { error("not supported") }) {

        val callLog: MutableList<String> = mutableListOf()
        val closeCount: AtomicInteger = AtomicInteger(0)

        override fun ensureOpen() {
            // no-op
        }

        override suspend fun hello(protocolVersion: Int): JsonObject {
            callLog += "hello"
            return buildJsonObject { }
        }

        override suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonElement {
            callLog += method
            // Return a benign empty object so the wrapper doesn't trip.
            return buildJsonObject { }
        }

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private fun freshSession(): DebugSession = object : DebugSession() {}

    @Test
    fun reset_with_active_traces_drains_via_stop_all_traces() {
        val session = freshSession()
        val client = RecordingAgentClient()
        session.agentClient = client
        session.agentState = AgentState(
            protocolVersion = 2,
            agentVersion = "1.6.0",
            capabilities = buildJsonObject { put("can_tag_objects", true) },
            attachPath = "/data/local/tmp/libamdb_agent.so",
            hostSocketPath = Path.of("/tmp/fake.sock"),
            abstractNamespace = "android-debugger-com.x",
            attachPid = 1234,
            attachedAt = Instant.now(),
        )
        session.methodTraceBufferIds.add("mt-1")
        session.methodTraceBufferIds.add("mt-2")
        session.allocTraceBufferIds.add("alloc-1")

        session.reset()

        // Both sets must be empty after reset.
        assertEquals(0, session.methodTraceBufferIds.size)
        assertEquals(0, session.allocTraceBufferIds.size)
        // The agent client must have been closed.
        assertTrue(client.closeCount.get() >= 1, "expected agent client to be closed once, got ${client.closeCount.get()}")
        // The single bulk RPC fired (covers both method and alloc surfaces per spec).
        assertTrue(
            client.callLog.contains("agent.stop_all_traces"),
            "expected agent.stop_all_traces in call log, got ${client.callLog}",
        )
        // agentClient must be nulled out.
        assertEquals(null, session.agentClient)
    }

    @Test
    fun reset_with_no_active_traces_does_not_send_stop_all_traces() {
        val session = freshSession()
        val client = RecordingAgentClient()
        session.agentClient = client
        session.agentState = AgentState(
            protocolVersion = 2,
            agentVersion = "1.6.0",
            capabilities = buildJsonObject { put("can_tag_objects", true) },
            attachPath = "/data/local/tmp/libamdb_agent.so",
            hostSocketPath = Path.of("/tmp/fake.sock"),
            abstractNamespace = "android-debugger-com.x",
            attachPid = 1234,
            attachedAt = Instant.now(),
        )
        // No active buffer ids.

        session.reset()

        // Client still closed.
        assertTrue(client.closeCount.get() >= 1)
        // But no stop_all_traces — nothing to drain.
        assertEquals(
            emptyList<String>(),
            client.callLog,
            "expected no RPCs when both sets are empty; got ${client.callLog}",
        )
    }

    @Test
    fun reset_clears_only_alloc_traces_when_method_traces_empty() {
        val session = freshSession()
        val client = RecordingAgentClient()
        session.agentClient = client
        session.agentState = AgentState(
            protocolVersion = 2,
            agentVersion = "1.6.0",
            capabilities = buildJsonObject { put("can_tag_objects", true) },
            attachPath = "/data/local/tmp/libamdb_agent.so",
            hostSocketPath = Path.of("/tmp/fake.sock"),
            abstractNamespace = "android-debugger-com.x",
            attachPid = 1234,
            attachedAt = Instant.now(),
        )
        // Only alloc traces active.
        session.allocTraceBufferIds.add("alloc-99")

        session.reset()

        assertEquals(0, session.allocTraceBufferIds.size)
        assertTrue(
            client.callLog.contains("agent.stop_all_traces"),
            "stop_all_traces must fire when allocTraceBufferIds is non-empty",
        )
    }
}
