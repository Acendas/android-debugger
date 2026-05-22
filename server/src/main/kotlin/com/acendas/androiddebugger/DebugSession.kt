package com.acendas.androiddebugger

import com.acendas.androiddebugger.adb.Adb
import com.acendas.androiddebugger.events.DebugEvent
import com.acendas.androiddebugger.events.EventLoop
import com.acendas.androiddebugger.inspection.WatchManager
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle phases the [DebugSession] moves through. */
enum class SessionState {
    UNATTACHED,
    ATTACHING,
    ATTACHED_RUNNING,
    ATTACHED_PAUSED,
    DETACHING,
}

/**
 * Canonical "current attached state" for the MCP server. One per process; lives behind
 * the [Session] singleton. Tools acquire [mutex] to serialize calls into the JDI VM,
 * which is not safe under concurrent modification.
 *
 * Per Story 0.1.4.
 */
open class DebugSession {

    @Volatile var vm: VirtualMachine? = null
    @Volatile var forwardedPort: Int? = null
    @Volatile var serial: String? = null
    @Volatile var packageName: String? = null
    @Volatile var pid: Int? = null
    @Volatile var state: SessionState = SessionState.UNATTACHED
    @Volatile var attachedAt: Instant? = null

    /**
     * The currently-paused thread, if any. Set by the event loop when a SUSPEND_*
     * event fires; cleared by `resume` and stepping operations. Only meaningful when
     * [state] == [SessionState.ATTACHED_PAUSED].
     */
    @Volatile var pausedThread: ThreadReference? = null

    /**
     * Cached probed JDI/ART capabilities, populated by `attach` after `Capabilities.probe()`.
     * Read by [Capability.requireCapability] so capability-gated tools never need to
     * re-probe. Null when unattached. Per Story 7.1.1.
     */
    @Volatile var capabilities: kotlinx.serialization.json.JsonObject? = null

    /**
     * The Android UI thread (`name == "main"`), cached at attach time so the ANR watchdog
     * doesn't have to re-scan `vm.allThreads()` on every tick. Per Story 7.1.3.
     */
    @Volatile var mainThread: ThreadReference? = null

    /**
     * Per-thread step budget tracker. Mapped from `thread.uniqueID()` to a counter of
     * consecutive steps inside the same method. Reset on resume / run_to_line / breakpoint
     * hit / frame change. Per Story 7.1.4.
     */
    val stepBudget: com.acendas.androiddebugger.execution.StepBudget = com.acendas.androiddebugger.execution.StepBudget()

    /**
     * Monotonic counter incremented on every state-changing operation (attach, resume,
     * step, detach). The snapshot cache uses `(thread, vmStateVersion)` to invalidate.
     * Per Task 0.1.4.2.
     */
    val vmStateVersion: AtomicLong = AtomicLong(0L)

    // Story 2.1.2: frame-snapshot cache. Returned verbatim from `frame_snapshot` if
    // the (thread, vmStateVersion) key still matches; killed on every state change.
    @Volatile var lastSnapshotKey: Pair<Long, Long>? = null
    @Volatile var lastSnapshotPayload: kotlinx.serialization.json.JsonObject? = null
    @Volatile var lastSnapshotDepth: Int = 0

    /** Single mutex serializing tool calls into the VM. Use from coroutines. */
    val mutex: Mutex = Mutex()

    /** Shared adb facade — all process invocations route through here. */
    val adb: Adb = Adb()

    /**
     * Per-session watch registry. Per Story 5.1.1 — re-evaluated on every pause via the
     * snapshot builder; cleared on `detach`. Survives `bumpVmStateVersion` (watches don't
     * vanish on resume/step), only on session reset.
     */
    val watchManager: WatchManager = WatchManager()

    /**
     * Per-session logcat buffers, keyed by minted buffer id. Per Story 6.1.1 —
     * `tail_logcat` registers; `read_logcat` reads; `stop_logcat` and `detach` close.
     * Held here (not in the AndroidTools registry) so `Session.detach()` can shut every
     * buffer down without each tool keeping its own static state.
     */
    val logcatBuffers: com.acendas.androiddebugger.android.LogcatRegistry =
        com.acendas.androiddebugger.android.LogcatRegistry()

