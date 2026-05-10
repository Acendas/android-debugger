package com.acendas.androiddebugger.adb

import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Façade over [CommandRunner] that names the three call shapes adb invocations
 * fall into: a small `runText` for one-shot output, a convenience `runLines` for
 * line-oriented output (`adb devices`, `adb jdwp`), and `runStream` for long-running
 * commands (`adb logcat`). Per Task 0.1.1.1.
 *
 * [portPicker] is injectable so unit tests can deterministically check the forward path.
 */
class Adb(
    private val runner: CommandRunner = DefaultCommandRunner(),
    private val portPicker: () -> Int = { ServerSocket(0).use { it.localPort } },
) {

    /** Story 0.1.3: track active forwards so [releaseAllForwards] can clean them up. */
    private val forwards: MutableSet<Pair<String?, Int>> = ConcurrentHashMap.newKeySet()

    fun runText(args: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): AdbResult =
        runner.run(args, timeoutMs)

    fun runLines(args: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): AdbResult =
        runner.run(args, timeoutMs)

    fun runStream(args: List<String>, onLine: (String) -> Unit): StreamHandle =
        runner.stream(args, onLine)

    /** Story 0.1.2: list devices/emulators visible to adb. Empty list when adb fails or none connected. */
    fun listDevices(): List<AdbDevice> {
        val r = runText(listOf("devices", "-l"), timeoutMs = 5_000)
        return when (r) {
            is AdbResult.Success -> DiscoveryParsers.parseDevices(r.stdout)
            else -> emptyList()
        }
    }

    /**
     * Story 0.1.2: list debuggable PIDs on a device. `adb jdwp` streams PIDs and may not exit on
     * its own — we cap with a short timeout and parse whatever made it out before the kill.
     */
    fun listJdwpPids(serial: String? = null, timeoutMs: Long = 1_000): List<Int> {
        val args = buildList {
            if (serial != null) { add("-s"); add(serial) }
            add("jdwp")
        }
        val r = runText(args, timeoutMs)
        val text = when (r) {
            is AdbResult.Success -> r.stdout
            is AdbResult.Timeout -> r.partialStdout
            else -> return emptyList()
        }
        return DiscoveryParsers.parseJdwpPids(text)
    }

    /** Story 0.1.2: map a list of PIDs to the package they belong to (or null for kernel/system). */
    fun mapPidsToPackages(serial: String?, pids: List<Int>): Map<Int, String?> {
        if (pids.isEmpty()) return emptyMap()
        val args = buildList {
            if (serial != null) { add("-s"); add(serial) }
            add("shell"); add("ps"); add("-A")
        }
        val r = runText(args, timeoutMs = 5_000)
        return when (r) {
            is AdbResult.Success -> DiscoveryParsers.parsePsOutput(r.stdout, pids)
            else -> pids.associateWith { null }
        }
    }

    /** Story 0.1.3: pick a free local TCP port via the injected picker. */
    fun pickFreePort(): Int = portPicker()

    /**
     * Story 0.1.3: forward a free local TCP port to the JDWP port of [pid]. Returns the
     * chosen port on success, null on adb failure. The forward is tracked for cleanup.
     */
    fun forwardJdwp(serial: String?, pid: Int): Int? {
        val port = portPicker()
        val args = buildList {
            if (serial != null) { add("-s"); add(serial) }
            add("forward"); add("tcp:$port"); add("jdwp:$pid")
        }
        val r = runText(args, timeoutMs = 5_000)
        return if (r is AdbResult.Success) {
            forwards.add(serial to port)
            port
        } else {
            null
        }
    }

    /**
     * Story 0.1.3: remove a previously created forward. Idempotent — if adb reports the
     * forward doesn't exist, we still drop it from our tracking set.
     */
    fun removeForward(serial: String?, port: Int): Boolean {
        val args = buildList {
            if (serial != null) { add("-s"); add(serial) }
            add("forward"); add("--remove"); add("tcp:$port")
        }
        val r = runText(args, timeoutMs = 3_000)
        forwards.remove(serial to port)
        return r is AdbResult.Success
    }

    /** Story 0.1.3: release every forward we created. Called from the JVM shutdown hook. */
    fun releaseAllForwards() {
        val snapshot = forwards.toList()
        for ((serial, port) in snapshot) {
            removeForward(serial, port)
        }
    }

    /** Test-only inspection of the live forwards set. */
    internal fun activeForwards(): Set<Pair<String?, Int>> = forwards.toSet()

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L
    }
}

/** Convenience: when an [AdbResult] represents a successful line-oriented call, get the lines. */
fun AdbResult.lines(): List<String> = when (this) {
    is AdbResult.Success -> stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    else -> emptyList()
}
