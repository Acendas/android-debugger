package com.acendas.androiddebugger.staticanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand-built [Graph]s exercising [AsciiRenderer.render] (AC-6): a plain tree with edge
 * labels, a recursive back-edge rendered as `(cycle, see ... above)`, a DAG-convergence
 * shared descendant rendered as `(see ... above)`, the `truncated` trailing line, and the
 * `collapsedCount` label suffix.
 */
class AsciiRendererTest {

    @Test
    fun simple_tree_renders_with_box_drawing_connectors_and_edge_labels() {
        val graph = Graph(
            rootId = "root",
            nodes = listOf(
                GNode("root", "Root", NodeKind.CLASS),
                GNode("a", "A", NodeKind.CLASS),
                GNode("b", "B", NodeKind.CLASS),
            ),
            edges = listOf(
                GEdge("root", "a", "extends"),
                GEdge("root", "b", "implements"),
            ),
            truncated = false,
        )

        val ascii = AsciiRenderer.render(graph)

        assertEquals("Root\n├─ A [extends]\n└─ B [implements]\n", ascii)
    }

    @Test
    fun recursive_back_edge_renders_as_cycle_not_infinite_recursion() {
        val graph = Graph(
            rootId = "a",
            nodes = listOf(
                GNode("a", "A", NodeKind.METHOD),
                GNode("b", "B", NodeKind.METHOD),
            ),
            edges = listOf(
                GEdge("a", "b", "calls"),
                GEdge("b", "a", "calls"),
            ),
            truncated = false,
        )

        val ascii = AsciiRenderer.render(graph)

        assertEquals("A\n└─ B [calls]\n   └─ A [calls] (cycle, see A above)\n", ascii)
    }

    @Test
    fun shared_descendant_renders_as_dag_convergence_not_a_cycle() {
        val graph = Graph(
            rootId = "root",
            nodes = listOf(
                GNode("root", "Root", NodeKind.METHOD),
                GNode("x", "X", NodeKind.METHOD),
                GNode("y", "Y", NodeKind.METHOD),
                GNode("helper", "Helper", NodeKind.METHOD),
                GNode("leaf", "Leaf", NodeKind.METHOD),
            ),
            edges = listOf(
                GEdge("root", "x", null),
                GEdge("root", "y", null),
                GEdge("x", "helper", null),
                GEdge("y", "helper", null),
                GEdge("helper", "leaf", null),
            ),
            truncated = false,
        )

        val ascii = AsciiRenderer.render(graph)

        assertEquals(
            "Root\n├─ X\n│  └─ Helper\n│     └─ Leaf\n└─ Y\n   └─ Helper (see Helper above)\n",
            ascii,
        )
        assertTrue(!ascii.contains("(cycle"))
    }

    @Test
    fun truncated_graph_gets_a_trailing_truncation_line() {
        val graph = Graph(
            rootId = "root",
            nodes = listOf(
                GNode("root", "Root", NodeKind.CLASS),
                GNode("a", "A", NodeKind.CLASS),
            ),
            edges = listOf(GEdge("root", "a", null)),
            truncated = true,
        )

        val ascii = AsciiRenderer.render(graph)

        assertTrue(ascii.endsWith("... (truncated at 2 nodes)\n"))
    }

    @Test
    fun collapsed_count_is_shown_as_a_suffix() {
        val graph = Graph(
            rootId = "root",
            nodes = listOf(
                GNode("root", "Root", NodeKind.CLASS, collapsedCount = 3),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val ascii = AsciiRenderer.render(graph)

        assertEquals("Root (+3 collapsed)\n", ascii)
    }
}
