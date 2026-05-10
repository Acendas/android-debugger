---
name: Walk through code
description: This skill should be used when the user asks "walk me through X", "show me how login works", "onboard me to this code", "explain what happens when I tap Y", "trace through this method", or runs `/android-debugger:walk`. Guided walkthrough with a step budget — sets one breakpoint at the entry, prompts the user to trigger, then steps with bounded budget, narrating each new method/frame in plain English. Stops automatically on budget exhaustion or scope leave.
argument-hint: "<entry point — file:line or class.method>"
allowed-tools: Read, Grep, mcp__android-debugger__connection_status, mcp__android-debugger__add_line_breakpoint, mcp__android-debugger__remove_breakpoint, mcp__android-debugger__wait_for_event, mcp__android-debugger__frame_snapshot, mcp__android-debugger__step_into, mcp__android-debugger__step_over, mcp__android-debugger__step_out, mcp__android-debugger__resume
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

7. **Walk loop** (default budget: 30 steps, configurable via argument suffix `--budget=N`):
   - Call `mcp__android-debugger__step_over` (default — most readable). Use `step_into` only when the user explicitly asks "what happens inside that call?" or when the next call is *clearly* the interesting one (e.g., `repository.signIn(...)`).
   - On each pause, call `frame_snapshot` and narrate **only** if the method changed (we entered/left a frame). Don't narrate every line within the same method — too noisy.
   - When the method changes, lead with: "Now in `<class>.<method>` — this <one-sentence purpose>." Then surface the most-interesting locals.
   - When a frame returns: "`<method>` returned `<value>`. Back in `<caller>`."
   - **Stop conditions:**
     - Budget exhausted (the server returns `step_budget_exhausted: true` after 50 same-method steps — surface this).
     - User says stop.
     - We left the original logical scope (returned out of all frames the user was tracing). Suggest next: resume, set another bp, or end the walk.

8. **End cleanly.** Suggest `resume` (let the app run free) or `pause` later if they want to inspect another moment.

## What you do NOT do

- Do not narrate every line. The user is here to understand the *shape*, not read line-by-line.
- Do not auto-step-into framework code. Default class-exclusion filters (`java.*`, `android.*`, `kotlin.*`, `com.android.*`) handle this in `step_into`. Don't override unless the user asks.
- Do not invent state. Lean on `frame_snapshot` for every claim.
- Do not call `frame_snapshot` if the method didn't change (cache returns the same payload, but don't waste the call).

## Cross-platform discipline

All MCP-mediated. Skill is fully portable.
