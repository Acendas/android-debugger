package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.inspection.VmCoordinator
import com.acendas.androiddebugger.jvmti.AgentHeap
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * v1.6 — JVMTI-backed heap inspection tools. Two tools that route through the
 * agent's `agent.heap_*` RPCs:
 *
 *  - `iterate_heap_by_class` — enumerate live instances of a class on the heap.
 *  - `find_referrer_chain` — walk back from an object toward a GC root.
 *
 * Both refuse with `agent_not_loaded` when the JVMTI agent isn't loaded into the
 * target app. Both serialize through [VmCoordinator] under the `heap_walk` label
 * so a heap walk can't race against an in-flight `eval_method` or `hot_swap`.
 */
object HeapTools {

    /** Default + hard cap echo agent-side limits (10_000 hard cap on the wire). */
    private const val ITERATE_DEFAULT: Int = 100
    private const val ITERATE_HARD_CAP: Int = 10_000

    private const val CHAIN_DEFAULT_DEPTH: Int = 5
    private const val CHAIN_HARD_DEPTH: Int = 32
    private const val CHAIN_DEFAULT_PATHS: Int = 3
    private const val CHAIN_HARD_PATHS: Int = 10

    fun register(server: Server) {
        registerIterateHeapByClass(server)
        registerFindReferrerChain(server)
    }

    // ---------------- iterate_heap_by_class ----------------

    private fun registerIterateHeapByClass(server: Server) {
        server.addTool(
            name = "iterate_heap_by_class",
            description = "v1.6 — enumerate live instances of a class on the heap via the JVMTI agent. " +
                "Returns up to `max` opaque `vobj#` refs (agent caps at 10,000) plus per-instance size. " +
                "Agent-only: refuses with `agent_not_loaded` when the JVMTI agent isn't loaded into the " +
                "target app. Routes through VmCoordinator so heap walks can't race an in-flight eval/swap. " +
                "Pass `class_signature` in JVM internal form (e.g., `Lcom/example/Foo;`). " +
                "The returned `vobj#` refs are session-scoped and feed `find_referrer_chain`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class_signature") {
                        put("type", "string")
                        put("description", "JVM internal signature, e.g. `Lcom/example/Foo;`.")
                    }
                    putJsonObject("max") {
                        put("type", "integer")
                        put("description", "Max instances to return. Default $ITERATE_DEFAULT; hard cap $ITERATE_HARD_CAP.")
                    }
                },
                required = listOf("class_signature"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "iterate_heap_by_class") {
                Session.requireAttached()
                val client = Session.agentClient
                    ?: throw ToolError(
                        errorCode = ErrorCode.CapabilityUnavailable,
                        message = "agent_not_loaded: JVMTI agent not loaded into target app.",
                        hint = "Re-run /android-debugger:attach without `load_agent: false`.",
                    )
                val sig = (request.arguments?.get("class_signature") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `class_signature`.")
                if (!sig.startsWith("L") || !sig.endsWith(";")) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "class_signature `$sig` must be JVM internal form (e.g., `Lcom/example/Foo;`).",
                    )
                }
                val rawMax = (request.arguments?.get("max") as? JsonPrimitive)?.intOrNull
                val max = when {
                    rawMax == null -> ITERATE_DEFAULT
                    rawMax <= 0 -> ITERATE_DEFAULT
                    else -> rawMax.coerceAtMost(ITERATE_HARD_CAP)
                }

                val result = VmCoordinator.withExclusiveAccess(
                    operation = "heap_walk",
                    timeoutMs = 30_000L,
                ) {
                    AgentHeap.iterateByClass(client, sig, max)
                }

                toolOk {
                    put("class_signature", result.classSignature)
                    put("instances", buildJsonArray {
                        for (i in result.instances) addJsonObject {
                            put("ref", i.ref)
                            put("size_bytes", i.sizeBytes)
                        }
                    })
                    put("total", result.total)
                    put("truncated", result.truncated)
                    put("backend", "jvmti")
                }
            }
        }
    }

    // ---------------- find_referrer_chain ----------------

    private fun registerFindReferrerChain(server: Server) {
        server.addTool(
            name = "find_referrer_chain",
            description = "v1.6 — walk backward from an object toward GC roots via the JVMTI agent. " +
                "Returns up to `max_chains` paths from the supplied `ref` to a root, each at most " +
                "`max_depth` hops. Useful for leak hunting: shows the chain of references keeping an " +
                "object alive. Each step carries `edge` (field / static_field / array_element / " +
                "jni_global / ...) and `edge_detail` (field name, array index, ...). " +
                "Agent-only: refuses with `agent_not_loaded` when the JVMTI agent isn't loaded. " +
                "Routes through VmCoordinator. Pass a `vobj#N` ref from `iterate_heap_by_class` or " +
                "`find_referrers` (agent path).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("ref") {
                        put("type", "string")
                        put("description", "Opaque heap-object ref (e.g., `vobj#42`) from a prior agent-backed call.")
                    }
                    putJsonObject("max_depth") {
                        put("type", "integer")
                        put("description", "Max hops per chain. Default $CHAIN_DEFAULT_DEPTH; hard cap $CHAIN_HARD_DEPTH.")
                    }
                    putJsonObject("max_chains") {
                        put("type", "integer")
                        put("description", "Max distinct chains to report. Default $CHAIN_DEFAULT_PATHS; hard cap $CHAIN_HARD_PATHS.")
                    }
                },
                required = listOf("ref"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "find_referrer_chain") {
                Session.requireAttached()
                val client = Session.agentClient
                    ?: throw ToolError(
                        errorCode = ErrorCode.CapabilityUnavailable,
                        message = "agent_not_loaded: JVMTI agent not loaded into target app.",
                        hint = "Re-run /android-debugger:attach without `load_agent: false`.",
                    )
                val ref = (request.arguments?.get("ref") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `ref`.")
                val rawDepth = (request.arguments?.get("max_depth") as? JsonPrimitive)?.intOrNull
                val maxDepth = when {
                    rawDepth == null -> CHAIN_DEFAULT_DEPTH
                    rawDepth <= 0 -> CHAIN_DEFAULT_DEPTH
                    else -> rawDepth.coerceAtMost(CHAIN_HARD_DEPTH)
                }
                val rawChains = (request.arguments?.get("max_chains") as? JsonPrimitive)?.intOrNull
                val maxChains = when {
                    rawChains == null -> CHAIN_DEFAULT_PATHS
                    rawChains <= 0 -> CHAIN_DEFAULT_PATHS
                    else -> rawChains.coerceAtMost(CHAIN_HARD_PATHS)
                }

                val result = VmCoordinator.withExclusiveAccess(
                    operation = "heap_walk",
                    timeoutMs = 30_000L,
                ) {
                    AgentHeap.findReferrerChain(client, ref, maxDepth, maxChains)
                }

                toolOk {
                    put("ref", result.ref)
                    put("chains", buildJsonArray {
                        for (chain in result.chains) addJsonObject {
                            put("depth", chain.depth)
                            put("root_kind", chain.rootKind)
                            put("path", buildJsonArray {
                                for (step in chain.path) addJsonObject {
                                    put("ref", step.ref)
                                    put("type", step.type)
                                    step.edge?.let { put("edge", it) }
                                    step.edgeDetail?.let { put("edge_detail", it) }
                                }
                            })
                        }
                    })
                    put("max_depth_reached", result.maxDepthReached)
                    put("backend", "jvmti")
                }
            }
        }
    }
}
