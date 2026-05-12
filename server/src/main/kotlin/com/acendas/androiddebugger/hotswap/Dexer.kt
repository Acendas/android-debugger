package com.acendas.androiddebugger.hotswap

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import com.android.tools.r8.ByteDataView
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DexIndexedConsumer
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.origin.Origin
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Compile a single JVM `.class` byte array into a single-class DEX blob suitable
 * for handing to ART's `RedefineClasses` via the JVMTI agent.
 *
 * Per v1.5 spec §6 — embedded r8/d8 pipeline.
 *
 * Why a class shows up as a single .dex file:
 *   - ART's `RedefineClasses` reads the bytes via the same dex-container parser
 *     it uses for APK loading. Pass JVM bytecode and it rejects with a parser
 *     error. We must hand it a fully-formed dex container whose only class is
 *     the redefined one.
 *   - d8 (the dexer half of r8) does exactly that — class file in, dex file out.
 *     Calling it with one class produces a `classes.dex` containing one class.
 *
 * Threading: d8 is reentrant, but every call spins up its own internal worker
 * pool. We serialize on [dexLock] so a HotSwap-of-batch doesn't fan out a dozen
 * parallel d8 invocations and trash JVM file handles. Single call latency on a
 * typical Kotlin class: 5–50 ms.
 */
class Dexer(private val apiLevel: Int) {

    init {
        require(apiLevel in 1..99) { "apiLevel must be 1..99 (got $apiLevel)" }
    }

    /**
     * Dex a single `.class` file. The returned bytes are a complete DEX container
     * (header + maps + one class) ready for `jvmtiClassDefinition.class_bytes`.
     *
     * @param classBytes Raw JVM bytecode from kotlinc/javac (`.class` file contents).
     * @param fqn Fully-qualified class name in dot notation. Used only for error reporting.
     * @return DEX bytes. Never returns an empty array — throws if d8 produced no output.
     * @throws ToolError with [ErrorCode.InvalidTarget] on d8 compilation failure.
     */
    fun dexSingleClass(classBytes: ByteArray, fqn: String): ByteArray = dexLock.withLock {
        val collector = ErrorCollectingDiagnostics()
        val consumer = SingleClassConsumer()
        val command = D8Command.builder(collector)
            .addClassProgramData(classBytes, Origin.unknown())
            .setMinApiLevel(apiLevel)
            .setMode(CompilationMode.DEBUG)
            .setProgramConsumer(consumer)
            .setDisableDesugaring(true)
            .build()
        try {
            D8.run(command)
        } catch (t: Throwable) {
            val msg = collector.errorMessages().ifEmpty { listOf(t.message ?: t::class.java.simpleName) }
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Failed to dex class `$fqn`: ${msg.joinToString("; ")}",
                hint = "Confirm the .class bytes were produced by kotlinc/javac for API level $apiLevel.",
            )
        }
        if (collector.hasErrors()) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "d8 reported errors dexing `$fqn`: ${collector.errorMessages().joinToString("; ")}",
            )
        }
        consumer.bytes ?: throw ToolError(
            errorCode = ErrorCode.Internal,
            message = "d8 produced no output for `$fqn` (no warnings/errors logged either).",
        )
    }

    companion object {
        /** Serializes d8 invocations across all Dexer instances in this process. */
        private val dexLock = ReentrantLock()
    }
}

/**
 * Captures the bytes from d8's single output dex file. d8 invokes [accept] once
 * per produced dex file; for single-class input we expect exactly one call with
 * `fileIndex == 0`.
 */
private class SingleClassConsumer : DexIndexedConsumer {
    @Volatile var bytes: ByteArray? = null

    override fun accept(
        fileIndex: Int,
        data: ByteDataView,
        descriptors: MutableSet<String>?,
        handler: DiagnosticsHandler?,
    ) {
        // Copy out — ByteDataView wraps an internal buffer that may be recycled.
        bytes = data.copyByteData()
    }

    override fun finished(handler: DiagnosticsHandler?) {
        // No-op. The bytes were captured in accept().
    }
}

/**
 * Surfaces d8 diagnostic output. d8 reports parser/verifier issues via
 * [DiagnosticsHandler.error]; we collect them so the Dexer error path can show
 * the actual reason instead of a generic "d8 failed".
 */
private class ErrorCollectingDiagnostics : DiagnosticsHandler {
    private val errors = mutableListOf<String>()

    override fun error(diagnostic: Diagnostic) {
        errors.add(diagnostic.diagnosticMessage)
    }

    override fun warning(diagnostic: Diagnostic) {
        // Warnings non-fatal; not surfaced to the caller (a real-world Kotlin .class
        // produces a handful of "synthetic method" warnings on every dex).
    }

    override fun info(diagnostic: Diagnostic) {
        // Discarded — info-level chatter is noise for the agent.
    }

    fun hasErrors(): Boolean = errors.isNotEmpty()
    fun errorMessages(): List<String> = errors.toList()
}
