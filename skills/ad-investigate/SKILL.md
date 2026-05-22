---
name: ad-investigate
description: Investigate an Android bug end-to-end via plan dispatch.
model: opus
---

# Investigate — meta-orchestrator (v1.7 plan-first)

This skill is the META-orchestrator. Triage the user's goal shape, dispatch to the matching plan-author template skill (`ad-catch`, `ad-trace`, `ad-walk`, `ad-bisect-flaky`), monitor the resulting `plan_progress` event stream, and handle the yield → abort → re-author loop with effort escalation.

Plans are the default. Interactive imperative tools are the escape hatch.

## Role

The template skills compose and submit a Debug Plan; the server executes it deterministically against the JDWP event queue without an LLM in the loop. This skill orchestrates: it picks the template, watches the plan stream, decides what to do on yield/contradiction, and re-engages with a fresh plan when needed.

Plans cut LLM round-trips per known-shape investigation from ~20 to ~3. Use them.

## Step 1 — Preflight

Call `mcp__android-debugger__connection_status`. If not attached, tell the user to run `/android-debugger:ad-attach` first and stop. Do not re-run preflight that a dispatching skill has already confirmed via the `[orchestrator note: session is attached…]` marker.

## Step 2 — Goal-shape triage

Classify the user's goal into one shape. This is the first decision the agent makes:

| Goal shape | Dispatch to | Examples |
|---|---|---|
| Crash / exception | `ad-catch` | "App throws NPE in LoginActivity", "force-closes on tap login" |
| Find where X happens / silent failure / behavior bug | `ad-trace` | "login does nothing on slow networks", "find where retry fires" |
| Onboarding / walk-through | `ad-walk` | "walk me through what happens when settings opens" |
| 1-in-N test failure / flaky | `ad-bisect-flaky` | "TestX fails 1 in 10", "flaky on CI, green locally" |
| Genuinely exploratory ("I don't know what's wrong") | start with `ad-catch` on common exceptions; if nothing fires, fall back to interactive `frame_snapshot` + `evaluate` | "something's wrong, debug this" |

If the goal is genuinely ambiguous, ask once via `AskUserQuestion`. Otherwise pick the most-likely shape and proceed — the user can redirect.

For sub-shape disambiguation (e.g. Behavior sub-shapes: "bug at a known line" vs "find where X happens" vs "state is wrong with no repro") and the canonical disambiguation rules when triggers overlap, consult `references/four-shape-triage.md`. The reference is the single source of truth shared with the orchestrator agent — the table above is the quick-glance summary.

## Step 3 — Dispatch the template

Invoke the matching `/android-debugger:ad-*` skill with the user's goal verbatim. The template skill composes a plan from its `references/plan-template.json`, validates it via `validate_plan`, and submits via `run_debug_plan`. Control returns to this skill with the plan's `plan_id` once dispatch is acknowledged.

Do NOT re-implement what the template does. The template owns the FEEL expressions, hypotheses, and harvest list for its shape.

## Step 4 — Stream-monitor the plan

Poll `wait_for_event({ types: ["plan_progress"], timeout_ms: 30000 })` repeatedly. Each event carries `plan_id`, `seq`, `subtype`, and a structured payload.

Subtypes worth narrating to the user (one-line each):

- `event_handled` — a setup breakpoint fired; carries the event kind + location.
- `hypothesis_graded` — a hypothesis transitioned to `matched` or `contradicted`.
- `snapshot_captured` — a snapshot ref is now available.
- `feel_evaluated` — a named FEEL output landed.
- `yielded` — declarative `yield_when` fired; VM paused; carries `reason`.
- `aborted` — clean shutdown; VM resumed.
- `completed` — plan finished its event budget or `until` clause.

Read effort: low. The VM is paused-or-running depending on plan state; speed beats depth here.

## Step 5 — Handle terminal subtypes

### `completed`

Read the final report. Present hypothesis verdicts (`matched | contradicted | inconclusive`), key FEEL outputs by name, snapshot refs the user can drill into, and termination reason. Hand back to the user.

### `yielded` (or hypothesis contradicted)

**The first decision is `abort_plan` vs `pause_plan`, not what to do next.**

