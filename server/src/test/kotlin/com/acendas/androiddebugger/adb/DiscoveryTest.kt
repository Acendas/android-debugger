package com.acendas.androiddebugger.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTest {

    // ---------- parseDevices ----------

    @Test
    fun parseDevices_handles_no_devices() {
        val stdout = "List of devices attached\n\n"
        assertEquals(emptyList(), DiscoveryParsers.parseDevices(stdout))
    }

    @Test
    fun parseDevices_extracts_serial_state_and_attrs() {
        val stdout = """
            List of devices attached
            emulator-5554          device product:sdk_phone_arm64 model:sdk_phone_arm64 device:generic transport_id:1
            ABCDEF123              device product:Pixel_7 model:Pixel_7 device:panther transport_id:2
        """.trimIndent()
        val result = DiscoveryParsers.parseDevices(stdout)
        assertEquals(2, result.size)
        assertEquals("emulator-5554", result[0].serial)
        assertEquals("device", result[0].state)
        assertEquals("sdk_phone_arm64", result[0].model)
        assertEquals("Pixel_7", result[1].model)
    }

    @Test
    fun parseDevices_keeps_unauthorized_devices_with_state() {
        val stdout = """
            List of devices attached
            ZZZ999                 unauthorized
        """.trimIndent()
        val result = DiscoveryParsers.parseDevices(stdout)
        assertEquals(1, result.size)
        assertEquals("unauthorized", result[0].state)
        assertNull(result[0].model)
    }

    @Test
    fun parseDevices_skips_daemon_status_lines() {
        // Sometimes adb prints "* daemon not running; starting now ..." before the list.
        val stdout = """
            * daemon not running; starting now at tcp:5037 *
            * daemon started successfully *
            List of devices attached
            emulator-5554          device
        """.trimIndent()
        val result = DiscoveryParsers.parseDevices(stdout)
        assertEquals(1, result.size)
        assertEquals("emulator-5554", result[0].serial)
    }

    // ---------- parseJdwpPids ----------

    @Test
    fun parseJdwpPids_extracts_distinct_sorted_ints() {
        val stdout = "12345\n12345\n98765\n  4242 \n\n"
        assertEquals(listOf(4242, 12345, 98765), DiscoveryParsers.parseJdwpPids(stdout))
    }

    @Test
    fun parseJdwpPids_returns_empty_for_blank() {
        assertEquals(emptyList(), DiscoveryParsers.parseJdwpPids(""))
        assertEquals(emptyList(), DiscoveryParsers.parseJdwpPids("\n\n"))
    }

    @Test
    fun parseJdwpPids_ignores_garbage_lines() {
        val stdout = "12345\nnot-a-pid\n67890\nerror: device offline\n"
        assertEquals(listOf(12345, 67890), DiscoveryParsers.parseJdwpPids(stdout))
    }

    // ---------- parsePsOutput ----------

    @Test
    fun parsePsOutput_maps_pid_to_package_for_modern_android() {
        val stdout = """
            USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME
            u0_a210      12345    1   123456  78901 wait                 0 S com.example.app
            u0_a211      56789    1   234567  89012 wait                 0 S com.example.other
            shell        77777    1    12345   6789 wait                 0 S /system/bin/sh
        """.trimIndent()
        val result = DiscoveryParsers.parsePsOutput(stdout, listOf(12345, 56789, 77777))
        assertEquals("com.example.app", result[12345])
        assertEquals("com.example.other", result[56789])
        // shell process is not a package — null so the caller knows we looked but it's not a debuggable
        assertNull(result[77777])
    }

    @Test
    fun parsePsOutput_returns_null_for_unknown_pids() {
        val stdout = """
            USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME
            u0_a210      12345    1   123456  78901 wait                 0 S com.example.app
        """.trimIndent()
        val result = DiscoveryParsers.parsePsOutput(stdout, listOf(12345, 99999))
        assertEquals("com.example.app", result[12345])
        assertNull(result[99999])
        assertTrue(99999 in result.keys)
    }

    @Test
    fun parsePsOutput_handles_empty_pids() {
        assertEquals(emptyMap(), DiscoveryParsers.parsePsOutput("anything", emptyList()))
    }
}
