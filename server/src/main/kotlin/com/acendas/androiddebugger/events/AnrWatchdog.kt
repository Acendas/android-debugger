package com.acendas.androiddebugger.events

import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the Android UI thread (`name == "main"`) and emits synthetic [DebugEvent.Warning]s
 * when it has been suspended too long. Polls every 500ms; a 2s threshold yields a regular
 * `anr_risk` warning, 5s escalates to `severity=critical`.
 *
 * Per Story 7.1.3.
 *
 * The watchdog is **only** meaningful while attached. The lifecycle wires it up after
 * `Session.startEventLoop` and tears it down on detach / disconnect.
 *
 * Clock-injectable so the unit test can fast-forward without real `delay()`. The
 * [pollIntervalMs] and [warningThresholdMs] / [criticalThresholdMs] are tunable for tests
 * that want sub-second sensitivity.
 *
 * Re-entrancy / dedup: once an `anr_risk` warning is emitted on the current suspended-run,
 * we don't re-emit until the suspendCount drops back to 0. This avoids spamming the
 * channel every 500ms.
 */
class AnrWatchdog(
    private val mainThread: ThreadReference,
    private val channel: Channel<DebugEvent>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 500L,
    private val warningThresholdMs: Long = 2_000L,
    private val criticalThresholdMs: Long = 5_000L,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + parentJob)

    @Volatile private var job: Job? = null

    /**
     * Start the watchdog loop. Returns the running job so callers can hold + cancel it.
     * Idempotent — calling twice returns the same job.
     */
    fun start(): Job {
        job?.let { return it }
        val j = scope.launch {
            runLoop()
        }
        job = j
        return j
    }

    /**
     * Single-iteration step useful for synthetic-clock tests. Returns the warning emitted
     * on this tick (or null if none). Tests inject a controllable clock via [clock] +
     * `suspendStart` and call [tickOnce] repeatedly to drive the state machine without
     * real `delay`.
     */
    suspend fun runLoop() {
        var suspendStartMs: Long? = null
        var warningEmitted = false
        var criticalEmitted = false
        while (scope.isActive) {
            val state = inspect(suspendStartMs, warningEmitted, criticalEmitted)
            suspendStartMs = state.suspendStartMs
            warningEmitted = state.warningEmitted
            criticalEmitted = state.criticalEmitted
            if (state.warning != null) {
                channel.trySend(state.warning)
            }
            try {
                delay(pollIntervalMs)
            } catch (_: Throwable) {
                break
            }
        }
    }

    /** Snapshot of the watchdog's running state — used by tests that drive the loop manually. */
    data class TickResult(
        val suspendStartMs: Long?,
        val warningEmitted: Boolean,
        val criticalEmitted: Boolean,
        val warning: DebugEvent.Warning?,
    )

    /**
     * Pure state-machine step: given the prior `suspendStart` and emission flags, decide
     * whether to emit a warning this tick. Exposed for unit testing without spinning up
     * coroutines or a real JDI VM. Per Story 7.1.3 ("synthetic clock-driven test").
     */
    fun inspect(
        prevSuspendStartMs: Long?,
        prevWarningEmitted: Boolean,
        prevCriticalEmitted: Boolean,
    ): TickResult {
        val now = clock()
        val suspended = try {
            mainThread.suspendCount() > 0
        } catch (_: VMDisconnectedException) {
            return TickResult(null, false, false, null)
        } catch (_: Throwable) {
            return TickResult(prevSuspendStartMs, prevWarningEmitted, prevCriticalEmitted, null)
        }
        if (!suspended) {
            // Cleared — reset state so the next suspend starts fresh.
            return TickResult(null, false, false, null)
        }
        val start = prevSuspendStartMs ?: now
        val elapsed = now - start
        var warning: DebugEvent.Warning? = null
        var newWarningEmitted = prevWarningEmitted
        var newCriticalEmitted = prevCriticalEmitted
        if (elapsed >= criticalThresholdMs && !prevCriticalEmitted) {
            warning = DebugEvent.Warning(
                warningType = "anr_risk",
                severity = "critical",
                extra = mapOf("suspended_ms" to elapsed),
            )
            newCriticalEmitted = true
            newWarningEmitted = true
        } else if (elapsed >= warningThresholdMs && !prevWarningEmitted) {
            warning = DebugEvent.Warning(
                warningType = "anr_risk",
                severity = "warning",
                extra = mapOf("suspended_ms" to elapsed),
            )
            newWarningEmitted = true
        }
        return TickResult(start, newWarningEmitted, newCriticalEmitted, warning)
    }

    /** Cancel the loop. Safe to call multiple times; quietly swallows cancellation noise. */
    suspend fun stop() {
        try {
            parentJob.cancelAndJoin()
        } catch (_: Throwable) {
            // Best-effort.
        }
    }
}
