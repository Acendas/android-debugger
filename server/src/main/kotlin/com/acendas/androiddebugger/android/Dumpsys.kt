package com.acendas.androiddebugger.android

/**
 * Pure parsers for `dumpsys` outputs. Per Stories 6.1.4 and 6.1.5.
 *
 * Kept as plain functions on string inputs so they're trivially unit-testable on
 * canned `dumpsys` text without an attached device. The MCP tools shell `adb shell
 * dumpsys ...` and feed the raw stdout into these parsers.
 *
 * dumpsys output format is not officially stable across Android versions, but the
 * specific fields we extract have been stable for ~5+ years; we use forgiving regex
 * with line-anchored prefixes and tolerate spacing differences.
 */
object Dumpsys {

    // ---------------- get_current_activity ----------------

    /** Result of parsing `dumpsys activity activities`. */
    data class CurrentActivity(
        val packageName: String?,
        val activity: String?,
        /** Stack id / display id when present, else null. */
        val taskId: Int?,
        /** Resumed/state hint ("RESUMED", "PAUSED", ...). */
        val state: String?,
    )

    /**
     * Parse the front-of-stack activity from `dumpsys activity activities`.
     *
     * Looks for one of these forms (which differ across Android versions):
     *
     *     mResumedActivity: ActivityRecord{abcd1234 u0 com.example/.MainActivity t42}
     *     ResumedActivity: ActivityRecord{abcd1234 u0 com.example/.MainActivity t42}
     *     mFocusedActivity: ActivityRecord{abcd1234 u0 com.example/.MainActivity t42}
     *     topResumedActivity=ActivityRecord{... com.example/.MainActivity ...}
     *
     * Returns the first match; `null` fields if nothing parses.
     */
    fun parseCurrentActivity(stdout: String): CurrentActivity {
        val patterns = listOf(
            Regex("""(?:mResumedActivity|ResumedActivity|mFocusedActivity|topResumedActivity)\s*[:=]?\s*ActivityRecord\{[^}]*\s+(\S+)/(\S+?)(?:\s+t(\d+))?\s*[}\s]"""),
            Regex("""mResumedActivity:\s*\S+\s+\S+\s+(\S+)/(\S+?)(?:\s+t(\d+))?\b"""),
        )
        for (re in patterns) {
            val m = re.find(stdout) ?: continue
            val pkg = m.groupValues[1].trim().ifBlank { null }
            val act = m.groupValues.getOrNull(2)?.trim()?.let {
                if (it.startsWith(".")) (pkg ?: "") + it else it
            }
            val taskId = m.groupValues.getOrNull(3)?.toIntOrNull()
            return CurrentActivity(
                packageName = pkg,
                activity = act,
                taskId = taskId,
                state = if (re.pattern.contains("Resumed")) "RESUMED" else "FOCUSED",
            )
        }
        // Last-ditch: parse `topResumedActivity=` stanza or "Hist  #0: ActivityRecord{...}"
        val histRe = Regex("""Hist\s+\#0:\s+ActivityRecord\{[^}]*\s+(\S+)/(\S+?)(?:\s+t(\d+))?\s*[}\s]""")
        histRe.find(stdout)?.let { m ->
            return CurrentActivity(
                packageName = m.groupValues[1].trim().ifBlank { null },
                activity = m.groupValues[2].trim().let { if (it.startsWith(".")) m.groupValues[1] + it else it },
                taskId = m.groupValues.getOrNull(3)?.toIntOrNull(),
                state = null,
            )
        }
        return CurrentActivity(null, null, null, null)
    }

    // ---------------- get_app_info ----------------

    /** Result of parsing `dumpsys package <pkg>`. */
    data class AppInfo(
        val packageName: String,
        val debuggable: Boolean,
        val targetSdk: Int?,
        val minSdk: Int?,
        val versionName: String?,
        val versionCode: Long?,
        /** Distinct `android:process` declarations found across components. */
        val declaredProcesses: List<String>,
    )

