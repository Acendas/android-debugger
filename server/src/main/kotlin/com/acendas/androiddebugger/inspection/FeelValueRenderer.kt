package com.acendas.androiddebugger.inspection

import ca.acendas.kfeel.api.FeelValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Render a kfeel [FeelValue] to the same [RenderedValue] shape that [ValueRenderer]
 * emits for JDI values. Per Story A.1.4 of the v1.3 plan.
 *
 * Symmetry matters: the snapshot JSON, watch payloads, and logpoint entries all use
 * `RenderedValue(rendered, type, refId?)` — keeping the kfeel side on the same shape
 * means the agent sees one consistent format whether the value came from `evaluate`
 * (FEEL) or `eval_method` / `frame_snapshot` (JDI). The `refId` slot lights up for
 * opaque-object Contexts so the agent can drill in with `inspect_object` exactly the
 * way it does with JDI-rendered values.
 *
 * Numbers render with [java.math.BigDecimal.toPlainString] (no scientific notation,
 * no trailing-zero fuzz). Truncation cap is 200 chars to match [ValueRenderer].
 */
object FeelValueRenderer {

    private const val MAX_RENDERED_LEN: Int = 200

    fun render(feel: FeelValue): RenderedValue = when (feel) {
        FeelValue.Null -> RenderedValue("null", "null")
        is FeelValue.Boolean -> RenderedValue(feel.value.toString(), "boolean")
        is FeelValue.Number -> {
            val plain = feel.value.toPlainString()
            // Treat integral BigDecimals as `number` (FEEL keeps one numeric type;
            // we surface the underlying integer-ness for agent legibility).
            val type = if (feel.value.scale() <= 0) "number" else "number"
            RenderedValue(plain, type)
        }
        is FeelValue.Text -> {
            val display = truncate(feel.value)
            RenderedValue("\"" + escapeString(display) + "\"", "string")
        }
        is FeelValue.Date -> RenderedValue(feel.value.toString(), "date")
        is FeelValue.Time -> RenderedValue(feel.value.toString(), "time")
        is FeelValue.DateTime -> RenderedValue(feel.value.toString(), "date_time")
        is FeelValue.Duration -> RenderedValue(
            rendered = (feel.dayTimeDuration?.toString() ?: feel.yearMonthDuration?.toString() ?: "P0D"),
            type = "duration",
        )
        is FeelValue.List -> RenderedValue(
            rendered = "[${feel.elements.size} items]",
            type = "list",
        )
        is FeelValue.Range -> {
            val ob = if (feel.startInclusive) "[" else "("
            val cb = if (feel.endInclusive) "]" else ")"
            RenderedValue(
                rendered = "$ob${shortRender(feel.start)}..${shortRender(feel.end)}$cb",
                type = "range",
            )
        }
        is FeelValue.Function -> RenderedValue(
            rendered = "function(${feel.parameters.joinToString(", ")})",
            type = "function",
        )
        is FeelValue.Context -> renderContext(feel)
    }

    /**
     * Convert a FeelValue to a JSON element suitable for inclusion in MCP tool replies.
     * Mirrors [render] in carrying refId info — opaque-object Contexts include their
     * `__objectId` + `__type` so the agent can use them directly with `eval_method`.
     */
    fun toJson(feel: FeelValue): JsonElement = when (feel) {
        FeelValue.Null -> JsonNull
        is FeelValue.Boolean -> JsonPrimitive(feel.value)
        is FeelValue.Number -> {
            // Preserve integral vs decimal so JSON consumers can match expectations.
            if (feel.value.scale() <= 0) {
                try {
                    val asLong = feel.value.longValueExact()
                    JsonPrimitive(asLong)
                } catch (_: ArithmeticException) {
                    JsonPrimitive(feel.value.toPlainString())
                }
            } else {
                JsonPrimitive(feel.value.toDouble())
            }
        }
        is FeelValue.Text -> JsonPrimitive(feel.value)
        is FeelValue.Date -> JsonPrimitive(feel.value.toString())
        is FeelValue.Time -> JsonPrimitive(feel.value.toString())
        is FeelValue.DateTime -> JsonPrimitive(feel.value.toString())
        is FeelValue.Duration -> JsonPrimitive(
            feel.dayTimeDuration?.toString() ?: feel.yearMonthDuration?.toString() ?: "P0D",
        )
        is FeelValue.List -> JsonArray(feel.elements.map { toJson(it) })
        is FeelValue.Range -> JsonObject(
            mapOf(
                "start" to toJson(feel.start),
                "end" to toJson(feel.end),
                "start_inclusive" to JsonPrimitive(feel.startInclusive),
                "end_inclusive" to JsonPrimitive(feel.endInclusive),
            ),
        )
        is FeelValue.Function -> JsonObject(
            mapOf(
                "__function" to JsonPrimitive(feel.name),
                "parameters" to JsonArray(feel.parameters.map { JsonPrimitive(it) }),
            ),
        )
        is FeelValue.Context -> JsonObject(feel.entries.mapValues { toJson(it.value) })
    }

    private fun renderContext(ctx: FeelValue.Context): RenderedValue {
        // Opaque-object Contexts (with `__objectId`) render as `<Type#refId>` to mirror
        // the JDI ValueRenderer output, and carry the refId so the agent can use it.
        val refId = FeelValueCodec.objectIdOf(ctx)
        if (refId != null) {
            val typeName = (ctx.entries[FeelValueCodec.OBJECT_TYPE_KEY] as? FeelValue.Text)?.value
                ?: "Object"
            return RenderedValue(
                rendered = "<$typeName#${refId.removePrefix("obj#")}>",
                type = typeName,
                refId = refId,
            )
        }
        // Truncated-array Context (carries `__truncated: true`).
        if (ctx.entries[FeelValueCodec.TRUNCATED_KEY] == FeelValue.Boolean(true)) {
            val total = (ctx.entries["length_total"] as? FeelValue.Number)?.value?.toLong() ?: -1L
            val returned = (ctx.entries["length_returned"] as? FeelValue.Number)?.value?.toLong() ?: -1L
            return RenderedValue(
                rendered = "[$returned of $total items shown]",
                type = "list_truncated",
            )
        }
        // Plain context — compact key=value preview.
        val preview = ctx.entries.entries
            .filterNot { it.key.startsWith("__") }
            .take(5)
            .joinToString(", ") { (k, v) -> "$k: ${shortRender(v)}" }
        val ellipsis = if (ctx.entries.size > 5) ", …" else ""
        return RenderedValue(
            rendered = truncate("{$preview$ellipsis}"),
            type = "context",
        )
    }

    private fun shortRender(feel: FeelValue): String = when (feel) {
        is FeelValue.Number -> feel.value.toPlainString()
        is FeelValue.Text -> "\"${truncate(feel.value, 40)}\""
        is FeelValue.Boolean -> feel.value.toString()
        FeelValue.Null -> "null"
        is FeelValue.List -> "[${feel.elements.size} items]"
        is FeelValue.Context -> {
            val refId = FeelValueCodec.objectIdOf(feel)
            if (refId != null) {
                val type = (feel.entries[FeelValueCodec.OBJECT_TYPE_KEY] as? FeelValue.Text)?.value ?: "obj"
                "<$type>"
            } else "{…}"
        }
        else -> feel.toString()
    }

    private fun truncate(s: String, max: Int = MAX_RENDERED_LEN): String =
        if (s.length <= max) s else s.substring(0, max) + "…"

    private fun escapeString(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
