package com.acendas.androiddebugger.events

import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.SessionState
import com.acendas.androiddebugger.breakpoints.BreakpointKind
import com.acendas.androiddebugger.breakpoints.BreakpointManager
import com.acendas.androiddebugger.breakpoints.BreakpointMeta
import com.acendas.androiddebugger.breakpoints.LogMessageRenderer
import com.acendas.androiddebugger.breakpoints.LogpointBuffer
import com.acendas.androiddebugger.inspection.Evaluator
import com.acendas.androiddebugger.inspection.ObjectIdMint
import ca.acendas.kfeel.api.FeelValue
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.BooleanValue
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.AccessWatchpointEvent
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.Event
import com.sun.jdi.event.EventQueue
import com.sun.jdi.event.EventSet
import com.sun.jdi.event.ExceptionEvent
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.event.ModificationWatchpointEvent
import com.sun.jdi.event.StepEvent
import com.sun.jdi.event.ThreadDeathEvent
import com.sun.jdi.event.ThreadStartEvent
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.event.VMStartEvent
import com.sun.jdi.event.WatchpointEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single coroutine that pumps `vm.eventQueue()` into a [Channel] of typed [DebugEvent]s.
 * `wait_for_event` reads from the channel; on a SUSPEND_* event the loop also flips
 * [Session.state] / [Session.pausedThread] so subsequent inspection tools see the
 * paused state without racing.
 *
 * The channel is bounded (capacity = 128) with [BufferOverflow.DROP_OLDEST] — if no one
 * is calling `wait_for_event`, we silently drop the oldest events rather than blocking
 * the JDI thread (which would freeze the target). Per Task 4.1.3.1.
 *
 * Phase 3 wiring: Breakpoint/Watchpoint/MethodEntry/MethodExit events are looked up in
 * [BreakpointManager]; conditional bps eval and resume on false; hit-count bps suppress
 * until the threshold; logpoints render the message and resume immediately. ClassPrepare
 * events drive deferred-bp resolution.
 */