- **Default to `abort_plan`** when the next step is "think hard about what to do next." `abort_plan` resumes the VM, releases the plan's suspends, removes plan-owned breakpoints, and returns the partial report. Re-author at high effort with the VM unfrozen, then re-submit.
- **Use `pause_plan`** ONLY when the next move genuinely needs the live paused frame — an interactive step, an `eval_method` on the live frame, an `inspect_object` on a frame-local that isn't already in a snapshot ref.

Thinking harder while the VM is paused makes the original problem (held app) worse. Get the VM moving first; reason second.

### `aborted` / `timeout` / `error`

Present partial findings from the partial report. Ask the user for direction: re-author with a different hypothesis, switch shape (e.g., crash → trace), or hand off to interactive.

### v1.7.1 hang-error signals — react deliberately

The server guarantees every tool returns within a wall-clock budget. When that budget fires, you'll see one of: `tool_timeout`, `attach_timeout`, `plan_progress(stuck)`, or an `abort_plan` report whose `reason` mentions "force-killed". Do NOT retry blindly — every retry the user can't see feels like a hang.

Read `references/hang-error-playbook.md` for the full decision tree. Hard rules:

- **2 consecutive `tool_timeout` or `attach_timeout` → STOP** and surface the signal. Recommend `adb kill-server && adb start-server` or emulator cold restart.
- **`plan_progress(stuck, events_handled = 0)` → tell the user immediately** ("plan armed, no events — trigger the bug now or the BP target is wrong"). Wait one more interval. If second stuck, abort and re-author with broader setup. Never pause_plan on a stuck signal — keep the VM running.
- **3 stuck signals across an investigation → drop to interactive** (`frame_snapshot` + `evaluate`). The plan-first approach isn't a fit for this code path.
- **`reason` contains "force-killed" → JDWP transport unhealthy.** Recommend `detach` + re-`attach` before the next investigation step.

## Step 6 — Re-author after abort

If a hypothesis was contradicted, encode the corrected assumption as a fresh hypothesis in the next plan. **Do not silently retry the same plan.** Each re-author is a new dispatch — same template skill if the shape still fits, or a different template if the corrected hypothesis points elsewhere.

High-effort re-author runs after `abort_plan` so the VM is unfrozen during reasoning. This is the only place high effort is appropriate inside this loop.

## When to drop to interactive entirely

Plans are the default; drop to interactive when:

1. **User explicitly asks for a step-through.** "Walk me through this manually."
2. **Multiple plans in a row have yielded with surprises.** The agent doesn't have enough model of the code to plan well — switch to direct exploration via `frame_snapshot` + `evaluate` + `inspect_object`.
3. **Need to invoke a mutating method.** There is no plan action for explicit user-acknowledged mutation; `eval_method({ allow_mutation: true })` runs interactively after the user is told what's about to happen.
4. **One-shot read at an existing pause.** Use `/android-debugger:ad-explain` — a plan with one event handler is just ceremony.

Within an active plan, read-only inspection tools (`frame_snapshot`, `get_locals`, `inspect_object`, `evaluate`, `count_instances`, `list_threads`, `read_logcat`, `wait_for_event`) still work — useful for sneak peeks without aborting. State-mutating tools (`eval_method`, `resume`, `step_*`, `add_*_breakpoint`, `hot_swap_*`) return `vm_in_plan`; pause or abort first if you need them.

## Where to find the templates

- `/android-debugger:ad-catch` — exception breakpoint plan with hypotheses around throw-site locals.
- `/android-debugger:ad-trace` — logpoint-sweep plan harvesting a merged timeline.
- `/android-debugger:ad-walk` — guided walkthrough with step-budget enforcement.
- `/android-debugger:ad-bisect-flaky` — flaky-test rerun loop with divergence capture.
- `/android-debugger:ad-explain` — UNCHANGED; one-shot snapshot read on an existing pause. No plan overhead.

## Style

Lead with the dispatch decision. "Triage: crash shape. Dispatching `ad-catch` with `java.lang.NullPointerException` filtered to `com.example.login.*`." Then narrate plan_progress concisely. On terminal, present the report. Don't editorialize the template's findings; render them straight.

One question per round at most. Auto-mode: when you'd normally ask, pick the reasonable default and keep going.
