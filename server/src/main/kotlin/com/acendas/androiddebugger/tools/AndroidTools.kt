package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.adb.AdbResult
import com.acendas.androiddebugger.android.Dumpsys
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Phase 6 — Android-specific MCP tools.
 *
 * Tools registered:
 *  - tail_logcat / read_logcat / stop_logcat — server-side `adb logcat -v threadtime`
 *    reader with ring-buffered storage. Per Story 6.1.1.
 *  - dump_heap — `am dumpheap <pid>` + `adb pull`. Per Story 6.1.3.
 *  - get_current_activity — parses `dumpsys activity activities`. Per Story 6.1.4.
 *  - dump_view_hierarchy — `uiautomator dump` + `adb pull`. Per Story 6.1.4.
 *  - get_app_info — parses `dumpsys package <pkg>`. Per Story 6.1.5.
 */
object AndroidTools {

    fun register(server: Server) {
        registerTailLogcat(server)
        registerReadLogcat(server)
        registerStopLogcat(server)
        registerDumpHeap(server)
        registerGetCurrentActivity(server)
        registerDumpViewHierarchy(server)
        registerGetAppInfo(server)
    }

    // ---------------- logcat ----------------

    private fun registerTailLogcat(server: Server) {
        server.addTool(
            name = "tail_logcat",
            description = "Start a server-side `adb logcat -v threadtime` subprocess and ring-buffer its " +
                "parsed entries. Returns `{ buffer_id }` for use with `read_logcat` and `stop_logcat`. " +
                "Pass `filter` (exact tag match), `regex` (substring on the message), `since` (passed to " +
                "`adb logcat -T`), `pid_filter` (pass `--pid`; defaults to the attached pid when set on the " +
                "session). `max_buffer` defaults to 2000 entries; bounded between 50 and 50000.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("filter") {
                        put("type", "string")
                        put("description", "Exact tag match (e.g. \"MyTag\"). Drops lines whose tag isn't this.")
                    }
                    putJsonObject("regex") {
                        put("type", "string")
                        put("description", "Regex matched against the message text. Drops lines that don't match.")
                    }
                    putJsonObject("since") {
                        put("type", "string")
                        put("description", "Logcat -T value (\"MM-DD HH:MM:SS.mmm\" or epoch seconds).")
                    }
                    putJsonObject("pid_filter") {
                        put("type", "integer")
                        put("description", "Restrict to a single PID. Defaults to the attached pid (when set).")
                    }
                    putJsonObject("max_buffer") {
                        put("type", "integer")
                        put("description", "Ring-buffer size. Default 2000; range 50..50000.")
                    }
                    putJsonObject("scope_to_app") {
                        put("type", "boolean")
                        put("description", "If true (default), pass --pid=<attached pid> when attached. Set " +
                            "false to follow system-wide logcat regardless of attached state.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { request ->
            runTool {
                val args = request.arguments
                val filter = (args?.get("filter") as? JsonPrimitive)?.contentOrNull
                val regex = (args?.get("regex") as? JsonPrimitive)?.contentOrNull
                val since = (args?.get("since") as? JsonPrimitive)?.contentOrNull
                val maxBuffer = ((args?.get("max_buffer") as? JsonPrimitive)?.intOrNull ?: 2000)
                    .coerceIn(50, 50_000)
                val scopeToApp = (args?.get("scope_to_app") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val pidFilterArg = (args?.get("pid_filter") as? JsonPrimitive)?.intOrNull
                    ?: if (scopeToApp) Session.pid else null

                // Validate regex eagerly so the agent gets a sane error instead of a silent
                // empty buffer that grows forever.
                if (regex != null) {
                    try {
                        Regex(regex)
                    } catch (e: Throwable) {
                        throw ToolError(
                            errorCode = ErrorCode.InvalidTarget,
                            message = "Invalid regex: ${e.message ?: "?"}",
                        )
                    }
                }

                val buf = Session.logcatBuffers.create(
                    adb = Session.adb,
                    serial = Session.serial,
                    pidFilter = pidFilterArg,
                    filter = filter,
                    regex = regex,
                    sinceArg = since,
                    maxBuffer = maxBuffer,
                )
                toolOk {
                    put("buffer_id", buf.id)
                    put("max_buffer", buf.maxBuffer)
                    filter?.let { put("filter", it) }
                    regex?.let { put("regex", it) }
                    pidFilterArg?.let { put("pid_filter", it) }
                }
            }
        }
    }

    private fun registerReadLogcat(server: Server) {
        server.addTool(
            name = "read_logcat",
            description = "Read entries from a logcat buffer started by `tail_logcat`. Pass `since` (a seq id from " +
                "a previous read) to fetch only new entries — leave it 0 / omit to dump the whole ring. Returns " +
                "`[entries]` plus the highest seq seen so the agent can paginate. Logpoints (Phase 3 logpoint " +
                "breakpoints) are merged into the same response under `logpoints` (separate seq cursor); each is " +
                "tagged `debugger:logpoint` with the source file/line + breakpoint id so the agent can correlate.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("buffer_id") {
                        put("type", "integer")
                        put("description", "Buffer id from `tail_logcat`.")
                    }
                    putJsonObject("since") {
                        put("type", "integer")
                        put("description", "Return logcat entries with `seq > since`. 0 = whole ring.")
                    }
                    putJsonObject("since_logpoint") {
                        put("type", "integer")
                        put("description", "Return logpoint entries with `seq > since_logpoint`. 0 = whole logpoint ring.")
                    }
                    putJsonObject("include_logpoints") {
                        put("type", "boolean")
                        put("description", "Include logpoint entries in the response. Default true.")
                    }
                },
                required = listOf("buffer_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                val args = request.arguments
                val bufferId = (args?.get("buffer_id") as? JsonPrimitive)?.intOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `buffer_id`.")
                val since = (args.get("since") as? JsonPrimitive)?.longOrNull ?: 0L
                val sinceLogpoint = (args.get("since_logpoint") as? JsonPrimitive)?.longOrNull ?: 0L
                val includeLogpoints = (args.get("include_logpoints") as? JsonPrimitive)?.booleanOrNull ?: true
                val buf = Session.logcatBuffers.get(bufferId)
                    ?: throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "No logcat buffer with id $bufferId.",
                        hint = "Start one with `tail_logcat`. Buffers vanish on `detach`.",
                    )
                val entries = buf.readSince(since)
                val maxSeq = entries.maxOfOrNull { it.seq } ?: since

                // Story 6.1.2: merge in logpoint entries from the Phase 3 LogpointBuffer.
                // Synthesized as logcat-shaped entries with tag `debugger:logpoint` so
                // the agent can filter by tag if it wants logpoints-only.
                val logpoints = if (includeLogpoints) {
                    com.acendas.androiddebugger.breakpoints.LogpointBuffer.since(sinceLogpoint)
                } else emptyList()
                val maxLogpointSeq = logpoints.maxOfOrNull { it.seq } ?: sinceLogpoint

                toolOk {
                    put("entries", buildJsonArray {
                        for (e in entries) {
                            add(buildJsonObject {
                                put("seq", e.seq)
                                put("ts", e.ts)
                                put("pid", e.pid)
                                put("tid", e.tid)
                                put("level", e.level)
                                put("tag", e.tag)
                                put("message", e.message)
                            })
                        }
                    })
                    put("count", entries.size)
                    put("max_seq", maxSeq)
                    val snap = buf.snapshot()
                    put("buffer_size_now", snap.sizeNow)
                    put("alive", snap.alive)
                    put("logpoints", buildJsonArray {
                        for (lp in logpoints) {
                            add(buildJsonObject {
                                put("seq", lp.seq)
                                put("ts", lp.timestamp.toString())
                                lp.threadName?.let { put("thread", it) }
                                lp.file?.let { put("file", it) }
                                put("line", lp.line)
                                put("breakpoint_id", lp.breakpointId)
                                put("tag", "debugger:logpoint")
                                put("level", "I")
                                put("message", lp.rendered)
                            })
                        }
                    })
                    put("logpoint_count", logpoints.size)
                    put("max_logpoint_seq", maxLogpointSeq)
                }
            }
        }
    }

    private fun registerStopLogcat(server: Server) {
        server.addTool(
            name = "stop_logcat",
            description = "Terminate a logcat buffer's `adb logcat` subprocess and drop its ring. Idempotent — " +
                "returns `{ stopped: false }` if the id is unknown.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("buffer_id") {
                        put("type", "integer")
                        put("description", "Buffer id from `tail_logcat`.")
                    }
                },
                required = listOf("buffer_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val bufferId = (request.arguments?.get("buffer_id") as? JsonPrimitive)?.intOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `buffer_id`.")
                val stopped = Session.logcatBuffers.stop(bufferId)
                toolOk {
                    put("stopped", stopped)
                }
            }
        }
    }

    // ---------------- dump_heap ----------------

    private fun registerDumpHeap(server: Server) {
        server.addTool(
            name = "dump_heap",
            description = "Capture an HPROF heap dump of the attached app via `am dumpheap <pid>`, then `adb pull` " +
                "the file to a local path. Returns `{ ok, local_path, size_bytes, device_path }`. Default " +
                "`out_path` is under `\${java.io.tmpdir}/android-debugger/heaps/<timestamp>.hprof`. The HPROF can " +
                "be opened in Android Studio's Memory Profiler or Eclipse MAT.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("out_path") {
                        put("type", "string")
                        put("description", "Local filesystem path to write the HPROF to. Default: tmp.")
                    }
                    putJsonObject("pid") {
                        put("type", "integer")
                        put("description", "Override the pid (defaults to the attached pid).")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { request ->
            runTool {
                val attachedPid = Session.pid
                val argPid = (request.arguments?.get("pid") as? JsonPrimitive)?.intOrNull
                val targetPid = argPid ?: attachedPid
                    ?: throw ToolError(
                        errorCode = ErrorCode.NotAttached,
                        message = "No attached pid and no `pid` argument given.",
                        hint = "Pass `pid` explicitly or attach first.",
                    )
                val outPath = (request.arguments?.get("out_path") as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Paths.get(it) }
                    ?: defaultHeapOutPath()
                Files.createDirectories(outPath.parent ?: outPath.toAbsolutePath().parent)

                val timestamp = System.currentTimeMillis()
                val deviceFile = "/data/local/tmp/android-debugger-${targetPid}-${timestamp}.hprof"

                // 1) am dumpheap on the device. Synchronous; writes the HPROF to deviceFile.
                val serial = Session.serial
                val dumpArgs = buildList {
                    if (serial != null) { add("-s"); add(serial) }
                    add("shell"); add("am"); add("dumpheap"); add(targetPid.toString()); add(deviceFile)
                }
                val dumpResult = Session.adb.runText(dumpArgs, timeoutMs = 60_000)
                if (dumpResult !is AdbResult.Success) {
                    throw ToolError(
                        errorCode = ErrorCode.AdbError,
                        message = "am dumpheap failed: $dumpResult",
                        hint = "Make sure the app is running and debuggable; some Android versions also require `run-as`.",
                    )
                }

                // 2) adb pull the file locally.
                val pullArgs = buildList {
                    if (serial != null) { add("-s"); add(serial) }
                    add("pull"); add(deviceFile); add(outPath.toAbsolutePath().toString())
                }
                val pullResult = Session.adb.runText(pullArgs, timeoutMs = 120_000)
                // 3) Best-effort cleanup of the device-side file regardless of pull outcome.
                val rmArgs = buildList {
                    if (serial != null) { add("-s"); add(serial) }
                    add("shell"); add("rm"); add("-f"); add(deviceFile)
                }
                runCatching { Session.adb.runText(rmArgs, timeoutMs = 5_000) }

                if (pullResult !is AdbResult.Success) {
                    throw ToolError(
                        errorCode = ErrorCode.AdbError,
                        message = "adb pull failed: $pullResult",
                        hint = "The dump succeeded on the device but the pull failed — check disk space at $outPath.",
                    )
                }
                val size = runCatching { Files.size(outPath) }.getOrDefault(-1L)
                toolOk {
                    put("local_path", outPath.toAbsolutePath().toString())
                    put("device_path", deviceFile)
                    put("size_bytes", size)
                }
            }
        }
    }

    private fun defaultHeapOutPath(): Path {
        val tmp = System.getProperty("java.io.tmpdir") ?: "."
        val ts = System.currentTimeMillis()
        val dir = Paths.get(tmp, "android-debugger", "heaps")
        Files.createDirectories(dir)
        return dir.resolve("$ts.hprof")
    }

    // ---------------- current activity ----------------

    private fun registerGetCurrentActivity(server: Server) {
        server.addTool(
            name = "get_current_activity",
            description = "Return the resumed/focused activity by parsing `dumpsys activity activities`. " +
                "Returns `{ package, activity, task_id, state }`. Useful for confirming you're in the screen " +
                "you think you are before running a probe.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
        ) {
            runTool {
                val serial = Session.serial ?: LifecycleTools.resolveSerial(null)
                val args = buildList {
                    if (serial != null) { add("-s"); add(serial) }
                    add("shell"); add("dumpsys"); add("activity"); add("activities")
                }
                val result = Session.adb.runText(args, timeoutMs = 10_000)
                if (result !is AdbResult.Success) {
                    throw ToolError(
                        errorCode = ErrorCode.AdbError,
                        message = "dumpsys activity activities failed: $result",
                    )
                }
                val parsed = Dumpsys.parseCurrentActivity(result.stdout)
                toolOk {
                    parsed.packageName?.let { put("package", it) }
                    parsed.activity?.let { put("activity", it) }
                    parsed.taskId?.let { put("task_id", it) }
                    parsed.state?.let { put("state", it) }
                    if (parsed.packageName == null && parsed.activity == null) {
                        put("hint", "Could not locate a resumed activity in dumpsys output. The device may be at the home screen, or the format changed.")
                    }
                }
            }
        }
    }

    // ---------------- view hierarchy ----------------

    private fun registerDumpViewHierarchy(server: Server) {
        server.addTool(
            name = "dump_view_hierarchy",
            description = "Run `uiautomator dump` on the device, pull the resulting XML, and return either a " +
                "structured tree or the raw text if XML parsing fails. The tree shape is `{ class, resource_id, " +
                "text, content_desc, bounds, children: [...] }`. Useful for confirming an element is visible " +
                "before driving instrumentation.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) {
            runTool {
                val serial = Session.serial ?: LifecycleTools.resolveSerial(null)
                val devicePath = "/sdcard/window_dump.xml"
                val dumpArgs = buildList {
                    if (serial != null) { add("-s"); add(serial) }
                    add("shell"); add("uiautomator"); add("dump"); add(devicePath)
                }
                val dumpResult = Session.adb.runText(dumpArgs, timeoutMs = 30_000)
                if (dumpResult !is AdbResult.Success) {
                    throw ToolError(
                        errorCode = ErrorCode.AdbError,
                        message = "uiautomator dump failed: $dumpResult",
                        hint = "Some emulator images don't have uiautomator. On rooted devices, try `uiautomator dump --compressed`.",
                    )
                }

                // Pull to a temp file, read it back, then drop it.
                val tmp = System.getProperty("java.io.tmpdir") ?: "."
                val localPath = Paths.get(tmp, "android-debugger", "window_dump.${System.currentTimeMillis()}.xml")
                Files.createDirectories(localPath.parent)

                val pullArgs = buildList {
                    if (serial != null) { add("-s"); add(serial) }
                    add("pull"); add(devicePath); add(localPath.toAbsolutePath().toString())
                }
                val pullResult = Session.adb.runText(pullArgs, timeoutMs = 30_000)
                if (pullResult !is AdbResult.Success) {
                    throw ToolError(
                        errorCode = ErrorCode.AdbError,
                        message = "adb pull window_dump.xml failed: $pullResult",
                    )
                }
                val xml = runCatching { Files.readString(localPath) }.getOrNull() ?: ""
                runCatching { Files.deleteIfExists(localPath) }

                val tree = Dumpsys.parseUiAutomatorXml(xml)
                toolOk {
                    if (tree != null) {
                        put("hierarchy", treeToJson(tree))
                        put("parse_ok", true)
                    } else {
                        put("raw_xml", xml)
                        put("parse_ok", false)
                        put("hint", "XML parsing failed; raw text returned instead.")
                    }
                }
            }
        }
    }

    private fun treeToJson(node: Dumpsys.ViewNode): JsonObject = buildJsonObject {
        node.cls?.let { put("class", it) }
        node.resourceId?.let { put("resource_id", it) }
        node.text?.let { put("text", it) }
        node.contentDesc?.let { put("content_desc", it) }
        node.bounds?.let { put("bounds", it) }
        if (node.children.isNotEmpty()) {
            put("children", buildJsonArray {
                for (c in node.children) add(treeToJson(c))
            })
        }
    }

    // ---------------- app info ----------------

    private fun registerGetAppInfo(server: Server) {
        server.addTool(
            name = "get_app_info",
            description = "Read app metadata via `dumpsys package <package>`. Returns `{ package, debuggable, " +
                "target_sdk, min_sdk, version_name, version_code, declared_processes }`. Catches the canonical " +
                "release-build cause: `debuggable: false` means JDWP attach won't work even if the device says yes.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Package name (e.g. `com.example.app`).")
                    }
                },
                required = listOf("package"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
        ) { request ->
            runTool {
                val pkg = (request.arguments?.get("package") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `package`.")
                val serial = Session.serial ?: LifecycleTools.resolveSerial(null)
                val args = buildList {
                    if (serial != null) { add("-s"); add(serial) }
                    add("shell"); add("dumpsys"); add("package"); add(pkg)
                }
                val result = Session.adb.runText(args, timeoutMs = 15_000)
                if (result !is AdbResult.Success) {
                    throw ToolError(
                        errorCode = ErrorCode.AdbError,
                        message = "dumpsys package $pkg failed: $result",
                    )
                }
                val info = Dumpsys.parseAppInfo(pkg, result.stdout)
                toolOk {
                    put("package", info.packageName)
                    put("debuggable", info.debuggable)
                    info.targetSdk?.let { put("target_sdk", it) }
                    info.minSdk?.let { put("min_sdk", it) }
                    info.versionName?.let { put("version_name", it) }
                    info.versionCode?.let { put("version_code", it) }
                    put("declared_processes", buildJsonArray {
                        info.declaredProcesses.forEach { add(JsonPrimitive(it)) }
                    })
                    if (!info.debuggable) {
                        put("warning", "App is NOT debuggable (release/AOSP build) — JDWP attach will not work.")
                    }
                }
            }
        }
    }
}

