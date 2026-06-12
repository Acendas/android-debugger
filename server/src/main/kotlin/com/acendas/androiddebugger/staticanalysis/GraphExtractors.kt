package com.acendas.androiddebugger.staticanalysis

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import sootup.core.graph.BasicBlock
import sootup.core.jimple.common.expr.JInterfaceInvokeExpr
import sootup.core.jimple.common.expr.JVirtualInvokeExpr
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.model.ClassModifier
import sootup.core.signatures.MethodSignature
import sootup.core.typehierarchy.TypeHierarchy
import sootup.core.types.ArrayType
import sootup.core.types.ClassType
import sootup.core.types.Type
import sootup.java.core.JavaSootMethod
import sootup.java.core.views.JavaView
import java.util.IdentityHashMap

/**
 * The four `static_*` analyses (AC-1, AC-3, AC-6, AC-8), plus [resolveMethod] for the
 * `target_method` ambiguity contract (AC-5).
 *
 * Every extractor returns a [Graph] — [nodes]/[edges]/[truncated]/[warnings] are the
 * primary, agent-facing contract (see the "Output priority" decision in the v1.8 plan);
 * [AsciiRenderer]/[MermaidRenderer] turn the same [Graph] into the secondary
 * presentation outputs. [classHierarchy], [callGraph], and [packageGraph] pass through
 * [SyntheticCollapse]; [cfg] does not (its nodes are basic blocks within one method, not
 * classes).
 */
object GraphExtractors {

    // ------------------------------------------------------------------
    // AC-1, AC-2, AC-9, AC-10, AC-12 — class hierarchy
    // ------------------------------------------------------------------

    /**
     * BFS over [TypeHierarchy] from [targetClass]. `direction = both` runs the `up` and
     * `down` traversals independently (each with its own [BfsBudget]) and unions the
     * results, mirroring [callGraph]'s `both`.
     *
     * Edge convention (see [MermaidRenderer]'s doc comment): an edge always points from
     * the root-ward node to the away-from-root node. `up` neighbors (superclass/
     * interfaces) are labeled `extends`/`implements`; `down` neighbors (subtypes) are
     * labeled `extended_by`/`implemented_by`.
     */
    fun classHierarchy(
        scene: AnalysisScene,
        targetClass: String,
        direction: Direction,
        depth: Int,
        nodeCap: Int,
        collapseSynthetic: Boolean,
    ): Graph {
        val view = scene.view
        val th = view.typeHierarchy
        val rootType = scene.identifierFactory.getClassType(targetClass)
        if (!th.contains(rootType)) {
            throw ToolError(
                ErrorCode.ClassNotFound,
                "No class named '$targetClass' in this scene.",
                hint = "Check the fully-qualified class name (e.g. com.example.Foo) and that " +
                    "its compiled .class file is reachable from class_dirs/extra_classpath/android_jar.",
            )
        }

        val rootId = rootType.fullyQualifiedName
        val nodes = LinkedHashMap<String, GNode>()
        val edges = mutableListOf<GEdge>()
        val seenEdges = mutableSetOf<Triple<String, String, String?>>()
        nodes[rootId] = nodeForClass(view, rootType)

        var truncated = false
        var elided = 0

        if (direction == Direction.UP || direction == Direction.BOTH) {
            val r = bfsHierarchy(view, th, rootType, goingUp = true, depth, nodeCap)
            mergeInto(nodes, edges, seenEdges, r)
            truncated = truncated || r.truncated
            elided += r.elidedCount
        }
        if (direction == Direction.DOWN || direction == Direction.BOTH) {
            val r = bfsHierarchy(view, th, rootType, goingUp = false, depth, nodeCap)
            mergeInto(nodes, edges, seenEdges, r)
            truncated = truncated || r.truncated
            elided += r.elidedCount
        }

        // AC-12: any neighbor whose class file SootUp couldn't resolve becomes a phantom
        // (view.getClass empty) — count rather than fail.
        val unresolved = nodes.values.count {
            (it.kind == NodeKind.CLASS || it.kind == NodeKind.INTERFACE) &&
                it.id != rootId &&
                !view.getClass(scene.identifierFactory.getClassType(it.id)).isPresent
        }
        val warnings = if (unresolved > 0) listOf("$unresolved unresolved reference(s)") else emptyList()

        val graph = Graph(rootId, nodes.values.toList(), edges, truncated, warnings, elided)
        return SyntheticCollapse.collapse(graph, collapseSynthetic)
    }

