package com.acendas.androiddebugger.staticanalysis

/**
 * Folds Kotlin-compiler-synthetic classes (companions, lambdas, `$DefaultImpls`,
 * `$WhenMappings`, coroutine continuation classes for `suspend fun`) into their
 * enclosing class, so an agent reading a hierarchy/package graph isn't drowned in
 * `Foo$Companion`, `Foo$1`, `Foo$onCreate$1` noise (AC-9).
 *
 * Only [GNode]s of kind [NodeKind.CLASS] or [NodeKind.INTERFACE] are ever folded — their
 * [GNode.id] is a class FQN, so "enclosing class" is well-defined as the FQN portion
 * before the first `$`. Method/statement/package nodes are left alone.
 *
 * [GraphExtractors] populates [GNode.meta] with `synthetic` ("true" when
 * `ClassModifier.isSynthetic`), `super` (superclass FQN), and `interfaces`
 * (comma-joined implemented-interface FQNs) so the coroutine/synthetic-modifier
 * heuristics below have something to check beyond the FQN itself.
 *
 * `collapse_synthetic: false` calls [collapse] with `enabled = false`, which returns
 * [graph] unchanged.
 */
object SyntheticCollapse {

    /** FQN-suffix patterns that mark a class as compiler-generated. */
    private val NAME_PATTERNS = listOf(
        Regex("""\${'$'}Companion$"""),
        Regex("""\${'$'}DefaultImpls$"""),
        Regex("""\${'$'}WhenMappings$"""),
        Regex("""\$\d+(\$.*)?$"""), // Foo$1, Foo$1$2 — anonymous classes / nested lambdas
        Regex("""-\$\${'$'}Lambda\$"""), // D8/R8 desugared lambda classes
    )

    private val COROUTINE_SUPERTYPES = setOf(
        "kotlin.coroutines.jvm.internal.ContinuationImpl",
        "kotlin.coroutines.jvm.internal.SuspendLambda",
        "kotlin.coroutines.jvm.internal.RestrictedSuspendLambda",
    )

    private const val COROUTINE_INTERFACE = "kotlin.coroutines.Continuation"

    /**
     * Returns a new [Graph] with synthetic classes folded into their enclosing class, or
     * [graph] unchanged if [enabled] is false or nothing matched.
     */
    fun collapse(graph: Graph, enabled: Boolean): Graph {
        if (!enabled) return graph

        // synthetic node id -> immediate enclosing id (may itself be synthetic, e.g.
        // Foo$1$2 -> Foo$1 -> Foo; resolve() below walks the chain).
        val foldTargets = mutableMapOf<String, String>()
        for (node in graph.nodes) {
            if (node.id == graph.rootId) continue // never fold the query root away
            val enclosing = enclosingIdIfSynthetic(node) ?: continue
            if (enclosing != node.id) foldTargets[node.id] = enclosing
        }
        if (foldTargets.isEmpty()) return graph

        fun resolve(id: String): String {
            var current = id
            var hops = 0
            while (hops < 8) {
                val next = foldTargets[current] ?: return current
                if (next == current) return current
                current = next
                hops++
            }
            return current
        }

        val collapsedCounts = mutableMapOf<String, Int>()
        for (syntheticId in foldTargets.keys) {
            val target = resolve(syntheticId)
            collapsedCounts[target] = (collapsedCounts[target] ?: 0) + 1
        }

        val survivingNodes = mutableListOf<GNode>()
        for (node in graph.nodes) {
            if (foldTargets.containsKey(node.id)) continue // folded away
            val extra = collapsedCounts[node.id] ?: 0
            survivingNodes.add(
                if (extra > 0) node.copy(collapsedCount = node.collapsedCount + extra) else node,
            )
        }

        val repointed = mutableListOf<GEdge>()
        val seenEdges = mutableSetOf<Triple<String, String, String?>>()
        for (edge in graph.edges) {
            val from = resolve(edge.from)
            val to = resolve(edge.to)
            if (from == to) continue // dedupe self-loops created by folding both ends together
            val key = Triple(from, to, edge.label)
            if (!seenEdges.add(key)) continue // dedupe edges that converge after folding
            repointed.add(GEdge(from, to, edge.label))
        }

        return graph.copy(nodes = survivingNodes, edges = repointed)
    }

    private fun enclosingIdIfSynthetic(node: GNode): String? {
        if (node.kind != NodeKind.CLASS && node.kind != NodeKind.INTERFACE) return null

        val id = node.id
        val isSyntheticByName = NAME_PATTERNS.any { it.containsMatchIn(id) }
        val isSyntheticByModifier = node.meta["synthetic"] == "true"
        val isCoroutine = node.meta["super"] in COROUTINE_SUPERTYPES ||
            node.meta["interfaces"]?.split(",")?.contains(COROUTINE_INTERFACE) == true

        if (!isSyntheticByName && !isSyntheticByModifier && !isCoroutine) return null

        val dollarIdx = id.indexOf('$')
        return if (dollarIdx > 0) id.substring(0, dollarIdx) else null
    }
}
