package com.acendas.androiddebugger.staticanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [BfsBudget] directly (AC-10): node-cap vs depth-limit truncation are tracked
 * independently, `truncated` is the OR of both, and a BFS driven by [BfsBudget.admit] /
 * [BfsBudget.canExpand] / [BfsBudget.markUnexploredAtMaxDepth] retains the nodes closest
 * to the root when the cap is hit.
 */
class BfsBudgetTest {

    @Test
    fun admit_returns_true_for_first_occurrence_of_each_id() {
        val budget = BfsBudget(nodeCap = 10, maxDepth = 3)

        assertTrue(budget.admit("a"))
        assertTrue(budget.admit("b"))
        assertFalse(budget.nodeCapHit)
        assertEquals(0, budget.elidedCount)
        assertEquals(2, budget.visitedCount)
    }

    @Test
    fun admit_returns_false_for_already_visited_id_without_affecting_cap_state() {
        val budget = BfsBudget(nodeCap = 10, maxDepth = 3)

        assertTrue(budget.admit("a"))
        assertFalse(budget.admit("a"))
        assertFalse(budget.nodeCapHit)
        assertEquals(0, budget.elidedCount)
        assertEquals(1, budget.visitedCount)
    }

    @Test
    fun admit_hits_node_cap_and_increments_elided_count() {
        val budget = BfsBudget(nodeCap = 2, maxDepth = 3)

        assertTrue(budget.admit("a"))
        assertTrue(budget.admit("b"))
        assertFalse(budget.admit("c"))

        assertTrue(budget.nodeCapHit)
        assertTrue(budget.truncated)
        assertEquals(1, budget.elidedCount)
        assertEquals(2, budget.visitedCount)
        assertFalse(budget.isVisited("c"))
    }

    @Test
    fun is_visited_reflects_admitted_ids_only() {
        val budget = BfsBudget(nodeCap = 10, maxDepth = 3)

        budget.admit("a")

        assertTrue(budget.isVisited("a"))
        assertFalse(budget.isVisited("b"))
    }

    @Test
    fun can_expand_is_true_only_below_max_depth() {
        val budget = BfsBudget(nodeCap = 10, maxDepth = 2)

        assertTrue(budget.canExpand(0))
        assertTrue(budget.canExpand(1))
        assertFalse(budget.canExpand(2))
        assertFalse(budget.canExpand(3))
    }

    @Test
    fun mark_unexplored_at_max_depth_sets_flag_and_elided_count_only_when_nonzero() {
        val budget = BfsBudget(nodeCap = 10, maxDepth = 1)

        budget.markUnexploredAtMaxDepth(0)
        assertFalse(budget.hasUnexploredAtMaxDepth)
        assertFalse(budget.truncated)
        assertEquals(0, budget.elidedCount)

        budget.markUnexploredAtMaxDepth(3)
        assertTrue(budget.hasUnexploredAtMaxDepth)
        assertTrue(budget.truncated)
        assertEquals(3, budget.elidedCount)
    }

    @Test
    fun truncated_is_true_if_either_node_cap_or_depth_limit_fired_independently() {
        val nodeCapOnly = BfsBudget(nodeCap = 1, maxDepth = 5)
        nodeCapOnly.admit("a")
        nodeCapOnly.admit("b")
        assertTrue(nodeCapOnly.truncated)
        assertTrue(nodeCapOnly.nodeCapHit)
        assertFalse(nodeCapOnly.hasUnexploredAtMaxDepth)

        val depthOnly = BfsBudget(nodeCap = 100, maxDepth = 0)
        depthOnly.admit("root")
        depthOnly.markUnexploredAtMaxDepth(2)
        assertTrue(depthOnly.truncated)
        assertFalse(depthOnly.nodeCapHit)
        assertTrue(depthOnly.hasUnexploredAtMaxDepth)
    }

    @Test
    fun bfs_with_low_node_cap_retains_nodes_closest_to_root() {
        // root -> {a, b, c}; a -> {d}. With cap = 2, only root + one of {a,b,c} are
        // admitted — "d" (two hops away) must never be reached, regardless of the
        // order neighbors are visited in.
        val adjacency = mapOf(
            "root" to listOf("a", "b", "c"),
            "a" to listOf("d"),
            "b" to emptyList(),
            "c" to emptyList(),
            "d" to emptyList(),
        )

        val budget = BfsBudget(nodeCap = 2, maxDepth = 5)
        val admitted = mutableListOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()

        budget.admit("root")
        admitted.add("root")
        queue.add("root" to 0)

        while (queue.isNotEmpty()) {
            val (id, depth) = queue.removeFirst()
            val neighbors = adjacency[id].orEmpty()
            if (!budget.canExpand(depth)) {
                budget.markUnexploredAtMaxDepth(neighbors.size)
                continue
            }
            for (n in neighbors) {
                if (budget.admit(n)) {
                    admitted.add(n)
                    queue.add(n to depth + 1)
                }
            }
        }

        assertEquals(2, admitted.size)
        assertEquals("root", admitted[0])
        assertTrue(admitted[1] in setOf("a", "b", "c"))
        assertFalse(admitted.contains("d"))
        assertTrue(budget.truncated)
        assertTrue(budget.nodeCapHit)
    }
}
