---
name: ad-trace
description: Trace an Android code path via logpoint plan sweep.
model: opus
---

# Trace — author a Debug Plan that sweeps logpoints across a call graph

Some bugs don't need stepping — they need to know "what got called, in what order, with what values." Author a Debug Plan whose setup is a batch of non-suspending logpoints across the suspect call graph, whose `on_event` immediately resumes (so the VM never blocks), and whose harvest is the merged logpoint timeline.

## When to use

The user described an ordering bug, race, or "I don't know where this value comes from" symptom in a known Android app. They have a seed location or symptom (e.g., "login flow", "MyVm.uiState").

## Preflight

1. Call `connection_status`. Must be attached.
2. **Identify 5–10 instrumentation points** across the suspect call graph by reading the user's project sources (Read/Grep). Aim for the entry, the readers, and the writers of the suspect value.
3. **Reactive recognition check.** Read `skills/ad-trace/references/reactive-codepaths.md`. If the codepath is Flow/StateFlow/Compose/suspend, instrument `emit` / `collect` / `derivedStateOf` / `LaunchedEffect` / suspending call sites — *not* "writers" and "readers". Otherwise use imperative writer/reader instrumentation.
4. **Confirm the batch** with one `AskUserQuestion` listing the proposed `(file:line)` or `(class.method)` set, one line per entry.

## Compose the plan

Read `references/plan-template.json`. The template instruments three method-entry logpoints on one class — adapt to N entries by replicating the setup entries. For line-precise instrumentation, swap `method_entry_bp` for `line_bp` with `file` + `line`.

Substitute:

- `<<PLAN_NAME>>` → e.g. `trace-login-flow`.
- Each setup entry: fill `method_class` + `method_name` (or `file` + `line`) + `log_message`. **Templates use `{expr}` for local interpolation; field reads only — never call non-trivial methods** (`{user.id}` ok; `{password.length()}` not — use Kotlin property `{password.length}`).
- `<<TIMEOUT_MS>>` → wall-clock budget. Default `120000` for an interactive repro; raise for slow flows.
- `<<MAX_EVENTS>>` → cap on logpoint hits. Default `200`; raise for hot paths and accept truncation.

The `on_event` block matches the breakpoint hits and immediately resumes — logpoints are non-suspending. Keep no hypotheses by default; the timeline IS the harvest.

Validate before launching:

```
validate_plan(plan: <composed plan>)
```

Fix returned errors and re-validate.

## Launch + monitor

```
run_debug_plan(plan: <validated plan>)
```

Capture `plan_id`. Tell the user: "Logpoints armed. Reproduce the symptom now — I'll harvest the timeline when you say go."

Then loop:

```
wait_for_event(timeout_ms: 60000, types: ["plan_progress"])
```

Per event subtype:

- `event_handled` — note the log line in your narrative buffer.
- `completed` — terminal. Read the final report's `logpoints[]` and render the timeline as a numbered list with timestamps. Flag ordering surprises, missing entries, and repeated invocations.
- `aborted` / `timeout` — read the partial report; surface the captured timeline even if incomplete.

## After terminal

Reason over the timeline:

- "Event 03 happened before 02 — that's unexpected."
- "Expected `AuthRepo.signOut` between 02 and 03 but didn't see it."
- "Step 04 ran 3 times — possible recomposition or leaked observer."

**If the report's `logpoints[]` is empty** after a successful repro, the setup didn't intersect the actual codepath. Re-do reactive recognition (the path may have gone through a Flow operator), or broaden the suspect graph. Don't silently re-prompt the user to "try again" — empty logs are a meaningful negative signal.

**Conditional narrowing.** If the first sweep was too noisy, re-author the plan with `condition` on the noisiest logpoints (e.g., `condition: "result.success = false"`) so only the failing-case entries render. Submit the narrower plan as a fresh `run_debug_plan`.

## When to drop to interactive

Almost never. The trace pattern is purely observational. Drop to interactive only if the user explicitly asks to drill into a specific captured event with `eval_method` or step semantics — and even then, prefer `abort_plan` → re-author a `:catch`-shaped plan over `pause_plan`.

## Anti-hallucination

Read `skills/ad-explain/references/anti-hallucination.md`. Never claim a value appears in the timeline that doesn't. Never claim a logpoint fired N times when the count is something else. Quote actual `logpoints[]` entries from the report.
