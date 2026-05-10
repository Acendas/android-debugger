---
name: Detach android-debugger
description: This skill should be used when the user asks to "detach", "stop debugging android", "disconnect debugger", "end debug session", "close the debugger", or runs `/android-debugger:detach`. Releases the adb forward, disposes the JDI VirtualMachine, removes all event requests, and returns the app to a fully running state. Always run this when finished — abandoned attachments leave the app suspended on the next breakpoint hit.
allowed-tools: mcp__android-debugger__detach, mcp__android-debugger__connection_status
---

# Detach — clean shutdown of the debug session

Always call this at the end of a session. Forgetting to detach can leave the app suspended at its next breakpoint hit — the user won't understand if Claude has gone quiet.

## What you do

1. Call `mcp__android-debugger__detach`. The tool is idempotent — calling when unattached returns `{ ok: true, was_attached: false }` cleanly.

2. Render the result as a one-liner:
   - If `was_attached: true`: `detached from <package>; released tcp:<port>` (port comes from the response's `released_port`).
   - If `was_attached: false`: `nothing was attached; clean state.`

3. If the user wants to re-attach to the same app, point them at `/android-debugger:attach`.

## What the server does under the hood (informational)

- Calls `vm.dispose()` on the JDI `VirtualMachine` (NOT `vm.exit()` — `exit` would call `System.exit` in the target app and kill it; `dispose` cleanly disconnects).
- Removes the `adb forward tcp:N jdwp:PID` so the local TCP port is released.
- Resets the in-memory session state: clears breakpoints, watches, the snapshot cache.

The target app **continues running** — JDWP doesn't kill the VM on transport loss, threads simply un-suspend.

## What you do NOT do

- Do not leave breakpoints "for next time" — `detach` clears them by design. If the user wants to persist them across sessions, that's a future feature; today, gone.
- Do not call `adb kill-server` or any global adb operation. Other tools may be using adb.
- Do not error if the user calls detach twice — it's idempotent. Just report `was_attached: false`.
