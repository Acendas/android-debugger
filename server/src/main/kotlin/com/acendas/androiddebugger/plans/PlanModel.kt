package com.acendas.androiddebugger.plans

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * v1.7 Debug Plan data model — the JSON shape the agent submits to `run_debug_plan` and
 * `validate_plan`, plus the in-memory shape the executor consumes after compile. Per the
 * iterative-anchor spec at /Users/mafahir/.claude-work/plans/i-want-to-build-iterative-anchor.md.
 *
 * The JSON wire format is intentionally close to the in-memory model so the compiler is a
 * thin schema + type-check pass, not a transformation. FEEL expressions are carried as
 * strings until evaluation time; they are *parsed* by the compiler but not *evaluated*.
 *
 * Polymorphism discriminator: a single global `kind` field. SetupEntry uses `kind` to
 * pick between `line_bp` / `exception_bp` / ... and Action uses the same `kind` to pick
 * between `snapshot` / `feel` / `resume` / etc. The kotlinx-serialization Json instance
 * in [PlanJson] configures `classDiscriminator = "kind"` so the discriminator is injected
 * and read automatically.
 *
 * Termination guarantees (enforced by [PlanCompiler]):
 *   - `timeoutMs` is mandatory and must be > 0
 *   - at least one of `maxEvents` or `until` must be present
 *   - hypotheses are not control flow; they record verdicts
 */
@Serializable
data class Plan(
    /** Human-readable name. Filename-safe slug recommended (lowercase, hyphens). */
    val name: String,
    /** Schema version. Pinned at 1 for v1.7. */
    val version: Int = 1,
    /** Wall-clock budget. Plan auto-aborts (terminal subtype: `timeout`) when elapsed. */
    @SerialName("timeout_ms")
    val timeoutMs: Long,
    /** Max number of JDI events processed by this plan. */
    @SerialName("max_events")
    val maxEvents: Int? = null,
    /** FEEL expression over plan-local state; plan completes naturally when true. */
    val until: String? = null,
    /** Breakpoint/watchpoint/exception-bp installs that fire during the run. */
    val setup: List<SetupEntry> = emptyList(),
    /** Hypotheses graded over the event stream. Optional. */
    val hypotheses: List<Hypothesis> = emptyList(),
    /** Match→actions handler chain. First matching block fires per event. */
    @SerialName("on_event")
    val onEvent: List<OnEvent> = emptyList(),
    /** Harvest declaration — what the final report carries. Empty = everything. */
    val harvest: List<String> = emptyList(),
    /** Max events per second; surplus dropped, report carries `truncated:true`. */
    @SerialName("max_event_rate")
    val maxEventRate: Int = DEFAULT_MAX_EVENT_RATE,
    /** Max snapshots pinned in the plan store. Default 50; global cap enforced separately. */
    @SerialName("max_snapshots")
    val maxSnapshots: Int = DEFAULT_MAX_SNAPSHOTS,
    /**
     * v1.7.1 (M4 hang mitigation): per-plan stuck-watchdog threshold. If no input
     * event reaches the handler chain within this many milliseconds since plan start
     * (or since the last handled event), the watchdog emits a `plan_progress(stuck, ...)`
     * signal so the agent can decide whether to wait or abort. The watchdog never
     * auto-aborts — it only surfaces a signal. Null = use [DEFAULT_STUCK_DETECT_MS].
     */
    @SerialName("stuck_detect_ms")
    val stuckDetectMs: Long? = null,
) {
    companion object {
        const val DEFAULT_MAX_EVENT_RATE: Int = 100
        const val DEFAULT_MAX_SNAPSHOTS: Int = 50

        /**
         * v1.7.1 (M4 hang mitigation): default watchdog threshold for "this plan is
         * armed but no events have arrived." 90s is generous for slow Android startup
         * but tight enough that a misconfigured class pattern surfaces within a normal
         * agent attention span.
         */
        const val DEFAULT_STUCK_DETECT_MS: Long = 90_000L

        /** Hard ceiling on snapshots (per-plan + global). Stops runaway plans from OOMing. */
        const val GLOBAL_MAX_SNAPSHOTS: Int = 200

        /** Hard ceiling on plan JSON size (compile rejects above). */
        const val MAX_PLAN_BYTES: Long = 256L * 1024L

        /**
         * v1.7.1 (M3 hang mitigation): hard ceiling on plan wall-clock budget. A
         * misconfigured plan declaring `timeout_ms: 3600000` (1 hour) could hold the
         * VmCoordinator slot for that long, blocking every other plan. 10 minutes is
         * generous for any sane investigation; longer means the plan needs to be
         * split into multiple sequential dispatches.
         */
        const val MAX_TIMEOUT_MS: Long = 10L * 60L * 1000L
    }
}

