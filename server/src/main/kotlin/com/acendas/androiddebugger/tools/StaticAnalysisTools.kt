package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolErr
import com.acendas.androiddebugger.staticanalysis.AndroidJarResolver
import com.acendas.androiddebugger.staticanalysis.AnalysisRoot
import com.acendas.androiddebugger.staticanalysis.AsciiRenderer
import com.acendas.androiddebugger.staticanalysis.Direction
import com.acendas.androiddebugger.staticanalysis.GEdge
import com.acendas.androiddebugger.staticanalysis.GNode
import com.acendas.androiddebugger.staticanalysis.Graph
import com.acendas.androiddebugger.staticanalysis.GraphExtractors
import com.acendas.androiddebugger.staticanalysis.MermaidRenderer
import com.acendas.androiddebugger.staticanalysis.GraphExtractors.MethodResolution
import com.acendas.androiddebugger.staticanalysis.GraphExtractors.MethodTarget
import com.acendas.androiddebugger.staticanalysis.SootUpView
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * v1.8 — SootUp-backed static analysis tools. Four standalone, read-only tools, no
 * `attach`/`VmCoordinator` interaction:
 *
 *  - `static_class_hierarchy` — superclass/interfaces/subtypes around a class.
 *  - `static_call_graph` — direct callers/callees of a method, with virtual-dispatch expansion.
 *  - `static_cfg` — per-method control-flow graph (basic blocks + branch/exception edges).
 *  - `static_package_graph` — cross-package reference graph rooted at a package.
 *
 * Every response is `{ ok: true, root_id, nodes, edges, truncated, elided_count, warnings,
 * ascii, mermaid }`. `nodes`/`edges`/`truncated`/`warnings` are the primary, agent-facing
 * contract; `ascii`/`mermaid` are server-rendered presentation outputs for the human
 * watching the session (terminal / persisted docs) — see the "Output priority" decision
 * in the v1.8 plan.
 *
 * All four take the same `class_dirs`/`extra_classpath`/`android_jar`/`android_api_level`
 * scene-construction params (see [commonClasspathProps] and [buildAnalysisRoot]).
 * `target_method` (call graph, CFG) accepts a canonical signature string or
 * `{class, name, params?}`; an omitted `params` on an overloaded method returns
 * `method_ambiguous` with `candidates` (see [parseMethodTarget]/[GraphExtractors.resolveMethod]).
 */
object StaticAnalysisTools {

    fun register(server: Server) {
        registerClassHierarchy(server)
        registerCallGraph(server)
        registerCfg(server)
        registerPackageGraph(server)
    }

    // ---------------- static_class_hierarchy ----------------

