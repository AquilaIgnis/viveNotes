package com.vivenotes.model.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
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
     * The point at the top of the arc, where [bulge] measures its height.
     *
     * `mid + bulge * (dy, -dx)`: the perpendicular to the chord is `(dy, -dx) / chord` and the
     * height along it is `bulge * chord`, so the chord cancels — which is why this needs no special
     * case for a zero-length segment. A straight segment's apex is its own midpoint.
     */
    fun apex(): Pair<Float, Float> = ((x1 + x2) / 2f + bulge * (y2 - y1)) to
        ((y1 + y2) / 2f - bulge * (x2 - x1))

    /**
     * The segment scaled about ([anchorX], [anchorY]) — one corner of a corner-handle drag, AD7.
     *
     * The endpoints scale, and the **bulge is re-derived** from where the arc's crown lands. Carrying
     * [bulge] across unchanged instead looks right, because it is a fraction of the chord and so
     * survives a *uniform* scale untouched — but a corner drag is rarely uniform, and under an
     * unequal one the chord turns while the arc's height does not follow it. An ellipse's four
     * quadrants then each keep a quarter circle's curvature against a chord that is no longer a
     * quarter circle's: they bow past where the outline should be, cross their neighbours, and the
     * shape comes apart into four separate arcs that read as a figure of eight. Which is what it did.
     *
     * [scaledCrown] is where the replacement height comes from, and why it is not simply the scaled
     * apex. Mirroring falls out of it too, and used to be a sign flip applied when the two scales
     * disagreed: a negative scale carries the crown across its chord, and the signed height comes
     * back negative on its own — including for the 180° turn that rule had backwards.
     */
    fun scaledAbout(anchorX: Float, anchorY: Float, scaleX: Float, scaleY: Float): ShapeSegment {
        val sx1 = anchorX + (x1 - anchorX) * scaleX
        val sy1 = anchorY + (y1 - anchorY) * scaleY
        val sx2 = anchorX + (x2 - anchorX) * scaleX
        val sy2 = anchorY + (y2 - anchorY) * scaleY
        val crown = scaledCrown(anchorX, anchorY, scaleX, scaleY)
            ?: return copy(x1 = sx1, y1 = sy1, x2 = sx2, y2 = sy2)

        return copy(
            x1 = sx1,
            y1 = sy1,
            x2 = sx2,
            y2 = sy2,
            bulge = bulgeThrough(
                x1 = sx1, y1 = sy1, x2 = sx2, y2 = sy2,
                apexX = crown.first, apexY = crown.second,
            ),
        )
    }

    /**
     * The segment as a polyline of interleaved x/y, ready to stroke.
     *
     * A straight segment is its two endpoints and nothing more — sampling it would be inventing
     * points. A bulged one is sampled by the same length-driven rule the rest of the geometry uses,
     * so a segment on a picker chip and the same segment filling the page both come out smooth
     * without either being a special case.
     */
    fun polyline(): FloatArray {
        val circle = arcCircle() ?: return floatArrayOf(x1, y1, x2, y2)
        val centreX = circle.centreX
        val centreY = circle.centreY
        val radius = circle.radius

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
        if (abs(bulge) > 0.5f) {
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

    /**
     * The circle this segment is an arc of, or null when it is straight or has no length.
     *
     * The centre sits on the far side of the chord from the bulge, which is what lets the sweep in
     * [polyline] be read straight off the endpoints.
     */
    private fun arcCircle(): ArcCircle? {
        if (bulge == 0f) return null
        val chord = length
        if (chord == 0f) return null

        // Circle through both endpoints whose midpoint height is bulge * chord.
        val sagitta = bulge * chord
        val radius = (chord * chord / 4f + sagitta * sagitta) / (2f * abs(sagitta))
        // Perpendicular to the chord, pointing the way a positive bulge bows: to the *left* of the
        // direction of travel. That is the choice that makes a clockwise ring of segments — which is
        // how every closed shape here is seeded — bulge outwards rather than into itself.
        val normalX = (y2 - y1) / chord
        val normalY = -(x2 - x1) / chord
        val offset = (radius - abs(sagitta)) * if (sagitta >= 0f) 1f else -1f
        return ArcCircle(
            centreX = (x1 + x2) / 2f - normalX * offset,
            centreY = (y1 + y2) / 2f - normalY * offset,
            radius = radius,
        )
    }

    /**
     * The point of the scaled arc that its new bulge has to reach — the crown of the ellipse the
     * scale turns this circular arc into, measured over the middle of the new chord.
     *
     * A bulge can only describe an arc that is symmetric about its chord, so the best it can do
     * against an elliptical arc is meet it at three points: the two endpoints, which the scale
     * already places exactly, and one in between. Taking the *scaled apex* for that third point is
     * the obvious choice and the wrong one, because the scale moves the apex off the middle of the
     * chord — its height then gets rebuilt at the middle, where the ellipse is lower, and the arc
     * stands proud of it by a few percent that grows with the stretch.
     *
     * So this asks the question the other way round: which point of the *original* arc lands on the
     * new chord's perpendicular bisector? Pulling that bisector back through the scale leaves a line
     * through the original chord's midpoint — leaning by the ratio of the two scales, and reducing
     * to the original bisector when they agree — and a line meets a circle in closed form. The
     * result meets the ellipse exactly at the crown, so a stretched circle keeps the bounds it was
     * dragged to.
     */
    private fun scaledCrown(
        anchorX: Float,
        anchorY: Float,
        scaleX: Float,
        scaleY: Float,
    ): Pair<Float, Float>? {
        val circle = arcCircle() ?: return null
        val dx = x2 - x1
        val dy = y2 - y1
        val midX = (x1 + x2) / 2f
        val midY = (y1 + y2) / 2f

        // The pulled-back bisector, as a direction through the chord's midpoint.
        val alongX = -scaleY * scaleY * dy
        val alongY = scaleX * scaleX * dx
        val a = alongX * alongX + alongY * alongY
        if (a == 0f) return null
        val fromCentreX = midX - circle.centreX
        val fromCentreY = midY - circle.centreY
        val b = 2f * (fromCentreX * alongX + fromCentreY * alongY)
        val c = fromCentreX * fromCentreX + fromCentreY * fromCentreY - circle.radius * circle.radius
        val discriminant = b * b - 4f * a * c
        if (discriminant < 0f) return null

        // The midpoint of a chord is inside its circle, so the line always crosses it twice — the
        // roots have opposite signs — and the two crossings are on opposite sides of the chord. The
        // one to take is the side the bulge bows to; the other is where the arc would be turned
        // inside out. `along` was built as (-dy, dx) weighted by the scales, which puts it on the
        // *negative* side of the chord whatever the scales are, so a positive bulge wants the
        // negative root.
        val root = sqrt(discriminant)
        val t = (-b + if (bulge >= 0f) -root else root) / (2f * a)
        return (anchorX + (midX + t * alongX - anchorX) * scaleX) to
            (anchorY + (midY + t * alongY - anchorY) * scaleY)
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
         * The arc from one point to another that passes over a third — how a curve that is not a
         * circle is laid onto segments that are.
         *
         * The third point is the crown: where the arc should stand over the middle of its chord.
         * Seeding an ellipse asks for it once per arc, and a resize asks for it again from wherever
         * the scale has put the curve.
         */
        fun through(
            id: String,
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            viaX: Float,
            viaY: Float,
            hidden: Boolean = false,
        ) = ShapeSegment(
            id = id,
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            bulge = bulgeThrough(x1, y1, x2, y2, viaX, viaY),
            hidden = hidden,
        )

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

/**
 * A run of segments that is drawn as **one** stroke: joined end to start, and all visible or all
 * occluded.
 *
 * Exists because a dash pattern restarts at the beginning of every path it is applied to. Stroking
 * an ellipse's sixteen arcs one at a time therefore lays a dot at each of the sixteen joints — one
 * ending the pattern, one beginning it again a hair away — and a dotted or dashed border comes out
 * with its dots doubled up at every seam. It went unnoticed while a rim was a single segment and
 * became obvious the moment rims were cut into arcs, which is the sort of thing that is invisible
 * until the geometry underneath changes shape.
 *
 * Grouping first also gives the joins to the stroker instead of leaving them as two round caps
 * meeting, so a thick border corners properly.
 */
data class ShapeContour(val hidden: Boolean, val segments: List<ShapeSegment>) {

    /** True when the run returns to where it started, so the stroker can close it rather than cap it. */
    val isClosed: Boolean
        get() = segments.size > 1 &&
            segments.first().let { first ->
                segments.last().joins(first.x1, first.y1)
            }

    /** The whole run as one polyline, without the duplicated point at each joint. */
    fun polyline(): FloatArray {
        val points = ArrayList<Float>(segments.size * 8)
        segments.forEachIndexed { index, segment ->
            val own = segment.polyline()
            val from = if (index == 0) 0 else 2
            for (i in from until own.size) points.add(own[i])
        }
        return points.toFloatArray()
    }
}

/** True when this segment ends where ([x], [y]) is, to within a fraction of a page unit. */
private fun ShapeSegment.joins(x: Float, y: Float): Boolean =
    abs(x2 - x) <= JOIN_TOLERANCE && abs(y2 - y) <= JOIN_TOLERANCE

/**
 * The segments grouped into the runs they should be stroked as — see [ShapeContour].
 *
 * Order is preserved and nothing is dropped, so a shape whose segments do not join at all comes back
 * as one contour each and draws exactly as it did before.
 */
fun List<ShapeSegment>.contours(): List<ShapeContour> {
    val contours = mutableListOf<ShapeContour>()
    var run = mutableListOf<ShapeSegment>()

    forEach { segment ->
        val previous = run.lastOrNull()
        val continues = previous != null &&
            previous.hidden == segment.hidden &&
            previous.joins(segment.x1, segment.y1)
        if (!continues && previous != null) {
            contours += ShapeContour(previous.hidden, run)
            run = mutableListOf()
        }
        run.add(segment)
    }
    run.lastOrNull()?.let { contours += ShapeContour(it.hidden, run) }
    return contours
}

/** Page units, so well under a pixel at any zoom — segments either meet or they do not. */
private const val JOIN_TOLERANCE = 0.01f

/** The circle a bulged segment is an arc of. */
private class ArcCircle(val centreX: Float, val centreY: Float, val radius: Float)

/**
 * [ShapeSegment.apex] inverted: the bulge of the arc between two points that passes over a third.
 *
 * The signed height of the apex above the chord, over the chord's own length — the two divisions
 * collapse into one by the squared length, which is also the only quantity that can be zero.
 */
private fun bulgeThrough(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    apexX: Float,
    apexY: Float,
): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    val chordSquared = dx * dx + dy * dy
    if (chordSquared == 0f) return 0f
    val offsetX = apexX - (x1 + x2) / 2f
    val offsetY = apexY - (y1 + y2) / 2f
    return (offsetX * dy - offsetY * dx) / chordSquared
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
