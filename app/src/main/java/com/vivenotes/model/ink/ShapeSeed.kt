package com.vivenotes.model.ink

import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a [ShapeKind] and the box it was dragged out into the segments that shape starts as —
 * `memory/inkPlan.md` §5.4.
 *
 * The seam between the two halves of the geometry. [trace] answers *what does this shape look like*,
 * which is what a picker chip and a live drag preview need; this answers *what segments is it made
 * of*, which is what the document stores and what the handles edit. Seeding runs once, at insert:
 * afterwards the segments are the shape and this is not consulted again, which is exactly what lets
 * a corner be dragged somewhere no box would have put it.
 *
 * Curved shapes are seeded as arc segments rather than as sampled polylines, so an ellipse arrives
 * as sixteen records instead of three hundred — and, because an arc is resampled by size when it is
 * drawn, stays smooth at a zoom that would show a stored polyline's facets. See [ShapeSegment.bulge].
 */
fun seedSegments(
    kind: ShapeKind,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    nextId: () -> String,
): List<ShapeSegment> {
    val left = minOf(startX, endX)
    val top = minOf(startY, endY)
    val right = maxOf(startX, endX)
    val bottom = maxOf(startY, endY)

    fun straight(x1: Float, y1: Float, x2: Float, y2: Float, hidden: Boolean = false) =
        ShapeSegment.straight(nextId(), x1, y1, x2, y2, hidden)

    /** Consecutive points of a closed outline, as one segment each. */
    fun chain(vararg points: Float): List<ShapeSegment> =
        (0 until points.size - 2 step 2).map { index ->
            straight(points[index], points[index + 1], points[index + 2], points[index + 3])
        }

    fun closedChain(vararg points: Float): List<ShapeSegment> =
        chain(*points, points[0], points[1])

    return when (kind) {
        // The primitive every other shape is made of, and the one that needs no box at all: it runs
        // from where the drag began to where it ended, which is why `trace` takes a drag.
        ShapeKind.Line -> listOf(straight(startX, startY, endX, endY))

        // The head is two more segments meeting at the tip, so its wings get handles of their own
        // and an arrowhead can be widened without redrawing the arrow.
        ShapeKind.Arrow -> {
            val tracing = trace(kind, startX, startY, endX, endY)
            tracing.solid.flatMap { polyline ->
                (0 until polyline.size - 2 step 2).map { index ->
                    straight(
                        polyline[index], polyline[index + 1],
                        polyline[index + 2], polyline[index + 3],
                    )
                }
            }
        }

        ShapeKind.Rectangle -> closedChain(left, top, right, top, right, bottom, left, bottom)
        ShapeKind.Triangle -> closedChain((left + right) / 2f, top, right, bottom, left, bottom)
        ShapeKind.RightTriangle -> closedChain(left, top, right, bottom, left, bottom)
        ShapeKind.Diamond -> closedChain(
            (left + right) / 2f, top,
            right, (top + bottom) / 2f,
            (left + right) / 2f, bottom,
            left, (top + bottom) / 2f,
        )
        ShapeKind.Pentagon -> closedChain(*regularPoints(left, top, right, bottom, 5, -HALF_PI))
        ShapeKind.Hexagon -> closedChain(*regularPoints(left, top, right, bottom, 6, 0f))

        // One segment per arm, which is the whole point of the kind: a segment is what an arm handle
        // grabs, so the vertical and the horizontal have to be two records rather than one polyline.
        // They meet at the bottom-left corner, so that corner is the only endpoint either of them
        // shares — which is how `arms()` finds the two free ends without being told about L.
        ShapeKind.L -> chain(left, top, left, bottom, right, bottom)

        ShapeKind.Ellipse -> ellipseRing(left, top, right, bottom, nextId)

        // A rounded rectangle is four straight sides and four quarter-arc corners, each editable.
        ShapeKind.RoundedRectangle -> {
            val radius = minOf(right - left, bottom - top) * 0.18f
            listOf(
                straight(left + radius, top, right - radius, top),
                arc(nextId(), right - radius, top, right, top + radius),
                straight(right, top + radius, right, bottom - radius),
                arc(nextId(), right, bottom - radius, right - radius, bottom),
                straight(right - radius, bottom, left + radius, bottom),
                arc(nextId(), left + radius, bottom, left, bottom - radius),
                straight(left, bottom - radius, left, top + radius),
                arc(nextId(), left, top + radius, left + radius, top),
            )
        }

        // The solids seed straight from their tracing: every edge there is already a straight line
        // between two corners, which is exactly one segment, and the occluded ones carry the flag
        // that makes them dotted.
        ShapeKind.Cube, ShapeKind.Pyramid, ShapeKind.Wedge -> {
            val tracing = trace(kind, startX, startY, endX, endY)
            tracing.solid.flatMap { edgeSegments(it, hidden = false, nextId) } +
                tracing.hidden.flatMap { edgeSegments(it, hidden = true, nextId) }
        }

        // Silhouette plus the equator, which is what makes it read as a sphere rather than a circle.
        // The near half bows *towards* the viewer — downwards, since the viewer is above — and is
        // solid; the far half bows up behind the body and is dotted. Those two were the wrong way
        // round until 2026-08-05, so every sphere was drawn as if seen from below while its shading
        // said otherwise.
        ShapeKind.Sphere -> {
            val centreY = (top + bottom) / 2f
            val equatorDepth = (bottom - top) / 2f * EQUATOR_FLATTEN
            ellipseRing(left, top, right, bottom, nextId) +
                equator(left, right, centreY, equatorDepth, nextId)
        }

        // Apex to the base's widest points, then the base ellipse split near/far like the sphere's
        // equator. The base sits at the bottom of the box and the sides meet it at its ends, which
        // is where an ellipse is tangent to them.
        ShapeKind.Cone -> {
            val centreX = (left + right) / 2f
            val radiusX = (right - left) / 2f
            val baseDepth = baseDepth(radiusX, bottom - top)
            val centreY = bottom - baseDepth
            listOf(
                straight(centreX, top, left, centreY),
                straight(centreX, top, right, centreY),
            ) + equator(left, right, centreY, baseDepth, nextId)
        }

        // A whole top rim, two sides, and a bottom rim that is half occluded — the asymmetry is the
        // only thing distinguishing a cylinder from a rectangle with lens ends.
        ShapeKind.Cylinder -> {
            val radiusX = (right - left) / 2f
            val capDepth = baseDepth(radiusX, bottom - top)
            val topY = top + capDepth
            val bottomY = bottom - capDepth
            ellipseRing(left, topY - capDepth, right, topY + capDepth, nextId) + listOf(
                straight(left, topY, left, bottomY),
                straight(right, topY, right, bottomY),
            ) + equator(left, right, bottomY, capDepth, nextId)
        }
    }
}