    /**
     * Channel of translated debug events fed by [eventLoop]. Capacity 128 with
     * DROP_OLDEST so a slow consumer never blocks the JDI thread (would freeze the
     * target). Created on attach; closed on detach. Per Task 4.1.3.1.
     */
    @Volatile var eventChannel: Channel<DebugEvent>? = null

    /** Coroutine job pumping the JDI event queue into [eventChannel]. */
    @Volatile var eventLoopJob: Job? = null

    /** Holds the event-loop instance so we can call `stop()` cleanly on detach. */
    @Volatile var eventLoop: EventLoop? = null

    /**
     * Stuck-JDI recovery wrapper (v1.2.4). Set on attach via reflection to capture
     * the underlying `java.net.Socket`; consulted by tools that wrap blocking JDI
     * calls when timeouts pile up. See `JdiSocketWedgeRecovery` for the full design.
     */
    @Volatile var socketWedgeRecovery: com.acendas.androiddebugger.jdi.JdiSocketWedgeRecovery? = null

    /** ANR watchdog instance — runs while attached, watches main-thread suspend duration. */
    @Volatile var anrWatchdog: com.acendas.androiddebugger.events.AnrWatchdog? = null

    /**
     * v1.4 — JVMTI agent state if loaded for this session, else null. Carries the
     * capability map, host socket path, and crash-record info for the next
     * [com.acendas.androiddebugger.tools.AgentTools] `agent_info` call.
     */
    @Volatile var agentState: com.acendas.androiddebugger.jvmti.AgentState? = null

    /** v1.4 — open JSON-RPC client over the agent's Unix socket. Closed in [reset]. */
    @Volatile var agentClient: com.acendas.androiddebugger.jvmti.AgentClient? = null

    /**
     * v1.5 — detected at attach via [com.acendas.androiddebugger.hotswap.MinifyDetector].
     * When true, `hot_swap_class` refuses with `minified_build_unsupported`.
     */
    @Volatile var minifyDetected: Boolean = false

    /**
     * v1.5 — Android API level of the attached device, used to drive the d8
     * dexer's `--min-api` flag so the produced dex matches what ART can read.
     * Populated at attach time from `getprop ro.build.version.sdk`. Falls back
     * to 26 (v1.5 floor) on probe failure.
     */
    @Volatile var apiLevel: Int = 26

    /**
     * v1.5 — pre-swap class-bytes snapshot store, keyed by FQN. Captured on
     * first redefine of each class via the agent's ClassFileLoadHook cache;
     * consumed by `hot_swap_revert`. Per-session; cleared on detach.
     */
    val snapshotStore: com.acendas.androiddebugger.hotswap.SnapshotStore =
        com.acendas.androiddebugger.hotswap.SnapshotStore()

    /**
     * v1.5 — server-side dexer instance, configured for the attached device's
     * API level. Created at attach; reused for every hot_swap call this session.
     * Null when unattached.
     */
    @Volatile var dexer: com.acendas.androiddebugger.hotswap.Dexer? = null

    /**
     * v1.6 — active method-trace buffer ids on the JVMTI agent. Populated by
     * [com.acendas.androiddebugger.tools.MethodTraceTools] on `start_method_trace`;
     * removed on `stop_method_trace`. Drained at detach via
     * `AgentMethodTrace.stopAll` so traces don't outlive the session.
     */
    @Volatile var methodTraceBufferIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * v1.6 — active alloc-trace buffer ids on the JVMTI agent. Same lifecycle
     * pattern as [methodTraceBufferIds]. `agent.stop_all_traces` covers both
     * surfaces in a single RPC so detach only needs one call.
     */
    @Volatile var allocTraceBufferIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * v1.6 — coroutine scope for per-breakpoint JVMTI poll loops (e.g., method
     * breakpoints auto-routed through `agent.method_trace_*`). Initialized in
     * [com.acendas.androiddebugger.jvmti.JvmtiAgentLauncher.launch] after the
     * agent client is set; cancelled in [reset] before the agent client is
     * nulled out.
     */
    @Volatile var v16PollScope: kotlinx.coroutines.CoroutineScope? = null

    /**
     * v1.7 — coroutine scope owning the in-flight Debug Plan executor coroutine. Mirrors
     * the [v16PollScope] pattern: created when the event loop starts (so the plan can
     * dispatch into it the moment the agent calls `run_debug_plan`), and cancelled on
     * [reset] before the agent client / JDI VM tear down. SupervisorJob so a crash in
     * the plan run-loop doesn't propagate up and cancel other session-scoped coroutines.
     */
    @Volatile var planScope: kotlinx.coroutines.CoroutineScope? = null

