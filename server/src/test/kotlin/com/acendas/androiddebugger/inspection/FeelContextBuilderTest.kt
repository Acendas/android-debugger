package com.acendas.androiddebugger.inspection

import ca.acendas.kfeel.api.FeelValue
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Field
import com.sun.jdi.IntegerValue
import com.sun.jdi.LocalVariable
import com.sun.jdi.Location
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers [FeelContextBuilder] — the JDI frame → kfeel context bridge. Frames are
 * mocked via Mockito so the tests don't need a real attached VM.
 *
 * Most-load-bearing assertion: depth-3 field walking actually materializes
 * `user.address.city` as a navigable FeelValue tree. That's what makes
 * `evaluate("user.address.city")` work without a JDI round-trip per `.`.
 */
class FeelContextBuilderTest {

    private fun resetMint() = ObjectIdMint.clear()

    @Test
    fun locals_with_absent_information_yield_no_entries_but_dont_throw() {
        resetMint()
        val declaring = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(declaring.name()).thenReturn("com.example.Foo")
        Mockito.`when`(declaring.visibleFields()).thenReturn(emptyList())

        val location = Mockito.mock(Location::class.java)
        Mockito.`when`(location.declaringType()).thenReturn(declaring)

        val frame = Mockito.mock(StackFrame::class.java)
        Mockito.`when`(frame.visibleVariables()).thenThrow(AbsentInformationException())
        Mockito.`when`(frame.thisObject()).thenReturn(null)
        Mockito.`when`(frame.location()).thenReturn(location)

        val ctx = FeelContextBuilder.build(frame)
        // No throw. No locals reachable. That's the contract.
        assertEquals(emptySet(), ctx.getVariableNames())
    }

    @Test
    fun primitive_local_lands_as_FeelValue_Number() {
        resetMint()
        val countVar = Mockito.mock(LocalVariable::class.java)
        Mockito.`when`(countVar.name()).thenReturn("count")
        val intVal = Mockito.mock(IntegerValue::class.java)
        Mockito.`when`(intVal.value()).thenReturn(7)

        val frame = baseFrame(
            locals = listOf(countVar),
            valueFor = { v -> if (v == countVar) intVal else null },
        )
        val ctx = FeelContextBuilder.build(frame)
        assertTrue(ctx.hasVariable("count"))
        val feel = ctx.getVariable("count")
        assertTrue(feel is FeelValue.Number)
        assertEquals(0, feel.value.compareTo(java.math.BigDecimal(7)))
    }

    @Test
    fun string_local_lands_as_FeelValue_Text() {
        resetMint()
        val nameVar = Mockito.mock(LocalVariable::class.java)
        Mockito.`when`(nameVar.name()).thenReturn("name")
        val stringRef = Mockito.mock(StringReference::class.java)
        Mockito.`when`(stringRef.value()).thenReturn("Alice")

        val frame = baseFrame(
            locals = listOf(nameVar),
            valueFor = { v -> if (v == nameVar) stringRef else null },
        )
        val ctx = FeelContextBuilder.build(frame)
        assertEquals(FeelValue.Text("Alice"), ctx.getVariable("name"))
    }

    @Test
    fun this_object_with_fields_flattens_and_keeps_this_alias() {
        resetMint()
        // `this` is a User { name: "Alice", age: 30 }
        val nameField = mockField("name", isStatic = false)
        val ageField = mockField("age", isStatic = false)
        val nameVal = Mockito.mock(StringReference::class.java)
        Mockito.`when`(nameVal.value()).thenReturn("Alice")
        val ageVal = Mockito.mock(IntegerValue::class.java)
        Mockito.`when`(ageVal.value()).thenReturn(30)

        val userType = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(userType.name()).thenReturn("com.example.User")
        Mockito.`when`(userType.visibleFields()).thenReturn(listOf(nameField, ageField))

        val self = Mockito.mock(ObjectReference::class.java)
        Mockito.`when`(self.referenceType()).thenReturn(userType)
        Mockito.`when`(self.uniqueID()).thenReturn(101L)
        Mockito.`when`(self.getValue(nameField)).thenReturn(nameVal)
        Mockito.`when`(self.getValue(ageField)).thenReturn(ageVal)

        val frame = baseFrame(locals = emptyList(), thisObject = self)
        val ctx = FeelContextBuilder.build(frame)
        // `this` itself is set as a Context.
        val thisFeel = ctx.getVariable("this")
        assertTrue(thisFeel is FeelValue.Context)
        val thisCtx = thisFeel
        // Markers are present.
        assertNotNull(thisCtx.entries[FeelValueCodec.OBJECT_ID_KEY])
        assertEquals(FeelValue.Text("com.example.User"), thisCtx.entries[FeelValueCodec.OBJECT_TYPE_KEY])
        // Fields are pre-resolved on the `this` Context.
        assertEquals(FeelValue.Text("Alice"), thisCtx.entries["name"])
        // Field shortcuts flattened to top level (so `name` works without `this.name`).
        assertEquals(FeelValue.Text("Alice"), ctx.getVariable("name"))
    }