    private fun bfsHierarchy(
        view: JavaView,
        th: TypeHierarchy,
        root: ClassType,
        goingUp: Boolean,
        maxDepth: Int,
        nodeCap: Int,
    ): PartialGraph {
        val budget = BfsBudget(nodeCap, maxDepth)
        val rootId = root.fullyQualifiedName
        budget.admit(rootId)

        val nodes = mutableListOf<GNode>()
        val edges = mutableListOf<GEdge>()
        val queue = ArrayDeque<Pair<ClassType, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty()) {
            val (cls, d) = queue.removeFirst()
            val clsId = cls.fullyQualifiedName

            val neighbors: List<Pair<ClassType, String>> = if (goingUp) {
                val result = mutableListOf<Pair<ClassType, String>>()
                if (th.isInterface(cls)) {
                    // Interfaces have no superclass and directlyImplementedInterfacesOf
                    // throws "is not a class" for them — interface-extends-interface is
                    // directlyExtendedInterfacesOf instead.
                    for (iface in th.directlyExtendedInterfacesOf(cls)) {
                        result.add(iface to "extends")
                    }
                } else {
                    val superOpt = th.superClassOf(cls)
                    if (superOpt.isPresent) result.add(superOpt.get() to "extends")
                    for (iface in th.directlyImplementedInterfacesOf(cls)) {
                        result.add(iface to "implements")
                    }
                }
                result
            } else {
                val parentIsInterface = th.isInterface(cls)
                th.directSubtypesOf(cls).map { sub ->
                    sub to if (parentIsInterface) "implemented_by" else "extended_by"
                }.toList()
            }

            if (!budget.canExpand(d)) {
                budget.markUnexploredAtMaxDepth(neighbors.count { !budget.isVisited(it.first.fullyQualifiedName) })
                continue
            }

            for ((neighborType, label) in neighbors) {
                val neighborId = neighborType.fullyQualifiedName
                val isNew = budget.admit(neighborId)
                if (!isNew && !budget.isVisited(neighborId)) continue // node cap hit; skip node + edge
                edges.add(GEdge(clsId, neighborId, label))
                if (isNew) {
                    nodes.add(nodeForClass(view, neighborType))
                    queue.add(neighborType to d + 1)
                }
            }
        }

