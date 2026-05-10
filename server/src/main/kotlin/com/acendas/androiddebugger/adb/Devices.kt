package com.acendas.androiddebugger.adb

/** A device or emulator visible to adb. `model`/`product`/`device` come from `adb devices -l` attrs. */
data class AdbDevice(
    val serial: String,
    val state: String,
    val model: String? = null,
    val product: String? = null,
    val device: String? = null,
)

/**
 * Parsing helpers for adb output formats. Pure functions — no I/O — so unit tests can
 * exercise every Android-version quirk by feeding canned stdout in.
 */
object DiscoveryParsers {

    /** Parse `adb devices -l` output. Skips the header line; ignores blanks. */
    fun parseDevices(stdout: String): List<AdbDevice> =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull { line ->
                val tokens = line.split(Regex("\\s+"))
                if (tokens.size < 2) return@mapNotNull null
                val attrs = tokens.drop(2)
                    .mapNotNull { token ->
                        val idx = token.indexOf(':')
                        if (idx > 0) token.substring(0, idx) to token.substring(idx + 1) else null
                    }
                    .toMap()
                AdbDevice(
                    serial = tokens[0],
                    state = tokens[1],
                    model = attrs["model"],
                    product = attrs["product"],
                    device = attrs["device"],
                )
            }
            .toList()

    /** Parse `adb jdwp` output (one PID per line). Tolerates partial stdout from a timeout. */
    fun parseJdwpPids(stdout: String): List<Int> =
        stdout.lineSequence()
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()
            .sorted()
            .toList()

    /**
     * Parse `adb shell ps -A` output and return PID → package for each PID in [pids].
     * The "package" is the NAME column (last token); we only return values that look like
     * Java/Kotlin package names (contain a `.`). Kernel processes / sh / etc. → null.
     *
     * Robust to column-count variation across Android versions: PID is column index 1,
     * NAME is the last token; we don't depend on intermediate columns.
     */
    fun parsePsOutput(stdout: String, pids: List<Int>): Map<Int, String?> {
        val wanted = pids.toSet()
        val found = mutableMapOf<Int, String?>()
        for (line in stdout.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val tokens = trimmed.split(Regex("\\s+"))
            if (tokens.size < 3) continue
            // Header lines start with USER or PID; skip.
            if (tokens[0] == "USER" || tokens[0] == "PID") continue
            val pid = tokens[1].toIntOrNull() ?: continue
            if (pid !in wanted) continue
            val name = tokens.last()
            found[pid] = if (looksLikePackage(name)) name else null
        }
        // Pids we never saw — explicitly null so the caller knows we tried.
        return pids.associateWith { found[it] }
    }

    private fun looksLikePackage(s: String): Boolean =
        s.contains('.') && s.first().isLetter() && s.none { it.isWhitespace() } && !s.startsWith("/")
}
