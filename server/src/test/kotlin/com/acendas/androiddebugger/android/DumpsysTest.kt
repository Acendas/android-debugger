package com.acendas.androiddebugger.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-function tests for the dumpsys parsers. Each test fixture is a canned snippet
 * trimmed from a real `dumpsys` output. No device required.
 */
class DumpsysTest {

    // ---------------- parseCurrentActivity ----------------

    @Test
    fun parses_resumed_activity_record() {
        val text = """
            Activity Stack:
              mResumedActivity: ActivityRecord{abcd1234 u0 com.example.app/.MainActivity t42}
              mFocusedActivity: ActivityRecord{abcd1234 u0 com.example.app/.MainActivity t42}
        """.trimIndent()
        val ca = Dumpsys.parseCurrentActivity(text)
        assertEquals("com.example.app", ca.packageName)
        assertEquals("com.example.app.MainActivity", ca.activity)
        assertEquals(42, ca.taskId)
    }

    @Test
    fun parses_top_resumed_activity_form() {
        val text = """
            Display #0:
              topResumedActivity=ActivityRecord{deadbeef u0 com.foo/.SettingsActivity t7}
        """.trimIndent()
        val ca = Dumpsys.parseCurrentActivity(text)
        assertEquals("com.foo", ca.packageName)
        // Note: trailing fragment is `.SettingsActivity` so the parser reconstitutes "com.foo.SettingsActivity"
        assertEquals("com.foo.SettingsActivity", ca.activity)
    }

    @Test
    fun returns_nulls_when_no_resumed_activity_present() {
        val text = "lots of activity stack output but no Resumed/Focused/Hist entry that matches"
        val ca = Dumpsys.parseCurrentActivity(text)
        assertNull(ca.packageName)
        assertNull(ca.activity)
    }

    @Test
    fun handles_fully_qualified_inner_class_form() {
        val text = "mResumedActivity: ActivityRecord{abcd u0 com.example/com.example.foo.Bar t1}"
        val ca = Dumpsys.parseCurrentActivity(text)
        assertEquals("com.example", ca.packageName)
        assertEquals("com.example.foo.Bar", ca.activity)
    }

    // ---------------- parseAppInfo ----------------

    @Test
    fun parses_debuggable_target_min_version_fields() {
        val text = """
            Packages:
              Package [com.example.app] (12345):
                versionCode=42 minSdk=26 targetSdk=34
                versionName=1.2.3
                pkgFlags=[ DEBUGGABLE ALLOW_BACKUP HAS_CODE ]
        """.trimIndent()
        val info = Dumpsys.parseAppInfo("com.example.app", text)
        assertEquals("com.example.app", info.packageName)
        assertTrue(info.debuggable)
        assertEquals(34, info.targetSdk)
        assertEquals(26, info.minSdk)
        assertEquals("1.2.3", info.versionName)
        assertEquals(42L, info.versionCode)
    }

    @Test
    fun debuggable_false_when_flag_absent() {
        val text = """
            Packages:
              Package [com.example.app] (12345):
                versionCode=1 minSdk=21 targetSdk=33
                versionName=1.0
                pkgFlags=[ ALLOW_BACKUP HAS_CODE ]
        """.trimIndent()
        val info = Dumpsys.parseAppInfo("com.example.app", text)
        assertFalse(info.debuggable)
    }

    @Test
    fun captures_distinct_processName_declarations() {
        val text = """
            Activity Resolver Table:
              MainActivity: processName=com.example.app
              ServiceA:
                processName=com.example.app:bg
              ServiceB:
                processName=com.example.app:bg
              ServiceC:
                processName=com.example.app:isolated
        """.trimIndent()
        val info = Dumpsys.parseAppInfo("com.example.app", text)
        assertEquals(
            listOf("com.example.app", "com.example.app:bg", "com.example.app:isolated"),
            info.declaredProcesses,
        )
    }

    @Test
    fun ignores_default_in_legacy_process_field() {
        val text = """
            Activity:
              process=default
              process=com.example.app:render
        """.trimIndent()
        val info = Dumpsys.parseAppInfo("com.example.app", text)
        assertEquals(listOf("com.example.app:render"), info.declaredProcesses)
    }

    @Test
    fun handles_unknown_package_with_minimal_output() {
        // dumpsys for a not-installed package emits "Unable to find package: foo".
        val text = "Unable to find package: foo"
        val info = Dumpsys.parseAppInfo("foo", text)
        assertEquals("foo", info.packageName)
        assertFalse(info.debuggable)
        assertNull(info.targetSdk)
        assertNull(info.minSdk)
        assertNull(info.versionName)
        assertNull(info.versionCode)
        assertEquals(emptyList(), info.declaredProcesses)
    }

    // ---------------- parseUiAutomatorXml ----------------

    @Test
    fun parses_simple_view_hierarchy_xml() {
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" resource-id="com.example:id/title" text="Hello" bounds="[10,20][300,80]"/>
                <node class="android.widget.Button" resource-id="com.example:id/ok" content-desc="OK button" bounds="[10,100][200,160]"/>
              </node>
            </hierarchy>
        """.trimIndent()
        val tree = Dumpsys.parseUiAutomatorXml(xml)
        assertNotNull(tree)
        assertEquals("android.widget.FrameLayout", tree.cls)
        assertEquals(2, tree.children.size)
        val title = tree.children[0]
        assertEquals("android.widget.TextView", title.cls)
        assertEquals("com.example:id/title", title.resourceId)
        assertEquals("Hello", title.text)
        val ok = tree.children[1]
        assertEquals("OK button", ok.contentDesc)
    }

    @Test
    fun returns_null_for_garbage_xml() {
        assertNull(Dumpsys.parseUiAutomatorXml("not even close to xml"))
    }
}
