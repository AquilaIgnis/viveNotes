package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arms of an L, and what a handle on one of them may do — `docs/inkPlan.md` §5.4 SD9.
 *
 * All of this is geometry, so it belongs here rather than on a device: which ends a shape offers,
 * which axis each runs along, and what a drag along that axis leaves untouched. The failure it is
 * really guarding is the quiet one — an arm drag that also moves the far end, or that drags the
 * *other* arm because the two segments were told apart by their order in the list.
 */
class ShapeArmTest {

    /** An L in the box (20, 30)–(120, 130): corner at the bottom left, arms 100 long each. */
    private fun ell(): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = "ell",
            kind = ShapeKind.L,
            segments = seedSegments(ShapeKind.L, 20f, 30f, 120f, 130f) { "seg-${next++}" },
        ).withRecomputedBounds()
    }

    /** The outer tip of an arm: the end that points away from the corner an L is seeded with. */
    private fun Outline.Shape.tipOn(axis: ShapeAxis): ShapeArm =
        arms().single { it.axis == axis && it.outward == if (axis == ShapeAxis.Horizontal) 1f else -1f }

    /** The other end of the same arm — the corner end, until something is dragged. */
    private fun Outline.Shape.tailOn(axis: ShapeAxis): ShapeArm =
        arms().single { it.axis == axis && it.outward == if (axis == ShapeAxis.Horizontal) -1f else 1f }

    // -------------------------------------------------------------------------------------------

    @Test
    fun `an L offers both ends of both arms`() {
        // Head and tail each: without the tails the corner is the one point on an L that cannot
        // move, and a cross — an L whose arms have been pulled back through their corner — is
        // unreachable.
        val arms = ell().arms()

        assertEquals(4, arms.size)
        assertEquals(2, arms.count { it.axis == ShapeAxis.Vertical })
        assertEquals(2, arms.count { it.axis == ShapeAxis.Horizontal })
        // The outer tips: (20, 30) is the top of the upright, (120, 130) the end of the foot. Up the
        // page is decreasing y, so the end the user pulls "^" runs the negative way.
        assertEquals(30f, ell().tipOn(ShapeAxis.Vertical).y, TOLERANCE)
        assertEquals(120f, ell().tipOn(ShapeAxis.Horizontal).x, TOLERANCE)
        // Both tails sit on the corner, one per arm, pointing back along their own axis.
        arms.filter { it != ell().tipOn(ShapeAxis.Vertical) && it != ell().tipOn(ShapeAxis.Horizontal) }
            .forEach { tail ->
                assertEquals("a tail is not on the corner", 20f, tail.x, TOLERANCE)
                assertEquals("a tail is not on the corner", 130f, tail.y, TOLERANCE)
            }
    }

    @Test
    fun `a closed shape is not a kind that offers arms`() {
        var next = 0
        val rectangle = Outline.Shape(
            id = "rect",
            kind = ShapeKind.Rectangle,
            segments = seedSegments(ShapeKind.Rectangle, 0f, 0f, 80f, 40f) { "seg-${next++}" },
        ).withRecomputedBounds()

        assertTrue(rectangle.arms().isEmpty())
    }

    @Test
    fun `a line has two ends but is not a kind that offers them`() {
        // The gate is the kind, not the geometry: an arrow's head has loose ends that mean nothing to
        // pull on, and a line already resizes end to end by its corners.
        var next = 0
        val line = Outline.Shape(
            id = "line",
            kind = ShapeKind.Line,
            segments = seedSegments(ShapeKind.Line, 0f, 0f, 80f, 40f) { "seg-${next++}" },
        ).withRecomputedBounds()

        assertTrue(line.arms().isEmpty())
        assertEquals("the geometry does have two ends", 2, line.segments.arms().size)
    }

    @Test
    fun `dragging the foot moves only its own tip, and only in x`() {
        val ell = ell()
        val foot = ell.tipOn(ShapeAxis.Horizontal)

        val pulled = ell.withArm(foot, along = 260f)

        val segment = pulled.segments.single { it.id == foot.segmentId }
        assertEquals("the tip did not follow the finger", 260f, segment.x2, TOLERANCE)
        assertEquals("the foot drifted off its own line", 130f, segment.y2, TOLERANCE)
        assertEquals("the corner moved", 20f, segment.x1, TOLERANCE)
        assertEquals("the corner moved", 130f, segment.y1, TOLERANCE)
        // The upright is the whole point: it is not what was grabbed, so it does not change.
        val upright = pulled.segments.single { it.id != foot.segmentId }
        assertEquals(ell.segments.single { it.id != foot.segmentId }, upright)
    }

    @Test
    fun `dragging the upright moves only its own tip, and only in y`() {
        val ell = ell()
        val upright = ell.tipOn(ShapeAxis.Vertical)

        val pulled = ell.withArm(upright, along = -50f)

        val segment = pulled.segments.single { it.id == upright.segmentId }
        assertEquals("the tip did not follow the finger", -50f, segment.y1, TOLERANCE)
        assertEquals("the upright leaned", 20f, segment.x1, TOLERANCE)
        assertEquals("the corner moved", 130f, segment.y2, TOLERANCE)
        val foot = pulled.segments.single { it.id != upright.segmentId }
        assertEquals(ell.segments.single { it.id != upright.segmentId }, foot)
    }

    @Test
    fun `the bounds follow the arm that moved`() {
        // Stored bounds are what the canvas lays out and hit-tests with, so an arm drag that left
        // them behind would give a shape whose selection box no longer contains it.
        val ell = ell()

        val pulled = ell.withArm(ell.tipOn(ShapeAxis.Horizontal), along = 260f)

        assertEquals(20f, pulled.x, TOLERANCE)
        assertEquals(240f, pulled.width, TOLERANCE)
        assertEquals("the other axis should not have moved", 100f, pulled.height, TOLERANCE)
    }

    @Test
    fun `an arm can be shortened to its minimum but never through its own other end`() {
        val ell = ell()
        val foot = ell.tipOn(ShapeAxis.Horizontal)

        // The corner is at x = 20, so this asks for a foot pointing the other way.
        val crushed = ell.withArm(foot, along = -400f)

        val segment = crushed.segments.single { it.id == foot.segmentId }
        assertEquals(20f + MIN_ARM_LENGTH, segment.x2, TOLERANCE)
        assertTrue("the foot flipped through its own other end", segment.x2 > segment.x1)
    }

    @Test
    fun `the same clamp holds for an arm that runs the negative way`() {
        val ell = ell()
        val upright = ell.tipOn(ShapeAxis.Vertical)

        val crushed = ell.withArm(upright, along = 900f)

        val segment = crushed.segments.single { it.id == upright.segmentId }
        assertEquals(130f - MIN_ARM_LENGTH, segment.y1, TOLERANCE)
    }

    @Test
    fun `pulling both tails back through the corner makes a cross`() {
        // The shape the tails exist for. Neither arm is stretched — each is *extended past the other*,
        // which is a thing no corner handle and no outer tip alone can do.
        val ell = ell()

        val crossed = ell.withArm(ell.tailOn(ShapeAxis.Horizontal), along = -40f)
            .let { it.withArm(it.tailOn(ShapeAxis.Vertical), along = 200f) }

        val foot = crossed.segments.single { it.y1 == it.y2 }
        val upright = crossed.segments.single { it.x1 == it.x2 }
        assertEquals("the foot did not reach back past the upright", -40f, foot.x1, TOLERANCE)
        assertEquals("the foot's far end moved", 120f, foot.x2, TOLERANCE)
        assertEquals("the upright did not reach down past the foot", 200f, upright.y2, TOLERANCE)
        assertEquals("the upright's far end moved", 30f, upright.y1, TOLERANCE)
        // They cross rather than meet: the point they share is in the middle of both now.
        assertTrue("the foot no longer spans the upright", foot.x1 < upright.x1 && upright.x1 < foot.x2)
        assertTrue("the upright no longer spans the foot", upright.y1 < foot.y1 && foot.y1 < upright.y2)
        assertEquals("a cross should still offer four handles", 4, crossed.arms().size)
    }

    @Test
    fun `a tail is clamped against its own arm, not against the other one`() {
        // The one limit: an end may pass the other arm freely — that is the cross — but may not be
        // pushed through the far end of the line it belongs to.
        val ell = ell()

        val crushed = ell.withArm(ell.tailOn(ShapeAxis.Horizontal), along = 400f)

        val foot = crushed.segments.single { it.id == ell.tailOn(ShapeAxis.Horizontal).segmentId }
        assertEquals(120f - MIN_ARM_LENGTH, foot.x1, TOLERANCE)
        assertTrue("the foot turned around", foot.x1 < foot.x2)
    }

    @Test
    fun `setting the same tip twice is setting it once`() {
        // What lets the layer preview a drag every frame and then commit the same number on the lift.
        val ell = ell()
        val foot = ell.tipOn(ShapeAxis.Horizontal)

        val once = ell.withArm(foot, along = 200f)
        val twice = once.withArm(foot, along = 200f)

        assertEquals(once, twice)
    }

    @Test
    fun `arms are still found after the shape has been corner-resized`() {
        // The reason arms are read off the segments rather than re-derived from the kind and a box:
        // by the time a handle is grabbed, the L may be nothing like the one that was seeded.
        val scaled = ell().scaledAbout(anchorX = 20f, anchorY = 130f, scaleX = 3f, scaleY = 0.5f)

        val horizontal = scaled.tipOn(ShapeAxis.Horizontal)
        val vertical = scaled.tipOn(ShapeAxis.Vertical)

        assertEquals(320f, horizontal.x, TOLERANCE)
        assertEquals(80f, vertical.y, TOLERANCE)
        assertEquals(1f, horizontal.outward, TOLERANCE)
        assertEquals(-1f, vertical.outward, TOLERANCE)
    }

    @Test
    fun `an arm from another shape is left alone rather than grafted on`() {
        // The ViewModel looks an arm up again before applying it, but the model is what has to be
        // safe: a stale drag naming a segment this shape has never had must change nothing.
        val ell = ell()
        val stranger = ShapeArm("not-mine", atEnd = true, x = 0f, y = 0f, axis = ShapeAxis.Horizontal, outward = 1f)

        assertEquals(ell, ell.withArm(stranger, along = 999f))
        assertNull(ell.arms().firstOrNull { it.segmentId == "not-mine" })
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
