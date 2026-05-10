---
name: Bisect a flaky test
description: This skill should be used when the user asks "this test fails 1 in 10 times", "bisect this flaky test", "narrow down a flaky failure", "why does this test sometimes fail", "find the trigger for the flake", or runs `/android-debugger:bisect-flaky`. Loops a flaky instrumented test until both pass and fail are observed, captures state at the divergence point with conditional breakpoints, narrows down the trigger, proposes a hypothesis.
argument-hint: "<test class.method>"
allowed-tools: Bash, Read, Grep, mcp__android-debugger__connection_status, mcp__android-debugger__attach, mcp__android-debugger__detach, mcp__android-debugger__add_exception_breakpoint, mcp__android-debugger__add_line_breakpoint, mcp__android-debugger__list_breakpoints, mcp__android-debugger__remove_breakpoint, mcp__android-debugger__wait_for_event, mcp__android-debugger__frame_snapshot, mcp__android-debugger__evaluate, mcp__android-debugger__tail_logcat, mcp__android-debugger__read_logcat
---

# Bisect-flaky — loop a flaky test until you can see the divergence

Flaky tests are state-divergence bugs masquerading as "intermittent." This skill runs the test in a loop in debug-wait mode, captures the state when it fails vs. when it passes, and points at the difference.

The loop is human-in-the-loop: Claude orchestrates the breakpoints + analysis; the user launches the test runs (because launching `am instrument` from a skill bash block crosses too much device state to do robustly).

## What you do

1. Call `mcp__android-debugger__connection_status`. If attached, ask the user to detach first (the test runner attaches debug-wait — we'll re-attach to the test PID).

2. **Resolve the test target.** The user provides `<class>.<method>` or just the class. Grep the user's project to confirm the test class file path; warn if not found.

3. **Identify likely flake roots** in the test:
   - The assertion line(s) in the test method (where the failure surfaces).
   - Calls in the system-under-test that involve: shared state, `TimeUnit.MILLISECONDS.sleep`, coroutine `delay()`, network/IO, random, system clock, threading primitives.
   - Aim for 3–5 candidate breakpoint sites, ranked by likelihood.

4. **Tell the user how to run the test in debug-wait mode.** Provide a concrete command they can paste — cross-platform (works on macOS / Linux / Windows shells with `adb` on PATH):

   ```
   adb shell am instrument -w -e debug true \
     -e class <fully.qualified.test.Class>#<method> \
     <test.package>/<runner.fully.qualified>
   ```

   The runner waits for the debugger to attach. Tell them the PID will appear in the output as `Waiting for debugger`.

5. **Walk the user through the loop:**
   - **Round N:** User launches the test → it pauses waiting for debugger → user runs `/android-debugger:attach <pid>` → control comes back here.
   - At your candidate breakpoints, call `add_line_breakpoint` for the assertion line and the top-1 flake-suspect line.
   - Tell the user: "Resume; if the test passes, tell me 'pass'. If it fails, tell me 'fail' — I'll inspect."
   - If `wait_for_event` returns a `stopped` event (breakpoint hit), call `frame_snapshot` and capture key locals via `evaluate`. Stash the captured state per round (label as `pass-1`, `fail-1`, etc.).
   - On `resume`, the test concludes; user reports pass/fail.

6. **Aim for ≥1 pass and ≥1 fail captured.** Once you have both:
   - Diff the captured states. The first divergence is the flake trigger.
   - If states look identical, the divergence is in something we didn't capture (timing, GC pause, system clock). Add more breakpoints around timing-sensitive code and re-run.

7. **Tighten with a conditional breakpoint** once you have a hypothesis. Example: if the suspect is `userCount > expected`, set `add_line_breakpoint({ ..., condition: "userCount > 10" })` so the bp only fires when the flaky condition is true. Iterate the loop with the tighter condition.

8. **Final report:**
   - State the trigger condition in plain English.
   - Show the divergent locals.
   - Propose a fix shape (e.g., "the cache isn't being cleared between tests; add `@After cache.clear()`" or "the assertion races against an async write; use `runTest { advanceUntilIdle() }`").
   - Cleanup: `remove_breakpoint` for every id in `list_breakpoints`. Detach.

## What you do NOT do

- Do not run `am instrument` from this skill's bash. Test runners interact with installation state, signing, runner classes — too brittle to wrap. Tell the user the command and let them run it.
- Do not declare a flake "fixed" without seeing both pass and fail captured at least once.
- Do not place breakpoints on every line of the test — start with 3–5 and tighten with conditions.

## Cross-platform notes

The user pastes the `adb shell am instrument` command into their own shell. It works the same on macOS / Linux / Windows. The skill itself doesn't shell out — only the user does.
