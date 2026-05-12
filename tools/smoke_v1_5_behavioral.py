#!/usr/bin/env python3
"""
v1.5 behavioral smoke — proves ART actually applies the redefine, not just
that the wire reports success.

Method:
  1. Compile two versions of OosCopyLinter.containsForbiddenTokens — original
     uses FORBIDDEN_TOKENS.any{...}, modified returns true unconditionally.
  2. Attach + pause VM
  3. Reach the Kotlin singleton via Java reflection chain:
       Class.forName("...OosCopyLinter")  -> Class<?>
         .getDeclaredField("INSTANCE")    -> Field
         .get(null)                        -> singleton instance
         .containsForbiddenTokens("hello") -> Boolean
  4. Swap to v1; invoke; assert false.
  5. Swap to v2; invoke; assert true.
  6. Resume + detach.

Usage:
  python3 tools/smoke_v1_5_behavioral.py \\
    --jar dist/android-debugger-server.jar \\
    --package com.ventekintl.amdb.core \\
    --serial 10.0.2.151:5555 \\
    --v1 /tmp/OosCopyLinter.v1.class \\
    --v2 /tmp/OosCopyLinter.v2.class \\
    --target-fqn com.ventekintl.amdb.core.presentation.oos.OosCopyLinter
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path


class McpClient:
    def __init__(self, jar: Path, env: dict):
        self.proc = subprocess.Popen(
            ["java", "--add-modules=jdk.jdi", "-jar", str(jar)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=env, bufsize=0,
        )
        self._id = 0
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            sys.stderr.write("[server] " + line.decode(errors="replace"))

    def _send(self, payload: dict):
        self.proc.stdin.write((json.dumps(payload) + "\n").encode())
        self.proc.stdin.flush()

    def _read_one(self, timeout: float) -> dict:
        deadline = time.time() + timeout
        while time.time() < deadline:
            line = self.proc.stdout.readline()
            if not line:
                time.sleep(0.05); continue
            text = line.decode(errors="replace").strip()
            if not text: continue
            try: return json.loads(text)
            except json.JSONDecodeError: continue
        raise TimeoutError("no response from server within timeout")

    def request(self, method: str, params: dict | None = None, timeout: float = 60.0) -> dict:
        self._id += 1
        rid = self._id
        payload = {"jsonrpc": "2.0", "id": rid, "method": method}
        if params is not None: payload["params"] = params
        self._send(payload)
        while True:
            reply = self._read_one(timeout=timeout)
            if reply.get("id") == rid: return reply

    def notify(self, method: str, params: dict | None = None):
        payload = {"jsonrpc": "2.0", "method": method}
        if params is not None: payload["params"] = params
        self._send(payload)

    def tool(self, name: str, args: dict | None = None, timeout: float = 60.0) -> dict:
        reply = self.request("tools/call", {"name": name, "arguments": args or {}}, timeout=timeout)
        if "error" in reply: raise RuntimeError(f"{name} → MCP error: {reply['error']}")
        content = reply.get("result", {}).get("content", [])
        if not content: raise RuntimeError(f"{name} → empty content")
        text = content[0].get("text", "")
        try: return json.loads(text)
        except json.JSONDecodeError: return {"raw": text}

    def close(self):
        try: self.proc.stdin.close()
        except Exception: pass
        try: self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired: self.proc.kill()


def banner(msg: str): print(f"\n=== {msg} ===")


def extract_ref_id(reply: dict) -> str | None:
    """Pull obj#N from an eval_method reply. The `value` field is the
    RenderedValue.toJson() shape: {rendered, type, ref_id?}.
    """
    val = reply.get("value")
    if isinstance(val, dict) and "ref_id" in val:
        return val["ref_id"]
    # Fallback: the `raw` field carries the FeelValue toJson(); for object
    # refs it's a JsonObject with __objectId.
    raw = reply.get("raw")
    if isinstance(raw, dict) and "__objectId" in raw:
        return raw["__objectId"]
    return None


def extract_bool(reply: dict) -> bool | None:
    """Pull a boolean out of an eval_method reply. RenderedValue has
    {rendered: "true", type: "boolean"} — we parse the rendered text."""
    val = reply.get("value")
    if isinstance(val, dict) and val.get("type") == "boolean":
        rendered = val.get("rendered")
        if rendered == "true": return True
        if rendered == "false": return False
    raw = reply.get("raw")
    if isinstance(raw, bool):
        return raw
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True, type=Path)
    ap.add_argument("--package", required=True)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--v1", required=True, type=Path)
    ap.add_argument("--v2", required=True, type=Path)
    ap.add_argument("--target-fqn", required=True)
    ap.add_argument("--data-dir", type=Path, default=Path("/tmp/abd-smoke-v15-beh-data"))
    args = ap.parse_args()

    if args.data_dir.exists(): shutil.rmtree(args.data_dir)
    args.data_dir.mkdir(parents=True)

    env = os.environ.copy()
    env["CLAUDE_PLUGIN_DATA"] = str(args.data_dir)
    env["CLAUDE_PLUGIN_ROOT"] = str(args.jar.resolve().parent.parent)
    env["ANDROID_SERIAL"] = args.serial

    v1_bytes = args.v1.read_bytes()
    v2_bytes = args.v2.read_bytes()
    if v1_bytes == v2_bytes:
        print("✗ v1 and v2 bytes are identical — nothing to verify behaviorally"); sys.exit(1)
    v1_b64 = base64.b64encode(v1_bytes).decode()
    v2_b64 = base64.b64encode(v2_bytes).decode()
    target = args.target_fqn

    failures, passed = [], []
    def check(cond: bool, msg: str):
        (passed if cond else failures).append(msg)
        print(f"  {'✓' if cond else '✗'} {msg}")

    client = McpClient(args.jar, env)

    def invoke_via_reflection(frame_id: str, input_str: str) -> tuple[bool | None, str | None]:
        """Chain: Thread.currentThread().getContextClassLoader() -> ClassLoader
        Class.forName(target, true, loader) -> Class<?>
        cls.getDeclaredField("INSTANCE") -> Field
        field.get(null) -> singleton
        singleton.containsForbiddenTokens(input_str) -> Boolean
        Returns (result, err)."""
        # 1) Thread.currentThread() — static
        r = client.tool("eval_method", {
            "frame_id": frame_id,
            "target": "java.lang.Thread",
            "method": "currentThread",
            "args": [],
        })
        if not r.get("ok"): return None, f"Thread.currentThread: {r.get('message') or r}"
        thread_ref = extract_ref_id(r)
        if not thread_ref: return None, "no Thread ref"

        # 2) thread.getContextClassLoader() — instance, returns the app's PathClassLoader
        r = client.tool("eval_method", {
            "frame_id": frame_id,
            "target": thread_ref,
            "method": "getContextClassLoader",
            "args": [],
        })
        if not r.get("ok"): return None, f"getContextClassLoader: {r.get('message') or r}"
        loader_ref = extract_ref_id(r)
        if not loader_ref: return None, "no ClassLoader ref"

        # 3) Class.forName(name, initialize, classLoader) — static 3-arg form
        r = client.tool("eval_method", {
            "frame_id": frame_id,
            "target": "java.lang.Class",
            "method": "forName",
            "args": [target, True, loader_ref],
        })
        if not r.get("ok"): return None, f"Class.forName: {r.get('message') or r}"
        cls_ref = extract_ref_id(r)
        if not cls_ref: return None, f"no Class<?> ref in: {json.dumps(r)[:200]}"

        # Class.getDeclaredField(String) — instance
        r = client.tool("eval_method", {
            "frame_id": frame_id,
            "target": cls_ref,
            "method": "getDeclaredField",
            "args": ["INSTANCE"],
        })
        if not r.get("ok"): return None, f"getDeclaredField: {r.get('message') or r}"
        field_ref = extract_ref_id(r)
        if not field_ref: return None, f"no Field ref"

        # Field.get(Object) — instance, pass null for static field
        r = client.tool("eval_method", {
            "frame_id": frame_id,
            "target": field_ref,
            "method": "get",
            "args": ["null"],  # server treats string "null" as JDI null
        })
        if not r.get("ok"): return None, f"Field.get: {r.get('message') or r}"
        inst_ref = extract_ref_id(r)
        if not inst_ref: return None, f"no INSTANCE ref"

        # singleton.containsForbiddenTokens(String) — instance
        r = client.tool("eval_method", {
            "frame_id": frame_id,
            "target": inst_ref,
            "method": "containsForbiddenTokens",
            "args": [input_str],
        })
        if not r.get("ok"): return None, f"containsForbiddenTokens: {r.get('message') or r}"
        b = extract_bool(r)
        if b is None: return None, f"could not extract bool from: {json.dumps(r)[:200]}"
        return b, None

    try:
        banner("MCP init + attach")
        client.request("initialize", {
            "protocolVersion": "2024-11-05", "capabilities": {},
            "clientInfo": {"name": "smoke-v1.5-behavioral", "version": "0"},
        })
        client.notify("notifications/initialized", {})

        attached = client.tool("attach", {"package": args.package, "load_agent": True, "serial": args.serial})
        if not attached.get("ok"):
            print(json.dumps(attached, indent=2)); sys.exit(1)
        print(f"  attached pid={attached.get('pid')}; agent v={(attached.get('agent') or {}).get('version')}")

        # JDI's invokeMethod requires the thread to be paused at an EVENT (breakpoint/
        # step/exception), not via a generic vm.suspend(). Generic suspend leaves threads
        # at whatever native poll they were in (most Android threads sit in
        # MessageQueue.nativePollOnce or Unsafe.park) — IncompatibleThreadStateException.
        #
        # Workaround: install a method-entry breakpoint on android.os.Handler.dispatchMessage
        # (fires on every UI message dispatch — at least once per ~16ms on an active UI).
        # Wait for it to hit; the firing thread is paused at an event and accepts
        # invokeMethod.
        banner("install method bp on Handler.dispatchMessage + wait for it")
        bp = client.tool("add_method_breakpoint", {
            "class": "android.os.Handler",
            "method": "dispatchMessage",
            "kind": "entry",
        })
        check(bp.get("ok") is True, "method breakpoint installed")
        if not bp.get("ok"):
            print(json.dumps(bp, indent=2)); sys.exit(1)
        bp_id = bp.get("id")

        # Idempotent: VM is running after attach, but call resume to be safe (e.g., if
        # the BP registration internally paused for class-prepare deferral).
        client.tool("resume")
        evt = client.tool("wait_for_event", {"timeout_ms": 10000})
        if not evt.get("ok") or evt.get("event") is None:
            print(f"✗ no breakpoint event within 10s: {evt}")
            sys.exit(1)
        ev = evt.get("event") or {}
        print(f"  event: type={ev.get('type')} reason={ev.get('reason')} "
              f"thread={ev.get('thread_name')} thread_id={ev.get('thread_id')}")
        thread_id = ev.get("thread_id")
        if thread_id is None:
            print(f"✗ event has no thread_id: {evt}")
            sys.exit(1)
        frame_id = f"frame#{thread_id}:0"
        print(f"  frame_id: {frame_id}")

        # Confirm invokeMethod works on this event-paused thread.
        probe = client.tool("eval_method", {
            "frame_id": frame_id,
            "target": "java.lang.Class", "method": "forName",
            "args": ["java.lang.String"],
        })
        check(probe.get("ok") is True, "event-paused thread accepts invokeMethod")
        if not probe.get("ok"):
            print(json.dumps(probe, indent=2)); sys.exit(1)

        banner("Step 1: invoke first (Class.forName triggers class load) → expect v1 behavior")
        # The reflection chain's first call (Class.forName) ensures the class is
        # loaded in ART. Without this, hot_swap_class refuses with class_not_loaded
        # for lazily-loaded user classes.
        v1_result, err = invoke_via_reflection(frame_id, "hello")
        if err: print(f"  ✗ {err}"); failures.append("v1 invocation chain failed"); sys.exit(1)
        print(f"  v1 result: {v1_result}")
        check(v1_result is False,
              "v1 containsForbiddenTokens('hello') == false (running app's original code)")

        banner("Step 2: hot_swap_class to v2 (method body returns true)")
        v2_swap = client.tool("hot_swap_class", {"class": target, "class_bytes_b64": v2_b64})
        check(v2_swap.get("ok") is True, "v2 swap ok")
        if not v2_swap.get("ok"):
            print(json.dumps(v2_swap, indent=2))
        else:
            print(f"  v2 dex_sha256: {v2_swap.get('dex_sha256')}")

        banner("Step 3: re-invoke after swap → expect v2 behavior")
        v2_result, err = invoke_via_reflection(frame_id, "hello")
        if err: print(f"  ✗ {err}"); failures.append("v2 invocation chain failed")
        else:
            print(f"  v2 result: {v2_result}")
            check(v2_result is True,
                  "v2 containsForbiddenTokens('hello') == TRUE (proves ART applied the swap!)")
            check(v1_result is False and v2_result is True,
                  "BEHAVIOR CHANGED: ART successfully redefined the live method body")

        banner("Step 4: restore v1 so the app's session state is preserved")
        restore = client.tool("hot_swap_class", {"class": target, "class_bytes_b64": v1_b64})
        check(restore.get("ok") is True, "restore-to-v1 ok")
        if restore.get("ok"):
            check(v2_swap.get("dex_sha256") != restore.get("dex_sha256"),
                  "v2 vs restored-v1 dex_sha256 differ")
            # Confirm behavior actually rolled back too.
            restore_result, err = invoke_via_reflection(frame_id, "hello")
            if err: print(f"  ✗ {err}")
            else:
                print(f"  post-restore result: {restore_result}")
                check(restore_result is False,
                      "post-restore containsForbiddenTokens('hello') == false (full round-trip)")

        banner("cleanup: remove method bp + resume + detach")
        if 'bp_id' in dir() and bp_id is not None:
            client.tool("remove_breakpoint", {"id": bp_id})
        client.tool("resume")
        client.tool("detach", {"persist": False})

    finally:
        client.close()

    print("\n" + "=" * 60)
    print(f"PASSED: {len(passed)}/{len(passed)+len(failures)}")
    if failures:
        print(f"BEHAVIORAL SMOKE FAILED — {len(failures)} check(s):")
        for f in failures: print(f"  ✗ {f}")
        sys.exit(1)
    print("BEHAVIORAL SMOKE PASSED — v1.5 HotSwap proven to change runtime behavior")


if __name__ == "__main__":
    main()
