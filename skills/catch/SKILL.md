---
name: Catch an exception and root-cause
description: This skill should be used when the user asks "why does X crash", "find the source of this NPE", "catch this IllegalStateException", "trace this exception", "break on uncaught exception", "find what's throwing", or runs `/android-debugger:catch`. Sets an uncaught exception breakpoint (optionally scoped to a class), prompts the user to reproduce, awaits the hit via wait_for_event, then runs the explain pattern on the paused frame.
argument-hint: "[exception-class | \"any\" | empty for all uncaught]"
allowed-tools: mcp__android-debugger__connection_status, mcp__android-debugger__add_exception_breakpoint, mcp__android-debugger__wait_for_event, mcp__android-debugger__frame_snapshot, mcp__android-debugger__exception_summary, mcp__android-debugger__inspect_object, mcp__android-debugger__remove_breakpoint, mcp__android-debugger__evaluate
---

# Catch — break on the next throw and root-cause it

The high-leverage exception-triage workflow. User has a crash or an exception they can't pin down; this skill puts a breakpoint on the throw site and runs the analysis when it fires.

## What you do

1. Call `mcp__android-debugger__connection_status`. If not attached, tell the user to run `/android-debugger:attach` first and stop.

2. Resolve the exception class. Argument shapes:
   - Empty or literal `"any"` → catch ALL uncaught exceptions (`add_exception_breakpoint` with no `class`).
   - Short name (no dots, e.g. `NullPointerException`) → read `skills/catch/references/exception-fqn-table.md` and apply the resolution-order list there. Try each prefix in order, stop at the first existing class; ask the user to disambiguate if the name maps to multiple FQNs (e.g., three `CancellationException` variants).
   - Fully qualified (contains dots, e.g. `com.example.MyException`) → use as-is, never re-resolve.

2.5. **Yellow-flag check.** Before calling `add_exception_breakpoint`, check whether the resolved FQN is in the yellow-flag list at `skills/catch/references/exception-fqn-table.md` — exceptions like `kotlinx.coroutines.CancellationException`, `SecurityException`, `ActivityNotFoundException`, `DeadObjectException` are usually expected control flow, not bugs. If yellow-flagged, print one short warning line per the file's "Confirm message" column and proceed only if the user confirms.

3. Call `mcp__android-debugger__add_exception_breakpoint` with `{ class: <fqn>, caught: false, uncaught: true }` (or omit `class` for the "any" / empty case). By default we only break on **uncaught** exceptions — caught exceptions in production code (e.g., parser fallbacks) are noisy. Add `caught: true` only if the user explicitly asks for both.

4. Tell the user **what to do**: "Set. Now reproduce the crash — open the app, take the action that triggers it. I'll wait up to 60 seconds for the next throw."

5. Call `mcp__android-debugger__wait_for_event` with `{ timeout_ms: 60000, types: ["exception"] }`.

6. On hit:
   - Call `mcp__android-debugger__frame_snapshot` (depth: 8 — exceptions often have informative ancestor frames).
   - Call `mcp__android-debugger__exception_summary({ ref: <exception_id from the event> })`. The tool returns `{ exception_class, message, throw_site, trigger_frame, cause_chain, stack_summary }` — exactly the structured root-cause data you'd otherwise assemble by hand from `inspect_object` + frame walking. Use this as the basis for the report; fall back to `inspect_object` only if the tool errors with `code: invalid_target` (the ref expired or isn't actually a `Throwable`).
   - State the most likely root cause based on the `trigger_frame`'s locals (read them from the existing `frame_snapshot`, not by re-reading the same frame). If a `null` parameter caused a NPE, name the parameter and the method that received it.
   - Propose a **fix shape** (don't write code unless asked).
   - Suggest next steps: inspect specific locals, step out to see the caller, or `:trace` to find when the contributing state was set.

7. On timeout (`timed_out: true`):
   - Tell the user the breakpoint is still set and they can reproduce again. Offer to remove it via `remove_breakpoint`.

8. After analysis: ask whether to keep the breakpoint set (for repeated reproductions) or remove it.

## Anti-hallucination rules

Read `skills/explain/references/anti-hallucination.md` and follow the snapshot-grounding + evaluate-safety rules there. Apply them to every claim made about the exception's state — quote actual locals from the snapshot, never invent a value, refuse mutating `evaluate` calls.

## What you do NOT do

- Do not silently leave breakpoints set on session detach — they vanish anyway, but tell the user.
- Do not propose fixes from memory of similar bugs — ground every claim in what the snapshot shows.
- Do not break on caught exceptions by default; production code throws lots of caught exceptions for control flow.