    private fun registerClassHierarchy(server: Server) {
        server.addTool(
            name = "static_class_hierarchy",
            description = "v1.8 — SootUp-backed class hierarchy graph rooted at `target_class`. " +
                "`direction: up` walks superclass + interfaces, `down` walks subtypes/implementers, " +
                "`both` (default) unions both, each independently bounded by `depth`/`node_cap`. " +
                "Standalone — no attach/session required. Returns nodes/edges/truncated/warnings " +
                "(primary, for agent reasoning) plus ascii/mermaid (secondary, for terminal/doc " +
                "display). `class_not_found` if target_class isn't in the scene; " +
                "`class_dirs_empty_or_missing` if none of class_dirs exist.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    commonClasspathProps()
                    putJsonObject("target_class") {
                        put("type", "string")
                        put("description", "Fully-qualified class name, e.g. com.example.LoginActivity.")
                    }
                    putJsonObject("direction") {
                        put("type", "string")
                        put("description", "up | down | both (default both).")
                    }
                    putJsonObject("depth") {
                        put("type", "integer")
                        put("description", "Max BFS depth per direction. Default 3.")
                    }
                    putJsonObject("node_cap") {
                        put("type", "integer")
                        put("description", "Max nodes before truncation (closest to root kept). Default 40.")
                    }
                    putJsonObject("collapse_synthetic") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Fold Kotlin-synthetic classes (companions, lambdas, DefaultImpls, " +
                                "coroutine continuations) into their enclosing class. Default true.",
                        )
                    }
                },
                required = listOf("class_dirs", "target_class"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "static_class_hierarchy") {
                val args = request.arguments
                val scene = SootUpView.forRoot(buildAnalysisRoot(args))
                val targetClass = (args?.get("target_class") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `target_class`.")
                val direction = parseHierarchyDirection(args)
                val depth = intArg(args, "depth", 3)
                val nodeCap = intArg(args, "node_cap", 40)
                val collapseSynthetic = boolArg(args, "collapse_synthetic", true)

                val graph = GraphExtractors.classHierarchy(scene, targetClass, direction, depth, nodeCap, collapseSynthetic)
                graphResult(graph, MermaidRenderer.classDiagram(graph))
            }
        }
    }

    // ---------------- static_call_graph ----------------

    private fun registerCallGraph(server: Server) {
        server.addTool(
            name = "static_call_graph",
            description = "v1.8 — SootUp-backed call graph rooted at `target_method`. " +
                "`direction: callees` (body scan) | `callers` (app-wide body scan) | `both` " +
                "(default), each independently bounded by `depth`/`node_cap`. Edges always " +
                "point caller -> callee. Virtual/interface invokes expand to concrete overrides " +
                "via the type hierarchy, capped at `max_dispatch_targets` (default 5) — overflow " +
                "becomes one '+N more implementers' node. Standalone — no attach/session " +
                "required. Returns nodes/edges/truncated/warnings (primary) plus ascii/mermaid " +
                "(secondary). `method_not_found`/`class_not_found` if target_method doesn't " +
                "resolve; `method_ambiguous` (with `candidates`) if `params` is omitted on an " +
                "overloaded method; `class_dirs_empty_or_missing` if none of class_dirs exist.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    commonClasspathProps()
                    putJsonObject("target_method") {
                        put("type", buildJsonArray { add(JsonPrimitive("string")); add(JsonPrimitive("object")) })
                        put(
                            "description",
                            "Canonical SootUp method signature string (e.g. " +
                                "'<com.example.Foo: void onClick(android.view.View)>'), or " +
                                "{class, name, params?} where params is a list of FQN parameter " +
                                "type names. If params is omitted and the method is overloaded, " +
                                "returns method_ambiguous with candidate signatures.",
                        )
                    }
                    putJsonObject("direction") {
                        put("type", "string")
                        put("description", "callers | callees | both (default both).")
                    }
                    putJsonObject("depth") {
                        put("type", "integer")
                        put("description", "Max BFS depth per direction. Default 3.")
                    }
                    putJsonObject("node_cap") {
                        put("type", "integer")
                        put("description", "Max nodes before truncation (closest to root kept). Default 40.")
                    }
                    putJsonObject("max_dispatch_targets") {
                        put("type", "integer")
                        put(
                            "description",
                            "Max concrete overrides expanded per virtual/interface call site, " +
                                "independent of node_cap. Default 5.",
                        )
                    }
                    putJsonObject("collapse_synthetic") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Fold Kotlin-synthetic classes (companions, lambdas, DefaultImpls, " +
                                "coroutine continuations) into their enclosing class. Default true.",
                        )
                    }
                },
                required = listOf("class_dirs", "target_method"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "static_call_graph") {
                val args = request.arguments
                val scene = SootUpView.forRoot(buildAnalysisRoot(args))

                when (val resolution = GraphExtractors.resolveMethod(scene, parseMethodTarget(args))) {
                    is MethodResolution.Ambiguous -> methodAmbiguousErr(resolution)
                    is MethodResolution.Found -> {
                        val direction = parseCallGraphDirection(args)
                        val depth = intArg(args, "depth", 3)
                        val nodeCap = intArg(args, "node_cap", 40)
                        val maxDispatchTargets = intArg(args, "max_dispatch_targets", 5)
                        val collapseSynthetic = boolArg(args, "collapse_synthetic", true)

                        val graph = GraphExtractors.callGraph(
                            scene, resolution.method, direction, depth, nodeCap, maxDispatchTargets, collapseSynthetic,
                        )
                        graphResult(graph, MermaidRenderer.flowchart(graph))
                    }
                }
            }
        }
    }

    // ---------------- static_cfg ----------------

    private fun registerCfg(server: Server) {
        server.addTool(
            name = "static_cfg",
            description = "v1.8 — SootUp-backed control-flow graph for `target_method`: one node " +
                "per basic block, edges labeled true/false (if-branches), fallthrough " +
                "(other multi-successor blocks), or 'catch <ExceptionType>' (exceptional " +
                "successors). `node_cap` bounds basic-block count (default 100), not statement " +
                "count. Not passed through synthetic-collapse (nodes are blocks within one " +
                "method, not classes). Standalone — no attach/session required. Returns " +
                "nodes/edges/truncated/warnings (primary) plus ascii/mermaid (secondary). " +
                "`method_not_found`/`class_not_found` if target_method doesn't resolve or has " +
                "no body; `method_ambiguous` (with `candidates`) if `params` is omitted on an " +
                "overloaded method; `class_dirs_empty_or_missing` if none of class_dirs exist.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    commonClasspathProps()
                    putJsonObject("target_method") {
                        put("type", buildJsonArray { add(JsonPrimitive("string")); add(JsonPrimitive("object")) })
                        put(
                            "description",
                            "Canonical SootUp method signature string (e.g. " +
                                "'<com.example.Foo: void onClick(android.view.View)>'), or " +
                                "{class, name, params?} where params is a list of FQN parameter " +
                                "type names. If params is omitted and the method is overloaded, " +
                                "returns method_ambiguous with candidate signatures.",
                        )
                    }
                    putJsonObject("node_cap") {
                        put("type", "integer")
                        put("description", "Max basic blocks before truncation. Default 100.")
                    }
                },
                required = listOf("class_dirs", "target_method"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "static_cfg") {
                val args = request.arguments
                val scene = SootUpView.forRoot(buildAnalysisRoot(args))

                when (val resolution = GraphExtractors.resolveMethod(scene, parseMethodTarget(args))) {
                    is MethodResolution.Ambiguous -> methodAmbiguousErr(resolution)
                    is MethodResolution.Found -> {
                        val nodeCap = intArg(args, "node_cap", 100)
                        val graph = GraphExtractors.cfg(scene, resolution.method, nodeCap)
                        graphResult(graph, MermaidRenderer.flowchart(graph))
                    }
                }
            }
        }
    }

    // ---------------- static_package_graph ----------------

    private fun registerPackageGraph(server: Server) {
        server.addTool(
            name = "static_package_graph",
            description = "v1.8 — SootUp-backed package dependency graph. Aggregates application " +
                "classes within `depth` sub-package segments of `root_package` into package-level " +
                "nodes, with edges for cross-package references found in field types, method " +
                "signatures, and statement uses/defs. Standalone — no attach/session required. " +
                "Returns nodes/edges/truncated/warnings (primary; unresolved type references " +
                "are counted into warnings) plus ascii/mermaid (secondary). `class_not_found` if " +
                "no application classes are found under root_package within depth; " +
                "`class_dirs_empty_or_missing` if none of class_dirs exist.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    commonClasspathProps()
                    putJsonObject("root_package") {
                        put("type", "string")
                        put("description", "Java/Kotlin package name, e.g. com.example.app.")
                    }
                    putJsonObject("depth") {
                        put("type", "integer")
                        put("description", "Max sub-package segments below root_package to include. Default 3.")
                    }
                    putJsonObject("node_cap") {
                        put("type", "integer")
                        put("description", "Max package nodes before truncation. Default 40.")
                    }
                    putJsonObject("collapse_synthetic") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Fold Kotlin-synthetic classes (companions, lambdas, DefaultImpls, " +
                                "coroutine continuations) into their enclosing class before " +
                                "computing package references. Default true.",
                        )
                    }
                },
                required = listOf("class_dirs", "root_package"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool(allowsDuringPlan = true, toolName = "static_package_graph") {
                val args = request.arguments
                val scene = SootUpView.forRoot(buildAnalysisRoot(args))
                val rootPackage = (args?.get("root_package") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `root_package`.")
                val depth = intArg(args, "depth", 3)
                val nodeCap = intArg(args, "node_cap", 40)
                val collapseSynthetic = boolArg(args, "collapse_synthetic", true)

                val graph = GraphExtractors.packageGraph(scene, rootPackage, depth, nodeCap, collapseSynthetic)
                graphResult(graph, MermaidRenderer.flowchart(graph))
            }
        }
    }

    // ---------------- shared: schema, arg parsing, response shaping ----------------

    /** `class_dirs`/`extra_classpath`/`android_jar`/`android_api_level` — shared by all four tools. */
    private fun JsonObjectBuilder.commonClasspathProps() {
        putJsonObject("class_dirs") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put(
                "description",
                "Directories of compiled .class output (e.g. build/intermediates/javac/debug/classes, " +
                    "build/tmp/kotlin-classes/debug). Required; at least one must exist on disk.",
            )
        }
        putJsonObject("extra_classpath") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", "Optional extra jar/dir paths (kotlin-stdlib, AndroidX, etc.).")
        }
        putJsonObject("android_jar") {
            put("type", "string")
            put(
                "description",
                "Optional explicit path to android.jar. If omitted, resolved from " +
                    "android_api_level or the latest installed SDK platform under " +
                    "\$ANDROID_HOME/ANDROID_SDK_ROOT.",
            )
        }
        putJsonObject("android_api_level") {
            put("type", "integer")
            put("description", "Optional API level used to resolve android.jar when android_jar is not given.")
        }
    }

    private fun stringList(args: JsonObject?, key: String): List<String> {
        val arr = args?.get(key) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun intArg(args: JsonObject?, key: String, default: Int): Int =
        (args?.get(key) as? JsonPrimitive)?.intOrNull ?: default

    private fun boolArg(args: JsonObject?, key: String, default: Boolean): Boolean =
        (args?.get(key) as? JsonPrimitive)?.booleanOrNull ?: default

    private fun buildAnalysisRoot(args: JsonObject?): AnalysisRoot {
        val classDirs = stringList(args, "class_dirs")
        val extraClasspath = stringList(args, "extra_classpath")
        val androidJarArg = (args?.get("android_jar") as? JsonPrimitive)?.contentOrNull
        val apiLevel = (args?.get("android_api_level") as? JsonPrimitive)?.intOrNull
        val androidJar = androidJarArg ?: apiLevel?.let { AndroidJarResolver.find(it) }
        return AnalysisRoot(classDirs, extraClasspath, androidJar)
    }

    private fun parseHierarchyDirection(args: JsonObject?): Direction {
        val raw = (args?.get("direction") as? JsonPrimitive)?.contentOrNull ?: "both"
        return when (raw) {
            "up" -> Direction.UP
            "down" -> Direction.DOWN
            "both" -> Direction.BOTH
            else -> throw ToolError(ErrorCode.InvalidTarget, "`direction` must be one of: up, down, both.")
        }
    }

    private fun parseCallGraphDirection(args: JsonObject?): Direction {
        val raw = (args?.get("direction") as? JsonPrimitive)?.contentOrNull ?: "both"
        return when (raw) {
            "callers" -> Direction.CALLERS
            "callees" -> Direction.CALLEES
            "both" -> Direction.BOTH
            else -> throw ToolError(ErrorCode.InvalidTarget, "`direction` must be one of: callers, callees, both.")
        }
    }

    /** Parse `target_method` — a canonical signature string, or `{class, name, params?}`. */
    private fun parseMethodTarget(args: JsonObject?): MethodTarget {
        val raw = args?.get("target_method")
            ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `target_method`.")
        return when (raw) {
            is JsonPrimitive -> {
                val s = raw.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "`target_method` must be a string or object.")
                MethodTarget.Signature(s)
            }
            is JsonObject -> {
                val className = (raw["class"] as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "`target_method.class` is required.")
                val methodName = (raw["name"] as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "`target_method.name` is required.")
                val params = (raw["params"] as? JsonArray)?.map {
                    (it as? JsonPrimitive)?.contentOrNull
                        ?: throw ToolError(ErrorCode.InvalidTarget, "`target_method.params` entries must be strings.")
                }
                MethodTarget.ClassNameParams(className, methodName, params)
            }
            else -> throw ToolError(ErrorCode.InvalidTarget, "`target_method` must be a string or object.")
        }
    }

    private fun methodAmbiguousErr(resolution: MethodResolution.Ambiguous) = toolErr(
        code = ErrorCode.MethodAmbiguous,
        message = "target_method is ambiguous — ${resolution.candidates.size} overloads match.",
        hint = "Retry target_method as {class, name, params: [...]} using one of the candidate signatures.",
        extra = { put("candidates", buildJsonArray { for (c in resolution.candidates) add(c) }) },
    )

    private fun nodeJson(node: GNode) = buildJsonObject {
        put("id", node.id)
        put("label", node.label)
        put("kind", node.kind.name.lowercase())
        if (node.collapsedCount > 0) put("collapsed_count", node.collapsedCount)
        if (node.meta.isNotEmpty()) put("meta", buildJsonObject { for ((k, v) in node.meta) put(k, v) })
    }

    private fun edgeJson(edge: GEdge) = buildJsonObject {
        put("from", edge.from)
        put("to", edge.to)
        if (edge.label != null) put("label", edge.label)
    }

    /** Assemble the common `{ ok: true, root_id, nodes, edges, truncated, elided_count, warnings, ascii, mermaid }` reply. */
    private fun graphResult(graph: Graph, mermaid: String) = com.acendas.androiddebugger.toolOk {
        put("root_id", graph.rootId)
        put("nodes", buildJsonArray { for (n in graph.nodes) add(nodeJson(n)) })
        put("edges", buildJsonArray { for (e in graph.edges) add(edgeJson(e)) })
        put("truncated", graph.truncated)
        put("elided_count", graph.elidedCount)
        put("warnings", buildJsonArray { for (w in graph.warnings) add(w) })
        put("ascii", AsciiRenderer.render(graph))
        put("mermaid", mermaid)
    }
}
