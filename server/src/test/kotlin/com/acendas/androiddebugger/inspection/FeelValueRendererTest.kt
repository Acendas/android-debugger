package com.acendas.androiddebugger.inspection

import ca.acendas.kfeel.api.FeelValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cover [FeelValueRenderer] — every FeelValue subclass renders to the same
 * `RenderedValue(rendered, type, refId?)` shape that [ValueRenderer] produces for JDI
 * values, keeping snapshot/watch/log payloads symmetric. Per Story A.1.4.
 */
class FeelValueRendererTest {

    @Test
    fun null_renders_to_literal_null() {
        val r = FeelValueRenderer.render(FeelValue.Null)
        assertEquals("null", r.rendered)
        assertEquals("null", r.type)
        assertNull(r.refId)
    }

    @Test
    fun boolean_renders_string() {
        assertEquals("true", FeelValueRenderer.render(FeelValue.Boolean(true)).rendered)
        assertEquals("false", FeelValueRenderer.render(FeelValue.Boolean(false)).rendered)
        assertEquals("boolean", FeelValueRenderer.render(FeelValue.Boolean(true)).type)
    }

    @Test
    fun number_renders_plain_BigDecimal() {
        assertEquals("42", FeelValueRenderer.render(FeelValue.Number(BigDecimal(42))).rendered)
        assertEquals("3.14", FeelValueRenderer.render(FeelValue.Number(BigDecimal("3.14"))).rendered)
        // No scientific notation for large numbers.
        assertEquals(
            "1234567890123",
            FeelValueRenderer.render(FeelValue.Number(BigDecimal("1234567890123"))).rendered,
        )
    }

    @Test
    fun text_renders_quoted_with_escaping() {
        assertEquals("\"hello\"", FeelValueRenderer.render(FeelValue.Text("hello")).rendered)
        // Newlines escape.
        assertEquals("\"a\\nb\"", FeelValueRenderer.render(FeelValue.Text("a\nb")).rendered)
        // Quote escapes.
        assertEquals("\"he said \\\"hi\\\"\"",
            FeelValueRenderer.render(FeelValue.Text("he said \"hi\"")).rendered)
    }

    @Test
    fun date_time_datetime_render_iso_form() {
        val date = FeelValueRenderer.render(FeelValue.Date(LocalDate.of(2026, 5, 11)))
        assertEquals("2026-05-11", date.rendered)
        assertEquals("date", date.type)

        val time = FeelValueRenderer.render(FeelValue.Time(LocalTime.of(14, 30, 0)))
        assertEquals("14:30", time.rendered)
        assertEquals("time", time.type)
    }

    @Test
    fun list_renders_count_summary() {
        val list = FeelValue.List(
            listOf(FeelValue.Number(BigDecimal(1)), FeelValue.Number(BigDecimal(2))),
        )
        assertEquals("[2 items]", FeelValueRenderer.render(list).rendered)
        assertEquals("list", FeelValueRenderer.render(list).type)
    }

    @Test
    fun opaque_object_context_renders_with_refId() {
        val ctx = FeelValue.Context(
            entries = mapOf(
                FeelValueCodec.OBJECT_ID_KEY to FeelValue.Text("obj#42"),
                FeelValueCodec.OBJECT_TYPE_KEY to FeelValue.Text("com.example.User"),
            ),
        )
        val r = FeelValueRenderer.render(ctx)
        assertEquals("<com.example.User#42>", r.rendered)
        assertEquals("com.example.User", r.type)
        assertEquals("obj#42", r.refId)
    }

    @Test
    fun truncated_array_context_renders_partial_count() {
        val ctx = FeelValue.Context(
            entries = linkedMapOf(
                "elements" to FeelValue.List(emptyList()),
                "length_total" to FeelValue.Number(BigDecimal(1000)),
                "length_returned" to FeelValue.Number(BigDecimal(256)),
                FeelValueCodec.TRUNCATED_KEY to FeelValue.Boolean(true),
            ),
        )
        val r = FeelValueRenderer.render(ctx)
        assertTrue("256" in r.rendered && "1000" in r.rendered, "Expected partial-count summary, got: ${r.rendered}")
        assertEquals("list_truncated", r.type)
    }

    @Test
    fun plain_context_renders_compact_key_value_preview() {
        val ctx = FeelValue.Context(
            entries = linkedMapOf(
                "name" to FeelValue.Text("Alice"),
                "age" to FeelValue.Number(BigDecimal(30)),
            ),
        )
        val r = FeelValueRenderer.render(ctx)
        assertTrue(r.rendered.startsWith("{") && r.rendered.endsWith("}"))
        assertTrue("name" in r.rendered && "age" in r.rendered)
        assertEquals("context", r.type)
    }

    @Test
    fun toJson_number_integer_becomes_long_primitive() {
        val json = FeelValueRenderer.toJson(FeelValue.Number(BigDecimal(42)))
        assertTrue(json is JsonPrimitive)
        assertEquals(42L, json.long)
    }

    @Test
    fun toJson_number_decimal_becomes_double_primitive() {
        val json = FeelValueRenderer.toJson(FeelValue.Number(BigDecimal("3.14")))
        assertTrue(json is JsonPrimitive)
        // double comparison fine — kfeel uses valueOf so the text representation is stable.
        assertEquals("3.14", json.contentOrNull)
    }

    @Test
    fun toJson_null_becomes_JsonNull() {
        assertEquals(JsonNull, FeelValueRenderer.toJson(FeelValue.Null))
    }

    @Test
    fun toJson_boolean_becomes_primitive() {
        val json = FeelValueRenderer.toJson(FeelValue.Boolean(true))
        assertTrue(json is JsonPrimitive)
        assertEquals(true, json.boolean)
    }

    @Test
    fun toJson_list_becomes_array() {
        val list = FeelValue.List(
            listOf(
                FeelValue.Number(BigDecimal(1)),
                FeelValue.Text("hi"),
                FeelValue.Boolean(true),
            ),
        )
        val json = FeelValueRenderer.toJson(list)
        assertTrue(json is JsonArray)
        assertEquals(3, json.size)
    }

    @Test
    fun toJson_context_becomes_object_carrying_refId() {
        val ctx = FeelValue.Context(
            entries = mapOf(
                FeelValueCodec.OBJECT_ID_KEY to FeelValue.Text("obj#7"),
                FeelValueCodec.OBJECT_TYPE_KEY to FeelValue.Text("X"),
            ),
        )
        val json = FeelValueRenderer.toJson(ctx)
        assertTrue(json is JsonObject)
        val typed = json
        assertNotNull(typed[FeelValueCodec.OBJECT_ID_KEY])
        assertNotNull(typed[FeelValueCodec.OBJECT_TYPE_KEY])
    }
}
