package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.plans.Plan
import com.acendas.androiddebugger.plans.PlanCompileError
import com.acendas.androiddebugger.plans.PlanCompiler
import com.acendas.androiddebugger.plans.PlanExecutor
import com.acendas.androiddebugger.plans.PlanJson
import com.acendas.androiddebugger.plans.PlanPersistence
import com.acendas.androiddebugger.plans.PlanReport
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolErr
import com.acendas.androiddebugger.toolOk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * v1.7 — Debug Plan MCP tools.
 *
 * Eight tools form the user-facing surface:
 *   - `run_debug_plan`   : compile + launch a plan; returns plan_id immediately
 *   - `pause_plan`       : hand control back to the agent (VM stays paused)
 *   - `abort_plan`       : clean shutdown, VM resumes
 *   - `validate_plan`    : compile only (no launch)
 *   - `list_plans`       : enumerate persisted plans
 *   - `save_plan`        : persist a plan by name
 *   - `load_plan`        : read a persisted plan by name
 *   - `delete_plan`      : remove a persisted plan by name
 *
 * Lifecycle tools (`pause_plan` / `abort_plan` / `validate_plan` / `list_plans` /
 * `save_plan` / `load_plan` / `delete_plan`) pass `allowsDuringPlan = true` so the
 * agent can drive plan control even when the executor holds the VM. Only the
 * `run_debug_plan` launch path defaults `allowsDuringPlan = false`, since claiming
 * the slot is mutually exclusive with another running plan.
 *
 * All errors flow back as structured `{ ok:false, code, message, ... }`; compile
 * failures additionally carry a JSON `errors` array so the agent can fix every
 * mistake in one round-trip.
 */
object PlanTools {

    fun register(server: Server) {
        registerRunDebugPlan(server)
        registerPausePlan(server)
        registerAbortPlan(server)
        registerValidatePlan(server)
        registerListPlans(server)
        registerSavePlan(server)
        registerLoadPlan(server)
        registerDeletePlan(server)
    }

    // -------------------------------------------------------------------------
    // run_debug_plan
    // -------------------------------------------------------------------------

