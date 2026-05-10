package com.acendas.androiddebugger

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Capability gate for tools that depend on a JDWP/ART capability.
 *
 * Different Android ART versions support different `vm.canXxx()` flags. We probe these
 * once at attach (see `Capabilities.probe()` and `Session.capabilities`) and gate any
 * tool that needs one of them. The agent reads the probe map from `attach`'s response,
 * but a defensive server-side gate prevents the agent from making the call anyway and
 * getting a surprising native JDI exception. Per Story 7.1.1.
 *
 * Per the architecture spec: errors return `code: capability_unavailable, feature: <name>,
 * hint: <suggestion>` so the agent reacts on `feature`, not prose.
 */
object Capability {

    // The well-known capability names we gate on. Keeping this enum-ish makes it easy
    // to review the list against `Capabilities.probe()`'s emitted keys.
    const val FIELD_MODIFICATION_WATCHPOINTS: String = "field_modification_watchpoints"
    const val FIELD_ACCESS_WATCHPOINTS: String = "field_access_watchpoints"
    const val REDEFINE_CLASSES: String = "redefine_classes"
    const val POP_FRAMES: String = "pop_frames"
    const val FORCE_EARLY_RETURN: String = "force_early_return"
    const val GET_INSTANCE_INFO: String = "get_instance_info"
    const val REQUEST_MONITOR_EVENTS: String = "request_monitor_events"
    const val GET_METHOD_RETURN_VALUES: String = "get_method_return_values"
    const val GET_SOURCE_DEBUG_EXTENSION: String = "get_source_debug_extension"
    const val REQUEST_VM_DEATH_EVENT: String = "request_vm_death_event"

    /**
     * Synthetic capability gate for any future `MethodExitEvent` consumer that wants the
     * frame's return value. JDI exposes this via `MethodExitEvent.returnValue()` only when
     * `canGetMethodReturnValues == true`. Stubbed now so call sites can be wired before
     * the actual feature lands. Per Story 7.1.1's "synthetic gate" requirement.
     */
    const val FRAME_HAS_RETURN_VALUE: String = "frame_has_return_value"

    /**
     * Throw [ToolError] with [ErrorCode.CapabilityUnavailable] if the named capability
     * is not available in the current session. Reads from [Session.capabilities] which
     * is populated by `attach` after [com.acendas.androiddebugger.jdi.Capabilities.probe].
     *
     * Maps the synthetic [FRAME_HAS_RETURN_VALUE] gate onto the underlying probe key
     * `get_method_return_values`.
     */
    fun requireCapability(name: String) {
        val caps = Session.capabilities ?: throw ToolError(
            errorCode = ErrorCode.NotAttached,
            message = "Capability check requires an attached session.",
            hint = "Run /android-debugger:attach first.",
            currentState = Session.state.name,
        )
        val probeKey = when (name) {
            FRAME_HAS_RETURN_VALUE -> GET_METHOD_RETURN_VALUES
            else -> name
        }
        val available = (caps[probeKey] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!available) {
            throw ToolError(
                errorCode = ErrorCode.CapabilityUnavailable,
                message = "ART on this device does not support `$probeKey`.",
                hint = hintFor(probeKey),
            )
        }
    }

    /** Friendly per-capability hint so the agent can suggest the right fallback. */
    private fun hintFor(name: String): String = when (name) {
        FIELD_MODIFICATION_WATCHPOINTS, FIELD_ACCESS_WATCHPOINTS ->
            "Field watchpoints aren't available on this Android version. Use add_method_breakpoint or a conditional line bp instead."
        GET_INSTANCE_INFO ->
            "Some Android versions / ART builds disable instance counting. Use `dump_heap` for an HPROF instead."
        REDEFINE_CLASSES ->
            "Hot-swap is unsupported on this device. Reinstall the app to pick up code changes."
        POP_FRAMES ->
            "Frame pop isn't supported on this device. Use `step_out` to leave the frame instead."
        FORCE_EARLY_RETURN ->
            "Force-early-return isn't supported on this device. Step to the natural return instead."
        REQUEST_MONITOR_EVENTS ->
            "Monitor (lock) events aren't available on this device."
        GET_METHOD_RETURN_VALUES ->
            "Method return-value capture isn't supported on this device. Inspect the value at the call site instead."
        GET_SOURCE_DEBUG_EXTENSION ->
            "SourceDebugExtension (SMAP) isn't available on this device. Stratum-aware breakpoints fall back to Java stratum."
        REQUEST_VM_DEATH_EVENT ->
            "VM death events aren't surfaced by this device. Use `wait_for_event(types=[\"disconnect\"])` instead."
        else -> "Capability `$name` is not available on this device."
    }
}
