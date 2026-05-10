package com.acendas.androiddebugger.inspection

/**
 * Single canonical list of class-name prefixes that identify "framework / stdlib /
 * generated boilerplate" frames the user almost never wants to step into or read as
 * the trigger location of an exception. Per BR-03 (v1.1.1 skill craft review).
 *
 * Before this object existed, three call sites each maintained their own subset:
 *   1. `Capabilities.isLikelyReleaseBuild` (jdi/Capabilities.kt) — sampled non-framework
 *      classes for stripped-locals detection.
 *   2. `Stepper.DEFAULT_EXCLUSIONS` (execution/Stepper.kt) — JDI step-class exclusion
 *      filters use a glob shape (`java.*`) and intentionally include `sun.*`.
 *   3. The crash-loop / `:catch` "first user-code frame" heuristic, which until BR-01
 *      had no shared implementation at all and was inlined into the agent body.
 *
 * They drifted: `Stepper` skipped `kotlin.*`, the crash-loop didn't; the capability
 * heuristic skipped `dalvik.` but `Stepper` didn't. BR-03 collapses everything onto
 * this list so adding a prefix updates every caller.
 *
 * The list is **prefix-form** (no trailing `.*` glob). Callers that need globs (Stepper)
 * append `*` themselves; callers that need a `startsWith` check (this file's
 * [isFramework]) use it verbatim.
 */
object FrameworkFrames {

    /**
     * Prefixes that identify framework / stdlib / generated DI / VM-internal classes.
     *
     * Order is informational only — [isFramework] does an O(n) scan, n is small.
     * We intentionally include the trailing `.` so `kotlin.foo.Bar` matches but a
     * user package named `kotlinapp.Foo` does not.
     *
     * Includes both Java/JVM stdlib (`java.`, `javax.`, `sun.`, `jdk.`), the Android
     * platform (`android.`, `androidx.`), Kotlin's runtime (`kotlin.`, `kotlinx.`),
     * Google's Android internals (`com.android.`, `com.google.android.`), Hilt/Dagger
     * generated code (`dagger.`, `dagger.hilt.`, `hilt_aggregated_deps.`), the
     * JDK's `com.sun.` namespace, ART/Dalvik internals (`dalvik.`, `libcore.`).
     */
    val PREFIXES: List<String> = listOf(
        "java.",
        "javax.",
        "android.",
        "androidx.",
        "kotlin.",
        "kotlinx.",
        "com.android.",
        "com.google.android.",
        "dagger.",
        "dagger.hilt.",
        "hilt_aggregated_deps.",
        "com.sun.",
        "sun.",
        "jdk.",
        "dalvik.",
        "libcore.",
    )

    /**
     * Glob form of [PREFIXES] for JDI's `addClassExclusionFilter`, which expects a
     * trailing `*` wildcard. Callers that drive the JDI step API (Stepper) use this.
     *
     * `java.` -> `java.*`, etc.
     */
    val PREFIX_GLOBS: List<String> = PREFIXES.map { it + "*" }

    /**
     * `true` if [className] starts with any of [PREFIXES]. Returns `false` for the
     * empty string and for class names that happen to share a non-`.`-terminated
     * prefix (e.g., `androidaffinity.Foo` is **not** treated as framework code).
     */
    fun isFramework(className: String): Boolean {
        if (className.isEmpty()) return false
        for (p in PREFIXES) {
            if (className.startsWith(p)) return true
        }
        return false
    }

    /**
     * Pick the first frame whose declaring class is **not** framework. Used by BR-01's
     * `exception_summary` to surface the trigger frame — i.e., the first user-code
     * stack frame, skipping `<init>` / synthetic / framework boilerplate that ART
     * threads through the throw path.
     *
     * Returns `null` if every frame is framework code (rare — usually means the
     * exception bubbled out of pure platform code with no user contribution, e.g.,
     * an OOM thrown by the GC thread).
     *
     * The lambda parameter shape `(Frame) -> String` keeps this object JDI-free so
     * unit tests don't need a live VM; production callers pass a closure that reads
     * the JDI `Location.declaringType().name()`.
     */
    fun <T> firstUserFrame(frames: List<T>, classNameOf: (T) -> String): T? {
        return frames.firstOrNull { !isFramework(classNameOf(it)) }
    }
}
