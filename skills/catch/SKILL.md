---
name: Catch an exception and root-cause
description: This skill should be used when the user asks "why does X crash", "find the source of this NPE", "catch this IllegalStateException", "trace this exception", "break on uncaught exception", "find what's throwing", or runs `/android-debugger:catch`. Sets an uncaught exception breakpoint (optionally scoped to a class), prompts the user to reproduce, awaits the hit via wait_for_event, then runs the explain pattern on the paused frame.
argument-hint: "[exception-class]"
allowed-tools: mcp__android-debugger__connection_status, mcp__android-debugger__add_exception_breakpoint, mcp__android-debugger__wait_for_event, mcp__android-debugger__frame_snapshot, mcp__android-debugger__remove_breakpoint, mcp__android-debugger__evaluate
---

# Catch — break on the next throw and root-cause it

The high-leverage exception-triage workflow. User has a crash or an exception they can't pin down; this skill puts a breakpoint on the throw site and runs the analysis when it fires.

## What you do

1. Call `mcp__android-debugger__connection_status`. If not attached, tell the user to run `/android-debugger:attach` first and stop.

2. Resolve the exception class. Argument shapes:
   - Empty / "any" → catch ALL uncaught exceptions (`add_exception_breakpoint` with no `class`).
   - Short name (e.g. `NullPointerException`) → expand to `java.lang.NullPointerException`. Common Android exceptions: prepend `java.lang.` (NPE, IllegalStateException, IllegalArgumentException, etc.) or `android.os.` (DeadObjectException) or fully qualify when ambiguous.
   - Fully qualified (e.g. `com.example.MyException`) → use as-is.

3. Call `mcp__android-debugger__add_exception_breakpoint` with `{ class: <fqn>, caught: false, uncaught: true }`. By default we only break on **uncaught** exceptions — caught exceptions in production code (e.g., parser fallbacks) are noisy. Add `caught: true` only if the user explicitly asks for both.

4. Tell the user **what to do**: "Set. Now reproduce the crash — open the app, take the action that triggers it. I'll wait up to 60 seconds for the next throw."

5. Call `mcp__android-debugger__wait_for_event` with `{ timeout_ms: 60000, types: ["exception"] }`.

6. On hit:
   - Call `mcp__android-debugger__frame_snapshot` (depth: 8 — exceptions often have informative ancestor frames).
   - Render the exception type + message (rendered from the exception object — `inspect_object` the `exception_id` from the event payload to get the message field).
   - Identify the **throw site** (top frame) and the **trigger frame** (first user-code frame, skipping framework `<init>` boilerplate).
   - State the most likely root cause based on the locals visible at the trigger frame. If a `null` parameter caused a NPE, name the parameter and the method that received it.
   - Propose a **fix shape** (don't write code unless asked).
   - Suggest next steps: inspect specific locals, step out to see the caller, or `:trace` to find when the contributing state was set.

7. On timeout (`timed_out: true`):
   - Tell the user the breakpoint is still set and they can reproduce again. Offer to remove it via `remove_breakpoint`.

8. After analysis: ask whether to keep the breakpoint set (for repeated reproductions) or remove it.

## What you do NOT do

- Do not silently leave breakpoints set on session detach — they vanish anyway, but tell the user.
- Do not propose fixes from memory of similar bugs — ground every claim in what the snapshot shows.
- Do not break on caught exceptions by default; production code throws lots of caught exceptions for control flow.
