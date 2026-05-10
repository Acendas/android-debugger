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
suspend inline fun runTool(crossinline block: suspend () -> CallToolResult): CallToolResult = try {
    val acquired = Session.mutex.tryAcquireWithTimeout(MUTEX_ACQUIRE_TIMEOUT_MS)
    if (!acquired) {
        toolErr(
            code = ErrorCode.VmBusy,
            message = "Another tool call is currently holding the JDI session lock.",
            hint = "Wait for the in-flight call to finish, or check connection_status.",
        )
    } else {
        try {
            block()
        } finally {
            Session.mutex.unlock()
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
 * Try to acquire the mutex within [timeoutMs]. Returns `true` on success, `false` if
 * the timeout elapsed first. Implemented with [withTimeoutOrNull] so we don't busy-wait.
 */
suspend fun kotlinx.coroutines.sync.Mutex.tryAcquireWithTimeout(timeoutMs: Long): Boolean {
    return withTimeoutOrNull(timeoutMs) {
        lock()
        true
    } ?: false
}
