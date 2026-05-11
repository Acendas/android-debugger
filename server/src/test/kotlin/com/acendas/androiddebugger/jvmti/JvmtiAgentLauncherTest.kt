package com.acendas.androiddebugger.jvmti

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.PluginRoot
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.adb.Adb
import com.acendas.androiddebugger.adb.AdbResult
import com.acendas.androiddebugger.adb.CommandRunner
import com.acendas.androiddebugger.adb.StreamHandle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per Phase F.1.1 of the v1.4 plan. Mocks the device-side adb via [FakeRunner];
 * exercises ABI detection, push flow (primary + SELinux fallback), Studio
 * conflict probe, attach-agent command construction, error mapping.
 */
class JvmtiAgentLauncherTest {

    private class FakeRunner : CommandRunner {
        val invocations: MutableList<List<String>> = mutableListOf()
        val results: MutableMap<String, AdbResult> = mutableMapOf()
        var defaultResult: AdbResult = AdbResult.Success("")

        override fun run(args: List<String>, timeoutMs: Long): AdbResult {
            invocations += args
            // Match by the longest registered prefix.
            val joined = args.joinToString(" ")
            val match = results.entries
                .filter { joined.contains(it.key) }
                .maxByOrNull { it.key.length }
            return match?.value ?: defaultResult
        }

        override fun stream(args: List<String>, onLine: (String) -> Unit): StreamHandle {
            invocations += args
            return object : StreamHandle {
                override fun stop() {}
                override val alive: Boolean = false
            }
        }
    }

    private lateinit var fakeRoot: Path

    @BeforeTest
    fun setupPluginRoot() {
        fakeRoot = Files.createTempDirectory("agent-launcher-test-")
        // Mirror the on-disk layout the launcher expects:
        // <root>/.claude-plugin/, <root>/dist/agents/<abi>/libamdb_agent.so
        Files.createDirectories(fakeRoot.resolve(".claude-plugin"))
        for (abi in listOf("arm64-v8a", "x86_64", "armeabi-v7a")) {
            val so = fakeRoot.resolve("dist/agents/$abi/libamdb_agent.so")
            Files.createDirectories(so.parent)
            Files.writeString(so, "fake $abi agent contents\n")
        }
        PluginRoot.pluginRootOverrideForTest = fakeRoot
    }

    @AfterTest
    fun teardown() {
        PluginRoot.pluginRootOverrideForTest = null
        fakeRoot.toFile().deleteRecursively()
    }

    private fun adbWith(setup: FakeRunner.() -> Unit): Adb {
        val runner = FakeRunner().apply(setup)
        return Adb(runner = runner)
    }

    // ---------------- C.1.1 ABI detection ----------------

    @Test
    fun detectAbi_returns_arm64_when_device_is_arm64() {
        val adb = adbWith {
            results["shell getprop ro.product.cpu.abi"] = AdbResult.Success("arm64-v8a\n")
        }
        assertEquals("arm64-v8a", JvmtiAgentLauncher.detectAbi(adb, "S1"))
    }

    @Test
    fun detectAbi_returns_armv7_when_device_is_armv7() {
        val adb = adbWith {
            results["shell getprop ro.product.cpu.abi"] = AdbResult.Success("armeabi-v7a\n")
        }
        assertEquals("armeabi-v7a", JvmtiAgentLauncher.detectAbi(adb, "S1"))
    }

    @Test
    fun detectAbi_returns_x86_64_when_device_is_x86_64() {
        val adb = adbWith {
            results["shell getprop ro.product.cpu.abi"] = AdbResult.Success("x86_64\n")
        }
        assertEquals("x86_64", JvmtiAgentLauncher.detectAbi(adb, "S1"))
    }

    @Test
    fun detectAbi_throws_invalid_target_on_unsupported_abi() {
        val adb = adbWith {
            results["shell getprop ro.product.cpu.abi"] = AdbResult.Success("mips\n")
        }
        val err = assertFailsWith<ToolError> { JvmtiAgentLauncher.detectAbi(adb, "S1") }
        assertEquals(ErrorCode.InvalidTarget, err.errorCode)
        assertTrue(err.message!!.contains("mips"))
    }

    @Test
    fun detectAbi_throws_adb_error_on_command_failure() {
        val adb = adbWith {
            results["shell getprop ro.product.cpu.abi"] = AdbResult.Error(
                exitCode = 1, stdout = "", stderr = "device offline", command = listOf("adb"),
            )
        }
        val err = assertFailsWith<ToolError> { JvmtiAgentLauncher.detectAbi(adb, "S1") }
        assertEquals(ErrorCode.AdbError, err.errorCode)
    }

    // ---------------- C.1.2 push agent ----------------

    @Test
    fun pushAgent_primary_path_wins_when_selinux_allows_tmp_read() {
        val adb = adbWith {
            results["push"] = AdbResult.Success("1 file pushed")
            results["head -c 4"] = AdbResult.Success("OK\n")
        }
        val path = JvmtiAgentLauncher.pushAgent(adb, "S1", "arm64-v8a", "com.x")
        assertEquals("/data/local/tmp/libamdb_agent.so", path)
    }

    @Test
    fun pushAgent_falls_back_to_code_cache_when_tmp_unreadable_by_app() {
        val adb = adbWith {
            results["push"] = AdbResult.Success("ok")
            // SELinux probe fails (no "OK" in output → considered unreadable).
            results["head -c 4"] = AdbResult.Success("")
            results["mkdir -p"] = AdbResult.Success("OK\n")
        }
        val path = JvmtiAgentLauncher.pushAgent(adb, "S1", "arm64-v8a", "com.x")
        assertEquals("/data/data/com.x/code_cache/libamdb_agent.so", path)
    }

