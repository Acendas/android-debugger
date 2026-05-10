package com.acendas.androiddebugger.android

import com.acendas.androiddebugger.adb.AdbResult
import com.acendas.androiddebugger.adb.CommandRunner
import com.acendas.androiddebugger.adb.StreamHandle
import com.acendas.androiddebugger.adb.Adb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-function tests for the threadtime parser and the per-buffer ring/filter logic.
 * No JDI / device required — see Phase 6 acceptance for the canned-string approach.
 */
class LogcatParserTest {

    // ---------------- ThreadtimeParser ----------------

    @Test
    fun parses_canonical_threadtime_line() {
        val line = "09-15 14:23:45.123  1234  5678 D MyTag: hello world"
        val e = ThreadtimeParser.parse(line, seq = 1L)
        assertNotNull(e)
        assertEquals("09-15 14:23:45.123", e.ts)
        assertEquals(1234, e.pid)
        assertEquals(5678, e.tid)
        assertEquals("D", e.level)
        assertEquals("MyTag", e.tag)
        assertEquals("hello world", e.message)
        assertEquals(1L, e.seq)
    }

    @Test
    fun parses_line_with_variable_whitespace() {
        // Single-space, tab, and multi-space variants all need to parse.
        val variants = listOf(
            "09-15 14:23:45.123 25342 25342 W ActivityManager: foo bar baz",
            "09-15 14:23:45.123\t1234\t5678\tE\tAndroidRuntime: FATAL",
            "09-15 14:23:45.123  1234  5678 I tag-with-dashes: msg",
        )
        for (line in variants) {
            val e = ThreadtimeParser.parse(line, seq = 0L)
            assertNotNull(e, "failed: $line")
        }
    }

    @Test
    fun parses_each_log_level_letter() {
        for (lvl in listOf("V", "D", "I", "W", "E", "F", "A")) {
            val line = "09-15 14:23:45.123  1234  5678 $lvl T: m"
            val e = ThreadtimeParser.parse(line, 0L)
            assertNotNull(e)
            assertEquals(lvl, e.level)
        }
    }

    @Test
    fun parses_message_containing_colons_and_braces() {
        val line = "09-15 14:23:45.123  1234  5678 W My.Tag: error: java.lang.IllegalStateException at Foo.bar:42"
        val e = ThreadtimeParser.parse(line, 0L)
        assertNotNull(e)
        assertEquals("My.Tag", e.tag)
        assertEquals("error: java.lang.IllegalStateException at Foo.bar:42", e.message)
    }

    @Test
    fun parses_empty_message_after_tag() {
        // Tag with no body — pretty rare in logcat but valid.
        val line = "09-15 14:23:45.123  1234  5678 D MyTag: "
        val e = ThreadtimeParser.parse(line, 0L)
        assertNotNull(e)
        assertEquals("MyTag", e.tag)
        // The `: ` separator is consumed; the message starts empty after it.
        assertEquals("", e.message)
    }

    @Test
    fun rejects_logcat_buffer_header_lines() {
        assertNull(ThreadtimeParser.parse("--------- beginning of main", 0L))
        assertNull(ThreadtimeParser.parse("--------- beginning of crash", 0L))
        assertNull(ThreadtimeParser.parse("--------- beginning of system", 0L))
    }

    @Test
    fun rejects_garbage_lines() {
        assertNull(ThreadtimeParser.parse("", 0L))
        assertNull(ThreadtimeParser.parse("not a log line", 0L))
        assertNull(ThreadtimeParser.parse("09-15 14:23:45.123 abc def G T: m", 0L)) // pid/tid not int
    }

    // ---------------- LogcatBuffer (ring + filter) ----------------

    /** Trivial fake CommandRunner so LogcatBuffer.start() doesn't try to spawn a real adb. */
    private class FakeRunner : CommandRunner {
        override fun run(args: List<String>, timeoutMs: Long): AdbResult = AdbResult.Success("")
        override fun stream(args: List<String>, onLine: (String) -> Unit): StreamHandle = object : StreamHandle {
            override fun stop() = Unit
            override val alive: Boolean = true
        }
    }

    private fun newBuffer(filter: String? = null, regex: String? = null, max: Int = 8): LogcatBuffer {
        val adb = Adb(FakeRunner())
        return LogcatRegistry().create(
            adb = adb,
            serial = null,
            pidFilter = null,
            filter = filter,
            regex = regex,
            sinceArg = null,
            maxBuffer = max,
        )
    }

    @Test
    fun ring_buffer_evicts_oldest_when_full() {
        val buf = newBuffer(max = 50)
        repeat(120) { i ->
            buf.pushForTest("09-15 14:23:45.123  1234  5678 I T: line $i")
        }
        val all = buf.readSince(0L)
        assertEquals(50, all.size)
        // Oldest seen should be "line 70" (entries 0..69 were evicted to make room).
        assertEquals("line 70", all.first().message)
        assertEquals("line 119", all.last().message)
    }

    @Test
    fun read_since_returns_only_newer_entries() {
        val buf = newBuffer()
        buf.pushForTest("09-15 14:23:45.123  1 1 D T: a")
        buf.pushForTest("09-15 14:23:45.123  1 1 D T: b")
        val first = buf.readSince(0L)
        assertEquals(2, first.size)
        // Push more, read since the seq we already saw.
        buf.pushForTest("09-15 14:23:45.123  1 1 D T: c")
        val cursor = first.last().seq
        val newer = buf.readSince(cursor)
        assertEquals(1, newer.size)
        assertEquals("c", newer.first().message)
    }

    @Test
    fun tag_filter_drops_non_matching_lines() {
        val buf = newBuffer(filter = "Wanted", max = 100)
        buf.pushForTest("09-15 14:23:45.123  1 1 D Wanted: yes")
        buf.pushForTest("09-15 14:23:45.123  1 1 D Other: no")
        buf.pushForTest("09-15 14:23:45.123  1 1 D Wanted: also yes")
        val out = buf.readSince(0L)
        assertEquals(2, out.size)
        assertTrue(out.all { it.tag == "Wanted" })
    }

    @Test
    fun regex_filter_matches_against_message() {
        val buf = newBuffer(regex = """error \w+""", max = 100)
        buf.pushForTest("09-15 14:23:45.123  1 1 W T: mostly fine")
        buf.pushForTest("09-15 14:23:45.123  1 1 W T: error code 42")
        buf.pushForTest("09-15 14:23:45.123  1 1 W T: also error things")
        val out = buf.readSince(0L)
        assertEquals(2, out.size)
        assertTrue(out.all { it.message.contains("error") })
    }

    @Test
    fun stop_marks_buffer_as_not_alive_and_is_idempotent() {
        val buf = newBuffer()
        assertTrue(buf.snapshot().alive)
        buf.stop()
        assertFalse(buf.snapshot().alive)
        buf.stop() // idempotent
        assertFalse(buf.snapshot().alive)
    }
}
