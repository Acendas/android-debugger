package com.acendas.androiddebugger.inspection

import com.sun.jdi.ArrayReference
import com.sun.jdi.ClassType
import com.sun.jdi.Field
import com.sun.jdi.IntegerValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per BR-01 (v1.1.1 skill craft review): exception_summary builds the structured
 * root-cause payload. We test the whole pipeline using a mocked `Throwable` mirror
 * (no live VM, no live JDI) — the load-bearing pieces are:
 *
 *   1. **throw_site** — first entry in the StackTraceElement array
 *   2. **trigger_frame** — first user-code frame, skipping framework / VM / kotlin
 *      coroutine boilerplate (delegates to [FrameworkFrames])
 *   3. **cause_chain** — Throwable.cause walked iteratively, broken on self-reference
 *
 * The test fixture builds a fake StackTraceElement[] that mimics what JDI would
 * return for a real exception thrown from user code that bubbled through framework
 * code on the way out.
 */
class ExceptionSummaryTest {

    @Test
    fun trigger_frame_skips_framework_classes_and_picks_first_user_frame() {
        // Stack shape (top -> bottom):
        //   java.lang.Throwable.<init>           (framework — should be skipped)
        //   kotlin.coroutines.Continuation.resume (framework — should be skipped)
        //   com.example.app.PaymentService.charge (USER — should be the trigger)
        //   com.example.app.MainActivity.onClick   (user — but trigger is the first match)
        val exception = mockThrowable(
            className = "java.lang.NullPointerException",
            message = "Attempt to invoke virtual method 'charge' on a null object reference",
            stackTrace = listOf(
                fakeFrame("java.lang.Throwable", "<init>", "Throwable.java", 261),
                fakeFrame("kotlin.coroutines.Continuation", "resume", "ContinuationImpl.kt", 33),
                fakeFrame("com.example.app.PaymentService", "charge", "PaymentService.kt", 87),
                fakeFrame("com.example.app.MainActivity", "onClick", "MainActivity.kt", 42),
            ),
        )
        val json = ExceptionSummary.build(exception, pausedThread = null)
        // throw_site is the literal top of the array.
        val throwSite = json["throw_site"] as JsonObject
        assertEquals("java.lang.Throwable", (throwSite["class"] as JsonPrimitive).contentOrNull)
        assertEquals("<init>", (throwSite["method"] as JsonPrimitive).contentOrNull)
        // trigger_frame is the first non-framework entry.
        val trigger = json["trigger_frame"] as JsonObject
        assertEquals("com.example.app.PaymentService", (trigger["class"] as JsonPrimitive).contentOrNull)
        assertEquals("charge", (trigger["method"] as JsonPrimitive).contentOrNull)
        assertEquals(87, (trigger["line"] as JsonPrimitive).intOrNull)
    }

    @Test
    fun ok_and_exception_class_and_message_present() {
        val exception = mockThrowable(
            className = "java.lang.IllegalStateException",
            message = "scope already closed",
            stackTrace = listOf(fakeFrame("com.example.app.Foo", "bar", "Foo.kt", 10)),
        )
        val json = ExceptionSummary.build(exception, pausedThread = null)
        assertEquals(true, (json["ok"] as JsonPrimitive).booleanOrNull)
        assertEquals("java.lang.IllegalStateException", (json["exception_class"] as JsonPrimitive).contentOrNull)
        assertEquals("scope already closed", (json["message"] as JsonPrimitive).contentOrNull)
    }

    @Test
    fun stack_summary_caps_at_ten_frames() {
        // 25 user frames — the summary should keep only the top 10 in stack_summary
        // (throw_site is the first; stack_summary mirrors the top of the array).
        val frames = (1..25).map {
            fakeFrame("com.example.app.Layer$it", "doWork", "Layer$it.kt", it)
        }
        val exception = mockThrowable(
            className = "java.lang.RuntimeException",
            message = "deep stack",
            stackTrace = frames,
        )
        val json = ExceptionSummary.build(exception, pausedThread = null)
        val summary = json["stack_summary"] as JsonArray
        assertEquals(10, summary.size, "stack_summary should cap at 10 frames")
    }

