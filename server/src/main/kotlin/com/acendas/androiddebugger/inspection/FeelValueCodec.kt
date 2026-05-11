package com.acendas.androiddebugger.inspection

import ca.acendas.kfeel.api.FeelValue
import com.sun.jdi.ArrayReference
import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.DoubleValue
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.LongValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.ShortValue
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import java.math.BigDecimal

/**
 * Bidirectional bridge between JDI [Value]s in the target VM and kfeel [FeelValue]s
 * in the evaluator. Per Story A.1.2 of the v1.3 plan.
 *
 * **toFeel** (`JDI → FEEL`) is total: every JDI [Value] (including `null`) maps to some
 * [FeelValue]. Opaque object references that don't have a natural FEEL counterpart
 * become a [FeelValue.Context] tagged with `__objectId` + `__type` so they round-trip
 * back via [fromFeel] without losing identity.
 *
 * **fromFeel** (`FEEL → JDI`) is partial: it covers the cases needed to box FEEL
 * expression results back into JDI for `invokeMethod` args. Cases it does not cover
 * (constructing a fresh JDI array from a [FeelValue.List], date/time mirrors,
 * ranges, functions) throw [UnsupportedCoercionException]; the caller is expected
 * to surface a clear error so the agent can drop down to `eval_method` with explicit
 * `obj#N` refs.
 *
 * **Arrays bounded at 256.** [toFeel] truncates [ArrayReference]s past 256 elements
 * and tags the result with a `__truncated: true` entry on a wrapping context, since
 * a 100k-element array would cost a JDI round-trip per element and the agent rarely
 * needs every element to make a decision.
 */
object FeelValueCodec {

    /** Maximum array elements materialized in [toFeel] before truncation. */
    const val MAX_ARRAY_ELEMENTS: Int = 256

    /**
     * Convert a JDI [Value] (possibly null) to a [FeelValue]. Total — every input maps.
     *
     * Object references that aren't strings/arrays become opaque [FeelValue.Context]s:
     * ```
     * { "__objectId": "obj#42", "__type": "com.example.User" }
     * ```
     * The codec registers the ref with [ObjectIdMint] eagerly so the `__objectId` is
     * resolvable from a FEEL expression (e.g., as the target of `invoke(user, "getName")`).
     */
    fun toFeel(jdi: Value?): FeelValue = when (jdi) {
        null -> FeelValue.Null
        is BooleanValue -> FeelValue.Boolean(jdi.value())
        is ByteValue -> FeelValue.Number(BigDecimal(jdi.value().toInt()))
        is ShortValue -> FeelValue.Number(BigDecimal(jdi.value().toInt()))
        is IntegerValue -> FeelValue.Number(BigDecimal(jdi.value()))
        is LongValue -> FeelValue.Number(BigDecimal(jdi.value()))
        is FloatValue -> FeelValue.Number(BigDecimal.valueOf(jdi.value().toDouble()))
        is DoubleValue -> FeelValue.Number(BigDecimal.valueOf(jdi.value()))
        is CharValue -> FeelValue.Text(jdi.value().toString())
        is StringReference -> FeelValue.Text(jdi.value())
        is ArrayReference -> arrayToFeel(jdi)
        is ObjectReference -> opaqueObject(jdi)
        else -> FeelValue.Text("<unrenderable: ${jdi.type().name()}>")
    }

