# Four-shape triage — canonical routing table

Single source of truth for shape classification. Read this from `/android-debugger:ad-investigate` (interactive triage) and from `agents/android-debug-orchestrator.md` (autonomous loop). If the table changes, change it here — both surfaces re-read this file each invocation, so there is no second copy to update.

The four shapes are exhaustive in practice: every Android debugging request the team has seen falls into one of them. A request that genuinely doesn't match any shape is almost always a request that doesn't need a debugger (e.g., "rename a variable", "review my code") — push back and ask the user to restate the *runtime* problem.

## Zeroth check — structural/static questions (route before the four shapes)

Some requests aren't about *runtime behavior* — they're about code *shape*: "show me the call graph for X", "what implements OnClickListener", "what calls LoginActivity.onCreate", "how is this package organized", "class hierarchy of Foo". These route directly to `/android-debugger:ad-graph` — a standalone SootUp-backed static-analysis skill that needs no attach, no Debug Plan, and no live VM.

This is not a fifth Debug-Plan shape (the four shapes above remain exhaustive for *dynamic* investigations) — `ad-graph` is an imperative single-call skill, the same category as `/android-debugger:ad-explain` and `/android-debugger:ad-status`.

**Trigger phrases**: "show me the call graph", "what implements X", "what calls Y", "class hierarchy of X", "how is this app/package structured/organized", "control flow of method X".

**Disambiguation**: "what calls Y" with no symptom → `ad-graph` (static). "what calls Y, because it's returning the wrong value" → Behavior shape (`ad-trace`) — the user has a runtime symptom and static structure alone won't answer it.

## The four shapes

| Shape | Trigger words / shapes the user types | Routes to (interactive) | Routes to (autonomous loop name) |
|---|---|---|---|
| **Crash** | "crashes", "NPE", "throws", "fatal error", a stack trace pasted, "FATAL EXCEPTION", "force close", "ANR" with a stack | `/android-debugger:ad-catch` | crash loop |
| **Behavior** | "doesn't work", "wrong value", "should do X but does Y", "find when X gets called", "trace this", "why is this state wrong", "Login does nothing on slow networks", "find where X happens" | `/android-debugger:ad-trace` (multi-call / ordering / "where") OR set a breakpoint + `/android-debugger:ad-explain` (known line) | behavior loop |
| **Flaky test** | "flaky", "1 in N runs", "intermittent", "sometimes fails", "passes locally fails on CI", "TestX flaked again", "this test is busted half the time", "test is being weird" | `/android-debugger:ad-bisect-flaky` | flaky-test bisect loop |
| **Onboarding** | "walk me through", "show me how X works", "explain what happens when I tap Y", "step through this method", "onboard me to this code" | `/android-debugger:ad-walk` | walk loop |

## Disambiguation rules

If the user's text matches more than one shape's triggers, prefer:

1. **Crash over Behavior** when a stack trace or exception name is named — exceptions are the most concrete starting point.
2. **Behavior over Onboarding** when "why does X happen" appears — onboarding is for the user wanting to *learn* code shape, not diagnose a problem.
3. **Flaky over Behavior** when the failure is intermittent — flaky has a specialized capture loop that Behavior's logpoint sweep can't substitute for.
4. **Onboarding only when no symptom is named** — "walk me through login" is onboarding; "walk me through why login fails" is Behavior.

## Sub-shapes within Behavior (most useful — promote above the table)

The Behavior bucket is the broadest. Sub-route on these signals before falling back to the generic Behavior loop:

- **Bug at a known line** — the user names a `file:line` or `Class.method` they suspect. Set a breakpoint there directly via `add_line_breakpoint`, prompt the user to reproduce, then run `/android-debugger:ad-explain` on the hit. Skip the logpoint sweep entirely.
- **"Find where X happens" / "trace this" / "what gets called"** — `/android-debugger:ad-trace` (logpoint sweep is the right tool — non-suspending, harvest a timeline).
- **"State is wrong" with no reproduction recipe** — put a watchpoint on the suspect field via `add_field_watchpoint` (capability permitting), prompt the user to reproduce, run `/android-debugger:ad-explain` when the watch fires. If `field_modification_watchpoints: false`, fall back to a logpoint sweep at every assignment site.

## Notes for the orchestrator agent

- Use the same shape classifier on the user's prompt; do not invent a fifth shape.
- After classifying, run the matching per-shape loop in `agents/android-debug-orchestrator.md`.
- If the user's goal is genuinely ambiguous, ask once via `AskUserQuestion`. Don't ask twice.
