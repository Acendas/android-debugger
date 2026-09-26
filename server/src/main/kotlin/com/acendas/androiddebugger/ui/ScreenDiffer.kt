package com.acendas.androiddebugger.ui

/**
 * Turns successive settled layout reads into `screen_changed` diffs.
 *
 * Why this exists: Jetpack Compose apps emit only anonymous `WINDOW_CONTENT_CHANGED`
 * pings (no text, no clicks, no window-state change on in-activity navigation), so the
 * raw accessibility stream says *that* something changed, never *what*. The server
 * settles the pings, reads the screen once, and this class reports the visible-text
 * difference against the previous read.
 *
 * Tick suppression (every rule below was found live on a kiosk and is pinned by a test):
 *  - A countdown ("15s" -> "14s") changes only digits, once a second. A digit-only diff
 *    whose digit-masked pattern also changed less than [tickWindowMs] ago is suppressed
 *    and counted, so an agent sees the first tick and then silence.
 *  - Digit *runs* are masked, not single digits, or "10s" -> "9s" breaks the chain.
 *  - Patterns are tracked per key, so a per-minute clock ticking mid-countdown does not
 *    reset the countdown's suppression.
 *  - Sparse digit changes (quantity 0 -> 1, the per-minute clock) still report.
 *  - [onInput] clears suppression: a change we caused (a second "+" tap 1 s after the
 *    first) is never a tick.
 */
class ScreenDiffer(private val tickWindowMs: Long = DEFAULT_TICK_WINDOW_MS) {

    data class Change(
        val first: Boolean,
        val packageName: String?,
        val added: List<String>,
        val removed: List<String>,
        val visible: Int,
        val digitsOnly: Boolean,
        val suppressedTicksBefore: Int,
    )

    private var lastScreen: List<String>? = null
    private var lastPackage: String? = null
    private val tickSeenAt = HashMap<String, Long>()
    private var suppressedTicks = 0

    /** A tap/key/text injection just happened; the next digit-only diff must report. */
    @Synchronized
    fun onInput() {
        tickSeenAt.clear()
    }

    /** Forget the previous screen, e.g. after the daemon restarted. */
    @Synchronized
    fun reset() {
        lastScreen = null
        lastPackage = null
        tickSeenAt.clear()
        suppressedTicks = 0
    }

    /**
     * Record a settled read. Returns the change to publish, or null when nothing visible
     * changed or the change is a suppressed tick.
     */
    @Synchronized
    fun observe(packageName: String?, screen: List<String>, nowMs: Long): Change? {
        val previous = lastScreen
        if (screen == previous && packageName == lastPackage) return null

        val added = screen.toMutableList()
        val removed = mutableListOf<String>()
        if (previous != null) {
            for (s in previous) if (!added.remove(s)) removed.add(s)
        }
        lastScreen = screen
        lastPackage = packageName

        val tick = tickKey(added, removed)
        val prevAt = tick?.let { tickSeenAt[it] }
        val repeatTick = prevAt != null && nowMs - prevAt < tickWindowMs
        if (tick != null) tickSeenAt[tick] = nowMs
        if (repeatTick) {
            suppressedTicks++
            return null
        }
        val change = Change(
            first = previous == null,
            packageName = packageName,
            added = added,
            removed = removed,
            visible = screen.size,
            digitsOnly = tick != null,
            suppressedTicksBefore = suppressedTicks,
        )
        suppressedTicks = 0
        return change
    }

    companion object {
        const val DEFAULT_TICK_WINDOW_MS: Long = 2_500L
        private val DIGIT_RUN = Regex("\\d+")

        /** Non-null when [added]/[removed] differ only in digit runs; the masked strings as a key. */
        fun tickKey(added: List<String>, removed: List<String>): String? {
            if (added.isEmpty() || added.size != removed.size) return null
            val a = added.map { it.replace(DIGIT_RUN, "#") }.sorted()
            val r = removed.map { it.replace(DIGIT_RUN, "#") }.sorted()
            return if (a == r) a.joinToString("\u0001") else null
        }
    }
}
