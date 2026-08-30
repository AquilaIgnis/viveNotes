package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import kotlin.math.abs

/** Which way an arm runs, and so the one direction a handle on its end may take it. */
enum class ShapeAxis { Horizontal, Vertical }

/**
 * One end of one arm, draggable on its own — `memory/inkPlan.md` §5.4 SD9.
 *
 * The L is what this exists for: its two arms are the two axes, and lengthening one of them has
 * nothing to do with the other. The four corner handles scale a shape *whole* (AD7), which is the
 * right gesture for a rectangle and the wrong one here — dragging a corner of an L to make its
 * horizontal arm longer stretches the vertical one to match, and there is no way back to the
 * proportions you wanted.
 *
 * **Both ends of every arm, head and tail.** Only the outer tips were draggable at first, which
 * quietly made the corner the one point on an L that could not move — and so made a cross
 * unreachable, because a cross is precisely an L whose arms have been pulled back *through* their
 * corner. An arm is a line, a line has two ends, and each of them gets a handle; whether the two
 * arms still meet at one of those ends is then just where they happen to be.
 *
 * **Derived from the segments, never from the kind's ideal geometry.** An arm's axis is read off its
 * own direction, so an L that has already been resized, moved or arm-dragged reports arms where its
 * arms actually are — and a shape dragged out into a cross keeps working, though nothing about it
 * matches what was seeded any more. [ShapeKind.hasArms] decides *whether* a kind offers arms at all;
 * everything else here is geometry.
 *
 * [outward] is the sign of the direction this end lies in from the *other* end of the same arm. A
 * drag is clamped against it so an arm can be shortened to [MIN_ARM_LENGTH] but never turned inside
 * out through its own far end.
 */
data class ShapeArm(
    val segmentId: String,
    /** True for the segment's second point. Which end this is; both of them are handles. */
    val atEnd: Boolean,
    val x: Float,
    val y: Float,
    val axis: ShapeAxis,
    val outward: Float,
) {
    /** Where this end sits on its own axis: the single number a drag along the arm may change. */
    val along: Float get() = if (axis == ShapeAxis.Horizontal) x else y
}

/**
 * The arm ends this shape offers handles for, or none.
 *
 * Gated on the kind rather than on the geometry, because ends alone do not make an arm: an arrow's
 * head is two polylines with a loose end each, and pulling on one of those would take the head off
 * the shaft. Only the kinds that mean it are asked ([ShapeKind.hasArms]).
 */
fun Outline.Shape.arms(): List<ShapeArm> =
    if (kind.hasArms) segments.arms() else emptyList()

/**
 * Moves one end of one arm to [along] on its own axis, leaving the other coordinate — and every
 * other segment — exactly where it was.
 *
 * **Two limits, both floors on how short the arm may be pulled.** [MIN_ARM_LENGTH] from the arm's
 * *own other end*, without which a fast drag would flip an arm through zero — and a horizontal arm
 * that now points left is one no further drag can straighten, since its axis reads back the other
 * way round and the handle that shortened it now lengthens it. And the **junction**: an end may not
 * be pulled back past a point where its arm currently crosses another one ([junctionWith]).
 *
 * The junction limit is what keeps an L an L. Its two arms are two segments that happen to meet, and
 * nothing but this stopped either of them being shortened until they no longer did — leaving a
 * corner with a gap in it, or two loose strokes lying near each other, from a gesture that reads as
 * "make this arm shorter". Outward travel is untouched, so an end still passes the other arm as far
 * as it likes, which is the whole of how an L becomes a cross; what it may no longer do is retreat
 * back through the crossing it is holding.
 *
 * **Absolute, like a corner drag and unlike a move.** It sets where the end is rather than how far
 * it has come, so applying it twice with the same value is applying it once — which is what lets the
 * layer preview a drag frame by frame and commit the same number on the lift.
 */
