---
name: Investigate an Android issue
description: This skill should be used when the user asks to "debug this android bug", "investigate why X happens", "find the cause of this issue", "I have a problem with my android app", "why is this happening", "help me debug this", or runs `/android-debugger:investigate`. The catch-all orchestrator — first asks whether to drive interactively or hand the loop to the autonomous Android Debug Orchestrator agent, then either triages into a specialist skill (:catch / :trace / :bisect-flaky / :walk) or dispatches to the agent for end-to-end investigation. Use this when you don't know which specialized skill fits.
argument-hint: "<bug description or investigation goal>"
allowed-tools: AskUserQuestion, Task, mcp__android-debugger__connection_status, mcp__android-debugger__render_capabilities, mcp__android-debugger__add_line_breakpoint, mcp__android-debugger__add_field_watchpoint
---

# Investigate — top-level debugging orchestrator

The catch-all entry point. The user describes a problem; this skill picks between **interactive mode** (you drive each step, surfacing snapshots for the user) and **autonomous mode** (the `Android Debug Orchestrator` agent runs the full loop and returns a report).

## What you do

1. Call `mcp__android-debugger__connection_status`. If not attached, tell the user to run `/android-debugger:attach` first and stop.

2. **Decide the mode** with one structured question via `AskUserQuestion`:

   | Mode | When to pick |
   |---|---|
   | **Autonomous** (Recommended when there's a concrete repro recipe) | The goal mentions a specific exception, test name, or call path. The user wants the agent to iterate without watching each tool call. Best for crashes with stack traces, named flaky tests, "investigate this and tell me when you're done". |
   | **Interactive** | The goal needs product judgment ("does this feel laggy?", "should this UI be different?") or the user explicitly asks to drive. Best for ambiguous behavior, UX-flavored questions, anything where the user wants to see each snapshot and redirect mid-flight. |

   **Default rule:** default to **autonomous** if the goal has a concrete repro recipe (specific exception name, test name, call path, file/method named); default to **interactive** if the goal needs product judgment or the user explicitly asks to drive. Busy users want the loop run for them unless they signal otherwise — biasing to autonomous matches the skill-creator framework's "models under-trigger" finding.

3. **Triage by shape.** Read `skills/investigate/references/four-shape-triage.md` and apply the shape-matching rules + sub-shape branches there. The file is canonical (shared with the orchestrator agent), so a future change to the table updates both behaviors. Sub-shapes within "Behavior" (covered in the file) are the most useful — surface them above the four-shape table when routing.

4. **Interactive path** — once you've classified the shape via the reference, dispatch to the matching specialist skill: `/android-debugger:catch` (crash), `/android-debugger:trace` or set a bp + `:explain` (behavior), `/android-debugger:bisect-flaky` (flaky test), `/android-debugger:walk` (onboarding). For Behavior sub-shapes, follow the per-sub-shape routing in the reference.

5. **Autonomous path** — dispatch to the orchestrator agent. Pass an attached-session hint so the agent doesn't re-run preflight that this skill just confirmed:

   ```
   Agent({
     subagent_type: "android-debug-orchestrator",
     description: "Autonomous Android debug investigation",
     prompt: "<the user's goal verbatim, with any context they provided>\n[orchestrator note: session is attached to <package> on <serial>; skip preflight]"
   })
   ```

   The agent runs the loop end-to-end and returns a structured findings report (hypothesis / evidence / proposed fix shape / repro recipe). Render the report verbatim back to the user — don't second-guess it.

6. After autonomous dispatch returns, ask whether the user wants to:
   - Apply the proposed fix (you do not auto-apply).
   - Drill into a specific frame interactively (suggests `/android-debugger:explain`).
   - Detach (suggests `/android-debugger:detach`).

## What you do NOT do

- Do not ask multiple clarifying questions. One structured question for mode max; one for shape if interactive and the goal is genuinely ambiguous.
- Do not silently dispatch a workflow that doesn't fit. If the user's argument is genuinely ambiguous, ask once.
- Do not duplicate the dispatched skill or agent's logic here. This skill is router-only.
- Do not auto-pick autonomous mode for goals that need product judgment ("does this feel laggy?", "should this UI be different?") — those need the user.

## Style

Lead with the dispatch decision: "Triage: this is a crash. Running `:catch java.lang.NullPointerException` interactively." Or: "Dispatching to the autonomous orchestrator — I'll bring back a findings report." Then hand off cleanly.
