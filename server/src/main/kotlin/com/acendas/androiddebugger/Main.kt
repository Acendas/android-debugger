package com.acendas.androiddebugger

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PrintStream

/**
 * MCP server entry point.
 *
 * stdio transport: stdin = JSON-RPC requests in, stdout = responses out.
 *
 * CRITICAL: anything written to System.out other than MCP frames corrupts the transport.
 * Some libraries (kotlin-logging, slf4j-simple defaults) write banners to stdout on first
 * use. We capture the real stdout into a private FileDescriptor first, then redirect
 * System.out to stderr so any stray println goes somewhere harmless. The captured stream
 * is what we hand to StdioServerTransport.
 */
fun main(): Unit {
    // Per Story 7.1.6 — extracted into [BannerSuppressor] so the swap is reusable from
    // tests (the JSON-RPC stdout-cleanliness regression test installs the same shim).
    val realStdout: PrintStream = BannerSuppressor.installAndCapture()

    val log = org.slf4j.LoggerFactory.getLogger("android-debugger")
    log.info("android-debugger MCP server starting (v${BuildInfo.VERSION})")

    // Story 1.1.4 / Task 1.1.4.2: shutdown hook releases the adb forward and disposes
    // the JDI VM if we exit unexpectedly. Without this, a killed server leaves the app
    // suspended at the next breakpoint hit and the local TCP port forwarded forever.
    Runtime.getRuntime().addShutdownHook(
        Thread({ runCatching { Session.detach() } }, "android-debugger-shutdown"),
    )

    runBlocking {
        val server = Server(
            serverInfo = Implementation(
                name = "android-debugger",
                version = BuildInfo.VERSION,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
            ),
        )

        Tools.register(server)

        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = realStdout.asSink().buffered(),
        )

        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        log.info("android-debugger MCP server connected; awaiting requests")
        done.join()
        log.info("android-debugger MCP server shutting down")
    }
}