fun Outline.Shape.withArm(arm: ShapeArm, along: Float): Outline.Shape {
    val segment = segments.firstOrNull { it.id == arm.segmentId } ?: return this
    val horizontal = arm.axis == ShapeAxis.Horizontal
    // The end that stays put: this arm's other one, which on a freshly seeded L is the corner.
    val anchor = when {
        horizontal && arm.atEnd -> segment.x1
        horizontal -> segment.x2
        arm.atEnd -> segment.y1
        else -> segment.y2
    }
    // The coordinate the drag does not change: the arm's own line, and so the line the rest of the
    // shape is asked where it crosses.
    val across = if (horizontal) {
        if (arm.atEnd) segment.y2 else segment.y1
    } else {
        if (arm.atEnd) segment.x2 else segment.x1
    }
    // Every crossing this arm is currently holding. The binding one is the furthest along the
    // direction the end lies in — retreating past the nearest would already have let go of it.
    val junctions = segments.mapNotNull { other ->
        if (other.id == arm.segmentId) null else segment.junctionWith(other, arm.axis, across)
    }
    val limited = if (arm.outward >= 0f) {
        maxOf(along, anchor + MIN_ARM_LENGTH, junctions.maxOrNull() ?: Float.NEGATIVE_INFINITY)
    } else {
        minOf(along, anchor - MIN_ARM_LENGTH, junctions.minOrNull() ?: Float.POSITIVE_INFINITY)
    }
    val moved = if (horizontal) {
        segment.withEnd(arm.atEnd, limited, across)
    } else {
        segment.withEnd(arm.atEnd, across, limited)
    }

    return copy(
        segments = segments.map { if (it.id == arm.segmentId) moved else it },
    ).withRecomputedBounds()
}

/**
 * Both ends of every segment, as arms.
 *
 * Ends shared with another segment are included rather than skipped — the corner of an L is two of
 * them stacked, and dragging either one apart from the other is what turns the L into a cross. The
 * handles do not collide, because each is offset outward along its *own* axis and those two axes are
 * perpendicular; see `ShapeLayer`.
 *
 * A segment with a length on neither axis has no direction to be pulled in and is skipped, which is
 * what keeps a zero-length leftover from a degenerate drag out of the handle set.
 */
internal fun List<ShapeSegment>.arms(): List<ShapeArm> = flatMap { segment ->
    listOf(false, true).mapNotNull { atEnd ->
        val x = if (atEnd) segment.x2 else segment.x1
        val y = if (atEnd) segment.y2 else segment.y1

        val dx = if (atEnd) segment.x2 - segment.x1 else segment.x1 - segment.x2
        val dy = if (atEnd) segment.y2 - segment.y1 else segment.y1 - segment.y2
        val axis = if (abs(dx) >= abs(dy)) ShapeAxis.Horizontal else ShapeAxis.Vertical
        val reach = if (axis == ShapeAxis.Horizontal) dx else dy
        if (reach == 0f) return@mapNotNull null

        ShapeArm(
            segmentId = segment.id,
            atEnd = atEnd,
            x = x,
            y = y,
            axis = axis,
            outward = if (reach > 0f) 1f else -1f,
        )
    }
}

/**
 * Where [other] crosses the line this arm's segment runs along, on the arm's own axis — or null when
 * it does not, or when the crossing is one this arm is not currently holding.
 *
 * The whole of the junction limit, and the reason it needs no knowledge of the L: it asks where two
 * segments meet, and reports the answer as the single coordinate an arm drag can move. [across] is
 * the arm's other coordinate, the one the drag leaves alone, so the question is "at what [axis] does
 * [other] reach this line".
 *
 * **Only a crossing that already exists.** A shape whose arms have somehow come apart — one saved
 * before this limit, say — is left as it is rather than snapped back together the moment a handle is
 * grabbed. Dragging the arm back over the other one restores the junction, and from then on it holds.
 *
 * Chords, not arcs: an arm is a straight segment on a kind that declares arms ([ShapeKind.hasArms]),
 * so there is no bulge here to account for.
 */
private fun ShapeSegment.junctionWith(
    other: ShapeSegment,
    axis: ShapeAxis,
    across: Float,
): Float? {
    val horizontal = axis == ShapeAxis.Horizontal
    val fromAcross = if (horizontal) other.y1 else other.x1
    val toAcross = if (horizontal) other.y2 else other.x2
    val span = toAcross - fromAcross
    // Alongside rather than across: two parallel segments have no one point to hold on to.
    if (span == 0f) return null
    val travelled = (across - fromAcross) / span
    // It stops short of this arm's line, so there is nothing here to hold.
    if (travelled < 0f || travelled > 1f) return null

    val fromAlong = if (horizontal) other.x1 else other.y1
    val toAlong = if (horizontal) other.x2 else other.y2
    val at = fromAlong + travelled * (toAlong - fromAlong)

    val ownFrom = if (horizontal) x1 else y1
    val ownTo = if (horizontal) x2 else y2
    return at.takeIf { it >= minOf(ownFrom, ownTo) && it <= maxOf(ownFrom, ownTo) }
}

/**
 * How short an arm may be pulled, in page units.
 *
 * Not zero: an arm dragged to nothing leaves two handles sitting on top of each other with nothing
 * between them to grab, and the shape reads as having lost a side rather than as having a very short
 * one. The other limit is the junction — an end may pass the *other arm* freely on the way out,
 * which is how a cross is drawn, but may not retreat back through it.
 */
const val MIN_ARM_LENGTH = 8f
