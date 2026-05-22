---
name: ad-catch
description: Break on an Android exception and root-cause it via plan.
model: opus
---

# Catch — author a Debug Plan that traps the next throw and root-causes it

Author a Debug Plan that installs an exception breakpoint, captures the failing frame's locals, grades a hypothesis about why, and resumes. The orchestrator submits the plan, polls progress events, and reads the structured report. Interactive drill-down is reserved for the cases the plan can't fully grade.

## When to use

The user described a crash, NullPointerException, IllegalStateException, or similar exception in a known Android app process. They have an exception class (e.g., `java.lang.NullPointerException`, `com.example.MyException`) and ideally a package filter to narrow delivery.

## Preflight

1. Call `connection_status`. If not attached, tell the user to run `/android-debugger:ad-attach` and stop.
2. Resolve the exception class:
   - Empty / `"any"` → omit `class_pattern` in the plan setup (catches all).
   - Short name (no dots) → look up the FQN list in `skills/ad-catch/references/exception-fqn-table.md`. Pick the first FQN that exists in the user's project; ask via `AskUserQuestion` if multiple candidates match.
   - Already an FQN → use as-is.
3. Yellow-flag check against the table — names like `kotlinx.coroutines.CancellationException` are usually control flow. Warn and confirm before proceeding.

## Compose the plan

Read `references/plan-template.json`. Substitute:

- `<<EXCEPTION_FQN>>` → the resolved FQN (or remove the `class_pattern` field for the "any" case).
- `<<FILTER_CLASS>>` → a package narrowing pattern such as `com.example.login.*` (or remove the field if the user didn't narrow).
- `<<PLAN_NAME>>` → a slug like `catch-npe-login`.

Keep `caught: false, uncaught: true` unless the user explicitly asks to break on caught exceptions too. Adjust hypotheses to the user's stated theory — e.g., if they said "I think `token` is null at the throw," set `expect: "frame.locals['token'] = null"`. If they have no theory, keep the default `event.exception_class != null` smoke check.

> **FEEL string-literal quoting:** kfeel 1.0.0 only accepts **single-quoted** strings (`'exception'`, `'token'`). Double-quoted FEEL strings (`"exception"`) will fail compile. Use single quotes for any string literal inside a FEEL expression. JSON keys / values that aren't FEEL stay double-quoted as JSON requires.

Validate before launching:

```
validate_plan(plan: <composed plan>)
```

Fix all returned errors and re-validate. Do not submit a plan with compile errors.

## Launch + monitor

```
run_debug_plan(plan: <validated plan>)
```

Capture the returned `plan_id`. Tell the user: "Plan armed. Reproduce the crash now — open the app and trigger it. I'll harvest the failing frame when it fires."

Then loop:

```
wait_for_event(timeout_ms: 30000, types: ["plan_progress"])
```

Per event subtype:

- `event_handled` — note the event seq for the narrative.
- `snapshot_captured` — record the `snap#<plan_id>:<seq>` ref.
- `hypothesis_graded` — note the transition (`matched` / `contradicted`).
- `completed` — terminal. Read the final report: grades + `feel_outputs` + `snapshot_refs`. Present the root-cause to the user grounded in actual values from the report (never invented).
- `yielded` — VM is paused at the failing frame. Read the partial report. If you can root-cause from what's already there, summarize and call `abort_plan(plan_id)` to release the VM. If you need a deeper hypothesis, call `abort_plan` FIRST (so the VM resumes — never think hard while the app is frozen), then re-author with the new hypothesis and re-submit.
- `aborted` / `timeout` / `error` — read the partial report, surface what was captured, propose the next move.

## After terminal

Use the report's `snapshot_refs` to drill in with `inspect_object(snap#<plan_id>:<seq>, vobj#<id>)` when the user wants more depth — snapshot refs survive past plan termination until the next `run_debug_plan`.

If a hypothesis came back `contradicted`, that's a real finding: the user's theory was wrong. Present what the snapshot showed instead.

## When to drop to interactive

The plan handles the routine catch-and-grade path. Drop to interactive (no plan) when:

- The captured snapshot needs `eval_method` on a live frame.
- The user wants to step from the captured frame.

In both cases, prefer `abort_plan` → think hard out-of-band → re-submit a new plan, over `pause_plan` (which holds the VM frozen while you reason).

## Anti-hallucination

Read `skills/ad-explain/references/anti-hallucination.md`. Ground every claim in the report's `feel_outputs` and `snapshot_refs` — never invent a local value, never paraphrase a hypothesis grade that wasn't returned.
