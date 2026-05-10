---
name: Investigate an Android issue
description: This skill should be used when the user asks to "debug this android bug", "investigate why X happens", "find the cause of this issue", "I have a problem with my android app", "why is this happening", "help me debug this", or runs `/android-debugger:investigate`. The catch-all orchestrator — first asks whether to drive interactively or hand the loop to the autonomous Android Debug Orchestrator agent, then either triages into a specialist skill (:catch / :trace / :bisect-flaky / :walk) or dispatches to the agent for end-to-end investigation. Use this when you don't know which specialized skill fits.
argument-hint: "<bug description or investigation goal>"
allowed-tools: mcp__android-debugger__connection_status
---

# Investigate — top-level debugging orchestrator

The catch-all entry point. The user describes a problem; this skill picks between **interactive mode** (you drive each step, surfacing snapshots for the user) and **autonomous mode** (the `Android Debug Orchestrator` agent runs the full loop and returns a report).

## What you do

1. Call `mcp__android-debugger__connection_status`. If not attached, tell the user to run `/android-debugger:attach` first and stop.

2. **Decide the mode** with one structured question via `AskUserQuestion`:

   | Mode | When to pick |
   |---|---|
   | **Interactive** (Recommended for behavior bugs) | The user has product/UX context, wants to see each snapshot, plans to redirect mid-flight. Best for "explain what's happening", "why is this state wrong", anything where human judgment matters. |
   | **Autonomous** | The bug has a clear repro recipe. The user wants the agent to iterate without watching each tool call. Best for crashes with stack traces, mechanical flaky tests, "investigate this and tell me when you're done". |

   Default to interactive if the user's argument doesn't suggest "let me know when you're done" semantics.

3. **Interactive path** — triage the goal into one of four shapes and dispatch to a specialist skill:

   | Shape | Trigger words | Dispatches to |
   |---|---|---|
   | **Crash** | "X crashes", "NPE", "throws", a stack trace pasted, "fatal error" | `/android-debugger:catch` |
   | **Unexpected behavior** | "Login does nothing on slow networks", "the value is wrong", "Y doesn't work", "find when X gets set to Y" | `/android-debugger:trace` (if ordering/multi-call), or set a breakpoint + `:explain` (if known line) |
   | **Flaky test** | "Test fails 1 in 10 runs", "intermittent failure", "this test is flaky" | `/android-debugger:bisect-flaky` |
   | **Onboarding / understanding** | "Walk me through X", "show me how Y works" | `/android-debugger:walk` |

4. **Autonomous path** — dispatch to the orchestrator agent:

   ```
   Agent({
     subagent_type: "android-debug-orchestrator",
     description: "Autonomous Android debug investigation",
     prompt: "<the user's goal verbatim, with any context they provided>"
   })
   ```

   The agent runs the loop end-to-end and returns a structured findings report (hypothesis / evidence / proposed fix shape / repro recipe). Render the report verbatim back to the user — don't second-guess it.

5. After autonomous dispatch returns, ask whether the user wants to:
   - Apply the proposed fix (you do not auto-apply).
   - Drill into a specific frame interactively (suggests `/android-debugger:explain`).
   - Detach (suggests `/android-debugger:detach`).

## Sub-shapes within "unexpected behavior"

The "unexpected behavior" interactive bucket is the broadest. Sub-route:

- **Bug at a known line** (user names the file/method) → set a breakpoint there directly via `add_line_breakpoint`, prompt the user to reproduce, then run `/android-debugger:explain` on the hit.
- **"Find where X happens" / "trace this" / "what gets called"** → `/android-debugger:trace` (logpoint sweep is the right tool).
- **"State is wrong" with no reproduction recipe** → put a watchpoint on the suspect field via `add_field_watchpoint` (capability permitting), prompt user to reproduce, run `:explain` when the watch fires.

## What you do NOT do

- Do not ask multiple clarifying questions. One structured question for mode max; one for shape if interactive and the goal is genuinely ambiguous.
- Do not silently dispatch a workflow that doesn't fit. If the user's argument is genuinely ambiguous, ask once.
- Do not duplicate the dispatched skill or agent's logic here. This skill is router-only.
- Do not auto-pick autonomous mode for goals that need product judgment ("does this feel laggy?", "should this UI be different?") — those need the user.

## Style

Lead with the dispatch decision: "Triage: this is a crash. Running `:catch java.lang.NullPointerException` interactively." Or: "Dispatching to the autonomous orchestrator — I'll bring back a findings report." Then hand off cleanly.
