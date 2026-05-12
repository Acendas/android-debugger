package com.acendas.androiddebugger.jvmti

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * v1.6 method-trace wrapper over [AgentClient]. Encodes/decodes the four
 * `agent.method_trace_*` JSON-RPC methods and `agent.stop_all_traces`.
 *
 * Per v1.6 spec — method-trace RPCs.
 *
 * All methods translate [AgentRpcError]s into [ToolError]s with structured
 * codes the MCP boundary surfaces verbatim.
 */
object AgentMethodTrace {

    enum class FilterKind { METHODS, CLASS_PATTERN, METHOD_REGEX }
    enum class EventKind { ENTRY, EXIT }

    data class StartRequest(
        val filterKind: FilterKind,
        val methods: List<String>? = null,
        val classPattern: String? = null,
        val methodPattern: String? = null,
        val methodRegex: String? = null,
        val includeArgs: Boolean = false,
        val includeReturn: Boolean = false,
        val kinds: Set<EventKind> = setOf(EventKind.ENTRY, EventKind.EXIT),
        val maxEventsPerSec: Int = 1000,
        val sampleRate: Double = 1.0,
        val bufferSize: Int = 10000,
    )

    data class StartResult(
        val bufferId: String,
        val startedAtMs: Long,
        val filterKind: String,
        val estimatedMatchCount: Int,
    )

    data class MethodEvent(
        val kind: EventKind,
        val classSig: String,
        val method: String,
        val thread: String,
        val nanoTime: Long,
        val depth: Int,
        val argsRaw: JsonElement?,
        val argsAbsent: Boolean,
        val returnRaw: JsonElement?,
        val isVoid: Boolean,
        val elapsedNs: Long?,
        val wasPoppedByException: Boolean,
    )

    data class ReadResult(
        val bufferId: String,
        val events: List<MethodEvent>,
        val buffered: Long,
        val droppedSinceLastRead: Long,
        val droppedTotal: Long,
    )

    data class StopResult(
        val bufferId: String,
        val stoppedAtMs: Long,
        val totalEvents: Long,
        val droppedTotal: Long,
        val tailEvents: List<MethodEvent>,
    )

    data class ListEntry(
        val bufferId: String,
        val filterKind: String,
        val startedAtMs: Long,
        val buffered: Long,
        val droppedTotal: Long,
    )

