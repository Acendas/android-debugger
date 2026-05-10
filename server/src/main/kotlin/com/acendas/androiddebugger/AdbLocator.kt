package com.acendas.androiddebugger

import java.io.File

/**
 * Locate the `adb` binary across macOS, Linux, and Windows.
 *
 * Resolution order (fail-loud — no silent fallback to a guessed default):
 *   1. ADB_PATH env var (explicit override).
 *   2. ANDROID_HOME / ANDROID_SDK_ROOT + platform-tools/adb[.exe].
 *   3. PATH lookup for adb / adb.exe.
 *
 * Returns null if not found; callers surface an actionable message naming all three sources.
 */
object AdbLocator {

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val exeName: String
        get() = if (isWindows) "adb.exe" else "adb"

    fun find(): String? {
        // 1. Explicit env override.
        System.getenv("ADB_PATH")?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            if (f.canExecute()) return f.absolutePath
        }

        // 2. Android SDK root.
        val sdkRoot = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
        if (!sdkRoot.isNullOrBlank()) {
            val candidate = File(sdkRoot, "platform-tools/$exeName")
            if (candidate.canExecute()) return candidate.absolutePath
        }

        // 3. PATH lookup.
        val path = System.getenv("PATH").orEmpty()
        val sep = File.pathSeparator
        for (dir in path.split(sep).filter { it.isNotBlank() }) {
            val candidate = File(dir, exeName)
            if (candidate.canExecute()) return candidate.absolutePath
        }

        return null
    }
}
