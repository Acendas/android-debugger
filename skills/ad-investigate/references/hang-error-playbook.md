# v1.7.1 hang-error playbook

Single source of truth for how the meta-orchestrator (ad-investigate) and the orchestrator agent (android-debug-orchestrator) react to hang-mitigation error codes and signals. Customers see your reaction; cycles wasted in retry loops feel like a hang to them.

The server guarantees every tool returns within a wall-clock budget. When that budget fires, the codes below are the signal to escalate — do not retry blindly.

## `tool_timeout`

Tool exceeded its wall-clock budget (default 60s; `wait_for_event` 65s; heap dump 180s; hot_swap 180s). Hint names the budget.

```
1. connection_status      → cheap, bounded at 60s
2. If attached: false     → call attach() again from preflight
3. If attached: true      → detach() + re-attach() (JDWP socket likely wedged)
4. If 2 consecutive       → STOP. Partial report titled "investigation halted: repeated tool timeouts."
   tool_timeouts            Recommend `adb kill-server && adb start-server`, then full restart of
                             emulator + app. Do NOT keep retrying.
```

## `attach_timeout`

JDI attach hit its 10s socket-level ceiling. App process probably gone or JDWP port stale.

```
1. list_debuggable_processes (cheap; <2s) — does the target PID still exist?
2. If gone                → tell user "target no longer debuggable; relaunch app and I'll re-attach". STOP.
3. If still present       → Bash: `adb kill-server && adb start-server`. Wait 5s. Re-attach ONCE.
4. If second              → STOP. Tell user the emulator is wedged at the JDWP layer; recommend
   attach_timeout           cold restart of the emulator.
```

## `plan_progress(stuck, events_handled = 0)`

Plan armed but no JDI events arrived within `stuck_detect_ms` (default 90s). Setup BPs may not match the user's code path, OR the user hasn't triggered the bug yet.

```
1. IMMEDIATELY tell the user:
   "Plan is armed but hasn't seen any events in 90s. Either the bug hasn't been reproduced yet
   (trigger it now), or the breakpoints aren't matching the code path."
2. Keep polling wait_for_event(["plan_progress"], timeout_ms: 30000) for ONE more interval.
3. If second stuck fires  → abort_plan(plan_id) (NOT pause — VM should run while we re-author).
   with events_handled=0    Re-author with broader class patterns, an additional exception_bp for
                             safety, or a different shape entirely (crash → trace). Re-submit at
                             high effort.
4. If the third plan      → STOP plan-mode. Drop to interactive frame_snapshot + evaluate
   also stucks with         exploration. The agent doesn't have enough model of the code to plan
   events_handled=0         well; let the user drive while we observe.
```

## `plan_progress(stuck, events_handled > 0)`

Plan got events earlier but they stopped flowing. User likely stopped reproducing, OR the code branched away.

```
1. Tell user: "events stopped flowing — is the bug still reproducible right now?"
2. Inspect partial report:
   - If hypothesis verdicts + snapshots already root-cause → abort_plan and present.
   - If insufficient → wait ONE more poll cycle, then abort_plan and re-author with broader setup.
```

## `pause_plan` / `abort_plan` returned status "aborted" with reason mentioning "force-killed"

The executor coroutine didn't honor cooperative cancellation; the server force-completed the terminal. The JDI surface was wedged. The VM might be in an inconsistent state (plan BPs removed, but other tools may have stale references).

```
1. Acknowledge the signal: the underlying JDWP transport is unhealthy.
2. Run connection_status. If still attached → detach() + re-attach() before next investigation step.
3. Note in the final report: "v1.7.1 force-kill triggered during plan abort — JDWP transport
   recommended for cold restart if force-kills recur."
```

## `vm_disconnected` (post-M8: now also fires when a tool times out and VM is gone)

```
1. STOP the current dispatch.
2. Post a partial report with whatever was harvested.
3. Recommend `/android-debugger:ad-attach` to reconnect.
```

## Hard rules across all of the above

- **2 consecutive timeouts of the same kind = STOP.** Surface the signal honestly; do not loop.
- **3 stuck signals across an investigation = drop to interactive.** Plan-first isn't a fit for this code path.
- **Repeated force-kills in one session = recommend cold restart.** Don't paper over a wedged JDWP transport.
- **Never silently retry.** Every retry needs a documented reason in your narration so the user can intervene.
