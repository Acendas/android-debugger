package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.SessionState
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.events.DebugEvent
import com.acendas.androiddebugger.execution.Stepper
import com.acendas.androiddebugger.inspection.SnapshotBuilder
import com.acendas.androiddebugger.inspection.toJson
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.EventRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Phase 4 — execution control + event polling.
 *
 * Tools registered:
 *  - step_over / step_into / step_out — single-shot StepRequest with class-exclusion
 *    filters; awaits the matching StepEvent from the session event channel and returns
 *    a fresh frame_snapshot.
 *  - run_to_line — temporary line breakpoint + resume; removed on hit.
 *  - resume / pause — vm.resume() and vm.suspend() with state transitions.
 *  - wait_for_event — read the next event from the channel with a timeout, optionally
 *    filtered by `types`. Non-matching events are re-queued so we don't lose them.
 */
object ExecutionTools {

    /** How long to wait for a step's StepEvent before giving up. Steps are usually instant. */
    private const val STEP_AWAIT_MS: Long = 30_000L

    fun register(server: Server) {
        registerStep(server, "step_over", Stepper.Depth.Over,
            "Step over the current line. Stays in the same method; if a call is on this line, " +
                "it executes the call and stops on the next line. Requires paused state. " +
                "Returns a fresh frame_snapshot of the new top frame.")
        registerStep(server, "step_into", Stepper.Depth.Into,
            "Step into the next call on the current line. Default class-exclusion filters skip " +
                "java.*, android.*, kotlin.*, com.android.* etc. so you don't drop into framework " +
                "innards. Pass `extra_skip_filters` to skip more, or `disable_default_filters: true` " +
                "to drop the defaults entirely.")
        registerStep(server, "step_out", Stepper.Depth.Out,
            "Step out of the current method, stopping on the next line of the caller. " +
                "Useful when step_into landed you somewhere uninteresting.")
        registerRunToLine(server)
        registerResume(server)
        registerPause(server)
        registerWaitForEvent(server)
    }

