package com.vivenotes.graph

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** The part of the plane a graph shows, in the function's own units. */
data class GraphWindow(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
    val width: Double get() = xMax - xMin
    val height: Double get() = yMax - yMin

    /** Scaled about its centre: below 1 zooms in. Never collapses to nothing. */
    fun zoomed(factor: Double): GraphWindow {
        val cx = (xMin + xMax) / 2
        val cy = (yMin + yMax) / 2
        val halfWidth = max(width * factor / 2, MIN_SPAN)
        val halfHeight = max(height * factor / 2, MIN_SPAN)
        return GraphWindow(cx - halfWidth, cx + halfWidth, cy - halfHeight, cy + halfHeight)
    }

    val isUsable: Boolean
        get() = xMin.isFinite() && xMax.isFinite() && yMin.isFinite() && yMax.isFinite() &&
            width > 0 && height > 0

    companion object {
        val DEFAULT = GraphWindow(-10.0, 10.0, -10.0, 10.0)
        private const val MIN_SPAN = 1e-6
    }
}

/** What a point of interest is. Order is drawing order: later kinds are painted over earlier ones. */
enum class PointKind { Inflection, Maximum, Minimum, YIntercept, Zero }

data class GraphPoint(val x: Double, val y: Double, val kind: PointKind)

/**
 * The points of interest of a function in a window, found numerically.
 *
 * Numerical because they must follow a drag at frame rate — Desmos makes the same choice for the same
 * reason, and labels its points with approximations too. The window is sampled, then each sign change
 * is narrowed by bisection:
 *
 * - **zeros** where f changes sign, and where an extremum sits on the axis (x² at 0);
 * - **the y-intercept** where 0 is in view;
 * - **maxima and minima** where a central difference changes sign;
 * - **inflection points** where the second difference changes sign, only when it is clearly away from
 *   zero on both sides, so the rounding noise of a straight stretch is never reported.
 *
 * A sign change across a pole (1/x, tan) is a break in the curve, never a zero: the bisection walks
 * into the pole and the value there is refused. A kind that turns up more than [MAX_PER_KIND] times —
 * sin(50x) — is left out altogether rather than painted as a row of dots nobody can tell apart.
 */
object GraphAnalysis {

    const val MAX_PER_KIND = 20
    private const val SAMPLES = 400
    private const val BISECTIONS = 64

    fun pointsOfInterest(f: (Double) -> Double, window: GraphWindow): List<GraphPoint> {
        if (!window.isUsable) return emptyList()
        val step = window.width / SAMPLES
        val xs = DoubleArray(SAMPLES + 1) { window.xMin + it * step }
        val ys = DoubleArray(xs.size) { f(xs[it]) }
        val tolerance = max(window.height, 1e-9) * 1e-7

        val zeros = zeros(f, xs, ys)
        val (maxima, minima) = extrema(f, xs, ys, window)
        val points = mutableListOf<GraphPoint>()

        // An extremum on the axis is a touching zero: x² at 0, (x − 1)²(x + 3) at 1. It is shown once,
        // as the zero, since that is the point a student looks for and drags.
        val touching = (maxima + minima).filter { abs(it.second) <= tolerance }
        val allZeros = (zeros + touching.map { it.first }).distinctNear(step)
        points += allZeros.capped()?.map { GraphPoint(it, 0.0, PointKind.Zero) }.orEmpty()

        maxima.filterNot { abs(it.second) <= tolerance }.capped()
            ?.forEach { points += GraphPoint(it.first, it.second, PointKind.Maximum) }
        minima.filterNot { abs(it.second) <= tolerance }.capped()
            ?.forEach { points += GraphPoint(it.first, it.second, PointKind.Minimum) }
        inflections(f, xs, window).capped()
            ?.forEach { points += GraphPoint(it.first, it.second, PointKind.Inflection) }

        if (window.xMin <= 0.0 && window.xMax >= 0.0) {
            val y0 = f(0.0)
            // At the origin it is already shown as a zero.
            if (y0.isFinite() && allZeros.none { abs(it) <= step / 2 }) {
                points += GraphPoint(0.0, y0, PointKind.YIntercept)
            }
        }
        return points.sortedBy { it.kind.ordinal }
    }

