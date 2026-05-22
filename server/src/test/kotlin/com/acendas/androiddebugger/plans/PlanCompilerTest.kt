package com.acendas.androiddebugger.plans

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Validates the PlanCompiler covers schema, bounds, breakpoint-target, and
 * FEEL-parse checks, and that all recoverable errors aggregate into one list.
 *
 * Tests round-trip plans through PlanJson.json where possible (exercising the
 * JSON entry-point) and use the typed [Plan] constructor for cases where
 * authoring the JSON by hand is noisy.
 */
class PlanCompilerTest {

    // ---------------- happy path ----------------

    @Test
    fun minimal_valid_plan_compiles() {
        val plan = Plan(
            name = "demo",
            timeoutMs = 30_000,
            maxEvents = 50,
            onEvent = listOf(
                OnEvent(match = "true", actions = listOf(Action.Resume()))
            ),
        )
        val result = PlanCompiler.compile(plan)
        assertOk(result)
    }

    @Test
    fun valid_plan_from_json_compiles() {
        val json = PlanJson.json.encodeToString(
            Plan(
                name = "demo",
                timeoutMs = 5_000,
                maxEvents = 10,
                onEvent = listOf(
                    OnEvent(match = "true", actions = listOf(Action.Resume()))
                ),
            )
        )
        val result = PlanCompiler.compile(json)
        assertOk(result)
    }

    // ---------------- bounds ----------------

