package com.acendas.androiddebugger.jvmti

import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.tools.AgentTools
import com.acendas.androiddebugger.tools.V16TestHelpers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v1.6 spec §3.4 + §6/§11 — `agent_info` exposes derived feature-readiness
 * flags so callers don't have to read the raw capability map:
 *
 *   - `heap_walk_supported` <- `can_tag_objects`
 *   - `referrer_chain_supported` <- `can_tag_objects`
 *   - `method_trace_supported` <- `can_generate_method_entry_events` AND `can_generate_method_exit_events`
 *   - `alloc_trace_supported` <- `can_generate_vm_object_alloc_events` AND `can_tag_objects`
 *
 * Each test wires a specific capability shape and asserts the derived flag.
 */
class AgentInfoV16FlagsTest {

    private fun wireSessionWithCaps(caps: JsonObject) {
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

    @AfterTest
    fun teardown() {
        Session.agentState = null
    }

    private suspend fun callAgentInfo(): JsonObject {
        val server = V16TestHelpers.newServer()
        AgentTools.register(server)
        return V16TestHelpers.invokeTool(server, "agent_info", buildJsonObject {})
    }

    @Test
    fun heap_walk_and_referrer_chain_flags_track_can_tag_objects_true() = runTest {
        wireSessionWithCaps(buildJsonObject { put("can_tag_objects", true) })
        val response = callAgentInfo()
        assertEquals(true, response["heap_walk_supported"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, response["referrer_chain_supported"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun heap_walk_and_referrer_chain_flags_false_when_capability_absent() = runTest {
        wireSessionWithCaps(buildJsonObject { put("can_tag_objects", false) })
        val response = callAgentInfo()
        assertEquals(false, response["heap_walk_supported"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, response["referrer_chain_supported"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun method_trace_supported_requires_both_entry_and_exit_caps() = runTest {
        wireSessionWithCaps(buildJsonObject {
            put("can_generate_method_entry_events", true)
            put("can_generate_method_exit_events", true)
        })
        val response = callAgentInfo()
        assertEquals(true, response["method_trace_supported"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun method_trace_supported_false_when_only_entry_cap() = runTest {
        wireSessionWithCaps(buildJsonObject {
            put("can_generate_method_entry_events", true)
            put("can_generate_method_exit_events", false)
        })
        val response = callAgentInfo()
        assertEquals(false, response["method_trace_supported"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun method_trace_supported_false_when_only_exit_cap() = runTest {
        wireSessionWithCaps(buildJsonObject {
            put("can_generate_method_entry_events", false)
            put("can_generate_method_exit_events", true)
        })
        val response = callAgentInfo()
        assertEquals(false, response["method_trace_supported"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun alloc_trace_supported_requires_alloc_and_tag_caps() = runTest {
        wireSessionWithCaps(buildJsonObject {
            put("can_generate_vm_object_alloc_events", true)
            put("can_tag_objects", true)
        })
        val response = callAgentInfo()
        assertEquals(true, response["alloc_trace_supported"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun alloc_trace_supported_false_when_only_alloc_cap() = runTest {
        wireSessionWithCaps(buildJsonObject {
            put("can_generate_vm_object_alloc_events", true)
            put("can_tag_objects", false)
        })
        val response = callAgentInfo()
        assertEquals(false, response["alloc_trace_supported"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun alloc_trace_supported_false_when_only_tag_cap() = runTest {
        wireSessionWithCaps(buildJsonObject {
            put("can_generate_vm_object_alloc_events", false)
            put("can_tag_objects", true)
        })
        val response = callAgentInfo()
        assertEquals(false, response["alloc_trace_supported"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun all_capabilities_false_yields_all_flags_false() = runTest {
        wireSessionWithCaps(buildJsonObject {
            put("can_tag_objects", false)
            put("can_generate_method_entry_events", false)
            put("can_generate_method_exit_events", false)
            put("can_generate_vm_object_alloc_events", false)
        })
        val response = callAgentInfo()
        assertEquals(false, response["heap_walk_supported"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, response["referrer_chain_supported"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, response["method_trace_supported"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, response["alloc_trace_supported"]?.jsonPrimitive?.booleanOrNull)
    }
}
