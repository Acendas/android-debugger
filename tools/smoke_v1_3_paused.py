#!/usr/bin/env python3
"""
v1.3 smoke — paused-frame edition. Attaches to a running AMDB process, manually
pauses the VM, picks a frame with locals, and exercises the parts the first smoke
couldn't reach against a live JDI frame:

  - frame_snapshot of a real paused frame
  - evaluate with FEEL arithmetic, ternary, instance of, ranges
  - evaluate with FEEL identifier resolution from the live frame
  - eval_method with target=this — JDI invokeMethod round-trip returning FeelValue
  - mutation refusal still applies via eval_method (FEEL grammar can't even express it)

We use `pause` (vm.suspend) rather than waiting for a breakpoint to fire because:
  (a) the app may already be past common lifecycle methods by the time we attach,
  (b) `am start` on a foregrounded activity doesn't re-fire onResume,
  (c) `pause` is deterministic and exercises the same paused-state code paths.

Usage:
  CLAUDE_PLUGIN_DATA=/tmp/abd-smoke-paused python3 tools/smoke_v1_3_paused.py \\
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

    def _read_one(self, timeout: float) -> dict:
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

    def tool(self, name: str, args: dict | None = None, timeout: float = 60.0) -> dict:
        reply = self.request("tools/call", {"name": name, "arguments": args or {}}, timeout=timeout)
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


def adb(*args, check=True, capture=False):
    cmd = ["adb"] + list(args)
    if capture:
        r = subprocess.run(cmd, capture_output=True, text=True)
        if check and r.returncode != 0:
            raise RuntimeError(f"{' '.join(cmd)} exited {r.returncode}: {r.stderr}")
        return r.stdout
    else:
        r = subprocess.run(cmd)
        if check and r.returncode != 0:
            raise RuntimeError(f"{' '.join(cmd)} exited {r.returncode}")


def pick_frame_with_locals(snap: dict, threads_listing: dict) -> tuple[int | None, dict | None]:
    """Pick a frame that has at least one local — those are the ones evaluate can
    meaningfully test against."""
    # snap is a single-thread snapshot. We get a thread choice from the threads listing first.
    # Strategy: iterate frames top-down; the first frame whose `locals` list is non-empty wins.
    for frame in snap.get("frames", []):
        if frame.get("locals") and len(frame["locals"]) > 0:
            return frame.get("frame_id"), frame
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True, type=Path)
    ap.add_argument("--package", required=True)
    ap.add_argument("--data-dir", type=Path,
                    default=Path("/tmp/abd-smoke-paused-data"))
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
        banner("MCP initialize")
        init = client.request("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "smoke-paused", "version": "0"},
        })
        check("error" not in init, "initialize ok")
        client.notify("notifications/initialized", {})

        banner("attach")
        attached = client.tool("attach", {"package": args.package})
        if not attached.get("ok"):
            print("  attach reply:", json.dumps(attached))
            sys.exit(1)
        check(attached.get("ok") is True, "attach ok")
        print(f"  attached: pid={attached.get('pid')} vm={attached.get('vm_name')} {attached.get('vm_version')}")

        banner("pause — vm.suspend() to freeze the VM at whatever it's doing")
        paused = client.tool("pause")
        print("  →", json.dumps(paused)[:200])
        check(paused.get("ok") is True, "pause ok")

        banner("list_threads — find a thread with frames")
        threads = client.tool("list_threads")
        print(f"  {len(threads.get('threads', []))} threads")
        check(threads.get("ok") is True, "list_threads ok")

        # Pick the main thread first (Android UI thread, name='main'). If it has no
        # usable frame, fall back to scanning other suspended threads.
        chosen_thread = None
        for t in threads.get("threads", []):
            if t.get("name") == "main" and t.get("suspended"):
                chosen_thread = t
                break
        if not chosen_thread:
            for t in threads.get("threads", []):
                if t.get("suspended"):
                    chosen_thread = t
                    break
        check(chosen_thread is not None, "found a suspended thread")
        if not chosen_thread:
            sys.exit(1)
        thread_id = chosen_thread["id"]
        print(f"  using thread {thread_id} ({chosen_thread.get('name')})")

        banner(f"frame_snapshot on thread {thread_id}")
        snap = client.tool("frame_snapshot", {"thread_id": thread_id, "depth": 15})
        # snap shape: { ok, snapshot: { thread, frames: [{ method, file, line, frame_id, locals?, this? }] } }
        frames = snap.get("snapshot", {}).get("frames", [])
        if not frames:
            frames = snap.get("frames", [])  # older shape
        print(f"  {len(frames)} frames in snapshot")
        for i, f in enumerate(frames[:5]):
            print(f"   [{i}] {f.get('method')} @ {f.get('file')}:{f.get('line')} "
                  f"(locals={len(f.get('locals') or [])}, this={'yes' if f.get('this') else 'no'})")

        # Pick the top frame that has a non-empty locals AND a `this` if possible. Failing
        # that, just take frame 0.
        frame_id = None
        chosen_frame = None
        for f in frames:
            if f.get("locals") and len(f["locals"]) > 0:
                frame_id = f.get("frame_id")
                chosen_frame = f
                break
        if frame_id is None and frames:
            frame_id = frames[0].get("frame_id")
            chosen_frame = frames[0]
        check(frame_id is not None, "got a frame_id")
        if frame_id is None:
            sys.exit(1)
        print(f"  using frame_id {frame_id} → {chosen_frame.get('method')}")

        banner("evaluate — pure FEEL arithmetic (no identifiers)")
        e1 = client.tool("evaluate", {"expr": "1 + 2 * 3", "frame_id": frame_id})
        print("  →", json.dumps(e1)[:300])
        check(e1.get("ok") is True, "evaluate arithmetic ok")
        check(str(e1.get("value", {}).get("rendered")) == "7", "1 + 2 * 3 == 7")

        banner("evaluate — boolean and-chain")
        e2 = client.tool("evaluate", {"expr": "5 > 3 and 10 < 100", "frame_id": frame_id})
        print("  →", json.dumps(e2)[:300])
        check(e2.get("ok") is True and str(e2.get("value", {}).get("rendered")) == "true",
              "boolean and-chain true")

        banner("evaluate — ternary with single-quoted FEEL strings")
        e3 = client.tool("evaluate",
                         {"expr": "if 5 > 3 then 'yes' else 'no'", "frame_id": frame_id})
        print("  →", json.dumps(e3)[:300])
        check(e3.get("ok") is True, "ternary ok")
        check("yes" in str(e3.get("value", {}).get("rendered", "")), "ternary returned 'yes'")

        banner("evaluate — instance of string")
        e4 = client.tool("evaluate",
                         {"expr": "'hello' instance of string", "frame_id": frame_id})
        print("  →", json.dumps(e4)[:300])
        check(e4.get("ok") is True and str(e4.get("value", {}).get("rendered")) == "true",
              "'hello' instance of string == true")

        banner("evaluate — range membership")
        e5 = client.tool("evaluate", {"expr": "42 in [1..100]", "frame_id": frame_id})
        print("  →", json.dumps(e5)[:300])
        check(e5.get("ok") is True and str(e5.get("value", {}).get("rendered")) == "true",
              "42 in [1..100] == true")

        banner("evaluate — list comprehension count")
        e6 = client.tool("evaluate", {"expr": "count([1,2,3,4,5])", "frame_id": frame_id})
        print("  →", json.dumps(e6)[:300])
        check(e6.get("ok") is True and str(e6.get("value", {}).get("rendered")) == "5",
              "count([1,2,3,4,5]) == 5")

        banner("evaluate — quantifier 'every'")
        e7 = client.tool("evaluate",
                         {"expr": "every x in [1,2,3] satisfies x > 0", "frame_id": frame_id})
        print("  →", json.dumps(e7)[:300])
        check(e7.get("ok") is True and str(e7.get("value", {}).get("rendered")) == "true",
              "every x in [1,2,3] satisfies x > 0")

        banner("evaluate — identifier resolution from live frame")
        # Try `this` first if the frame has it.
        if chosen_frame.get("this"):
            e_this = client.tool("evaluate", {"expr": "this", "frame_id": frame_id})
            print("  this →", json.dumps(e_this)[:400])
            check(e_this.get("ok") is True, "evaluate('this') ok")
            if e_this.get("ok"):
                v = e_this.get("value", {})
                print(f"    type={v.get('type')} refId={v.get('refId')}")
                check(v.get("refId") is not None,
                      "evaluate('this') carries refId for eval_method drill-down")
        # And one local by name if any. Locals shape is {"name": RenderedValue} dict.
        locals_map = chosen_frame.get("locals")
        local_names = []
        if isinstance(locals_map, dict):
            local_names = list(locals_map.keys())
        elif isinstance(locals_map, list):
            local_names = [item.get("name") for item in locals_map if isinstance(item, dict)]
        if local_names:
            first_local_name = local_names[0]
            print(f"  trying local: {first_local_name}")
            e_local = client.tool("evaluate",
                                  {"expr": first_local_name, "frame_id": frame_id})
            print("  →", json.dumps(e_local)[:300])
            check(e_local.get("ok") is True,
                  f"evaluate('{first_local_name}') resolves identifier from live frame")

        banner("eval_method — toString on `this` (read-allowed framework method)")
        if chosen_frame.get("this"):
            em = client.tool("eval_method", {
                "frame_id": frame_id,
                "target": "this",
                "method": "toString",
                "args": [],
            })
            print("  →", json.dumps(em)[:400])
            check(em.get("ok") is True, "eval_method toString on this")
            if em.get("ok"):
                print(f"    toString: {em.get('value', {}).get('rendered')}")
                # Also confirm the v1.3 `raw` JSON field is present (new in v1.3).
                check(em.get("raw") is not None, "eval_method reply carries `raw` FeelValue JSON (v1.3)")

        banner("evaluate — unknown function (clear) rejected by FEEL grammar")
        e_clear = client.tool("evaluate", {"expr": "clear()", "frame_id": frame_id})
        print("  →", json.dumps(e_clear)[:300])
        check(e_clear.get("ok") is False, "FEEL grammar rejects bare clear() — no kfeel built-in")
        check(e_clear.get("code") == "invalid_target",
              "rejection comes back as invalid_target (parse/undefined-function family)")

        banner("eval_method — mutation refusal still bites for setter-like names")
        if chosen_frame.get("this"):
            em_set = client.tool("eval_method", {
                "frame_id": frame_id,
                "target": "this",
                "method": "setSomething",
                "args": [],
                "allow_mutation": False,
            })
            print("  →", json.dumps(em_set)[:300])
            # The refusal happens BEFORE the JDI lookup, so even though `setSomething`
            # doesn't exist on the target, the response should be `vm_mutation_refused`
            # (not `invalid_target`). That's the v1.2 BR-02 contract preserved in v1.3.
            check(em_set.get("ok") is False, "setSomething rejected")
            check(em_set.get("code") == "vm_mutation_refused",
                  "rejection is vm_mutation_refused (not invalid_target — refusal precedes lookup)")

        banner("resume + detach (persist=false)")
        client.tool("resume")
        det = client.tool("detach", {"persist": False})
        print("  →", json.dumps(det))
        check(det.get("ok") is True, "final detach ok")

    finally:
        client.close()

    print("\n" + "=" * 60)
    if failures:
        print(f"SMOKE FAILED — {len(failures)} check(s) didn't pass:")
        for f in failures:
            print(f"  ✗ {f}")
        sys.exit(1)
    else:
        print("SMOKE PASSED — paused-frame v1.3 paths exercised end-to-end")


if __name__ == "__main__":
    main()
