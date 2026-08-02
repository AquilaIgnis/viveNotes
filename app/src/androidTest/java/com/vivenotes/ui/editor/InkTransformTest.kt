package com.vivenotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The page → view transform for ink.
 *
 * Worth its own test because getting it wrong is silent: nothing crashes, ink just lands somewhere
 * other than the pen. Both directions matter — the forward matrix draws finished strokes, and its
 * inverse is what turns a touch into the page coordinates a stroke is stored in.
 */
class InkTransformTest {

    private fun map(zoom: Float, density: Float, scrollX: Float, scrollY: Float, x: Float, y: Float) =
        floatArrayOf(x, y).also { inkPageToView(zoom, density, scrollX, scrollY).mapPoints(it) }

    @Test
    fun pageUnitsScaleByZoomAndDensity() {
        // 100 dp at density 2 is 200px; at 200% zoom it is 400px.
        assertEquals(200f, map(1f, 2f, 0f, 0f, 100f, 100f)[0], 0.01f)
        assertEquals(400f, map(2f, 2f, 0f, 0f, 100f, 100f)[0], 0.01f)
    }

    @Test
    fun scrollIsSubtractedInPixelsNotPageUnits() {
        // The scroll offset is already in pixels, so it is not scaled again by zoom or density.
        val p = map(zoom = 2f, density = 2f, scrollX = 50f, scrollY = 30f, x = 100f, y = 100f)
        assertEquals(400f - 50f, p[0], 0.01f)
        assertEquals(400f - 30f, p[1], 0.01f)
    }

    /** The inverse is what a touch goes through, so a round trip has to land back where it started. */
    @Test
    fun theInverseTurnsAViewTouchBackIntoPageUnits() {
        val forward = inkPageToView(zoom = 1.5f, density = 2.75f, scrollX = 120f, scrollY = 340f)
        val inverse = android.graphics.Matrix().also { assert(forward.invert(it)) }
        val point = floatArrayOf(412.5f, 96.25f)
        forward.mapPoints(point)
        inverse.mapPoints(point)
        assertEquals(412.5f, point[0], 0.01f)
        assertEquals(96.25f, point[1], 0.01f)
    }

    /** A touch at the top-left of an unscrolled page is the page's origin, at any zoom. */
    @Test
    fun theOriginIsTheOriginAtAnyZoom() {
        val p = map(zoom = 4f, density = 3f, scrollX = 0f, scrollY = 0f, x = 0f, y = 0f)
        assertEquals(0f, p[0], 0.01f)
        assertEquals(0f, p[1], 0.01f)
    }
}
