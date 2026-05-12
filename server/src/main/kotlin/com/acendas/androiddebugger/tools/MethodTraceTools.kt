package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.jvmti.AgentMethodTrace
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * v1.6 — JVMTI-backed method-entry/exit trace tools. Four tools that route
 * through the agent's `agent.method_trace_*` RPCs:
 *
 *  - `start_method_trace` — register a filter + event-kind set, get a buffer id.
 *  - `read_method_trace` — drain events from a buffer.
 *  - `stop_method_trace` — terminate a buffer, drain tail events.
 *  - `list_method_traces` — enumerate active traces.
 *
 * All refuse with `agent_not_loaded` when the JVMTI agent isn't loaded. All
 * gate on `can_generate_method_entry_events` / `can_generate_method_exit_events`
 * before transmitting.
 *
 * **Not** routed through [com.acendas.androiddebugger.inspection.VmCoordinator] —
 * method traces are pure event streams and don't touch the class-metadata surface
 * that eval / hot_swap mutate.
 *
 * Active buffer ids are tracked in `Session.methodTraceBufferIds` so detach can
 * stop them cleanly through `agent.stop_all_traces`.
 */
object MethodTraceTools {

    fun register(server: Server) {
        registerStartMethodTrace(server)
        registerReadMethodTrace(server)
        registerStopMethodTrace(server)
        registerListMethodTraces(server)
    }

    // ---------------- start_method_trace ----------------

    private fun registerStartMethodTrace(server: Server) {
        server.addTool(
            name = "start_method_trace",
            description = "v1.6 — start a JVMTI method entry/exit trace. Pick exactly ONE filter " +
                "shape: `methods` (list of `Lclass;method` keys), `class_pattern` + optional " +
                "`method_pattern` (glob), or `method_regex` (a Java-flavored regex over the same " +
                "key). Returns a `buffer_id` to feed into `read_method_trace`. The agent buffers " +
                "events in a bounded queue; tune `buffer_size`, `max_events_per_sec`, and " +
                "`sample_rate` to keep dropped counts manageable. Agent-only — refuses with " +
                "`agent_not_loaded` if no JVMTI agent. Capability-gated on " +
                "`can_generate_method_entry_events` and `can_generate_method_exit_events`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("filter_kind") {
                        put("type", "string")
                        put("description", "`methods`, `class_pattern`, or `method_regex`.")
                    }
                    putJsonObject("methods") {
                        put("type", "array")
                        put("description", "filter_kind=methods: list of `Lclass;method` keys (no signature).")
                    }
                    putJsonObject("class_pattern") {
                        put("type", "string")
                        put("description", "filter_kind=class_pattern: glob like `com.example.*`.")
                    }
                    putJsonObject("method_pattern") {
                        put("type", "string")
                        put("description", "filter_kind=class_pattern: optional method-name glob.")
                    }
                    putJsonObject("method_regex") {
                        put("type", "string")
                        put("description", "filter_kind=method_regex: regex over `Lclass;method` keys.")
                    }
                    putJsonObject("include_args") {
                        put("type", "boolean")
                        put("description", "Capture method args. Default false (per-frame metadata costs).")
                    }
                    putJsonObject("include_return") {
                        put("type", "boolean")
                        put("description", "Capture return value on exit events. Default false.")
                    }
                    putJsonObject("kinds") {
                        put("type", "array")
                        put("description", "Subset of [\"entry\", \"exit\"]. Default both.")
                    }
                    putJsonObject("max_events_per_sec") {
                        put("type", "integer")
                        put("description", "Throttle. Default 1000.")
                    }
                    putJsonObject("sample_rate") {
                        put("type", "number")
                        put("description", "Fraction in (0.0, 1.0]. Default 1.0 (every event).")
                    }
                    putJsonObject("buffer_size") {
                        put("type", "integer")
                        put("description", "Agent-side ring buffer. Default 10000.")
                    }
                },
                required = listOf("filter_kind"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val client = requireAgentClient()
                requireMethodTraceCapability()

                val args = request.arguments
                val filterStr = (args?.get("filter_kind") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `filter_kind`.")
                val filterKind = parseFilterKind(filterStr)

                val methods = (args.get("methods") as? JsonArray)?.map {
                    (it as? JsonPrimitive)?.contentOrNull
                        ?: throw ToolError(ErrorCode.InvalidTarget, "`methods` entries must be strings.")
                }
                val classPattern = (args.get("class_pattern") as? JsonPrimitive)?.contentOrNull
                val methodPattern = (args.get("method_pattern") as? JsonPrimitive)?.contentOrNull
                val methodRegex = (args.get("method_regex") as? JsonPrimitive)?.contentOrNull
                val includeArgs = (args.get("include_args") as? JsonPrimitive)?.booleanOrNull ?: false
                val includeReturn = (args.get("include_return") as? JsonPrimitive)?.booleanOrNull ?: false
                val kindsArr = args.get("kinds") as? JsonArray
                val kinds: Set<AgentMethodTrace.EventKind> = if (kindsArr == null) {
                    setOf(AgentMethodTrace.EventKind.ENTRY, AgentMethodTrace.EventKind.EXIT)
                } else {
                    kindsArr.map {
                        when ((it as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                            "entry" -> AgentMethodTrace.EventKind.ENTRY
                            "exit" -> AgentMethodTrace.EventKind.EXIT
                            else -> throw ToolError(
                                errorCode = ErrorCode.InvalidTarget,
                                message = "kinds entries must be `entry` or `exit`.",
                            )
                        }
                    }.toSet()
                }
                val maxEventsPerSec = (args.get("max_events_per_sec") as? JsonPrimitive)?.intOrNull ?: 1000
                val sampleRate = (args.get("sample_rate") as? JsonPrimitive)?.doubleOrNull ?: 1.0
                val bufferSize = (args.get("buffer_size") as? JsonPrimitive)?.intOrNull ?: 10000

                val req = AgentMethodTrace.StartRequest(
                    filterKind = filterKind,
                    methods = methods,
                    classPattern = classPattern,
                    methodPattern = methodPattern,
                    methodRegex = methodRegex,
                    includeArgs = includeArgs,
                    includeReturn = includeReturn,
                    kinds = kinds,
                    maxEventsPerSec = maxEventsPerSec,
                    sampleRate = sampleRate,
                    bufferSize = bufferSize,
                )
                val result = AgentMethodTrace.start(client, req)
                Session.methodTraceBufferIds.add(result.bufferId)

                toolOk {
                    put("buffer_id", result.bufferId)
                    put("started_at_ms", result.startedAtMs)
                    put("filter_kind", result.filterKind)
                    put("estimated_match_count", result.estimatedMatchCount)
                }
            }
        }
    }

