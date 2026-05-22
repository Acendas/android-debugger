package com.acendas.androiddebugger.plans

import ca.acendas.kfeel.api.FeelContext
import ca.acendas.kfeel.api.FeelValue
import java.math.BigDecimal

/**
 * Pre-pass that rewrites `dbg.*` calls in a FEEL expression into synthetic variable
 * references with pre-computed values.
 *
 * Background: kfeel 1.0.0 has no public API for registering user functions. The
 * iterative-anchor plan executor still needs to expose debugger-introspection
 * primitives inside FEEL conditions (`dbg.instance_count("...")`, etc.), so this
 * pre-pass scans the expression source, evaluates each `dbg.X(...)` call out of
 * band via [DbgDispatcher] (every recognized function is side-effect-free), and
 * substitutes the result into the expression as a reference to a synthetic
 * `__dbg_N` variable. The caller then injects the variable map into the
 * [FeelContext] before evaluation.
 *
 * The substitution is shallow: arguments are parsed as literals (string / int /
 * null) directly from source — no FEEL-inside-args. Cross-call nesting like
 * `dbg.has_capability(dbg.thread_state("main"))` is intentionally out of scope.
 *
 * Identical `dbg.X(...)` invocations within one expression collapse to a single
 * evaluation, sharing the same synthetic variable.
 */
object DebuggerContext {

    /** Output of one [preprocess] call. */
    data class Rewrite(
        val expression: String,
        val injected: Map<String, FeelValue>,
        val errors: List<String>,
    )

    /**
     * Dispatcher the plan executor implements against the live debug session. Each
     * method is invoked at most once per distinct `(name, raw args)` per expression.
     * Implementations may throw — exceptions are caught, recorded in
     * [Rewrite.errors], and substituted as `null` at the call site so the
     * expression remains syntactically valid.
     */
    interface DbgDispatcher {
        fun instanceCount(classSignature: String): FeelValue
        fun isReachable(ref: String, rootKind: String?): FeelValue
        fun threadState(threadKey: String): FeelValue
        fun frameCount(threadKey: String?): FeelValue
        fun hasCapability(capName: String): FeelValue
        fun elapsedMs(): FeelValue
        fun logcatSince(since: String, filter: String?): FeelValue
    }

    // The regex deliberately rejects nested parens. Argument lists are flat literals.
    private val DBG_CALL = Regex("""dbg\.([a-z_]+)\s*\(([^)]*)\)""")

    fun preprocess(expr: String, dispatcher: DbgDispatcher): Rewrite {
        val literalSpans = stringLiteralSpans(expr)
        val injected = linkedMapOf<String, FeelValue>()
        val errors = mutableListOf<String>()

        // Memoize by the matched substring so duplicate calls collapse.
        val seen = linkedMapOf<String, String>()
        val rewritten = StringBuilder()
        var cursor = 0

        for (match in DBG_CALL.findAll(expr)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (insideLiteral(start, literalSpans)) continue

            rewritten.append(expr, cursor, start)

            val raw = match.value
            val varName = seen[raw]
            if (varName != null) {
                rewritten.append(varName)
                cursor = end
                continue
            }

            val fnName = match.groupValues[1]
            val rawArgs = match.groupValues[2]
            val newName = "__dbg_${injected.size}"

            val value = try {
                val args = parseArgs(rawArgs)
                evaluateCall(fnName, args, dispatcher)
            } catch (e: DbgRewriteException) {
                errors.add("$raw: ${e.message}")
                FeelValue.Null
            } catch (e: Throwable) {
                errors.add("$raw: dispatcher threw ${e.javaClass.simpleName}: ${e.message ?: ""}")
                FeelValue.Null
            }

            injected[newName] = value
            seen[raw] = newName
            rewritten.append(newName)
            cursor = end
        }
        rewritten.append(expr, cursor, expr.length)

        return Rewrite(rewritten.toString(), injected, errors)
    }

    fun inject(context: FeelContext, injected: Map<String, FeelValue>) {
        for ((name, value) in injected) {
            context.setVariable(name, value)
        }
    }

    // ---------------- argument parsing ----------------

    private sealed class Arg {
        data class Str(val v: String) : Arg()
        data class Int_(val v: Long) : Arg()
        object Nul : Arg()
    }

