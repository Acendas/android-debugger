package com.acendas.androiddebugger.jdi

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import com.sun.jdi.Bootstrap
import com.sun.jdi.VirtualMachine
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * JDI socket-attach helper. We always go through `localhost:<port>` because adb
 * forwards the device-side JDWP port to localhost. Per Task 1.1.3.1.
 *
 * v1.7.1 (M2 hang mitigation): the underlying `AttachingConnector.attach(args)`
 * call is blocking and (despite accepting a `timeout` arg) the JDK is widely
 * known to ignore it on the initial socket connect / JDWP handshake. A wedged
 * emulator can hang the calling thread for minutes. We wrap the call in a
 * single-thread Executor future with a hard wall-clock ceiling. On expiration,
 * we open a separate Socket to the same host:port for ~100ms to perturb any
 * in-flight read (mirrors the JdiSocketWedgeRecovery technique), then throw a
 * structured [ToolError(ErrorCode.AttachTimeout)] so the caller surfaces a
 * useful hint instead of a generic attach_failed.
 */
object JdiAttacher {

    private val log = LoggerFactory.getLogger("JdiAttacher")

    /**
     * Attach to `host:port`. The connector's own `timeout` arg is still passed
     * as a belt-and-suspenders (some JDK versions DO honor it on auth/handshake),
     * but the authoritative ceiling is the outer Future-based timeout.
     *
     * @param timeoutMs hard wall-clock ceiling for the attach. On expiration,
     *   throws [ToolError(ErrorCode.AttachTimeout)] within roughly +200ms of the
     *   budget. Default 10000ms — longer than 5s for slow emulators on first
     *   attach, short enough to fail fast on a wedged port.
     */
    fun attach(host: String, port: Int, timeoutMs: Long = 10_000): VirtualMachine {
        val mgr = Bootstrap.virtualMachineManager()
        val connector = mgr.attachingConnectors().firstOrNull { it.transport().name() == "dt_socket" }
            ?: error("No dt_socket attaching connector available in this JDK.")
        val args = connector.defaultArguments().toMutableMap().apply {
            this["hostname"]?.setValue(host)
            this["port"]?.setValue(port.toString())
            this["timeout"]?.setValue(timeoutMs.toString())
        }

        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "jdi-attach-$host-$port").apply { isDaemon = true }
        }
        return try {
            val future = executor.submit<VirtualMachine> { connector.attach(args) }
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (te: TimeoutException) {
                // Cancel the future (best-effort — JDI's blocking socket read is
                // not interruptible by Thread.interrupt, so the worker thread
                // may linger until the kernel times out the read; that's OK —
                // it's a daemon and the executor will be GC'd).
                future.cancel(true)
                // Mirror JdiSocketWedgeRecovery's perturbation: opening a fresh
                // socket to the same endpoint can unblock a half-open peer that
                // never sent its JDWP handshake. Best-effort, 100ms budget.
                perturbSocket(host, port)
                throw ToolError(
                    errorCode = ErrorCode.AttachTimeout,
                    message = "JDI attach to $host:$port timed out after ${timeoutMs}ms.",
                    hint = "JDWP port may be stale (app process gone) or the emulator is wedged. " +
                        "Try /android-debugger:ad-detach, restart adb (adb kill-server && adb start-server), then re-attach.",
                )
            } catch (ie: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw ie
            } catch (ee: java.util.concurrent.ExecutionException) {
                // Unwrap the underlying exception thrown by connector.attach so
                // callers see a real cause (IOException, IllegalConnectorArgs,
                // etc.) rather than ExecutionException prose.
                throw ee.cause ?: ee
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Open a quick connection to `host:port` and immediately close it. The act
     * of completing a TCP three-way handshake against a half-open JDWP peer is
     * sometimes enough to dislodge a wedged JDI worker's pending read. Bounded
     * at 100ms so this never adds noticeable latency to the timeout path.
     */
    private fun perturbSocket(host: String, port: Int) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 100)
            }
        }.onFailure {
            log.debug("perturbSocket($host:$port) failed (expected on a fully-wedged peer): ${it.message}")
        }
    }
}