    private fun zeros(f: (Double) -> Double, xs: DoubleArray, ys: DoubleArray): List<Double> {
        val found = mutableListOf<Double>()
        for (i in 0 until xs.lastIndex) {
            val a = ys[i]
            val b = ys[i + 1]
            if (!a.isFinite() || !b.isFinite()) continue
            if (a == 0.0) {
                found += xs[i]
                continue
            }
            if (a * b > 0.0 || b == 0.0) continue
            val root = bisect(f, xs[i], xs[i + 1], a)
            // Refuses a pole: walking into one, |f| grows past both ends instead of shrinking. NaN
            // fails the comparison too.
            if (abs(f(root)) <= max(abs(a), abs(b))) found += root
        }
        if (ys.last() == 0.0) found += xs.last()
        return found.distinctNear(xs[1] - xs[0])
    }

    /**
     * Where [g] changes sign after sample [i], or null. [values] is [g] at the samples [xs].
     *
     * A sign change *onto* a sample — the slope of x² is exactly 0 at a sample landing on 0 — is
     * read across the zero to the sample after it, or the parabola's vertex would be missed whenever
     * the window is symmetric about it, which is most of the time.
     */
    private fun signChange(g: (Double) -> Double, xs: DoubleArray, values: DoubleArray, i: Int): Double? {
        val a = values[i]
        val b = values[i + 1]
        if (!a.isFinite() || !b.isFinite() || a == 0.0) return null
        if (b == 0.0) {
            val c = values.getOrNull(i + 2) ?: return null
            return if (c.isFinite() && a * c < 0.0) xs[i + 1] else null
        }
        return if (a * b < 0.0) bisect(g, xs[i], xs[i + 1], a) else null
    }

    /** Where [f] changes sign between [lo] and [hi], given f([lo]) = [fLo]. */
    private fun bisect(f: (Double) -> Double, lo: Double, hi: Double, fLo: Double): Double {
        var a = lo
        var b = hi
        var fa = fLo
        repeat(BISECTIONS) {
            val m = (a + b) / 2
            val fm = f(m)
            if (!fm.isFinite()) return m
            if (fm == 0.0) return m
            if ((fm < 0.0) == (fa < 0.0)) {
                a = m
                fa = fm
            } else {
                b = m
            }
        }
        return (a + b) / 2
    }

