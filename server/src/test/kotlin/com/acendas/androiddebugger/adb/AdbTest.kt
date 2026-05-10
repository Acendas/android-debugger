package com.acendas.androiddebugger.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [Adb]. The point of routing every call through [CommandRunner] is
 * exactly so tests like these can pass without a real adb on PATH. Per Task 0.1.1.4.
 */
class AdbTest {

    private class FakeRunner(
        private val results: List<AdbResult>,
        private val streamLines: List<String> = emptyList(),
    ) : CommandRunner {
        val invocations: MutableList<List<String>> = mutableListOf()
        var streamStopped: Boolean = false
            private set
        private var index = 0

        override fun run(args: List<String>, timeoutMs: Long): AdbResult {
            invocations += args
            return results[index++.coerceAtMost(results.lastIndex)]
        }

        override fun stream(args: List<String>, onLine: (String) -> Unit): StreamHandle {
            invocations += args
            streamLines.forEach(onLine)
            return object : StreamHandle {
                override fun stop() { streamStopped = true }
                override val alive: Boolean = !streamStopped
            }
        }
    }

    @Test
    fun runText_passes_args_to_runner_and_returns_success() {
        val runner = FakeRunner(listOf(AdbResult.Success("hello\n")))
        val adb = Adb(runner)

        val result = adb.runText(listOf("devices"))

        assertIs<AdbResult.Success>(result)
        assertEquals("hello\n", result.stdout)
        assertEquals(listOf(listOf("devices")), runner.invocations)
    }

    @Test
    fun runText_surfaces_non_zero_exit_as_Error_without_throwing() {
        val cmd = listOf("shell", "pm", "path", "com.example")
        val runner = FakeRunner(
            listOf(
                AdbResult.Error(
                    exitCode = 1,
                    stdout = "",
                    stderr = "Error: Could not access the Package Manager. Is the system running?",
                    command = listOf("/usr/local/bin/adb") + cmd,
                ),
            ),
        )
        val adb = Adb(runner)

        val result = adb.runText(cmd)

        assertIs<AdbResult.Error>(result)
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Package Manager"))
    }

    @Test
    fun runText_surfaces_timeout_distinctly_from_error() {
        val runner = FakeRunner(
            listOf(AdbResult.Timeout(partialStdout = "partial\n", command = listOf("logcat", "-d"))),
        )
        val adb = Adb(runner)

        val result = adb.runText(listOf("logcat", "-d"), timeoutMs = 50)

        assertIs<AdbResult.Timeout>(result)
        assertEquals("partial\n", result.partialStdout)
    }

    @Test
    fun runText_surfaces_NotFound_when_runner_cant_locate_adb() {
        val runner = FakeRunner(listOf(AdbResult.NotFound("adb not found.")))
        val adb = Adb(runner)

        val result = adb.runText(listOf("devices"))

        assertIs<AdbResult.NotFound>(result)
        assertTrue(result.hint.contains("adb"))
    }

    @Test
    fun runLines_extension_splits_Success_stdout_into_trimmed_nonblank_lines() {
        val raw = "  alpha  \n\nbeta\n   \ngamma\n"
        val result: AdbResult = AdbResult.Success(raw)

        assertEquals(listOf("alpha", "beta", "gamma"), result.lines())
    }

    @Test
    fun runLines_extension_returns_empty_for_non_success() {
        val err: AdbResult = AdbResult.Error(1, "", "boom", listOf("adb"))
        val timeout: AdbResult = AdbResult.Timeout("", listOf("adb"))
        val notFound: AdbResult = AdbResult.NotFound("hint")

        assertEquals(emptyList(), err.lines())
        assertEquals(emptyList(), timeout.lines())
        assertEquals(emptyList(), notFound.lines())
    }

    @Test
    fun runStream_pipes_lines_to_callback_and_handle_reports_alive_until_stopped() {
        val runner = FakeRunner(results = emptyList(), streamLines = listOf("L1", "L2", "L3"))
        val adb = Adb(runner)
        val collected = mutableListOf<String>()

        val handle = adb.runStream(listOf("logcat")) { collected += it }

        assertEquals(listOf("L1", "L2", "L3"), collected)
        assertTrue(handle.alive)

        handle.stop()
        assertTrue(runner.streamStopped)
    }
}
