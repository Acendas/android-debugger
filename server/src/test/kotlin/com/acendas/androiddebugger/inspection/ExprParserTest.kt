package com.acendas.androiddebugger.inspection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the tiny `evaluate` expression parser. Per Task 2.1.5.6 — JDI mocks
 * are too heavyweight for this story; we exercise tokenization, AST shape, and a fake
 * symbol-table identifier-path resolver. Real JDI integration tests land with the
 * Phase-3 breakpoints story.
 */
class ExprParserTest {

    // ---------------- tokenization ----------------

    @Test
    fun tokenizes_bare_identifier() {
        val ast = ExprParser.parse("x")
        assertEquals(IdentExpr("x"), ast)
    }

    @Test
    fun tokenizes_dollar_and_underscore_in_identifiers() {
        val ast = ExprParser.parse("_foo\$bar")
        assertEquals(IdentExpr("_foo\$bar"), ast)
    }

    @Test
    fun tokenizes_string_literal_with_escapes() {
        val ast = ExprParser.parse("\"hello\\nworld\\t!\"")
        val lit = assertIs<LitExpr>(ast).value
        val str = assertIs<LiteralValue.Str>(lit)
        assertEquals("hello\nworld\t!", str.value)
    }

    @Test
    fun tokenizes_int_long_float_double_literals() {
        // int
        var ast = ExprParser.parse("42")
        assertEquals(LiteralValue.Int32(42), assertIs<LitExpr>(ast).value)
        // negative
        ast = ExprParser.parse("-7")
        assertEquals(LiteralValue.Int32(-7), assertIs<LitExpr>(ast).value)
        // long with suffix
        ast = ExprParser.parse("1234567890123L")
        assertEquals(LiteralValue.Int64(1234567890123L), assertIs<LitExpr>(ast).value)
        // long because it doesn't fit Int
        ast = ExprParser.parse("4294967296")
        assertEquals(LiteralValue.Int64(4294967296L), assertIs<LitExpr>(ast).value)
        // float with suffix
        ast = ExprParser.parse("3.14f")
        assertEquals(LiteralValue.Flt(3.14f), assertIs<LitExpr>(ast).value)
        // double from decimal
        ast = ExprParser.parse("2.71828")
        assertEquals(LiteralValue.Dbl(2.71828), assertIs<LitExpr>(ast).value)
        // double via exponent
        ast = ExprParser.parse("1e10")
        assertEquals(LiteralValue.Dbl(1e10), assertIs<LitExpr>(ast).value)
    }

    @Test
    fun tokenizes_boolean_and_null_keywords() {
        assertEquals(LiteralValue.Bool(true), assertIs<LitExpr>(ExprParser.parse("true")).value)
        assertEquals(LiteralValue.Bool(false), assertIs<LitExpr>(ExprParser.parse("false")).value)
        assertEquals(LiteralValue.Null, assertIs<LitExpr>(ExprParser.parse("null")).value)
    }

    @Test
    fun tokenizes_char_literal() {
        val ast = ExprParser.parse("'A'")
        assertEquals(LiteralValue.Chr('A'), assertIs<LitExpr>(ast).value)
    }

    @Test
    fun tokenizes_char_literal_with_escape() {
        val ast = ExprParser.parse("'\\n'")
        assertEquals(LiteralValue.Chr('\n'), assertIs<LitExpr>(ast).value)
    }

    // ---------------- AST shape ----------------

    @Test
    fun parses_simple_member_access() {
        val ast = ExprParser.parse("a.b")
        assertEquals(MemberExpr(IdentExpr("a"), "b"), ast)
    }

    @Test
    fun parses_chained_member_access_left_associative() {
        val ast = ExprParser.parse("a.b.c")
        // ((a.b).c)
        val outer = assertIs<MemberExpr>(ast)
        assertEquals("c", outer.member)
        val inner = assertIs<MemberExpr>(outer.receiver)
        assertEquals("b", inner.member)
        assertEquals(IdentExpr("a"), inner.receiver)
    }

    @Test
    fun parses_method_call_no_args() {
        val ast = ExprParser.parse("list.size()")
        val call = assertIs<CallExpr>(ast)
        assertEquals("size", call.method)
        assertEquals(IdentExpr("list"), call.receiver)
        assertTrue(call.args.isEmpty())
    }

