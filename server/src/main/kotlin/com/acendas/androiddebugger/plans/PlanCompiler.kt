package com.acendas.androiddebugger.plans

import ca.acendas.kfeel.api.FeelExpression
import ca.acendas.kfeel.api.FeelParseException
import ca.acendas.kfeel.api.FeelValue
import kotlinx.serialization.SerializationException

/**
 * Validates a v1.7 debug plan: parses JSON into [Plan], then runs structural,
 * bounds, breakpoint-target, and FEEL syntax checks. The compiler is pure — it
 * never touches a live JDI session, the agent, or any of the executor surfaces.
 *
 * All recoverable errors are aggregated into a list so the agent can fix the
 * whole plan in one round-trip. Only the size cap and the schema-parse step
 * short-circuit (because no further checking is meaningful with a malformed
 * JSON tree).
 *
 * FEEL expressions are validated by:
 *   1. running them through [DebuggerContext.preprocess] with a
 *      side-effect-free dispatcher that returns `null` for every dbg.* call —
 *      this guarantees we exercise the same syntactic rewrite path the
 *      executor uses;
 *   2. calling [FeelExpression.parse] on the rewritten text and catching
 *      [FeelParseException].
 */
object PlanCompiler {

    /** Result of compiling a plan: either a valid Plan or a list of structured errors. */
    sealed class Result {
        data class Ok(val plan: Plan) : Result()
        data class Errors(val errors: List<PlanCompileError>) : Result()
    }

    /** Compile from raw JSON. Performs size check, schema parse, then full validation. */
    fun compile(json: String): Result {
        // Fail fast on oversize input — protects the parser from blowing up.
        // Use UTF-8 byte length, which is what the wire transfers.
        val bytes = json.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > Plan.MAX_PLAN_BYTES) {
            return Result.Errors(
                listOf(
                    PlanCompileError(
                        path = "$",
                        code = "size",
                        message = "plan JSON is $bytes bytes; max ${Plan.MAX_PLAN_BYTES}",
                    )
                )
            )
        }

        val plan = try {
            PlanJson.json.decodeFromString(Plan.serializer(), json)
        } catch (e: SerializationException) {
            return Result.Errors(
                listOf(
                    PlanCompileError(
                        path = "$",
                        code = "schema",
                        message = e.message ?: e.javaClass.simpleName,
                    )
                )
            )
        } catch (e: IllegalArgumentException) {
            // kotlinx-serialization can throw IAE for missing required fields in
            // some configurations — treat as schema error.
            return Result.Errors(
                listOf(
                    PlanCompileError(
                        path = "$",
                        code = "schema",
                        message = e.message ?: e.javaClass.simpleName,
                    )
                )
            )
        }

