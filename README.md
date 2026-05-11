# android-debugger

**A Claude Code plugin for AI-agent-driven Android debugging.** Exposes the Android Java/Kotlin debugger surface (JDI/JDWP over `adb forward`) as MCP tools an AI coding agent calls. It is **designed to be driven by Claude Code or compatible agents**, not by humans typing in a REPL — every tool surface, error code, and syntax choice optimizes for agent legibility over human ergonomics.

If you want a human IDE-style debugger, use Android Studio. If you want Claude Code to drive a debug session end-to-end on your behalf, install this.

Backed by a Kotlin MCP server using JDI (Java Debug Interface) over JDWP. Works on **macOS, Linux, and Windows**.

## v1.4 highlights

- **JVMTI agent foundation.** A small C++ agent (~250 KB per ABI, fully stripped) loads into the target app via `cmd activity attach-agent` and exposes ART's JVMTI surface — `can_redefine_classes`, `can_retransform_classes`, `can_tag_objects`, etc. Auto-loaded on every `attach` by default; opt out with `attach({ load_agent: false })`. Live capability map returned in the attach response and via the new `agent_info` MCP tool.
- **No user-visible v1.4 feature beyond `agent_info`.** This release is plumbing. v1.5 layers HotSwap on top (`hot_swap_class` via JVMTI `RedefineClasses` — Apply-Changes-style edit-and-continue). v1.6 routes heap walks (`find_referrers`, `count_instances`) through the agent for 10–100× speedups on big heaps.
- **ABIs**: `arm64-v8a`, `x86_64`, `armeabi-v7a`. Pre-built `.so` files checked into `dist/agents/<abi>/libamdb_agent.so` — most users never need NDK.
- **Crash diagnostics.** Signal handlers write `/data/data/<package>/cache/amdb_agent_crash.txt` on agent crash (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE) with the faulting PC + last RPC method. The Kotlin server picks it up on next attach and surfaces `crashed_last_session` in the warnings.
- **Studio coexistence guard.** Probes `/proc/<pid>/maps` for known Android Studio Apply-Changes agent artifacts (`libperfa`, `studio_profiler`, etc.); refuses with `agent_conflict` rather than fighting Studio for control.
- **Strict protocol versioning.** First message on the agent socket MUST be `{method: hello, params: {protocol_version: 1}}`. Mismatch returns `agent_version_mismatch`. Forward-compatible — v1.5+ agents will negotiate higher protocol versions.

## v1.3 highlights