/**
 * Setup-phase entries. Each maps 1:1 to a [com.acendas.androiddebugger.breakpoints.BreakpointInstaller]
 * install* method. The polymorphism discriminator is the `kind` JSON field.
 */
@Serializable
sealed class SetupEntry {

    /** Line breakpoint. Mirrors `add_line_breakpoint`. */
    @Serializable
    @SerialName("line_bp")
    data class LineBp(
        val file: String,
        val line: Int,
        val condition: String? = null,
        @SerialName("hit_count")
        val hitCount: Int? = null,
        @SerialName("log_message")
        val logMessage: String? = null,
    ) : SetupEntry()

    /** Exception breakpoint. Mirrors `add_exception_breakpoint`. */
    @Serializable
    @SerialName("exception_bp")
    data class ExceptionBp(
        @SerialName("class_pattern")
        val classPattern: String? = null,
        val caught: Boolean = true,
        val uncaught: Boolean = true,
    ) : SetupEntry()

    /** Method entry breakpoint. Mirrors `add_method_breakpoint(kind=entry)`. */
    @Serializable
    @SerialName("method_entry_bp")
    data class MethodEntryBp(
        @SerialName("method_class")
        val methodClass: String,
        @SerialName("method_name")
        val methodName: String,
        @SerialName("log_message")
        val logMessage: String? = null,
    ) : SetupEntry()

    /** Method exit breakpoint. */
    @Serializable
    @SerialName("method_exit_bp")
    data class MethodExitBp(
        @SerialName("method_class")
        val methodClass: String,
        @SerialName("method_name")
        val methodName: String,
    ) : SetupEntry()

    /** Field watchpoint. Mirrors `add_field_watchpoint`. */
    @Serializable
    @SerialName("field_watchpoint")
    data class FieldWatchpoint(
        @SerialName("field_class")
        val fieldClass: String,
        @SerialName("field_name")
        val fieldName: String,
        @SerialName("want_access")
        val wantAccess: Boolean = false,
        @SerialName("want_modification")
        val wantModification: Boolean = true,
    ) : SetupEntry()

    /** Class-load breakpoint. Mirrors `add_class_load_breakpoint`. */
    @Serializable
    @SerialName("class_load_bp")
    data class ClassLoadBp(
        @SerialName("class_pattern")
        val classPattern: String,
    ) : SetupEntry()
}

/**
 * A hypothesis is a (when, expect) pair. On every event whose `when` clause holds, the
 * `expect` clause is evaluated. State transitions are monotonic in the contradiction
 * direction: `inconclusive → matched | contradicted`. Once contradicted, stays
 * contradicted.
 */
@Serializable
data class Hypothesis(
    val name: String,
    /** FEEL condition selecting which events grade this hypothesis. */
    @SerialName("when")
    val whenExpr: String,
    /** FEEL expression that must hold whenever `when` is true. */
    val expect: String,
)

/**
 * An on-event handler block. The first block whose `match` FEEL condition holds for a
 * given event fires; remaining blocks are skipped for that event. Actions execute in
 * order; each completion emits a `plan_progress` event.
 */
@Serializable
data class OnEvent(
    /** FEEL condition over the synthetic `event` context. Empty / `"true"` ⇒ match all. */
    val match: String = "true",
    val actions: List<Action>,
)

