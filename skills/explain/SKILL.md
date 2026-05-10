---
name: Explain why we paused
description: This skill should be used when the user asks "explain why we stopped here", "what's happening now", "what does this paused state mean", "why did we hit this breakpoint", "analyze this stop", "where am I", or runs `/android-debugger:explain`. The cheap, high-leverage default after every pause — calls `frame_snapshot`, identifies the most-interesting frame and locals, and proposes 2–3 hypotheses for why we paused. Refuses to invent values not in the snapshot.
argument-hint: ""
allowed-tools: mcp__android-debugger__frame_snapshot, mcp__android-debugger__evaluate, mcp__android-debugger__inspect_object
---

# Explain — what's happening at this pause

The bread-and-butter workflow. Every breakpoint hit, every step pause, every exception break — the user calls this and gets a tight, hypothesis-first read of the current state.

## What you do

1. Call `mcp__android-debugger__frame_snapshot` (depth defaults to 5; pass `depth: 10` if the user is investigating a deep stack). Errors `vm_running` if not paused — say so and stop.

2. Render a tight summary:

   - **One-line title:** "Paused in `<class>.<method>` at `<file>:<line>` (event: `<BREAKPOINT|STEP|EXCEPTION|PAUSED>`)."
   - **Frame chain (collapsed):** list the top 3–5 frames as `<class>.<method> @ <file>:<line>`, indented. Collapse framework frames (java.*, android.*, kotlin.*, com.android.*) to a single `...framework...` line unless they're at the top.
   - **Locals worth calling out:** pick 2–4 locals that look load-bearing. Heuristics: nullable values that are null, collections with surprising sizes, status/error/exception fields, anything whose name matches the user's stated symptom.
   - **Watches:** if `snapshot.watches` is non-empty, list each `expr → rendered`.

3. Propose 2–3 **hypotheses** for why we paused, framed as "X because Y suggests Z." Hypotheses must be grounded in the snapshot — never invent values. If you don't have enough information for a confident hypothesis, say so and propose what to inspect next.

4. Suggest **one** next action — *not* a menu of three options:
   - If a local looks suspicious → `step_into` to see how it got that way, OR `evaluate` to confirm a value claim, OR `inspect_object` to drill into its fields.
   - If the hypothesis is strong → suggest the fix shape (don't write the code; describe it).
   - If the user explicitly asked something specific → answer that.

## Anti-hallucination rules (load-bearing)

- **Never** answer "what's the value of `x`?" from memory. If you need a value the snapshot doesn't surface (e.g., a deeply-nested field, the result of a method call), call `mcp__android-debugger__evaluate` or `mcp__android-debugger__inspect_object` to get the real value.
- **Never** narrate state that isn't in the snapshot. If the snapshot has `locals_unavailable: true` (R8/ProGuard build), say so explicitly and recommend `evaluate` for any specific identifier the user names.
- **Never** invoke a method via `evaluate` that is likely to mutate state (e.g., `someList.clear()`, `repository.save(...)`) without asking the user first. `evaluate` runs arbitrary code in the target VM with whatever permissions the app has.

## What you do NOT do

- Do not auto-step. The user decides when to advance.
- Do not call `frame_snapshot` repeatedly in one turn — the snapshot cache returns the same payload until next resume/step. One call per `:explain` invocation.
- Do not chain into `:catch` or `:trace` automatically — surface the hypothesis and let the user direct.

## Style

- Compact. The user is reading at a glance.
- Lead with the hypothesis if there's a clear one. "It's probably X because the snapshot shows Y" beats a 12-bullet locals dump.
- If you don't know, say "the snapshot doesn't tell us — inspecting `Z` next would clarify."
