package com.acendas.androiddebugger

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolve the plugin's root directory (the one containing `dist/`,
 * `.claude-plugin/`, `agent/`, etc.). Needed so v1.4's JVMTI agent attach
 * flow can locate the per-ABI `.so` files at
 * `${pluginRoot}/dist/agents/<abi>/libamdb_agent.so`.
 *
 * Resolution order:
 *   1. [pluginRootOverrideForTest] (test-only injection).
 *   2. `CLAUDE_PLUGIN_ROOT` env var (Claude Code injects this in newer versions).
 *   3. Walk up from the running jar's URL. The server jar lives at
 *      `${pluginRoot}/dist/android-debugger-server.jar`, so the parent of the
 *      `dist/` dir is the plugin root.
 *
 * Returns `null` when none of the above resolve — caller is expected to fail
 * loudly with `code: agent_root_unresolved` so the user knows to set
 * `CLAUDE_PLUGIN_ROOT` explicitly.
 */
object PluginRoot {

    /** Test hook — bypasses env / jar walking. Reset to null in production. */
    @Volatile
    internal var pluginRootOverrideForTest: Path? = null

    fun resolve(): Path? {
        pluginRootOverrideForTest?.let { return it }

        System.getenv("CLAUDE_PLUGIN_ROOT")?.takeIf { it.isNotBlank() }?.let { p ->
            val asPath = Paths.get(p)
            if (Files.isDirectory(asPath)) return asPath
        }

        // Walk up from the running jar. `PluginRoot::class.java.protectionDomain.codeSource.location`
        // points at the jar URL when packaged, or the classes/ dir during dev runs.
        val codeSource = runCatching {
            PluginRoot::class.java.protectionDomain?.codeSource?.location
        }.getOrNull() ?: return null

        val rawPath = runCatching {
            URLDecoder.decode(codeSource.path, StandardCharsets.UTF_8)
        }.getOrNull() ?: return null

        val start = Paths.get(rawPath).let {
            if (Files.isDirectory(it)) it else it.parent
        } ?: return null

        // Walk up looking for a directory that contains BOTH `dist/` and
        // `.claude-plugin/`. That's a strong signature for the plugin root.
        var cur: Path? = start
        repeat(8) {
            if (cur == null) return null
            val dist = cur!!.resolve("dist")
            val manifest = cur!!.resolve(".claude-plugin")
            if (Files.isDirectory(dist) && Files.isDirectory(manifest)) {
                return cur
            }
            cur = cur!!.parent
        }
        return null
    }

    /** Convenience — fail-loud variant for tool callers. */
    fun require(): Path = resolve() ?: throw ToolError(
        errorCode = ErrorCode.Internal,
        message = "Could not resolve CLAUDE_PLUGIN_ROOT.",
        hint = "Set CLAUDE_PLUGIN_ROOT explicitly, or run the server jar from " +
            "<plugin-root>/dist/android-debugger-server.jar.",
    )
}
