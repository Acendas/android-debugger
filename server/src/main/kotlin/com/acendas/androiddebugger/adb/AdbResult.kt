package com.acendas.androiddebugger.adb

/**
 * Result of a synchronous adb invocation. Sealed so callers must pattern-match —
 * non-zero exits never throw; they surface as [Error]. Per Task 0.1.1.3.
 */
sealed class AdbResult {
    data class Success(val stdout: String) : AdbResult()
    data class Error(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val command: List<String>,
    ) : AdbResult()
    data class Timeout(val partialStdout: String, val command: List<String>) : AdbResult()
    data class NotFound(val hint: String) : AdbResult()
    data class LaunchFailed(val cause: Throwable, val command: List<String>) : AdbResult()
}
