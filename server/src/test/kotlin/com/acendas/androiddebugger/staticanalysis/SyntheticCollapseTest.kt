package com.acendas.androiddebugger.staticanalysis

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand-built [Graph]s exercising [SyntheticCollapse.collapse] (AC-9): name-pattern,
 * modifier, and coroutine-supertype detection; edge re-pointing; self-loop and
 * duplicate-edge dedup after folding; the root-node and non-CLASS/INTERFACE exclusions;
 * and the `collapse_synthetic: false` / no-op passthroughs.
 */
class SyntheticCollapseTest {

    @Test
    fun companion_object_folds_into_enclosing_class() {
        val graph = Graph(
            rootId = "com.example.Foo",
            nodes = listOf(
                GNode("com.example.Foo", "Foo", NodeKind.CLASS),
                GNode("com.example.Foo\$Companion", "Companion", NodeKind.CLASS),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(listOf("com.example.Foo"), result.nodes.map { it.id })
        assertEquals(1, result.nodes.single().collapsedCount)
    }

    @Test
    fun anonymous_inner_class_folds_into_enclosing_class_and_repoints_edges() {
        val graph = Graph(
            rootId = "com.example.Foo",
            nodes = listOf(
                GNode("com.example.Foo", "Foo", NodeKind.CLASS),
                GNode("com.example.Foo\$1", "Foo\$1", NodeKind.CLASS),
            ),
            edges = listOf(GEdge("com.example.Foo\$1", "com.example.Bar", "uses")),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(listOf("com.example.Foo"), result.nodes.map { it.id })
        assertEquals(listOf(GEdge("com.example.Foo", "com.example.Bar", "uses")), result.edges)
    }

    @Test
    fun default_impls_and_when_mappings_fold_into_enclosing_class() {
        val graph = Graph(
            rootId = "com.example.Foo",
            nodes = listOf(
                GNode("com.example.Foo", "Foo", NodeKind.INTERFACE),
                GNode("com.example.Foo\$DefaultImpls", "DefaultImpls", NodeKind.CLASS),
                GNode("com.example.Foo\$WhenMappings", "WhenMappings", NodeKind.CLASS),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(listOf("com.example.Foo"), result.nodes.map { it.id })
        assertEquals(2, result.nodes.single().collapsedCount)
    }

    @Test
    fun coroutine_continuation_folds_via_super_type_meta() {
        val graph = Graph(
            rootId = "com.example.Foo",
            nodes = listOf(
                GNode("com.example.Foo", "Foo", NodeKind.CLASS),
                GNode(
                    "com.example.Foo\$fetchData",
                    "fetchData",
                    NodeKind.CLASS,
                    meta = mapOf("super" to "kotlin.coroutines.jvm.internal.ContinuationImpl"),
                ),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(listOf("com.example.Foo"), result.nodes.map { it.id })
        assertEquals(1, result.nodes.single().collapsedCount)
    }

    @Test
    fun coroutine_continuation_folds_via_continuation_interface_meta() {
        val graph = Graph(
            rootId = "com.example.Foo",
            nodes = listOf(
                GNode("com.example.Foo", "Foo", NodeKind.CLASS),
                GNode(
                    "com.example.Foo\$loadUser",
                    "loadUser",
                    NodeKind.CLASS,
                    meta = mapOf("interfaces" to "kotlin.coroutines.Continuation,java.io.Serializable"),
                ),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(listOf("com.example.Foo"), result.nodes.map { it.id })
    }

    @Test
    fun synthetic_modifier_flag_folds_even_without_matching_name_pattern() {
        val graph = Graph(
            rootId = "com.example.Foo",
            nodes = listOf(
                GNode("com.example.Foo", "Foo", NodeKind.CLASS),
                GNode(
                    "com.example.Foo\$Helper",
                    "Helper",
                    NodeKind.CLASS,
                    meta = mapOf("synthetic" to "true"),
                ),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(listOf("com.example.Foo"), result.nodes.map { it.id })
    }

    @Test
    fun self_loops_and_duplicate_edges_created_by_folding_are_deduped() {
        val graph = Graph(
            rootId = "com.example.Foo",
            nodes = listOf(
                GNode("com.example.Foo", "Foo", NodeKind.CLASS),
                GNode("com.example.Foo\$1", "Foo\$1", NodeKind.CLASS),
                GNode("com.example.Foo\$2", "Foo\$2", NodeKind.CLASS),
                GNode("com.example.Bar", "Bar", NodeKind.CLASS),
            ),
            edges = listOf(
                // Both lambdas reference Bar the same way -> dedupe to one edge.
                GEdge("com.example.Foo\$1", "com.example.Bar", "calls"),
                GEdge("com.example.Foo\$2", "com.example.Bar", "calls"),
                // Foo$1 -> Foo$2 both fold to Foo -> self-loop, dropped.
                GEdge("com.example.Foo\$1", "com.example.Foo\$2", "calls"),
            ),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(setOf("com.example.Foo", "com.example.Bar"), result.nodes.map { it.id }.toSet())
        assertEquals(listOf(GEdge("com.example.Foo", "com.example.Bar", "calls")), result.edges)
        assertEquals(2, result.nodes.find { it.id == "com.example.Foo" }?.collapsedCount)
    }

    @Test
    fun root_node_is_never_folded_even_if_it_matches_a_synthetic_pattern() {
        val graph = Graph(
            rootId = "com.example.Foo\$1",
            nodes = listOf(
                GNode("com.example.Foo\$1", "Foo\$1", NodeKind.CLASS),
                GNode("com.example.Foo", "Foo", NodeKind.CLASS),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(setOf("com.example.Foo\$1", "com.example.Foo"), result.nodes.map { it.id }.toSet())
    }

    @Test
    fun non_class_or_interface_nodes_are_never_folded() {
        val graph = Graph(
            rootId = "root",
            nodes = listOf(
                GNode("root", "root", NodeKind.CLASS),
                GNode("com.example.Foo\$1.bar()", "Foo\$1.bar()", NodeKind.METHOD),
                GNode("com.example.Foo\$1", "Foo\$1", NodeKind.PACKAGE),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(graph.nodes.map { it.id }.toSet(), result.nodes.map { it.id }.toSet())
    }

    @Test
    fun collapse_disabled_returns_graph_unchanged() {
        val graph = Graph(
            rootId = "com.example.Foo",
            nodes = listOf(
                GNode("com.example.Foo", "Foo", NodeKind.CLASS),
                GNode("com.example.Foo\$Companion", "Companion", NodeKind.CLASS),
            ),
            edges = emptyList(),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = false)

        assertEquals(graph, result)
    }

    @Test
    fun graph_with_no_synthetic_nodes_is_unchanged() {
        val graph = Graph(
            rootId = "com.example.Foo",
            nodes = listOf(
                GNode("com.example.Foo", "Foo", NodeKind.CLASS),
                GNode("com.example.Bar", "Bar", NodeKind.CLASS),
            ),
            edges = listOf(GEdge("com.example.Foo", "com.example.Bar", "extends")),
            truncated = false,
        )

        val result = SyntheticCollapse.collapse(graph, enabled = true)

        assertEquals(graph, result)
    }
}
