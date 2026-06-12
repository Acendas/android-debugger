---
name: ad-graph
description: Generate static-analysis graphs as ASCII or Mermaid.
model: sonnet
allowed-tools: AskUserQuestion, Glob, Grep, Read, Write, Bash, mcp__android-debugger__connection_status, mcp__android-debugger__agent_info, mcp__android-debugger__get_app_info, mcp__android-debugger__static_class_hierarchy, mcp__android-debugger__static_call_graph, mcp__android-debugger__static_cfg, mcp__android-debugger__static_package_graph
---

# Graph — static-analysis graphs (class hierarchy, call graph, CFG, package graph)

Standalone, read-only structural analysis backed by SootUp 2.0.0 over compiled `.class` output. No `attach`, no live device, no Debug Plan — this skill answers "what does the code look like" questions, not "what is the code doing right now" questions.

Use this for "show me the call graph for X", "what implements OnClickListener", "what calls Y", "class hierarchy of Foo", "how is this package organized", "control flow of method Z". If the user's request carries a runtime symptom ("...because it returns the wrong value"), this isn't the right skill — point them at `/android-debugger:ad-investigate` instead.

## Step 1 — Triage: graph kind + target

Classify the request into one of four kinds and extract the target:

| Kind | Tool | Target shape |
|---|---|---|
| Class hierarchy ("what extends/implements X", "superclasses of Y") | `static_class_hierarchy` | a class FQN, e.g. `com.example.login.LoginActivity` |
| Call graph ("what calls X", "what does X call", "callers/callees of Y") | `static_call_graph` | a method — FQN class + method name (+ params if known) |
| Control-flow graph ("CFG of X", "control flow of method Y", "show me the branches in Z") | `static_cfg` | a single method, same shape as call graph |
| Package graph ("how is this package organized", "dependencies between packages") | `static_package_graph` | a package name, e.g. `com.example.app.feature.login` |

If the user names a class or method that doesn't obviously map to a kind ("tell me about LoginActivity"), default to `static_class_hierarchy` for a bare class and `static_call_graph` (`direction: both`) for a bare method — these are the cheapest, most generally useful views.

If the target itself is ambiguous (user said "the login method" and multiple classes have one), ask once via `AskUserQuestion` with the candidates, or narrow using `Grep` over the project source first.

## Step 2 — Resolve `class_dirs`

Every tool requires `class_dirs`: directories of compiled `.class` output. Read `references/class-dir-discovery.md` for the full Glob-pattern table across AGP/Gradle layouts — it covers single-module and multi-module projects, and the older-vs-newer AGP path shapes.

