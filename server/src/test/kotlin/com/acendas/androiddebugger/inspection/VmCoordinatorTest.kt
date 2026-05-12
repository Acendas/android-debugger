package com.acendas.androiddebugger.inspection

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VmCoordinatorTest {

    @Test
    fun second_caller_fails_fast_with_vm_busy(): Unit = runBlocking {
        val gate = Mutex(locked = true)
        val firstStarted = AtomicBoolean(false)

        val first = async {
            VmCoordinator.withExclusiveAccess("first_op", timeoutMs = 30_000) {
                firstStarted.set(true)
                gate.lock()  // hold the coordinator until the test releases
                "first-done"
            }
        }
        // Wait for the first to actually enter the body.
        repeat(50) {
            if (firstStarted.get()) return@repeat
            delay(20)
        }
        assertTrue(firstStarted.get())

        val err = assertFailsWith<ToolError> {
            VmCoordinator.withExclusiveAccess("second_op", timeoutMs = 100) {
                "should never run"
            }
        }
        assertEquals(ErrorCode.VmBusy, err.errorCode)
        assertEquals("first_op", err.currentState)

        gate.unlock()
        assertEquals("first-done", first.await())
    }

    @Test
    fun release_after_body_completes(): Unit = runBlocking {
        val r1 = VmCoordinator.withExclusiveAccess("op1", timeoutMs = 1_000) { 1 }
        assertEquals(1, r1)
        val r2 = VmCoordinator.withExclusiveAccess("op2", timeoutMs = 1_000) { 2 }
        assertEquals(2, r2)
        assertNull(VmCoordinator.currentOperation(), "currentOperation should clear after each body")
    }

    @Test
    fun body_timeout_releases_lock_and_can_run_again(): Unit = runBlocking {
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            VmCoordinator.withExclusiveAccess("slow", timeoutMs = 100) {
                delay(5_000)
            }
        }
        // Lock should be released; next call succeeds quickly.
        val r = VmCoordinator.withExclusiveAccess("fast", timeoutMs = 1_000) { 42 }
        assertEquals(42, r)
        assertNull(VmCoordinator.currentOperation())
    }

    @Test
    fun current_operation_reflects_in_flight_name(): Unit = runBlocking {
        val gate = Mutex(locked = true)
        val started = AtomicBoolean(false)
        val job = async {
            VmCoordinator.withExclusiveAccess("named_op", timeoutMs = 30_000) {
                started.set(true)
                gate.lock()
            }
        }
        repeat(50) {
            if (started.get()) return@repeat
            delay(20)
        }
        assertEquals("named_op", VmCoordinator.currentOperation())
        gate.unlock()
        job.await()
        assertNull(VmCoordinator.currentOperation())
    }
}