    @Test
    fun deep_field_walk_resolves_nested_object_fields() {
        resetMint()
        // user.address.city — 3 layers of refs. depth = 3 should resolve all of them.
        val cityField = mockField("city", isStatic = false)
        val cityVal = Mockito.mock(StringReference::class.java)
        Mockito.`when`(cityVal.value()).thenReturn("Toronto")
        val addressType = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(addressType.name()).thenReturn("com.example.Address")
        Mockito.`when`(addressType.visibleFields()).thenReturn(listOf(cityField))
        val address = Mockito.mock(ObjectReference::class.java)
        Mockito.`when`(address.referenceType()).thenReturn(addressType)
        Mockito.`when`(address.uniqueID()).thenReturn(202L)
        Mockito.`when`(address.getValue(cityField)).thenReturn(cityVal)

        val addressField = mockField("address", isStatic = false)
        val userType = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(userType.name()).thenReturn("com.example.User")
        Mockito.`when`(userType.visibleFields()).thenReturn(listOf(addressField))
        val self = Mockito.mock(ObjectReference::class.java)
        Mockito.`when`(self.referenceType()).thenReturn(userType)
        Mockito.`when`(self.uniqueID()).thenReturn(101L)
        Mockito.`when`(self.getValue(addressField)).thenReturn(address)

        val frame = baseFrame(locals = emptyList(), thisObject = self)
        val ctx = FeelContextBuilder.build(frame, fieldDepth = 3)
        val thisCtx = ctx.getVariable("this") as FeelValue.Context
        val addressCtx = thisCtx.entries["address"] as FeelValue.Context
        // The Address ref must carry its own `__objectId` + `__type` markers AND the
        // pre-resolved city field.
        assertEquals(FeelValue.Text("com.example.Address"), addressCtx.entries[FeelValueCodec.OBJECT_TYPE_KEY])
        assertEquals(FeelValue.Text("Toronto"), addressCtx.entries["city"])
    }

    @Test
    fun depth_budget_exhausted_keeps_opaque_marker_only() {
        resetMint()
        // Single nested ref; building with fieldDepth=0 should leave nested refs as
        // opaque markers (no field traversal at all from the receiver).
        val innerField = mockField("inner", isStatic = false)
        val innerType = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(innerType.name()).thenReturn("Inner")
        Mockito.`when`(innerType.visibleFields()).thenReturn(emptyList())
        val inner = Mockito.mock(ObjectReference::class.java)
        Mockito.`when`(inner.referenceType()).thenReturn(innerType)
        Mockito.`when`(inner.uniqueID()).thenReturn(202L)

        val outerType = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(outerType.name()).thenReturn("Outer")
        Mockito.`when`(outerType.visibleFields()).thenReturn(listOf(innerField))
        val self = Mockito.mock(ObjectReference::class.java)
        Mockito.`when`(self.referenceType()).thenReturn(outerType)
        Mockito.`when`(self.uniqueID()).thenReturn(101L)
        Mockito.`when`(self.getValue(innerField)).thenReturn(inner)

        val frame = baseFrame(locals = emptyList(), thisObject = self)
        val ctx = FeelContextBuilder.build(frame, fieldDepth = 0)
        val thisCtx = ctx.getVariable("this") as FeelValue.Context
        // Top `this` itself still carries opaque markers (depth=0 means no walk into refs).
        assertNotNull(thisCtx.entries[FeelValueCodec.OBJECT_ID_KEY])
    }

    @Test
    fun cycle_detection_terminates_on_self_reference() {
        resetMint()
        // self.parent = self → must not loop.
        val parentField = mockField("parent", isStatic = false)
        val selfType = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(selfType.name()).thenReturn("Node")
        Mockito.`when`(selfType.visibleFields()).thenReturn(listOf(parentField))
        val self = Mockito.mock(ObjectReference::class.java)
        Mockito.`when`(self.referenceType()).thenReturn(selfType)
        Mockito.`when`(self.uniqueID()).thenReturn(101L)
        Mockito.`when`(self.getValue(parentField)).thenReturn(self)

        val frame = baseFrame(locals = emptyList(), thisObject = self)
        // Should complete without StackOverflowError.
        val ctx = FeelContextBuilder.build(frame, fieldDepth = 5)
        val thisCtx = ctx.getVariable("this") as FeelValue.Context
        // The parent ref should appear; thanks to cycle detection it's the opaque shell
        // (no further recursive entries).
        val parentCtx = thisCtx.entries["parent"]
        assertTrue(parentCtx is FeelValue.Context)
        assertEquals(FeelValue.Text("Node"), parentCtx.entries[FeelValueCodec.OBJECT_TYPE_KEY])
    }

    // ---------------- helpers ----------------

    private fun baseFrame(
        locals: List<LocalVariable>,
        thisObject: ObjectReference? = null,
        valueFor: (LocalVariable) -> com.sun.jdi.Value? = { null },
    ): StackFrame {
        val declaring = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(declaring.name()).thenReturn("com.example.Frame")
        Mockito.`when`(declaring.visibleFields()).thenReturn(emptyList())

        val location = Mockito.mock(Location::class.java)
        Mockito.`when`(location.declaringType()).thenReturn(declaring)

        val frame = Mockito.mock(StackFrame::class.java)
        Mockito.`when`(frame.visibleVariables()).thenReturn(locals)
        Mockito.`when`(frame.thisObject()).thenReturn(thisObject)
        Mockito.`when`(frame.location()).thenReturn(location)
        for (v in locals) {
            Mockito.`when`(frame.getValue(v)).thenReturn(valueFor(v))
        }
        return frame
    }

    private fun mockField(name: String, isStatic: Boolean): Field {
        val f = Mockito.mock(Field::class.java)
        Mockito.`when`(f.name()).thenReturn(name)
        Mockito.`when`(f.isStatic).thenReturn(isStatic)
        return f
    }
}