    @Test
    fun plan_without_max_events_or_until_fails() {
        val plan = Plan(
            name = "demo",
            timeoutMs = 30_000,
            onEvent = listOf(OnEvent(actions = listOf(Action.Resume()))),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "$" && it.code == "bounds" && it.message.contains("max_events or until") },
            "expected bounds error at \$, got $errors",
        )
    }

    @Test
    fun timeout_zero_fails() {
        val plan = Plan(
            name = "demo",
            timeoutMs = 0,
            maxEvents = 5,
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(errors.any { it.path == "timeout_ms" && it.code == "bounds" }, errors.toString())
    }

    @Test
    fun negative_max_events_fails() {
        val plan = Plan(
            name = "demo",
            timeoutMs = 5_000,
            maxEvents = -1,
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(errors.any { it.path == "max_events" && it.code == "bounds" }, errors.toString())
    }

    @Test
    fun blank_name_fails() {
        val plan = Plan(
            name = "  ",
            timeoutMs = 1_000,
            maxEvents = 5,
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(errors.any { it.path == "name" }, errors.toString())
    }

    @Test
    fun max_snapshots_above_global_cap_fails() {
        val plan = Plan(
            name = "demo",
            timeoutMs = 5_000,
            maxEvents = 5,
            maxSnapshots = Plan.GLOBAL_MAX_SNAPSHOTS + 1,
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(errors.any { it.path == "max_snapshots" }, errors.toString())
    }

    @Test
    fun max_event_rate_zero_fails() {
        val plan = Plan(
            name = "demo",
            timeoutMs = 5_000,
            maxEvents = 5,
            maxEventRate = 0,
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(errors.any { it.path == "max_event_rate" }, errors.toString())
    }

    // ---------------- setup ----------------

    @Test
    fun line_bp_blank_file_fails() {
        val plan = base().copy(
            setup = listOf(SetupEntry.LineBp(file = "", line = 10)),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(errors.any { it.path == "setup[0].file" && it.code == "bp_target" }, errors.toString())
    }

    @Test
    fun line_bp_line_zero_fails() {
        val plan = base().copy(
            setup = listOf(SetupEntry.LineBp(file = "Foo.kt", line = 0)),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(errors.any { it.path == "setup[0].line" && it.code == "bp_target" }, errors.toString())
    }

    @Test
    fun exception_bp_with_both_false_fails() {
        val plan = base().copy(
            setup = listOf(SetupEntry.ExceptionBp(caught = false, uncaught = false)),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "setup[0]" && it.code == "bp_target" },
            errors.toString(),
        )
    }

    @Test
    fun method_entry_bp_blank_class_fails() {
        val plan = base().copy(
            setup = listOf(SetupEntry.MethodEntryBp(methodClass = "", methodName = "foo")),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "setup[0].method_class" && it.code == "bp_target" },
            errors.toString(),
        )
    }

    @Test
    fun field_watchpoint_no_modes_fails() {
        val plan = base().copy(
            setup = listOf(
                SetupEntry.FieldWatchpoint(
                    fieldClass = "Foo",
                    fieldName = "bar",
                    wantAccess = false,
                    wantModification = false,
                )
            ),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(errors.any { it.path == "setup[0]" && it.code == "bp_target" }, errors.toString())
    }

    @Test
    fun class_load_bp_blank_pattern_fails() {
        val plan = base().copy(
            setup = listOf(SetupEntry.ClassLoadBp(classPattern = "")),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "setup[0].class_pattern" && it.code == "bp_target" },
            errors.toString(),
        )
    }

    // ---------------- FEEL ----------------

    @Test
    fun hypothesis_when_feel_parse_error() {
        val plan = base().copy(
            hypotheses = listOf(Hypothesis(name = "h1", whenExpr = "(", expect = "true")),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "hypotheses[0].when" && it.code == "feel_parse" },
            errors.toString(),
        )
    }

    @Test
    fun hypothesis_expect_feel_parse_error() {
        val plan = base().copy(
            hypotheses = listOf(Hypothesis(name = "h1", whenExpr = "true", expect = "1 + + 2")),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "hypotheses[0].expect" && it.code == "feel_parse" },
            errors.toString(),
        )
    }

    @Test
    fun dbg_elapsed_ms_parses_cleanly() {
        val plan = base().copy(
            hypotheses = listOf(
                Hypothesis(name = "h1", whenExpr = "dbg.elapsed_ms() > 1000", expect = "true"),
            ),
        )
        val result = PlanCompiler.compile(plan)
        assertOk(result)
    }

    @Test
    fun action_feel_parse_error_uses_correct_path() {
        val plan = base().copy(
            onEvent = listOf(
                OnEvent(
                    match = "true",
                    actions = listOf(
                        Action.Resume(),
                        Action.Feel(feel = "1 + ", asName = "x"),
                    ),
                )
            ),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "on_event[0].actions[1].feel" && it.code == "feel_parse" },
            errors.toString(),
        )
    }

    @Test
    fun yield_when_feel_parse_error() {
        val plan = base().copy(
            onEvent = listOf(
                OnEvent(
                    actions = listOf(Action.YieldWhen(condition = "1 +")),
                )
            ),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "on_event[0].actions[0].yield_when" && it.code == "feel_parse" },
            errors.toString(),
        )
    }

    // ---------------- actions ----------------

    @Test
    fun snapshot_depth_zero_fails() {
        val plan = base().copy(
            onEvent = listOf(
                OnEvent(actions = listOf(Action.Snapshot(depth = 0))),
            ),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "on_event[0].actions[0].depth" && it.code == "bounds" },
            errors.toString(),
        )
    }

    @Test
    fun feel_action_blank_as_fails() {
        val plan = base().copy(
            onEvent = listOf(
                OnEvent(actions = listOf(Action.Feel(feel = "1 + 1", asName = ""))),
            ),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "on_event[0].actions[0].as" },
            errors.toString(),
        )
    }

    @Test
    fun set_var_blank_name_fails() {
        val plan = base().copy(
            onEvent = listOf(
                OnEvent(actions = listOf(Action.SetVar(name = "", value = "1"))),
            ),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.path == "on_event[0].actions[0].name" },
            errors.toString(),
        )
    }

    // ---------------- duplicates ----------------

    @Test
    fun duplicate_hypothesis_names_flagged() {
        val plan = base().copy(
            hypotheses = listOf(
                Hypothesis(name = "h1", whenExpr = "true", expect = "true"),
                Hypothesis(name = "h1", whenExpr = "true", expect = "true"),
            ),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any {
                it.path == "hypotheses[1].name" &&
                    it.code == "schema" &&
                    it.message.contains("duplicate")
            },
            errors.toString(),
        )
    }

    // ---------------- aggregation ----------------

    @Test
    fun multiple_errors_aggregate() {
        // Stack 4 independent errors and assert we see all of them.
        val plan = base().copy(
            timeoutMs = 0, // bounds @ timeout_ms
            setup = listOf(SetupEntry.LineBp(file = "", line = 0)), // 2 bp_target errors
            hypotheses = listOf(Hypothesis(name = "h1", whenExpr = "(", expect = "true")), // feel_parse
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(errors.size >= 4, "expected at least 4 errors, got ${errors.size}: $errors")
        assertTrue(errors.any { it.path == "timeout_ms" })
        assertTrue(errors.any { it.path == "setup[0].file" })
        assertTrue(errors.any { it.path == "setup[0].line" })
        assertTrue(errors.any { it.path == "hypotheses[0].when" })
    }

    // ---------------- size + parse ----------------

    @Test
    fun oversize_json_rejected() {
        // Build a JSON string just over MAX_PLAN_BYTES without actually
        // allocating excessive memory — a single string field is enough.
        val padding = "x".repeat((Plan.MAX_PLAN_BYTES + 16).toInt())
        val json = """{"name":"$padding","timeout_ms":1000,"max_events":1}"""
        val errors = assertErrors(PlanCompiler.compile(json))
        assertEquals(1, errors.size, errors.toString())
        assertEquals("size", errors[0].code)
    }

    @Test
    fun malformed_json_returns_schema_error() {
        val errors = assertErrors(PlanCompiler.compile("{ not json"))
        assertTrue(errors.any { it.code == "schema" && it.path == "$" }, errors.toString())
    }

    @Test
    fun compile_from_typed_plan_runs_feel_check() {
        // Goes through the typed compile(Plan) overload, not the JSON path.
        val plan = base().copy(
            hypotheses = listOf(Hypothesis(name = "h1", whenExpr = "1 +", expect = "true")),
        )
        val errors = assertErrors(PlanCompiler.compile(plan))
        assertTrue(
            errors.any { it.code == "feel_parse" && it.path == "hypotheses[0].when" },
            errors.toString(),
        )
    }

    // ---------------- helpers ----------------

    private fun base(): Plan = Plan(
        name = "demo",
        timeoutMs = 5_000,
        maxEvents = 10,
    )

    private fun assertOk(result: PlanCompiler.Result): Plan {
        return when (result) {
            is PlanCompiler.Result.Ok -> result.plan
            is PlanCompiler.Result.Errors -> {
                fail("expected Ok, got errors: ${result.errors}")
            }
        }
    }

    private fun assertErrors(result: PlanCompiler.Result): List<PlanCompileError> {
        return when (result) {
            is PlanCompiler.Result.Ok -> fail("expected Errors, got Ok")
            is PlanCompiler.Result.Errors -> {
                assertNotNull(result.errors)
                assertTrue(result.errors.isNotEmpty(), "errors list must be non-empty")
                result.errors
            }
        }
    }
}
