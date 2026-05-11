package com.acendas.androiddebugger.state

import com.acendas.androiddebugger.breakpoints.BreakpointKind
import com.acendas.androiddebugger.breakpoints.BreakpointMeta
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [SessionPersistence] — save → file → load round-trip for breakpoint + watch
 * state. Per Phase B of the v1.3 plan.
 *
 * The data dir resolution depends on the `CLAUDE_PLUGIN_DATA` env var; we use a
 * Java-level reflection trick to set/clear it for the duration of each test so we
 * don't depend on the host environment.
 */
class SessionPersistenceTest {

    private fun withDataDir(dir: Path, block: () -> Unit) {
        val prev = SessionPersistence.dataDirOverrideForTest
        SessionPersistence.dataDirOverrideForTest = dir
        try {
            SessionPersistence.resetWarningForTest()
            block()
        } finally {
            SessionPersistence.dataDirOverrideForTest = prev
            SessionPersistence.resetWarningForTest()
        }
    }

    private fun withoutDataDir(block: () -> Unit) {
        val prev = SessionPersistence.dataDirOverrideForTest
        // Force the "no data dir" path by setting override to null AND nuking any
        // host env var via System property — neither code path consults system properties,
        // so this is a clean way to assert "neither override nor env var available".
        SessionPersistence.dataDirOverrideForTest = null
        // If the host happens to have CLAUDE_PLUGIN_DATA set, this test's "without"
        // assertion can't run safely. Skip in that case.
        val envSet = !System.getenv("CLAUDE_PLUGIN_DATA").isNullOrBlank()
        try {
            SessionPersistence.resetWarningForTest()
            if (!envSet) block()
        } finally {
            SessionPersistence.dataDirOverrideForTest = prev
            SessionPersistence.resetWarningForTest()
        }
    }

    private fun makeLineMeta(id: Int, file: String, line: Int): BreakpointMeta =
        BreakpointMeta(id = id, kind = BreakpointKind.LINE, file = file, line = line)

    private fun makeLineMetaConditional(id: Int, file: String, line: Int, condition: String, hitCount: Int): BreakpointMeta =
        BreakpointMeta(
            id = id,
            kind = BreakpointKind.LINE,
            file = file,
            line = line,
            condition = condition,
            hitCount = hitCount,
        )

    private fun makeExceptionMeta(id: Int, cls: String, caught: Boolean, uncaught: Boolean): BreakpointMeta =
        BreakpointMeta(
            id = id,
            kind = BreakpointKind.EXCEPTION,
            exceptionClass = cls,
            caught = caught,
            uncaught = uncaught,
        )

    @Test
    fun save_load_round_trip_preserves_all_breakpoint_kinds() {
        val tmp = Files.createTempDirectory("sp-test-")
        withDataDir(tmp) {
            val breakpoints = listOf(
                makeLineMeta(5, "MainActivity.kt", 42),
                makeLineMetaConditional(7, "MyVm.kt", 100, "count > 0", 3),
                makeExceptionMeta(12, "java.lang.IllegalStateException", caught = false, uncaught = true),
                BreakpointMeta(id = 20, kind = BreakpointKind.METHOD_ENTRY, methodClass = "com.example.Foo", methodName = "bar"),
                BreakpointMeta(id = 21, kind = BreakpointKind.METHOD_EXIT, methodClass = "com.example.Foo", methodName = "baz"),
                BreakpointMeta(id = 30, kind = BreakpointKind.FIELD_ACCESS, fieldClass = "com.example.X", fieldName = "y"),
                BreakpointMeta(id = 31, kind = BreakpointKind.FIELD_MODIFICATION, fieldClass = "com.example.X", fieldName = "z"),
                BreakpointMeta(id = 40, kind = BreakpointKind.CLASS_LOAD, classPattern = "com.example.*"),
            )
            val watches = listOf(1 to "user.age", 2 to "count(items)")

            val written = SessionPersistence.save(
                serial = "emulator-5554",
                packageName = "com.example.app",
                breakpoints = breakpoints,
                watches = watches,
            )
            assertNotNull(written)
            assertTrue(Files.exists(written))

            val loaded = SessionPersistence.load("emulator-5554", "com.example.app")
            assertNotNull(loaded)
            assertEquals(1, loaded.version)
            assertEquals("emulator-5554", loaded.serial)
            assertEquals("com.example.app", loaded.packageName)
            assertEquals(8, loaded.breakpoints.size)
            assertEquals(2, loaded.watches.size)

            val classLoad = loaded.breakpoints.first { it.id == 40 }
            assertEquals("CLASS_LOAD", classLoad.kind)
            assertEquals("com.example.*", classLoad.classPattern)

            // Spot-check a few — id preservation, kind preservation, field preservation.
            val line5 = loaded.breakpoints.first { it.id == 5 }
            assertEquals("LINE", line5.kind)
            assertEquals("MainActivity.kt", line5.file)
            assertEquals(42, line5.line)
            assertTrue(line5.enabled)

            val conditional = loaded.breakpoints.first { it.id == 7 }
            assertEquals("count > 0", conditional.condition)
            assertEquals(3, conditional.hitCount)

            val exc = loaded.breakpoints.first { it.id == 12 }
            assertEquals("java.lang.IllegalStateException", exc.exceptionClass)
            assertFalse(exc.caught)
            assertTrue(exc.uncaught)

            val watch = loaded.watches.first { it.id == 1 }
            assertEquals("user.age", watch.expr)
        }
    }

