package com.acendas.androiddebugger.adb

import com.acendas.androiddebugger.AdbLocator
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Real [CommandRunner] backed by [ProcessBuilder]. Drains stdout + stderr on dedicated
 * threads to avoid the pipe-fills-and-blocks deadlock that's the classic Java
 * subprocess gotcha. Per Task 0.1.1.2.
 */
class DefaultCommandRunner(
    private val adbPathProvider: () -> String? = AdbLocator::find,
    private val ioPool: ExecutorService = sharedIoPool,
) : CommandRunner {

    override fun run(args: List<String>, timeoutMs: Long): AdbResult {
        val adb = adbPathProvider() ?: return AdbResult.NotFound(adbNotFoundHint())
        val command = listOf(adb) + args
        val pb = ProcessBuilder(command).redirectErrorStream(false)
        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            return AdbResult.LaunchFailed(t, command)
        }
        val stdoutFuture: Future<String> = ioPool.submit(Callable { drainToString(proc.inputStream) })
        val stderrFuture: Future<String> = ioPool.submit(Callable { drainToString(proc.errorStream) })
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            // Best-effort drain of whatever made it out before we killed it.
            val partial = runCatching { stdoutFuture.get(500, TimeUnit.MILLISECONDS) }.getOrDefault("")
            stderrFuture.cancel(true)
            return AdbResult.Timeout(partial, command)
        }
        val stdout = runCatching { stdoutFuture.get(2000, TimeUnit.MILLISECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(2000, TimeUnit.MILLISECONDS) }.getOrDefault("")
        val exit = proc.exitValue()
        return if (exit == 0) {
            AdbResult.Success(stdout)
        } else {
            AdbResult.Error(exit, stdout, stderr, command)
        }
    }

    override fun stream(args: List<String>, onLine: (String) -> Unit): StreamHandle {
        val adb = adbPathProvider() ?: return DeadHandle
        val command = listOf(adb) + args
        val proc = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            return DeadHandle
        }
        val reader = Thread({
            try {
                proc.inputStream.bufferedReader().useLines { seq ->
                    for (line in seq) onLine(line)
                }
            } catch (_: Throwable) {
                // Process killed or stream closed — exit the reader cleanly.
            }
        }, "adb-stream-${runCatching { proc.pid() }.getOrDefault(0)}")
        reader.isDaemon = true
        reader.start()
        // Drain stderr so it doesn't fill up and block the child.
        val stderrDrain = Thread({
            runCatching { proc.errorStream.bufferedReader().useLines { it.toList() } }
        }, "adb-stream-stderr")
        stderrDrain.isDaemon = true
        stderrDrain.start()
        return ProcessStreamHandle(proc, reader)
    }

    private fun drainToString(stream: InputStream): String =
        stream.bufferedReader().use { it.readText() }

    private fun adbNotFoundHint(): String =
        "adb not found. Set ADB_PATH or ANDROID_HOME/ANDROID_SDK_ROOT, or add adb (adb.exe on Windows) to PATH."

    private class ProcessStreamHandle(
        private val proc: Process,
        private val reader: Thread,
    ) : StreamHandle {
        override fun stop() {
            runCatching { proc.destroyForcibly() }
            reader.interrupt()
        }
        override val alive: Boolean get() = proc.isAlive
    }

    private object DeadHandle : StreamHandle {
        override fun stop() = Unit
        override val alive: Boolean = false
    }

    companion object {
        private val sharedIoPool: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "adb-io").apply { isDaemon = true }
        }
    }
}
