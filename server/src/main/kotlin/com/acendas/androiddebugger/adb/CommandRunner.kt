package com.acendas.androiddebugger.adb

/**
 * Indirection between [Adb] and the OS-level process invocation so unit tests can
 * inject synthetic stdout/stderr/exit codes without spawning a real adb. Per Task 0.1.1.4.
 */
interface CommandRunner {
    /** Run a single adb invocation to completion (or [timeoutMs]) and return the result. */
    fun run(args: List<String>, timeoutMs: Long): AdbResult

    /**
     * Spawn a long-running adb invocation (e.g., `adb logcat`) and pipe each stdout line
     * to [onLine]. The returned [StreamHandle] is the only way to terminate the process.
     */
    fun stream(args: List<String>, onLine: (String) -> Unit): StreamHandle
}

/** Opaque handle to a streaming adb subprocess. Always call [stop] to release the process. */
interface StreamHandle {
    fun stop()
    val alive: Boolean
}
