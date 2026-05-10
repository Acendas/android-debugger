# Contributing to android-debugger

Thanks for considering a contribution. This guide walks through the local development loop — building the server, smoke-testing it against a real Android app, and the conventions the codebase follows.

## Prerequisites

- **JDK 17 or newer.** The Gradle toolchain is pinned to 17, so any recent JDK works. Android Studio bundles `jbr/` which is fine.
- **Android SDK platform-tools on PATH** (or `ANDROID_HOME`/`ANDROID_SDK_ROOT` set). `adb` is the only tool from the SDK we shell out to.
- **A connected Android device or emulator** for end-to-end smoke. A Pixel emulator running Android 14 (API 34) is what the maintainers use day-to-day.

## Recommended sample app

We deliberately don't ship a guinea-pig Android app inside this repo (keeps the plugin lean). Use a public sample app for the test loop:

- **`https://github.com/android/compose-samples`** — pick `JetNews` or `Jetcaster`. Both are debug-buildable out of the box and cover Compose, ViewModel, Coroutines, networking — a representative debugging surface.
- Clone the samples repo, open in Android Studio, build the debug variant, install + launch on your emulator. The launched process will appear in `adb jdwp` because debug builds are debuggable by default.

Quick walkthrough on macOS/Linux (Windows users: same commands, equivalent shells):

```
git clone https://github.com/android/compose-samples.git
cd compose-samples/JetNews
./gradlew :app:installDebug
adb shell am start -n com.example.jetnews/com.example.jetnews.ui.MainActivity
```

Now `adb jdwp` should list the `com.example.jetnews` PID, which the debugger can attach to.

## Build the server

```
cd android-debugger/server
./gradlew shadowJar
```

The fat jar lands at `android-debugger/dist/android-debugger-server.jar` — that's the artifact `.mcp.json` launches. **Commit the rebuilt jar** when you change server code; users install the plugin and shouldn't need a build step.

## Run the unit tests

```
cd android-debugger/server
./gradlew test
```

Tests cover the parsing helpers (`adb devices -l`, `adb shell ps -A`, etc.) and the `Adb` façade with a mock `CommandRunner`. JDI integration code is exercised by smoke tests against a real device, not unit tests — JDI types are too large to mock cleanly.

## Smoke the MCP server end-to-end

Once the jar builds, the MCP server speaks JSON-RPC over stdio. Quick handshake:

```
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}' | java -jar android-debugger/dist/android-debugger-server.jar
```

You should see a JSON-RPC response advertising the tools. To smoke a real attach (with a sample app running):

```
(echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
 sleep 0.3
 echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"attach","arguments":{"package":"com.example.jetnews"}}}'
 sleep 4
 echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"detach","arguments":{}}}'
 sleep 1
) | java -jar android-debugger/dist/android-debugger-server.jar
```

Replace `com.example.jetnews` with whichever sample you installed.

## Code conventions

- **Kotlin everywhere.** Toolchain JDK 17; Kotlin 2.3.21.
- **Tools never throw to MCP.** Wrap every body with `runTool { ... }` and return structured `{ ok, ... }` / `{ ok: false, code, message, hint? }` payloads via the `toolOk` / `toolErr` helpers.
- **Cross-platform discipline.** The plugin runs on macOS, Linux, and Windows. In skill bash blocks (`skills/**/SKILL.md`), avoid POSIX-only builtins: no `mktemp`, `readlink -f`, GNU `realpath`, `sed -i ''`, `stat -c`, `/dev/stdin`. If a skill needs anything beyond `java -version` / trivial commands, route it through an MCP tool.
- **No `.claude/` or `CLAUDE.md` inside `android-debugger/`.** Plugin repos ship to end users; Claude-authoring artifacts live at the workspace level.
- **Single source of truth for design:** the dev notes at `docs/android-debugger-dev.md` (workspace level) and the in-flight execution plan at `.claude/plans/android-debugger-v1.0.md`.
- **Cite the *why*** in commits — mention the constraint, prior bug, or research source that motivated the change. Don't just describe the *what*.

## Where to add new MCP tools

| Concern | File |
|---|---|
| New lifecycle/connection tool | `tools/LifecycleTools.kt` |
| New inspection/read tool | `tools/InspectionTools.kt` |
| New execution control tool | `tools/ExecutionTools.kt` |
| New breakpoint tool | `tools/BreakpointTools.kt` (Phase 3) |
| New Android-specific tool | `tools/AndroidTools.kt` (Phase 6) |
| Cross-cutting helper | `inspection/`, `execution/`, `events/` subpackages |

Whichever you touch, register the tool group in `Tools.register(server)` in `Tools.kt`.

## Submitting changes

The plugin lives in the Acendas marketplace. PRs should target `main`. After a PR lands and the marketplace ref is bumped, users install via:

```
/plugin install android-debugger@acendas
```

Or directly during development:

```
/plugin install Acendas/android-debugger
```
