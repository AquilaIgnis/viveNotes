package com.vivenotes.graph

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * What dragging one point of interest does to the function, while the finger is still down.
 *
 * The point stays under the finger; which parameter moves depends on the point:
 *
 * | Point | Moves along | The function becomes |
 * |---|---|---|
 * | zero of a polynomial | the x-axis | that root moves with its multiplicity, nothing else does |
 * | zero of anything else | the x-axis | `f(x − h)` |
 * | y-intercept | the y-axis | `f(x) + k` |
 * | maximum, minimum, inflection | anywhere | `f(x − h) + k` |
 *
 * Drawn from [evaluate] at frame rate; on the lift [request] is sent to `mutate_graph`, which writes
 * the same function exactly. Every number in a request is snapped to a round step first, so the
 * rewritten function reads `x² − 3x + 1.5` rather than the decimals a finger produces.
 */
sealed interface GraphDrag {
    /** Where the dragged point is drawn now. */
    val pointX: Double
    val pointY: Double

    /** The function as it will be once this drag is let go. */
    fun evaluate(x: Double): Double

    /** `mutate_graph`'s mutation JSON. */
    fun request(): String

    /** Whether anything would change: a drag that ends where it started is not an edit. */
    val changes: Boolean

    /** `f(x − h) + k`. */
    class Shift(
        private val base: (Double) -> Double,
        val h: BigDecimal,
        val k: BigDecimal,
        override val pointX: Double,
        override val pointY: Double,
    ) : GraphDrag {
        private val dx = h.toDouble()
        private val dy = k.toDouble()
        override fun evaluate(x: Double): Double = base(x - dx) + dy
        override fun request(): String = """{"kind":"shift","h":"${h.toPlainString()}","k":"${k.toPlainString()}"}"""
        override val changes: Boolean get() = h.signum() != 0 || k.signum() != 0
    }

    /** One root of a polynomial, moved to [to]: `q(x)·(x − to)^m`, q the polynomial with it divided out. */
    class Root(
        private val quotient: DoubleArray,
        private val multiplicity: Int,
        val root: Double,
        val to: BigDecimal,
    ) : GraphDrag {
        private val target = to.toDouble()
        override val pointX: Double get() = target
        override val pointY: Double get() = 0.0
        override fun evaluate(x: Double): Double = horner(quotient, x) * (x - target).pow(multiplicity)
        override fun request(): String = """{"kind":"root","root":"$root","to":"${to.toPlainString()}"}"""
        override val changes: Boolean get() = abs(target - root) > 1e-12 * max(1.0, abs(root))
    }

    companion object {
        /**
         * The drag of [point] on [base] to ([x], [y]) in graph units, snapped to [window]'s fine grid.
         *
         * [polynomial] is `base.polynomial()`, passed in so a drag reads it once rather than on every
         * frame.
         */
        fun of(
            base: (Double) -> Double,
            polynomial: DoubleArray?,
            point: GraphPoint,
            x: Double,
            y: Double,
            window: GraphWindow,
        ): GraphDrag {
            val xStep = GraphAnalysis.niceStep(window.width, SNAP_STEPS)
            val yStep = GraphAnalysis.niceStep(window.height, SNAP_STEPS)
            return when (point.kind) {
                PointKind.Zero -> polynomial?.let { rootDrag(it, point.x, x, xStep) }
                    ?: shift(base, point, GraphAnalysis.snap(x - point.x, xStep), BigDecimal.ZERO)
                PointKind.YIntercept ->
                    shift(base, point, BigDecimal.ZERO, GraphAnalysis.snap(y - point.y, yStep))
                PointKind.Maximum, PointKind.Minimum, PointKind.Inflection -> shift(
                    base,
                    point,
                    GraphAnalysis.snap(x - point.x, xStep),
                    GraphAnalysis.snap(y - point.y, yStep),
                )
            }
        }

        /** Positions snap to about this many steps across the window: 0.5 on a window 20 wide. */
        private const val SNAP_STEPS = 40

        private fun shift(base: (Double) -> Double, point: GraphPoint, h: BigDecimal, k: BigDecimal): GraphDrag =
            Shift(base, h, k, point.x + h.toDouble(), point.y + k.toDouble())

        /**
         * Null when [root] does not divide [coefficients] — a zero found numerically that the
         * polynomial does not quite have, which then moves by shifting instead.
         */
        private fun rootDrag(coefficients: DoubleArray, root: Double, x: Double, step: BigDecimal): GraphDrag? {
            if (coefficients.size < 2) return null
            val scale = coefficients.sumOf { abs(it) } * max(1.0, abs(root)).pow(coefficients.size - 1)
            var quotient = coefficients
            var multiplicity = 0
            while (quotient.size > 1) {
                val (next, remainder) = divideByRoot(quotient, root)
                // A double root found by bisection is only accurate to about √ε, so its remainder
                // is far from zero in absolute terms; relative to the coefficients it is still tiny.
                if (abs(remainder) > scale * 1e-6) break
                quotient = next
                multiplicity++
            }
            if (multiplicity == 0) return null
            return Root(quotient, multiplicity, root, GraphAnalysis.snap(x, step))
        }

        /** Synthetic division by (x − r), coefficients constant term first. */
        private fun divideByRoot(coefficients: DoubleArray, r: Double): Pair<DoubleArray, Double> {
            val degree = coefficients.lastIndex
            val quotient = DoubleArray(degree)
            var carry = 0.0
            for (power in degree downTo 1) {
                carry = carry * r + coefficients[power]
                quotient[power - 1] = carry
            }
            return quotient to (carry * r + coefficients[0])
        }

        private fun horner(coefficients: DoubleArray, x: Double): Double {
            var value = 0.0
            for (index in coefficients.indices.reversed()) value = value * x + coefficients[index]
            return value
        }
    }
}
