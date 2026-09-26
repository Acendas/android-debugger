package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.AdbLocator
import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.PluginRoot
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.toolErr
import com.acendas.androiddebugger.toolOk
import com.acendas.androiddebugger.ui.UiSession
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * UI driving: read the screen, tap/type/swipe/key, and wait for the screen to change.
 *
 * Backed by [UiSession] (one on-device daemon holding the device's single UiAutomation
 * slot). These tools deliberately do NOT use [com.acendas.androiddebugger.runTool]:
 * that takes [com.acendas.androiddebugger.Session.mutex], which `wait_for_event` holds
 * for up to a minute, and a tap must not return `vm_busy` while an orchestrator waits
 * for a breakpoint. They also run during Debug Plans — driving the UI while a plan's
 * breakpoints are armed is the point.
 */
object UiTools {

    private const val WAIT_MAX_MS = 60_000L

    fun register(server: Server) {
        registerStart(server)
        registerStop(server)
        registerStatus(server)
        registerLayout(server)
        registerTap(server, "ui_tap", longPress = false)
        registerTap(server, "ui_long_press", longPress = true)
        registerSwipe(server)
        registerKey(server)
        registerType(server)
        registerWait(server)
        registerScreenshot(server)
    }

    // ---- wrapper ---------------------------------------------------------------------------

    private suspend fun runUiTool(
        wallClockMs: Long = 30_000,
        block: suspend () -> CallToolResult,
    ): CallToolResult = try {
        withTimeoutOrNull(wallClockMs) { withContext(Dispatchers.IO) { block() } }
            ?: toolErr(ErrorCode.ToolTimeout, "UI tool exceeded ${wallClockMs}ms.")
    } catch (e: ToolError) {
        toolErr(e.errorCode, e.message ?: "error", e.hint, e.currentState)
    } catch (t: Throwable) {
        toolErr(ErrorCode.Internal, "${t::class.simpleName}: ${t.message}")
    }

