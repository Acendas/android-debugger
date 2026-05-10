package com.acendas.androiddebugger.inspection

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import com.sun.jdi.ThreadReference
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.mockito.Mockito
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R-08: cover the Evaluator's single-flight refusal. Two concurrent `evaluate` calls —
 * the first acquires the busy flag and holds it (we synchronize on a mock ThreadReference
 * that blocks on `frame(0)`), and the second observes busy = true and immediately throws
 * a structured `ToolError` with [ErrorCode.VmPaused].
 *
 * The Evaluator's [Evaluator.busy] is an [AtomicBoolean]. We don't reach into it directly;
 * instead we cause the first call to suspend deep inside `evalInner` (specifically
 * `thread.frame(frameIdx)`) so the second call's `compareAndSet(false, true)` returns
 * false and we hit the structured-error path.
 */
class EvaluatorReentryTest {

    @Test
    fun second_concurrent_evaluate_returns_vm_paused_error(): Unit = runBlocking {
        // The first thread mock blocks inside frame() until we release `gate`, simulating
        // an in-flight evaluation. The second call should observe busy=true and refuse.
        val gate = Mutex(locked = true)
        val firstStarted = AtomicBoolean(false)
        val blockingThread = Mockito.mock(ThreadReference::class.java)
        Mockito.`when`(blockingThread.isSuspended).thenReturn(true)
        Mockito.`when`(blockingThread.frame(0)).thenAnswer {
            firstStarted.set(true)
            // Block until the test releases the gate. This emulates JDI's invokeMethod
            // path being in flight.
            runBlocking { gate.lock() }
            // Once unblocked, throw to short-circuit further resolution.
            throw IllegalStateException("test gate released — short-circuit")
        }

        val first = async {
            try {
                Evaluator.evaluate(blockingThread, 0, LitExpr(LiteralValue.Int32(1)))
            } catch (_: Throwable) {
                // Either the IllegalStateException above or a wrapped ToolError —
                // we only care about the second call's response.
                null
            }
        }

        // Wait until the first call is actually inside `evalInner`. We poll the
        // `firstStarted` flag with a short delay to avoid a brittle sleep-only test.
        repeat(50) {
            if (firstStarted.get()) return@repeat
            delay(20)
        }
        assertTrue(firstStarted.get(), "first evaluate did not enter evalInner in time")

        // Now the second call. busy is true; we expect immediate ToolError(VmPaused).
        val secondThread = Mockito.mock(ThreadReference::class.java)
        Mockito.`when`(secondThread.isSuspended).thenReturn(true)
        val err = assertFailsWith<ToolError> {
            Evaluator.evaluate(secondThread, 0, LitExpr(LiteralValue.Int32(2)))
        }
        assertEquals(ErrorCode.VmPaused, err.errorCode)
        assertTrue(
            (err.message ?: "").contains("Another evaluation is already in flight"),
            "Expected single-flight refusal message, got: ${err.message}",
        )

        // Release the first call so test cleanup works.
        gate.unlock()
        first.await()
    }
}
