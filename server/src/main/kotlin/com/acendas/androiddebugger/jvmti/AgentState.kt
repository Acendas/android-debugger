package com.acendas.androiddebugger.jvmti

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.time.Instant

/**
 * What we know about the loaded JVMTI agent. Lives at [com.acendas.androiddebugger.Session.agentState].
 * `null` means no agent currently loaded for the active session — either because the user passed
 * `load_agent: false` on attach, or the agent load failed (and the failure landed in the
 * `attach` response's `warnings` array rather than `error`).
 *
 * Per v1.4 plan Phase C.
 */
data class AgentState(
    /** Wire-protocol version negotiated with the agent (currently 1). */
    val protocolVersion: Int,
    /** Agent build version (e.g., "1.4.0"). */
    val agentVersion: String,
    /** Capability map returned by the agent's `agent_info_raw` RPC. JSON object. */
    val capabilities: JsonObject,
    /** Device-side path the agent was loaded from. */
    val attachPath: String,
    /** Local Unix-domain socket path forwarded by adb to the device's abstract namespace. */
    val hostSocketPath: Path,
    /** Abstract-namespace socket name on the device (without the leading '@'). */
    val abstractNamespace: String,
    /** PID of the target app process the agent is attached to. */
    val attachPid: Int,
    /** Wall-clock time the Kotlin side observed the handshake succeed. */
    val attachedAt: Instant,
    /** Crash-marker file picked up from a prior session (if any). */
    val crashedLastSession: AgentCrashRecord? = null,
)

/**
 * Contents of `/data/data/<package>/cache/amdb_agent_crash.txt` written by the
 * agent's signal handler. Parsed line-by-line; missing keys → null fields.
 */
data class AgentCrashRecord(
    val signal: String?,
    val pc: String?,
    val siAddr: String?,
    val pid: Int?,
    val tid: Int?,
    val lastRpcMethod: String?,
    val agentVersion: String?,
    val whenUnix: Long?,
    /** Raw file contents, for verbatim diagnostic output. */
    val raw: String,
) {

    companion object {
        /** Parse the agent's crash-file `key=value\n` format. */
        fun parse(raw: String): AgentCrashRecord {
            val kv = mutableMapOf<String, String>()
            for (line in raw.lineSequence()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                kv[line.substring(0, eq)] = line.substring(eq + 1)
            }
            return AgentCrashRecord(
                signal = kv["signal"],
                pc = kv["pc"],
                siAddr = kv["si_addr"],
                pid = kv["pid"]?.toIntOrNull(),
                tid = kv["tid"]?.toIntOrNull(),
                lastRpcMethod = kv["last_rpc_method"],
                agentVersion = kv["agent_version"],
                whenUnix = kv["when_unix"]?.toLongOrNull(),
                raw = raw,
            )
        }
    }
}
