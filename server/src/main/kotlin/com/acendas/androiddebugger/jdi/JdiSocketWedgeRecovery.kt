package com.acendas.androiddebugger.jdi

import com.sun.jdi.VirtualMachine
import org.slf4j.LoggerFactory
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Surgical recovery for stuck JDI calls** (v1.2.4).
 *
 * JDI's blocking I/O does NOT honor `Thread.interrupt()`. A Kotlin coroutine
 * `withTimeoutOrNull` can't cancel a thread blocked inside JDI; submitting to a
 * `Future.get(timeout)` and "abandoning" the worker thread on timeout is what
 * IntelliJ IDEA itself does (see `DebuggerManagerThreadImpl.terminateAndInvoke`,
 * `COMMAND_TIMEOUT = 3000`). That's our baseline — and it's industry standard.
 *
 * What this class adds: when N consecutive JDI calls time out, **forcibly
 * unwedge** the underlying socket. Two-tier escalation:
 *
 *  1. Soft: call `vm.dispose()` via a 2 s `Future` timeout. The Dispose JDWP
 *     packet, if it lands, makes pending `readPacket()` calls throw
 *     `VMDisconnectedException` — the JDI workers exit naturally and the
 *     thread pool reclaims them.
 *  2. Hard: if dispose itself hangs (kernel send buffer full on a wedged adb
 *     forward), call `Socket.close()` on the underlying transport. This
 *     atomically unblocks every blocked I/O on both sides — JDI's
 *     `SocketConnection` synchronizes on `sendLock`/`receiveLock` but neither
 *     lock guards the actual socket syscall, so close() is always reachable.
 *
 * **Reflection plumbing:** we walk
 * `vm` → `VirtualMachineImpl.target` (TargetVM)
 *      → `TargetVM.connection` (SocketConnection — `com.sun.jdi.connect.spi.Connection`)
 *      → `SocketConnection.socket` (`java.net.Socket`).
 *
 * On JDK 9+ `com.sun.tools.jdi` is in module `jdk.jdi` and reflective access
 * to private fields requires `--add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED`
 * on the server JVM (wired into `.mcp.json`). If reflection fails (different
 * JDK, restricted module, alternate JDI implementation), we fall back to the
 * v1.2.3 behavior — leaked worker threads bounded by `Future` timeouts. The
 * `socket` field is null after a failed capture; `escalate()` skips the hard
 * path and just calls `dispose()`.
 *
 * **References:**
 * - [openjdk/jdk SocketTransportService.java](https://github.com/openjdk/jdk/blob/master/src/jdk.jdi/share/classes/com/sun/tools/jdi/SocketTransportService.java)
 * - [JetBrains DebuggerManagerThreadImpl.kt](https://github.com/JetBrains/intellij-community/blob/master/java/debugger/impl/src/com/intellij/debugger/engine/DebuggerManagerThreadImpl.kt)
 * - foojay: "Who Killed the JVM? Attaching a Debugger Twice"
 */
class JdiSocketWedgeRecovery(
    private val vm: VirtualMachine,
    /** The captured underlying socket, or null if reflection failed. */
    val socket: Socket?,
) {

    private val log = LoggerFactory.getLogger("JdiSocketWedgeRecovery")
    private val consecutiveTimeouts = AtomicInteger(0)

    @Volatile private var escalating: Boolean = false
    @Volatile private var escalated: Boolean = false

    /** Caller signals one JDI call has just timed out. Returns `true` if the threshold tripped this call. */
    fun notifyTimeout(): Boolean {
        val n = consecutiveTimeouts.incrementAndGet()
        return n >= TIMEOUT_THRESHOLD
    }

    /** Caller signals one JDI call has just succeeded — reset the streak. */
    fun notifySuccess() {
        consecutiveTimeouts.set(0)
    }

    /**
     * Force the JDI session to unblock. Returns `true` if escalation actually
     * ran (and the session should be considered dead), `false` if a prior
     * caller already escalated. Idempotent and thread-safe.
     */
    @Synchronized
    fun escalate(): Boolean {
        if (escalated) return false
        escalating = true
        log.warn("JDI session wedged — escalating recovery (vm.dispose, then socket.close)")

        // Step 1: try vm.dispose() with a 2s budget. JDI's Dispose packet, if it
        // gets through, propagates VMDisconnectedException to all blocked readers.
        val disposed = runWithBudget(2_000) {
            runCatching { vm.dispose() }.isSuccess
        }
        if (disposed == true) {
            log.warn("JDI session unwedged via vm.dispose()")
            escalated = true
            return true
        }
        log.warn("vm.dispose() didn't return within 2s — falling back to socket.close()")

        // Step 2: hard close the underlying socket. This is the unconditional
        // unblock — kernel-level FIN/RST releases any blocked I/O syscall.
        val sock = socket
        if (sock != null) {
            runCatching { sock.close() }
                .onSuccess { log.warn("JDI socket closed; pending readers will throw VMDisconnectedException") }
                .onFailure { log.warn("Failed to close JDI socket: ${it.message}") }
        } else {
            log.warn("No socket reference captured (reflection failed at attach); leaving leaked workers to drain on JVM exit")
        }
        escalated = true
        return true
    }

    /** Run [block] with a wall-clock budget on a daemon thread; null on timeout. */
    private fun <T> runWithBudget(timeoutMs: Long, block: () -> T): T? {
        val t = Thread { runCatching { block() } }.apply {
            isDaemon = true
            name = "android-debugger-recovery"
            start()
        }
        t.join(timeoutMs)
        return if (t.isAlive) null else block.invoke()
    }

    companion object {
        /** N consecutive JDI-call timeouts before we escalate. */
        private const val TIMEOUT_THRESHOLD = 2

        private val log = LoggerFactory.getLogger("JdiSocketWedgeRecovery")

        /**
         * Capture the underlying socket from a freshly-attached [vm] via reflection.
         * Best-effort — returns a recovery wrapper even on capture failure (the
         * `socket` will be null and the hard-close path becomes a no-op). Per v1.2.4.
         */
        fun captureFor(vm: VirtualMachine): JdiSocketWedgeRecovery {
            val sock = runCatching { extractSocket(vm) }.getOrElse {
                log.warn(
                    "Could not reflect underlying JDI socket (${it.message}). " +
                        "Stuck-call recovery will fall back to vm.dispose only. " +
                        "Wire `--add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED` on the server JVM " +
                        "to enable hard socket-close recovery.",
                )
                null
            }
            return JdiSocketWedgeRecovery(vm, sock)
        }

        /**
         * Walk the OpenJDK JDI internals to extract the `java.net.Socket` backing
         * the JDWP transport. Throws on any access denial; caller catches.
         */
        private fun extractSocket(vm: VirtualMachine): Socket? {
            // VirtualMachineImpl is com.sun.tools.jdi.VirtualMachineImpl on Hotspot.
            val vmImplClass = vm::class.java.let { actual ->
                // Walk up the inheritance chain to find a class named VirtualMachineImpl —
                // some IDE-bundled JDIs subclass it.
                var cls: Class<*>? = actual
                while (cls != null && cls.simpleName != "VirtualMachineImpl") cls = cls.superclass
                cls ?: actual
            }
            val targetField = vmImplClass.getDeclaredField("target").apply { isAccessible = true }
            val target = targetField.get(vm) ?: return null
            val targetVmClass = target::class.java
            val connectionField = targetVmClass.getDeclaredField("connection").apply { isAccessible = true }
            val connection = connectionField.get(target) ?: return null
            val socketConnClass = connection::class.java
            val socketField = socketConnClass.getDeclaredField("socket").apply { isAccessible = true }
            return socketField.get(connection) as? Socket
        }
    }
}
