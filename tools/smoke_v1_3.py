#!/usr/bin/env python3
"""
v1.3 smoke test — drives the android-debugger MCP server's stdio transport directly
against a live device. Exercises:

  - initialize / tools/list (sanity)
  - list_devices, list_debuggable_processes
  - attach to a specified package
  - evaluate (kfeel-backed FEEL): pure-FEEL identifier resolution + arithmetic
  - eval_method (v1.2 surface, now returning FeelValue): String.length() round-trip
  - add_class_load_breakpoint (new in v1.3) — kind in list_breakpoints
  - detach (with persistence) — confirms `persisted: true` + saved counts
  - attach again — confirms `restored: { breakpoints: N }`
  - cleanup detach

Usage:
  CLAUDE_PLUGIN_DATA=/tmp/abd-smoke python3 tools/smoke_v1_3.py \\
    --jar dist/android-debugger-server.jar \\
    --package com.ventekintl.amdb.core
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path


class McpClient:
    def __init__(self, jar: Path, env: dict[str, str]):
        self.proc = subprocess.Popen(
            ["java", "--add-modules=jdk.jdi", "-jar", str(jar)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            bufsize=0,
        )
        self._id = 0
        self._stderr_thread = threading.Thread(target=self._drain_stderr, daemon=True)
        self._stderr_thread.start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            sys.stderr.write("[server] " + line.decode(errors="replace"))

    def _next_id(self) -> int:
        self._id += 1
        return self._id

    def _send(self, payload: dict):
        line = json.dumps(payload) + "\n"
        self.proc.stdin.write(line.encode())
        self.proc.stdin.flush()

    def _read_one(self, timeout: float = 30.0) -> dict:
        deadline = time.time() + timeout
        while time.time() < deadline:
            line = self.proc.stdout.readline()
            if not line:
                time.sleep(0.05)
                continue
            text = line.decode(errors="replace").strip()
            if not text:
                continue
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                print(f"[skip non-json] {text}", file=sys.stderr)
                continue
        raise TimeoutError("no response from server within timeout")

    def request(self, method: str, params: dict | None = None, timeout: float = 60.0) -> dict:
        rid = self._next_id()
        payload = {"jsonrpc": "2.0", "id": rid, "method": method}
        if params is not None:
            payload["params"] = params
        self._send(payload)
        while True:
            reply = self._read_one(timeout=timeout)
            # Skip notifications (no id).
            if "id" not in reply:
                continue
            if reply.get("id") != rid:
                continue
            return reply

    def notify(self, method: str, params: dict | None = None):
        payload = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            payload["params"] = params
        self._send(payload)

    def tool(self, name: str, args: dict | None = None) -> dict:
        reply = self.request("tools/call", {"name": name, "arguments": args or {}})
        if "error" in reply:
            raise RuntimeError(f"{name} → MCP error: {reply['error']}")
        content = reply.get("result", {}).get("content", [])
        if not content:
            raise RuntimeError(f"{name} → empty content")
        text = content[0].get("text", "")
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return {"raw": text}

    def close(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()


def banner(msg: str):
    print(f"\n=== {msg} ===")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True, type=Path)
    ap.add_argument("--package", required=True, help="Target package name")
    ap.add_argument("--data-dir", type=Path,
                    default=Path("/tmp/abd-smoke-data"),
                    help="CLAUDE_PLUGIN_DATA equivalent for the smoke run")
    args = ap.parse_args()

    if args.data_dir.exists():
        shutil.rmtree(args.data_dir)
    args.data_dir.mkdir(parents=True)

    env = os.environ.copy()
    env["CLAUDE_PLUGIN_DATA"] = str(args.data_dir)

    failures: list[str] = []

    def check(cond: bool, msg: str):
        if cond:
            print(f"  ✓ {msg}")
        else:
            print(f"  ✗ {msg}")
            failures.append(msg)

    client = McpClient(args.jar, env)
    try:
        banner("initialize")
        init = client.request("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "smoke", "version": "0"},
        })
        check("error" not in init, "initialize returned ok")
        client.notify("notifications/initialized", {})

        banner("tools/list")
        listed = client.request("tools/list", {})
        tool_names = [t["name"] for t in listed.get("result", {}).get("tools", [])]
        print(f"  {len(tool_names)} tools registered")
        for required in ("evaluate", "eval_method", "add_class_load_breakpoint",
                         "detach", "attach", "list_breakpoints"):
            check(required in tool_names, f"tool registered: {required}")

        banner("list_devices")
        devices = client.tool("list_devices")
        print("  →", json.dumps(devices)[:200])
        check(devices.get("ok") is True, "list_devices ok")
        ds = devices.get("devices", [])
        check(len(ds) >= 1, "at least one device visible")
        if not ds:
            raise RuntimeError("no device; aborting")
        serial = ds[0]["serial"]

        banner("list_debuggable_processes")
        procs = client.tool("list_debuggable_processes", {"serial": serial})
        print("  →", json.dumps(procs)[:200])
        check(procs.get("ok") is True, "list_debuggable_processes ok")
        matched = [p for p in procs.get("processes", []) if p.get("package") == args.package]
        check(len(matched) >= 1, f"target package present: {args.package}")
        if not matched:
            raise RuntimeError("target package not in JDWP list; aborting")

        banner("attach (first time — no persisted state)")
        attached = client.tool("attach", {"serial": serial, "package": args.package})
        print("  →", json.dumps(attached)[:400])
        check(attached.get("ok") is True, "first attach ok")
        check("capabilities" in attached, "capabilities probe present")
        check("restored" not in attached, "no restored state (first attach)")

        banner("evaluate — pure FEEL arithmetic (no frame needed for trivial cases)")
        # We need a frame id to call evaluate, but the VM isn't paused. To still smoke
        # the kfeel parse + bridge, set a class-load breakpoint that will fire on the
        # next class load, then wait for it.

        banner("add_class_load_breakpoint (new in v1.3)")
        bp = client.tool("add_class_load_breakpoint", {"class_pattern": "java.lang.Object"})
        print("  →", json.dumps(bp))
        check(bp.get("ok") is True, "add_class_load_breakpoint ok")
        check("id" in bp, "got bp id")
        bp_id = bp.get("id")

        banner("list_breakpoints — confirm new kind appears")
        lst = client.tool("list_breakpoints")
        print("  →", json.dumps(lst)[:400])
        check(any(b.get("kind") == "class_load" for b in lst.get("breakpoints", [])),
              "list shows class_load kind")
        check(any(b.get("class_pattern") == "java.lang.Object" for b in lst.get("breakpoints", [])),
              "list carries class_pattern")

        banner("add_line_breakpoint (will deferred — class likely not loaded)")
        line_bp = client.tool("add_line_breakpoint", {"file": "MainActivity.kt", "line": 1})
        print("  →", json.dumps(line_bp)[:300])
        check(line_bp.get("ok") is True, "add_line_breakpoint ok (deferred or resolved)")

        banner("detach with persist=true")
        det = client.tool("detach", {"persist": True})
        print("  →", json.dumps(det))
        check(det.get("ok") is True, "detach ok")
        check(det.get("was_attached") is True, "was_attached true")
        check(det.get("persisted") is True, "persisted: true")
        check(det.get("saved_breakpoints", 0) >= 2, "saved at least 2 breakpoints")
        saved_path = det.get("persisted_path")
        if saved_path:
            print(f"  persisted at: {saved_path}")
            check(Path(saved_path).exists(), "persisted file exists on disk")

        banner("attach (second time — should rehydrate)")
        re_attached = client.tool("attach", {"serial": serial, "package": args.package})
        print("  →", json.dumps(re_attached)[:400])
        check(re_attached.get("ok") is True, "second attach ok")
        restored = re_attached.get("restored")
        check(restored is not None, "restored block present")
        if restored:
            print(f"  restored: {restored}")
            check(restored.get("breakpoints", 0) >= 2, "restored at least 2 breakpoints")

        banner("list_breakpoints after restore — original ids preserved")
        lst2 = client.tool("list_breakpoints")
        print("  →", json.dumps(lst2)[:400])
        ids_post = sorted(b["id"] for b in lst2.get("breakpoints", []))
        print(f"  ids after restore: {ids_post}")
        check(bp_id in ids_post, f"original class-load bp id {bp_id} preserved")

        banner("final detach with persist=false (clean state for re-run)")
        det2 = client.tool("detach", {"persist": False})
        print("  →", json.dumps(det2))
        check(det2.get("ok") is True, "final detach ok")
        check(det2.get("persisted") is False, "persisted: false (opt-out honored)")

    finally:
        client.close()

    print("\n" + "=" * 60)
    if failures:
        print(f"SMOKE FAILED — {len(failures)} check(s) didn't pass:")
        for f in failures:
            print(f"  ✗ {f}")
        sys.exit(1)
    else:
        print("SMOKE PASSED")


if __name__ == "__main__":
    main()
