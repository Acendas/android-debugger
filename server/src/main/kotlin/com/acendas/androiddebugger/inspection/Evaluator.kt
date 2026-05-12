package com.acendas.androiddebugger.inspection

import ca.acendas.kfeel.api.FeelContext
import ca.acendas.kfeel.api.FeelExpression
import ca.acendas.kfeel.api.FeelValue
import ca.acendas.kfeel.api.FeelContextKeyException
import ca.acendas.kfeel.api.FeelEvaluationException
import ca.acendas.kfeel.api.FeelException
import ca.acendas.kfeel.api.FeelInvalidArgumentException
import ca.acendas.kfeel.api.FeelOperationException
import ca.acendas.kfeel.api.FeelParseException
import ca.acendas.kfeel.api.FeelTypeException
import ca.acendas.kfeel.api.FeelUndefinedFunctionException
import ca.acendas.kfeel.api.FeelUndefinedVariableException
import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.Session
import com.acendas.androiddebugger.ToolError
import com.sun.jdi.ClassType
import com.sun.jdi.IncompatibleThreadStateException
import com.sun.jdi.InterfaceType
import com.sun.jdi.InvocationException
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Expression evaluator backed by **kfeel** ([ca.acendas:kfeel:1.0.0], DMN 1.3 FEEL).
 * Per Story A.1.5 of the v1.3 plan.
 *
 * **The deal vs. v1.2**:
 *
 *  - `evaluate(thread, frameIdx, exprString, ...)` takes a raw FEEL expression string
 *    and returns a [FeelValue]. The v1.2 hand-rolled parser / [Expr] AST is retired.
 *  - `invokeRaw(thread, frameIdx, target, methodName, args, ...)` keeps the v1.2
 *    surface as the explicit method-call escape hatch backing the `eval_method` MCP
 *    tool — same mutation-refusal regex, same single-flight discipline, same
 *    `invokeMethod` dispatch. Returns a [FeelValue] instead of a JDI [Value] so the
 *    rendering pipeline is uniform (callers route through [FeelValueRenderer]).
 *  - FEEL has no syntax for `obj.method(args)`, and kfeel's published 1.0.0 surface
 *    doesn't let us register user functions. The clean split: `evaluate` for pure
 *    expressions over the pre-resolved context built by [FeelContextBuilder]
 *    (binary ops, ternary, instance of, comprehensions, ranges, property access on
 *    deeply-resolved object trees); `eval_method` / [invokeRaw] for method calls.
 *
 * **Single-flight + 10s timeout still applies.** `invokeMethod` resumes the target
 * thread to run the callee inline; concurrent calls deadlock JDI. We refuse re-entry
 * via an [AtomicBoolean] busy flag (returns [ErrorCode.VmPaused]) and cap each call
 * at [DEFAULT_TIMEOUT_MS] via [withTimeout]. The bounded [executor] runs the actual
 * JDI invocation on a dedicated thread so the timeout can interrupt cleanly.
 *
 * **Mutation refusal** is unchanged from v1.2: the [isLikelyMutator] predicate
 * classifies method names matching the documented mutator patterns; [invokeRaw]
 * checks before dispatching and throws [ErrorCode.VmMutationRefused] unless the
 * caller passed `allowMutation = true`.
 */
object Evaluator {

