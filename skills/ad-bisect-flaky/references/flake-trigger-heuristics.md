# Flake trigger heuristics + bisect-ability preflight

Canonical for `/android-debugger:ad-bisect-flaky`. Two responsibilities:

1. **Preflight (B-02):** decide whether the flake is breakpoint-bisectable at all, or whether it's a timing-window flake that needs a logpoint sweep instead.
2. **Ranked patterns (C-12):** when bisectable, where to place the 3–5 candidate breakpoints — ordered by likelihood of being the trigger.

## Preflight: is this actually bisectable?

Breakpoints can capture state at a chosen line, but they can't capture *timing*. Flakes whose root cause is "the `IdlingResource` released before the assertion ran" or "GC paused for 80ms in the wrong place" don't have a single line that's wrong — they have a window that closed. Setting breakpoints on those flakes turns the flake into a non-flake (the bp itself fixes the timing) and you'll never see it fail.

### Timing-flake signatures

If **any** of these are true, route to logpoint sweep instead of breakpoint bisect:

- The user reports "the test passes more often when I run it in the debugger" — classic timing-window-closed-by-bp signal.
- The error message names `IdlingResource`, `EspressoIdling`, or contains `idle policy was violated`.
- The captured frames include `kotlinx.coroutines.scheduling.CoroutineScheduler` or `Dispatchers.Default` and the failure is intermittent under load.
- The test asserts on a value within a fixed timeout (`runBlocking(timeout = ...)`, `await(timeout = ...)`) and the timeout is short (<1s).
- The test has `Thread.sleep(...)` or `delay(...)` calls in the test body or in the system-under-test path.
- The test uses `System.currentTimeMillis()` / `Clock.systemDefaultZone()` / `Instant.now()` in a comparison.
- The captured stack involves `RxJava` and `subscribeOn` / `observeOn` are mixed across the call chain.

### Logpoint-sweep fallback (when not bisectable)

When the flake matches a timing signature:

1. **Don't set breakpoints.** They'll likely close the timing window and the test will pass deterministically under the debugger.
2. **Place logpoints across timing-sensitive entries:** every dispatcher hop (`withContext(Dispatchers.IO)` call sites), every `delay()` / `sleep()` / `IdlingResource.dec`, every `currentTimeMillis()` read.
3. **Run the test 10–20 times** and harvest the logcat timeline. Look for ordering anomalies between pass and fail runs — the divergent pair of timestamps is the timing window.
4. **Report the timing window**, not a single line — flake fix shapes for timing windows are usually "swap the dispatcher", "use `runTest` with virtual time", or "add a coordinating IdlingResource".

## Ranked candidate patterns (when bisectable)

When the flake is *not* a timing-window flake, place 3–5 breakpoints at lines matching these patterns, in ranked order. Rank means "place the highest-ranked match first, fall down the list if none match".

1. **Shared mutable state across tests.** Static fields, singleton repositories, `@Inject` singletons that persist across `@Test` methods. Look for `companion object`, `object Foo`, `@Singleton` annotations. If found in the system-under-test, place a bp on every assignment site.
2. **`delay()` / `Thread.sleep()` calls.** A test that sleeps to "wait for" async work is racing against the actual timing. Bp on the line *after* the sleep — capture the state when the sleep ended.
3. **`Dispatchers.Default` mixing.** A coroutine launched on `Dispatchers.Default` reads/writes shared state without synchronization. Bp on the read site and the write site.
4. **`System.currentTimeMillis()` / `Clock.systemDefaultZone()` reads.** Tests using wall-clock time are flaky around DST changes, leap seconds, and CI clock drift. Bp on the comparison site (the `if` / `assert` using the clock value).
5. **AndroidX `IdlingResource` starvation.** If the test uses `IdlingRegistry`, check whether the resource is properly registered/unregistered. Bp on `IdlingResource.dec()`.
6. **`SharedPreferences MODE_MULTI_PROCESS`** (uncommon but real on multi-process apps). Bp on every `getSharedPreferences` call to confirm the mode flag.
7. **RxJava `subscribeOn` / `observeOn` mixing.** Bp on the chain construction site to confirm the scheduler hierarchy.

## Runner-FQN derivation hint (C-13)

For step 4 of the skill (the `am instrument` command), the user needs the runner FQN. If they don't know it, point at the AGP-built manifest:

```
app/build/intermediates/manifest_merge_blame_file/androidTest*/AndroidManifest.xml
```

Look for `<instrumentation android:name="..."/>`. The default modern AGP value is `androidx.test.runner.AndroidJUnitRunner`. Custom runners exist (Hilt: `dagger.hilt.android.testing.HiltTestApplication`-paired runner; in-house: `com.example.app.MyTestRunner`).

The `<instrumentation>` element is also in the merged manifest if the user has built any androidTest variant; if the directory is missing, a `./gradlew :app:processDebugAndroidTestManifest` produces it.

## File-sharing note

Read by `/android-debugger:ad-bisect-flaky` step 0 (preflight) and step 3 (candidate selection). Update when new flake patterns appear in customer reports — cite the report in the commit so future maintainers know why the entry exists.
