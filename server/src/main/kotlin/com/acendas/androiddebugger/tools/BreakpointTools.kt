package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.breakpoints.BreakpointInstaller
import com.acendas.androiddebugger.breakpoints.BreakpointKind
import com.acendas.androiddebugger.breakpoints.BreakpointManager
import com.acendas.androiddebugger.breakpoints.BreakpointMeta
import com.acendas.androiddebugger.breakpoints.LogpointBuffer
import com.acendas.androiddebugger.breakpoints.SourceResolver
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        registerAddClassLoadBreakpoint(server)
        registerListBreakpoints(server)
        registerRemoveBreakpoint(server)
        registerEnableBreakpoint(server)
        registerDisableBreakpoint(server)
        registerListLogpointEntries(server)
    }

    // -------------------- add_class_load_breakpoint --------------------

    private fun registerAddClassLoadBreakpoint(server: Server) {
        server.addTool(
            name = "add_class_load_breakpoint",
            description = "v1.3 — pause when a class matching `class_pattern` is loaded. " +
                "Pattern uses JDWP glob syntax: `com.example.Foo` (exact), `com.example.*` " +
                "(prefix), `*.MyService` (suffix). Fires on every class that matches, with " +
                "`Stopped(reason='class_prepare', breakpoint_id=…)`. The originating thread is " +
                "the loader's thread; inspect with `frame_snapshot` to see the load site. " +
                "Common uses: catching first-load of a class for static-initializer debugging; " +
                "pausing before any of a class's code runs; gating on dependency injection. " +
                "Returns `{ ok, id, class_pattern }`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class_pattern") {
                        put("type", "string")
                        put(
                            "description",
                            "Glob pattern per JDWP: `com.example.Foo`, `com.example.*`, `*.MyService`.",
                        )
                    }
                },
                required = listOf("class_pattern"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val pattern = (request.arguments?.get("class_pattern") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `class_pattern`.")
                val id = BreakpointManager.mintId()
                BreakpointInstaller.installClassLoad(vm, id, pattern)
                toolOk {
                    put("id", id)
                    put("class_pattern", pattern)
                }
            }
        }
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
                val result = BreakpointInstaller.installLine(
                    vm = vm,
                    id = id,
                    file = file,
                    line = line,
                    condition = condition,
                    hitCount = hitCount,
                    logMessage = logMessage,
                )
                toolOk {
                    put("id", id)
                    put("resolved_locations", result.resolvedLocations)
                    put("deferred", result.deferred || result.resolvedLocations == 0)
                    put("file", file)
                    put("line", line)
                    if (condition != null) put("condition", condition)
                    if (hitCount != null) put("hit_count", hitCount)
                    if (logMessage != null) put("log_message", logMessage)
                    if (result.resolvedLocations == 0) {
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
                val result = BreakpointInstaller.installException(
                    vm = vm,
                    id = id,
                    exceptionClass = cls,
                    caught = caught,
                    uncaught = uncaught,
                )
                toolOk {
                    put("id", id)
                    put("deferred", result.deferred)
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
            description = "Pause on method entry or exit. `class` is the fully-qualified declaring " +
                "class; `method` is the simple method name (overloads all match). `kind` is `entry` " +
                "or `exit`. Optional `log_message` turns this into a non-suspending logpoint (no " +
                "`{expr}` interpolation in the JVMTI auto-route). Optional `condition` keeps the JDI " +
                "path and gates suspension on a server-side expression. " +
                "Auto-routes through the JVMTI agent's method-trace surface when the agent is " +
                "loaded AND `log_message` is set AND `condition` is unset — the JVMTI path is " +
                "natively per-method (no class-filter-then-match-in-handler), 10-100x cheaper on " +
                "hot classes. Response carries `backend: \"jvmti\"|\"jdi\"`. " +
                "Be aware: a hot class on the JDI path can fire thousands of method events per " +
                "second. The agent should use this surgically.",
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
                    putJsonObject("log_message") {
                        put("type", "string")
                        put(
                            "description",
                            "Optional. Logpoint message — turns the bp into a non-suspending logpoint. " +
                                "Auto-routes through JVMTI when the agent is loaded; no `{expr}` interpolation on the JVMTI path.",
                        )
                    }
                    putJsonObject("condition") {
                        put("type", "string")
                        put(
                            "description",
                            "Optional. Server-side predicate; keeps the JDI path even when `log_message` is set.",
                        )
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
                val logMessage = (args.get("log_message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val condition = (args.get("condition") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

                // v1.6 auto-route: a method bp with a logMessage and no condition can ride
                // the JVMTI method-trace surface, which is natively per-method (vs. the JDI
                // path which fires ClassEntry then matches in the handler). Falls through
                // to the JDI path when the conditions aren't met.
                val agentClient = Session.agentClient
                val canMethodEntryEvents = (Session.agentState?.capabilities?.get("can_generate_method_entry_events")
                    as? JsonPrimitive)?.booleanOrNull == true
                val canUseJvmti = agentClient != null
                    && canMethodEntryEvents
                    && logMessage != null
                    && condition == null
                if (canUseJvmti) {
                    val methodKey = toJvmSignature(cls) + method
                    val kinds = if (isEntry) setOf(com.acendas.androiddebugger.jvmti.AgentMethodTrace.EventKind.ENTRY)
                        else setOf(com.acendas.androiddebugger.jvmti.AgentMethodTrace.EventKind.EXIT)
                    val req = com.acendas.androiddebugger.jvmti.AgentMethodTrace.StartRequest(
                        filterKind = com.acendas.androiddebugger.jvmti.AgentMethodTrace.FilterKind.METHODS,
                        methods = listOf(methodKey),
                        kinds = kinds,
                        includeArgs = false,
                        includeReturn = false,
                    )
                    val startResult = com.acendas.androiddebugger.jvmti.AgentMethodTrace.start(agentClient, req)
                    val id = BreakpointManager.mintId()
                    val meta = BreakpointMeta(
                        id = id,
                        kind = if (isEntry) BreakpointKind.METHOD_ENTRY else BreakpointKind.METHOD_EXIT,
                        methodClass = cls,
                        methodName = method,
                        logMessage = logMessage,
                        jvmtiTraceBufferId = startResult.bufferId,
                    )
                    BreakpointManager.register(meta)
                    Session.methodTraceBufferIds.add(startResult.bufferId)

                    // Spawn a poll coroutine that drains the trace buffer every 500 ms
                    // and pushes each event into the LogpointBuffer. Lives on
                    // Session.v16PollScope so cancellation on detach is clean.
                    val pollScope = Session.v16PollScope
                    if (pollScope != null) {
                        startBreakpointPoll(
                            scope = pollScope,
                            meta = meta,
                            bufferId = startResult.bufferId,
                            agentClient = agentClient,
                        )
                    }

                    return@runTool toolOk {
                        put("id", id)
                        put("class", cls)
                        put("method", method)
                        put("kind", if (isEntry) "entry" else "exit")
                        put("log_message", logMessage)
                        put("backend", "jvmti")
                        put("buffer_id", startResult.bufferId)
                    }
                }

                // JDI path. Conditions / suspending bps stay here.
                val id = BreakpointManager.mintId()
                if (isEntry) {
                    BreakpointInstaller.installMethodEntry(vm, id, cls, method)
                } else {
                    BreakpointInstaller.installMethodExit(vm, id, cls, method)
                }
                toolOk {
                    put("id", id)
                    put("class", cls)
                    put("method", method)
                    put("kind", if (isEntry) "entry" else "exit")
                    put("backend", "jdi")
                }
            }
        }
    }

    /** Convert FQN to JVM signature (`com.example.Foo` -> `Lcom/example/Foo;`). */
    private fun toJvmSignature(fqn: String): String =
        "L" + fqn.replace('.', '/') + ";"

    /**
     * Spawn a coroutine on [scope] that drains [bufferId] every 500 ms and emits one
     * [com.acendas.androiddebugger.breakpoints.LogpointEntry] per event into
     * [com.acendas.androiddebugger.breakpoints.LogpointBuffer]. Best-effort: read
     * errors get logged + retried; the loop exits on scope cancellation.
     *
     * Caveat: on cancellation the trace session stays running on the agent until
     * the detach path's `stopAll` fires. That's acceptable — the agent side is
     * lightweight and detach always closes both surfaces in one RPC.
     */
    private fun startBreakpointPoll(
        scope: kotlinx.coroutines.CoroutineScope,
        meta: BreakpointMeta,
        bufferId: String,
        agentClient: com.acendas.androiddebugger.jvmti.AgentClient,
    ) {
        val log = org.slf4j.LoggerFactory.getLogger("android-debugger.bp-jvmti-poll")
        scope.launch {
            while (isActive) {
                try {
                    val result = com.acendas.androiddebugger.jvmti.AgentMethodTrace.read(
                        agentClient, bufferId, max = 500,
                    )
                    for (e in result.events) {
                        if (!meta.enabled) continue
                        com.acendas.androiddebugger.breakpoints.LogpointBuffer.push(
                            threadName = e.thread,
                            file = null,
                            line = -1,
                            breakpointId = meta.id,
                            rendered = meta.logMessage ?: "",
                        )
                        meta.logpointEntries.incrementAndGet()
                        meta.totalHits.incrementAndGet()
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    log.debug("poll error for bp {} buffer {}: {}", meta.id, bufferId, t.message)
                }
                kotlinx.coroutines.delay(500)
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

                val id = BreakpointManager.mintId()
                BreakpointInstaller.installFieldWatchpoint(
                    vm = vm,
                    id = id,
                    fieldClass = cls,
                    fieldName = fieldName,
                    wantAccess = wantAccess,
                    wantModification = wantModification,
                )
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
            description = "Delete a breakpoint by id. Idempotent. Returns `{ ok, removed: bool }`. " +
                "If the breakpoint was auto-routed through JVMTI (method bp + log_message + " +
                "agent loaded), the underlying method-trace session is stopped too (best-effort).",
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
                // v1.6: look up meta BEFORE remove so we can drain its JVMTI trace
                // session if it has one. The bp poll coroutine will exit on its own
                // (scope cancellation on detach) or when the bp's enabled flag drops
                // — meanwhile draining the agent buffer here is cheap and clean.
                val meta = BreakpointManager.get(id)
                val jvmtiBuf = meta?.jvmtiTraceBufferId
                val agentClient = Session.agentClient
                if (jvmtiBuf != null && agentClient != null) {
                    runCatching {
                        com.acendas.androiddebugger.jvmti.AgentMethodTrace.stop(agentClient, jvmtiBuf)
                    }
                    Session.methodTraceBufferIds.remove(jvmtiBuf)
                }
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
            BreakpointKind.CLASS_LOAD -> "class_load"
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
        m.classPattern?.let { put("class_pattern", it) }
    }
}
