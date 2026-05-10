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
     *
     * **Performance.** `vm.allClasses()` returns 30k+ classes on a real Android app and
     * each `sourceName()` call is a JDWP round-trip over adb (~1–5ms). A naive
     * `classes.filter { sourceName() == file }` therefore freezes the whole MCP server
     * for 30–150 seconds while `Session.mutex` is held. We sidestep that with a
     * cheap class-name pre-filter: `type.name()` is cached locally by the JDI client
     * and doesn't round-trip, so we can use it to discard 99.9% of classes before
     * paying the `sourceName()` cost. The pre-filter accepts any class whose simple
     * name (after `.` and before `$`) matches one of the candidate names derived
     * from the source filename — handles Kotlin file-level facades (`MyFileKt`),
     * top-level classes (`MyFile`), and their inner / lambda-synthetic descendants.
     */
    fun resolve(vm: VirtualMachine, file: String, line: Int): List<Location> {
        val out = mutableListOf<Location>()
        val candidates = candidateClasses(vm, file)
        // First pass: candidates whose own sourceName actually matches the requested file.
        val direct = candidates.filter { type -> sourceMatches(type, file) }
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
    fun classesForFile(vm: VirtualMachine, file: String): List<ReferenceType> =
        candidateClasses(vm, file).filter { sourceMatches(it, file) }

    /**
     * Cheap class-name pre-filter. Derives candidate simple names from [file]
     * (`"MainActivity.kt"` → `["MainActivity", "MainActivityKt"]`) and only returns
     * classes whose own simple name (post-`.`, pre-`$`) matches. `type.name()` is
     * locally cached so this iterates `vm.allClasses()` without any JDWP traffic.
     *
     * The follow-up `sourceMatches` filter then pays the JDWP cost only on the
     * surviving handful of classes — typically <50 instead of 30k+.
     */
    private fun candidateClasses(vm: VirtualMachine, file: String): List<ReferenceType> {
        val classes: List<ReferenceType> = try { vm.allClasses() } catch (_: Throwable) { return emptyList() }
        val base = file.substringAfterLast('/').substringBeforeLast('.')
        if (base.isBlank()) return emptyList()
        // Candidate simple names cover top-level classes, Kotlin file-level facades, and
        // anonymous/inner classes synthesized for inline-fn lambdas.
        val names = setOf(base, "${base}Kt")
        return classes.filter { type ->
            val name = try { type.name() } catch (_: Throwable) { return@filter false }
            // Strip the package prefix and any inner-class suffix to get the simple name.
            val simple = name.substringAfterLast('.').substringBefore('$')
            simple in names
        }
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
        // JDI's `sourceName()` always returns the file basename (e.g. "MainActivity.kt"),
        // never a path. The user's `file` argument might be either, so normalize to
        // basename before comparing — otherwise `src/main/java/.../MainActivity.kt`
        // will never match the JDI-reported `MainActivity.kt`.
        type.sourceName() == file.substringAfterLast('/')
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
