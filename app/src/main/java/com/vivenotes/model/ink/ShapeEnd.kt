package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import kotlin.math.hypot

/**
 * One end of a shape that *is* a line — `memory/inkPlan.md` §5.4 SD12.
 *
 * The line and the arrow are the two kinds with no inside and no box: what they are is a run from
 * one point to another, and every question you can ask of one — how long, which way, where it
 * points — is a question about those two points. The four corner handles Prime Object gives
 * everything else (AD7) answer none of them. A horizontal line has no height for two of those
 * corners to pull on, so half the handles do nothing at all; the other half stretch it along its own
 * axis, which is the one edit that *could* be phrased as an endpoint move — and cannot turn it.
 *
 * So a line-like kind offers **two handles, one per end, each dragged freely in both directions**.
 * Pulling one across the other's line turns the shape rather than resizing it, which is how a line
 * is aimed: there is nowhere else in the object model to put a rotation, and for a line there is no
 * need for one, because an endpoint already carries the angle.
 *
 * The sibling of [ShapeArm], and deliberately not the same thing. An arm end is pinned to its own
 * axis so that lengthening an L's foot cannot also drift it down; a line end is pinned to nothing,
 * because for a line that drift *is* the gesture. Both are absolute — they say where the end lands,
 * not how far it came — so both preview per frame and commit once on the lift.
 */
data class ShapeEnd(
    /** True for the shaft's second point. The tip of an arrow; either end of a plain line. */
    val atEnd: Boolean,
    val x: Float,
    val y: Float,
)

/**
 * The two ends this shape offers handles for, or none.
 *
 * Gated on [ShapeKind.hasEnds] rather than on the geometry, for the reason [arms] is gated on
 * [ShapeKind.hasArms]: loose ends alone do not make a handle. An arrow's head has two of its own,
 * and pulling on one of those would take the head off the shaft — so the ends offered are the
 * **shaft's**, which for both kinds is the first segment, and the head is carried along by the
 * re-trace in [withEnd] rather than being dragged itself.
 */
fun Outline.Shape.ends(): List<ShapeEnd> {
    if (!kind.hasEnds) return emptyList()
    val shaft = segments.firstOrNull() ?: return emptyList()
    return listOf(
        ShapeEnd(atEnd = false, x = shaft.x1, y = shaft.y1),
        ShapeEnd(atEnd = true, x = shaft.x2, y = shaft.y2),
    )
}

/**
 * The end within [reach] of ([x], [y]), nearest first — or null when the point is near neither.
 *
 * [reach] is passed in rather than known here because how far a finger may miss by is a fact about
 * fingers, not about geometry: `SelectionChrome.HANDLE_REACH` owns it, and both the layer that draws
 * a tapped line's handles and the lasso that draws a lassoed one's read it from there. Page units,
 * which dp already are.
 */
fun Outline.Shape.endNear(x: Float, y: Float, reach: Float): ShapeEnd? = ends()
    .minByOrNull { hypot(x - it.x, y - it.y) }
    ?.takeIf { hypot(x - it.x, y - it.y) <= reach }

/**
 * Moves one end of a line-like shape to ([x], [y]), leaving the other where it is.
 *
 * **The shape is re-traced from its two endpoints, not edited in place.** For a plain line the two
 * are the same thing, and for an arrow they are not: the head is geometry *derived* from the shaft —
 * its wings open back along the line, and its size follows the shaft's length up to a cap — so an
 * end moved without rebuilding it leaves the head pointing where the arrow used to go. Asking
 * [trace] the same question the insert asked is what keeps a dragged arrow identical to one drawn at
 * that size in the first place.
 *
 * Segment **ids survive**, because the edges come back in the order they were seeded in: the shaft
 * first, then the head. An id that is not re-traced keeps the geometry it had, which is what a kind
 * whose segments have been edited into something [trace] no longer describes gets — nothing rather
 * than a shape rebuilt out from under it.
 *
 * Clamped to [MIN_LINE_LENGTH] from the far end. A line dragged onto its own other end has no
 * direction left to be pulled back out along, and its two handles land on the same point with
 * nothing between them to grab — the same reason [MIN_ARM_LENGTH] exists, and the same fate if it
 * did not.
 */
fun Outline.Shape.withEnd(end: ShapeEnd, x: Float, y: Float): Outline.Shape {
    if (!kind.hasEnds) return this
    val shaft = segments.firstOrNull() ?: return this

    val fixedX = if (end.atEnd) shaft.x1 else shaft.x2
    val fixedY = if (end.atEnd) shaft.y1 else shaft.y2
    val heldX = if (end.atEnd) shaft.x2 else shaft.x1
    val heldY = if (end.atEnd) shaft.y2 else shaft.y1
    val (movedX, movedY) = heldOff(fixedX, fixedY, x, y, heldX, heldY)

    val tracing = trace(
        kind,
        startX = if (end.atEnd) fixedX else movedX,
        startY = if (end.atEnd) fixedY else movedY,
        endX = if (end.atEnd) movedX else fixedX,
        endY = if (end.atEnd) movedY else fixedY,
    )
    val edges = tracing.edges()

    return copy(
        segments = segments.mapIndexed { index, segment ->
            val edge = edges.getOrNull(index) ?: return@mapIndexed segment
            segment.copy(x1 = edge[0], y1 = edge[1], x2 = edge[2], y2 = edge[3])
        },
    ).withRecomputedBounds()
}

/**
 * The requested point, pushed back out to [MIN_LINE_LENGTH] from the end that is staying put.
 *
 * Along the direction the finger asked for, so a drag that crosses the far end comes out the other
 * side pointing the way it was pushed rather than snapping back to where the line already was. Only
 * a drag landing *exactly* on the fixed end has no direction of its own, and that one keeps the
 * line's current heading — anything else would spin it at random on the last pixel of travel.
 */
private fun heldOff(
    fixedX: Float,
    fixedY: Float,
    x: Float,
    y: Float,
    heldX: Float,
    heldY: Float,
): Pair<Float, Float> {
    val reach = hypot(x - fixedX, y - fixedY)
    if (reach >= MIN_LINE_LENGTH) return x to y

    val (dirX, dirY) = if (reach > 0f) {
        (x - fixedX) / reach to (y - fixedY) / reach
    } else {
        val heading = hypot(heldX - fixedX, heldY - fixedY)
        // A line with no length at all has no heading either; east is as good an answer as any, and
        // is the direction a fresh one is dragged out in.
        if (heading > 0f) (heldX - fixedX) / heading to (heldY - fixedY) / heading else 1f to 0f
    }
    return (fixedX + dirX * MIN_LINE_LENGTH) to (fixedY + dirY * MIN_LINE_LENGTH)
}

/** A tracing's polylines cut into the two-point edges the segments store, in seeding order. */
private fun ShapeTracing.edges(): List<FloatArray> = solid.flatMap { polyline ->
    (0 until polyline.size - 2 step 2).map { index ->
        floatArrayOf(
            polyline[index], polyline[index + 1],
            polyline[index + 2], polyline[index + 3],
        )
    }
}

/**
 * How short a line may be dragged, in page units.
 *
 * [MIN_ARM_LENGTH]'s argument applied to a whole shape rather than to one arm of one: an end pulled
 * onto the other leaves two handles stacked on a point, and the shape reads as having been deleted
 * rather than as having been made very short.
 */
const val MIN_LINE_LENGTH = 8f
