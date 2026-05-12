package com.acendas.androiddebugger.hotswap

import java.security.MessageDigest
import java.time.Instant
import java.util.LinkedHashMap

/**
 * In-memory store of pre-swap class bytes, keyed by class FQN. The server
 * captures the original bytes BEFORE the first redefine of a given class via
 * the agent's [com.acendas.androiddebugger.jvmti.AgentClient]'s
 * `get_original_class_bytes` RPC (backed by the agent's ClassFileLoadHook
 * cache). Subsequent revert calls feed those same bytes back through the
 * dexer + redefine pipeline.
 *
 * Per v1.5 spec §7.
 *
 * **Lifetime:** session-scoped. Cleared on `detach`. Not persisted across
 * sessions per spec §13 — app restarts invalidate any saved swap state anyway.
 *
 * **Capacity:** [maxEntries] (default 200). LRU eviction via [LinkedHashMap]
 * access-order. Evicting an entry just means we lose the ability to revert
 * that specific class via in-memory cache; the agent's own ClassFileLoadHook
 * cache (capped at 5000) remains the upstream source of truth.
 */
class SnapshotStore(private val maxEntries: Int = 200) {

    /** One stored snapshot. [classBytes] is JVM `.class` form (NOT dex). */
    data class Snapshot(
        val fqn: String,
        val classBytes: ByteArray,
        val sha256Hex: String,
        val savedAt: Instant,
    ) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Snapshot && fqn == other.fqn && sha256Hex == other.sha256Hex)
        override fun hashCode(): Int = fqn.hashCode() * 31 + sha256Hex.hashCode()
    }

    private val cache = object : LinkedHashMap<String, Snapshot>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>): Boolean =
            size > maxEntries
    }

    /**
     * Insert [classBytes] as the snapshot for [fqn] **only if** we haven't seen
     * this fqn before in this session. Returns the stored [Snapshot] (existing
     * or newly inserted). The "first-write wins" rule means a sequence
     * swap-A → swap-B → revert returns to A's input, not B's input — and from
     * the agent's perspective, to the **original** pre-swap bytes.
     */
    @Synchronized
    fun captureIfAbsent(fqn: String, classBytes: ByteArray): Snapshot {
        val existing = cache[fqn]
        if (existing != null) return existing
        val sha = sha256Hex(classBytes)
        val snapshot = Snapshot(fqn, classBytes.copyOf(), sha, Instant.now())
        cache[fqn] = snapshot
        return snapshot
    }

    /** Returns the stored snapshot for [fqn], or null if never captured. */
    @Synchronized
    fun get(fqn: String): Snapshot? = cache[fqn]

    /** Returns true if a snapshot exists for [fqn]. */
    @Synchronized
    fun contains(fqn: String): Boolean = cache.containsKey(fqn)

    /** Remove the snapshot for [fqn]. Returns the removed entry or null. */
    @Synchronized
    fun remove(fqn: String): Snapshot? = cache.remove(fqn)

    /** Returns the set of FQNs with stored snapshots, in access (LRU) order. */
    @Synchronized
    fun keys(): List<String> = cache.keys.toList()

    /** Number of stored snapshots. */
    @Synchronized
    fun size(): Int = cache.size

    /** Drop every snapshot. Used in [com.acendas.androiddebugger.DebugSession.reset]. */
    @Synchronized
    fun clear() {
        cache.clear()
    }

    companion object {
        /** Standard hex-lowercase SHA-256. Used for [Snapshot.sha256Hex] and for diff comparisons. */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
            return sb.toString()
        }
    }
}