    /**
     * Convert a [FeelValue] back to a JDI [Value] for use as an `invokeMethod` arg.
     *
     * Numbers pick the smallest fitting JDI primitive: `Int` if scale=0 and fits, then
     * `Long`, else `Double`. This is a heuristic — when overload resolution needs a
     * specific type (`setX(long)` vs `setX(int)`), [Evaluator.pickMethod]'s
     * primitive-compat scoring handles the tiebreak.
     *
     * Opaque object Contexts (with an `__objectId` entry) resolve back through
     * [ObjectIdMint] to the original [ObjectReference].
     *
     * @throws UnsupportedCoercionException for types with no JDI counterpart
     *   (constructed lists, date/time mirrors, ranges, functions). Caller maps to
     *   `ToolError(evaluate_type)`.
     */
    fun fromFeel(feel: FeelValue, vm: VirtualMachine): Value? = when (feel) {
        FeelValue.Null -> null
        is FeelValue.Boolean -> vm.mirrorOf(feel.value)
        is FeelValue.Number -> numberToJdi(feel.value, vm)
        is FeelValue.Text -> vm.mirrorOf(feel.value)
        is FeelValue.Context -> contextToJdi(feel)
            ?: throw UnsupportedCoercionException(
                "Cannot convert FEEL Context to JDI — no `__objectId` entry. " +
                    "Constructed contexts can't be passed to invokeMethod; pass an `obj#N` ref instead.",
            )
        is FeelValue.List -> throw UnsupportedCoercionException(
            "Cannot construct a JDI array from a FEEL list. Use `eval_method` with `obj#N` ref args " +
                "if you need to pass an existing array; mirror-side construction is not supported.",
        )
        is FeelValue.Date -> throw UnsupportedCoercionException(
            "Cannot mirror FEEL Date to JDI. Read existing java.time.LocalDate refs as opaque objects instead.",
        )
        is FeelValue.Time -> throw UnsupportedCoercionException(
            "Cannot mirror FEEL Time to JDI. Read existing java.time.LocalTime refs as opaque objects instead.",
        )
        is FeelValue.DateTime -> throw UnsupportedCoercionException(
            "Cannot mirror FEEL DateTime to JDI. Read existing java.time.ZonedDateTime refs as opaque objects instead.",
        )
        is FeelValue.Duration -> throw UnsupportedCoercionException(
            "Cannot mirror FEEL Duration to JDI. Read existing java.time.Duration refs as opaque objects instead.",
        )
        is FeelValue.Range -> throw UnsupportedCoercionException(
            "FEEL Range has no JDI counterpart — use comparison operators (`x >= 1 and x <= 10`) instead.",
        )
        is FeelValue.Function -> throw UnsupportedCoercionException(
            "FEEL functions are not first-class values in JDI.",
        )
    }

    /**
     * Returns the [ObjectIdMint] id for an opaque object FeelValue, or `null` if [feel]
     * isn't an opaque-object Context. Useful for FEEL functions (like `invoke()`) that
     * need to resolve the target ref before dispatching to JDI.
     */
    fun objectIdOf(feel: FeelValue): String? {
        if (feel !is FeelValue.Context) return null
        val idEntry = feel.entries[OBJECT_ID_KEY] as? FeelValue.Text ?: return null
        return idEntry.value
    }

    /** Marker key for opaque-object Context entries. */
    const val OBJECT_ID_KEY: String = "__objectId"

    /** Marker key for the JDI type name on opaque-object Contexts. */
    const val OBJECT_TYPE_KEY: String = "__type"

    /** Marker key for truncated-array Contexts. */
    const val TRUNCATED_KEY: String = "__truncated"

    // ---------------- internal helpers ----------------

    private fun arrayToFeel(arr: ArrayReference): FeelValue {
        val total = arr.length()
        val take = minOf(total, MAX_ARRAY_ELEMENTS)
        val slice = if (take == 0) emptyList() else arr.getValues(0, take)
        val mapped = slice.map { toFeel(it) }
        if (total <= MAX_ARRAY_ELEMENTS) {
            return FeelValue.List(mapped)
        }
        // Truncation marker — wrap the partial list inside a Context so the agent can
        // see what was elided without us forging a full list.
        return FeelValue.Context(
            entries = linkedMapOf(
                "elements" to FeelValue.List(mapped),
                "length_total" to FeelValue.Number(BigDecimal(total)),
                "length_returned" to FeelValue.Number(BigDecimal(take)),
                TRUNCATED_KEY to FeelValue.Boolean(true),
            ),
        )
    }

    private fun opaqueObject(ref: ObjectReference): FeelValue.Context {
        val id = ObjectIdMint.registerObject(ref)
        return FeelValue.Context(
            entries = linkedMapOf(
                OBJECT_ID_KEY to FeelValue.Text(id),
                OBJECT_TYPE_KEY to FeelValue.Text(ref.referenceType().name()),
            ),
        )
    }

    private fun numberToJdi(value: BigDecimal, vm: VirtualMachine): Value {
        if (value.scale() <= 0) {
            // Integral. Pick the smallest fitting type.
            try {
                val asLong = value.longValueExact()
                if (asLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    return vm.mirrorOf(asLong.toInt())
                }
                return vm.mirrorOf(asLong)
            } catch (_: ArithmeticException) {
                // BigDecimal exceeds Long.MAX_VALUE — fall through to Double.
            }
        }
        return vm.mirrorOf(value.toDouble())
    }

    private fun contextToJdi(ctx: FeelValue.Context): Value? {
        val id = objectIdOf(ctx) ?: return null
        return ObjectIdMint.resolveObject(id)
    }
}

/**
 * Thrown by [FeelValueCodec.fromFeel] when the source [FeelValue] has no JDI counterpart
 * usable for `invokeMethod` arg passing. The Evaluator maps this to
 * `ToolError(evaluate_type)` so the agent gets an actionable code.
 */
class UnsupportedCoercionException(message: String) : RuntimeException(message)
