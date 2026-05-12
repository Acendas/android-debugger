package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.hotswap.ClassDiff
import com.acendas.androiddebugger.hotswap.SnapshotStore
import com.acendas.androiddebugger.inspection.VmCoordinator
import com.acendas.androiddebugger.jvmti.AgentHotSwap
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolErr
import com.acendas.androiddebugger.toolOk
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * v1.5 — three HotSwap tools, all gated on an attached JVMTI agent that has
 * `can_redefine_classes && can_retransform_classes`. Per spec §3.
 *
 *   - `hot_swap_class` — single class redefine.
 *   - `hot_swap_classes` — atomic batch redefine.
 *   - `hot_swap_revert` — restore the original (pre-attach) class bytes from
 *     the agent's ClassFileLoadHook cache.
 *
 * Every tool routes through [VmCoordinator] so it can't race against an
 * `eval_method` invocation (the two operate on the same class metadata).
 */
object HotSwapTools {

    fun register(server: Server) {
        registerHotSwapClass(server)
        registerHotSwapClasses(server)
        registerHotSwapRevert(server)
    }

    // ---------------- hot_swap_class ----------------

    private fun registerHotSwapClass(server: Server) {
        server.addTool(
            name = "hot_swap_class",
            description = "v1.5 — redefine the body of a single loaded class using JVMTI's RedefineClasses. " +
                "Accepts a JVM `.class` byte array (base64); the server dexes it via embedded d8 at the " +
                "attached device's API level and hands the resulting single-class dex to ART. " +
                "**Restrictions** (ART enforces; we pre-validate the common cases): only method bodies may " +
                "change. Adding/removing methods, changing signatures, modifying fields, changing the " +
                "superclass or interface set, or flipping the class access flags all REJECT with a " +
                "structured diff. Use `force_re_enter: true` (capability-gated on `can_pop_frame`) to pop " +
                "the swapped method's stack frames so the next call lands on the new code; otherwise " +
                "existing frames continue to run the old code until they exit naturally. " +
                "Returns: redefined class FQN, dex_sha256, active_frames_using_old_code (per-thread), " +
                "any warnings (e.g. @Composable detected, coroutine state machine). " +
                "Refuses with `minified_build_unsupported` if the attached app is a R8/ProGuard-minified " +
                "debug build (`agent_info.minify_detected`).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class") {
                        put("type", "string")
                        put("description", "FQN of the class to redefine (dot notation, e.g. com.example.app.LoginActivity).")
                    }
                    putJsonObject("class_bytes_b64") {
                        put("type", "string")
                        put("description", "Base64-encoded `.class` file bytes (JVM bytecode from kotlinc/javac).")
                    }
                    putJsonObject("force_re_enter") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Pop active frames in the swapped class so threads re-enter on the new code. " +
                                "Capability-gated: returns `capability_unavailable` if the device's ART " +
                                "reports `can_pop_frame=false`. Default false.",
                        )
                    }
                    putJsonObject("include_bytecode") {
                        put("type", "boolean")
                        put("description", "Echo the dex sha256 in the response. Default true.")
                    }
                },
                required = listOf("class", "class_bytes_b64"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val fqn = (request.arguments?.get("class") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing required argument: class")
                val classB64 = (request.arguments?.get("class_bytes_b64") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing required argument: class_bytes_b64")
                val forceReEnter = (request.arguments?.get("force_re_enter") as? JsonPrimitive)?.booleanOrNull ?: false
                val includeBytecode = (request.arguments?.get("include_bytecode") as? JsonPrimitive)?.booleanOrNull ?: true

                val classBytes = try {
                    Base64.getDecoder().decode(classB64)
                } catch (_: IllegalArgumentException) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "class_bytes_b64 is not valid base64.",
                    )
                }

                doSwap(
                    entries = listOf(SwapInput(fqn = fqn, classBytes = classBytes)),
                    forceReEnter = forceReEnter,
                    includeBytecode = includeBytecode,
                    isBatch = false,
                )
            }
        }
    }

    // ---------------- hot_swap_classes ----------------

    private fun registerHotSwapClasses(server: Server) {
        server.addTool(
            name = "hot_swap_classes",
            description = "v1.5 — atomic batch variant of `hot_swap_class`. Accepts an array of " +
                "{ class, class_bytes_b64 } entries. JVMTI's RedefineClasses is natively atomic: either " +
                "every entry redefines or none. Pre-validation runs on every entry first; if ANY entry " +
                "fails (shape-change violation, dex error), the entire batch is rejected with a per-entry " +
                "`failures` array — nothing in the VM is modified. " +
                "Use this when a single source-file edit produces multiple changed classes (Kotlin lambdas " +
                "compile to `Foo\$bar\$1.class` siblings, sealed-class hierarchies produce companion " +
                "classes, etc.). Atomicity matters: a partial swap leaves the outer method calling stale " +
                "inner-class bodies. " +
                "Same `force_re_enter` semantics as hot_swap_class — applies to all swapped classes.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("entries") {
                        put("type", "array")
                        put("description", "Array of `{ class, class_bytes_b64 }`. Must have at least one entry.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("class") { put("type", "string") }
                                putJsonObject("class_bytes_b64") { put("type", "string") }
                            }
                            put("required", buildJsonArray {
                                add("class"); add("class_bytes_b64")
                            })
                        }
                    }
                    putJsonObject("force_re_enter") {
                        put("type", "boolean")
                        put("description", "Same as hot_swap_class. Default false.")
                    }
                    putJsonObject("include_bytecode") {
                        put("type", "boolean")
                        put("description", "Echo dex sha256s in the response. Default true.")
                    }
                },
                required = listOf("entries"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val arr = request.arguments?.get("entries") as? JsonArray
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing required argument: entries (array)")
                if (arr.isEmpty()) {
                    throw ToolError(ErrorCode.InvalidTarget, "entries array is empty")
                }
                val inputs = arr.mapIndexed { idx, el ->
                    val obj = el as? JsonObject
                        ?: throw ToolError(ErrorCode.InvalidTarget, "entries[$idx] is not an object")
                    val fqn = (obj["class"] as? JsonPrimitive)?.contentOrNull
                        ?: throw ToolError(ErrorCode.InvalidTarget, "entries[$idx].class missing")
                    val b64 = (obj["class_bytes_b64"] as? JsonPrimitive)?.contentOrNull
                        ?: throw ToolError(ErrorCode.InvalidTarget, "entries[$idx].class_bytes_b64 missing")
                    val bytes = try {
                        Base64.getDecoder().decode(b64)
                    } catch (_: IllegalArgumentException) {
                        throw ToolError(ErrorCode.InvalidTarget, "entries[$idx].class_bytes_b64 not valid base64")
                    }
                    SwapInput(fqn = fqn, classBytes = bytes)
                }
                val forceReEnter = (request.arguments?.get("force_re_enter") as? JsonPrimitive)?.booleanOrNull ?: false
                val includeBytecode = (request.arguments?.get("include_bytecode") as? JsonPrimitive)?.booleanOrNull ?: true

                doSwap(
                    entries = inputs,
                    forceReEnter = forceReEnter,
                    includeBytecode = includeBytecode,
                    isBatch = true,
                )
            }
        }
    }

    // ---------------- hot_swap_revert ----------------

    private fun registerHotSwapRevert(server: Server) {
        server.addTool(
            name = "hot_swap_revert",
            description = "v1.5 — restore a previously swapped class to its pre-attach original bytes. " +
                "Source of truth is the agent's ClassFileLoadHook cache (the bytes ART handed the agent " +
                "the FIRST time the class loaded). Per session, not persistent — app restart wipes the " +
                "cache. " +
                "**Caveat:** classes loaded BEFORE the agent attached are not in the cache; revert returns " +
                "`class_bytes_not_cached` for those. Practical impact: small — most user-touched code is " +
                "reached by post-attach interaction. " +
                "Pass `class` to revert one specific FQN, or omit to revert every class swapped this session.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class") {
                        put("type", "string")
                        put("description", "FQN to revert. Omit to revert every class swapped this session.")
                    }
                    putJsonObject("force_re_enter") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Pop active frames so threads re-enter on the reverted code. " +
                                "**Default: matches `agent_info.force_re_enter_supported`** — true when " +
                                "ART reports `can_pop_frame: true`, false otherwise. This avoids the " +
                                "common API-26 trap where ART has no PopFrame and a default-true revert " +
                                "would always refuse with capability_unavailable. Pass explicitly to override.",
                        )
                    }
                },
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                val target = (request.arguments?.get("class") as? JsonPrimitive)?.contentOrNull
                // Default force_re_enter to the device's PopFrame capability so reverts on
                // API-26-class ART (where can_pop_frame=false) don't hard-fail. Smoke caught
                // this: every revert was tripping capability_unavailable on the VAR_SOM_MX6.
                val canPopFrameDefault = (Session.agentState?.capabilities
                    ?.get("can_pop_frame") as? JsonPrimitive)?.booleanOrNull == true
                val forceReEnter = (request.arguments?.get("force_re_enter") as? JsonPrimitive)
                    ?.booleanOrNull ?: canPopFrameDefault
                val toRevert = if (target != null) {
                    if (!Session.snapshotStore.contains(target)) {
                        return@runTool toolErr(
                            code = ErrorCode.InvalidTarget,
                            message = "No snapshot stored for $target.",
                            hint = "hot_swap_revert can only restore classes that this session's hot_swap_class " +
                                "captured a snapshot for. The agent caches bytes for classes loaded after " +
                                "attach; classes loaded earlier need a process restart.",
                        )
                    }
                    listOf(target)
                } else {
                    Session.snapshotStore.keys()
                }
                if (toRevert.isEmpty()) {
                    return@runTool toolOk {
                        put("reverted", buildJsonArray { })
                        put("no_snapshot", buildJsonArray { })
                        put("count", 0)
                    }
                }
                // Build the swap entries from the snapshot store. Each snapshot is a JVM `.class`
                // (the agent's ClassFileLoadHook captured the original .class form). We re-run
                // the dexer over them so revert uses the same code path as forward swap.
                val inputs = toRevert.mapNotNull { fqn ->
                    val snap = Session.snapshotStore.get(fqn) ?: return@mapNotNull null
                    SwapInput(fqn = fqn, classBytes = snap.classBytes, isRevert = true)
                }
                doSwap(
                    entries = inputs,
                    forceReEnter = forceReEnter,
                    includeBytecode = false,
                    isBatch = inputs.size > 1,
                ).also {
                    // Successfully reverted entries are removed from the snapshot store so a
                    // second revert call doesn't double-process. Pre-validate violations
                    // (shouldn't happen on revert, but be defensive) leave the snapshot in
                    // place; the doSwap caller already returned the structured error in that
                    // case so we won't reach here.
                    for (input in inputs) Session.snapshotStore.remove(input.fqn)
                }
            }
        }
    }

    // ---------------- shared swap pipeline ----------------

    private data class SwapInput(val fqn: String, val classBytes: ByteArray, val isRevert: Boolean = false)

    private suspend fun doSwap(
        entries: List<SwapInput>,
        forceReEnter: Boolean,
        includeBytecode: Boolean,
        isBatch: Boolean,
    ) = runSwap(entries, forceReEnter, includeBytecode, isBatch)

    private suspend fun runSwap(
        entries: List<SwapInput>,
        forceReEnter: Boolean,
        includeBytecode: Boolean,
        isBatch: Boolean,
    ): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult {
        // Pre-flight gating — needs the agent loaded AND HotSwap-supported.
        val vm = Session.requireAttached()
        val agentClient = Session.agentClient
            ?: return toolErr(
                code = ErrorCode.CapabilityUnavailable,
                message = "JVMTI agent not loaded in the target app — HotSwap unavailable.",
                hint = "Re-run /android-debugger:attach without `load_agent: false`.",
            )
        if (Session.minifyDetected) {
            return toolErr(
                code = ErrorCode.InvalidTarget,
                message = "minified_build_unsupported: target app's debug variant has minification " +
                    "enabled. After R8/ProGuard rewrites class names, JVMTI can't match the FQN you " +
                    "asked to swap.",
                hint = "Set `minifyEnabled=false` on the debug build variant and rebuild.",
            )
        }
        val agentState = Session.agentState
        val caps = agentState?.capabilities
        val canRedefine = (caps?.get("can_redefine_classes") as? JsonPrimitive)?.booleanOrNull == true
        if (!canRedefine) {
            return toolErr(
                code = ErrorCode.CapabilityUnavailable,
                message = "ART on this device reports `can_redefine_classes=false` — HotSwap unsupported.",
                hint = "JVMTI's RedefineClasses isn't available on this Android version. Tip: API 26+ " +
                    "is required for ART's JVMTI; older devices have no path.",
            )
        }
        val canPopFrame = (caps?.get("can_pop_frame") as? JsonPrimitive)?.booleanOrNull == true
        if (forceReEnter && !canPopFrame) {
            return toolErr(
                code = ErrorCode.CapabilityUnavailable,
                message = "force_re_enter requested but ART reports `can_pop_frame=false`.",
                hint = "Retry with force_re_enter=false. The swap will install but active frames " +
                    "continue with old code until they exit and re-enter.",
            )
        }
        val dexer = Session.dexer
            ?: return toolErr(
                code = ErrorCode.Internal,
                message = "Internal: Session.dexer is null after attach. This is a bug — please report.",
            )

        // Phase 1: locate jclass-equivalent metadata + pre-validate every entry. Atomic
        // — if any entry fails, we return the whole batch without invoking JVMTI.
        val validations = mutableListOf<Validation>()
        for (input in entries) {
            val refType = resolveLoadedClass(vm, input.fqn)
            // Behavioral-smoke finding (P10): on ART API 26, classes loaded by user
            // class loaders (e.g., via `Class.forName(name, true, appClassLoader)`)
            // are NOT always returned by JDI's `vm.classesByName(fqn)` — even when
            // we just successfully invoked a method on them through eval_method.
            // The agent's `GetLoadedClasses` is authoritative; defer to it.
            // We skip the JDI-side pre-validate diff for these classes (no diff
            // comparison happens since `refType == null` would block the cached-
            // original lookup anyway), and hand the bytes straight to JVMTI.
            // Active-frames and multi-loader detection get null defaults.
            val additionalCopies = if (refType != null) countLoadedCopies(vm, input.fqn) - 1 else 0

            // Capture original bytes for revert BEFORE we redefine. Skip on revert path
            // (we're already restoring from the snapshot).
            if (!input.isRevert) {
                captureSnapshotIfPossible(agentClient, input.fqn, refType)
            }

            // Pre-validate shape change against the bytes the agent has cached (or the
            // best-available proxy — if no snapshot exists, we skip shape diff and let
            // JVMTI reject if the new bytes are incompatible).
            val cachedOriginal = Session.snapshotStore.get(input.fqn)?.classBytes
            val diffResult = if (cachedOriginal != null) {
                runCatching { ClassDiff.diff(cachedOriginal, input.classBytes) }.getOrNull()
            } else null

            if (diffResult != null && diffResult.hasViolations) {
                validations.add(
                    Validation.Failure(
                        fqn = input.fqn,
                        code = "redefine_unsupported_shape_change",
                        message = "Cannot redefine `${input.fqn}` — shape change detected " +
                            "(${diffResult.violations.size} violation(s)).",
                        hint = "JVMTI on ART only allows method-body changes. See `diff` for specifics.",
                        diff = ClassDiff.violationsToJson(diffResult.violations),
                        warnings = ClassDiff.warningsToJson(diffResult.warnings),
                    ),
                )
                continue
            }

            // Dex the bytes.
            val dexBytes = try {
                dexer.dexSingleClass(input.classBytes, input.fqn)
            } catch (te: ToolError) {
                validations.add(
                    Validation.Failure(
                        fqn = input.fqn,
                        code = "dex_failed",
                        message = te.message ?: "d8 dexing failed",
                        hint = te.hint,
                    ),
                )
                continue
            }
            validations.add(
                Validation.Success(
                    fqn = input.fqn,
                    classBytes = input.classBytes,
                    dexBytes = dexBytes,
                    refType = refType,
                    additionalCopies = additionalCopies,
                    warnings = diffResult?.let { ClassDiff.warningsToJson(it.warnings) },
                ),
            )
        }

        val failures = validations.filterIsInstance<Validation.Failure>()
        if (failures.isNotEmpty()) {
            // Atomic: any failure aborts the whole batch (spec §3.2 / §10.4-option-1).
            return toolErr(
                code = ErrorCode.InvalidTarget,
                message = if (isBatch) "batch_validation_failed: one or more entries refused" else failures.first().message,
                hint = if (isBatch) "Fix the listed entries; the whole batch was rolled back." else failures.first().hint,
            ).withFailureDetails(failures)
        }

        val successes = validations.filterIsInstance<Validation.Success>()
        // Capture pre-swap active-frames for each class so we can report them.
        val activeFramesByFqn = successes.associate { it.fqn to findActiveFrames(vm, it.fqn) }

        // Phase 2: invoke RedefineClasses through the coordinator. Single batch RPC.
        val redefineEntries = successes.map {
            AgentHotSwap.RedefineEntry(
                classSignature = toJvmSignature(it.fqn),
                dexBytes = it.dexBytes,
            )
        }
        val redefined: List<String> = try {
            VmCoordinator.withExclusiveAccess("hot_swap", timeoutMs = VmCoordinator.DEFAULT_TIMEOUT_MS) {
                AgentHotSwap.redefineClasses(agentClient, redefineEntries)
            }
        } catch (te: ToolError) {
            return toolErr(
                code = te.errorCode,
                message = te.message ?: "redefine failed",
                hint = te.hint,
                currentState = te.currentState,
            )
        }

        // Phase 3: optional pop-frame to force re-enter on the swapped methods.
        val poppedThreads = mutableListOf<String>()
        if (forceReEnter) {
            // Pop frames on every thread that has an active frame in any swapped class.
            val threadsToPop = activeFramesByFqn.values
                .flatten()
                .map { it.first /* thread name */ }
                .toSet()
            for (threadName in threadsToPop) {
                runCatching {
                    AgentHotSwap.popFrame(agentClient, threadName, framesToPop = 1)
                    poppedThreads.add(threadName)
                }
            }
        }

        return toolOk {
            put("ok", true)
            if (isBatch) {
                put("entries", buildJsonArray {
                    for (s in successes) addJsonObject {
                        put("class", s.fqn)
                        if (includeBytecode) {
                            put("dex_sha256", SnapshotStore.sha256Hex(s.dexBytes))
                            put("class_bytes_sha256", SnapshotStore.sha256Hex(s.classBytes))
                        }
                        put("loader_kind", "main_app")
                        put("additional_copies_unswapped", s.additionalCopies)
                        s.warnings?.let { put("warnings", it) }
                        val active = activeFramesByFqn[s.fqn].orEmpty()
                        put("active_frames_using_old_code", buildJsonArray {
                            for ((thread, depth) in active) addJsonObject {
                                put("thread", thread)
                                put("frame_depth", depth)
                            }
                        })
                    }
                })
            } else {
                val s = successes.first()
                put("class", s.fqn)
                if (includeBytecode) {
                    put("dex_sha256", SnapshotStore.sha256Hex(s.dexBytes))
                    put("class_bytes_sha256", SnapshotStore.sha256Hex(s.classBytes))
                }
                put("loader_kind", "main_app")
                put("additional_copies_unswapped", s.additionalCopies)
                s.warnings?.let { put("warnings", it) }
                val active = activeFramesByFqn[s.fqn].orEmpty()
                put("active_frames_using_old_code", buildJsonArray {
                    for ((thread, depth) in active) addJsonObject {
                        put("thread", thread)
                        put("frame_depth", depth)
                    }
                })
            }
            // Convert JVM internal signatures (Lcom/foo/Bar;) back to FQN dot notation
            // so the MCP-facing surface is consistent — `class` field is FQN; this list
            // should be too. The native agent returns sig form on the wire.
            put("redefined_classes", buildJsonArray { for (r in redefined) add(fromJvmSignature(r)) })
            put("force_re_enter", forceReEnter)
            put("popped_frame", poppedThreads.isNotEmpty())
            if (poppedThreads.isNotEmpty()) {
                put("popped_threads", buildJsonArray { for (t in poppedThreads) add(t) })
            }
        }
    }

    /**
     * Find every loaded copy of [fqn] across class loaders. Returns the count.
     * Caller subtracts 1 to get "additional copies not swapped."
     */
    private fun countLoadedCopies(vm: VirtualMachine, fqn: String): Int =
        vm.classesByName(fqn).size

    /** Resolve the *primary* loaded class for [fqn] — prefers the main-app PathClassLoader. */
    private fun resolveLoadedClass(vm: VirtualMachine, fqn: String): ReferenceType? {
        val candidates = vm.classesByName(fqn)
        if (candidates.isEmpty()) return null
        // Prefer one whose ClassLoader is the main app loader. JDI doesn't expose loader
        // class name reliably, but the "no parent" / "system" check works as a proxy
        // on Android: anonymous DexClassLoaders have a non-null loader; the app's
        // PathClassLoader is a child of the bootstrap.
        return candidates.firstOrNull { it.classLoader() != null } ?: candidates.first()
    }

    /**
     * Walk every thread's stack; return (threadName, frameDepth) for every frame
     * whose declaring type matches [fqn]. Used to populate
     * `active_frames_using_old_code` and to pick threads to pop when forcing re-enter.
     */
    private fun findActiveFrames(vm: VirtualMachine, fqn: String): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()
        // Only meaningful when the VM is paused; otherwise frames are unstable. We
        // attempt to read all threads but suppress any failures (running threads
        // throw IncompatibleThreadStateException on `frames()`).
        runCatching {
            for (t in vm.allThreads()) {
                if (!t.isSuspended) continue
                val name = t.name() ?: continue
                runCatching {
                    val frames = t.frames()
                    for ((depth, frame) in frames.withIndex()) {
                        val declaring = runCatching { frame.location().declaringType().name() }.getOrNull()
                        if (declaring == fqn) results.add(name to depth)
                    }
                }
            }
        }
        return results
    }

    /**
     * Convert a Java FQN (`com.example.Foo`) to JVM internal signature form
     * (`Lcom/example/Foo;`) for the agent's wire surface.
     */
    private fun toJvmSignature(fqn: String): String =
        "L" + fqn.replace('.', '/') + ";"

    /** Convert "Lcom/foo/Bar;" back to "com.foo.Bar". Tolerant of unexpected shapes. */
    private fun fromJvmSignature(sig: String): String {
        if (sig.length < 2 || !sig.startsWith("L") || !sig.endsWith(";")) return sig
        return sig.substring(1, sig.length - 1).replace('/', '.')
    }

    /**
     * Try to capture the agent's pre-attach bytes for [fqn] BEFORE we swap, so a
     * later `hot_swap_revert` has somewhere to restore from. Snapshot store is
     * write-once (first capture wins) so this is safe to call before every swap.
     */
    private suspend fun captureSnapshotIfPossible(
        agentClient: com.acendas.androiddebugger.jvmti.AgentClient,
        fqn: String,
        @Suppress("UNUSED_PARAMETER") refType: ReferenceType?,
    ) {
        if (Session.snapshotStore.contains(fqn)) return
        val sig = toJvmSignature(fqn)
        val bytes = runCatching {
            AgentHotSwap.getOriginalClassBytes(agentClient, sig)
        }.getOrNull() ?: return
        // P10 smoke caught this: on ART API 26, `ClassFileLoadHook` doesn't always
        // hand the agent JVM-form `.class` bytes — for at least some Kotlin-compiled
        // classes (Companion objects observed), the bytes arrive in an ART-internal
        // form without the `0xCAFEBABE` magic that d8 expects. Storing those bytes
        // would make `hot_swap_revert` fail with a confusing "Invalid classfile header"
        // when the user tries to roll back. Validate the magic at capture time so
        // the snapshot store only ever contains valid .class bytes; revert cleanly
        // surfaces "no snapshot" for these classes.
        if (!hasJvmClassMagic(bytes)) {
            org.slf4j.LoggerFactory.getLogger("android-debugger.hotswap")
                .info(
                    "Agent's ClassFileLoadHook returned non-.class bytes for {} ({} bytes, first 4: {}). " +
                        "Skipping snapshot — revert won't be available for this class.",
                    fqn,
                    bytes.size,
                    bytes.take(4).joinToString(",") { "0x%02x".format(it) },
                )
            return
        }
        Session.snapshotStore.captureIfAbsent(fqn, bytes)
    }

    /** True iff [bytes] starts with the JVM `.class` magic 0xCAFEBABE. */
    private fun hasJvmClassMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == 0xCA.toByte() &&
            bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0xBA.toByte() &&
            bytes[3] == 0xBE.toByte()
    }

    // ---------------- validation result ----------------

    private sealed class Validation {
        data class Success(
            val fqn: String,
            val classBytes: ByteArray,
            val dexBytes: ByteArray,
            /**
             * JDI's view of the class. NULL when JDI's `classesByName` returned empty
             * even though the class IS loaded — happens on ART for classes loaded by
             * user class loaders that JDWP's `ClassesBySignature` doesn't see. We
             * defer to the agent's `GetLoadedClasses` in those cases.
             */
            val refType: ReferenceType?,
            val additionalCopies: Int,
            val warnings: JsonArray?,
        ) : Validation()

        data class Failure(
            val fqn: String,
            val code: String,
            val message: String,
            val hint: String? = null,
            val diff: JsonArray? = null,
            val warnings: JsonArray? = null,
        ) : Validation()
    }

    /**
     * Re-emit a structured-error MCP reply carrying per-class failure diagnostics so the
     * agent can show `failures: [{ class, code, diff: [...] }]` instead of one generic
     * line. Returns the same [io.modelcontextprotocol.kotlin.sdk.types.CallToolResult]
     * shape `toolErr` produces, just with extra fields.
     */
    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.withFailureDetails(
        failures: List<Validation.Failure>,
    ): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult {
        // The CallToolResult's content is one TextContent holding a JSON string we built
        // via toolErr. We rebuild it with the failures array appended.
        val original = (this.content.firstOrNull() as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)
            ?.text ?: return this
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(original).jsonObject
        val updated = buildJsonObject {
            for ((k, v) in parsed) put(k, v)
            put("validation_phase", "failed")
            put("redefine_phase", "skipped")
            put("failures", buildJsonArray {
                for (f in failures) addJsonObject {
                    put("class", f.fqn)
                    put("code", f.code)
                    put("message", f.message)
                    f.hint?.let { put("hint", it) }
                    f.diff?.let { put("diff", it) }
                    f.warnings?.let { put("warnings", it) }
                }
            })
        }
        return io.modelcontextprotocol.kotlin.sdk.types.CallToolResult(
            content = listOf(io.modelcontextprotocol.kotlin.sdk.types.TextContent(text = updated.toString())),
        )
    }
}
