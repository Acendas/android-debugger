package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.inspection.Evaluator
import com.acendas.androiddebugger.inspection.ExprParser
import com.acendas.androiddebugger.inspection.LiteralValue
import com.acendas.androiddebugger.inspection.ObjectIdMint
import com.acendas.androiddebugger.inspection.ParseException
import com.acendas.androiddebugger.inspection.RenderedValue
import com.acendas.androiddebugger.inspection.SnapshotBuilder
import com.acendas.androiddebugger.inspection.ValueRenderer
import com.acendas.androiddebugger.inspection.toJson
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ArrayReference
import com.sun.jdi.ObjectReference
import com.sun.jdi.Value
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Read-path MCP tools — frame snapshot, granular drill-downs, evaluate. Per Phase 2.
 */
object InspectionTools {

    fun register(server: Server) {
        registerFrameSnapshot(server)
        registerListThreads(server)
        registerGetFrames(server)
        registerGetLocals(server)
        registerInspectObject(server)
        registerGetArraySlice(server)
        registerEvaluate(server)
        registerEvalMethod(server)
    }

    private fun registerEvaluate(server: Server) {
        server.addTool(
            name = "evaluate",
            description = "Evaluate a small expression in a paused frame and return the rendered value. " +
                "**Supports**: identifiers (`x`), member access (`a.b.c`), method calls with literal " +
                "and identifier args (`list.get(0)`, `\"hello\".length()`, `cache.put(\"k\", item)`), " +
                "and basic casts (`(String) x`). " +
                "**Out of scope**: binary operators (`+`, `==`, `&&`), conditionals, lambdas, array " +
                "indexing, generics, and anything else from the full Java expression language. " +
                "For raw `invokeMethod` access (overload-disambiguated, static targets, ref-id args), " +
                "use the `eval_method` escape-hatch tool. " +
                "Identifiers resolve in this order: frame locals → `this` fields → enclosing-class statics. " +
                "Single-flight per session — concurrent calls receive `vm_paused` immediately. " +
                "10s default timeout; primitives are auto-boxed via `vm.mirrorOf` so ART's verifier accepts them.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("expr") {
                        put("type", "string")
                        put("description", "Expression to evaluate (paths + method calls + literal args).")
                    }
                    putJsonObject("frame_id") {
                        put("type", "string")
                        put("description", "Frame id (frame#<thread>:<idx>) from frame_snapshot or get_frames.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val expr = (request.arguments?.get("expr") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `expr`.")
                val frameId = (request.arguments?.get("frame_id") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `frame_id`.")
                val (threadId, frameIdx) = ObjectIdMint.resolveFrame(frameId)
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Bad frame id `$frameId`.")
                val thread = vm.allThreads().firstOrNull { it.uniqueID() == threadId }
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Thread $threadId not found.")
                val ast = try {
                    ExprParser.parse(expr)
                } catch (e: ParseException) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "Failed to parse expression: ${e.message}",
                        hint = "evaluate supports paths + method calls + literal args. Use eval_method for anything else.",
                    )
                }
                val value = Evaluator.evaluate(thread, frameIdx, ast)
                val rendered = ValueRenderer.render(value)
                toolOk {
                    put("value", rendered.toJson())
                    put("expr", expr)
                }
            }
        }
    }

    private fun registerEvalMethod(server: Server) {
        server.addTool(
            name = "eval_method",
            description = "Escape-hatch raw `invokeMethod` for cases the `evaluate` parser can't handle " +
                "(static methods, overload disambiguation by ref-id args, fully-qualified class targets). " +
                "Args is an array of literal values (string/number/bool/null) or ref-id strings " +
                "(e.g. `obj#12345`) which will be looked up in the object id mint. " +
                "Same single-flight, single-threaded-invoke, primitive-boxing, 10s-timeout discipline as `evaluate`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("frame_id") {
                        put("type", "string")
                        put("description", "Frame id (frame#<thread>:<idx>).")
                    }
                    putJsonObject("target") {
                        put("type", "string")
                        put(
                            "description",
                            "Either `this` (instance method on the current frame's `this`) or a " +
                                "fully-qualified class name (static method on that class).",
                        )
                    }
                    putJsonObject("method") {
                        put("type", "string")
                        put("description", "Method name (no signature — arity-matched then heuristic-picked).")
                    }
                    putJsonObject("args") {
                        put("type", "array")
                        put(
                            "description",
                            "Args list. Strings starting with `obj#` are resolved as object refs; " +
                                "everything else (numbers, booleans, plain strings, null) is passed as a literal.",
                        )
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val frameId = (request.arguments?.get("frame_id") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `frame_id`.")
                val target = (request.arguments?.get("target") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `target`.")
                val methodName = (request.arguments?.get("method") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `method`.")
                val argsJson = request.arguments?.get("args") as? JsonArray
                val (threadId, frameIdx) = ObjectIdMint.resolveFrame(frameId)
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Bad frame id `$frameId`.")
                val thread = vm.allThreads().firstOrNull { it.uniqueID() == threadId }
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Thread $threadId not found.")
                val args: List<Value?> = (argsJson ?: JsonArray(emptyList())).map { argEl ->
                    coerceJsonArgToJdiValue(argEl)
                }
                val value = Evaluator.invokeRaw(thread, frameIdx, target, methodName, args)
                val rendered = ValueRenderer.render(value)
                toolOk {
                    put("value", rendered.toJson())
                    put("target", target)
                    put("method", methodName)
                }
            }
        }
    }

    /**
     * Coerce one MCP-side JSON arg into a JDI [Value]. Strings shaped like `obj#N` are
     * looked up in the id mint; otherwise primitives box via `vm.mirrorOf` (Task 2.1.5.4).
     */
    private fun coerceJsonArgToJdiValue(arg: kotlinx.serialization.json.JsonElement): Value? {
        val vm = Session.requireAttached()
        if (arg !is JsonPrimitive) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "eval_method args must be primitives or ref-id strings; got $arg.",
            )
        }
        if (arg.isString) {
            val s = arg.content
            if (s.startsWith("obj#")) {
                return ObjectIdMint.resolveObject(s) ?: throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "Unknown ref `$s` in args.",
                )
            }
            return vm.mirrorOf(s)
        }
        // JsonPrimitive that isn't a string: bool / number / null.
        arg.booleanOrNull?.let { return vm.mirrorOf(it) }
        arg.longOrNull?.let { v ->
            return if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) vm.mirrorOf(v.toInt())
            else vm.mirrorOf(v)
        }
        arg.intOrNull?.let { return vm.mirrorOf(it) }
        arg.floatOrNull?.let { return vm.mirrorOf(it) }
        arg.doubleOrNull?.let { return vm.mirrorOf(it) }
        if (arg.content == "null") return null
        // Last resort: treat as string.
        return vm.mirrorOf(arg.content)
    }

    private fun registerGetFrames(server: Server) {
        server.addTool(
            name = "get_frames",
            description = "List stack frames of a thread (no locals — use frame_snapshot or get_locals for those). " +
                "Cheap when you only need method/file/line.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("thread_id") {
                        put("type", "integer")
                        put("description", "Thread id from list_threads.")
                    }
                    putJsonObject("depth") {
                        put("type", "integer")
                        put("description", "Max frames. Default 30; max 100.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val threadId = (request.arguments?.get("thread_id") as? JsonPrimitive)?.longOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `thread_id`.")
                val depth = ((request.arguments?.get("depth") as? JsonPrimitive)?.intOrNull ?: 30)
                    .coerceIn(1, 100)
                val thread = vm.allThreads().firstOrNull { it.uniqueID() == threadId }
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Thread $threadId not found.")
                if (!thread.isSuspended) {
                    throw ToolError(
                        errorCode = ErrorCode.VmRunning,
                        message = "Thread $threadId is not suspended.",
                        hint = "Frame info is only readable on suspended threads.",
                    )
                }
                val n = minOf(depth, thread.frameCount())
                toolOk {
                    put("frames", buildJsonArray {
                        for (i in 0 until n) {
                            val f = thread.frame(i)
                            val loc = f.location()
                            add(buildJsonObject {
                                put("index", i)
                                put("frame_id", ObjectIdMint.frameId(thread, i))
                                put("method", loc.method().name())
                                put("class", loc.declaringType().name())
                                try { loc.sourceName()?.let { put("file", it) } } catch (_: AbsentInformationException) {}
                                put("line", loc.lineNumber())
                            })
                        }
                    })
                    put("count", n)
                }
            }
        }
    }

    private fun registerGetLocals(server: Server) {
        server.addTool(
            name = "get_locals",
            description = "List local variables for a paused frame. Returns rendered values in the same shape as " +
                "frame_snapshot.frames[i].locals. Errors `absent_local_variables` on R8/ProGuard-stripped frames.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("frame_id") {
                        put("type", "string")
                        put("description", "Frame id (frame#<thread>:<idx>) from frame_snapshot or get_frames.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                val frameId = (request.arguments?.get("frame_id") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `frame_id`.")
                val (threadId, frameIdx) = ObjectIdMint.resolveFrame(frameId)
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Bad frame id `$frameId`.")
                val thread = vm.allThreads().firstOrNull { it.uniqueID() == threadId }
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Thread $threadId not found.")
                if (!thread.isSuspended) {
                    throw ToolError(ErrorCode.VmRunning, "Thread $threadId is not suspended.")
                }
                val frame = try { thread.frame(frameIdx) } catch (_: Throwable) {
                    throw ToolError(ErrorCode.InvalidTarget, "Frame $frameIdx out of range on thread $threadId.")
                }
                val locals: Map<String, RenderedValue> = try {
                    frame.visibleVariables().associate { v ->
                        v.name() to ValueRenderer.render(frame.getValue(v))
                    }
                } catch (_: AbsentInformationException) {
                    throw ToolError(
                        errorCode = ErrorCode.AbsentLocalVariables,
                        message = "Locals not available for this frame.",
                        hint = "Build appears to be R8/ProGuard-stripped. Rebuild as a debug variant.",
                    )
                }
                toolOk {
                    put("locals", buildJsonObject {
                        for ((n, v) in locals) put(n, v.toJson())
                    })
                }
            }
        }
    }

    private fun registerInspectObject(server: Server) {
        server.addTool(
            name = "inspect_object",
            description = "Inspect a JDI object reference at bounded depth. Pass a `ref` from frame_snapshot " +
                "or another inspect_object call. Returns class, fields with rendered values, and synthetic " +
                "`ref_id`s for nested objects so you can drill in further. Depth caps at 4 to prevent runaway.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("ref") {
                        put("type", "string")
                        put("description", "Object ref id (e.g. obj#12345).")
                    }
                    putJsonObject("depth") {
                        put("type", "integer")
                        put("description", "Levels of nested objects to expand. Default 2; max 4.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val refId = (request.arguments?.get("ref") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `ref` argument.")
                val depth = ((request.arguments?.get("depth") as? JsonPrimitive)?.intOrNull ?: 2)
                    .coerceIn(1, 4)
                val obj = ObjectIdMint.resolveObject(refId) ?: throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "Unknown ref `$refId`.",
                    hint = "Object ids expire when the snapshot is invalidated by resume/step. Re-snapshot before drilling in.",
                )
                toolOk {
                    put("inspection", renderObject(obj, depth))
                }
            }
        }
    }

    private fun renderObject(obj: ObjectReference, depth: Int): JsonObject = buildJsonObject {
        put("class", obj.referenceType().name())
        put("ref_id", ObjectIdMint.registerObject(obj))
        if (depth <= 0) {
            put("fields_collapsed", true)
            return@buildJsonObject
        }
        val fields = obj.referenceType().allFields().filter { !it.isStatic }
        put("fields", buildJsonObject {
            for (field in fields) {
                try {
                    val v = obj.getValue(field)
                    if (v is ObjectReference && v !is com.sun.jdi.StringReference && v !is ArrayReference && depth > 1) {
                        put(field.name(), renderObject(v, depth - 1))
                    } else {
                        put(field.name(), ValueRenderer.render(v).toJson())
                    }
                } catch (t: Throwable) {
                    put(field.name(), buildJsonObject { put("error", t.message ?: "?") })
                }
            }
        })
    }

    private fun registerGetArraySlice(server: Server) {
        server.addTool(
            name = "get_array_slice",
            description = "Read a slice of an array referenced by `ref`. Capped at 1024 elements per call. " +
                "Returns the total length so you know how much remains.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("ref") {
                        put("type", "string")
                        put("description", "Array ref id from frame_snapshot or inspect_object (e.g. obj#12345).")
                    }
                    putJsonObject("start") {
                        put("type", "integer")
                        put("description", "Starting index. Default 0.")
                    }
                    putJsonObject("length") {
                        put("type", "integer")
                        put("description", "Number of elements. Default 64; max 1024.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val refId = (request.arguments?.get("ref") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `ref`.")
                val start = ((request.arguments?.get("start") as? JsonPrimitive)?.intOrNull ?: 0)
                    .coerceAtLeast(0)
                val length = ((request.arguments?.get("length") as? JsonPrimitive)?.intOrNull ?: 64)
                    .coerceIn(1, 1024)
                val obj = ObjectIdMint.resolveObject(refId)
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Unknown ref `$refId`.")
                if (obj !is ArrayReference) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "Ref `$refId` is not an array (it's ${obj.referenceType().name()}).",
                    )
                }
                val total = obj.length()
                val realLength = minOf(length, maxOf(0, total - start))
                val values = if (realLength > 0) obj.getValues(start, realLength) else emptyList()
                toolOk {
                    put("length_total", total)
                    put("start", start)
                    put("returned", realLength)
                    put("slice", buildJsonArray {
                        values.forEach { add(ValueRenderer.render(it).toJson()) }
                    })
                }
            }
        }
    }

    private fun registerFrameSnapshot(server: Server) {
        server.addTool(
            name = "frame_snapshot",
            description = "Get a structured snapshot of the paused VM state — top frames, locals (rendered to bounded strings), watch values, source ref. Bundled to defeat the 're-read the same frame' anti-pattern. Cached by (thread, vm_state_version); invalidated on resume/step. Errors `vm_running` if not paused. The default tool to call after every pause.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("depth") {
                        put("type", "integer")
                        put("description", "How many frames to include from top of stack. Default 5; max 30.")
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val paused = Session.requirePaused()
                val depth = ((request.arguments?.get("depth") as? JsonPrimitive)?.intOrNull ?: 5)
                    .coerceIn(1, 30)

                // Story 2.1.2: snapshot caching keyed on (thread, vmStateVersion).
                val key = paused.uniqueID() to Session.vmStateVersion.get()
                val cached = Session.lastSnapshotKey == key
                val payload: JsonObject = if (cached && Session.lastSnapshotPayload != null) {
                    Session.lastSnapshotPayload!!
                } else {
                    val snap = SnapshotBuilder().build(paused, depth)
                    val json = snap.toJson()
                    Session.lastSnapshotKey = key
                    Session.lastSnapshotPayload = json
                    Session.lastSnapshotDepth = depth
                    json
                }

                toolOk {
                    put("snapshot", payload)
                    put("from_cache", cached)
                    put("vm_state_version", Session.vmStateVersion.get())
                }
            }
        }
    }

    private fun registerListThreads(server: Server) {
        server.addTool(
            name = "list_threads",
            description = "List threads in the attached VM. Returns each as { id, name, status, suspended }. " +
                "Cheap read; doesn't require the VM to be paused.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) {
            runTool {
                val vm = Session.requireAttached()
                val threads = vm.allThreads()
                toolOk {
                    put("threads", buildJsonArray {
                        for (t in threads) {
                            add(buildJsonObject {
                                put("id", t.uniqueID())
                                put("name", t.name())
                                put("status", threadStatus(t))
                                put("suspended", t.isSuspended)
                            })
                        }
                    })
                    put("count", threads.size)
                }
            }
        }
    }

    private fun threadStatus(t: com.sun.jdi.ThreadReference): String = try {
        when (t.status()) {
            com.sun.jdi.ThreadReference.THREAD_STATUS_UNKNOWN -> "unknown"
            com.sun.jdi.ThreadReference.THREAD_STATUS_ZOMBIE -> "zombie"
            com.sun.jdi.ThreadReference.THREAD_STATUS_RUNNING -> "running"
            com.sun.jdi.ThreadReference.THREAD_STATUS_SLEEPING -> "sleeping"
            com.sun.jdi.ThreadReference.THREAD_STATUS_MONITOR -> "monitor"
            com.sun.jdi.ThreadReference.THREAD_STATUS_WAIT -> "wait"
            com.sun.jdi.ThreadReference.THREAD_STATUS_NOT_STARTED -> "not_started"
            else -> "?"
        }
    } catch (_: Throwable) {
        "?"
    }
}
