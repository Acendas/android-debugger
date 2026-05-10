---
name: Setup android-debugger
description: This skill should be used when the user asks to "setup android debugger", "init android debugger", "configure android debugger", "check android debugger environment", "verify java and adb", "android debugger isn't working", or runs `/android-debugger:setup`. Probes the user's environment for JDK + adb, verifies the MCP server is reachable, and reports what's missing with platform-specific (macOS/Linux/Windows) install hints. Run once after install, and any time the debugger reports "adb not found" or fails to start.
allowed-tools: Bash, mcp__android-debugger__server_info, mcp__android-debugger__list_devices
---

# Setup — environment probe + MCP smoke test

The first thing to run after install. Also the diagnostic to fall back to whenever the debugger seems broken. Be diagnostic, not prescriptive — surface what's missing; the user knows their machine.

## What you do

1. Call `mcp__android-debugger__server_info`. The response carries `jdk_version`, `os_name`, `adb_status`, and (on success) `adb_path`. Lead with this; it's the cheapest signal that the server jar runs at all.

2. If the MCP tool errors (server unreachable / jar missing), surface the exact error and check the most likely causes — in this order:
   - **Server jar missing**: the plugin ships `dist/android-debugger-server.jar`. If install was incomplete, ask the user to reinstall the plugin.
   - **JDK missing or too old**: minimum JDK 17. Run `java -version` via Bash to confirm.
   - **`.mcp.json` not loaded**: ask the user to restart Claude Code.

3. If `server_info` returns `adb_status: "not_found"`, do not silently proceed. Print the exact resolution the server tried (ADB_PATH → ANDROID_HOME → PATH) and give one platform-specific hint:
   - **macOS**: `brew install --cask android-platform-tools` or set `ANDROID_HOME=$HOME/Library/Android/sdk`.
   - **Linux**: `apt install android-tools-adb` (Debian/Ubuntu) or set `ANDROID_HOME=$HOME/Android/Sdk`.
   - **Windows**: install Android Studio (bundles platform-tools) or download standalone `platform-tools` from `https://developer.android.com/tools/releases/platform-tools`, then add to PATH or set `ANDROID_HOME`.

4. If both server and adb are reachable, call `mcp__android-debugger__list_devices` to confirm at least one device or emulator is connected. Do **not** start an emulator on the user's behalf — they may have a specific AVD they want; just point them at how to start one if no devices are listed.

5. Report back as a compact table:

   ```
   server jar:   ok  (v1.0.0)
   jdk:          ok  (21.0.8)
   adb:          ok  (/Users/.../platform-tools/adb)
   devices:      1 connected (Pixel_Tablet)
   ```

6. If `server_info`'s response includes a `capabilities` map (it doesn't today, but `attach` does), and the user later attaches, recommend they run `/android-debugger:status` to see the per-device capability matrix that ART exposes.

## What you do NOT do

- **Do not** install anything. The user installs their own toolchain.
- **Do not** modify environment variables in shell rc files — show the user the export line and let them decide.
- **Do not** fall back to a guessed adb path. The Kotlin `AdbLocator` is the single source of truth.
- **Do not** invoke the JDI debugger tools (`attach`, `set_line_breakpoint`, ...) from this skill — setup is only about plumbing.

## Cross-platform discipline

Skill bash blocks must run on macOS / Linux / Windows. Avoid `mktemp`, `readlink -f`, GNU `realpath`, `sed -i ''`, `stat -c`, `/dev/stdin`. Stick to `java -version` and trivial commands here; everything else routes through the MCP server.