    private fun registerStep(server: Server, name: String, depth: Stepper.Depth, description: String) {
        server.addTool(
            name = name,
            description = description,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("extra_skip_filters", buildJsonObject {
                        put("type", "array")
                        put("description", "Additional class patterns to skip during step (e.g., \"com.example.generated.*\").")
                    })
                    put("disable_default_filters", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, do NOT apply the default java/android/kotlin exclusion filters. Default false.")
                    })
                    put("snapshot_depth", buildJsonObject {
                        put("type", "integer")
                        put("description", "Frames in the returned snapshot. Default 5; max 30.")
                    })
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val thread = Session.requirePaused()
                val args = request.arguments
                val extras = (args?.get("extra_skip_filters") as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()
                val disableDefaults = (args?.get("disable_default_filters") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val snapshotDepth = ((args?.get("snapshot_depth") as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 30)

                // Per Story 7.1.4: step budget. Read the current method off the paused
                // top frame, ask the budget if we're allowed to step. If the budget is
                // exhausted, return WITHOUT issuing the JDI step request — the agent
                // gets `step_budget_exhausted: true` and a suggestion to set a different
                // breakpoint or run_to_line. Reset only on a frame change / resume /
                // breakpoint hit (handled via the StepBudget's same-method tracking).
                val currentMethod: String? = runCatching {
                    val loc = thread.frame(0).location()
                    "${loc.declaringType().name()}#${loc.method().name()}"
                }.getOrNull()
                if (currentMethod != null && !Session.stepBudget.tryStep(thread.uniqueID(), currentMethod)) {
                    val stuck = Session.stepBudget.lastMethod(thread.uniqueID()) ?: currentMethod
                    return@runTool toolOk {
                        put("step_budget_exhausted", true)
                        put(
                            "suggestion",
                            "Set a different breakpoint or run_to_line — you're stuck in $stuck",
                        )
                        put("consecutive_steps", Session.stepBudget.consecutiveSteps(thread.uniqueID()))
                    }
                }

                val req = Stepper.startStep(vm, thread, depth, extras, disableDefaults)

                Session.bumpVmStateVersion()
                Session.pausedThread = null
                Session.state = SessionState.ATTACHED_RUNNING

                try {
                    vm.resume()
                } catch (t: Throwable) {
                    Stepper.dispose(vm, req)
                    throw t
                }

                // Wait for the resulting Stopped(reason="step") on the same thread,
                // re-queueing any unrelated events.
                val event = awaitEvent(STEP_AWAIT_MS) { e ->
                    e is DebugEvent.Stopped && e.reason == "step" && e.threadId == thread.uniqueID()
                } ?: run {
                    // Timed out — try to clean up the StepRequest and surface a structured error.
                    Stepper.dispose(vm, req)
                    throw ToolError(
                        errorCode = ErrorCode.Internal,
                        message = "Step did not complete within ${STEP_AWAIT_MS}ms.",
                        hint = "The thread may be blocked or the VM disconnected. Try `wait_for_event` " +
                            "or `connection_status` to inspect.",
                    )
                }

                Stepper.dispose(vm, req)

                // The event loop already updated Session.pausedThread / state before
                // delivering the Stopped event, but be defensive in case of races.
                Session.pausedThread = thread
                Session.state = SessionState.ATTACHED_PAUSED

                val snapshot = SnapshotBuilder().build(thread, snapshotDepth, event = "STEP", pausedReason = "step")
                toolOk {
                    put("event", event.toJson())
                    put("snapshot", snapshot.toJson())
                    put("vm_state_version", Session.vmStateVersion.get())
                }
            }
        }
    }

    private fun registerRunToLine(server: Server) {
        server.addTool(
            name = "run_to_line",
            description = "Resume the VM until execution reaches `(file, line)`, then pause. Internally " +
                "places a temporary single-shot breakpoint and removes it on hit. The class containing " +
                "the file must already be loaded — if it isn't, errors `invalid_target` (deferred class " +
                "loading is breakpoint territory). Returns the frame_snapshot at the target line.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("file", buildJsonObject {
                        put("type", "string")
                        put("description", "Source file name as it appears in the .class file (e.g., MainActivity.kt).")
                    })
                    put("line", buildJsonObject {
                        put("type", "integer")
                        put("description", "Line number in the file.")
                    })
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "How long to wait for the line to be hit. Default 30000.")
                    })
                },
                required = listOf("file", "line"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val thread = Session.requirePaused()
                val args = request.arguments
                val file = (args?.get("file") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `file`.")
                val line = (args.get("line") as? JsonPrimitive)?.intOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `line`.")
                val timeout = ((args.get("timeout_ms") as? JsonPrimitive)?.intOrNull ?: 30_000)
                    .coerceIn(500, 600_000).toLong()

                val location = resolveLocation(vm, file, line)
                    ?: throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "Could not resolve $file:$line to a code location.",
                        hint = "The class containing this line may not be loaded yet. Use add_line_breakpoint " +
                            "to register a deferred breakpoint and resume manually.",
                    )

                val erm = vm.eventRequestManager()
                val bp: BreakpointRequest = erm.createBreakpointRequest(location).apply {
                    setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                    addCountFilter(1) // Single-shot.
                    enable()
                }

                // Per Story 7.1.4: run_to_line always resets the budget. The agent has
                // explicitly chosen to break the same-method loop.
                Session.stepBudget.reset(thread.uniqueID())

                Session.bumpVmStateVersion()
                Session.pausedThread = null
                Session.state = SessionState.ATTACHED_RUNNING
                try {
                    vm.resume()
                } catch (t: Throwable) {
                    runCatching { bp.disable() }
                    runCatching { erm.deleteEventRequest(bp) }
                    throw t
                }

                val event = awaitEvent(timeout) { e ->
                    e is DebugEvent.Stopped && e.reason == "breakpoint"
                }
                runCatching { bp.disable() }
                runCatching { erm.deleteEventRequest(bp) }

                if (event == null) {
                    throw ToolError(
                        errorCode = ErrorCode.Internal,
                        message = "run_to_line timed out after ${timeout}ms without hitting $file:$line.",
                        hint = "The line may be unreachable from the current state. Use connection_status to inspect.",
                    )
                }

                val pausedNow = Session.pausedThread ?: thread
                Session.pausedThread = pausedNow
                Session.state = SessionState.ATTACHED_PAUSED
                val snapshot = SnapshotBuilder().build(
                    pausedNow,
                    depth = 5,
                    event = "RUN_TO_LINE",
                    pausedReason = "run_to_line",
                )
                toolOk {
                    put("event", event.toJson())
                    put("snapshot", snapshot.toJson())
                    put("vm_state_version", Session.vmStateVersion.get())
                }
            }
        }
    }

    private fun resolveLocation(vm: VirtualMachine, file: String, line: Int): Location? {
        // Try every loaded class whose sourceName() matches the requested file. This
        // is fail-loud: if the class isn't loaded, we surface invalid_target at the
        // call site. Deferred class-load handling is Phase 3 (breakpoint manager).
        val classes: List<ReferenceType> = try { vm.allClasses() } catch (_: Throwable) { return null }
        val candidates = classes.filter { type ->
            try { type.sourceName() == file } catch (_: AbsentInformationException) { false }
        }
        for (type in candidates) {
            // Try Kotlin stratum first then Java; walk nested types for inline-fn lambdas.
            val locs = locationsFor(type, line)
            if (locs.isNotEmpty()) return locs.first()
            for (nested in nestedTypesRecursive(type)) {
                val ll = locationsFor(nested, line)
                if (ll.isNotEmpty()) return ll.first()
            }
        }
        return null
    }

    private fun locationsFor(type: ReferenceType, line: Int): List<Location> {
        val tries = listOf("Kotlin", null)
        for (stratum in tries) {
            try {
                val locs = if (stratum != null) type.locationsOfLine(stratum, null, line)
                else type.locationsOfLine(line)
                if (locs.isNotEmpty()) return locs
            } catch (_: AbsentInformationException) {
                // Continue.
            } catch (_: Throwable) {
                // Continue.
            }
        }
        return emptyList()
    }

    private fun nestedTypesRecursive(type: ReferenceType): List<ReferenceType> {
        val out = mutableListOf<ReferenceType>()
        try {
            for (n in type.nestedTypes()) {
                out += n
                out += nestedTypesRecursive(n)
            }
        } catch (_: Throwable) {
            // Ignore.
        }
        return out
    }

    private fun registerResume(server: Server) {
        server.addTool(
            name = "resume",
            description = "Resume the paused VM. Increments vm_state_version (invalidates the snapshot " +
                "cache). Idempotent semantics: errors `vm_running` if not paused.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) {
            runTool {
                val vm = Session.requireAttached()
                val paused = Session.requirePaused()
                // Per Story 7.1.4: resume always resets the step budget for this thread —
                // the agent has explicitly asked to stop stepping, so the next step from
                // wherever we end up is fresh.
                Session.stepBudget.reset(paused.uniqueID())
                Session.bumpVmStateVersion()
                Session.pausedThread = null
                Session.state = SessionState.ATTACHED_RUNNING
                try {
                    vm.resume()
                } catch (e: VMDisconnectedException) {
                    throw ToolError(
                        errorCode = ErrorCode.VmDisconnected,
                        message = "VM disconnected during resume.",
                        hint = "Reattach via /android-debugger:attach.",
                    )
                }
                toolOk {
                    put("vm_state_version", Session.vmStateVersion.get())
                    put("state", Session.state.name)
                }
            }
        }
    }

    private fun registerPause(server: Server) {
        server.addTool(
            name = "pause",
            description = "Suspend the running VM. Returns a frame_snapshot of the most-recently-active " +
                "thread (heuristic: first RUNNING thread, else the first non-system thread). Errors " +
                "`vm_paused` if already paused.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("snapshot_depth", buildJsonObject {
                        put("type", "integer")
                        put("description", "Frames in the returned snapshot. Default 5; max 30.")
                    })
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                if (Session.state == SessionState.ATTACHED_PAUSED || Session.pausedThread != null) {
                    throw ToolError(
                        errorCode = ErrorCode.VmPaused,
                        message = "VM is already paused.",
                        hint = "Use frame_snapshot to inspect the current pause; resume before pausing again.",
                        currentState = Session.state.name,
                    )
                }
                val depth = ((request.arguments?.get("snapshot_depth") as? JsonPrimitive)?.intOrNull ?: 5)
                    .coerceIn(1, 30)

                try {
                    vm.suspend()
                } catch (e: VMDisconnectedException) {
                    throw ToolError(
                        errorCode = ErrorCode.VmDisconnected,
                        message = "VM disconnected during pause.",
                    )
                }

                val picked = pickActiveThread(vm)
                Session.bumpVmStateVersion()
                Session.pausedThread = picked
                Session.state = SessionState.ATTACHED_PAUSED

                val snapshotJson: JsonObject? = if (picked != null) {
                    SnapshotBuilder()
                        .build(picked, depth, event = "PAUSED", pausedReason = "manual_pause")
                        .toJson()
                } else null

                toolOk {
                    put("state", Session.state.name)
                    put("vm_state_version", Session.vmStateVersion.get())
                    if (snapshotJson != null) put("snapshot", snapshotJson)
                    if (picked != null) {
                        put("paused_thread", buildJsonObject {
                            put("id", picked.uniqueID())
                            put("name", picked.name())
                        })
                    } else {
                        put("paused_thread", JsonPrimitive(null as String?))
                        put("hint", "VM suspended but no paused-thread heuristic match — pass a thread_id to inspection tools explicitly.")
                    }
                }
            }
        }
    }

    /** Pick a "most-recently-active" thread heuristic. Prefer RUNNING, else first non-zombie. */
    private fun pickActiveThread(vm: VirtualMachine): ThreadReference? {
        val threads = try { vm.allThreads() } catch (_: Throwable) { return null }
        val running = threads.firstOrNull { runCatching { it.status() == ThreadReference.THREAD_STATUS_RUNNING }.getOrDefault(false) }
        if (running != null) return running
        // Fallback: first non-zombie, non-not-started thread.
        return threads.firstOrNull { runCatching {
            val s = it.status()
            s != ThreadReference.THREAD_STATUS_ZOMBIE && s != ThreadReference.THREAD_STATUS_NOT_STARTED
        }.getOrDefault(false) } ?: threads.firstOrNull()
    }

    private fun registerWaitForEvent(server: Server) {
        server.addTool(
            name = "wait_for_event",
            description = "Block until the next debug event arrives or the timeout elapses. Optional " +
                "`types` filter (subset of [\"stopped\", \"exception\", \"exit\", \"class_prepare\", " +
                "\"disconnect\"]) — non-matching events are re-queued so they're not lost. Returns " +
                "either { ok: true, event: { type, ... } } or { ok: true, timed_out: true }.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max wait in milliseconds. Default 10000; max 600000.")
                    })
                    put("types", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional event-type filter. Defaults to all types.")
                    })
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val args = request.arguments
                val timeout = ((args?.get("timeout_ms") as? JsonPrimitive)?.intOrNull ?: 10_000)
                    .coerceIn(0, 600_000).toLong()
                val types: Set<String>? = (args?.get("types") as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }

                val event = awaitEvent(timeout) { e ->
                    types == null || e.type in types
                }
                if (event == null) {
                    toolOk { put("timed_out", true) }
                } else {
                    toolOk { put("event", event.toJson()) }
                }
            }
        }
    }

    /**
     * Read the next event from the session channel that satisfies [predicate], with a
     * timeout. Non-matching events are re-sent into the channel after the wait
     * completes so other readers don't lose them. Cleanest impl with the Channel API:
     * we drain into a list and re-queue the leftovers.
     */
    private fun awaitEvent(timeoutMs: Long, predicate: (DebugEvent) -> Boolean): DebugEvent? = runBlocking {
        val channel: Channel<DebugEvent> = Session.eventChannel ?: return@runBlocking null
        val deferred = mutableListOf<DebugEvent>()
        var matched: DebugEvent? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                val next = withTimeoutOrNull(remaining) {
                    select<DebugEvent?> {
                        channel.onReceive { it }
                    }
                } ?: break
                if (predicate(next)) {
                    matched = next
                    break
                } else {
                    deferred += next
                }
            }
        } finally {
            // Re-queue everything we drained but didn't consume so subsequent waits see them.
            for (e in deferred) {
                channel.trySend(e)
            }
        }
        matched
    }
}
