---
name: Investigate an Android issue
description: This skill should be used when the user asks to "debug this android bug", "investigate why X happens", "find the cause of this issue", "I have a problem with my android app", "why is this happening", "help me debug this", or runs `/android-debugger:investigate`. The catch-all orchestrator — triages the user's goal into one of four shapes (crash / unexpected behavior / flaky test / onboarding) and dispatches to the right specialized skill (:catch / :trace / :bisect-flaky / :walk). Use this when you don't know which specialized skill fits.
argument-hint: "<bug description or investigation goal>"
allowed-tools: mcp__android-debugger__connection_status
---

# Investigate — top-level debugging orchestrator

The catch-all entry point. The user describes a problem and Claude triages it to the right specialized workflow.

## What you do

1. Call `mcp__android-debugger__connection_status`. If not attached, tell the user to run `/android-debugger:attach` first and stop.

2. **Triage the goal** into one of four shapes. Use the user's argument; if ambiguous, ask **one** structured question via `AskUserQuestion` with these four options:

   | Shape | When to pick | Dispatches to |
   |---|---|---|
   | **Crash** | "X crashes", "I'm getting an NPE", "this throws", a stack trace pasted, "fatal error" | `/android-debugger:catch` |
   | **Unexpected behavior** | "Login button does nothing on slow networks", "the value is wrong", "Y doesn't work", "this should do Z but does W", "find when X gets set to Y" | `/android-debugger:trace` (logpoint sweep) — best when the bug is about *ordering* or *what gets called*. Or set a breakpoint manually if the bug is at a known line. |
   | **Flaky test** | "Test fails 1 in 10 runs", "intermittent failure", "this test is flaky", "sometimes passes sometimes fails" | `/android-debugger:bisect-flaky` |
   | **Onboarding / understanding** | "Walk me through X", "show me how Y works", "explain what happens when…", "I'm new to this code" | `/android-debugger:walk` |

3. **Confirm the dispatch** with a one-liner. "Looks like a crash investigation — running `/android-debugger:catch` for `<inferred exception class>`." Then call the chosen skill.

4. If the goal genuinely doesn't fit any shape (e.g., "is the debugger working?"), suggest `/android-debugger:setup` or `/android-debugger:status` instead.

## Sub-shapes within "unexpected behavior"

The "unexpected behavior" bucket is the broadest. Sub-route:

- **Bug at a known line** (user names the file/method) → set a breakpoint there directly via `add_line_breakpoint`, prompt the user to reproduce, then run `/android-debugger:explain` on the hit.
- **"Find where X happens" / "trace this" / "what gets called"** → `/android-debugger:trace` (logpoint sweep is the right tool).
- **"State is wrong" with no reproduction recipe** → put a watchpoint on the suspect field via `add_field_watchpoint` (capability permitting), prompt user to reproduce, run `:explain` when the watch fires.

## What you do NOT do

- Do not ask multiple clarifying questions. One structured question max — the four-shape triage is enough.
- Do not silently dispatch a workflow that doesn't fit. If the user's argument is genuinely ambiguous, ask once.
- Do not duplicate the dispatched skill's logic here. This skill is router-only — the specialized skill does the actual work.

## Style

Lead with the dispatch decision: "Triage: this is a crash. Running `:catch java.lang.NullPointerException`." Then hand off. The user gets one quick acknowledgement and then the specialized workflow takes over.
