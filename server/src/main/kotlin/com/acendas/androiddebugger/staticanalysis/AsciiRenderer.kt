package com.acendas.androiddebugger.staticanalysis

/**
 * Renders a [Graph] as an ASCII tree for terminal display — secondary, presentation-layer
 * output per the "Output priority" decision (the agent reasons from `nodes`/`edges`, not
 * this string).
 *
 * DFS from [Graph.rootId], tracking two sets to distinguish two visually-similar but
 * semantically-different situations an agent must not conflate:
 *
 * - `path` (current-branch ancestors): revisiting a node already in `path` is a **true
 *   cycle** (recursion) — rendered `(cycle, see <label> above)`, not re-expanded.
 * - `rendered` (anything fully expanded anywhere in the tree): revisiting a node that is
 *   *not* in `path` is **DAG convergence** (e.g. two callers of one helper, or diamond
 *   inheritance) — rendered `(see <label> above)`, not re-expanded.
 *
 * [Graph.truncated] (size/depth cap hit, independent of cycles/convergence) gets its own
 * trailing line: `... (truncated at N nodes)`.
 */
object AsciiRenderer {

    fun render(graph: Graph): String {
        val nodeById = graph.nodes.associateBy { it.id }
        val childrenOf = graph.edges.groupBy { it.from }
        val sb = StringBuilder()
        val path = LinkedHashSet<String>()
        val rendered = mutableSetOf<String>()

        renderNode(
            id = graph.rootId,
            edgeLabel = null,
            prefix = "",
            connector = "",
            nodeById = nodeById,
            childrenOf = childrenOf,
            path = path,
            rendered = rendered,
            sb = sb,
        )

        if (graph.truncated) {
            sb.append("... (truncated at ${graph.nodes.size} nodes)\n")
        }

        return sb.toString()
    }

    private fun displayLabel(node: GNode?, id: String, edgeLabel: String?): String {
        val base = node?.label ?: id
        val collapsedSuffix = if (node != null && node.collapsedCount > 0) {
            " (+${node.collapsedCount} collapsed)"
        } else {
            ""
        }
        val edgeSuffix = edgeLabel?.let { " [$it]" } ?: ""
        return "$base$collapsedSuffix$edgeSuffix"
    }

    /** Indentation contributed to descendants by a node rendered with [connector]. */
    private fun extensionFor(connector: String): String = when (connector) {
        "" -> ""
        "└─ " -> "   "
        else -> "│  "
    }

    private fun renderNode(
        id: String,
        edgeLabel: String?,
        prefix: String,
        connector: String,
        nodeById: Map<String, GNode>,
        childrenOf: Map<String, List<GEdge>>,
        path: LinkedHashSet<String>,
        rendered: MutableSet<String>,
        sb: StringBuilder,
    ) {
        val node = nodeById[id]
        sb.append(prefix).append(connector).append(displayLabel(node, id, edgeLabel)).append('\n')

        val children = childrenOf[id].orEmpty()
        if (children.isEmpty()) return

        path.add(id)
        rendered.add(id)
        val childPrefix = prefix + extensionFor(connector)

        children.forEachIndexed { idx, edge ->
            val last = idx == children.lastIndex
            val childConnector = if (last) "└─ " else "├─ "
            val childId = edge.to
            val childNode = nodeById[childId]
            when {
                childId in path -> {
                    sb.append(childPrefix).append(childConnector)
                        .append(displayLabel(childNode, childId, edge.label))
                        .append(" (cycle, see ").append(childNode?.label ?: childId).append(" above)\n")
                }
                childId in rendered -> {
                    sb.append(childPrefix).append(childConnector)
                        .append(displayLabel(childNode, childId, edge.label))
                        .append(" (see ").append(childNode?.label ?: childId).append(" above)\n")
                }
                else -> renderNode(
                    id = childId,
                    edgeLabel = edge.label,
                    prefix = childPrefix,
                    connector = childConnector,
                    nodeById = nodeById,
                    childrenOf = childrenOf,
                    path = path,
                    rendered = rendered,
                    sb = sb,
                )
            }
        }
        path.remove(id)
    }
}
