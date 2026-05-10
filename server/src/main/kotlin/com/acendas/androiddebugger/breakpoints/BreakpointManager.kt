package com.acendas.androiddebugger.breakpoints

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.AccessWatchpointRequest
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.ClassPrepareRequest
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.EventRequestManager
import com.sun.jdi.request.ExceptionRequest
import com.sun.jdi.request.MethodEntryRequest
import com.sun.jdi.request.MethodExitRequest
import com.sun.jdi.request.ModificationWatchpointRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Kinds of breakpoints the manager owns. Drives the [BreakpointMeta] payload shape and
 * lookup keys for the agent-facing JSON.
 */
enum class BreakpointKind { LINE, EXCEPTION, METHOD_ENTRY, METHOD_EXIT, FIELD_ACCESS, FIELD_MODIFICATION }

/**
 * One breakpoint registration. Lives in [BreakpointManager.metas] keyed by id. We stash
 * both the user-supplied parameters (file, line, condition, hitCount, logMessage, ...)
 * and the JDI-side requests we created. Disable/remove operate on the requests; deferred
 * resolution updates the request list as classes load.
 */
data class BreakpointMeta(
    val id: Int,
    val kind: BreakpointKind,
    /** Line breakpoints. */
    val file: String? = null,
    val line: Int? = null,
    /** Conditional breakpoints. */
    val condition: String? = null,
    /** Hit-count breakpoints. */
    val hitCount: Int? = null,
    /** Logpoint message template. */
    val logMessage: String? = null,
    /** Exception breakpoints. */
    val exceptionClass: String? = null,
    val caught: Boolean = false,
    val uncaught: Boolean = false,
    /** Method entry/exit breakpoints. */
    val methodClass: String? = null,
    val methodName: String? = null,
    /** Field watchpoints. */
    val fieldClass: String? = null,
    val fieldName: String? = null,
) {

    /** Live JDI requests created for this meta. Plural because a line can resolve to multiple locations. */
    val activeRequests: MutableList<EventRequest> = mutableListOf()

    /** Class-prepare deferral: requests we register so we resolve once the class loads. */
    val deferredPrepareRequests: MutableList<ClassPrepareRequest> = mutableListOf()

    /** Whether the manager considers this bp active. Mirrors `request.isEnabled` for the live requests. */
    @Volatile var enabled: Boolean = true

    /** Per-bp counters so the agent can spot a too-noisy condition / hit-count. */
    val totalHits: AtomicLong = AtomicLong(0L)
    val falseConditionHits: AtomicLong = AtomicLong(0L)
    val suppressedHits: AtomicLong = AtomicLong(0L)
    val deliveredStops: AtomicLong = AtomicLong(0L)
    val logpointEntries: AtomicLong = AtomicLong(0L)
}

/**
 * Manages every breakpoint kind on the attached VM. Owns:
 *
 *  - id minting (sequential `Int`s — small for log readability),
 *  - the live `Map<Int, BreakpointMeta>`,
 *  - the deferred queue keyed by class-pattern hint so we can resolve a [BreakpointMeta]
 *    when the right class prepares,
 *  - hit-count + condition-false counters consulted by the event loop.
 *
 * Per Story 3.1.1.
 *
 * Concurrency: the manager is touched from both MCP tool threads (add/remove/list) and
 * the JDI event-loop thread (counters, deferred resolution). We use `ConcurrentHashMap`
 * for the bp store and `AtomicInteger`/`AtomicLong` for counters; the JDI requests
 * themselves are touched on the event-loop thread or under [DebugSession.mutex]
 * indirectly (we only mutate requests during attach/detach/add/remove which is
 * already mutex-serialized at the tool layer).
 */
object BreakpointManager {

    private val nextId = AtomicInteger(1)
    private val metas: MutableMap<Int, BreakpointMeta> = ConcurrentHashMap()

    fun size(): Int = metas.size

    fun all(): List<BreakpointMeta> = metas.values.sortedBy { it.id }

    fun get(id: Int): BreakpointMeta? = metas[id]

