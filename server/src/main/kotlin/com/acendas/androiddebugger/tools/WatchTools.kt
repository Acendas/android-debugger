package com.acendas.androiddebugger.tools

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.acendas.androiddebugger.inspection.ObjectIdMint
import com.acendas.androiddebugger.runTool
import com.acendas.androiddebugger.toolOk
import com.sun.jdi.ClassType
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Phase 5 — watches + heap-cheap queries.
 *
 * Tools:
 *  - add_watch / remove_watch / list_watches — per-session watch registry.
 *  - count_instances({ class }) — `vm.instanceCounts(...)`.
 *  - find_referrers({ ref, max=20 }) — `ObjectReference.referringObjects(max)` with
 *    field-name resolution per referrer.
 */
object WatchTools {

    /** Hard upper bound on `find_referrers.max` per Story 5.1.3 (cap "100 hard"). */
    private const val FIND_REFERRERS_HARD_CAP: Int = 100
    private const val FIND_REFERRERS_DEFAULT: Int = 20

    fun register(server: Server) {
        registerAddWatch(server)
        registerRemoveWatch(server)
        registerListWatches(server)
        registerCountInstances(server)
        registerFindReferrers(server)
    }

    private fun registerAddWatch(server: Server) {
        server.addTool(
            name = "add_watch",
            description = "Register a watch expression. Re-evaluated against the top frame on every pause and " +
                "included in `frame_snapshot.watches`. Same expression grammar as the `evaluate` tool: paths, " +
                "member access, method calls with literal/identifier args. Watches are session-scoped — they " +
                "vanish on `detach`. Returns the freshly-minted numeric id (used by `remove_watch`).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("expr") {
                        put("type", "string")
                        put("description", "Expression to watch (same grammar as `evaluate`).")
                    }
                },
                required = listOf("expr"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val expr = (request.arguments?.get("expr") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `expr`.")
                if (expr.isBlank()) {
                    throw ToolError(ErrorCode.InvalidTarget, "Watch expression cannot be empty.")
                }
                val id = Session.watchManager.add(expr)
                toolOk {
                    put("id", id)
                    put("expr", expr)
                    put("count", Session.watchManager.size())
                }
            }
        }
    }

    private fun registerRemoveWatch(server: Server) {
        server.addTool(
            name = "remove_watch",
            description = "Drop a watch by id. Returns `{ ok: true, removed: true|false }` — `false` if the id " +
                "wasn't registered (idempotent).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "Watch id from `add_watch` or `list_watches`.")
                    }
                },
                required = listOf("id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                val id = (request.arguments?.get("id") as? JsonPrimitive)?.intOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing or non-integer `id`.")
                val removed = Session.watchManager.remove(id)
                toolOk {
                    put("removed", removed)
                    put("count", Session.watchManager.size())
                }
            }
        }
    }

    private fun registerListWatches(server: Server) {
        server.addTool(
            name = "list_watches",
            description = "List registered watches with their last evaluated value (or error). Doesn't re-evaluate " +
                "— for fresh values, look at `frame_snapshot.watches` after a pause.",
            inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) {
            runTool {
                val list = Session.watchManager.list()
                toolOk {
                    put("watches", buildJsonArray {
                        for (w in list) {
                            add(buildJsonObject {
                                put("id", w.id)
                                put("expr", w.expr)
                                w.lastValue?.let { rv ->
                                    put("last_value", buildJsonObject {
                                        put("rendered", rv.rendered)
                                        put("type", rv.type)
                                        rv.refId?.let { put("ref_id", it) }
                                    })
                                }
                                w.lastError?.let { put("last_error", it) }
                            })
                        }
                    })
                    put("count", list.size)
                }
            }
        }
    }

    private fun registerCountInstances(server: Server) {
        server.addTool(
            name = "count_instances",
            description = "Count loaded instances of a class via `vm.instanceCounts`. Useful for leak hunting " +
                "(\"how many `Bitmap`s are alive right now?\"). Errors `capability_unavailable` if the device's ART " +
                "reports `canGetInstanceInfo == false`. The class must already be loaded — error `invalid_target` " +
                "if it isn't (touch the class in the app first).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("class") {
                        put("type", "string")
                        put("description", "Fully-qualified class name (e.g. `android.graphics.Bitmap`).")
                    }
                },
                required = listOf("class"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                val vm = Session.requireAttached()
                // Per Story 7.1.1: gate via the central capability check so the error
                // shape (`code: capability_unavailable, feature: get_instance_info`) is
                // consistent across the surface.
                com.acendas.androiddebugger.Capability.requireCapability(
                    com.acendas.androiddebugger.Capability.GET_INSTANCE_INFO,
                )
                val className = (request.arguments?.get("class") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `class`.")
                val refTypes: List<ReferenceType> = vm.classesByName(className)
                if (refTypes.isEmpty()) {
                    throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "Class `$className` is not loaded in the VM.",
                        hint = "The JDI sees a class only after it's loaded by the app. Touch a code path that " +
                            "references it first.",
                    )
                }
                val counts: LongArray = vm.instanceCounts(refTypes)
                val total = counts.sum()
                toolOk {
                    put("class", className)
                    put("count", total)
                    if (refTypes.size > 1) {
                        // Multiple classloaders define the same name — surface the breakdown.
                        put("by_classloader", buildJsonArray {
                            refTypes.forEachIndexed { i, rt ->
                                add(buildJsonObject {
                                    put("ref_type", rt.toString())
                                    put("count", counts[i])
                                })
                            }
                        })
                    }
                }
            }
        }
    }

    private fun registerFindReferrers(server: Server) {
        server.addTool(
            name = "find_referrers",
            description = "Find incoming references to an object via `ObjectReference.referringObjects`. " +
                "Useful for tracking down stale references holding a leaked object alive. " +
                "Returns `[{ class, ref_id, field? }]`. `field` is best-effort — we scan the referrer's instance " +
                "fields for a matching value. `max` is capped at 100; `max=0` is rejected (foot-gun on a phone heap).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("ref") {
                        put("type", "string")
                        put("description", "Object ref id (e.g. `obj#12345`) from a previous tool call.")
                    }
                    putJsonObject("max") {
                        put("type", "integer")
                        put("description", "Max referrers to return. Default $FIND_REFERRERS_DEFAULT; hard cap $FIND_REFERRERS_HARD_CAP.")
                    }
                },
                required = listOf("ref"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) { request ->
            runTool {
                Session.requireAttached()
                // Per Story 7.1.1: `referringObjects` is gated by `canGetInstanceInfo`
                // on JDI/ART. Gate before any work to give the agent a clean
                // capability_unavailable instead of an opaque UnsupportedOperationException.
                com.acendas.androiddebugger.Capability.requireCapability(
                    com.acendas.androiddebugger.Capability.GET_INSTANCE_INFO,
                )
                val refId = (request.arguments?.get("ref") as? JsonPrimitive)?.contentOrNull
                    ?: throw ToolError(ErrorCode.InvalidTarget, "Missing `ref`.")
                val rawMax = (request.arguments?.get("max") as? JsonPrimitive)?.intOrNull
                val max = when {
                    rawMax == null -> FIND_REFERRERS_DEFAULT
                    rawMax <= 0 -> FIND_REFERRERS_DEFAULT
                    else -> rawMax.coerceAtMost(FIND_REFERRERS_HARD_CAP)
                }
                val obj = ObjectIdMint.resolveObject(refId)
                    ?: throw ToolError(
                        errorCode = ErrorCode.InvalidTarget,
                        message = "Unknown ref `$refId`.",
                        hint = "Object ids expire on resume/step. Re-snapshot before drilling in.",
                    )
                val referrers: List<ObjectReference> = try {
                    obj.referringObjects(max.toLong())
                } catch (_: UnsupportedOperationException) {
                    throw ToolError(
                        errorCode = ErrorCode.CapabilityUnavailable,
                        message = "VM does not support reverse-pointer queries on this object.",
                        hint = "Try `dump_heap` for an offline analysis instead.",
                    )
                }
                toolOk {
                    put("referrers", buildJsonArray {
                        for (r in referrers) {
                            add(renderReferrer(r, obj))
                        }
                    })
                    put("count", referrers.size)
                    put("capped_at", max)
                }
            }
        }
    }

    /**
     * Render a single referrer with optional `field` if we can find one of [r]'s instance
     * fields whose value equals [target]. Best-effort: collection-internal references
     * (entries inside an `ArrayList.elementData` array) won't surface a field name —
     * the array reference itself will be the referrer in that case.
     */
    private fun renderReferrer(r: ObjectReference, target: ObjectReference): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("class", r.referenceType().name())
        put("ref_id", ObjectIdMint.registerObject(r))
        // Try to identify which field on `r` holds `target`. Scan only instance fields
        // (static fields would be a class-level holder, exposed separately).
        val matchingField = try {
            r.referenceType().allFields().firstOrNull { f ->
                if (f.isStatic) return@firstOrNull false
                runCatching { r.getValue(f) == target }.getOrDefault(false)
            }
        } catch (_: Throwable) {
            null
        }
        if (matchingField != null) {
            put("field", matchingField.name())
        }
        // For static-field holders of [target], surface them too. (`r` may itself be a
        // ClassObjectReference — the JDI handle to a Class — when the holder is a static.)
        val type = r.referenceType()
        if (type is ClassType) {
            val staticMatch = runCatching {
                type.allFields().firstOrNull { f ->
                    f.isStatic && runCatching { type.getValue(f) == target }.getOrDefault(false)
                }
            }.getOrNull()
            if (staticMatch != null && matchingField == null) {
                put("static_field", staticMatch.name())
            }
        }
    }
}
