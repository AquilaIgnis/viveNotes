package com.vivenotes.graph

import com.vivenotes.model.GraphInterval
import kotlin.math.abs
import kotlin.math.max

/**
 * The integral over an interval: [net] counts area below the axis as negative — displacement on a
 * velocity graph — and [total] counts all of it — the distance travelled. [crosses] says whether the
 * curve changes sign inside, which is the only time the two differ and both are worth showing.
 */
data class AreaValue(val net: Double, val total: Double, val crosses: Boolean)

object GraphArea {

    /**
     * ∫ f from [from] to [to], or null where the integral does not exist as a number: the function
     * is undefined somewhere inside, or runs off to a pole.
     *
     * The interval is cut at every zero first, so each piece has one sign: [AreaValue.total] is then
     * the sum of their sizes, with no kink left for the integrator to blur. Each piece is integrated
     * by adaptive Simpson's rule.
     */
    fun integrate(f: (Double) -> Double, from: Double, to: Double): AreaValue? {
        if (!from.isFinite() || !to.isFinite()) return null
        if (from == to) return AreaValue(0.0, 0.0, false)
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val step = (hi - lo) / SAMPLES
        val samples = DoubleArray(SAMPLES + 1) { f(lo + step * it) }
        if (samples.any { !it.isFinite() }) return null
        if (hasPole(f, lo, step, samples)) return null

        val cuts = mutableListOf(lo)
        for (i in 0 until SAMPLES) {
            val a = samples[i]
            val b = samples[i + 1]
            if (a * b >= 0.0) continue
            val zero = zeroBetween(f, lo + step * i, lo + step * (i + 1), a)
            // A sign change through a pole — tan, 1/x — walks the bisection into the pole, where
            // |f| grows past both ends instead of shrinking: no area there.
            if (!(abs(f(zero)) <= max(abs(a), abs(b)))) return null
            cuts += zero
        }
        cuts += hi

        var net = 0.0
        var total = 0.0
        for (i in 0 until cuts.lastIndex) {
            val piece = simpson(f, cuts[i], cuts[i + 1]) ?: return null
            net += piece
            total += abs(piece)
        }
        val sign = if (from <= to) 1.0 else -1.0
        return AreaValue(net * sign, total, cuts.size > 2)
    }

    /**
     * Where an area starts when it is turned on: between the two zeros nearest the middle of the
     * window — the region a student is usually asked about — or the middle third when there are not
     * two zeros to use.
     */
    fun start(f: (Double) -> Double, window: GraphWindow): GraphInterval {
        val middle = (window.xMin + window.xMax) / 2
        val zeros = GraphAnalysis.pointsOfInterest(f, window)
            .filter { it.kind == PointKind.Zero }
            .map { it.x }
            .sorted()
        var best: GraphInterval? = null
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until zeros.lastIndex) {
            val distance = abs((zeros[i] + zeros[i + 1]) / 2 - middle)
            // `<=`, so of two pairs equally near the middle the right-hand one wins: from a zero at
            // the origin, sin's arch above the axis rather than the one below it.
            if (distance <= bestDistance) {
                bestDistance = distance
                best = GraphInterval(zeros[i], zeros[i + 1])
            }
        }
        best?.let { return it }
        val step = GraphAnalysis.niceStep(window.width, 40)
        return GraphInterval(
            GraphAnalysis.snap(middle - window.width / 6, step).toDouble(),
            GraphAnalysis.snap(middle + window.width / 6, step).toDouble(),
        )
    }

    /** `∫ y dx = 4.5`, in the graph's own names: `∫ v dt` for a velocity. */
    fun netText(value: AreaValue, variable: String, dependent: String): String =
        "∫ $dependent d$variable = ${GraphTangent.number(value.net)}"

    /** `∫ |y| dx = 6.2` — the area counting both sides of the axis. */
    fun totalText(value: AreaValue, variable: String, dependent: String): String =
        "∫ |$dependent| d$variable = ${GraphTangent.number(value.total)}"

    /**
     * Whether a spike among [samples] is a pole rather than a peak: closing in on it, a peak's value
     * levels off while a pole's runs away. Each spike — a local maximum of |f| well above the
     * stretch's middle value — is narrowed by ternary search between its neighbours.
     *
     * A logarithm's slow climb, ln|x| at 0, levels off by this measure and is integrated; the area it
     * gets is approximate there, as any numerical integrator's is.
     */
    private fun hasPole(f: (Double) -> Double, lo: Double, step: Double, samples: DoubleArray): Boolean {
        val sizes = samples.map { abs(it) }
        val typical = max(sizes.sorted()[SAMPLES / 2], 1e-12)
        for (i in 1 until SAMPLES) {
            val size = sizes[i]
            if (size < SPIKE_RATIO * typical || size < sizes[i - 1] || size < sizes[i + 1]) continue
            var a = lo + step * (i - 1)
            var b = lo + step * (i + 1)
            repeat(80) {
                val third = (b - a) / 3
                val left = abs(f(a + third))
                val right = abs(f(b - third))
                if (!left.isFinite() || !right.isFinite()) return true
                if (left < right) a += third else b -= third
            }
            val peak = abs(f((a + b) / 2))
            if (!peak.isFinite() || peak > POLE_RATIO * size) return true
        }
        return false
    }

    private fun zeroBetween(f: (Double) -> Double, lo: Double, hi: Double, fLo: Double): Double {
        var a = lo
        var b = hi
        var fa = fLo
        repeat(60) {
            val m = (a + b) / 2
            val fm = f(m)
            if (fm == 0.0 || !fm.isFinite()) return m
            if ((fm < 0.0) == (fa < 0.0)) {
                a = m
                fa = fm
            } else {
                b = m
            }
        }
        return (a + b) / 2
    }

    private fun simpson(f: (Double) -> Double, a: Double, b: Double): Double? {
        val fa = f(a)
        val fb = f(b)
        val m = (a + b) / 2
        val fm = f(m)
        val whole = (b - a) / 6 * (fa + 4 * fm + fb)
        val tolerance = max(1e-12, abs(whole) * 1e-10)
        return refine(f, a, b, fa, fm, fb, whole, tolerance, MAX_DEPTH)?.takeIf { it.isFinite() }
    }

    private fun refine(
        f: (Double) -> Double,
        a: Double,
        b: Double,
        fa: Double,
        fm: Double,
        fb: Double,
        whole: Double,
        tolerance: Double,
        depth: Int,
    ): Double? {
        val m = (a + b) / 2
        val lm = (a + m) / 2
        val rm = (m + b) / 2
        val flm = f(lm)
        val frm = f(rm)
        if (!flm.isFinite() || !frm.isFinite()) return null
        val left = (m - a) / 6 * (fa + 4 * flm + fm)
        val right = (b - m) / 6 * (fm + 4 * frm + fb)
        val delta = left + right - whole
        if (depth <= 0 || abs(delta) <= 15 * tolerance) return left + right + delta / 15
        val l = refine(f, a, m, fa, flm, fm, left, tolerance / 2, depth - 1) ?: return null
        val r = refine(f, m, b, fm, frm, fb, right, tolerance / 2, depth - 1) ?: return null
        return l + r
    }

    private const val SAMPLES = 200
    private const val MAX_DEPTH = 18

    /** How far above the stretch's middle value a sample has to be to be looked at as a spike. */
    private const val SPIKE_RATIO = 20.0

    /** How much higher a spike has to climb, closed in on, to be called a pole. */
    private const val POLE_RATIO = 100.0
}
