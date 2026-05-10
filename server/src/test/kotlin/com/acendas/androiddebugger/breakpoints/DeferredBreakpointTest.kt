package com.acendas.androiddebugger.breakpoints

import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.ClassPrepareRequest
import com.sun.jdi.request.EventRequestManager
import org.mockito.Mockito
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R-08: cover the deferred line-breakpoint resolution path that fires when a
 * ClassPrepareEvent matches an outstanding deferred prepare request. The path is the
 * spine of Kotlin inline-fn / lambda support — without it, breakpoints on lines inside
 * inline lambdas never bind because the synthetic class loads only on first call.
 *
 * We exercise [BreakpointManager.installDeferredLineBreakpoint] directly with fakes /
 * Mockito stand-ins for the JDI surface. The assertion is that the manager:
 *   1. Resolves locations against the just-prepared type AND its recursive nestedTypes
 *   2. Creates exactly one breakpoint request per resolved location (no duplicates)
 *   3. Wires the new requests into [BreakpointMeta.activeRequests] AND the reverse index
 *      so [BreakpointManager.findByRequest] returns the right meta on first hit
 */
class DeferredBreakpointTest {

    @AfterTest
    fun cleanup() {
        BreakpointManager.clear()
    }

    @Test
    fun deferred_line_breakpoint_resolves_against_outer_type_and_nested_types() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val erm = Mockito.mock(EventRequestManager::class.java)
        Mockito.`when`(vm.eventRequestManager()).thenReturn(erm)

        // Outer class — this is the type that just prepared. Source name matches the
        // meta's `file`, no locations directly on line 17 (the inline lambda lives in
        // a nested anonymous class).
        val outer = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(outer.sourceName()).thenReturn("MainActivity.kt")
        Mockito.`when`(outer.locationsOfLine(Mockito.eq("Kotlin"), Mockito.isNull(), Mockito.eq(17)))
            .thenReturn(emptyList())
        Mockito.`when`(outer.locationsOfLine(17)).thenReturn(emptyList())

        // Nested class generated for the lambda — has the line we care about.
        val nested = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(nested.sourceName()).thenReturn("MainActivity.kt")
        val nestedLoc = Mockito.mock(Location::class.java)
        val nestedMethod = Mockito.mock(Method::class.java)
        Mockito.`when`(nestedLoc.method()).thenReturn(nestedMethod)
        Mockito.`when`(nestedLoc.lineNumber()).thenReturn(17)
        // First stratum lookup hits — return a single location.
        Mockito.`when`(nested.locationsOfLine(Mockito.eq("Kotlin"), Mockito.isNull(), Mockito.eq(17)))
            .thenReturn(listOf(nestedLoc))
        Mockito.`when`(nested.locationsOfLine(17)).thenReturn(listOf(nestedLoc))

        // Wire nested-types: outer.nestedTypes() returns [nested]; nested.nestedTypes()
        // empty (terminal).
        Mockito.`when`(outer.nestedTypes()).thenReturn(listOf(nested))
        Mockito.`when`(nested.nestedTypes()).thenReturn(emptyList())
        Mockito.`when`(outer.name()).thenReturn("com.example.MainActivity")
        Mockito.`when`(nested.name()).thenReturn("com.example.MainActivity\$onCreate\$1")

        // The breakpoint request the EventRequestManager hands back when we ask it to
        // create one for our location. We capture it to verify it lands in activeRequests.
        val createdReq = Mockito.mock(BreakpointRequest::class.java)
        Mockito.`when`(createdReq.location()).thenReturn(nestedLoc)
        Mockito.`when`(erm.createBreakpointRequest(nestedLoc)).thenReturn(createdReq)

        // Register a deferred line-bp meta as the BreakpointTools attach flow would.
        val meta = BreakpointMeta(
            id = BreakpointManager.mintId(),
            kind = BreakpointKind.LINE,
            file = "MainActivity.kt",
            line = 17,
        )
        BreakpointManager.register(meta)

        // Sanity: meta has no active requests yet.
        assertEquals(0, meta.activeRequests.size)

        // Fire the deferred resolution. This is what handleClassPrepare invokes from
        // EventLoop when a ClassPrepareEvent matches.
        val added = BreakpointManager.installDeferredLineBreakpoint(vm, meta, outer)

        assertEquals(1, added, "expected exactly one new location resolved on the nested type")
        assertEquals(
            1,
            meta.activeRequests.size,
            "the new BreakpointRequest must be wired into meta.activeRequests",
        )
        assertTrue(meta.activeRequests.first() === createdReq)

        // Per R-19: the reverse index must resolve the request back to this meta.
        val resolved = BreakpointManager.findByRequest(createdReq)
        assertTrue(resolved === meta, "findByRequest must return the owning meta after deferred resolution")
    }

    @Test
    fun re_preparing_same_class_does_not_double_register_bp() {
        val vm = Mockito.mock(VirtualMachine::class.java)
        val erm = Mockito.mock(EventRequestManager::class.java)
        Mockito.`when`(vm.eventRequestManager()).thenReturn(erm)

        val type = Mockito.mock(ReferenceType::class.java)
        Mockito.`when`(type.sourceName()).thenReturn("Foo.kt")
        val loc = Mockito.mock(Location::class.java)
        val method = Mockito.mock(Method::class.java)
        Mockito.`when`(loc.method()).thenReturn(method)
        Mockito.`when`(loc.lineNumber()).thenReturn(42)
        Mockito.`when`(type.locationsOfLine(Mockito.eq("Kotlin"), Mockito.isNull(), Mockito.eq(42)))
            .thenReturn(listOf(loc))
        Mockito.`when`(type.locationsOfLine(42)).thenReturn(listOf(loc))
        Mockito.`when`(type.nestedTypes()).thenReturn(emptyList())
        Mockito.`when`(type.name()).thenReturn("com.example.Foo")

        val req = Mockito.mock(BreakpointRequest::class.java)
        Mockito.`when`(req.location()).thenReturn(loc)
        Mockito.`when`(erm.createBreakpointRequest(loc)).thenReturn(req)

        val meta = BreakpointMeta(
            id = BreakpointManager.mintId(),
            kind = BreakpointKind.LINE,
            file = "Foo.kt",
            line = 42,
        )
        BreakpointManager.register(meta)

        val firstAdded = BreakpointManager.installDeferredLineBreakpoint(vm, meta, type)
        val secondAdded = BreakpointManager.installDeferredLineBreakpoint(vm, meta, type)

        assertEquals(1, firstAdded)
        assertEquals(0, secondAdded, "duplicate location must not create a second BreakpointRequest")
        assertEquals(1, meta.activeRequests.size)
    }
}
