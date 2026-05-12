package com.acendas.androiddebugger.hotswap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnapshotStoreTest {

    @Test
    fun first_capture_wins_subsequent_returns_existing() {
        val store = SnapshotStore()
        val first = store.captureIfAbsent("com.example.Foo", byteArrayOf(1, 2, 3))
        val second = store.captureIfAbsent("com.example.Foo", byteArrayOf(9, 9, 9))
        assertEquals(first, second, "captureIfAbsent must return the first capture, not the new one")
        assertTrue(first.classBytes.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun sha256_is_stable_across_byte_copies() {
        val store = SnapshotStore()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val snap = store.captureIfAbsent("com.example.Bar", bytes)
        val sha = SnapshotStore.sha256Hex(bytes)
        assertEquals(sha, snap.sha256Hex)
        assertEquals(64, sha.length, "SHA-256 hex is 64 chars")
    }

    @Test
    fun eviction_drops_oldest_past_cap() {
        val store = SnapshotStore(maxEntries = 3)
        store.captureIfAbsent("com.example.A", byteArrayOf(1))
        store.captureIfAbsent("com.example.B", byteArrayOf(2))
        store.captureIfAbsent("com.example.C", byteArrayOf(3))
        assertEquals(3, store.size())
        store.captureIfAbsent("com.example.D", byteArrayOf(4))
        assertEquals(3, store.size(), "size should remain capped at 3")
        assertFalse(store.contains("com.example.A"), "oldest entry A should be evicted")
        assertTrue(store.contains("com.example.D"))
    }

    @Test
    fun remove_evicts_specific_entry() {
        val store = SnapshotStore()
        store.captureIfAbsent("com.example.A", byteArrayOf(1))
        store.captureIfAbsent("com.example.B", byteArrayOf(2))
        val removed = store.remove("com.example.A")
        assertNotNull(removed)
        assertFalse(store.contains("com.example.A"))
        assertTrue(store.contains("com.example.B"))
    }

    @Test
    fun clear_wipes_all_entries() {
        val store = SnapshotStore()
        store.captureIfAbsent("com.example.A", byteArrayOf(1))
        store.captureIfAbsent("com.example.B", byteArrayOf(2))
        store.clear()
        assertEquals(0, store.size())
        assertFalse(store.contains("com.example.A"))
    }

    @Test
    fun stored_bytes_are_independent_copy() {
        val store = SnapshotStore()
        val input = byteArrayOf(1, 2, 3)
        val snap = store.captureIfAbsent("com.example.A", input)
        input[0] = 99
        assertEquals(1.toByte(), snap.classBytes[0], "snapshot must defensively copy bytes")
    }
}
