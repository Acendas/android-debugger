package com.acendas.androiddebugger.inspection

import ca.acendas.kfeel.api.FeelContext
import ca.acendas.kfeel.api.FeelValue
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.Value

/**
 * Build a kfeel [FeelContext] from a paused JDI [StackFrame] so the agent can refer
 * to frame locals, `this` fields, and enclosing-class statics from a FEEL expression
 * with no extra ceremony. Per Story A.1.3 of the v1.3 plan.
 *
 * Identifier resolution order (mirrors the v1.2 [Evaluator.resolveIdentifier] rules):
 *
 *  1. **Frame locals** — `frame.visibleVariables()`. If R8/ProGuard stripped the table,
 *     `AbsentInformationException` lands; we substitute a per-local sentinel rather
 *     than failing the whole build so identifier names that fall through to fields
 *     still resolve.
 *  2. **`this` fields** — top-level key `"this"` holds a deeply-resolved
 *     [FeelValue.Context] (opaque-object marker plus pre-resolved instance fields up
 *     to [DEFAULT_FIELD_DEPTH]); individual field names are also flattened to the top
 *     level so the bare identifier `name` works alongside `this.name`.
 *  3. **Enclosing-class statics** — exposed under the declaring type's simple name
 *     and fully-qualified name (e.g., both `MainActivity.TAG` and
 *     `com.example.MainActivity.TAG`). Bare names land at the top level too unless
 *     a local / instance field already claims that name.
 *
 * **Eager field pre-resolution** is the load-bearing piece for v1.3's
 * `evaluate`-without-method-calls model: since FEEL has no syntax for `obj.method()`,
 * the agent reaches into nested object state via plain property access
 * (`user.address.city`). We pre-walk the object graph to [DEFAULT_FIELD_DEPTH]
 * (default 3) and materialize nested fields as [FeelValue.Context] entries. Leaves
 * (beyond the depth budget OR already-visited via cycle detection) keep just
 * `__objectId` + `__type` so the agent can still drill in via `eval_method` /
 * `inspect_object`.
 */
object FeelContextBuilder {

    /** Default depth budget for the eager field walk. Past this, refs stay opaque. */
    const val DEFAULT_FIELD_DEPTH: Int = 3

    /**
     * Sentinel value placed on a context entry when the local exists in the frame's
     * variable table but JDI raised [AbsentInformationException] reading it. Lets the
     * agent distinguish "stripped by R8" from "doesn't exist."
     */
    val LOCAL_UNAVAILABLE: FeelValue.Context = FeelValue.Context(
        entries = linkedMapOf(
            "__unavailable" to FeelValue.Boolean(true),
            "__reason" to FeelValue.Text("local variable table absent — likely R8/ProGuard stripped"),
        ),
    )

    /**
     * Build a context populated with everything reachable from [frame]. The returned
     * [FeelContext] is fresh — callers may add more entries (the evaluator does not
     * today) without leaking state into a later evaluation.
     */
    fun build(frame: StackFrame, fieldDepth: Int = DEFAULT_FIELD_DEPTH): FeelContext {
        val ctx = FeelContext()

        // 1. Frame locals (best-effort, deep-resolved per local).
        for ((name, value) in readLocals(frame, fieldDepth)) {
            ctx.setVariable(name, value)
        }

        // 2. `this` — deep-resolved Context; instance-field shortcuts flattened so
        //    `name` works on top of `this.name`. Skipping a flatten on collision
        //    preserves the v1.2 resolution order (locals > fields > statics).
        val self = frame.thisObject()
        if (self != null) {
            val visited = HashSet<Long>()
            val selfContext = resolveDeep(self, fieldDepth, visited)
            ctx.setVariable("this", selfContext)
            if (selfContext is FeelValue.Context) {
                for ((fieldName, fieldValue) in selfContext.entries) {
                    if (fieldName.startsWith(OPAQUE_PREFIX)) continue
                    if (!ctx.hasVariable(fieldName)) {
                        ctx.setVariable(fieldName, fieldValue)
                    }
                }
            }
        }

        // 3. Statics on the declaring type, under both `Class.STATIC`, the FQN
        //    `com.example.Class.STATIC`, and bare `STATIC` aliases. Bare alias loses on
        //    a name collision with a local / field (documented resolution order).
        val declaring = frame.location().declaringType()
        for ((fieldName, fieldValue) in staticFields(declaring, fieldDepth)) {
            val simple = simpleTypeName(declaring)
            ctx.setVariable("$simple.$fieldName", fieldValue)
            ctx.setVariable("${declaring.name()}.$fieldName", fieldValue)
            if (!ctx.hasVariable(fieldName)) {
                ctx.setVariable(fieldName, fieldValue)
            }
        }

        return ctx
    }

