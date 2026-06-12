package com.acendas.androiddebugger.staticanalysis

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Builds [AnalysisScene]s against the compiled `com.acendas.fixtures.*` classes under
 * `build/classes/java/test` (compiled from `src/test/java/com/acendas/fixtures/...` by
 * `compileTestJava`, which `test` depends on transitively via `testClasses`) and exercises
 * the four extractors plus [GraphExtractors.resolveMethod] (AC-1, 2, 3, 4, 5, 6, 7, 8, 12).
 */
class GraphExtractorsTest {

    private val fixturesDir = "build/classes/java/test"

    private fun scene(): AnalysisScene = SootUpView.forRoot(AnalysisRoot(classDirs = listOf(fixturesDir)))

    private fun resolve(scene: AnalysisScene, className: String, methodName: String) =
        (
            GraphExtractors.resolveMethod(
                scene,
                GraphExtractors.MethodTarget.ClassNameParams(className, methodName, null),
            ) as GraphExtractors.MethodResolution.Found
            ).method

    // ------------------------------------------------------------------
    // AC-1, AC-2 — class hierarchy
    // ------------------------------------------------------------------

    @Test
    fun ac1_hierarchy_up_from_circle_finds_superclass_and_interfaces() {
        val graph = GraphExtractors.classHierarchy(
            scene(),
            "com.acendas.fixtures.hierarchy.Circle",
            Direction.UP,
            depth = 3,
            nodeCap = 40,
            collapseSynthetic = true,
        )

        assertFalse(graph.truncated)
        val ids = graph.nodes.map { it.id }.toSet()
        assertTrue(ids.contains("com.acendas.fixtures.hierarchy.Circle"))
        assertTrue(ids.contains("com.acendas.fixtures.hierarchy.Shape"))
        assertTrue(ids.contains("com.acendas.fixtures.hierarchy.Movable"))
        assertTrue(ids.contains("com.acendas.fixtures.hierarchy.Drawable"))

        fun labelOf(from: String, to: String) = graph.edges.find { it.from == from && it.to == to }?.label
        assertEquals(
            "extends",
            labelOf("com.acendas.fixtures.hierarchy.Circle", "com.acendas.fixtures.hierarchy.Shape"),
        )
        assertEquals(
            "implements",
            labelOf("com.acendas.fixtures.hierarchy.Circle", "com.acendas.fixtures.hierarchy.Movable"),
        )
        assertEquals(
            "implements",
            labelOf("com.acendas.fixtures.hierarchy.Shape", "com.acendas.fixtures.hierarchy.Drawable"),
        )
    }

    @Test
    fun ac1_hierarchy_down_from_shape_finds_circle_as_extended_by() {
        val graph = GraphExtractors.classHierarchy(
            scene(),
            "com.acendas.fixtures.hierarchy.Shape",
            Direction.DOWN,
            depth = 3,
            nodeCap = 40,
            collapseSynthetic = true,
        )

        val ids = graph.nodes.map { it.id }.toSet()
        assertTrue(ids.contains("com.acendas.fixtures.hierarchy.Circle"))
        val edge = graph.edges.find {
            it.from == "com.acendas.fixtures.hierarchy.Shape" && it.to == "com.acendas.fixtures.hierarchy.Circle"
        }
        assertEquals("extended_by", edge?.label)
    }

    @Test
    fun ac1_hierarchy_both_unions_up_and_down() {
        val graph = GraphExtractors.classHierarchy(
            scene(),
            "com.acendas.fixtures.hierarchy.Shape",
            Direction.BOTH,
            depth = 3,
            nodeCap = 40,
            collapseSynthetic = true,
        )

        val ids = graph.nodes.map { it.id }.toSet()
        // up: Drawable (implements); down: Circle (extended_by) — both present in the union.
        assertTrue(ids.contains("com.acendas.fixtures.hierarchy.Drawable"))
        assertTrue(ids.contains("com.acendas.fixtures.hierarchy.Circle"))
    }

    @Test
    fun ac2_hierarchy_unknown_class_is_class_not_found() {
        val error = assertFailsWith<ToolError> {
            GraphExtractors.classHierarchy(
                scene(),
                "com.acendas.fixtures.hierarchy.DoesNotExist",
                Direction.UP,
                depth = 3,
                nodeCap = 40,
                collapseSynthetic = true,
            )
        }
        assertEquals(ErrorCode.ClassNotFound, error.errorCode)
    }

    // ------------------------------------------------------------------
    // AC-3, AC-4 — call graph
    // ------------------------------------------------------------------

    @Test
    fun ac3_call_graph_callees_expands_virtual_dispatch_and_caps_at_max_dispatch_targets() {
        val s = scene()
        val target = resolve(s, "com.acendas.fixtures.dispatch.Dispatcher", "dispatch")

        val graph = GraphExtractors.callGraph(
            s,
            target,
            Direction.CALLEES,
            depth = 3,
            nodeCap = 40,
            maxDispatchTargets = 5,
            collapseSynthetic = true,
        )

        // Direct declared callee: ClickHandler.handle().
        val handleNode = graph.nodes.find { it.id.contains("ClickHandler") && it.id.contains("handle") }
        assertNotNull(handleNode)

        val overriddenByEdges = graph.edges.filter { it.label == "overridden_by" }
        val implementerEdges = overriddenByEdges.filter { !it.to.endsWith("#more") }
        // 7 HandlerA..HandlerG implementers, capped at maxDispatchTargets = 5.
        assertEquals(5, implementerEdges.size)

        val overflowEdge = overriddenByEdges.find { it.to.endsWith("#more") }
        assertNotNull(overflowEdge)
        val overflowNode = graph.nodes.find { it.id == overflowEdge.to }
        assertEquals("+2 more implementers", overflowNode?.label)
    }

