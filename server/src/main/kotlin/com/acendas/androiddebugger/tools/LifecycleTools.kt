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
                "Always run this when finished — abandoned attachments leave the app suspended at the next breakpoint hit.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) {
            runTool {
                val result = Session.detach()
                toolOk {
                    put("was_attached", result.wasAttached)
                    result.releasedPort?.let { put("released_port", it) }
                }
            }
        }
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
                "The agent should read `capabilities` and avoid requesting features the device doesn't support.",
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
                val warnings = buildJsonArray {
                    if (Capabilities.isLikelyReleaseBuild(vm)) {
                        add(buildJsonObject {
                            put("type", "release_build_likely")
                            put(
                                "hint",
                                "Most user classes have stripped local-variable info. Rebuild as a debug variant " +
                                    "to see locals on paused frames.",
                            )
                        })
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
                    put("warnings", warnings)
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
