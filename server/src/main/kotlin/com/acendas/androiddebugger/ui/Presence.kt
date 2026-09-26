package com.acendas.androiddebugger.ui

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.adb.Adb
import com.acendas.androiddebugger.adb.AdbResult
import java.nio.file.Path

/**
 * The opt-in "presence" accessibility service.
 *
 * Why: Jetpack Compose sends no accessibility events while the enabled-service list is
 * empty — it reads that state as "UIAutomator is running" and stays quiet to keep
 * benchmarks clean (see Compose's android_a11y_implementation_notes.md). Our daemon uses
 * UiAutomation, which is not in that list, so a Compose app looks frozen to the event
 * stream. Enabling a no-op service that observes nothing makes the list non-empty and
 * Compose emits; the daemon still receives everything through UiAutomation.
 *
 * Contract — the device's own settings are someone else's:
 *  - Opt-in only. It changes a secure setting, and apps that branch on "accessibility
 *    enabled" can behave differently while it is on.
 *  - We *append* our component to `enabled_accessibility_services` and restore the exact
 *    prior values on disable. Never `settings delete` a value that existed: that would
 *    switch off a user's TalkBack or switch-access service.
 *  - The prior values are also written to an on-device [MARKER], so a server that was
 *    hard-killed mid-session is repaired by the next `ui_start` ([repairIfStale]).
 */
class Presence(
    private val adb: Adb,
    private val serial: String,
    private val settleDelayMs: Long = 400,
) {

    data class Prior(val services: String?, val enabled: String?)

    /** Read prior values, persist the marker, install, enable. Returns the prior values. */
    fun enable(apk: Path): Prior {
        repairIfStale()
        val prior = Prior(
            services = getSecure(SERVICES_KEY),
            enabled = getSecure(ENABLED_KEY),
        )
        writeMarker(prior)
        install(apk)
        putSecure(SERVICES_KEY, mergedServices(prior.services))
        putSecure(ENABLED_KEY, "1")
        return prior
    }

    /** Restore exactly what was there before [enable], uninstall, drop the marker. */
    fun disable(prior: Prior) {
        // AccessibilityManagerService rewrites accessibility_enabled by itself whenever the
        // service list changes, and while a UiAutomation session is still unregistering it
        // writes 1. So restore, read back, and retry until the device shows the prior values.
        for (attempt in 1..RESTORE_ATTEMPTS) {
            restoreSecure(SERVICES_KEY, prior.services)
            restoreSecure(ENABLED_KEY, prior.enabled)
            if (settleDelayMs > 0) Thread.sleep(settleDelayMs)
            if (getSecure(SERVICES_KEY) == prior.services && getSecure(ENABLED_KEY) == prior.enabled) break
        }
        shell("pm uninstall $PACKAGE")
        shell("rm -f $MARKER")
    }

    /**
     * A marker left by a server that died mid-session: restore from it. Returns true
     * when a repair happened.
     */
    fun repairIfStale(): Boolean {
        val text = (shell("cat $MARKER 2>/dev/null") as? AdbResult.Success)?.stdout ?: return false
        val prior = parseMarker(text) ?: return false
        disable(prior)
        return true
    }

    /** Whether any accessibility service other than ours is currently enabled. */
    fun otherServicesEnabled(): Boolean =
        getSecure(SERVICES_KEY)?.split(':')?.any { it.isNotBlank() && it != COMPONENT } == true

    private fun install(apk: Path) {
        val first = adb.runText(prefix() + listOf("install", "-r", apk.toString()), timeoutMs = 60_000)
        if (first is AdbResult.Success) return
        val text = when (first) {
            is AdbResult.Error -> first.stdout + first.stderr
            else -> first.toString()
        }
        if ("INSTALL_FAILED_UPDATE_INCOMPATIBLE" in text) {
            // Signed by a different key (e.g. a dev build): replace it.
            shell("pm uninstall $PACKAGE")
            val second = adb.runText(prefix() + listOf("install", apk.toString()), timeoutMs = 60_000)
            if (second is AdbResult.Success) return
        }
        throw ToolError(
            errorCode = ErrorCode.AdbError,
            message = "Could not install the UI presence service: ${text.trim().take(300)}",
            hint = "Install needs adb install permission on the device. UI events for Compose apps " +
                "stay unavailable without it; ui_start without presence still drives the UI.",
        )
    }

    private fun prefix(): List<String> = listOf("-s", serial)

    private fun shell(cmd: String): AdbResult = adb.runText(prefix() + listOf("shell", cmd), timeoutMs = 10_000)

    private fun getSecure(key: String): String? {
        val out = (shell("settings get secure $key") as? AdbResult.Success)?.stdout?.trim() ?: return null
        return out.takeUnless { it.isEmpty() || it == "null" }
    }

    private fun putSecure(key: String, value: String) {
        val r = shell("settings put secure $key ${quote(value)}")
        if (r !is AdbResult.Success) {
            throw ToolError(ErrorCode.AdbError, "settings put secure $key failed: $r")
        }
    }

    private fun restoreSecure(key: String, prior: String?) {
        if (prior == null) shell("settings delete secure $key") else putSecure(key, prior)
    }

    private fun writeMarker(prior: Prior) {
        val body = "services=${prior.services ?: NULL_TOKEN}\nenabled=${prior.enabled ?: NULL_TOKEN}\n"
        shell("printf %s ${quote(body)} > $MARKER")
    }

    companion object {
        const val PACKAGE = "com.acendas.adui.presence"
        const val COMPONENT = "$PACKAGE/.PresenceService"
        const val MARKER = "/data/local/tmp/ad-ui-presence.prior"
        private const val SERVICES_KEY = "enabled_accessibility_services"
        private const val ENABLED_KEY = "accessibility_enabled"
        private const val NULL_TOKEN = "<null>"
        private const val RESTORE_ATTEMPTS = 8

        /** Append ours to a colon-separated component list without duplicating it. */
        fun mergedServices(prior: String?): String = when {
            prior.isNullOrBlank() -> COMPONENT
            prior.split(':').contains(COMPONENT) -> prior
            else -> "$prior:$COMPONENT"
        }

        fun parseMarker(text: String): Prior? {
            val map = text.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
            if ("services" !in map || "enabled" !in map) return null
            fun v(k: String) = map[k]?.takeUnless { it == NULL_TOKEN || it.isEmpty() }
            // A marker that already contains our own component as the whole prior list is
            // what a double-enable would write; treat it as "no prior".
            return Prior(services = v("services")?.takeUnless { it == COMPONENT }, enabled = v("enabled"))
        }

        /** Single-quote for `adb shell` (which hands the command string to sh). */
        fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