Collect across **every module**, not just `:app`. Run the Glob patterns from the reference rooted at the project root, dedupe, and keep only directories that actually exist and contain `.class` files (an empty `kotlin-classes` dir from a Java-only module is common and harmless to include, but don't pass nonexistent paths).

If after globbing `class_dirs` is empty, don't call any tool yet — tell the user no compiled output was found and suggest `./gradlew assembleDebug` (or the specific variant), matching the `class_dirs_empty_or_missing` hint the tools themselves would give (`build/intermediates/javac/debug/classes`, `build/tmp/kotlin-classes/debug`).

## Step 3 — Resolve `android_api_level` (optional)

Improves resolution of `android.jar`-only types (framework superclasses, interfaces, etc.) but every tool works without it — an unresolved level just means more `warnings` about unresolved Android framework types.

1. If a debugger session is currently attached (`mcp__android-debugger__connection_status` → `attached: true`), call `mcp__android-debugger__agent_info` or `mcp__android-debugger__get_app_info` and use the target/device SDK level it reports.
2. Otherwise, `Grep` the project's `build.gradle` / `build.gradle.kts` files for `compileSdk` or `targetSdkVersion` and use the highest value found across modules.
3. If neither source yields a level, omit `android_api_level` entirely — don't guess a number.

Pass the resolved value as `android_api_level` on whichever tool you call in Step 5.

## Step 4 — Staleness check (skill-side, non-fatal)

Before calling the tool, do a cheap freshness sanity check so the user isn't shown a graph of stale code:

1. For each module whose `class_dirs` you're including, find the newest mtime under `src/main/java` and `src/main/kotlin`.
2. Compare against the newest mtime among the `.class` files in that module's resolved `class_dirs`.
3. If source is newer than the compiled output, surface a one-line warning: "Heads up: `<file>` was modified after the last build — this graph may not reflect it. Want me to run `./gradlew compile<Variant>Kotlin compile<Variant>JavaWithJavac` (or `assembleDebug`) first?"

This is advisory only — don't block on it, and don't run the build yourself unless the user says yes.

## Step 5 — Call the matching tool

Call exactly one of the four tools with `class_dirs` (+ `android_api_level` if resolved) plus the kind-specific params from the table below.

### Tool reference

All four tools also accept optional `extra_classpath` (list of jar/dir paths — kotlin-stdlib, AndroidX, etc.) and `android_jar` (explicit path, takes precedence over `android_api_level`).

- **`static_class_hierarchy({ class_dirs, target_class, direction?, depth?, node_cap?, collapse_synthetic? })`**
  `target_class` is a FQN string. `direction`: `up` (superclass + interfaces) | `down` (subtypes/implementers) | `both` (default). `depth` default 3, `node_cap` default 40, `collapse_synthetic` default true (folds Kotlin companions/lambdas/`DefaultImpls`/coroutine continuations into the enclosing class).
  Errors: `class_not_found`, `class_dirs_empty_or_missing`.

- **`static_call_graph({ class_dirs, target_method, direction?, depth?, node_cap?, max_dispatch_targets?, collapse_synthetic? })`**
  `target_method` is either a canonical SootUp signature string `<pkg.Class: returnType name(paramType,...)>` or `{class, name, params?}`. `direction`: `callers` | `callees` | `both` (default). `depth` default 3, `node_cap` default 40, `max_dispatch_targets` default 5 (virtual/interface call sites expand to concrete overrides up to this many; overflow becomes one "+N more implementers" node), `collapse_synthetic` default true.
  Errors: `method_not_found`, `class_not_found`, `method_ambiguous` (carries `candidates`), `class_dirs_empty_or_missing`.

- **`static_cfg({ class_dirs, target_method, node_cap? })`**
  One node per basic block; edges labeled `true`/`false` (if-branches), fallthrough, or `catch <ExceptionType>`. `target_method` same shape/errors as call graph. `node_cap` default 100 (bounds basic-block count, not statement count). Not synthetic-collapsed — nodes are blocks within one method.
  Errors: `method_not_found`, `class_not_found`, `method_ambiguous`, `class_dirs_empty_or_missing`.

- **`static_package_graph({ class_dirs, root_package, depth?, node_cap?, collapse_synthetic? })`**
  `root_package` is a Java/Kotlin package name. `depth` (default 3) bounds sub-package segments below `root_package`. `node_cap` default 40, `collapse_synthetic` default true.
  Errors: `class_not_found` (no application classes under `root_package` within `depth`), `class_dirs_empty_or_missing`.

### Handling `method_ambiguous`

If `static_call_graph` or `static_cfg` returns `method_ambiguous`, the response carries a `candidates` array of signature strings. If the user's request makes one candidate obviously correct (matching arity, a parameter type they mentioned), retry immediately with `target_method: { class, name, params: [...] }` using that candidate's params. Otherwise present the candidates via `AskUserQuestion` and retry with the chosen one.

## Step 6 — Render for the human

The response always has the shape `{ ok: true, root_id, nodes, edges, truncated, elided_count, warnings, ascii, mermaid }` (or `{ ok: false, code, message, hint, ... }`).

Show the `ascii` field directly in your reply — it's the terminal-friendly view and needs no further formatting. Don't re-derive your own ASCII rendering from `nodes`/`edges`.

If the user wants the diagram persisted (asks to "save it", "put it in docs", or similar), read `references/mermaid-persistence.md` and follow the confirm-then-write flow there: propose a default path, show the `mermaid` content in a fenced block, get explicit confirmation, then `Write`.

## Step 7 — Reason from `nodes`/`edges`, not `ascii`

Your own narration — counts, "the hierarchy has N subclasses", "this method has 3 callers", conclusions about structure — comes from the structured `nodes`/`edges`/`truncated`/`warnings` fields. `ascii` and `mermaid` are presentation outputs for the human; don't parse them back to understand the graph yourself.

Surface every entry in `warnings` verbatim — don't paraphrase or drop them. Common ones: unresolved type references (often from a missing `android_jar`/`extra_classpath`), synthetic-collapse counts, truncation notices, minified-build notices. If `truncated: true`, tell the user how many nodes were elided (`elided_count`) and that they can narrow with a smaller `depth`/larger `node_cap`/more specific target to see more.

## What you do NOT do

- Do not `attach` or start a debug session — this skill is fully static.
- Do not call more than one of the four tools per user request unless they explicitly ask for multiple views (e.g., "show me both the hierarchy and the call graph").
- Do not write files without explicit confirmation (Step 6 / `references/mermaid-persistence.md`).
- Do not invent class/method/package names that aren't in `nodes` — if the target doesn't resolve, surface the tool's error (`class_not_found`, `method_not_found`) and its `hint` rather than guessing a near-miss name.
