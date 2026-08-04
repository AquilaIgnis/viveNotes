package com.vivenotes.model.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.serialization.Serializable

/**
 * One edge of a shape — `docs/inkPlan.md` §5.4.
 *
 * A shape is a **list of these**. They are how its geometry is stored and drawn, and in particular
 * they are what carries [hidden]: a solid's occluded edges have to be distinguishable from its
 * visible ones to be drawn dotted, and a single flattened path cannot say which is which.
 *
 * Segments are not separately editable — a shape is selected and moved whole — so nothing here
 * mutates one endpoint.
 *
 * [bulge] is the reason this stays two points rather than a polyline. It is the arc's height at its
 * midpoint, as a fraction of the chord, measured perpendicular to it: zero is a straight line, and a
 * non-zero value bows the segment into a circular arc. That keeps an ellipse four records instead of
 * three hundred sampled points, so a shape stays small enough to belong in the document.
 *
 * Coordinates are page units, absolute, like every other outline coordinate.
 */
@Serializable
data class ShapeSegment(
    val id: String,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    /** Arc height at the midpoint as a fraction of the chord. 0 is straight. */
    val bulge: Float = 0f,
    /** An edge the solid occludes, drawn dotted. Never true on a flat shape. */
    val hidden: Boolean = false,
) {
    val startX: Float get() = x1
    val startY: Float get() = y1
    val endX: Float get() = x2
    val endY: Float get() = y2

    val length: Float get() = hypot(x2 - x1, y2 - y1)

    fun translated(dx: Float, dy: Float): ShapeSegment =
        copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)

    /**
     * The segment as a polyline of interleaved x/y, ready to stroke.
     *
     * A straight segment is its two endpoints and nothing more — sampling it would be inventing
     * points. A bulged one is sampled by the same length-driven rule the rest of the geometry uses,
     * so a segment on a picker chip and the same segment filling the page both come out smooth
     * without either being a special case.
     */
    fun polyline(): FloatArray {
        if (bulge == 0f) return floatArrayOf(x1, y1, x2, y2)
        val chord = length
        if (chord == 0f) return floatArrayOf(x1, y1, x2, y2)

        // Circle through both endpoints whose midpoint height is bulge * chord.
        val sagitta = bulge * chord
        val radius = (chord * chord / 4f + sagitta * sagitta) / (2f * abs(sagitta))
        val midX = (x1 + x2) / 2f
        val midY = (y1 + y2) / 2f
        // Perpendicular to the chord, pointing the way a positive bulge bows: to the *left* of the
        // direction of travel. That is the choice that makes a clockwise ring of segments — which is
        // how every closed shape here is seeded — bulge outwards rather than into itself.
        val normalX = (y2 - y1) / chord
        val normalY = -(x2 - x1) / chord
        val centreOffset = radius - abs(sagitta)
        val sign = if (sagitta >= 0f) 1f else -1f
        val centreX = midX - normalX * centreOffset * sign
        val centreY = midY - normalY * centreOffset * sign

        val from = atan2(y1 - centreY, x1 - centreX)
        val to = atan2(y2 - centreY, x2 - centreX)
        // The centre was placed on the far side of the chord from the bulge, so the *shorter* way
        // round between the endpoints is already the arc this bulge describes. Forcing the sweep's
        // sign to match the bulge on top of that is what turns a quarter circle into three
        // quarters of one — the centre has said which side it is on, and saying it twice inverts it.
        var sweep = to - from
        while (sweep <= -PI_F) sweep += 2f * PI_F
        while (sweep > PI_F) sweep -= 2f * PI_F
        // Only a bulge past a half-circle genuinely wants the long way round.
        if (abs(sagitta) > chord / 2f) {
            sweep += if (sweep >= 0f) -2f * PI_F else 2f * PI_F
        }

        val samples = arcSampleCount(radius, abs(sweep))
        val points = FloatArray((samples + 1) * 2)
        for (index in 0..samples) {
            val angle = from + sweep * index / samples
            points[index * 2] = centreX + radius * cos(angle)
            points[index * 2 + 1] = centreY + radius * sin(angle)
        }
        return points
    }

    /** Shortest distance from a page point to this segment, for hit testing a thin line. */
    fun distanceTo(x: Float, y: Float): Float {
        val points = polyline()
        var best = Float.MAX_VALUE
        for (index in 0 until points.size - 2 step 2) {
            best = minOf(
                best,
                distanceToLine(x, y, points[index], points[index + 1], points[index + 2], points[index + 3]),
            )
        }
        return best
    }

    companion object {
        fun straight(id: String, x1: Float, y1: Float, x2: Float, y2: Float, hidden: Boolean = false) =
            ShapeSegment(id = id, x1 = x1, y1 = y1, x2 = x2, y2 = y2, hidden = hidden)

        /**
         * The bulge of a quarter circle. Four of these make a circle.
         *
         * `r(1 - cos 45°) / (r√2)`. **Not** CAD's `tan(θ/4)` = 0.414: that convention measures the
         * sagitta against the *half* chord, and [bulge] measures it against the whole one. Using the
         * CAD number here bows every arc exactly twice as far as it should, which turns a circle
         * into a four-petal flower — which is what it did.
         */
        const val QUARTER_ARC = 0.20710678f
    }
}

private const val PI_F = 3.1415927f

private fun arcSampleCount(radius: Float, sweep: Float): Int =
    ceil(abs(radius) * sweep / 3.5f).toInt().coerceIn(6, 180)

private fun distanceToLine(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Float {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}
