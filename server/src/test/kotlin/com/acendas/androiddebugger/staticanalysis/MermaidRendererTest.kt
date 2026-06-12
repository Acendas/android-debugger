package com.acendas.androiddebugger.staticanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand-built [Graph]s exercising [MermaidRenderer] (AC-14): `classDiagram` arrow direction
 * for `extends`/`implements`/`extended_by`/`implemented_by`, `flowchart TD` node shapes
 * and edge labels, sequential `n0, n1, ...` id sanitization (including edge endpoints
 * absent from `nodes`), the `collapsedCount` label suffix, quote escaping, and the
 * `n_trunc[...]` truncation node.
 */
class MermaidRendererTest {

    @Test
    fun class_diagram_renders_extends_and_implements_with_correct_arrow_direction() {
        val graph = Graph(
            rootId = "com.example.Circle",
            nodes = listOf(
                GNode("com.example.Circle", "Circle", NodeKind.CLASS),
                GNode("com.example.Shape", "Shape", NodeKind.CLASS),
                GNode("com.example.Movable", "Movable", NodeKind.INTERFACE),
            ),
            edges = listOf(
                GEdge("com.example.Circle", "com.example.Shape", "extends"),
                GEdge("com.example.Circle", "com.example.Movable", "implements"),
            ),
            truncated = false,
        )

        val mermaid = MermaidRenderer.classDiagram(graph)

        val expected = listOf(
            "classDiagram",
            "    class n0[\"Circle\"]",
            "    class n1[\"Shape\"]",
            "    class n2[\"Movable\"]",
            "    <<interface>> n2",
            "    n1 <|-- n0",
            "    n2 <|.. n0",
        ).joinToString("\n", postfix = "\n")

        assertEquals(expected, mermaid)
    }

    @Test
    fun class_diagram_renders_extended_by_and_implemented_by_with_correct_arrow_direction() {
        val graph = Graph(
            rootId = "com.example.Shape",
            nodes = listOf(
                GNode("com.example.Shape", "Shape", NodeKind.CLASS),
                GNode("com.example.Circle", "Circle", NodeKind.CLASS),
                GNode("com.example.Drawable", "Drawable", NodeKind.INTERFACE),
            ),
            edges = listOf(
                GEdge("com.example.Shape", "com.example.Circle", "extended_by"),
                GEdge("com.example.Drawable", "com.example.Shape", "implemented_by"),
            ),
            truncated = false,
        )

        val mermaid = MermaidRenderer.classDiagram(graph)

        val expected = listOf(
            "classDiagram",
            "    class n0[\"Shape\"]",
            "    class n1[\"Circle\"]",
            "    class n2[\"Drawable\"]",
            "    <<interface>> n2",
            "    n0 <|-- n1",
            "    n2 <|.. n0",
        ).joinToString("\n", postfix = "\n")

        assertEquals(expected, mermaid)
    }

    @Test
    fun flowchart_uses_rounded_nodes_for_statements_and_labeled_edges() {
        val graph = Graph(
            rootId = "bb0",
            nodes = listOf(
                GNode("bb0", "if (n > 0)", NodeKind.STMT),
                GNode("bb1", "result = 1", NodeKind.STMT),
                GNode("bb2", "result = -1", NodeKind.STMT),
            ),
            edges = listOf(
                GEdge("bb0", "bb1", "true"),
                GEdge("bb0", "bb2", "false"),
            ),
            truncated = false,
        )

        val mermaid = MermaidRenderer.flowchart(graph)

        val expected = listOf(
            "flowchart TD",
            "    n0(\"if (n > 0)\")",
            "    n1(\"result = 1\")",
            "    n2(\"result = -1\")",
            "    n0 -->|true| n1",
            "    n0 -->|false| n2",
        ).joinToString("\n", postfix = "\n")

        assertEquals(expected, mermaid)
    }

    @Test
    fun flowchart_renders_unlabeled_edges_and_truncation_node_with_elided_count() {
        val graph = Graph(
            rootId = "com.example.Caller#run()",
            nodes = listOf(
                GNode("com.example.Caller#run()", "Caller.run()", NodeKind.METHOD),
                GNode("com.example.Greeter#sayHello()", "Greeter.sayHello()", NodeKind.METHOD),
            ),
            edges = listOf(
                GEdge("com.example.Caller#run()", "com.example.Greeter#sayHello()", null),
            ),
            truncated = true,
            elidedCount = 4,
        )

        val mermaid = MermaidRenderer.flowchart(graph)

        val expected = listOf(
            "flowchart TD",
            "    n0[\"Caller.run()\"]",
            "    n1[\"Greeter.sayHello()\"]",
            "    n0 --> n1",
            "    n_trunc[\"... 4 more (truncated)\"]",
        ).joinToString("\n", postfix = "\n")

        assertEquals(expected, mermaid)
    }

    @Test
    fun truncation_node_without_elided_count_uses_generic_label() {
        val graph = Graph(
            rootId = "a",
            nodes = listOf(GNode("a", "A", NodeKind.CLASS)),
            edges = emptyList(),
            truncated = true,
        )

        val mermaid = MermaidRenderer.flowchart(graph)

        assertTrue(mermaid.contains("n_trunc[\"... (truncated)\"]"))
    }

    @Test
    fun collapsed_count_and_embedded_quotes_are_handled_in_labels() {
        val graph = Graph(
            rootId = "a",
            nodes = listOf(
                GNode("a", "Foo (+\"weird\")", NodeKind.CLASS, collapsedCount = 2),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val mermaid = MermaidRenderer.classDiagram(graph)

        assertTrue(mermaid.contains("class n0[\"Foo (+#quot;weird#quot;) (+2)\"]"))
    }

    @Test
    fun sanitize_ids_assigns_sequential_ids_to_edge_endpoints_absent_from_nodes() {
        val graph = Graph(
            rootId = "a",
            nodes = listOf(GNode("a", "A", NodeKind.CLASS)),
            edges = listOf(GEdge("a", "phantom", "extends")),
            truncated = false,
        )

        val mermaid = MermaidRenderer.classDiagram(graph)

        // "phantom" has no GNode, so it gets no "class n1[...]" line, but the edge still
        // references a sanitized id for it.
        assertTrue(mermaid.contains("n1 <|-- n0"))
        assertTrue(!mermaid.contains("class n1"))
    }
}