- **kfeel-backed `evaluate` tool.** Pure-FEEL expression engine ([ca.acendas:kfeel](https://github.com/Acendas/kfeel)) — binary ops, ternary `if/then/else`, `instance of`, list comprehensions (`every x in items satisfies x > 0`), ranges (`score in [1..100]`), three-valued null logic. Property access on pre-resolved object trees works without a JDI round-trip per `.` — `FeelContextBuilder` walks instance fields to depth 3 at context-build time. String literals use **single quotes** (`'hello'`) per the FEEL spec.
- **Method calls live on `eval_method`.** FEEL has no `obj.method()` syntax, and kfeel's published surface doesn't let us register custom functions. v1.3 commits to a two-tool split: `evaluate` for pure-expression FEEL; the existing `eval_method` MCP tool for method invocations (unchanged mutation-refusal, single-flight, 10s timeout). The skill docs make the split explicit so the agent picks the right tool first try.
- **Persistent state across detach.** Breakpoints and watches save to `$CLAUDE_PLUGIN_DATA/android-debugger/sessions/<serial>_<package>.json` on `detach` and rehydrate on the next `attach` to the same `(serial, package)`. Original ids preserved so the agent's prior `remove_breakpoint(id)` calls keep working across the cycle. Opt out with `detach({ persist: false })`.
- **Pause-on-class-load.** New `add_class_load_breakpoint({ class_pattern })` MCP tool. Pattern uses JDWP glob syntax (`com.example.*`, `*.MyService`). Fires `Stopped(reason='class_prepare', breakpoint_id=...)` — useful for catching first-load of a class to debug static initializers, gating on dependency injection, etc.

## v1.3 migration

**Breaking syntax change** for `evaluate`:

| v1.2                                | v1.3                                                         |
|-------------------------------------|--------------------------------------------------------------|
| `evaluate("user.getName()")`        | `eval_method({ frame, target: 'this', method: 'getName' })` |
| `evaluate("list.size() > 10")`      | `eval_method` for size, then decide directly                 |
| `evaluate("\"hello\".length() > 3")`| `eval_method` for length                                     |
| `evaluate("a + b")`                 | `evaluate("a + b")` — **same, kfeel just made it actually work** |
| `evaluate("user.age >= 18 ? 'a' : 'm'")` | `evaluate("if user.age >= 18 then 'a' else 'm'")` (FEEL ternary syntax) |

Watches and conditional breakpoints likewise use FEEL syntax. The conversion table is the same.

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
- **Android 8 (API 26) or newer** for the target app. Anything that runs on ART works; pre-ART devices aren't supported.
- **Claude Code with `CLAUDE_PLUGIN_DATA` propagation** for v1.3 persistence. Older Claude Code versions still work — persistence is silently disabled and the agent reads breakpoints fresh each session.

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

### Autonomous mode — the orchestrator agent

For "I'm stuck, please debug this for me" workflows, hand the whole loop to the registered `android-debug-orchestrator` agent:

```
Agent({
  subagent_type: "android-debug-orchestrator",
  prompt: "Why does login crash on slow networks? App is com.example.app."
})
```

The agent attaches, triages the goal (crash / behavior / flaky / onboarding), runs the matching iterative loop end-to-end, and returns a structured findings report (hypothesis / evidence / proposed fix shape / repro recipe). Your main session sees one prompt + one report instead of dozens of tool-call rounds. `/android-debugger:investigate` will offer to dispatch to the agent on goals that look mechanical enough.

## How it works

1. `adb jdwp` lists debuggable PIDs on the connected device.
2. The plugin asks adb to forward a free local TCP port to the JDWP port of your chosen PID.
3. The MCP server attaches via JDI's `SocketAttachingConnector` and holds the live `VirtualMachine` for the rest of the Claude Code session.
4. Claude calls MCP tools (`add_line_breakpoint`, `resume`, `step_over`, `evaluate`, `frame_snapshot`, ...). On every pause, the server bundles a snapshot of thread + frames + locals + watch values; granular drill-downs (`inspect_object`, `get_array_slice`, `evaluate`) operate on the same paused state.
5. `wait_for_event(timeout, types?)` lets the agent poll for the next stopped/exception/exit event without blocking the Claude UI.

The server only listens on `localhost` — adb's port forward binds there, and JDI never opens a public socket. Logging goes to stderr; stdout is reserved for the MCP transport.

## JVMTI agent (v1.4+)

v1.4 adds a small C++ agent that loads INTO the target app process via `cmd activity attach-agent`. The agent exposes a Unix abstract-namespace socket (`@android-debugger-<package>`) that the Kotlin MCP server connects to over an `adb forward localfilesystem:<host> localabstract:<abstract>` channel. Wire protocol: line-delimited JSON-RPC 2.0 with strict-v1 protocol-version handshake.

**Why the agent matters**: JDI/JDWP gives us a great read surface but a tiny write surface (`setLocal`, `setField`, `invokeMethod` only). The JVMTI agent unlocks more: `RedefineClasses` for HotSwap (v1.5), faster heap walks via `IterateThroughHeap` (v1.6), native-speed method-entry/exit events without JDWP's `addClassFilter` overhead.

**Lifetime**: the agent loads once per app process and stays loaded until the process dies. JVMTI doesn't support agent unload, so `:detach` only closes the IPC socket. Re-attaching to the same app process reuses the running agent via a fresh socket. If the app's process gets killed and respawns (common on memory-constrained devices like automotive/embedded SOMs), the next attach pushes the `.so` and loads the agent fresh.

**Requirements**: Android 8 (API 26) or newer — JVMTI on ART was introduced in Oreo. Below that, agent load fails cleanly and JDI continues to work standalone.

**Security note**: the agent's abstract-namespace socket is reachable by any process on the device that knows the name. Android's app sandbox restricts cross-UID access, but `userdebug`/`eng` builds (typical for embedded dev boards) are more permissive. **Do not run android-debugger on a device you don't control.** v1.5 may add token authentication once HotSwap raises the stakes.

To verify the agent loaded: `/android-debugger:agent_info`. To opt out: `attach({ load_agent: false })`.

## Troubleshooting

**`adb_status: not_found`** — set `ADB_PATH`, `ANDROID_HOME`, or add adb to PATH. macOS: `brew install --cask android-platform-tools`. Linux: `apt install android-tools-adb`. Windows: install Android Studio.

**`code: attach_failed`** — most often means the app isn't built debuggable, or another debugger (Android Studio's) is already attached. Detach the IDE debugger and retry.

**`code: already_attached`** — only one VM per session. Run `/android-debugger:detach` first.

**Multiple devices connected** — attach errors with `code: invalid_target`. Pass `serial:` from `list_devices` to disambiguate.

**App ANR-killed mid-debug** — the system kills processes that block the main thread for >5s. Set breakpoints off the UI thread, or use logpoints (non-suspending) for main-thread suspects.

**Transient disconnect** (USB unplug, emulator restart) — surfaces as `code: vm_disconnected`. Re-run `/android-debugger:attach`. v1.3 persistence will rehydrate prior breakpoints + watches automatically on re-attach to the same `(serial, package)`.

**`dump_heap` fails with permission denied** — `am dumpheap` needs `run-as` access on Android 11+, which only debuggable APKs have on user/userdebug builds. Confirm the app is built debuggable (the `attach` response carries a `release_build_likely` warning if it isn't), or run on a rooted/eng emulator if you're profiling a release build.

**`evaluate` says "Parse error in FEEL expression"** — FEEL uses single quotes for string literals (`'hello'`), single `=` for equality, `and`/`or`/`not` for boolean logic, `instance of` for type checks. For method calls on JDI references, use the separate `eval_method` MCP tool — FEEL has no `obj.method()` syntax. See the `evaluate` tool description for the full grammar quick reference.

**Persistence file not appearing in `$CLAUDE_PLUGIN_DATA/android-debugger/sessions/`** — your Claude Code version may not propagate `CLAUDE_PLUGIN_DATA` to MCP subprocesses. v1.3 logs a single line ("CLAUDE_PLUGIN_DATA is unset; persistence disabled") at first save attempt — check the server log via `connection_status`. Functionality is unaffected; the agent just re-adds breakpoints each session.

**`code: agent_push_failed`** (v1.4) — both `/data/local/tmp/` and `/data/data/<package>/code_cache/` failed to receive the agent `.so`. Confirm the app is built debuggable and that `adb shell run-as <package>` works for your user. On strict-SELinux devices, run-as may require the app's build.gradle to explicitly enable JDWP.

**`code: agent_attach_failed`** (v1.4) — `cmd activity attach-agent` rejected the load. If the stderr mentions "not debuggable", rebuild the app as a debug variant. If it mentions "could not load", the device's SELinux blocked the load — try the fallback path (the launcher does this automatically but some custom builds need extra policy).

**`code: agent_conflict`** (v1.4) — Android Studio's Apply Changes agent is already in the target process. Detach Studio's debugger first.

**`code: agent_in_use`** (v1.4) — another Claude Code session is already attached to the same app. Detach it first, or force-stop the app to clear the abstract socket.

**`code: agent_version_mismatch`** (v1.4) — the loaded agent uses an older protocol than the Kotlin server expects. This shouldn't happen for fresh installs but can occur if the app process was kept alive across a plugin upgrade. Force-stop the app and re-attach.

**`code: agent_handshake_timeout`** (v1.4) — the agent loaded but its socket didn't come up within 5 s. Most often a SELinux issue (the `.so` loaded but the abstract-namespace socket couldn't bind). Check `adb logcat -s amdb_agent:V` for the listener's bind output. If the agent crashed during a prior session, `agent_info` will surface `crashed_last_session` with the faulting PC.

## Building from source

```
cd server
./gradlew shadowJar
./gradlew assembleAgent     # v1.4 — rebuild dist/agents/<abi>/libamdb_agent.so
```

The agent has its own build path; see `agent/README.md` for NDK / CMake details.

The fat jar lands at `dist/android-debugger-server.jar` and is committed to the repo so users don't need a build step. See `CONTRIBUTING.md` for the end-to-end test loop against a public sample Android app.

## Dependencies

- **[ca.acendas:kfeel](https://github.com/Acendas/kfeel)** — production-ready Kotlin implementation of the DMN 1.3 FEEL (Friendly Enough Expression Language) specification. Powers the v1.3 `evaluate` MCP tool's expression grammar. Zero transitive dependencies; bundled in the fat jar.

## License

MIT — see `LICENSE`.
