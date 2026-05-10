package com.acendas.androiddebugger.inspection

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per BR-02 (v1.1.1 skill craft review): the Evaluator refuses calls that look like
 * state mutators unless the caller passes `allow_mutation: true`.
 *
 * The actual `invokeMethod` machinery lives behind JDI; we don't reach into a live VM.
 * Instead we cover the **classification predicate** [Evaluator.isLikelyMutator], which
 * is the load-bearing piece — once classification is right, the wiring (refuse before
 * invoke) is mechanical and covered by the integration tests in :catch.
 */
class EvaluatorMutationRefusalTest {

    @Test
    fun set_prefix_with_camelcase_boundary_is_mutator() {
        // `setName`, `setEnabled`, `setBrightnessOverride` — all the canonical Java
        // setter shapes. `set` followed by an uppercase letter.
        assertTrue(Evaluator.isLikelyMutator("setName"))
        assertTrue(Evaluator.isLikelyMutator("setEnabled"))
        assertTrue(Evaluator.isLikelyMutator("setBrightnessOverride"))
    }

    @Test
    fun set_prefix_with_underscore_boundary_is_mutator() {
        // Some Android Java code uses snake_case field setters. `set_name` and friends
        // should refuse so the pattern catches both Java and Kotlin styles.
        assertTrue(Evaluator.isLikelyMutator("set_name"))
        assertTrue(Evaluator.isLikelyMutator("clear_cache"))
    }

    @Test
    fun set_prefix_without_boundary_is_not_a_mutator() {
        // `setting` and `settings` don't have the camelcase / underscore boundary so
        // they're plain identifier names, not setter idioms.
        assertFalse(Evaluator.isLikelyMutator("setting"))
        assertFalse(Evaluator.isLikelyMutator("settings"))
        // `addy` is a noun shape, not `add` + boundary.
        assertFalse(Evaluator.isLikelyMutator("addy"))
    }

    @Test
    fun bare_apply_commit_clear_reset_are_mutators() {
        // The four bare-name mutators in the BR-02 spec — these match by exact name,
        // not prefix.
        assertTrue(Evaluator.isLikelyMutator("apply"))
        assertTrue(Evaluator.isLikelyMutator("commit"))
        assertTrue(Evaluator.isLikelyMutator("clear"))
        assertTrue(Evaluator.isLikelyMutator("reset"))
    }

    @Test
    fun all_documented_mutator_prefixes_are_classified() {
        // Every prefix in the BR-02 spec should fire when followed by an uppercase
        // boundary. This locks in the spec; a PR that drops one is a deliberate change.
        val prefixes = listOf(
            "set", "clear", "delete", "remove", "add", "put", "update", "reset",
            "apply", "commit", "insert", "drop", "destroy", "invalidate", "cancel",
        )
        for (p in prefixes) {
            val name = p + "X"
            assertTrue(Evaluator.isLikelyMutator(name), "expected `$name` to be a mutator")
        }
    }

    @Test
    fun or_throw_suffix_is_carved_out() {
        // BR-02 explicitly carves out names ending in `OrThrow` because that's a
        // read-validation idiom in the project's coding style. `setOrThrow` is
        // therefore NOT refused.
        assertFalse(Evaluator.isLikelyMutator("setOrThrow"))
        assertFalse(Evaluator.isLikelyMutator("addOrThrow"))
        assertFalse(Evaluator.isLikelyMutator("removeOrThrow"))
    }

    @Test
    fun read_methods_are_not_mutators() {
        // The negative space — typical read-only methods on Java/Kotlin types.
        // None of these should fire.
        val readers = listOf(
            "get",
            "getName",
            "size",
            "length",
            "toString",
            "hashCode",
            "equals",
            "isEmpty",
            "contains",
            "valueOf",
            "of",
            "iterator",
        )
        for (name in readers) {
            assertFalse(Evaluator.isLikelyMutator(name), "expected `$name` to be read-only")
        }
    }

    @Test
    fun verb_only_short_strings_dont_misclassify() {
        // Bare three-letter verbs that aren't on the bare-mutator list. `set`, `add`,
        // `put`, `get` alone should NOT match because the regex requires a boundary
        // character after the verb. (For `set`/`add`/`put` alone, the regex anchors
        // require `[A-Z_].*` AFTER the verb, so a bare verb has nothing to match.)
        assertFalse(Evaluator.isLikelyMutator("set"))
        assertFalse(Evaluator.isLikelyMutator("add"))
        assertFalse(Evaluator.isLikelyMutator("put"))
        assertFalse(Evaluator.isLikelyMutator("get"))
    }
}
