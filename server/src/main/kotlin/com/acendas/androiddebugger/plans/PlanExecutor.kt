package com.acendas.androiddebugger.plans

import ca.acendas.kfeel.api.FeelContext
import ca.acendas.kfeel.api.FeelExpression
import ca.acendas.kfeel.api.FeelValue
import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.breakpoints.BreakpointInstaller
import com.acendas.androiddebugger.breakpoints.BreakpointManager
import com.acendas.androiddebugger.events.DebugEvent
import com.acendas.androiddebugger.events.EventLoop
import com.acendas.androiddebugger.execution.Stepper
import com.acendas.androiddebugger.inspection.Evaluator
import com.acendas.androiddebugger.inspection.FeelContextBuilder
import com.acendas.androiddebugger.inspection.FeelValueCodec
import com.acendas.androiddebugger.inspection.FeelValueRenderer
import com.acendas.androiddebugger.inspection.FrameSnapshot
import com.acendas.androiddebugger.inspection.SnapshotBuilder
import com.acendas.androiddebugger.inspection.VmCoordinator
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.Value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * v1.7 Debug Plan runtime — consumes the JDI event stream, dispatches matched events
 * through `on_event` handler chains, captures snapshots, evaluates FEEL expressions
 * (with `dbg.*` rewriting), grades hypotheses, and produces a [PlanReport].
 *
 * Public construction is via [launch]; callers receive a [PlanRunHandle] they can
 * `pause` / `abort` / `awaitTerminal` against. The executor's coroutine job is owned
 * internally and cancelled on terminal.
 *
 * Concurrency model: the executor runs on a coroutine on the caller's `scope`. It does
 * NOT hold `Session.mutex` — that mutex serializes user tool calls. JDI sub-ops
 * (eval_method) acquire `VmCoordinator.withExclusiveAccess` internally. Read-only
 * inspection (FEEL evaluate, snapshot build) does not need a coordinator slot.
 *
 * Termination is structurally bounded: `plan.timeoutMs` + (`plan.maxEvents` or
 * `plan.until` true). No loop in the user grammar can keep the executor alive past
 * those bounds.
 */