    fun mintId(): Int = nextId.getAndIncrement()

    /** Drop everything. Called from `Session.reset()` so a re-attach starts clean. */
    fun clear() {
        // Don't try to call disable() on the requests; the VM is gone by the time this
        // runs (or we're explicitly resetting). Just drop the bookkeeping.
        metas.clear()
        nextId.set(1)
    }

    /**
     * Insert a fresh meta. The caller already populated requests / prepare-requests on
     * the meta object before calling.
     */
    fun register(meta: BreakpointMeta) {
        metas[meta.id] = meta
    }

    /**
     * Lookup by JDI request — used by the event loop on every breakpoint/exception
     * /watchpoint hit. Linear over the meta map but the count is bounded (typical
     * debugger sessions have <50 breakpoints, often <10).
     */
    fun findByRequest(request: EventRequest): BreakpointMeta? {
        for (meta in metas.values) {
            if (meta.activeRequests.contains(request)) return meta
        }
        return null
    }

    fun findByPrepareRequest(request: EventRequest): BreakpointMeta? {
        if (request !is ClassPrepareRequest) return null
        for (meta in metas.values) {
            if (meta.deferredPrepareRequests.contains(request)) return meta
        }
        return null
    }

    /**
     * Remove [id] entirely. Disables and deletes every JDI request the meta owns.
     * Idempotent — returns false if the id wasn't registered.
     */
    fun remove(vm: VirtualMachine, id: Int): Boolean {
        val meta = metas.remove(id) ?: return false
        val erm = vm.eventRequestManager()
        for (req in meta.activeRequests) {
            runCatching { req.disable() }
            runCatching { erm.deleteEventRequest(req) }
        }
        for (cpr in meta.deferredPrepareRequests) {
            runCatching { cpr.disable() }
            runCatching { erm.deleteEventRequest(cpr) }
        }
        meta.activeRequests.clear()
        meta.deferredPrepareRequests.clear()
        return true
    }

    fun setEnabled(id: Int, enabled: Boolean): Boolean {
        val meta = metas[id] ?: return false
        meta.enabled = enabled
        for (req in meta.activeRequests) {
            runCatching { if (enabled) req.enable() else req.disable() }
        }
        // We deliberately leave deferredPrepareRequests enabled regardless — disabling
        // them would mean we miss the class-load and never get a chance to install the
        // real bp request. The `enabled` flag on the meta gates event delivery instead.
        return true
    }

    /**
     * After a `ClassPrepareEvent` for one of the deferred requests on [meta], resolve
     * fresh locations on the just-loaded type, create breakpoint requests, and add them
     * to [meta.activeRequests]. Called on the event-loop thread.
     *
     * Returns the number of new locations resolved. The deferred prepare requests stay
     * registered until [remove] — multiple classes can match the file pattern (one for
     * each Kotlin lambda nested class) and they prepare independently.
     */
    fun installDeferredLineBreakpoint(
        vm: VirtualMachine,
        meta: BreakpointMeta,
        type: ReferenceType,
    ): Int {
        if (meta.kind != BreakpointKind.LINE) return 0
        val file = meta.file ?: return 0
        val line = meta.line ?: return 0
        val erm = vm.eventRequestManager()
        // Pull fresh locations on this newly-loaded type only — we don't want to scan
        // the whole VM on every class-prepare.
        val locations = mutableListOf<com.sun.jdi.Location>()
        // Direct match on this type.
        if (sourceNameSafe(type) == file || sourceUnknown(type)) {
            locations += locationsFor(type, line)
        }
        // Walk its nestedTypes too; Kotlin inline lambdas create extra layers.
        for (nested in nestedTypesRecursive(type)) {
            if (sourceNameSafe(nested) == file || sourceUnknown(nested)) {
                locations += locationsFor(nested, line)
            }
        }
        if (locations.isEmpty()) return 0

        var added = 0
        for (loc in locations) {
            // Avoid duplicating an already-registered location (a re-prepare of the
            // same class with same locations shouldn't double the bp set).
            val already = meta.activeRequests.any { it is BreakpointRequest && it.location() == loc }
            if (already) continue
            val bpReq: BreakpointRequest = erm.createBreakpointRequest(loc).apply {
                setSuspendPolicy(suspendPolicyFor(meta))
                if (meta.enabled) enable() else disable()
            }
            meta.activeRequests.add(bpReq)
            added++
        }
        return added
    }