    @Test
    fun cause_chain_walks_throwable_cause() {
        // Top exception -> cause -> cause-of-cause. We chain three Throwable mocks
        // and verify the agent sees them in order.
        val root = mockThrowable(
            className = "java.io.IOException",
            message = "underlying socket closed",
            stackTrace = emptyList(),
        )
        val mid = mockThrowable(
            className = "java.lang.RuntimeException",
            message = "wrapped in business layer",
            stackTrace = emptyList(),
            cause = root,
        )
        val top = mockThrowable(
            className = "com.example.app.PaymentFailedException",
            message = "checkout failed",
            stackTrace = listOf(fakeFrame("com.example.app.Foo", "bar", "Foo.kt", 1)),
            cause = mid,
        )
        val json = ExceptionSummary.build(top, pausedThread = null)
        val chain = json["cause_chain"] as JsonArray
        assertEquals(2, chain.size)
        val first = chain[0] as JsonObject
        assertEquals("java.lang.RuntimeException", (first["class"] as JsonPrimitive).contentOrNull)
        assertEquals("wrapped in business layer", (first["message"] as JsonPrimitive).contentOrNull)
        val second = chain[1] as JsonObject
        assertEquals("java.io.IOException", (second["class"] as JsonPrimitive).contentOrNull)
    }

    @Test
    fun pure_framework_stack_has_no_trigger_frame() {
        // OOM-from-the-GC-thread case — every frame is platform code. The summary
        // still includes throw_site (whatever's at top) but trigger_frame is omitted.
        val exception = mockThrowable(
            className = "java.lang.OutOfMemoryError",
            message = "GC thread allocation failure",
            stackTrace = listOf(
                fakeFrame("java.lang.OutOfMemoryError", "<init>", "OutOfMemoryError.java", 23),
                fakeFrame("dalvik.system.VMRuntime", "newNonMovableArray", "VMRuntime.java", 100),
            ),
        )
        val json = ExceptionSummary.build(exception, pausedThread = null)
        assertNotNull(json["throw_site"])
        assertNull(json["trigger_frame"], "all-framework stacks should omit trigger_frame")
    }

    @Test
    fun stack_trace_unavailable_when_array_is_null_or_empty() {
        val exception = mockThrowable(
            className = "java.lang.RuntimeException",
            message = "thrown without fillInStackTrace",
            stackTrace = emptyList(),
        )
        val json = ExceptionSummary.build(exception, pausedThread = null)
        assertEquals(true, (json["stack_trace_unavailable"] as JsonPrimitive).booleanOrNull)
        assertNull(json["throw_site"], "no frames -> no throw_site")
    }

    @Test
    fun cause_self_reference_terminates_walk() {
        // Throwable's no-cause init sets cause = this. We must NOT loop forever.
        val exception = mockThrowable(
            className = "java.lang.RuntimeException",
            message = "self-cause-loop",
            stackTrace = emptyList(),
            cause = null, // null cause-field returns the exception itself in our mock helper.
            causeIsSelf = true,
        )
        val json = ExceptionSummary.build(exception, pausedThread = null)
        // cause_chain absent because the only "cause" is self.
        assertTrue(json["cause_chain"] == null || (json["cause_chain"] as JsonArray).isEmpty())
    }

    // ---------------- mock helpers ----------------

    private data class FakeStackTraceElement(
        val declaringClass: String,
        val methodName: String,
        val fileName: String?,
        val lineNumber: Int,
    )

    private fun fakeFrame(cls: String, method: String, file: String?, line: Int): FakeStackTraceElement =
        FakeStackTraceElement(cls, method, file, line)

