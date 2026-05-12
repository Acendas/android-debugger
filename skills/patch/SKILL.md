---
name: Patch Android code via HotSwap
description: Edit a method body, recompile, hot-swap into the running app, and verify against a caller-supplied success criterion — no app restart.
argument-hint: "<goal> verify_via: <success criterion>"
allowed-tools: AskUserQuestion, Bash, Read, Edit, Glob, Grep, mcp__android-debugger__connection_status, mcp__android-debugger__agent_info, mcp__android-debugger__hot_swap_class, mcp__android-debugger__hot_swap_classes, mcp__android-debugger__hot_swap_revert, mcp__android-debugger__list_threads, mcp__android-debugger__frame_snapshot, mcp__android-debugger__wait_for_event, mcp__android-debugger__dump_view_hierarchy, mcp__android-debugger__get_current_activity
---

# Patch — edit, hot-swap, verify

The user wants to change running app behavior without rebuild + reinstall + relaunch. v1.5 ships method-body redefinition via JVMTI on top of the v1.4 agent.

The user invocation looks like:

```
/android-debugger:patch <goal> verify_via: <success criterion>
```

Two clauses, separated by `verify_via:`. Both are required. Refuse to enter the autonomous loop without `verify_via:`.

## What you do

1. **Probe HotSwap support.** Call `mcp__android-debugger__connection_status`. If not attached, tell the user to run `/android-debugger:attach` first and stop. Then call `mcp__android-debugger__agent_info`. Read these fields:
   - `hot_swap_supported`: must be `true` to proceed.
   - `minify_detected`: if `true`, refuse with: "the attached build is minified; set `minifyEnabled=false` on the debug variant and rebuild."
   - `force_re_enter_supported`: gates the `force_re_enter` flag we use later. Note the value.

2. **Parse the input.** Split the user's argument on `verify_via:` (case-insensitive). The first half is the goal; the second is the success criterion. If `verify_via:` is missing, tell the user the skill needs it and show the syntax. Stop — do not guess a criterion.

3. **Locate the source.** Use Grep + Read to find the symbol referenced in the goal. If multiple candidates, ask the user via `AskUserQuestion`. Confirm the chosen file with a one-liner: "I'll edit `<file>:<line>` (<package>.<class>.<method>)."

4. **Snapshot the build dir.** Determine the Android module's build output directory. Default heuristic: walk up from the source file looking for `build.gradle.kts` or `build.gradle`, then check `<module>/build/tmp/kotlin-classes/debug/`. If that doesn't exist, fall back to `<module>/build/intermediates/javac/debug/classes/` (Java-only modules).

   Run the cross-platform classdiff tool to hash every `.class` under the build dir:

   ```bash
   "${CLAUDE_PLUGIN_ROOT}/tools/android-debugger-classdiff" snapshot --root <module>/build/tmp/kotlin-classes/debug
   ```

   It writes `$TMPDIR/android-debugger/classdiff-<sessionId>.json` and prints the path to stdout. Capture the path as `BEFORE_PATH`.

5. **Apply the edit.** Use the `Edit` tool to change the source file. Don't ad-lib — the change must match the user's stated goal verbatim. If the goal is ambiguous, ask via `AskUserQuestion` before editing.

6. **Recompile.** Shell the project's incremental Kotlin compile:

   ```bash
   cd <module-root>
   ./gradlew :<module>:compileDebugKotlin --console=plain
   ```

   Bound to 90 seconds. On non-zero exit, return the last 30 lines of stderr and stop. Don't attempt to swap after a failed build.

7. **Diff the build dir.** Run classdiff again with the pre-snapshot:

   ```bash
   "${CLAUDE_PLUGIN_ROOT}/tools/android-debugger-classdiff" diff --before "$BEFORE_PATH" --root <module>/build/tmp/kotlin-classes/debug
   ```

   Stdout is a JSON object with `changed: [{ fqn, class_path }, ...]`. Read each changed `.class` from disk and base64-encode for the swap call.

8. **Swap.** If only one class changed, call `mcp__android-debugger__hot_swap_class`. If more than one (lambdas, inner classes), call `mcp__android-debugger__hot_swap_classes` with the batch. Pass `force_re_enter: true` IFF (a) `agent_info.force_re_enter_supported` was true AND (b) the response would otherwise show `active_frames_using_old_code` populated. A practical proxy: if the user paused the VM at a breakpoint, set `force_re_enter: true`; otherwise leave it false.

   On `code: redefine_unsupported_shape_change`, surface the `diff` array and stop — the user must restructure the change (e.g., move the new method to a different file, or add it via a different path that doesn't require redefinition).

   On `code: capability_unavailable` for `force_re_enter`, retry once with `force_re_enter: false` and note the caveat in the response.

9. **Drive the verify clause.** Parse `verify_via:` into an action + expectation. Common patterns:
   - "tap X and expect Y to be foreground" → use `mcp__android-debugger__dump_view_hierarchy` to find X, then `adb shell input tap` via Bash, then `mcp__android-debugger__get_current_activity` to confirm Y.
   - "set breakpoint on Z and observe variable W" → install the breakpoint, `wait_for_event`, `frame_snapshot`, check W.
   - "navigate to settings and expect Y visible" → use `dump_view_hierarchy` to read current screen, navigate via input events, re-dump.

   Report `verified: true` only when the expectation is observably satisfied. On failure, `verified: false` with the reason — the actual observation vs. the expected.

10. **Offer revert on failure.** If `verified: false`, ask via `AskUserQuestion`: "Swap installed but verify failed — revert?" If yes, call `mcp__android-debugger__hot_swap_revert` (no args = revert every class swapped this session).

## Cross-platform notes

- `adb` resolution and ALL adb invocations route through the server (existing tools).
- Bash blocks must work on macOS / Linux / Windows: no `mktemp`, `readlink -f`, GNU `realpath`, `sed -i ''`, `stat -c`, `/dev/stdin`. The classdiff tool ships as both `.sh` and `.cmd` wrappers; invoke as a bare command and the OS picks the right one.

## What you do NOT do

- Don't pick a verify criterion if the user didn't supply one. Always require explicit `verify_via:`.
- Don't retry a failed Gradle build automatically. Surface the error and stop.
- Don't attempt to swap if `agent_info` says HotSwap isn't supported on this device.
- Don't swap minified builds — the user gets a clearer error from refusing upfront than from a JVMTI `class_not_loaded`.
- Don't `@Composable` warnings or coroutine warnings to refuse the swap — surface them in the response so the user knows the result may not propagate cleanly, but proceed.
