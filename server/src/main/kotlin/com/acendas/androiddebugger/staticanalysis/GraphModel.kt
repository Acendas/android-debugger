package com.acendas.androiddebugger.staticanalysis

/** Discriminates the kind of entity a [GNode] represents in a rendered [Graph]. */
enum class NodeKind { CLASS, INTERFACE, METHOD, STMT, PACKAGE }

/**
 * One node in a rendered graph. [id] is a stable, renderer-agnostic identifier (a FQN,
 * a method signature, a `bb<N>` basic-block id, or a package name) — both
 * [AsciiRenderer] and [MermaidRenderer] key off [id], not [label].
 *
 * [collapsedCount] is non-zero when [SyntheticCollapse] folded synthetic
 * classes/lambdas/companions into this node (AC-9) — the count of folded siblings.
 */
data class GNode(
    val id: String,
    val label: String,
    val kind: NodeKind,
    val collapsedCount: Int = 0,
    val meta: Map<String, String> = emptyMap(),
)

/** A directed edge between two [GNode.id]s, with an optional human-readable [label]. */
data class GEdge(
    val from: String,
    val to: String,
    val label: String? = null,
)

/**
 * The shared, renderer-agnostic result of every `static_*` extractor in
 * [GraphExtractors]. Both [AsciiRenderer] and [MermaidRenderer] consume this — and per
 * the "Output priority" decision in the v1.8 plan, [nodes]/[edges]/[truncated]/[warnings]
 * are the primary, agent-facing fields. The rendered `ascii`/`mermaid` strings are
 * secondary presentation outputs for the human watching the session.
 */
data class Graph(
    val rootId: String,
    val nodes: List<GNode>,
    val edges: List<GEdge>,
    val truncated: Boolean,
    val warnings: List<String> = emptyList(),
    /**
     * Approximate count of nodes that were discovered but not admitted because
     * [BfsBudget.nodeCapHit] or [BfsBudget.hasUnexploredAtMaxDepth] fired. Surfaced by
     * [MermaidRenderer] as the `n_trunc["... N more (truncated)"]` node (AC-14) and by
     * [GraphExtractors] in [warnings] (e.g. "truncated: ~N more node(s) not shown").
     * Zero when [truncated] is false.
     */
    val elidedCount: Int = 0,
)

/** Traversal direction for [GraphExtractors.classHierarchy] and [GraphExtractors.callGraph]. */
enum class Direction { UP, DOWN, BOTH, CALLERS, CALLEES }

/**
 * Shared BFS truncation bookkeeping for [GraphExtractors]. BFS (not DFS) so [nodeCap]
 * retains the nodes *closest* to the root — "this is everything within N hops" rather
 * than an arbitrary DFS-order prefix.
 *
 * [truncated] is true if either the node cap was hit ([nodeCapHit]) or a node at
 * [maxDepth] had unexplored neighbors ([hasUnexploredAtMaxDepth]) — callers can tell
 * the agent which knob (`node_cap` vs `depth`) to raise to see more.
 *
 * Typical use from an extractor's BFS loop:
 * ```
 * val budget = BfsBudget(nodeCap, maxDepth)
 * budget.admit(rootId) // true — first node, always admitted unless nodeCap == 0
 * // ... enqueue (rootId, 0) ...
 * while (queue not empty) {
 *     val (id, depth) = queue.removeFirst()
 *     val neighbors = neighborsOf(id)
 *     if (!budget.canExpand(depth)) {
 *         budget.markUnexploredAtMaxDepth(neighbors.size)
 *         continue
 *     }
 *     for (n in neighbors) {
 *         val isNew = budget.admit(n.id)
 *         if (!isNew && !budget.isVisited(n.id)) continue // node-cap hit; skip node + edge
 *         edges.add(GEdge(id, n.id, n.label))
 *         if (isNew) {
 *             nodes.add(n.node)
 *             queue.add(n.id to depth + 1)
 *         }
 *     }
 * }
 * ```
 */
class BfsBudget(val nodeCap: Int, val maxDepth: Int) {

    private val visitedIds = mutableSetOf<String>()

    var nodeCapHit: Boolean = false
        private set

    var hasUnexploredAtMaxDepth: Boolean = false
        private set

    /** Approximate count of nodes discovered-but-not-admitted. See [Graph.elidedCount]. */
    var elidedCount: Int = 0
        private set

    val truncated: Boolean
        get() = nodeCapHit || hasUnexploredAtMaxDepth

    val visitedCount: Int
        get() = visitedIds.size

    /** True if [id] has already been admitted (added to `nodes`). */
    fun isVisited(id: String): Boolean = id in visitedIds

    /**
     * Attempt to admit [id] into the result set.
     *
     * Returns `true` the first time [id] is seen and the node cap hasn't been hit — the
     * caller should add a [GNode] for it and (subject to [canExpand]) enqueue it for
     * expansion.
     *
     * Returns `false` if [id] was already admitted (the caller may still add a
     * convergence [GEdge] to it — check [isVisited]) or if the node cap was hit (sets
     * [nodeCapHit] and increments [elidedCount]; the caller should skip the node *and*
     * any edge to it).
     */
    fun admit(id: String): Boolean {
        if (id in visitedIds) return false
        if (visitedIds.size >= nodeCap) {
            nodeCapHit = true
            elidedCount++
            return false
        }
        visitedIds.add(id)
        return true
    }

    /** Whether a node admitted at [depth] should have its neighbors explored. */
    fun canExpand(depth: Int): Boolean = depth < maxDepth

    /**
     * Record that a node at [maxDepth] had [skippedNeighbors] neighbors that were not
     * explored (and not admitted).
     */
    fun markUnexploredAtMaxDepth(skippedNeighbors: Int) {
        if (skippedNeighbors > 0) {
            hasUnexploredAtMaxDepth = true
            elidedCount += skippedNeighbors
        }
    }
}
