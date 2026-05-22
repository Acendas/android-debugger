package com.acendas.androiddebugger

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sealed enumeration of error codes that flow back to MCP. Tools never throw to MCP —
 * they return a structured `{ ok: false, code, message, hint?, current_state? }` payload.
 * The agent reacts on `code`, not on prose. Per Task 0.1.5.1.
 */
enum class ErrorCode(val code: String) {
    NotAttached("not_attached"),
    AlreadyAttached("already_attached"),
    VmRunning("vm_running"),
    VmPaused("vm_paused"),
    VmBusy("vm_busy"),
    AbsentLocalVariables("absent_local_variables"),
    CapabilityUnavailable("capability_unavailable"),
    InvalidTarget("invalid_target"),
    AdbError("adb_error"),
    AttachFailed("attach_failed"),
    VmDisconnected("vm_disconnected"),
    /**
     * Per BR-02: the resolved JDI method name looks like a state mutator
     * (`set*`, `clear*`, `apply`, etc.) and the caller did not pass
     * `allow_mutation: true`. Read-only inspection is the default; the agent must
     * opt in explicitly to invoke methods that change app state.
     */
    VmMutationRefused("vm_mutation_refused"),
    /**
     * Per v1.7 Debug Plan spec §Concurrency: a state-mutating tool was called while a
     * Debug Plan is executing. Read-only inspection (frame_snapshot, get_locals,
     * inspect_object, etc.) passes through; mutations (eval_method, set_field, resume,
     * step_*, add_*_breakpoint outside the plan's setup, hot_swap_*) are gated. The
     * error message names the active plan id so the agent can `pause_plan(id)` to take
     * over interactively or `abort_plan(id)` to release the VM.
     */
    VmInPlan("vm_in_plan"),
    /** v1.7: a plan compile failed — schema, FEEL parse, BP target, or bounds. */
    PlanInvalid("plan_invalid"),
    /** v1.7: a referenced plan_id is unknown (no active or recently-terminated plan). */
    PlanUnknown("plan_unknown"),
    /** v1.7: persisted plan file not found / corrupt / schema-version mismatch. */
    PlanNotFound("plan_not_found"),
    /**
     * v1.7.1 (M1 hang mitigation): a tool body exceeded its wall-clock budget. The
     * caller sees this instead of an infinite MCP wait. Hint names the elapsed budget
     * so the agent can decide to retry with a fresh attach or abandon the operation.
     */
    ToolTimeout("tool_timeout"),
    /**
     * v1.7.1 (M2 hang mitigation): JDI socket attach exceeded its 10s ceiling and was
     * force-closed via JdiSocketWedgeRecovery. The remote VM never completed the
     * JDWP handshake — usually app process gone, JDWP port stale, or device wedged.
     */
    AttachTimeout("attach_timeout"),
    Internal("internal"),
}

/**
 * Typed exception thrown by helpers like [DebugSession.requireAttached]. Caught at the
 * tool boundary by [runTool] and converted to a structured MCP reply. Never reaches the
 * MCP transport.
 */
class ToolError(
    val errorCode: ErrorCode,
    message: String,
    val hint: String? = null,
    val currentState: String? = null,
) : RuntimeException(message)

/** Build an `ok: true` MCP reply with optional extra fields. Per Task 0.1.5.3. */
inline fun toolOk(block: JsonObjectBuilder.() -> Unit = {}): CallToolResult {
    val payload = buildJsonObject {
        put("ok", true)
        block()
    }
    return CallToolResult(content = listOf(TextContent(text = payload.toString())))
}

/** Build an `ok: false` MCP reply with a structured error code. Per Task 0.1.5.3. */
fun toolErr(
    code: ErrorCode,
    message: String,
    hint: String? = null,
    currentState: String? = null,
): CallToolResult {
    val payload = buildJsonObject {
        put("ok", false)
        put("code", code.code)
        put("message", message)
        if (hint != null) put("hint", hint)
        if (currentState != null) put("current_state", currentState)
    }
    return CallToolResult(content = listOf(TextContent(text = payload.toString())))
}

/**
 * Wrap a tool body so any [ToolError] becomes a structured MCP reply, and any unexpected
 * exception becomes a generic [ErrorCode.Internal] reply rather than escaping to the
 * transport. Per Task 0.1.5.2.
 *
 * Story 7.1.5: any [com.sun.jdi.VMDisconnectedException] is centralized here. We route
 * it through `Session.handleDisconnect()` (which posts a Disconnect event, releases the
 * adb forward, and resets state) and translate it to the structured `vm_disconnected`
 * error so the agent gets a clear "rerun /android-debugger:attach" hint instead of an
 * opaque JDI stack.
 *
 * Per R-01 (v1.1.0 review): every tool body runs under [Session.mutex] so JDI calls are
 * serialized. JDI is not thread-safe across event handling, and the architecture spec
 * promises tool-level serialization. The mutex is acquired with a short timeout via
 * [tryAcquireMutex]; a busy mutex returns a structured [ErrorCode.VmBusy] reply rather
 * than blocking the coroutine pool indefinitely. [Evaluator] uses its own private mutex
 * inside the JDI thread context so there is no recursion against this outer lock.
 */
