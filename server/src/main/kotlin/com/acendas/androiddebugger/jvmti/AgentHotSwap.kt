package com.acendas.androiddebugger.jvmti

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * v1.5 HotSwap-shaped wrapper over [AgentClient]. Encodes/decodes the
 * `agent.redefine_classes`, `agent.pop_frame`, and `agent.get_original_class_bytes`
 * JSON-RPC methods so the [com.acendas.androiddebugger.tools.HotSwapTools] tool
 * bodies don't have to build JSON objects by hand.
 *
 * Per v1.5 spec §4 — wire protocol additions.
 *
 * All methods translate [AgentRpcError]s into [ToolError]s with structured
 * codes the MCP boundary surfaces verbatim.
 */
object AgentHotSwap {

    /** One entry for the batch redefine RPC. */
    data class RedefineEntry(val classSignature: String, val dexBytes: ByteArray)

    /**
     * Send `agent.redefine_classes` with [entries]. ART processes the batch
     * atomically: either every class redefines or none.
     *
     * @return list of `class_signature` strings the agent acknowledged redefining.
     * @throws ToolError on JVMTI failure or class-not-loaded.
     */
    suspend fun redefineClasses(client: AgentClient, entries: List<RedefineEntry>): List<String> {
        require(entries.isNotEmpty()) { "redefineClasses called with empty entries" }
        val params = buildJsonObject {
            put("entries", buildJsonArray {
                for (e in entries) {
                    addJsonObject {
                        put("class_signature", e.classSignature)
                        put("dex_bytes_b64", Base64.getEncoder().encodeToString(e.dexBytes))
                    }
                }
            })
        }
        val result = try {
            client.request("agent.redefine_classes", params, timeoutMs = 15_000L)
        } catch (e: AgentRpcError) {
            mapRedefineError(e)
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.redefine_classes returned non-object")
        val arr = obj["redefined"] as? JsonArray
            ?: throw ToolError(ErrorCode.Internal, "agent.redefine_classes response missing `redefined`")
        return arr.map {
            it.jsonPrimitive.contentOrNull
                ?: throw ToolError(ErrorCode.Internal, "agent.redefine_classes returned non-string in `redefined`")
        }
    }

    /**
     * Send `agent.pop_frame` to pop [framesToPop] from the thread named
     * [threadName]. The thread must be suspended (JDI-side suspend is visible
     * to JVMTI's PopFrame on ART per the v1.5 spec §9).
     *
     * @return number of frames actually popped (always equals [framesToPop] on success).
     */
    suspend fun popFrame(
        client: AgentClient,
        threadName: String,
        framesToPop: Int = 1,
    ): Int {
        val params = buildJsonObject {
            put("thread_name", threadName)
            put("frames_to_pop", framesToPop)
        }
        val result = try {
            client.request("agent.pop_frame", params, timeoutMs = 5_000L)
        } catch (e: AgentRpcError) {
            mapPopFrameError(e)
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.pop_frame returned non-object")
        return (obj["popped"]?.jsonPrimitive?.intOrNull)
            ?: throw ToolError(ErrorCode.Internal, "agent.pop_frame missing `popped`")
    }

    /**
     * Fetch the cached pre-attach class bytes for [classSignature] (JVM internal
     * form, e.g., `Lcom/example/Foo;`). Returns null when the agent has no cached
     * bytes (class was loaded before the agent attached, or evicted from cache).
     */
    suspend fun getOriginalClassBytes(client: AgentClient, classSignature: String): ByteArray? {
        val params = buildJsonObject { put("class_signature", classSignature) }
        val result = try {
            client.request("agent.get_original_class_bytes", params, timeoutMs = 5_000L)
        } catch (e: AgentRpcError) {
            if (e.rpcMessage == "class_bytes_not_cached") return null
            throw ToolError(
                errorCode = ErrorCode.Internal,
                message = "agent.get_original_class_bytes failed: ${e.rpcMessage}",
            )
        }
        val obj = result as? JsonObject
            ?: throw ToolError(ErrorCode.Internal, "agent.get_original_class_bytes returned non-object")
        val b64 = obj["class_bytes_b64"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolError(ErrorCode.Internal, "response missing class_bytes_b64")
        return Base64.getDecoder().decode(b64)
    }

    private fun mapRedefineError(e: AgentRpcError): Nothing {
        when (e.rpcMessage) {
            "class_not_loaded" -> {
                val failing = e.data?.get("failing_class")?.jsonPrimitive?.contentOrNull
                throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "Class not loaded in the target VM: ${failing ?: "unknown"}",
                    hint = "Trigger the code path that loads this class first, then retry. " +
                        "Classes are loaded lazily on first reference.",
                )
            }
            "redefine_failed_jvmti" -> {
                val jvmtiErr = e.data?.get("jvmti_error")?.jsonPrimitive?.intOrNull
                val failing = e.data?.get("failing_class")?.jsonPrimitive?.contentOrNull
                throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "redefine_failed_jvmti: ART rejected the redefine (jvmti_error=$jvmtiErr)" +
                        (failing?.let { " on $it" } ?: ""),
                    hint = describeRedefineJvmtiError(jvmtiErr),
                )
            }
            else -> throw ToolError(
                errorCode = ErrorCode.Internal,
                message = "agent.redefine_classes failed: ${e.rpcMessage}",
            )
        }
    }

    private fun mapPopFrameError(e: AgentRpcError): Nothing {
        val jvmtiErr = e.data?.get("jvmti_error")?.jsonPrimitive?.intOrNull
        when (e.rpcMessage) {
            "thread_not_found" -> throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "pop_frame: no thread named '${e.data?.get("thread_name")?.jsonPrimitive?.contentOrNull}'.",
            )
            "thread_not_suspended" -> throw ToolError(
                errorCode = ErrorCode.VmRunning,
                message = "pop_frame: target thread is not suspended.",
                hint = "Pause the VM (hit a breakpoint or call /android-debugger:pause) before popping.",
            )
            "opaque_frame" -> throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "pop_frame: top frame is opaque (native frame, can't pop).",
                hint = "PopFrame can't unwind native code. Step out to a Java/Kotlin frame, then retry.",
            )
            "no_more_frames" -> throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "pop_frame: thread has no more frames to pop.",
            )
            "capability_unavailable" -> throw ToolError(
                errorCode = ErrorCode.CapabilityUnavailable,
                message = "pop_frame: ART on this device reports `can_pop_frame=false`.",
                hint = "force_re_enter is unavailable on this ART version; the swap installed but " +
                    "active frames will continue with old code until they exit and re-enter naturally.",
            )
            else -> throw ToolError(
                errorCode = ErrorCode.Internal,
                message = "pop_frame failed: ${e.rpcMessage} (jvmti_error=$jvmtiErr)",
            )
        }
    }

    /** Human hint for the most common ART RedefineClasses JVMTI errors. */
    private fun describeRedefineJvmtiError(jvmtiErr: Int?): String = when (jvmtiErr) {
        // Values per OpenJDK jvmti.h (consistent with ART's table).
        62 -> "JVMTI_ERROR_UNSUPPORTED_VERSION: dex format mismatch. " +
            "The dex bytes may have been built for a different API level than the device. " +
            "Verify the dexer used the device's API level."
        63 -> "JVMTI_ERROR_INVALID_CLASS_FORMAT: malformed dex bytes."
        64 -> "JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION: superclass/interface chain forms a cycle."
        66 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED: added a method — JVMTI on ART doesn't support this."
        67 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED: field/method shape changed in a way ART rejects."
        69 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED: class access modifiers changed."
        70 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED: method access modifiers changed."
        71 -> "JVMTI_ERROR_NAMES_DONT_MATCH: dex class name doesn't match the loaded class name."
        72 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED: superclass or interface set changed."
        73 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED: removed a method — JVMTI on ART doesn't support this."
        21 -> "JVMTI_ERROR_INVALID_CLASS: the jclass we passed is no longer valid (class unloaded?)."
        20 -> "JVMTI_ERROR_INVALID_THREAD: thread reference invalid."
        else -> "Unknown JVMTI error. Check logcat for `amdb_agent` lines for context."
    }
}
