package com.acendas.androiddebugger.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `java -jar android-debugger-server.jar ui-follow <event-log> [--server-pid N] [--from-start]`
 *
 * A portable `tail -f` for Claude Code's Monitor (no POSIX tools, works on Windows): prints
 * one compact line per UI event. Monitor is visibility only; decisions come from ui_wait.
 *
 * Starts at end-of-file so re-arming doesn't replay history, and always ends with a line:
 * on `ui_stopped` / `ui_disconnected`, or when the owning server process is gone — a
 * silent dead follower would look exactly like a quiet screen.
 */
object UiFollow {

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    fun run(args: List<String>, out: PrintStream = System.out): Int {
        val file = args.firstOrNull { !it.startsWith("--") }?.let { Paths.get(it) }
            ?: return usage(out)
        val serverPid = args.indexOf("--server-pid").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() }
        val fromStart = "--from-start" in args

        var waited = 0
        while (!Files.exists(file)) {
            if (waited++ > 50) { out.println("ui-follow: no event log at $file"); out.flush(); return 1 }
            Thread.sleep(100)
        }
        RandomAccessFile(file.toFile(), "r").use { raf ->
            if (!fromStart) raf.seek(raf.length())
            val pending = StringBuilder()
            var lastPidCheck = 0L
            while (true) {
                val chunk = readAvailable(raf)
                if (chunk.isNotEmpty()) {
                    pending.append(chunk)
                    while (true) {
                        val nl = pending.indexOf("\n")
                        if (nl < 0) break
                        val line = pending.substring(0, nl)
                        pending.delete(0, nl + 1)
                        val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        format(event)?.let { out.println(it); out.flush() }
                        val kind = event.s("kind")
                        if (kind == "ui_stopped" || kind == "ui_disconnected") return 0
                    }
                } else {
                    Thread.sleep(150)
                }
                val now = System.currentTimeMillis()
                if (serverPid != null && now - lastPidCheck > 2_000) {
                    lastPidCheck = now
                    if (!ProcessHandle.of(serverPid).map { it.isAlive }.orElse(false)) {
                        out.println("${clock.format(Instant.now())} ui_disconnected android-debugger server exited")
                        out.flush()
                        return 0
                    }
                }
            }
        }
    }

    private fun readAvailable(raf: RandomAccessFile): String {
        val available = raf.length() - raf.filePointer
        if (available <= 0) return ""
        val buf = ByteArray(minOf(available, 1L shl 20).toInt())
        raf.readFully(buf)
        return String(buf, StandardCharsets.UTF_8)
    }

    /** One compact line per event; null for events not worth a Monitor notification. */
    fun format(e: JsonObject): String? {
        val t = e["wall_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { clock.format(Instant.ofEpochMilli(it)) } ?: "--:--:--"
        return when (e.s("kind")) {
            "screen_changed" -> buildString {
                append(t).append(if (e.b("first") == true) " screen" else " screen_changed")
                e.s("package")?.let { append(' ').append(it) }
                append(" +").append(short(e.list("added")))
                append(" -").append(short(e.list("removed")))
                if (e.b("digits_only") == true) append(" digits_only")
                e.s("suppressed_ticks_before")?.let { append(" (after $it suppressed ticks)") }
            }
            "ui_window" -> "$t window ${e.s("type")?.removePrefix("TYPE_")} ${e.s("package") ?: ""} " +
                "${e.s("class")?.substringAfterLast('.') ?: ""} ${e.list("text").joinToString(" | ").take(60).let { if (it.isEmpty()) "" else "\"$it\"" }}".trimEnd()
            "ui_action" -> "$t action ${e.s("action")} ${e.s("target")?.let { "'$it' " } ?: ""}" +
                (e.s("x")?.let { "(${e.s("x")},${e.s("y")})" } ?: e.s("key") ?: e.s("from")?.let { "$it -> ${e.s("to")}" } ?: "")
            "screen_unreadable" -> "$t screen_unreadable ${e.s("code")}"
            "ui_started" -> "$t ui_started ${e.s("serial")} sdk=${e.s("sdk")} presence=${e.s("presence")}"
            "ui_stopped" -> "$t ui_stopped"
            "ui_disconnected" -> "$t ui_disconnected ${e.s("reason") ?: ""}".trimEnd()
            else -> null
        }
    }

    private fun short(xs: List<String>, n: Int = 4): String {
        val cleaned = xs.map { it.replace("⁦", "").replace("⁩", "").take(40) }
        val shown = cleaned.take(n).joinToString(", ") { "'$it'" }
        return "[" + shown + (if (cleaned.size > n) ", …+${cleaned.size - n}" else "") + "]"
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun usage(out: PrintStream): Int {
        out.println("usage: ui-follow <event-log.ndjson> [--server-pid N] [--from-start]")
        return 2
    }
}
