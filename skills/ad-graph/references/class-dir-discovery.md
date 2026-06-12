# Class-dir discovery — Glob patterns for compiled `.class` output

`class_dirs` is required by all four `static_*` tools. This reference lists the `Glob` patterns to run, rooted at the project root, to find compiled output across common AGP/Gradle layouts. Run all of these (they're cheap globs) and dedupe — don't try to guess which AGP version a project uses ahead of time.

## Multi-module awareness

Every pattern below is written with a leading `**/` so it matches inside every module's `build/` directory, not just `:app`'s. A typical multi-module project (`:app`, `:core:network`, `:feature:login`, ...) has its own `build/` per module — glob across all of them and union the results. Don't special-case `:app`.

## Kotlin output (AGP, all versions)

```
**/build/tmp/kotlin-classes/debug/**
**/build/tmp/kotlin-classes/<variant>/**
```

`<variant>` is whatever build variant the user cares about (`release`, `debugMinified`, flavor-prefixed like `freeDebug`). Default to `debug` unless the user names a variant. This path has been stable across AGP versions — no version-specific variation here.

## Java output — AGP version variation

Java (`javac`) output location changed shape across AGP versions:

**Older AGP (≈4.x and earlier):**
```
**/build/intermediates/javac/<variant>/classes/**
```

**Newer AGP (≈7.x+):** the javac task name is embedded in the path:
```
**/build/intermediates/javac/<variant>/compile<Variant>JavaWithJavac/classes/**
```

where `<Variant>` is the capitalized variant name (e.g. `compileDebugJavaWithJavac` for `debug`).

Run both glob shapes — the project's AGP version isn't known ahead of time, and globbing for a path that doesn't exist is a no-op (returns nothing, doesn't error).

## Canonical two-path set (matches the tools' own error hint)

When `class_dirs` ends up empty, the tools' `class_dirs_empty_or_missing` error names these two paths as the canonical example — use them as the baseline glob set before adding variant-specific variations:

```
**/build/intermediates/javac/debug/classes/**
**/build/tmp/kotlin-classes/debug/**
```

## Putting it together

Suggested glob set for the default (`debug`) variant, run from the project root:

```
**/build/intermediates/javac/debug/classes
**/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
**/build/tmp/kotlin-classes/debug
```

For each match: confirm the directory exists and contains at least one file before including it in `class_dirs` (an empty `kotlin-classes` dir in a Java-only module is normal — including it is harmless, but don't pass paths that don't exist on disk at all, since that's what triggers `class_dirs_empty_or_missing`).

## If nothing matches

Tell the user no compiled output was found for the requested variant and suggest the relevant build task:

- `./gradlew assembleDebug` (builds everything for the debug variant), or
- `./gradlew compileDebugKotlin compileDebugJavaWithJavac` (compile-only, faster, skips packaging) for just refreshing `.class` output.

Don't run the build yourself — surface the suggestion and let the user decide (same discipline as the Step 4 staleness check in `SKILL.md`).