/**
 * The two halves of a rim seen edge-on: near solid, far dotted.
 *
 * A sphere's equator, a cone's base and a cylinder's foot are all the same figure — a flattened
 * ellipse the body cuts in half — so they are all this. Each half is [ELLIPSE_ARCS] / 2 arcs cut
 * from the ellipse, for the reason [ellipseRing] gives: a single arc across a rim this flat stands
 * well outside it at the ends.
 */
private fun equator(
    left: Float,
    right: Float,
    centreY: Float,
    depth: Float,
    nextId: () -> String,
): List<ShapeSegment> {
    val centreX = (left + right) / 2f
    val radiusX = (right - left) / 2f
    return ellipticalArc(centreX, centreY, radiusX, depth, 0f, PI_F, ELLIPSE_ARCS / 2, nextId) +
        ellipticalArc(
            centreX, centreY, radiusX, depth, PI_F, 2f * PI_F, ELLIPSE_ARCS / 2, nextId,
            hidden = true,
        )
}

/** Cap depth, matching [trace]'s so the committed shape is the one the preview showed. */
private fun baseDepth(radiusX: Float, height: Float): Float =
    (radiusX * EQUATOR_FLATTEN).coerceAtMost(height * MAX_CAP_FRACTION)

private fun edgeSegments(
    polyline: FloatArray,
    hidden: Boolean,
    nextId: () -> String,
): List<ShapeSegment> = (0 until polyline.size - 2 step 2).map { index ->
    ShapeSegment.straight(
        nextId(),
        polyline[index], polyline[index + 1],
        polyline[index + 2], polyline[index + 3],
        hidden,
    )
}

