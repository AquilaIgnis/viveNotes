package com.vivenotes.ink

import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeEnd
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.ends
import com.vivenotes.model.ink.seedSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A line end held to the page — `memory/inkPlan.md` §5.4 SD12.
 *
 * The seam this exists for is narrow and easy to miss: the layers clamp where the *finger* is, and
 * the snap then turns the line about its far end, which can take the result somewhere the finger was
 * never allowed to go. A handle out there is drawn outside the page and cannot be grabbed.
 */
class ShapeEndPlacementTest {

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = "line",
            kind = ShapeKind.Line,
            segments = seedSegments(ShapeKind.Line, x1, y1, x2, y2) { "seg-${next++}" },
        ).withRecomputedBounds()
    }

    private fun Outline.Shape.end(atEnd: Boolean): ShapeEnd = ends().single { it.atEnd == atEnd }

    @Test
    fun `an end the snap would carry off the page stops on the wall instead`() {
        // The finger is *on* the page, hard against the wall at x = 0, and 1.4° short of a diagonal
        // — so it is the aim, not the drag, that puts the end 5dp past the origin corner. Shortened
        // rather than clamped: the angle is what the gesture promised.
        val line = line(200f, 100f, 280f, 100f)

        val held = line.withEndOnPage(line.end(atEnd = true), x = 0f, y = 310f)

        val shaft = held.segments.single()
        assertEquals("the end was left off the page", 0f, shaft.x2, TOLERANCE)
        assertTrue("the page's own corner moved", held.x >= PageBounds.MIN_X)
        assertTrue("the page's own corner moved", held.y >= PageBounds.MIN_Y)
        // Still on the diagonal it snapped to: the two legs are equal, which is the whole point of
        // giving up length rather than moving the point back.
        assertEquals(shaft.x1 - shaft.x2, shaft.y2 - shaft.y1, TOLERANCE)
    }

    @Test
    fun `an aim that lands on the page is left exactly where it aimed`() {
        // The common case, and the one that must not pay for the rare one.
        val line = line(0f, 0f, 100f, 0f)

        val held = line.withEndOnPage(line.end(atEnd = true), x = 100f, y = 3f)

        val shaft = held.segments.single()
        assertEquals("the hold shortened a line that was already on the page", 0f, shaft.y2, 0f)
        assertEquals(100.045f, shaft.x2, 0.01f)
    }

    @Test
    fun `the fixed end never moves, however far the other is held back`() {
        // Shortening is along the ray from the end that is staying put, so that end is the one thing
        // the hold cannot touch — otherwise holding the line on the page would drag the rest of it.
        val line = line(10f, 10f, 200f, 10f)

        val held = line.withEndOnPage(line.end(atEnd = true), x = -500f, y = 14f)

        val shaft = held.segments.single()
        assertEquals(10f, shaft.x1, TOLERANCE)
        assertEquals(10f, shaft.y1, TOLERANCE)
        assertTrue("the held end went off the page", shaft.x2 >= PageBounds.MIN_X)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
