package com.acendas.androiddebugger.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Where UI events go. Three consumers, one publish:
 *
 *  1. An append-only NDJSON [logFile] — what `ui-follow` tails for Claude Code's Monitor.
 *     Monitor is visibility only; decisions come from [awaitEvent] / tool results.
 *  2. A bounded in-memory ring for `ui_wait` ([awaitEvent]).
 *  3. Optional forwarding into the debugger's event loop (done by [UiSession], opt-in),
 *     never by default: that channel is 128 slots DROP_OLDEST, and a chatty screen must
 *     not evict the `stopped` event an orchestrator is waiting for.
 *
 * Every event gets a monotonically increasing `seq`. Input tools return the seq at the
 * moment they acted, and `ui_wait` defaults to "after the last action", so a screen
 * change that lands before the wait call is never missed.
 */
class UiEventHub(val logFile: Path, private val ringSize: Int = 500) {

    private val ring = ArrayDeque<JsonObject>()
    private val lastSeq = MutableStateFlow(0L)
    private var writer: BufferedWriter? = null

    init {
        Files.createDirectories(logFile.parent)
        writer = Files.newBufferedWriter(
            logFile, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    val seq: Long get() = lastSeq.value

    /** Stamp, log, ring, notify. Returns the stamped event. */
    @Synchronized
    fun publish(event: JsonObject): JsonObject {
        val next = lastSeq.value + 1
        val stamped = buildJsonObject {
            put("seq", next)
            if ("wall_ms" !in event) put("wall_ms", System.currentTimeMillis())
            for ((k, v) in event) put(k, v)
        }
        ring.addLast(stamped)
        while (ring.size > ringSize) ring.removeFirst()
        runCatching {
            writer?.apply {
                write(stamped.toString())
                newLine()
                flush()
            }
        }
        lastSeq.value = next
        return stamped
    }

    @Synchronized
    private fun firstMatch(afterSeq: Long, predicate: (JsonObject) -> Boolean): JsonObject? =
        ring.firstOrNull { (it["seq"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { s -> s > afterSeq } == true && predicate(it) }

    /** Wait for the first event with seq > [afterSeq] matching [predicate], up to [timeoutMs]. */
    suspend fun awaitEvent(afterSeq: Long, timeoutMs: Long, predicate: (JsonObject) -> Boolean): JsonObject? =
        withTimeoutOrNull(timeoutMs) {
            var match: JsonObject? = null
            while (match == null) {
                // Read the high-water mark BEFORE scanning, so an event published during
                // the scan still wakes the wait below instead of being slept through.
                val seen = lastSeq.value
                match = firstMatch(afterSeq, predicate)
                if (match == null) lastSeq.first { it > seen }
            }
            match
        }

    @Synchronized
    fun recent(limit: Int): List<JsonObject> = ring.toList().takeLast(limit)

    @Synchronized
    fun close() {
        runCatching { writer?.close() }
        writer = null
    }
}
