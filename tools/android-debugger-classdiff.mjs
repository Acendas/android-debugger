#!/usr/bin/env node
// android-debugger-classdiff — walk a build dir's .class files, hash each, and
// report which changed between a pre-snapshot and a post-build state.
//
// Used by the `/android-debugger:patch` skill to identify exactly which .class
// files need to be HotSwap'd after a Gradle incremental compile.
//
// Cross-platform: Node 18+. No external deps. Companion `.sh` and `.cmd`
// wrappers let skill bash blocks invoke `android-debugger-classdiff` as a bare
// command without resolving the .mjs path manually.
//
// Subcommands:
//
//   snapshot --root <dir>
//     Walk <dir> recursively for *.class files. Compute SHA-256 of each. Write
//     the result to $TMPDIR/android-debugger/classdiff-<sessionId>.json and
//     print the path on stdout. Errors go to stderr; non-zero exit on failure.
//
//   diff --before <snapshot-path> --root <dir>
//     Re-walk <dir>, hash, compare to <snapshot-path>. Print JSON to stdout:
//       { changed: [{ fqn, class_path, sha256_before?, sha256_after }],
//         added:   [{ fqn, class_path, sha256_after }],
//         removed: [{ fqn, class_path, sha256_before }] }
//
//   help
//     Print this usage block.

import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, resolve, sep } from 'node:path';
import { tmpdir } from 'node:os';

function usage() {
  process.stderr.write(`Usage:
  android-debugger-classdiff snapshot --root <dir>
  android-debugger-classdiff diff --before <snapshot> --root <dir>
  android-debugger-classdiff help
`);
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const k = a.slice(2);
      const v = argv[i + 1];
      if (v && !v.startsWith('--')) {
        out[k] = v;
        i++;
      } else {
        out[k] = true;
      }
    }
  }
  return out;
}

function walkClasses(root, out, base) {
  let entries;
  try {
    entries = readdirSync(root, { withFileTypes: true });
  } catch (e) {
    return;
  }
  for (const e of entries) {
    const p = join(root, e.name);
    if (e.isDirectory()) {
      walkClasses(p, out, base);
    } else if (e.isFile() && e.name.endsWith('.class')) {
      const rel = p.slice(base.length + (base.endsWith(sep) ? 0 : 1));
      const fqn = rel.replace(/[\\/]/g, '.').replace(/\.class$/, '');
      const bytes = readFileSync(p);
      const sha = createHash('sha256').update(bytes).digest('hex');
      out[fqn] = { class_path: p, sha256: sha };
    }
  }
}

function classMap(root) {
  const map = {};
  const abs = resolve(root);
  walkClasses(abs, map, abs);
  return map;
}

function snapshot(root) {
  if (!existsSync(root)) {
    process.stderr.write(`error: --root not found: ${root}\n`);
    process.exit(2);
  }
  const map = classMap(root);
  const dir = join(tmpdir(), 'android-debugger');
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  const sessionId = `${process.pid}-${Date.now()}`;
  const outPath = join(dir, `classdiff-${sessionId}.json`);
  writeFileSync(outPath, JSON.stringify({
    root: resolve(root),
    captured_at: new Date().toISOString(),
    class_count: Object.keys(map).length,
    classes: map,
  }, null, 2));
  process.stdout.write(outPath + '\n');
}

function diff(beforePath, root) {
  if (!existsSync(beforePath)) {
    process.stderr.write(`error: --before snapshot not found: ${beforePath}\n`);
    process.exit(2);
  }
  if (!existsSync(root)) {
    process.stderr.write(`error: --root not found: ${root}\n`);
    process.exit(2);
  }
  const before = JSON.parse(readFileSync(beforePath, 'utf8'));
  const after = classMap(root);
  const changed = [];
  const added = [];
  const removed = [];
  // Iterate after to find changed + added.
  for (const fqn of Object.keys(after)) {
    const a = after[fqn];
    const b = before.classes[fqn];
    if (!b) {
      added.push({ fqn, class_path: a.class_path, sha256_after: a.sha256 });
    } else if (b.sha256 !== a.sha256) {
      changed.push({
        fqn,
        class_path: a.class_path,
        sha256_before: b.sha256,
        sha256_after: a.sha256,
      });
    }
  }
  // Iterate before to find removed.
  for (const fqn of Object.keys(before.classes)) {
    if (!after[fqn]) {
      removed.push({ fqn, class_path: before.classes[fqn].class_path, sha256_before: before.classes[fqn].sha256 });
    }
  }
  process.stdout.write(JSON.stringify({
    root: resolve(root),
    before: beforePath,
    after_class_count: Object.keys(after).length,
    changed,
    added,
    removed,
  }, null, 2) + '\n');
}

function main() {
  const argv = process.argv.slice(2);
  if (argv.length === 0 || argv[0] === 'help' || argv[0] === '--help' || argv[0] === '-h') {
    usage();
    process.exit(argv.length === 0 ? 1 : 0);
  }
  const sub = argv[0];
  const args = parseArgs(argv.slice(1));
  if (sub === 'snapshot') {
    if (!args.root) {
      process.stderr.write('error: snapshot requires --root <dir>\n');
      process.exit(2);
    }
    snapshot(args.root);
  } else if (sub === 'diff') {
    if (!args.before || !args.root) {
      process.stderr.write('error: diff requires --before <snapshot> --root <dir>\n');
      process.exit(2);
    }
    diff(args.before, args.root);
  } else {
    process.stderr.write(`error: unknown subcommand '${sub}'\n`);
    usage();
    process.exit(2);
  }
}

main();
