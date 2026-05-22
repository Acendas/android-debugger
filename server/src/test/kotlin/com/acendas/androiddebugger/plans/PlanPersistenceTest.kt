package com.acendas.androiddebugger.plans

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [PlanPersistence] — save → file → load round-trip, list metadata, schema
 * version handling, sanitization. Mirrors the style in SessionPersistenceTest.
 */
class PlanPersistenceTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("plan-persist-test-")
        PlanPersistence.dataDirOverrideForTest = tmp
        PlanPersistence.resetWarningForTest()
    }

    @AfterTest
    fun tearDown() {
        PlanPersistence.dataDirOverrideForTest = null
        PlanPersistence.resetWarningForTest()
        runCatching {
            Files.walkFileTree(
                tmp,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                        runCatching { Files.deleteIfExists(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Build a minimal-but-representative Plan covering the requested surface. */
    private fun samplePlan(name: String = "demo-plan"): Plan = Plan(
        name = name,
        version = PlanPersistence.CURRENT_SCHEMA_VERSION,
        timeoutMs = 30_000L,
        maxEvents = 100,
        setup = listOf(
            SetupEntry.LineBp(file = "MainActivity.kt", line = 42, condition = "user != null"),
            SetupEntry.MethodEntryBp(methodClass = "com.example.Foo", methodName = "bar"),
        ),
        hypotheses = listOf(
            Hypothesis(name = "user-non-null", whenExpr = "event.kind = \"breakpoint\"", expect = "user != null"),
        ),
        onEvent = listOf(
            OnEvent(
                match = "event.kind = \"breakpoint\"",
                actions = listOf(
                    Action.Snapshot(depth = 4),
                    Action.Feel(feel = "user.id", asName = "uid"),
                    Action.Resume(resume = true),
                ),
            ),
        ),
        harvest = listOf("uid"),
    )

    private fun withoutDataDir(block: () -> Unit) {
        val envSet = !System.getenv("CLAUDE_PLUGIN_DATA").isNullOrBlank()
        assumeFalse(envSet, "CLAUDE_PLUGIN_DATA set in host env; cannot exercise no-data-dir path")
        val prev = PlanPersistence.dataDirOverrideForTest
        PlanPersistence.dataDirOverrideForTest = null
        PlanPersistence.resetWarningForTest()
        try {
            block()
        } finally {
            PlanPersistence.dataDirOverrideForTest = prev
            PlanPersistence.resetWarningForTest()
        }
    }

    private fun plansDir(): Path = tmp.resolve("android-debugger").resolve("plans")

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    fun save_then_load_round_trips_a_full_plan() {
        val plan = samplePlan()
        val written = PlanPersistence.save("demo-plan", plan)
        assertNotNull(written)
        assertTrue(Files.exists(written))
        assertTrue(Files.size(written) > 0)

        val loaded = PlanPersistence.load("demo-plan")
        assertNotNull(loaded)
        assertEquals(plan.name, loaded.name)
        assertEquals(plan.version, loaded.version)
        assertEquals(plan.timeoutMs, loaded.timeoutMs)
        assertEquals(plan.maxEvents, loaded.maxEvents)
        assertEquals(plan.setup.size, loaded.setup.size)
        assertEquals(plan.hypotheses, loaded.hypotheses)
        assertEquals(plan.harvest, loaded.harvest)

        // Spot-check polymorphic discriminator is preserved.
        val firstSetup = loaded.setup.first() as SetupEntry.LineBp
        assertEquals("MainActivity.kt", firstSetup.file)
        assertEquals(42, firstSetup.line)
        assertEquals("user != null", firstSetup.condition)

        val secondSetup = loaded.setup[1] as SetupEntry.MethodEntryBp
        assertEquals("com.example.Foo", secondSetup.methodClass)
        assertEquals("bar", secondSetup.methodName)

        val onEvent = loaded.onEvent.single()
        assertTrue(onEvent.actions[0] is Action.Snapshot)
        assertTrue(onEvent.actions[1] is Action.Feel)
        assertTrue(onEvent.actions[2] is Action.Resume)
        assertEquals("uid", (onEvent.actions[1] as Action.Feel).asName)
    }

    @Test
    fun save_writes_file_under_plans_subdir() {
        val written = PlanPersistence.save("demo-plan", samplePlan())
        assertNotNull(written)
        assertEquals(plansDir().resolve("demo-plan.json").toAbsolutePath(), written.toAbsolutePath())
    }

    @Test
    fun load_of_missing_name_returns_null_not_throw() {
        assertNull(PlanPersistence.load("never-saved-plan"))
    }

    @Test
    fun delete_removes_and_is_idempotent() {
        PlanPersistence.save("doomed", samplePlan("doomed"))
        assertNotNull(PlanPersistence.load("doomed"))
        assertTrue(PlanPersistence.delete("doomed"))
        assertNull(PlanPersistence.load("doomed"))
        // Second delete: false, no throw.
        assertFalse(PlanPersistence.delete("doomed"))
    }

    @Test
    fun list_returns_metadata_in_mtime_descending_order() {
        PlanPersistence.save("alpha", samplePlan("alpha"))
        PlanPersistence.save("beta", samplePlan("beta"))
        PlanPersistence.save("gamma", samplePlan("gamma"))

        // Force a stable, distinguishable mtime ordering: gamma newest, beta middle, alpha oldest.
        val now = Instant.now()
        Files.setLastModifiedTime(plansDir().resolve("alpha.json"), FileTime.from(now.minusSeconds(300)))
        Files.setLastModifiedTime(plansDir().resolve("beta.json"), FileTime.from(now.minusSeconds(60)))
        Files.setLastModifiedTime(plansDir().resolve("gamma.json"), FileTime.from(now))

        val list = PlanPersistence.list()
        assertEquals(listOf("gamma", "beta", "alpha"), list.map { it.name })

        // SHA-256 hex is 64 chars lowercase.
        for (m in list) {
            assertEquals(64, m.sha256.length, "sha256 is hex")
            assertTrue(m.sha256.all { it.isDigit() || it in 'a'..'f' }, "sha256 lowercase hex")
            assertTrue(m.sizeBytes > 0, "size populated")
            assertEquals(PlanPersistence.CURRENT_SCHEMA_VERSION, m.version)
        }
    }

    @Test
    fun list_skips_corrupted_files_but_returns_others() {
        PlanPersistence.save("good", samplePlan("good"))
        // Write a non-JSON file directly into the plans dir to simulate corruption.
        Files.writeString(plansDir().resolve("bad.json"), "this is not json {{{")

        val list = PlanPersistence.list()
        val names = list.map { it.name }
        assertTrue("good" in names, "Good plan present")
        assertFalse("bad" in names, "Corrupted plan skipped")
    }

    @Test
    fun save_with_path_segments_in_name_is_sanitized() {
        // Try to escape with `/` and `..`.
        val written = PlanPersistence.save("../evil/plan", samplePlan("../evil/plan"))
        assertNotNull(written)
        val filename = written.fileName.toString()
        assertFalse('/' in filename, "Filename should not contain slashes: $filename")
        assertFalse(filename.startsWith(".."), "Filename should not start with ..: $filename")
        // File should live directly under plansDir(), not above it.
        assertEquals(plansDir().toAbsolutePath(), written.parent.toAbsolutePath())
    }

    @Test
    fun save_with_blank_name_throws() {
        assertFails { PlanPersistence.save("", samplePlan()) }
        assertFails { PlanPersistence.save("   ", samplePlan()) }
    }

    @Test
    fun load_with_blank_name_throws() {
        assertFails { PlanPersistence.load("") }
        assertFails { PlanPersistence.load("   ") }
    }

    @Test
    fun load_returns_null_when_schema_version_mismatches() {
        // Write a plan with version=99 directly to disk under the resolved plans path.
        Files.createDirectories(plansDir())
        val mismatched = """{"name":"future","version":99,"timeout_ms":1000,"max_events":1}"""
        Files.writeString(plansDir().resolve("future.json"), mismatched)

        assertNull(PlanPersistence.load("future"))
    }

    @Test
    fun save_overwrites_existing_file_atomically() {
        val first = samplePlan("rewrite").copy(timeoutMs = 1_000L)
        val second = samplePlan("rewrite").copy(timeoutMs = 9_999L)

        PlanPersistence.save("rewrite", first)
        val a = PlanPersistence.load("rewrite")
        assertNotNull(a)
        assertEquals(1_000L, a.timeoutMs)

        PlanPersistence.save("rewrite", second)
        val b = PlanPersistence.load("rewrite")
        assertNotNull(b)
        assertEquals(9_999L, b.timeoutMs)

        // After overwrite there should be no leftover .tmp file.
        val leftoverTmp = plansDir().resolve("rewrite.json.tmp")
        assertFalse(Files.exists(leftoverTmp), "tmp file should have been moved away")
    }

    @Test
    fun save_and_load_with_no_data_dir_are_noops() {
        withoutDataDir {
            assertNull(PlanPersistence.save("nope", samplePlan("nope")))
            assertNull(PlanPersistence.load("nope"))
            assertEquals(emptyList(), PlanPersistence.list())
            // A second call should also no-op without throwing.
            assertNull(PlanPersistence.save("nope-again", samplePlan("nope-again")))
        }
    }
}
