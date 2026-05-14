---
name: trace
description: Trace an Android code path via logpoint sweep.
argument-hint: "<symptom or call site to trace>"
allowed-tools: Read, Grep, Glob, mcp__android-debugger__connection_status, mcp__android-debugger__add_line_breakpoint, mcp__android-debugger__list_breakpoints, mcp__android-debugger__remove_breakpoint, mcp__android-debugger__tail_logcat, mcp__android-debugger__read_logcat, mcp__android-debugger__stop_logcat
---

# Trace — instrument-and-reproduce, no breakpoint pause

Some bugs don't need stepping — they need to know "what got called, in what order, with what values." Logpoints are non-suspending breakpoints that emit a rendered log line and immediately resume. Drop a handful across a suspect call graph, run the failing path, read the timeline.

This is the highest-leverage workflow for ordering bugs, race conditions, and "I don't know where this value comes from" symptoms.

## What you do

1. Call `mcp__android-debugger__connection_status`. Must be attached.

2. **Identify the suspect call graph.** The argument is a symptom or seed location — e.g., "login flow" or "MyVm.uiState". Use Read/Grep on the user's project (NOT decompiled bytecode) to find:
   - The entry point (e.g., the click handler, lifecycle method, network callback).
   - Methods that READ the suspect value.
   - Methods that WRITE the suspect value.
   - Aim for 5–10 instrumentation points across the call graph. More than 12 is noise.

2.5. **Check whether the codepath is reactive before placing logpoints.** Modern Kotlin Android moves values through Flow operators, Compose recomposition, and suspend continuations — not method-call boundaries. Read `skills/trace/references/reactive-codepaths.md` and apply the recognition checklist there. If the codepath matches a reactive pattern (Flow/StateFlow, Compose, suspend functions), follow the per-shape strategy in that file (logpoint at every `emit` and `collect`, at `derivedStateOf`/`LaunchedEffect`, at suspending call sites — *not* at "writers" and "readers"). If no reactive signals match, fall through to the imperative writer/reader strategy below.

3. **Confirm the BATCH** with one `AskUserQuestion` listing the proposed `(file:line)` set with a one-line label each. Do NOT ask per-logpoint — the user wants to confirm the recipe, not approve 10 things.

4. **Place the logpoints.** For each `(file, line)`, call `mcp__android-debugger__add_line_breakpoint` with:
   - `file: <relative path>`
   - `line: <number>`
   - `log_message: <template>` — use `{expr}` placeholders to inject local values. **Never call non-trivial methods in a `log_message` template — read fields, not methods.** Method calls run in the target VM and `toString()` chains, lazy getters, and `equals`/`hashCode` implementations can mutate state. Only field reads (`{user.id}`, `{state.name}`) and trivial getters that match Java/Kotlin property convention (`{list.size}`, `{string.length}`, `{collection.isEmpty}`) are safe. Avoid `{password.length()}` (method call) — use `{password.length}` (Kotlin property accessor; resolves to the field). Custom `toString`, computed properties, and fluent builders are out of scope.

5. **Start logcat tail** (if not already running) via `mcp__android-debugger__tail_logcat` filtering on the app's package + tag `debugger:logpoint`. Save the `buffer_id`.

6. **Tell the user to reproduce** the symptom. "Run the failing flow now. I'll harvest the timeline when you say go."

7. **Wait for the user's signal** — they'll tell you they triggered it. (You can also poll `read_logcat` with a short interval.)

8. **Harvest** via `mcp__android-debugger__read_logcat({ buffer_id })`. **If `read_logcat` returns 0 logpoint entries** after the user reports a successful repro, conclude the breakpoint set didn't intersect the actual codepath — broaden the suspect graph (try the reactive recognition again if you skipped it; the path may have gone through a Flow operator), or confirm the repro by running `/android-debugger:explain` if the VM happened to pause at a non-logpoint event. Don't silently re-prompt the user to "try again" — empty logs are a meaningful signal that the recipe is wrong, not that the user mis-reproduced.

   Render the timeline as a numbered list with timestamps:

   ```
   01  T+0ms     login.click user=u_42 pwLen=8
   02  T+12ms    AuthRepo.signIn called user=u_42
   03  T+340ms   AuthRepo.signIn returned ok=false err=NetworkError
   04  T+341ms   LoginVm._uiState <- Failure(NetworkError)
   ...
   ```

9. **Reason over the timeline.** Flag:
   - Order surprises ("event 03 happened before 02 — that's weird").
   - Missing entries ("expected `AuthRepo.signOut` between 02 and 03 but didn't see it").
   - Repeated invocations ("step 04 ran 3 times — possible recomposition / leaked observer").

10. **Propose** the hypothesis + suggested next move (drill in with `:explain` at a specific bp, narrow the timeline with a conditional logpoint, or fix the obvious thing).

10a. **Conditional logpoints for narrowing.** If the first sweep produced too much noise, re-place the noisiest logpoints with a `condition:` clause so they only fire under the failing predicate. The `condition` gate is checked BEFORE the log render — buffer stays clean. Example: change `add_line_breakpoint({ file, line, log_message: "..." })` to `add_line_breakpoint({ file, line, condition: "result.success = false", log_message: "..." })` to keep only the failing-case entries. Gates apply uniformly — `hit_count: 10` + `log_message` also works (log every 10th hit) for sampling hot paths.

11. **Cleanup.** Ask the user if they want to keep the logpoints (for further runs) or remove them. On confirm, call `remove_breakpoint` for each id from `list_breakpoints`. Stop the logcat buffer with `stop_logcat`.

## Anti-hallucination rules

Read `skills/explain/references/anti-hallucination.md` and follow the snapshot-grounding + evaluate-safety rules there. Apply them especially when reasoning over the harvested timeline — never claim a value appears in the timeline that doesn't, never claim a logpoint fired N times when the count is something else.

## What you do NOT do

- Do not place logpoints inside framework code (java.*, android.*, kotlin.*) — too noisy. Stick to the user's project sources.
- Do not use suspending breakpoints when a logpoint will do — pause-on-every-hit destroys the timing the user is trying to understand.
- Do not write huge `log_message` templates. 1–4 placeholders max.
- Do not chase obvious ordering anomalies before the user asks — surface them, let them direct the next move.

## Cross-platform notes

All work routes through MCP tools — no shell commands needed. Skill is fully portable.
