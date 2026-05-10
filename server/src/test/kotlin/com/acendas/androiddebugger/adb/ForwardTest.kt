package com.acendas.androiddebugger.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForwardTest {

    private class RecordingRunner(private val resultStream: ArrayDeque<AdbResult>) : CommandRunner {
        val invocations: MutableList<List<String>> = mutableListOf()
        override fun run(args: List<String>, timeoutMs: Long): AdbResult {
            invocations += args
            return resultStream.removeFirstOrNull() ?: AdbResult.Success("")
        }
        override fun stream(args: List<String>, onLine: (String) -> Unit): StreamHandle = error("not used")
    }

    @Test
    fun forwardJdwp_invokes_adb_forward_and_records_for_cleanup() {
        val runner = RecordingRunner(ArrayDeque(listOf(AdbResult.Success(""))))
        val adb = Adb(runner = runner, portPicker = { 50_123 })

        val port = adb.forwardJdwp(serial = "emulator-5554", pid = 12345)

        assertEquals(50_123, port)
        assertEquals(
            listOf(listOf("-s", "emulator-5554", "forward", "tcp:50123", "jdwp:12345")),
            runner.invocations,
        )
        assertTrue(("emulator-5554" to 50_123) in adb.activeForwards())
    }

    @Test
    fun forwardJdwp_omits_dash_s_when_serial_is_null() {
        val runner = RecordingRunner(ArrayDeque(listOf(AdbResult.Success(""))))
        val adb = Adb(runner = runner, portPicker = { 60_000 })

        adb.forwardJdwp(serial = null, pid = 999)

        assertEquals(listOf(listOf("forward", "tcp:60000", "jdwp:999")), runner.invocations)
    }

    @Test
    fun forwardJdwp_returns_null_and_doesnt_track_on_adb_failure() {
        val err = AdbResult.Error(1, "", "device offline", listOf("adb"))
        val runner = RecordingRunner(ArrayDeque(listOf(err)))
        val adb = Adb(runner = runner, portPicker = { 50_000 })

        val port = adb.forwardJdwp("S1", 1)

        assertNull(port)
        assertTrue(adb.activeForwards().isEmpty())
    }

    @Test
    fun removeForward_invokes_adb_forward_remove_and_drops_tracking_entry() {
        val runner = RecordingRunner(
            ArrayDeque(listOf(AdbResult.Success(""), AdbResult.Success(""))),
        )
        val adb = Adb(runner = runner, portPicker = { 51_000 })

        adb.forwardJdwp("S1", 100)
        val ok = adb.removeForward("S1", 51_000)

        assertTrue(ok)
        assertFalse(("S1" to 51_000) in adb.activeForwards())
        assertEquals(
            listOf("-s", "S1", "forward", "--remove", "tcp:51000"),
            runner.invocations[1],
        )
    }

    @Test
    fun releaseAllForwards_removes_each_tracked_forward() {
        // 3 successful forwards + 3 successful removes
        val runner = RecordingRunner(
            ArrayDeque(MutableList(6) { AdbResult.Success("") }),
        )
        var nextPort = 52_000
        val adb = Adb(runner = runner, portPicker = { nextPort++ })

        adb.forwardJdwp("S1", 1)
        adb.forwardJdwp("S1", 2)
        adb.forwardJdwp(null, 3)

        adb.releaseAllForwards()

        assertTrue(adb.activeForwards().isEmpty())
        // last 3 invocations should all be `forward --remove ...`
        val removes = runner.invocations.takeLast(3)
        assertTrue(removes.all { "--remove" in it })
    }
}
