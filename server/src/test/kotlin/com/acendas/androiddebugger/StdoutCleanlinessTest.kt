package com.acendas.androiddebugger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for Story 7.1.6 (logging discipline).
 *
 * The MCP stdio transport requires that **every** byte on stdout be a valid JSON-RPC
 * frame. Any banner / `println` / log line corrupts the protocol so the client can't
 * parse the next response. The fix is [BannerSuppressor], which captures the real stdout
 * into a private slot and routes [System.out] to stderr.
 *
 * This test exercises the BannerSuppressor directly (rather than spinning up the full
 * MCP server, which would require an event-loop coroutine + JDI module). It:
 *
 *   1. Installs the suppressor.
 *   2. Writes a deliberate "banner-shaped" message via [System.out.println].
 *   3. Asserts that the captured real-stdout stream remained empty (the banner went
 *      to stderr instead).
 *   4. Writes a JSON-RPC-shaped frame via the captured real stdout (simulating what
 *      `StdioServerTransport` would do).
 *   5. Asserts the captured stream parses as valid JSON-RPC.
 *
 * If a future change accidentally re-routes a slf4j logger or third-party library back
 * to the real stdout, this test catches it.
 */
class StdoutCleanlinessTest {

    @Test
    fun banner_suppressor_routes_println_to_stderr_and_preserves_stdout() {
        // Capture the real stdout via the production helper.
        val realStdoutCaptured = java.io.ByteArrayOutputStream()
        val realStdoutPrint = java.io.PrintStream(realStdoutCaptured, true, Charsets.UTF_8)

        // Replace System.out with our spy BEFORE installing the suppressor so the
        // suppressor "captures" a controlled stream that we can later inspect.
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(realStdoutPrint)
            // Capture stderr too so we can assert the banner WAS rerouted.
            val stderrCapture = java.io.ByteArrayOutputStream()
            System.setErr(java.io.PrintStream(stderrCapture, true, Charsets.UTF_8))

            // Defeat any cached state from a prior test; reflective reset.
            resetSuppressor()
            val captured = BannerSuppressor.installAndCapture()
            assertTrue(BannerSuppressor.installed)
            // captured == realStdoutPrint (the System.out at install time).
            assertEquals(realStdoutPrint, captured)

            // Anything written via System.out now goes to stderr (per the suppressor).
            System.out.println("BANNER: this is a slf4j-simple style banner")
            System.out.println("logback-classic 1.5.x")

            // The captured real stdout MUST remain empty so far.
            assertEquals("", realStdoutCaptured.toString(Charsets.UTF_8))
            // The banner ended up on stderr.
            val errOutput = stderrCapture.toString(Charsets.UTF_8)
            assertTrue("BANNER" in errOutput, "stderr should contain the banner; got: $errOutput")

            // Write a JSON-RPC-shaped frame via the captured real stdout — simulating
            // what StdioServerTransport does.
            captured.println("""{"jsonrpc":"2.0","id":1,"result":{"ok":true}}""")
            captured.println("""{"jsonrpc":"2.0","id":2,"result":{"capabilities":{}}}""")
            captured.flush()

            // Parse every line; each must be valid JSON with a "jsonrpc": "2.0" field.
            val output = realStdoutCaptured.toString(Charsets.UTF_8)
            val lines = output.split("\n").filter { it.isNotBlank() }
            assertTrue(lines.isNotEmpty(), "expected at least one JSON-RPC line on stdout")
            for (line in lines) {
                val parsed: JsonElement = try {
                    Json.parseToJsonElement(line)
                } catch (t: Throwable) {
                    error("Line failed to parse as JSON: `$line` — ${t.message}")
                }
                require(parsed is JsonObject) { "Top-level must be an object: $line" }
                val jsonrpc = parsed["jsonrpc"]
                assertTrue(
                    jsonrpc is JsonPrimitive && jsonrpc.content == "2.0",
                    "Each line must declare jsonrpc 2.0; got: $line",
                )
            }
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            resetSuppressor()
        }
    }

    /**
     * Reflective reset of [BannerSuppressor]'s `capturedRealStdout` field so successive
     * test runs in the same JVM start clean. The production code path is install-once
     * idempotent, but tests deliberately re-install with controlled streams.
     */
    private fun resetSuppressor() {
        runCatching {
            val cls = BannerSuppressor::class.java
            val field = cls.getDeclaredField("capturedRealStdout")
            field.isAccessible = true
            field.set(BannerSuppressor, null)
        }
    }
}
