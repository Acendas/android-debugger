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
 * a structured [ToolError] with [ErrorCode.VmPaused].
 *
 * Adapted for v1.3 (Story A.1.5): the public API now takes a raw FEEL expression string
 * (`"1"`) instead of the v1.2 [Expr] AST. The single-flight contract is unchanged.
 */
class EvaluatorReentryTest {

    @Test
    fun second_concurrent_evaluate_returns_vm_paused_error(): Unit = runBlocking {
        val gate = Mutex(locked = true)
        val firstStarted = AtomicBoolean(false)
        val blockingThread = Mockito.mock(ThreadReference::class.java)
        Mockito.`when`(blockingThread.isSuspended).thenReturn(true)
        Mockito.`when`(blockingThread.frame(0)).thenAnswer {
            firstStarted.set(true)
            runBlocking { gate.lock() }
            throw IllegalStateException("test gate released — short-circuit")
        }

        val first = async {
            try {
                Evaluator.evaluate(blockingThread, 0, "1")
            } catch (_: Throwable) {
                // Either the IllegalStateException above or a wrapped ToolError —
                // we only care about the second call's response.
                null
            }
        }

        repeat(50) {
            if (firstStarted.get()) return@repeat
            delay(20)
        }
        assertTrue(firstStarted.get(), "first evaluate did not enter evalInner in time")

        val secondThread = Mockito.mock(ThreadReference::class.java)
        Mockito.`when`(secondThread.isSuspended).thenReturn(true)
        val err = assertFailsWith<ToolError> {
            Evaluator.evaluate(secondThread, 0, "2")
        }
        assertEquals(ErrorCode.VmPaused, err.errorCode)
        assertTrue(
            (err.message ?: "").contains("Another evaluation is already in flight"),
            "Expected single-flight refusal message, got: ${err.message}",
        )

        gate.unlock()
        first.await()
    }
}
