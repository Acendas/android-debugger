package com.acendas.androiddebugger.breakpoints

import com.acendas.androiddebugger.inspection.Evaluator
import com.acendas.androiddebugger.inspection.ExprParser
import com.acendas.androiddebugger.inspection.ParseException
import com.acendas.androiddebugger.inspection.ValueRenderer
import com.sun.jdi.ThreadReference

/**
 * Render a logpoint's mini-template against a paused thread/frame.
 *
 * Template syntax: `"text {expr} more text {other.expr}"`. Each `{...}` is parsed by the
 * existing [ExprParser] and evaluated via [Evaluator] in the top frame of [thread]; the
 * rendered value (string form, no quotes) replaces the placeholder. Literal `{` is
 * escaped as `{{`; literal `}` as `}}`. An expression that fails to parse or evaluate
 * is replaced with `<error: ...>` so the logpoint never crashes a hot loop.
 *
 * Per Story 3.1.4 / Task 3.1.4.2.
 */
object LogMessageRenderer {

    /** Parse [template] into a list of segments — pure text or an expression placeholder. */
    fun parse(template: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                // Escaped '{{' -> literal '{'.
                c == '{' && i + 1 < template.length && template[i + 1] == '{' -> {
                    sb.append('{')
                    i += 2
                }
                // Escaped '}}' -> literal '}'.
                c == '}' && i + 1 < template.length && template[i + 1] == '}' -> {
                    sb.append('}')
                    i += 2
                }
                c == '{' -> {
                    if (sb.isNotEmpty()) {
                        out += Segment.Text(sb.toString())
                        sb.setLength(0)
                    }
                    // Find matching '}'.
                    val end = template.indexOf('}', startIndex = i + 1)
                    if (end < 0) {
                        // Unterminated placeholder — treat the rest as literal text so we
                        // never throw on a typo'd template.
                        sb.append(template.substring(i))
                        i = template.length
                    } else {
                        val expr = template.substring(i + 1, end).trim()
                        out += Segment.Expr(expr)
                        i = end + 1
                    }
                }
                c == '}' -> {
                    // Stray '}' — include literally.
                    sb.append(c)
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        if (sb.isNotEmpty()) out += Segment.Text(sb.toString())
        return out
    }

    /**
     * Render [template] in the top frame of [thread]. Each `{...}` placeholder evaluates
     * via [Evaluator]; failures land as `<error: ...>` rather than throwing. The
     * returned string is bounded by the rendered-value length cap (200 chars per
     * substitution, set by [ValueRenderer]); the overall message can be longer.
     */
    fun render(template: String, thread: ThreadReference): String {
        val segments = parse(template)
        val sb = StringBuilder()
        for (seg in segments) {
            when (seg) {
                is Segment.Text -> sb.append(seg.text)
                is Segment.Expr -> sb.append(renderOneExpr(seg.expr, thread))
            }
        }
        return sb.toString()
    }

    private fun renderOneExpr(expr: String, thread: ThreadReference): String {
        if (expr.isEmpty()) return ""
        val ast = try {
            ExprParser.parse(expr)
        } catch (e: ParseException) {
            return "<parse error: ${e.message}>"
        } catch (e: Throwable) {
            return "<parse error: ${e.message ?: e::class.simpleName}>"
        }
        val value = try {
            Evaluator.evaluate(thread, frameIdx = 0, expr = ast)
        } catch (e: Throwable) {
            return "<eval error: ${e.message ?: e::class.simpleName}>"
        }
        // ValueRenderer gives us the same form locals/snapshot uses — bounded length, quoted strings, etc.
        return ValueRenderer.render(value).rendered
    }

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Expr(val expr: String) : Segment
    }
}
