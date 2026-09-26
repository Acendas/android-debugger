package com.acendas.androiddebugger.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * One NDJSON connection to the on-device UI daemon (through an adb forward).
 *
 * The daemon answers one line per request, so a connection is used either for
 * request/response ([request]) or, after `subscribe`, as an event stream ([readEvent]).
 * [UiSession] keeps separate connections for tool calls, settle reads and the event
 * stream so a slow settle read never delays a tap.
 */
class UiDaemonClient(port: Int, connectTimeoutMs: Int = 3_000) : AutoCloseable {

    private val socket = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
        tcpNoDelay = true
    }
    private val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
    private val output: OutputStream = socket.getOutputStream()

    /** Send one request and read its one-line reply. [readTimeoutMs] bounds the wait. */
    @Synchronized
    fun request(req: JsonObject, readTimeoutMs: Int = 10_000): JsonObject {
        socket.soTimeout = readTimeoutMs
        output.write((req.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
        val line = input.readLine() ?: throw java.io.EOFException("UI daemon closed the connection")
        return Json.parseToJsonElement(line).jsonObject
    }

    /** Blocking read of the next streamed event; null at end of stream. No timeout. */
    fun readEvent(): JsonObject? {
        socket.soTimeout = 0
        val line = input.readLine() ?: return null
        return runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
