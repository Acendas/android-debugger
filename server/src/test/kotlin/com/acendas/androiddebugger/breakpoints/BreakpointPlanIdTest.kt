package com.acendas.androiddebugger.breakpoints

import com.acendas.androiddebugger.state.SessionPersistence
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.EventRequestManager
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.7 Debug Plan plumbing — covers the planId tag on [BreakpointMeta], the bulk
 * teardown via [BreakpointManager.removeByPlan], and the persistence-skip rule
 * (plan-owned BPs are transient and must NOT round-trip through
 * [SessionPersistence]).
 *
 * Tests avoid the real JDI install path entirely — we construct [BreakpointMeta]
 * directly and register via [BreakpointManager.register] so the tests don't need
 * a fully-mocked VM. [BreakpointAutoRouteTest] covers the install paths.
 */
class BreakpointPlanIdTest {

    private lateinit var vm: VirtualMachine
    private lateinit var erm: EventRequestManager

    @BeforeTest
    fun setup() {
        BreakpointManager.clear()
        // The VM mock only needs to expose an EventRequestManager whose
        // deleteEventRequest is a no-op — BreakpointManager.remove() iterates the
        // meta's activeRequests + deferredPrepareRequests calling disable() +
        // deleteEventRequest(), both wrapped in runCatching. Since we register
        // metas without any active requests, the mocks are barely touched.
        vm = Mockito.mock(VirtualMachine::class.java)
        erm = Mockito.mock(EventRequestManager::class.java)
        Mockito.`when`(vm.eventRequestManager()).thenReturn(erm)
    }

    @AfterTest
    fun teardown() {
        BreakpointManager.clear()
    }

    @Test
    fun breakpointMeta_carries_planId_field() {
        val meta = BreakpointMeta(
            id = 1,
            kind = BreakpointKind.LINE,
            file = "Foo.kt",
            line = 10,
            planId = "plan-abc",
        )
        assertEquals("plan-abc", meta.planId)
    }

    @Test
    fun breakpointMeta_planId_defaults_to_null() {
        val meta = BreakpointMeta(id = 1, kind = BreakpointKind.LINE, file = "Foo.kt", line = 10)
        assertNull(meta.planId)
    }

    @Test
    fun removeByPlan_removes_tagged_bps_and_leaves_untagged_alone() {
        // Two plan-A bps, one plan-B bp, one untagged bp.
        BreakpointManager.register(
            BreakpointMeta(id = 1, kind = BreakpointKind.LINE, file = "A.kt", line = 1, planId = "plan-A"),
        )
        BreakpointManager.register(
            BreakpointMeta(id = 2, kind = BreakpointKind.LINE, file = "A.kt", line = 2, planId = "plan-A"),
        )
        BreakpointManager.register(
            BreakpointMeta(id = 3, kind = BreakpointKind.LINE, file = "B.kt", line = 1, planId = "plan-B"),
        )
        BreakpointManager.register(
            BreakpointMeta(id = 4, kind = BreakpointKind.LINE, file = "C.kt", line = 1),
        )

        val removed = BreakpointManager.removeByPlan(vm, "plan-A")
        assertEquals(setOf(1, 2), removed.toSet())
        // plan-A ids gone.
        assertNull(BreakpointManager.get(1))
        assertNull(BreakpointManager.get(2))
        // plan-B and untagged untouched.
        assertNotNull(BreakpointManager.get(3))
        assertNotNull(BreakpointManager.get(4))
    }

    @Test
    fun removeByPlan_with_no_matches_returns_empty_list() {
        BreakpointManager.register(
            BreakpointMeta(id = 1, kind = BreakpointKind.LINE, file = "A.kt", line = 1),
        )
        BreakpointManager.register(
            BreakpointMeta(id = 2, kind = BreakpointKind.LINE, file = "B.kt", line = 1, planId = "plan-X"),
        )
        val removed = BreakpointManager.removeByPlan(vm, "plan-does-not-exist")
        assertTrue(removed.isEmpty())
        // Neither bp was touched.
        assertNotNull(BreakpointManager.get(1))
        assertNotNull(BreakpointManager.get(2))
    }

    @Test
    fun removeByPlan_is_idempotent() {
        BreakpointManager.register(
            BreakpointMeta(id = 1, kind = BreakpointKind.LINE, file = "A.kt", line = 1, planId = "plan-A"),
        )
        val first = BreakpointManager.removeByPlan(vm, "plan-A")
        assertEquals(listOf(1), first)
        // Second call: nothing left to remove.
        val second = BreakpointManager.removeByPlan(vm, "plan-A")
        assertTrue(second.isEmpty())
    }

    @Test
    fun removeByPlan_does_not_affect_untagged_bps() {
        // Regression guard: a stray null check could match all null planIds; verify
        // removeByPlan("anything") never sweeps untagged bps.
        BreakpointManager.register(
            BreakpointMeta(id = 1, kind = BreakpointKind.LINE, file = "A.kt", line = 1),
        )
        BreakpointManager.register(
            BreakpointMeta(id = 2, kind = BreakpointKind.LINE, file = "B.kt", line = 1),
        )
        val removed = BreakpointManager.removeByPlan(vm, "plan-A")
        assertTrue(removed.isEmpty())
        assertNotNull(BreakpointManager.get(1))
        assertNotNull(BreakpointManager.get(2))
    }

    @Test
    fun sessionPersistence_skips_plan_owned_bps() {
        val tmp: Path = Files.createTempDirectory("plan-id-test-")
        val prev = SessionPersistence.dataDirOverrideForTest
        SessionPersistence.dataDirOverrideForTest = tmp
        try {
            SessionPersistence.resetWarningForTest()
            val untagged = BreakpointMeta(
                id = 1,
                kind = BreakpointKind.LINE,
                file = "Persisted.kt",
                line = 42,
            )
            val planOwned = BreakpointMeta(
                id = 2,
                kind = BreakpointKind.LINE,
                file = "Transient.kt",
                line = 7,
                planId = "plan-abc",
            )
            BreakpointManager.register(untagged)
            BreakpointManager.register(planOwned)

            val saved = SessionPersistence.save(
                serial = "emulator-5554",
                packageName = "com.example.app",
                breakpoints = listOf(untagged, planOwned),
                watches = emptyList(),
            )
            assertNotNull(saved)
            val loaded = SessionPersistence.load("emulator-5554", "com.example.app")
            assertNotNull(loaded)
            // Only the untagged bp round-trips through persistence.
            assertEquals(1, loaded.breakpoints.size)
            assertEquals(1, loaded.breakpoints[0].id)
            assertEquals("Persisted.kt", loaded.breakpoints[0].file)
        } finally {
            SessionPersistence.dataDirOverrideForTest = prev
            SessionPersistence.resetWarningForTest()
        }
    }
}
