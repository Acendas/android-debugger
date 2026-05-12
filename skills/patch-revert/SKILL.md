---
name: Revert a HotSwap
description: Restore a previously HotSwap'd class to its pre-attach original bytes, or revert every class swapped this session.
argument-hint: "[FQN]"
allowed-tools: AskUserQuestion, mcp__android-debugger__connection_status, mcp__android-debugger__hot_swap_revert
---

# Patch-revert — undo a HotSwap

The user wants to roll back a HotSwap. Revert sources from the JVMTI agent's `ClassFileLoadHook` cache (the bytes ART had when the class first loaded post-attach).

## What you do

1. Call `mcp__android-debugger__connection_status`. If not attached, tell the user to run `/android-debugger:attach` and stop.

2. Read the argument:
   - **Empty** → revert every class swapped this session.
   - **One FQN** (e.g., `com.example.app.LoginActivity`) → revert just that class.
   - **Anything else** → ask the user to clarify via `AskUserQuestion`. Don't guess.

3. Call `mcp__android-debugger__hot_swap_revert` with the parsed args. Pass `force_re_enter: true` (the default) so the swap takes effect on threads currently paused in the targeted methods.

4. Report the result. The tool returns:
   - On success: `reverted` (array of FQNs) and `no_snapshot` (array of FQNs that weren't in the cache — typically classes loaded before the agent attached).
   - On no-snapshot for a specific FQN: surface "no snapshot stored for `<FQN>`" and explain that the agent caches class bytes from attach time onward; classes loaded before attach need a process restart to fully revert.

## What you do NOT do

- Don't try to re-swap to "undo" a revert. Use `/android-debugger:patch` to apply a fresh change instead.
- Don't iterate revert calls in a loop hoping for a different result. If the tool says `no_snapshot`, that's the truth.
