package com.vivenotes.graph

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * A placed graph's function, read from the grammar `math_engine.py` writes, and evaluated.
 *
 * The grammar is numbers, the variable written `x` whatever the graph calls it, `+ - * / ^`,
 * parentheses, `pi`, `E` and the names in [Function]. Python's `_GraphParser` reads the same
 * language; the two must accept exactly the same strings.
 *
 * Evaluated here rather than in Python because the canvas asks for thousands of values a frame
 * while a point is dragged. Undefined values — a square root of a negative, a pole — are NaN, never
 * an exception: a curve with gaps is the normal case.
 */
class GraphExpression private constructor(private val root: Node) {

    /** f(x), or NaN where the function is undefined or not real. */
    fun evaluate(x: Double): Double = root.evaluate(x)

    /**
     * The coefficients, constant term first, when this is a polynomial in `x`; otherwise null.
     *
     * A polynomial is what lets a single root be dragged on its own — see `GraphDrag.Root`. Only
     * `+ - *`, division by a constant and whole non-negative powers qualify, so `1/x` and `sqrt(x)`
     * are refused. Degree is capped where floating point stops being honest about the roots.
     */
    fun polynomial(): DoubleArray? = root.polynomial()?.takeIf { it.size - 1 <= MAX_DEGREE }?.trimmed()

    sealed interface Node {
        fun evaluate(x: Double): Double
        fun polynomial(): DoubleArray?
    }

    private data class Constant(val value: Double) : Node {
        override fun evaluate(x: Double): Double = value
        override fun polynomial(): DoubleArray = doubleArrayOf(value)
    }

    private data object Variable : Node {
        override fun evaluate(x: Double): Double = x
        override fun polynomial(): DoubleArray = doubleArrayOf(0.0, 1.0)
    }

    private data class Negate(val operand: Node) : Node {
        override fun evaluate(x: Double): Double = -operand.evaluate(x)
        override fun polynomial(): DoubleArray? = operand.polynomial()?.let { p -> DoubleArray(p.size) { -p[it] } }
    }

    private data class Binary(val operator: Char, val left: Node, val right: Node) : Node {
        override fun evaluate(x: Double): Double {
            val a = left.evaluate(x)
            val b = right.evaluate(x)
            return when (operator) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> if (b == 0.0) Double.NaN else a / b
                else -> power(a, b)
            }
        }