    /** Single-thread executor so all invokeMethod calls happen off the MCP coroutine pool. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "android-debugger-evaluator").apply { isDaemon = true }
    }

    private const val DEFAULT_TIMEOUT_MS: Long = VmCoordinator.EVAL_TIMEOUT_MS

    /**
     * Evaluate [expr] (a FEEL expression string) in the context of [thread]/[frameIdx].
     * Returns a [FeelValue] suitable for [FeelValueRenderer.render] / [FeelValueRenderer.toJson].
     *
     * FEEL syntax — see kfeel docs for the full grammar. Quick reference:
     *
     *   - Identifiers resolve against frame locals → `this` fields → enclosing-class statics
     *   - Property access: `user.address.city` (pre-resolved by [FeelContextBuilder])
     *   - Arithmetic: `+ - * / **`
     *   - Comparison: `= != < <= > >=`
     *   - Boolean: `and or not`
     *   - Conditional: `if x > 10 then "big" else "small"`
     *   - Type check: `value instance of string`
     *   - Lists: `[1, 2, 3]`, `count(items)`, `sum(items)`, `every x in items satisfies x > 0`
     *   - Ranges: `x in [1..10]`, `score in (passing_grade..100]`
     *   - Three-valued null logic: operations on `null` propagate `null` rather than throwing
     *
     * For method calls on JDI references, use `eval_method` (the [invokeRaw] backing).
     */
    fun evaluate(
        thread: ThreadReference,
        frameIdx: Int,
        expr: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): FeelValue {
        // v1.5 (spec §8): the eval/hot-swap single-flight mutex moved to VmCoordinator.
        // Routing both eval_method and hot_swap_class through it prevents the
        // "redefine while invoke runs" race that would leave the agent reading
        // either old or new bytecode unpredictably.
        //
        // HACK: re-evaluate when the MCP SDK ships proper suspend handlers and the
        // Evaluator can become a `suspend fun`. Currently called from both suspending
        // tool contexts and the non-suspending JDI event-loop thread (conditional
        // breakpoints, logpoint rendering), so runBlocking stays. Per R-03.
        return runBlocking {
            VmCoordinator.withExclusiveAccess("eval_method", timeoutMs) {
                withTimeout(timeoutMs) { evalInner(thread, frameIdx, expr) }
            }
        }
    }

    /**
     * Invoke [methodName] on [target] (`"this"` or a class name) with [args]. Backs the
     * `eval_method` MCP tool — the explicit method-call surface in v1.3. Returns a
     * [FeelValue] for uniform rendering through [FeelValueRenderer].
     *
     * Same single-flight + timeout + mutation-refusal discipline as [evaluate].
     */
    fun invokeRaw(
        thread: ThreadReference,
        frameIdx: Int,
        target: String,
        methodName: String,
        args: List<Value?>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        allowMutation: Boolean = false,
    ): FeelValue {
        // v1.5 — see VmCoordinator note in [evaluate].
        return runBlocking {
            VmCoordinator.withExclusiveAccess("eval_method", timeoutMs) {
                withTimeout(timeoutMs) {
                    val jdi = invokeRawInner(thread, frameIdx, target, methodName, args, allowMutation)
                    FeelValueCodec.toFeel(jdi)
                }
            }
        }
    }

    /**
     * Per BR-02: refusal pattern for method names that look like state mutators.
     *
     * Matches:
     *   - `^(set|clear|delete|remove|add|put|update|reset|apply|commit|insert|drop|
     *      destroy|invalidate|cancel)[A-Z_].*` — `setName`, `clear_cache`, etc.
     *   - The bare names `apply` / `commit` / `clear` / `reset`.
     *
     * Does **not** match:
     *   - Names ending in `OrThrow` — `setOrThrow` is allowed because the suffix is a
     *     read-validation idiom in the project's coding style.
     */
    fun isLikelyMutator(methodName: String): Boolean {
        if (methodName.endsWith("OrThrow")) return false
        val bareMutators = setOf("apply", "commit", "clear", "reset")
        if (methodName in bareMutators) return true
        return MUTATOR_REGEX.matches(methodName)
    }

    private val MUTATOR_REGEX: Regex = Regex(
        "^(set|clear|delete|remove|add|put|update|reset|apply|commit|insert|drop|destroy|invalidate|cancel)[A-Z_].*",
    )

    private fun refuseIfMutator(methodName: String, className: String, allowMutation: Boolean) {
        if (allowMutation) return
        if (!isLikelyMutator(methodName)) return
        throw ToolError(
            errorCode = ErrorCode.VmMutationRefused,
            message = "$methodName looks like a state mutator. " +
                "Pass `allow_mutation: true` if you actually want to invoke it.",
            hint = "Read-only inspection is the default; mutators need explicit consent.",
            currentState = "$className.$methodName",
        )
    }

