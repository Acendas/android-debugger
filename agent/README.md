# android-debugger JVMTI agent

A small C++ JVMTI agent loaded into the target Android app via
`cmd activity attach-agent`. The Kotlin MCP server talks to it over a Unix
abstract-namespace socket (`@android-debugger-<package>`) forwarded by adb.

v1.4 ships the foundation: capability probe + JSON-RPC `hello` / `ping` /
`agent_info_raw`. v1.5 adds HotSwap (`hot_swap_class` via `RedefineClasses`).
v1.6 routes heap-walk operations through the agent for ~10–100× speedup vs JDI.

## What's already built

Pre-built `.so` files for every shipped ABI live under
`dist/agents/<abi>/libamdb_agent.so` and are checked into the plugin repo
(same precedent as `dist/android-debugger-server.jar`). End users don't need
the NDK installed.

```
dist/agents/
├── arm64-v8a/libamdb_agent.so       ~250 KB
├── x86_64/libamdb_agent.so          ~250 KB
└── armeabi-v7a/libamdb_agent.so     ~150 KB
```

## When you need to rebuild

If you edit any source under `agent/src/` or change a build option in
`agent/CMakeLists.txt`, regenerate the `.so` files:

```bash
./agent/build.sh                # release build, all ABIs (default)
./agent/build.sh debug          # debug build (keeps symbols)
```

or via Gradle (which wraps the script):

```bash
cd server && ./gradlew assembleAgent
```

Both invocations regenerate `dist/agents/<abi>/libamdb_agent.so` for every
shipped ABI.

## Prerequisites for rebuilds

- **Android NDK r26d or newer.** Install via
  `sdkmanager 'ndk;27.2.12479018'` (or any later 27.x). The build script
  auto-detects the highest installed NDK under `$ANDROID_HOME/ndk/`.
- **CMake 3.22 or newer.** The Android SDK installs one under
  `$ANDROID_HOME/cmake/`; the build script falls back to that if `cmake` isn't
  on `PATH`. Or install via `brew install cmake` on macOS,
  `apt install cmake` on Debian/Ubuntu.
- A POSIX shell. The `build.sh` script is bash-only; Windows maintainers can
  invoke it from Git Bash or WSL.

## ABI matrix

| ABI | When you need it |
|---|---|
| `arm64-v8a` | All modern Android devices (post-2017) |
| `x86_64` | Android emulator on Intel/AMD hosts |
| `armeabi-v7a` | Older 32-bit ARM devices (Cortex-A9, embedded SOMs) |

Devices report their primary ABI via `getprop ro.product.cpu.abi`. The plugin's
Kotlin attach flow detects the device ABI and pushes the right `.so`.

## License: the vendored `jvmti.h`

`agent/include/jvmti.h` is a verbatim copy of the OpenJDK 11 header, GPLv2
with the Classpath Exception. The Classpath Exception explicitly allows
linking with otherwise-incompatible licenses, which is why this plugin can
ship under MIT. Standard practice for every JVMTI agent on Android (Studio's
Apply Changes does the same).

## Lifetime

The agent loads once per app process and stays loaded until the process dies.
JVMTI doesn't support agent unload, so `:detach` from the plugin only closes
the socket — the agent itself remains in memory. Re-attaching to the same app
reuses the existing agent via a fresh socket connection.

If the agent crashes (signal handler triggers), it writes a marker file at
`/data/data/<package>/cache/amdb_agent_crash.txt` containing the signal name,
faulting PC, and last RPC method. The Kotlin server picks this up on the next
attach and surfaces it as a `crashed_last_session` warning so you can triage.

## Wire protocol

Line-delimited JSON-RPC 2.0 over the abstract socket. First message must be
`{"method": "hello", "params": {"protocol_version": 1}}`. Mismatched
protocol versions trigger `agent_version_mismatch` and the agent closes the
socket. See `agent/src/agent.cpp:serve_client` for the dispatch loop.

v1.4 RPCs: `hello`, `ping`, `agent_info_raw`.

## Security note (v1.4)

The agent's Unix abstract-namespace socket is reachable by any process on the
device that knows the name. Android's app sandbox restricts cross-UID access,
but `userdebug` / `eng` builds (typical for development boards) are more
permissive. **Do not run android-debugger on a device you don't control.**
v1.5 may add token-based authentication when the HotSwap surface raises the
stakes.
