package com.acendas.androiddebugger.plans

import ca.acendas.kfeel.api.FeelContext
import ca.acendas.kfeel.api.FeelValue
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers [DebuggerContext] — the FEEL `dbg.*` pre-pass rewriter. Tests use hand-written
 * dispatcher stubs (no mocking framework) because the recorded calls + canned returns
 * are simpler with an anonymous object than with Mockito stubs.
 */
class DebuggerContextTest {

    private class RecordingDispatcher(
        val canned: MutableMap<String, FeelValue> = mutableMapOf(),
    ) : DebuggerContext.DbgDispatcher {
        val calls = mutableListOf<String>()
        var throwOn: String? = null

        private fun record(tag: String): FeelValue {
            calls.add(tag)
            if (throwOn == tag) error("boom")
            return canned[tag] ?: FeelValue.Null
        }

        override fun instanceCount(classSignature: String): FeelValue =
            record("instance_count:$classSignature")

        override fun isReachable(ref: String, rootKind: String?): FeelValue =
            record("is_reachable:$ref:$rootKind")

        override fun threadState(threadKey: String): FeelValue =
            record("thread_state:$threadKey")

        override fun frameCount(threadKey: String?): FeelValue =
            record("frame_count:$threadKey")

        override fun hasCapability(capName: String): FeelValue =
            record("has_capability:$capName")

        override fun elapsedMs(): FeelValue = record("elapsed_ms:")

        override fun logcatSince(since: String, filter: String?): FeelValue =
            record("logcat_since:$since:$filter")
    }

    @Test
    fun single_call_rewrites_to_synthetic_variable() {
        val d = RecordingDispatcher()
        d.canned["instance_count:Lcom/foo/Bar;"] = FeelValue.Number(BigDecimal.valueOf(42))

        val out = DebuggerContext.preprocess(
            """dbg.instance_count("Lcom/foo/Bar;") > 0""",
            d,
        )

        assertEquals("__dbg_0 > 0", out.expression)
        assertEquals(FeelValue.Number(BigDecimal.valueOf(42)), out.injected["__dbg_0"])
        assertTrue(out.errors.isEmpty())
        assertEquals(listOf("instance_count:Lcom/foo/Bar;"), d.calls)
    }

    @Test
    fun multiple_distinct_calls_each_get_unique_synthetic_var() {
        val d = RecordingDispatcher()
        d.canned["instance_count:Foo"] = FeelValue.Number(BigDecimal.ONE)
        d.canned["elapsed_ms:"] = FeelValue.Number(BigDecimal.valueOf(500))

        val out = DebuggerContext.preprocess(
            """dbg.instance_count("Foo") > 0 and dbg.elapsed_ms() < 1000""",
            d,
        )

        assertEquals("__dbg_0 > 0 and __dbg_1 < 1000", out.expression)
        assertEquals(2, out.injected.size)
        assertEquals(FeelValue.Number(BigDecimal.ONE), out.injected["__dbg_0"])
        assertEquals(FeelValue.Number(BigDecimal.valueOf(500)), out.injected["__dbg_1"])
    }

    @Test
    fun duplicate_calls_reuse_same_var_with_single_evaluation() {
        val d = RecordingDispatcher()
        d.canned["instance_count:Foo"] = FeelValue.Number(BigDecimal.valueOf(3))

        val out = DebuggerContext.preprocess(
            """dbg.instance_count("Foo") + dbg.instance_count("Foo")""",
            d,
        )

        assertEquals("__dbg_0 + __dbg_0", out.expression)
        assertEquals(1, out.injected.size)
        // Critical: the dispatcher must only be invoked once for an identical call.
        assertEquals(1, d.calls.size)
    }

    @Test
    fun dbg_call_inside_double_quoted_literal_is_not_rewritten() {
        val d = RecordingDispatcher()
        val src = """ "the dbg.elapsed_ms() value" = "static" """
        val out = DebuggerContext.preprocess(src, d)
        assertEquals(src, out.expression)
        assertTrue(out.injected.isEmpty())
        assertTrue(d.calls.isEmpty())
    }

    @Test
    fun dbg_call_inside_single_quoted_literal_is_not_rewritten() {
        val d = RecordingDispatcher()
        val src = """ 'the dbg.elapsed_ms() value' = 'static' """
        val out = DebuggerContext.preprocess(src, d)
        assertEquals(src, out.expression)
        assertTrue(d.calls.isEmpty())
    }

    @Test
    fun unknown_function_records_error_and_substitutes_null() {
        val d = RecordingDispatcher()
        val out = DebuggerContext.preprocess("""dbg.no_such_fn("x") = null""", d)

        assertEquals("__dbg_0 = null", out.expression)
        assertEquals(FeelValue.Null, out.injected["__dbg_0"])
        assertEquals(1, out.errors.size)
        assertTrue(
            out.errors[0].contains("no_such_fn"),
            "error should name the missing fn, got: ${out.errors[0]}",
        )
    }

    @Test
    fun dispatcher_throwing_records_error_and_substitutes_null() {
        val d = RecordingDispatcher()
        d.throwOn = "instance_count:Foo"

        val out = DebuggerContext.preprocess("""dbg.instance_count("Foo") > 0""", d)

        assertEquals("__dbg_0 > 0", out.expression)
        assertEquals(FeelValue.Null, out.injected["__dbg_0"])
        assertEquals(1, out.errors.size)
        assertTrue(out.errors[0].contains("dispatcher threw"))
    }

