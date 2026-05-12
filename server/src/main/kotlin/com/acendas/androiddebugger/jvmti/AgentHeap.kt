package com.acendas.androiddebugger.jvmti

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * v1.6 JVMTI-backed heap wrapper over [AgentClient]. Encodes/decodes the four
 * `agent.heap_*` JSON-RPC methods so [com.acendas.androiddebugger.tools] bodies
 * don't have to build JSON objects by hand.
 *
 * Per v1.6 spec — heap RPCs.
 *
 * All methods translate [AgentRpcError]s into [ToolError]s with structured
 * codes the MCP boundary surfaces verbatim.
 */
object AgentHeap {

    data class HeapCount(
        val classSignature: String,
        val count: Long,
        val sampleSizeBytes: Long,
    )

    data class HeapInstance(val ref: String, val sizeBytes: Long)

    data class HeapInstancesResult(
        val classSignature: String,
        val instances: List<HeapInstance>,
        val total: Long,
        val truncated: Boolean,
    )

    data class Referrer(
        val ref: String,
        val type: String,
        val edge: String?,
        val edgeDetail: String?,
    )

    data class ReferrersResult(
        val ref: String,
        val referrers: List<Referrer>,
        val total: Long,
        val truncated: Boolean,
    )

    data class ChainStep(
        val ref: String,
        val type: String,
        val edge: String?,
        val edgeDetail: String?,
    )

    data class Chain(
        val depth: Int,
        val rootKind: String,
        val path: List<ChainStep>,
    )

    data class ReferrerChainResult(
        val ref: String,
        val chains: List<Chain>,
        val maxDepthReached: Boolean,
    )

