package com.acendas.androiddebugger.breakpoints

import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the freeze-fix from v1.2.1 — `candidateClasses` must not call `sourceName()`
 * on every loaded class. The pre-filter operates on `name()` (locally cached, no
 * JDWP traffic) and shortlists by simple-name match. Without this, real Android
 * apps with 30k+ loaded classes were spending 30–150 seconds on `add_line_breakpoint`
 * while `Session.mutex` was held, blocking the whole MCP server.
 *
 * The load-bearing assertion is the **call-count** assertion: `sourceName()` must be
 * called only on classes whose simple name matches the source-file basename. Whether
 * those candidates then post-filter as matches depends on real JDI behavior we don't
 * model here (e.g., a Kotlin facade `UtilKt` reports `sourceName="Util.kt"` — the
 * original `.kt` file — while a top-level `MainActivity` reports `"MainActivity.kt"`).
 * The fakes below all return a fixed "match" sourceName so the post-filter doesn't
 * confuse the call-count signal.
 */
class SourceResolverPreFilterTest {

    /** Fake [ReferenceType] that always answers `sourceName == matchFile` so it passes the post-filter. */
    private fun fakeType(name: String, matchFile: String, sourceCalled: () -> Unit = {}): ReferenceType {
        val m = mock(ReferenceType::class.java)
        whenever(m.name()).thenReturn(name)
        whenever(m.sourceName()).thenAnswer {
            sourceCalled()
            matchFile
        }
        return m
    }

    private fun vmWith(types: List<ReferenceType>): VirtualMachine {
        val m = mock(VirtualMachine::class.java)
        whenever(m.allClasses()).thenReturn(types)
        return m
    }

    @Test
    fun classesForFile_calls_sourceName_only_on_simple_name_matches() {
        val sourceCalledOn = mutableListOf<String>()
        val match1 = fakeType("com.example.MainActivity", "MainActivity.kt") {
            sourceCalledOn += "com.example.MainActivity"
        }
        val match2 = fakeType("com.example.MainActivity\$Inner", "MainActivity.kt") {
            sourceCalledOn += "com.example.MainActivity\$Inner"
        }
        val match3 = fakeType("com.example.MainActivityKt", "MainActivity.kt") {
            sourceCalledOn += "com.example.MainActivityKt"
        }
        // 200 noise classes stand in for the 29 997 framework classes a real Android app loads.
        val noiseClasses = (1..200).map { i ->
            fakeType("com.android.framework.NoiseClass$i", "Other.kt") {
                sourceCalledOn += "com.android.framework.NoiseClass$i"
            }
        }

        val vm = vmWith(noiseClasses + listOf(match1, match2, match3))
        val result = SourceResolver.classesForFile(vm, "MainActivity.kt")

        // The load-bearing claim: sourceName() is called ONLY on the 3 simple-name
        // matches, never on the 200 noise classes. Without the pre-filter this would
        // be 203, and on a real Android app with 30k classes it would be 30k.
        assertEquals(
            3,
            sourceCalledOn.size,
            "sourceName() called on too many classes; was called on $sourceCalledOn",
        )
        // All 3 candidates pass the post-filter (their fake sourceName returns "MainActivity.kt").
        assertEquals(3, result.size)
        assertTrue(noiseClasses.none { it in result })
    }

    @Test
    fun classesForFile_picks_kotlin_file_facade_naming_into_candidates() {
        // Kotlin file `Util.kt` containing top-level functions compiles to a facade class
        // `UtilKt`. The pre-filter must accept BOTH `Util` and `UtilKt` as candidate names.
        val sourceCalledOn = mutableListOf<String>()
        val facade = fakeType("com.example.UtilKt", "Util.kt") { sourceCalledOn += "facade" }
        // `OtherKt` simple name is `OtherKt` — pre-filter rejects (not in {Util, UtilKt}).
        val unrelated = fakeType("com.example.OtherKt", "Util.kt") { sourceCalledOn += "unrelated" }
        val vm = vmWith(listOf(facade, unrelated))

        val result = SourceResolver.classesForFile(vm, "Util.kt")

        // Pre-filter narrows to {facade}. sourceName called only on facade.
        assertEquals(listOf("facade"), sourceCalledOn)
        assertEquals(1, result.size)
    }

    @Test
    fun classesForFile_returns_empty_for_blank_file() {
        val vm = vmWith(listOf(fakeType("com.example.Foo", "Foo.kt")))
        assertEquals(emptyList(), SourceResolver.classesForFile(vm, ""))
    }

    @Test
    fun classesForFile_strips_leading_path_components_before_name_match() {
        val sourceCalledOn = mutableListOf<String>()
        val match = fakeType("com.example.MainActivity", "MainActivity.kt") {
            sourceCalledOn += "match"
        }
        val vm = vmWith(listOf(match))

        val result = SourceResolver.classesForFile(vm, "src/main/java/com/example/MainActivity.kt")

        // Path prefix is stripped before name matching — match's simple name "MainActivity"
        // matches the basename "MainActivity" derived from the path-prefixed input.
        assertEquals(listOf("match"), sourceCalledOn)
        assertEquals(1, result.size)
    }
}
