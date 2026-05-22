package com.acendas.androiddebugger.breakpoints

import com.acendas.androiddebugger.Capability
import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.AccessWatchpointRequest
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.ClassPrepareRequest
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.MethodEntryRequest
import com.sun.jdi.request.MethodExitRequest
import com.sun.jdi.request.ModificationWatchpointRequest

/**
 * Pure-install entry points used by both the `add_*_breakpoint` MCP tools and the v1.3
 * session-persistence rehydration path. Encapsulates the "create JDI requests + attach
 * them to a [BreakpointMeta] + register with the manager" step that used to live inline
 * in every tool body.
 *
 * Each function takes a caller-supplied [id] so persistence can re-use ids minted in a
 * previous session. For fresh tool invocations the caller passes
 * [BreakpointManager.mintId]; for rehydration the caller passes the persisted id.
 *
 * Return value summary is structured ([InstallResult]) so the tool body can report
 * `resolved_locations`, `deferred`, etc. back to the agent.
 */
object BreakpointInstaller {

    data class InstallResult(
        val meta: BreakpointMeta,
        val resolvedLocations: Int = 0,
        val deferred: Boolean = false,
    )

    fun installLine(
        vm: VirtualMachine,
        id: Int,
        file: String,
        line: Int,
        condition: String? = null,
        hitCount: Int? = null,
        logMessage: String? = null,
        enabled: Boolean = true,
        planId: String? = null,
    ): InstallResult {
        val meta = BreakpointMeta(
            id = id,
            kind = BreakpointKind.LINE,
            file = file,
            line = line,
            condition = condition,
            hitCount = hitCount,
            logMessage = logMessage,
            planId = planId,
        ).also { it.enabled = enabled }

        val locations = SourceResolver.resolve(vm, file, line)
        val erm = vm.eventRequestManager()
        for (loc in locations) {
            val req: BreakpointRequest = erm.createBreakpointRequest(loc).apply {
                setSuspendPolicy(BreakpointManager.suspendPolicyFor(meta))
                if (enabled) enable() else disable()
            }
            BreakpointManager.attachRequest(meta, req)
        }

        // Class-prepare deferral: register patterns even when we already resolved some
        // locations. Kotlin inline lambdas may live in classes that load later.
        val patterns = SourceResolver.classPatternsFor(file)
        if (patterns.isNotEmpty()) {
            BreakpointManager.addDeferredPrepareRequests(erm, meta, patterns)
        }

        BreakpointManager.register(meta)
        return InstallResult(
            meta = meta,
            resolvedLocations = locations.size,
            deferred = locations.isEmpty() && patterns.isNotEmpty(),
        )
    }

    fun installException(
        vm: VirtualMachine,
        id: Int,
        exceptionClass: String?,
        caught: Boolean,
        uncaught: Boolean,
        enabled: Boolean = true,
        planId: String? = null,
    ): InstallResult {
        val meta = BreakpointMeta(
            id = id,
            kind = BreakpointKind.EXCEPTION,
            exceptionClass = exceptionClass,
            caught = caught,
            uncaught = uncaught,
            planId = planId,
        ).also { it.enabled = enabled }

        val erm = vm.eventRequestManager()
        var deferred = false
        if (exceptionClass == null) {
            val req = erm.createExceptionRequest(null, caught, uncaught).apply {
                setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                if (enabled) enable() else disable()
            }
            BreakpointManager.attachRequest(meta, req)
        } else {
            val refs = vm.classesByName(exceptionClass)
            if (refs.isEmpty()) {
                BreakpointManager.addDeferredPrepareRequests(erm, meta, listOf(exceptionClass))
                deferred = true
            } else {
                for (ref in refs) {
                    val req = erm.createExceptionRequest(ref, caught, uncaught).apply {
                        setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                        if (enabled) enable() else disable()
                    }
                    BreakpointManager.attachRequest(meta, req)
                }
            }
        }
        BreakpointManager.register(meta)
        return InstallResult(meta = meta, deferred = deferred)
    }

    fun installMethodEntry(
        vm: VirtualMachine,
        id: Int,
        methodClass: String,
        methodName: String,
        enabled: Boolean = true,
        planId: String? = null,
    ): InstallResult {
        val meta = BreakpointMeta(
            id = id,
            kind = BreakpointKind.METHOD_ENTRY,
            methodClass = methodClass,
            methodName = methodName,
            planId = planId,
        ).also { it.enabled = enabled }
        val erm = vm.eventRequestManager()
        val req: MethodEntryRequest = erm.createMethodEntryRequest().apply {
            addClassFilter(methodClass)
            setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            if (enabled) enable() else disable()
        }
        BreakpointManager.attachRequest(meta, req)
        BreakpointManager.register(meta)
        return InstallResult(meta = meta)
    }

