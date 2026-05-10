package com.acendas.androiddebugger

import java.io.PrintStream

/**
 * Captures the real `System.out` and redirects [System.setOut] to stderr, so any stray
 * `println` / banner from a third-party library doesn't corrupt the MCP stdio transport
 * (stdin/stdout = JSON-RPC frames; anything else here breaks the protocol).
 *
 * Per Story 7.1.6 / Task 7.1.6.2.
 *
 * Usage: call [installAndCapture] once at process start (from [main]) before any logging
 * or third-party code runs. The returned [PrintStream] is the real stdout — hand it to
 * `StdioServerTransport`'s output stream. Subsequent `System.out.println(...)` calls go
 * to stderr, which is harmless.
 *
 * The [installed] flag protects against double-install in tests that run [main] multiple
 * times in the same JVM — calling twice returns the originally-captured stdout.
 */
object BannerSuppressor {

    @Volatile private var capturedRealStdout: PrintStream? = null

    /**
     * Idempotent install. Captures the real stdout once and routes [System.out] to stderr.
     * Returns the captured real stdout. Subsequent calls return the same captured stream
     * without re-routing.
     */
    @Synchronized
    fun installAndCapture(): PrintStream {
        capturedRealStdout?.let { return it }
        val real = System.out
        capturedRealStdout = real
        System.setOut(System.err)
        // slf4j-simple defaults to stderr if its system properties point there.
        // Force it explicitly so a future logback/etc. swap doesn't accidentally split logs.
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
        }
        return real
    }

    /** True iff [installAndCapture] has been called this JVM. */
    val installed: Boolean
        get() = capturedRealStdout != null
}
