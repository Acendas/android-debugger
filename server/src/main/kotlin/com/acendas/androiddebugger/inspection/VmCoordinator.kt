package com.acendas.androiddebugger.inspection

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide single-flight mutex for operations that touch JDI's `invokeMethod`
 * surface or JVMTI's `RedefineClasses`. Per v1.5 spec §8.
 *
 * **What it guards:**
 *
 *   - `eval_method` (JDI `invokeMethod`) — touched the VM by resuming threads to
 *     run the callee. Concurrent calls deadlock JDI; documented in Oracle's docs.
 *   - `hot_swap_class` / `hot_swap_classes` / `hot_swap_revert` (JVMTI
 *     `RedefineClasses`) — mutates the class metadata that `invokeMethod` reads
 *     when resolving method bodies. Running both concurrently means evaluating
 *     `obj.method()` could land on either the old or new method body — order
 *     unobservable.
 *   - Future: any other coordinator-aware mutation we add.
 *
 * **Semantics:**
 *
 *   - Caller passes a short `operation` label (e.g., `"eval_method"`, `"hot_swap"`).
 *     This label is exposed via [currentOperation] so a busy-reply tool can name
 *     what's holding the lock.
 *   - Acquire is bounded by [timeoutMs]; on timeout we throw a structured
 *     [ToolError] with [ErrorCode.VmBusy] naming the in-flight operation.
 *   - Re-entry from the same operation is **not** supported — recursive calls
 *     would mean a single tool body deadlocks against itself. If a tool body
 *     needs both eval and hot-swap, structure it as two sequential
 *     `withExclusiveAccess` blocks.
 *
 * **Why not just reuse `Session.mutex`?** That mutex is broader — every MCP tool
 * call holds it for the duration of the call, including reads. The coordinator
 * is finer: it only fires for the duration of the VM-mutating sub-op inside a
 * tool body. Reads (frame_snapshot, get_locals, etc.) shouldn't block a HotSwap
 * any more than they have to, and a HotSwap shouldn't block a parallel read.
 * The two mutexes are nested: outer `Session.mutex` serializes tool boundaries;
 * inner `VmCoordinator` serializes mutating sub-ops across all tools.
 */
object VmCoordinator {

    private val mutex: Mutex = Mutex()

    /** Name of the operation currently holding the lock, or null if free. */
    private val current: AtomicReference<String?> = AtomicReference(null)

    /** Default timeout — long enough for a single dex + redefine roundtrip on a slow device. */
    const val DEFAULT_TIMEOUT_MS: Long = 15_000

    /** Eval-method-specific timeout — matches the pre-v1.5 Evaluator timeout. */
    const val EVAL_TIMEOUT_MS: Long = 10_000

    /**
     * Run [block] under the coordinator's mutex, naming the operation [operation].
     * **Fail-fast on contention** — if the mutex is held when called, immediately
     * throws [ToolError] with [ErrorCode.VmBusy] including the in-flight operation
     * name. No queueing. Queuing two `invokeMethod`s ties up the JDI thread for
     * minutes; the agent (Claude) should see the busy reply, adjust, retry.
     *
     * [timeoutMs] governs how long the BODY may run, not how long to wait for
     * the lock. If the body exceeds the timeout, throws cancellation.
     *
     * Suspend variant — preferred. Coroutine-friendly.
     */
    suspend fun <T> withExclusiveAccess(
        operation: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: suspend () -> T,
    ): T {
        if (!mutex.tryLock()) {
            throw ToolError(
                errorCode = ErrorCode.VmBusy,
                message = "Another evaluation is already in flight on this session.",
                hint = "VmCoordinator is single-flight to prevent JDI invokeMethod / JVMTI " +
                    "RedefineClasses races. Wait for the in-flight `${current.get() ?: "op"}` to finish.",
                currentState = current.get(),
            )
        }
        current.set(operation)
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) { block() }
        } finally {
            current.set(null)
            mutex.unlock()
        }
    }

    /**
     * Blocking variant for callers that can't suspend (e.g., the JDI event-loop
     * thread running a conditional breakpoint's predicate). Wraps the suspend
     * variant in `runBlocking`. Avoid from suspend contexts — use [withExclusiveAccess].
     */
    fun <T> withExclusiveAccessBlocking(
        operation: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: () -> T,
    ): T = runBlocking {
        withExclusiveAccess(operation, timeoutMs) { block() }
    }

    /** Test/debug hook — read the in-flight op name without changing state. */
    fun currentOperation(): String? = current.get()

    // -----------------------------------------------------------------------------------
    // v1.7 — long-lived plan claim.
    //
    // The v1.5/1.6 mutex above is a *brief* single-flight gate for `invokeMethod` /
    // `RedefineClasses` — held for the duration of one call. Debug Plans run for
    // seconds-to-minutes and must coexist with read-only inspection tools. So plans
    // claim a separate AtomicReference instead of holding the mutex.
    //
    // Semantics:
    //   - Exactly one plan can be claimed at a time per process.
    //   - Read-only tools (frame_snapshot, get_locals, inspect_object, evaluate FEEL,
    //     ...) pass through regardless of the claim — they read the captured snapshot
    //     store or inspect the paused VM safely.
    //   - State-mutating tools (eval_method, set_field, resume, step_*, add_*_breakpoint
    //     not owned by the plan, hot_swap_*) check `currentPlanId()` and either reject
    //     (`vm_in_plan` via runTool's `allowsDuringPlan` gate) or — for plan-internal
    //     calls — go through the existing `withExclusiveAccess` mutex inside the plan
    //     executor itself.
    //   - claim/release are idempotent for the same planId; calling release with a
    //     different planId is a no-op (defensive against double-release races).
    // -----------------------------------------------------------------------------------

    private val activePlan: AtomicReference<String?> = AtomicReference(null)

    /**
     * Claim the long-lived plan slot. Throws [ToolError] with [ErrorCode.VmInPlan] if
     * another plan is already active.
     */
    fun claimPlan(planId: String) {
        if (!activePlan.compareAndSet(null, planId)) {
            val existing = activePlan.get()
            throw ToolError(
                errorCode = ErrorCode.VmInPlan,
                message = "Plan `$existing` is already running; cannot claim `$planId`.",
                hint = "Call pause_plan / abort_plan on the active plan first.",
                currentState = "PLAN_ACTIVE:$existing",
            )
        }
    }

    /**
     * Release the plan slot. Idempotent — releasing the wrong planId is a no-op (defends
     * against late releases from finalizers / cancellation paths).
     */
    fun releasePlan(planId: String) {
        activePlan.compareAndSet(planId, null)
    }

    /** Read the in-flight plan id, or null if no plan is active. */
    fun currentPlanId(): String? = activePlan.get()

    /** Test hook — reset both lock state (for isolated test runs). */
    internal fun resetForTest() {
        if (mutex.isLocked) runCatching { mutex.unlock() }
        current.set(null)
        activePlan.set(null)
    }
}