        override fun polynomial(): DoubleArray? {
            val a = left.polynomial() ?: return null
            if (operator == '^') {
                val exponent = right as? Constant ?: return null
                val whole = exponent.value.roundToInt()
                if (exponent.value != whole.toDouble() || whole < 0 || whole > MAX_DEGREE) return null
                var result = doubleArrayOf(1.0)
                repeat(whole) { result = multiply(result, a) }
                return result
            }
            val b = right.polynomial() ?: return null
            return when (operator) {
                '+' -> add(a, b, 1.0)
                '-' -> add(a, b, -1.0)
                '*' -> multiply(a, b).takeIf { it.size - 1 <= MAX_DEGREE }
                // Only by a constant: anything else is a rational function, which has no roots to
                // move one at a time.
                else -> if (b.trimmed().size == 1 && b[0] != 0.0) DoubleArray(a.size) { a[it] / b[0] } else null
            }
        }
    }

    private data class Call(val function: Function, val argument: Node) : Node {
        override fun evaluate(x: Double): Double = function.apply(argument.evaluate(x))
        override fun polynomial(): DoubleArray? = null
    }

    /** The functions the grammar names. `ln` is the natural logarithm. */
    enum class Function(val token: String) {
        Sin("sin"), Cos("cos"), Tan("tan"), Cot("cot"), Sec("sec"), Csc("csc"),
        Asin("asin"), Acos("acos"), Atan("atan"),
        Sinh("sinh"), Cosh("cosh"), Tanh("tanh"),
        Exp("exp"), Ln("ln"), Sqrt("sqrt"), Abs("abs"),
        Floor("floor"), Ceil("ceil"), Sign("sign");

        fun apply(value: Double): Double = when (this) {
            Sin -> sin(value)
            Cos -> cos(value)
            Tan -> tan(value)
            Cot -> 1.0 / tan(value)
            Sec -> 1.0 / cos(value)
            Csc -> 1.0 / sin(value)
            Asin -> asin(value)
            Acos -> acos(value)
            Atan -> atan(value)
            Sinh -> sinh(value)
            Cosh -> cosh(value)
            Tanh -> tanh(value)
            Exp -> exp(value)
            Ln -> if (value > 0.0) ln(value) else Double.NaN
            Sqrt -> if (value >= 0.0) sqrt(value) else Double.NaN
            Abs -> abs(value)
            Floor -> floor(value)
            Ceil -> ceil(value)
            Sign -> sign(value)
        }

        companion object {
            private val byToken = entries.associateBy(Function::token)
            fun of(token: String): Function? = byToken[token]
        }
    }

    /** Recursive descent over the grammar; null on anything it does not contain. */
    private class Parser(private val source: String) {
        private var at = 0
        private var depth = 0

        fun parse(): Node? {
            val node = sum() ?: return null
            skipSpace()
            return if (at == source.length) node else null
        }

        private fun sum(): Node? {
            var node = product() ?: return null
            while (true) {
                val operator = peek()
                if (operator != '+' && operator != '-') return node
                at++
                val right = product() ?: return null
                node = Binary(operator, node, right)
            }
        }

        private fun product(): Node? {
            var node = unary() ?: return null
            while (true) {
                val operator = peek()
                if (operator != '*' && operator != '/') return node
                at++
                val right = unary() ?: return null
                node = Binary(operator, node, right)
            }
        }

        private fun unary(): Node? {
            if (peek() == '-') {
                at++
                return nested { unary() }?.let(::Negate)
            }
            return power()
        }

        /** Right-associative, and binding tighter than a leading minus on its exponent's base. */
        private fun power(): Node? {
            val base = atom() ?: return null
            if (peek() != '^') return base
            at++
            val exponent = nested { unary() } ?: return null
            return Binary('^', base, exponent)
        }

        private fun atom(): Node? {
            val next = peek() ?: return null
            if (next == '(') {
                at++
                val inner = nested { sum() } ?: return null
                return if (peek() == ')') inner.also { at++ } else null
            }
            if (next.isDigit()) return number()
            if (!next.isLetter()) return null
            val start = at
            while (at < source.length && source[at].isLetter()) at++
            return when (val name = source.substring(start, at)) {
                "x" -> Variable
                "pi" -> Constant(PI)
                "E" -> Constant(E)
                else -> {
                    val function = Function.of(name) ?: return null
                    if (peek() != '(') return null
                    at++
                    val argument = nested { sum() } ?: return null
                    if (peek() != ')') return null
                    at++
                    Call(function, argument)
                }
            }
        }

        private fun number(): Node? {
            val match = NUMBER.matchAt(source, at) ?: return null
            at = match.range.last + 1
            return match.value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::Constant)
        }

        /** Guards the recursion: a hostile string of ten thousand brackets is refused, not a crash. */
        private inline fun nested(read: () -> Node?): Node? {
            if (++depth > MAX_DEPTH) return null
            return read().also { depth-- }
        }

        private fun peek(): Char? {
            skipSpace()
            return source.getOrNull(at)
        }

        private fun skipSpace() {
            while (at < source.length && source[at].isWhitespace()) at++
        }
    }

    companion object {
        /** Past this a polynomial's roots are not worth dragging one at a time. */
        const val MAX_DEGREE = 12

        /** The same ceiling `math_engine.py` puts on what it writes. */
        const val MAX_SOURCE_CHARS = 4000

        private const val MAX_DEPTH = 200
        private val NUMBER = Regex("""\d+(?:\.\d*)?(?:[eE][-+]?\d+)?""")

        /** The function [source] names, or null when it is not in the grammar. */
        fun parse(source: String): GraphExpression? {
            if (source.isBlank() || source.length > MAX_SOURCE_CHARS) return null
            return Parser(source).parse()?.let(::GraphExpression)
        }

        private fun power(base: Double, exponent: Double): Double {
            // A negative base to a fractional power is not real, which `pow` already says with
            // NaN — except for an odd root written as a fraction, which the grammar cannot express
            // exactly and so is left undefined too, as SymPy's principal root is.
            return base.pow(exponent)
        }

        private fun add(a: DoubleArray, b: DoubleArray, sign: Double): DoubleArray =
            DoubleArray(maxOf(a.size, b.size)) { (a.getOrElse(it) { 0.0 }) + sign * (b.getOrElse(it) { 0.0 }) }

        private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
            val result = DoubleArray(a.size + b.size - 1)
            for (i in a.indices) for (j in b.indices) result[i + j] += a[i] * b[j]
            return result
        }

        private fun DoubleArray.trimmed(): DoubleArray {
            var last = lastIndex
            while (last > 0 && this[last] == 0.0) last--
            return copyOf(last + 1)
        }
    }
}
