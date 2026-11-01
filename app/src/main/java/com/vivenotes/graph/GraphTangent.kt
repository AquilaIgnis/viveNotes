package com.vivenotes.graph

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max

/**
 * The tracer's point on the curve and the tangent there.
 *
 * [slope] is null where the function has no derivative — a corner such as |x| at 0, or a jump — and
 * then no tangent is drawn. Showing 0 there, which is what a central difference across a symmetric
 * corner says, would teach that |x| is flat at its vertex.
 */
data class Tangent(val x: Double, val y: Double, val slope: Double?) {

    /** The tangent's height at [at]. */
    fun lineAt(at: Double): Double = y + (slope ?: Double.NaN) * (at - x)

    /** Where the tangent crosses the vertical axis: the `b` of `y = mx + b`. */
    val intercept: Double? get() = slope?.let { y - it * x }
}

object GraphTangent {

    /**
     * The tangent to [f] at [x], or null where [f] is undefined there.
     *
     * The slope is a five-point central difference, accurate to the fourth power of the step, with
     * the step a hundred-thousandth of the window so it means the same at any zoom. It is refused
     * where the two one-sided slopes disagree — a corner or a jump — rather than averaged across.
     */
    fun at(f: (Double) -> Double, x: Double, window: GraphWindow): Tangent? {
        val y = f(x)
        if (!y.isFinite()) return null
        val h = window.width * 1e-5
        val slope = (8 * (f(x + h) - f(x - h)) - (f(x + 2 * h) - f(x - 2 * h))) / (12 * h)
        val left = (y - f(x - h)) / h
        val right = (f(x + h) - y) / h
        val smooth = slope.isFinite() && left.isFinite() && right.isFinite() &&
            abs(left - right) <= 1e-2 * max(1.0, max(abs(left), abs(right)))
        return Tangent(x, y, slope.takeIf { smooth })
    }

    /**
     * Where a dragged tracer settles: on a point of interest within [magnet] graph units — so a
     * maximum's slope reads exactly 0 — else on the window's fine grid, so x reads `1.5`.
     */
    fun snap(x: Double, points: List<GraphPoint>, magnet: Double, window: GraphWindow): Double {
        points.minByOrNull { abs(it.x - x) }?.takeIf { abs(it.x - x) <= magnet }?.let { return it.x }
        return GraphAnalysis.snap(x, GraphAnalysis.niceStep(window.width, FINE_STEPS)).toDouble()
    }

    /**
     * Where a tracer starts when it is turned on: the middle of the window, or the nearest place to
     * it the function is defined.
     */
    fun start(f: (Double) -> Double, window: GraphWindow): Double? {
        val middle = GraphAnalysis.snap((window.xMin + window.xMax) / 2, GraphAnalysis.niceStep(window.width, FINE_STEPS))
            .toDouble()
        if (f(middle).isFinite()) return middle
        val step = window.width / 200
        for (i in 1..100) {
            if (f(middle + i * step).isFinite()) return middle + i * step
            if (f(middle - i * step).isFinite()) return middle - i * step
        }
        return null
    }

    /** `dy/dx = 3`, in the graph's own names: `dv/dt` for a velocity. */
    fun slopeText(slope: Double, variable: String, dependent: String): String =
        "d$dependent/d$variable = ${number(slope)}"

    /** The tangent as an equation — `y = 3x − 2.25` — written the way a student would. */
    fun lineText(tangent: Tangent, variable: String, dependent: String): String? {
        val m = tangent.slope ?: return null
        val b = tangent.intercept ?: return null
        val slope = round(m)
        val constant = round(b)
        val terms = buildString {
            when {
                slope.signum() == 0 -> Unit
                slope.compareTo(BigDecimal.ONE) == 0 -> append(variable)
                slope.compareTo(BigDecimal.ONE.negate()) == 0 -> append("−").append(variable)
                else -> append(signed(slope)).append(variable)
            }
            when {
                constant.signum() == 0 -> if (isEmpty()) append("0")
                isEmpty() -> append(signed(constant))
                constant.signum() > 0 -> append(" + ").append(constant.toPlainString())
                else -> append(" − ").append(constant.negate().toPlainString())
            }
        }
        return "$dependent = $terms"
    }

    /** Three decimals at most, with a real minus sign. */
    fun number(value: Double): String = signed(round(value))

    private fun round(value: Double): BigDecimal {
        val rounded = BigDecimal(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (rounded.signum() == 0) BigDecimal.ZERO else rounded
    }

    private fun signed(value: BigDecimal): String =
        if (value.signum() < 0) "−" + value.negate().toPlainString() else value.toPlainString()

    /** The same fine grid a dragged point snaps to. */
    private const val FINE_STEPS = 40
}