    /**
     * Recursively resolve [value] to a [FeelValue], walking up to [depthRemaining]
     * levels into object-typed fields. Primitives, Strings, Arrays, and null go
     * through [FeelValueCodec.toFeel] unchanged. Object refs become Contexts whose
     * entries include both the opaque markers (`__objectId`, `__type`) and the
     * instance-field tree. [visited] tracks already-seen object ids (by
     * `uniqueID()`) so cyclic graphs terminate.
     */
    fun resolveDeep(value: Value?, depthRemaining: Int, visited: MutableSet<Long>): FeelValue {
        if (value !is ObjectReference || value is com.sun.jdi.StringReference || value is com.sun.jdi.ArrayReference) {
            // Primitives / null / strings / arrays — codec handles them.
            return FeelValueCodec.toFeel(value)
        }
        val id = value.uniqueID()
        // Always include the opaque markers so eval_method can still address this ref.
        val refIdString = ObjectIdMint.registerObject(value)
        val typeName = value.referenceType().name()
        val markers = linkedMapOf<String, FeelValue>(
            FeelValueCodec.OBJECT_ID_KEY to FeelValue.Text(refIdString),
            FeelValueCodec.OBJECT_TYPE_KEY to FeelValue.Text(typeName),
        )
        if (depthRemaining <= 0 || !visited.add(id)) {
            // Either out of depth budget or we've already visited this exact ref;
            // return the opaque shell so the agent still has a refId to drill on.
            return FeelValue.Context(markers)
        }
        try {
            for ((fieldName, fieldValue) in instanceFields(value)) {
                markers[fieldName] = resolveDeep(fieldValue, depthRemaining - 1, visited)
            }
        } finally {
            // Allow sibling branches to also visit this ref (we only protect against
            // *recursion*, not against the same ref appearing twice in the tree).
            visited.remove(id)
        }
        return FeelValue.Context(markers)
    }

    /** Prefix for synthetic opaque-marker keys — used to skip flattening. */
    private const val OPAQUE_PREFIX: String = "__"

    /** Reads locals defensively — never throws. Each local is deep-resolved. */
    private fun readLocals(frame: StackFrame, depth: Int): List<Pair<String, FeelValue>> {
        val list = mutableListOf<Pair<String, FeelValue>>()
        val vars = try {
            frame.visibleVariables()
        } catch (_: AbsentInformationException) {
            return emptyList()
        } catch (_: Throwable) {
            return emptyList()
        }
        val visited = HashSet<Long>()
        for (v in vars) {
            val name = try {
                v.name()
            } catch (_: Throwable) {
                continue
            }
            val feel = try {
                resolveDeep(frame.getValue(v), depth, visited)
            } catch (_: AbsentInformationException) {
                LOCAL_UNAVAILABLE
            } catch (_: Throwable) {
                continue
            }
            list += name to feel
        }
        return list
    }

    private fun instanceFields(self: ObjectReference): List<Pair<String, Value?>> {
        val out = mutableListOf<Pair<String, Value?>>()
        val refType = self.referenceType()
        val fields = try {
            refType.visibleFields()
        } catch (_: Throwable) {
            return emptyList()
        }
        for (f in fields) {
            if (f.isStatic) continue
            val name = try { f.name() } catch (_: Throwable) { continue }
            val v = try { self.getValue(f) } catch (_: Throwable) { continue }
            out += name to v
        }
        return out
    }

    private fun staticFields(type: ReferenceType, depth: Int): List<Pair<String, FeelValue>> {
        val out = mutableListOf<Pair<String, FeelValue>>()
        val fields = try {
            type.visibleFields()
        } catch (_: Throwable) {
            return emptyList()
        }
        val visited = HashSet<Long>()
        for (f in fields) {
            if (!f.isStatic) continue
            val name = try { f.name() } catch (_: Throwable) { continue }
            val raw = try { type.getValue(f) } catch (_: Throwable) { continue }
            out += name to resolveDeep(raw, depth, visited)
        }
        return out
    }

    private fun simpleTypeName(type: ReferenceType): String {
        val full = type.name()
        val dot = full.lastIndexOf('.')
        return if (dot >= 0) full.substring(dot + 1) else full
    }
}
