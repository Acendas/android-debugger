package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.jvmti.AgentClient
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * v1.6 — shared test scaffolding for tool-level tests. Constructs an in-process
 * [Server] without any transport so a test can invoke a registered tool's
 * handler directly. The handler lambda is an extension on [ClientConnection]
 * but the v1.6 tools don't touch it, so we pass `null` through a Function3
 * cast.
 */
object V16TestHelpers {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Build a fresh [Server] for a test. No transport is attached; tools are
     * registered against this instance and invoked directly via [invokeTool].
     */
    fun newServer(): Server = Server(
        serverInfo = Implementation(name = "test", version = "test"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    /**
     * Invoke a registered tool's handler with [args] and return the resulting
     * JSON payload parsed from the single [TextContent] block. The first
     * argument to the handler (the [ClientConnection] receiver) is passed as
     * `null` — the v1.6 tools don't touch it.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun invokeTool(server: Server, name: String, args: JsonObject = buildJsonObject {}): JsonObject {
        val registered = server.tools[name]
            ?: error("tool `$name` not registered on server")
        val handler = registered.handler as suspend (ClientConnection?, CallToolRequest) -> CallToolResult
        val request = buildCallToolRequest {
            this.name = name
            arguments(args)
        }
        val result: CallToolResult = handler(null, request)
        val text = (result.content.first() as TextContent).text
        return json.parseToJsonElement(text) as JsonObject
    }
}