    // ---------------- internal ----------------

    private fun evalInner(thread: ThreadReference, frameIdx: Int, expr: String): FeelValue {
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
        val context: FeelContext = FeelContextBuilder.build(frame)
        return try {
            FeelExpression.parse(expr).evaluate(context)
        } catch (e: FeelParseException) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Parse error in FEEL expression: ${e.message}",
                hint = "See the `evaluate` tool description for FEEL syntax; for method calls use `eval_method`.",
            )
        } catch (e: FeelUndefinedVariableException) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = e.message ?: "Identifier not in scope.",
                hint = "Check spelling, or use `frame_snapshot` to see what's in scope.",
            )
        } catch (e: FeelContextKeyException) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = e.message ?: "Identifier not in scope.",
                hint = "Check spelling, or use `frame_snapshot` to see what's in scope.",
            )
        } catch (e: FeelUndefinedFunctionException) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = e.message ?: "Unknown function.",
                hint = "FEEL only supports its built-in functions in `evaluate`. " +
                    "For method calls on JDI references, use `eval_method` instead.",
            )
        } catch (e: FeelTypeException) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = e.message ?: "Type error in FEEL expression.",
                hint = "Check the operand types — e.g., `+` between a number and a string isn't valid FEEL.",
            )
        } catch (e: FeelInvalidArgumentException) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = e.message ?: "Invalid function argument.",
            )
        } catch (e: FeelOperationException) {
            throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = e.message ?: "Operation error in FEEL expression.",
            )
        } catch (e: FeelEvaluationException) {
            throw ToolError(
                errorCode = ErrorCode.Internal,
                message = e.message ?: "FEEL evaluation failed.",
            )
        } catch (e: FeelException) {
            throw ToolError(
                errorCode = ErrorCode.Internal,
                message = e.message ?: "FEEL error.",
            )
        }
    }

    private fun invokeRawInner(
        thread: ThreadReference,
        frameIdx: Int,
        target: String,
        methodName: String,
        args: List<Value?>,
        allowMutation: Boolean,
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
        // Resolve target: "this" -> frame.thisObject; otherwise treat as a class name OR
        // an `obj#N` opaque-object refId minted by FeelValueCodec / ObjectIdMint.
        if (target == "this") {
            val receiver = frame.thisObject() ?: throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Frame has no `this` (static method or top-level frame).",
            )
            refuseIfMutator(methodName, receiver.referenceType().name(), allowMutation)
            return invokeOnObject(thread, receiver, methodName, args)
        }
        if (target.startsWith("obj#")) {
            val receiver = ObjectIdMint.resolveObject(target) ?: throw ToolError(
                errorCode = ErrorCode.InvalidTarget,
                message = "Unknown ref `$target`.",
            )
            refuseIfMutator(methodName, receiver.referenceType().name(), allowMutation)
            return invokeOnObject(thread, receiver, methodName, args)
        }
        val type = Session.requireAttached().classesByName(target).firstOrNull()
            ?: throw ToolError(ErrorCode.InvalidTarget, "Class `$target` is not loaded in the VM.")
        if (type !is ClassType) {
            throw ToolError(ErrorCode.InvalidTarget, "Target `$target` is not a class.")
        }
        refuseIfMutator(methodName, type.name(), allowMutation)
        return invokeOnClass(thread, type, methodName, args)
    }

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
                hint = "Use eval_method with a fully-qualified target if the receiver chain is ambiguous.",
            )
        return invokeWithJdiErrorMapping {
            executor.submit<Value?> {
                receiver.invokeMethod(thread, method, args, ObjectReference.INVOKE_SINGLE_THREADED)
            }.get()
        }
    }

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

    private fun pickMethod(candidates: List<Method>, args: List<Value?>): Method? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
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
