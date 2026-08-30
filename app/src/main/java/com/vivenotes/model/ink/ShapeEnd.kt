package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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
 *
 * And **aimed**: a drag landing within [END_SNAP_TOLERANCE] of an eighth-turn is taken to mean that
 * eighth-turn exactly — see [aimedFrom]. Nothing else in the app can tell you a line is level, so a
 * line that is meant to be level has to become level on its own.
 */
fun Outline.Shape.withEnd(end: ShapeEnd, x: Float, y: Float): Outline.Shape {
    if (!kind.hasEnds) return this
    val shaft = segments.firstOrNull() ?: return this

    val fixedX = if (end.atEnd) shaft.x1 else shaft.x2
    val fixedY = if (end.atEnd) shaft.y1 else shaft.y2
    val (movedX, movedY) = aimEnd(end, x, y)

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
 * Where [end] lands for a finger at ([x], [y]) — the snap and the minimum length applied, and nothing
 * else. The point [withEnd] will use.
 *
 * Public so that a caller who has to hold the result to something *else* can ask before committing:
 * the page's origin corner is one such wall, and a snap turns the line after any clamp on the finger
 * has already been applied — see `withEndOnPage`. Answering "where would this go" separately from
 * "put it there" is what keeps the preview and the commit on one number.
 */
fun Outline.Shape.aimEnd(end: ShapeEnd, x: Float, y: Float): Pair<Float, Float> {
    val shaft = segments.firstOrNull() ?: return x to y
    return aimedFrom(
        fixedX = if (end.atEnd) shaft.x1 else shaft.x2,
        fixedY = if (end.atEnd) shaft.y1 else shaft.y2,
        x = x,
        y = y,
        heldX = if (end.atEnd) shaft.x2 else shaft.x1,
        heldY = if (end.atEnd) shaft.y2 else shaft.y1,
    )
}

/**
 * Where the end actually lands for a finger at ([x], [y]): the requested point with the angle snapped
 * and the length held off [MIN_LINE_LENGTH], both measured from the end that is staying put.
 *
 * **The angle snaps to an eighth-turn and the length never does.** A drag within
 * [END_SNAP_TOLERANCE] of level, upright or 45° is read as meaning that exactly, and the length stays
 * whatever the finger reached — so the gesture is still "put this end here", with only the one
 * quantity a hand cannot hit by eye taken out of its hands. Snapping the length as well would be a
 * grid, which is a different feature and one nothing here asked for.
 *
 * Set against the alternative of *showing* the angle and leaving the aim to the user: a readout tells
 * you that you are at 89.4°, and then you still cannot get to 90 — the pixel you would need is
 * smaller than the finger asking for it. The snap is what makes level reachable at all.
 *
 * The length is held off along the **aimed** direction, so the two rules compose: a drag pushed
 * through the far end comes back out along the eighth-turn it was nearest, not along the raw one.
 * Only a drag landing *exactly* on the fixed end has no direction of its own, and that one keeps the
 * line's current heading — anything else would spin it at random on the last pixel of travel.
 *
 * A snapped end is placed from [eighthTurn]'s exact unit vectors rather than from `cos`/`sin`, so
 * level really is level: see there.
 */
private fun aimedFrom(
    fixedX: Float,
    fixedY: Float,
    x: Float,
    y: Float,
    heldX: Float,
    heldY: Float,
): Pair<Float, Float> {
    val reach = hypot(x - fixedX, y - fixedY)
    val direction = if (reach > 0f) {
        atan2(y - fixedY, x - fixedX)
    } else {
        val heading = hypot(heldX - fixedX, heldY - fixedY)
        // A line with no length at all has no heading either; east is as good an answer as any, and
        // is the direction a fresh one is dragged out in.
        if (heading > 0f) atan2(heldY - fixedY, heldX - fixedX) else 0f
    }
    val length = reach.coerceAtLeast(MIN_LINE_LENGTH)

    val eighth = (direction / SNAP_STEP).roundToInt()
    if (abs(direction - eighth * SNAP_STEP) <= SNAP_REACH) {
        val (unitX, unitY) = eighthTurn(eighth)
        return (fixedX + length * unitX) to (fixedY + length * unitY)
    }
    // Neither rule has anything to say, so the end goes exactly where it was asked to — no round
    // trip through polar coordinates to blunt it.
    if (length == reach) return x to y
    return (fixedX + length * cos(direction)) to (fixedY + length * sin(direction))
}

/**
 * The unit vector of one eighth-turn, by index — clockwise from east, as page coordinates run.
 *
 * **Written out rather than taken from `cos` and `sin` of the angle**, which answer -4.4e-8 for a
 * quarter turn rather than 0. The whole point of the snap is that a line laid flat *is* flat: its two
 * ends share a y exactly, its stored height is exactly zero, and a 45° line's two legs are exactly
 * equal rather than equal to seven decimal places. A residue that small is invisible on screen and
 * still spoils every exact answer downstream — the bounds, an equality test, an exporter's idea of
 * whether this line is horizontal.
 */
private fun eighthTurn(index: Int): Pair<Float, Float> = when (((index % 8) + 8) % 8) {
    0 -> 1f to 0f
    1 -> HALF_ROOT_TWO to HALF_ROOT_TWO
    2 -> 0f to 1f
    3 -> -HALF_ROOT_TWO to HALF_ROOT_TWO
    4 -> -1f to 0f
    5 -> -HALF_ROOT_TWO to -HALF_ROOT_TWO
    6 -> 0f to -1f
    else -> HALF_ROOT_TWO to -HALF_ROOT_TWO
}

private const val HALF_ROOT_TWO = 0.70710678f

private val SNAP_STEP = PI.toFloat() / 4f

private val SNAP_REACH = END_SNAP_TOLERANCE * PI.toFloat() / 180f

/**
 * How near an eighth-turn a drag has to come before it is taken to mean it, in degrees.
 *
 * The one number the feature is tuned by, and it is a trade: every degree of it is a degree of angle
 * that can no longer be drawn. Three takes 6° out of each 45° arc — an eighth of the range — leaving
 * 39° of every arc free. Against roughly a degree of steadiness in a hand drawing a 200dp line, that
 * is about three times the wobble it has to absorb: enough that aiming for level lands on level, and
 * little enough that a line meant to lean by five degrees still leans by five degrees.
 *
 * It was 7° for a day, which caught more than it should: a third of every direction on the page
 * became unreachable, and a deliberate shallow lean kept coming back flat.
 *
 * Whether the snap should be escapable is the open question here, and it is a UI one: there is no
 * modifier key on a tablet, so it would have to be a hold, a second finger, or a setting. Nothing is
 * built for it, and until something is, angles within this of an eighth-turn are simply unreachable.
 */
const val END_SNAP_TOLERANCE = 3f

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
