package com.vivenotes.model.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * The two ends of a line the user meant to draw straight.
 *
 * Page units, like everything else the document stores. A plain record rather than
 * `com.vivenotes.ink.InkPoint` because that type lives beside `androidx.ink` and this file is
 * deliberately Android-free — the same rule the rest of `model/` keeps, and the reason this half of
 * hold-for-straight-line is JVM-tested rather than device-tested.
 */
data class StraightLine(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

/**
 * Decides whether a freehand trace was somebody drawing a straight line — `memory/inkPlan.md` §5.2,
 * scoped down to the one candidate the feature ships with.
 *
 * §5.2 describes a classifier over line, ellipse and polygon. This is only its `Line` branch, and
 * that is the whole feature by request: *hold for straight line*, not hold for shape. The rejected
 * kinds are not stubbed here — a classifier with two dead branches invites someone to fill them in
 * without re-reading the thresholds, and the interaction is different anyway (a snapped circle needs
 * a live resize before the pen lifts, which a line does not).
 *
 * **Refusal is the safe answer and the common one.** A wrong snap destroys a mark the user made; a
 * refusal costs them nothing but the second they spent holding still. Every threshold below is
 * therefore set in the direction of keeping the freehand stroke, which is also why there are three
 * independent tests rather than one score — each catches a different way of not being a line, and a
 * single blended residual lets a bad trace pass by being only slightly bad in three ways at once.
 */
object StraightLineFit {

    /**
     * Shorter than this and it is a tick, a dot or the start of something — page units.
     *
     * A stroke this small is also one where the hand's own wobble is a large fraction of the length,
     * so the tests below cannot tell a line from anything else at that scale. §5.2's number.
     */
    const val MIN_TRAVEL_DP = 24f

    /**
     * How far the trace may stray sideways from the straight line between its ends.
     *
     * Proportional to that line's length, with a floor: 3.5% of a 200 dp rule is 7 dp, which is
     * about what an unsupported hand produces at speed, while 3.5% of a 30 dp one is a third of a
     * millimetre and would refuse every line that short. The floor is what makes the short case
     * possible at all.
     *
     * Measured as the **maximum** perpendicular distance rather than the RMS §5.2 proposes. RMS
     * averages a single bad excursion away — an L drawn in one stroke is mostly straight, and its
     * one corner is exactly what must not be averaged out.
     */
    const val SIDEWAYS_FRACTION = 0.035f
    const val MIN_SIDEWAYS_DP = 3f

    /**
     * How far the trace may travel backwards along its own direction before it stops being one line.
     *
     * This is the test that catches doubling back, and it is separate from the sideways one because
     * a trace drawn right and then straight back left has a perpendicular error of nearly zero — it
     * fits the line perfectly and is not one. Also proportional with a floor, and for the same
     * reason: a fixed value is either useless on a long line or fatal on a short one.
     *
     * *Rejected:* comparing arc length to chord length, which is the obvious version of this test.
     * It cannot distinguish doubling back from digitiser noise: per-sample jitter inflates arc
     * length without moving the pen anywhere, so the threshold that tolerates a noisy device also
     * tolerates a trace that went back on itself.
     */
    const val BACKTRACK_FRACTION = 0.06f
    const val MIN_BACKTRACK_DP = 4f

    /**
     * How close to horizontal or vertical is close enough to be called horizontal or vertical.
     *
     * §5.2's 5°. Somebody ruling a margin or underlining a heading is drawing an axis, not a line at
     * 2°, and the point of the whole gesture is to get the thing they meant. Beyond this the angle
     * is left exactly as drawn: a deliberate diagonal that quietly became horizontal is the wrong
     * kind of help, and worse than no snap at all.
     */
    const val AXIS_SNAP_DEGREES = 5f

    /**
     * The straight line [points] is, or null to keep the freehand stroke.
     *
     * [points] is interleaved x/y in page units, oldest first — the same layout
     * [ShapeTrace] and [seedSegments] speak, so nothing between the pen and the document
     * has to convert between point representations.
     *
     * The ends of the result are the ends of the trace, not the ends of a best-fit axis through it.
     * Total least squares would place the line slightly better against a bowed trace, but a bowed
     * trace is one this refuses anyway; against a trace it accepts the two agree to within the
     * tolerance above, and *where the pen started and where it is now* is the answer the user can
     * predict. Only [AXIS_SNAP_DEGREES] moves an end, and it pivots about the start so the line
     * keeps the length it was drawn at.
     */
    fun of(points: FloatArray): StraightLine? {
        if (points.size < 4 || points.size % 2 != 0) return null

        val startX = points[0]
        val startY = points[1]
        val endX = points[points.size - 2]
        val endY = points[points.size - 1]

        val spanX = endX - startX
        val spanY = endY - startY
        val chord = hypot(spanX, spanY)
        if (chord < MIN_TRAVEL_DP) return null

        // The chord's unit vector, and the perpendicular to it. Every sample is measured in this
        // frame: `along` is progress towards the end, `across` is how far off the line it is.
        val alongX = spanX / chord
        val alongY = spanY / chord

        val sidewaysLimit = max(MIN_SIDEWAYS_DP, chord * SIDEWAYS_FRACTION)
        val backtrackLimit = max(MIN_BACKTRACK_DP, chord * BACKTRACK_FRACTION)

        var furthestAlong = 0f
        for (index in 0 until points.size step 2) {
            val dx = points[index] - startX
            val dy = points[index + 1] - startY

            val across = abs(dx * -alongY + dy * alongX)
            if (across > sidewaysLimit) return null

            val along = dx * alongX + dy * alongY
            // Three ways to fail, all of them "the pen did not go one way": it started behind the
            // beginning, it ran past the end and came back, or it turned round in the middle.
            if (along < -backtrackLimit) return null
            if (along > chord + backtrackLimit) return null
            if (along < furthestAlong - backtrackLimit) return null
            if (along > furthestAlong) furthestAlong = along
        }

        return snappedToAxis(startX, startY, endX, endY, chord)
    }

    /**
     * The same line, pulled onto the horizontal or the vertical when it is already nearly there.
     *
     * Rotated about the start rather than projected onto the axis, so a 200 dp line stays 200 dp
     * long instead of losing the fraction of itself that was off-axis. At 5° that is a 0.4%
     * difference in length — invisible on its own, but it is the difference between two lines drawn
     * the same way ending up the same length and not.
     */
    private fun snappedToAxis(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        chord: Float,
    ): StraightLine {
        val degrees = Math.toDegrees(atan2((endY - startY).toDouble(), (endX - startX).toDouble()))
        // Distance to the nearest multiple of 90°, in the range 0..45.
        val fromAxis = (((degrees % 90.0) + 90.0) % 90.0).let { minOf(it, 90.0 - it) }
        if (fromAxis > AXIS_SNAP_DEGREES) {
            return StraightLine(startX, startY, endX, endY)
        }
        val quadrant = Math.round(degrees / 90.0).toInt()
        return when (((quadrant % 4) + 4) % 4) {
            0 -> StraightLine(startX, startY, startX + chord, startY)
            1 -> StraightLine(startX, startY, startX, startY + chord)
            2 -> StraightLine(startX, startY, startX - chord, startY)
            else -> StraightLine(startX, startY, startX, startY - chord)
        }
    }
}
