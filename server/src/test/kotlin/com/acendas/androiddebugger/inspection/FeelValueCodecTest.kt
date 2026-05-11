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
import com.sun.jdi.ReferenceType
import com.sun.jdi.ShortValue
import com.sun.jdi.StringReference
import com.sun.jdi.VirtualMachine
import org.mockito.Mockito
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the JDI↔FEEL bridge per Story A.1.2 of the v1.3 plan. The codec is the
 * load-bearing piece for kfeel-backed expression evaluation; every type pair must
 * round-trip correctly or the agent will see surprising losses.
 */
class FeelValueCodecTest {

    /** ObjectIdMint is a singleton; reset between tests so opaque-object ids don't leak. */
    private fun resetMint() = ObjectIdMint.clear()

    // ---------------- toFeel ----------------

    @Test
    fun toFeel_null_maps_to_FeelValue_Null() {
        assertSame(FeelValue.Null, FeelValueCodec.toFeel(null))
    }

    @Test
    fun toFeel_boolean() {
        val v = Mockito.mock(BooleanValue::class.java)
        Mockito.`when`(v.value()).thenReturn(true)
        assertEquals(FeelValue.Boolean(true), FeelValueCodec.toFeel(v))
    }

    @Test
    fun toFeel_integer_byte_short_long_all_become_Number() {
        val byte = Mockito.mock(ByteValue::class.java).also { Mockito.`when`(it.value()).thenReturn(7.toByte()) }
        val short = Mockito.mock(ShortValue::class.java).also { Mockito.`when`(it.value()).thenReturn(42.toShort()) }
        val int = Mockito.mock(IntegerValue::class.java).also { Mockito.`when`(it.value()).thenReturn(1000) }
        val long = Mockito.mock(LongValue::class.java).also { Mockito.`when`(it.value()).thenReturn(123_456_789_012L) }

        assertEquals(FeelValue.Number(BigDecimal(7)), FeelValueCodec.toFeel(byte))
        assertEquals(FeelValue.Number(BigDecimal(42)), FeelValueCodec.toFeel(short))
        assertEquals(FeelValue.Number(BigDecimal(1000)), FeelValueCodec.toFeel(int))
        assertEquals(FeelValue.Number(BigDecimal(123_456_789_012L)), FeelValueCodec.toFeel(long))
    }

    @Test
    fun toFeel_float_double_become_Number_via_BigDecimal_valueOf() {
        val flt = Mockito.mock(FloatValue::class.java).also { Mockito.`when`(it.value()).thenReturn(1.5f) }
        val dbl = Mockito.mock(DoubleValue::class.java).also { Mockito.`when`(it.value()).thenReturn(3.14) }

        // BigDecimal.valueOf(Double) preserves the textual form — no binary-float drift.
        assertEquals(FeelValue.Number(BigDecimal.valueOf(1.5)), FeelValueCodec.toFeel(flt))
        assertEquals(FeelValue.Number(BigDecimal.valueOf(3.14)), FeelValueCodec.toFeel(dbl))
    }

    @Test
    fun toFeel_char_becomes_single_char_Text() {
        val c = Mockito.mock(CharValue::class.java).also { Mockito.`when`(it.value()).thenReturn('X') }
        assertEquals(FeelValue.Text("X"), FeelValueCodec.toFeel(c))
    }

    @Test
    fun toFeel_string_reference_becomes_Text() {
        val s = Mockito.mock(StringReference::class.java)
        Mockito.`when`(s.value()).thenReturn("hello")
        assertEquals(FeelValue.Text("hello"), FeelValueCodec.toFeel(s))
    }

    @Test
    fun toFeel_short_array_becomes_List() {
        val a = Mockito.mock(ArrayReference::class.java)
        val e1 = Mockito.mock(IntegerValue::class.java).also { Mockito.`when`(it.value()).thenReturn(10) }
        val e2 = Mockito.mock(IntegerValue::class.java).also { Mockito.`when`(it.value()).thenReturn(20) }
        Mockito.`when`(a.length()).thenReturn(2)
        Mockito.`when`(a.getValues(0, 2)).thenReturn(listOf(e1, e2))

        val result = FeelValueCodec.toFeel(a)
        assertEquals(
            FeelValue.List(listOf(FeelValue.Number(BigDecimal(10)), FeelValue.Number(BigDecimal(20)))),
            result,
        )
    }