/**
 * A single action inside an [OnEvent.actions] list. The polymorphism discriminator is
 * the `kind` JSON field (set by kotlinx-serialization based on `@SerialName`).
 */
@Serializable
sealed class Action {

    /** Capture a FrameSnapshot at the current pause and pin it in the per-plan store. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val depth: Int = 8,
    ) : Action()

    /** Evaluate a FEEL expression and store in `feel_outputs[as]` + plan-local vars. */
    @Serializable
    @SerialName("feel")
    data class Feel(
        val feel: String,
        @SerialName("as")
        val asName: String,
    ) : Action()

    /** Invoke a method on a JDI reference via the existing eval_method path. */
    @Serializable
    @SerialName("eval_method")
    data class EvalMethod(
        val target: String,
        val method: String,
        val args: List<String> = emptyList(),
        @SerialName("as")
        val asName: String,
        @SerialName("allow_mutation")
        val allowMutation: Boolean = false,
    ) : Action()

    /** Resume the current pause. Terminal for this on_event block. */
    @Serializable
    @SerialName("resume")
    data class Resume(val resume: Boolean = true) : Action()

    /** Step over the current line and wait for the next StepEvent. */
    @Serializable
    @SerialName("step_over")
    data object StepOver : Action()

    /** Step into the next call. */
    @Serializable
    @SerialName("step_into")
    data object StepInto : Action()

    /** Step out of the current frame. */
    @Serializable
    @SerialName("step_out")
    data object StepOut : Action()

    /** Yield if FEEL condition holds — plan pauses, VM stays paused, agent takes over. */
    @Serializable
    @SerialName("yield_when")
    data class YieldWhen(
        @SerialName("yield_when")
        val condition: String,
        val reason: String = "yield_when condition true",
    ) : Action()

    /** Abort if FEEL condition holds — plan ends cleanly, VM resumes. */
    @Serializable
    @SerialName("abort_when")
    data class AbortWhen(
        @SerialName("abort_when")
        val condition: String,
        val reason: String = "abort_when condition true",
    ) : Action()

    /** Emit a plan_progress log event with a custom message. */
    @Serializable
    @SerialName("log")
    data class Log(val log: String) : Action()

    /** Store a literal or computed FEEL value into plan-local state. */
    @Serializable
    @SerialName("set_var")
    data class SetVar(
        val name: String,
        val value: String,
    ) : Action()
}

// -----------------------------------------------------------------------------------
// Report — the structure returned by run_debug_plan / pause_plan / abort_plan.
// -----------------------------------------------------------------------------------

/**
 * Final or partial report produced by a plan run. The terminal state lives in [status].
 * All fields are populated as the plan executes; on yield/abort/timeout the report
 * carries whatever was harvested through the terminating event.
 */
@Serializable
data class PlanReport(
    @SerialName("plan_id")
    val planId: String,
    @SerialName("plan_name")
    val planName: String,
    /** completed | yielded | aborted | timeout | paused | error */
    val status: String,
    /** Reason string carried alongside terminal status (e.g., yield_when's reason). */
    val reason: String? = null,
    /** Total wall-clock duration in ms. */
    @SerialName("duration_ms")
    val durationMs: Long,
    /** Events the executor saw (pre-throttle count). */
    @SerialName("events_total")
    val eventsTotal: Long,
    /** Events delivered to handlers after throttling. */
    @SerialName("events_handled")
    val eventsHandled: Long,
    /** Events dropped by the leaky-bucket throttle. */
    @SerialName("events_dropped")
    val eventsDropped: Long,
    /** True if any events were dropped or any snapshot was elided due to caps. */
    val truncated: Boolean,
    @SerialName("hypothesis_grades")
    val hypothesisGrades: List<HypothesisGrade> = emptyList(),
    /** FEEL outputs (name → JSON value), in order of first set. */
    @SerialName("feel_outputs")
    val feelOutputs: Map<String, JsonElement> = emptyMap(),
    /** Snapshot refs (`snap#<plan_id>:<event_seq>`) captured during the run. */
    @SerialName("snapshot_refs")
    val snapshotRefs: List<String> = emptyList(),
    /** Per-step errors. Plans continue past step errors; report carries the full list. */
    val errors: List<StepError> = emptyList(),
    /** Logpoint timeline (rendered messages). */
    val logpoints: List<JsonObject> = emptyList(),
    /** Per-setup-entry install outcomes (one entry per `plan.setup[]`). */
    @SerialName("setup_results")
    val setupResults: List<SetupResult> = emptyList(),
)

