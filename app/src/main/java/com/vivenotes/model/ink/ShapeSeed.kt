package com.vivenotes.model.ink

/**
 * Turns a [ShapeKind] and the box it was dragged out into the segments that shape starts as —
 * `docs/inkPlan.md` §5.4.
 *
 * The seam between the two halves of the geometry. [trace] answers *what does this shape look like*,
 * which is what a picker chip and a live drag preview need; this answers *what segments is it made
 * of*, which is what the document stores and what the handles edit. Seeding runs once, at insert:
 * afterwards the segments are the shape and this is not consulted again, which is exactly what lets
 * a corner be dragged somewhere no box would have put it.
 *
 * Curved shapes are seeded as arc segments rather than as sampled polylines, so an ellipse arrives
 * with four handles instead of three hundred. See [ShapeSegment.bulge].
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
        ShapeKind.Arrow, ShapeKind.DoubleArrow -> {
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
        ShapeKind.Star -> closedChain(*starPoints(left, top, right, bottom))

        // Four quadrants, so a circle has four handles at its compass points — the same places a
        // vector editor puts them.
        ShapeKind.Ellipse -> quadrants(left, top, right, bottom, nextId)

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

        // Silhouette as four quadrants, plus the equator as two half-arcs — the near one solid and
        // the far one dotted, which is the whole of what makes it read as a sphere.
        ShapeKind.Sphere -> {
            val centreY = (top + bottom) / 2f
            val equatorHalf = (bottom - top) / 2f * 0.28f
            quadrants(left, top, right, bottom, nextId) + listOf(
                ShapeSegment(nextId(), right, centreY, left, centreY, bulge = -equatorBulge(right - left, equatorHalf)),
                ShapeSegment(
                    nextId(), right, centreY, left, centreY,
                    bulge = equatorBulge(right - left, equatorHalf),
                    hidden = true,
                ),
            )
        }
    }
}

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

/** The four compass-point quadrants of the ellipse inscribed in the box. */
private fun quadrants(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    nextId: () -> String,
): List<ShapeSegment> {
    val midX = (left + right) / 2f
    val midY = (top + bottom) / 2f
    return listOf(
        arc(nextId(), midX, top, right, midY),
        arc(nextId(), right, midY, midX, bottom),
        arc(nextId(), midX, bottom, left, midY),
        arc(nextId(), left, midY, midX, top),
    )
}

private fun arc(id: String, x1: Float, y1: Float, x2: Float, y2: Float): ShapeSegment =
    ShapeSegment(id = id, x1 = x1, y1 = y1, x2 = x2, y2 = y2, bulge = ShapeSegment.QUARTER_ARC)

/** Bulge that gives a half-ellipse of the requested height across a full-width chord. */
private fun equatorBulge(chord: Float, height: Float): Float =
    if (chord == 0f) 0f else height / chord