    /**
     * Send `agent.heap_count_instances`. JVMTI walks loaded instances of
     * [classSignature] and returns total + sampled size estimate.
     *
     * [consistency] is forwarded verbatim: "strong" pauses the world via JVMTI;
     * "weak" walks live without a stop-the-world. Default "strong".
     */
    suspend fun countInstances(
        client: AgentClient,
        classSignature: String,
        consistency: String = "strong",
    ): HeapCount {
        val params = buildJsonObject {
            put("class_signature", classSignature)
            put("consistency", consistency)
        }
        val result = try {
            client.request("agent.heap_count_instances", params, timeoutMs = 30_000L)
        } catch (e: AgentRpcError) {
            mapHeapError(e, "agent.heap_count_instances")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_count_instances returned non-object")
        val sig = obj["class_signature"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_count_instances missing `class_signature`")
        val count = obj["count"]?.jsonPrimitive?.longOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_count_instances missing `count`")
        val sampleSize = obj["sample_size_bytes"]?.jsonPrimitive?.longOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_count_instances missing `sample_size_bytes`")
        return HeapCount(sig, count, sampleSize)
    }

    /**
     * Send `agent.heap_iterate_by_class`. Returns up to [max] live instances
     * (agent caps at 10_000).
     */
    suspend fun iterateByClass(
        client: AgentClient,
        classSignature: String,
        max: Int,
    ): HeapInstancesResult {
        val params = buildJsonObject {
            put("class_signature", classSignature)
            put("max", max)
        }
        val result = try {
            client.request("agent.heap_iterate_by_class", params, timeoutMs = 30_000L)
        } catch (e: AgentRpcError) {
            mapHeapError(e, "agent.heap_iterate_by_class")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_iterate_by_class returned non-object")
        val sig = obj["class_signature"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_iterate_by_class missing `class_signature`")
        val arr = obj["instances"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_iterate_by_class missing `instances`")
        val instances = arr.map { el ->
            val o = el as? JsonObject
                ?: throw ToolError(ErrorCode.Internal, "heap_iterate_by_class: non-object in `instances`")
            HeapInstance(
                ref = o["ref"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolError(ErrorCode.Internal, "heap_iterate_by_class: instance missing `ref`"),
                sizeBytes = o["size_bytes"]?.jsonPrimitive?.longOrNull
                    ?: throw ToolError(ErrorCode.Internal, "heap_iterate_by_class: instance missing `size_bytes`"),
            )
        }
        val total = obj["total"]?.jsonPrimitive?.longOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_iterate_by_class missing `total`")
        val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_iterate_by_class missing `truncated`")
        return HeapInstancesResult(sig, instances, total, truncated)
    }

    /**
     * Send `agent.heap_find_referrers`. Returns up to [max] referrers of [ref]
     * (agent caps at 10_000).
     */
    suspend fun findReferrers(
        client: AgentClient,
        ref: String,
        max: Int,
    ): ReferrersResult {
        val params = buildJsonObject {
            put("ref", ref)
            put("max", max)
        }
        val result = try {
            client.request("agent.heap_find_referrers", params, timeoutMs = 30_000L)
        } catch (e: AgentRpcError) {
            mapHeapError(e, "agent.heap_find_referrers")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_find_referrers returned non-object")
        val resolvedRef = obj["ref"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_find_referrers missing `ref`")
        val arr = obj["referrers"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_find_referrers missing `referrers`")
        val referrers = arr.map { el ->
            val o = el as? JsonObject
                ?: throw ToolError(ErrorCode.Internal, "heap_find_referrers: non-object in `referrers`")
            Referrer(
                ref = o["ref"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolError(ErrorCode.Internal, "heap_find_referrers: referrer missing `ref`"),
                type = o["type"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolError(ErrorCode.Internal, "heap_find_referrers: referrer missing `type`"),
                edge = o["edge"]?.jsonPrimitive?.contentOrNull,
                edgeDetail = o["edge_detail"]?.jsonPrimitive?.contentOrNull,
            )
        }
        val total = obj["total"]?.jsonPrimitive?.longOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_find_referrers missing `total`")
        val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_find_referrers missing `truncated`")
        return ReferrersResult(resolvedRef, referrers, total, truncated)
    }

    /**
     * Send `agent.heap_find_referrer_chain`. Builds up to [maxChains] paths
     * from [ref] back toward a GC root, each at most [maxDepth] hops.
     */
    suspend fun findReferrerChain(
        client: AgentClient,
        ref: String,
        maxDepth: Int,
        maxChains: Int = 3,
    ): ReferrerChainResult {
        val params = buildJsonObject {
            put("ref", ref)
            put("max_depth", maxDepth)
            put("max_chains", maxChains)
        }
        val result = try {
            client.request("agent.heap_find_referrer_chain", params, timeoutMs = 60_000L)
        } catch (e: AgentRpcError) {
            mapHeapError(e, "agent.heap_find_referrer_chain")
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_find_referrer_chain returned non-object")
        val resolvedRef = obj["ref"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_find_referrer_chain missing `ref`")
        val arr = obj["chains"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_find_referrer_chain missing `chains`")
        val chains = arr.map { el ->
            val o = el as? JsonObject
                ?: throw ToolError(ErrorCode.Internal, "heap_find_referrer_chain: non-object in `chains`")
            val depth = o["depth"]?.jsonPrimitive?.intOrNull
                ?: throw ToolError(ErrorCode.Internal, "heap_find_referrer_chain: chain missing `depth`")
            val rootKind = o["root_kind"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "heap_find_referrer_chain: chain missing `root_kind`")
            val pathArr = o["path"] as? JsonArray
                ?: throw ToolError(ErrorCode.Internal, "heap_find_referrer_chain: chain missing `path`")
            val path = pathArr.map { stepEl ->
                val s = stepEl as? JsonObject
                    ?: throw ToolError(ErrorCode.Internal, "heap_find_referrer_chain: non-object in `path`")
                ChainStep(
                    ref = s["ref"]?.jsonPrimitive?.contentOrNull
                        ?: throw ToolError(ErrorCode.Internal, "heap_find_referrer_chain: step missing `ref`"),
                    type = s["type"]?.jsonPrimitive?.contentOrNull
                        ?: throw ToolError(ErrorCode.Internal, "heap_find_referrer_chain: step missing `type`"),
                    edge = s["edge"]?.jsonPrimitive?.contentOrNull,
                    edgeDetail = s["edge_detail"]?.jsonPrimitive?.contentOrNull,
                )
            }
            Chain(depth, rootKind, path)
        }
        val maxDepthReached = obj["max_depth_reached"]?.jsonPrimitive?.booleanOrNull
            ?: throw ToolError(ErrorCode.Internal, "agent.heap_find_referrer_chain missing `max_depth_reached`")
        return ReferrerChainResult(resolvedRef, chains, maxDepthReached)
    }

    /**
     * Translate every heap RPC error into a structured [ToolError]. Common shapes:
     * - `class_not_loaded`: ART hasn't loaded the requested class yet.
     * - `unknown_ref`: a `vobj#N` ref the agent doesn't recognize (probably stale
     *   across detach; ObjectId mints are session-scoped).
     * - `jvmti_error` (with `data.jvmti_error`): native heap walk hit a JVMTI error.
     */
    private fun mapHeapError(e: AgentRpcError, methodName: String): Nothing {
        when (e.rpcMessage) {
            "class_not_loaded" -> {
                val sig = e.data?.get("class_signature")?.jsonPrimitive?.contentOrNull
                    ?: e.data?.get("failing_class")?.jsonPrimitive?.contentOrNull
                throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "Class not loaded: ${sig ?: "unknown"}",
                    hint = "Trigger the code path that loads the class first.",
                )
            }
            "unknown_ref" -> {
                val badRef = e.data?.get("ref")?.jsonPrimitive?.contentOrNull
                throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "Unknown ref: ${badRef ?: "unknown"}",
                    hint = "vobj# refs come from iterate_heap_by_class / find_referrers / find_referrer_chain. " +
                        "They don't survive detach.",
                )
            }
            "jvmti_error" -> {
                val jvmtiErr = e.data?.get("jvmti_error")?.jsonPrimitive?.intOrNull
                throw ToolError(
                    errorCode = ErrorCode.Internal,
                    message = "JVMTI heap walk failed (jvmti_error=$jvmtiErr)",
                )
            }
            else -> throw ToolError(
                errorCode = ErrorCode.Internal,
                message = "$methodName failed: ${e.rpcMessage}",
            )
        }
    }
}