    // ---------------- read_method_trace ----------------

    private fun registerReadMethodTrace(server: Server) {
        server.addTool(
            name = "read_method_trace",
            description = "v1.6 — drain up to `max` events from a method-trace buffer (agent caps " +
                "at 5000). Returns `events`, `buffered` (remaining in agent buffer), " +
                "`dropped_since_last_read`, and `dropped_total`. Each event has `kind`, `class`, " +
                "`method`, `thread`, `nano_time`, `depth`; on entry events also `args` (when " +
                "`include_args` was set, else `args_absent`); on exit events also `elapsed_ns`, " +
                "`return` or `void`, and `was_popped_by_exception`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("buffer_id") {
                        put("type", "string")
                        put("description", "Buffer id from `start_method_trace`.")
                    }
                    putJsonObject("max") {
                        put("type", "integer")
                        put("description", "Max events to return. Default 500; hard cap 5000.")
                    }
                },
                required = listOf("buffer_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val client = requireAgentClient()
                val bufferId = (request.arguments?.get("buffer_id") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `buffer_id`.")
                val max = (request.arguments?.get("max") as? JsonPrimitive)?.intOrNull ?: 500
                val result = AgentMethodTrace.read(client, bufferId, max)
                toolOk {
                    put("buffer_id", result.bufferId)
                    put("events", buildJsonArray {
                        for (e in result.events) add(methodEventToJson(e))
                    })
                    put("buffered", result.buffered)
                    put("dropped_since_last_read", result.droppedSinceLastRead)
                    put("dropped_total", result.droppedTotal)
                }
            }
        }
    }

    // ---------------- stop_method_trace ----------------

    private fun registerStopMethodTrace(server: Server) {
        server.addTool(
            name = "stop_method_trace",
            description = "v1.6 — terminate a method-trace buffer. Returns `total_events` collected " +
                "this run, `dropped_total`, and any `tail_events` drained on close. The buffer id " +
                "is freed; further `read_method_trace` calls against it return `unknown_buffer_id`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("buffer_id") {
                        put("type", "string")
                        put("description", "Buffer id from `start_method_trace`.")
                    }
                },
                required = listOf("buffer_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val client = requireAgentClient()
                val bufferId = (request.arguments?.get("buffer_id") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `buffer_id`.")
                val result = AgentMethodTrace.stop(client, bufferId)
                Session.methodTraceBufferIds.remove(bufferId)
                toolOk {
                    put("buffer_id", result.bufferId)
                    put("stopped_at_ms", result.stoppedAtMs)
                    put("total_events", result.totalEvents)
                    put("dropped_total", result.droppedTotal)
                    put("tail_events", buildJsonArray {
                        for (e in result.tailEvents) add(methodEventToJson(e))
                    })
                }
            }
        }
    }

    // ---------------- list_method_traces ----------------

    private fun registerListMethodTraces(server: Server) {
        server.addTool(
            name = "list_method_traces",
            description = "v1.6 — list every active method-trace buffer with its filter kind, " +
                "start time, current buffered count, and total dropped. Use to find a stale " +
                "buffer you forgot to stop.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) {
            runTool {
                Session.requireAttached()
                val client = requireAgentClient()
                val list = AgentMethodTrace.list(client)
                toolOk {
                    put("traces", buildJsonArray {
                        for (e in list) addJsonObject {
                            put("buffer_id", e.bufferId)
                            put("filter_kind", e.filterKind)
                            put("started_at_ms", e.startedAtMs)
                            put("buffered", e.buffered)
                            put("dropped_total", e.droppedTotal)
                        }
                    })
                    put("count", list.size)
                }
            }
        }
    }

    // ---------------- helpers ----------------

    private fun requireAgentClient(): com.acendas.androiddebugger.jvmti.AgentClient =
        Session.agentClient
            ?: throw ToolError(
                errorCode = ErrorCode.CapabilityUnavailable,
                message = "agent_not_loaded: JVMTI agent not loaded into target app.",
                hint = "Re-run /android-debugger:attach without `load_agent: false`.",
            )

    private fun requireMethodTraceCapability() {
        val caps = Session.agentState?.capabilities
        val entry = (caps?.get("can_generate_method_entry_events") as? JsonPrimitive)?.booleanOrNull == true
        val exit = (caps?.get("can_generate_method_exit_events") as? JsonPrimitive)?.booleanOrNull == true
        if (!entry || !exit) {
            throw ToolError(
                errorCode = ErrorCode.CapabilityUnavailable,
                message = "capability_unavailable: device's ART doesn't report can_generate_method_entry_events " +
                    "and can_generate_method_exit_events.",
                hint = "Method tracing requires JVMTI on Android 8+ (API 26).",
            )
        }
    }

    private fun parseFilterKind(s: String): AgentMethodTrace.FilterKind = when (s.lowercase()) {
        "methods" -> AgentMethodTrace.FilterKind.METHODS
        "class_pattern" -> AgentMethodTrace.FilterKind.CLASS_PATTERN
        "method_regex" -> AgentMethodTrace.FilterKind.METHOD_REGEX
        else -> throw ToolError(
            errorCode = ErrorCode.InvalidTarget,
            message = "filter_kind must be `methods`, `class_pattern`, or `method_regex`; got `$s`.",
        )
    }

    /**
     * Render one [AgentMethodTrace.MethodEvent] to JSON. Note: args/return are
     * [JsonElement] passthrough — whatever the agent emitted lands here verbatim,
     * with no re-serialization that could lose precision.
     */
    private fun methodEventToJson(e: AgentMethodTrace.MethodEvent) = buildJsonObject {
        put("kind", when (e.kind) {
            AgentMethodTrace.EventKind.ENTRY -> "entry"
            AgentMethodTrace.EventKind.EXIT -> "exit"
        })
        put("class", e.classSig)
        put("method", e.method)
        put("thread", e.thread)
        put("nano_time", e.nanoTime)
        put("depth", e.depth)
        if (e.argsRaw != null) put("args", e.argsRaw)
        if (e.argsAbsent) put("args_absent", true)
        if (e.returnRaw != null) put("return", e.returnRaw)
        if (e.isVoid) put("void", true)
        e.elapsedNs?.let { put("elapsed_ns", it) }
        if (e.wasPoppedByException) put("was_popped_by_exception", true)
    }
}
