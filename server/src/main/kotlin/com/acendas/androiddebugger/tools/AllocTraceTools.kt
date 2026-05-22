package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.jvmti.AgentAllocTrace
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * v1.6 — JVMTI-backed allocation-tracking tools. Four tools that route through
 * the agent's `agent.alloc_trace_*` RPCs:
 *
 *  - `start_alloc_trace` — subscribe to allocation events for one or more classes.
 *  - `read_alloc_trace` — drain events from a buffer.
 *  - `stop_alloc_trace` — terminate a buffer.
 *  - `list_alloc_traces` — enumerate active alloc traces.
 *
 * All refuse with `agent_not_loaded` when the JVMTI agent isn't loaded. All gate
 * on `can_generate_vm_object_alloc_events` before transmitting. Not routed
 * through VmCoordinator — pure event streams.
 *
 * Active buffer ids are tracked in `Session.allocTraceBufferIds` so detach can
 * stop them through `agent.stop_all_traces`.
 */
object AllocTraceTools {

    fun register(server: Server) {
        registerStartAllocTrace(server)
        registerReadAllocTrace(server)
        registerStopAllocTrace(server)
        registerListAllocTraces(server)
    }

    // ---------------- start_alloc_trace ----------------

    private fun registerStartAllocTrace(server: Server) {
        server.addTool(
            name = "start_alloc_trace",
            description = "v1.6 — subscribe to JVMTI VM-object allocation events for one or more " +
                "classes. Returns a `buffer_id` plus how many of the requested classes were resolved " +
                "(unresolved are listed too — they aren't loaded yet). Optional `capture_stack_depth` " +
                "(0..10) attaches a backtrace to each event. " +
                "Agent-only — refuses with `agent_not_loaded`. Capability-gated on " +
                "`can_generate_vm_object_alloc_events`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class_signatures") {
                        put("type", "array")
                        put("description", "JVM internal signatures, e.g. [\"Lcom/example/Foo;\"].")
                    }
                    putJsonObject("capture_stack_depth") {
                        put("type", "integer")
                        put("description", "Frames per event (0..10). Default 0.")
                    }
                    putJsonObject("max_events_per_sec") {
                        put("type", "integer")
                        put("description", "Throttle. Default 1000.")
                    }
                    putJsonObject("buffer_size") {
                        put("type", "integer")
                        put("description", "Agent-side ring buffer. Default 10000.")
                    }
                },
                required = listOf("class_signatures"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val client = requireAgentClient()
                requireAllocTraceCapability()

                val args = request.arguments
                val sigsArr = args?.get("class_signatures") as? JsonArray
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `class_signatures` array.")
                val classSignatures = sigsArr.map {
                    (it as? JsonPrimitive)?.contentOrNull
                        ?: throw ToolError(ErrorCode.InvalidTarget, "`class_signatures` entries must be strings.")
                }
                val captureStackDepth = (args.get("capture_stack_depth") as? JsonPrimitive)?.intOrNull ?: 0
                val maxEventsPerSec = (args.get("max_events_per_sec") as? JsonPrimitive)?.intOrNull ?: 1000
                val bufferSize = (args.get("buffer_size") as? JsonPrimitive)?.intOrNull ?: 10000

                val result = AgentAllocTrace.start(
                    client = client,
                    classSignatures = classSignatures,
                    captureStackDepth = captureStackDepth,
                    maxEventsPerSec = maxEventsPerSec,
                    bufferSize = bufferSize,
                )
                Session.allocTraceBufferIds.add(result.bufferId)

                toolOk {
                    put("buffer_id", result.bufferId)
                    put("started_at_ms", result.startedAtMs)
                    put("resolved_classes", result.resolvedClasses)
                    put("unresolved_classes", buildJsonArray { for (s in result.unresolvedClasses) add(s) })
                }
            }
        }
    }

    // ---------------- read_alloc_trace ----------------

    private fun registerReadAllocTrace(server: Server) {
        server.addTool(
            name = "read_alloc_trace",
            description = "v1.6 — drain up to `max` events from an alloc-trace buffer (agent caps " +
                "at 5000). Returns `events`, `buffered`, `dropped_since_last_read`, " +
                "`dropped_total`. Each event has `class`, `thread`, `nano_time`, `size_bytes`, and " +
                "`stack` (empty unless `capture_stack_depth > 0` at start time).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("buffer_id") {
                        put("type", "string")
                        put("description", "Buffer id from `start_alloc_trace`.")
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
            runTool(allowsDuringPlan = true, toolName = "read_alloc_trace") {
                Session.requireAttached()
                val client = requireAgentClient()
                val bufferId = (request.arguments?.get("buffer_id") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `buffer_id`.")
                val max = (request.arguments?.get("max") as? JsonPrimitive)?.intOrNull ?: 500
                val result = AgentAllocTrace.read(client, bufferId, max)
                toolOk {
                    put("buffer_id", result.bufferId)
                    put("events", buildJsonArray {
                        for (e in result.events) add(allocEventToJson(e))
                    })
                    put("buffered", result.buffered)
                    put("dropped_since_last_read", result.droppedSinceLastRead)
                    put("dropped_total", result.droppedTotal)
                }
            }
        }
    }

    // ---------------- stop_alloc_trace ----------------

    private fun registerStopAllocTrace(server: Server) {
        server.addTool(
            name = "stop_alloc_trace",
            description = "v1.6 — terminate an alloc-trace buffer. Returns `total_events`, " +
                "`dropped_total`, and `tail_events` drained on close. The buffer id is freed; " +
                "further `read_alloc_trace` calls against it return `unknown_buffer_id`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("buffer_id") {
                        put("type", "string")
                        put("description", "Buffer id from `start_alloc_trace`.")
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
                val result = AgentAllocTrace.stop(client, bufferId)
                Session.allocTraceBufferIds.remove(bufferId)
                toolOk {
                    put("buffer_id", result.bufferId)
                    put("stopped_at_ms", result.stoppedAtMs)
                    put("total_events", result.totalEvents)
                    put("dropped_total", result.droppedTotal)
                    put("tail_events", buildJsonArray {
                        for (e in result.tailEvents) add(allocEventToJson(e))
                    })
                }
            }
        }
    }

    // ---------------- list_alloc_traces ----------------

    private fun registerListAllocTraces(server: Server) {
        server.addTool(
            name = "list_alloc_traces",
            description = "v1.6 — list every active alloc-trace buffer with start time, class count, " +
                "buffered count, and dropped total.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) {
            runTool(allowsDuringPlan = true, toolName = "list_alloc_traces") {
                Session.requireAttached()
                val client = requireAgentClient()
                val list = AgentAllocTrace.list(client)
                toolOk {
                    put("traces", buildJsonArray {
                        for (e in list) addJsonObject {
                            put("buffer_id", e.bufferId)
                            put("started_at_ms", e.startedAtMs)
                            put("buffered", e.buffered)
                            put("dropped_total", e.droppedTotal)
                            put("class_count", e.classCount)
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

    private fun requireAllocTraceCapability() {
        val caps = Session.agentState?.capabilities
        val canAlloc = (caps?.get("can_generate_vm_object_alloc_events") as? JsonPrimitive)
            ?.booleanOrNull == true
        if (!canAlloc) {
            throw ToolError(
                errorCode = ErrorCode.CapabilityUnavailable,
                message = "capability_unavailable: device's ART doesn't report " +
                    "can_generate_vm_object_alloc_events.",
                hint = "VM-object allocation events require JVMTI on Android 8+ (API 26).",
            )
        }
    }

    private fun allocEventToJson(e: AgentAllocTrace.AllocEvent) = buildJsonObject {
        put("class", e.classSig)
        put("thread", e.thread)
        put("nano_time", e.nanoTime)
        put("size_bytes", e.sizeBytes)
        put("stack", buildJsonArray {
            for (s in e.stack) addJsonObject {
                put("class", s.classSig)
                put("method", s.method)
                put("line", s.line)
            }
        })
    }
}
