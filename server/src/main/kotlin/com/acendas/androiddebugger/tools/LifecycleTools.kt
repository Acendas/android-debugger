package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.SessionState
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.jdi.Capabilities
import com.acendas.androiddebugger.jdi.JdiAttacher
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * Lifecycle MCP tools — device discovery, process discovery, attach, detach, status.
 * Per Phase 1.
 */
object LifecycleTools {

    fun register(server: Server) {
        registerListDevices(server)
        registerListDebuggableProcesses(server)
        registerAttach(server)
        registerDetach(server)
        registerConnectionStatus(server)
    }

    private fun registerConnectionStatus(server: Server) {
        server.addTool(
            name = "connection_status",
            description = "Read the current debug-session state without touching the VM. Returns whether " +
                "we're attached, the target package/serial/pid, the JDWP port, the session state, breakpoint and " +
                "watch counts, and how long we've been attached. Cheap; never blocks on a paused VM.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) {
            runTool {
                val attached = Session.vm != null
                toolOk {
                    put("attached", attached)
                    put("state", Session.state.name)
                    Session.serial?.let { put("serial", it) }
                    Session.packageName?.let { put("package", it) }
                    Session.pid?.let { put("pid", it) }
                    Session.forwardedPort?.let { put("jdwp_port", it) }
                    Session.attachedAt?.let {
                        put("attached_at", it.toString())
                        put("since_ms", java.time.Duration.between(it, java.time.Instant.now()).toMillis())
                    }
                    // Breakpoint count wired in Phase 3; watch count wired in Phase 5.
                    put("breakpoint_count", com.acendas.androiddebugger.breakpoints.BreakpointManager.size())
                    put("watch_count", Session.watchManager.size())
                }
            }
        }
    }