    @Test
    fun integer_argument_passes_through_as_string_for_thread_key() {
        val d = RecordingDispatcher()
        d.canned["thread_state:123"] = FeelValue.Text("RUNNING")

        val out = DebuggerContext.preprocess("""dbg.thread_state(123) = "RUNNING"""", d)

        assertEquals(""""RUNNING"""", out.expression.substring(out.expression.indexOf("=") + 2))
        assertEquals(FeelValue.Text("RUNNING"), out.injected["__dbg_0"])
        assertEquals(listOf("thread_state:123"), d.calls)
    }

    @Test
    fun null_argument_is_accepted_for_optional_root_kind() {
        val d = RecordingDispatcher()
        d.canned["is_reachable:vobj#7:null"] = FeelValue.Boolean(true)

        val out = DebuggerContext.preprocess("""dbg.is_reachable("vobj#7", null)""", d)

        assertEquals("__dbg_0", out.expression)
        assertEquals(FeelValue.Boolean(true), out.injected["__dbg_0"])
        assertEquals(listOf("is_reachable:vobj#7:null"), d.calls)
    }

    @Test
    fun string_argument_supports_escape_sequences() {
        val d = RecordingDispatcher()
        // The parsed string should be: a"b\c (one literal quote, one backslash).
        val expected = """a"b\c"""
        d.canned["instance_count:$expected"] = FeelValue.Number(BigDecimal.ONE)

        val out = DebuggerContext.preprocess(
            """dbg.instance_count("a\"b\\c")""",
            d,
        )

        assertEquals("__dbg_0", out.expression)
        assertEquals(1, d.calls.size)
        assertEquals("instance_count:$expected", d.calls[0])
    }

    @Test
    fun empty_arg_list_works_for_no_arg_functions() {
        val d = RecordingDispatcher()
        d.canned["elapsed_ms:"] = FeelValue.Number(BigDecimal.valueOf(7))

        val out = DebuggerContext.preprocess("""dbg.elapsed_ms()""", d)

        assertEquals("__dbg_0", out.expression)
        assertEquals(FeelValue.Number(BigDecimal.valueOf(7)), out.injected["__dbg_0"])
    }

    @Test
    fun frame_count_works_with_zero_or_one_arg() {
        val d = RecordingDispatcher()
        d.canned["frame_count:null"] = FeelValue.Number(BigDecimal.valueOf(4))
        d.canned["frame_count:main"] = FeelValue.Number(BigDecimal.valueOf(5))

        val out = DebuggerContext.preprocess(
            """dbg.frame_count() + dbg.frame_count("main")""",
            d,
        )

        assertEquals("__dbg_0 + __dbg_1", out.expression)
        assertEquals(FeelValue.Number(BigDecimal.valueOf(4)), out.injected["__dbg_0"])
        assertEquals(FeelValue.Number(BigDecimal.valueOf(5)), out.injected["__dbg_1"])
    }

    @Test
    fun inject_helper_writes_all_synthetic_vars_into_context() {
        val ctx = FeelContext()
        val injected = mapOf(
            "__dbg_0" to FeelValue.Number(BigDecimal.valueOf(42)),
            "__dbg_1" to FeelValue.Text("hello"),
        )
        DebuggerContext.inject(ctx, injected)

        assertTrue(ctx.hasVariable("__dbg_0"))
        assertTrue(ctx.hasVariable("__dbg_1"))
        assertEquals(FeelValue.Number(BigDecimal.valueOf(42)), ctx.getVariable("__dbg_0"))
        assertEquals(FeelValue.Text("hello"), ctx.getVariable("__dbg_1"))
    }

    @Test
    fun expression_with_no_dbg_calls_is_returned_unchanged() {
        val d = RecordingDispatcher()
        val src = """count > 5 and name = "Alice""""
        val out = DebuggerContext.preprocess(src, d)
        assertEquals(src, out.expression)
        assertTrue(out.injected.isEmpty())
        assertNotNull(out.errors)
        assertTrue(out.errors.isEmpty())
    }

    @Test
    fun logcat_since_with_filter_passes_both_args_through() {
        val d = RecordingDispatcher()
        d.canned["logcat_since:plan_start:MainActivity"] = FeelValue.Text("E/MainActivity: NPE")

        val out = DebuggerContext.preprocess(
            """dbg.logcat_since("plan_start", "MainActivity")""",
            d,
        )

        assertEquals("__dbg_0", out.expression)
        assertEquals(FeelValue.Text("E/MainActivity: NPE"), out.injected["__dbg_0"])
    }

    @Test
    fun bad_arg_arity_records_error_with_null_substitution() {
        val d = RecordingDispatcher()
        // instance_count requires 1 arg; pass 0.
        val out = DebuggerContext.preprocess("""dbg.instance_count()""", d)
        assertEquals("__dbg_0", out.expression)
        assertEquals(FeelValue.Null, out.injected["__dbg_0"])
        assertEquals(1, out.errors.size)
    }
}