    fun installMethodExit(
        vm: VirtualMachine,
        id: Int,
        methodClass: String,
        methodName: String,
        enabled: Boolean = true,
        planId: String? = null,
    ): InstallResult {
        val meta = BreakpointMeta(
            id = id,
            kind = BreakpointKind.METHOD_EXIT,
            methodClass = methodClass,
            methodName = methodName,
            planId = planId,
        ).also { it.enabled = enabled }
        val erm = vm.eventRequestManager()
        val req: MethodExitRequest = erm.createMethodExitRequest().apply {
            addClassFilter(methodClass)
            setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            if (enabled) enable() else disable()
        }
        BreakpointManager.attachRequest(meta, req)
        BreakpointManager.register(meta)
        return InstallResult(meta = meta)
    }

    /**
     * Install a field watchpoint. `wantAccess` / `wantModification` may be true
     * independently; if both are requested we create one of each request kind.
     * Capability-gated — throws `ToolError(CapabilityUnavailable)` if ART doesn't
     * report the corresponding capability.
     *
     * Returns a meta whose [BreakpointMeta.kind] reflects the primary kind requested
     * (FIELD_ACCESS if access is requested, else FIELD_MODIFICATION) but whose
     * `activeRequests` may contain both.
     */
    fun installFieldWatchpoint(
        vm: VirtualMachine,
        id: Int,
        fieldClass: String,
        fieldName: String,
        wantAccess: Boolean,
        wantModification: Boolean,
        enabled: Boolean = true,
        planId: String? = null,
    ): InstallResult {
        if (!wantAccess && !wantModification) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "At least one of access / modification must be enabled.",
            )
        }
        if (wantAccess) Capability.requireCapability(Capability.FIELD_ACCESS_WATCHPOINTS)
        if (wantModification) Capability.requireCapability(Capability.FIELD_MODIFICATION_WATCHPOINTS)

        val refs = vm.classesByName(fieldClass)
        if (refs.isEmpty()) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Class `$fieldClass` is not loaded in the VM.",
                hint = "Field watchpoints can't be deferred. Wait for the class to load (e.g., trigger one access) and try again.",
            )
        }
        val refType = refs.first()
        val field = refType.fieldByName(fieldName)
            ?: throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Field `$fieldName` not found on class `$fieldClass`.",
            )

        val meta = BreakpointMeta(
            id = id,
            kind = if (wantAccess) BreakpointKind.FIELD_ACCESS else BreakpointKind.FIELD_MODIFICATION,
            fieldClass = fieldClass,
            fieldName = fieldName,
            planId = planId,
        ).also { it.enabled = enabled }

        val erm = vm.eventRequestManager()
        if (wantAccess) {
            val req: AccessWatchpointRequest = erm.createAccessWatchpointRequest(field).apply {
                setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                if (enabled) enable() else disable()
            }
            BreakpointManager.attachRequest(meta, req)
        }
        if (wantModification) {
            val req: ModificationWatchpointRequest = erm.createModificationWatchpointRequest(field).apply {
                setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                if (enabled) enable() else disable()
            }
            BreakpointManager.attachRequest(meta, req)
        }
        BreakpointManager.register(meta)
        return InstallResult(meta = meta)
    }

    /**
     * Pause when a class matching [classPattern] is loaded. v1.3 (Phase C).
     *
     * Backed by a JDI `ClassPrepareRequest` with `SUSPEND_EVENT_THREAD` (distinct
     * from the internal, non-suspending class-prepare requests on a meta's
     * `deferredPrepareRequests` — those exist solely to install line/exception bps
     * once their target classes load).
     *
     * The class-prepare request is attached as an `activeRequest` on the meta so
     * `findByRequest` (the O(1) reverse index) hits it on every prepare event.
     * `EventLoop.handleClassPrepare` dispatches user-facing CLASS_LOAD events as
     * `Stopped(reason="class_prepare", breakpoint_id=…)` instead of swallowing them.
     */
    fun installClassLoad(
        vm: VirtualMachine,
        id: Int,
        classPattern: String,
        enabled: Boolean = true,
        planId: String? = null,
    ): InstallResult {
        if (classPattern.isBlank()) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "classPattern must be non-empty.",
            )
        }
        val meta = BreakpointMeta(
            id = id,
            kind = BreakpointKind.CLASS_LOAD,
            classPattern = classPattern,
            planId = planId,
        ).also { it.enabled = enabled }

        val erm = vm.eventRequestManager()
        val req: ClassPrepareRequest = erm.createClassPrepareRequest().apply {
            addClassFilter(classPattern)
            setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            if (enabled) enable() else disable()
        }
        BreakpointManager.attachRequest(meta, req)
        BreakpointManager.register(meta)
        return InstallResult(meta = meta)
    }
}