    private fun parseArgs(raw: String): List<Arg> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        val args = mutableListOf<Arg>()
        var i = 0
        val s = trimmed
        while (i < s.length) {
            // skip leading whitespace
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break

            val c = s[i]
            when {
                c == '"' || c == '\'' -> {
                    val (value, next) = readQuoted(s, i, c)
                    args.add(Arg.Str(value))
                    i = next
                }
                c == 'n' && s.startsWith("null", i) &&
                    (i + 4 == s.length || !s[i + 4].isLetterOrDigit() && s[i + 4] != '_') -> {
                    args.add(Arg.Nul)
                    i += 4
                }
                c == '-' || c.isDigit() -> {
                    val start = i
                    if (c == '-') i++
                    while (i < s.length && s[i].isDigit()) i++
                    val numText = s.substring(start, i)
                    val n = numText.toLongOrNull()
                        ?: throw DbgRewriteException("invalid integer literal '$numText'")
                    args.add(Arg.Int_(n))
                }
                else -> throw DbgRewriteException("unexpected character '${c}' in args")
            }

            // skip trailing whitespace
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            if (s[i] != ',') {
                throw DbgRewriteException("expected ',' between args, found '${s[i]}'")
            }
            i++
        }
        return args
    }

    private fun readQuoted(s: String, start: Int, quote: Char): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val esc = s[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(esc)
                }
                i += 2
                continue
            }
            if (c == quote) {
                return sb.toString() to (i + 1)
            }
            sb.append(c)
            i++
        }
        throw DbgRewriteException("unterminated string literal")
    }

    // ---------------- string-literal span detection ----------------

    private fun stringLiteralSpans(expr: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c == '"' || c == '\'') {
                val open = c
                val start = i
                i++
                while (i < expr.length) {
                    val ch = expr[i]
                    if (ch == '\\' && i + 1 < expr.length) {
                        i += 2
                        continue
                    }
                    if (ch == open) {
                        spans.add(start..i)
                        i++
                        break
                    }
                    i++
                }
                if (i >= expr.length) {
                    // unterminated literal — treat the rest as one span so we don't rewrite inside it
                    spans.add(start..expr.length - 1)
                }
            } else {
                i++
            }
        }
        return spans
    }

    private fun insideLiteral(pos: Int, spans: List<IntRange>): Boolean =
        spans.any { pos in it }

    // ---------------- dispatch ----------------

    private class DbgRewriteException(message: String) : RuntimeException(message)

    private fun evaluateCall(
        fnName: String,
        args: List<Arg>,
        d: DbgDispatcher,
    ): FeelValue = when (fnName) {
        "instance_count" -> {
            requireArity(fnName, args, 1)
            d.instanceCount(stringArg(fnName, args, 0))
        }
        "is_reachable" -> {
            requireArity(fnName, args, 1, 2)
            val ref = stringArg(fnName, args, 0)
            val rootKind = if (args.size == 2) optionalStringArg(fnName, args, 1) else null
            d.isReachable(ref, rootKind)
        }
        "thread_state" -> {
            requireArity(fnName, args, 1)
            d.threadState(stringOrIntArg(fnName, args, 0))
        }
        "frame_count" -> {
            requireArity(fnName, args, 0, 1)
            val thread = if (args.isEmpty()) null else optionalStringOrIntArg(fnName, args, 0)
            d.frameCount(thread)
        }
        "has_capability" -> {
            requireArity(fnName, args, 1)
            d.hasCapability(stringArg(fnName, args, 0))
        }
        "elapsed_ms" -> {
            requireArity(fnName, args, 0)
            d.elapsedMs()
        }
        "logcat_since" -> {
            requireArity(fnName, args, 1, 2)
            val since = stringArg(fnName, args, 0)
            val filter = if (args.size == 2) optionalStringArg(fnName, args, 1) else null
            d.logcatSince(since, filter)
        }
        else -> throw DbgRewriteException("unknown dbg function 'dbg.$fnName'")
    }

    private fun requireArity(fn: String, args: List<Arg>, vararg allowed: Int) {
        if (args.size !in allowed.toList()) {
            throw DbgRewriteException(
                "dbg.$fn expects ${allowed.joinToString("/")} args, got ${args.size}"
            )
        }
    }

    private fun stringArg(fn: String, args: List<Arg>, i: Int): String {
        val a = args[i]
        if (a !is Arg.Str) throw DbgRewriteException("dbg.$fn arg $i must be a string")
        return a.v
    }

    private fun optionalStringArg(fn: String, args: List<Arg>, i: Int): String? = when (val a = args[i]) {
        is Arg.Str -> a.v
        is Arg.Nul -> null
        else -> throw DbgRewriteException("dbg.$fn arg $i must be a string or null")
    }

    private fun stringOrIntArg(fn: String, args: List<Arg>, i: Int): String = when (val a = args[i]) {
        is Arg.Str -> a.v
        is Arg.Int_ -> a.v.toString()
        else -> throw DbgRewriteException("dbg.$fn arg $i must be a string or integer")
    }

    private fun optionalStringOrIntArg(fn: String, args: List<Arg>, i: Int): String? = when (val a = args[i]) {
        is Arg.Str -> a.v
        is Arg.Int_ -> a.v.toString()
        is Arg.Nul -> null
    }

    // ---------------- public helpers for dispatcher implementations ----------------

    /** Pack an integer as the FeelValue type kfeel expects for numeric dispatch results. */
    fun number(n: Long): FeelValue = FeelValue.Number(BigDecimal.valueOf(n))

    /** Pack a boolean. */
    fun bool(b: Boolean): FeelValue = FeelValue.Boolean(b)

    /** Pack a string. */
    fun text(s: String): FeelValue = FeelValue.Text(s)
}
