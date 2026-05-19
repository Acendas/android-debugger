---
name: ad-patch-status
description: List classes hot-swapped in the current session.
allowed-tools: mcp__android-debugger__connection_status, mcp__android-debugger__agent_info
---

# Patch-status — see what's been HotSwap'd this session

The user wants a quick "what's the current HotSwap state of this session?" readout — useful when iterating on a feature and forgetting which classes are currently in a non-default state.

## What you do

1. Call `mcp__android-debugger__connection_status`. If not attached, tell the user to attach first and stop.

2. Call `mcp__android-debugger__agent_info`. Read:
   - `hot_swap_supported` — whether the device supports HotSwap at all.
   - `force_re_enter_supported` — whether `force_re_enter: true` is honored.
   - `minify_detected` — whether the build is minified (blocks HotSwap upfront).
   - `device_api_level` — the API level used by the embedded dexer.

3. Render a compact summary. v1.5 doesn't expose a `list_swapped_classes` tool (the snapshot store is server-internal); surface what `agent_info` exposes:

   ```
   HotSwap status on session <session_id>:
     supported:           <hot_swap_supported>
     minify detected:     <minify_detected>
     force-re-enter:      <force_re_enter_supported>
     device API:          <device_api_level>
   ```

4. If `hot_swap_supported: false`, explain why: minify_detected, ART capability, or agent not loaded. Suggest the remediation (rebuild without minify; check ART version; re-attach with `load_agent: true`).

## What you do NOT do

- Don't claim to know which classes have been swapped this session — the server doesn't expose the list yet (v1.6 may). Surface what the tools provide; don't fabricate.
