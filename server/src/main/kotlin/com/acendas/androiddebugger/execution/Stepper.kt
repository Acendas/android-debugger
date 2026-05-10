package com.acendas.androiddebugger.execution

import com.acendas.androiddebugger.inspection.FrameworkFrames
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.StepRequest

/**
 * Issues single-shot [StepRequest]s with the right depth + class-exclusion filters.
 * The actual "wait for the StepEvent" loop lives in [com.acendas.androiddebugger.tools.ExecutionTools]
 * because it composes with the event channel; this object just centralizes the JDI
 * fiddly bits. Per Tasks 4.1.1.1 / 4.1.1.2.
 */
object Stepper {

    /**
     * Default class-exclusion filters. Skip framework + stdlib so step_into doesn't
     * dump us into AOSP innards on every invocation. The agent can override via
     * `extra_skip_filters` on the tool; pass `disable_default_filters: true` to drop
     * these entirely (rare — only useful when intentionally stepping into kotlin.* code).
     *
     * Per BR-03: sourced from [FrameworkFrames.PREFIX_GLOBS] so the framework-prefix
     * list is canonical across the capability heuristic, step exclusions, and BR-01.
     */
    val DEFAULT_EXCLUSIONS: List<String> = FrameworkFrames.PREFIX_GLOBS

    enum class Depth(val jdi: Int) {
        Over(StepRequest.STEP_OVER),
        Into(StepRequest.STEP_INTO),
        Out(StepRequest.STEP_OUT),
    }

    /**
     * Create + enable a single-shot [StepRequest] on [thread]. The caller is responsible
     * for waiting on the resulting `StepEvent` and disabling/deleting the request once
     * the event arrives — see [dispose].
     */
    fun startStep(
        vm: VirtualMachine,
        thread: ThreadReference,
        depth: Depth,
        extraSkipFilters: List<String> = emptyList(),
        disableDefaultFilters: Boolean = false,
    ): StepRequest {
        val erm = vm.eventRequestManager()
        // JDI rejects creating a new StepRequest for a thread that already has one.
        // Defensive cleanup: drop any prior steps for this thread before adding ours.
        erm.stepRequests().filter { it.thread() == thread }.forEach { erm.deleteEventRequest(it) }

        val req = erm.createStepRequest(thread, StepRequest.STEP_LINE, depth.jdi)
        val excludes = (if (disableDefaultFilters) emptyList() else DEFAULT_EXCLUSIONS) + extraSkipFilters
        for (pattern in excludes) {
            req.addClassExclusionFilter(pattern)
        }
        req.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
        req.addCountFilter(1) // Single-shot.
        req.enable()
        return req
    }

    /** Disable + delete a step request once we've consumed its event. */
    fun dispose(vm: VirtualMachine, request: StepRequest) {
        runCatching { request.disable() }
        runCatching { vm.eventRequestManager().deleteEventRequest(request) }
    }
}
