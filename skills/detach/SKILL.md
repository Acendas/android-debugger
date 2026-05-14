---
name: detach
description: Detach the Android debugger and release the app.
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

4. If the user asks why detach is safe ("won't this kill my app?", "what does detach actually do?"), read `skills/detach/references/lifecycle-internals.md` and answer from there — don't paraphrase, the file has the precise `vm.dispose` vs. `vm.exit` distinction.

## What you do NOT do

- Do not leave breakpoints "for next time" — `detach` clears them by design. If the user wants to persist them across sessions, that's a future feature; today, gone.
- Do not call `adb kill-server` or any global adb operation. Other tools may be using adb.
- Do not error if the user calls detach twice — it's idempotent. Just report `was_attached: false`.