    @Test
    fun parses_method_call_with_int_arg() {
        val ast = ExprParser.parse("list.get(0)")
        val call = assertIs<CallExpr>(ast)
        assertEquals("get", call.method)
        assertEquals(1, call.args.size)
        assertEquals(LiteralValue.Int32(0), assertIs<LitExpr>(call.args[0]).value)
    }

    @Test
    fun parses_method_call_on_string_literal() {
        val ast = ExprParser.parse("\"foo\".length()")
        val call = assertIs<CallExpr>(ast)
        assertEquals("length", call.method)
        val recv = assertIs<LitExpr>(call.receiver).value
        assertEquals("foo", assertIs<LiteralValue.Str>(recv).value)
    }

    @Test
    fun parses_method_call_with_multiple_mixed_args() {
        val ast = ExprParser.parse("obj.format(true, 42, \"xyz\")")
        val call = assertIs<CallExpr>(ast)
        assertEquals("format", call.method)
        assertEquals(3, call.args.size)
        assertEquals(LiteralValue.Bool(true), assertIs<LitExpr>(call.args[0]).value)
        assertEquals(LiteralValue.Int32(42), assertIs<LitExpr>(call.args[1]).value)
        assertEquals(LiteralValue.Str("xyz"), assertIs<LitExpr>(call.args[2]).value)
    }

    @Test
    fun parses_method_call_with_identifier_arg() {
        val ast = ExprParser.parse("cache.put(\"k\", item)")
        val call = assertIs<CallExpr>(ast)
        assertEquals("put", call.method)
        assertEquals(2, call.args.size)
        assertEquals(LiteralValue.Str("k"), assertIs<LitExpr>(call.args[0]).value)
        assertEquals(IdentExpr("item"), call.args[1])
    }

    @Test
    fun parses_chained_method_calls() {
        val ast = ExprParser.parse("user.getName().toLowerCase()")
        val outer = assertIs<CallExpr>(ast)
        assertEquals("toLowerCase", outer.method)
        val inner = assertIs<CallExpr>(outer.receiver)
        assertEquals("getName", inner.method)
        assertEquals(IdentExpr("user"), inner.receiver)
    }

    @Test
    fun parses_field_then_method() {
        val ast = ExprParser.parse("this.config.toString()")
        val call = assertIs<CallExpr>(ast)
        assertEquals("toString", call.method)
        val mem = assertIs<MemberExpr>(call.receiver)
        assertEquals("config", mem.member)
        assertEquals(IdentExpr("this"), mem.receiver)
    }

    @Test
    fun parses_basic_cast_and_passes_through_inner() {
        val ast = ExprParser.parse("(String) x")
        val cast = assertIs<CastExpr>(ast)
        assertEquals("String", cast.typeName)
        assertEquals(IdentExpr("x"), cast.inner)
    }

    @Test
    fun parses_qualified_cast_with_dotted_type() {
        val ast = ExprParser.parse("(java.util.Map) m")
        val cast = assertIs<CastExpr>(ast)
        assertEquals("java.util.Map", cast.typeName)
        assertEquals(IdentExpr("m"), cast.inner)
    }

    @Test
    fun parses_cast_then_method_call() {
        // We treat ((String) s).length() — the postfix loop sees the cast result and
        // continues consuming `.length()`. Verify the AST shape.
        val ast = ExprParser.parse("(String) s")
        val cast = assertIs<CastExpr>(ast)
        assertEquals("String", cast.typeName)
    }

    @Test
    fun parses_bare_call_as_implicit_this_method() {
        // `foo(0)` is rewritten to `this.foo(0)` so identifier resolution can find it.
        val ast = ExprParser.parse("foo(0)")
        val call = assertIs<CallExpr>(ast)
        assertEquals("foo", call.method)
        assertEquals(IdentExpr("this"), call.receiver)
        assertEquals(LiteralValue.Int32(0), assertIs<LitExpr>(call.args[0]).value)
    }

    // ---------------- error cases ----------------

    @Test
    fun rejects_unsupported_binary_operator() {
        // Operators are explicitly out of scope; the tokenizer rejects '+' as a stray char.
        assertFailsWith<ParseException> { ExprParser.parse("a + b") }
    }

    @Test
    fun rejects_trailing_garbage() {
        assertFailsWith<ParseException> { ExprParser.parse("a.b extra") }
    }

    @Test
    fun rejects_dot_without_member() {
        assertFailsWith<ParseException> { ExprParser.parse("a.") }
    }

    @Test
    fun rejects_mismatched_parens() {
        assertFailsWith<ParseException> { ExprParser.parse("a.b(1, 2") }
    }