    private fun extrema(
        f: (Double) -> Double,
        xs: DoubleArray,
        ys: DoubleArray,
        window: GraphWindow,
    ): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val h = window.width * 1e-6
        fun slope(x: Double): Double = (f(x + h) - f(x - h)) / (2 * h)
        val slopes = DoubleArray(xs.size) { slope(xs[it]) }
        val maxima = mutableListOf<Pair<Double, Double>>()
        val minima = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until xs.lastIndex) {
            val a = slopes[i]
            val b = slopes.getOrElse(i + 2) { 0.0 }.takeIf { slopes[i + 1] == 0.0 } ?: slopes[i + 1]
            if (!ys[i].isFinite() || !ys[i + 1].isFinite()) continue
            val x = signChange(::slope, xs, slopes, i) ?: continue
            val y = f(x)
            // A pole between two samples also flips the slope's sign; there the slope at the
            // turning point is enormous rather than about zero, and the point is not an extremum.
            if (!y.isFinite() || abs(slope(x)) > max(abs(a), abs(b))) continue
            if (a > 0.0) maxima += x to y else minima += x to y
        }
        return maxima to minima
    }

    private fun inflections(
        f: (Double) -> Double,
        xs: DoubleArray,
        window: GraphWindow,
    ): List<Pair<Double, Double>> {
        val h = window.width * 1e-3
        fun bend(x: Double): Double = (f(x + h) - 2 * f(x) + f(x - h)) / (h * h)
        val bends = DoubleArray(xs.size) { bend(xs[it]) }
        val scale = xs.map { abs(f(it)) }.filter { it.isFinite() }.maxOrNull() ?: return emptyList()
        // Rounding in f, divided by h², is all a straight stretch's second difference is made of.
        val noise = (scale + 1.0) * 1e-9 / (h * h)
        val found = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until xs.lastIndex) {
            val a = bends[i]
            val b = bends.getOrElse(i + 2) { 0.0 }.takeIf { bends[i + 1] == 0.0 } ?: bends[i + 1]
            if (abs(a) <= noise || abs(b) <= noise) continue
            val x = signChange(::bend, xs, bends, i) ?: continue
            val y = f(x)
            if (!y.isFinite() || abs(bend(x)) > max(abs(a), abs(b))) continue
            found += x to y
        }
        return found
    }

    /**
     * A window that shows what is interesting about [f]: every point of interest found on −10…10,
     * with room around them, and the curve's values in between.
     *
     * The vertical range comes from the middle 90% of the samples rather than their extremes, so a
     * pole does not squash the rest of the curve into a flat line.
     */
    fun fit(f: (Double) -> Double, around: GraphWindow = GraphWindow.DEFAULT): GraphWindow {
        val coarse = GraphWindow(around.xMin, around.xMax, -1e6, 1e6)
        val points = pointsOfInterest(f, coarse)
        val (xMin, xMax) = if (points.isEmpty()) {
            around.xMin to around.xMax
        } else {
            val lo = points.minOf { it.x }
            val hi = points.maxOf { it.x }
            val pad = max((hi - lo) * 0.5, 2.0)
            (lo - pad) to (hi + pad)
        }

        val values = (0..SAMPLES).map { f(xMin + (xMax - xMin) * it / SAMPLES) }.filter { it.isFinite() }.sorted()
        if (values.isEmpty()) return GraphWindow(xMin, xMax, -10.0, 10.0)
        var low = values[(values.size * 0.05).toInt().coerceAtMost(values.lastIndex)]
        var high = values[(values.size * 0.95).toInt().coerceAtMost(values.lastIndex)]
        points.filter { it.x in xMin..xMax }.forEach {
            low = minOf(low, it.y)
            high = maxOf(high, it.y)
        }
        // The x-axis is the reference every point is read against, so it stays in view whenever the
        // curve comes anywhere near it.
        val span = max(high - low, 1.0)
        if (low > 0.0 && low < span) low = 0.0
        if (high < 0.0 && -high < span) high = 0.0
        val pad = max(high - low, 1.0) * 0.15
        return GraphWindow(xMin, xMax, low - pad, high + pad)
    }

    /** About [count] round steps across [span]: 1, 2 or 5 times a power of ten. */
    fun niceStep(span: Double, count: Int): BigDecimal {
        if (!(span > 0.0) || !span.isFinite()) return BigDecimal.ONE
        val raw = span / count
        val exponent = floor(log10(raw)).toInt()
        val normalized = raw / 10.0.pow(exponent)
        val (mantissa, shift) = when {
            normalized < 1.5 -> 1L to 0
            normalized < 3.5 -> 2L to 0
            normalized < 7.5 -> 5L to 0
            else -> 1L to 1
        }
        return BigDecimal.valueOf(mantissa).scaleByPowerOfTen(exponent + shift)
    }

    /**
     * [value] on the nearest multiple of [step], exactly — a dragged point lands on `1.5`, not on
     * `1.4999999999999998`, so the function rewritten from it reads cleanly.
     */
    fun snap(value: Double, step: BigDecimal): BigDecimal =
        step.multiply(BigDecimal.valueOf((value / step.toDouble()).roundToLong())).stripTrailingZeros()

    private fun List<Double>.distinctNear(reach: Double): List<Double> {
        val sorted = sorted()
        val kept = mutableListOf<Double>()
        sorted.forEach { if (kept.isEmpty() || abs(it - kept.last()) > reach / 2) kept += it }
        return kept
    }

    /** The list, or null when it is too long to be worth drawing. */
    private fun <T> List<T>.capped(): List<T>? = takeIf { it.size <= MAX_PER_KIND }
}

/** The window a placed graph shows, or the default one if the stored numbers make no window. */
fun com.vivenotes.model.Outline.Graph.window(): GraphWindow =
    GraphWindow(xMin, xMax, yMin, yMax).takeIf { it.isUsable } ?: GraphWindow.DEFAULT
