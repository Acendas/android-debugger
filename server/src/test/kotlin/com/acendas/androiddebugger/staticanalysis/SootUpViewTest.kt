package com.acendas.androiddebugger.staticanalysis

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises [SootUpView.forRoot] (AC-11, AC-13): empty/missing `class_dirs` is a hard
 * [ErrorCode.ClassDirsEmptyOrMissing] naming standard Gradle output paths, and the
 * per-[AnalysisRoot] cache is keyed by a content fingerprint — unchanged `.class` mtimes
 * reuse the cached [AnalysisScene], a touched mtime rebuilds it.
 */
class SootUpViewTest {

    private val fixturesDir = "build/classes/java/test"

    // ------------------------------------------------------------------
    // AC-11 — missing class_dirs
    // ------------------------------------------------------------------

    @Test
    fun empty_class_dirs_is_class_dirs_empty_or_missing_with_rebuild_hint() {
        val error = assertFailsWith<ToolError> {
            SootUpView.forRoot(AnalysisRoot(classDirs = emptyList()))
        }

        assertEquals(ErrorCode.ClassDirsEmptyOrMissing, error.errorCode)
        assertNotNullHint(error)
    }

    @Test
    fun nonexistent_class_dirs_is_class_dirs_empty_or_missing_with_rebuild_hint() {
        val error = assertFailsWith<ToolError> {
            SootUpView.forRoot(AnalysisRoot(classDirs = listOf("/no/such/path/ad-static-analysis")))
        }

        assertEquals(ErrorCode.ClassDirsEmptyOrMissing, error.errorCode)
        assertNotNullHint(error)
    }

    private fun assertNotNullHint(error: ToolError) {
        val hint = error.hint
        assertTrue(hint != null)
        assertTrue(hint.contains("build/intermediates/javac/debug/classes"))
        assertTrue(hint.contains("build/tmp/kotlin-classes/debug"))
        assertTrue(hint.contains("./gradlew assembleDebug"))
    }

    // ------------------------------------------------------------------
    // AC-13 — view caching + fingerprint invalidation
    // ------------------------------------------------------------------

    @Test
    fun unchanged_fingerprint_reuses_cached_scene_and_touched_mtime_rebuilds_it() {
        val tempDir = Files.createTempDirectory("ad-sootupview-cache").toFile()
        try {
            val pkgDir = File(tempDir, "com/acendas/fixtures/hierarchy")
            pkgDir.mkdirs()
            val classFile = File(pkgDir, "Drawable.class")
            File(fixturesDir, "com/acendas/fixtures/hierarchy/Drawable.class").copyTo(classFile)

            val root = AnalysisRoot(classDirs = listOf(tempDir.absolutePath))

            val scene1 = SootUpView.forRoot(root)
            val scene2 = SootUpView.forRoot(root)
            assertSame(scene1, scene2)

            // Bump the .class file's mtime to change the fingerprint without changing
            // its content.
            val bumped = FileTime.fromMillis(classFile.lastModified() + 60_000)
            Files.setLastModifiedTime(classFile.toPath(), bumped)

            val scene3 = SootUpView.forRoot(root)
            assertNotSame(scene1, scene3)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