    @Test
    fun ac4_call_graph_callers_finds_application_call_site() {
        val s = scene()
        val target = resolve(s, "com.acendas.fixtures.callgraph.Greeter", "sayHello")

        val graph = GraphExtractors.callGraph(
            s,
            target,
            Direction.CALLERS,
            depth = 3,
            nodeCap = 40,
            maxDispatchTargets = 5,
            collapseSynthetic = true,
        )

        val callerNode = graph.nodes.find { it.label.startsWith("Caller.run") }
        assertNotNull(callerNode)
        val edge = graph.edges.find { it.from == callerNode.id && it.to == graph.rootId }
        assertNotNull(edge)
    }

    // ------------------------------------------------------------------
    // AC-5 — method ambiguity
    // ------------------------------------------------------------------

    @Test
    fun ac5_overloaded_method_without_params_is_ambiguous() {
        val result = GraphExtractors.resolveMethod(
            scene(),
            GraphExtractors.MethodTarget.ClassNameParams("com.acendas.fixtures.overload.Calc", "add", null),
        )

        val ambiguous = assertIs<GraphExtractors.MethodResolution.Ambiguous>(result)
        assertEquals(3, ambiguous.candidates.size)
        assertTrue(ambiguous.candidates.all { it.contains("add") })
    }

    // ------------------------------------------------------------------
    // AC-6, AC-7 — control-flow graph
    // ------------------------------------------------------------------

    @Test
    fun ac6_cfg_branch_and_loop_has_true_false_and_back_edges() {
        val s = scene()
        val target = resolve(s, "com.acendas.fixtures.cfg.BranchLoop", "classify")

        val graph = GraphExtractors.cfg(s, target, nodeCap = 100)

        assertTrue(graph.edges.any { it.label == "true" })
        assertTrue(graph.edges.any { it.label == "false" })

        // The for-loop introduces a back-edge: an edge pointing to a block whose
        // bb<N> index is <= the source block's index — a cycle, not a forward-only DAG.
        fun blockIndex(id: String) = id.removePrefix("bb").toInt()
        assertTrue(graph.edges.any { blockIndex(it.to) <= blockIndex(it.from) })
    }

    @Test
    fun ac7_cfg_try_catch_has_exceptional_successor_edge() {
        val s = scene()
        val target = resolve(s, "com.acendas.fixtures.cfg.TryCatch", "safeDivide")

        val graph = GraphExtractors.cfg(s, target, nodeCap = 100)

        assertTrue(graph.edges.any { it.label?.startsWith("catch ") == true })
    }

    // ------------------------------------------------------------------
    // AC-8 — package dependency graph
    // ------------------------------------------------------------------

    @Test
    fun ac8_package_graph_aggregates_cross_package_reference() {
        val graph = GraphExtractors.packageGraph(
            scene(),
            "com.acendas.fixtures.pkg",
            depth = 2,
            nodeCap = 40,
            collapseSynthetic = true,
        )

        val ids = graph.nodes.map { it.id }.toSet()
        assertTrue(ids.contains("com.acendas.fixtures.pkg.a"))
        assertTrue(ids.contains("com.acendas.fixtures.pkg.b"))

        val edge = graph.edges.find { it.from == "com.acendas.fixtures.pkg.a" && it.to == "com.acendas.fixtures.pkg.b" }
        assertNotNull(edge)
    }

    // ------------------------------------------------------------------
    // AC-12 — unresolved references are warnings, not failures
    // ------------------------------------------------------------------

    @Test
    fun ac12_unresolved_superclass_surfaces_as_warning() {
        // HasUnresolvedSuper.class extends MissingType, but copy only the former into an
        // isolated class_dirs so MissingType is unresolvable from SootUp's point of view.
        val isolatedDir = Files.createTempDirectory("ad-unresolved-fixture").toFile()
        try {
            val pkgDir = File(isolatedDir, "com/acendas/fixtures/unresolved")
            pkgDir.mkdirs()
            File(fixturesDir, "com/acendas/fixtures/unresolved/HasUnresolvedSuper.class")
                .copyTo(File(pkgDir, "HasUnresolvedSuper.class"))

            val s = SootUpView.forRoot(AnalysisRoot(classDirs = listOf(isolatedDir.absolutePath)))
            val graph = GraphExtractors.classHierarchy(
                s,
                "com.acendas.fixtures.unresolved.HasUnresolvedSuper",
                Direction.UP,
                depth = 3,
                nodeCap = 40,
                collapseSynthetic = true,
            )

            assertTrue(graph.warnings.any { Regex("""\d+ unresolved reference\(s\)""").matches(it) })
        } finally {
            isolatedDir.deleteRecursively()
        }
    }
