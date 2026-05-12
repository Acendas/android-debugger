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
 * v1.6 spec §6 — `find_referrer_chain` MCP tool. Surfaces:
 *   - one-deep chain (single hop to a root)
 *   - five-deep chain
 *   - max_depth_reached:true echo
 *   - multiple chains in one response
 */
class FindReferrerChainTest {

    private lateinit var server: io.modelcontextprotocol.kotlin.sdk.server.Server

    @BeforeTest
    fun setup() {
        server = V16TestHelpers.newServer()
        HeapTools.register(server)
        val vm = Mockito.mock(VirtualMachine::class.java)
        Session.vm = vm
        Session.capabilities = buildJsonObject { put("get_instance_info", true) }
        val fake = FakeAgentClient()
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
        fakeClient = fake
    }

    private lateinit var fakeClient: FakeAgentClient

    @AfterTest
    fun teardown() {
        Session.vm = null
        Session.capabilities = null
        Session.agentClient = null
        Session.agentState = null
    }

    private fun chain(depth: Int, rootKind: String, vararg steps: Triple<String, String, String?>): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put("depth", depth)
            put("root_kind", rootKind)
            put("path", buildJsonArray {
                for (s in steps) {
                    add(buildJsonObject {
                        put("ref", s.first)
                        put("type", s.second)
                        s.third?.let { put("edge", it) }
                    })
                }
            })
        }

    @Test
    fun one_deep_returns_single_chain_with_one_hop() = runTest {
        fakeClient.stub("agent.heap_find_referrer_chain", buildJsonObject {
            put("ref", "vobj#100")
            put("chains", buildJsonArray {
                add(chain(1, "jni_global", Triple("vobj#100", "com.example.Foo", "jni_global")))
            })
            put("max_depth_reached", false)
        })
        val response = V16TestHelpers.invokeTool(server, "find_referrer_chain", buildJsonObject {
            put("ref", "vobj#100")
            put("max_depth", 1)
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("jvmti", response["backend"]?.jsonPrimitive?.contentOrNull)
        val chains = response["chains"]?.jsonArray
        assertNotNull(chains)
        assertEquals(1, chains.size)
        assertEquals(1, chains[0].jsonObject["depth"]?.jsonPrimitive?.intOrNull)
        assertEquals("jni_global", chains[0].jsonObject["root_kind"]?.jsonPrimitive?.contentOrNull)
        val path = chains[0].jsonObject["path"]?.jsonArray
        assertNotNull(path)
        assertEquals(1, path.size)
    }

    @Test
    fun five_deep_chain_returns_five_path_steps() = runTest {
        fakeClient.stub("agent.heap_find_referrer_chain", buildJsonObject {
            put("ref", "vobj#100")
            put("chains", buildJsonArray {
                add(chain(
                    depth = 5,
                    rootKind = "thread_root",
                    Triple("vobj#100", "com.example.Foo", "field"),
                    Triple("vobj#99", "com.example.Bar", "field"),
                    Triple("vobj#98", "com.example.Baz", "field"),
                    Triple("vobj#97", "com.example.Qux", "array_element"),
                    Triple("vobj#1", "java.lang.Thread", "thread_root"),
                ))
            })
            put("max_depth_reached", false)
        })
        val response = V16TestHelpers.invokeTool(server, "find_referrer_chain", buildJsonObject {
            put("ref", "vobj#100")
            put("max_depth", 5)
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        val chains = response["chains"]?.jsonArray
        assertNotNull(chains)
        assertEquals(1, chains.size)
        assertEquals(5, chains[0].jsonObject["depth"]?.jsonPrimitive?.intOrNull)
        assertEquals(5, chains[0].jsonObject["path"]?.jsonArray?.size)
    }

    @Test
    fun max_depth_reached_flag_is_surfaced() = runTest {
        fakeClient.stub("agent.heap_find_referrer_chain", buildJsonObject {
            put("ref", "vobj#100")
            put("chains", buildJsonArray {
                add(chain(
                    depth = 5,
                    rootKind = "unresolved",
                    Triple("vobj#100", "com.example.Foo", "field"),
                ))
            })
            put("max_depth_reached", true)
        })
        val response = V16TestHelpers.invokeTool(server, "find_referrer_chain", buildJsonObject {
            put("ref", "vobj#100")
            put("max_depth", 5)
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, response["max_depth_reached"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun multiple_chains_returned_to_caller() = runTest {
        fakeClient.stub("agent.heap_find_referrer_chain", buildJsonObject {
            put("ref", "vobj#100")
            put("chains", buildJsonArray {
                add(chain(2, "jni_global",
                    Triple("vobj#100", "com.example.Foo", "field"),
                    Triple("vobj#99", "com.example.Bar", "jni_global"),
                ))
                add(chain(3, "thread_root",
                    Triple("vobj#100", "com.example.Foo", "field"),
                    Triple("vobj#50", "com.example.Bar", "field"),
                    Triple("vobj#1", "java.lang.Thread", "thread_root"),
                ))
                add(chain(2, "static_field",
                    Triple("vobj#100", "com.example.Foo", "static_field"),
                    Triple("vobj#10", "com.example.Holder", "static_field"),
                ))
            })
            put("max_depth_reached", false)
        })
        val response = V16TestHelpers.invokeTool(server, "find_referrer_chain", buildJsonObject {
            put("ref", "vobj#100")
            put("max_depth", 5)
            put("max_chains", 3)
        })
        assertEquals(true, response["ok"]?.jsonPrimitive?.booleanOrNull)
        val chains = response["chains"]?.jsonArray
        assertNotNull(chains)
        assertEquals(3, chains.size)
        // Verify root_kind shapes are preserved across the boundary.
        assertEquals("jni_global", chains[0].jsonObject["root_kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals("thread_root", chains[1].jsonObject["root_kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals("static_field", chains[2].jsonObject["root_kind"]?.jsonPrimitive?.contentOrNull)
    }
}
