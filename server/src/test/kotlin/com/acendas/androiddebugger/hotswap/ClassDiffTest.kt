package com.acendas.androiddebugger.hotswap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pre-validate shape-change detection. Each test pairs a baseline class with a
 * variant and asserts the right violation/warning entries appear.
 */
class ClassDiffTest {

    @Test
    fun identical_classes_produce_empty_diff() {
        val bytes = TestClassFixture.simpleClass("com/example/Foo", methodReturn = 0)
        val result = ClassDiff.diff(bytes, bytes)
        assertFalse(result.hasViolations, "identical classes should not violate")
        assertTrue(result.warnings.isEmpty(), "no warnings expected")
    }

    @Test
    fun method_body_change_only_is_safe() {
        val a = TestClassFixture.simpleClass("com/example/Foo", methodReturn = 0)
        val b = TestClassFixture.simpleClass("com/example/Foo", methodReturn = 42)
        val result = ClassDiff.diff(a, b)
        // Method-body-only change leaves shape identical; no violations.
        assertFalse(result.hasViolations, "method body change should be safe; got ${result.violations}")
    }

    @Test
    fun method_added_flagged_as_violation() {
        val a = TestClassFixture.simpleClass("com/example/Foo", extraMethod = false)
        val b = TestClassFixture.simpleClass("com/example/Foo", extraMethod = true)
        val result = ClassDiff.diff(a, b)
        assertTrue(result.hasViolations)
        assertTrue(
            result.violations.any { it.kind == "method_added" },
            "expected method_added violation, got: ${result.violations.map { it.kind }}",
        )
    }

    @Test
    fun method_removed_flagged_as_violation() {
        val a = TestClassFixture.simpleClass("com/example/Foo", extraMethod = true)
        val b = TestClassFixture.simpleClass("com/example/Foo", extraMethod = false)
        val result = ClassDiff.diff(a, b)
        assertTrue(result.hasViolations)
        assertTrue(
            result.violations.any { it.kind == "method_removed" },
            "expected method_removed, got: ${result.violations.map { it.kind }}",
        )
    }

    @Test
    fun field_added_flagged_as_violation() {
        val a = TestClassFixture.simpleClass("com/example/Foo", extraField = false)
        val b = TestClassFixture.simpleClass("com/example/Foo", extraField = true)
        val result = ClassDiff.diff(a, b)
        assertTrue(result.hasViolations)
        assertTrue(
            result.violations.any { it.kind == "field_added" },
            "expected field_added, got: ${result.violations.map { it.kind }}",
        )
    }

    @Test
    fun composable_method_emits_warning_not_violation() {
        val a = TestClassFixture.simpleClass("com/example/Foo")
        val b = TestClassFixture.composableMethodClass("com/example/Foo")
        val result = ClassDiff.diff(a, b)
        // The class shape changed (added `render`); we expect at least the
        // method_added violation. The Composable warning attaches separately.
        assertTrue(result.hasViolations, "shape change should still violate")
        assertTrue(
            result.warnings.any { it.kind == "composable_method" },
            "expected @Composable warning, got: ${result.warnings.map { it.kind }}",
        )
    }

    @Test
    fun coroutine_super_emits_warning() {
        val a = TestClassFixture.coroutineLikeClass("com/example/Bar\$lambda")
        val b = TestClassFixture.coroutineLikeClass("com/example/Bar\$lambda")
        val result = ClassDiff.diff(a, b)
        assertTrue(
            result.warnings.any { it.kind == "coroutine_state_machine" },
            "expected coroutine_state_machine warning, got: ${result.warnings.map { it.kind }}",
        )
    }

    @Test
    fun violations_to_json_preserves_kind_and_signature() {
        val a = TestClassFixture.simpleClass("com/example/Foo", extraMethod = false)
        val b = TestClassFixture.simpleClass("com/example/Foo", extraMethod = true)
        val result = ClassDiff.diff(a, b)
        val json = ClassDiff.violationsToJson(result.violations).toString()
        assertTrue(json.contains("method_added"), "expected method_added in json: $json")
        assertTrue(json.contains("extraMethod"), "expected method signature in json: $json")
    }

    @Test
    fun simple_class_count_baseline() {
        // Sanity: a simpleClass has <init>, run, counter.
        val bytes = TestClassFixture.simpleClass("com/example/Foo")
        val twin = TestClassFixture.simpleClass("com/example/Foo")
        // Same fixture twice → byte-identical (ASM is deterministic for our inputs).
        val r = ClassDiff.diff(bytes, twin)
        assertEquals(0, r.violations.size)
    }
}