        return PartialGraph(nodes, edges, budget.truncated, budget.elidedCount)
    }

    private fun nodeForClass(view: JavaView, classType: ClassType): GNode {
        val fqn = classType.fullyQualifiedName
        val classOpt = view.getClass(classType)
        if (!classOpt.isPresent) {
            return GNode(fqn, classType.className, NodeKind.CLASS)
        }
        val cls = classOpt.get()
        val kind = if (cls.isInterface) NodeKind.INTERFACE else NodeKind.CLASS
        val meta = mutableMapOf<String, String>()
        if (ClassModifier.isSynthetic(cls.modifiers)) meta["synthetic"] = "true"
        val superOpt = cls.superclass
        if (superOpt.isPresent) meta["super"] = superOpt.get().fullyQualifiedName
        val interfaces = cls.interfaces
        if (interfaces.isNotEmpty()) meta["interfaces"] = interfaces.joinToString(",") { it.fullyQualifiedName }
        return GNode(fqn, classType.className, kind, meta = meta)
    }

    // ------------------------------------------------------------------
    // AC-5 — method resolution / ambiguity
    // ------------------------------------------------------------------

    /** `target_method` as given by the caller: a canonical signature string, or `{class, name, params?}`. */
    sealed class MethodTarget {
        data class Signature(val raw: String) : MethodTarget()
        data class ClassNameParams(val className: String, val methodName: String, val paramTypes: List<String>?) : MethodTarget()
    }

    /** Result of [resolveMethod] — either a single match, or every overload's signature when `params` was omitted. */
    sealed class MethodResolution {
        data class Found(val method: JavaSootMethod) : MethodResolution()
        data class Ambiguous(val candidates: List<String>) : MethodResolution()
    }

    fun resolveMethod(scene: AnalysisScene, target: MethodTarget): MethodResolution {
        val view = scene.view
        val idFactory = scene.identifierFactory

        return when (target) {
            is MethodTarget.Signature -> {
                val sig = idFactory.parseMethodSignature(target.raw)
                val methodOpt = view.getMethod(sig)
                if (!methodOpt.isPresent) {
                    throw ToolError(
                        ErrorCode.MethodNotFound,
                        "No method matching signature '${target.raw}'.",
                        hint = "Check the canonical signature syntax, e.g. " +
                            "'<com.example.Foo: void onClick(android.view.View)>'.",
                    )
                }
                MethodResolution.Found(methodOpt.get())
            }
            is MethodTarget.ClassNameParams -> {
                val classType = idFactory.getClassType(target.className)
                val classOpt = view.getClass(classType)
                if (!classOpt.isPresent) {
                    throw ToolError(
                        ErrorCode.ClassNotFound,
                        "No class named '${target.className}' in this scene.",
                        hint = "Check the fully-qualified class name and that its compiled " +
                            ".class file is reachable from class_dirs/extra_classpath/android_jar.",
                    )
                }
                val cls = classOpt.get()
                if (target.paramTypes != null) {
                    val paramTypes = target.paramTypes.map { idFactory.getType(it) }
                    val methodOpt = cls.getMethod(target.methodName, paramTypes)
                    if (!methodOpt.isPresent) {
                        throw ToolError(
                            ErrorCode.MethodNotFound,
                            "No method '${target.methodName}(${target.paramTypes.joinToString(",")})' " +
                                "on '${target.className}'.",
                            hint = "Check the method name and parameter type names (FQNs, e.g. java.lang.String).",
                        )
                    }
                    MethodResolution.Found(methodOpt.get())
                } else {
                    val candidates = cls.getMethodsByName(target.methodName)
                    when {
                        candidates.isEmpty() -> throw ToolError(
                            ErrorCode.MethodNotFound,
                            "No method named '${target.methodName}' on '${target.className}'.",
                            hint = "Check the method name.",
                        )
                        candidates.size == 1 -> MethodResolution.Found(candidates.first() as JavaSootMethod)
                        else -> MethodResolution.Ambiguous(candidates.map { it.signature.toString() })
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // AC-3, AC-4, AC-10 — call graph
    // ------------------------------------------------------------------

    /**
     * BFS over direct callees/callers of [target], starting from its own [JavaSootMethod].
     * `direction = both` runs the callees-BFS and callers-BFS independently (each with its
     * own [BfsBudget]) and unions the results.
     *
     * Edges always point from caller to callee, regardless of traversal direction — that's
     * the natural "calls" relationship for [AsciiRenderer]/[MermaidRenderer].
     *
     * Virtual/interface invokes additionally expand to concrete overrides via
     * [TypeHierarchy.subtypesOf], capped at [maxDispatchTargets]; the overflow becomes a
     * single `"+N more implementers"` node, independent of [nodeCap] (AC-10).
     */
    fun callGraph(
        scene: AnalysisScene,
        target: JavaSootMethod,
        direction: Direction,
        depth: Int,
        nodeCap: Int,
        maxDispatchTargets: Int,
        collapseSynthetic: Boolean,
    ): Graph {
        val rootSig = target.signature
        val rootId = rootSig.toString()
        val nodes = LinkedHashMap<String, GNode>()
        val edges = mutableListOf<GEdge>()
        val seenEdges = mutableSetOf<Triple<String, String, String?>>()
        nodes[rootId] = nodeForMethod(scene, rootSig)

        var truncated = false
        var elided = 0

        if (direction == Direction.CALLEES || direction == Direction.BOTH) {
            val r = bfsCallGraph(scene, rootSig, depth, nodeCap, maxDispatchTargets, callees = true)
            mergeInto(nodes, edges, seenEdges, r)
            truncated = truncated || r.truncated
            elided += r.elidedCount
        }
        if (direction == Direction.CALLERS || direction == Direction.BOTH) {
            val r = bfsCallGraph(scene, rootSig, depth, nodeCap, maxDispatchTargets, callees = false)
            mergeInto(nodes, edges, seenEdges, r)
            truncated = truncated || r.truncated
            elided += r.elidedCount
        }

        val graph = Graph(rootId, nodes.values.toList(), edges, truncated, elidedCount = elided)
        return SyntheticCollapse.collapse(graph, collapseSynthetic)
    }

    /** One BFS neighbor: the [node] to admit, the [edge] to it, and (if expandable) its [sig]. */
    private data class CallNeighbor(val node: GNode, val edge: GEdge, val sig: MethodSignature?)

    private fun bfsCallGraph(
        scene: AnalysisScene,
        root: MethodSignature,
        maxDepth: Int,
        nodeCap: Int,
        maxDispatchTargets: Int,
        callees: Boolean,
    ): PartialGraph {
        val budget = BfsBudget(nodeCap, maxDepth)
        budget.admit(root.toString())

        val nodes = mutableListOf<GNode>()
        val edges = mutableListOf<GEdge>()
        val queue = ArrayDeque<Pair<MethodSignature, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty()) {
            val (sig, d) = queue.removeFirst()

            val neighbors: List<CallNeighbor> = if (callees) {
                calleeNeighbors(scene, sig, maxDispatchTargets)
            } else {
                val methodOpt = scene.view.getMethod(sig)
                if (methodOpt.isPresent) callerNeighbors(scene, methodOpt.get()) else emptyList()
            }

            if (!budget.canExpand(d)) {
                budget.markUnexploredAtMaxDepth(neighbors.count { !budget.isVisited(it.node.id) })
                continue
            }

            for (cn in neighbors) {
                val isNew = budget.admit(cn.node.id)
                if (!isNew && !budget.isVisited(cn.node.id)) continue // node cap hit; skip node + edge
                edges.add(cn.edge)
                if (isNew) {
                    nodes.add(cn.node)
                    if (cn.sig != null) queue.add(cn.sig to d + 1)
                }
            }
        }

        return PartialGraph(nodes, edges, budget.truncated, budget.elidedCount)
    }

    /** Direct callees of [sig]'s body, with virtual/interface dispatch expanded (AC-3). */
    private fun calleeNeighbors(scene: AnalysisScene, sig: MethodSignature, maxDispatchTargets: Int): List<CallNeighbor> {
        val view = scene.view
        val th = view.typeHierarchy
        val methodOpt = view.getMethod(sig)
        if (!methodOpt.isPresent) return emptyList()
        val method = methodOpt.get()
        if (!method.hasBody()) return emptyList()

        val currentId = sig.toString()
        val result = mutableListOf<CallNeighbor>()
        val seenDeclared = mutableSetOf<String>()

        for (stmt in method.body.stmts) {
            if (!stmt.isInvokableStmt) continue
            val invokeOpt = stmt.asInvokableStmt().invokeExpr
            if (!invokeOpt.isPresent) continue
            val invokeExpr = invokeOpt.get()
            val declaredSig = invokeExpr.methodSignature
            val declaredId = declaredSig.toString()

            if (seenDeclared.add(declaredId)) {
                result.add(CallNeighbor(nodeForMethod(scene, declaredSig), GEdge(currentId, declaredId, null), declaredSig))
            }

            if (invokeExpr is JVirtualInvokeExpr || invokeExpr is JInterfaceInvokeExpr) {
                val declClass = declaredSig.declClassType
                val implementers = th.subtypesOf(declClass).toList()
                    .mapNotNull { sub -> view.getClass(sub).flatMap { it.getMethod(declaredSig.subSignature) }.orElse(null) }
                    .filter { it.isConcrete }
                    .map { it.signature }
                    .distinct()

                val capped = implementers.take(maxDispatchTargets)
                for (implSig in capped) {
                    val implId = implSig.toString()
                    result.add(CallNeighbor(nodeForMethod(scene, implSig), GEdge(declaredId, implId, "overridden_by"), implSig))
                }
                val overflow = implementers.size - capped.size
                if (overflow > 0) {
                    val overflowId = "$declaredId#more"
                    val overflowNode = GNode(overflowId, "+$overflow more implementers", NodeKind.METHOD)
                    result.add(CallNeighbor(overflowNode, GEdge(declaredId, overflowId, "overridden_by"), null))
                }
            }
        }

        return result
    }

    /** App-code methods whose body contains an invoke matching (or overriding-and-invoked-as) [target] (AC-4). */
    private fun callerNeighbors(scene: AnalysisScene, target: JavaSootMethod): List<CallNeighbor> {
        val view = scene.view
        val th = view.typeHierarchy
        val targetSig = target.signature
        val targetId = targetSig.toString()
        val result = mutableListOf<CallNeighbor>()

        for (className in scene.applicationClassNames) {
            val classOpt = view.getClass(scene.identifierFactory.getClassType(className))
            if (!classOpt.isPresent) continue
            for (m in classOpt.get().methods) {
                if (!m.hasBody()) continue
                var matched = false
                for (stmt in m.body.stmts) {
                    if (!stmt.isInvokableStmt) continue
                    val invokeOpt = stmt.asInvokableStmt().invokeExpr
                    if (!invokeOpt.isPresent) continue
                    if (matchesTarget(invokeOpt.get().methodSignature, targetSig, th)) {
                        matched = true
                        break
                    }
                }
                if (matched) {
                    val callerSig = m.signature
                    result.add(CallNeighbor(nodeForMethod(scene, callerSig), GEdge(callerSig.toString(), targetId, null), callerSig))
                }
            }
        }

        return result
    }

    /** True if invoking [invokeSig] could dispatch to [target] — same sub-signature, related declaring types. */
    private fun matchesTarget(invokeSig: MethodSignature, target: MethodSignature, th: TypeHierarchy): Boolean {
        if (invokeSig.subSignature != target.subSignature) return false
        if (invokeSig.declClassType == target.declClassType) return true
        return th.isSubtype(invokeSig.declClassType, target.declClassType) ||
            th.isSubtype(target.declClassType, invokeSig.declClassType)
    }

    private fun nodeForMethod(scene: AnalysisScene, sig: MethodSignature): GNode {
        val id = sig.toString()
        val label = methodLabel(sig)
        val meta = mutableMapOf<String, String>()
        val methodOpt = scene.view.getMethod(sig)
        if (!methodOpt.isPresent || !methodOpt.get().hasBody()) {
            meta["no_body"] = "true"
        }
        return GNode(id, label, NodeKind.METHOD, meta = meta)
    }

    private fun methodLabel(sig: MethodSignature): String {
        val params = sig.parameterTypes.joinToString(",") { shortTypeName(it) }
        return "${sig.declClassType.className}.${sig.name}($params)"
    }

    private fun shortTypeName(type: Type): String {
        val s = type.toString()
        val idx = s.lastIndexOf('.')
        return if (idx >= 0) s.substring(idx + 1) else s
    }

    // ------------------------------------------------------------------
    // AC-6, AC-7 — control-flow graph
    // ------------------------------------------------------------------

    /**
     * One [GNode] per basic block of [target]'s [sootup.core.graph.StmtGraph], with
     * `true`/`false` branch edges for [JIfStmt] tails, `fallthrough` for other
     * multi-successor blocks, and `catch <ExceptionType>` for
     * [BasicBlock.getExceptionalSuccessors]. Block ids are `bb<N>` (index into
     * [sootup.core.graph.StmtGraph.getBlocksSorted]) via an [IdentityHashMap] — `BasicBlock`
     * doesn't implement `equals`/`hashCode`. No depth bound, only [nodeCap] (AC-10 applies
     * to CFG via basic-block count); not passed through [SyntheticCollapse].
     */
    fun cfg(scene: AnalysisScene, target: JavaSootMethod, nodeCap: Int): Graph {
        if (!target.hasBody()) {
            throw ToolError(
                ErrorCode.MethodNotFound,
                "Method '${target.signature}' has no body (abstract/native/interface) — no CFG available.",
                hint = "Pick a concrete method with a body.",
            )
        }

        val stmtGraph = target.body.stmtGraph
        val blocks = stmtGraph.blocksSorted
        val ids = IdentityHashMap<BasicBlock<*>, String>()
        for ((i, b) in blocks.withIndex()) ids[b] = "bb$i"

        val start = stmtGraph.startingStmtBlock
        val rootId = ids[start] ?: "bb0"

        val budget = BfsBudget(nodeCap, Int.MAX_VALUE)
        val nodes = mutableListOf<GNode>()
        val edges = mutableListOf<GEdge>()
        val seenEdges = mutableSetOf<Triple<String, String, String?>>()
        val queue = ArrayDeque<BasicBlock<*>>()

        budget.admit(rootId)
        nodes.add(nodeForBlock(rootId, start))
        queue.add(start)

        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            val blockId = ids[block] ?: continue

            for ((succ, label) in blockSuccessors(block)) {
                val succId = ids[succ] ?: continue
                val isNew = budget.admit(succId)
                if (!isNew && !budget.isVisited(succId)) continue // node cap hit; skip node + edge
                if (seenEdges.add(Triple(blockId, succId, label))) {
                    edges.add(GEdge(blockId, succId, label))
                }
                if (isNew) {
                    nodes.add(nodeForBlock(succId, succ))
                    queue.add(succ)
                }
            }
        }

        return Graph(rootId, nodes, edges, budget.truncated, elidedCount = budget.elidedCount)
    }

    /** Successor blocks of [block] with branch/fallthrough/exception labels. */
    private fun blockSuccessors(block: BasicBlock<*>): List<Pair<BasicBlock<*>, String?>> {
        val result = mutableListOf<Pair<BasicBlock<*>, String?>>()
        val tail = block.tail
        val successors = block.successors

        when {
            tail is JIfStmt && successors.size == 2 -> {
                for ((idx, succ) in successors.withIndex()) {
                    val label = when (idx) {
                        JIfStmt.TRUE_BRANCH_IDX -> "true"
                        JIfStmt.FALSE_BRANCH_IDX -> "false"
                        else -> null
                    }
                    result.add(succ to label)
                }
            }
            successors.size > 1 -> for (succ in successors) result.add(succ to "fallthrough")
            else -> for (succ in successors) result.add(succ to null)
        }

        for ((excType, succ) in block.exceptionalSuccessors) {
            result.add(succ to "catch ${excType.className}")
        }

        return result
    }

    private fun nodeForBlock(id: String, block: BasicBlock<*>): GNode {
        val label = block.stmts.joinToString("; ") { stmtLabel(it) }
        return GNode(id, label.ifBlank { "(empty)" }, NodeKind.STMT)
    }

    private fun stmtLabel(stmt: Any): String {
        val s = stmt.toString().trim()
        return if (s.length > 60) s.take(57) + "..." else s
    }

    // ------------------------------------------------------------------
    // AC-8, AC-12 — package dependency graph
    // ------------------------------------------------------------------

    /**
     * Package-level [Graph] over the in-scope application classes — those under
     * [rootPackage] within [depth] sub-package segments — built by scanning each class's
     * field types, method signatures, and statement uses/defs for cross-package
     * [ClassType] references (recursing through [ArrayType.getBaseType]).
     *
     * In-scope packages are admitted first; out-of-scope referenced packages are admitted
     * as leaf nodes up to [nodeCap]. Unresolvable referenced types are counted into
     * `warnings` (AC-12) rather than failing.
     */
    fun packageGraph(
        scene: AnalysisScene,
        rootPackage: String,
        depth: Int,
        nodeCap: Int,
        collapseSynthetic: Boolean,
    ): Graph {
        val view = scene.view
        val th = view.typeHierarchy
        val idFactory = scene.identifierFactory

        fun relativeDepth(pkg: String): Int = when {
            pkg == rootPackage -> 0
            pkg.startsWith("$rootPackage.") -> pkg.removePrefix("$rootPackage.").count { it == '.' } + 1
            else -> -1
        }

        val classesByPackage = LinkedHashMap<String, MutableList<ClassType>>()
        for (className in scene.applicationClassNames.sorted()) {
            val classType = idFactory.getClassType(className)
            val pkg = classType.packageName.name
            val rel = relativeDepth(pkg)
            if (rel < 0 || rel > depth) continue
            classesByPackage.getOrPut(pkg) { mutableListOf() }.add(classType)
        }

        if (classesByPackage.isEmpty()) {
            throw ToolError(
                ErrorCode.ClassNotFound,
                "No application classes found under package '$rootPackage' (within depth $depth).",
                hint = "Check root_package and depth, and that class_dirs contains compiled output for that package.",
            )
        }

        val budget = BfsBudget(nodeCap, Int.MAX_VALUE)
        val nodes = mutableListOf<GNode>()
        val edges = mutableListOf<GEdge>()
        val seenEdges = mutableSetOf<Triple<String, String, String?>>()

        fun admitPackage(id: String): Boolean {
            if (budget.isVisited(id)) return true
            return if (budget.admit(id)) {
                nodes.add(GNode(id, id, NodeKind.PACKAGE))
                true
            } else {
                false
            }
        }

        admitPackage(rootPackage)
        for (pkg in classesByPackage.keys.sortedWith(compareBy({ relativeDepth(it) }, { it }))) {
            admitPackage(pkg)
        }

        var unresolved = 0
        for ((pkg, classes) in classesByPackage) {
            if (!budget.isVisited(pkg)) continue
            for (classType in classes) {
                val classOpt = view.getClass(classType)
                if (!classOpt.isPresent) continue
                val cls = classOpt.get()

                val referenced = mutableSetOf<ClassType>()
                for (field in cls.fields) collectClassTypes(field.type, referenced)
                for (method in cls.methods) {
                    for (p in method.signature.parameterTypes) collectClassTypes(p, referenced)
                    if (method.hasBody()) {
                        for (stmt in method.body.stmts) {
                            for (v in stmt.usesAndDefs) collectClassTypes(v.type, referenced)
                        }
                    }
                }

                for (ref in referenced) {
                    if (ref == classType) continue
                    if (!th.contains(ref)) {
                        unresolved++
                        continue
                    }
                    val refPkg = ref.packageName.name
                    if (refPkg == pkg) continue
                    if (admitPackage(refPkg) && seenEdges.add(Triple(pkg, refPkg, null as String?))) {
                        edges.add(GEdge(pkg, refPkg, null))
                    }
                }
            }
        }

        val warnings = if (unresolved > 0) listOf("$unresolved unresolved reference(s)") else emptyList()
        val graph = Graph(rootPackage, nodes, edges, budget.truncated, warnings, budget.elidedCount)
        return SyntheticCollapse.collapse(graph, collapseSynthetic)
    }

    /** Recurse through array element types to the underlying [ClassType], if any. */
    private fun collectClassTypes(type: Type, out: MutableSet<ClassType>) {
        when (type) {
            is ArrayType -> collectClassTypes(type.baseType, out)
            is ClassType -> out.add(type)
            else -> {}
        }
    }

    // ------------------------------------------------------------------
    // shared
    // ------------------------------------------------------------------

    private data class PartialGraph(val nodes: List<GNode>, val edges: List<GEdge>, val truncated: Boolean, val elidedCount: Int)

    private fun mergeInto(
        nodes: LinkedHashMap<String, GNode>,
        edges: MutableList<GEdge>,
        seenEdges: MutableSet<Triple<String, String, String?>>,
        partial: PartialGraph,
    ) {
        for (n in partial.nodes) nodes.putIfAbsent(n.id, n)
        for (e in partial.edges) {
            if (seenEdges.add(Triple(e.from, e.to, e.label))) edges.add(e)
        }
    }
}