        return compile(plan)
    }

    /** Compile from an already-parsed Plan (no schema step). */
    fun compile(plan: Plan): Result {
        val errors = mutableListOf<PlanCompileError>()

        validatePlanBounds(plan, errors)
        validateSetup(plan.setup, errors)
        validateHypotheses(plan.hypotheses, errors)
        validateOnEvent(plan.onEvent, errors)
        validateTopLevelFeel(plan, errors)

        return if (errors.isEmpty()) Result.Ok(plan) else Result.Errors(errors)
    }

    // ---------------- bounds ----------------

    private fun validatePlanBounds(plan: Plan, errors: MutableList<PlanCompileError>) {
        if (plan.name.isBlank()) {
            errors += PlanCompileError(
                path = "name",
                code = "bounds",
                message = "plan name must not be blank",
            )
        }
        if (plan.timeoutMs <= 0) {
            errors += PlanCompileError(
                path = "timeout_ms",
                code = "bounds",
                message = "timeout_ms must be > 0",
            )
        } else if (plan.timeoutMs > Plan.MAX_TIMEOUT_MS) {
            // v1.7.1 (M3 hang mitigation): cap plan wall-clock at 10 min. A long-lived
            // plan blocks every other plan submission and risks holding the VM paused
            // for a customer-visible eternity. Split into multiple sequential dispatches
            // if the investigation genuinely needs more time.
            errors += PlanCompileError(
                path = "timeout_ms",
                code = "bounds",
                message = "timeout_ms (${plan.timeoutMs}) exceeds the ${Plan.MAX_TIMEOUT_MS}ms (10 min) hard cap.",
                hint = "Long investigations should be split into multiple sequential plan dispatches; the agent can chain them based on each report.",
            )
        }
        plan.maxEvents?.let {
            if (it <= 0) {
                errors += PlanCompileError(
                    path = "max_events",
                    code = "bounds",
                    message = "max_events must be > 0",
                )
            }
        }
        if (plan.maxEvents == null && plan.until == null) {
            errors += PlanCompileError(
                path = "$",
                code = "bounds",
                message = "plan must declare max_events or until",
            )
        }
        if (plan.maxEventRate < 1) {
            errors += PlanCompileError(
                path = "max_event_rate",
                code = "bounds",
                message = "max_event_rate must be >= 1",
            )
        }
        if (plan.maxSnapshots < 1) {
            errors += PlanCompileError(
                path = "max_snapshots",
                code = "bounds",
                message = "max_snapshots must be >= 1",
            )
        } else if (plan.maxSnapshots > Plan.GLOBAL_MAX_SNAPSHOTS) {
            errors += PlanCompileError(
                path = "max_snapshots",
                code = "bounds",
                message = "max_snapshots must be <= ${Plan.GLOBAL_MAX_SNAPSHOTS}",
            )
        }
    }

    // ---------------- setup ----------------

    private fun validateSetup(setup: List<SetupEntry>, errors: MutableList<PlanCompileError>) {
        setup.forEachIndexed { i, entry ->
            when (entry) {
                is SetupEntry.LineBp -> {
                    if (entry.file.isBlank()) {
                        errors += PlanCompileError(
                            path = "setup[$i].file",
                            code = "bp_target",
                            message = "file must not be blank",
                        )
                    }
                    if (entry.line <= 0) {
                        errors += PlanCompileError(
                            path = "setup[$i].line",
                            code = "bp_target",
                            message = "line must be > 0",
                        )
                    }
                    entry.condition?.let { cond ->
                        checkFeel(cond, "setup[$i].condition", errors)
                    }
                }
                is SetupEntry.ExceptionBp -> {
                    if (!entry.caught && !entry.uncaught) {
                        errors += PlanCompileError(
                            path = "setup[$i]",
                            code = "bp_target",
                            message = "exception_bp must have at least one of caught/uncaught set",
                        )
                    }
                }
                is SetupEntry.MethodEntryBp -> {
                    if (entry.methodClass.isBlank()) {
                        errors += PlanCompileError(
                            path = "setup[$i].method_class",
                            code = "bp_target",
                            message = "method_class must not be blank",
                        )
                    }
                    if (entry.methodName.isBlank()) {
                        errors += PlanCompileError(
                            path = "setup[$i].method_name",
                            code = "bp_target",
                            message = "method_name must not be blank",
                        )
                    }
                }
                is SetupEntry.MethodExitBp -> {
                    if (entry.methodClass.isBlank()) {
                        errors += PlanCompileError(
                            path = "setup[$i].method_class",
                            code = "bp_target",
                            message = "method_class must not be blank",
                        )
                    }
                    if (entry.methodName.isBlank()) {
                        errors += PlanCompileError(
                            path = "setup[$i].method_name",
                            code = "bp_target",
                            message = "method_name must not be blank",
                        )
                    }
                }
                is SetupEntry.FieldWatchpoint -> {
                    if (entry.fieldClass.isBlank()) {
                        errors += PlanCompileError(
                            path = "setup[$i].field_class",
                            code = "bp_target",
                            message = "field_class must not be blank",
                        )
                    }
                    if (entry.fieldName.isBlank()) {
                        errors += PlanCompileError(
                            path = "setup[$i].field_name",
                            code = "bp_target",
                            message = "field_name must not be blank",
                        )
                    }
                    if (!entry.wantAccess && !entry.wantModification) {
                        errors += PlanCompileError(
                            path = "setup[$i]",
                            code = "bp_target",
                            message = "field_watchpoint must have at least one of want_access/want_modification set",
                        )
                    }
                }
                is SetupEntry.ClassLoadBp -> {
                    if (entry.classPattern.isBlank()) {
                        errors += PlanCompileError(
                            path = "setup[$i].class_pattern",
                            code = "bp_target",
                            message = "class_pattern must not be blank",
                        )
                    }
                }
            }
        }
    }

    // ---------------- hypotheses ----------------

    private fun validateHypotheses(hypotheses: List<Hypothesis>, errors: MutableList<PlanCompileError>) {
        val seen = mutableSetOf<String>()
        hypotheses.forEachIndexed { i, h ->
            if (h.name.isBlank()) {
                errors += PlanCompileError(
                    path = "hypotheses[$i].name",
                    code = "schema",
                    message = "hypothesis name must not be blank",
                )
            } else if (!seen.add(h.name)) {
                errors += PlanCompileError(
                    path = "hypotheses[$i].name",
                    code = "schema",
                    message = "duplicate hypothesis name '${h.name}'",
                )
            }
            checkFeel(h.whenExpr, "hypotheses[$i].when", errors)
            checkFeel(h.expect, "hypotheses[$i].expect", errors)
        }
    }

    // ---------------- on_event ----------------

    private fun validateOnEvent(blocks: List<OnEvent>, errors: MutableList<PlanCompileError>) {
        blocks.forEachIndexed { bi, block ->
            checkFeel(block.match, "on_event[$bi].match", errors)
            block.actions.forEachIndexed { ai, action ->
                validateAction(action, "on_event[$bi].actions[$ai]", errors)
            }
        }
    }

    private fun validateAction(action: Action, basePath: String, errors: MutableList<PlanCompileError>) {
        when (action) {
            is Action.Snapshot -> {
                if (action.depth <= 0) {
                    errors += PlanCompileError(
                        path = "$basePath.depth",
                        code = "bounds",
                        message = "snapshot depth must be > 0",
                    )
                }
            }
            is Action.Feel -> {
                if (action.asName.isBlank()) {
                    errors += PlanCompileError(
                        path = "$basePath.as",
                        code = "schema",
                        message = "feel action 'as' must not be blank",
                    )
                }
                checkFeel(action.feel, "$basePath.feel", errors)
            }
            is Action.EvalMethod -> {
                if (action.asName.isBlank()) {
                    errors += PlanCompileError(
                        path = "$basePath.as",
                        code = "schema",
                        message = "eval_method 'as' must not be blank",
                    )
                }
                if (action.target.isBlank()) {
                    errors += PlanCompileError(
                        path = "$basePath.target",
                        code = "schema",
                        message = "eval_method target must not be blank",
                    )
                }
                if (action.method.isBlank()) {
                    errors += PlanCompileError(
                        path = "$basePath.method",
                        code = "schema",
                        message = "eval_method method must not be blank",
                    )
                }
            }
            is Action.Resume -> Unit
            Action.StepOver -> Unit
            Action.StepInto -> Unit
            Action.StepOut -> Unit
            is Action.YieldWhen -> {
                checkFeel(action.condition, "$basePath.yield_when", errors)
            }
            is Action.AbortWhen -> {
                checkFeel(action.condition, "$basePath.abort_when", errors)
            }
            is Action.Log -> {
                if (action.log.isBlank()) {
                    errors += PlanCompileError(
                        path = "$basePath.log",
                        code = "schema",
                        message = "log message must not be blank",
                    )
                }
            }
            is Action.SetVar -> {
                if (action.name.isBlank()) {
                    errors += PlanCompileError(
                        path = "$basePath.name",
                        code = "schema",
                        message = "set_var name must not be blank",
                    )
                }
                checkFeel(action.value, "$basePath.value", errors)
            }
        }
    }

    // ---------------- top-level FEEL ----------------

    private fun validateTopLevelFeel(plan: Plan, errors: MutableList<PlanCompileError>) {
        plan.until?.let { checkFeel(it, "until", errors) }
    }

    // ---------------- FEEL parse check ----------------

    private fun checkFeel(expr: String, path: String, errors: MutableList<PlanCompileError>) {
        // Preprocess dbg.* calls so the parser sees a pure FEEL expression. We
        // hand back canned nulls — the goal here is syntactic validation only.
        val rewrite = try {
            DebuggerContext.preprocess(expr, SyntaxOnlyDispatcher)
        } catch (e: Throwable) {
            errors += PlanCompileError(
                path = path,
                code = "feel_parse",
                message = "dbg.* preprocess failed: ${e.message ?: e.javaClass.simpleName}",
                hint = feelHintFor(expr, e.message),
            )
            return
        }

        try {
            FeelExpression.parse(rewrite.expression)
        } catch (e: FeelParseException) {
            errors += PlanCompileError(
                path = path,
                code = "feel_parse",
                message = e.message ?: "FEEL parse error",
                hint = feelHintFor(expr, e.message),
            )
        } catch (e: Throwable) {
            // Defensive: kfeel could throw something other than FeelParseException
            // for an unusual input. Treat as parse failure rather than crash.
            errors += PlanCompileError(
                path = path,
                code = "feel_parse",
                message = "${e.javaClass.simpleName}: ${e.message ?: ""}",
                hint = feelHintFor(expr, e.message),
            )
        }
    }

    /**
     * Produce an actionable hint for a FEEL parse error. v1.7.1: detect the common
     * "user used JSON-style double-quoted strings" mistake — kfeel 1.0.0 only
     * accepts single-quoted FEEL strings (`'foo'`, not `"foo"`). The soak that shipped
     * v1.7 surfaced this as a workspace-wide bug; the hint stops the next plan
     * author from losing 20 minutes to it.
     */
    private fun feelHintFor(expr: String, parseMessage: String?): String {
        val msg = parseMessage ?: ""
        val mentionsQuote = msg.contains("Unexpected character '\"'") ||
            msg.contains("Unexpected character \"")
        val exprHasDoubleQuoted = expr.contains('"')
        if (mentionsQuote && exprHasDoubleQuoted) {
            return "kfeel 1.0.0 requires single-quoted strings inside FEEL expressions " +
                "(e.g. event.kind = 'exception', frame.locals['token']). Double-quoted FEEL " +
                "strings are a JSON-vs-FEEL escape-level confusion — JSON keys/values keep " +
                "their double quotes, but the FEEL expression text inside them must use " +
                "single quotes for its own string literals."
        }
        return "FEEL syntax: see evaluate tool docs."
    }

    /**
     * Syntax-validation dispatcher: returns FeelValue.Null for every call. The
     * compiler uses this so dbg.*(...) calls in plan expressions rewrite to a
     * synthetic variable reference and the rest of the expression can be
     * handed to the FEEL parser cleanly.
     */
    private object SyntaxOnlyDispatcher : DebuggerContext.DbgDispatcher {
        override fun instanceCount(classSignature: String): FeelValue = FeelValue.Null
        override fun isReachable(ref: String, rootKind: String?): FeelValue = FeelValue.Null
        override fun threadState(threadKey: String): FeelValue = FeelValue.Null
        override fun frameCount(threadKey: String?): FeelValue = FeelValue.Null
        override fun hasCapability(capName: String): FeelValue = FeelValue.Null
        override fun elapsedMs(): FeelValue = FeelValue.Null
        override fun logcatSince(since: String, filter: String?): FeelValue = FeelValue.Null
    }
}
