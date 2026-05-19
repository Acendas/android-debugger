---
name: ad-status
description: Show current Android debugger session state.
allowed-tools: mcp__android-debugger__connection_status, mcp__android-debugger__list_threads, mcp__android-debugger__render_capabilities
---

# Status — current debugger session at a glance

A read-only one-pager. Don't change anything; don't probe the user's machine. Just call the server, render the state.

## What you do

1. Call `mcp__android-debugger__connection_status`. The response carries `attached`, `state`, `package`, `serial`, `pid`, `jdwp_port`, `since_ms`, plus breakpoint and watch counts.

2. Render as a compact summary:

   ```
   attached:   yes
   state:      ATTACHED_PAUSED
   target:     com.example.app (pid 9840) on emulator-5554
   port:       tcp:50398 forwarded since 12s
   breakpoints: 3 active
   watches:     1 expression
   ```

   If unattached:

   ```
   attached:   no
   state:      UNATTACHED
   ```

   **`since_ms` rendering rule:** if `since_ms` is present and within a sane range (0 to 86400000ms / 24h), render as a humanized duration (`12s`, `4m 30s`, `1h 12m`). If `since_ms` is null, missing, negative, or absurd (>86400000ms — clock skew, server bug), render the raw `attached_at` ISO timestamp instead (`since 2025-05-09T18:31:22Z`). Never render a nonsense value like `since -2147483648s` or `since 3987d`.

3. If `state == ATTACHED_PAUSED`, optionally call `mcp__android-debugger__list_threads` and surface the count of suspended threads + the name of the paused thread (the one carrying the user's code, usually `main`). This helps the user remember "right, we're paused at a breakpoint."

4. If `state == ATTACHED_RUNNING` and there are active breakpoints, suggest the user `resume`/`pause` or wait for a breakpoint hit. If there are zero breakpoints set, suggest `/android-debugger:ad-catch <exception>` for crashes or `/android-debugger:ad-investigate <goal>` for the catch-all router.

## What you do NOT do

- Do not run `adb devices` or any external command — `connection_status` already carries every signal needed.
- Do not invoke the JDI debugger control tools (`step_*`, `resume`, `pause`) — status is read-only.
- Do not editorialize. If the server is unreachable, say so and stop — don't propose fixes (that's `/android-debugger:ad-setup`'s job).
- Do not call `frame_snapshot` from status — that's expensive and meant for `/android-debugger:ad-explain`. Status stays cheap.
