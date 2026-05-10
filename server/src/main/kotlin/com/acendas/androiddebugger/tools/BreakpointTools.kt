package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.breakpoints.BreakpointKind
import com.acendas.androiddebugger.breakpoints.BreakpointManager
import com.acendas.androiddebugger.breakpoints.BreakpointMeta
import com.acendas.androiddebugger.breakpoints.LogpointBuffer
import com.acendas.androiddebugger.breakpoints.SourceResolver
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
import com.sun.jdi.ClassType
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.AccessWatchpointRequest
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.EventRequestManager
import com.sun.jdi.request.ExceptionRequest
import com.sun.jdi.request.MethodEntryRequest
import com.sun.jdi.request.MethodExitRequest
import com.sun.jdi.request.ModificationWatchpointRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * Phase 3 — every breakpoint kind plus list/remove/enable/disable management.
 *
 * Tools registered:
 *  - add_line_breakpoint(file, line, condition?, hit_count?, log_message?) — Story 3.1.1–3.1.4
 *  - add_exception_breakpoint(class?, caught, uncaught) — Story 3.1.5
 *  - add_method_breakpoint(class, method, kind) — Story 3.1.6
 *  - add_field_watchpoint(class, field, kind) — Story 3.1.7 (capability-gated)
 *  - list_breakpoints, remove_breakpoint, enable_breakpoint, disable_breakpoint
 *  - list_logpoint_entries({since?})
 *
 * The hit-side semantics (condition eval, hit-count gating, logpoint render) live in
 * EventLoop.kt — see BreakpointManager.findByRequest + the meta's counters.
 */
object BreakpointTools {

    fun register(server: Server) {
        registerAddLineBreakpoint(server)
        registerAddExceptionBreakpoint(server)
        registerAddMethodBreakpoint(server)
        registerAddFieldWatchpoint(server)
        registerListBreakpoints(server)
        registerRemoveBreakpoint(server)
        registerEnableBreakpoint(server)
        registerDisableBreakpoint(server)
        registerListLogpointEntries(server)
    }

    // -------------------- add_line_breakpoint --------------------

