package com.vivenotes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page's origin corner, which is the one wall an otherwise infinite canvas has.
 *
 * Pinned here rather than on a device because every one of these is arithmetic: the bug it guards
 * against — content dragged or drawn to a negative coordinate, where nothing can ever scroll to it
 * again — shows up on screen as an absence, which is the hardest kind of thing to test for.
 */
class PageBoundsTest {

    private fun bounds(left: Float, top: Float, right: Float, bottom: Float) =
        InkBounds(left, top, right, bottom)

    // --- translation ----------------------------------------------------------------------------

    @Test
    fun `a move that stays on the page is passed through untouched`() {
        val delta = PageBounds.clampTranslation(bounds(100f, 100f, 200f, 200f), -40f, -30f)
        assertEquals(-40f, delta.x, 0f)
        assertEquals(-30f, delta.y, 0f)
    }

    @Test
    fun `a move past the corner stops at the corner`() {
        val delta = PageBounds.clampTranslation(bounds(20f, 30f, 120f, 130f), -500f, -500f)
        assertEquals(-20f, delta.x, 0f)
        assertEquals(-30f, delta.y, 0f)
    }

    /** The hand expects to slide along the edge it reaches first, not to stop dead against it. */
    @Test
    fun `each axis is limited on its own`() {
        val delta = PageBounds.clampTranslation(bounds(10f, 400f, 110f, 500f), -80f, -80f)
        assertEquals(-10f, delta.x, 0f)
        assertEquals(-80f, delta.y, 0f)
    }

    @Test
    fun `moving away from the corner is never limited`() {
        val delta = PageBounds.clampTranslation(bounds(0f, 0f, 10f, 10f), 900f, 900f)
        assertEquals(900f, delta.x, 0f)
        assertEquals(900f, delta.y, 0f)
    }

    /**
     * The healing case, and the one that brings a page written by an older build back.
     *
     * Content that is already off the page raises the floor above zero, so even a move that asks to
     * go further out returns the delta that lands it exactly on the wall.
     */
    @Test
    fun `content already off the page is pulled back onto it`() {
        val delta = PageBounds.clampTranslation(bounds(-43f, -77f, 57f, 23f), -10f, -10f)
        assertEquals(43f, delta.x, 0f)
        assertEquals(77f, delta.y, 0f)
    }

    // --- scale ----------------------------------------------------------------------------------

    @Test
    fun `a resize that stays on the page is passed through untouched`() {
        val scale = PageBounds.clampScale(
            bounds(100f, 100f, 200f, 200f),
            anchor = InkPoint(200f, 200f),
            scaleX = 1.5f,
            scaleY = 1.5f,
        )
        assertEquals(1.5f, scale.x, 1e-4f)
        assertEquals(1.5f, scale.y, 1e-4f)
    }

    /**
     * Dragging the top-left handle out: the anchor is the bottom-right corner, so the left edge
     * travels towards the origin and reaches it at exactly `anchor / (anchor - left)`.
     */
    @Test
    fun `growing towards the corner stops when the far edge reaches it`() {
        val scale = PageBounds.clampScale(
            bounds(100f, 50f, 200f, 150f),
            anchor = InkPoint(200f, 150f),
            scaleX = 9f,
            scaleY = 9f,
        )
        assertEquals(2f, scale.x, 1e-4f)
        assertEquals(1.5f, scale.y, 1e-4f)

        // And the limit is the exact one: applying it lands the rectangle on the wall, not past it.
        val scaled = bounds(100f, 50f, 200f, 150f)
            .scaled(InkPoint(200f, 150f), scale.x, scale.y)
        assertEquals(PageBounds.MIN_X, scaled.left, 1e-3f)
        assertEquals(PageBounds.MIN_Y, scaled.top, 1e-3f)
    }

    /** The anchor is the corner nearest the origin, so scaling only ever pushes the rest away. */
    @Test
    fun `growing away from the corner is never limited`() {
        val scale = PageBounds.clampScale(
            bounds(10f, 10f, 60f, 60f),
            anchor = InkPoint(10f, 10f),
            scaleX = 40f,
            scaleY = 40f,
        )
        assertEquals(40f, scale.x, 0f)
        assertEquals(40f, scale.y, 0f)
    }

    @Test
    fun `shrinking towards an anchor is never limited`() {
        val scale = PageBounds.clampScale(
            bounds(5f, 5f, 500f, 500f),
            anchor = InkPoint(500f, 500f),
            scaleX = 0.1f,
            scaleY = 0.1f,
        )
        assertEquals(0.1f, scale.x, 1e-4f)
        assertEquals(0.1f, scale.y, 1e-4f)
    }

    /**
     * The case that cost a page: a rectangle already off the page, scaled about an anchor sitting
     * **on** the wall.
     *
     * The arithmetic limit here is exactly zero — no positive scale about that anchor brings a
     * negative edge back — and applying it collapses the object into a point. Replay hands this
     * shape in for every stored translation, whose anchor is (0, 0) and whose scale is 1, so the
     * first page loaded with anything still left of the origin would have had its ink vanish. A
     * scale that destroys what it was asked to limit is not a limit; the edge is left alone and the
     * translation is what brings it back.
     */
    @Test
    fun `content already off the page is never scaled away`() {
        val scale = PageBounds.clampScale(
            bounds(-43f, -77f, 500f, 400f),
            anchor = InkPoint(PageBounds.MIN_X, PageBounds.MIN_Y),
            scaleX = 1f,
            scaleY = 1f,
        )
        assertEquals(1f, scale.x, 0f)
        assertEquals(1f, scale.y, 0f)
    }

    /** Nothing this returns may shrink an object: the limit exists to stop growth, not to cause it. */
    @Test
    fun `a limit never drags an edge inward on its own`() {
        listOf(
            bounds(0f, 0f, 10f, 10f),
            bounds(0.5f, 900f, 40f, 1000f),
            bounds(300f, 12f, 900f, 40f),
        ).forEach { rect ->
            listOf(InkPoint(0f, 0f), InkPoint(1000f, 1000f), InkPoint(5f, 5f)).forEach { anchor ->
                val scale = PageBounds.clampScale(rect, anchor, 1f, 1f)
                assertTrue("$rect about $anchor gave ${scale.x}", scale.x >= 1f)
                assertTrue("$rect about $anchor gave ${scale.y}", scale.y >= 1f)
            }
        }
    }

    // --- points and corners ---------------------------------------------------------------------

    @Test
    fun `a point is clamped onto the page corner-wise`() {
        val clamped = PageBounds.clamp(InkPoint(-12f, 340f))
        assertEquals(0f, clamped.x, 0f)
        assertEquals(340f, clamped.y, 0f)
    }

    @Test
    fun `a corner on the page needs no correction`() {
        val correction = PageBounds.correctionFor(4f, 0f)
        assertEquals(0f, correction.x, 0f)
        assertEquals(0f, correction.y, 0f)
    }

    @Test
    fun `a corner off the page is corrected by exactly its overhang`() {
        val correction = PageBounds.correctionFor(-8f, -220f)
        assertEquals(8f, correction.x, 0f)
        assertEquals(220f, correction.y, 0f)
    }
}
