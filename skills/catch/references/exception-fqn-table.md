# Exception FQN resolution table + yellow-flag list

Canonical for `/android-debugger:catch`. Two responsibilities:

1. Expand a short exception name (e.g. `NullPointerException`) into a fully-qualified class name for `add_exception_breakpoint`.
2. Warn on yellow-flag exceptions that are usually expected control flow (not "a crash") — running `:catch` on these floods the user with non-bug events.

## Resolution order

When the user passes a short name (no dots), try each prefix in order. Stop at the first match (use `inspect_object` or a class-existence probe via `evaluate` if multiple ambiguously match).

1. `kotlin.*` — Kotlin-specific exceptions take precedence in modern Android codebases.
   - `NullPointerException` — Kotlin-aware throws (e.g., `!!` on a null) often surface here, not `java.lang.*`. Check this first.
   - `UninitializedPropertyAccessException` — `lateinit var` accessed before init.
   - `KotlinNullPointerException` — explicit Kotlin NPE.
   - `NotImplementedError` — `TODO()` placeholders.
   - `ClassCastException` (Kotlin's `as` cast failures land here in some compilations).
2. `kotlinx.coroutines.*` — coroutine-scoped exceptions.
   - `TimeoutCancellationException` — `withTimeout` exceeded.
   - `JobCancellationException`.
3. `kotlinx.io.*` — Kotlinx IO exceptions.
4. `java.lang.*` — JDK runtime exceptions.
   - `NullPointerException`, `IllegalStateException`, `IllegalArgumentException`, `ClassCastException`, `IndexOutOfBoundsException`, `ArithmeticException`, `RuntimeException`, `UnsupportedOperationException`.
5. `java.io.*` — IO exceptions.
   - `IOException`, `FileNotFoundException`, `EOFException`.
6. `java.util.concurrent.*` — concurrency exceptions.
   - `TimeoutException`, `CancellationException` (note: this is the **JDK** one; Kotlin coroutines uses `kotlinx.coroutines.CancellationException`, which is a subclass).
   - `ExecutionException`, `RejectedExecutionException`.
7. `android.os.*` — Android system exceptions.
   - `DeadObjectException` — Binder peer died.
   - `RemoteException` — IPC failed.
   - `TransactionTooLargeException` — Binder payload over limit.
   - `BadParcelableException`.
8. `android.content.*` — content-resolver / activity-resolution exceptions.
   - `ActivityNotFoundException` — `startActivity` with an unhandled Intent.
   - `OperationCanceledException`.

If the name is ambiguous (e.g., `CancellationException` exists in `kotlinx.coroutines`, `java.util.concurrent`, and `android.os`), ask the user which one via `AskUserQuestion`. Default to the **most specific** one matching the user's stated context (Kotlin app → coroutine variant; pure JDK code → concurrent variant).

If the user passes a fully-qualified name (contains a dot), use as-is — never re-resolve.

## Yellow-flag list

These exceptions are usually *expected control flow*, not bugs. Running `:catch` on them will flood the user with non-bug events. Warn the user before setting the breakpoint and confirm they really want it.

| Exception | Why it's expected | Confirm message |
|---|---|---|
| `kotlinx.coroutines.CancellationException` | Coroutine scope was cancelled (user navigated away, screen disposed, parent job cancelled). Kotlin coroutines throw this routinely; *not* a bug. | "`CancellationException` is normal coroutine cleanup — every screen dispose throws one. Do you specifically want to catch *uncaught* ones, or are you debugging cancellation propagation?" |
| `java.util.concurrent.CancellationException` | A `Future` / `CompletableFuture` was cancelled. Often expected on shutdown. | "`CancellationException` from the concurrent package is usually intentional cancellation. Are you sure?" |
| `java.lang.SecurityException` | Permission denied — often expected on permission-gated calls before grant. | "`SecurityException` often fires before runtime permissions are granted. Do you want to catch it across the entire session, or after a specific user action?" |
| `android.content.ActivityNotFoundException` | `startActivity` with a deep-link Intent that no app on the device can resolve. | "`ActivityNotFoundException` fires when no app can handle the Intent — sometimes expected (the app falls back to a browser). Confirm?" |
| `android.os.DeadObjectException` | Binder peer process died (e.g., system service restarted). Usually transient and retried. | "`DeadObjectException` is often transient (system service restart). Are you debugging a specific case, or all of them?" |

The skill should print one short warning line and then proceed if the user confirms. Don't *block* — the user might have a legitimate reason to catch yellow-flag exceptions (e.g., suspected leak of cancellation across a scope boundary).

## File-sharing note

Read by `/android-debugger:catch` step 2 (resolution) and step 2.5 (yellow-flag check). Update here when new exceptions surface in support reports.
