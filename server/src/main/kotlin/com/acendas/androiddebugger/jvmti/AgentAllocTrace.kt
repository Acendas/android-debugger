package com.acendas.androiddebugger.jvmti

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * v1.6 allocation-trace wrapper over [AgentClient]. Encodes/decodes the four
 * `agent.alloc_trace_*` JSON-RPC methods.
 *
 * Per v1.6 spec — alloc-trace RPCs.
 *
 * All methods translate [AgentRpcError]s into [ToolError]s with structured
 * codes the MCP boundary surfaces verbatim.
 */
object AgentAllocTrace {

    data class StackFrame(val classSig: String, val method: String, val line: Int)

    data class AllocEvent(
        val classSig: String,
        val thread: String,
        val nanoTime: Long,
        val sizeBytes: Long,
        val stack: List<StackFrame>,
    )

    data class StartResult(
        val bufferId: String,
        val startedAtMs: Long,
        val resolvedClasses: Int,
        val unresolvedClasses: List<String>,
    )

    data class ReadResult(
        val bufferId: String,
        val events: List<AllocEvent>,
        val buffered: Long,
        val droppedSinceLastRead: Long,
        val droppedTotal: Long,
    )

    data class StopResult(
        val bufferId: String,
        val stoppedAtMs: Long,
        val totalEvents: Long,
        val droppedTotal: Long,
        val tailEvents: List<AllocEvent>,
    )

    data class ListEntry(
        val bufferId: String,
        val startedAtMs: Long,
        val buffered: Long,
        val droppedTotal: Long,
        val classCount: Int,
    )

