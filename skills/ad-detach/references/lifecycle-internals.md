# Detach lifecycle internals (informational)

Read this from `/android-debugger:ad-detach` if the user asks why detach is safe — i.e. "won't this kill my app?" or "what does detach actually do?". Documentation, not instruction-for-Claude.

## What the server does under the hood

- Calls `vm.dispose()` on the JDI `VirtualMachine` (NOT `vm.exit()` — `exit` would call `System.exit` in the target app and kill it; `dispose` cleanly disconnects the debugger transport without affecting the target process).
- Removes the `adb forward tcp:N jdwp:PID` so the local TCP port is released and won't conflict on the next attach.
- Resets the in-memory session state on the MCP server side: clears all breakpoints, watches, the `frame_snapshot` cache, the logpoint buffers, and the suspended-thread record.

## What happens to the target app

The target app **continues running**. JDWP doesn't kill the VM on transport loss — threads simply un-suspend (resume from any breakpoint they were paused on) and execution continues from where the debugger had paused them.

This is the correct behavior; it's why `:detach` is the always-run-when-finished skill: forgetting to detach leaves breakpoints set and threads suspended at the next hit, which the user perceives as "the app froze" without a clear cause.

## What the user sees

After detach:

- The app is responsive again on the device.
- The next `:attach` to the same package can re-create the session cleanly without port-conflict errors.
- Any `Log.d` output or app behavior visible in logcat resumes normally — debug-mode logs aren't filtered post-detach.

## Why we don't `kill-server` adb

`adb kill-server` would terminate the whole adb daemon, breaking *every* adb-using tool on the user's machine (Android Studio, Flutter tooling, any other CLI). The plugin only releases its own forwarded port — global adb operations are out of scope.