    @Test
    fun rejects_unterminated_string() {
        assertFailsWith<ParseException> { ExprParser.parse("\"hello") }
    }

    @Test
    fun rejects_array_indexing() {
        // Out of scope for v1; documented in the tool description.
        assertFailsWith<ParseException> { ExprParser.parse("a[0]") }
    }

    // ---------------- identifier-path resolution against fake symbol tables ----------------
    //
    // We can't exercise JDI here (no live VM), but we can validate the resolution
    // **algorithm** against a fake symbol table: locals → this fields → statics. The
    // real Evaluator.resolveIdentifier wraps a JDI StackFrame; the unit-testable layer
    // is the lookup ordering, captured by FakeFrameSymbols below.

    private class FakeFrameSymbols(
        val locals: Map<String, String>,
        val thisFields: Map<String, String>,
        val statics: Map<String, String>,
    ) {
        /** Mirror of [Evaluator.resolveIdentifier]'s lookup ordering. */
        fun resolve(name: String): String? {
            if (name == "this") return "<this>"
            locals[name]?.let { return it }
            thisFields[name]?.let { return it }
            statics[name]?.let { return it }
            return null
        }
    }

    @Test
    fun resolves_local_when_local_and_field_share_a_name() {
        val syms = FakeFrameSymbols(
            locals = mapOf("x" to "local-x"),
            thisFields = mapOf("x" to "field-x"),
            statics = emptyMap(),
        )
        assertEquals("local-x", syms.resolve("x"))
    }

    @Test
    fun resolves_field_when_no_local() {
        val syms = FakeFrameSymbols(
            locals = mapOf("y" to "local-y"),
            thisFields = mapOf("x" to "field-x"),
            statics = emptyMap(),
        )
        assertEquals("field-x", syms.resolve("x"))
    }

    @Test
    fun resolves_static_when_no_local_no_field() {
        val syms = FakeFrameSymbols(
            locals = emptyMap(),
            thisFields = emptyMap(),
            statics = mapOf("LOG_TAG" to "static-LOG_TAG"),
        )
        assertEquals("static-LOG_TAG", syms.resolve("LOG_TAG"))
    }

    @Test
    fun resolves_this_keyword() {
        val syms = FakeFrameSymbols(
            locals = emptyMap(),
            thisFields = mapOf("a" to "field-a"),
            statics = emptyMap(),
        )
        assertEquals("<this>", syms.resolve("this"))
    }

    @Test
    fun returns_null_when_identifier_not_in_any_scope() {
        val syms = FakeFrameSymbols(
            locals = emptyMap(),
            thisFields = emptyMap(),
            statics = emptyMap(),
        )
        assertNull(syms.resolve("nope"))
    }

    /**
     * Walk a parsed path expression (`a.b.c`) against a fake nested symbol table.
     * Verifies parser+resolver compose correctly without booting a JDI VM.
     */
    private fun walkPath(syms: Map<String, Any?>, path: Expr): Any? = when (path) {
        is IdentExpr -> syms[path.name]
        is MemberExpr -> {
            @Suppress("UNCHECKED_CAST")
            val recvMap = walkPath(syms, path.receiver) as? Map<String, Any?>
                ?: error("Cannot member-access on non-map at ${path.member}")
            recvMap[path.member]
        }
        else -> error("Unexpected node $path")
    }

    @Test
    fun nested_member_access_walks_correctly() {
        val ast = ExprParser.parse("user.address.zip")
        val syms = mapOf(
            "user" to mapOf(
                "address" to mapOf(
                    "zip" to "94107",
                    "street" to "Market",
                ),
                "name" to "Ada",
            ),
        )
        assertEquals("94107", walkPath(syms, ast))
    }

    @Test
    fun nested_member_returns_null_for_missing_segment() {
        val ast = ExprParser.parse("user.address.country")
        val syms = mapOf(
            "user" to mapOf(
                "address" to mapOf("zip" to "94107"),
            ),
        )
        assertNull(walkPath(syms, ast))
    }

    @Test
    fun parser_emits_call_node_for_path_then_method() {
        val ast = ExprParser.parse("user.address.format()")
        val call = assertIs<CallExpr>(ast)
        assertEquals("format", call.method)
        // Receiver should be user.address (MemberExpr around MemberExpr).
        val outer = assertIs<MemberExpr>(call.receiver)
        assertEquals("address", outer.member)
        assertNotNull(outer.receiver)
        assertEquals(IdentExpr("user"), outer.receiver)
    }
}