    /** Suspend policy depends on whether this is a logpoint (no suspension) or a normal bp. */
    fun suspendPolicyFor(meta: BreakpointMeta): Int = when {
        // Logpoints still suspend the event thread so we can render against the frame
        // (we need locals); the event loop resumes immediately after rendering.
        meta.logMessage != null -> EventRequest.SUSPEND_EVENT_THREAD
        meta.kind == BreakpointKind.METHOD_ENTRY || meta.kind == BreakpointKind.METHOD_EXIT ->
            EventRequest.SUSPEND_EVENT_THREAD
        else -> EventRequest.SUSPEND_EVENT_THREAD
    }

    private fun sourceNameSafe(type: ReferenceType): String? = try {
        type.sourceName()
    } catch (_: com.sun.jdi.AbsentInformationException) { null } catch (_: Throwable) { null }

    private fun sourceUnknown(type: ReferenceType): Boolean = try {
        type.sourceName(); false
    } catch (_: com.sun.jdi.AbsentInformationException) { true } catch (_: Throwable) { true }

    private fun locationsFor(type: ReferenceType, line: Int): List<com.sun.jdi.Location> {
        for (stratum in listOf<String?>("Kotlin", "Java", null)) {
            try {
                val locs = if (stratum != null) type.locationsOfLine(stratum, null, line)
                else type.locationsOfLine(line)
                if (!locs.isNullOrEmpty()) return locs
            } catch (_: Throwable) { /* try next */ }
        }
        return emptyList()
    }

    private fun nestedTypesRecursive(type: ReferenceType): List<ReferenceType> {
        val out = mutableListOf<ReferenceType>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<ReferenceType>()
        stack.addLast(type)
        while (stack.isNotEmpty()) {
            val curr = stack.removeLast()
            val nested = try { curr.nestedTypes() } catch (_: Throwable) { emptyList() }
            for (n in nested) {
                val name = try { n.name() } catch (_: Throwable) { continue }
                if (seen.add(name)) {
                    out += n
                    stack.addLast(n)
                }
            }
        }
        return out
    }

    /**
     * Helper: register a [ClassPrepareRequest] for each pattern in [classPatterns] and
     * push them onto [meta.deferredPrepareRequests]. Caller is responsible for the rest
     * of the meta wiring + register() call.
     */
    fun addDeferredPrepareRequests(
        erm: EventRequestManager,
        meta: BreakpointMeta,
        classPatterns: List<String>,
    ) {
        for (pattern in classPatterns.distinct()) {
            val cpr = erm.createClassPrepareRequest()
            try {
                cpr.addClassFilter(pattern)
                cpr.setSuspendPolicy(EventRequest.SUSPEND_NONE)
                cpr.enable()
                meta.deferredPrepareRequests.add(cpr)
            } catch (t: Throwable) {
                runCatching { erm.deleteEventRequest(cpr) }
                throw ToolError(
                    errorCode = ErrorCode.Internal,
                    message = "Failed to register class-prepare deferral for `$pattern`: ${t.message}",
                )
            }
        }
    }

    // -------- Helpers used by the tool registrations to identify the kind of req at hit --------

    fun isBreakpointRequest(req: EventRequest): Boolean = req is BreakpointRequest
    fun isExceptionRequest(req: EventRequest): Boolean = req is ExceptionRequest
    fun isMethodEntryRequest(req: EventRequest): Boolean = req is MethodEntryRequest
    fun isMethodExitRequest(req: EventRequest): Boolean = req is MethodExitRequest
    fun isAccessWatchpointRequest(req: EventRequest): Boolean = req is AccessWatchpointRequest
    fun isModificationWatchpointRequest(req: EventRequest): Boolean = req is ModificationWatchpointRequest
}
