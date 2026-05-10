package com.acendas.androiddebugger.inspection

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

/** A single rendered value: short string + type + optional ref-id for drill-down. */
data class RenderedValue(
    val rendered: String,
    val type: String,
    val refId: String? = null,
)

/**
 * Render a JDI [Value] to a small, bounded display form. Strings get truncated past 200
 * chars; arrays show a length-and-type summary; objects show `<Type#id>` and carry a
 * `refId` the agent can use to drill in. Per Task 2.1.1.3.
 */
object ValueRenderer {

    private const val MAX_STRING_LEN = 200

    fun render(value: Value?): RenderedValue {
        if (value == null) return RenderedValue("null", "null")
        return when (value) {
            is BooleanValue -> RenderedValue(value.value().toString(), "boolean")
            is ByteValue -> RenderedValue(value.value().toString(), "byte")
            is CharValue -> RenderedValue("'${escapeChar(value.value())}'", "char")
            is ShortValue -> RenderedValue(value.value().toString(), "short")
            is IntegerValue -> RenderedValue(value.value().toString(), "int")
            is LongValue -> RenderedValue("${value.value()}L", "long")
            is FloatValue -> RenderedValue("${value.value()}f", "float")
            is DoubleValue -> RenderedValue(value.value().toString(), "double")
            is StringReference -> {
                val raw = value.value()
                val display = if (raw.length > MAX_STRING_LEN) raw.take(MAX_STRING_LEN) + "…" else raw
                RenderedValue(
                    rendered = "\"" + escapeString(display) + "\"",
                    type = "java.lang.String",
                    refId = ObjectIdMint.registerObject(value),
                )
            }
            is ArrayReference -> {
                val type = value.referenceType().name()
                val len = value.length()
                RenderedValue(
                    rendered = "[$len items, type=$type]",
                    type = type,
                    refId = ObjectIdMint.registerObject(value),
                )
            }
            is ObjectReference -> {
                val type = value.referenceType().name()
                RenderedValue(
                    rendered = "<$type#${value.uniqueID()}>",
                    type = type,
                    refId = ObjectIdMint.registerObject(value),
                )
            }
            else -> RenderedValue(value.toString(), value.type().name())
        }
    }

    private fun escapeChar(c: Char): String = when (c) {
        '\\' -> "\\\\"
        '\'' -> "\\'"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> c.toString()
    }

    private fun escapeString(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
