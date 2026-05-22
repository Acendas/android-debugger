---
name: ad-walk
description: Walk Android code with bounded step budget via plan.
model: opus
---

# Walk — author a Debug Plan that narrates an entry point with a step budget

Onboarding mode. The user wants to understand what the code *does*, not fix a bug. Author a Debug Plan whose setup installs an entry breakpoint, whose `on_event` snapshots each frame boundary and steps into the next call, and whose `until` clause stops when the walk leaves the original method or hits the step budget.

## When to use

The user said "walk me through X", "show me how Y works", "onboard me to this codepath" — they have an entry point (file:line, class.method, or a flow description) and want a narrated step trace.

## Preflight

1. Call `connection_status`. Must be attached.
2. **Resolve the entry point.**
   - `file.kt:42` → use as-is with a `line_bp` setup entry.
   - `MyClass.someMethod` → use a `method_entry_bp` setup entry. If multiple overloads, ask via `AskUserQuestion`.
   - `"login flow"` → Read/Grep for likely entry keywords (`onClick`, `onCreate`, `LaunchedEffect`). Confirm with one `AskUserQuestion`.
3. Decide the step budget. Default `50` events (each event is one frame boundary). User may override via `--budget=N` in the argument suffix.

## Compose the plan

Read `references/plan-template.json`. The template installs a method-entry breakpoint, snapshots on each event, captures the current method name, and steps into the next call. Termination is by `until: dbg.frame_count() < entry_depth` OR `max_events` budget.

Substitute:

- `<<PLAN_NAME>>` → e.g. `walk-login-onclick`.
- `<<METHOD_CLASS>>` + `<<METHOD_NAME>>` → entry point. Replace the `method_entry_bp` setup entry with a `line_bp` if the user gave file:line.
- `<<MAX_EVENTS>>` → step budget (default 50).
- `<<TIMEOUT_MS>>` → wall-clock budget. Default `60000`.

Validate before launching:

```
validate_plan(plan: <composed plan>)
```

## Launch + monitor

```
run_debug_plan(plan: <validated plan>)
```

Tell the user: "Walk armed. Trigger the entry now — tap the button (or however you reach this code path)." Capture the `plan_id`.

Then loop:

```
wait_for_event(timeout_ms: 60000, types: ["plan_progress"])
```

Per event subtype:

- `snapshot_captured` — read the snapshot ref. Narrate the current frame in 1–2 sentences using values from `feel_outputs` (`current_method`, top locals): "Now in `<class>.<method>` — this <one-sentence purpose>." Surface the most-interesting locals.
- `event_handled` — recurring step boundary. Continue narrating from the snapshot.
- `completed` — terminal. The walk ended cleanly (left the original scope or budget exhausted). Read `snapshot_refs[]` and `feel_outputs` to produce the final narration arc. Suggest next: resume, set another bp, or end.
- `yielded` — VM paused at an interesting boundary (e.g., user-injected `yield_when`). For walk plans this is rare; if it happens, `abort_plan` and summarize.
- `aborted` / `timeout` — read the partial report. Surface as much narrative as captured.

## After terminal

Use `snapshot_refs[]` for deeper drill-down — `inspect_object(snap#<plan_id>:<seq>, vobj#<id>)` resolves objects at any captured frame boundary, even after the plan finished.

**Reactive boundaries.** If the snapshot shows `Continuation.resume*` / `BaseContinuationImpl.resumeWith` in a frame, narrate as "resumed from suspend at `<previous call site>`; now on thread `<thread name>`" — see `skills/ad-trace/references/reactive-codepaths.md` for the shared reactive-codepath strategy.

## When to drop to interactive

Drop to interactive when the user pivots from "walk" to "debug this exact value" mid-trace. Call `abort_plan` first to release the VM, then either re-author as `:catch` or `:trace`, or do single-step drill-down with imperative tools.

## Anti-hallucination

Read `skills/ad-explain/references/anti-hallucination.md`. Every narration line must ground in `feel_outputs` or `snapshot_refs` from the report. Never invent a local value. Never claim a frame transitioned that the snapshot stream didn't record.

## What you do NOT do

- Do not narrate every line. The user is here to understand the *shape*, not read line-by-line.
- Do not raise `max_events` beyond what the user asked — step-into loops are the top failure mode of walk patterns.
- Do not auto-step into framework classes. Use `step_into` with the standard skip filters list in `skills/ad-explain/references/framework-frames.md`.