class PlanExecutor private constructor(
    val plan: Plan,
    override val planId: String,
    private val scope: CoroutineScope,
) : PlanRunHandle {

    override val planName: String = plan.name

    @Volatile override var isTerminal: Boolean = false
        private set

    // ------- internal state -------

    private val log = LoggerFactory.getLogger("android-debugger.plans")

    private val planStartMs: Long = System.currentTimeMillis()
    private val deadlineMs: Long = planStartMs + plan.timeoutMs

    private val progressSeq: AtomicLong = AtomicLong(0)
    private val eventSeq: AtomicLong = AtomicLong(0)

    // Counters (long-lived; visible on the final report).
    @Volatile private var eventsTotal: Long = 0
    @Volatile private var eventsHandled: Long = 0
    @Volatile private var eventsDropped: Long = 0

    // Hypothesis state machine.
    internal enum class HState { Inconclusive, Matched, Contradicted }
    internal data class HypothesisRecord(
        var state: HState = HState.Inconclusive,
        var firstTriggerSeq: Long? = null,
        var triggerCount: Long = 0,
        var contradictionSeq: Long? = null,
        var lastEvidence: String? = null,
    )

    private val hypothesisStates: LinkedHashMap<String, HypothesisRecord> =
        LinkedHashMap<String, HypothesisRecord>().also {
            for (h in plan.hypotheses) it[h.name] = HypothesisRecord()
        }

    // FEEL outputs (action.Feel + action.SetVar values surface here; SetVar also
    // shadows into planLocals for downstream actions / until evaluation).
    private val feelOutputs: LinkedHashMap<String, JsonElement> = linkedMapOf()
    private val planLocals: LinkedHashMap<String, FeelValue> = linkedMapOf()
    private val snapshotRefs: MutableList<String> = mutableListOf()
    private val stepErrors: MutableList<StepError> = mutableListOf()
    private val installedBpIds: MutableList<Int> = mutableListOf()
    private val setupResults: MutableList<SetupResult> = mutableListOf()

    // Leaky bucket. Bucket size = maxEventRate (per second). Tokens refill linearly.
    private val rateLimiter = LeakyBucket(plan.maxEventRate)

    // Step-pending flag — set when a step_* action fires so the next StepEvent in the
    // loop is recognized as the step landing.
    @Volatile private var stepPending: Boolean = false

    // Terminal state.
    private val terminalDeferred: CompletableDeferred<PlanReport> = CompletableDeferred()
    @Volatile private var terminalStatus: String? = null
    @Volatile private var terminalReason: String? = null

    @Volatile private var runJob: Job? = null
    @Volatile private var watchdogJob: Job? = null

    // M4 — wall-clock of the last input event that reached the handler chain. Read by
    // the watchdog. Updated when `eventsHandled` is incremented.
    @Volatile private var lastProgressEventMs: Long = planStartMs

    // Real dispatcher for FEEL dbg.* calls.
    private val dispatcher: RealDbgDispatcher = RealDbgDispatcher(planStartMs)

    // ------- launch + lifecycle -------

    companion object {
        /**
         * Validate + claim + create + start the executor. Returns the handle. Throws
         * [ToolError] for setup failures (NotAttached, VmInPlan, PlanInvalid). The
         * handle is published on [Session.activePlan] / [Session.activePlanId].
         */
        fun launch(plan: Plan, scope: CoroutineScope): PlanRunHandle {
            // Re-validate. compile() returns Errors with structured details — surface as
            // PlanInvalid so the agent can fix and re-submit.
            val compile = PlanCompiler.compile(plan)
            if (compile is PlanCompiler.Result.Errors) {
                val first = compile.errors.firstOrNull()
                throw ToolError(
                    errorCode = ErrorCode.PlanInvalid,
                    message = "Plan failed validation: ${first?.message ?: "see errors"}",
                    hint = first?.hint,
                    currentState = first?.path,
                )
            }

            // Require an attached VM. Throws NotAttached.
            Session.requireAttached()

            // Claim the slot — throws VmInPlan if already claimed.
            val planId = UUID.randomUUID().toString()
            VmCoordinator.claimPlan(planId)

            val executor = PlanExecutor(plan, planId, scope)
            // Publish on Session BEFORE starting the run loop so pause/abort dispatch
            // works the instant the agent gets the planId back.
            Session.activePlanId = planId
            Session.activePlan = executor
            // Clear any leftover snapshot refs from a prior plan.
            runCatching { Session.planStore.clearForPlan(planId) }

            executor.start()
            return executor
        }
    }

    /**
     * Synchronous launch phase (M6 hang mitigation v1.7.1). Returns in <100ms typical:
     *   1. Emit the `plan_progress(started, phase="claimed")` event so the agent sees
     *      that the slot is owned before any potentially-blocking JDI calls run.
     *   2. Spin up the watchdog coroutine (M4) and the executor coroutine. The executor
     *      coroutine handles setup-BP installation asynchronously — if BP install
     *      blocks against a wedged VM, the customer's `run_debug_plan` call still
     *      returns immediately.
     *
     * Setup-phase errors land as a terminal `plan_progress(error, phase="setup", ...)`
     * via the normal [terminate] path; the report's `errors` list carries the structured
     * per-entry failure.
     */
    private fun start() {
        // Phase 1 (sync) — claim is already done by launch(); publish the started signal
        // before any blocking work. `phase: "claimed"` distinguishes this from the
        // subsequent `setup_complete` event the executor emits once setup BPs are wired.
        emitProgress("started", buildJsonObject {
            put("plan_id", JsonPrimitive(planId))
            put("plan_name", JsonPrimitive(planName))
            put("phase", JsonPrimitive("claimed"))
        })

        // Phase 2 (async) — setup BP install + watchdog + run loop happen on the
        // executor coroutine so the launch path returns to the caller immediately.
        runJob = scope.launch {
            try {
                // Sub-phase: setup BP install. Each entry is independently best-effort
                // so one bad pattern doesn't take out the whole plan; failures surface
                // via stepErrors and the report.
                val vm = Session.vm
                if (vm == null) {
                    emitProgress("error", buildJsonObject {
                        put("phase", JsonPrimitive("setup"))
                        put("message", JsonPrimitive("vm not attached at start"))
                    })
                    terminate("error", "vm not attached at start")
                    return@launch
                }
                for ((idx, entry) in plan.setup.withIndex()) {
                    try {
                        val result = installSetup(idx, entry)
                        setupResults += result
                        if (result.status == "error") {
                            // Mirror into stepErrors so the structured report's `errors`
                            // list still shows setup failures the existing way.
                            stepErrors += StepError(
                                eventSeq = 0,
                                op = "setup[$idx]",
                                code = "setup_failed",
                                message = result.error ?: "install failed",
                            )
                        }
                    } catch (t: Throwable) {
                        // Belt-and-braces: installSetup is supposed to convert throws
                        // into status="error" results, but if anything escapes (e.g.,
                        // Session.requireAttached throwing), still report it cleanly.
                        val errMsg = t.message ?: t::class.simpleName ?: "unknown"
                        setupResults += SetupResult(
                            index = idx,
                            kind = setupKindOf(entry),
                            status = "error",
                            target = setupTargetOf(entry),
                            error = errMsg,
                        )
                        stepErrors += StepError(
                            eventSeq = 0,
                            op = "setup[$idx]",
                            code = (t as? ToolError)?.errorCode?.code ?: "setup_failed",
                            message = errMsg,
                        )
                    }
                }
                val resolvedCount = setupResults.count { it.status == "resolved" }
                val deferredCount = setupResults.count { it.status == "deferred" }
                val errorCount = setupResults.count { it.status == "error" }
                emitProgress("setup_complete", buildJsonObject {
                    // installed_count = "armed" (resolved + deferred). Both fire when
                    // the target executes; the agent treats them identically for whether
                    // the plan has live BPs in the VM.
                    put("installed_count", JsonPrimitive(resolvedCount + deferredCount))
                    put("resolved_count", JsonPrimitive(resolvedCount))
                    put("deferred_count", JsonPrimitive(deferredCount))
                    put("error_count", JsonPrimitive(errorCount))
                    if (deferredCount > 0) {
                        // List of deferred targets so the agent can see "these classes
                        // haven't loaded yet — trigger the code path or wait". Without
                        // this, deferred-only setups look identical to all-failed.
                        val targets = setupResults.filter { it.status == "deferred" }.map { it.target }
                        put("deferred_targets", kotlinx.serialization.json.JsonArray(targets.map { JsonPrimitive(it) }))
                    }
                })

                // Sub-phase: watchdog (M4). Independent sibling job. Cancelled when
                // terminalDeferred completes.
                watchdogJob = scope.launch {
                    runWatchdog()
                }

                // Sub-phase: the run loop.
                runLoop()
            } catch (t: Throwable) {
                if (!isTerminal) {
                    stepErrors += StepError(
                        eventSeq = eventSeq.get(),
                        op = "run_loop",
                        code = (t as? ToolError)?.errorCode?.code ?: "internal",
                        message = t.message ?: t::class.simpleName ?: "unknown",
                    )
                    terminate("error", t.message ?: "executor crashed")
                }
            }
        }
    }

    // ------- M4 watchdog -------

    /**
     * v1.7.1 M4 — stuck-watchdog. Polls every 500ms (or watchdogInterval(thresholdMs)
     * for small thresholds in tests) and emits a `plan_progress(stuck, ...)` if the
     * input-event side has been silent past the threshold. Signal-only: the watchdog
     * does NOT auto-abort. The agent can re-check via wait_for_event and decide.
     *
     * Lifecycle: runs while !isTerminal. The terminate() path completes
     * terminalDeferred, which the watchdog observes via the `isTerminal` flag (volatile
     * read) and exits the polling loop. If the executor coroutine is cancelled, the
     * watchdog's parent scope is the same and it will be cancelled too.
     */
    private suspend fun runWatchdog() {
        val thresholdMs = plan.stuckDetectMs ?: Plan.DEFAULT_STUCK_DETECT_MS
        // Use a tighter poll for small thresholds so the test pattern (stuck_detect_ms=500)
        // gets at least 2-3 ticks during the test window. For production thresholds
        // (90s default), 500ms polling is fine.
        val pollMs = pollIntervalForThreshold(thresholdMs)
        var lastSignaledAt: Long = 0
        while (!isTerminal) {
            try {
                delay(pollMs)
            } catch (_: kotlinx.coroutines.CancellationException) {
                return
            }
            if (isTerminal) return
            val now = System.currentTimeMillis()
            val ageMs = now - lastProgressEventMs
            if (ageMs >= thresholdMs) {
                val handled = eventsHandled
                // Re-emit no more often than once per threshold window so we don't spam
                // the channel; the agent gets a steady, low-rate signal.
                if (now - lastSignaledAt >= thresholdMs) {
                    val reason = if (handled == 0L) {
                        "no events received since plan start"
                    } else {
                        "no new events"
                    }
                    runCatching {
                        emitProgress("stuck", buildJsonObject {
                            put("last_event_age_ms", JsonPrimitive(ageMs))
                            put("events_handled", JsonPrimitive(handled))
                            put("reason", JsonPrimitive(reason))
                        })
                    }
                    lastSignaledAt = now
                }
            }
        }
    }

    private fun pollIntervalForThreshold(thresholdMs: Long): Long =
        Watchdog.pollIntervalForThreshold(thresholdMs)

    /**
     * v1.7.1 M5 hang mitigation — 3-stage cooperative-then-forceful pause.
     *
     * Stage 1 (cooperative, ≤2s): mark terminate("paused", reason). The executor's
     *   run loop notices `isTerminal=true` next iteration and exits naturally.
     * Stage 2 (force release, ≤2s): force-remove plan-owned breakpoints directly.
     *   Pause-vs-abort intentionally does NOT call vm.resume() here — pause's contract
     *   is that the VM stays paused at the last handled event.
     * Stage 3 (force complete, immediate): manually construct a partial report with
     *   `status: "paused"` and force-complete the terminal deferred so the caller
     *   returns within bounded wall-clock. The executor coroutine is left to die on
     *   its own — acceptable leak vs. customer hang.
     */
    override suspend fun pause(reason: String): PlanReport {
        return forceTerminate(
            targetStatus = "paused",
            reason = reason,
            forceResumeVm = false,
        )
    }

    /**
     * v1.7.1 M5 hang mitigation — 3-stage cooperative-then-forceful abort.
     * Same stages as [pause] except Stage 2 also force-resumes the VM if paused,
     * matching abort's contract that the VM be released after the plan ends.
     */
    override suspend fun abort(reason: String): PlanReport {
        return forceTerminate(
            targetStatus = "aborted",
            reason = reason,
            forceResumeVm = true,
        )
    }

    override suspend fun awaitTerminal(): PlanReport = terminalDeferred.await()

    /**
     * v1.7.1 M5 — the 3-stage force-terminate state machine. Stays in this method
     * until either [terminalDeferred] completes naturally or we force-complete it.
     *
     * The actual stage transitions are factored into [ForceAbortStateMachine] for
     * unit-testability — the helper is a pure decision machine the executor drives
     * with real waits between stages.
     */
    internal suspend fun forceTerminate(
        targetStatus: String,
        reason: String,
        forceResumeVm: Boolean,
        stage1TimeoutMs: Long = 2_000,
        stage2TimeoutMs: Long = 2_000,
    ): PlanReport {
        // If already terminal, just return the existing report. Idempotent.
        if (terminalDeferred.isCompleted) {
            return terminalDeferred.await()
        }

        // Stage 1 — cooperative cancel. Mark terminal and let the run loop unwind.
        terminate(targetStatus, reason)
        // Cancel the executor coroutine in case it's parked in awaitEvent. terminate()
        // already kicks off cancellation, but we explicitly wait here.
        val s1 = withTimeoutOrNull(stage1TimeoutMs) { terminalDeferred.await() }
        if (s1 != null) return s1

        // Stage 2 — force release. terminate() already best-effort removed plan BPs +
        // released the coordinator slot, but if the executor is wedged inside
        // installSetup() or eval_method(), terminate's cleanup may not have run. Do
        // it again directly here, then optionally force-resume the VM for abort.
        log.warn("plan {} did not honor cooperative cancel within {}ms; force-releasing", planId, stage1TimeoutMs)
        runCatching {
            val vm = Session.vm
            if (vm != null) BreakpointManager.removeByPlan(vm, planId)
        }
        if (forceResumeVm) {
            runCatching {
                val vm = Session.vm
                val thread = Session.pausedThread
                if (vm != null && thread != null) {
                    vm.resume()
                    Session.pausedThread = null
                    Session.state = com.acendas.androiddebugger.SessionState.ATTACHED_RUNNING
                    Session.bumpVmStateVersion()
                }
            }
        }
        val s2 = withTimeoutOrNull(stage2TimeoutMs) { terminalDeferred.await() }
        if (s2 != null) return s2

        // Stage 3 — force complete. Build a partial report manually and complete the
        // deferred directly. NOTE: this can leak the executor coroutine because we
        // can't kill a non-cancellable JDI blocking call (vm.eventQueue().remove()).
        // Acceptable trade vs. customer hang — the leaked coroutine will be GC'd when
        // the next detach + reattach destroys the session scope.
        log.warn(
            "plan {} did not terminate after stage 2 force-release; force-completing terminal deferred (executor coroutine may leak until next session reset)",
            planId,
        )
        val forceReason = "force-killed; executor coroutine did not honor cooperative cancel"
        val report = buildReport(targetStatus, forceReason)
        // Beat any in-flight terminate path: complete() is idempotent (returns false
        // if already complete) so a natural completion winning the race is fine.
        terminalDeferred.complete(report)
        // Re-run the cleanup that terminate() would have done, defensively.
        runCatching { VmCoordinator.releasePlan(planId) }
        synchronized(Session) {
            if (Session.activePlanId == planId) Session.activePlanId = null
            if (Session.activePlan === this) Session.activePlan = null
        }
        isTerminal = true
        return terminalDeferred.await()
    }

    // ------- the run loop -------

    private suspend fun runLoop() {
        val eventLoop = Session.eventLoop
        if (eventLoop == null) {
            terminate("error", "event_loop unavailable")
            return
        }

        while (!isTerminal) {
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) {
                terminate("timeout", "timeout_ms elapsed")
                break
            }

            val event = try {
                eventLoop.awaitEvent(remaining) { ev ->
                    when (ev) {
                        is DebugEvent.Stopped -> true
                        is DebugEvent.Exception -> true
                        is DebugEvent.ClassPrepare -> true
                        is DebugEvent.Disconnect -> true
                        is DebugEvent.Exit -> true
                        else -> false
                    }
                }
            } catch (_: VMDisconnectedException) {
                terminate("error", "vm_disconnected")
                break
            }

            if (event == null) {
                terminate("timeout", "timeout_ms elapsed")
                break
            }
            if (event is DebugEvent.Disconnect) {
                terminate("error", "vm_disconnected")
                break
            }
            if (event is DebugEvent.Exit) {
                terminate("completed", "vm_exit")
                break
            }

            eventsTotal++

            // Leaky-bucket throttle. If we're over the rate, drop this event AND resume
            // the VM (the JDI event-loop already decided it's a stop, so we must release).
            if (!rateLimiter.allow()) {
                eventsDropped++
                runCatching { resumeVmIfPaused() }
                continue
            }

            val seq = eventSeq.incrementAndGet()
            eventsHandled++
            // M4 — record wall-clock for the watchdog. Input-liveness only; we do NOT
            // bump this on `emitProgress` (those are output, not input).
            lastProgressEventMs = System.currentTimeMillis()

            try {
                handleEvent(event, seq)
            } catch (te: ToolError) {
                stepErrors += StepError(
                    eventSeq = seq,
                    op = "handler",
                    code = te.errorCode.code,
                    message = te.message ?: "ToolError",
                )
            } catch (t: Throwable) {
                stepErrors += StepError(
                    eventSeq = seq,
                    op = "handler",
                    code = "internal",
                    message = t.message ?: t::class.simpleName ?: "unknown",
                )
            }

            // until-check.
            val untilExpr = plan.until
            if (!isTerminal && untilExpr != null) {
                val untilVal = evalFeelSafe(untilExpr, event, seq, op = "until")
                if (untilVal != null && feelTruthy(untilVal)) {
                    terminate("completed", "until satisfied")
                    break
                }
            }
            if (!isTerminal && plan.maxEvents != null && eventsHandled >= plan.maxEvents) {
                terminate("completed", "max_events reached")
                break
            }
        }
    }

    private suspend fun handleEvent(event: DebugEvent, seq: Long) {
        // Grade hypotheses regardless of whether a handler matches — hypotheses are
        // independent of on_event dispatch.
        gradeHypotheses(event, seq)

        // First-matching on_event block fires.
        val handler = findHandler(event, seq)
        if (handler == null) {
            emitProgress("event_handled", buildJsonObject {
                put("matched", JsonPrimitive(false))
                put("seq", JsonPrimitive(seq))
                put("event_type", JsonPrimitive(event.type))
            })
            // No handler — default resume so the VM doesn't sit forever.
            runCatching { resumeVmIfPaused() }
            return
        }

        emitProgress("event_handled", buildJsonObject {
            put("matched", JsonPrimitive(true))
            put("seq", JsonPrimitive(seq))
            put("event_type", JsonPrimitive(event.type))
        })

        // Execute actions in order. Track whether any action took ownership of the
        // resume so we know whether to apply the default resume at the end.
        var explicitResumeTaken = false
        for ((aidx, action) in handler.actions.withIndex()) {
            if (isTerminal) break
            try {
                val outcome = executeAction(action, event, seq)
                if (outcome == ActionOutcome.ResumeOrStep) explicitResumeTaken = true
                if (outcome == ActionOutcome.Terminal) return
            } catch (te: ToolError) {
                stepErrors += StepError(
                    eventSeq = seq,
                    op = "${action::class.simpleName ?: "action"}[$aidx]",
                    code = te.errorCode.code,
                    message = te.message ?: "ToolError",
                )
            } catch (t: Throwable) {
                stepErrors += StepError(
                    eventSeq = seq,
                    op = "${action::class.simpleName ?: "action"}[$aidx]",
                    code = "internal",
                    message = t.message ?: t::class.simpleName ?: "unknown",
                )
            }
        }

        // Default resume policy: if no explicit resume/step/yield fired, resume.
        if (!isTerminal && !explicitResumeTaken) {
            runCatching { resumeVmIfPaused() }
        }
    }

    private enum class ActionOutcome { Continue, ResumeOrStep, Terminal }

    private suspend fun executeAction(action: Action, event: DebugEvent, seq: Long): ActionOutcome {
        when (action) {
            is Action.Snapshot -> {
                val thread = Session.pausedThread
                if (thread == null) {
                    stepErrors += StepError(
                        eventSeq = seq,
                        op = "snapshot",
                        code = "vm_running",
                        message = "no paused thread for snapshot",
                    )
                    return ActionOutcome.Continue
                }
                val snap = SnapshotBuilder().build(
                    thread = thread,
                    depth = action.depth,
                    event = "PLAN",
                    pausedReason = "plan_snapshot",
                )
                val ref = Session.planStore.put(planId, seq, snap, plan.maxSnapshots)
                snapshotRefs += ref
                emitProgress("snapshot_captured", buildJsonObject {
                    put("seq", JsonPrimitive(seq))
                    put("ref", JsonPrimitive(ref))
                })
                return ActionOutcome.Continue
            }
            is Action.Feel -> {
                val value = evalFeelSafe(action.feel, event, seq, op = "feel:${action.asName}")
                if (value != null) {
                    feelOutputs[action.asName] = FeelValueRenderer.toJson(value)
                    planLocals[action.asName] = value
                    emitProgress("feel_evaluated", buildJsonObject {
                        put("seq", JsonPrimitive(seq))
                        put("name", JsonPrimitive(action.asName))
                    })
                }
                return ActionOutcome.Continue
            }
            is Action.SetVar -> {
                val value = evalFeelSafe(action.value, event, seq, op = "set_var:${action.name}")
                if (value != null) {
                    planLocals[action.name] = value
                    feelOutputs[action.name] = FeelValueRenderer.toJson(value)
                }
                return ActionOutcome.Continue
            }
            is Action.EvalMethod -> {
                val thread = Session.pausedThread
                if (thread == null) {
                    stepErrors += StepError(
                        eventSeq = seq,
                        op = "eval_method:${action.method}",
                        code = "vm_running",
                        message = "no paused thread for eval_method",
                    )
                    return ActionOutcome.Continue
                }
                val vm = Session.vm
                if (vm == null) {
                    stepErrors += StepError(
                        eventSeq = seq,
                        op = "eval_method:${action.method}",
                        code = "not_attached",
                        message = "vm gone",
                    )
                    return ActionOutcome.Continue
                }
                // Args: each arg string is evaluated as a FEEL expression in the
                // current event context, then coerced to JDI Value via FeelValueCodec.
                val args = mutableListOf<Value?>()
                var argFailed = false
                for ((i, argSrc) in action.args.withIndex()) {
                    val fv = evalFeelSafe(argSrc, event, seq, op = "eval_method:${action.method}:arg[$i]")
                    if (fv == null) {
                        argFailed = true; break
                    }
                    try {
                        args += FeelValueCodec.fromFeel(fv, vm)
                    } catch (e: com.acendas.androiddebugger.inspection.UnsupportedCoercionException) {
                        stepErrors += StepError(
                            eventSeq = seq,
                            op = "eval_method:${action.method}:arg[$i]",
                            code = "invalid_target",
                            message = e.message ?: "coercion failed",
                        )
                        argFailed = true; break
                    }
                }
                if (argFailed) return ActionOutcome.Continue

                val result = try {
                    Evaluator.invokeRaw(
                        thread = thread,
                        frameIdx = 0,
                        target = action.target,
                        methodName = action.method,
                        args = args,
                        allowMutation = action.allowMutation,
                    )
                } catch (te: ToolError) {
                    stepErrors += StepError(
                        eventSeq = seq,
                        op = "eval_method:${action.method}",
                        code = te.errorCode.code,
                        message = te.message ?: "eval_method failed",
                    )
                    return ActionOutcome.Continue
                }
                feelOutputs[action.asName] = FeelValueRenderer.toJson(result)
                planLocals[action.asName] = result
                return ActionOutcome.Continue
            }
            is Action.Resume -> {
                if (action.resume) {
                    runCatching { resumeVmIfPaused() }
                }
                return ActionOutcome.ResumeOrStep
            }
            Action.StepOver -> {
                requestStep(Stepper.Depth.Over, seq, "step_over")
                return ActionOutcome.ResumeOrStep
            }
            Action.StepInto -> {
                requestStep(Stepper.Depth.Into, seq, "step_into")
                return ActionOutcome.ResumeOrStep
            }
            Action.StepOut -> {
                requestStep(Stepper.Depth.Out, seq, "step_out")
                return ActionOutcome.ResumeOrStep
            }
            is Action.YieldWhen -> {
                val v = evalFeelSafe(action.condition, event, seq, op = "yield_when")
                if (v != null && feelTruthy(v)) {
                    emitProgress("yielded", buildJsonObject {
                        put("seq", JsonPrimitive(seq))
                        put("reason", JsonPrimitive(action.reason))
                    })
                    terminate("paused", action.reason)
                    return ActionOutcome.Terminal
                }
                return ActionOutcome.Continue
            }
            is Action.AbortWhen -> {
                val v = evalFeelSafe(action.condition, event, seq, op = "abort_when")
                if (v != null && feelTruthy(v)) {
                    runCatching { resumeVmIfPaused() }
                    emitProgress("aborted", buildJsonObject {
                        put("seq", JsonPrimitive(seq))
                        put("reason", JsonPrimitive(action.reason))
                    })
                    terminate("aborted", action.reason)
                    return ActionOutcome.Terminal
                }
                return ActionOutcome.Continue
            }
            is Action.Log -> {
                emitProgress("log", buildJsonObject {
                    put("seq", JsonPrimitive(seq))
                    put("message", JsonPrimitive(action.log))
                })
                return ActionOutcome.Continue
            }
        }
    }

    private fun requestStep(depth: Stepper.Depth, seq: Long, op: String) {
        val vm = Session.vm
        val thread = Session.pausedThread
        if (vm == null || thread == null) {
            stepErrors += StepError(
                eventSeq = seq,
                op = op,
                code = if (vm == null) "not_attached" else "vm_running",
                message = "no paused thread for $op",
            )
            return
        }
        try {
            Stepper.startStep(vm, thread, depth)
            stepPending = true
            runCatching { vm.resume() }
            Session.bumpVmStateVersion()
        } catch (t: Throwable) {
            stepErrors += StepError(
                eventSeq = seq,
                op = op,
                code = "internal",
                message = t.message ?: "step setup failed",
            )
        }
    }

    // ------- hypothesis state machine -------

    private fun gradeHypotheses(event: DebugEvent, seq: Long) {
        for (h in plan.hypotheses) {
            val record = hypothesisStates[h.name] ?: continue
            val whenVal = evalFeelSafe(h.whenExpr, event, seq, op = "hypothesis:${h.name}:when")
                ?: continue
            if (!feelTruthy(whenVal)) continue

            record.triggerCount++
            if (record.firstTriggerSeq == null) record.firstTriggerSeq = seq

            val expectVal = evalFeelSafe(h.expect, event, seq, op = "hypothesis:${h.name}:expect")
                ?: continue
            val expectTruthy = feelTruthy(expectVal)

            val priorState = record.state
            val newState = when (priorState) {
                HState.Inconclusive -> if (expectTruthy) HState.Matched else HState.Contradicted
                HState.Matched -> if (expectTruthy) HState.Matched else HState.Contradicted
                HState.Contradicted -> HState.Contradicted
            }
            if (newState == HState.Contradicted && record.contradictionSeq == null) {
                record.contradictionSeq = seq
                record.lastEvidence = "expect=false at seq=$seq"
            }
            if (newState != priorState) {
                record.state = newState
                emitProgress("hypothesis_graded", buildJsonObject {
                    put("name", JsonPrimitive(h.name))
                    put("grade", JsonPrimitive(newState.name.lowercase()))
                    put("seq", JsonPrimitive(seq))
                })
            }
        }
    }

    // ------- FEEL eval -------

    private fun evalFeelSafe(expr: String, event: DebugEvent?, seq: Long, op: String): FeelValue? {
        return try {
            evalFeel(expr, event)
        } catch (te: ToolError) {
            stepErrors += StepError(
                eventSeq = seq,
                op = op,
                code = te.errorCode.code,
                message = te.message ?: "feel eval failed",
            )
            null
        } catch (t: Throwable) {
            stepErrors += StepError(
                eventSeq = seq,
                op = op,
                code = "feel_eval",
                message = t.message ?: t::class.simpleName ?: "unknown",
            )
            null
        }
    }

    private fun evalFeel(expr: String, event: DebugEvent?): FeelValue {
        // Build context: paused-frame context if available, else fresh.
        val thread = Session.pausedThread
        val ctx: FeelContext = if (thread != null) {
            val frame = runCatching { thread.frame(0) }.getOrNull()
            if (frame != null) FeelContextBuilder.build(frame) else FeelContext()
        } else {
            FeelContext()
        }

        // Inject plan locals.
        for ((name, value) in planLocals) {
            if (!ctx.hasVariable(name)) ctx.setVariable(name, value)
        }

        // Inject synthetic event context.
        if (event != null) {
            ctx.setVariable("event", buildEventContext(event))
        }

        // dbg.* preprocess.
        val rewrite = DebuggerContext.preprocess(expr, dispatcher)
        DebuggerContext.inject(ctx, rewrite.injected)

        return FeelExpression.parse(rewrite.expression).evaluate(ctx)
    }

    private fun buildEventContext(event: DebugEvent): FeelValue.Context {
        val entries = linkedMapOf<String, FeelValue>()
        entries["kind"] = FeelValue.Text(event.type)
        when (event) {
            is DebugEvent.Stopped -> {
                entries["reason"] = FeelValue.Text(event.reason)
                event.threadId?.let { entries["thread_id"] = FeelValue.Number(BigDecimal.valueOf(it)) }
                event.threadName?.let { entries["thread_name"] = FeelValue.Text(it) }
                event.breakpointId?.let { entries["breakpoint_id"] = FeelValue.Number(BigDecimal.valueOf(it.toLong())) }
                event.location?.let { entries["location"] = FeelValue.Text(it) }
            }
            is DebugEvent.Exception -> {
                event.exceptionClass?.let { entries["exception_class"] = FeelValue.Text(it) }
                event.exceptionId?.let { entries["exception_id"] = FeelValue.Text(it) }
                event.threadId?.let { entries["thread_id"] = FeelValue.Number(BigDecimal.valueOf(it)) }
                entries["caught"] = FeelValue.Boolean(event.caught)
            }
            is DebugEvent.ClassPrepare -> {
                entries["class_name"] = FeelValue.Text(event.className)
            }
            else -> { /* type only */ }
        }
        return FeelValue.Context(entries)
    }

    private fun feelTruthy(v: FeelValue): Boolean = when (v) {
        is FeelValue.Boolean -> v.value
        FeelValue.Null -> false
        is FeelValue.Number -> v.value.signum() != 0
        is FeelValue.Text -> v.value.isNotEmpty()
        is FeelValue.List -> v.elements.isNotEmpty()
        else -> true
    }

    // ------- on_event match -------

    private fun findHandler(event: DebugEvent, seq: Long): OnEvent? {
        for (block in plan.onEvent) {
            val matchExpr = block.match.ifBlank { "true" }
            val v = evalFeelSafe(matchExpr, event, seq, op = "on_event.match") ?: continue
            if (feelTruthy(v)) return block
        }
        return null
    }

    // ------- setup install -------

    private fun installSetup(idx: Int, entry: SetupEntry): SetupResult {
        val vm = Session.requireAttached()
        val mintedId = BreakpointManager.mintId()
        val kind = setupKindOf(entry)
        val target = setupTargetOf(entry)
        val install: BreakpointInstaller.InstallResult = try {
            when (entry) {
                is SetupEntry.LineBp -> BreakpointInstaller.installLine(
                    vm = vm,
                    id = mintedId,
                    file = entry.file,
                    line = entry.line,
                    condition = entry.condition,
                    hitCount = entry.hitCount,
                    logMessage = entry.logMessage,
                    planId = planId,
                )
                is SetupEntry.ExceptionBp -> BreakpointInstaller.installException(
                    vm = vm,
                    id = mintedId,
                    exceptionClass = entry.classPattern,
                    caught = entry.caught,
                    uncaught = entry.uncaught,
                    planId = planId,
                )
                is SetupEntry.MethodEntryBp -> BreakpointInstaller.installMethodEntry(
                    vm = vm,
                    id = mintedId,
                    methodClass = entry.methodClass,
                    methodName = entry.methodName,
                    planId = planId,
                )
                is SetupEntry.MethodExitBp -> BreakpointInstaller.installMethodExit(
                    vm = vm,
                    id = mintedId,
                    methodClass = entry.methodClass,
                    methodName = entry.methodName,
                    planId = planId,
                )
                is SetupEntry.FieldWatchpoint -> BreakpointInstaller.installFieldWatchpoint(
                    vm = vm,
                    id = mintedId,
                    fieldClass = entry.fieldClass,
                    fieldName = entry.fieldName,
                    wantAccess = entry.wantAccess,
                    wantModification = entry.wantModification,
                    planId = planId,
                )
                is SetupEntry.ClassLoadBp -> BreakpointInstaller.installClassLoad(
                    vm = vm,
                    id = mintedId,
                    classPattern = entry.classPattern,
                    planId = planId,
                )
            }
        } catch (t: Throwable) {
            return SetupResult(
                index = idx,
                kind = kind,
                status = "error",
                target = target,
                error = t.message ?: t::class.simpleName ?: "install failed",
            )
        }
        installedBpIds += install.meta.id
        // installLine/installException return `deferred=true` when no live class
        // matched — the BP is armed via ClassPrepareRequest and will install when
        // the class loads. Treat that as a first-class non-error outcome.
        return SetupResult(
            index = idx,
            kind = kind,
            status = if (install.deferred) "deferred" else "resolved",
            bpId = install.meta.id,
            target = target,
            resolvedLocations = install.resolvedLocations.takeIf { entry is SetupEntry.LineBp },
        )
    }

    private fun setupKindOf(entry: SetupEntry): String = when (entry) {
        is SetupEntry.LineBp -> "line"
        is SetupEntry.ExceptionBp -> "exception"
        is SetupEntry.MethodEntryBp -> "method_entry"
        is SetupEntry.MethodExitBp -> "method_exit"
        is SetupEntry.FieldWatchpoint -> "field"
        is SetupEntry.ClassLoadBp -> "class_load"
    }

    private fun setupTargetOf(entry: SetupEntry): String = when (entry) {
        is SetupEntry.LineBp -> "${entry.file}:${entry.line}"
        is SetupEntry.ExceptionBp -> entry.classPattern ?: "*"
        is SetupEntry.MethodEntryBp -> "${entry.methodClass}.${entry.methodName}"
        is SetupEntry.MethodExitBp -> "${entry.methodClass}.${entry.methodName}"
        is SetupEntry.FieldWatchpoint -> "${entry.fieldClass}#${entry.fieldName}"
        is SetupEntry.ClassLoadBp -> entry.classPattern
    }

    // ------- resume helpers -------

    private fun resumeVmIfPaused() {
        val vm = Session.vm ?: return
        val thread = Session.pausedThread ?: return
        // Use VM.resume rather than thread.resume so suspend-all events also clear.
        runCatching {
            vm.resume()
            Session.pausedThread = null
            Session.state = com.acendas.androiddebugger.SessionState.ATTACHED_RUNNING
            Session.bumpVmStateVersion()
        }
    }

    // ------- terminal handling -------

    private fun terminate(status: String, reason: String) {
        // Idempotent: first call wins.
        synchronized(this) {
            if (isTerminal) return
            terminalStatus = status
            terminalReason = reason
            isTerminal = true
        }

        // Build and complete the report BEFORE clearing Session state, so a follow-up
        // claim can't race the deferred completion.
        val report = buildReport(status, reason)
        terminalDeferred.complete(report)

        // Plan-progress event mirroring the terminal status.
        runCatching {
            emitProgress(status, buildJsonObject {
                put("reason", JsonPrimitive(reason))
                put("duration_ms", JsonPrimitive(report.durationMs))
                put("events_handled", JsonPrimitive(report.eventsHandled))
            })
        }

        // Tear down plan-owned breakpoints. Best-effort.
        runCatching {
            val vm = Session.vm
            if (vm != null) BreakpointManager.removeByPlan(vm, planId)
        }

        // Release the VmCoordinator plan slot.
        runCatching { VmCoordinator.releasePlan(planId) }

        // Clear Session pointers — but only if still pointing at us, defending against
        // a fresh plan that already claimed.
        synchronized(Session) {
            if (Session.activePlanId == planId) Session.activePlanId = null
            if (Session.activePlan === this) Session.activePlan = null
        }

        // Cancel the watchdog (M4) — once we're terminal, it should stop polling.
        val wd = watchdogJob
        if (wd != null) {
            runCatching { wd.cancel() }
        }

        // Cancel the run-loop job if still active (e.g., abort() called from outside).
        val job = runJob
        if (job != null) {
            scope.launch { runCatching { job.cancelAndJoin() } }
        }
    }

    internal fun buildReport(status: String, reason: String): PlanReport {
        val grades = plan.hypotheses.map { h ->
            val rec = hypothesisStates[h.name] ?: HypothesisRecord()
            HypothesisGrade(
                name = h.name,
                grade = when (rec.state) {
                    HState.Matched -> "matched"
                    HState.Contradicted -> "contradicted"
                    HState.Inconclusive -> "inconclusive"
                },
                firstTriggerSeq = rec.firstTriggerSeq,
                triggerCount = rec.triggerCount,
                contradictionSeq = rec.contradictionSeq,
                evidence = rec.lastEvidence,
            )
        }
        val truncated = eventsDropped > 0 || Session.planStore.truncated
        return PlanReport(
            planId = planId,
            planName = planName,
            status = status,
            reason = reason,
            durationMs = System.currentTimeMillis() - planStartMs,
            eventsTotal = eventsTotal,
            eventsHandled = eventsHandled,
            eventsDropped = eventsDropped,
            truncated = truncated,
            hypothesisGrades = grades,
            feelOutputs = feelOutputs.toMap(),
            snapshotRefs = snapshotRefs.toList(),
            errors = stepErrors.toList(),
            setupResults = setupResults.toList(),
        )
    }

    // ------- progress emission -------

    private fun emitProgress(subtype: String, data: JsonObject = JsonObject(emptyMap())) {
        val seq = progressSeq.incrementAndGet()
        val ev = DebugEvent.PlanProgress(
            planId = planId,
            seq = seq,
            subtype = subtype,
            data = data,
        )
        val loop = Session.eventLoop
        if (loop != null) {
            runCatching { loop.dispatchSynthetic(ev) }
        } else {
            // No event loop — best-effort send into the channel directly.
            runCatching { Session.eventChannel?.trySend(ev) }
        }
    }

    // ------- leaky bucket -------

    /**
     * Per-second token bucket. Default-sized at [Plan.maxEventRate]; refills linearly
     * with elapsed wall-clock time. Lock-free single-writer model — only the run loop
     * touches this.
     */
    internal class LeakyBucket(private val perSec: Int) {
        private var tokens: Double = perSec.toDouble()
        private var lastRefillMs: Long = System.currentTimeMillis()

        fun allow(): Boolean {
            val now = System.currentTimeMillis()
            val elapsed = (now - lastRefillMs).coerceAtLeast(0).toDouble()
            // tokens-per-ms * elapsed-ms.
            tokens = (tokens + (perSec.toDouble() / 1000.0) * elapsed).coerceAtMost(perSec.toDouble())
            lastRefillMs = now
            if (tokens >= 1.0) {
                tokens -= 1.0
                return true
            }
            return false
        }
    }

    // ------- M4 watchdog — pure helper -------

    /**
     * Pure decision logic for the M4 stuck-watchdog. Decoupled from the executor so
     * unit tests can drive it deterministically without a coroutine scope. The
     * executor's `runWatchdog()` is the live driver; this object owns the math.
     */
    internal object Watchdog {
        /**
         * Choose a poll interval given the threshold. Returns a value short enough to
         * give a small-threshold test (`stuck_detect_ms=500`) at least 2-3 ticks, and
         * long enough for a production threshold (90s) not to waste CPU. Cap at 500ms
         * floor for default-sized thresholds; for very small thresholds, scale down so
         * the first stuck signal lands within ~2x the threshold.
         */
        fun pollIntervalForThreshold(thresholdMs: Long): Long {
            val target = thresholdMs / 4L
            return target.coerceIn(50L, 500L)
        }

        /** Should we emit a stuck signal? `now` is wall-clock; `lastEventMs` is last input. */
        fun shouldSignal(
            now: Long,
            lastEventMs: Long,
            thresholdMs: Long,
            lastSignaledAt: Long,
        ): Boolean {
            val age = now - lastEventMs
            if (age < thresholdMs) return false
            return (now - lastSignaledAt) >= thresholdMs
        }

        /** Stuck reason string based on whether any events have been handled. */
        fun reasonForState(eventsHandled: Long): String =
            if (eventsHandled == 0L) "no events received since plan start" else "no new events"
    }

    // ------- M5 force-abort — pure state machine -------

    /**
     * Pure state machine for the M5 3-stage abort/pause. Each transition is a function
     * of the previous stage outcome (did the cooperative cancel land in time, did the
     * forced release land in time). The executor calls into [next] with the observed
     * outcome of waiting for the terminal deferred at the prior stage. Decoupled so
     * unit tests don't need a coroutine context.
     */
    internal object ForceAbortStateMachine {
        enum class Stage { Cooperative, ForceRelease, ForceComplete, Done }

        /** What to do next given the prior stage's `naturallyCompleted` observation. */
        fun next(stage: Stage, naturallyCompleted: Boolean): Stage = when (stage) {
            Stage.Cooperative -> if (naturallyCompleted) Stage.Done else Stage.ForceRelease
            Stage.ForceRelease -> if (naturallyCompleted) Stage.Done else Stage.ForceComplete
            Stage.ForceComplete -> Stage.Done
            Stage.Done -> Stage.Done
        }

        /** Reason string carried in the synthetic force-completed report at Stage 3. */
        const val FORCE_REASON: String =
            "force-killed; executor coroutine did not honor cooperative cancel"
    }

    // ------- test hooks -------

    /** Test-only: snapshot internal counters + state. */
    internal data class InternalState(
        val eventsTotal: Long,
        val eventsHandled: Long,
        val eventsDropped: Long,
        val hypothesisStates: Map<String, HState>,
        val feelOutputs: Map<String, JsonElement>,
        val snapshotRefs: List<String>,
        val errors: List<StepError>,
        val isTerminal: Boolean,
        val terminalStatus: String?,
        val terminalReason: String?,
    )

    internal fun snapshotInternalState(): InternalState = InternalState(
        eventsTotal = eventsTotal,
        eventsHandled = eventsHandled,
        eventsDropped = eventsDropped,
        hypothesisStates = hypothesisStates.mapValues { it.value.state },
        feelOutputs = feelOutputs.toMap(),
        snapshotRefs = snapshotRefs.toList(),
        errors = stepErrors.toList(),
        isTerminal = isTerminal,
        terminalStatus = terminalStatus,
        terminalReason = terminalReason,
    )
}

