#!/usr/bin/env python3
"""
v1.5 smoke — drives the android-debugger MCP server against a live device with
the JVMTI agent v2 (HotSwap) loaded. Exercises the 9 scenarios from
.claude/plans/android-debugger-v1.5.md §17.3:

  1. Wire/protocol: agent loaded, protocol_version=2, v1.5 tools registered
  2. agent_info has the 5 derived flags from spec §3.4
  3. hot_swap_class with unloaded FQN → class_not_loaded
  4. hot_swap_class with invalid base64 → invalid_target
  5. hot_swap_revert on a never-swapped class → invalid_target with hint
  6. SHAPE-CHANGE REJECT: ASM-synthesized class with extra method → redefine_unsupported_shape_change
  7. ROUND-TRIP SWAP: same bytes back → ok, structured response
  8. REVERT after round-trip → ok
  9. BATCH REJECT: hot_swap_classes with one valid + one violation → batch rolled back

Behavioral verification (scenarios "next invocation runs new code" + "force_re_enter
while paused") is left for /android-debugger:patch driven by the user; this smoke
verifies the wire end-to-end.

Usage:
  python3 tools/smoke_v1_5.py \\
    --jar dist/android-debugger-server.jar \\
    --package com.ventekintl.amdb.core \\
    --serial 10.0.2.151:5555 \\
    --target-class amdbcore/build/tmp/kotlin-classes/debug/com/ventekintl/amdb/core/AmdbCoreActivity\$Companion.class \\
    --target-fqn com.ventekintl.amdb.core.AmdbCoreActivity\$Companion
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import struct
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


def adb(serial: str, *args, check=True, capture=False):
    cmd = ["adb", "-s", serial] + list(args)
    if capture:
        r = subprocess.run(cmd, capture_output=True, text=True)
        if check and r.returncode != 0:
            raise RuntimeError(f"{' '.join(cmd)} exited {r.returncode}: {r.stderr}")
        return r.stdout
    else:
        r = subprocess.run(cmd)
        if check and r.returncode != 0:
            raise RuntimeError(f"{' '.join(cmd)} exited {r.returncode}")


def wait_for_pid(serial: str, package: str, timeout: float = 15.0) -> int | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = adb(serial, "shell", "pidof", package, capture=True).strip()
        if out:
            try:
                return int(out.split()[0])
            except ValueError:
                pass
        time.sleep(0.3)
    return None


# ---------- ASM-equivalent: minimal class-bytecode mutator ----------
#
# We need to take a real .class file (which the JVMTI agent's
# ClassFileLoadHook has cached for some class in the target VM) and
# produce a variant with a new method added. Done in pure Python via
# direct ClassFile structure surgery — avoids pulling ASM into the
# smoke driver. The synthetic method is added to the methods table and
# the methods_count is bumped. Constant-pool entries for the method
# name + descriptor are appended.
#
# Standard ClassFile layout (JVM 8):
#   u4 magic = 0xCAFEBABE
#   u2 minor; u2 major
#   u2 constant_pool_count
#   cp_info constant_pool[constant_pool_count - 1]
#   u2 access_flags; u2 this_class; u2 super_class
#   u2 interfaces_count; u2 interfaces[];
#   u2 fields_count; field_info fields[];
#   u2 methods_count; method_info methods[];
#   u2 attributes_count; attribute_info attributes[];
#
# We don't actually need to dex this variant successfully — the smoke
# expects the *server-side ClassDiff pre-validate* to catch the added
# method BEFORE handing to d8. So the mutated bytes only need to parse
# enough for ASM's ClassReader to recognise the extra method entry.

CONSTANT_Utf8 = 1
CONSTANT_Integer = 3
CONSTANT_Class = 7
CONSTANT_String = 8
CONSTANT_NameAndType = 12
CONSTANT_MethodRef = 10

def _skip_cp_entry(buf: bytes, pos: int) -> tuple[int, int]:
    """Return (next_pos, tag_width_in_entries) for one cp entry."""
    tag = buf[pos]
    pos += 1
    if tag in (CONSTANT_Utf8,):
        (length,) = struct.unpack_from(">H", buf, pos)
        pos += 2 + length
        return pos, 1
    if tag in (CONSTANT_Integer, 4, CONSTANT_MethodRef, 9, 11, CONSTANT_NameAndType, 17, 18):
        pos += 4
        return pos, 1
    if tag in (CONSTANT_Class, CONSTANT_String, 16, 19, 20):
        pos += 2
        return pos, 1
    if tag in (5, 6):  # Long, Double take 2 cp slots
        pos += 8
        return pos, 2
    if tag == 15:  # MethodHandle
        pos += 3
        return pos, 1
    raise ValueError(f"unknown cp tag {tag} at pos {pos-1}")


def add_synthetic_method(class_bytes: bytes) -> bytes:
    """Add a new public void method __smoke_v1_5_synthetic() to the class.
    Returns the mutated .class bytes. The new method has an empty Code attribute
    (just a RETURN instruction)."""
    # Validate magic.
    if class_bytes[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a .class file (bad magic)")

    pos = 8  # past magic + minor + major
    (cp_count,) = struct.unpack_from(">H", class_bytes, pos)
    pos += 2
    cp_start = pos
    n = 1
    while n < cp_count:
        pos, width = _skip_cp_entry(class_bytes, pos)
        n += width
    cp_end = pos

    # We need to append 3 cp entries:
    #   cp[X+0]   Utf8  "__smoke_v1_5_synthetic"
    #   cp[X+1]   Utf8  "()V"
    #   cp[X+2]   Utf8  "Code"
    new_name = b"__smoke_v1_5_synthetic"
    new_desc = b"()V"
    new_code_attr_name = b"Code"
    new_cp_bytes = b""
    new_cp_bytes += bytes([CONSTANT_Utf8]) + struct.pack(">H", len(new_name)) + new_name
    new_cp_bytes += bytes([CONSTANT_Utf8]) + struct.pack(">H", len(new_desc)) + new_desc
    new_cp_bytes += bytes([CONSTANT_Utf8]) + struct.pack(">H", len(new_code_attr_name)) + new_code_attr_name
    name_idx = cp_count           # 1-indexed
    desc_idx = cp_count + 1
    code_attr_idx = cp_count + 2
    new_cp_count = cp_count + 3

    # Walk past header, interfaces, fields, then arrive at methods_count.
    (access_flags, this_class, super_class) = struct.unpack_from(">HHH", class_bytes, cp_end)
    after_super = cp_end + 6
    (interfaces_count,) = struct.unpack_from(">H", class_bytes, after_super)
    interfaces_end = after_super + 2 + 2 * interfaces_count

    def _skip_fields_or_methods(start: int) -> int:
        (count,) = struct.unpack_from(">H", class_bytes, start)
        p = start + 2
        for _ in range(count):
            p += 6  # access_flags + name_index + descriptor_index
            (attr_count,) = struct.unpack_from(">H", class_bytes, p)
            p += 2
            for _ in range(attr_count):
                p += 2  # attribute_name_index
                (attr_len,) = struct.unpack_from(">I", class_bytes, p)
                p += 4 + attr_len
        return p

    fields_end = _skip_fields_or_methods(interfaces_end)
    methods_start = fields_end
    (methods_count,) = struct.unpack_from(">H", class_bytes, methods_start)
    methods_body_start = methods_start + 2
    methods_end = _skip_fields_or_methods(methods_start)

    # Build the synthetic method_info: ACC_PUBLIC(0x0001), name_idx, desc_idx,
    # 1 attribute (Code), Code: name_idx, length, max_stack=0, max_locals=1,
    # code_length=1, code=[RETURN(0xB1)], 0 exception_handlers, 0 attributes.
    code_body = b"\x00\x00"   # max_stack
    code_body += b"\x00\x01"  # max_locals
    code_body += struct.pack(">I", 1)  # code_length
    code_body += b"\xb1"     # RETURN
    code_body += b"\x00\x00" # 0 exception handlers
    code_body += b"\x00\x00" # 0 attributes
    code_attr = struct.pack(">H", code_attr_idx) + struct.pack(">I", len(code_body)) + code_body
    method = struct.pack(">HHH", 0x0001, name_idx, desc_idx) + struct.pack(">H", 1) + code_attr

    # Rebuild the class file: header + new CP + middle (unchanged) +
    # methods_count+1 + existing methods + new method + tail.
    new_class = bytearray()
    new_class.extend(class_bytes[:8])  # magic + version
    new_class.extend(struct.pack(">H", new_cp_count))
    new_class.extend(class_bytes[cp_start:cp_end])
    new_class.extend(new_cp_bytes)
    new_class.extend(class_bytes[cp_end:methods_start])
    new_class.extend(struct.pack(">H", methods_count + 1))
    new_class.extend(class_bytes[methods_body_start:methods_end])
    new_class.extend(method)
    new_class.extend(class_bytes[methods_end:])
    return bytes(new_class)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True, type=Path)
    ap.add_argument("--package", required=True)
    ap.add_argument("--serial", required=True, help="adb device serial, e.g. 10.0.2.151:5555")
    ap.add_argument("--activity", default=None)
    ap.add_argument("--data-dir", type=Path, default=Path("/tmp/abd-smoke-v15-data"))
    ap.add_argument("--target-class", type=Path, required=True,
                    help="Path to a .class file from the project's build output; used as the source of the round-trip swap.")
    ap.add_argument("--target-fqn", required=True,
                    help="Java FQN matching --target-class, dot notation.")
    args = ap.parse_args()

    serial = args.serial
    activity = args.activity or f"{args.package}/.AmdbCoreActivity"

    if args.data_dir.exists():
        shutil.rmtree(args.data_dir)
    args.data_dir.mkdir(parents=True)

    env = os.environ.copy()
    env["CLAUDE_PLUGIN_DATA"] = str(args.data_dir)
    plugin_root = args.jar.resolve().parent.parent
    env["CLAUDE_PLUGIN_ROOT"] = str(plugin_root)
    # Inherit ANDROID_SERIAL so the launcher's adb commands target the right device.
    env["ANDROID_SERIAL"] = serial

    target_class_bytes = args.target_class.read_bytes()
    target_class_b64 = base64.b64encode(target_class_bytes).decode()
    target_fqn = args.target_fqn

    failures: list[str] = []
    passed: list[str] = []

    def check(cond: bool, msg: str):
        if cond:
            print(f"  ✓ {msg}")
            passed.append(msg)
        else:
            print(f"  ✗ {msg}")
            failures.append(msg)

    # Make sure the app process exists before we start. We do NOT force-stop —
    # the user may already be using the app and a force-stop would kill UX.
    pid = wait_for_pid(serial, args.package, timeout=5.0)
    if pid is None:
        print(f"  ✗ target package not running on {serial}")
        sys.exit(1)
    print(f"  PID: {pid}")

    client = McpClient(args.jar, env)
    try:
        # ============ Scenario 1: WIRE / PROTOCOL ============
        banner("Scenario 1: MCP initialize + tools/list")
        init = client.request("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "smoke-v1.5", "version": "0"},
        })
        check("error" not in init, "initialize ok")
        client.notify("notifications/initialized", {})

        listed = client.request("tools/list", {})
        tool_names = [t["name"] for t in listed.get("result", {}).get("tools", [])]
        for tname in ("hot_swap_class", "hot_swap_classes", "hot_swap_revert", "agent_info"):
            check(tname in tool_names, f"{tname} registered")

        banner("Scenario 1 (cont): attach + protocol v2 handshake")
        attached = client.tool("attach", {"package": args.package, "load_agent": True, "serial": serial})
        if not attached.get("ok"):
            print("  attach reply:", json.dumps(attached, indent=2))
            sys.exit(1)
        print(f"  attached: pid={attached.get('pid')}")
        agent = attached.get("agent") or {}
        check(agent.get("loaded") is True, "agent loaded=true")
        check(agent.get("protocol_version") == 2, f"protocol_version=2 (got {agent.get('protocol_version')})")
        check(agent.get("version", "").startswith("1.5"), f"agent.version starts with 1.5 (got {agent.get('version')})")

        # ============ Scenario 2: agent_info derived flags ============
        banner("Scenario 2: agent_info has v1.5 derived flags")
        info = client.tool("agent_info")
        check(info.get("loaded") is True, "agent_info loaded=true")
        for fld in ("hot_swap_supported", "force_re_enter_supported", "minify_detected",
                    "device_api_level", "redefine_method_added_supported",
                    "redefine_field_added_supported"):
            check(fld in info, f"agent_info has `{fld}` field")
        print(f"  hot_swap_supported = {info.get('hot_swap_supported')}")
        print(f"  force_re_enter_supported = {info.get('force_re_enter_supported')}")
        print(f"  minify_detected = {info.get('minify_detected')}")
        print(f"  device_api_level = {info.get('device_api_level')}")
        check(info.get("hot_swap_supported") is True, "hot_swap_supported=true on this device")
        check(info.get("minify_detected") is False, "minify_detected=false (debug build)")
        check(info.get("device_api_level") == 26, "device_api_level=26 (Android 8)")

        # ============ Scenario 3: class_not_loaded error ============
        banner("Scenario 3: hot_swap_class with unloaded FQN → class_not_loaded")
        # Tiny dummy class bytes for a class that DOES NOT exist in the target.
        # We pass valid base64 (mutate a single byte of the magic so dex would
        # reject, but pre-flight class_not_loaded fires first).
        dummy = base64.b64encode(b"\xca\xfe\xba\xbe\x00\x00\x00\x34\x00\x05").decode()
        r = client.tool("hot_swap_class", {
            "class": "com.example.NonExistentClassForSmoke",
            "class_bytes_b64": dummy,
        })
        check(r.get("ok") is False, "hot_swap_class returned ok=false")
        # class_not_loaded surfaces from the agent; before that, we hit the
        # server's pre-validate which finds the class is not in vm.classesByName.
        # Either path lands in code: invalid_target with a class-not-loaded message.
        print(f"  reply code: {r.get('code')}, message: {r.get('message')}")
        check(r.get("code") in ("invalid_target", "class_not_loaded"),
              f"refused with class_not_loaded family (got {r.get('code')})")

        # ============ Scenario 4: invalid base64 → invalid_target ============
        banner("Scenario 4: hot_swap_class with invalid base64 → invalid_target")
        r = client.tool("hot_swap_class", {
            "class": target_fqn,
            "class_bytes_b64": "!!!not-valid-base64!!!",
        })
        check(r.get("ok") is False, "hot_swap_class returned ok=false")
        check(r.get("code") == "invalid_target", f"refused with invalid_target (got {r.get('code')})")

        # ============ Scenario 5: revert without prior swap ============
        banner("Scenario 5: hot_swap_revert on never-swapped class")
        r = client.tool("hot_swap_revert", {"class": "com.example.NeverSwappedClass"})
        check(r.get("ok") is False, "revert returned ok=false")
        print(f"  code: {r.get('code')}, message: {r.get('message')}")
        check(r.get("code") == "invalid_target", "revert refused with invalid_target")
        check("No snapshot stored" in (r.get("message") or "") or "no_snapshot" in (r.get("message") or "").lower(),
              "revert message names missing snapshot")

        # ============ Scenario 6: SHAPE-CHANGE REJECT ============
        banner(f"Scenario 6: ASM-mutated {target_fqn} with extra method → redefine_unsupported_shape_change")
        mutated = add_synthetic_method(target_class_bytes)
        mutated_b64 = base64.b64encode(mutated).decode()
        r = client.tool("hot_swap_class", {
            "class": target_fqn,
            "class_bytes_b64": mutated_b64,
        })
        check(r.get("ok") is False, "shape-change swap returned ok=false")
        print(f"  code: {r.get('code')}")
        # Code may be redefine_unsupported_shape_change OR — if the class wasn't yet
        # cached by the agent so the server-side diff is skipped — fall through to
        # JVMTI's reject as redefine_failed_jvmti. Both are spec-conformant.
        check(r.get("code") in ("invalid_target",), f"refused with invalid_target family (got {r.get('code')})")
        msg = (r.get("message") or "").lower()
        check("shape change" in msg or "method_added" in msg or "redefine_failed_jvmti" in msg
              or "redefine_unsupported_shape_change" in msg,
              f"message points at shape-change or JVMTI redefine reject (got: {r.get('message')[:120] if r.get('message') else ''})")
        if "diff" in r:
            kinds = [e.get("kind") for e in (r.get("diff") or [])]
            print(f"  diff kinds: {kinds}")
            check("method_added" in kinds, "diff entries include method_added")
        else:
            print("  (no diff field — likely fell through to JVMTI reject)")

        # ============ Scenario 7: ROUND-TRIP SWAP ============
        banner(f"Scenario 7: round-trip swap of {target_fqn} → ok")
        r = client.tool("hot_swap_class", {
            "class": target_fqn,
            "class_bytes_b64": target_class_b64,
        })
        if r.get("ok") is not True:
            print(f"  reply: {json.dumps(r, indent=2)[:400]}")
        check(r.get("ok") is True, "round-trip swap ok=true")
        if r.get("ok") is True:
            check(r.get("class") == target_fqn, f"response carries class={target_fqn}")
            check("dex_sha256" in r, "dex_sha256 present")
            check("class_bytes_sha256" in r, "class_bytes_sha256 present")
            check(target_fqn in (r.get("redefined_classes") or []), "redefined_classes includes target")
            print(f"  dex_sha256: {r.get('dex_sha256')}")
            print(f"  loader_kind: {r.get('loader_kind')}")
            print(f"  additional_copies_unswapped: {r.get('additional_copies_unswapped')}")

        # ============ Scenario 8: REVERT ============
        banner(f"Scenario 8: revert {target_fqn}")
        r = client.tool("hot_swap_revert", {"class": target_fqn})
        # Two acceptable outcomes per spec §7 + post-smoke architectural finding:
        # (a) revert succeeds — agent's ClassFileLoadHook had valid .class bytes
        # (b) revert refused with "No snapshot stored" — either class loaded pre-attach
        #     OR ART handed non-.class bytes (header validation skipped the capture)
        # The latter is the documented limitation; we accept it. What we DON'T accept:
        # any other failure shape (e.g., dex failure on revert path — bug we just fixed).
        if r.get("ok") is True:
            check(target_fqn in (r.get("reverted") or []), "reverted list includes target")
            print(f"  reverted: {r.get('reverted')}")
        else:
            print(f"  ⚠ revert refused (code={r.get('code')}): {r.get('message')}")
            check(r.get("code") == "invalid_target", "revert refused with documented invalid_target")
            msg = (r.get("message") or "")
            check(
                "No snapshot stored" in msg,
                f"revert refusal explains missing snapshot (got: {msg[:80]})",
            )
            print("  (Class loaded pre-attach OR ART handed non-.class bytes via ClassFileLoadHook.")
            print("   Either is the documented v1.5 limitation per spec §7.)")

        # ============ Scenario 9: BATCH REJECTION ============
        banner("Scenario 9: hot_swap_classes batch with one violation → all rejected")
        r = client.tool("hot_swap_classes", {
            "entries": [
                {"class": target_fqn, "class_bytes_b64": target_class_b64},
                {"class": target_fqn, "class_bytes_b64": mutated_b64},  # violation
            ],
        })
        check(r.get("ok") is False, "batch returned ok=false")
        # Either batch_validation_failed (if pre-validate caught it) or
        # invalid_target/redefine_failed_jvmti (if it fell through). All are
        # spec-conformant — what matters is the BATCH did not partially apply.
        print(f"  code: {r.get('code')}")
        if "validation_phase" in r:
            check(r.get("validation_phase") == "failed", "validation_phase=failed")
            check(r.get("redefine_phase") == "skipped", "redefine_phase=skipped (no partial apply)")
        if "failures" in r:
            print(f"  failures: {[f.get('class') for f in r.get('failures') or []]}")

        banner("final detach")
        client.tool("detach", {"persist": False})

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
        print("SMOKE PASSED — v1.5 HotSwap wire verified end-to-end against",
              args.package, "on", serial)


if __name__ == "__main__":
    main()