suspend inline fun runTool(
    allowsDuringPlan: Boolean = false,
    toolName: String = "tool",
    wallClockMs: Long = DEFAULT_WALL_CLOCK_MS,
    crossinline block: suspend () -> CallToolResult,
): CallToolResult = try {
    // v1.7: state-mutating tools are gated while a Debug Plan is executing. Read-only
    // inspection passes through (allowsDuringPlan = true). The plan executor itself
    // owns plan-scoped mutations; user calls during a plan must pause_plan / abort_plan
    // first.
    val activePlan = Session.activePlanId
    if (!allowsDuringPlan && activePlan != null) {
        toolErr(
            code = ErrorCode.VmInPlan,
            message = "Tool `$toolName` cannot run while plan `$activePlan` is executing.",
            hint = "Call pause_plan(plan_id) to take over interactively, or abort_plan(plan_id) to release the VM.",
            currentState = "PLAN_ACTIVE:$activePlan",
        )
    } else {
        val acquired = Session.mutex.tryAcquireWithTimeout(MUTEX_ACQUIRE_TIMEOUT_MS)
        if (!acquired) {
            toolErr(
                code = ErrorCode.VmBusy,
                message = "Another tool call is currently holding the JDI session lock.",
                hint = "Wait for the in-flight call to finish, or check connection_status.",
            )
        } else {
            try {
                // v1.7.1 (M1): wall-clock budget on every tool body. If the block exceeds
                // the budget, we return tool_timeout (or vm_disconnected if the VM is now
                // gone — M8 piggybacks here). Prevents a single wedged JDI call from
                // hanging the MCP transport indefinitely. Callers that legitimately need
                // longer (heap dump, hot_swap) opt into a larger wallClockMs.
                val result = kotlinx.coroutines.withTimeoutOrNull(wallClockMs) { block() }
                if (result == null) {
                    // v1.7.1 (M8): on timeout, check whether the VM is still attached.
                    // A timed-out call against a disconnected VM is almost certainly a
                    // stale JDWP socket — surface vm_disconnected so the agent re-attaches
                    // instead of retrying the same dead session.
                    if (Session.vm == null) {
                        toolErr(
                            code = ErrorCode.VmDisconnected,
                            message = "Tool `$toolName` timed out after ${wallClockMs}ms and the VM is no longer attached.",
                            hint = "Rerun /android-debugger:ad-attach.",
                        )
                    } else {
                        toolErr(
                            code = ErrorCode.ToolTimeout,
                            message = "Tool `$toolName` exceeded its wall-clock budget (${wallClockMs}ms).",
                            hint = "If the VM is wedged, call detach to release and re-attach. Long-running tools (heap dump) accept higher wallClockMs.",
                            currentState = Session.state.name,
                        )
                    }
                } else {
                    result
                }
            } finally {
                Session.mutex.unlock()
            }
        }
    }
} catch (e: ToolError) {
    toolErr(
        code = e.errorCode,
        message = e.message ?: e.errorCode.code,
        hint = e.hint,
        currentState = e.currentState,
    )
} catch (e: com.sun.jdi.VMDisconnectedException) {
    runCatching { Session.handleDisconnect() }
    toolErr(
        code = ErrorCode.VmDisconnected,
        message = "VM disconnected — debug session ended.",
        hint = "rerun /android-debugger:attach",
    )
} catch (e: Throwable) {
    toolErr(
        code = ErrorCode.Internal,
        message = e.message ?: e::class.simpleName ?: "unknown error",
    )
}

/**
 * Mutex acquisition timeout for [runTool]. Long enough that a typical JDI operation
 * (step, snapshot, evaluate) finishes; short enough that a wedged session reports
 * `vm_busy` rather than hanging the MCP coroutine pool. Per R-01.
 */
const val MUTEX_ACQUIRE_TIMEOUT_MS: Long = 30_000L

/**
 * v1.7.1 (M1 hang mitigation): default wall-clock budget for every tool body. Tools
 * that legitimately need longer (heap dump pull = up to 2 min, hot_swap dexing) pass
 * a larger `wallClockMs` to [runTool]. Customer-visible: any tool returns within this
 * window even if the underlying JDI / JVMTI / adb call wedges.
 *
 * Tuning: 60s covers attach (10s socket cap from M2), eval_method (10s in Evaluator),
 * heap walks (30s in HeapTools), method-trace ops (10s agent RPC), with headroom for
 * coroutine scheduling. Higher-than-60s tools opt in case-by-case.
 */
const val DEFAULT_WALL_CLOCK_MS: Long = 60_000L

/** Long-running tools (heap dump pull from device, hot_swap dexing) opt into this. */
const val LONG_WALL_CLOCK_MS: Long = 180_000L

/**
 * Try to acquire the mutex within [timeoutMs]. Returns `true` on success, `false` if
 * the timeout elapsed first. Implemented with [withTimeoutOrNull] so we don't busy-wait.
 */
suspend fun kotlinx.coroutines.sync.Mutex.tryAcquireWithTimeout(timeoutMs: Long): Boolean {
    return withTimeoutOrNull(timeoutMs) {
        lock()
        true
    } ?: false
}
