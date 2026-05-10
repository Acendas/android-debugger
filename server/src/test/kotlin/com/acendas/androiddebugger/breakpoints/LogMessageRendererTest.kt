package com.acendas.androiddebugger.breakpoints

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for the logpoint template parser. The full `render` path needs a paused JDI
 * thread, but the template tokenizer is pure-Kotlin and the lion's share of parsing logic
 * lives there — testing it here catches every off-by-one in `{...}` boundary detection,
 * brace escaping, and stray-brace handling.
 *
 * Per Phase 3 verification rule (test the renderer parser instead of the source resolver).
 */
class LogMessageRendererTest {

    // ---------------- pure text ----------------

    @Test
    fun parses_pure_text_into_one_text_segment() {
        val segs = LogMessageRenderer.parse("hello world")
        assertEquals(1, segs.size)
        val text = assertIs<LogMessageRenderer.Segment.Text>(segs[0])
        assertEquals("hello world", text.text)
    }

    @Test
    fun parses_empty_template_into_zero_segments() {
        val segs = LogMessageRenderer.parse("")
        assertTrue(segs.isEmpty())
    }

    // ---------------- single placeholder ----------------

    @Test
    fun parses_single_placeholder() {
        val segs = LogMessageRenderer.parse("{x}")
        assertEquals(1, segs.size)
        val expr = assertIs<LogMessageRenderer.Segment.Expr>(segs[0])
        assertEquals("x", expr.expr)
    }

    @Test
    fun trims_whitespace_inside_placeholder() {
        val segs = LogMessageRenderer.parse("{  user.id  }")
        val expr = assertIs<LogMessageRenderer.Segment.Expr>(segs[0])
        assertEquals("user.id", expr.expr)
    }

    // ---------------- mixed text + placeholder ----------------

    @Test
    fun parses_text_then_placeholder() {
        val segs = LogMessageRenderer.parse("user=")
        assertEquals(1, segs.size)
        assertIs<LogMessageRenderer.Segment.Text>(segs[0])
    }

    @Test
    fun parses_template_with_text_and_placeholder() {
        val segs = LogMessageRenderer.parse("user={user.id}")
        assertEquals(2, segs.size)
        assertEquals("user=", assertIs<LogMessageRenderer.Segment.Text>(segs[0]).text)
        assertEquals("user.id", assertIs<LogMessageRenderer.Segment.Expr>(segs[1]).expr)
    }

    @Test
    fun parses_multiple_placeholders_with_text_between() {
        val segs = LogMessageRenderer.parse("user={user.id} count={items.size()}")
        assertEquals(4, segs.size)
        assertEquals("user=", assertIs<LogMessageRenderer.Segment.Text>(segs[0]).text)
        assertEquals("user.id", assertIs<LogMessageRenderer.Segment.Expr>(segs[1]).expr)
        assertEquals(" count=", assertIs<LogMessageRenderer.Segment.Text>(segs[2]).text)
        assertEquals("items.size()", assertIs<LogMessageRenderer.Segment.Expr>(segs[3]).expr)
    }

    @Test
    fun parses_template_starting_and_ending_with_placeholders() {
        val segs = LogMessageRenderer.parse("{a}+{b}")
        assertEquals(3, segs.size)
        assertEquals("a", assertIs<LogMessageRenderer.Segment.Expr>(segs[0]).expr)
        assertEquals("+", assertIs<LogMessageRenderer.Segment.Text>(segs[1]).text)
        assertEquals("b", assertIs<LogMessageRenderer.Segment.Expr>(segs[2]).expr)
    }

    // ---------------- escaped braces ----------------

    @Test
    fun escapes_double_open_brace_as_literal() {
        val segs = LogMessageRenderer.parse("{{not a placeholder}}")
        // {{ -> '{', '}' is literal-stray (since we already consumed the opener), }} -> literal '}'.
        assertEquals(1, segs.size)
        val text = assertIs<LogMessageRenderer.Segment.Text>(segs[0])
        assertEquals("{not a placeholder}", text.text)
    }