    @Test
    fun toFeel_long_array_truncates_at_max_with_marker_context() {
        val a = Mockito.mock(ArrayReference::class.java)
        val total = FeelValueCodec.MAX_ARRAY_ELEMENTS + 50
        val take = FeelValueCodec.MAX_ARRAY_ELEMENTS
        val ints = (0 until take).map { idx ->
            Mockito.mock(IntegerValue::class.java).also { Mockito.`when`(it.value()).thenReturn(idx) }
        }
        Mockito.`when`(a.length()).thenReturn(total)
        Mockito.`when`(a.getValues(0, take)).thenReturn(ints)

        val result = FeelValueCodec.toFeel(a)
        assertTrue(result is FeelValue.Context, "Expected truncation marker Context")
        val ctx = result
        assertEquals(FeelValue.Boolean(true), ctx.entries[FeelValueCodec.TRUNCATED_KEY])
        assertEquals(FeelValue.Number(BigDecimal(total)), ctx.entries["length_total"])
        assertEquals(FeelValue.Number(BigDecimal(take)), ctx.entries["length_returned"])
        val elements = ctx.entries["elements"]
        assertTrue(elements is FeelValue.List)
        assertEquals(take, elements.elements.size)
    }

    @Test
    fun toFeel_opaque_object_carries_objectId_and_type() {
        resetMint()
        val refType = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(refType.name()).thenReturn("com.example.User")
        val obj = Mockito.mock(ObjectReference::class.java)
        Mockito.`when`(obj.referenceType()).thenReturn(refType)
        Mockito.`when`(obj.uniqueID()).thenReturn(42L)

        val result = FeelValueCodec.toFeel(obj)
        assertTrue(result is FeelValue.Context)
        val ctx = result
        val id = ctx.entries[FeelValueCodec.OBJECT_ID_KEY]
        assertTrue(id is FeelValue.Text)
        assertEquals(FeelValue.Text("com.example.User"), ctx.entries[FeelValueCodec.OBJECT_TYPE_KEY])
        // Round-trip: the id must resolve back to the same JDI ref.
        assertSame(obj, ObjectIdMint.resolveObject(id.value))
    }

    @Test
    fun objectIdOf_returns_id_for_opaque_context_null_for_plain_context() {
        val opaque = FeelValue.Context(
            entries = mapOf(
                FeelValueCodec.OBJECT_ID_KEY to FeelValue.Text("obj#7"),
                FeelValueCodec.OBJECT_TYPE_KEY to FeelValue.Text("X"),
            ),
        )
        assertEquals("obj#7", FeelValueCodec.objectIdOf(opaque))

        val plain = FeelValue.Context(entries = mapOf("name" to FeelValue.Text("Alice")))
        assertNull(FeelValueCodec.objectIdOf(plain))
    }

    // ---------------- fromFeel ----------------

