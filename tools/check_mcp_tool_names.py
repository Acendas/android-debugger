#!/usr/bin/env python3
"""Verify skill/agent MCP tool references match the server.

Device-free, dependency-free. Run before committing any change to skills/,
agents/, .mcp.json, or plugin.json:

    python3 tools/check_mcp_tool_names.py

Two checks:

1. **Scoping.** Claude Code namespaces a plugin's bundled MCP server by
   plugin name, so tools are `mcp__plugin_<plugin>_<server>__<tool>`. The
   bare `mcp__<server>__<tool>` form names nothing.

   This failure is inert, not loud: an `allowed-tools` entry matching no
   real tool grants nothing and raises no error. The skill looks correct,
   nothing errors at load, and the only symptom is that every call — read-
   only ones included — falls through to the permission classifier and gets
   blocked or prompts. atlassian-suite shipped this bug across all 64 of its
   skills and agents before a customer hit it.

2. **Cross-reference.** Every referenced tool must exist as a
   `server.addTool(name = "...")` in the Kotlin source, catching the
   "renamed a tool, forgot the skill" regression.

Exit 0 on pass, 1 on failure.
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN_SRC = ROOT / "server" / "src" / "main" / "kotlin"
SCANNED_DIRS = ("skills", "agents")

# Tools reach the server two ways. Most are registered inline:
#     server.addTool(name = "frame_snapshot", ...)
# A few go through a helper that forwards a literal name, e.g. ExecutionTools'
#     registerStep(server, "step_over", Stepper.Depth.Over, ...)
#       -> server.addTool(name = name, ...)
# Missing the second shape would flag step_over/step_into/step_out — real,
# working tools — as dangling references, so collect both.
ADD_TOOL_RE = re.compile(r'server\.addTool\s*\(\s*\n?\s*name\s*=\s*"([a-zA-Z_][a-zA-Z0-9_]*)"')
HELPER_REG_RE = re.compile(r'\w+\s*\(\s*server\s*,\s*"([a-zA-Z_][a-zA-Z0-9_]*)"')


def manifest_names():
    plugin = json.loads(
        (ROOT / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8")
    )["name"]
    server = next(
        iter(json.loads((ROOT / ".mcp.json").read_text(encoding="utf-8"))["mcpServers"])
    )
    return plugin, server


def registered_tools():
    tools = set()
    if not KOTLIN_SRC.exists():
        return tools
    for kt in KOTLIN_SRC.rglob("*.kt"):
        text = kt.read_text(encoding="utf-8")
        tools.update(ADD_TOOL_RE.findall(text))
        tools.update(HELPER_REG_RE.findall(text))
    return tools


def markdown_files():
    for sub in SCANNED_DIRS:
        base = ROOT / sub
        if base.exists():
            yield from sorted(base.rglob("*.md"))


def main():
    plugin, server = manifest_names()
    scoped_prefix = f"mcp__plugin_{plugin}_{server}__"
    unscoped_re = re.compile(r"(?<!plugin_)mcp__" + re.escape(server) + r"__")
    scoped_re = re.compile(re.escape(scoped_prefix) + r"([a-zA-Z_][a-zA-Z0-9_]*)")

    failures = []

    # Check 1 — scoping.
    for path in markdown_files():
        hits = len(unscoped_re.findall(path.read_text(encoding="utf-8")))
        if hits:
            failures.append(
                f"{path.relative_to(ROOT)}: {hits} unscoped `mcp__{server}__` "
                f"reference(s) — these match no real tool, so allowed-tools "
                f"grants nothing. Use {scoped_prefix}<tool>."
            )

    # Check 2 — cross-reference against the Kotlin server.
    registered = registered_tools()
    if not registered:
        failures.append(
            f"no `server.addTool(name = \"...\")` registrations found under "
            f"{KOTLIN_SRC.relative_to(ROOT)} — cannot cross-reference tool names"
        )
    else:
        for path in markdown_files():
            referenced = set(scoped_re.findall(path.read_text(encoding="utf-8")))
            for tool in sorted(referenced - registered):
                failures.append(
                    f"{path.relative_to(ROOT)}: references {tool!r} but no "
                    f"server.addTool(name = \"{tool}\") exists"
                )

    if failures:
        print("FAIL — MCP tool name check")
        for f in failures:
            print(f"  {f}")
        return 1

    print(f"PASS — MCP tool names scoped as {scoped_prefix}* "
          f"and cross-referenced against {len(registered)} registered tools")
    return 0


if __name__ == "__main__":
    sys.exit(main())
