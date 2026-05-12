package com.acendas.androiddebugger.hotswap

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/**
 * Compare an old vs. new JVM `.class` file for shape changes that ART's
 * `RedefineClasses` will reject. Per v1.5 spec §5 — pre-validate the common
 * cases for actionable agent-facing errors before sending the dex to JVMTI.
 *
 * **ART's restrictions** (from AOSP `art/runtime/jdwp/jdwp_handler.cc` and
 * `art/openjdkjvmti/ti_redefine.cc`):
 *
 *   - `canRedefineAnyClass` is true → bodies of existing methods may change.
 *   - `canAddMethod` is false → no method add / remove / signature change.
 *   - No field add / remove (would break `instanceSize`).
 *   - No superclass / interface change (would break vtable layout).
 *   - No class-modifier change (`final`, `interface` bit) — also breaks vtable.
 *
 * The diff classifies every change and emits structured `kind` entries so the
 * agent can show "you added `newMethod()V` — move it to a non-redefinition path
 * or restart the app" instead of a JVMTI error code.
 *
 * **Warnings** (non-fatal):
 *   - `@Composable` annotation on a method → Compose runtime may not pick up
 *     the redefine cleanly (commits state machines elsewhere).
 *   - Coroutine state-machine pattern (`extends SuspendLambda`) on the class →
 *     suspend lambda redefinition has caveats.
 */
object ClassDiff {

    /** Result of comparing two class files. [violations] block the redefine; [warnings] don't. */
    data class Result(
        val violations: List<Entry>,
        val warnings: List<Entry>,
    ) {
        val hasViolations: Boolean = violations.isNotEmpty()
    }

    /** One diff entry — either a violation that blocks redefine, or a warning. */
    data class Entry(
        val kind: String,
        val signature: String? = null,
        val detail: String? = null,
    )

    /**
     * Diff [oldBytes] against [newBytes]. Both are full `.class` files. Returns
     * a [Result] describing every shape change found. Empty violations + empty
     * warnings means "safe to redefine" by our pre-validate; ART can still
     * reject for niche reasons (annotation-default tweaks, version bumps), but
     * those fall through to the JVMTI error path.
     */
    fun diff(oldBytes: ByteArray, newBytes: ByteArray): Result {
        val oldNode = parse(oldBytes)
        val newNode = parse(newBytes)

        val violations = mutableListOf<Entry>()
        val warnings = mutableListOf<Entry>()

        diffSuperAndInterfaces(oldNode, newNode, violations)
        diffAccessFlags(oldNode, newNode, violations)
        diffMethods(oldNode, newNode, violations, warnings)
        diffFields(oldNode, newNode, violations)
        detectTransformedCode(newNode, warnings)

        return Result(violations, warnings)
    }

    // ---------------- super / interfaces ----------------

    private fun diffSuperAndInterfaces(old: ClassNode, new: ClassNode, violations: MutableList<Entry>) {
        if (old.superName != new.superName) {
            violations.add(
                Entry(
                    kind = "superclass_changed",
                    detail = "old=${old.superName} new=${new.superName}",
                ),
            )
        }
        val oldInterfaces = old.interfaces?.toSet() ?: emptySet()
        val newInterfaces = new.interfaces?.toSet() ?: emptySet()
        if (oldInterfaces != newInterfaces) {
            val added = newInterfaces - oldInterfaces
            val removed = oldInterfaces - newInterfaces
            violations.add(
                Entry(
                    kind = "interfaces_changed",
                    detail = buildString {
                        if (added.isNotEmpty()) append("added=${added.joinToString(",")}")
                        if (removed.isNotEmpty()) {
                            if (isNotEmpty()) append(" ")
                            append("removed=${removed.joinToString(",")}")
                        }
                    },
                ),
            )
        }
    }

    // ---------------- access flags ----------------

    private fun diffAccessFlags(old: ClassNode, new: ClassNode, violations: MutableList<Entry>) {
        // ART tolerates source-debug-extension changes and synthetic-bit changes.
        // The structurally-significant bits: ACC_PUBLIC, ACC_FINAL, ACC_INTERFACE,
        // ACC_ABSTRACT, ACC_ANNOTATION, ACC_ENUM, ACC_MODULE. We mask the rest.
        val mask = 0x0001 or  // PUBLIC
            0x0010 or         // FINAL
            0x0200 or         // INTERFACE
            0x0400 or         // ABSTRACT
            0x2000 or         // ANNOTATION
            0x4000 or         // ENUM
            0x8000            // MODULE
        if ((old.access and mask) != (new.access and mask)) {
            violations.add(
                Entry(
                    kind = "access_flags_changed",
                    detail = "old=0x${Integer.toHexString(old.access)} new=0x${Integer.toHexString(new.access)}",
                ),
            )
        }
    }

    // ---------------- methods ----------------

    private fun diffMethods(
        old: ClassNode,
        new: ClassNode,
        violations: MutableList<Entry>,
        warnings: MutableList<Entry>,
    ) {
        val oldByKey = (old.methods ?: emptyList<MethodNode>()).associateBy { methodKey(it) }
        val newByKey = (new.methods ?: emptyList<MethodNode>()).associateBy { methodKey(it) }

        for (k in newByKey.keys - oldByKey.keys) {
            violations.add(Entry(kind = "method_added", signature = k))
        }
        for (k in oldByKey.keys - newByKey.keys) {
            violations.add(Entry(kind = "method_removed", signature = k))
        }
        // For methods present in both, the body MAY change. But the access flags
        // and signature already match (same key). Static-vs-instance flips show up
        // as add+remove because the key includes the descriptor — caught above.

        // Warnings — methods carrying compiler-plugin markers.
        for ((key, methodNode) in newByKey) {
            if (hasComposableAnnotation(methodNode)) {
                warnings.add(
                    Entry(
                        kind = "composable_method",
                        signature = key,
                        detail = "@Composable on method body — Compose runtime may not pick up the redefine cleanly. " +
                            "Force a recomposition (state change or activity restart) to test.",
                    ),
                )
            }
        }
    }

