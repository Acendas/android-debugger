package com.acendas.androiddebugger.staticanalysis

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.views.JavaView
import java.io.File
import java.nio.file.Paths

/**
 * Inputs that define one SootUp analysis scope. [classDirs] are the app's own compiled
 * `.class` output (mapped to [SourceType.Application] — this set defines "the app's own
 * code" for caller-scanning in [GraphExtractors] and the synthetic-collapse heuristics).
 * [extraClasspath] and [androidJar] are mapped to [SourceType.Library].
 *
 * A data class so it can key [SootUpView]'s cache by structural equality.
 */
data class AnalysisRoot(
    val classDirs: List<String>,
    val extraClasspath: List<String> = emptyList(),
    val androidJar: String? = null,
)

/**
 * A built SootUp [view] plus the identifier factory used to resolve class/method names.
 *
 * [applicationClassNames] is the set of FQNs found under [AnalysisRoot.classDirs] — this
 * is "the app's own code" for [GraphExtractors.callGraph]'s caller-scan and
 * [GraphExtractors.packageGraph]'s in-scope-package computation. Derived from `.class`
 * file paths rather than [sootup.core.model.SourceType] because that's a property of the
 * input *location*, not queryable per-[sootup.core.model.SootClass] from the view.
 */
class AnalysisScene(
    val view: JavaView,
    val identifierFactory: JavaIdentifierFactory,
    val applicationClassNames: Set<String>,
)

/**
 * Builds and caches [AnalysisScene]s for an [AnalysisRoot].
 *
 * Per the v1.8 plan: cached per-[AnalysisRoot], keyed additionally by a content
 * [fingerprint] (max `.class` mtime across [AnalysisRoot.classDirs]) so a mid-session
 * recompile (AC-13) is picked up on the next call without a stale-cache hit.
 */
object SootUpView {

    private data class CacheEntry(val fingerprint: Long, val scene: AnalysisScene)

    private val cache = mutableMapOf<AnalysisRoot, CacheEntry>()

    /**
     * Resolve (building or reusing from cache) the [AnalysisScene] for [root].
     *
     * Throws [ToolError] with [ErrorCode.ClassDirsEmptyOrMissing] if none of
     * [AnalysisRoot.classDirs] exist (AC-11), or [ErrorCode.SceneBuildFailed] if SootUp
     * itself fails while constructing the view's type hierarchy.
     */
    fun forRoot(root: AnalysisRoot): AnalysisScene {
        val existingClassDirs = root.classDirs.filter { File(it).isDirectory }
        if (existingClassDirs.isEmpty()) {
            throw ToolError(
                ErrorCode.ClassDirsEmptyOrMissing,
                "None of the given class_dirs exist: ${root.classDirs}",
                hint = "Build the project first (e.g. `./gradlew assembleDebug`), then pass " +
                    "compiled-output directories such as `build/intermediates/javac/debug/classes` " +
                    "and `build/tmp/kotlin-classes/debug`.",
            )
        }

        val fingerprint = fingerprintOf(existingClassDirs)
        cache[root]?.let { entry ->
            if (entry.fingerprint == fingerprint) return entry.scene
        }

        val inputLocations = mutableListOf<AnalysisInputLocation>()
        for (dir in existingClassDirs) {
            inputLocations.add(PathBasedAnalysisInputLocation.create(Paths.get(dir), SourceType.Application))
        }
        for (cp in root.extraClasspath) {
            if (File(cp).exists()) {
                inputLocations.add(PathBasedAnalysisInputLocation.create(Paths.get(cp), SourceType.Library))
            }
        }
        val androidJar = root.androidJar?.takeIf { File(it).exists() }
            ?: AndroidJarResolver.find(apiLevel = null)
        if (androidJar != null) {
            inputLocations.add(PathBasedAnalysisInputLocation.create(Paths.get(androidJar), SourceType.Library))
        }

        val view = JavaView(inputLocations)
        try {
            // Force eager construction of the type hierarchy now, so a bad classpath
            // entry (e.g. a corrupt .class file) surfaces here as scene_build_failed
            // rather than mid-traversal in an extractor.
            view.typeHierarchy
        } catch (e: Exception) {
            throw ToolError(
                ErrorCode.SceneBuildFailed,
                "Failed to build SootUp view: ${e.message ?: e::class.simpleName}",
                hint = "Check that class_dirs/extra_classpath/android_jar point at valid .class/.jar paths.",
            )
        }

        val scene = AnalysisScene(view, JavaIdentifierFactory.getInstance(), applicationClassNames(existingClassDirs))
        cache[root] = CacheEntry(fingerprint, scene)
        return scene
    }

    /** Max `.class` mtime across [dirs], used to invalidate the cache (AC-13). */
    private fun fingerprintOf(dirs: List<String>): Long {
        var max = 0L
        for (dir in dirs) {
            File(dir).walkTopDown().forEach { f ->
                if (f.isFile && f.extension == "class") {
                    val mtime = f.lastModified()
                    if (mtime > max) max = mtime
                }
            }
        }
        return max
    }

    /** FQNs of every `.class` file under [dirs], derived from path relative to its dir root. */
    private fun applicationClassNames(dirs: List<String>): Set<String> {
        val names = mutableSetOf<String>()
        for (dir in dirs) {
            val root = File(dir)
            root.walkTopDown().forEach { f ->
                if (f.isFile && f.extension == "class") {
                    val rel = f.relativeTo(root).path
                    if (rel == "module-info.class") return@forEach
                    names.add(rel.removeSuffix(".class").replace(File.separatorChar, '.'))
                }
            }
        }
        return names
    }
}

/**
 * Mirrors [com.acendas.androiddebugger.AdbLocator]'s resolution shape for `android.jar`:
 * env-based SDK root + a fixed subpath, no PATH-lookup analog (there's no "android.jar on
 * PATH" concept). Returns `null` (not fail-loud) when unresolved — an absent android.jar
 * is a [warnings]-level concern for the caller (cross-reference resolution against the
 * framework is best-effort), not a hard failure.
 */
object AndroidJarResolver {

    /**
     * Resolve `<sdk>/platforms/android-<level>/android.jar`. [apiLevel] is the
     * `android_api_level` tool param if the caller supplied one; otherwise the highest
     * `android-N` directory under `<sdk>/platforms/` is used.
     */
    fun find(apiLevel: Int?): String? {
        val sdkRoot = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: return null

        val platformsDir = File(sdkRoot, "platforms")
        if (!platformsDir.isDirectory) return null

        val level = apiLevel ?: latestApiLevel(platformsDir) ?: return null
        val jar = File(platformsDir, "android-$level/android.jar")
        return if (jar.isFile) jar.absolutePath else null
    }

    private fun latestApiLevel(platformsDir: File): Int? {
        return platformsDir.listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
            ?.mapNotNull { it.name.removePrefix("android-").toIntOrNull() }
            ?.maxOrNull()
    }
}
