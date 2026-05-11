package com.acendas.androiddebugger.jvmti

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.PluginRoot
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.adb.Adb
import com.acendas.androiddebugger.adb.AdbResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/**
 * Orchestrates loading the JVMTI agent into a target Android app. Per v1.4 plan
 * Phase C — six stories:
 *   - C.1.1 detect device ABI
 *   - C.1.2 push agent `.so` with SELinux fallback
 *   - C.1.3 Studio Apply Changes conflict probe
 *   - C.1.4 invoke `cmd activity attach-agent` with query-string options
 *   - C.1.5 wait for the abstract socket + JSON-RPC handshake
 *   - C.1.6 (implicit) record state on [Session.agentState]
 *
 * Used by the `attach` MCP tool when `load_agent: true` (the default per D10).
 */
object JvmtiAgentLauncher {

    private const val PROTOCOL_VERSION = 1
    private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64", "armeabi-v7a")

    /** Studio's Apply Changes agent leaves these recognizable patterns in `/proc/<pid>/maps`. */
    private val STUDIO_AGENT_PATTERNS = listOf(
        "studio_profiler",
        "apply_changes_agent",
        "libjvmtiagent",
        "libperfa",
        "libdex2oat_jit",
    )

    private val log = org.slf4j.LoggerFactory.getLogger("android-debugger.jvmti")

