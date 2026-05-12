package com.acendas.androiddebugger.jvmti

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin client over the JVMTI agent's JSON-RPC Unix-domain socket.
 *
 * Wire protocol (per v1.4 plan D4 + D9):
 *   - Line-delimited JSON-RPC 2.0.
 *   - First message MUST be `hello` with `protocol_version: 1`.
 *   - Subsequent calls dispatch to handlers like `ping`, `agent_info_raw`.
 *
 * Discipline:
 *   - One [request] in flight at a time, serialized by [mutex]. JDI's
 *     single-flight evaluator runs on its own private mutex; this is parallel.
 *   - On `IOException` (socket closed mid-call), [request] attempts a single
 *     reconnect via [reconnect] before failing the caller.
 *
 * Lifetime: opened in [JvmtiAgentLauncher.openClient]; closed in
 * [com.acendas.androiddebugger.Session.reset].
 */
open class AgentClient(
    private val hostSocketPath: Path,
    private val reconnect: suspend () -> AgentClient,
) : AutoCloseable {

    private val mutex: Mutex = Mutex()
    private val idCounter: AtomicLong = AtomicLong(0L)

    @Volatile
    private var channel: SocketChannel? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Open the socket. Idempotent — re-opens if previously closed. */
    @Synchronized
    open fun ensureOpen() {
        if (channel?.isOpen == true) return
        close()
        val addr = UnixDomainSocketAddress.of(hostSocketPath)
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(addr) }
        channel = ch
        val input = Channels.newInputStream(ch)
        val output = Channels.newOutputStream(ch)
        reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
    }

    /**
     * Send the JSON-RPC `hello` handshake. Must succeed before any other call.
     * Throws [ToolError] on protocol mismatch.
     */
    open suspend fun hello(protocolVersion: Int = 1): JsonObject = mutex.withLock {
        ensureOpen()
        val params = buildJsonObject { put("protocol_version", protocolVersion) }
        val result = sendRaw("hello", params)
        result.jsonObject
    }

    /**
     * Generic request. Caller passes method name + optional params; gets back the
     * `result` JSON element. JSON-RPC errors are translated to [AgentRpcError].
     */
    open suspend fun request(
        method: String,
        params: JsonObject? = null,
        timeoutMs: Long = 10_000L,
    ): JsonElement = mutex.withLock {
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            ensureOpen()
            withTimeoutOrNull(timeoutMs) { sendRaw(method, params) }
                ?: throw ToolError(
                    errorCode = ErrorCode.Internal,
                    message = "Agent RPC `$method` timed out after $timeoutMs ms.",
                )
        } catch (io: IOException) {
            // Per v1.4 plan D.1.2 — try one reconnect before failing.
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L)
            close()
            val reconnected = try {
                reconnect()
            } catch (t: Throwable) {
                throw ToolError(
                    errorCode = ErrorCode.Internal,
                    message = "Agent RPC `$method` failed and reconnect threw: ${t.message}",
                    hint = "The JVMTI agent may have died. Force-stop the app and re-attach.",
                )
            }
            // Hand-roll one retry via the freshly-built client. Note: reconnected
            // is a fresh AgentClient instance — we send through ITS socket, not
            // our own. Caller is expected to swap this client out for the new one.
            reconnected.ensureOpen()
            withTimeoutOrNull(remaining) { reconnected.sendRaw(method, params) }
                ?: throw ToolError(
                    errorCode = ErrorCode.Internal,
                    message = "Agent RPC `$method` timed out after reconnect.",
                )
        }
    }

    /** Sends `{id,method,params}\n`, reads one line, returns `result` or throws on `error`. */
    private fun sendRaw(method: String, params: JsonObject?): JsonElement {
        val rid = idCounter.incrementAndGet()
        val envelope = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", rid)
            put("method", method)
            if (params != null) put("params", params)
        }
        val w = writer ?: throw IOException("agent socket writer not open")
        val r = reader ?: throw IOException("agent socket reader not open")
        w.write(json.encodeToString(JsonObject.serializer(), envelope))
        w.write("\n")
        w.flush()
        val line = r.readLine() ?: throw IOException("agent socket closed during read")
        val reply = try {
            json.parseToJsonElement(line).jsonObject
        } catch (t: Throwable) {
            throw IOException("malformed agent reply: ${line.take(200)}", t)
        }
        reply["error"]?.let { errEl ->
            val err = errEl.jsonObject
            throw AgentRpcError(
                code = err["code"]?.jsonPrimitive?.intOrNull ?: -1,
                rpcMessage = err["message"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                data = err["data"] as? JsonObject,
            )
        }
        return reply["result"] ?: throw IOException("agent reply missing both result and error")
    }

    override fun close() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { channel?.close() }
        writer = null
        reader = null
        channel = null
    }
}

/**
 * JSON-RPC-level error from the agent. Distinct from [ToolError]: the agent
 * may report `agent_version_mismatch`, `agent_in_use`, etc., which the caller
 * maps to the appropriate [ToolError] for the MCP boundary.
 */
class AgentRpcError(
    val code: Int,
    val rpcMessage: String,
    val data: JsonObject? = null,
) : RuntimeException("agent rpc error $code: $rpcMessage")
