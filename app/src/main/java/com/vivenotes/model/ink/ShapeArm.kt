package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import kotlin.math.abs

/** Which way an arm runs, and so the one direction a handle on its end may take it. */
enum class ShapeAxis { Horizontal, Vertical }

/**
 * One end of one arm, draggable on its own — `docs/inkPlan.md` §5.4 SD9.
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
 * Clamped to [MIN_ARM_LENGTH] from the arm's *own other end*, and to nothing else — an end may pass
 * the other arm as far as it likes, which is the whole of how an L becomes a cross. Without the one
 * limit a fast drag would flip an arm through zero, and a horizontal arm that now points left is one
 * no further drag can straighten: its axis reads back the other way round, so the handle that
 * shortened it now lengthens it.
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
    val limited = if (arm.outward >= 0f) {
        maxOf(along, anchor + MIN_ARM_LENGTH)
    } else {
        minOf(along, anchor - MIN_ARM_LENGTH)
    }
    val across = if (horizontal) {
        if (arm.atEnd) segment.y2 else segment.y1
    } else {
        if (arm.atEnd) segment.x2 else segment.x1
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
 * How short an arm may be pulled, in page units.
 *
 * Not zero: an arm dragged to nothing leaves two handles sitting on top of each other with nothing
 * between them to grab, and the shape reads as having lost a side rather than as having a very short
 * one. It is the only limit on an arm — either end may pass the *other arm* freely, which is how a
 * cross is drawn.
 */
const val MIN_ARM_LENGTH = 8f