/**
 * A graded hypothesis. `grade` is one of: matched | contradicted | inconclusive.
 * Evidence carries the event(s) that triggered evaluation and the value of `expect`.
 */
@Serializable
data class HypothesisGrade(
    val name: String,
    val grade: String,
    /** First `when`-matching event seq; null if `when` never matched. */
    @SerialName("first_trigger_seq")
    val firstTriggerSeq: Long? = null,
    /** Total times `when` matched. */
    @SerialName("trigger_count")
    val triggerCount: Long = 0,
    /** Event seq that produced the contradiction, if any. */
    @SerialName("contradiction_seq")
    val contradictionSeq: Long? = null,
    /** Optional human-readable evidence string. */
    val evidence: String? = null,
)

/** An error recorded by one plan step (FEEL eval, action exec). */
@Serializable
data class StepError(
    /** Sequence number of the event being handled when the error occurred. */
    @SerialName("event_seq")
    val eventSeq: Long,
    /** Action kind (`feel`, `snapshot`, ...). */
    val op: String,
    /** Error code matching ErrorCode.code (best-effort). */
    val code: String,
    val message: String,
)

/**
 * Outcome of installing one entry from [Plan.setup]. Reported back in [PlanReport.setupResults]
 * and rolled up in the `setup_complete` `plan_progress` event so the agent can distinguish
 * three structurally different post-setup states:
 *   - **resolved** — at least one JDI location was bound to the entry.
 *   - **deferred** — entry is armed via `ClassPrepareRequest`; will install on class load.
 *     Counts toward `installed_count` because the BP is live and will fire if the class loads.
 *   - **error** — install threw. [error] carries the message; [bpId] is null.
 */
@Serializable
data class SetupResult(
    /** 0-based index into [Plan.setup]. */
    val index: Int,
    /** Entry kind: line | exception | method_entry | method_exit | field | class_load. */
    val kind: String,
    /** resolved | deferred | error */
    val status: String,
    /** Breakpoint id when status != error. Null on error. */
    @SerialName("bp_id")
    val bpId: Int? = null,
    /** Short human-readable target ("file.kt:42", "com.x.Y.method", "com.x.Y#field"). */
    val target: String,
    /** Number of JDI locations resolved (line/method entries). Null for non-line entries. */
    @SerialName("resolved_locations")
    val resolvedLocations: Int? = null,
    /** Error message when status = error. */
    val error: String? = null,
)

/**
 * Compile-time error list returned by [PlanCompiler.compile] / `validate_plan`. The
 * full list is returned (not just the first) so the agent can fix all in one round-trip.
 */
@Serializable
data class PlanCompileError(
    /** JSON path to the failing element, e.g. `hypotheses[0].expect`. */
    val path: String,
    /** Stable code: `schema | feel_parse | bp_target | bounds | size`. */
    val code: String,
    val message: String,
    val hint: String? = null,
)

/**
 * Shared JSON instance configured for the plan DSL — class discriminator `kind`,
 * lenient parsing for forward compat, pretty-print off (compact transport).
 */
object PlanJson {
    val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
        // Allows polymorphic deserialization when the source JSON omits the discriminator
        // and provides a structural shape — useful for tolerant agent-authored plans.
        // We still require the discriminator for actions/setup; this only affects
        // optional sealed types added in future versions.
        useArrayPolymorphism = false
    }
}
