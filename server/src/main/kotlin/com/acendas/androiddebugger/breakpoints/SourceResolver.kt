package com.acendas.androiddebugger.breakpoints

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine

/**
 * Maps `(file, line)` to a list of executable [Location]s in the attached VM. Tries
 * the **Kotlin** stratum first then falls back to **Java** so Kotlin sources resolve
 * even when ART exposes them via the Kotlin SMAP-stripped stratum (per ART's
 * `canGetSourceDebugExtension == false`). For inline-function lambdas (which compile
 * to nested anonymous classes), walks `nestedTypes()` recursively.
 *
 * Per Task 3.1.1.2.
 *
 * Returns an empty list if no class with this source name is currently loaded — that's
 * the trigger for the breakpoint manager to register a deferred [com.sun.jdi.request.ClassPrepareRequest].
 */
object SourceResolver {

    private val STRATA_TRIES: List<String?> = listOf("Kotlin", "Java", null)

    /**
     * All executable locations in already-loaded classes for the given source [file] and
     * [line]. The list may be empty (class not yet prepared) or contain multiple
     * locations (e.g., one for the outer class plus one per inline-lambda nested class).
     *
     * The breakpoint manager creates a `BreakpointRequest` for each returned location.
     */
    fun resolve(vm: VirtualMachine, file: String, line: Int): List<Location> {
        val out = mutableListOf<Location>()
        // allClasses() is the cheap path; we don't pre-filter by source name because
        // sourceName() can throw AbsentInformationException and we'd lose Kotlin lambdas
        // whose declaring class has a different source-name than the user typed.
        val classes: List<ReferenceType> = try { vm.allClasses() } catch (_: Throwable) { return out }

        // First pass: classes whose own sourceName matches the requested file.
        val direct = classes.filter { type -> sourceMatches(type, file) }
        for (type in direct) {
            out += locationsFor(type, line)
            for (nested in nestedTypesRecursive(type)) {
                // Inline-fn lambdas often live in nested anonymous classes. Their
                // sourceName may or may not match — try both ways.
                if (sourceMatches(nested, file) || sourceUnknown(nested)) {
                    out += locationsFor(nested, line)
                }
            }
        }
        return out
    }

    /** Find currently-loaded reference types whose source file matches [file]. */
    fun classesForFile(vm: VirtualMachine, file: String): List<ReferenceType> {
        val classes: List<ReferenceType> = try { vm.allClasses() } catch (_: Throwable) { return emptyList() }
        return classes.filter { sourceMatches(it, file) }
    }

    /**
     * Class-pattern filters for a [ClassPrepareRequest][com.sun.jdi.request.ClassPrepareRequest]
     * that should fire for this file. We can't filter by source name (ART reports
     * `canUseSourceNameFilters=false`), so the breakpoint manager registers one per
     * already-known class plus a wildcard pattern derived from the file's likely package.
     *
     * Returns null when no useful class-pattern hint is available — the caller should
     * fall back to "match any class" with post-event filtering by source.
     */
    fun classPatternsFor(file: String): List<String> {
        // The user passes us "MainActivity.kt" or maybe "com/example/MainActivity.kt".
        // We can't infer a package from a bare file name; emit a permissive list.
        val base = file.substringAfterLast('/').substringBeforeLast('.')
        if (base.isBlank()) return emptyList()
        // Three patterns:
        //   1. Outer class: "*<base>"
        //   2. Inner classes (Kotlin lambdas, anonymous classes): "*<base>$*"
        //   3. Kotlin file-level facade class (`MainKt`): "*<base>Kt"
        return listOf("*$base", "*$base\$*", "*${base}Kt", "*${base}Kt\$*")
    }

    /** All `locationsOfLine(line)` results for [type], trying each stratum until one yields. */
    private fun locationsFor(type: ReferenceType, line: Int): List<Location> {
        for (stratum in STRATA_TRIES) {
            try {
                val locs = if (stratum != null) {
                    type.locationsOfLine(stratum, null, line)
                } else {
                    type.locationsOfLine(line)
                }
                if (!locs.isNullOrEmpty()) return locs
            } catch (_: AbsentInformationException) {
                // Try next stratum.
            } catch (_: Throwable) {
                // Try next stratum.
            }
        }
        return emptyList()
    }

    /**
     * Walk [type]'s `nestedTypes()` recursively. Inline-function lambdas in Kotlin
     * compile to multiple levels of anonymous nested classes; a simple one-level walk
     * misses them. Per the design north star we err on the side of resolving more rather
     * than fewer locations — multiple breakpoints for one (file, line) is the right
     * answer when the line lives inside an inline lambda body.
     */
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

    private fun sourceMatches(type: ReferenceType, file: String): Boolean = try {
        type.sourceName() == file
    } catch (_: AbsentInformationException) {
        false
    } catch (_: Throwable) {
        false
    }

    private fun sourceUnknown(type: ReferenceType): Boolean = try {
        type.sourceName(); false
    } catch (_: AbsentInformationException) {
        true
    } catch (_: Throwable) {
        true
    }
}
