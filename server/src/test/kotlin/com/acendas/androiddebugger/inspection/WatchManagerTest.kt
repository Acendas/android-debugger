package com.acendas.androiddebugger.inspection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure registry behavior of [WatchManager] — id minting, removal, list ordering, clear.
 * Evaluation paths require JDI and are exercised end-to-end via the snapshot integration
 * tests once Phase 3 breakpoints are wired up against a sample app.
 */
class WatchManagerTest {

    @Test
    fun add_returns_monotonically_increasing_ids_starting_at_one() {
        val wm = WatchManager()
        val a = wm.add("x")
        val b = wm.add("y")
        val c = wm.add("z")
        assertEquals(1, a)
        assertEquals(2, b)
        assertEquals(3, c)
        assertEquals(3, wm.size())
    }

    @Test
    fun remove_returns_true_for_known_id_false_for_unknown() {
        val wm = WatchManager()
        val id = wm.add("x")
        assertTrue(wm.remove(id))
        assertFalse(wm.remove(id))           // already gone
        assertFalse(wm.remove(9999))         // never existed
    }

    @Test
    fun list_returns_watches_in_registration_order() {
        val wm = WatchManager()
        wm.add("first")
        wm.add("second")
        wm.add("third")
        val list = wm.list()
        assertEquals(listOf("first", "second", "third"), list.map { it.expr })
        // Ids strictly increasing.
        for (i in 1 until list.size) {
            assertTrue(list[i].id > list[i - 1].id)
        }
    }

    @Test
    fun clear_drops_all_watches_and_resets_id_counter() {
        val wm = WatchManager()
        wm.add("a")
        wm.add("b")
        wm.clear()
        assertEquals(0, wm.size())
        // After clear, ids restart at 1.
        assertEquals(1, wm.add("c"))
    }

    @Test
    fun list_snapshot_decouples_from_internal_state() {
        val wm = WatchManager()
        wm.add("a")
        val snap = wm.list()
        wm.add("b")
        // Snapshot taken before "b" should not see it.
        assertEquals(listOf("a"), snap.map { it.expr })
    }
}
