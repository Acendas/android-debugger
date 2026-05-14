---
name: walk
description: Walk through Android code with a step budget.
argument-hint: "<entry point — file:line or class.method> [--budget=N]"
allowed-tools: Read, Grep, mcp__android-debugger__connection_status, mcp__android-debugger__add_line_breakpoint, mcp__android-debugger__remove_breakpoint, mcp__android-debugger__wait_for_event, mcp__android-debugger__frame_snapshot, mcp__android-debugger__step_into, mcp__android-debugger__step_over, mcp__android-debugger__step_out, mcp__android-debugger__step_until_method_change, mcp__android-debugger__resume
---

# Walk — guided narrated walkthrough

Onboarding mode. The user wants to understand what the code *does*, not fix a bug. Set one breakpoint at the entry, trigger it, then step through with a bounded budget, explaining each new method in plain English.

## What you do

1. Call `mcp__android-debugger__connection_status`. Must be attached.

2. **Resolve the entry point.** Argument shapes:
   - `file.kt:42` → use directly with `add_line_breakpoint`.
   - `MyClass.someMethod` → Read/Grep the project to find the method's file:line. If multiple matches, ask via `AskUserQuestion`.
   - `"login flow"` etc. → Grep for likely entry keywords (`onClick`, `onCreate`, `LaunchedEffect`, etc.) in the suspect class hierarchy. Confirm with one `AskUserQuestion`.

3. Place the entry breakpoint via `mcp__android-debugger__add_line_breakpoint`. Save the breakpoint id.

4. **Tell the user to trigger** the entry: "Tap the login button now (or however you reach this code path). I'll wait."

5. Call `mcp__android-debugger__wait_for_event` with `{ timeout_ms: 60000, types: ["stopped"] }`. On hit, remove the entry breakpoint immediately (`remove_breakpoint`) — we don't want it firing again on every iteration.

6. Call `mcp__android-debugger__frame_snapshot`. Narrate the entry frame in 1–2 sentences: "We're entering `MainActivity.onLoginClick`. The `view` argument is the button itself; no other locals yet."

7. **Walk loop** (default budget: 30 steps, configurable via the argument suffix `--budget=N`):
   - Prefer `mcp__android-debugger__step_until_method_change({ max_steps: <budget> })` — the server steps internally until the current method changes (or the budget exhausts), so you get one snapshot per method instead of looping `step_over` and re-checking method names yourself. The tool returns the post-change snapshot plus `steps_taken` and `prior_method`.
   - Fall back to `step_over` only when you specifically need single-step granularity (e.g., the user asked "step exactly one line"). Use `step_into` when the user explicitly asks "what happens inside that call?" or the next call is *clearly* the interesting one (e.g., `repository.signIn(...)`).
   - On each `step_until_method_change` return, call `frame_snapshot` and narrate the new method. (Narration *only* on method change is now the server's contract — you don't have to enforce it yourself.)
   - When the method changes, lead with: "Now in `<class>.<method>` — this <one-sentence purpose>." Then surface the most-interesting locals.
   - When a frame returns: "`<method>` returned `<value>`. Back in `<caller>`."
   - **When the next frame is a `Continuation` (suspend-function resumption).** If the snapshot shows `Continuation.resume*` / `BaseContinuationImpl.resumeWith` / `DispatchedContinuationKt` in the next frame, the previous suspend point just resumed — possibly on a different thread/dispatcher. *Don't narrate as a method change* (technically the caller method continues). Instead narrate "resumed from suspend at `<previous call site>`; now on thread `<thread name>`." Read `skills/trace/references/reactive-codepaths.md` for the full reactive-codepath strategy — it's shared with `:trace` and `:bisect-flaky`.
   - **Generated-proxy carve-out (Hilt/Dagger/Compose factories).** When the next call's class name matches `*_HiltModules*`, `*_Factory`, `*_Provider`, `*_Impl` (Dagger/Hilt synthetic), do `step_into` then immediate `step_out` to skip the synthetic frame and land on the real implementation. Don't narrate the synthetic frame; it's noise the user doesn't want to read. The default class-exclusion filters (see `skills/explain/references/framework-frames.md`) catch the bulk of Compose/AndroidX framework code, but Hilt-generated factories live in the user's package and slip through.
   - **Stop conditions:**
     - Budget exhausted (the server returns `step_budget_exhausted: true` after 50 same-method steps — surface this; or the user-supplied `--budget=N` was reached).
     - User says stop.
     - We left the original logical scope (returned out of all frames the user was tracing). Suggest next: resume, set another bp, or end the walk.

8. **End cleanly.** Suggest `resume` (let the app run free) or `pause` later if they want to inspect another moment.

## Anti-hallucination rules

Read `skills/explain/references/anti-hallucination.md` and follow the snapshot-grounding + evaluate-safety rules there. Apply them to every narration step — never invent a local value, never claim a frame transitioned that didn't, never call a mutating method via `evaluate` to "see what would happen". The user is here to learn what the code does; making up behavior would teach them wrong.

## What you do NOT do

- Do not narrate every line. The user is here to understand the *shape*, not read line-by-line.
- Do not auto-step-into framework code. The default class-exclusion filter list lives in `skills/explain/references/framework-frames.md` (shared with `:explain`'s collapse rule). Don't override unless the user asks. The C-10 carve-out for generated proxies (`*_HiltModules*` / `*_Factory` / `*_Provider`) is the only exception — those are user-package classes that *should* be stepped through with step-into-then-out.
- Do not invent state. Lean on `frame_snapshot` for every claim.
- Do not call `frame_snapshot` if the method didn't change (cache returns the same payload, but don't waste the call).

## Cross-platform discipline

All MCP-mediated. Skill is fully portable.
