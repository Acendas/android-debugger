# Framework-frame collapse list

Canonical allowlist for collapsing noisy framework frames in `frame_snapshot` output. Read this from `/android-debugger:explain` (collapse rule for the rendered frame chain) and `/android-debugger:walk` (default `step_into` class-exclusion filters).

The list is broad — modern Android stacks include code from at least a dozen DI/UI/Compose/JDK packages that the user doesn't write or own. Surfacing them by default buries the user-code frame they actually care about under 30 lines of `androidx.lifecycle.runtime.compose.SaveableStateHolderImpl` noise.

## Prefix list (collapse adjacent runs of frames whose declaring class matches any of these)

- `java.*`
- `javax.*`
- `android.*`
- `androidx.*`
- `kotlin.*`
- `kotlinx.*`
- `com.android.*`
- `com.google.android.*`
- `dagger.*`
- `dagger.hilt.*`
- `hilt_aggregated_deps.*`
- `com.sun.*`
- `sun.*`
- `jdk.*`
- `dalvik.*`
- `libcore.*`

## Rule

Collapse any **adjacent run** of frames whose declaring class matches any of these prefixes into a single line: `... <N> framework frames (<top-pkg>) ...`. Keep:

- The **top frame** even if it matches a prefix (the user wants to know "we're paused inside `kotlinx.coroutines.JobImpl.cancel`").
- The **first non-framework frame** *immediately above* the collapsed run (the user-code caller — usually the actually-interesting frame).
- The **first non-framework frame** *immediately below* the collapsed run, if any.

This surfaces "user code → big framework chunk → user code" as three rendered frames instead of forty.

## Example

Before (raw 12-frame stack):

```
0  com.example.app.LoginVm$signIn$1.invokeSuspend (LoginVm.kt:84)
1  kotlinx.coroutines.intrinsics.UndispatchedKt.startCoroutineUndispatched (...)
2  kotlinx.coroutines.BuildersKt__Builders_commonKt.async (...)
3  kotlinx.coroutines.BuildersKt.async$default (...)
4  androidx.lifecycle.viewmodel.compose.SaveableStateHolderImpl... (...)
5  androidx.compose.runtime.ComposerImpl.startGroup (...)
6  androidx.compose.runtime.SnapshotStateKt.derivedStateOf (...)
7  com.example.app.LoginScreenKt$LoginScreen.invoke (LoginScreen.kt:42)
8  androidx.compose.runtime.RecomposeScopeImpl.invalidate (...)
9  androidx.compose.runtime.ComposerImpl.recompose (...)
10 com.example.app.MainActivityKt$onCreate.invoke (MainActivity.kt:18)
11 android.app.ActivityThread.handleResumeActivity (...)
```

After collapse:

```
0  com.example.app.LoginVm$signIn$1.invokeSuspend (LoginVm.kt:84)
   ... 6 framework frames (kotlinx.coroutines + androidx.compose) ...
7  com.example.app.LoginScreenKt$LoginScreen.invoke (LoginScreen.kt:42)
   ... 2 framework frames (androidx.compose) ...
10 com.example.app.MainActivityKt$onCreate.invoke (MainActivity.kt:18)
   ... 1 framework frame (android.app) ...
```

Three user-code frames stand out; the framework noise is summarized.

## Walk skill: same prefixes for step-filter defaults

The `:walk` skill's "don't auto-step-into framework code" rule uses the **same list** as the `step_into` class-exclusion filter. If a prefix is added/removed here, both behaviors track. The only carve-out is the C-10 generated-proxy carve-out (Hilt/Dagger factories) — those classes match `*_HiltModules*` / `*_Factory` / `*_Provider` and are stepped *into-then-out-of* to skip the synthetic frame, separate from this list.