    /**
     * v1.7 — id of the currently-executing Debug Plan, or null if no plan is running.
     * Mirrors [com.acendas.androiddebugger.inspection.VmCoordinator.currentPlanId] but
     * exposed here for cheap read access from the [runTool] gate without grabbing the
     * coordinator lock. Set when `run_debug_plan` claims the slot; cleared on plan
     * completion / abort / pause / yield / timeout / error.
     */
    @Volatile var activePlanId: String? = null

    /**
     * v1.7 — handle on the in-flight plan execution. Owned by [com.acendas.androiddebugger.plans.PlanExecutor];
     * exposed here so `pause_plan` / `abort_plan` / `detach` can signal the executor.
     */
    @Volatile var activePlan: com.acendas.androiddebugger.plans.PlanRunHandle? = null

    /**
     * v1.7 — per-plan snapshot store. Holds FrameSnapshots captured during plan
     * execution keyed by (plan_id, event_seq), survives the per-pause vmStateVersion
     * eviction, cleared on next plan start / detach. Pinned here so `inspect_object`
     * and follow-up reads can drill into snapshots after the plan terminates.
     */
    val planStore: com.acendas.androiddebugger.plans.PlanStore = com.acendas.androiddebugger.plans.PlanStore()

    /** Bump after any operation that changed the VM's run/pause state or frame stack. */
    fun bumpVmStateVersion(): Long {
        // Any state change invalidates the snapshot cache. Per Task 2.1.2.3.
        lastSnapshotKey = null
        lastSnapshotPayload = null
        return vmStateVersion.incrementAndGet()
    }