    private fun registerRunDebugPlan(server: Server) {
        server.addTool(
            name = "run_debug_plan",
            description = "v1.7: Launch a Debug Plan that runs deterministically over the JDI event stream. " +
                "Compiles + validates the plan JSON, then dispatches execution as a background coroutine " +
                "that emits plan_progress events. Returns immediately with plan_id; agent polls " +
                "wait_for_event(types=['plan_progress']) for streaming visibility, or calls pause_plan/abort_plan " +
                "to take over. The Plan DSL declares setup breakpoints, on_event handlers with actions " +
                "(snapshot, feel, eval_method, resume, step_*, yield_when, abort_when, log, set_var), " +
                "hypotheses graded as matched/contradicted/inconclusive, and harvest spec. See the spec at " +
                "docs/android-debugger-dev.md.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("plan", buildJsonObject {
                        put("type", "object")
                        put("description", "Inline Plan JSON object. Exactly one of plan / plan_json must be set.")
                    })
                    put("plan_json", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Serialized Plan JSON string. Useful for clients that prefer wire-format strings. " +
                                "Exactly one of plan / plan_json must be set.",
                        )
                    })
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = false, toolName = "run_debug_plan") {
                val source = extractPlanSource(request.arguments)
                    ?: throw ToolError(
                        errorCode = ErrorCode.PlanInvalid,
                        message = "Provide either `plan` (object) or `plan_json` (string).",
                        hint = "Exactly one of plan / plan_json must be set.",
                    )

                when (val compile = PlanCompiler.compile(source)) {
                    is PlanCompiler.Result.Errors -> compileErrorsResult(compile.errors)
                    is PlanCompiler.Result.Ok -> {
                        // Require an attached VM before claiming the slot.
                        Session.requireAttached()
                        val scope = Session.planScope ?: throw ToolError(
                            errorCode = ErrorCode.Internal,
                            message = "Plan executor scope is unavailable.",
                            hint = "Reattach via /android-debugger:attach to rebuild the session scopes.",
                        )
                        // PlanExecutor.launch throws ToolError on VmInPlan / NotAttached /
                        // PlanInvalid — runTool's catch will translate these.
                        val handle = PlanExecutor.launch(compile.plan, scope)
                        toolOk {
                            put("plan_id", handle.planId)
                            put("plan_name", handle.planName)
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // pause_plan
    // -------------------------------------------------------------------------

    private fun registerPausePlan(server: Server) {
        server.addTool(
            name = "pause_plan",
            description = "v1.7: Hand control back to the agent while keeping the VM paused at the last " +
                "handled event. Returns the partial PlanReport. Use when you want to inspect a frame the " +
                "plan reached and then resume manual stepping. Idempotent if the plan has already terminated.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("plan_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Plan id from run_debug_plan.")
                    })
                },
                required = listOf("plan_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "pause_plan") {
                val planId = requirePlanIdArg(request.arguments)
                val handle = resolveActivePlan(planId)
                val report = handle.pause(reason = "agent requested pause")
                reportResult(report)
            }
        }
    }

    // -------------------------------------------------------------------------
    // abort_plan
    // -------------------------------------------------------------------------

    private fun registerAbortPlan(server: Server) {
        server.addTool(
            name = "abort_plan",
            description = "v1.7: Cleanly shut down an in-flight Debug Plan. Removes plan-owned breakpoints, " +
                "releases the VM (resumes if paused), and returns the partial PlanReport. Idempotent if the " +
                "plan has already terminated.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("plan_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Plan id from run_debug_plan.")
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional human-readable reason carried in the report. Default 'agent requested abort'.")
                    })
                },
                required = listOf("plan_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "abort_plan") {
                val planId = requirePlanIdArg(request.arguments)
                val reason = (request.arguments?.get("reason") as? JsonPrimitive)?.contentOrNull
                    ?: "agent requested abort"
                val handle = resolveActivePlan(planId)
                val report = handle.abort(reason = reason)
                reportResult(report)
            }
        }
    }

    // -------------------------------------------------------------------------
    // validate_plan
    // -------------------------------------------------------------------------

    private fun registerValidatePlan(server: Server) {
        server.addTool(
            name = "validate_plan",
            description = "v1.7: Compile + structurally validate a Debug Plan without launching it. Returns " +
                "the parsed Plan on success or an errors array on failure (with JSON paths so the agent can " +
                "fix every issue in one round-trip). Cheap; safe to call during another plan run.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("plan", buildJsonObject {
                        put("type", "object")
                        put("description", "Inline Plan JSON object. Exactly one of plan / plan_json must be set.")
                    })
                    put("plan_json", buildJsonObject {
                        put("type", "string")
                        put("description", "Serialized Plan JSON string. Exactly one of plan / plan_json must be set.")
                    })
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "validate_plan") {
                val source = extractPlanSource(request.arguments)
                    ?: throw ToolError(
                        errorCode = ErrorCode.PlanInvalid,
                        message = "Provide either `plan` (object) or `plan_json` (string).",
                        hint = "Exactly one of plan / plan_json must be set.",
                    )
                when (val compile = PlanCompiler.compile(source)) {
                    is PlanCompiler.Result.Errors -> compileErrorsResult(compile.errors)
                    is PlanCompiler.Result.Ok -> toolOk {
                        put("plan", PlanJson.json.encodeToJsonElement(Plan.serializer(), compile.plan))
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // list_plans
    // -------------------------------------------------------------------------

    private fun registerListPlans(server: Server) {
        server.addTool(
            name = "list_plans",
            description = "v1.7: List all Debug Plans persisted at \$CLAUDE_PLUGIN_DATA/android-debugger/plans/. " +
                "Returns metadata only (name, version, sha256, size_bytes, mtime_iso) — load_plan fetches the body. " +
                "Ordered most-recently-modified first.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) {
            runTool(allowsDuringPlan = true, toolName = "list_plans") {
                val plans = PlanPersistence.list()
                toolOk {
                    put("plans", buildJsonArray {
                        for (meta in plans) {
                            add(buildJsonObject {
                                put("name", meta.name)
                                put("version", meta.version)
                                put("sha256", meta.sha256)
                                put("size_bytes", meta.sizeBytes)
                                put("mtime_iso", meta.mtimeIso)
                            })
                        }
                    })
                    put("count", plans.size)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // save_plan
    // -------------------------------------------------------------------------

    private fun registerSavePlan(server: Server) {
        server.addTool(
            name = "save_plan",
            description = "v1.7: Persist a Debug Plan under `name` so the agent can re-run it across attach " +
                "cycles. Plan is compile-validated first; PlanInvalid on failure carries the structured errors " +
                "array. On success returns the absolute path the plan was written to (atomic write via temp + " +
                "rename, mode 0600 on POSIX).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Filename-safe slug, e.g. 'login-crash-investigation'.")
                    })
                    put("plan", buildJsonObject {
                        put("type", "object")
                        put("description", "Plan JSON object to persist.")
                    })
                },
                required = listOf("name", "plan"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "save_plan") {
                val name = (request.arguments?.get("name") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `name`.")
                if (name.isBlank()) {
                    throw ToolError(ErrorCode.InvalidTarget, "`name` must not be blank.")
                }
                val planJson = request.arguments?.get("plan") as? JsonObject
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing or non-object `plan`.")
                val source = planJson.toString()

                when (val compile = PlanCompiler.compile(source)) {
                    is PlanCompiler.Result.Errors -> compileErrorsResult(compile.errors)
                    is PlanCompiler.Result.Ok -> {
                        val savedPath = PlanPersistence.save(name, compile.plan)
                        if (savedPath == null) {
                            toolErr(
                                code = ErrorCode.Internal,
                                message = "Failed to persist plan '$name'.",
                                hint = "Check that CLAUDE_PLUGIN_DATA is set and writable.",
                            )
                        } else {
                            toolOk {
                                put("name", name)
                                put("path", savedPath.toString())
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // load_plan
    // -------------------------------------------------------------------------

    private fun registerLoadPlan(server: Server) {
        server.addTool(
            name = "load_plan",
            description = "v1.7: Read a persisted Debug Plan by name. Returns the parsed Plan JSON so the agent " +
                "can edit it before passing to run_debug_plan / save_plan. Errors plan_not_found if no plan with " +
                "that name exists.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Saved plan name from list_plans.")
                    })
                },
                required = listOf("name"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "load_plan") {
                val name = (request.arguments?.get("name") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `name`.")
                if (name.isBlank()) {
                    throw ToolError(ErrorCode.InvalidTarget, "`name` must not be blank.")
                }
                val plan = PlanPersistence.load(name)
                if (plan == null) {
                    toolErr(
                        code = ErrorCode.PlanNotFound,
                        message = "Plan '$name' not found.",
                        hint = "Run list_plans to enumerate saved plans.",
                    )
                } else {
                    toolOk {
                        put("name", name)
                        put("plan", PlanJson.json.encodeToJsonElement(Plan.serializer(), plan))
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // delete_plan
    // -------------------------------------------------------------------------

    private fun registerDeletePlan(server: Server) {
        server.addTool(
            name = "delete_plan",
            description = "v1.7: Remove a persisted Debug Plan from \$CLAUDE_PLUGIN_DATA/android-debugger/plans/. " +
                "Returns `{ ok: true, deleted: true|false }` — `false` if no plan with that name existed " +
                "(idempotent).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Saved plan name to delete.")
                    })
                },
                required = listOf("name"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "delete_plan") {
                val name = (request.arguments?.get("name") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `name`.")
                if (name.isBlank()) {
                    throw ToolError(ErrorCode.InvalidTarget, "`name` must not be blank.")
                }
                val deleted = PlanPersistence.delete(name)
                toolOk {
                    put("name", name)
                    put("deleted", deleted)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extract a plan source string from MCP args. Accepts either `plan_json` (string) or
     * `plan` (object → serialize back to JSON). Returns null if neither is provided.
     */
    private fun extractPlanSource(args: JsonObject?): String? {
        val planJson = (args?.get("plan_json") as? JsonPrimitive)?.contentOrNull
        if (!planJson.isNullOrBlank()) return planJson
        val planObj = args?.get("plan") as? JsonObject ?: return null
        return planObj.toString()
    }

    private fun requirePlanIdArg(args: JsonObject?): String {
        val id = (args?.get("plan_id") as? JsonPrimitive)?.contentOrNull
            ?: throw ToolError(ErrorCode.PlanUnknown, "Missing `plan_id`.")
        if (id.isBlank()) {
            throw ToolError(ErrorCode.PlanUnknown, "`plan_id` must not be blank.")
        }
        return id
    }

    /**
     * Look up the active plan handle, validating the id matches. Throws
     * [ErrorCode.PlanUnknown] if no plan is active or the id mismatches.
     */
    private fun resolveActivePlan(planId: String): com.acendas.androiddebugger.plans.PlanRunHandle {
        val activeId = Session.activePlanId
        val handle = Session.activePlan
        if (activeId == null || handle == null) {
            throw ToolError(
                errorCode = ErrorCode.PlanUnknown,
                message = "No active Debug Plan.",
                hint = "run_debug_plan first.",
            )
        }
        if (activeId != planId) {
            throw ToolError(
                errorCode = ErrorCode.PlanUnknown,
                message = "Plan id '$planId' does not match the active plan '$activeId'.",
                hint = "Use the plan_id returned by run_debug_plan.",
            )
        }
        return handle
    }

    /** Build a structured `{ ok:false, code:'plan_invalid', errors:[...] }` reply. */
    private fun compileErrorsResult(errors: List<PlanCompileError>): CallToolResult {
        val payload = buildJsonObject {
            put("ok", false)
            put("code", ErrorCode.PlanInvalid.code)
            put("message", "Plan failed validation (${errors.size} error(s)).")
            put("hint", "Fix every entry in `errors` and resubmit.")
            put(
                "errors",
                PlanJson.json.encodeToJsonElement(
                    ListSerializer(PlanCompileError.serializer()),
                    errors,
                ),
            )
        }
        return CallToolResult(content = listOf(TextContent(text = payload.toString())))
    }

    /** Render a pause/abort response: `{ ok:true, plan_id, status, report }`. */
    private fun reportResult(report: PlanReport): CallToolResult = toolOk {
        put("plan_id", report.planId)
        put("plan_name", report.planName)
        put("status", report.status)
        report.reason?.let { put("reason", it) }
        put("report", PlanJson.json.encodeToJsonElement(PlanReport.serializer(), report))
    }
}
