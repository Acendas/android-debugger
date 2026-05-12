```
                       ▄▄▄▄▄▄▄▄▄▄▄▄▄
                    ▄█▀             ▀█▄
                  ▄█▀                 ▀█▄
                 █▀   ●           ●    ▀█
                ██                       ██
   █▀▀▀▀▀▀▀▀▀▀▀█████████████████████████████▀▀▀▀▀▀▀▀▀▀▀█
   █  █▀▀▀▀▀▀▀████  █▀▀▀▀▀▀█    █▀▀▀▀▀▀█  ████▀▀▀▀▀▀▀█  █
   █  █       ████  █      █    █      █  ████       █  █
   █  █  ◉◉◉  ████  █ JDI  █    █ JVMTI█  ████  ◉◉◉  █  █
   █  █       ████  █      █    █      █  ████       █  █
   █  █▄▄▄▄▄▄▄████  █▄▄▄▄▄▄█    █▄▄▄▄▄▄█  ████▄▄▄▄▄▄▄█  █
   █▄▄▄▄▄▄▄▄▄▄▄█████████████████████████████▄▄▄▄▄▄▄▄▄▄▄█
                ██                       ██
                ██  ANDROID-DEBUGGER  ██
                ██▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄██
                  ▀▀▀█▀▀▀     ▀▀▀█▀▀▀
                     █           █
                     █           █
                  ▄▄▄█▄▄▄     ▄▄▄█▄▄▄
```

<div align="center">

<h1>Android Debugger</h1>

<p><strong>Give Claude a real Android debugger so it can investigate bugs instead of guessing at them.</strong></p>

<p>
  <a href="https://github.com/Acendas/android-debugger/releases"><img src="https://img.shields.io/github/v/release/Acendas/android-debugger?style=flat-square&color=3DDC84" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Acendas/android-debugger?style=flat-square" alt="License"></a>
  <a href="https://github.com/Acendas/android-debugger/issues"><img src="https://img.shields.io/github/issues/Acendas/android-debugger?style=flat-square" alt="Issues"></a>
</p>
</div>

---

## Stop letting your AI flail at `Log.d`.

Your AI agent hits a bug. It reads the stack trace. It reads the source. It guesses. It adds `Log.d`. It rebuilds. It runs the app. It reads more logs. It guesses again. Half its "fixes" are made-up field names. The other half pass tests that don't actually exercise the failure path.

**That's not debugging. That's the agent doing improv from memory of your codebase.**

Android Debugger attaches Claude to your running Android app the same way Android Studio does — JDI over JDWP, plus a native JVMTI agent for the things JDWP can't do. Claude sets real breakpoints. It reads real variables off a real paused VM. It evaluates expressions inside a grammar that *literally cannot mutate state*. It patches method bodies live via HotSwap. It walks the heap in native code, not by re-reading the source.

**You ask. Claude attaches. The VM answers. The agent reports.**

```
┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
│  IDEA   │──►│ ATTACH  │──►│ OBSERVE │──►│  PATCH  │──►│ REPORT  │
│         │   │         │   │         │   │ (live)  │   │         │
│  you    │   │  auto   │   │  auto   │   │  auto   │   │  you    │
│  ask    │   │         │   │         │   │         │   │ approve │
└─────────┘   └─────────┘   └─────────┘   └─────────┘   └─────────┘
:investigate   :attach     :explain      :patch         (verdict)
```

Breakpoints, conditional breaks, logpoint sweeps, frame snapshots, FEEL expression eval, method invocation, HotSwap, heap walks, allocation tracing, session persistence — all driven by `/android-debugger:*` skills. No IDE. No browser tabs. No rebuilds for tracing. Just Claude and a live VM.

## Why Android Debugger

<table>
<tr>
<td width="50%" valign="top">

### Without Android Debugger

- Agent guesses field names from training data
- "Add a `Log.d` and rebuild" is the only instrumentation pattern
- Tests pass; the bug ships anyway
- Every fix requires a full Gradle build
- Stack traces are the entire signal — locals and watches don't exist
- Memory leaks: shrug, "looks fine in the source"
- Race conditions: "probably synchronized somewhere"
- Heap walks: humans only, via Android Studio's profiler
- Crash-and-fix loops with no understanding of *why*

