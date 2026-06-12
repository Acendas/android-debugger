package com.acendas.androiddebugger.staticanalysis

/**
 * Renders a [Graph] as Mermaid diagram text for persisted docs — secondary,
 * presentation-layer output per the "Output priority" decision (the agent reasons from
 * `nodes`/`edges`, not this string).
 *
 * Node ids are sanitized to sequential `n0, n1, ...` because real ids (FQNs, method
 * signatures) contain characters Mermaid can't use as identifiers (`.`, `$`, `<`, `>`,
 * `(`, `)`); the real name becomes the node's display label instead.
 *
 * [classDiagram] is for [GraphExtractors.classHierarchy]. [flowchart] is for call graph,
 * CFG, and package graph.
 *
 * Edge-label convention produced by [GraphExtractors] and consumed here for
 * [classDiagram]: an edge always points from the BFS-root-ward node to the
 * away-from-root node (so [AsciiRenderer] can render outward from the root without
 * knowing traversal direction). The *label* carries the semantic relationship:
 * - `extends` / `implements` — the away-from-root node (`to`) is the supertype/interface.
 * - `extended_by` / `implemented_by` — the root-ward node (`from`) is the supertype/interface.
 */
object MermaidRenderer {

    fun classDiagram(graph: Graph): String {
        val ids = sanitizeIds(graph)
        val sb = StringBuilder("classDiagram\n")

        for (node in graph.nodes) {
            val id = ids.getValue(node.id)
            sb.append("    class ").append(id).append("[\"").append(escapeLabel(displayLabel(node))).append("\"]\n")
            if (node.kind == NodeKind.INTERFACE) {
                sb.append("    <<interface>> ").append(id).append('\n')
            }
        }

        for (edge in graph.edges) {
            val from = ids[edge.from] ?: continue
            val to = ids[edge.to] ?: continue
            val label = edge.label.orEmpty()
            when {
                label.startsWith("implemented_by") -> sb.append("    ").append(from).append(" <|.. ").append(to).append('\n')
                label.startsWith("implements") -> sb.append("    ").append(to).append(" <|.. ").append(from).append('\n')
                label.startsWith("extended_by") -> sb.append("    ").append(from).append(" <|-- ").append(to).append('\n')
                label.startsWith("extends") -> sb.append("    ").append(to).append(" <|-- ").append(from).append('\n')
                else -> sb.append("    ").append(from).append(" --> ").append(to).append('\n')
            }
        }

        appendTruncationNode(sb, graph)
        return sb.toString()
    }

    fun flowchart(graph: Graph): String {
        val ids = sanitizeIds(graph)
        val sb = StringBuilder("flowchart TD\n")

        for (node in graph.nodes) {
            val id = ids.getValue(node.id)
            val (open, close) = if (node.kind == NodeKind.STMT) "(" to ")" else "[" to "]"
            sb.append("    ").append(id).append(open).append('"').append(escapeLabel(displayLabel(node))).append('"').append(close).append('\n')
        }

        for (edge in graph.edges) {
            val from = ids[edge.from] ?: continue
            val to = ids[edge.to] ?: continue
            if (edge.label.isNullOrBlank()) {
                sb.append("    ").append(from).append(" --> ").append(to).append('\n')
            } else {
                sb.append("    ").append(from).append(" -->|").append(escapeLabel(edge.label)).append("| ").append(to).append('\n')
            }
        }

        appendTruncationNode(sb, graph)
        return sb.toString()
    }

    private fun displayLabel(node: GNode): String =
        if (node.collapsedCount > 0) "${node.label} (+${node.collapsedCount})" else node.label

    /** Mermaid uses `"` to delimit labels; escape any embedded quotes. */
    private fun escapeLabel(s: String): String = s.replace("\"", "#quot;")

    /**
     * Map every node id (and any edge endpoint not present in `nodes`, defensively) to a
     * sequential `n0, n1, ...` Mermaid-safe identifier, in [Graph.nodes] order.
     */
    private fun sanitizeIds(graph: Graph): Map<String, String> {
        val ids = LinkedHashMap<String, String>()
        var next = 0
        for (node in graph.nodes) {
            if (ids.putIfAbsent(node.id, "n${next}") == null) next++
        }
        for (edge in graph.edges) {
            if (ids.putIfAbsent(edge.from, "n${next}") == null) next++
            if (ids.putIfAbsent(edge.to, "n${next}") == null) next++
        }
        return ids
    }

    /** AC-14: a truncated graph appends an `n_trunc["... N more (truncated)"]` node. */
    private fun appendTruncationNode(sb: StringBuilder, graph: Graph) {
        if (!graph.truncated) return
        val label = if (graph.elidedCount > 0) {
            "... ${graph.elidedCount} more (truncated)"
        } else {
            "... (truncated)"
        }
        sb.append("    n_trunc[\"").append(escapeLabel(label)).append("\"]\n")
    }
}
