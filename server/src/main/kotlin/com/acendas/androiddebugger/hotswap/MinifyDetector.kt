package com.acendas.androiddebugger.hotswap

import com.sun.jdi.VirtualMachine

/**
 * Heuristic for "this debug build has R8/ProGuard minification enabled."
 * Per v1.5 spec §10.
 *
 * **Why it matters:** R8 rewrites class/method names to single letters
 * (`a.b.c`). After rewrite, the FQN the developer expects to redefine
 * (`com.example.LoginActivity`) doesn't match anything ART has loaded —
 * `RedefineClasses` returns `JVMTI_ERROR_INVALID_CLASS`. The error doesn't say
 * "this build is minified"; it just says "class not found." Detecting up front
 * lets the agent show a clear actionable refusal instead of mistakenly burning
 * a Gradle cycle.
 *
 * **Heuristic:** scan classes whose FQN starts with the app's package or the
 * heuristic root packages (`com.`, `io.`, `org.` — same prefixes R8 typically
 * targets). If more than 50% have a single-letter or two-letter simple name
 * with no digits-only suffix, flag as minified.
 *
 * **False positives:** vanishingly rare. A debug build that legitimately has
 * 50%+ single-letter classes would have to be authored deliberately with
 * `a.kt`, `b.kt`, etc. — we accept this trade-off.
 *
 * **False negatives:** if R8 is configured with `keep` rules covering most
 * classes (only stripping a handful), the heuristic misses. The eventual
 * `RedefineClasses` failure still surfaces as a structured error; the agent
 * just doesn't see the upfront warning.
 */
object MinifyDetector {

    /** Threshold (single-letter ratio) above which we declare the build minified. */
    private const val THRESHOLD: Double = 0.5

    /** Minimum number of app classes we need to see before deciding. Below this, return false. */
    private const val MIN_CLASSES_FOR_DECISION: Int = 20

    /**
     * Probe whether the attached VM appears to host a minified debug build.
     * Filters classes by the supplied [appPackagePrefix] when non-null, else by
     * the common heuristic prefixes.
     *
     * @param vm JDI VirtualMachine — attach time.
     * @param appPackagePrefix App package (e.g., "com.example.app"). Null = use heuristic prefixes.
     */
    fun isMinifiedBuild(vm: VirtualMachine, appPackagePrefix: String?): Boolean {
        val classes = runCatching { vm.allClasses() }.getOrElse { return false }
        val targetPrefixes = if (!appPackagePrefix.isNullOrBlank()) {
            listOf(appPackagePrefix)
        } else {
            HEURISTIC_PREFIXES
        }
        val candidates = classes.asSequence()
            .map { it.name() }
            // Drop anonymous/synthetic inners — `Foo$1`, `Foo$Companion`. They legitimately
            // have short names regardless of minification, so including them dilutes the signal.
            .filter { '$' !in it.substringAfterLast('.') }
            .filter { fqn -> targetPrefixes.any { fqn.startsWith("$it.") } }
            .toList()

        if (candidates.size < MIN_CLASSES_FOR_DECISION) return false

        val singleLetter = candidates.count { fqn ->
            val simple = fqn.substringAfterLast('.')
            simple.length <= 2 && simple.all { it.isLetterOrDigit() } &&
                !simple.all { it.isDigit() }
        }
        return singleLetter.toDouble() / candidates.size > THRESHOLD
    }

    /** Prefixes we sample when no app package is known. R8 typically targets these. */
    private val HEURISTIC_PREFIXES = listOf("com", "io", "org", "net", "ca")
}
