package com.acendas.androiddebugger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cases reproduced from the AMDB kiosk run (i.MX6, Android 8.0, Compose) on 2026-09-26.
 * Each one was a live bug in the tick rule before it was pinned here.
 */
class ScreenDifferTest {

    private val pkg = "com.example.kiosk"
    private val home = listOf("KIOSK2", "Sep 26, 2026 1:25 AM", "One Way Ticket", "0", "$0.00", "Pay")

    private fun ScreenDiffer.seed(screen: List<String>, at: Long = 0) = observe(pkg, screen, at)

    @Test
    fun `first read is a baseline with everything added`() {
        val c = assertNotNull(ScreenDiffer().seed(home))
        assertTrue(c.first)
        assertEquals(home, c.added)
        assertEquals(emptyList(), c.removed)
    }

    @Test
    fun `identical read publishes nothing`() {
        val d = ScreenDiffer()
        d.seed(home, 0)
        assertNull(d.observe(pkg, home, 100))
    }

    @Test
    fun `navigation reports added and removed text`() {
        val d = ScreenDiffer()
        d.seed(home, 0)
        val terms = listOf("KIOSK2", "Sep 26, 2026 1:25 AM", "Tickets expire 90 days", "Cash", "Back")
        val c = assertNotNull(d.observe(pkg, terms, 5_000))
        assertEquals(listOf("Tickets expire 90 days", "Cash", "Back"), c.added)
        assertEquals(listOf("One Way Ticket", "0", "$0.00", "Pay"), c.removed)
        assertEquals(false, c.digitsOnly)
    }

    @Test
    fun `two quick plus taps both report when input clears suppression`() {
        val d = ScreenDiffer()
        d.seed(home, 0)
        d.onInput()
        val one = home.map { if (it == "0") "1" else if (it == "$0.00") "$4.75" else it }
        val c1 = assertNotNull(d.observe(pkg, one, 1_000))
        assertTrue(c1.digitsOnly)
        d.onInput()
        val two = home.map { if (it == "0") "2" else if (it == "$0.00") "$9.50" else it }
        // 1.2 s later: inside the tick window, but we caused it.
        val c2 = assertNotNull(d.observe(pkg, two, 2_200))
        assertEquals(listOf("2", "$9.50"), c2.added)
        assertEquals(listOf("1", "$4.75"), c2.removed)
    }

    @Test
    fun `countdown reports the first tick then suppresses and counts the rest`() {
        val d = ScreenDiffer()
        fun dialog(n: Int) = listOf("${n}s", "This session will expire", "Dismiss")
        d.seed(dialog(15), 0)
        assertNotNull(d.observe(pkg, dialog(14), 1_000), "first tick reports")
        for ((i, n) in (13 downTo 1).withIndex()) {
            assertNull(d.observe(pkg, dialog(n), 2_000L + i * 1_000), "tick $n suppressed")
        }
        val homeAgain = assertNotNull(d.observe(pkg, home, 16_000))
        assertEquals(13, homeAgain.suppressedTicksBefore)
    }

    @Test
    fun `ten to nine does not break the countdown chain`() {
        val d = ScreenDiffer()
        fun dialog(n: Int) = listOf("Returning to home in $n seconds")
        d.seed(dialog(11), 0)
        assertNotNull(d.observe(pkg, dialog(10), 1_000))
        assertNull(d.observe(pkg, dialog(9), 2_000), "10 -> 9 is the same digit-run pattern")
        assertNull(d.observe(pkg, dialog(8), 3_000))
    }

    @Test
    fun `clock minute tick mid-countdown does not reset countdown suppression`() {
        val d = ScreenDiffer()
        fun screen(sec: Int, clock: String) = listOf(clock, "${sec}s", "Dismiss")
        d.seed(screen(15, "1:21 AM"), 0)
        assertNotNull(d.observe(pkg, screen(14, "1:21 AM"), 1_000))
        assertNull(d.observe(pkg, screen(13, "1:21 AM"), 2_000))
        // Clock and countdown change in separate settles.
        assertNotNull(d.observe(pkg, screen(13, "1:22 AM"), 2_400), "sparse clock tick reports")
        assertNull(d.observe(pkg, screen(12, "1:22 AM"), 3_000), "countdown still suppressed")
    }

    @Test
    fun `sparse digit change still reports`() {
        val d = ScreenDiffer()
        d.seed(listOf("1:21 AM"), 0)
        assertNotNull(d.observe(pkg, listOf("1:22 AM"), 60_000))
        assertNotNull(d.observe(pkg, listOf("1:23 AM"), 120_000))
    }

    @Test
    fun `package change with identical text still reports`() {
        val d = ScreenDiffer()
        d.observe("a", listOf("OK"), 0)
        val c = assertNotNull(d.observe("b", listOf("OK"), 10))
        assertEquals("b", c.packageName)
    }

    @Test
    fun `tickKey masks digit runs and requires equal counts`() {
        assertEquals("#s", ScreenDiffer.tickKey(listOf("9s"), listOf("10s")))
        assertNull(ScreenDiffer.tickKey(listOf("Cash"), listOf("Card")))
        assertNull(ScreenDiffer.tickKey(listOf("1", "2"), listOf("1")))
        assertNull(ScreenDiffer.tickKey(emptyList(), emptyList()))
    }
}
