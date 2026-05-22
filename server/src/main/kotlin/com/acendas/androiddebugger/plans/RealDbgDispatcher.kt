package com.acendas.androiddebugger.plans

import ca.acendas.kfeel.api.FeelValue
import com.acendas.androiddebugger.Session
import com.sun.jdi.ThreadReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/**
 * Production [DebuggerContext.DbgDispatcher] backed by the live debug session.
 *
 * Every method here reads from the attached VM (or JVMTI agent, where preferred) and
 * never mutates. This is the runtime counterpart to [PlanCompiler]'s syntax-only
 * dispatcher: where the compiler returns canned nulls just to keep the FEEL parser
 * happy, this implementation actually consults [Session.vm] / [Session.agentClient] /
 * [Session.capabilities] for live data.
 *
 * **Gracefully degraded**: every probe wraps in try-catch and returns [FeelValue.Null]
 * (or `0` / `""` where the FEEL grammar can't tolerate a null) rather than throwing.
 * The plan executor records [DebuggerContext.Rewrite.errors] separately; throwing here
 * would just blow up the whole expression for no extra signal.
 *
 * @param planStartMs `System.currentTimeMillis()` captured by [PlanExecutor.launch] —
 *   drives `dbg.elapsed_ms()`.
 */
class RealDbgDispatcher(private val planStartMs: Long) : DebuggerContext.DbgDispatcher {

    override fun instanceCount(classSignature: String): FeelValue {
        // Prefer the JVMTI agent route. Native heap walk is cheaper than JDI's
        // ObjectReference.instances() especially on a phone heap.
        val client = Session.agentClient
        val caps = Session.agentState?.capabilities
        val canTag = caps?.get("can_tag_objects")?.jsonPrimitive?.booleanOrNull == true
        if (client != null && canTag) {
            val agentResult = runCatching {
                runBlocking {
                    withTimeoutOrNull(5_000) {
                        com.acendas.androiddebugger.jvmti.AgentHeap.countInstances(
                            client = client,
                            classSignature = classSignature,
                            consistency = "weak",
                        )
                    }
                }
            }.getOrNull()
            if (agentResult != null) {
                return FeelValue.Number(BigDecimal.valueOf(agentResult.count))
            }
        }
        // JDI fallback. classesByName takes a dotted JVM name (com.example.Foo) — accept
        // either that or a JVMTI-style signature (Lcom/example/Foo;) by normalizing.
        val vm = Session.vm ?: return FeelValue.Null
        val name = normalizeClassSignature(classSignature)
        val count = runCatching {
            val refs = vm.classesByName(name)
            refs.firstOrNull()?.instances(0L)?.size?.toLong() ?: 0L
        }.getOrNull() ?: return FeelValue.Null
        return FeelValue.Number(BigDecimal.valueOf(count))
    }

    override fun isReachable(ref: String, rootKind: String?): FeelValue {
        // v1.7: the agent surface for direct is_reachable doesn't exist yet; the plan
        // executor can call find_referrer_chain to approximate, but at the dispatcher
        // layer we'd rather return null than emit a long-running probe that risks
        // blowing the plan's event budget. Document as degraded; revisit when the
        // agent ships a dedicated agent.is_reachable RPC.
        return FeelValue.Null
    }

    override fun threadState(threadKey: String): FeelValue {
        val vm = Session.vm ?: return FeelValue.Null
        val thread = resolveThread(threadKey) ?: return FeelValue.Null
        val state = runCatching { threadStatusName(thread) }.getOrNull() ?: return FeelValue.Null
        return FeelValue.Text(state)
    }

    override fun frameCount(threadKey: String?): FeelValue {
        val thread = if (threadKey == null) Session.pausedThread
        else resolveThread(threadKey)
        thread ?: return FeelValue.Null
        val n = runCatching { thread.frameCount().toLong() }.getOrNull() ?: return FeelValue.Null
        return FeelValue.Number(BigDecimal.valueOf(n))
    }

    override fun hasCapability(capName: String): FeelValue {
        val caps = Session.capabilities ?: return FeelValue.Null
        val entry = caps[capName] ?: return FeelValue.Null
        return when (entry) {
            is JsonPrimitive -> entry.booleanOrNull?.let { FeelValue.Boolean(it) }
                ?: FeelValue.Null
            else -> FeelValue.Null
        }
    }

    override fun elapsedMs(): FeelValue {
        val elapsed = System.currentTimeMillis() - planStartMs
        return FeelValue.Number(BigDecimal.valueOf(elapsed))
    }

    override fun logcatSince(since: String, filter: String?): FeelValue {
        // Pick the most-recently-created buffer if multiple exist; the agent's typical
        // flow is "tail one logcat for the duration of the plan" so this is fine.
        val snapshots = runCatching { Session.logcatBuffers.list() }.getOrDefault(emptyList())
        if (snapshots.isEmpty()) return FeelValue.Text("")
        // Build a flat string of the last 50 matching lines.
        val maxLines = 50
        val needle = filter
        val joined = snapshots
            .asSequence()
            .flatMap { snap ->
                val buf = Session.logcatBuffers.get(snap.bufferId) ?: return@flatMap emptySequence()
                buf.readSince(0L).asSequence()
            }
            .let { seq -> if (needle == null) seq else seq.filter { it.message.contains(needle) || it.tag.contains(needle) } }
            .toList()
            .takeLast(maxLines)
            .joinToString("\n") { "${it.ts} ${it.level}/${it.tag}: ${it.message}" }
        return FeelValue.Text(joined)
    }

    // ---------------- helpers ----------------

    private fun resolveThread(key: String): ThreadReference? {
        val vm = Session.vm ?: return null
        val all = runCatching { vm.allThreads() }.getOrDefault(emptyList())
        // Numeric match against uniqueID.
        val asLong = key.toLongOrNull()
        if (asLong != null) {
            return all.firstOrNull { runCatching { it.uniqueID() == asLong }.getOrDefault(false) }
        }
        // Otherwise name match.
        return all.firstOrNull { runCatching { it.name() == key }.getOrDefault(false) }
    }

    private fun threadStatusName(thread: ThreadReference): String = when (thread.status()) {
        ThreadReference.THREAD_STATUS_UNKNOWN -> "UNKNOWN"
        ThreadReference.THREAD_STATUS_ZOMBIE -> "ZOMBIE"
        ThreadReference.THREAD_STATUS_RUNNING -> "RUNNING"
        ThreadReference.THREAD_STATUS_SLEEPING -> "SLEEPING"
        ThreadReference.THREAD_STATUS_MONITOR -> "MONITOR"
        ThreadReference.THREAD_STATUS_WAIT -> "WAIT"
        ThreadReference.THREAD_STATUS_NOT_STARTED -> "NOT_STARTED"
        else -> "UNKNOWN"
    }

    private fun normalizeClassSignature(input: String): String {
        // Accept both JVMTI sig (Lcom/example/Foo;) and dotted JVM name
        // (com.example.Foo). JDI's classesByName wants the dotted form.
        val trimmed = input.trim()
        if (trimmed.startsWith("L") && trimmed.endsWith(";") && trimmed.length > 2) {
            return trimmed.substring(1, trimmed.length - 1).replace('/', '.')
        }
        return trimmed
    }
}