/**
 * Test-only constructor + helpers exposed via top-level factory so unit tests can
 * exercise the hypothesis state machine and report-building logic without spinning up
 * a real coroutine scope, a JDI VM, or the Session singleton.
 */
internal object PlanExecutorTestSupport {

    /** Apply one synthetic event through the hypothesis grading state machine. */
    fun gradeHypotheses(
        states: MutableMap<String, PlanExecutor.HState>,
        hypotheses: List<Hypothesis>,
        whenEval: (Hypothesis) -> Boolean,
        expectEval: (Hypothesis) -> Boolean,
        seq: Long,
        firstTriggerSeqs: MutableMap<String, Long>,
        triggerCounts: MutableMap<String, Long>,
        contradictionSeqs: MutableMap<String, Long>,
    ) {
        for (h in hypotheses) {
            if (!whenEval(h)) continue
            triggerCounts[h.name] = (triggerCounts[h.name] ?: 0L) + 1L
            firstTriggerSeqs.putIfAbsent(h.name, seq)
            val expectTruthy = expectEval(h)
            val prior = states[h.name] ?: PlanExecutor.HState.Inconclusive
            val next = when (prior) {
                PlanExecutor.HState.Inconclusive -> if (expectTruthy) PlanExecutor.HState.Matched else PlanExecutor.HState.Contradicted
                PlanExecutor.HState.Matched -> if (expectTruthy) PlanExecutor.HState.Matched else PlanExecutor.HState.Contradicted
                PlanExecutor.HState.Contradicted -> PlanExecutor.HState.Contradicted
            }
            if (next == PlanExecutor.HState.Contradicted) {
                contradictionSeqs.putIfAbsent(h.name, seq)
            }
            states[h.name] = next
        }
    }
}