    /**
     * Send `agent.method_trace_start`. Validates [req] before transmission:
     * exactly one of methods/classPattern/methodRegex must be set per
     * [FilterKind]; sample rate must be in (0.0, 1.0].
     */
    suspend fun start(client: AgentClient, req: StartRequest): StartResult {
        validateStartRequest(req)
        val params = buildJsonObject {
            put("filter_kind", filterKindWire(req.filterKind))
            when (req.filterKind) {
                FilterKind.METHODS -> {
                    put("methods", buildJsonArray { req.methods!!.forEach { add(it) } })
                }
                FilterKind.CLASS_PATTERN -> {
                    put("class_pattern", req.classPattern!!)
                    if (req.methodPattern != null) put("method_pattern", req.methodPattern)
                }
                FilterKind.METHOD_REGEX -> {
                    put("method_regex", req.methodRegex!!)
                }
            }
            put("include_args", req.includeArgs)
            put("include_return", req.includeReturn)
            put("kinds", buildJsonArray { req.kinds.forEach { add(eventKindWire(it)) } })
            put("max_events_per_sec", req.maxEventsPerSec)
            put("sample_rate", req.sampleRate)
            put("buffer_size", req.bufferSize)
        }
        val result = try {
            client.request("agent.method_trace_start", params, timeoutMs = 10_000L)
        } catch (e: AgentRpcError) {
            mapTraceError(e, "agent.method_trace_start")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.method_trace_start returned non-object")
        return StartResult(
            bufferId = obj["buffer_id"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_start missing `buffer_id`"),
            startedAtMs = obj["started_at_ms"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_start missing `started_at_ms`"),
            filterKind = obj["filter_kind"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_start missing `filter_kind`"),
            estimatedMatchCount = obj["estimated_match_count"]?.jsonPrimitive?.intOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_start missing `estimated_match_count`"),
        )
    }

    /** Send `agent.method_trace_read`. [max] is capped to 5000 by the agent. */
    suspend fun read(client: AgentClient, bufferId: String, max: Int = 500): ReadResult {
        val params = buildJsonObject {
            put("buffer_id", bufferId)
            put("max", max)
        }
        val result = try {
            client.request("agent.method_trace_read", params, timeoutMs = 5_000L)
        } catch (e: AgentRpcError) {
            mapTraceError(e, "agent.method_trace_read")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.method_trace_read returned non-object")
        val arr = obj["events"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "agent.method_trace_read missing `events`")
        return ReadResult(
            bufferId = obj["buffer_id"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_read missing `buffer_id`"),
            events = arr.map { parseMethodEvent(it) },
            buffered = obj["buffered"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_read missing `buffered`"),
            droppedSinceLastRead = obj["dropped_since_last_read"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_read missing `dropped_since_last_read`"),
            droppedTotal = obj["dropped_total"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_read missing `dropped_total`"),
        )
    }

    /** Send `agent.method_trace_stop`. Returns tail events drained from the buffer on close. */
    suspend fun stop(client: AgentClient, bufferId: String): StopResult {
        val params = buildJsonObject { put("buffer_id", bufferId) }
        val result = try {
            client.request("agent.method_trace_stop", params, timeoutMs = 10_000L)
        } catch (e: AgentRpcError) {
            mapTraceError(e, "agent.method_trace_stop")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.method_trace_stop returned non-object")
        val arr = obj["tail_events"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "agent.method_trace_stop missing `tail_events`")
        return StopResult(
            bufferId = obj["buffer_id"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_stop missing `buffer_id`"),
            stoppedAtMs = obj["stopped_at_ms"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_stop missing `stopped_at_ms`"),
            totalEvents = obj["total_events"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_stop missing `total_events`"),
            droppedTotal = obj["dropped_total"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace_stop missing `dropped_total`"),
            tailEvents = arr.map { parseMethodEvent(it) },
        )
    }

    /** Send `agent.method_trace_list`. */
    suspend fun list(client: AgentClient): List<ListEntry> {
        val result = try {
            client.request("agent.method_trace_list", buildJsonObject {}, timeoutMs = 5_000L)
        } catch (e: AgentRpcError) {
            mapTraceError(e, "agent.method_trace_list")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.method_trace_list returned non-object")
        val arr = obj["traces"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "agent.method_trace_list missing `traces`")
        return arr.map { el ->
            val o = el as? JsonObject
                ?: throw ToolError(ErrorCode.Internal, "method_trace_list: non-object in `traces`")
            ListEntry(
                bufferId = o["buffer_id"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolError(ErrorCode.Internal, "method_trace_list entry missing `buffer_id`"),
                filterKind = o["filter_kind"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolError(ErrorCode.Internal, "method_trace_list entry missing `filter_kind`"),
                startedAtMs = o["started_at_ms"]?.jsonPrimitive?.longOrNull
                    ?: throw ToolError(ErrorCode.Internal, "method_trace_list entry missing `started_at_ms`"),
                buffered = o["buffered"]?.jsonPrimitive?.longOrNull
                    ?: throw ToolError(ErrorCode.Internal, "method_trace_list entry missing `buffered`"),
                droppedTotal = o["dropped_total"]?.jsonPrimitive?.longOrNull
                    ?: throw ToolError(ErrorCode.Internal, "method_trace_list entry missing `dropped_total`"),
            )
        }
    }

    /** Send `agent.stop_all_traces`. Used at detach to drain every active trace. */
    suspend fun stopAll(client: AgentClient) {
        try {
            client.request("agent.stop_all_traces", buildJsonObject {}, timeoutMs = 10_000L)
        } catch (e: AgentRpcError) {
            mapTraceError(e, "agent.stop_all_traces")
        }
    }

    private fun validateStartRequest(req: StartRequest) {
        // Exactly one filter source set per kind.
        when (req.filterKind) {
            FilterKind.METHODS -> {
                if (req.methods.isNullOrEmpty()) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "method_trace_start: filter_kind=METHODS requires non-empty `methods` list.",
                    )
                }
                if (req.classPattern != null || req.methodRegex != null) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "method_trace_start: filter_kind=METHODS conflicts with classPattern/methodRegex.",
                    )
                }
            }
            FilterKind.CLASS_PATTERN -> {
                if (req.classPattern.isNullOrBlank()) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "method_trace_start: filter_kind=CLASS_PATTERN requires `classPattern`.",
                    )
                }
                if (req.methods != null || req.methodRegex != null) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "method_trace_start: filter_kind=CLASS_PATTERN conflicts with methods/methodRegex.",
                    )
                }
            }
            FilterKind.METHOD_REGEX -> {
                if (req.methodRegex.isNullOrBlank()) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "method_trace_start: filter_kind=METHOD_REGEX requires `methodRegex`.",
                    )
                }
                if (req.methods != null || req.classPattern != null) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "method_trace_start: filter_kind=METHOD_REGEX conflicts with methods/classPattern.",
                    )
                }
            }
        }
        if (req.sampleRate <= 0.0 || req.sampleRate > 1.0) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "method_trace_start: sampleRate=${req.sampleRate} must be in (0.0, 1.0].",
            )
        }
        if (req.kinds.isEmpty()) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "method_trace_start: `kinds` must contain at least one of ENTRY, EXIT.",
            )
        }
    }

    private fun filterKindWire(k: FilterKind): String = when (k) {
        FilterKind.METHODS -> "methods"
        FilterKind.CLASS_PATTERN -> "class_pattern"
        FilterKind.METHOD_REGEX -> "method_regex"
    }

    private fun eventKindWire(k: EventKind): String = when (k) {
        EventKind.ENTRY -> "entry"
        EventKind.EXIT -> "exit"
    }

    private fun parseEventKind(s: String): EventKind = when (s) {
        "entry" -> EventKind.ENTRY
        "exit" -> EventKind.EXIT
        else -> throw ToolError(ErrorCode.Internal, "method_trace: unknown event kind `$s`")
    }

    private fun parseMethodEvent(el: JsonElement): MethodEvent {
        val o = el as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "method_trace: non-object event")
        val kindStr = o["kind"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolError(ErrorCode.Internal, "method_trace event missing `kind`")
        return MethodEvent(
            kind = parseEventKind(kindStr),
            classSig = o["class"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace event missing `class`"),
            method = o["method"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace event missing `method`"),
            thread = o["thread"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace event missing `thread`"),
            nanoTime = o["nano_time"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace event missing `nano_time`"),
            depth = o["depth"]?.jsonPrimitive?.intOrNull
                ?: throw ToolError(ErrorCode.Internal, "method_trace event missing `depth`"),
            argsRaw = o["args"],
            argsAbsent = o["args_absent"]?.jsonPrimitive?.booleanOrNull ?: false,
            returnRaw = o["return"],
            isVoid = o["void"]?.jsonPrimitive?.booleanOrNull ?: false,
            elapsedNs = o["elapsed_ns"]?.jsonPrimitive?.longOrNull,
            wasPoppedByException = o["was_popped_by_exception"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun mapTraceError(e: AgentRpcError, methodName: String): Nothing {
        when (e.rpcMessage) {
            "unknown_buffer_id" -> {
                val badId = e.data?.get("buffer_id")?.jsonPrimitive?.contentOrNull
                throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "Unknown method-trace buffer: ${badId ?: "unknown"}",
                )
            }
            "no_classes_loaded" -> throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "None of the requested classes are loaded in the VM.",
                hint = "Trigger the code path that loads them first, then retry.",
            )
            else -> throw ToolError(
                errorCode = ErrorCode.Internal,
                message = "$methodName failed: ${e.rpcMessage}",
            )
        }
    }
}