    /**
     * Parse fields from `dumpsys package <pkg>` output.
     *
     * - debuggable: looks for `flags=...DEBUGGABLE` (a packed flag list); true iff the
     *   token DEBUGGABLE appears among the flags. Some Android versions emit
     *   `pkgFlags=[ DEBUGGABLE ... ]`.
     * - targetSdk: line contains `targetSdk=N` (single integer).
     * - minSdk: line contains `minSdk=N`.
     * - versionName: line `versionName=...` up to whitespace.
     * - versionCode: line `versionCode=N`. May be followed by `targetSdk=`/`minSdk=`.
     * - declaredProcesses: any `processName=foo` lines (Activity/Service/Receiver/Provider sections).
     */
    fun parseAppInfo(packageName: String, stdout: String): AppInfo {
        val debuggable = run {
            // Try `flags=[ DEBUGGABLE ... ]` and `pkgFlags=[ ... DEBUGGABLE ... ]`.
            val re = Regex("""(?:^|\s)(?:pkgFlags|flags)=\[?[^\]\n]*\bDEBUGGABLE\b""")
            re.containsMatchIn(stdout)
        }
        val targetSdk = Regex("""(?<![A-Za-z])targetSdk=(\d+)""").find(stdout)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minSdk = Regex("""(?<![A-Za-z])minSdk=(\d+)""").find(stdout)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val versionName = Regex("""versionName=(\S+)""").find(stdout)?.groupValues?.getOrNull(1)
        val versionCode = Regex("""versionCode=(\d+)""").find(stdout)?.groupValues?.getOrNull(1)?.toLongOrNull()
        // processName= lines from each component declaration.
        val procs = Regex("""processName=(\S+)""")
            .findAll(stdout)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        // Older Android versions surface this as `process=foo` on Activity sections;
        // include those too, but only when in a component subsection (loose match —
        // the value is harmless if it ends up listing the package).
        Regex("""(?<![A-Za-z])process=(\S+)""")
            .findAll(stdout)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .filter { it.isNotEmpty() && !it.equals("default", ignoreCase = true) }
            .forEach { procs.add(it) }
        return AppInfo(
            packageName = packageName,
            debuggable = debuggable,
            targetSdk = targetSdk,
            minSdk = minSdk,
            versionName = versionName,
            versionCode = versionCode,
            declaredProcesses = procs.toList().sorted(),
        )
    }

    // ---------------- view hierarchy ----------------

    /**
     * Light shape we return from `dump_view_hierarchy` when XML parsing succeeds.
     * Falls back to the raw text if parsing fails, which the caller does in the tool.
     */
    data class ViewNode(
        val cls: String?,
        val resourceId: String?,
        val text: String?,
        val contentDesc: String?,
        val bounds: String?,
        val children: List<ViewNode>,
    )

    /**
     * Parse the XML produced by `uiautomator dump`. The structure is:
     *
     *   <hierarchy ...>
     *     <node class="..." resource-id="..." bounds="[l,t][r,b]" ...>
     *       <node ...> ... </node>
     *     </node>
     *   </hierarchy>
     *
     * We use the JDK's StAX parser (no extra dependency). Returns null if the input
     * isn't a parseable XML document.
     */
    fun parseUiAutomatorXml(xml: String): ViewNode? {
        return try {
            val factory = javax.xml.stream.XMLInputFactory.newInstance().apply {
                // Defense-in-depth: disable external entities — uiautomator XML never references any.
                runCatching { setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false) }
                runCatching { setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false) }
            }
            val reader = factory.createXMLStreamReader(java.io.StringReader(xml))
            val rootChildren = mutableListOf<ViewNode>()
            val stack: ArrayDeque<MutableList<ViewNode>> = ArrayDeque()
            stack.addLast(rootChildren)
            // We synthesize one "frame" per open <node>; on close we pop and assemble.
            data class Frame(val cls: String?, val resourceId: String?, val text: String?, val contentDesc: String?, val bounds: String?, val children: MutableList<ViewNode>)
            val frames: ArrayDeque<Frame> = ArrayDeque()
            while (reader.hasNext()) {
                when (reader.next()) {
                    javax.xml.stream.XMLStreamConstants.START_ELEMENT -> {
                        if (reader.localName == "node") {
                            val frame = Frame(
                                cls = reader.getAttributeValue(null, "class"),
                                resourceId = reader.getAttributeValue(null, "resource-id")?.takeIf { it.isNotEmpty() },
                                text = reader.getAttributeValue(null, "text")?.takeIf { it.isNotEmpty() },
                                contentDesc = reader.getAttributeValue(null, "content-desc")?.takeIf { it.isNotEmpty() },
                                bounds = reader.getAttributeValue(null, "bounds"),
                                children = mutableListOf(),
                            )
                            frames.addLast(frame)
                        }
                    }
                    javax.xml.stream.XMLStreamConstants.END_ELEMENT -> {
                        if (reader.localName == "node") {
                            val popped = frames.removeLast()
                            val node = ViewNode(
                                cls = popped.cls,
                                resourceId = popped.resourceId,
                                text = popped.text,
                                contentDesc = popped.contentDesc,
                                bounds = popped.bounds,
                                children = popped.children.toList(),
                            )
                            if (frames.isNotEmpty()) frames.last().children.add(node)
                            else rootChildren.add(node)
                        }
                    }
                }
            }
            // Wrap multiple top-level <node>s under a synthetic root if needed; otherwise
            // return the single root.
            when {
                rootChildren.isEmpty() -> null
                rootChildren.size == 1 -> rootChildren.first()
                else -> ViewNode(cls = "hierarchy", resourceId = null, text = null, contentDesc = null, bounds = null, children = rootChildren)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
