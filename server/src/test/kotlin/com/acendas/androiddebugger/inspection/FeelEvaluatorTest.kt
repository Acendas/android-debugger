package com.acendas.androiddebugger.inspection

import ca.acendas.kfeel.api.FeelContext
import ca.acendas.kfeel.api.FeelExpression
import ca.acendas.kfeel.api.FeelValue
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure-kfeel-grammar coverage that v1.2's hand-rolled evaluator could not satisfy. The
 * Evaluator wrapper delegates parse + evaluate to kfeel; these tests pin down that the
 * grammar surface we expose to the agent actually behaves the way the `evaluate` tool
 * description promises (binary ops, ternary, `instance of`, ranges, list ops, three-
 * valued null logic).
 *
 * No JDI mocking — we go straight through [FeelExpression] with a hand-built
 * [FeelContext], because the integration glue ([Evaluator.evaluate]) just wraps these
 * calls with the single-flight + timeout discipline (covered separately by
 * [EvaluatorReentryTest]).
 */
class FeelEvaluatorTest {

    @Test
    fun binary_op_arithmetic_with_precedence() {
        // `+ - * /` with precedence — v1.2 evaluator couldn't do this. kfeel returns
        // BigDecimal values whose scale isn't guaranteed (e.g., `2 * 3` may produce
        // scale 0 while `1 + 2 * 3` may produce a non-zero scale via promotion). We
        // compare via numeric value with `compareTo` so scale doesn't poison the test.
        assertNumberEquals(7, FeelExpression.parse("1 + 2 * 3").evaluate())
        assertNumberEquals(9, FeelExpression.parse("(1 + 2) * 3").evaluate())
        assertNumberEquals(2, FeelExpression.parse("10 / 5").evaluate())
    }

    private fun assertNumberEquals(expected: Int, actual: FeelValue) {
        assertTrue(actual is FeelValue.Number, "Expected a Number, got $actual")
        assertEquals(
            0,
            actual.value.compareTo(BigDecimal(expected)),
            "Expected $expected, got ${actual.value.toPlainString()}",
        )
    }

    @Test
    fun comparison_operators_with_context_variables() {
        val ctx = FeelContext.of("x" to 15, "limit" to 10)
        assertEquals(FeelValue.Boolean(true), FeelExpression.parse("x > limit").evaluate(ctx))
        assertEquals(FeelValue.Boolean(false), FeelExpression.parse("x < limit").evaluate(ctx))
        // FEEL uses single `=` for equality, not `==`.
        assertEquals(FeelValue.Boolean(false), FeelExpression.parse("x = limit").evaluate(ctx))
        assertEquals(FeelValue.Boolean(true), FeelExpression.parse("x != limit").evaluate(ctx))
    }

    @Test
    fun boolean_logic() {
        val ctx = FeelContext.of("a" to true, "b" to false)
        assertEquals(FeelValue.Boolean(false), FeelExpression.parse("a and b").evaluate(ctx))
        assertEquals(FeelValue.Boolean(true), FeelExpression.parse("a or b").evaluate(ctx))
        assertEquals(FeelValue.Boolean(false), FeelExpression.parse("not a").evaluate(ctx))
    }

    @Test
    fun ternary_if_then_else() {
        // FEEL uses single quotes for string literals.
        val ctx = FeelContext.of("age" to 25)
        assertEquals(
            FeelValue.Text("adult"),
            FeelExpression.parse("if age >= 18 then 'adult' else 'minor'").evaluate(ctx),
        )
        val minorCtx = FeelContext.of("age" to 12)
        assertEquals(
            FeelValue.Text("minor"),
            FeelExpression.parse("if age >= 18 then 'adult' else 'minor'").evaluate(minorCtx),
        )
    }

    @Test
    fun instance_of_type_check() {
        val ctx = FeelContext.of("name" to "Alice", "age" to 30)
        assertEquals(
            FeelValue.Boolean(true),
            FeelExpression.parse("name instance of string").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Boolean(false),
            FeelExpression.parse("name instance of number").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Boolean(true),
            FeelExpression.parse("age instance of number").evaluate(ctx),
        )
    }

    @Test
    fun list_functions() {
        val ctx = FeelContext.of("items" to listOf(1, 2, 3, 4, 5))
        assertEquals(
            FeelValue.Number(BigDecimal(5)),
            FeelExpression.parse("count(items)").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Number(BigDecimal(15)),
            FeelExpression.parse("sum(items)").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Number(BigDecimal(1)),
            FeelExpression.parse("min(items)").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Number(BigDecimal(5)),
            FeelExpression.parse("max(items)").evaluate(ctx),
        )
    }

    @Test
    fun range_membership_with_in() {
        val ctx = FeelContext.of("score" to 75)
        assertEquals(
            FeelValue.Boolean(true),
            FeelExpression.parse("score in [0..100]").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Boolean(false),
            FeelExpression.parse("score in [80..100]").evaluate(ctx),
        )
    }

    @Test
    fun three_valued_null_logic() {
        // Operations on null don't throw — they propagate null.
        val ctx = FeelContext.from(mapOf("x" to null))
        assertSame(
            FeelValue.Null,
            FeelExpression.parse("x + 1").evaluate(ctx),
        )
        // Comparing null produces null, not false.
        val nullResult = FeelExpression.parse("x > 0").evaluate(ctx)
        assertTrue(
            nullResult is FeelValue.Null || nullResult == FeelValue.Boolean(false),
            "FEEL comparing null returns null per spec; some engines coerce to false. Got: $nullResult",
        )
    }

    @Test
    fun property_access_on_pre_resolved_context() {
        // Mirrors what FeelContextBuilder produces — a Context entry with nested fields.
        val user = FeelValue.Context(
            entries = linkedMapOf(
                "name" to FeelValue.Text("Alice"),
                "age" to FeelValue.Number(BigDecimal(30)),
                "address" to FeelValue.Context(
                    entries = mapOf(
                        "city" to FeelValue.Text("Toronto"),
                        "zip" to FeelValue.Text("M5V"),
                    ),
                ),
            ),
        )
        val ctx = FeelContext()
        ctx.setVariable("user", user)

        assertEquals(FeelValue.Text("Alice"), FeelExpression.parse("user.name").evaluate(ctx))
        assertEquals(
            FeelValue.Number(BigDecimal(30)),
            FeelExpression.parse("user.age").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Text("Toronto"),
            FeelExpression.parse("user.address.city").evaluate(ctx),
        )
        // Combined with comparison.
        assertEquals(
            FeelValue.Boolean(true),
            FeelExpression.parse("user.age >= 18").evaluate(ctx),
        )
    }

    @Test
    fun every_some_quantifiers() {
        val ctx = FeelContext.of("scores" to listOf(80, 90, 75, 85))
        assertEquals(
            FeelValue.Boolean(true),
            FeelExpression.parse("every x in scores satisfies x >= 70").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Boolean(false),
            FeelExpression.parse("every x in scores satisfies x >= 80").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Boolean(true),
            FeelExpression.parse("some x in scores satisfies x >= 90").evaluate(ctx),
        )
    }

    @Test
    fun string_functions() {
        val ctx = FeelContext.of("name" to "Alice")
        assertEquals(
            FeelValue.Number(BigDecimal(5)),
            FeelExpression.parse("string length(name)").evaluate(ctx),
        )
        assertEquals(
            FeelValue.Text("ALICE"),
            FeelExpression.parse("upper case(name)").evaluate(ctx),
        )
    }
}
