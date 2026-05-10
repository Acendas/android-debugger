package com.acendas.androiddebugger.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Translated, agent-facing view of a JDI [com.sun.jdi.event.Event]. The [EventLoop]
 * pumps the JDI event queue, demultiplexes it into one of these, and pushes them onto
 * the session's `Channel<DebugEvent>` for `wait_for_event` to consume.
 *
 * Per Task 4.1.3.2.
 */
sealed class DebugEvent {

    /** Stable string the agent matches on (`types` filter in `wait_for_event`). */
    abstract val type: String

    /** Render to MCP JSON shape. */
    abstract fun toJson(): JsonObject

    /** A thread paused: breakpoint, step, manual pause, etc. */
    data class Stopped(
        val reason: String,
        val threadId: Long?,
        val threadName: String?,
        val breakpointId: Int? = null,
        val location: String? = null,
    ) : DebugEvent() {
        override val type: String = "stopped"
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            put("reason", reason)
            threadId?.let { put("thread_id", it) }
            threadName?.let { put("thread_name", it) }
            breakpointId?.let { put("breakpoint_id", it) }
            location?.let { put("location", it) }
        }
    }

    /** An exception fired (caught or uncaught, depending on the request). */
    data class Exception(
        val exceptionId: String?,
        val exceptionClass: String?,
        val threadId: Long?,
        val caught: Boolean,
    ) : DebugEvent() {
        override val type: String = "exception"
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            exceptionId?.let { put("exception_id", it) }
            exceptionClass?.let { put("exception_class", it) }
            threadId?.let { put("thread_id", it) }
            put("caught", caught)
        }
    }

    /** The target VM exited normally. */
    data class Exit(val code: Int) : DebugEvent() {
        override val type: String = "exit"
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            put("code", code)
        }
    }

    /** A class was prepared (loaded). Surfaced for deferred breakpoint resolution. */
    data class ClassPrepare(val className: String) : DebugEvent() {
        override val type: String = "class_prepare"
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            put("class_name", className)
        }
    }

    /** The JDWP transport closed (USB unplug, app killed, dispose). */
    data object Disconnect : DebugEvent() {
        override val type: String = "disconnect"
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
        }
    }

    /**
     * Synthetic non-JDI signal the server emits when something is sketchy but didn't
     * actually pause the VM — e.g., the main thread has been suspended too long and the
     * device is at risk of an ANR. Per Story 7.1.3.
     *
     * `severity`: `"warning"` for soft thresholds, `"critical"` for escalations.
     * `warningType`: identifier the agent matches on (e.g., `"anr_risk"`).
     * `extra`: free-form key/value pairs (`suspended_ms`, `thread_name`, etc.).
     */
    data class Warning(
        val warningType: String,
        val severity: String,
        val extra: Map<String, Long> = emptyMap(),
    ) : DebugEvent() {
        override val type: String = "warning"
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            put("warning_type", warningType)
            put("severity", severity)
            for ((k, v) in extra) put(k, v)
        }
    }
}