class EventLoop(
    private val vm: VirtualMachine,
    private val channel: Channel<DebugEvent>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger("android-debugger.events")
    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + parentJob)

    @Volatile
    private var pumpJob: Job? = null

    /**
     * One-shot listener registered by [awaitEvent]. The JDI thread inspects the listener
     * list FIFO and hands the first event whose predicate matches to that listener; the
     * listener completes its [deferred] and is removed from the list. Per R-07.
     *
     * Concurrency: the list is a [CopyOnWriteArrayList] so the JDI dispatch path can
     * iterate without locking, and listener registration / removal are infrequent.
     */
    private data class Listener(
        val predicate: (DebugEvent) -> Boolean,
        val deferred: CompletableDeferred<DebugEvent>,
    )

    private val listeners: CopyOnWriteArrayList<Listener> = CopyOnWriteArrayList()

    /**
     * Block the calling coroutine until the next [DebugEvent] satisfying [predicate]
     * arrives, or [timeoutMs] elapses. Concurrent waiters are dispatched in registration
     * order: each event hands off to the first matching listener, so multiple
     * `wait_for_event` calls with disjoint type filters all see the events they expect
     * in arrival order. Per R-07.
     *
     * If no listener matches a given event, it falls through to the legacy channel so a
     * later `wait_for_event` (registered after the event arrived) can still see it.
     */
    suspend fun awaitEvent(timeoutMs: Long, predicate: (DebugEvent) -> Boolean): DebugEvent? {
        val deferred = CompletableDeferred<DebugEvent>()
        val listener = Listener(predicate, deferred)
        listeners.add(listener)
        // Drain any already-queued events that match before we wait — keeps semantics
        // identical to the pre-R-07 channel-drain behavior. Iterate the channel poll
        // path; non-matching events go back through the dispatch path so other waiters
        // can claim them.
        try {
            while (true) {
                val polled = channel.tryReceive().getOrNull() ?: break
                if (predicate(polled)) {
                    return polled
                }
                dispatch(polled)
                if (deferred.isCompleted) {
                    // dispatch handed it to a different listener; keep looking for ours.
                    // (Shouldn't happen because predicate(polled) was false, but be safe.)
                    break
                }
            }
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            listeners.remove(listener)
        }
    }

    /**
     * Dispatch a freshly-arrived [DebugEvent]. Hands it to the first matching listener
     * and returns; if no listener matches, falls back to the channel so a later
     * `wait_for_event` can still see it. Per R-07.
     */
    private fun dispatch(event: DebugEvent) {
        // Iterate snapshot. CopyOnWriteArrayList iteration sees a frozen view; the
        // listener may have already been removed by a timeout — `complete` returns false
        // in that case and we move on.
        for (listener in listeners) {
            if (listener.predicate(event)) {
                if (listener.deferred.complete(event)) {
                    listeners.remove(listener)
                    return
                }
            }
        }
        // No active listener matched — fall back to the channel. Consumers polling
        // the channel directly (legacy / Disconnect catch-all) will still see it.
        runCatching { channel.trySend(event) }
    }

    /**
     * Broadcast a [DebugEvent.Disconnect] to every active waiter and into the channel.
     * Used in the disconnect path — every concurrent waiter needs to wake up, not just
     * the first matching one.
     */
    private fun broadcastDisconnect() {
        val ev = DebugEvent.Disconnect
        // Snapshot to avoid concurrent-modification under iteration; CopyOnWriteArrayList
        // already provides a safe iterator but we want to remove() on hits.
        for (listener in listeners.toList()) {
            if (listener.predicate(ev)) {
                listener.deferred.complete(ev)
                listeners.remove(listener)
            }
        }
        runCatching { channel.trySend(ev) }
    }

    /** Start pumping. Returns the running job so callers can hold + cancel it. */
    fun start(): Job {
        val job = scope.launch {
            val queue: EventQueue = vm.eventQueue()
            try {
                while (isActive) {
                    val set: EventSet = try {
                        queue.remove()
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: VMDisconnectedException) {
                        break
                    }
                    handleEventSet(set)
                }
            } catch (_: VMDisconnectedException) {
                // Fall through to disconnect signal.
            } catch (t: Throwable) {
                log.warn("event loop terminated unexpectedly: ${t.message}")
            } finally {
                runCatching { broadcastDisconnect() }
            }
        }
        pumpJob = job
        return job
    }

    /**
     * Process one `EventSet`. Each set arrives with its own suspend policy (per the
     * triggering request); we decide per-event whether to **deliver** a `Stopped`
     * (paused) signal to the channel and leave the thread suspended, or **swallow**
     * the event and resume.
     *
     * The set-level `resume()` honors the original suspend policy:
     *  - SUSPEND_NONE → no-op (nothing was suspended)
     *  - SUSPEND_EVENT_THREAD → resumes just the thread that triggered
     *  - SUSPEND_ALL → resumes all threads
     *
     * If we deliver `Stopped`, we DO NOT call `set.resume()` — the agent will resume
     * via the `resume` / `step_*` tools.
     */
    private fun handleEventSet(set: EventSet) {
        var deliveredStop = false
        var disconnect = false
        for (event in set) {
            val outcome = processEvent(event)
            when (outcome) {
                Outcome.STOPPED -> deliveredStop = true
                Outcome.RESUME -> { /* keep going */ }
                Outcome.DISCONNECT -> { disconnect = true; break }
            }
        }
        if (disconnect) return
        if (!deliveredStop) {
            try {
                set.resume()
            } catch (_: VMDisconnectedException) {
                // Fall through; outer loop will see VMDisconnectEvent next.
            } catch (_: Throwable) {
                // Best-effort resume.
            }
        }
    }

    private enum class Outcome { STOPPED, RESUME, DISCONNECT }

    private fun processEvent(event: Event): Outcome {
        // VM lifecycle short-circuits.
        if (event is VMDisconnectEvent || event is VMDeathEvent) {
            runCatching { broadcastDisconnect() }
            return Outcome.DISCONNECT
        }
        if (event is VMStartEvent || event is ThreadStartEvent || event is ThreadDeathEvent) {
            return Outcome.RESUME
        }

        // Class-prepare drives deferred breakpoint resolution. We resolve every meta
        // whose deferred-prepare-request matches this event, install BPs on the new
        // type, and surface the agent-facing event after.
        //
        // v1.3 — also handles user-facing CLASS_LOAD breakpoints (Phase C). Those
        // attach their `ClassPrepareRequest` as a normal active-request (vs. the
        // internal deferred ones), so a hit lands in `findByRequest` and we emit a
        // proper `Stopped(reason="class_prepare", breakpoint_id=…)` instead of
        // swallowing the pause.
        if (event is ClassPrepareEvent) {
            val userBp = BreakpointManager.findByRequest(event.request())
            if (userBp != null && userBp.kind == BreakpointKind.CLASS_LOAD) {
                userBp.totalHits.incrementAndGet()
                if (userBp.enabled) {
                    userBp.deliveredStops.incrementAndGet()
                    sendClassLoadStopped(event, userBp.id)
                    markPausedFromClassPrepare(event)
                    return Outcome.STOPPED
                }
                userBp.suppressedHits.incrementAndGet()
            }
            handleClassPrepare(event)
            runCatching {
                dispatch(DebugEvent.ClassPrepare(
                    className = runCatching { event.referenceType().name() }.getOrDefault(""),
                ))
            }
            return Outcome.RESUME
        }

        // Step events always deliver. The Stepper tool waits for them.
        if (event is StepEvent) {
            sendStopped("step", event)
            markPaused(event)
            return Outcome.STOPPED
        }

        // Exception events: resolve bp meta if any, push Exception event, then mark paused.
        if (event is ExceptionEvent) {
            return handleException(event)
        }

        // Field watchpoints — both kinds — deliver as breakpoint-style stops with the
        // owning meta's id so the agent can correlate.
        if (event is WatchpointEvent) {
            return handleWatchpoint(event)
        }

        // Method entry/exit — class-filter is set by JDI; per-method matching here.
        if (event is MethodEntryEvent) {
            return handleMethodEntryExit(event, exit = false)
        }
        if (event is MethodExitEvent) {
            return handleMethodEntryExit(event, exit = true)
        }

        // Plain breakpoint event — line/conditional/hit-count/logpoint all funnel here.
        if (event is BreakpointEvent) {
            return handleBreakpoint(event)
        }

        // Unknown/unhandled — let JDI's default suspend policy resume normally.
        return Outcome.RESUME
    }

    private fun handleException(event: ExceptionEvent): Outcome {
        // Per R-20: gate exception events on meta.enabled + condition the same way the
        // breakpoint / watchpoint / method-entry-exit handlers do. Disabling an exception
        // breakpoint via `disable_breakpoint` already calls `req.disable()` so JDI stops
        // firing, but symmetry across handlers prevents drift if we ever change the
        // disable path.
        val meta = BreakpointManager.findByRequest(event.request())
        meta?.totalHits?.incrementAndGet()
        if (meta != null && !meta.enabled) return Outcome.RESUME

        // Conditional path mirrors handleBreakpoint — useful for "stop only when
        // exception.message contains X" patterns the agent may hand in via
        // add_exception_breakpoint(condition=...).
        val condition = meta?.condition
        if (condition != null) {
            val passed = evalCondition(condition, event)
            if (!passed) {
                meta.falseConditionHits.incrementAndGet()
                return Outcome.RESUME
            }
        }

        val exc = runCatching { event.exception() }.getOrNull()
        val excId = exc?.let { ObjectIdMint.registerObject(it) }
        val excClass = exc?.referenceType()?.name()
        val caughtNow = runCatching { event.catchLocation() != null }.getOrDefault(false)
        runCatching {
            dispatch(DebugEvent.Exception(
                exceptionId = excId,
                exceptionClass = excClass,
                threadId = runCatching { event.thread().uniqueID() }.getOrNull(),
                caught = caughtNow,
            ))
        }
        meta?.deliveredStops?.incrementAndGet()
        markPaused(event)
        return Outcome.STOPPED
    }

    private fun handleBreakpoint(event: BreakpointEvent): Outcome {
        val meta = BreakpointManager.findByRequest(event.request())
        if (meta == null) {
            // Unknown breakpoint (e.g., the run_to_line one-shot, which is not in the manager).
            // Deliver a generic stop so existing tools (run_to_line, step) keep working.
            sendStopped("breakpoint", event, breakpointId = null)
            markPaused(event)
            return Outcome.STOPPED
        }
        meta.totalHits.incrementAndGet()
        if (!meta.enabled) {
            // The user disabled this — JDI requests are already disabled, but in case of
            // a race, just resume.
            return Outcome.RESUME
        }

        // Logpoint path: render, push to buffer, resume immediately. We need locals so
        // suspend policy is SUSPEND_EVENT_THREAD; resuming the event-set restores execution.
        val logTemplate = meta.logMessage
        if (logTemplate != null) {
            val rendered = try {
                LogMessageRenderer.render(logTemplate, event.thread())
            } catch (t: Throwable) {
                "<render error: ${t.message ?: t::class.simpleName}>"
            }
            val loc = runCatching { event.location() }.getOrNull()
            val file = try { loc?.sourceName() } catch (_: AbsentInformationException) { null }
            val line = loc?.lineNumber() ?: meta.line ?: -1
            LogpointBuffer.push(
                threadName = runCatching { event.thread().name() }.getOrNull(),
                file = file,
                line = line,
                breakpointId = meta.id,
                rendered = rendered,
            )
            meta.logpointEntries.incrementAndGet()
            return Outcome.RESUME
        }

        // Conditional path: evaluate + resume on false. We use the existing Evaluator,
        // which is single-flight; if a parallel eval is already running, treat it as
        // "condition unknown — surface the stop" so we don't accidentally swallow real hits.
        val condition = meta.condition
        if (condition != null) {
            val passed = evalCondition(condition, event)
            if (!passed) {
                meta.falseConditionHits.incrementAndGet()
                return Outcome.RESUME
            }
        }

        // Hit-count path: bump suppressed counter and resume until threshold reached.
        val hitCount = meta.hitCount
        if (hitCount != null && hitCount > 1) {
            // Hit `hitCount` means deliver on the Nth and onwards. `totalHits` was
            // already incremented; if we haven't reached N yet, suppress.
            if (meta.totalHits.get() < hitCount) {
                meta.suppressedHits.incrementAndGet()
                return Outcome.RESUME
            }
        }

        // Deliver as a stopped event.
        sendStopped("breakpoint", event, breakpointId = meta.id)
        meta.deliveredStops.incrementAndGet()
        markPaused(event)
        return Outcome.STOPPED
    }

    private fun handleMethodEntryExit(event: LocatableEvent, exit: Boolean): Outcome {
        val request = if (exit) (event as MethodExitEvent).request() else (event as MethodEntryEvent).request()
        val meta = BreakpointManager.findByRequest(request)
        if (meta == null) {
            // No meta — should only happen if the bp was just removed mid-flight. Resume.
            return Outcome.RESUME
        }
        meta.totalHits.incrementAndGet()
        if (!meta.enabled) return Outcome.RESUME

        // Per-method filtering in the handler — JDI's MethodEntryRequest filter is by
        // class only.
        val expectedMethod = meta.methodName
        val actualMethod = runCatching {
            if (exit) (event as MethodExitEvent).method().name()
            else (event as MethodEntryEvent).method().name()
        }.getOrNull()
        if (expectedMethod != null && actualMethod != expectedMethod) {
            meta.suppressedHits.incrementAndGet()
            return Outcome.RESUME
        }

        sendStopped(
            reason = if (exit) "method_exit" else "method_entry",
            event = event,
            breakpointId = meta.id,
        )
        meta.deliveredStops.incrementAndGet()
        markPaused(event)
        return Outcome.STOPPED
    }

    private fun handleWatchpoint(event: WatchpointEvent): Outcome {
        val meta = BreakpointManager.findByRequest(event.request())
        if (meta == null) return Outcome.RESUME
        meta.totalHits.incrementAndGet()
        if (!meta.enabled) return Outcome.RESUME

        val reason = if (event is AccessWatchpointEvent) "field_access" else "field_modification"
        sendStopped(reason, event, breakpointId = meta.id)
        meta.deliveredStops.incrementAndGet()
        markPaused(event)
        return Outcome.STOPPED
    }

    private fun handleClassPrepare(event: ClassPrepareEvent) {
        val meta = BreakpointManager.findByPrepareRequest(event.request())
            ?: return
        val type = runCatching { event.referenceType() }.getOrNull() ?: return
        when (meta.kind) {
            BreakpointKind.LINE -> {
                runCatching { BreakpointManager.installDeferredLineBreakpoint(vm, meta, type) }
            }
            BreakpointKind.EXCEPTION -> {
                val cls = meta.exceptionClass ?: return
                if (type.name() != cls) return
                val erm = vm.eventRequestManager()
                val req = runCatching {
                    erm.createExceptionRequest(type, meta.caught, meta.uncaught).apply {
                        setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD)
                        if (meta.enabled) enable() else disable()
                    }
                }.getOrNull()
                if (req != null) BreakpointManager.attachRequest(meta, req)
            }
            else -> {
                // Method entry/exit and field watchpoints aren't deferred via class-prepare in v1.
            }
        }
    }

    private fun evalCondition(condition: String, event: LocatableEvent): Boolean {
        return try {
            val v = Evaluator.evaluate(event.thread(), 0, condition)
            feelTruthy(v)
        } catch (_: Throwable) {
            // Eval failure → surface the stop so the agent can investigate. Same
            // policy as IntelliJ: don't silently swallow on a broken condition.
            true
        }
    }

    /**
     * FEEL value → truthy semantics for conditional breakpoints. FEEL's preferred form
     * is an explicit boolean expression (`i > 100`), but we apply the same defensive
     * coercion the v1.2 evaluator did so a misshapen condition still has a sensible
     * fallback:
     *  - [FeelValue.Boolean] → the value
     *  - [FeelValue.Number] → non-zero is truthy
     *  - [FeelValue.Null] → false
     *  - [FeelValue.Text] → non-empty is truthy
     *  - [FeelValue.List] → non-empty is truthy
     *  - Anything else (Context / Range / Function / temporal) → truthy
     */
    private fun feelTruthy(v: FeelValue): Boolean = when (v) {
        is FeelValue.Boolean -> v.value
        FeelValue.Null -> false
        is FeelValue.Number -> v.value.signum() != 0
        is FeelValue.Text -> v.value.isNotEmpty()
        is FeelValue.List -> v.elements.isNotEmpty()
        else -> true
    }

    private fun sendStopped(reason: String, event: LocatableEvent, breakpointId: Int? = null) {
        val stopped = DebugEvent.Stopped(
            reason = reason,
            threadId = runCatching { event.thread().uniqueID() }.getOrNull(),
            threadName = runCatching { event.thread().name() }.getOrNull(),
            breakpointId = breakpointId,
            location = locationString(event),
        )
        // Per R-07: dispatch hands the event to the first matching listener registered
        // by [awaitEvent]; if none match, it falls back to the channel as a backstop.
        runCatching { dispatch(stopped) }
    }

    /**
     * Emit a `Stopped` event for a user-facing CLASS_LOAD breakpoint (Phase C). The
     * underlying [ClassPrepareEvent] is not a [LocatableEvent], so we hand-build the
     * event payload instead of reusing the [sendStopped] overload.
     */
    private fun sendClassLoadStopped(event: ClassPrepareEvent, breakpointId: Int) {
        val className = runCatching { event.referenceType().name() }.getOrDefault("")
        val stopped = DebugEvent.Stopped(
            reason = "class_prepare",
            threadId = runCatching { event.thread().uniqueID() }.getOrNull(),
            threadName = runCatching { event.thread().name() }.getOrNull(),
            breakpointId = breakpointId,
            location = if (className.isNotEmpty()) "class_prepare:$className" else null,
        )
        runCatching { dispatch(stopped) }
    }

    /** Mark paused from a class-prepare event — same accounting as the locatable path. */
    private fun markPausedFromClassPrepare(event: ClassPrepareEvent) {
        val thread = runCatching { event.thread() }.getOrNull()
        Session.pausedThread = thread
        Session.state = SessionState.ATTACHED_PAUSED
        Session.bumpVmStateVersion()
        if (thread != null) {
            Session.stepBudget.reset(thread.uniqueID())
        }
    }

    private fun markPaused(event: LocatableEvent) {
        val thread = runCatching { event.thread() }.getOrNull()
        Session.pausedThread = thread
        Session.state = SessionState.ATTACHED_PAUSED
        Session.bumpVmStateVersion()
        // Per Story 7.1.4: any breakpoint / watchpoint / exception / method_entry /
        // method_exit hit resets the step budget for that thread — the agent reached
        // a real "interesting" point, not a same-method step. We DON'T reset on a
        // step event since same-method-step counts are exactly what the budget tracks.
        if (thread != null && event !is com.sun.jdi.event.StepEvent) {
            Session.stepBudget.reset(thread.uniqueID())
        }
    }

    /** Cancel the loop, joining briefly so subsequent vm.dispose() races aren't possible. */
    suspend fun stop() {
        try {
            parentJob.cancelAndJoin()
        } catch (_: Throwable) {
            // Best-effort.
        }
    }

    private fun locationString(event: LocatableEvent): String? = try {
        val loc = event.location()
        val file = try { loc.sourceName() } catch (_: AbsentInformationException) { null }
        val cls = loc.declaringType().name()
        val line = loc.lineNumber()
        if (file != null) "$cls($file:$line)" else "$cls:$line"
    } catch (_: Throwable) {
        null
    }
}
