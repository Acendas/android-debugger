package com.acendas.androiddebugger.inspection

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ArrayReference
import com.sun.jdi.ClassType
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per BR-01 (v1.1.1 skill craft review): centralized root-cause builder for a paused
 * exception object. Combines what `inspect_object` returns (message + cause chain +
 * stack-trace via reflection) with frame analysis to identify both the **throw site**
 * (the topmost frame on the stack-trace array) and the **trigger frame** (the first
 * user-code frame, skipping framework/<init>/synthetic boilerplate).
 *
 * Replaces the inline assembly that lived in `:catch` step 6 and the orchestrator's
 * crash loop. Skill bodies and the agent are no longer responsible for getting the
 * "user-code" definition right; that lives in [FrameworkFrames] now.
 *
 * The exception object is the standard `java.lang.Throwable`-shaped JDI mirror:
 *   - `detailMessage: String` (renamed `message` in agent-facing JSON)
 *   - `cause: Throwable` (linked list — we walk to a sane depth)
 *   - `stackTrace: StackTraceElement[]` populated by JVM at throw-time
 *
 * On ART the stack-trace array is populated lazily via `Throwable.getStackTrace()`,
 * which we *don't* invoke here — calling it through `invokeMethod` would resume the
 * VM and could deadlock. Instead we read the `stackTrace` field directly. If it's
 * `null` (rare; happens on freshly-thrown exceptions before fillInStackTrace runs),
 * the summary returns an empty stack with `stack_trace_unavailable: true`.
 */
object ExceptionSummary {

    /** Cap the cause-chain walk so a self-referential cause never spins forever. */
    private const val MAX_CAUSE_DEPTH: Int = 16

    /** Cap the stack-trace dump so a 500-frame StackOverflowError doesn't blow the payload. */
    private const val MAX_STACK_FRAMES: Int = 10

    /**
     * Build the structured summary for [exception]. The optional [pausedThread] is
     * used to read live JDI frames as a complement to the throwable's frozen
     * stack-trace array — useful when the agent wants the **trigger frame** to be
     * the actual paused location (where the exception was caught), not the throw
     * site. When `null` we synthesize the trigger frame from the same stack-trace
     * array as the throw site.
     */
    fun build(exception: ObjectReference, pausedThread: ThreadReference? = null): JsonObject = buildJsonObject {
        val excClassName = runCatching { exception.referenceType().name() }.getOrNull() ?: "?"
        put("ok", true)
        put("exception_class", excClassName)

        val message = readMessage(exception)
        if (message != null) put("message", message)

        // Stack trace from the Throwable's frozen array.
        val frames = readStackTrace(exception)
        val throwSite = frames.firstOrNull()
        if (throwSite != null) {
            put("throw_site", throwSite.toJson())
        }

        val triggerFrame = pickTriggerFrame(frames, pausedThread)
        if (triggerFrame != null) {
            put("trigger_frame", triggerFrame.toJson())
        }

        // Cause chain (Throwable.cause walked iteratively).
        val causeChain = readCauseChain(exception)
        if (causeChain.isNotEmpty()) {
            put("cause_chain", buildJsonArray {
                for (c in causeChain) {
                    add(buildJsonObject {
                        put("class", c.className)
                        c.message?.let { put("message", it) }
                    })
                }
            })
        }

        // Stack summary — top-N rendered frames so the agent has context without us
        // dumping a 500-element array.
        if (frames.isEmpty()) {
            put("stack_trace_unavailable", true)
        } else {
            put("stack_summary", buildJsonArray {
                for (f in frames.take(MAX_STACK_FRAMES)) add(f.toJson())
            })
        }
    }

    /**
     * Resolve [exception] from a `obj#` ref-id and dispatch to [build]. Returns `null`
     * if the ref isn't an object reference at all (caller maps to `invalid_target`).
     */
    fun fromRef(refId: String, pausedThread: ThreadReference?): JsonObject? {
        val obj = ObjectIdMint.resolveObject(refId) ?: return null
        return build(obj, pausedThread)
    }

    // ---------------- internals ----------------

