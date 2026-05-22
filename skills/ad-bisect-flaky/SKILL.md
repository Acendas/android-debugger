---
name: ad-bisect-flaky
description: Bisect an Android flaky test via plan rerun loop.
model: opus
---

# Bisect-flaky — author a Debug Plan, run the test in a loop, diff pass vs fail

Flaky tests are state-divergence bugs masquerading as "intermittent." Author a Debug Plan whose setup captures state at the failing assertion line, run the test through the loop the user drives via `am instrument`, and aggregate per-run reports. The first divergence between pass and fail captures is the flake trigger.

## When to use

The user named an Android instrumentation test that fails intermittently (`<class>.<method>`). They want the divergence point, not a guess.

## Preflight: is this actually bisectable?

Read `skills/ad-bisect-flaky/references/flake-trigger-heuristics.md` and apply the timing-flake signature checklist **first**. Breakpoints capture state at a chosen line but can't capture timing windows — `IdlingResource` starvation, GC pauses, `Dispatchers.Default` mixing, system-clock drift produce flakes whose root cause is "the window closed." If the symptom matches a timing signature, route to `:trace`-style logpoint sweep instead (the plan in `:trace` runs without holding the VM and preserves timing). If no timing signature matches, continue here.

## Preflight

1. Call `connection_status`. If attached, ask the user to detach — the test runner attaches debug-wait and we re-attach to the test PID per round.
2. **Resolve the test target.** Grep the project to confirm the test class file; warn if not found.
3. **Identify 3–5 candidate flake roots** using the ranked pattern list in the heuristics file. Do not invent candidates outside that list.

## Tell the user how to drive the loop

Provide the cross-platform command they'll paste once per round (the skill itself does not shell `am instrument` — too much device state):

```
adb shell am instrument -w -e debug true -e class <fully.qualified.test.Class>#<method> <test.package>/<runner.fully.qualified>
```

The runner waits for the debugger. User runs `/android-debugger:ad-attach <pid>` once paused. Then control returns to this skill.

## Compose the plan

Read `references/plan-template.json`. The template installs a `class_load_bp` on the test class (so the line breakpoint resolves once the test loads) plus a `line_bp` at the assertion line, and harvests locals around the suspect variable.

Substitute:

- `<<PLAN_NAME>>` → e.g. `bisect-test-signin-retry`.
- `<<TEST_FQN>>` → test class FQN.
- `<<ASSERT_FILE>>` + `<<ASSERT_LINE>>` → the failing assertion location.
- `<<SUSPECT_LOCAL>>` → the local you suspect diverges (e.g., `counter`, `cached`). Adjust the hypothesis `expect` to a guess about its value.

Validate:

```
validate_plan(plan: <composed plan>)
```

## Per-round loop

For each round:

1. **User launches** the `am instrument` command.
2. **User attaches** via `/android-debugger:ad-attach <pid>`.
3. **Submit the plan**:

   ```
   run_debug_plan(plan: <composed plan>)
   ```

   Capture `plan_id`.

4. **Poll progress**:

   ```
   wait_for_event(timeout_ms: 60000, types: ["plan_progress"])
   ```

   On `completed`, read the report's `feel_outputs` and `snapshot_refs`. Stash under a per-round label: `pass-1`, `fail-1`, etc.

5. **User reports pass/fail.** Record the verdict alongside the captured state.

6. **Persist** the report locally for cross-round comparison. The orchestrator may keep an in-memory map keyed by round label; for longer sessions, write a small JSON file under `$CLAUDE_PLUGIN_DATA/android-debugger/bisect/<plan_id>-<round>.json` using `save_plan` semantics (atomic write).

7. **User detaches**, runs again, attaches the next round.

## After ≥1 pass and ≥1 fail captured

Diff the captured states across rounds:

- The first divergent `feel_output` is the flake trigger.
- If states look identical, the divergence is in something not captured (timing, GC, system clock) — add more setup entries around timing-sensitive code and re-run.

## Tighten with a conditional plan

Once you have a hypothesis, re-author the plan with a `condition` on the line breakpoint so it only fires under the failing predicate (e.g., `condition: "counter > 10"`). Submit the tighter plan as a fresh `run_debug_plan` per round. Iterate.

## Final report

- State the trigger condition in plain English, grounded in actual captured values.
- Show the divergent locals as `pass-N: <val>` vs `fail-M: <val>`.
- Propose a fix shape (e.g., "the cache isn't being cleared between tests; add `@After cache.clear()`").

## Anti-hallucination

Read `skills/ad-explain/references/anti-hallucination.md`. Two extra rules specific to this skill:

- Never claim a divergent local without grounding in **two** captured reports — quote the actual values.
- Never propose a fix until you've captured ≥1 pass and ≥1 fail. If after 10 rounds you can't reproduce the failure, surface that honestly and recommend the logpoint-sweep fallback per the heuristics file.

## What you do NOT do

- Do not run `am instrument` from this skill. Tell the user the command; let them run it.
- Do not declare a flake "fixed" without observing both pass and fail in captured reports.
- Do not place setup breakpoints on every line of the test — start with 3–5 and tighten with `condition` once you have a hypothesis.
