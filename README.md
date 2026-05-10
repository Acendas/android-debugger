# android-debugger

Drive an Android Java/Kotlin debugging session from Claude Code. Set breakpoints (line, conditional, hit-count, logpoint, exception, method, field watchpoint), step over/into/out, evaluate expressions in scope, inspect frames + locals + objects + arrays, tail logcat, dump heap — the full Android debugger surface, exposed as MCP tools an LLM agent calls.

Backed by a Kotlin MCP server using JDI (Java Debug Interface) over JDWP. Works on **macOS, Linux, and Windows**.

## What's distinctive

- **Read-heavy by design.** The agent's default workflow is `:explain` — call `frame_snapshot`, summarize the paused state, hypothesize. Mutation is a small surface (set local, set field, `invokeMethod`); inspection is rich.
- **Snapshot-on-pause + caching.** The biggest token-burning anti-pattern in AI debuggers is re-reading the same frame after every step. `frame_snapshot` returns a bundled, cached payload keyed by `(thread, vm_state_version)` and invalidates only on resume/step.
- **Structured errors over throws.** Every tool returns `{ ok, ... }` or `{ ok: false, code, message, hint? }`. Codes like `vm_running`, `absent_local_variables`, `capability_unavailable` let the agent react cleanly without parsing prose.
- **Capability-probe at attach.** Different ART versions expose different things — `redefine_classes`, `pop_frames`, `force_early_return`, `field_modification_watchpoints`. The `attach` response includes the full probe map so the agent avoids requesting features the device can't support.
- **Cursor-style logpoint sweeps.** `add_line_breakpoint` carries an optional `log_message` template that turns it into a non-suspending tracepoint — drop 8 logpoints across a suspect call graph, run the failing path, harvest the timeline. Replaces `Log.d` archaeology.

## Requirements

- **JDK 17 or newer.** Android Studio bundles one — point `JAVA_HOME` at `<AndroidStudio>/jbr` if you don't have a system JDK.
- **`adb` on PATH** (or `ANDROID_HOME` / `ANDROID_SDK_ROOT` set). Comes with the Android SDK platform-tools.
- **A debuggable APK on a connected device or emulator.** Apps built with `debuggable=true` (the default for debug variants) expose a JDWP port discoverable via `adb jdwp`. Release/R8-stripped builds will attach but report `release_build_likely` warnings; locals will be mostly empty until you rebuild as a debug variant.

## Install

From the Acendas marketplace:

```
/plugin install android-debugger@acendas
```

Or directly during development:

```
/plugin install Acendas/android-debugger
```

## Quick start

```
/android-debugger:setup     # one-time environment check (JDK + adb + capabilities)
/android-debugger:attach    # picks a debuggable process and attaches
/android-debugger:explain   # snapshot + 1-paragraph hypothesis after a pause
/android-debugger:catch <X> # break on uncaught exception X, root-cause on hit
/android-debugger:trace <Y> # logpoint sweep across a suspect call graph
```

When you're done:

```
/android-debugger:detach
```

The full skill set: `:setup`, `:attach`, `:detach`, `:status`, `:explain`, `:catch`, `:trace`, `:walk`, `:bisect-flaky`, `:investigate`. Run `/android-debugger:investigate <goal>` to let Claude triage your goal (crash / unexpected behavior / flaky test / onboarding) and dispatch the right workflow.

## How it works

1. `adb jdwp` lists debuggable PIDs on the connected device.
2. The plugin asks adb to forward a free local TCP port to the JDWP port of your chosen PID.
3. The MCP server attaches via JDI's `SocketAttachingConnector` and holds the live `VirtualMachine` for the rest of the Claude Code session.
4. Claude calls MCP tools (`add_line_breakpoint`, `resume`, `step_over`, `evaluate`, `frame_snapshot`, ...). On every pause, the server bundles a snapshot of thread + frames + locals + watch values; granular drill-downs (`inspect_object`, `get_array_slice`, `evaluate`) operate on the same paused state.
5. `wait_for_event(timeout, types?)` lets the agent poll for the next stopped/exception/exit event without blocking the Claude UI.

The server only listens on `localhost` — adb's port forward binds there, and JDI never opens a public socket. Logging goes to stderr; stdout is reserved for the MCP transport.

## Troubleshooting

**`adb_status: not_found`** — set `ADB_PATH`, `ANDROID_HOME`, or add adb to PATH. macOS: `brew install --cask android-platform-tools`. Linux: `apt install android-tools-adb`. Windows: install Android Studio.

**`code: attach_failed`** — most often means the app isn't built debuggable, or another debugger (Android Studio's) is already attached. Detach the IDE debugger and retry.

**`code: already_attached`** — only one VM per session. Run `/android-debugger:detach` first.

**Multiple devices connected** — attach errors with `code: invalid_target`. Pass `serial:` from `list_devices` to disambiguate.

**App ANR-killed mid-debug** — the system kills processes that block the main thread for >5s. Set breakpoints off the UI thread, or use logpoints (non-suspending) for main-thread suspects.

**Transient disconnect** (USB unplug, emulator restart) — surfaces as `code: vm_disconnected`. Re-run `/android-debugger:attach`. Breakpoints don't persist across sessions in v1.0.

## Building from source

```
cd server
./gradlew shadowJar
```

The fat jar lands at `dist/android-debugger-server.jar` and is committed to the repo so users don't need a build step. See `CONTRIBUTING.md` for the end-to-end test loop against a public sample Android app.

## License

MIT — see `LICENSE`.