/**
 * The ellipse inscribed in the box, as a ring of arcs.
 *
 * **Not four quarter circles.** That is what this was, and it is only an ellipse when the box is
 * square: a quarter circle's bulge stretched across a wider quadrant stands well outside the curve
 * it is meant to trace — 29% out on a 3:1 box — and since each quadrant leans a different way, the
 * four of them meet at visible corners and the shape reads as four arcs pinned together rather than
 * as an ellipse. Which is what a wide ellipse looked like, drawn or dragged.
 *
 * So the arcs are cut from the *ellipse*: [ELLIPSE_ARCS] of them at equal parameter, each given the
 * bulge that carries it over the ellipse's own crown. A circular arc still cannot be an elliptical
 * one, but over a short enough span the difference stops being visible — and the joints stop being
 * corners, which is the part the eye actually catches.
 *
 * Starting at the top and going clockwise, as the four used to, so a fill traverses the ring the
 * same way and nothing downstream can tell the difference except by counting.
 */
private fun ellipseRing(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    nextId: () -> String,
): List<ShapeSegment> = ellipticalArc(
    centreX = (left + right) / 2f,
    centreY = (top + bottom) / 2f,
    radiusX = (right - left) / 2f,
    radiusY = (bottom - top) / 2f,
    // From the top, clockwise, as the four quadrants this replaced ran — so a fill traverses the
    // ring the same way and nothing downstream can tell the difference except by counting.
    from = -PI_F / 2f,
    to = 3f * PI_F / 2f,
    count = ELLIPSE_ARCS,
    nextId = nextId,
)

/**
 * An arc of the ellipse, cut into [count] segments.
 *
 * The one place a curve becomes segments, so a rim drawn by [trace] and the same rim stored in the
 * document are cut from the same parametric ellipse and cannot drift apart.
 */
private fun ellipticalArc(
    centreX: Float,
    centreY: Float,
    radiusX: Float,
    radiusY: Float,
    from: Float,
    to: Float,
    count: Int,
    nextId: () -> String,
    hidden: Boolean = false,
): List<ShapeSegment> {
    fun at(step: Float): Pair<Float, Float> {
        val angle = from + (to - from) * step / count
        return (centreX + radiusX * cos(angle)) to (centreY + radiusY * sin(angle))
    }

    return (0 until count).map { index ->
        val (x1, y1) = at(index.toFloat())
        val (x2, y2) = at(index + 1f)
        val (crownX, crownY) = at(index + 0.5f)
        ShapeSegment.through(nextId(), x1, y1, x2, y2, crownX, crownY, hidden)
    }
}

/**
 * A true quarter circle. Only for a corner that is square — a rounded rectangle's — where it is
 * exact; an ellipse's own arcs go through [ellipseRing].
 */
private fun arc(id: String, x1: Float, y1: Float, x2: Float, y2: Float): ShapeSegment =
    ShapeSegment(id = id, x1 = x1, y1 = y1, x2 = x2, y2 = y2, bulge = ShapeSegment.QUARTER_ARC)

/**
 * How many arcs an ellipse is cut into.
 *
 * The trade the whole representation rests on, and the number that decides whether a wide ellipse
 * looks like a curve or like a stack of arcs. Sixteen holds the joints under a degree across every
 * aspect ratio a page allows, drawn or dragged, and still leaves an ellipse smaller than a single
 * sampled polyline of it by an order of magnitude — which was the reason for arcs in the first
 * place. Segments are not individually editable, so more of them costs nothing to use.
 */
private const val ELLIPSE_ARCS = 16

private const val PI_F = 3.1415927f