    @Test
    fun fromFeel_null_returns_null() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        assertNull(FeelValueCodec.fromFeel(FeelValue.Null, vm))
        // Should not touch the VM at all.
        Mockito.verifyNoInteractions(vm)
    }

    @Test
    fun fromFeel_boolean_routes_to_mirrorOf() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val mirror = Mockito.mock(BooleanValue::class.java)
        Mockito.`when`(vm.mirrorOf(true)).thenReturn(mirror)
        assertSame(mirror, FeelValueCodec.fromFeel(FeelValue.Boolean(true), vm))
    }

    @Test
    fun fromFeel_text_routes_to_mirrorOf() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val mirror = Mockito.mock(StringReference::class.java)
        Mockito.`when`(vm.mirrorOf("hello")).thenReturn(mirror)
        assertSame(mirror, FeelValueCodec.fromFeel(FeelValue.Text("hello"), vm))
    }

    @Test
    fun fromFeel_integral_number_in_int_range_becomes_int_mirror() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val mirror = Mockito.mock(IntegerValue::class.java)
        Mockito.`when`(vm.mirrorOf(42)).thenReturn(mirror)

        assertSame(mirror, FeelValueCodec.fromFeel(FeelValue.Number(BigDecimal(42)), vm))
    }

    @Test
    fun fromFeel_integral_number_beyond_int_range_becomes_long_mirror() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val mirror = Mockito.mock(LongValue::class.java)
        val bigInt = 10_000_000_000L
        Mockito.`when`(vm.mirrorOf(bigInt)).thenReturn(mirror)

        assertSame(mirror, FeelValueCodec.fromFeel(FeelValue.Number(BigDecimal(bigInt)), vm))
    }

    @Test
    fun fromFeel_decimal_number_becomes_double_mirror() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val mirror = Mockito.mock(DoubleValue::class.java)
        Mockito.`when`(vm.mirrorOf(3.14)).thenReturn(mirror)

        assertSame(mirror, FeelValueCodec.fromFeel(FeelValue.Number(BigDecimal("3.14")), vm))
    }

    @Test
    fun fromFeel_opaque_object_context_resolves_via_ObjectIdMint() {
        resetMint()
        // Register a real-ish JDI ref so the mint round-trip exercises both halves.
        val refType = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(refType.name()).thenReturn("com.example.Foo")
        val obj = Mockito.mock(ObjectReference::class.java)
        Mockito.`when`(obj.referenceType()).thenReturn(refType)
        Mockito.`when`(obj.uniqueID()).thenReturn(99L)
        val id = ObjectIdMint.registerObject(obj)

        val opaque = FeelValue.Context(
            entries = mapOf(
                FeelValueCodec.OBJECT_ID_KEY to FeelValue.Text(id),
                FeelValueCodec.OBJECT_TYPE_KEY to FeelValue.Text("com.example.Foo"),
            ),
        )
        val vm = Mockito.mock(VirtualMachine::class.java)
        val resolved = FeelValueCodec.fromFeel(opaque, vm)
        assertSame(obj, resolved)
    }

    @Test
    fun fromFeel_plain_context_without_objectId_throws() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val plain = FeelValue.Context(entries = mapOf("name" to FeelValue.Text("Alice")))
        val ex = assertFailsWith<UnsupportedCoercionException> { FeelValueCodec.fromFeel(plain, vm) }
        assertTrue("__objectId" in ex.message!!, "Expected message naming the missing key")
    }

    @Test
    fun fromFeel_list_throws_unsupported() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val list = FeelValue.List(listOf(FeelValue.Number(BigDecimal(1)), FeelValue.Number(BigDecimal(2))))
        assertFailsWith<UnsupportedCoercionException> { FeelValueCodec.fromFeel(list, vm) }
    }

    @Test
    fun fromFeel_date_time_duration_range_function_all_throw_unsupported() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val cases = listOf<FeelValue>(
            FeelValue.Date(java.time.LocalDate.of(2026, 5, 11)),
            FeelValue.Time(java.time.LocalTime.NOON),
            FeelValue.DateTime(java.time.ZonedDateTime.now()),
            FeelValue.Duration(java.time.Duration.ofMinutes(1), null),
            FeelValue.Range(FeelValue.Number(BigDecimal(0)), FeelValue.Number(BigDecimal(10)), true, true),
            FeelValue.Function("noop", emptyList()) { FeelValue.Null },
        )
        for (case in cases) {
            assertFailsWith<UnsupportedCoercionException>("Expected throw for $case") {
                FeelValueCodec.fromFeel(case, vm)
            }
        }
    }

    // ---------------- round-trip (toFeel → fromFeel) ----------------

    @Test
    fun roundTrip_opaque_object_preserves_identity() {
        resetMint()
        val refType = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(refType.name()).thenReturn("com.example.User")
        val obj = Mockito.mock(ObjectReference::class.java)
        Mockito.`when`(obj.referenceType()).thenReturn(refType)
        Mockito.`when`(obj.uniqueID()).thenReturn(1234L)

        val feel = FeelValueCodec.toFeel(obj)
        val vm = Mockito.mock(VirtualMachine::class.java)
        val back = FeelValueCodec.fromFeel(feel, vm)
        assertSame(obj, back)
    }

    @Test
    fun roundTrip_primitives_lose_no_precision_for_typical_sizes() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val intMirror = Mockito.mock(IntegerValue::class.java)
        Mockito.`when`(intMirror.value()).thenReturn(123)
        Mockito.`when`(vm.mirrorOf(123)).thenReturn(intMirror)

        val asFeel = FeelValueCodec.toFeel(intMirror)
        val back = FeelValueCodec.fromFeel(asFeel, vm)
        assertNotNull(back)
        // The boxed-back value will be an IntegerValue mirror; we asserted vm.mirrorOf(123)
        // was called, which is the round-trip we care about. Mockito returns the configured mock.
    }
}