    @Test
    fun pushAgent_throws_when_both_paths_fail() {
        val adb = adbWith {
            results["push"] = AdbResult.Success("ok")
            results["head -c 4"] = AdbResult.Success("")
            results["mkdir -p"] = AdbResult.Error(1, "", "Permission denied", emptyList())
        }
        val err = assertFailsWith<ToolError> {
            JvmtiAgentLauncher.pushAgent(adb, "S1", "arm64-v8a", "com.x")
        }
        assertEquals(ErrorCode.InvalidTarget, err.errorCode)
        assertTrue(err.message!!.contains("agent_push_failed"))
    }

    @Test
    fun pushAgent_throws_when_so_missing_locally() {
        // Delete the staged .so to simulate a missing-build scenario.
        Files.delete(fakeRoot.resolve("dist/agents/arm64-v8a/libamdb_agent.so"))
        val adb = adbWith {}
        val err = assertFailsWith<ToolError> {
            JvmtiAgentLauncher.pushAgent(adb, "S1", "arm64-v8a", "com.x")
        }
        assertEquals(ErrorCode.Internal, err.errorCode)
        assertTrue(err.hint!!.contains("agent/build.sh"))
    }

    // ---------------- C.1.3 Studio conflict probe ----------------

    @Test
    fun probeForStudioAgent_returns_true_when_libperfa_in_maps() {
        val adb = adbWith {
            results["cat /proc/14210/maps"] = AdbResult.Success(
                "7f1234abc000-7f1234bcd000 r-xp 00000000 libperfa.so\n",
            )
        }
        val found = JvmtiAgentLauncher.probeForStudioAgent(adb, "S1", 14210, "com.x")
        assertTrue(found)
    }

    @Test
    fun probeForStudioAgent_returns_false_when_no_studio_artifacts() {
        val adb = adbWith {
            results["cat /proc/14210/maps"] = AdbResult.Success(
                "7f1234abc000-7f1234bcd000 r-xp 00000000 libc.so\n" +
                    "7f5678abc000-7f5678bcd000 r-xp 00000000 libart.so\n",
            )
        }
        val found = JvmtiAgentLauncher.probeForStudioAgent(adb, "S1", 14210, "com.x")
        assertFalse(found)
    }

    @Test
    fun probeForStudioAgent_returns_false_when_maps_unreadable() {
        // run-as fails — we can't probe; assume no conflict (don't block attach).
        val adb = adbWith {
            results["cat /proc/14210/maps"] = AdbResult.Error(1, "", "denied", emptyList())
        }
        val found = JvmtiAgentLauncher.probeForStudioAgent(adb, "S1", 14210, "com.x")
        assertFalse(found)
    }

    // ---------------- C.1.4 attach-agent error classification ----------------

    @Test
    fun attachAgent_constructs_query_string_options_correctly() {
        var captured: List<String> = emptyList()
        val runner = object : CommandRunner {
            override fun run(args: List<String>, timeoutMs: Long): AdbResult {
                captured = args
                return AdbResult.Success("")
            }
            override fun stream(args: List<String>, onLine: (String) -> Unit): StreamHandle =
                throw UnsupportedOperationException()
        }
        val adb = Adb(runner = runner)
        JvmtiAgentLauncher.attachAgent(adb, "S1", 14210, "/data/local/tmp/libamdb_agent.so", "com.x.app", verbose = true)
        val argString = captured.last()
        assertTrue("package=com.x.app" in argString, "expected package= in: $argString")
        assertTrue("verbose=1" in argString, "expected verbose=1 in: $argString")
        assertTrue("version=1" in argString, "expected version=1 in: $argString")
        assertTrue(argString.startsWith("/data/local/tmp/libamdb_agent.so="), "expected agentPath= prefix in: $argString")
    }

    @Test
    fun attachAgent_classifies_not_debuggable_error() {
        val adb = adbWith {
            defaultResult = AdbResult.Success("Failure: process com.x.app is not debuggable\n")
        }
        val err = assertFailsWith<ToolError> {
            JvmtiAgentLauncher.attachAgent(adb, "S1", 14210, "/data/local/tmp/libamdb_agent.so", "com.x.app", false)
        }
        assertEquals(ErrorCode.AttachFailed, err.errorCode)
        assertTrue(err.hint!!.contains("debug variant"))
    }

    @Test
    fun attachAgent_classifies_could_not_load_error() {
        val adb = adbWith {
            defaultResult = AdbResult.Success("Could not load agent /data/local/tmp/libamdb_agent.so\n")
        }
        val err = assertFailsWith<ToolError> {
            JvmtiAgentLauncher.attachAgent(adb, "S1", 14210, "/data/local/tmp/libamdb_agent.so", "com.x.app", false)
        }
        assertEquals(ErrorCode.InvalidTarget, err.errorCode)
        // Error mapping rewords the device-side "could not load" into a structured
        // "device couldn't load" message with hint pointing at the SELinux fallback.
        assertTrue(err.message!!.contains("couldn't load"), "got: ${err.message}")
        assertTrue(err.hint!!.contains("SELinux"), "expected SELinux hint, got: ${err.hint}")
    }
}