</td>
<td width="50%" valign="top">

### With Android Debugger

- Agent reads real locals off the paused frame
- Logpoint sweeps trace 8 sites at once, no rebuild
- Acceptance verified by re-reading the patched VM, not by passing-tests-passing
- HotSwap method bodies in milliseconds — no Gradle round-trip
- Snapshots bundle frame + locals + watches + source into one cached payload
- Heap walks at native speed via JVMTI — count, find referrers, trace chains to GC roots
- Method + allocation traces with arg + return-value capture, line-rate
- Session persistence — breakpoints survive detach
- Explain-first, fix-second — hypothesis precedes mutation, always

</td>
</tr>
</table>

### The gap this closes

Every "AI debugger" today is a chat window staring at log output. Real debugging means **pausing a VM, reading its state, forming a hypothesis, and proving it**. That requires JDI, JVMTI, and a tool surface tuned for an agent's reading level — not a human's.

Android Debugger closes that gap with three structural commitments:

- **Anti-hallucination by grammar.** The `evaluate` tool runs DMN-FEEL expressions over paused-frame state. FEEL has no method-call syntax, so it *cannot* mutate. Mutation goes through a separate, flagged tool that refuses likely-mutators unless opted in. The agent cannot accidentally change the app while inspecting it.
- **Snapshot-first, drill-down second.** On every pause, the server eagerly bundles the top N frames + locals + watches + source ref into a `frame_snapshot`, cached by `(thread, vmStateVersion)`. The token-burning "re-read every frame after every step" anti-pattern is dead.
- **Explain before fix.** The flagship workflow is `:explain` — snapshot the pause, state a one-paragraph hypothesis, *then* decide whether to step, trace, or patch. Published research (AutoSD-style scientific debugging) puts this loop at ~71% Pass@1 on real failures.

The agent stops guessing. It starts proving.

---

## Install