    /**
     * Send `agent.alloc_trace_start`. Validates [classSignatures] (non-empty,
     * each `L...;`) and [captureStackDepth] (0..10) before transmission.
     */
    suspend fun start(
        client: AgentClient,
        classSignatures: List<String>,
        captureStackDepth: Int = 0,
        maxEventsPerSec: Int = 1000,
        bufferSize: Int = 10000,
    ): StartResult {
        if (classSignatures.isEmpty()) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "alloc_trace_start: `classSignatures` must be non-empty.",
            )
        }
        classSignatures.forEach { sig ->
            if (!sig.startsWith("L") || !sig.endsWith(";")) {
                throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "alloc_trace_start: class signature `$sig` must be JVM internal form (L...;).",
                )
            }
        }
        if (captureStackDepth !in 0..10) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "alloc_trace_start: captureStackDepth=$captureStackDepth must be in 0..10.",
            )
        }
        val params = buildJsonObject {
            put("class_signatures", buildJsonArray { classSignatures.forEach { add(it) } })
            put("capture_stack_depth", captureStackDepth)
            put("max_events_per_sec", maxEventsPerSec)
            put("buffer_size", bufferSize)
        }
        val result = try {
            client.request("agent.alloc_trace_start", params, timeoutMs = 10_000L)
        } catch (e: AgentRpcError) {
            mapAllocError(e, "agent.alloc_trace_start")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.alloc_trace_start returned non-object")
        val unresolvedArr = obj["unresolved_classes"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "alloc_trace_start missing `unresolved_classes`")
        return StartResult(
            bufferId = obj["buffer_id"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_start missing `buffer_id`"),
            startedAtMs = obj["started_at_ms"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_start missing `started_at_ms`"),
            resolvedClasses = obj["resolved_classes"]?.jsonPrimitive?.intOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_start missing `resolved_classes`"),
            unresolvedClasses = unresolvedArr.map {
                it.jsonPrimitive.contentOrNull
                    ?: throw ToolError(ErrorCode.Internal, "alloc_trace_start: non-string in `unresolved_classes`")
            },
        )
    }

    /** Send `agent.alloc_trace_read`. [max] is capped to 5000 by the agent. */
    suspend fun read(client: AgentClient, bufferId: String, max: Int = 500): ReadResult {
        val params = buildJsonObject {
            put("buffer_id", bufferId)
            put("max", max)
        }
        val result = try {
            client.request("agent.alloc_trace_read", params, timeoutMs = 5_000L)
        } catch (e: AgentRpcError) {
            mapAllocError(e, "agent.alloc_trace_read")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.alloc_trace_read returned non-object")
        val arr = obj["events"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "alloc_trace_read missing `events`")
        return ReadResult(
            bufferId = obj["buffer_id"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_read missing `buffer_id`"),
            events = arr.map { parseAllocEvent(it) },
            buffered = obj["buffered"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_read missing `buffered`"),
            droppedSinceLastRead = obj["dropped_since_last_read"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_read missing `dropped_since_last_read`"),
            droppedTotal = obj["dropped_total"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_read missing `dropped_total`"),
        )
    }

    /** Send `agent.alloc_trace_stop`. Returns tail events drained on close. */
    suspend fun stop(client: AgentClient, bufferId: String): StopResult {
        val params = buildJsonObject { put("buffer_id", bufferId) }
        val result = try {
            client.request("agent.alloc_trace_stop", params, timeoutMs = 10_000L)
        } catch (e: AgentRpcError) {
            mapAllocError(e, "agent.alloc_trace_stop")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.alloc_trace_stop returned non-object")
        val arr = obj["tail_events"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "alloc_trace_stop missing `tail_events`")
        return StopResult(
            bufferId = obj["buffer_id"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_stop missing `buffer_id`"),
            stoppedAtMs = obj["stopped_at_ms"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_stop missing `stopped_at_ms`"),
            totalEvents = obj["total_events"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_stop missing `total_events`"),
            droppedTotal = obj["dropped_total"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_stop missing `dropped_total`"),
            tailEvents = arr.map { parseAllocEvent(it) },
        )
    }

    /** Send `agent.alloc_trace_list`. */
    suspend fun list(client: AgentClient): List<ListEntry> {
        val result = try {
            client.request("agent.alloc_trace_list", buildJsonObject {}, timeoutMs = 5_000L)
        } catch (e: AgentRpcError) {
            mapAllocError(e, "agent.alloc_trace_list")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.alloc_trace_list returned non-object")
        val arr = obj["traces"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "alloc_trace_list missing `traces`")
        return arr.map { el ->
            val o = el as? JsonObject
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace_list: non-object in `traces`")
            ListEntry(
                bufferId = o["buffer_id"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolError(ErrorCode.Internal, "alloc_trace_list entry missing `buffer_id`"),
                startedAtMs = o["started_at_ms"]?.jsonPrimitive?.longOrNull
                    ?: throw ToolError(ErrorCode.Internal, "alloc_trace_list entry missing `started_at_ms`"),
                buffered = o["buffered"]?.jsonPrimitive?.longOrNull
                    ?: throw ToolError(ErrorCode.Internal, "alloc_trace_list entry missing `buffered`"),
                droppedTotal = o["dropped_total"]?.jsonPrimitive?.longOrNull
                    ?: throw ToolError(ErrorCode.Internal, "alloc_trace_list entry missing `dropped_total`"),
                classCount = o["class_count"]?.jsonPrimitive?.intOrNull
                    ?: throw ToolError(ErrorCode.Internal, "alloc_trace_list entry missing `class_count`"),
            )
        }
    }

    private fun parseAllocEvent(el: JsonElement): AllocEvent {
        val o = el as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "alloc_trace: non-object event")
        val stackArr = o["stack"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "alloc_trace event missing `stack`")
        val stack = stackArr.map { s ->
            val sObj = s as? JsonObject
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace: non-object in `stack`")
            StackFrame(
                classSig = sObj["class"]?.jsonPrimitive?.contentOrNull
                    ?: sObj["class_sig"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolError(ErrorCode.Internal, "alloc_trace stack frame missing `class`"),
                method = sObj["method"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolError(ErrorCode.Internal, "alloc_trace stack frame missing `method`"),
                line = sObj["line"]?.jsonPrimitive?.intOrNull
                    ?: throw ToolError(ErrorCode.Internal, "alloc_trace stack frame missing `line`"),
            )
        }
        return AllocEvent(
            classSig = o["class"]?.jsonPrimitive?.contentOrNull
                ?: o["class_sig"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace event missing `class`"),
            thread = o["thread"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace event missing `thread`"),
            nanoTime = o["nano_time"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace event missing `nano_time`"),
            sizeBytes = o["size_bytes"]?.jsonPrimitive?.longOrNull
                ?: throw ToolError(ErrorCode.Internal, "alloc_trace event missing `size_bytes`"),
            stack = stack,
        )
    }

    private fun mapAllocError(e: AgentRpcError, methodName: String): Nothing {
        when (e.rpcMessage) {
            "no_classes_loaded" -> throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "None of the requested classes are loaded in the VM.",
                hint = "Trigger the code path that loads them first, then retry.",
            )
            "unknown_buffer_id" -> {
                val badId = e.data?.get("buffer_id")?.jsonPrimitive?.contentOrNull
                throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "Unknown alloc-trace buffer: ${badId ?: "unknown"}",
                )
            }
            else -> throw ToolError(
                errorCode = ErrorCode.Internal,
                message = "$methodName failed: ${e.rpcMessage}",
            )
        }
    }
}
