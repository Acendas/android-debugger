package com.acendas.androiddebugger

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tool registry.
 *
 * v0.1.0 ships only `server_info` so end-to-end MCP wiring can be verified before the
 * JDI integration lands. Real debugger tools (attach, set_line_breakpoint, snapshot,
 * step_over, ...) come in the next iteration — see docs/android-debugger-dev.md for
 * the full target surface.
 */
object Tools {

    fun register(server: Server) {
        registerServerInfo(server)
        com.acendas.androiddebugger.tools.LifecycleTools.register(server)
        com.acendas.androiddebugger.tools.InspectionTools.register(server)
        com.acendas.androiddebugger.tools.ExecutionTools.register(server)
        com.acendas.androiddebugger.tools.BreakpointTools.register(server)
        com.acendas.androiddebugger.tools.WatchTools.register(server)
        com.acendas.androiddebugger.tools.AndroidTools.register(server)
        // v1.4 — JVMTI agent tools (agent_info, future v1.5+ hot_swap_class, etc.).
        com.acendas.androiddebugger.tools.AgentTools.register(server)
        // v1.5 — HotSwap tools (hot_swap_class, hot_swap_classes, hot_swap_revert).
        com.acendas.androiddebugger.tools.HotSwapTools.register(server)
        // v1.6 — JVMTI-backed heap walks (iterate_heap_by_class, find_referrer_chain)
        // and method-/alloc-trace surfaces. count_instances + find_referrers already
        // auto-route in WatchTools; add_method_breakpoint auto-routes in BreakpointTools.
        com.acendas.androiddebugger.tools.HeapTools.register(server)
        com.acendas.androiddebugger.tools.MethodTraceTools.register(server)
        com.acendas.androiddebugger.tools.AllocTraceTools.register(server)
        // v1.7 — Debug Plan tools.
        com.acendas.androiddebugger.tools.PlanTools.register(server)
    }

    private fun registerServerInfo(server: Server) {
        server.addTool(
            name = "server_info",
            description = "Returns metadata about the android-debugger MCP server: " +
                "version, JDK, OS, and detected adb path. Use this to verify the server is " +
                "reachable and to surface a JDK/adb problem before attempting to attach.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { _ ->
            val info = buildJsonObject {
                put("name", "android-debugger")
                put("version", BuildInfo.VERSION)
                put("jdk_version", System.getProperty("java.version") ?: "unknown")
                put("jdk_vendor", System.getProperty("java.vendor") ?: "unknown")
                put("os_name", System.getProperty("os.name") ?: "unknown")
                put("os_arch", System.getProperty("os.arch") ?: "unknown")
                val adb = AdbLocator.find()
                if (adb != null) {
                    put("adb_path", adb)
                    put("adb_status", "found")
                } else {
                    put("adb_status", "not_found")
                    put(
                        "adb_hint",
                        "Set ADB_PATH or ANDROID_HOME/ANDROID_SDK_ROOT, or add adb (adb.exe on Windows) to PATH.",
                    )
                }
            }
            CallToolResult(content = listOf(TextContent(text = info.toString())))
        }
    }
}