Distributed through the [Acendas marketplace](https://github.com/Acendas/acendas-marketplace). Two steps: add the marketplace, then install the plugin.

**From inside a Claude Code session:**

```bash
/plugin marketplace add Acendas/acendas-marketplace
/plugin install android-debugger@acendas
```

**Or from the CLI:**

```bash
claude plugin marketplace add Acendas/acendas-marketplace
claude plugin install android-debugger@acendas
```

The marketplace pins to a released version tag — every install is deterministic. To pick up a new release, run `/plugin marketplace update` then `/plugin install android-debugger@acendas` again.

## Quick start

```
/android-debugger:setup        # one-time env probe — JDK + adb + ART capabilities
/android-debugger:attach       # pick a debuggable PID and connect
/android-debugger:explain      # snapshot + 1-paragraph hypothesis after a pause
/android-debugger:catch <Exc>  # break on uncaught exception, root-cause on hit
/android-debugger:trace <sym>  # logpoint sweep across suspect call graph
/android-debugger:patch <goal> # HotSwap loop with verify_via
/android-debugger:detach       # clean shutdown
```

For "I'm stuck, please debug this for me," dispatch the orchestrator:

```
Agent({
  subagent_type: "android-debug-orchestrator",
  prompt: "Why does login crash on slow networks? App is com.example.app."
})
```

## Top 10 Features

1. **Attach to any debuggable app** — no rebuild required. JDI over `adb forward jdwp:`.
2. **One-shot pause snapshot** — frame + locals + watches + source ref in a single cached payload.
3. **FEEL expression evaluator** — pure-expression language; can't mutate state by grammar.
4. **Every breakpoint type** — line, conditional, hit-count, logpoint, method, exception, field-watch, class-load.
5. **Native JVMTI agent** — C++ helper loaded via `cmd activity attach-agent`. Three ABIs ship pre-built.
6. **HotSwap method bodies live** — JVMTI `RedefineClasses` + d8 dexing. Pre-validated, revertible.
7. **Heap walks at native speed** — count instances, find referrers, walk chains to GC roots. 10–100× faster.
8. **Method + allocation tracing** — filter, throttle, capture args + return values. Flight-recorder mode.
9. **Session persistence** — breakpoints + watches saved on detach, rehydrated on reconnect.
10. **Workflow skills for an AI consumer** — `:explain`, `:catch`, `:trace`, `:walk`, `:patch`, `:investigate`. The agent picks the recipe.

## The Workflow

Skills are short, composable recipes. Most debug sessions chain a handful.

### 1. Attach

```
/android-debugger:attach
```

Lists debuggable PIDs, picks one (or accepts a `serial:` + `package:`), forwards a free local port to the app's JDWP port, attaches the JDI `VirtualMachine`, probes ART capabilities, optionally loads the JVMTI agent. Returns the capability map + any warnings (release-build detected, prior-session crash, etc.).

### 2. Explain why we stopped

```
/android-debugger:explain
```

The cheap, high-leverage default after every pause. Calls `frame_snapshot`, reads top N frames + locals + watches, writes a one-paragraph hypothesis. The agent decides what to do next from that hypothesis — not from guessing.

### 3. Catch the exception

```
/android-debugger:catch java.lang.NullPointerException
```

Sets an uncaught-exception breakpoint, resumes, reproduces, root-causes from the paused frame. Bread-and-butter workflow for "why does X crash."

### 4. Trace without rebuilding

```
/android-debugger:trace "login fails sometimes"
```

Cursor-style logpoint sweep — drops 6–8 non-suspending logpoints across a suspect call graph, runs the failing path, harvests the timeline. **Replaces `Log.d` archaeology entirely.** No rebuild, no Gradle, no APK push.

### 5. Walk through unfamiliar code

```
/android-debugger:walk MainActivity.onCreate
```

Sets an entry breakpoint, steps with a bounded step budget, narrates at frame boundaries. Onboarding workflow for "show me how login works."

### 6. Patch and verify

```
/android-debugger:patch "fix the off-by-one in the pagination"
```

Goal-driven HotSwap loop. Required `verify_via:` clause — the patch isn't done until the verify probe passes against the live VM. Pre-validates the shape diff (no field add/remove, no superclass change), dexes via embedded d8, sends to the JVMTI agent for `RedefineClasses`. Revertible.

### 7. Detach cleanly

```
/android-debugger:detach
```

Disposes the JDI VM, releases the adb forward, drains JVMTI ref tables, persists breakpoints + watches to `$CLAUDE_PLUGIN_DATA/android-debugger/sessions/<serial>_<package>.json` for next attach.

## All Skills

| Skill | What it does | When to use |
|---|---|---|
| `:setup` | Env probe — JDK, adb, ART capabilities | After install or "isn't working" |
| `:attach` | Pick + connect to a debuggable PID | Starting any session |
| `:detach` | Clean shutdown, persist state | Ending a session |
| `:status` | Current attached app, breakpoints, watches | "Where are we?" |
| `:explain` | Snapshot + hypothesis after pause | **Default after every pause** |
| `:catch` | Uncaught-exception break + root-cause | "Why does X crash?" |
| `:trace` | Logpoint sweep, no rebuild | "Find where X happens" |
| `:walk` | Entry break + guided step-through | "Show me how X works" |
| `:bisect-flaky` | Loop instrumented test, narrow with conditional break | "Fails 1 in 10 runs" |
| `:patch` | Goal-driven HotSwap loop with `verify_via:` | "Fix this and prove it" |
| `:patch-revert` | Roll back a HotSwap | Patch didn't work |
| `:patch-status` | Current HotSwap state | "What did we patch?" |
| `:investigate` | Top-level orchestrator — triage + dispatch | Catch-all "debug this for me" |

## Architecture

A Kotlin MCP server + a native JVMTI agent + a small set of workflow skills. No external runtime, no server process, no database.

### Two halves: JDI host-side + JVMTI in-process

```
┌────────────────────────┐              ┌────────────────────────┐
│  CLAUDE CODE SESSION   │              │  ANDROID DEVICE         │
│                        │              │                        │
│  /android-debugger:*   │              │  ┌──────────────────┐  │
│       │                │              │  │   TARGET APP     │  │
│       ▼                │              │  │                  │  │
│  ┌──────────────────┐  │              │  │  ┌────────────┐  │  │
│  │  KOTLIN MCP SRV  │◄─┼── adb fwd ──►│──┼──► JVMTI agent│  │  │
│  │                  │  │  jdwp + UDS  │  │  │   (C++)    │  │  │
│  │  ┌────────────┐  │  │              │  │  └────────────┘  │  │
│  │  │ JDI client │◄─┼──┼── adb fwd ──►│──┼─► JDWP server   │  │
│  │  └────────────┘  │  │              │  │                  │  │
│  └──────────────────┘  │              │  └──────────────────┘  │
└────────────────────────┘              └────────────────────────┘
```

- **JDI client** — Oracle's Java Debug Interface over JDWP. Read surface (frames, locals, fields), narrow write surface (`setLocal`, `setField`, `invokeMethod`), event stream.
- **JVMTI agent** — small C++ binary loaded INTO the app process via `cmd activity attach-agent`. Unlocks what JDWP can't do on ART: `RedefineClasses` for HotSwap, `IterateThroughHeap` for fast walks, line-rate method+allocation events. Three ABIs pre-built: arm64-v8a, x86_64, armeabi-v7a.
- **MCP tool surface** — ~50 tools, snake_case `<area>_<verb>`, all return `{ ok, ... }` or `{ ok: false, code, message, hint }`. The agent reasons over JSON, not stack traces.

### Skills (13)

Each `/android-debugger:*` command is a [skill](https://docs.anthropic.com/en/docs/claude-code/skills) with imperative-form body. Skill bodies are written for the *agent*, not for human readers — that's the consumer model.

### Agents (1)

| Agent | Role |
|---|---|
| **android-debug-orchestrator** | Autonomous "debug this for me" loop — triages goal shape (crash / behavior / flaky / onboarding), dispatches to the matching skill, drives the iterative loop end-to-end, returns a structured findings report |

### Session data

Persistent state (breakpoints + watches) lives at `${CLAUDE_PLUGIN_DATA}/android-debugger/sessions/<serial>_<package>.json`. Atomic-write + 0600 on POSIX. The data dir is per-Claude-plugin-install, never inside your repo.

```
plugin-data/android-debugger/sessions/
└── <serial>_<package>.json    Saved breakpoints + watches per (device, app)
```

## Safety Nets

The plugin assumes the AI will hallucinate and try to mutate things it shouldn't. Every safety net exists because we don't trust the model to police itself against a live VM.

- **Grammar-level mutation refusal** — `evaluate` runs DMN-FEEL. FEEL has no method-call syntax. Side effects are impossible at the parser level.
- **Mutation-refusal regex on `eval_method`** — likely-mutator names (`set*`, `add*`, `remove*`, `clear`, `put`, `delete`, etc.) refuse unless `allow_mutation: true` is set explicitly.
- **Capability-aware errors** — when the device's ART can't do something, the tool returns `code: capability_unavailable` with a hint, instead of pretending. The agent reacts on the code.
- **`evaluate` single-flight** — one invocation worker, 10s timeout, never invoked from the event-handler thread. ART's verifier is stricter than HotSpot; primitives are boxed via `vm.mirrorOf`. Deadlock on `INVOKE_SINGLE_THREADED` is structurally prevented.
- **VmCoordinator mutex** — `eval_method` and `hot_swap_*` can't run concurrently. Second caller gets `vm_busy` immediately. No mid-swap evaluation surprises.
- **HotSwap shape-diff pre-validation** — ASM-backed check refuses method-add/remove, field-add/remove, superclass / interface / access-flag changes. Structured `diff` array surfaces the specific violation.
- **HotSwap minify detection** — single-letter-class-name heuristic at attach. Refuses upfront on R8-minified builds with `minified_build_unsupported`.
- **Step budgets** — `:walk` and `step_until_method_change` bound their step count. Defends against step-into loops, the top failure mode in the AI debugger landscape.
- **Snapshot cache invalidation** — caches by `(thread, vmStateVersion)`. Any continue/step bumps the version. Stale snapshots can't be served.
- **Capability probe at attach** — the full ART JVMTI capability map ships back in the `attach` response so the agent knows up-front what's available on *this specific device*.
- **JVMTI agent crash diagnostics** — `sigaction` handlers write `/data/data/<pkg>/cache/amdb_agent_crash.txt` on SIGSEGV/SIGABRT/etc. Next attach picks it up and surfaces `crashed_last_session`.
- **Studio coexistence guard** — probes `/proc/<pid>/maps` for Android Studio's Apply-Changes agent (`libperfa`, `studio_profiler`). Refuses with `agent_conflict` rather than fighting Studio for control.
- **Strict protocol versioning** — first message on the agent socket is `{method: hello, params: {protocol_version: 3}}`. Mismatch returns `agent_version_mismatch` with both versions. No silent compat drift.
- **Clean detach** — `vm.dispose()` not `vm.exit()`. Shutdown hook on the JVM disposes + releases the adb forward on every exit path.

## Token Efficiency by Design

- **Snapshot caching** — `frame_snapshot` keyed by `(thread, vmStateVersion)`. The single biggest token-saver. Re-reads cost zero.
- **Structured errors** — `code` + `hint` instead of prose. The agent reacts on `code`, doesn't re-read explanations.
- **Bundled tools** — `frame_snapshot` returns frames + locals + watches + source in one call. The granular tools (`get_locals`, `get_frames`, `inspect_object`) are fallbacks for drill-down, not the default.
- **`exception_summary`, `framework_frame_filter`, `step_until_method_change`** — server-side bundles that collapse 5–10 round-trips into one. Less ceremony in the model's reasoning trace.
- **Polling, not streaming** — `wait_for_event(timeout, types?)` returns the next event or `{ timed_out: true }`. MCP doesn't stream; the polling shape is cheap and consistent.

## Cross-Platform

The plugin runs anywhere Claude Code runs. Server is JVM/Kotlin — runs on macOS, Linux, Windows. Agent `.so` files are pre-built for the device-side ABIs. Skill bash blocks avoid POSIX-only builtins (no `mktemp`, no GNU `realpath`, no `sed -i ''`) — anything beyond `java -version` and `adb devices` routes through MCP tool calls.

## Full feature list

### Lifecycle

- `list_devices` — enumerate connected devices/emulators
- `list_debuggable_processes` — discover debuggable PIDs with package labels
- `attach` — connect to a PID, probe ART capabilities, optionally load the JVMTI agent, return the capability map + warnings
- `detach` — clean shutdown, persist breakpoints + watches, release adb forwards
- `connection_status` — current attached app, session metadata
- `agent_info` — live JVMTI capability map + derived feature flags

### Breakpoints

- `add_line_breakpoint` — line, conditional (FEEL), hit-count, logpoint (non-suspending tracepoint) — one tool, four modes
- `add_method_breakpoint` — method entry / exit; auto-routes through JVMTI when used as a logpoint (line-rate)
- `add_exception_breakpoint` — caught / uncaught, per exception class
- `add_field_watchpoint` — access / modification (when device supports)
- `add_class_load_breakpoint` — pause when a class matching a JDWP-glob pattern loads
- `list_breakpoints`, `remove_breakpoint`, `enable_breakpoint`, `disable_breakpoint`
- Deferred-breakpoint queue — set bps on not-yet-loaded classes (Kotlin lambdas, inline functions, inner classes)

### Execution control

- `resume`, `pause`
- `step_over`, `step_into` (with framework-frame skip filters), `step_out`
- `run_to_line` — temp breakpoint + resume
- `step_until_method_change` — server-side bundle, bounded step budget, returns when the frame's method changes
- `wait_for_event` — polled bridge over the JDI event queue (`stopped`, `exception`, `class_prepare`, `exit`)

### Inspection

- `frame_snapshot` — bundled top-N frames + locals + watches + source ref, cached by `(thread, vmStateVersion)`
- `list_threads`, `get_frames`, `get_locals` — granular fallbacks for drill-down
- `inspect_object` — object fields, depth-limited
- `get_array_slice` — sliced array access
- `exception_summary` — bundled exception details (message, type, stack, framework-filtered)
- `render_capabilities` — human-readable capability map
- `framework_frame_filter` — drop framework frames (`android.*`, `kotlin.*`, `java.*`) from a frame list

### Expression evaluation

- `evaluate` — DMN-FEEL expressions over paused-frame state. Binary ops, `if/then/else`, `instance of`, list comprehensions, ranges, three-valued null logic. Property access on pre-resolved object trees (depth 3 by default). String literals: single quotes.
- `eval_method` — explicit JDI method invocation. Mutation-refusal on likely-mutator names; opt in with `allow_mutation: true`. Single-flight, 10s timeout. Primitives boxed via `vm.mirrorOf` for ART's stricter verifier.
- `add_watch`, `remove_watch`, `list_watches` — re-evaluated on every pause, bundled into `frame_snapshot`

### HotSwap (JVMTI `RedefineClasses`)

- `hot_swap_class` — single class. Server pre-validates shape diff (ASM), dexes JVM `.class` via embedded d8, sends to agent for atomic `RedefineClasses`.
- `hot_swap_classes` — batch swap with per-entry validation
- `hot_swap_revert` — roll back using bytes cached by `ClassFileLoadHook`
- `force_re_enter` — capability-gated `PopFrame` for paused-in-method swaps
- Minify detection at attach — refuses upfront on R8 builds with `minified_build_unsupported`
- Shape-diff violations surface structured `diff` arrays (method add/remove, field change, superclass / interface / access-flag change)
- @Composable / coroutine state-machine warnings
- `android-debugger-classdiff` cross-platform CLI — diffs build-dir hashes to identify changed classes for HotSwap

### Heap walks (JVMTI native-speed, 10–100× JDI)

- `count_instances` — auto-routes through JVMTI when agent is loaded; falls back to JDI
- `find_referrers` — what's holding a reference to this object; works on `vobj#` (JVMTI) and `obj#` (JDI) refs
- `iterate_heap_by_class` — materialize up to N instances of a class as `vobj#` refs; reports `total` past `max`
- `find_referrer_chain` — BFS reverse-reference walk to GC roots, returns `root_kind` (`jni_global`, `static_field`, `stack_local`, etc.)
- All gated single-flight via `VmCoordinator` (30s timeout, blocks against `eval_method` + HotSwap)

### Tracing

- `start_method_trace` / `read_method_trace` / `stop_method_trace` / `list_method_traces` — line-rate JVMTI MethodEntry/MethodExit. Filter modes: `methods` (exact list), `class_pattern` (literal prefix or wildcard), `method_regex`. Leaky-bucket throttling, per-session ring buffer, entry/exit symmetry under sampling, optional arg capture (real `GetLocalVariableTable` walk), optional return-value capture with `void` + `was_popped_by_exception` flags.
- `start_alloc_trace` / `read_alloc_trace` / `stop_alloc_trace` / `list_alloc_traces` — class-allowlisted `VMObjectAlloc` events with optional stack capture (depth 0–10). No object retention (would survive GC).

### Heap dump (full HPROF)

- `dump_heap` — shells `am dumpheap <pid>` + `adb pull` for full-heap HPROF analysis outside the live VM

### Logcat

- `tail_logcat` — start a buffered tail with regex filter; returns `buffer_id`
- `read_logcat` — read entries from a buffer; merges logpoints with `tag: "debugger:logpoint"`
- `stop_logcat` — terminate the buffer's `adb logcat` subprocess

### Android device introspection

- `get_current_activity` — `dumpsys activity top`
- `dump_view_hierarchy` — `uiautomator dump`
- `get_app_info` — debuggable flag, target SDK, declared processes

### Session persistence

- Breakpoints + watches save on `detach` to `$CLAUDE_PLUGIN_DATA/android-debugger/sessions/<serial>_<package>.json`
- Rehydrate on next `attach` to the same `(serial, package)`. Original IDs preserved so `remove_breakpoint(id)` keeps working.
- Atomic-write + 0600 on POSIX. Filenames sanitized.

### JVMTI agent

- C++ binary, three pre-built ABIs: arm64-v8a (~655 KB), x86_64 (~657 KB), armeabi-v7a (~401 KB) — fully stripped
- Loaded via `cmd activity attach-agent`. Wire protocol: line-delimited JSON-RPC 2.0 over Unix abstract-namespace socket
- Strict protocol-version handshake (v3); mismatch returns `agent_version_mismatch`
- Signal handlers write crash diagnostics to `/data/data/<pkg>/cache/amdb_agent_crash.txt`; next attach surfaces `crashed_last_session`
- Studio coexistence guard probes `/proc/<pid>/maps` for Apply-Changes artifacts; refuses with `agent_conflict`
- Eager capability acquisition at `Agent_OnAttach`; deopt cost paid once
- First-in-wins concurrency: second connect returns `agent_in_use`
- Auto-load on `attach` by default; opt out with `attach({ load_agent: false })`

### Workflow skills

13 skills covering the canonical debug loops: `:setup`, `:attach`, `:detach`, `:status`, `:explain`, `:catch`, `:trace`, `:walk`, `:bisect-flaky`, `:patch`, `:patch-revert`, `:patch-status`, `:investigate`. See [All Skills](#all-skills) above.

### Autonomous orchestrator

`android-debug-orchestrator` agent — accepts a goal, triages the shape (crash / behavior / flaky / onboarding), dispatches to the matching skill, drives the iterative loop, returns a structured findings report. One prompt in, one report out.

## Requirements

- **JDK 17 or newer.** Android Studio bundles one — point `JAVA_HOME` at `<AndroidStudio>/jbr` if you don't have a system JDK.
- **`adb` on PATH** (or `ANDROID_HOME` / `ANDROID_SDK_ROOT` set). Comes with Android SDK platform-tools.
- **A debuggable APK on a connected device or emulator.** Debug variants expose JDWP via `adb jdwp`. Release/R8-stripped builds attach with `release_build_likely` warnings; locals will be mostly empty.
- **Android 8 (API 26) or newer** for the target app. ART JVMTI was added in Oreo.
- **Claude Code** with `CLAUDE_PLUGIN_DATA` propagation for v1.3+ persistence. Older versions still work — persistence silently disables.

## Building from source

```bash
cd server
./gradlew shadowJar         # fat jar → dist/android-debugger-server.jar
./gradlew assembleAgent     # native agent → dist/agents/<abi>/libamdb_agent.so
```

The fat jar and agent `.so` files are committed to the repo so users don't need to build. The agent has its own NDK/CMake path — see `agent/README.md`.

## Troubleshooting

**`adb_status: not_found`** — set `ADB_PATH`, `ANDROID_HOME`, or add adb to PATH. macOS: `brew install --cask android-platform-tools`.

**`code: attach_failed`** — app isn't built debuggable, or Android Studio's debugger is already attached. Detach the IDE first.

**`code: already_attached`** — one VM per session. `/android-debugger:detach` first.

**`code: agent_conflict`** (v1.4+) — Android Studio's Apply-Changes agent is in the process. Detach Studio.

**`code: agent_version_mismatch`** (v1.4+) — agent loaded with an older protocol than the server expects. Force-stop the app and re-attach.

**`code: minified_build_unsupported`** (v1.5+) — HotSwap refused — class names look R8-minified. Rebuild as a debug variant.

**`code: redefine_unsupported_shape_change`** (v1.5+) — HotSwap pre-validate caught a violation. `data.diff` shows the specific delta (method add/remove, field change, etc.). Restart the app to land structural changes.

**`evaluate` says "Parse error in FEEL expression"** — FEEL uses single quotes for strings (`'hello'`), single `=` for equality, `and`/`or`/`not` for boolean logic. Method calls route through the separate `eval_method` tool — FEEL has no `obj.method()` syntax.

**App ANR-killed mid-debug** — the system kills processes that block the main thread >5s. Set breakpoints off the UI thread, or use logpoints (non-suspending).

## Dependencies

- **[ca.acendas:kfeel](https://github.com/Acendas/kfeel)** — Kotlin DMN 1.3 FEEL implementation, powers `evaluate`. Bundled in the fat jar.
- **`com.android.tools:r8:8.7.18`** — d8 dexer for HotSwap. Bundled in the fat jar.
- **ASM 9.x** — bytecode shape diff for HotSwap pre-validate. Bundled.
- **JDI** — `jdk.jdi` module, included with JDK 17+.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, end-to-end test loop against a public sample Android app, and conventions.

## License

MIT — see [LICENSE](LICENSE).