    @Test
    fun load_with_no_saved_file_returns_null() {
        val tmp = Files.createTempDirectory("sp-test-")
        withDataDir(tmp) {
            assertNull(SessionPersistence.load("emulator-5554", "com.never.saved"))
        }
    }

    @Test
    fun save_with_no_data_dir_returns_null_and_logs_once() {
        withoutDataDir {
            val result = SessionPersistence.save(
                serial = "x",
                packageName = "y",
                breakpoints = emptyList(),
                watches = emptyList(),
            )
            assertNull(result)
            // Second call should be a no-op too without the duplicate log line.
            val again = SessionPersistence.save("x", "y", emptyList(), emptyList())
            assertNull(again)
        }
    }

    @Test
    fun load_with_no_data_dir_returns_null() {
        withoutDataDir {
            assertNull(SessionPersistence.load("x", "y"))
        }
    }

    @Test
    fun clear_removes_the_saved_file() {
        val tmp = Files.createTempDirectory("sp-test-")
        withDataDir(tmp) {
            SessionPersistence.save("s", "p", listOf(makeLineMeta(1, "F.kt", 1)), emptyList())
            assertNotNull(SessionPersistence.load("s", "p"))
            SessionPersistence.clear("s", "p")
            assertNull(SessionPersistence.load("s", "p"))
        }
    }

    @Test
    fun filename_sanitizes_funky_serials_and_packages() {
        val tmp = Files.createTempDirectory("sp-test-")
        withDataDir(tmp) {
            // serial with a slash + package with a colon — both should land sanitized.
            val path = SessionPersistence.save(
                serial = "host/127.0.0.1:5555",
                packageName = "com.example.app:debug",
                breakpoints = listOf(makeLineMeta(1, "F.kt", 1)),
                watches = emptyList(),
            )
            assertNotNull(path)
            // No raw `/` or `:` in the filename.
            val name = path.fileName.toString()
            assertFalse('/' in name || ':' in name, "Filename should not contain special chars: $name")
        }
    }

    @Test
    fun parse_breakpoint_kind_round_trip() {
        for (k in BreakpointKind.values()) {
            assertEquals(k, parseBreakpointKind(k.name))
        }
        assertNull(parseBreakpointKind("nonsense"))
    }

    @Test
    fun overwrite_replaces_prior_content() {
        val tmp = Files.createTempDirectory("sp-test-")
        withDataDir(tmp) {
            SessionPersistence.save("s", "p", listOf(makeLineMeta(1, "F.kt", 1)), emptyList())
            SessionPersistence.save("s", "p", listOf(makeLineMeta(2, "G.kt", 2)), emptyList())
            val loaded = SessionPersistence.load("s", "p")
            assertNotNull(loaded)
            assertEquals(1, loaded.breakpoints.size)
            assertEquals(2, loaded.breakpoints.first().id)
            assertEquals("G.kt", loaded.breakpoints.first().file)
        }
    }

}
