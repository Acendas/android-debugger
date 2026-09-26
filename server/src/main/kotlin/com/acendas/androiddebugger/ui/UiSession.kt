package com.acendas.androiddebugger.ui

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.PluginRoot
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.SessionState
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.adb.Adb
import com.acendas.androiddebugger.adb.AdbResult
import com.acendas.androiddebugger.adb.StreamHandle
import com.acendas.androiddebugger.events.DebugEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The UI-driving session: one on-device daemon (see ui-daemon/AdUiDaemon.java) holding
 * the device's single UiAutomation slot, plus the host-side settle/diff loop that turns
 * its raw accessibility pings into `screen_changed` events.
 *
 * Independent of the JDWP debug session on purpose: you can drive an app to the screen
 * you care about *before* attaching, and UI tools never take [Session.mutex] (which
 * `wait_for_event` holds for up to a minute). The daemon serializes its own work.
 *
 * One UI session per server process; [start] on a different serial stops the old one.
 */
object UiSession {

    /** Must match AdUiDaemon.VERSION. The jar is pushed on every start, so a mismatch is a bug. */
    const val DAEMON_VERSION = "1"
    private const val DEVICE_JAR = "/data/local/tmp/ad-ui.jar"
    private const val DAEMON_CLASS = "com.acendas.adui.AdUiDaemon"
    private const val SETTLE_QUIET_MS = 300L
    private const val SETTLE_MAX_MS = 1_500L
    private const val SETTLE_READ_TIMEOUT_MS = 2_000L
    private const val PAUSED_RECHECK_MS = 1_000L

    /** a11y event types worth a Monitor line on their own. Content pings only drive settling. */
    private val WINDOW_TYPES = setOf(
        "TYPE_WINDOW_STATE_CHANGED", "TYPE_VIEW_CLICKED", "TYPE_VIEW_LONG_CLICKED",
        "TYPE_NOTIFICATION_STATE_CHANGED", "TYPE_ANNOUNCEMENT",
    )

    class Active(
        val serial: String,
        val sdk: Int,
        val port: Int,
        val daemon: StreamHandle,
        val control: UiDaemonClient,
        val settleClient: UiDaemonClient,
        val stream: UiDaemonClient,
        val hub: UiEventHub,
        val presencePrior: Presence.Prior?,
        val forwardToDebug: Boolean,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        val differ = ScreenDiffer()
        @Volatile var lastInputSeq: Long = 0
        @Volatile var alive: Boolean = true
        @Volatile var lastUnreadableCode: String? = null
    }