    /**
     * End-to-end launch. Throws [ToolError] on any unrecoverable failure;
     * caller is expected to route the failure into the attach response's
     * `warnings` array rather than letting it abort the attach (per E.1.2 —
     * the agent is additive; JDI works without it).
     */
    suspend fun launch(
        adb: Adb,
        serial: String,
        pid: Int,
        packageName: String,
        verbose: Boolean = false,
    ): AgentState {
        val abi = detectAbi(adb, serial)
        if (probeForStudioAgent(adb, serial, pid, packageName)) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "agent_conflict: Android Studio's Apply Changes agent appears to be loaded in this process.",
                hint = "Detach Studio first (Run → Stop debugger), then re-attach with android-debugger.",
            )
        }

        // Fast path: if the agent is already loaded in this process (e.g., this is
        // a re-attach within the same app process lifetime), the abstract socket
        // is already bound. Skipping `cmd activity attach-agent` avoids re-invoking
        // Agent_OnAttach (which is a no-op but can interact with adb's forward
        // state in surprising ways under back-to-back cycles). We probe via the
        // device's /proc/net/unix for our abstract namespace.
        val agentPath: String
        val priorCrash: AgentCrashRecord?
        if (isAbstractSocketBound(adb, serial, packageName)) {
            log.info(
                "JVMTI agent already loaded in pid={}; skipping attach-agent and reusing the existing listener",
                pid,
            )
            agentPath = "/data/local/tmp/libamdb_agent.so (already loaded)"
            priorCrash = null
        } else {
            agentPath = pushAgent(adb, serial, abi, packageName)
            // Best-effort: pick up any crash-marker file from a prior session BEFORE
            // we attach (the new agent may overwrite or the file may get cleared by
            // the app). Read happens via run-as so we have the app's UID.
            priorCrash = readCrashMarkerIfPresent(adb, serial, packageName)
            attachAgent(adb, serial, pid, agentPath, packageName, verbose)
        }
        val transport = waitForAgent(adb, serial, packageName)

        val state = AgentState(
            protocolVersion = transport.protocolVersion,
            agentVersion = transport.agentVersion,
            capabilities = transport.capabilities,
            attachPath = agentPath,
            hostSocketPath = transport.hostSocketPath,
            abstractNamespace = abstractName(packageName),
            attachPid = transport.attachPid,
            attachedAt = Instant.now(),
            crashedLastSession = priorCrash,
        )
        Session.agentState = state
        Session.agentClient = transport.client
        log.info(
            "JVMTI agent v${state.agentVersion} attached to {} (pid={}, abi={})",
            packageName, pid, abi,
        )
        return state
    }

    // ---------------- C.1.1 detect ABI ----------------

    fun detectAbi(adb: Adb, serial: String): String {
        val raw = runCatching {
            adbText(adb,if (serial.isNotBlank()) listOf("-s", serial, "shell", "getprop", "ro.product.cpu.abi")
            else listOf("shell", "getprop", "ro.product.cpu.abi"))
        }.getOrElse {
            throw ToolError(
                errorCode = ErrorCode.AdbError,
                message = "Failed to detect device ABI: ${it.message}",
                hint = "Is the device still connected? Check `adb devices`.",
            )
        }
        val abi = raw.trim()
        if (abi !in SUPPORTED_ABIS) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Unsupported device ABI: `$abi`. Supported: ${SUPPORTED_ABIS.joinToString(", ")}.",
                hint = "The agent has no .so for this ABI; HotSwap and JVMTI features unavailable on this device.",
            )
        }
        return abi
    }

    // ---------------- C.1.2 push agent ----------------

    fun pushAgent(adb: Adb, serial: String, abi: String, packageName: String): String {
        val pluginRoot = PluginRoot.require()
        val localPath = pluginRoot.resolve("dist").resolve("agents").resolve(abi).resolve("libamdb_agent.so")
        if (!Files.exists(localPath)) {
            throw ToolError(
                errorCode = ErrorCode.Internal,
                message = "Agent .so not found at $localPath.",
                hint = "Rebuild with `./agent/build.sh` and re-run.",
            )
        }
        val deviceTmpPath = "/data/local/tmp/libamdb_agent.so"
        runCatching {
            adbText(adb,prefixSerial(serial) + listOf("push", localPath.toString(), deviceTmpPath))
        }.onFailure {
            throw ToolError(
                errorCode = ErrorCode.AdbError,
                message = "Failed to push agent .so to /data/local/tmp/: ${it.message}",
            )
        }

        // Probe whether the app's SELinux context can read /data/local/tmp.
        //
        // adb shell flattens its argv with spaces before running on the device, which
        // means `sh -c <cmd-with-spaces>` is parsed wrong — `-c` only consumes the next
        // word. We work around it by sending the inner command as a SINGLE pre-quoted
        // argv element: `sh -c 'cmd && cmd2'` — the single quotes survive the flatten
        // and the on-device shell re-parses correctly.
        val canReadTmp = runCatching {
            val out = adbText(adb,
                prefixSerial(serial) + listOf(
                    "shell", "run-as", packageName,
                    "sh", "-c", "'head -c 4 $deviceTmpPath > /dev/null 2>&1 && echo OK'"
                )
            )
            "OK" in out
        }.getOrDefault(false)

        if (canReadTmp) {
            return deviceTmpPath
        }

        // Fallback: copy to /data/data/<package>/code_cache/ via run-as.
        val fallbackDir = "/data/data/$packageName/code_cache"
        val fallbackPath = "$fallbackDir/libamdb_agent.so"
        val fallbackResult = runCatching {
            adbText(adb,
                prefixSerial(serial) + listOf(
                    "shell", "run-as", packageName,
                    "sh", "-c", "'mkdir -p $fallbackDir && cp $deviceTmpPath $fallbackPath && chmod 0700 $fallbackPath && echo OK'"
                )
            )
        }.getOrElse { e ->
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "agent_push_failed: both /data/local/tmp/ and run-as fallback failed.",
                hint = "Last error: ${e.message}. Confirm the app is built debuggable.",
            )
        }
        if ("OK" !in fallbackResult) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "agent_push_failed: run-as fallback didn't produce the expected OK.",
                hint = "Output: $fallbackResult",
            )
        }
        return fallbackPath
    }

    // ---------------- C.1.3 Studio conflict probe ----------------

    fun probeForStudioAgent(adb: Adb, serial: String, pid: Int, packageName: String): Boolean {
        val maps = runCatching {
            adbText(adb,
                prefixSerial(serial) + listOf(
                    "shell", "run-as", packageName, "cat", "/proc/$pid/maps"
                )
            )
        }.getOrNull() ?: return false  // can't read maps → can't probe → assume no conflict

        return STUDIO_AGENT_PATTERNS.any { pattern -> pattern in maps }
    }

    // ---------------- C.1.4 attach-agent ----------------

    fun attachAgent(
        adb: Adb,
        serial: String,
        pid: Int,
        agentPath: String,
        packageName: String,
        verbose: Boolean,
    ) {
        val options = buildString {
            append("package=").append(packageName)
            append(",verbose=").append(if (verbose) "1" else "0")
            append(",version=").append(PROTOCOL_VERSION)
        }
        val arg = "$agentPath=$options"
        val cmd = prefixSerial(serial) + listOf("shell", "cmd", "activity", "attach-agent", pid.toString(), arg)
        val out = runCatching { adbText(adb,cmd) }.getOrElse {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "agent_attach_failed: ${it.message}",
                hint = classifyAttachFailure(it.message ?: ""),
            )
        }
        // `cmd activity attach-agent` returns 0 even for some failures (Android quirk).
        // Look for known failure idioms in the output.
        val lower = out.lowercase()
        if ("not debuggable" in lower) {
            throw ToolError(
                errorCode = ErrorCode.AttachFailed,
                message = "agent_attach_failed: target app `$packageName` is not debuggable.",
                hint = "Rebuild as a debug variant (the manifest needs android:debuggable=\"true\").",
            )
        }
        if ("could not load" in lower || "no such file" in lower) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "agent_attach_failed: device couldn't load the agent `.so`.",
                hint = "Output: ${out.trim()}. Try forcing the SELinux fallback (the agent should be at $agentPath).",
            )
        }
    }

    private fun classifyAttachFailure(stderr: String): String {
        val lower = stderr.lowercase()
        return when {
            "not debuggable" in lower -> "Rebuild as a debug variant."
            "could not load" in lower -> "Force the SELinux fallback path."
            "no such process" in lower -> "The target PID is gone; re-list and re-attach."
            else -> "See stderr above."
        }
    }

    // ---------------- C.1.5 wait for socket + handshake ----------------

    /** Carries everything from the handshake the launcher needs to record. */
    data class AgentTransport(
        val client: AgentClient,
        val protocolVersion: Int,
        val agentVersion: String,
        val capabilities: JsonObject,
        val hostSocketPath: Path,
        val attachPid: Int,
    )

    suspend fun waitForAgent(
        adb: Adb,
        serial: String,
        packageName: String,
        timeoutMs: Long = 5_000,
    ): AgentTransport {
        val hostSocketPath = newHostSocketPath()
        val abstractName = abstractName(packageName)

        // Before any new forward, clean up any STALE forwards from prior sessions
        // that target the same abstract socket. Multiple forwards-to-same-target
        // accumulate across detach/re-attach cycles because adb forwards are
        // process-state-of-adb-daemon, not of the plugin process — and
        // Session.detach() only removes the JDWP forward, not the agent forward.
        // Stale routes confuse adb's connection-mapping under load and surface
        // as "agent socket closed during read" mid-handshake.
        removeStaleAgentForwards(adb, serial, abstractName)

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null

        // Poll: try to forward + open + hello until success or timeout.
        while (System.currentTimeMillis() < deadline) {
            try {
                // Remove any stale forward at the host-side path before reseting.
                runCatching {
                    adbText(adb,prefixSerial(serial) + listOf("forward", "--remove", "localfilesystem:$hostSocketPath"))
                }
                // Delete any stale local socket file with the same name.
                runCatching { Files.deleteIfExists(hostSocketPath) }

                adbText(adb,prefixSerial(serial) + listOf(
                    "forward", "localfilesystem:$hostSocketPath", "localabstract:$abstractName"
                ))

                // Wait for the socket file to appear (adb creates it lazily).
                var attempts = 0
                while (!Files.exists(hostSocketPath) && attempts < 25) {
                    delay(50)
                    attempts++
                }
                if (!Files.exists(hostSocketPath)) {
                    lastError = IllegalStateException("adb forward did not create host socket within 1.25s")
                    delay(200)
                    continue
                }

                // The reconnect lambda (D.1.2 reconnect path) recreates the
                // forward + a fresh AgentClient. Mirrors this initial setup.
                val reconnect: suspend () -> AgentClient = {
                    waitForAgent(adb, serial, packageName, timeoutMs = 2_000).client
                }
                val client = AgentClient(hostSocketPath, reconnect)
                client.ensureOpen()
                val helloResult = try {
                    client.hello(PROTOCOL_VERSION)
                } catch (e: AgentRpcError) {
                    client.close()
                    if (e.rpcMessage.contains("agent_version_mismatch")) {
                        throw ToolError(
                            errorCode = ErrorCode.InvalidTarget,
                            message = "agent_version_mismatch: agent reports protocol mismatch.",
                            hint = "Force-stop the app to unload the existing agent; the next attach will load v$PROTOCOL_VERSION.",
                            currentState = e.data?.get("agent_protocol_version")?.toString(),
                        )
                    }
                    if (e.rpcMessage.contains("agent_in_use")) {
                        throw ToolError(
                            errorCode = ErrorCode.InvalidTarget,
                            message = "agent_in_use: another debugger session is already attached to this app.",
                            hint = "Detach the other session first, or force-stop the app to clear the agent.",
                        )
                    }
                    throw ToolError(
                        errorCode = ErrorCode.Internal,
                        message = "agent_handshake_failed: ${e.rpcMessage}",
                    )
                }

                val agentVersion = helloResult["agent_version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val protocolVersion = helloResult["protocol_version"]?.jsonPrimitive?.intOrNull ?: 0

                // Fetch agent_info_raw for capabilities + pid.
                val info = client.request("agent_info_raw")
                val infoObj = info as? JsonObject
                    ?: throw ToolError(ErrorCode.Internal, "agent_info_raw returned non-object")
                val caps = infoObj["capabilities"] as? JsonObject
                    ?: throw ToolError(ErrorCode.Internal, "agent_info_raw missing `capabilities`")
                val attachPid = infoObj["attach_pid"]?.jsonPrimitive?.intOrNull ?: 0

                return AgentTransport(
                    client = client,
                    protocolVersion = protocolVersion,
                    agentVersion = agentVersion,
                    capabilities = caps,
                    hostSocketPath = hostSocketPath,
                    attachPid = attachPid,
                )
            } catch (te: ToolError) {
                throw te  // already structured, propagate
            } catch (t: Throwable) {
                lastError = t
                delay(200)
            }
        }
        throw ToolError(
            errorCode = ErrorCode.InvalidTarget,
            message = "agent_handshake_timeout: agent didn't come up within ${timeoutMs} ms.",
            hint = "Last error: ${lastError?.message ?: "unknown"}. Check logcat for `amdb_agent` lines.",
        )
    }

    // ---------------- crash-marker pickup ----------------

    private fun readCrashMarkerIfPresent(adb: Adb, serial: String, packageName: String): AgentCrashRecord? {
        val path = "/data/data/$packageName/cache/amdb_agent_crash.txt"
        // Same single-quoting trick as pushAgent — adb shell flattens its argv,
        // so a multi-word `sh -c` command must be one pre-quoted arg.
        val raw = runCatching {
            adbText(adb,
                prefixSerial(serial) + listOf(
                    "shell", "run-as", packageName, "sh", "-c",
                    "'test -f $path && cat $path && rm -f $path'"
                )
            )
        }.getOrNull() ?: return null
        if (raw.isBlank()) return null
        return AgentCrashRecord.parse(raw)
    }

    // ---------------- helpers ----------------

    /** AdbResult → String, throws on non-Success. Used everywhere we need stdout. */
    private fun adbText(adb: Adb, args: List<String>, timeoutMs: Long = 10_000): String {
        return when (val r = adb.runText(args, timeoutMs)) {
            is AdbResult.Success -> r.stdout
            is AdbResult.Error -> throw ToolError(
                errorCode = ErrorCode.AdbError,
                message = "adb command failed (exit ${r.exitCode}): ${r.stderr.take(300)}",
                hint = "Command: ${r.command.joinToString(" ")}",
            )
            is AdbResult.Timeout -> throw ToolError(
                errorCode = ErrorCode.AdbError,
                message = "adb command timed out after $timeoutMs ms",
                hint = "Command: ${r.command.joinToString(" ")}",
            )
            is AdbResult.NotFound -> throw ToolError(
                errorCode = ErrorCode.AdbError,
                message = "adb binary not found: ${r.hint}",
            )
            is AdbResult.LaunchFailed -> throw ToolError(
                errorCode = ErrorCode.AdbError,
                message = "adb launch failed: ${r.cause.message ?: r.cause::class.simpleName}",
            )
        }
    }

    private fun prefixSerial(serial: String): List<String> =
        if (serial.isNotBlank()) listOf("-s", serial) else emptyList()

    private fun abstractName(packageName: String) = "android-debugger-$packageName"

    private fun newHostSocketPath(): Path {
        val dir = Paths.get(System.getProperty("java.io.tmpdir"))
        // Unique per attach to avoid stale-forward races. Use a long random suffix.
        val name = "amdb-agent-${System.nanoTime()}.sock"
        return dir.resolve(name)
    }

    /**
     * Check whether the agent's abstract-namespace socket is already bound on
     * the device. Used as the fast-path test in [launch]: if the agent is
     * already loaded in the target process, we skip `cmd activity attach-agent`
     * entirely and reuse the listener thread.
     *
     * Probe: `adb shell cat /proc/net/unix | grep <abstract-name>`. Abstract
     * sockets show up with `@<name>` in the Path column.
     */
    fun isAbstractSocketBound(adb: Adb, serial: String, packageName: String): Boolean {
        val abstractName = abstractName(packageName)
        val out = runCatching {
            adbText(adb, prefixSerial(serial) + listOf("shell", "cat", "/proc/net/unix"))
        }.getOrElse { return false }
        return out.lineSequence().any { line -> "@$abstractName" in line }
    }

    /**
     * Pre-cleanup before a new agent attach: list every adb forward currently
     * mapped to `localabstract:[abstractName]` and remove them. This unwinds
     * stale routes left by:
     *   - Previous plugin sessions (the plugin process exited; adb daemon
     *     state survived).
     *   - Multiple `attach` → `detach` cycles within one plugin session
     *     ([Session.reset] doesn't remove the agent forward, only the JDWP
     *     forward).
     *   - The app process being killed between sessions (the abstract socket
     *     dies with it, but the host-side forwards persist).
     *
     * Without this, stale forwards confuse adb under back-to-back attach
     * cycles — the new connection routes through a half-broken path and the
     * agent's listener sees EOF mid-handshake.
     */
    fun removeStaleAgentForwards(adb: Adb, serial: String, abstractName: String) {
        val out = runCatching {
            adbText(adb, prefixSerial(serial) + listOf("forward", "--list"))
        }.getOrElse { return }

        // `adb forward --list` lines look like:
        //   <serial> localfilesystem:/path/to/foo.sock localabstract:android-debugger-com.example.app
        for (line in out.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            // Match the abstract namespace target.
            if (!parts[2].endsWith(":$abstractName")) continue
            // The serial column is parts[0]; for multi-device setups it may be absent
            // in some adb versions, so we match defensively on the localfilesystem
            // prefix which is always present.
            val localPath = parts.firstOrNull { it.startsWith("localfilesystem:") } ?: continue
            runCatching {
                adbText(adb, prefixSerial(serial) + listOf("forward", "--remove", localPath))
            }
            // Also delete the dangling host-side socket file if any.
            runCatching {
                val pathStr = localPath.removePrefix("localfilesystem:")
                Files.deleteIfExists(Paths.get(pathStr))
            }
        }
    }
}
