---
name: ad-drive
description: Drive the app's UI and watch screen changes.
argument-hint: "[what to do, e.g. 'add one ticket and go to payment']"
allowed-tools: AskUserQuestion, Read, Monitor, mcp__plugin_android-debugger_android-debugger__ui_start, mcp__plugin_android-debugger_android-debugger__ui_stop, mcp__plugin_android-debugger_android-debugger__ui_status, mcp__plugin_android-debugger_android-debugger__ui_layout, mcp__plugin_android-debugger_android-debugger__ui_tap, mcp__plugin_android-debugger_android-debugger__ui_long_press, mcp__plugin_android-debugger_android-debugger__ui_swipe, mcp__plugin_android-debugger_android-debugger__ui_key, mcp__plugin_android-debugger_android-debugger__ui_type, mcp__plugin_android-debugger_android-debugger__ui_wait, mcp__plugin_android-debugger_android-debugger__ui_screenshot, mcp__plugin_android-debugger_android-debugger__list_devices, mcp__plugin_android-debugger_android-debugger__connection_status
---

# Drive — operate the app's UI, event-driven

Tap, type and navigate the app on a device, and see every screen change as it happens — including ones nobody caused (idle timeouts, popups, countdowns). Works with or without the debugger attached; combine with breakpoints to catch what a UI action triggers.

## What you do

1. **Start.** `ui_start` (pass `serial` if several devices). Keep the returned `monitor_command` and `event_log`.
   - `warnings: ["compose_events_suppressed"]` → the app is Jetpack Compose and sends no UI events yet. Reads and taps still work, but screen changes won't be pushed. Ask with `AskUserQuestion` before enabling presence: it installs a no-op accessibility service and changes a secure setting; `ui_stop` restores the exact prior values. If yes: `ui_stop`, then `ui_start(presence: true)`.
   - `ui_daemon_error` "did not start" → another UI automation client (a running UI test, Appium, Maestro, `uiautomator`, `android layout`) holds the device's single slot. Tell the user; don't retry in a loop.

2. **Watch.** Arm `Monitor` with the `monitor_command` exactly as returned (`timeout_ms: 1800000`, description like "app screen changes on <serial>"). Each line is one screen change, window, or action. Re-arm on expiry while you still need it. Monitor is visibility only — base decisions on `ui_wait` / `ui_layout` results, never on a Monitor line alone.

3. **Loop: read → act → wait.**
   - `ui_layout` to see the screen (text, content-desc, resource-id, center, interactions).
   - Act with `ui_tap` / `ui_type` / `ui_swipe` / `ui_key`. Prefer selectors (`text`, `content_desc`, `resource_id`) over coordinates; use `contains: true` for partial matches.
   - `ui_wait` right after, never a sleep. It defaults to "after my last action", so a fast change isn't missed. Use `until_text` when you know what should appear.
   - Repeat until the goal is reached. Read `references/driving-playbook.md` for interaction rules and error handling.

4. **Stop.** `ui_stop` when done, and always before the user runs instrumented UI tests, Appium/Maestro, or `android layout` — the daemon holds the device's only UI automation slot.

5. **Report** the path taken as a short timeline (action → what changed), and anything odd the app did on its own (error screens, popups, timeouts).

## With the debugger

- Drive to the screen first, then attach — the UI session is independent of `attach`.
- While the attached app is paused, UI tools refuse with `vm_paused` (its UI thread is frozen; input would queue and risk an ANR). Resume first. `allow_while_paused: true` only when targeting a different app.
- To correlate UI and breakpoints in one stream (e.g. from a Debug Plan), start with `forward_to_debug_events: true`; UI events then also arrive in `wait_for_event` as type `ui`.

## What you do NOT do

- Do not enable presence without asking. Do not leave a UI session running when you finish.
- Do not shell `adb shell input` or `uiautomator` via Bash — they bypass the daemon (and `uiautomator dump` fails while it runs).
- Do not tap through payment, purchase, delete, or send steps unless the user asked for exactly that.