    @Volatile var active: Active? = null
        private set

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ui-settle").apply { isDaemon = true }
    }
    private var pendingSettle: ScheduledFuture<*>? = null
    private var firstUnsettledAt = -1L

    val adb: Adb get() = Session.adb

    fun require(): Active = active?.takeIf { it.alive } ?: throw ToolError(
        errorCode = ErrorCode.UiNotStarted,
        message = if (active == null) "No UI session. Call ui_start first." else "The UI daemon exited.",
        hint = "Call ui_start (optionally with serial). If it keeps exiting, check `adb devices`.",
    )

    // ---- lifecycle -------------------------------------------------------------------------

    data class StartResult(val active: Active, val warnings: List<String>, val repairedStalePresence: Boolean)

    @Synchronized
    fun start(serialArg: String?, presence: Boolean, forwardToDebug: Boolean): StartResult {
        val serial = resolveSerial(serialArg)
        active?.let { if (it.serial == serial && it.alive) return StartResult(it, listOf("already_running"), false) }
        stop()

        val root = PluginRoot.require()
        val jar = root.resolve("dist").resolve("ui").resolve("ad-ui.jar")
        if (!Files.isRegularFile(jar)) {
            throw ToolError(ErrorCode.Internal, "UI daemon artifact missing: $jar", hint = "Rebuild with ui-daemon/build.sh.")
        }
        val s = listOf("-s", serial)

        // A killed server can leave presence enabled; repair before anything else.
        val presenceHelper = Presence(adb, serial)
        val repaired = runCatching { presenceHelper.repairIfStale() }.getOrDefault(false)

        adb.runText(s + listOf("shell", "pkill -f $DAEMON_CLASS"), timeoutMs = 5_000)

        // Presence must be on BEFORE the daemon connects. Compose re-reads the enabled
        // service list only when accessibility flips off -> on; once our UiAutomation
        // session has turned it on, enabling the service later changes nothing Compose
        // listens for, and the app stays silent.
        val prior = if (presence) {
            presenceHelper.enable(root.resolve("dist").resolve("ui").resolve("ad-ui-presence.apk"))
        } else null
        fun undoPresence() { prior?.let { runCatching { presenceHelper.disable(it) } } }

        val push = adb.runText(s + listOf("push", jar.toString(), DEVICE_JAR), timeoutMs = 30_000)
        if (push !is AdbResult.Success) {
            undoPresence()
            throw ToolError(ErrorCode.AdbError, "Could not push the UI daemon: ${describe(push)}")
        }

        val ready = CompletableFuture<String>()
        val output = StringBuilder()
        val daemon = adb.runStream(s + listOf("shell", "CLASSPATH=$DEVICE_JAR app_process / $DAEMON_CLASS")) { line ->
            synchronized(output) { output.appendLine(line) }
            if (line.startsWith("ad-ui ready")) ready.complete(line)
        }
        val banner = runCatching { ready.get(10, TimeUnit.SECONDS) }.getOrNull()
        if (banner == null) {
            daemon.stop()
            undoPresence()
            throw ToolError(
                errorCode = ErrorCode.UiDaemonError,
                message = "UI daemon did not start: ${synchronized(output) { output.toString().trim().take(600) }.ifEmpty { "no output" }}",
                hint = "Another UiAutomation client (a running UI test, Appium, Maestro, `uiautomator`) " +
                    "may hold the device's single slot. Stop it and retry.",
            )
        }

        val port = adb.forwardAbstract(serial, "ad-ui") ?: run {
            daemon.stop()
            undoPresence()
            throw ToolError(ErrorCode.AdbError, "adb forward to localabstract:ad-ui failed")
        }

        val clients = mutableListOf<UiDaemonClient>()
        try {
            val control = UiDaemonClient(port).also { clients += it }
            val ping = control.request(buildJsonObject { put("cmd", "ping") })
            val version = ping["version"]?.jsonPrimitive?.contentOrNull
            if (version != DAEMON_VERSION) {
                throw ToolError(ErrorCode.UiDaemonError, "UI daemon version $version, expected $DAEMON_VERSION")
            }
            val sdk = ping["sdk"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val settleClient = UiDaemonClient(port).also { clients += it }
            val stream = UiDaemonClient(port).also { clients += it }
            stream.request(buildJsonObject { put("cmd", "subscribe") })

            val hub = UiEventHub(eventLogPath(serial))
            val a = Active(serial, sdk, port, daemon, control, settleClient, stream, hub, prior, forwardToDebug)
            active = a
            startStreamReader(a)
            publish(a, buildJsonObject {
                put("kind", "ui_started")
                put("serial", serial)
                put("sdk", sdk)
                put("presence", presence)
            })
            armSettle() // baseline screen

            val warnings = mutableListOf<String>()
            if (!presence && looksLikeSuppressedCompose(a, presenceHelper)) warnings += "compose_events_suppressed"
            return StartResult(a, warnings, repaired)
        } catch (t: Throwable) {
            clients.forEach { it.close() }
            daemon.stop()
            adb.removeForward(serial, port)
            undoPresence()
            throw t
        }
    }

    @Synchronized
    fun stop(): Boolean {
        val a = active ?: return false
        active = null
        a.alive = false
        cancelSettle()
        runCatching { publish(a, buildJsonObject { put("kind", "ui_stopped") }) }
        runCatching { a.control.request(buildJsonObject { put("cmd", "quit") }, readTimeoutMs = 3_000) }
        listOf(a.control, a.settleClient, a.stream).forEach { it.close() }
        a.daemon.stop()
        runCatching { adb.runText(listOf("-s", a.serial, "shell", "rm -f $DEVICE_JAR"), timeoutMs = 5_000) }
        runCatching { adb.removeForward(a.serial, a.port) }
        a.presencePrior?.let { prior -> runCatching { Presence(adb, a.serial).disable(prior) } }
        a.hub.close()
        return true
    }

    // ---- requests --------------------------------------------------------------------------

    /** Send a request on the control connection; daemon errors become [ToolError]. */
    fun request(req: JsonObject, readTimeoutMs: Int = 10_000): JsonObject {
        val a = require()
        val resp = try {
            a.control.request(req, readTimeoutMs)
        } catch (t: Throwable) {
            markDead(a, "control connection failed: ${t.message}")
            throw ToolError(ErrorCode.UiNotStarted, "Lost the UI daemon (${t.message}).", hint = "Call ui_start again.")
        }
        if (resp["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            val code = resp["code"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val message = resp["message"]?.jsonPrimitive?.contentOrNull ?: "UI daemon error"
            throw ToolError(
                errorCode = ErrorCode.UiDaemonError,
                message = "$message (daemon_code=$code)",
                hint = hintFor(code),
                currentState = "daemon_code:$code",
            )
        }
        return resp
    }

    /** Record an input action: clears tick suppression and returns the seq cursor for ui_wait. */
    fun recordInput(action: JsonObject): Long {
        val a = require()
        val cursor = a.hub.seq
        a.differ.onInput()
        a.lastInputSeq = cursor
        publish(a, buildJsonObject {
            put("kind", "ui_action")
            for ((k, v) in action) put(k, v)
        })
        return cursor
    }

    /** Refuse UI input/reads while the attached app is suspended in the debugger. */
    fun requireNotPaused(allowWhilePaused: Boolean) {
        if (allowWhilePaused) return
        if (Session.state == SessionState.ATTACHED_PAUSED) {
            throw ToolError(
                errorCode = ErrorCode.VmPaused,
                message = "The attached app (${Session.packageName}) is paused in the debugger. Its UI " +
                    "thread is frozen: reads time out and input queues up (ANR risk).",
                hint = "resume first, or pass allow_while_paused: true when the target is a different app " +
                    "(e.g. system UI).",
                currentState = "ATTACHED_PAUSED",
            )
        }
    }

    // ---- events & settling -----------------------------------------------------------------

    private fun startStreamReader(a: Active) {
        Thread({
            while (a.alive) {
                val e = runCatching { a.stream.readEvent() }.getOrNull() ?: break
                onRawEvent(a, e)
            }
            if (a.alive) markDead(a, "event stream ended (daemon exited or adb disconnected)")
        }, "ui-stream-${a.serial}").apply { isDaemon = true }.start()
    }

    private fun onRawEvent(a: Active, e: JsonObject) {
        val type = e["type"]?.jsonPrimitive?.contentOrNull
        if (type in WINDOW_TYPES) {
            publish(a, buildJsonObject {
                put("kind", "ui_window")
                for ((k, v) in e) if (k != "kind") put(k, v)
            })
        }
        armSettle()
    }

    private fun armSettle() {
        synchronized(scheduler) {
            val now = System.currentTimeMillis()
            if (firstUnsettledAt < 0) firstUnsettledAt = now
            val delay = minOf(SETTLE_QUIET_MS, maxOf(0L, firstUnsettledAt + SETTLE_MAX_MS - now))
            pendingSettle?.cancel(false)
            pendingSettle = scheduler.schedule({ settle() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun cancelSettle() {
        synchronized(scheduler) {
            pendingSettle?.cancel(false)
            pendingSettle = null
            firstUnsettledAt = -1
        }
    }

    private fun settle() {
        synchronized(scheduler) {
            firstUnsettledAt = -1
            pendingSettle = null
        }
        val a = active?.takeIf { it.alive } ?: return
        // A paused app can't answer reads; every ping would become a 2 s timeout line.
        if (Session.state == SessionState.ATTACHED_PAUSED) {
            // Re-check until the debugger resumes; then report whatever changed meanwhile.
            synchronized(scheduler) {
                if (pendingSettle == null) {
                    pendingSettle = scheduler.schedule({ settle() }, PAUSED_RECHECK_MS, TimeUnit.MILLISECONDS)
                }
            }
            return
        }
        var resp = readLayout(a)
        if (resp?.get("code")?.jsonPrimitive?.contentOrNull == "no_root") {
            // Transient while windows swap (a dialog closing, an activity resuming).
            Thread.sleep(SETTLE_QUIET_MS)
            resp = readLayout(a)
        }
        if (resp == null) return
        if (resp["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            val code = resp["code"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            if (code != a.lastUnreadableCode) {
                a.lastUnreadableCode = code
                publish(a, buildJsonObject { put("kind", "screen_unreadable"); put("code", code) })
            }
            return
        }
        a.lastUnreadableCode = null
        val pkg = resp["package"]?.jsonPrimitive?.contentOrNull
        val screen = visibleStrings(resp)
        val change = a.differ.observe(pkg, screen, System.currentTimeMillis()) ?: return
        publish(a, buildJsonObject {
            put("kind", "screen_changed")
            pkg?.let { put("package", it) }
            put("first", change.first)
            put("added", buildJsonArray { change.added.forEach { add(JsonPrimitive(it)) } })
            put("removed", buildJsonArray { change.removed.forEach { add(JsonPrimitive(it)) } })
            put("visible", change.visible)
            if (change.digitsOnly) put("digits_only", true)
            if (change.suppressedTicksBefore > 0) put("suppressed_ticks_before", change.suppressedTicksBefore)
        })
    }

    private fun readLayout(a: Active): JsonObject? = runCatching {
        a.settleClient.request(
            buildJsonObject { put("cmd", "layout"); put("timeout_ms", SETTLE_READ_TIMEOUT_MS) },
            readTimeoutMs = (SETTLE_READ_TIMEOUT_MS + 3_000).toInt(),
        )
    }.getOrElse {
        markDead(a, "settle connection failed: ${it.message}")
        null
    }

    private fun publish(a: Active, event: JsonObject) {
        val stamped = a.hub.publish(event)
        if (a.forwardToDebug) {
            val kind = stamped["kind"]?.jsonPrimitive?.contentOrNull
            if (kind != "ui_action") Session.eventLoop?.dispatchSynthetic(DebugEvent.Ui(stamped))
        }
    }

    private fun markDead(a: Active, reason: String) {
        if (!a.alive) return
        a.alive = false
        runCatching { publish(a, buildJsonObject { put("kind", "ui_disconnected"); put("reason", reason) }) }
    }

    // ---- helpers ---------------------------------------------------------------------------

    fun visibleStrings(layout: JsonObject): List<String> =
        (layout["nodes"] as? JsonArray).orEmpty().mapNotNull { n ->
            val o = n.jsonObject
            (o["text"] ?: o["content-desc"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        }

    private fun looksLikeSuppressedCompose(a: Active, presence: Presence): Boolean = runCatching {
        val full = a.control.request(buildJsonObject { put("cmd", "layout"); put("full", true); put("timeout_ms", 3_000) })
        val compose = (full["nodes"] as? JsonArray).orEmpty().any {
            it.jsonObject["class"]?.jsonPrimitive?.contentOrNull?.contains("ComposeView") == true
        }
        compose && !presence.otherServicesEnabled()
    }.getOrDefault(false)

    private fun resolveSerial(serialArg: String?): String {
        serialArg?.let { return it }
        Session.serial?.let { return it }
        val devices = adb.listDevices().filter { it.state == "device" }
        return when (devices.size) {
            1 -> devices.single().serial
            0 -> throw ToolError(ErrorCode.InvalidTarget, "No device connected.", hint = "Connect a device; check `adb devices`.")
            else -> throw ToolError(
                ErrorCode.InvalidTarget,
                "Several devices connected: ${devices.joinToString { it.serial }}.",
                hint = "Pass serial.",
            )
        }
    }

    private fun eventLogPath(serial: String): Path {
        val safe = serial.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val pid = ProcessHandle.current().pid()
        return Paths.get(System.getProperty("java.io.tmpdir"), "android-debugger", "ui-$pid-$safe.ndjson")
    }

    private fun hintFor(code: String): String? = when (code) {
        "timeout" -> "The UI thread did not answer in time. If the app is paused in the debugger, resume it."
        "no_root" -> "No active window (a transition, or a secure window). Retry after the next screen_changed."
        "no_focus" -> "Tap the input field first, then ui_type."
        "released" -> "The UI session was released (e.g. for an instrumented test). Call ui_start again."
        "inject_failed" -> "Input injection was refused; the screen may be locked or a secure window is on top."
        else -> null
    }

    private fun describe(r: AdbResult): String = when (r) {
        is AdbResult.Error -> (r.stderr.ifBlank { r.stdout }).trim().take(300)
        is AdbResult.Timeout -> "timed out"
        is AdbResult.NotFound -> r.hint
        is AdbResult.LaunchFailed -> r.cause.message ?: "launch failed"
        is AdbResult.Success -> "ok"
    }

    /** For status: the settled screen's strings are not cached; expose counts only. */
    fun statusJson(): JsonObject = buildJsonObject {
        val a = active
        put("running", a != null && a.alive)
        if (a != null) {
            put("serial", a.serial)
            put("sdk", a.sdk)
            put("alive", a.alive)
            put("presence", a.presencePrior != null)
            put("forward_to_debug_events", a.forwardToDebug)
            put("event_log", a.hub.logFile.toString())
            put("last_seq", a.hub.seq)
            put("uptime_ms", System.currentTimeMillis() - a.startedAt)
        }
    }
}