    /**
     * Stable identity key for a method: name + descriptor + structurally-significant
     * access bits. Bridge methods (`ACC_BRIDGE`) and synthetics from the compiler
     * (`ACC_SYNTHETIC`) don't change the user-visible shape but are part of the dex
     * we redefine — include them so we don't false-positive a "method removed" when
     * kotlinc reorders synthetics across edits. We deliberately do NOT include
     * `ACC_NATIVE` (would prevent re-targeting a JNI declaration on bytecode-only
     * edits, which is fine — that's a real shape change).
     */
    private fun methodKey(m: MethodNode): String {
        val mask = 0x0001 or  // PUBLIC
            0x0002 or         // PRIVATE
            0x0004 or         // PROTECTED
            0x0008 or         // STATIC
            0x0010 or         // FINAL
            0x0020 or         // SYNCHRONIZED
            0x0040 or         // BRIDGE
            0x0100 or         // NATIVE
            0x0400 or         // ABSTRACT
            0x0800 or         // STRICT
            0x1000            // SYNTHETIC
        return "${m.name}${m.desc}[0x${Integer.toHexString(m.access and mask)}]"
    }

    // ---------------- fields ----------------

    private fun diffFields(old: ClassNode, new: ClassNode, violations: MutableList<Entry>) {
        val oldByKey = (old.fields ?: emptyList<FieldNode>()).associateBy { fieldKey(it) }
        val newByKey = (new.fields ?: emptyList<FieldNode>()).associateBy { fieldKey(it) }

        for (k in newByKey.keys - oldByKey.keys) {
            violations.add(Entry(kind = "field_added", signature = k))
        }
        for (k in oldByKey.keys - newByKey.keys) {
            violations.add(Entry(kind = "field_removed", signature = k))
        }
    }

    private fun fieldKey(f: FieldNode): String {
        // Field key: name + descriptor + access. Static/final/transient flips
        // change instance layout → must surface as add+remove.
        val mask = 0x0001 or
            0x0002 or
            0x0004 or
            0x0008 or
            0x0010 or
            0x0040 or  // VOLATILE
            0x0080     // TRANSIENT
        return "${f.name}:${f.desc}[0x${Integer.toHexString(f.access and mask)}]"
    }

    // ---------------- transformed-code detection ----------------

    private fun detectTransformedCode(new: ClassNode, warnings: MutableList<Entry>) {
        // Coroutine state-machine pattern — classes generated for `suspend` lambdas
        // extend SuspendLambda / RestrictedSuspendLambda. Redefining them is risky
        // because the resumeWith() invocation chain captures continuation state
        // that may reference the old method body indirectly.
        val coroutineSupers = setOf(
            "kotlin/coroutines/jvm/internal/SuspendLambda",
            "kotlin/coroutines/jvm/internal/RestrictedSuspendLambda",
            "kotlin/coroutines/jvm/internal/ContinuationImpl",
            "kotlin/coroutines/jvm/internal/RestrictedContinuationImpl",
        )
        if (new.superName in coroutineSupers) {
            warnings.add(
                Entry(
                    kind = "coroutine_state_machine",
                    detail = "Class extends ${new.superName} — this is a Kotlin coroutine state machine. " +
                        "Redefining suspend bodies has known caveats; verify behavior with a fresh invocation, " +
                        "not the in-flight continuation.",
                ),
            )
        }
    }

    private fun hasComposableAnnotation(m: MethodNode): Boolean {
        val all = mutableListOf<AnnotationNode>()
        m.visibleAnnotations?.let { all.addAll(it) }
        m.invisibleAnnotations?.let { all.addAll(it) }
        return all.any { it.desc == COMPOSABLE_DESC }
    }

    private const val COMPOSABLE_DESC = "Landroidx/compose/runtime/Composable;"

    // ---------------- parsing ----------------

    private fun parse(bytes: ByteArray): ClassNode {
        val reader = ClassReader(bytes)
        val node = ClassNode()
        // SKIP_CODE — we don't compare method bodies; only signatures + shape.
        // SKIP_FRAMES — frame info is irrelevant for shape diff. Saves memory.
        reader.accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        return node
    }

    // ---------------- JSON rendering ----------------

    /** Render a diff result's violations as a JSON array of `{ kind, signature?, detail? }`. */
    fun violationsToJson(violations: List<Entry>): JsonArray = buildJsonArray {
        for (v in violations) add(entryToJson(v))
    }

    /** Render warnings as a JSON array. Same shape as violations. */
    fun warningsToJson(warnings: List<Entry>): JsonArray = buildJsonArray {
        for (w in warnings) add(entryToJson(w))
    }

    private fun entryToJson(e: Entry): JsonObject = buildJsonObject {
        put("kind", e.kind)
        e.signature?.let { put("signature", it) }
        e.detail?.let { put("detail", it) }
    }
}