    @Test
    fun mixes_escaped_and_real_placeholders() {
        val segs = LogMessageRenderer.parse("{{literal}} {x}")
        // [Text("{literal} "), Expr("x")]
        assertEquals(2, segs.size)
        assertEquals("{literal} ", assertIs<LogMessageRenderer.Segment.Text>(segs[0]).text)
        assertEquals("x", assertIs<LogMessageRenderer.Segment.Expr>(segs[1]).expr)
    }

    // ---------------- malformed / edge cases ----------------

    @Test
    fun unterminated_placeholder_falls_back_to_text() {
        // Don't throw — the logpoint must never crash a hot loop.
        val segs = LogMessageRenderer.parse("user={user.id and forgot brace")
        // First emits "user=" text, then on '{' starts the placeholder, but no '}' is found
        // so the rest of the template is appended as raw text.
        assertEquals(2, segs.size)
        assertEquals("user=", assertIs<LogMessageRenderer.Segment.Text>(segs[0]).text)
        assertEquals(
            "{user.id and forgot brace",
            assertIs<LogMessageRenderer.Segment.Text>(segs[1]).text,
        )
    }

    @Test
    fun stray_close_brace_is_literal_text() {
        val segs = LogMessageRenderer.parse("} alone")
        assertEquals(1, segs.size)
        assertEquals("} alone", assertIs<LogMessageRenderer.Segment.Text>(segs[0]).text)
    }

    @Test
    fun empty_placeholder_is_an_expr_segment_with_empty_string() {
        // The renderer's runtime path treats "" as "render nothing".
        val segs = LogMessageRenderer.parse("a{}b")
        assertEquals(3, segs.size)
        assertEquals("a", assertIs<LogMessageRenderer.Segment.Text>(segs[0]).text)
        assertEquals("", assertIs<LogMessageRenderer.Segment.Expr>(segs[1]).expr)
        assertEquals("b", assertIs<LogMessageRenderer.Segment.Text>(segs[2]).text)
    }

    @Test
    fun nested_braces_are_not_supported_outer_wins() {
        // We don't support nested template syntax. The first '}' closes the placeholder.
        val segs = LogMessageRenderer.parse("{outer{inner}}")
        // [Expr("outer{inner"), Text("}")]
        assertEquals(2, segs.size)
        val expr = assertIs<LogMessageRenderer.Segment.Expr>(segs[0])
        assertEquals("outer{inner", expr.expr)
        assertEquals("}", assertIs<LogMessageRenderer.Segment.Text>(segs[1]).text)
    }

    @Test
    fun placeholder_can_contain_method_call_syntax() {
        // The parser doesn't actually validate the inner expression — Evaluator does
        // that at render time. But it must capture the expression intact.
        val segs = LogMessageRenderer.parse("size={items.size()}")
        val expr = assertIs<LogMessageRenderer.Segment.Expr>(segs[1])
        assertEquals("items.size()", expr.expr)
    }

    @Test
    fun placeholder_with_dotted_path() {
        val segs = LogMessageRenderer.parse("zip={user.address.zip}")
        val expr = assertIs<LogMessageRenderer.Segment.Expr>(segs[1])
        assertEquals("user.address.zip", expr.expr)
    }

    @Test
    fun adjacent_placeholders_stay_separate() {
        val segs = LogMessageRenderer.parse("{a}{b}")
        assertEquals(2, segs.size)
        assertEquals("a", assertIs<LogMessageRenderer.Segment.Expr>(segs[0]).expr)
        assertEquals("b", assertIs<LogMessageRenderer.Segment.Expr>(segs[1]).expr)
    }

    @Test
    fun many_segments_round_trip_in_order() {
        // Sanity check that segment ordering matches input order — important because
        // the renderer concatenates them in order to produce the output string.
        val template = "begin {a} mid1 {b} mid2 {c.d} end"
        val segs = LogMessageRenderer.parse(template)
        // Expected: 7 segments in this exact order.
        assertEquals(7, segs.size)
        val orderedTexts = segs.mapNotNull {
            when (it) {
                is LogMessageRenderer.Segment.Text -> "T:${it.text}"
                is LogMessageRenderer.Segment.Expr -> "E:${it.expr}"
            }
        }
        assertEquals(
            listOf("T:begin ", "E:a", "T: mid1 ", "E:b", "T: mid2 ", "E:c.d", "T: end"),
            orderedTexts,
        )
    }
}