    private fun registerAddLineBreakpoint(server: Server) {
        server.addTool(
            name = "add_line_breakpoint",
            description = "Set a line breakpoint at `(file, line)`. Optional `condition` (server-side; " +
                "evaluated via the same expression engine as `evaluate` — paths + method calls + literal " +
                "args). Optional `hit_count` — only break on the Nth hit and onwards. Optional " +
                "`log_message` — turns the bp into a non-suspending logpoint with `{expr}` template " +
                "syntax (e.g. `\"user={user.id} count={items.size()}\"`); rendered entries land in the " +
                "logpoint ring buffer (see `list_logpoint_entries`). " +
                "Class-load deferred: works for not-yet-loaded classes via `ClassPrepareRequest`. " +
                "Returns `{ ok, id, resolved_locations: N, deferred: bool }`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("file") {
                        put("type", "string")
                        put("description", "Source file name as it appears in the .class file (e.g., MainActivity.kt).")
                    }
                    putJsonObject("line") {
                        put("type", "integer")
                        put("description", "Line number in the source file.")
                    }
                    putJsonObject("condition") {
                        put("type", "string")
                        put("description", "Optional. Boolean expression evaluated when the bp hits; thread resumes if it evaluates to false.")
                    }
                    putJsonObject("hit_count") {
                        put("type", "integer")
                        put("description", "Optional. Suppress the first N-1 hits; only deliver the Nth and onwards.")
                    }
                    putJsonObject("log_message") {
                        put("type", "string")
                        put("description", "Optional. Logpoint template with `{expr}` placeholders. Setting this makes the bp non-suspending.")
                    }
                },
                required = listOf("file", "line"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val args = request.arguments
                val file = (args?.get("file") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `file`.")
                val line = (args.get("line") as? JsonPrimitive)?.intOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `line`.")
                val condition = (args.get("condition") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val hitCount = (args.get("hit_count") as? JsonPrimitive)?.intOrNull
                if (hitCount != null && hitCount < 1) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "hit_count must be >= 1.",
                    )
                }
                val logMessage = (args.get("log_message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

                val id = BreakpointManager.mintId()
                val meta = BreakpointMeta(
                    id = id,
                    kind = BreakpointKind.LINE,
                    file = file,
                    line = line,
                    condition = condition,
                    hitCount = hitCount,
                    logMessage = logMessage,
                )

                val locations = SourceResolver.resolve(vm, file, line)
                val erm = vm.eventRequestManager()
                for (loc in locations) {
                    val req: BreakpointRequest = erm.createBreakpointRequest(loc).apply {
                        setSuspendPolicy(BreakpointManager.suspendPolicyFor(meta))
                        enable()
                    }
                    meta.activeRequests.add(req)
                }

                // Class-prepare deferral: register patterns even when we already resolved
                // some locations — Kotlin inline lambdas may live in classes that load
                // later (the file's outer class is loaded but `MainActivity$onCreate$1`
                // doesn't prepare until first use).
                val patterns = SourceResolver.classPatternsFor(file)
                if (patterns.isNotEmpty()) {
                    BreakpointManager.addDeferredPrepareRequests(erm, meta, patterns)
                }

                BreakpointManager.register(meta)

                toolOk {
                    put("id", id)
                    put("resolved_locations", locations.size)
                    put("deferred", patterns.isNotEmpty())
                    put("file", file)
                    put("line", line)
                    if (condition != null) put("condition", condition)
                    if (hitCount != null) put("hit_count", hitCount)
                    if (logMessage != null) put("log_message", logMessage)
                    if (locations.isEmpty()) {
                        put(
                            "hint",
                            "No matching class is currently loaded — registered as a deferred breakpoint. " +
                                "It will activate when the containing class prepares.",
                        )
                    }
                }
            }
        }
    }

    // -------------------- add_exception_breakpoint --------------------

