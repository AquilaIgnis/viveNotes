package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a fill colour paints — `docs/inkPlan.md` §5.4 SD7.
 *
 * The question is "which shapes have an inside, and where is it", and every wrong answer draws
 * something rather than failing: a cube filled along its twelve edges is a self-crossing mess, an L
 * filled as if closed is a triangle nobody drew, and a fill that misses the border by a few units
 * looks like a rendering bug rather than a geometry one.
 */
class ShapeFillTest {

    private fun shape(kind: ShapeKind, box: FloatArray = floatArrayOf(20f, 30f, 220f, 190f)): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = kind.name,
            kind = kind,
            segments = seedSegments(kind, box[0], box[1], box[2], box[3]) { "seg-${next++}" },
        ).withRecomputedBounds()
    }

    private fun FloatArray.points(): List<Pair<Float, Float>> =
        (indices step 2).map { this[it] to this[it + 1] }

    // -------------------------------------------------------------------------------------------

    @Test
    fun `a closed flat shape fills the outline it draws`() {
        listOf(
            ShapeKind.Rectangle, ShapeKind.RoundedRectangle, ShapeKind.Ellipse,
            ShapeKind.Triangle, ShapeKind.RightTriangle, ShapeKind.Diamond,
            ShapeKind.Pentagon, ShapeKind.Hexagon,
        ).forEach { kind ->
            val shape = shape(kind)
            assertTrue("$kind cannot be filled", shape.canFill)
            val region = shape.fillRegion().single().points()
            // Within a rounding rather than exactly: an ellipse's ring starts and ends at the same
            // angle, and cos(-PI/2) and cos(3*PI/2) are not the same float.
            assertEquals("$kind did not close its region", region.first().first, region.last().first, TOLERANCE)
            assertEquals("$kind did not close its region", region.first().second, region.last().second, TOLERANCE)
        }
    }

    @Test
    fun `an open shape has no inside`() {
        listOf(ShapeKind.Line, ShapeKind.Arrow, ShapeKind.L).forEach { kind ->
            val shape = shape(kind)
            assertFalse("$kind offered a fill it has no room for", shape.canFill)
            assertTrue("$kind produced a region anyway", shape.fillRegion().isEmpty())
        }
    }

    @Test
    fun `an L dragged into a cross still has no inside`() {
        // Two crossing lines enclose nothing, however much they look like they surround four corners.
        val ell = shape(ShapeKind.L)
        val crossed = ell.withArm(ell.arms().single { it.axis == ShapeAxis.Horizontal && it.outward < 0f }, -60f)

        assertFalse(crossed.canFill)
        assertTrue(crossed.fillRegion().isEmpty())
    }

    @Test
    fun `a solid fills its silhouette rather than its edges`() {
        ShapeKind.entries.filter(ShapeKind::isSolid).forEach { kind ->
            val shape = shape(kind)
            assertTrue("$kind cannot be filled", shape.canFill)
            val region = shape.fillRegion().single()
            val points = region.points()

            assertEquals("$kind did not close its silhouette", points.first(), points.last())
            assertTrue("$kind reported a degenerate silhouette", points.size > 3)
            // Every drawn point is inside the hull, which is what makes it a silhouette rather than
            // one face: a fill that left an edge outside it would show as a sliver of bare page.
            shape.segments.flatMap { it.polyline().points() }.forEach { (x, y) ->
                assertTrue("$kind drew ($x, $y) outside its own fill", region.encloses(x, y))
            }
        }
    }

    @Test
    fun `a cube's silhouette is the hexagon it covers, not all eight corners`() {
        // The one solid where the answer is checkable by counting: seen from the front, above and to
        // the right, a cube covers six of its eight projected vertices and hides the seventh inside.
        val region = shape(ShapeKind.Cube).fillRegion().single().points().dropLast(1)

        assertEquals(6, region.size)
    }

    @Test
    fun `the silhouette follows an uneven resize instead of being re-traced`() {
        // Why the hull rather than `trace(kind, bounds).fill`: a solid's depth is derived from
        // min(width, height), so a fresh trace of a stretched box computes a depth the drawn edges no
        // longer have, and the fill stands off them.
        val stretched = shape(ShapeKind.Cube).scaledAbout(20f, 30f, scaleX = 3f, scaleY = 1f)
        val region = stretched.fillRegion().single()

        stretched.segments.flatMap { it.polyline().points() }.forEach { (x, y) ->
            assertTrue("a stretched cube drew ($x, $y) outside its fill", region.encloses(x, y))
        }
    }

    /**
     * True when the point is inside the closed convex region or on its edge.
     *
     * Every edge has to turn the same way about the point. Which way is deliberately not asserted:
     * the hull's winding is an implementation detail, and a test that pins it would fail the day the
     * sweep is written in the other order without anything being wrong.
     */
    private fun FloatArray.encloses(x: Float, y: Float): Boolean {
        val corners = points().dropLast(1)
        val turns = corners.indices.map { index ->
            val (ax, ay) = corners[index]
            val (bx, by) = corners[(index + 1) % corners.size]
            (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        }
        return turns.all { it >= -TOLERANCE } || turns.all { it <= TOLERANCE }
    }

    private companion object {
        const val TOLERANCE = 0.05f
    }
}
