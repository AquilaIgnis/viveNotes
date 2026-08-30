package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two ends of a line, and what a handle on one of them may do — `memory/inkPlan.md` §5.4 SD12.
 *
 * Geometry, so it belongs here rather than on a device. The failures it guards are the two that make
 * an endpoint handle worth having at all: an end that cannot leave its own axis is a corner handle
 * with extra steps, and an arrow whose head does not follow its shaft is an arrow pointing the way it
 * used to go.
 */
class ShapeEndTest {

    /** A line running east from the origin, 100 long. */
    private fun line(): Outline.Shape = seeded(ShapeKind.Line, 0f, 0f, 100f, 0f)

    /** The same, with a head on it: shaft first, then the head's two wings. */
    private fun arrow(): Outline.Shape = seeded(ShapeKind.Arrow, 0f, 0f, 100f, 0f)

    private fun seeded(kind: ShapeKind, x1: Float, y1: Float, x2: Float, y2: Float): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = kind.name.lowercase(),
            kind = kind,
            segments = seedSegments(kind, x1, y1, x2, y2) { "seg-${next++}" },
        ).withRecomputedBounds()
    }

    private fun Outline.Shape.end(atEnd: Boolean): ShapeEnd = ends().single { it.atEnd == atEnd }

    /** Where the arrow's head meets: the point both of its wing segments share. */
    private fun Outline.Shape.headTip(): Pair<Float, Float> =
        segments[1].x2 to segments[1].y2

    // -------------------------------------------------------------------------------------------

    @Test
    fun `a line offers one handle per end`() {
        val line = line()

        val ends = line.ends()

        assertEquals(2, ends.size)
        assertEquals(0f, line.end(atEnd = false).x, TOLERANCE)
        assertEquals(100f, line.end(atEnd = true).x, TOLERANCE)
    }

    @Test
    fun `an arrow offers the shaft's ends, not the head's`() {
        // The head has two loose ends of its own, and pulling on one of those would take it off the
        // shaft. Two handles, on the tail and on the tip — where the arrow starts and where it points.
        val arrow = arrow()

        val ends = arrow.ends()

        assertEquals(3, arrow.segments.size)
        assertEquals(2, ends.size)
        assertEquals(0f, ends.first { !it.atEnd }.x, TOLERANCE)
        assertEquals(100f, ends.first { it.atEnd }.x, TOLERANCE)
    }

    @Test
    fun `a shape with a box offers no ends at all`() {
        // The gate is the kind. A rectangle's ends are its corners, and those are AD7's handles.
        listOf(ShapeKind.Rectangle, ShapeKind.Ellipse, ShapeKind.L).forEach { kind ->
            assertTrue("$kind offered ends", seeded(kind, 0f, 0f, 80f, 40f).ends().isEmpty())
        }
    }

    @Test
    fun `moving an end off the line turns it rather than stretching it`() {
        // The whole reason a line does not use the corner handles: the four of them can only scale
        // the box the line spans, and no scale of a box turns a horizontal line into a vertical one.
        val line = line()

        val turned = line.withEnd(line.end(atEnd = true), x = 0f, y = 100f)

        val shaft = turned.segments.single()
        assertEquals("the tail moved", 0f, shaft.x1, TOLERANCE)
        assertEquals("the tail moved", 0f, shaft.y1, TOLERANCE)
        assertEquals(0f, shaft.x2, TOLERANCE)
        assertEquals(100f, shaft.y2, TOLERANCE)
    }

    @Test
    fun `either end moves, and only the one that was grabbed`() {
        val line = line()

        val moved = line.withEnd(line.end(atEnd = false), x = -40f, y = 60f)

        val shaft = moved.segments.single()
        assertEquals(-40f, shaft.x1, TOLERANCE)
        assertEquals(60f, shaft.y1, TOLERANCE)
        assertEquals("the far end followed", 100f, shaft.x2, TOLERANCE)
        assertEquals("the far end followed", 0f, shaft.y2, TOLERANCE)
    }

    @Test
    fun `the bounds follow the end that moved`() {
        // Stored bounds are what the canvas lays out, hit-tests and draws the selection box from, so
        // an end drag that left them behind gives a line its own selection no longer contains.
        val line = line()

        val turned = line.withEnd(line.end(atEnd = true), x = 60f, y = 80f)

        assertEquals(0f, turned.x, TOLERANCE)
        assertEquals(0f, turned.y, TOLERANCE)
        assertEquals(60f, turned.width, TOLERANCE)
        assertEquals(80f, turned.height, TOLERANCE)
    }

    @Test
    fun `an end dropped onto the other one is held off it`() {
        // Two handles on one point have nothing between them to grab, and a line with no length has
        // no direction to be pulled back out along. It keeps MIN_LINE_LENGTH, in the direction asked
        // for — here, straight down.
        val line = line()

        val crushed = line.withEnd(line.end(atEnd = true), x = 0f, y = 2f)

        val shaft = crushed.segments.single()
        assertEquals(
            MIN_LINE_LENGTH,
            hypot(shaft.x2 - shaft.x1, shaft.y2 - shaft.y1),
            TOLERANCE,
        )
        assertTrue("it did not keep the direction it was pushed", shaft.y2 > shaft.y1)
    }

    @Test
    fun `an end dropped exactly on the other keeps the heading the line had`() {
        // The one drag with no direction of its own. Snapping to any fixed direction would spin the
        // line on the last pixel of travel; it holds the heading it already had instead.
        val line = line()

        val crushed = line.withEnd(line.end(atEnd = true), x = 0f, y = 0f)

        val shaft = crushed.segments.single()
        assertEquals(MIN_LINE_LENGTH, shaft.x2, TOLERANCE)
        assertEquals(0f, shaft.y2, TOLERANCE)
    }

    @Test
    fun `an arrow's head follows its tip`() {
        val arrow = arrow()
        val before = arrow.headTip()

        val turned = arrow.withEnd(arrow.end(atEnd = true), x = 0f, y = 100f)

        assertEquals(100f, before.second + 100f, TOLERANCE) // the head started at (100, 0)
        val (tipX, tipY) = turned.headTip()
        assertEquals("the head stayed where the arrow used to point", 0f, tipX, TOLERANCE)
        assertEquals(100f, tipY, TOLERANCE)
        // Both wings open back up the shaft, which now runs down the page.
        assertTrue(turned.segments[1].y1 < tipY)
        assertTrue(turned.segments[2].y2 < tipY)
    }

    @Test
    fun `an arrow's head is re-aimed when its tail moves`() {
        // The head is derived from *both* endpoints — it opens back along the shaft — so a tail drag
        // has to rebuild it even though the tip has not moved a page unit.
        val arrow = arrow()
        val before = arrow.segments[1]

        val turned = arrow.withEnd(arrow.end(atEnd = false), x = 100f, y = 100f)

        val (tipX, tipY) = turned.headTip()
        assertEquals("the tip moved", 100f, tipX, TOLERANCE)
        assertEquals("the tip moved", 0f, tipY, TOLERANCE)
        assertNotEquals("the head kept its old heading", before.y1, turned.segments[1].y1)
        // The shaft now runs up the page, so the wings open downwards from the tip.
        assertTrue(turned.segments[1].y1 > tipY)
        assertTrue(turned.segments[2].y2 > tipY)
    }

    @Test
    fun `an arrow's head grows with its shaft, up to the cap`() {
        // Whatever `trace` would have drawn for a shaft this long, which is the point of re-tracing
        // rather than translating the head: a dragged arrow is the arrow you would have drawn.
        val short = seeded(ShapeKind.Arrow, 0f, 0f, 20f, 0f)

        val stretched = short.withEnd(short.end(atEnd = true), x = 400f, y = 0f)

        val wing = stretched.segments[1]
        val head = hypot(wing.x2 - wing.x1, wing.y2 - wing.y1)
        val drawn = seeded(ShapeKind.Arrow, 0f, 0f, 400f, 0f).segments[1]
        assertEquals(hypot(drawn.x2 - drawn.x1, drawn.y2 - drawn.y1), head, TOLERANCE)
    }

    @Test
    fun `segment ids survive an end move`() {
        // The selection, the toolkit and a drag in flight all name segments by id. A move that
        // reissued them would be a shape that had been replaced rather than edited.
        val arrow = arrow()

        val turned = arrow.withEnd(arrow.end(atEnd = true), x = 40f, y = 90f)

        assertEquals(arrow.segments.map { it.id }, turned.segments.map { it.id })
    }

    @Test
    fun `setting the same end twice is setting it once`() {
        // What lets the layer preview a drag every frame and commit the same pair of numbers on the
        // lift — the contract `withArm` keeps for arms.
        val arrow = arrow()

        val once = arrow.withEnd(arrow.end(atEnd = true), x = 40f, y = 90f)
        val twice = once.withEnd(once.end(atEnd = true), x = 40f, y = 90f)

        assertEquals(once, twice)
    }

    @Test
    fun `a kind with no ends is left alone rather than re-traced`() {
        // The model has to be safe on its own: the ViewModel looks an end up before applying it, but
        // a stale drag naming a shape that has since become something else must change nothing.
        val rectangle = seeded(ShapeKind.Rectangle, 0f, 0f, 80f, 40f)

        assertEquals(rectangle, rectangle.withEnd(ShapeEnd(atEnd = true, x = 0f, y = 0f), 9f, 9f))
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
