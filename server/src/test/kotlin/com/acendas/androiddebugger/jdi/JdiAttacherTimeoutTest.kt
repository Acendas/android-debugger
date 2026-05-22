package com.acendas.androiddebugger.jdi

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * v1.7.1 (M2 hang mitigation) — JdiAttacher must fail fast (via [ToolError] with
 * [ErrorCode.AttachTimeout]) when the JDWP peer never completes the handshake,
 * instead of hanging the calling thread for minutes on JDK's blocking socket-attach.
 *
 * We simulate a wedged JDWP peer with a [ServerSocket] that [ServerSocket.accept]s the
 * connection but never reads or writes anything — exactly the failure mode of an
 * Android emulator whose VMDebug subsystem has frozen.
 */
class JdiAttacherTimeoutTest {

    private lateinit var server: ServerSocket
    private val acceptedSockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var acceptorRunning = true

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        // Drain accept() in a background thread, but never read/write the
        // accepted sockets. This mimics a wedged JDWP peer.
        thread(name = "wedged-jdwp-acceptor", isDaemon = true) {
            while (acceptorRunning && !server.isClosed) {
                val s = try {
                    server.accept()
                } catch (_: Throwable) {
                    return@thread
                }
                acceptedSockets += s
            }
        }
    }

    @AfterTest
    fun tearDown() {
        acceptorRunning = false
        runCatching { server.close() }
        acceptedSockets.forEach { runCatching { it.close() } }
    }

    @Test
    fun attach_times_out_with_structured_error_within_budget() {
        val port = server.localPort
        val budgetMs = 500L

        val err: ToolError
        val elapsed = measureTimeMillis {
            err = assertFailsWith<ToolError> {
                JdiAttacher.attach("localhost", port, timeoutMs = budgetMs)
            }
        }

        assertEquals(
            ErrorCode.AttachTimeout,
            err.errorCode,
            "Expected AttachTimeout, got ${err.errorCode}: ${err.message}",
        )
        // Allow generous headroom for CI flakiness; the point is "≪ 30s", not exact.
        assertTrue(
            elapsed < 5_000,
            "Attach should fail fast on wedged peer. Budget=${budgetMs}ms, elapsed=${elapsed}ms",
        )
        assertTrue(
            err.hint?.contains("ad-detach") == true || err.hint?.contains("adb kill-server") == true,
            "Hint should mention the recovery procedure; got: ${err.hint}",
        )
    }

    @Test
    fun follow_up_attach_also_fails_fast_no_zombie_thread_holds_port() {
        val port = server.localPort
        val budgetMs = 500L

        // First attempt — wedged peer, must timeout.
        assertFailsWith<ToolError> {
            JdiAttacher.attach("localhost", port, timeoutMs = budgetMs)
        }

        // Second attempt against the SAME wedged peer should also fail fast.
        // The first attempt's executor must have shut down (no leaked daemon
        // thread holding state) and the previous future cancellation must not
        // have wedged the port from the client side.
        val secondErr: ToolError
        val elapsed = measureTimeMillis {
            secondErr = assertFailsWith<ToolError> {
                JdiAttacher.attach("localhost", port, timeoutMs = budgetMs)
            }
        }
        assertEquals(ErrorCode.AttachTimeout, secondErr.errorCode)
        assertTrue(
            elapsed < 5_000,
            "Second attach should also fail fast (no zombie holding port). elapsed=${elapsed}ms",
        )
    }
}
