package com.acendas.androiddebugger.inspection

import com.sun.jdi.ThreadReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-session registry of watch expressions. Per Story 5.1.1; updated for v1.3 to take
 * raw FEEL expression strings (parsed by kfeel at evaluation time, not pre-parsed).
 *
 * Watches are bare strings (the same shape `evaluate` accepts) plus a numeric id minted
 * at registration. They live for the duration of a single attach — `Session.detach()`
 * resets them; the agent re-adds in the next session (unless v1.3 persistence is enabled
 * and the agent re-attaches to the same package).
 *
 * On every pause, [evaluateAll] is called by the snapshot builder against the top frame
 * of the paused thread. Each watch's value is captured (or its error string), and the
 * results land in `frame_snapshot.watches`. Errors per-watch don't fail the snapshot.
 *
 * If the [Evaluator] is mid-flight when [evaluateAll] runs (concurrent eval call), the
 * prior cached value is returned with `stale = true` so the snapshot still shows
 * something useful instead of an opaque "busy" error.
 */
class WatchManager {

    /** A registered watch. */
    data class Watch(
        val id: Int,
        val expr: String,
        // Cached last value, updated on every successful evaluation. Read in the
        // stale-fallback path.
        @Volatile var lastValue: RenderedValue? = null,
        @Volatile var lastError: String? = null,
    )

    private val nextId: AtomicInteger = AtomicInteger(1)
    private val watches: MutableMap<Int, Watch> = ConcurrentHashMap()

    /** Register a new watch. Returns the freshly-minted id. */
    fun add(expr: String): Int {
        val id = nextId.getAndIncrement()
        watches[id] = Watch(id = id, expr = expr)
        return id
    }

    /**
     * Register a watch with a caller-specified [id] (used by v1.3 session persistence to
     * rehydrate). Returns true if registered; false if the id was already taken.
     */
    fun addWithId(id: Int, expr: String): Boolean {
        if (watches.putIfAbsent(id, Watch(id = id, expr = expr)) != null) return false
        // Bump the next-id counter past the highest restored id so future `add` calls
        // mint a fresh id.
        while (true) {
            val current = nextId.get()
            if (current > id) break
            if (nextId.compareAndSet(current, id + 1)) break
        }
        return true
    }

    /** Drop a watch by id. Returns true if it existed; false if it was already gone. */
    fun remove(id: Int): Boolean = watches.remove(id) != null

    /** Snapshot of the registered watches in registration order. */
    fun list(): List<Watch> = watches.values.sortedBy { it.id }

    /** Number of registered watches; cheap, used by `connection_status`. */
    fun size(): Int = watches.size

    /** Drop everything. Called by `Session.reset()` on detach. */
    fun clear() {
        watches.clear()
        nextId.set(1)
    }

    /**
     * Evaluate every registered watch against the top frame of [thread] (frame index 0).
     * Returns one [WatchValue] per watch in registration order.
     *
     * Errors per-watch are captured into [WatchValue.error] — they never throw out.
     * Concurrent-evaluator collisions surface as `stale = true` plus the prior value
     * (or `null` value with an error if we never had a successful prior eval).
     */
    fun evaluateAll(thread: ThreadReference, frameIdx: Int = 0): List<WatchValue> {
        if (watches.isEmpty()) return emptyList()
        val ordered = watches.values.sortedBy { it.id }
        val out = ArrayList<WatchValue>(ordered.size)
        for (w in ordered) {
            out += evaluateOne(thread, frameIdx, w)
        }
        return out
    }

    private fun evaluateOne(thread: ThreadReference, frameIdx: Int, w: Watch): WatchValue {
        return try {
            val feel = Evaluator.evaluate(thread, frameIdx, w.expr)
            val rendered = FeelValueRenderer.render(feel)
            w.lastValue = rendered
            w.lastError = null
            WatchValue(expr = w.expr, value = rendered, error = null, stale = false)
        } catch (te: com.acendas.androiddebugger.ToolError) {
            // Single-flight collision (Evaluator busy) — return cached prior value as stale.
            if (te.errorCode == com.acendas.androiddebugger.ErrorCode.VmPaused) {
                val prior = w.lastValue
                if (prior != null) {
                    return WatchValue(expr = w.expr, value = prior, error = null, stale = true)
                }
                val msg = te.message ?: "evaluator busy"
                w.lastError = msg
                return WatchValue(expr = w.expr, value = null, error = msg, stale = true)
            }
            // All other ToolErrors are real (parse error, unknown identifier, VM
            // disconnected, ...). Capture the message; don't kill the snapshot.
            val msg = te.message ?: te.errorCode.code
            w.lastError = msg
            WatchValue(expr = w.expr, value = w.lastValue, error = msg, stale = w.lastValue != null)
        } catch (t: Throwable) {
            val msg = "eval failed: ${t.message ?: t::class.simpleName}"
            w.lastError = msg
            WatchValue(expr = w.expr, value = w.lastValue, error = msg, stale = w.lastValue != null)
        }
    }
}
