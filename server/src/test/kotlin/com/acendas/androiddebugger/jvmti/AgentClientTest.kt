package com.acendas.androiddebugger.jvmti

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Per Phase F.1.1. Spawns a fake JVMTI agent server on a Unix domain socket
 * in the test process so the real [AgentClient] socket I/O is exercised
 * end-to-end (no host JDK side-effects, but real JSON-RPC over real Unix
 * sockets — JDK 17 ships [UnixDomainSocketAddress] without extras).
 */
class AgentClientTest {

    private lateinit var socketPath: Path
    private lateinit var server: ServerSocketChannel
    private val acceptCount = AtomicInteger(0)
    private val handlers = mutableMapOf<String, (JsonObject) -> JsonObject>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @BeforeTest
    fun setup() {
        socketPath = Files.createTempFile("agent-client-test-", ".sock")
        Files.deleteIfExists(socketPath)  // can't bind to an existing file
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socketPath))
        }
        Thread({
            try {
                while (true) {
                    val client = server.accept() ?: break
                    acceptCount.incrementAndGet()
                    serveClient(client)
                }
            } catch (_: Throwable) { /* shutting down */ }
        }, "fake-agent").apply { isDaemon = true; start() }

        // Default `hello` handler: respond with v1 + version string.
        handlers["hello"] = { _ ->
            buildJsonObject {
                put("protocol_version", 1)
                put("agent_version", "1.4.0")
            }
        }
    }

    @AfterTest
    fun teardown() {
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(socketPath) }
    }

    private fun serveClient(ch: java.nio.channels.SocketChannel) {
        val r = BufferedReader(InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8))
        val w = OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8)
        try {
            while (true) {
                val line = r.readLine() ?: return
                val obj = json.parseToJsonElement(line).jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: continue
                val params = (obj["params"] as? JsonObject) ?: JsonObject(emptyMap())
                val handler = handlers[method]
                val reply: JsonObject = if (handler != null) {
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        if (id != null) put("id", JsonPrimitive(id.toIntOrNull() ?: 0))
                        put("result", handler(params))
                    }
                } else {
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        if (id != null) put("id", JsonPrimitive(id.toIntOrNull() ?: 0))
                        put("error", buildJsonObject {
                            put("code", -32601)
                            put("message", "method not found")
                        })
                    }
                }
                w.write(json.encodeToString(JsonObject.serializer(), reply))
                w.write("\n")
                w.flush()
            }
        } catch (_: Throwable) {
            // client closed
        } finally {
            runCatching { ch.close() }
        }
    }

    @Test
    fun hello_round_trip_succeeds() = runBlocking {
        val client = AgentClient(socketPath, reconnect = { error("not expected") })
        try {
            client.ensureOpen()
            val result = client.hello(protocolVersion = 1)
            assertEquals("1.4.0", result["agent_version"]?.jsonPrimitive?.contentOrNull)
            assertEquals(1, result["protocol_version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
        } finally {
            client.close()
        }
    }

    @Test
    fun request_dispatches_and_returns_result() = runBlocking {
        handlers["ping"] = { _ ->
            buildJsonObject { put("pong", true) }
        }
        val client = AgentClient(socketPath, reconnect = { error("not expected") })
        try {
            client.ensureOpen()
            client.hello()
            val result = client.request("ping") as JsonObject
            assertEquals(true, result["pong"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        } finally {
            client.close()
        }
    }

    @Test
    fun request_propagates_rpc_error_to_AgentRpcError() = runBlocking {
        handlers["bad"] = { throw IllegalStateException("simulated") }
        val client = AgentClient(socketPath, reconnect = { error("not expected") })
        try {
            client.ensureOpen()
            client.hello()
            // The fake server falls through to method-not-found for unknown methods,
            // which surfaces as AgentRpcError with code -32601.
            val err = assertFailsWith<AgentRpcError> { client.request("never-defined") }
            assertEquals(-32601, err.code)
        } finally {
            client.close()
        }
    }

    @Test
    fun explicit_close_terminates_writer_and_reader() = runBlocking {
        // Open + close round-trip. After close, channel/reader/writer are nulled.
        // Re-opening (ensureOpen + hello) should still work because the test server
        // continues accepting connections.
        val client = AgentClient(socketPath, reconnect = { error("not expected") })
        client.ensureOpen()
        client.hello()
        client.close()
        // Re-open is a fresh socket connect.
        client.ensureOpen()
        val result = client.hello(1)
        assertEquals("1.4.0", result["agent_version"]?.jsonPrimitive?.contentOrNull)
        client.close()
    }
}
