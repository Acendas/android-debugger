package com.acendas.androiddebugger.ui

import com.acendas.androiddebugger.adb.Adb
import com.acendas.androiddebugger.adb.AdbResult
import com.acendas.androiddebugger.adb.CommandRunner
import com.acendas.androiddebugger.adb.StreamHandle
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresenceTest {

    /** Fake device: a secure-settings map plus a marker file, driven by the shell strings. */
    private class FakeDevice(
        val settings: MutableMap<String, String> = mutableMapOf(),
        var marker: String? = null,
    ) : CommandRunner {
        val commands = mutableListOf<String>()
        var installed = false
        /** Mimic AccessibilityManagerService re-writing accessibility_enabled=1 after a service-list change. */
        var amsRewritesLeft = 0

        override fun run(args: List<String>, timeoutMs: Long): AdbResult {
            if (args.getOrNull(2) == "install") {
                installed = true
                return AdbResult.Success("Success")
            }
            val cmd = args.drop(3).joinToString(" ")
            commands += cmd
            fun unquote(s: String) = s.removePrefix("'").removeSuffix("'").replace("'\\''", "'")
            return when {
                cmd.startsWith("settings get secure ") -> {
                    if (amsRewritesLeft > 0 && cmd.endsWith("accessibility_enabled")) {
                        amsRewritesLeft--
                        settings["accessibility_enabled"] = "1"
                    }
                    AdbResult.Success(settings[cmd.removePrefix("settings get secure ")] ?: "null")
                }
                cmd.startsWith("settings put secure ") -> {
                    val rest = cmd.removePrefix("settings put secure ")
                    val key = rest.substringBefore(' ')
                    settings[key] = unquote(rest.substringAfter(' '))
                    AdbResult.Success("")
                }
                cmd.startsWith("settings delete secure ") -> {
                    settings.remove(cmd.removePrefix("settings delete secure "))
                    AdbResult.Success("Deleted 1 rows")
                }
                cmd.startsWith("printf %s ") -> {
                    marker = unquote(cmd.removePrefix("printf %s ").substringBefore(" > "))
                    AdbResult.Success("")
                }
                cmd.startsWith("cat ") -> marker?.let { AdbResult.Success(it) }
                    ?: AdbResult.Error(1, "", "No such file", args)
                cmd.startsWith("rm -f ") -> { marker = null; AdbResult.Success("") }
                cmd.startsWith("pm uninstall") -> { installed = false; AdbResult.Success("Success") }
                else -> AdbResult.Success("")
            }
        }

        override fun stream(args: List<String>, onLine: (String) -> Unit): StreamHandle =
            error("not used")
    }

    private val apk = Paths.get("ad-ui-presence.apk")
    private val talkback = "com.google.android.marvin.talkback/.TalkBackService"

    @Test
    fun `enable appends to an existing service and disable restores it exactly`() {
        val dev = FakeDevice(mutableMapOf("enabled_accessibility_services" to talkback, "accessibility_enabled" to "1"))
        val p = Presence(Adb(dev), "S1", settleDelayMs = 0)
        val prior = p.enable(apk)
        assertEquals("$talkback:${Presence.COMPONENT}", dev.settings["enabled_accessibility_services"])
        assertTrue(dev.installed)
        p.disable(prior)
        assertEquals(talkback, dev.settings["enabled_accessibility_services"], "TalkBack must survive")
        assertEquals("1", dev.settings["accessibility_enabled"])
        assertFalse(dev.installed)
        assertNull(dev.marker)
    }

    @Test
    fun `values that did not exist are deleted, not set to empty`() {
        val dev = FakeDevice()
        val p = Presence(Adb(dev), "S1", settleDelayMs = 0)
        val prior = p.enable(apk)
        assertEquals(Presence.COMPONENT, dev.settings["enabled_accessibility_services"])
        assertEquals("1", dev.settings["accessibility_enabled"])
        p.disable(prior)
        assertTrue(dev.settings.isEmpty(), "back to no values at all: ${dev.settings}")
    }

    @Test
    fun `stale marker from a killed server is repaired before enabling`() {
        // Previous run: prior was TalkBack; server died with ours still appended.
        val dev = FakeDevice(
            settings = mutableMapOf(
                "enabled_accessibility_services" to "$talkback:${Presence.COMPONENT}",
                "accessibility_enabled" to "1",
            ),
            marker = "services=$talkback\nenabled=1\n",
        )
        val p = Presence(Adb(dev), "S1", settleDelayMs = 0)
        assertTrue(p.repairIfStale())
        assertEquals(talkback, dev.settings["enabled_accessibility_services"])
        assertNull(dev.marker)
        assertFalse(p.repairIfStale(), "nothing left to repair")
    }

    @Test
    fun `enable after a crash records the true prior, not our leftover`() {
        val dev = FakeDevice(
            settings = mutableMapOf("enabled_accessibility_services" to "$talkback:${Presence.COMPONENT}"),
            marker = "services=$talkback\nenabled=<null>\n",
        )
        val p = Presence(Adb(dev), "S1", settleDelayMs = 0)
        val prior = p.enable(apk)
        assertEquals(talkback, prior.services)
        assertNull(prior.enabled)
    }

    @Test
    fun `restore retries until the platform stops rewriting accessibility_enabled`() {
        // Seen on a real API 26 device: after ui_stop, AMS wrote accessibility_enabled=1
        // over our restored 0 while the UiAutomation session was still unregistering.
        val dev = FakeDevice(mutableMapOf("accessibility_enabled" to "0"))
        val p = Presence(Adb(dev), "S1", settleDelayMs = 0)
        val prior = p.enable(apk)
        dev.amsRewritesLeft = 2
        p.disable(prior)
        assertEquals("0", dev.settings["accessibility_enabled"])
        assertNull(dev.settings["enabled_accessibility_services"])
    }

    @Test
    fun `mergedServices does not duplicate`() {
        assertEquals(Presence.COMPONENT, Presence.mergedServices(null))
        assertEquals("a/.B:${Presence.COMPONENT}", Presence.mergedServices("a/.B"))
        assertEquals("a/.B:${Presence.COMPONENT}", Presence.mergedServices("a/.B:${Presence.COMPONENT}"))
    }

    @Test
    fun `quote survives single quotes`() {
        assertEquals("'it'\\''s'", Presence.quote("it's"))
    }
}