    private fun registerAddExceptionBreakpoint(server: Server) {
        server.addTool(
            name = "add_exception_breakpoint",
            description = "Pause when a matching exception is thrown. " +
                "`class` filters by exception type (fully-qualified name like `java.lang.IllegalStateException`); " +
                "omit to match all exceptions. `caught` (default false) and `uncaught` (default true) toggle " +
                "which sites trigger. Class-load deferred: if the exception class isn't loaded yet we register " +
                "a `ClassPrepareRequest` and install the real bp on prepare. Returns `{ ok, id, deferred }`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class") {
                        put("type", "string")
                        put("description", "Fully-qualified exception class. Omit to match all exceptions.")
                    }
                    putJsonObject("caught") {
                        put("type", "boolean")
                        put("description", "Pause on caught (handled) exceptions. Default false.")
                    }
                    putJsonObject("uncaught") {
                        put("type", "boolean")
                        put("description", "Pause on uncaught exceptions. Default true.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val args = request.arguments
                val cls = (args?.get("class") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val caught = (args?.get("caught") as? JsonPrimitive)?.booleanOrNull ?: false
                val uncaught = (args?.get("uncaught") as? JsonPrimitive)?.booleanOrNull ?: true
                if (!caught && !uncaught) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "At least one of caught/uncaught must be true.",
                    )
                }

                val id = BreakpointManager.mintId()
                val meta = BreakpointMeta(
                    id = id,
                    kind = BreakpointKind.EXCEPTION,
                    exceptionClass = cls,
                    caught = caught,
                    uncaught = uncaught,
                )

                val erm = vm.eventRequestManager()
                var deferred = false
                if (cls == null) {
                    val req = erm.createExceptionRequest(null, caught, uncaught).apply {
                        setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                        enable()
                    }
                    meta.activeRequests.add(req)
                } else {
                    val refs = vm.classesByName(cls)
                    if (refs.isEmpty()) {
                        // Defer.
                        BreakpointManager.addDeferredPrepareRequests(erm, meta, listOf(cls))
                        deferred = true
                    } else {
                        for (ref in refs) {
                            val req = erm.createExceptionRequest(ref, caught, uncaught).apply {
                                setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                                enable()
                            }
                            meta.activeRequests.add(req)
                        }
                    }
                }

                BreakpointManager.register(meta)
                toolOk {
                    put("id", id)
                    put("deferred", deferred)
                    if (cls != null) put("class", cls)
                    put("caught", caught)
                    put("uncaught", uncaught)
                }
            }
        }
    }

    // -------------------- add_method_breakpoint --------------------

    private fun registerAddMethodBreakpoint(server: Server) {
        server.addTool(
            name = "add_method_breakpoint",
            description = "Pause on method entry or exit. `class` is the fully-qualified declaring class; " +
                "`method` is the simple method name (overloads all match). `kind` is `entry` or `exit`. " +
                "Internally uses MethodEntryRequest/MethodExitRequest with a class filter; per-method " +
                "matching happens in the event handler (JDI doesn't support per-method native filters). " +
                "Be aware: a hot class can fire thousands of method events per second. The agent should " +
                "use this surgically.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class") {
                        put("type", "string")
                        put("description", "Fully-qualified class name (e.g., com.example.MyVm).")
                    }
                    putJsonObject("method") {
                        put("type", "string")
                        put("description", "Simple method name. All overloads match.")
                    }
                    putJsonObject("kind") {
                        put("type", "string")
                        put("description", "`entry` (default) or `exit`.")
                    }
                },
                required = listOf("class", "method"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val args = request.arguments
                val cls = (args?.get("class") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `class`.")
                val method = (args.get("method") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `method`.")
                val kindStr = ((args.get("kind") as? JsonPrimitive)?.contentOrNull ?: "entry").lowercase()
                val isEntry = when (kindStr) {
                    "entry" -> true
                    "exit" -> false
                    else -> throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "kind must be 'entry' or 'exit'; got '$kindStr'.",
                    )
                }

                val erm = vm.eventRequestManager()
                val id = BreakpointManager.mintId()
                val meta = BreakpointMeta(
                    id = id,
                    kind = if (isEntry) BreakpointKind.METHOD_ENTRY else BreakpointKind.METHOD_EXIT,
                    methodClass = cls,
                    methodName = method,
                )

                if (isEntry) {
                    val req: MethodEntryRequest = erm.createMethodEntryRequest().apply {
                        addClassFilter(cls)
                        setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                        enable()
                    }
                    meta.activeRequests.add(req)
                } else {
                    val req: MethodExitRequest = erm.createMethodExitRequest().apply {
                        addClassFilter(cls)
                        setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                        enable()
                    }
                    meta.activeRequests.add(req)
                }

                BreakpointManager.register(meta)
                toolOk {
                    put("id", id)
                    put("class", cls)
                    put("method", method)
                    put("kind", if (isEntry) "entry" else "exit")
                }
            }
        }
    }

    // -------------------- add_field_watchpoint --------------------

    private fun registerAddFieldWatchpoint(server: Server) {
        server.addTool(
            name = "add_field_watchpoint",
            description = "Pause when a field is read (`access`), written (`modification`), or both. " +
                "`class` is the fully-qualified declaring type; `field` is the field name. " +
                "Capability-gated: ART must report `canWatchFieldAccess` / `canWatchFieldModification` " +
                "true; otherwise this returns `code: capability_unavailable`. " +
                "Returns `{ ok, id, kind }` with `kind` echoing what was registered.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class") {
                        put("type", "string")
                        put("description", "Fully-qualified class name owning the field.")
                    }
                    putJsonObject("field") {
                        put("type", "string")
                        put("description", "Field name on the class.")
                    }
                    putJsonObject("kind") {
                        put("type", "string")
                        put("description", "`access`, `modification`, or `both`. Default `modification`.")
                    }
                },
                required = listOf("class", "field"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val args = request.arguments
                val cls = (args?.get("class") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `class`.")
                val fieldName = (args.get("field") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `field`.")
                val kindStr = ((args.get("kind") as? JsonPrimitive)?.contentOrNull ?: "modification").lowercase()
                val wantAccess = kindStr == "access" || kindStr == "both"
                val wantModification = kindStr == "modification" || kindStr == "both"
                if (!wantAccess && !wantModification) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "kind must be 'access', 'modification', or 'both'; got '$kindStr'.",
                    )
                }

                // Per Story 7.1.1: route through the central capability gate so the
                // error code/feature key is consistent with every other capability-gated
                // tool. Falls back to the live `vm.canWatch*` probe if the cached map
                // somehow disagrees (defensive — ART version skew between probe and call).
                if (wantAccess) {
                    com.acendas.androiddebugger.Capability.requireCapability(
                        com.acendas.androiddebugger.Capability.FIELD_ACCESS_WATCHPOINTS,
                    )
                }
                if (wantModification) {
                    com.acendas.androiddebugger.Capability.requireCapability(
                        com.acendas.androiddebugger.Capability.FIELD_MODIFICATION_WATCHPOINTS,
                    )
                }

                val refs = vm.classesByName(cls)
                if (refs.isEmpty()) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "Class `$cls` is not loaded in the VM.",
                        hint = "Field watchpoints can't be deferred. Wait for the class to load (e.g., trigger one access) and try again.",
                    )
                }
                val refType = refs.first()
                val field = refType.fieldByName(fieldName)
                    ?: throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "Field `$fieldName` not found on class `$cls`.",
                    )

                val id = BreakpointManager.mintId()
                val kindEnum = when {
                    wantAccess && wantModification -> BreakpointKind.FIELD_ACCESS // We track both kinds via meta + activeRequests; pick one for the enum.
                    wantAccess -> BreakpointKind.FIELD_ACCESS
                    else -> BreakpointKind.FIELD_MODIFICATION
                }
                val meta = BreakpointMeta(
                    id = id,
                    kind = kindEnum,
                    fieldClass = cls,
                    fieldName = fieldName,
                )

                val erm = vm.eventRequestManager()
                if (wantAccess) {
                    val req: AccessWatchpointRequest = erm.createAccessWatchpointRequest(field).apply {
                        setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                        enable()
                    }
                    meta.activeRequests.add(req)
                }
                if (wantModification) {
                    val req: ModificationWatchpointRequest = erm.createModificationWatchpointRequest(field).apply {
                        setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                        enable()
                    }
                    meta.activeRequests.add(req)
                }

                BreakpointManager.register(meta)
                toolOk {
                    put("id", id)
                    put("class", cls)
                    put("field", fieldName)
                    put("kind", kindStr)
                }
            }
        }
    }

    // -------------------- list / remove / enable / disable --------------------

    private fun registerListBreakpoints(server: Server) {
        server.addTool(
            name = "list_breakpoints",
            description = "List every registered breakpoint and watchpoint with its current state. " +
                "Returned hit counters help spot too-noisy conditions or method filters.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) {
            runTool {
                val all = BreakpointManager.all()
                toolOk {
                    put("breakpoints", buildJsonArray {
                        for (m in all) add(metaToJson(m))
                    })
                    put("count", all.size)
                }
            }
        }
    }

    private fun registerRemoveBreakpoint(server: Server) {
        server.addTool(
            name = "remove_breakpoint",
            description = "Delete a breakpoint by id. Idempotent. Returns `{ ok, removed: bool }`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "Breakpoint id from list_breakpoints / add_*_breakpoint.")
                    }
                },
                required = listOf("id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val id = (request.arguments?.get("id") as? JsonPrimitive)?.intOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `id`.")
                val removed = BreakpointManager.remove(vm, id)
                toolOk {
                    put("removed", removed)
                    put("id", id)
                }
            }
        }
    }

    private fun registerEnableBreakpoint(server: Server) = registerEnableDisable(server, "enable_breakpoint", true)
    private fun registerDisableBreakpoint(server: Server) = registerEnableDisable(server, "disable_breakpoint", false)

    private fun registerEnableDisable(server: Server, name: String, enable: Boolean) {
        server.addTool(
            name = name,
            description = if (enable)
                "Re-enable a previously-disabled breakpoint. Returns `{ ok, found: bool }`."
            else
                "Disable a breakpoint without removing it. Returns `{ ok, found: bool }`. Re-enable with enable_breakpoint.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "Breakpoint id.")
                    }
                },
                required = listOf("id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val id = (request.arguments?.get("id") as? JsonPrimitive)?.intOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `id`.")
                val found = BreakpointManager.setEnabled(id, enable)
                toolOk {
                    put("found", found)
                    put("id", id)
                    put("enabled", enable)
                }
            }
        }
    }

    private fun registerListLogpointEntries(server: Server) {
        server.addTool(
            name = "list_logpoint_entries",
            description = "Read buffered logpoint entries (from breakpoints with `log_message` set). " +
                "Pass `since` (a `seq` value) to read only entries newer than that — poll with the last " +
                "returned `seq` to incrementally consume. Buffer holds at most 1000 entries (oldest first dropped).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("since") {
                        put("type", "integer")
                        put("description", "Optional. Return only entries with seq > since. Default 0 (all buffered).")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val since = (request.arguments?.get("since") as? JsonPrimitive)?.longOrNull ?: 0L
                val entries = LogpointBuffer.since(since)
                toolOk {
                    put("entries", buildJsonArray {
                        for (e in entries) {
                            add(buildJsonObject {
                                put("seq", e.seq)
                                put("ts", e.timestamp.toString())
                                put("breakpoint_id", e.breakpointId)
                                e.threadName?.let { put("thread_name", it) }
                                e.file?.let { put("file", it) }
                                put("line", e.line)
                                put("rendered", e.rendered)
                            })
                        }
                    })
                    put("count", entries.size)
                    put("buffer_size", LogpointBuffer.size())
                    if (entries.isNotEmpty()) {
                        put("max_seq", entries.last().seq)
                    }
                }
            }
        }
    }

    private fun metaToJson(m: BreakpointMeta) = buildJsonObject {
        put("id", m.id)
        put("kind", when (m.kind) {
            BreakpointKind.LINE -> if (m.logMessage != null) "logpoint" else "line"
            BreakpointKind.EXCEPTION -> "exception"
            BreakpointKind.METHOD_ENTRY -> "method_entry"
            BreakpointKind.METHOD_EXIT -> "method_exit"
            BreakpointKind.FIELD_ACCESS -> "field_access"
            BreakpointKind.FIELD_MODIFICATION -> "field_modification"
        })
        put("enabled", m.enabled)
        put("active_request_count", m.activeRequests.size)
        put("deferred_request_count", m.deferredPrepareRequests.size)
        put("total_hits", m.totalHits.get())
        put("delivered_stops", m.deliveredStops.get())
        if (m.condition != null) {
            put("condition", m.condition)
            put("false_condition_hits", m.falseConditionHits.get())
        }
        if (m.hitCount != null) {
            put("hit_count", m.hitCount)
            put("suppressed_hits", m.suppressedHits.get())
        }
        if (m.logMessage != null) {
            put("log_message", m.logMessage)
            put("logpoint_entries_emitted", m.logpointEntries.get())
        }
        m.file?.let { put("file", it) }
        m.line?.let { put("line", it) }
        m.exceptionClass?.let { put("exception_class", it) }
        if (m.kind == BreakpointKind.EXCEPTION) {
            put("caught", m.caught)
            put("uncaught", m.uncaught)
        }
        m.methodClass?.let { put("method_class", it) }
        m.methodName?.let { put("method_name", it) }
        m.fieldClass?.let { put("field_class", it) }
        m.fieldName?.let { put("field_name", it) }
    }
}
