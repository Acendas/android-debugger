#!/usr/bin/env python3
"""
UI-driving smoke test against a real device, through the MCP server over stdio.

  python3 tools/smoke_ui.py --serial SERIAL [--presence] [--tap-xy X,Y]
                            [--pause-package PKG]

Checks:
  - ui_* tools are registered; ui_start returns event_log + monitor_command.
  - The Monitor follower (run from monitor_command exactly) prints lines and exits on ui_stop.
  - ui_layout returns nodes.
  - --tap-xy: ui_tap then ui_wait sees a screen_changed caused by it (tap something harmless
    and reversible; the script does not undo it).
  - --pause-package: attach + pause, UI tools refuse with vm_paused, resume, detach.
  - --presence: accessibility settings after ui_stop equal the ones before ui_start.

Device-dependent and interactive by nature; not part of `./gradlew test`.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from smoke_v1_4 import McpClient  # noqa: E402  (shared stdio JSON-RPC client)

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "dist" / "android-debugger-server.jar"
results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    results.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}", flush=True)
    return ok


def adb(serial: str, *args: str) -> str:
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True).stdout.strip()


def a11y_settings(serial: str) -> tuple[str, str]:
    return (adb(serial, "shell", "settings get secure enabled_accessibility_services"),
            adb(serial, "shell", "settings get secure accessibility_enabled"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--presence", action="store_true")
    ap.add_argument("--tap-xy")
    ap.add_argument("--undo-xy", help="tap that reverts --tap-xy (e.g. the minus next to a plus)")
    ap.add_argument("--pause-package")
    ap.add_argument("--inputs", metavar="LABEL", help="exercise swipe/long-press(on LABEL)/key/type-error")
    ap.add_argument("--crash-test", action="store_true",
                    help="with --presence: kill -9 the server, expect daemon self-exit and repair on next start")
    ap.add_argument("--expect-compose", action="store_true",
                    help="foreground app is Compose: without --presence, expect the suppression warning")
    opts = ap.parse_args()

    before = a11y_settings(opts.serial)
    env = dict(os.environ, CLAUDE_PLUGIN_ROOT=str(ROOT))
    c = McpClient(JAR, env)
    follower = None
    follow_lines: list[str] = []
    try:
        c.request("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                 "clientInfo": {"name": "smoke_ui", "version": "0"}})
        c.notify("notifications/initialized")
        names = {t["name"] for t in c.request("tools/list")["result"]["tools"]}
        want = {"ui_start", "ui_stop", "ui_status", "ui_layout", "ui_tap", "ui_long_press", "ui_swipe",
                "ui_key", "ui_type", "ui_wait", "ui_screenshot"}
        check("ui tools registered", want <= names, str(sorted(want - names)))

        r = c.tool("ui_start", {"serial": opts.serial, "presence": opts.presence}, timeout=120)
        if not check("ui_start", r.get("ok") is True, json.dumps(r)[:300]):
            return 1
        print("  monitor_command:", r["monitor_command"])
        print("  warnings:", r.get("warnings"))
        if not opts.presence and opts.expect_compose:
            check("compose_events_suppressed warned", "compose_events_suppressed" in (r.get("warnings") or []))

        # Monitor runs the command through the user's shell; do the same.
        follower = subprocess.Popen(r["monitor_command"], shell=True, stdout=subprocess.PIPE, text=True)
        threading.Thread(target=lambda: [follow_lines.append(l.rstrip()) for l in follower.stdout],
                         daemon=True).start()

        lay = c.tool("ui_layout")
        check("ui_layout returns nodes", lay.get("ok") and len(lay.get("nodes", [])) > 0,
              f"package={lay.get('package')} nodes={len(lay.get('nodes', []))} elapsed={lay.get('elapsed_ms')}ms")

        info = c.tool("server_info")
        check("server_info reports ui daemon + running session",
              info.get("ui_daemon") == "present" and info.get("ui_session") == "running", str(info.get("ui_session")))
        dv = c.tool("dump_view_hierarchy", timeout=60)
        check("dump_view_hierarchy routes through the daemon", dv.get("source") == "ui_daemon",
              f"nodes={len(dv.get('nodes', []))}")

        shot = c.tool("ui_screenshot")
        check("ui_screenshot writes a png", shot.get("ok") and shot.get("bytes", 0) > 1000, json.dumps(shot)[:200])

        if opts.inputs:
            # Harmless on a kiosk home screen: a slow swipe over empty space, a long-press on a
            # label, BACK (kiosks ignore it), and ui_type with no focused field (error path).
            sw = c.tool("ui_swipe", {"x1": 512, "y1": 560, "x2": 512, "y2": 520, "duration_ms": 400})
            check("ui_swipe injects", sw.get("ok") is True, json.dumps(sw)[:160])
            lp = c.tool("ui_long_press", {"text": opts.inputs, "contains": True, "duration_ms": 600})
            check("ui_long_press by text", lp.get("ok") is True, json.dumps(lp)[:160])
            k = c.tool("ui_key", {"key": "back"})
            check("ui_key back", k.get("ok") is True and k.get("code") == 4, json.dumps(k)[:160])
            ty = c.tool("ui_type", {"value": "hello world"})
            check("ui_type without focus -> structured no_focus", ty.get("code") == "ui_daemon_error"
                  and "no_focus" in (ty.get("current_state") or ""), json.dumps(ty)[:200])

        if opts.tap_xy:
            x, y = map(int, opts.tap_xy.split(","))
            t = c.tool("ui_tap", {"x": x, "y": y})
            w = c.tool("ui_wait", {"timeout_ms": 8000}, timeout=20)
            ev = w.get("event", {})
            check("tap -> ui_wait sees screen_changed", t.get("ok") and ev.get("kind") == "screen_changed",
                  f"added={ev.get('added')} removed={ev.get('removed')}")
            if opts.undo_xy:
                ux, uy = map(int, opts.undo_xy.split(","))
                c.tool("ui_tap", {"x": ux, "y": uy})
                w2 = c.tool("ui_wait", {"timeout_ms": 8000}, timeout=20)
                ev2 = w2.get("event", {})
                check("undo tap reported too (not swallowed as a tick)", ev2.get("kind") == "screen_changed",
                      f"added={ev2.get('added')} removed={ev2.get('removed')}")

        if opts.pause_package:
            att = c.tool("attach", {"package": opts.pause_package, "serial": opts.serial}, timeout=60)
            if check("attach", att.get("ok") is True, json.dumps(att)[:200]):
                c.tool("pause")
                g = c.tool("ui_layout")
                check("ui_layout refused while paused", g.get("code") == "vm_paused", json.dumps(g)[:160])
                g2 = c.tool("ui_tap", {"x": 1, "y": 1})
                check("ui_tap refused while paused", g2.get("code") == "vm_paused")
                c.tool("resume")
                g3 = c.tool("ui_layout")
                check("ui_layout works after resume", g3.get("ok") is True)
                c.tool("detach")

        if opts.crash_test:
            c.proc.kill()  # SIGKILL: no shutdown hook
            c.proc.wait()
            time.sleep(15)  # daemon orphan watchdog: 10 s without a host connection
            procs = adb(opts.serial, "shell", "ps -A -o PID,PPID,NAME,ARGS | grep com.acendas.adui | grep -v grep")
            check("daemon exited after host SIGKILL", "app_process" not in procs, f"survivors={procs!r}")
            dump = adb(opts.serial, "shell", "uiautomator dump /data/local/tmp/crash.xml >/dev/null 2>&1; echo $?")
            check("UiAutomation slot free again (uiautomator dump works)", dump.strip() == "0", dump)
            adb(opts.serial, "shell", "rm -f /data/local/tmp/crash.xml")
            c = McpClient(JAR, env)
            c.request("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                     "clientInfo": {"name": "smoke_ui", "version": "0"}})
            c.notify("notifications/initialized")
            r2 = c.tool("ui_start", {"serial": opts.serial}, timeout=120)
            check("next ui_start repairs stale presence", r2.get("repaired_stale_presence") is True, json.dumps(r2)[:200])
            try:
                follower.wait(timeout=10)
            except subprocess.TimeoutExpired:
                pass
            check("follower ended itself when the server died",
                  any("ui_disconnected" in l for l in follow_lines), f"last={follow_lines[-1:]}")

        st = c.tool("ui_stop")
        check("ui_stop", st.get("ok") and st.get("stopped") is True)
        try:
            follower.wait(timeout=10)
        except subprocess.TimeoutExpired:
            follower.kill()
        check("follower printed lines and exited",
              follower.returncode == 0 and any(("ui_stopped" in l or "ui_disconnected" in l) for l in follow_lines),
              f"{len(follow_lines)} lines")
        for line in follow_lines[:12]:
            print("   |", line)

        after = a11y_settings(opts.serial)
        check("accessibility settings restored exactly", before == after, f"before={before} after={after}")
        leftover = adb(opts.serial, "shell", "pm list packages com.acendas.adui.presence")
        check("presence app not left installed", leftover == "", leftover)
    finally:
        if follower and follower.poll() is None:
            follower.kill()
        c.close()

    passed = sum(ok for _, ok, _ in results)
    print(f"\nSUMMARY: {passed}/{len(results)} passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
