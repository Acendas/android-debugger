# Reactive codepaths — recognition + strategy

Canonical for X-02. Read this from `/android-debugger:ad-trace` (logpoint placement strategy), `/android-debugger:ad-walk` (narration unit), and `/android-debugger:ad-bisect-flaky` (where flake-trigger logpoints go for reactive code).

The skills' default mental model is imperative: a value is *written* in one method and *read* in another, and breakpoints / logpoints land at writers and readers. This is wrong for modern Kotlin Android. `Flow`, `StateFlow`, Compose `derivedStateOf`/`collectAsState`, and `suspend` functions move values through *operators* and *suspension boundaries* that aren't method calls. Standing at a writer or reader misses the path entirely.

## How to recognize reactive code

The codepath is reactive (and the imperative strategy is wrong) if **any** of these signals are present:

- `Continuation.resume*` frames in the `frame_snapshot` stack — the function is a suspend function, currently resumed on whatever dispatcher the caller chose.
- `Recomposer` / `RecomposeScopeImpl` / `Composer.recompose` in the stack — Jetpack Compose recomposition is driving the call.
- `Flow.collect` / `FlowKt__CollectKt` / `StateFlowImpl.value` / `MutableStateFlow.emit` in the stack — value is moving through a Flow.
- Operator names in the call chain: `.combine`, `.flatMapLatest`, `.distinctUntilChanged`, `.stateIn`, `.shareIn`, `.collectLatest`, `.debounce`.
- The user's project has `import kotlinx.coroutines.flow.*` near the suspect file and the suspect type is a `Flow<T>` / `StateFlow<T>` / `MutableStateFlow<T>`.
- The suspect call is annotated with `@Composable`.

If none of these match, fall through to the imperative strategy (the regular `:trace` / `:walk` / `:bisect-flaky` body).

## Per-shape strategy

### Flow / StateFlow / SharedFlow

Imperative "writer" and "reader" don't exist — values flow through operators between the source and the terminal collector, possibly across multiple operators that transform/combine them.

- **Place a logpoint at every `emit(...)` call in the Flow source.** This captures every value the source produced.
- **Place a logpoint at every `.collect { ... }` terminal** (and at every `collectAsState` / `collectAsStateWithLifecycle` call site in Compose). This captures every value the consumer saw.
- **Diff source vs. terminal.** If a value emitted at the source never appears at a terminal, the loss happened in an intermediate operator (`distinctUntilChanged` swallowed a duplicate, `flatMapLatest` cancelled the chain, `combine` is waiting for a partner Flow that never produced). Place additional logpoints at the operator's internal map function to bisect.
- **Don't logpoint at the Flow's `.value` getter** — `StateFlow.value` reads the latest cached value but doesn't trigger emission; you'll see *stale* values. The emit() and collect() sites are the real boundaries.

### Compose recomposition

State observation in Compose happens via `MutableState` / `derivedStateOf` / `collectAsState`. A composable function re-runs when any state it reads changes. Tracing what recomposed *and why* needs a different unit than method calls.

- **Place a logpoint at every `derivedStateOf { ... }` block** to capture each derivation cycle.
- **Place a logpoint at every `LaunchedEffect(...)` and `DisposableEffect(...)`** to capture each effect rerun (these run when their key changes).
- **Use recomposition counts as a secondary signal.** A `currentRecomposeScope.invalidate()` call surfaced by snapshot is the user-facing trigger; logpoint at the call site rather than at the composable function entry.
- **Don't logpoint at the composable function's first line** — that fires on every recomposition (which can be hundreds per frame); the resulting log flood is unreadable. Logpoint at the *cause* (state read, effect, derivation) instead.

### Suspend functions / coroutines

A suspend function can resume on a different dispatcher than it was invoked on. "Method changed" is the wrong narration unit because resumption is technically *the same method* continuing — but the surrounding dispatcher and the timing have changed.

- **Narrate at suspension boundaries, not method boundaries.** When the next frame contains `Continuation.resume*` or `BaseContinuationImpl.resumeWith`, the previous suspension point just resumed — narrate "resumed from suspend at `<call site>`" rather than "stepped into `resumeWith`".
- **Logpoint at the *suspending call sites*** (not the suspend function itself). E.g., for `val x = repo.fetchUser()`, place the logpoint on the line *after* `fetchUser()` so you capture the post-suspend continuation; this captures the actual resumption value.
- **For dispatcher tracking, capture `Thread.currentThread().name` in the log message.** Coroutines reschedule across `Dispatchers.Main` / `Dispatchers.IO` / `Dispatchers.Default`, and "the value got mutated on the wrong thread" is a common flake root — see `bisect-flaky/references/flake-trigger-heuristics.md`.

## Fall-through to imperative

If none of the recognition signals match, or you've placed reactive-aware logpoints and they didn't capture the path, fall through to the regular imperative strategy: logpoint at writers and readers of the suspect value, harvest the timeline, look for ordering/missing/repeated entries.

The reactive strategies are a refinement over imperative, not a replacement — when in doubt, place imperative logpoints first and add reactive ones if the timeline doesn't capture the divergence.

## File-sharing note

This file is read by:

- `/android-debugger:ad-trace` (step 2.5: check before placing logpoints).
- `/android-debugger:ad-walk` (when `Continuation.resume*` appears in the stack).
- `/android-debugger:ad-bisect-flaky` (when the suspect codepath is reactive).