    /**
     * Build a JDI ObjectReference that, when read by [ExceptionSummary], looks like
     * a freshly-thrown Throwable. We mock:
     *   - referenceType().name() = the exception class
     *   - referenceType().fieldByName("detailMessage" / "cause" / "stackTrace")
     *   - getValue() returning a mocked StringReference / ObjectReference / ArrayReference
     *   - the StackTraceElement objects with their own fields
     *   - the Throwable inheritance chain so isThrowableLike (used in InspectionTools,
     *     not here) would also accept it.
     */
    private fun mockThrowable(
        className: String,
        message: String?,
        stackTrace: List<FakeStackTraceElement>,
        cause: ObjectReference? = null,
        causeIsSelf: Boolean = false,
    ): ObjectReference {
        val obj = mock(ObjectReference::class.java)
        val type = mock(ClassType::class.java)
        whenever(obj.referenceType()).thenReturn(type)
        whenever(type.name()).thenReturn(className)
        // Distinct unique-id per top mock so visited-set dedup works.
        whenever(obj.uniqueID()).thenReturn(System.nanoTime())

        // detailMessage field.
        if (message != null) {
            val msgField = mock(Field::class.java)
            whenever(type.fieldByName("detailMessage")).thenReturn(msgField)
            val msgValue = mock(StringReference::class.java)
            whenever(msgValue.value()).thenReturn(message)
            whenever(obj.getValue(msgField)).thenReturn(msgValue)
        } else {
            whenever(type.fieldByName("detailMessage")).thenReturn(null)
        }

        // cause field — points at another throwable, at self, or null.
        val causeField = mock(Field::class.java)
        whenever(type.fieldByName("cause")).thenReturn(causeField)
        when {
            causeIsSelf -> whenever(obj.getValue(causeField)).thenReturn(obj)
            cause != null -> whenever(obj.getValue(causeField)).thenReturn(cause)
            else -> whenever(obj.getValue(causeField)).thenReturn(null)
        }

        // stackTrace field as an ArrayReference of StackTraceElement-shaped object refs.
        if (stackTrace.isNotEmpty()) {
            val stField = mock(Field::class.java)
            whenever(type.fieldByName("stackTrace")).thenReturn(stField)
            val arr = mock(ArrayReference::class.java)
            whenever(arr.length()).thenReturn(stackTrace.size)
            val elements = stackTrace.map { fakeStackTraceElement(it) }
            whenever(arr.getValues(0, stackTrace.size)).thenReturn(elements)
            whenever(obj.getValue(stField)).thenReturn(arr)
        } else {
            // Either the field is missing (some custom Throwable subclasses) or the
            // array is empty — both should produce stack_trace_unavailable.
            whenever(type.fieldByName("stackTrace")).thenReturn(null)
        }

        return obj
    }

    private fun fakeStackTraceElement(el: FakeStackTraceElement): ObjectReference {
        val obj = mock(ObjectReference::class.java)
        val type = mock(ReferenceType::class.java)
        whenever(obj.referenceType()).thenReturn(type)

        val declField = mock(Field::class.java)
        whenever(type.fieldByName("declaringClass")).thenReturn(declField)
        val declVal = mock(StringReference::class.java)
        whenever(declVal.value()).thenReturn(el.declaringClass)
        whenever(obj.getValue(declField)).thenReturn(declVal)

        val methodField = mock(Field::class.java)
        whenever(type.fieldByName("methodName")).thenReturn(methodField)
        val methodVal = mock(StringReference::class.java)
        whenever(methodVal.value()).thenReturn(el.methodName)
        whenever(obj.getValue(methodField)).thenReturn(methodVal)

        if (el.fileName != null) {
            val fileField = mock(Field::class.java)
            whenever(type.fieldByName("fileName")).thenReturn(fileField)
            val fileVal = mock(StringReference::class.java)
            whenever(fileVal.value()).thenReturn(el.fileName)
            whenever(obj.getValue(fileField)).thenReturn(fileVal)
        } else {
            whenever(type.fieldByName("fileName")).thenReturn(null)
        }

        val lineField = mock(Field::class.java)
        whenever(type.fieldByName("lineNumber")).thenReturn(lineField)
        val lineVal = mock(IntegerValue::class.java)
        whenever(lineVal.value()).thenReturn(el.lineNumber)
        whenever(obj.getValue(lineField)).thenReturn(lineVal)

        return obj
    }
}
