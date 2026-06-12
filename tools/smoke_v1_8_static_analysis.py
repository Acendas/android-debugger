#!/usr/bin/env python3
"""
v1.8 smoke — drives the android-debugger MCP server over stdio JSON-RPC and exercises
the 4 new SootUp-backed static-analysis tools (`static_class_hierarchy`,
`static_call_graph`, `static_cfg`, `static_package_graph`).

Device-free: no `--serial`, no `adb`, no emulator, no `attach`/`detach`. The tools are
registered unconditionally at server startup (see `Tools.kt`) and operate purely on
compiled `.class` fixture output under
`server/build/classes/java/test/com/acendas/fixtures/...`.

Scenarios (mapped to the v1.8 plan's AC numbers):

  AC-11  static_class_hierarchy with class_dirs=[] / nonexistent dir
         -> ok:false, code:"class_dirs_empty_or_missing", hint mentions rebuild guidance.
  AC-1   static_class_hierarchy on Circle, direction up / down / both
         -> ok:true; Circle/Shape/Drawable/Movable nodes; extends/implements/extended_by
         edges; truncated:false.
  AC-2   static_class_hierarchy with a nonexistent target_class
         -> ok:false, code:"class_not_found".
  AC-3   static_call_graph on Dispatcher.dispatch, direction:"callees"
         -> handler.handle() virtual dispatch expands to 5 of the 7 HandlerA..HandlerG
         implementers (overridden_by edges) + one "+2 more implementers" overflow node.
  AC-5   static_call_graph on {class: Calc, name: "add"} (no params)
         -> ok:false, code:"method_ambiguous", 3 candidates all containing "add".
  AC-6   static_cfg on BranchLoop.classify
         -> basic-block nodes; "true"/"false" branch edges; a loop back-edge
         (edge.to block index <= edge.from block index); ascii contains
         "(cycle, see ... above)".
  AC-7   static_cfg on TryCatch.safeDivide
         -> an edge labeled "catch ArithmeticException" (exceptional successor).
  AC-8   static_package_graph rooted at com.acendas.fixtures.pkg, depth=2
         -> pkg.a / pkg.b package nodes with a cross-package edge a -> b.
  AC-10  static_class_hierarchy on Circle with node_cap=1
         -> truncated:true.

Manual checklist (not exercised by this JSON-RPC harness — skill bodies / agent
dispatch are markdown prompts, not wire-testable):

  AC-15  Run the `ad-graph` skill end-to-end against a real Android project with a
         built debug variant. Confirm it:
           - resolves class_dirs from the project's build output without the agent
             being told the exact path (e.g. discovers
             build/intermediates/javac/debug/classes or
             build/tmp/kotlin-classes/debug),
           - renders the ascii/mermaid output in a way that's useful in a terminal
             session and in a persisted doc,
           - handles a real app's scale (node_cap/depth defaults feel reasonable,
             truncation messaging is clear when it triggers).
  AC-16  Orchestrator routing: ask the `android-debug-orchestrator` agent a natural
         -language question that should route to one of the 4 static-analysis tools
         (e.g. "what implements ClickHandler in this app?", "show me the call graph
         for onClick", "what's the control flow of this method", "what packages does
         this module depend on?") and confirm it picks the right tool + params
         without the user naming the tool explicitly.

Usage:
  python3 tools/smoke_v1_8_static_analysis.py
  python3 tools/smoke_v1_8_static_analysis.py --jar dist/android-debugger-server.jar
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


class McpClient:
    def __init__(self, jar: Path, env: dict):
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


def main():
    ap = argparse.ArgumentParser()
    repo_root = Path(__file__).resolve().parent.parent
    ap.add_argument("--jar", type=Path, default=repo_root / "dist" / "android-debugger-server.jar")
    args = ap.parse_args()

    jar = args.jar.resolve()
    if not jar.exists():
        print(f"  ✗ jar not found at {jar}")
        print("    Build it first: cd server && ./gradlew shadowJar")
        sys.exit(1)

    fixtures_dir = repo_root / "server" / "build" / "classes" / "java" / "test"
    if not fixtures_dir.is_dir():
        print(f"  ✗ fixture build output not found at {fixtures_dir}")
        print("    Compile the test fixtures first: cd server && ./gradlew compileTestJava")
        sys.exit(1)

    class_dirs = [str(fixtures_dir)]

    env = os.environ.copy()
    data_dir = Path("/tmp/abd-smoke-v18-data")
    env["CLAUDE_PLUGIN_DATA"] = str(data_dir)
    env["CLAUDE_PLUGIN_ROOT"] = str(jar.parent.parent)

    failures: list[str] = []
    passed: list[str] = []

    def check(cond: bool, msg: str):
        if cond:
            print(f"  ✓ {msg}")
            passed.append(msg)
        else:
            print(f"  ✗ {msg}")
            failures.append(msg)

    client = McpClient(jar, env)
    try:
        # ============ Wire / protocol ============
        banner("Wire: MCP initialize + tools/list")
        init = client.request("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "smoke-v1.8", "version": "0"},
        })
        check("error" not in init, "initialize ok")
        client.notify("notifications/initialized", {})

        listed = client.request("tools/list", {})
        tool_names = [t["name"] for t in listed.get("result", {}).get("tools", [])]
        for tname in (
            "static_class_hierarchy",
            "static_call_graph",
            "static_cfg",
            "static_package_graph",
        ):
            check(tname in tool_names, f"{tname} registered")

        # ============ AC-11: class_dirs_empty_or_missing ============
        banner("AC-11: class_dirs=[] -> class_dirs_empty_or_missing")
        r = client.tool("static_class_hierarchy", {
            "class_dirs": [],
            "target_class": "com.acendas.fixtures.hierarchy.Circle",
        })
        check(r.get("ok") is False, "empty class_dirs returned ok=false")
        check(r.get("code") == "class_dirs_empty_or_missing",
              f"code=class_dirs_empty_or_missing (got {r.get('code')})")
        hint = (r.get("hint") or "")
        check("gradlew" in hint or "assembleDebug" in hint or "build" in hint,
              f"hint mentions rebuild guidance (got: {hint[:120]})")

        banner("AC-11 (cont): class_dirs=[<nonexistent>] -> class_dirs_empty_or_missing")
        r = client.tool("static_class_hierarchy", {
            "class_dirs": ["/tmp/this-path-does-not-exist-abd-v18"],
            "target_class": "com.acendas.fixtures.hierarchy.Circle",
        })
        check(r.get("ok") is False, "nonexistent class_dirs returned ok=false")
        check(r.get("code") == "class_dirs_empty_or_missing",
              f"code=class_dirs_empty_or_missing (got {r.get('code')})")

        # ============ AC-1: class hierarchy on Circle ============
        banner("AC-1: static_class_hierarchy(Circle, direction=both)")
        r = client.tool("static_class_hierarchy", {
            "class_dirs": class_dirs,
            "target_class": "com.acendas.fixtures.hierarchy.Circle",
            "direction": "both",
        })
        check(r.get("ok") is True, "ok=true")
        if r.get("ok") is True:
            ids = {n["id"] for n in r.get("nodes", [])}
            for expected in (
                "com.acendas.fixtures.hierarchy.Circle",
                "com.acendas.fixtures.hierarchy.Shape",
                "com.acendas.fixtures.hierarchy.Drawable",
                "com.acendas.fixtures.hierarchy.Movable",
            ):
                check(expected in ids, f"nodes include {expected}")

            def label_of(frm, to):
                return next((e.get("label") for e in r.get("edges", [])
                              if e.get("from") == frm and e.get("to") == to), None)

            check(
                label_of("com.acendas.fixtures.hierarchy.Circle", "com.acendas.fixtures.hierarchy.Shape") == "extends",
                "Circle -extends-> Shape",
            )
            check(
                label_of("com.acendas.fixtures.hierarchy.Circle", "com.acendas.fixtures.hierarchy.Movable") == "implements",
                "Circle -implements-> Movable",
            )
            check(
                label_of("com.acendas.fixtures.hierarchy.Shape", "com.acendas.fixtures.hierarchy.Drawable") == "implements",
                "Shape -implements-> Drawable",
            )
            check(r.get("truncated") is False, "truncated=false")

        banner("AC-1 (cont): static_class_hierarchy(Circle, direction=up)")
        r = client.tool("static_class_hierarchy", {
            "class_dirs": class_dirs,
            "target_class": "com.acendas.fixtures.hierarchy.Circle",
            "direction": "up",
        })
        check(r.get("ok") is True, "ok=true")
        if r.get("ok") is True:
            ids = {n["id"] for n in r.get("nodes", [])}
            for expected in (
                "com.acendas.fixtures.hierarchy.Circle",
                "com.acendas.fixtures.hierarchy.Shape",
                "com.acendas.fixtures.hierarchy.Movable",
                "com.acendas.fixtures.hierarchy.Drawable",
            ):
                check(expected in ids, f"up-only nodes include {expected}")
            check(r.get("truncated") is False, "truncated=false")

        banner("AC-1 (cont): static_class_hierarchy(Shape, direction=down)")
        r = client.tool("static_class_hierarchy", {
            "class_dirs": class_dirs,
            "target_class": "com.acendas.fixtures.hierarchy.Shape",
            "direction": "down",
        })
        check(r.get("ok") is True, "ok=true")
        if r.get("ok") is True:
            ids = {n["id"] for n in r.get("nodes", [])}
            check("com.acendas.fixtures.hierarchy.Circle" in ids, "down-only nodes include Circle")
            edge = next((e for e in r.get("edges", [])
                         if e.get("from") == "com.acendas.fixtures.hierarchy.Shape"
                         and e.get("to") == "com.acendas.fixtures.hierarchy.Circle"), None)
            check(edge is not None and edge.get("label") == "extended_by",
                  f"Shape -extended_by-> Circle (got edge: {edge})")

        # ============ AC-2: unknown class -> class_not_found ============
        banner("AC-2: static_class_hierarchy with unknown target_class -> class_not_found")
        r = client.tool("static_class_hierarchy", {
            "class_dirs": class_dirs,
            "target_class": "com.acendas.fixtures.hierarchy.NoSuchClass",
        })
        check(r.get("ok") is False, "ok=false")
        check(r.get("code") == "class_not_found", f"code=class_not_found (got {r.get('code')})")

        # ============ AC-3: call graph virtual dispatch + max_dispatch_targets ============
        banner("AC-3: static_call_graph(Dispatcher.dispatch, direction=callees) "
               "-> virtual dispatch capped at max_dispatch_targets=5")
        r = client.tool("static_call_graph", {
            "class_dirs": class_dirs,
            "target_method": {"class": "com.acendas.fixtures.dispatch.Dispatcher", "name": "dispatch"},
            "direction": "callees",
        })
        check(r.get("ok") is True, "ok=true")
        if r.get("ok") is True:
            nodes = r.get("nodes", [])
            edges = r.get("edges", [])

            handle_node = next((n for n in nodes if "ClickHandler" in n["id"] and "handle" in n["id"]), None)
            check(handle_node is not None, "nodes include ClickHandler.handle()")

            overridden_by = [e for e in edges if e.get("label") == "overridden_by"]
            implementer_edges = [e for e in overridden_by if not e["to"].endswith("#more")]
            check(len(implementer_edges) == 5,
                  f"5 overridden_by implementer edges (got {len(implementer_edges)})")

            overflow_edge = next((e for e in overridden_by if e["to"].endswith("#more")), None)
            check(overflow_edge is not None, "an overflow (#more) overridden_by edge exists")
            if overflow_edge is not None:
                overflow_node = next((n for n in nodes if n["id"] == overflow_edge["to"]), None)
                check(overflow_node is not None and overflow_node.get("label") == "+2 more implementers",
                      f"overflow node label = '+2 more implementers' (got {overflow_node and overflow_node.get('label')})")

        # ============ AC-5: overloaded method without params -> method_ambiguous ============
        banner("AC-5: static_call_graph({class: Calc, name: add}) without params -> method_ambiguous")
        r = client.tool("static_call_graph", {
            "class_dirs": class_dirs,
            "target_method": {"class": "com.acendas.fixtures.overload.Calc", "name": "add"},
        })
        check(r.get("ok") is False, "ok=false")
        check(r.get("code") == "method_ambiguous", f"code=method_ambiguous (got {r.get('code')})")
        candidates = r.get("candidates") or []
        check(len(candidates) == 3, f"3 candidates (got {len(candidates)})")
        check(all("add" in c for c in candidates), f"all candidates contain 'add' (got {candidates})")

        # ============ AC-6: CFG branch + loop ============
        banner("AC-6: static_cfg(BranchLoop.classify) -> true/false branches + loop back-edge")
        r = client.tool("static_cfg", {
            "class_dirs": class_dirs,
            "target_method": {"class": "com.acendas.fixtures.cfg.BranchLoop", "name": "classify"},
        })
        check(r.get("ok") is True, "ok=true")
        if r.get("ok") is True:
            edges = r.get("edges", [])
            check(any(e.get("label") == "true" for e in edges), "has a 'true' branch edge")
            check(any(e.get("label") == "false" for e in edges), "has a 'false' branch edge")

            def block_index(node_id: str) -> int:
                return int(node_id.removeprefix("bb"))

            back_edges = [e for e in edges if block_index(e["to"]) <= block_index(e["from"])]
            check(len(back_edges) > 0, f"has a loop back-edge (to <= from) (got edges: {edges})")

            ascii_out = r.get("ascii") or ""
            check("(cycle, see " in ascii_out and " above)" in ascii_out,
                  "ascii contains '(cycle, see ... above)' for the loop")

        # ============ AC-7: CFG try/catch exceptional edge ============
        banner("AC-7: static_cfg(TryCatch.safeDivide) -> exceptional successor edge")
        r = client.tool("static_cfg", {
            "class_dirs": class_dirs,
            "target_method": {"class": "com.acendas.fixtures.cfg.TryCatch", "name": "safeDivide"},
        })
        check(r.get("ok") is True, "ok=true")
        if r.get("ok") is True:
            edges = r.get("edges", [])
            catch_edge = next((e for e in edges if (e.get("label") or "").startswith("catch ")), None)
            check(catch_edge is not None, f"has a 'catch <ExceptionType>' edge (got edges: {edges})")
            if catch_edge is not None:
                check(catch_edge["label"] == "catch ArithmeticException",
                      f"catch edge labeled 'catch ArithmeticException' (got '{catch_edge['label']}')")

        # ============ AC-8: package graph cross-package reference ============
        banner("AC-8: static_package_graph(com.acendas.fixtures.pkg, depth=2) -> pkg.a -> pkg.b")
        r = client.tool("static_package_graph", {
            "class_dirs": class_dirs,
            "root_package": "com.acendas.fixtures.pkg",
            "depth": 2,
        })
        check(r.get("ok") is True, "ok=true")
        if r.get("ok") is True:
            ids = {n["id"] for n in r.get("nodes", [])}
            check("com.acendas.fixtures.pkg.a" in ids, "nodes include pkg.a")
            check("com.acendas.fixtures.pkg.b" in ids, "nodes include pkg.b")
            edge = next((e for e in r.get("edges", [])
                         if e.get("from") == "com.acendas.fixtures.pkg.a"
                         and e.get("to") == "com.acendas.fixtures.pkg.b"), None)
            check(edge is not None, f"cross-package edge pkg.a -> pkg.b exists (got edges: {r.get('edges')})")

        # ============ AC-10: node_cap=1 -> truncated ============
        banner("AC-10: static_class_hierarchy(Circle, node_cap=1) -> truncated=true")
        r = client.tool("static_class_hierarchy", {
            "class_dirs": class_dirs,
            "target_class": "com.acendas.fixtures.hierarchy.Circle",
            "node_cap": 1,
        })
        check(r.get("ok") is True, "ok=true")
        if r.get("ok") is True:
            check(r.get("truncated") is True, f"truncated=true (got {r.get('truncated')})")

    finally:
        client.close()

    print("\n" + "=" * 60)
    print(f"PASSED: {len(passed)}/{len(passed) + len(failures)} checks")
    if failures:
        print(f"SMOKE FAILED — {len(failures)} check(s) didn't pass:")
        for f in failures:
            print(f"  ✗ {f}")
        sys.exit(1)
    else:
        print("SMOKE PASSED — v1.8 static-analysis tools verified end-to-end (device-free)")


if __name__ == "__main__":
    main()