    /** Reset to fully unattached state. Called by `detach` and on transient disconnect. */
    open fun reset() {
        vm = null
        forwardedPort = null
        serial = null
        packageName = null
        pid = null
        state = SessionState.UNATTACHED
        attachedAt = null
        pausedThread = null
        capabilities = null
        mainThread = null
        stepBudget.clear()
        lastSnapshotKey = null
        lastSnapshotPayload = null
        lastSnapshotDepth = 0
        // Don't reset vmStateVersion — keep monotonic across sessions to avoid cache reuse confusion.
        com.acendas.androiddebugger.inspection.ObjectIdMint.clear()
        // Per Story 5.1.1: watches vanish on detach. Agent re-adds in the new session.
        watchManager.clear()
        // Per Story 6.1.1.5: stop every active logcat buffer so we don't leak adb subprocesses.
        runCatching { logcatBuffers.stopAll() }
        // Per Phase 3: breakpoints + logpoint buffer don't survive detach.
        com.acendas.androiddebugger.breakpoints.BreakpointManager.clear()
        com.acendas.androiddebugger.breakpoints.LogpointBuffer.clear()
        eventLoop = null
        eventLoopJob = null
        eventChannel = null
        anrWatchdog = null
        socketWedgeRecovery = null
        // v1.6 — close any active trace sessions before the agent socket dies.
        // `agent.stop_all_traces` covers BOTH method and alloc traces in one RPC,
        // so we only need to fire it once.
        runCatching {
            val client = agentClient
            if (client != null && (methodTraceBufferIds.isNotEmpty() || allocTraceBufferIds.isNotEmpty())) {
                runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(2_000) {
                        com.acendas.androiddebugger.jvmti.AgentMethodTrace.stopAll(client)
                    }
                }
            }
        }
        methodTraceBufferIds.clear()
        allocTraceBufferIds.clear()
        // v1.6 — cancel per-bp poll coroutines BEFORE the agent client closes so
        // they don't see EOF mid-read and surface noisy errors. Best-effort.
        runCatching { v16PollScope?.cancel() }
        v16PollScope = null
        // v1.7 — cancel the plan executor scope. The active plan handle below is also
        // aborted; cancelling the scope itself releases any leftover coroutine state
        // even on the error path where abort() raced past the run-loop.
        runCatching { planScope?.cancel() }
        planScope = null
        // v1.7 — abort any in-flight plan + release the coordinator slot + clear store.
        // detach mid-plan is "plan aborts" by definition. abort() is suspend so we
        // bound it with runBlocking + short timeout — losing a partial report is fine
        // during forced teardown but we don't want detach to hang on a wedged executor.
        // HACK: re-evaluate when MCP SDK ships suspend-friendly transports. Per R-03.
        runCatching {
            val handle = activePlan
            if (handle != null && !handle.isTerminal) {
                runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(2_000) { handle.abort("session detach") }
                }
            }
        }
        val priorPlanId = activePlanId
        if (priorPlanId != null) {
            runCatching { com.acendas.androiddebugger.inspection.VmCoordinator.releasePlan(priorPlanId) }
        }
        activePlanId = null
        activePlan = null
        runCatching { planStore.clear() }
        // v1.4 — close the agent IPC socket but DON'T attempt to unload the
        // agent (JVMTI doesn't support unload). The agent stays loaded in the
        // app process until the process dies. Next attach to the same app
        // reuses it via a fresh socket connection.
        runCatching { agentClient?.close() }
        agentClient = null
        agentState = null
        // v1.5 — wipe hot-swap state. Snapshots are session-scoped per spec §13;
        // app restart invalidates them anyway. The agent's own ClassFileLoadHook
        // cache lives in the target process and survives our detach (until the
        // app process dies).
        minifyDetected = false
        apiLevel = 26
        dexer = null
        snapshotStore.clear()
    }

    /**
     * Handle a transient JDI [com.sun.jdi.VMDisconnectedException]. Posts a Disconnect
     * event into the channel before tearing down so a `wait_for_event` consumer notices,
     * releases the adb forward, and resets to UNATTACHED. Subsequent tool calls error
     * with `code: vm_disconnected`. Idempotent. Per Story 7.1.5.
     */
    fun handleDisconnect() {
        val ch = eventChannel
        runCatching { ch?.trySend(com.acendas.androiddebugger.events.DebugEvent.Disconnect) }
        val priorPort = forwardedPort
        val priorSerial = serial
        // Stop the event loop + ANR watchdog before nulling out the channel — otherwise
        // the pump can race a half-disposed VM and emit noise. Best-effort everywhere.
        runCatching { stopEventLoop() }
        // HACK: re-evaluate when the MCP SDK ships proper suspend handlers and we can
        // expose a suspend variant of handleDisconnect. handleDisconnect is invoked from
        // [runTool]'s VMDisconnectedException catch (which is now a suspend context, so
        // it could in principle be a suspend) and from the JVM shutdown hook (which is
        // a plain Thread and cannot suspend). Keep `runBlocking` here for the shutdown
        // path. Per R-03.
        runCatching { anrWatchdog?.let { runBlocking { it.stop() } } }
        // We don't dispose() the VM here — the JDI side already saw the disconnect, and
        // calling dispose on a dead VM throws.
        if (priorPort != null) {
            runCatching { adb.removeForward(priorSerial, priorPort) }
        }
        reset()
    }

    /**
     * Start the JDI event-queue pump for the freshly attached [vm]. Called from
     * lifecycle's `attach` AFTER the VM is wired into the session. Per Task 4.1.3.1.
     */
    fun startEventLoop(vm: VirtualMachine) {
        val channel = Channel<DebugEvent>(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val loop = EventLoop(vm, channel)
        eventChannel = channel
        eventLoop = loop
        eventLoopJob = loop.start()
        // v1.7 — stand up the plan executor scope alongside the event loop so
        // `run_debug_plan` can dispatch without bootstrapping its own scope. Sized
        // identically to v16PollScope (SupervisorJob + Dispatchers.IO).
        planScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
    }

    /**
     * Stop the event loop and close the channel. Idempotent.
     *
     * **Ordering rule (per v1.2.2 detach-hang fix):** call this AFTER `vm.dispose()`,
     * not before. The event-loop coroutine is blocked on `vm.eventQueue().remove()` —
     * a JDI blocking call that does NOT react to coroutine cancellation between events.
     * If we `cancelAndJoin` before disposing, the join waits forever because the only
     * thing that would unblock `queue.remove()` is `vm.dispose()` throwing
     * `VMDisconnectedException` — and we're waiting to call dispose. Disposing first
     * lets the loop exit naturally; the join then becomes a no-op.
     *
     * Wrapped in a 3 s timeout as a belt-and-suspenders defense in case the loop
     * still doesn't terminate (transport already broken, JDI quirk, etc.) — far
     * better to leak the coroutine than to hang `detach` forever.
     */
    fun stopEventLoop() {
        val loop = eventLoop
        val channel = eventChannel
        eventLoop = null
        eventLoopJob = null
        eventChannel = null
        if (loop != null) {
            // HACK: re-evaluate when the MCP SDK ships proper suspend handlers — this is
            // called from `detach` (suspend-friendly) and from the JVM shutdown hook
            // (plain Thread, cannot suspend), so we currently keep `runBlocking` for the
            // shutdown path. Per R-03.
            runCatching {
                runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(3_000) { loop.stop() }
                }
            }
        }
        channel?.close()
    }

    /**
     * Cleanly disconnect the debugger: `vm.dispose()` (never `exit`), release the adb
     * forward, reset the session. Idempotent — calling when unattached is fine.
     * Called from the [detach] tool and the JVM shutdown hook. Per Story 1.1.4.
     */
    fun detach(): DetachResult {
        val wasAttached = vm != null
        val priorPort = forwardedPort
        val priorSerial = serial
        if (wasAttached) {
            state = SessionState.DETACHING
            // ANR watchdog is independent — stop it first.
            // HACK: re-evaluate when the MCP SDK ships proper suspend handlers — detach
            // is called from a suspend tool body but also from the JVM shutdown hook
            // which cannot suspend, so `runBlocking` stays for now. Per R-03.
            runCatching { anrWatchdog?.let { runBlocking { it.stop() } } }
            // **Order matters (per v1.2.2 detach-hang fix).** Dispose the VM FIRST.
            // This causes `vm.eventQueue().remove()` (running in the event-loop coroutine)
            // to throw `VMDisconnectedException`, letting the loop exit naturally.
            // If we tried to stop the event loop first, `cancelAndJoin` would wait
            // forever for the blocking JDI call to react to cancellation — JDI's
            // `queue.remove()` doesn't check coroutine cancellation between events.
            runCatching { vm?.dispose() }
            // Now the loop has terminated (or is about to); join it cleanly.
            // `stopEventLoop` has its own 3 s timeout so this can't hang.
            stopEventLoop()
        } else {
            // Defensive: event loop should not be running when not attached, but
            // make sure we don't leak coroutine state on a re-entrant detach.
            // HACK: re-evaluate when the MCP SDK ships proper suspend handlers. Per R-03.
            runCatching { anrWatchdog?.let { runBlocking { it.stop() } } }
            stopEventLoop()
        }
        if (priorPort != null) {
            runCatching { adb.removeForward(priorSerial, priorPort) }
        }
        reset()
        return DetachResult(wasAttached = wasAttached, releasedPort = priorPort)
    }

    /**
     * Throws [ToolError] with [ErrorCode.NotAttached] if no VM is attached.
     * Returns the attached VM otherwise. Per Task 0.1.4.3.
     */
    fun requireAttached(): VirtualMachine = vm ?: throw ToolError(
        errorCode = ErrorCode.NotAttached,
        message = "No debug session is attached to a running app.",
        hint = "Run /android-debugger:attach first.",
        currentState = state.name,
    )

    /**
     * Throws [ToolError] with [ErrorCode.VmRunning] if no thread is currently paused.
     * Returns the paused thread otherwise. Per Task 0.1.4.3.
     */
    fun requirePaused(): ThreadReference = pausedThread ?: throw ToolError(
        errorCode = ErrorCode.VmRunning,
        message = "VM is not paused.",
        hint = "Wait for a breakpoint or call pause first.",
        currentState = state.name,
    )
}

/** Result returned by [DebugSession.detach]. */
data class DetachResult(val wasAttached: Boolean, val releasedPort: Int?)

/**
 * Process-wide singleton. Tool registrations call into this directly. Per Task 0.1.4.4.
 */
object Session : DebugSession() {
    /**
     * Override `reset` to also call `releaseAllForwards()` — defensive cleanup for any
     * forwards we created outside of the [forwardedPort] singleton (shouldn't happen in
     * v1.0, but JVM shutdown is the wrong time to leak adb forwards).
     */
    override fun reset() {
        super.reset()
        runCatching { adb.releaseAllForwards() }
    }
}