    private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.int(k: String) = (this?.get(k) as? JsonPrimitive)?.intOrNull
    private fun JsonObject?.long(k: String) = (this?.get(k) as? JsonPrimitive)?.longOrNull
    private fun JsonObject?.bool(k: String) = (this?.get(k) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObjectBuilder.prop(name: String, type: String, description: String) =
        putJsonObject(name) { put("type", type); put("description", description) }

    private val allowWhilePausedProp: JsonObjectBuilder.() -> Unit = {
        prop("allow_while_paused", "boolean",
            "Skip the paused-app guard. Only when the target is a different app than the one paused in the debugger.")
    }

    // ---- lifecycle -------------------------------------------------------------------------

    private fun registerStart(server: Server) {
        server.addTool(
            name = "ui_start",
            description = "Start UI driving on a device: pushes and starts the on-device UI daemon, which " +
                "holds the device's single UiAutomation session (stock `uiautomator dump` and `android layout` " +
                "fail while it runs). Returns `event_log` and a ready `monitor_command` that streams one line per " +
                "screen change for Claude Code's Monitor. Jetpack Compose apps send no UI events until an " +
                "accessibility service is enabled: if the result warns `compose_events_suppressed`, ask the user " +
                "before restarting with `presence: true` (installs a no-op service and changes a secure setting; " +
                "ui_stop restores the exact prior values). Independent of `attach`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    prop("serial", "string", "Device serial. Defaults to the attached device, else the only device.")
                    prop("presence", "boolean",
                        "Install + enable the no-op presence accessibility service so Compose apps emit events. " +
                            "Opt-in; ask the user first.")
                    prop("forward_to_debug_events", "boolean",
                        "Also publish UI events into wait_for_event as type \"ui\". Default false (the debug " +
                            "event queue is small and must not lose breakpoint events).")
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { request ->
            runUiTool(wallClockMs = 90_000) {
                val args = request.arguments
                val r = UiSession.start(
                    serialArg = args.str("serial"),
                    presence = args.bool("presence") ?: false,
                    forwardToDebug = args.bool("forward_to_debug_events") ?: false,
                )
                toolOk {
                    put("serial", r.active.serial)
                    put("sdk", r.active.sdk)
                    put("daemon_version", UiSession.DAEMON_VERSION)
                    put("presence", r.active.presencePrior != null)
                    put("event_log", r.active.hub.logFile.toString())
                    put("monitor_command", monitorCommand(r.active.hub.logFile.toString()))
                    if (r.repairedStalePresence) put("repaired_stale_presence", true)
                    if (r.warnings.isNotEmpty()) put("warnings", buildJsonArray { r.warnings.forEach { add(it) } })
                    if ("compose_events_suppressed" in r.warnings) {
                        put("hint", "This is a Compose app and no accessibility service is enabled, so it sends " +
                            "no UI events: screen_changed will not fire. Reads and input still work. Ask the " +
                            "user, then ui_stop + ui_start(presence: true) to enable events.")
                    }
                }
            }
        }
    }

    private fun registerStop(server: Server) {
        server.addTool(
            name = "ui_stop",
            description = "Stop UI driving: quit the daemon (releasing the device's UiAutomation slot), remove the " +
                "adb forward, and restore the exact prior accessibility settings if presence was enabled. Call it " +
                "before running instrumented UI tests, Appium/Maestro, or `android layout`.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { _ ->
            runUiTool { toolOk { put("stopped", UiSession.stop()) } }
        }
    }

    private fun registerStatus(server: Server) {
        server.addTool(
            name = "ui_status",
            description = "UI session status: running, serial, presence, event_log, last_seq, plus the " +
                "monitor_command to (re)arm Monitor.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { _ ->
            runUiTool {
                val s = UiSession.statusJson()
                toolOk {
                    for ((k, v) in s) put(k, v)
                    UiSession.active?.let { put("monitor_command", monitorCommand(it.hub.logFile.toString())) }
                }
            }
        }
    }

    // ---- reading ---------------------------------------------------------------------------

    private fun registerLayout(server: Server) {
        server.addTool(
            name = "ui_layout",
            description = "Read the current screen as a flat list of visible elements: text, content-desc, " +
                "resource-id, class, bounds, center [x,y], interactions (clickable, editable, scrollable...), " +
                "state (focused, checked, disabled). Default filters to elements with text or an interaction; " +
                "`full: true` returns every visible node. No idle wait unless `idle_ms` is set. Reads the active " +
                "window (a dialog when one is open).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    prop("full", "boolean", "Return every visible node, not just text/interactive ones.")
                    prop("idle_ms", "integer", "Wait for this much UI idle first (0 = no wait, the default).")
                    prop("timeout_ms", "integer", "Read timeout. Default 3000.")
                    allowWhilePausedProp()
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
        ) { request ->
            runUiTool {
                val args = request.arguments
                UiSession.requireNotPaused(args.bool("allow_while_paused") ?: false)
                val timeout = (args.long("timeout_ms") ?: 3_000L).coerceIn(200, 20_000)
                val resp = UiSession.request(
                    buildJsonObject {
                        put("cmd", "layout")
                        put("full", args.bool("full") ?: false)
                        put("idle_ms", args.long("idle_ms") ?: 0L)
                        put("timeout_ms", timeout)
                    },
                    readTimeoutMs = (timeout + 3_000).toInt(),
                )
                toolOk {
                    resp["package"]?.let { put("package", it) }
                    resp["elapsed_ms"]?.let { put("elapsed_ms", it) }
                    put("nodes", resp["nodes"] ?: JsonArray(emptyList()))
                    put("seq", UiSession.require().hub.seq)
                }
            }
        }
    }

    // ---- input -----------------------------------------------------------------------------

    private val selectorProps: JsonObjectBuilder.() -> Unit = {
        prop("x", "integer", "Screen x. Use with y, or use a selector instead.")
        prop("y", "integer", "Screen y.")
        prop("text", "string", "Tap the element whose text equals this (see `contains`).")
        prop("content_desc", "string", "Tap the element whose content-desc equals this.")
        prop("resource_id", "string", "Tap the element with this resource-id (full or after the ':id/').")
        prop("contains", "boolean", "Match text/content_desc as a substring (case-insensitive).")
        prop("index", "integer", "Which match to use when several match (0-based). Default 0.")
    }

    private data class Target(val x: Int, val y: Int, val matched: JsonObject?)

    private fun resolveTarget(args: JsonObject?): Target {
        val x = args.int("x")
        val y = args.int("y")
        if (x != null && y != null) return Target(x, y, null)
        val text = args.str("text")
        val desc = args.str("content_desc")
        val rid = args.str("resource_id")
        if (text == null && desc == null && rid == null) {
            throw ToolError(ErrorCode.InvalidTarget, "Give x+y, or one of text / content_desc / resource_id.")
        }
        val contains = args.bool("contains") ?: false
        fun eq(actual: String?, want: String?): Boolean = when {
            want == null -> true
            actual == null -> false
            contains -> actual.contains(want, ignoreCase = true)
            else -> actual == want
        }
        val layout = UiSession.request(buildJsonObject { put("cmd", "layout"); put("timeout_ms", 3_000) })
        val nodes = (layout["nodes"] as? JsonArray).orEmpty().map { it.jsonObject }
        val matches = nodes.filter { n ->
            eq(n.str("text"), text) && eq(n.str("content-desc"), desc) &&
                (rid == null || n.str("resource-id").let { it == rid || it?.substringAfter(":id/") == rid })
        }
        val index = args.int("index") ?: 0
        val hit = matches.getOrNull(index) ?: throw ToolError(
            errorCode = ErrorCode.UiElementNotFound,
            message = "No element matches ${listOfNotNull(text?.let { "text=$it" }, desc?.let { "content_desc=$it" }, rid?.let { "resource_id=$it" }).joinToString()}" +
                if (matches.isNotEmpty()) " at index $index (${matches.size} matches)" else "",
            hint = "Call ui_layout to see what is on screen; try contains: true. Icon-only buttons without a " +
                "content-desc can only be tapped by x/y.",
        )
        val (cx, cy) = hit.str("center")!!.trim('[', ']').split(",").map { it.trim().toInt() }
        return Target(cx, cy, hit)
    }

    private fun registerTap(server: Server, name: String, longPress: Boolean) {
        server.addTool(
            name = name,
            description = (if (longPress) "Long-press" else "Tap") + " a point or an element found by text / " +
                "content_desc / resource_id (its center). Injected through the daemon's UiAutomation session. " +
                "Returns `seq`: pass it to ui_wait as after_seq (the default) to wait for the resulting screen change.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    selectorProps()
                    if (longPress) prop("duration_ms", "integer", "Hold time. Default 800.")
                    allowWhilePausedProp()
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { request ->
            runUiTool {
                val args = request.arguments
                UiSession.requireNotPaused(args.bool("allow_while_paused") ?: false)
                val t = resolveTarget(args)
                val label = t.matched?.let { it.str("text") ?: it.str("content-desc") ?: it.str("resource-id") }
                val seq = UiSession.recordInput(buildJsonObject {
                    put("action", if (longPress) "long_press" else "tap")
                    put("x", t.x); put("y", t.y)
                    label?.let { put("target", it) }
                })
                UiSession.request(buildJsonObject {
                    put("cmd", if (longPress) "long_press" else "tap")
                    put("x", t.x); put("y", t.y)
                    if (longPress) put("duration_ms", args.long("duration_ms") ?: 800L)
                })
                toolOk {
                    put("x", t.x); put("y", t.y)
                    label?.let { put("target", it) }
                    put("seq", seq)
                }
            }
        }
    }

    private fun registerSwipe(server: Server) {
        server.addTool(
            name = "ui_swipe",
            description = "Swipe from (x1,y1) to (x2,y2) over duration_ms (default 400). Scroll a list down by " +
                "swiping upward. Returns `seq` for ui_wait.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    prop("x1", "integer", "Start x."); prop("y1", "integer", "Start y.")
                    prop("x2", "integer", "End x."); prop("y2", "integer", "End y.")
                    prop("duration_ms", "integer", "Default 400. Slower scrolls are more reliable.")
                    allowWhilePausedProp()
                },
                required = listOf("x1", "y1", "x2", "y2"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { request ->
            runUiTool {
                val args = request.arguments
                UiSession.requireNotPaused(args.bool("allow_while_paused") ?: false)
                val c = listOf("x1", "y1", "x2", "y2").map {
                    args.int(it) ?: throw ToolError(ErrorCode.InvalidTarget, "$it is required")
                }
                val duration = (args.long("duration_ms") ?: 400L).coerceIn(50, 5_000)
                val seq = UiSession.recordInput(buildJsonObject {
                    put("action", "swipe"); put("from", "${c[0]},${c[1]}"); put("to", "${c[2]},${c[3]}")
                })
                UiSession.request(buildJsonObject {
                    put("cmd", "swipe")
                    put("x1", c[0]); put("y1", c[1]); put("x2", c[2]); put("y2", c[3])
                    put("duration_ms", duration)
                }, readTimeoutMs = (duration + 10_000).toInt())
                toolOk { put("seq", seq) }
            }
        }
    }

    private val KEYS = mapOf(
        "back" to 4, "home" to 3, "enter" to 66, "delete" to 67, "tab" to 61, "menu" to 82,
        "recents" to 187, "escape" to 111, "up" to 19, "down" to 20, "left" to 21, "right" to 22,
        "search" to 84, "space" to 62,
    )

    private fun registerKey(server: Server) {
        server.addTool(
            name = "ui_key",
            description = "Press a key: one of ${KEYS.keys.joinToString()} or a raw Android keycode. Returns `seq`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    prop("key", "string", "Key name (back, home, enter, ...).")
                    prop("code", "integer", "Raw KeyEvent keycode, instead of key.")
                    allowWhilePausedProp()
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { request ->
            runUiTool {
                val args = request.arguments
                UiSession.requireNotPaused(args.bool("allow_while_paused") ?: false)
                val name = args.str("key")?.lowercase()
                val code = args.int("code") ?: name?.let { KEYS[it] } ?: throw ToolError(
                    ErrorCode.InvalidTarget, "Unknown key '${name ?: ""}'.", hint = "Use one of ${KEYS.keys.joinToString()} or code.",
                )
                val seq = UiSession.recordInput(buildJsonObject { put("action", "key"); put("key", name ?: code.toString()) })
                UiSession.request(buildJsonObject { put("cmd", "key"); put("code", code) })
                toolOk { put("code", code); put("seq", seq) }
            }
        }
    }

    private fun registerType(server: Server) {
        server.addTool(
            name = "ui_type",
            description = "Set the text of the focused input field (replaces its content; spaces and symbols " +
                "need no escaping). Tap the field first, or pass a selector to tap it here. Returns `seq`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    prop("value", "string", "Text to put in the field.")
                    selectorProps()
                    allowWhilePausedProp()
                },
                required = listOf("value"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { request ->
            runUiTool {
                val args = request.arguments
                UiSession.requireNotPaused(args.bool("allow_while_paused") ?: false)
                val value = args.str("value") ?: throw ToolError(ErrorCode.InvalidTarget, "value is required")
                val hasSelector = listOf("x", "text", "content_desc", "resource_id").any { args?.containsKey(it) == true }
                if (hasSelector) {
                    val t = resolveTarget(args)
                    UiSession.request(buildJsonObject { put("cmd", "tap"); put("x", t.x); put("y", t.y) })
                    Thread.sleep(300)
                }
                val seq = UiSession.recordInput(buildJsonObject { put("action", "type"); put("chars", value.length) })
                UiSession.request(buildJsonObject { put("cmd", "set_text"); put("text", value) })
                toolOk { put("seq", seq) }
            }
        }
    }

    // ---- waiting ---------------------------------------------------------------------------

    private fun registerWait(server: Server) {
        server.addTool(
            name = "ui_wait",
            description = "Wait for the UI to change instead of sleeping. Returns the first matching event after " +
                "`after_seq` (default: the last ui_* action's seq, so a change that landed before this call is " +
                "not missed). Filters: `until_text` (a screen_changed that adds text containing this — also " +
                "returns immediately if it is already on screen), `until_gone` (removes it), `kinds` (screen_changed, " +
                "ui_window, screen_unreadable, ui_disconnected). With no filter, any screen_changed/ui_window. " +
                "timeout_ms default 10000, max 60000.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    prop("until_text", "string", "Wait until text containing this appears.")
                    prop("until_gone", "string", "Wait until text containing this disappears.")
                    prop("kinds", "array", "Event kinds to accept.")
                    prop("after_seq", "integer", "Only events after this seq.")
                    prop("timeout_ms", "integer", "Default 10000; max 60000.")
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runUiTool(wallClockMs = WAIT_MAX_MS + 10_000) {
                val args = request.arguments
                val a = UiSession.require()
                val untilText = args.str("until_text")
                val untilGone = args.str("until_gone")
                val kinds = (args?.get("kinds") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                    ?: if (untilText != null || untilGone != null) setOf("screen_changed") else setOf("screen_changed", "ui_window")
                val timeout = (args.long("timeout_ms") ?: 10_000L).coerceIn(1, WAIT_MAX_MS)
                val after = args.long("after_seq") ?: a.lastInputSeq

                if (untilText != null) {
                    val now = runCatching {
                        UiSession.visibleStrings(UiSession.request(buildJsonObject { put("cmd", "layout"); put("timeout_ms", 2_000) }))
                    }.getOrDefault(emptyList())
                    if (now.any { it.contains(untilText, ignoreCase = true) }) {
                        return@runUiTool toolOk { put("already_visible", true); put("seq", a.hub.seq) }
                    }
                }
                fun strings(e: JsonObject, key: String) =
                    (e[key] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                val event = a.hub.awaitEvent(after, timeout) { e ->
                    val kind = e.str("kind")
                    when {
                        kind == "ui_disconnected" -> true
                        kind !in kinds -> false
                        untilText != null -> strings(e, "added").any { it.contains(untilText, ignoreCase = true) }
                        untilGone != null -> strings(e, "removed").any { it.contains(untilGone, ignoreCase = true) }
                        else -> true
                    }
                }
                if (event == null) toolOk { put("timed_out", true); put("seq", a.hub.seq) }
                else toolOk { put("event", event) }
            }
        }
    }

    // ---- screenshot ------------------------------------------------------------------------

    private fun registerScreenshot(server: Server) {
        server.addTool(
            name = "ui_screenshot",
            description = "Save a PNG of the device screen (adb screencap) and return its path. Use when the " +
                "layout can't explain what's shown (WebViews, canvases, images). Read the file to see it. Does " +
                "not need ui_start.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    prop("path", "string", "Where to write the PNG. Default: a temp file.")
                    prop("serial", "string", "Device serial. Defaults to the UI session's or attached device.")
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
        ) { request ->
            runUiTool {
                val args = request.arguments
                val serial = args.str("serial") ?: UiSession.active?.serial ?: com.acendas.androiddebugger.Session.serial
                val out = args.str("path")?.let { Paths.get(it) }
                    ?: Files.createTempFile("android-debugger-screen-", ".png")
                val adb = AdbLocator.find() ?: throw ToolError(ErrorCode.AdbError, "adb not found")
                val cmd = buildList {
                    add(adb)
                    if (serial != null) { add("-s"); add(serial) }
                    add("exec-out"); add("screencap"); add("-p")
                }
                val proc = ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                if (!proc.waitFor(20, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    throw ToolError(ErrorCode.AdbError, "screencap timed out")
                }
                val size = Files.size(out)
                if (proc.exitValue() != 0 || size < 100) {
                    throw ToolError(ErrorCode.AdbError, "screencap failed (exit ${proc.exitValue()}, $size bytes)")
                }
                toolOk { put("path", out.toString()); put("bytes", size) }
            }
        }
    }

    // ---- monitor ---------------------------------------------------------------------------

    /**
     * The exact command to hand Claude Code's Monitor. Absolute java + jar paths: the
     * Bash/Monitor shell does not reliably have CLAUDE_PLUGIN_ROOT or java on PATH.
     */
    fun monitorCommand(eventLog: String): String {
        val javaBin = Paths.get(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java").toString()
        val jar = runCatching { PluginRoot.require().resolve("dist").resolve("android-debugger-server.jar").toString() }
            .getOrElse { File(UiTools::class.java.protectionDomain.codeSource.location.toURI()).path }
        val pid = ProcessHandle.current().pid()
        return "\"$javaBin\" -jar \"$jar\" ui-follow \"$eventLog\" --server-pid $pid"
    }
}
