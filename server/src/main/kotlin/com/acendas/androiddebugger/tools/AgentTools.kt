package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.jvmti.AgentCrashRecord
import com.acendas.androiddebugger.jvmti.AgentState
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tools that surface the v1.4 JVMTI agent's state to the caller. v1.4 ships
 * one tool: `agent_info`. v1.5 will add `hot_swap_class`, `find_referrers` (via
 * agent), etc., each calling [Session.agentClient].
 *
 * Per Phase E of the v1.4 plan.
 */
object AgentTools {

    fun register(server: Server) {
        registerAgentInfo(server)
    }

    private fun registerAgentInfo(server: Server) {
        server.addTool(
            name = "agent_info",
            description = "v1.4 — return the JVMTI agent's state: whether the agent is loaded into the " +
                "attached app, its version, the ART JVMTI capability probe map (`can_redefine_classes`, " +
                "`can_tag_objects`, `can_pop_frame`, etc.), the device-side `.so` path, and the host-side " +
                "Unix socket. " +
                "If the agent isn't loaded, returns `loaded: false` with a reason — typically the user " +
                "passed `load_agent: false` to /android-debugger:attach, or the agent failed to load. " +
                "If a prior agent session crashed in the app (signal handler wrote a marker file), the " +
                "crash record surfaces as `crashed_last_session`. " +
                "Read this before invoking JVMTI-backed features (v1.5+ HotSwap, v1.6 faster heap walks) " +
                "to confirm the device's ART version supports them.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) {
            runTool {
                val state = Session.agentState
                toolOk {
                    if (state == null) {
                        put("loaded", false)
                        put("reason",
                            "agent not loaded — pass `load_agent: true` to /android-debugger:attach (the default)")
                    } else {
                        renderAgentState(state)
                    }
                }
            }
        }
    }

    /** Add the agent-state fields to a JSON object builder. Used by `agent_info` AND by `attach`'s
     *  response (Phase E.1.2). Shared so both surfaces emit the same shape. */
    fun JsonObjectBuilder.renderAgentState(state: AgentState) {
        put("loaded", true)
        put("version", state.agentVersion)
        put("protocol_version", state.protocolVersion)
        put("attach_pid", state.attachPid)
        put("attach_path", state.attachPath)
        put("attached_at", state.attachedAt.toString())
        put("transport", buildJsonObject {
            put("abstract_namespace", state.abstractNamespace)
            put("host_socket", state.hostSocketPath.toString())
        })
        put("capabilities", state.capabilities as JsonObject)
        state.crashedLastSession?.let { c ->
            put("crashed_last_session", renderCrashRecord(c))
        }
    }

    private fun renderCrashRecord(c: AgentCrashRecord): JsonObject = buildJsonObject {
        c.signal?.let { put("signal", it) }
        c.pc?.let { put("pc", it) }
        c.siAddr?.let { put("si_addr", it) }
        c.pid?.let { put("pid", it) }
        c.tid?.let { put("tid", it) }
        c.lastRpcMethod?.let { put("last_rpc_method", it) }
        c.agentVersion?.let { put("agent_version", it) }
        c.whenUnix?.let { put("when_unix", it) }
        put("hint",
            "The agent crashed during a prior session. The signal-handler wrote " +
                "/data/data/<package>/cache/amdb_agent_crash.txt with the registers shown. " +
                "Run `addr2line` against the unstripped build artifact to resolve the PC.")
    }
}
