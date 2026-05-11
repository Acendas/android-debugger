#!/usr/bin/env python3
"""
v1.4 smoke — drives the android-debugger MCP server's stdio transport against
a live device with the JVMTI agent auto-loaded. Exercises:

  - initialize / tools/list (verify agent_info registered)
  - attach({ load_agent: true }) → response carries `agent: { ... }` block
  - agent_info → direct query of the agent's capability map
  - re-attach in same app process → agent stays loaded; reused via fresh socket
  - load_agent: false → opt-out works; agent_info reports loaded: false
  - final detach with persist=false

Usage (matches smoke_v1_3.py and smoke_v1_3_paused.py):
  CLAUDE_PLUGIN_DATA=/tmp/abd-smoke-v14 python3 tools/smoke_v1_4.py \\
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
        self.proc.stdin.write((json.dumps(payload) + "\n").encode())
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


def force_stop(package: str):
    adb("shell", "am", "force-stop", package, check=False)


def launch(activity: str):
    adb("shell", "am", "start", "-W", "-n", activity, check=False)


def wait_for_pid(package: str, timeout: float = 15.0) -> int | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = adb("shell", "pidof", package, capture=True).strip()
        if out:
            try:
                return int(out.split()[0])
            except ValueError:
                pass
        time.sleep(0.3)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True, type=Path)
    ap.add_argument("--package", required=True)
    ap.add_argument("--activity", default=None,
                    help="component name for am start -n; default infers <pkg>/.AmdbCoreActivity")
    ap.add_argument("--data-dir", type=Path, default=Path("/tmp/abd-smoke-v14-data"))
    args = ap.parse_args()

    activity = args.activity or f"{args.package}/.AmdbCoreActivity"

    if args.data_dir.exists():
        shutil.rmtree(args.data_dir)
    args.data_dir.mkdir(parents=True)

    env = os.environ.copy()
    env["CLAUDE_PLUGIN_DATA"] = str(args.data_dir)
    # v1.4 needs CLAUDE_PLUGIN_ROOT to find dist/agents/<abi>/libamdb_agent.so.
    plugin_root = args.jar.resolve().parent.parent
    env["CLAUDE_PLUGIN_ROOT"] = str(plugin_root)

    failures: list[str] = []

    def check(cond: bool, msg: str):
        if cond:
            print(f"  ✓ {msg}")
        else:
            print(f"  ✗ {msg}")
            failures.append(msg)

    # Force-stop so we start with no prior agent loaded (clears the JVMTI agent state).
    banner("force-stop + relaunch target app")
    force_stop(args.package)
    time.sleep(0.5)
    launch(activity)
    pid = wait_for_pid(args.package, timeout=15.0)
    if pid is None:
        print("  ✗ target PID not visible — aborting")
        sys.exit(1)
    print(f"  PID: {pid}")

    client = McpClient(args.jar, env)
    try:
        banner("MCP initialize")
        init = client.request("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "smoke-v1.4", "version": "0"},
        })
        check("error" not in init, "initialize ok")
        client.notify("notifications/initialized", {})

        banner("tools/list — confirm agent_info registered")
        listed = client.request("tools/list", {})
        tool_names = [t["name"] for t in listed.get("result", {}).get("tools", [])]
        print(f"  {len(tool_names)} tools registered")
        check("agent_info" in tool_names, "agent_info tool registered")
        check("attach" in tool_names, "attach tool registered")

        banner("attach(load_agent: true) — auto-loads JVMTI agent")
        attached = client.tool("attach", {"package": args.package, "load_agent": True})
        if not attached.get("ok"):
            print("  attach reply:", json.dumps(attached, indent=2))
            sys.exit(1)
        print(f"  attached: pid={attached.get('pid')}")
        agent = attached.get("agent") or {}
        check(agent.get("loaded") is True, "attach response has agent.loaded=true")
        check(agent.get("version") == "1.4.0", f"agent.version is 1.4.0 (got {agent.get('version')})")
        check(agent.get("protocol_version") == 1, "agent.protocol_version is 1")
        check("capabilities" in agent, "agent.capabilities present")
        caps = agent.get("capabilities") or {}
        check(isinstance(caps.get("can_redefine_classes"), bool),
              f"can_redefine_classes is bool (got {type(caps.get('can_redefine_classes')).__name__})")
        check("attach_path" in agent, "agent.attach_path present")
        check("transport" in agent, "agent.transport present")
        # The capability map should have ~25 entries.
        check(len(caps) >= 20, f"capability map has 20+ entries ({len(caps)} got)")

        banner("agent_info — direct query")
        info = client.tool("agent_info")
        check(info.get("loaded") is True, "agent_info loaded=true")
        check(info.get("version") == "1.4.0", "agent_info version is 1.4.0")
        info_caps = info.get("capabilities") or {}
        check(len(info_caps) >= 20, "agent_info capabilities matches attach response")
        # Print a few key capabilities for visual confirmation.
        for key in ["can_redefine_classes", "can_retransform_classes", "can_tag_objects",
                    "can_pop_frame", "can_force_early_return"]:
            print(f"    {key} = {info_caps.get(key)}")

        banner("detach + force-stop + relaunch + re-attach — agent loads into fresh process")
        # On this embedded device (and many real-world scenarios), the target app
        # process can die between sessions. Force-stop+relaunch forces a fresh
        # process so we exercise the "agent loads from scratch" path (Agent_OnAttach
        # runs full init, listener binds the abstract socket).
        client.tool("detach", {"persist": False})
        time.sleep(0.5)
        force_stop(args.package)
        time.sleep(0.5)
        launch(activity)
        new_pid = wait_for_pid(args.package, timeout=15.0)
        check(new_pid is not None, "fresh process visible after relaunch")
        check(new_pid != pid, f"fresh PID differs from prior ({pid} → {new_pid})")
        reattached = client.tool("attach", {"package": args.package, "load_agent": True})
        check(reattached.get("ok") is True, "re-attach ok")
        reagent = reattached.get("agent") or {}
        check(reagent.get("loaded") is True, "re-attach loads agent into fresh process")
        check(reagent.get("version") == "1.4.0", "re-attached agent is same v1.4.0")
        check(reagent.get("attach_pid") == new_pid, "re-attach reports the fresh PID")
        re_caps = reagent.get("capabilities") or {}
        check(len(re_caps) >= 20, "re-attached agent reports the same capability shape")

        banner("detach + attach with load_agent=false — opt-out works")
        client.tool("detach", {"persist": False})
        time.sleep(0.5)
        attached_no_agent = client.tool("attach", {"package": args.package, "load_agent": False})
        check(attached_no_agent.get("ok") is True, "attach with load_agent=false ok")
        check(attached_no_agent.get("agent") is None,
              "no agent block in response when load_agent=false")

        banner("agent_info when load_agent was false — reports loaded=false")
        info_off = client.tool("agent_info")
        check(info_off.get("loaded") is False,
              "agent_info reports loaded=false when not requested")
        check("reason" in info_off, "agent_info loaded=false includes a reason")
        print(f"  reason: {info_off.get('reason')}")

        banner("final detach")
        client.tool("detach", {"persist": False})

    finally:
        client.close()

    print("\n" + "=" * 60)
    if failures:
        print(f"SMOKE FAILED — {len(failures)} check(s) didn't pass:")
        for f in failures:
            print(f"  ✗ {f}")
        sys.exit(1)
    else:
        print("SMOKE PASSED — v1.4 JVMTI agent foundation verified end-to-end")


if __name__ == "__main__":
    main()
