package com.acendas.androiddebugger.plans

import com.acendas.androiddebugger.inspection.FrameSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-plan snapshot store. Holds [FrameSnapshot]s captured during plan execution keyed by
 * `(plan_id, event_seq)` so the agent can drill into them via `inspect_object` *after*
 * the plan terminates — the existing `(thread, vmStateVersion)` cache in [com.acendas.androiddebugger.DebugSession]
 * is invalidated on resume, which is the wrong lifetime for plan-pinned reads.
 *
 * **Bounds**: per-plan cap from [Plan.maxSnapshots] (default 50); global LRU cap from
 * [Plan.GLOBAL_MAX_SNAPSHOTS] (default 200). Eviction is plan-scoped first (oldest
 * snapshot from the offending plan), then global LRU across plans. When eviction
 * happens we mark [Plan.truncated]-style `truncated:true` on the report.
 *
 * **Lifetime**: cleared on `Session.detach` (via [DebugSession.reset]) and at the start
 * of the next [com.acendas.androiddebugger.plans.PlanExecutor.run] call — refs from a
 * previous plan stop resolving immediately so a stale ref doesn't accidentally return a
 * snapshot from an unrelated plan run.
 *
 * **Ref format**: `snap#<plan_id>:<event_seq>` — the agent passes this verbatim back to
 * `inspect_object(ref, vobj#42)` etc. The store also tracks `vobj#`/`obj#` refs minted
 * by [com.acendas.androiddebugger.inspection.ObjectIdMint] inside each snapshot so the
 * follow-up `inspect_object` lookup knows which snapshot's ref namespace to use.
 */
class PlanStore {

    /** Internal storage keyed by ref string ("snap#<planId>:<seq>"). LinkedHashMap so we can LRU-iterate. */
    private val snapshots: MutableMap<String, Entry> = java.util.Collections.synchronizedMap(LinkedHashMap())

    /** Counts of snapshots per plan id, for cheap per-plan-cap checks. */
    private val perPlanCounts: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()

    /** True if any snapshot has been evicted under cap pressure since the last clear. */
    @Volatile var truncated: Boolean = false
        private set

    /**
     * Pin a snapshot under the canonical ref. Returns the ref string for the caller to
     * surface in plan_progress events / final report.
     */
    fun put(
        planId: String,
        eventSeq: Long,
        snapshot: FrameSnapshot,
        perPlanCap: Int = Plan.DEFAULT_MAX_SNAPSHOTS,
    ): String {
        val ref = "snap#$planId:$eventSeq"
        val entry = Entry(planId, eventSeq, snapshot)
        synchronized(snapshots) {
            snapshots[ref] = entry
            val count = perPlanCounts.computeIfAbsent(planId) { AtomicLong(0) }
            count.incrementAndGet()
            // Per-plan cap eviction.
            while (count.get() > perPlanCap) {
                val oldest = oldestForPlan(planId)
                if (oldest == null) break
                evict(oldest)
            }
            // Global cap eviction.
            while (snapshots.size > Plan.GLOBAL_MAX_SNAPSHOTS) {
                val it = snapshots.keys.iterator()
                if (!it.hasNext()) break
                val refToEvict = it.next()
                evict(refToEvict)
            }
        }
        return ref
    }

    /** Look up a snapshot by ref string. Returns null if not found or evicted. */
    fun get(ref: String): FrameSnapshot? {
        val entry = synchronized(snapshots) { snapshots[ref] } ?: return null
        return entry.snapshot
    }

    /** Returns all refs for a given plan id, oldest first. */
    fun refsFor(planId: String): List<String> = synchronized(snapshots) {
        snapshots.entries.filter { it.value.planId == planId }.map { it.key }
    }

    /** Drop every snapshot belonging to a plan id. Used by next-plan-start eviction. */
    fun clearForPlan(planId: String): Int {
        var dropped = 0
        synchronized(snapshots) {
            val it = snapshots.entries.iterator()
            while (it.hasNext()) {
                val (_, value) = it.next()
                if (value.planId == planId) {
                    it.remove()
                    dropped++
                }
            }
            perPlanCounts.remove(planId)
        }
        return dropped
    }

    /** Drop the whole store. Called from [DebugSession.reset]. */
    fun clear() {
        synchronized(snapshots) {
            snapshots.clear()
            perPlanCounts.clear()
            truncated = false
        }
    }

    /** Current snapshot count (used by tests + cap regression checks). */
    fun size(): Int = synchronized(snapshots) { snapshots.size }

    /** Snapshot count for one plan. */
    fun sizeFor(planId: String): Int = perPlanCounts[planId]?.get()?.toInt() ?: 0

    // --- internal --------------------------------------------------------------------

    private data class Entry(val planId: String, val eventSeq: Long, val snapshot: FrameSnapshot)

    /** Caller must hold the snapshots monitor. */
    private fun oldestForPlan(planId: String): String? {
        for ((ref, entry) in snapshots) {
            if (entry.planId == planId) return ref
        }
        return null
    }

    /** Caller must hold the snapshots monitor. */
    private fun evict(ref: String) {
        val entry = snapshots.remove(ref) ?: return
        perPlanCounts[entry.planId]?.decrementAndGet()
        truncated = true
    }
}
