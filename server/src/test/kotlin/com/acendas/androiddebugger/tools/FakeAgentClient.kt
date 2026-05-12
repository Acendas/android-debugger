package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.jvmti.AgentClient
import com.acendas.androiddebugger.jvmti.AgentRpcError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path

/**
 * v1.6 — test double for [AgentClient]. The real client requires a Unix socket;
 * the test double routes every `request` through a per-test [responder] map
 * keyed by RPC method name so tool tests can assert exactly which methods
 * were called and stub specific responses.
 *
 * Use [stub] to wire a method, [stubError] to fail one, and [callLog] to
 * inspect every call after the body ran.
 */
class FakeAgentClient(
    socketPath: Path = Path.of("/tmp/fake-agent-client.sock"),
) : AgentClient(socketPath, reconnect = { error("reconnect not supported in tests") }) {

    /** Per-method handler map. Returns a `JsonElement` (result body) or throws. */
    private val responders: MutableMap<String, (JsonObject?) -> JsonElement> = mutableMapOf()

    /** Record of every `(method, params)` pair the test triggered. */
    val callLog: MutableList<Pair<String, JsonObject?>> = mutableListOf()

    /** Whether [close] has been called. */
    var closed: Boolean = false
        private set

    fun stub(method: String, result: JsonElement) {
        responders[method] = { result }
    }

    fun stub(method: String, body: (JsonObject?) -> JsonElement) {
        responders[method] = body
    }

    fun stubError(method: String, rpcMessage: String, data: JsonObject? = null, code: Int = -32000) {
        responders[method] = { throw AgentRpcError(code, rpcMessage, data) }
    }

    override fun ensureOpen() {
        // No-op — tests bypass socket I/O.
    }

    override suspend fun hello(protocolVersion: Int): JsonObject {
        callLog += ("hello" to buildJsonObject {})
        return buildJsonObject { }
    }

    override suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonElement {
        callLog += (method to params)
        val responder = responders[method]
            ?: throw AgentRpcError(code = -32601, rpcMessage = "method not stubbed: $method")
        return responder(params)
    }

    override fun close() {
        closed = true
    }
}