    /** A single rendered stack-trace entry. */
    internal data class FrameEntry(
        val className: String,
        val methodName: String,
        val sourceFile: String?,
        val lineNumber: Int,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("class", className)
            put("method", methodName)
            sourceFile?.let { put("file", it) }
            put("line", lineNumber)
        }
    }

    /** Linked entry in the Throwable.cause chain. */
    internal data class CauseEntry(val className: String, val message: String?)

    private fun readMessage(exception: ObjectReference): String? {
        val type = exception.referenceType()
        // Throwable's message lives in the inherited `detailMessage` field.
        val field = type.fieldByName("detailMessage") ?: return null
        val value = runCatching { exception.getValue(field) }.getOrNull() ?: return null
        return (value as? StringReference)?.value()
    }

    /**
     * Read the cause chain by walking `Throwable.cause` iteratively. Throwables in
     * the JDK convention point `cause` at themselves when no cause is set, which we
     * detect and stop on. We also de-duplicate via a visited set to defend against
     * pathological circular cause graphs.
     */
    private fun readCauseChain(exception: ObjectReference): List<CauseEntry> {
        val out = mutableListOf<CauseEntry>()
        val visited = mutableSetOf<Long>()
        visited.add(exception.uniqueID())
        var current = exception
        var depth = 0
        while (depth < MAX_CAUSE_DEPTH) {
            val causeField = current.referenceType().fieldByName("cause") ?: break
            val causeValue = runCatching { current.getValue(causeField) }.getOrNull() ?: break
            if (causeValue !is ObjectReference) break
            // JDK convention: cause = this means "no cause" (set by Throwable's init).
            if (causeValue.uniqueID() == current.uniqueID()) break
            if (!visited.add(causeValue.uniqueID())) break
            val className = runCatching { causeValue.referenceType().name() }.getOrNull() ?: break
            val message = readMessage(causeValue)
            out += CauseEntry(className, message)
            current = causeValue
            depth++
        }
        return out
    }

    /**
     * Read `Throwable.stackTrace` (a `StackTraceElement[]`) and lift each element into
     * a [FrameEntry]. Returns an empty list if the field is null or the array is empty.
     */
    private fun readStackTrace(exception: ObjectReference): List<FrameEntry> {
        val field = exception.referenceType().fieldByName("stackTrace") ?: return emptyList()
        val raw: Value = runCatching { exception.getValue(field) }.getOrNull() ?: return emptyList()
        if (raw !is ArrayReference) return emptyList()
        val length = runCatching { raw.length() }.getOrDefault(0)
        if (length == 0) return emptyList()
        // Cap our read to defend against pathologically large traces — anything over the
        // top-10 cap gets truncated by the caller anyway.
        val readCap = minOf(length, 256)
        val elements = runCatching { raw.getValues(0, readCap) }.getOrDefault(emptyList())
        val out = mutableListOf<FrameEntry>()
        for (el in elements) {
            if (el !is ObjectReference) continue
            out += readStackTraceElement(el) ?: continue
        }
        return out
    }

    /**
     * Read one `java.lang.StackTraceElement`. The fields are stable across JDKs:
     *   - `declaringClass: String`
     *   - `methodName: String`
     *   - `fileName: String?`
     *   - `lineNumber: int`
     */
    private fun readStackTraceElement(el: ObjectReference): FrameEntry? {
        val type = el.referenceType()
        val declaring = stringField(el, type, "declaringClass") ?: return null
        val method = stringField(el, type, "methodName") ?: return null
        val file = stringField(el, type, "fileName")
        val lineField = type.fieldByName("lineNumber")
        val line = lineField?.let {
            val v = runCatching { el.getValue(it) }.getOrNull()
            (v as? com.sun.jdi.IntegerValue)?.value() ?: -1
        } ?: -1
        return FrameEntry(declaring, method, file, line)
    }

    private fun stringField(obj: ObjectReference, type: com.sun.jdi.ReferenceType, name: String): String? {
        val f = type.fieldByName(name) ?: return null
        val v = runCatching { obj.getValue(f) }.getOrNull() ?: return null
        return (v as? StringReference)?.value()
    }

    /**
     * Pick the trigger frame: the first non-framework frame in the stack trace. If
     * [pausedThread] is provided AND the live top frame is also user code, prefer that
     * (the agent's `:catch` flow wants the caught-at site to land here).
     *
     * Falls back to the first user frame found anywhere in [frames]. Returns `null` if
     * **every** frame is framework code, which should be exceedingly rare (and is
     * meaningful: the exception came from pure platform code).
     */
    private fun pickTriggerFrame(frames: List<FrameEntry>, pausedThread: ThreadReference?): FrameEntry? {
        // Live top-of-stack first if we have a paused thread and that frame is user code.
        if (pausedThread != null && pausedThread.isSuspended) {
            val live = runCatching {
                if (pausedThread.frameCount() == 0) null
                else {
                    val f = pausedThread.frame(0)
                    val loc = f.location()
                    val cls = loc.declaringType().name()
                    if (FrameworkFrames.isFramework(cls)) null
                    else FrameEntry(
                        className = cls,
                        methodName = loc.method().name(),
                        sourceFile = try { loc.sourceName() } catch (_: AbsentInformationException) { null },
                        lineNumber = loc.lineNumber(),
                    )
                }
            }.getOrNull()
            if (live != null) return live
        }
        // Otherwise: first non-framework frame on the throwable's array. We also skip
        // `<init>` boilerplate when it lives in framework code — the trigger is the
        // user code that invoked the framework, not the framework's constructor.
        return FrameworkFrames.firstUserFrame(frames) { it.className }
    }

    /** Build a `not_attached` / `invalid_target` style error — caller invokes via toolErr. */
    @Suppress("unused") // shape doc
    fun errorShape(): JsonObjectBuilder.() -> Unit = {}
}
