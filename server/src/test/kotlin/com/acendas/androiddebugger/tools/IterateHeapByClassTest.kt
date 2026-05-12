package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.jvmti.AgentState
import com.sun.jdi.VirtualMachine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
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
import kotlinx.serialization.json.putJsonObject
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
 * v1.6 spec §6 — `iterate_heap_by_class` MCP tool. Routes through the JVMTI
 * agent and surfaces:
 *   - per-instance `vobj#` refs + size_bytes
 *   - the `truncated` flag when the agent's 10_000 hard cap is hit
 *   - structured error codes when the class isn't loaded or the agent isn't loaded
 */
class IterateHeapByClassTest {

    private lateinit var server: io.modelcontextprotocol.kotlin.sdk.server.Server

    @BeforeTest
    fun setup() {
        server = V16TestHelpers.newServer()
        HeapTools.register(server)
        val vm = Mockito.mock(VirtualMachine::class.java)
        Session.vm = vm
        Session.capabilities = buildJsonObject { put("get_instance_info", true) }
    }

    @AfterTest
    fun teardown() {
        Session.vm = null
        Session.capabilities = null
        Session.agentClient = null
        Session.agentState = null
    }

    private fun wireAgentWithCaps(caps: kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("can_tag_objects", true)
    }): FakeAgentClient {
        val fake = FakeAgentClient()
        Session.agentClient = fake
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
        return fake
    }

    @Test
    fun happy_path_returns_three_instances_with_jvmti_backend() = runTest {
        val fake = wireAgentWithCaps()
        fake.stub("agent.heap_iterate_by_class", buildJsonObject {
            put("class_signature", "Lcom/example/Foo;")
            put("instances", buildJsonArray {
                add(buildJsonObject { put("ref", "vobj#1"); put("size_bytes", 64L) })
                add(buildJsonObject { put("ref", "vobj#2"); put("size_bytes", 128L) })
                add(buildJsonObject { put("ref", "vobj#3"); put("size_bytes", 256L) })
            })
            put("total", 3L)
            put("truncated", false)
        })

        val response = V16TestHelpers.invokeTool(server, "iterate_heap_by_class", buildJsonObject {
            put("class_signature", "Lcom/example/Foo;")
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("jvmti", response["backend"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3L, response["total"]?.jsonPrimitive?.longOrNull)
        assertEquals(false, response["truncated"]?.jsonPrimitive?.booleanOrNull)
        val instances = response["instances"]?.jsonArray
        assertNotNull(instances)
        assertEquals(3, instances.size)
        assertEquals("vobj#1", instances[0].jsonObject["ref"]?.jsonPrimitive?.contentOrNull)
        assertEquals(64L, instances[0].jsonObject["size_bytes"]?.jsonPrimitive?.longOrNull)
        assertEquals("vobj#3", instances[2].jsonObject["ref"]?.jsonPrimitive?.contentOrNull)
        assertEquals(256L, instances[2].jsonObject["size_bytes"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun truncated_response_surfaces_flag() = runTest {
        val fake = wireAgentWithCaps()
        fake.stub("agent.heap_iterate_by_class", buildJsonObject {
            put("class_signature", "Lcom/example/Foo;")
            put("instances", buildJsonArray {
                add(buildJsonObject { put("ref", "vobj#1"); put("size_bytes", 0L) })
            })
            put("total", 50000L)
            put("truncated", true)
        })
        val response = V16TestHelpers.invokeTool(server, "iterate_heap_by_class", buildJsonObject {
            put("class_signature", "Lcom/example/Foo;")
            put("max", 1)
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, response["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(50000L, response["total"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun class_not_loaded_is_surfaced_as_invalid_target() = runTest {
        val fake = wireAgentWithCaps()
        fake.stubError(
            "agent.heap_iterate_by_class",
            rpcMessage = "class_not_loaded",
            data = buildJsonObject { put("class_signature", "Lcom/example/Foo;") },
        )
        val response = V16TestHelpers.invokeTool(server, "iterate_heap_by_class", buildJsonObject {
            put("class_signature", "Lcom/example/Foo;")
        })
        assertEquals(false, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("invalid_target", response["code"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun agent_not_loaded_returns_capability_unavailable() = runTest {
        // Tool requires an agent; no agentClient on Session.
        Session.agentClient = null
        Session.agentState = null

        val response = V16TestHelpers.invokeTool(server, "iterate_heap_by_class", buildJsonObject {
            put("class_signature", "Lcom/example/Foo;")
        })
        assertEquals(false, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("capability_unavailable", response["code"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            (response["message"]?.jsonPrimitive?.contentOrNull ?: "").contains("agent_not_loaded"),
            "expected agent_not_loaded in message; got ${response["message"]}",
        )
    }
}
