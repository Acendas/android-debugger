package com.acendas.androiddebugger.inspection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per BR-03 (v1.1.1 skill craft review): canonical framework-prefix list.
 *
 * Locks in the prefix coverage so a future "let me trim this list" PR has to update
 * this test deliberately. The pre-BR-03 callers each owned their own subset, and they
 * silently drifted (capability heuristic skipped `dalvik.`, Stepper didn't include it
 * in its glob list at one point, etc.).
 */
class FrameworkFramesTest {

    @Test
    fun every_documented_prefix_is_classified_as_framework() {
        // Each well-known framework prefix should classify a class living under it as
        // framework code. If a future change drops one, this test is the trip wire.
        val cases = listOf(
            "java.lang.NullPointerException",
            "javax.net.ssl.SSLContext",
            "android.os.Handler",
            "androidx.lifecycle.LifecycleOwner",
            "kotlin.collections.ArrayList",
            "kotlinx.coroutines.flow.Flow",
            "com.android.internal.util.Preconditions",
            "com.google.android.gms.tasks.Task",
            "dagger.internal.DaggerComponent",
            "dagger.hilt.android.internal.managers.ApplicationComponentManager",
            "hilt_aggregated_deps._com_example_App",
            "com.sun.proxy.\$Proxy0",
            "sun.misc.Unsafe",
            "jdk.internal.misc.VM",
            "dalvik.system.PathClassLoader",
            "libcore.io.IoUtils",
        )
        for (cls in cases) {
            assertTrue(FrameworkFrames.isFramework(cls), "expected `$cls` to be framework code")
        }
    }

    @Test
    fun user_code_is_not_framework() {
        // The canonical user packages we care about: app code, common 3rd-party libs
        // that aren't part of the platform.
        val cases = listOf(
            "com.example.app.MainActivity",
            "com.acendas.androiddebugger.tools.InspectionTools",
            "io.reactivex.Observable",
            "okhttp3.OkHttpClient",
            "retrofit2.Call",
            "MyTopLevelClass",
        )
        for (cls in cases) {
            assertFalse(FrameworkFrames.isFramework(cls), "expected `$cls` to be user code")
        }
    }

    @Test
    fun empty_string_is_not_framework() {
        // Defensive: a class with no name (shouldn't happen via JDI but the
        // contract is "starts with one of PREFIXES") doesn't match.
        assertFalse(FrameworkFrames.isFramework(""))
    }

    @Test
    fun prefix_match_requires_dot_terminated_boundary() {
        // `androidaffinity.Foo` shares 7 chars with `android.` but the prefix list
        // includes the trailing `.`, so it should NOT be classified as framework.
        // This is the load-bearing check that protects against `kotlin*` style false
        // positives.
        assertFalse(FrameworkFrames.isFramework("androidaffinity.Foo"))
        assertFalse(FrameworkFrames.isFramework("kotlinapp.Bar"))
        assertFalse(FrameworkFrames.isFramework("javafooLib.Baz"))
    }

    @Test
    fun prefix_globs_have_trailing_wildcard_for_jdi_filters() {
        // JDI's addClassExclusionFilter wants a trailing `*`. The glob list must keep
        // the prefix and append exactly one `*` per entry — no double wildcards, no
        // missing wildcards.
        for ((prefix, glob) in FrameworkFrames.PREFIXES.zip(FrameworkFrames.PREFIX_GLOBS)) {
            assertEquals("$prefix*", glob, "glob for $prefix should be $prefix*")
        }
        assertEquals(FrameworkFrames.PREFIXES.size, FrameworkFrames.PREFIX_GLOBS.size)
    }

    @Test
    fun first_user_frame_picks_first_non_framework_entry() {
        // Synthetic frames as plain strings — the helper takes a className extractor
        // so we don't need a JDI mock.
        val frames = listOf(
            "java.lang.Throwable",
            "kotlin.coroutines.Continuation",
            "com.example.app.PaymentService",
            "com.example.app.MainActivity",
        )
        val pick = FrameworkFrames.firstUserFrame(frames) { it }
        assertEquals("com.example.app.PaymentService", pick)
    }

    @Test
    fun first_user_frame_returns_null_when_all_frames_are_framework() {
        // Pure platform stack — no user-code anywhere. The OOM-from-GC-thread case.
        val frames = listOf(
            "java.lang.OutOfMemoryError",
            "dalvik.system.VMRuntime",
            "android.os.Handler",
        )
        assertNull(FrameworkFrames.firstUserFrame(frames) { it })
    }

    @Test
    fun first_user_frame_handles_empty_list() {
        assertNull(FrameworkFrames.firstUserFrame(emptyList<String>()) { it })
    }
}
