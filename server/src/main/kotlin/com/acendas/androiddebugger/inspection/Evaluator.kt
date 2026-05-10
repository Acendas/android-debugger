package com.acendas.androiddebugger.inspection

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ClassType
import com.sun.jdi.IncompatibleThreadStateException
import com.sun.jdi.InterfaceType
import com.sun.jdi.InvocationException
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-threaded executor for JDI `invokeMethod` calls. Per Story 2.1.5.
 *
 * **Why single-flight**: `invokeMethod` resumes the target thread to run the callee
 * inline; if a second concurrent eval issues another `invokeMethod` on the same
 * (now-resumed) thread, JDI deadlocks the entire VM. We refuse re-entry instead.
 *
 * Two layers of protection:
 *   1. A coroutine [mutex] serializes evaluator entry within the same coroutine context.
 *   2. An [AtomicBoolean] busy flag catches re-entry from any other thread (e.g.
 *      the JDI event loop coroutine vs. an MCP request coroutine). On a busy collision
 *      the evaluator returns an [ErrorCode.VmPaused] error immediately rather than
 *      blocking the second caller behind the first.
 *
 * Default timeout is 10s; the bounded executor will hard-cancel by interrupting the
 * JDI call thread.
 */
object Evaluator {

    private val mutex: Mutex = Mutex()
    private val busy: AtomicBoolean = AtomicBoolean(false)
    /** Single-thread executor so all invokeMethod calls happen off the MCP coroutine pool. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "android-debugger-evaluator").apply { isDaemon = true }
    }

    private const val DEFAULT_TIMEOUT_MS: Long = 10_000

    /**
     * Evaluate a parsed [Expr] in the context of [thread]/[frameIdx]. Returns the
     * resolved JDI [Value] (which the caller renders via [ValueRenderer]).
     */
    fun evaluate(
        thread: ThreadReference,
        frameIdx: Int,
        expr: Expr,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Value? {
        if (!busy.compareAndSet(false, true)) {
            // Re-entry from a different coroutine while a previous eval is in flight.
            throw ToolError(
                errorCode = ErrorCode.VmPaused,
                message = "Another evaluation is already in flight on this session.",
                hint = "Evaluator is single-flight to avoid `invokeMethod` deadlocks. Wait for the prior call to complete.",
            )
        }
        return try {
            // HACK: re-evaluate when the MCP SDK ships proper suspend handlers and
            // [Evaluator.evaluate] can become a `suspend fun`. Today this is also called
            // from the non-suspend JDI event-loop thread (conditional breakpoints,
            // logpoint rendering), so a `runBlocking` is still required for the shared
            // implementation. The mutex below is private to the Evaluator and never
            // contends with the outer Session.mutex held by `runTool`. Per R-03.
            runBlocking {
                mutex.withLock {
                    withTimeout(timeoutMs) {
                        evalInner(thread, frameIdx, expr)
                    }
                }
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * Escape-hatch: invoke [methodName] on [target] (`"this"` or a class name) directly,
     * bypassing the parser. Args are JDI [Value]s, prepared by the caller (typically
     * literals via [boxLiteral] and refs via [ObjectIdMint]).
     *
     * Same single-flight discipline as [evaluate].
     */
    fun invokeRaw(
        thread: ThreadReference,
        frameIdx: Int,
        target: String,
        methodName: String,
        args: List<Value?>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Value? {
        if (!busy.compareAndSet(false, true)) {
            throw ToolError(
                errorCode = ErrorCode.VmPaused,
                message = "Another evaluation is already in flight on this session.",
                hint = "Evaluator is single-flight. Wait for the prior call to complete.",
            )
        }
        return try {
            runBlocking {
                mutex.withLock {
                    withTimeout(timeoutMs) {
                        invokeRawInner(thread, frameIdx, target, methodName, args)
                    }
                }
            }
        } finally {
            busy.set(false)
        }
    }

    // ---------------- internal ----------------

    private fun evalInner(thread: ThreadReference, frameIdx: Int, expr: Expr): Value? {
        if (!thread.isSuspended) {
            throw ToolError(
                errorCode = ErrorCode.VmRunning,
                message = "Thread ${thread.uniqueID()} is not suspended.",
                hint = "evaluate requires a paused thread; nothing to look at otherwise.",
            )
        }
        val frame = try {
            thread.frame(frameIdx)
        } catch (_: Throwable) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Frame $frameIdx out of range on thread ${thread.uniqueID()}.",
            )
        }
        return resolve(thread, frame, expr)
    }

    private fun invokeRawInner(
        thread: ThreadReference,
        frameIdx: Int,
        target: String,
        methodName: String,
        args: List<Value?>,
    ): Value? {
        if (!thread.isSuspended) {
            throw ToolError(
                errorCode = ErrorCode.VmRunning,
                message = "Thread ${thread.uniqueID()} is not suspended.",
            )
        }
        val frame = try { thread.frame(frameIdx) } catch (_: Throwable) {
            throw ToolError(ErrorCode.InvalidTarget, "Frame $frameIdx out of range.")
        }
        // Resolve target: "this" -> frame.thisObject; otherwise treat as class name.
        return if (target == "this") {
            val receiver = frame.thisObject() ?: throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Frame has no `this` (static method or top-level frame).",
            )
            invokeOnObject(thread, receiver, methodName, args)
        } else {
            val type = Session.requireAttached().classesByName(target).firstOrNull()
                ?: throw ToolError(ErrorCode.InvalidTarget, "Class `$target` is not loaded in the VM.")
            if (type !is ClassType) {
                throw ToolError(ErrorCode.InvalidTarget, "Target `$target` is not a class.")
            }
            invokeOnClass(thread, type, methodName, args)
        }
    }

    /**
     * Walk the AST. Identifiers resolve to a [Value]; method calls invoke; member access
     * reads a field on a receiver.
     */
    private fun resolve(thread: ThreadReference, frame: StackFrame, expr: Expr): Value? = when (expr) {
        is LitExpr -> boxLiteral(Session.requireAttached(), expr.value)
        is IdentExpr -> resolveIdentifier(frame, expr.name)
        is MemberExpr -> {
            val receiver = resolve(thread, frame, expr.receiver)
                ?: throw ToolError(ErrorCode.InvalidTarget, "Cannot access `.${expr.member}` on null receiver.")
            readMember(receiver, expr.member)
        }
        is CallExpr -> {
            val args = expr.args.map { resolve(thread, frame, it) }
            // CallExpr's receiver is always present (we synthesize IdentExpr("this") for bare calls).
            val recv = resolve(thread, frame, expr.receiver)
            when {
                recv is ObjectReference -> invokeOnObject(thread, recv, expr.method, args)
                // Static method call would require a CastExpr to introduce a class; we don't support
                // bare unqualified static names in v1. Use eval_method as the escape hatch.
                else -> throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "Cannot invoke `${expr.method}` — receiver is null or primitive.",
                    hint = "For static method calls, use the `eval_method` tool with `target` set to the class name.",
                )
            }
        }
        is CastExpr -> {
            // We don't perform real conversion; the cast in JDI-eval terms is informational.
            // The inner value carries its own JDI type; method/member resolution uses runtime types.
            resolve(thread, frame, expr.inner)
        }
    }

    /**
     * Identifier resolution order (per Task 2.1.5.3):
     * 1. Frame locals
     * 2. `thisObject` instance fields
     * 3. Enclosing class static fields (declaring type of the current method)
     * 4. The literal name `this` -> frame.thisObject()
     */
    fun resolveIdentifier(frame: StackFrame, name: String): Value? {
        if (name == "this") return frame.thisObject()
        // 1. Locals
        var localsStripped = false
        try {
            val v = frame.visibleVariableByName(name)
            if (v != null) return frame.getValue(v)
        } catch (_: AbsentInformationException) {
            // Release builds strip locals; record the fact so we can surface a
            // capability-shaped error if every fallback also misses. Fall through
            // to fields. Per Story 7.1.2 / Task 7.1.2.2.
            localsStripped = true
        }
        // 2. `this` fields
        val self = frame.thisObject()
        if (self != null) {
            val f = self.referenceType().fieldByName(name)
            if (f != null) return self.getValue(f)
        }
        // 3. Static fields of the enclosing type
        val declaring = frame.location().declaringType()
        val staticField = declaring.fieldByName(name)
        if (staticField != null && staticField.isStatic) {
            return declaring.getValue(staticField)
        }
        if (localsStripped) {
            // Story 7.1.2: identifier resolution failed AND the frame has stripped
            // locals — most likely the agent is asking for a local that R8 erased.
            // Surface a code the agent can react to.
            throw ToolError(
                errorCode = ErrorCode.AbsentLocalVariables,
                message = "Identifier `$name` could not be resolved; this frame has stripped local-variable info.",
                hint = "this build appears to be R8/ProGuard-stripped — rebuild as debug variant",
            )
        }
        throw ToolError(
            errorCode = ErrorCode.InvalidTarget,
            message = "Identifier `$name` not found in frame locals, `this` fields, or enclosing-class statics.",
            hint = "Check spelling, or use `frame_snapshot` to see what's in scope.",
        )
    }

    /**
     * Read `.member` on `receiver`. For [ObjectReference] receivers this is a field read.
     * Strings/arrays don't have user-readable fields beyond their length etc.; we don't
     * special-case them — `someArray.length` is *not* supported (use the existing
     * `get_array_slice` tool's `length_total`).
     */
    private fun readMember(receiver: Value, member: String): Value? {
        if (receiver !is ObjectReference) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Cannot read `.$member` on a primitive or null value.",
            )
        }
        val field = receiver.referenceType().fieldByName(member)
            ?: throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Field `$member` not found on ${receiver.referenceType().name()}.",
                hint = "Use inspect_object to enumerate available fields.",
            )
        return receiver.getValue(field)
    }

    /**
     * Invoke an instance method. Picks the first method whose name matches and whose
     * arity equals `args.size`. For overloaded methods this is a heuristic — the
     * `eval_method` escape hatch lets the agent pin a specific signature if needed.
     *
     * All `invokeMethod` calls carry [ObjectReference.INVOKE_SINGLE_THREADED] so we
     * don't accidentally resume the whole VM (which would void other paused frames).
     */
    private fun invokeOnObject(
        thread: ThreadReference,
        receiver: ObjectReference,
        methodName: String,
        args: List<Value?>,
    ): Value? {
        val methods = collectMethods(receiver.referenceType(), methodName, args.size)
        val method = pickMethod(methods, args)
            ?: throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Method `$methodName` with ${args.size} arg(s) not found on ${receiver.referenceType().name()}.",
                hint = "Use eval_method to specify a fully-qualified target if the receiver chain is ambiguous.",
            )
        return invokeWithJdiErrorMapping {
            // Run the actual JDI invokeMethod on a dedicated thread so withTimeout above
            // can interrupt cleanly if the target hangs.
            executor.submit<Value?> {
                receiver.invokeMethod(thread, method, args, ObjectReference.INVOKE_SINGLE_THREADED)
            }.get()
        }
    }

    /** Invoke a static method on a [ClassType]. Used by `eval_method` for `target = "<ClassName>"`. */
    private fun invokeOnClass(
        thread: ThreadReference,
        klass: ClassType,
        methodName: String,
        args: List<Value?>,
    ): Value? {
        val methods = collectMethods(klass, methodName, args.size).filter { it.isStatic }
        val method = pickMethod(methods, args)
            ?: throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Static method `$methodName` with ${args.size} arg(s) not found on ${klass.name()}.",
            )
        return invokeWithJdiErrorMapping {
            executor.submit<Value?> {
                klass.invokeMethod(thread, method, args, ClassType.INVOKE_SINGLE_THREADED)
            }.get()
        }
    }

    private fun collectMethods(type: ReferenceType, name: String, arity: Int): List<Method> = when (type) {
        is ClassType -> type.allMethods()
        is InterfaceType -> type.allMethods()
        else -> type.methodsByName(name)
    }.filter { it.name() == name && it.argumentTypeNames().size == arity }

    /**
     * For overloaded methods we prefer the one whose declared parameter types accept
     * each provided arg. This is best-effort: JDI doesn't tell us much about runtime
     * vs. declared types, and ART's verifier is strict about wrappers vs. primitives.
     * If all candidates are equally bad, we return the first.
     */
    private fun pickMethod(candidates: List<Method>, args: List<Value?>): Method? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        // Prefer candidates where every primitive arg matches a primitive parameter
        // (or every reference arg matches a reference parameter — null counts as "any reference").
        return candidates.maxByOrNull { m ->
            val params = m.argumentTypeNames()
            args.zip(params).count { (arg, param) ->
                val isPrimParam = param in JAVA_PRIMITIVE_NAMES
                val isPrimArg = arg is com.sun.jdi.PrimitiveValue
                isPrimParam == isPrimArg
            }
        }
    }

    private val JAVA_PRIMITIVE_NAMES = setOf(
        "boolean", "byte", "char", "short", "int", "long", "float", "double",
    )

    /**
     * Box a literal into the appropriate JDI mirror for `invokeMethod` arg passing.
     * Per Task 2.1.5.4 — ART's verifier rejects unboxed primitives that HotSpot would
     * accept, so this routes through `vm.mirrorOf` for every primitive type.
     */
    fun boxLiteral(vm: VirtualMachine, lit: LiteralValue): Value? = when (lit) {
        is LiteralValue.Str -> vm.mirrorOf(lit.value)
        is LiteralValue.Int32 -> vm.mirrorOf(lit.value)
        is LiteralValue.Int64 -> vm.mirrorOf(lit.value)
        is LiteralValue.Flt -> vm.mirrorOf(lit.value)
        is LiteralValue.Dbl -> vm.mirrorOf(lit.value)
        is LiteralValue.Bool -> vm.mirrorOf(lit.value)
        is LiteralValue.Chr -> vm.mirrorOf(lit.value)
        LiteralValue.Null -> null
    }

    /**
     * Translate JDI invocation faults into [ToolError]s with friendly codes/hints.
     * Wraps the inner block; never lets a raw JDI exception escape.
     */
    private inline fun invokeWithJdiErrorMapping(block: () -> Value?): Value? {
        return try {
            block()
        } catch (e: java.util.concurrent.ExecutionException) {
            mapJdiException(e.cause ?: e)
        } catch (e: Throwable) {
            mapJdiException(e)
        }
    }

    private fun mapJdiException(t: Throwable): Nothing {
        when (t) {
            is InvocationException -> {
                val ex = t.exception()
                val typeName = ex?.referenceType()?.name() ?: "?"
                throw ToolError(
                    errorCode = ErrorCode.InvalidTarget,
                    message = "Target method threw $typeName.",
                    hint = "The method ran but raised an exception; see frame_snapshot to inspect target state.",
                )
            }
            is IncompatibleThreadStateException -> throw ToolError(
                errorCode = ErrorCode.VmRunning,
                message = "Thread is not in a state that supports `invokeMethod`.",
                hint = "Ensure the VM is paused at a breakpoint/step before evaluating.",
            )
            is com.sun.jdi.VMDisconnectedException -> throw ToolError(
                errorCode = ErrorCode.VmDisconnected,
                message = "VM disconnected during evaluation.",
            )
            else -> throw ToolError(
                errorCode = ErrorCode.Internal,
                message = "Evaluation failed: ${t.message ?: t::class.simpleName}",
            )
        }
    }

    /** Test/debug hook: shut the executor down. Not normally called in production. */
    @Suppress("unused")
    fun shutdown() {
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
