package com.acendas.androiddebugger.jdi

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.VirtualMachine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Probe `vm.canXxx()` flags into a JSON map the agent can reason about. Different
 * Android ART versions support different things; the agent reads this map at attach
 * time and avoids requesting features the device doesn't support. Per Task 1.1.3.4.
 */
object Capabilities {

    @Suppress("DEPRECATION") // canAddMethod / canUnrestrictedlyRedefineClasses are
    // deprecated since JDK 9 but we still expose them in the probe map — both are
    // always false on ART, but keeping the keys preserves the agent-facing schema and
    // documents the negative answer. Per R-18.
    fun probe(vm: VirtualMachine): JsonObject = buildJsonObject {
        put("field_modification_watchpoints", vm.canWatchFieldModification())
        put("field_access_watchpoints", vm.canWatchFieldAccess())
        put("get_bytecodes", vm.canGetBytecodes())
        put("get_synthetic_attribute", vm.canGetSyntheticAttribute())
        put("get_owned_monitor_info", vm.canGetOwnedMonitorInfo())
        put("get_current_contended_monitor", vm.canGetCurrentContendedMonitor())
        put("get_monitor_info", vm.canGetMonitorInfo())
        put("get_monitor_frame_info", vm.canGetMonitorFrameInfo())
        put("redefine_classes", vm.canRedefineClasses())
        put("add_method", vm.canAddMethod())
        put("unrestrictedly_redefine_classes", vm.canUnrestrictedlyRedefineClasses())
        put("pop_frames", vm.canPopFrames())
        put("use_instance_filters", vm.canUseInstanceFilters())
        put("get_source_debug_extension", vm.canGetSourceDebugExtension())
        put("request_vm_death_event", vm.canRequestVMDeathEvent())
        put("get_instance_info", vm.canGetInstanceInfo())
        put("request_monitor_events", vm.canRequestMonitorEvents())
        put("use_source_name_filters", vm.canUseSourceNameFilters())
        put("get_constant_pool", vm.canGetConstantPool())
        put("force_early_return", vm.canForceEarlyReturn())
        put("get_method_return_values", vm.canGetMethodReturnValues())
    }

    /**
     * Cheap heuristic: sample up to 50 user classes; check whether their methods have
     * local-variable tables. R8/ProGuard release builds strip these. Per Task 1.1.3.5.
     *
     * Returns `true` if &gt;80% of sampled classes have stripped locals.
     */
    fun isLikelyReleaseBuild(vm: VirtualMachine): Boolean {
        val frameworkPrefixes = setOf(
            "java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.",
            "com.sun.", "sun.", "jdk.", "dalvik.", "libcore.", "com.android.",
        )
        val sample = vm.allClasses()
            .asSequence()
            .filter { type -> frameworkPrefixes.none { type.name().startsWith(it) } }
            .take(50)
            .toList()
        if (sample.isEmpty()) return false

        var stripped = 0
        for (cls in sample) {
            val firstNonAbstract = cls.methods().firstOrNull { !it.isAbstract && !it.isNative } ?: continue
            try {
                if (firstNonAbstract.variables().isEmpty()) stripped++
            } catch (_: AbsentInformationException) {
                stripped++
            } catch (_: Throwable) {
                // Any other failure — don't count it as stripped, don't fail the heuristic.
            }
        }
        return stripped * 5 > sample.size * 4 // > 80%
    }
}
