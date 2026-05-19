---
name: ad-attach
description: Attach the Android debugger to a running process.
argument-hint: "[package | pid | partial-package]"
allowed-tools: AskUserQuestion, mcp__android-debugger__list_devices, mcp__android-debugger__list_debuggable_processes, mcp__android-debugger__attach, mcp__android-debugger__connection_status, mcp__android-debugger__wait_for_event, mcp__android-debugger__render_capabilities
---

# Attach — connect the debugger to a running Android process

The user has a debuggable APK on a device/emulator and wants Claude driving the debug session.

## What you do

1. Call `mcp__android-debugger__connection_status`. If `attached: true`, ask the user whether to detach first (don't auto-detach — they may have active breakpoints). If they confirm, call `/android-debugger:ad-detach`, then continue.

2. Call `mcp__android-debugger__list_devices`. If zero devices, tell the user to plug one in or start an emulator and stop. If many, you'll resolve the right one in step 4.

3. Call `mcp__android-debugger__list_debuggable_processes`. If `serial` is omitted and there's only one device, it's auto-selected. Multiple devices → the tool returns `code: invalid_target` with a candidate list — ask the user via `AskUserQuestion` and retry with the chosen serial.

4. Resolve the target. Parsing precedence (apply in this order — first match wins):
   - **Numeric** (`/^\d+$/`) → match by `pid` against the process list and call `attach({ serial, pid })`.
   - **`package/component` shape** (e.g. `com.example.app/com.example.app.MainActivity`) → split on `/`, take the part before the slash as the package, ignore the component (the user pasted from `am start` output).
   - **Exact package match** → if the string matches a process `package` exactly, call `attach({ serial, package })`.
   - **Partial / prefix match** → if the string matches one process's `package` as a prefix or substring (e.g. `com.example` matches `com.example.app`), and exactly one process matches, attach to that process. If multiple processes match, ask via `AskUserQuestion` showing each `{ pid, package }`.
   - **No argument** → present the (max 4) most-likely targets via `AskUserQuestion`. "Most likely" = packages that don't start with `com.android.` or `system`.

5. After `attach` returns:
   - Confirm with a one-liner: `attached to <package> (pid <pid>) via tcp:<jdwp_port>`.
   - Surface the `vm_version` and `vm_name` (e.g. `Dalvik / 8` for Android 14).
   - Render the `capabilities` map as a small bullet list. Highlight any `false` flags that affect what the user can do next:
     - `redefine_classes: false` → "no HotSwap available; rebuild + reattach to apply code changes"
     - `pop_frames: false` → "no drop-frame; can't rewind in this VM"
     - `field_modification_watchpoints: false` → "field watchpoints unavailable"
   - If the response includes `warnings` and `release_build_likely` is present, surface that prominently — local-variable inspection will be mostly empty until they rebuild as a debug variant.

6. **Smoke-probe the live VM.** Call `mcp__android-debugger__wait_for_event({ timeout_ms: 2000 })` to confirm the VM is actually live and producing events. JDWP forward can succeed while the VM is still in early boot (process freshly spawned, ART warming up, Wear OS quirk) and no events ever fire — the user perceives this as "the debugger is attached but Claude can't see anything." On timeout (`timed_out: true`), tell the user the VM hasn't produced any events yet and suggest either: (a) launch the app fresh from the home screen so the VM finishes initialization, (b) confirm the build is `debuggable=true` (release builds with manifest debuggable strip JDWP), (c) on Wear OS, double-check the AVD is API 30+. Don't mark this as a hard failure — many apps idle without events; surface it as "no events in 2s" and let the user decide.

7. Suggest the next step: "Describe the bug to investigate with `/android-debugger:ad-investigate`" (catch-all router) "or jump straight to `/android-debugger:ad-catch <exception>` if you already know what's crashing."

## Cross-platform notes

- `adb` resolution lives in the server (`AdbLocator`). Don't shell out to adb from this skill.
- Bash blocks (if any) must work on macOS / Linux / Windows: no `mktemp`, `readlink -f`, GNU `realpath`, `sed -i ''`, `stat -c`, `/dev/stdin`.

## What you do NOT do

- Do not prompt the user to paste a JDWP port or a long PID list into chat — `list_debuggable_processes` returns a structured list; render it for them.
- Do not keep retrying `attach` if it returns `code: attach_failed` — surface the error and stop. Common causes the tool's `hint` will name: app not built debuggable, IDE debugger already attached.
- Do not attach to system processes (PID < 1000, no package) unless the user explicitly asks — they're rarely what users mean.