    private fun registerDetach(server: Server) {
        server.addTool(
            name = "detach",
            description = "Disconnect the debugger from the attached app. Calls vm.dispose() (NOT exit), " +
                "releases the adb forward, and resets state. Idempotent — safe to call when unattached. " +
                "Always run this when finished — abandoned attachments leave the app suspended at the next breakpoint hit. " +
                "**v1.3 persistence**: by default, breakpoints and watches are saved to " +
                "\$CLAUDE_PLUGIN_DATA/android-debugger/sessions/<serial>_<package>.json " +
                "and rehydrated on the next attach to the same (serial, package). Pass `persist: false` " +
                "to skip the save (useful for one-off sessions or to clear stale state — combine with " +
                "deleting the file by hand).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("persist") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Save breakpoint + watch state for the next attach to this (serial, package). Default true.",
                        )
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val persist = (request.arguments?.get("persist") as? JsonPrimitive)?.booleanOrNull ?: true
                // Capture the persistable state BEFORE detach() clears it. Save only if we
                // have a (serial, package) pair, were actually attached, and persist=true.
                val serial = Session.serial
                val packageName = Session.packageName
                val savedBreakpoints = if (persist && serial != null && packageName != null && Session.vm != null) {
                    com.acendas.androiddebugger.breakpoints.BreakpointManager.all().toList()
                } else emptyList()
                val savedWatches = if (persist && serial != null && packageName != null && Session.vm != null) {
                    Session.watchManager.list().map { it.id to it.expr }
                } else emptyList()

                val result = Session.detach()

                var persistedPath: String? = null
                if (persist && serial != null && packageName != null && result.wasAttached &&
                    (savedBreakpoints.isNotEmpty() || savedWatches.isNotEmpty())
                ) {
                    val path = com.acendas.androiddebugger.state.SessionPersistence.save(
                        serial = serial,
                        packageName = packageName,
                        breakpoints = savedBreakpoints,
                        watches = savedWatches,
                    )
                    persistedPath = path?.toString()
                }
                toolOk {
                    put("was_attached", result.wasAttached)
                    result.releasedPort?.let { put("released_port", it) }
                    put("persisted", persistedPath != null)
                    if (persistedPath != null) put("persisted_path", persistedPath)
                    put("saved_breakpoints", savedBreakpoints.size)
                    put("saved_watches", savedWatches.size)
                }
            }
        }
    }

    /**
     * Replay one persisted [SavedBreakpoint] against a freshly attached VM. Dispatches
     * on the kind name and calls the same [BreakpointInstaller] entry points the
     * `add_*_breakpoint` tools use. Throws if the kind is unknown or the install fails
     * (the caller catches per-bp and logs into `restored.errors`).
     */
    private fun rehydrateBreakpoint(
        vm: com.sun.jdi.VirtualMachine,
        bp: com.acendas.androiddebugger.state.SessionPersistence.SavedBreakpoint,
    ) {
        val kind = com.acendas.androiddebugger.state.parseBreakpointKind(bp.kind)
            ?: throw IllegalStateException("Unknown breakpoint kind `${bp.kind}` in saved session")
        when (kind) {
            com.acendas.androiddebugger.breakpoints.BreakpointKind.LINE -> {
                val file = bp.file ?: throw IllegalStateException("LINE bp missing `file`")
                val line = bp.line ?: throw IllegalStateException("LINE bp missing `line`")
                com.acendas.androiddebugger.breakpoints.BreakpointInstaller.installLine(
                    vm = vm,
                    id = bp.id,
                    file = file,
                    line = line,
                    condition = bp.condition,
                    hitCount = bp.hitCount,
                    logMessage = bp.logMessage,
                    enabled = bp.enabled,
                )
            }
            com.acendas.androiddebugger.breakpoints.BreakpointKind.EXCEPTION -> {
                com.acendas.androiddebugger.breakpoints.BreakpointInstaller.installException(
                    vm = vm,
                    id = bp.id,
                    exceptionClass = bp.exceptionClass,
                    caught = bp.caught,
                    uncaught = bp.uncaught,
                    enabled = bp.enabled,
                )
            }
            com.acendas.androiddebugger.breakpoints.BreakpointKind.METHOD_ENTRY -> {
                val cls = bp.methodClass ?: throw IllegalStateException("METHOD_ENTRY missing `methodClass`")
                val mn = bp.methodName ?: throw IllegalStateException("METHOD_ENTRY missing `methodName`")
                com.acendas.androiddebugger.breakpoints.BreakpointInstaller.installMethodEntry(
                    vm = vm, id = bp.id, methodClass = cls, methodName = mn, enabled = bp.enabled,
                )
            }
            com.acendas.androiddebugger.breakpoints.BreakpointKind.METHOD_EXIT -> {
                val cls = bp.methodClass ?: throw IllegalStateException("METHOD_EXIT missing `methodClass`")
                val mn = bp.methodName ?: throw IllegalStateException("METHOD_EXIT missing `methodName`")
                com.acendas.androiddebugger.breakpoints.BreakpointInstaller.installMethodExit(
                    vm = vm, id = bp.id, methodClass = cls, methodName = mn, enabled = bp.enabled,
                )
            }
            com.acendas.androiddebugger.breakpoints.BreakpointKind.FIELD_ACCESS,
            com.acendas.androiddebugger.breakpoints.BreakpointKind.FIELD_MODIFICATION -> {
                val cls = bp.fieldClass ?: throw IllegalStateException("FIELD watchpoint missing `fieldClass`")
                val fn = bp.fieldName ?: throw IllegalStateException("FIELD watchpoint missing `fieldName`")
                com.acendas.androiddebugger.breakpoints.BreakpointInstaller.installFieldWatchpoint(
                    vm = vm,
                    id = bp.id,
                    fieldClass = cls,
                    fieldName = fn,
                    wantAccess = kind == com.acendas.androiddebugger.breakpoints.BreakpointKind.FIELD_ACCESS,
                    wantModification = kind == com.acendas.androiddebugger.breakpoints.BreakpointKind.FIELD_MODIFICATION,
                    enabled = bp.enabled,
                )
            }
            com.acendas.androiddebugger.breakpoints.BreakpointKind.CLASS_LOAD -> {
                val pattern = bp.classPattern ?: throw IllegalStateException("CLASS_LOAD missing `classPattern`")
                com.acendas.androiddebugger.breakpoints.BreakpointInstaller.installClassLoad(
                    vm = vm, id = bp.id, classPattern = pattern, enabled = bp.enabled,
                )
            }
        }
    }

    /**
     * v1.5 — resolve the device's Android API level (used by [Dexer] to set d8's
     * `--min-api` flag). Order: parse `vm.version()` if it embeds an SDK int;
     * else shell `getprop ro.build.version.sdk`; else fall back to API 26
     * (v1.5 floor). Logs a warning on the fallback path because it implies the
     * device's ART surface is unusual.
     */
    internal fun resolveDeviceApiLevel(vm: com.sun.jdi.VirtualMachine, serial: String?): Int {
        // JDI's vm.version() format on ART is typically a free-form string
        // ("Android Runtime 2.1.0", "ART, 14"). Most ART builds put the OS
        // version (NOT SDK int) here, so we can't reliably parse it. Skip and
        // go straight to getprop.
        runCatching {
            val res = Session.adb.runText(
                args = listOfNotNull(
                    "-s".takeIf { !serial.isNullOrBlank() }, serial,
                    "shell", "getprop", "ro.build.version.sdk",
                ),
                timeoutMs = 3_000,
            )
            if (res is com.acendas.androiddebugger.adb.AdbResult.Success) {
                val sdk = res.stdout.trim().toIntOrNull()
                if (sdk != null && sdk in 1..99) return sdk
            }
        }
        org.slf4j.LoggerFactory.getLogger("android-debugger.lifecycle")
            .warn("Could not resolve device API level via getprop; falling back to 26 (v1.5 floor)")
        return 26
    }

    /** Resolve `(pid, package)` to a single target PID. `pid` always wins when given. */
    private fun resolveTargetPid(serial: String?, pid: Int?, pkg: String?): Int {
        if (pid != null) return pid
        if (pkg.isNullOrBlank()) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Provide either `pid` or `package` to attach.",
                hint = "Run list_debuggable_processes first to see candidates.",
            )
        }
        val pids = Session.adb.listJdwpPids(serial)
        if (pids.isEmpty()) {
            throw ToolError(ErrorCode.InvalidTarget, "No debuggable processes on this device.")
        }
        val map = Session.adb.mapPidsToPackages(serial, pids)
        val matches = map.entries.filter { it.value == pkg }
        return when (matches.size) {
            0 -> throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "No debuggable process found for package '$pkg'.",
                hint = "Currently debuggable: " + map.values.filterNotNull().distinct().joinToString(),
            )
            1 -> matches.first().key
            else -> throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Multiple PIDs match package '$pkg': ${matches.map { it.key }}.",
                hint = "Disambiguate with `pid`.",
            )
        }
    }

    private fun registerAttach(server: Server) {
        server.addTool(
            name = "attach",
            description = "Attach the debugger to a running, debuggable Android app via JDWP. " +
                "Specify the target by `pid` or `package` (pid wins). On success returns session info, " +
                "ART version, capability probe map, and any warnings (e.g., release-build detection). " +
                "The agent should read `capabilities` and avoid requesting features the device doesn't support. " +
                "**v1.4**: by default (`load_agent: true`) ALSO loads the JVMTI agent into the target app " +
                "via `cmd activity attach-agent`. The response includes an `agent: { loaded, version, " +
                "capabilities }` block. Agent load failures are non-fatal — they land in `warnings` and " +
                "the JDI session works without the agent. Pass `load_agent: false` to skip (faster attach, " +
                "no HotSwap available).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("serial") {
                        put("type", "string")
                        put("description", "Device serial. Required when multiple devices are connected.")
                    }
                    putJsonObject("pid") {
                        put("type", "integer")
                        put("description", "Process id from list_debuggable_processes. Wins over package.")
                    }
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Application package, e.g. com.example.app. Resolves to its single PID.")
                    }
                    putJsonObject("load_agent") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Load the v1.4 JVMTI agent into the target app via `cmd activity attach-agent`. " +
                                "Default true. Adds ~2-3 s to first attach (subsequent attaches in the same " +
                                "app process reuse the loaded agent — JVMTI doesn't support unload). Failures " +
                                "are non-fatal: they land in `warnings`.",
                        )
                    }
                    putJsonObject("agent_verbose") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Pass verbose=1 to the agent so it logs each RPC. Default false (quiet). " +
                                "Useful when diagnosing the agent itself.",
                        )
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { request ->
            runTool {
                if (Session.vm != null) {
                    throw ToolError(
                        errorCode = ErrorCode.AlreadyAttached,
                        message = "Already attached to ${Session.packageName ?: "(unknown)"} (pid ${Session.pid}).",
                        hint = "Detach first with /android-debugger:detach.",
                        currentState = Session.state.name,
                    )
                }
                val serialArg = (request.arguments?.get("serial") as? JsonPrimitive)?.contentOrNull
                val pidArg = (request.arguments?.get("pid") as? JsonPrimitive)?.intOrNull
                val pkgArg = (request.arguments?.get("package") as? JsonPrimitive)?.contentOrNull
                val loadAgent = (request.arguments?.get("load_agent") as? JsonPrimitive)?.booleanOrNull ?: true
                val agentVerbose = (request.arguments?.get("agent_verbose") as? JsonPrimitive)?.booleanOrNull ?: false

                val resolvedSerial = resolveSerial(serialArg)
                val targetPid = resolveTargetPid(resolvedSerial, pidArg, pkgArg)
                val pkg = Session.adb.mapPidsToPackages(resolvedSerial, listOf(targetPid))[targetPid]

                Session.state = SessionState.ATTACHING

                val port = Session.adb.forwardJdwp(resolvedSerial, targetPid)
                if (port == null) {
                    Session.state = SessionState.UNATTACHED
                    throw ToolError(
                        errorCode = ErrorCode.AdbError,
                        message = "adb forward to jdwp:$targetPid failed.",
                        hint = "Check the device is reachable and the app is debuggable.",
                    )
                }

                val vm = try {
                    JdiAttacher.attach("localhost", port)
                } catch (t: Throwable) {
                    Session.adb.removeForward(resolvedSerial, port)
                    Session.state = SessionState.UNATTACHED
                    throw ToolError(
                        errorCode = ErrorCode.AttachFailed,
                        message = "JDI attach failed: ${t.message ?: t::class.simpleName}",
                        hint = "Common causes: app is not built debuggable, another debugger is attached, " +
                            "or the JDWP port forward got stale. Try detaching any IDE debugger and retry.",
                    )
                }

                Session.vm = vm
                // Per v1.2.4: capture the underlying JDI socket via reflection so we can
                // forcibly unwedge the session if a JDI call gets stuck (Compose synthetic
                // frames, wedged adb forward, etc.). Best-effort; falls back to vm.dispose-only
                // recovery on a JDK that doesn't expose the field. Requires `--add-opens
                // jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED` on the server JVM (wired in .mcp.json).
                Session.socketWedgeRecovery =
                    com.acendas.androiddebugger.jdi.JdiSocketWedgeRecovery.captureFor(vm)
                Session.serial = resolvedSerial
                Session.packageName = pkg
                Session.pid = targetPid
                Session.forwardedPort = port
                Session.state = SessionState.ATTACHED_RUNNING
                Session.attachedAt = Instant.now()
                Session.bumpVmStateVersion()

                // Per Phase 4 wiring: spin up the JDI event-queue pump immediately
                // so `wait_for_event` and step/breakpoint flows receive events as
                // soon as they fire. Loop runs until detach() / VM disconnect.
                Session.startEventLoop(vm)

                val caps = Capabilities.probe(vm)
                // Story 7.1.1: cache the probed capabilities on Session so capability-
                // gated tools can read them without re-probing.
                Session.capabilities = caps

                // v1.5 — resolve device API level so the embedded d8 dexer produces
                // bytecode the device's ART version can read. Try parsing the JDI
                // vm.version() first ("ART, 14" → 34); fall back to getprop. Final
                // fallback is API 26 (v1.5 floor).
                val resolvedApi = resolveDeviceApiLevel(vm, resolvedSerial)
                Session.apiLevel = resolvedApi
                Session.dexer = com.acendas.androiddebugger.hotswap.Dexer(resolvedApi)

                // v1.5 — detect minified debug builds and surface as a warning. The
                // hot_swap_class tool consults Session.minifyDetected and refuses
                // upfront so the agent doesn't burn a Gradle cycle to discover this.
                Session.minifyDetected =
                    com.acendas.androiddebugger.hotswap.MinifyDetector.isMinifiedBuild(vm, pkg)

                // Story 7.1.3: identify the Android UI thread by name so the ANR
                // watchdog has a fixed reference. `name == "main"` on every Android
                // app process; if we can't find one (rare, e.g. attaching to a system
                // service worker), skip the watchdog entirely.
                val main = runCatching {
                    vm.allThreads().firstOrNull { it.name() == "main" }
                }.getOrNull()
                Session.mainThread = main
                if (main != null) {
                    val ch = Session.eventChannel
                    if (ch != null) {
                        val watchdog = com.acendas.androiddebugger.events.AnrWatchdog(main, ch)
                        Session.anrWatchdog = watchdog
                        watchdog.start()
                    }
                }
                val warnings = mutableListOf<kotlinx.serialization.json.JsonObject>()
                if (Session.minifyDetected) {
                    warnings += buildJsonObject {
                        put("type", "minified_build_detected")
                        put(
                            "hint",
                            "More than 50% of app classes have single-letter names — debug build appears " +
                                "minified (R8/ProGuard). HotSwap and other class-FQN-driven features will " +
                                "refuse with `minified_build_unsupported` until you set `minifyEnabled=false` " +
                                "on the debug build variant.",
                        )
                    }
                }
                if (Capabilities.isLikelyReleaseBuild(vm)) {
                    warnings += buildJsonObject {
                        put("type", "release_build_likely")
                        put(
                            "hint",
                            "Most user classes have stripped local-variable info. Rebuild as a debug variant " +
                                "to see locals on paused frames.",
                        )
                    }
                }

                // v1.4 — load the JVMTI agent (per D10 default-on). Failures are
                // non-fatal: they surface in `warnings`, not as a tool error.
                // JDI works without the agent; the agent unlocks v1.5+ features.
                var agentLoaded = false
                if (loadAgent && resolvedSerial != null && pkg != null) {
                    try {
                        val agentState = com.acendas.androiddebugger.jvmti.JvmtiAgentLauncher.launch(
                            adb = Session.adb,
                            serial = resolvedSerial,
                            pid = targetPid,
                            packageName = pkg,
                            verbose = agentVerbose,
                        )
                        agentLoaded = true
                        if (agentState.crashedLastSession != null) {
                            warnings += buildJsonObject {
                                put("type", "agent_crashed_last_session")
                                val c = agentState.crashedLastSession
                                put("signal", c.signal ?: "unknown")
                                c.pc?.let { put("pc", it) }
                                c.lastRpcMethod?.let { put("last_rpc_method", it) }
                                put(
                                    "hint",
                                    "The JVMTI agent crashed during a prior session in this app process. " +
                                        "The new agent has been loaded; force-stop the app to start fresh.",
                                )
                            }
                        }
                    } catch (te: ToolError) {
                        warnings += buildJsonObject {
                            put("type", "agent_load_failed")
                            put("code", te.errorCode.code)
                            put("message", te.message ?: "unknown")
                            te.hint?.let { put("hint", it) }
                        }
                    } catch (t: Throwable) {
                        warnings += buildJsonObject {
                            put("type", "agent_load_failed")
                            put("code", "internal")
                            put("message", t.message ?: t::class.simpleName ?: "unknown")
                        }
                    }
                }

                // v1.3 persistence: rehydrate prior session state if any. Per (serial,
                // package) pair, the JSON file is loaded and each breakpoint reinstalled
                // through the same install path as `add_*_breakpoint`; watches are added
                // with their original ids. Failure on any single restored item logs but
                // doesn't fail the attach.
                val restoredBreakpoints = mutableListOf<Int>()
                val restoredWatches = mutableListOf<Int>()
                val restoreErrors = mutableListOf<String>()
                val effectiveSerial = resolvedSerial
                if (effectiveSerial != null && pkg != null) {
                    val saved = com.acendas.androiddebugger.state.SessionPersistence.load(effectiveSerial, pkg)
                    if (saved != null) {
                        for (bp in saved.breakpoints) {
                            try {
                                rehydrateBreakpoint(vm, bp)
                                com.acendas.androiddebugger.breakpoints.BreakpointManager.advanceIdsTo(bp.id)
                                restoredBreakpoints += bp.id
                            } catch (t: Throwable) {
                                restoreErrors += "bp#${bp.id} (${bp.kind}): ${t.message ?: t::class.simpleName}"
                            }
                        }
                        for (w in saved.watches) {
                            if (Session.watchManager.addWithId(w.id, w.expr)) restoredWatches += w.id
                        }
                    }
                }

                toolOk {
                    put("session_id", "${resolvedSerial ?: "unknown"}:$targetPid")
                    resolvedSerial?.let { put("serial", it) }
                    put("pid", targetPid)
                    pkg?.let { put("package", it) }
                    put("vm_version", vm.version() ?: "")
                    put("vm_name", vm.name() ?: "")
                    put("jdwp_port", port)
                    put("capabilities", caps)
                    put("warnings", buildJsonArray { for (w in warnings) add(w) })
                    if (restoredBreakpoints.isNotEmpty() || restoredWatches.isNotEmpty()) {
                        put(
                            "restored",
                            buildJsonObject {
                                put("breakpoints", restoredBreakpoints.size)
                                put("watches", restoredWatches.size)
                                if (restoreErrors.isNotEmpty()) {
                                    put("errors", buildJsonArray { for (e in restoreErrors) add(e) })
                                }
                            },
                        )
                    }
                    // v1.4 — surface the agent state alongside JDI capabilities so the
                    // agent (Claude) sees both surfaces in one response without a
                    // second tool call.
                    Session.agentState?.let { st ->
                        put("agent", buildJsonObject {
                            with(com.acendas.androiddebugger.tools.AgentTools) {
                                renderAgentState(st)
                            }
                        })
                    }
                    if (!agentLoaded && loadAgent) {
                        put("agent_load_attempted", true)
                    }
                }
            }
        }
    }

    /**
     * Resolve a target device serial. If [explicit] is null and exactly one device is
     * connected, pick it. Otherwise throw [ToolError] with [ErrorCode.InvalidTarget]
     * listing the candidates so the agent can ask the user.
     */
    internal fun resolveSerial(explicit: String?): String? {
        if (!explicit.isNullOrBlank()) return explicit
        val devices = Session.adb.listDevices().filter { it.state == "device" }
        return when (devices.size) {
            0 -> throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "No devices connected.",
                hint = "Plug in a device with USB debugging enabled, or start an emulator.",
            )
            1 -> devices[0].serial
            else -> throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Multiple devices connected — pass `serial` to disambiguate.",
                hint = "Candidates: " + devices.joinToString { "${it.serial} (${it.model ?: it.state})" },
            )
        }
    }

    private fun registerListDebuggableProcesses(server: Server) {
        server.addTool(
            name = "list_debuggable_processes",
            description = "List debuggable processes (PIDs with JDWP enabled) on a connected device. " +
                "Returns each as { pid, package?, label }. Pass `serial` to disambiguate when multiple " +
                "devices are connected; otherwise the unique device is auto-selected. By default, system " +
                "processes (no package, low PID) are filtered out — pass `include_system: true` to include them.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("serial") {
                        put("type", "string")
                        put("description", "Device serial. Required when multiple devices are connected.")
                    }
                    putJsonObject("include_system") {
                        put("type", "boolean")
                        put("description", "Include system/non-package processes. Default false.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
        ) { request ->
            runTool {
                val serialArg = (request.arguments?.get("serial") as? JsonPrimitive)?.contentOrNull
                val includeSystem = (request.arguments?.get("include_system") as? JsonPrimitive)?.booleanOrNull ?: false

                val resolved = resolveSerial(serialArg)
                val pids = Session.adb.listJdwpPids(resolved)

                if (pids.isEmpty()) {
                    return@runTool toolOk {
                        put("processes", buildJsonArray { })
                        put("count", 0)
                        resolved?.let { put("serial", it) }
                    }
                }

                val pkgMap = Session.adb.mapPidsToPackages(resolved, pids)
                val rows = pids.mapNotNull { pid ->
                    val pkg = pkgMap[pid]
                    val isSystem = pid < 1000 || pkg == null
                    if (!includeSystem && isSystem) return@mapNotNull null
                    buildJsonObject {
                        put("pid", pid)
                        if (pkg != null) put("package", pkg)
                        put("label", pkg ?: "(pid $pid)")
                    }
                }

                toolOk {
                    put("processes", buildJsonArray { rows.forEach { add(it) } })
                    put("count", rows.size)
                    resolved?.let { put("serial", it) }
                }
            }
        }
    }

    private fun registerListDevices(server: Server) {
        server.addTool(
            name = "list_devices",
            description = "List Android devices and emulators visible to adb. Returns each device's " +
                "serial, state (device/unauthorized/offline), and optional model/product/device codename. " +
                "Empty list if no devices are connected.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
        ) {
            runTool {
                val devices = Session.adb.listDevices()
                toolOk {
                    put("devices", buildJsonArray {
                        for (d in devices) {
                            add(buildJsonObject {
                                put("serial", d.serial)
                                put("state", d.state)
                                d.model?.let { put("model", it) }
                                d.product?.let { put("product", it) }
                                d.device?.let { put("device", it) }
                            })
                        }
                    })
                    put("count", devices.size)
                }
            }
        }
    }
}
