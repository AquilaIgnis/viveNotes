package com.vivenotes.ui.editor

import androidx.compose.ui.geometry.Offset
import com.vivenotes.data.ViewSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a pinch does to the viewport.
 *
 * The half worth testing is the scroll, not the zoom: scaling a number is hard to get wrong, while
 * "the page grows about the point between your fingers" is a two-term expression that looks right
 * in every arrangement and is only correct in one. Every case here asks the same question — after
 * the step, is the content pixel that was under the fingers still under them?
 *
 * All view pixels; see [pinchStep] for why no density appears.
 */
class PinchZoomTest {

    /** Where the content point that started under [focus] ends up, relative to the viewport. */
    private fun focusAfter(
        zoom: Float,
        scrollX: Float,
        scrollY: Float,
        focus: Offset,
        pan: Offset,
        zoomChange: Float,
    ): Offset {
        val step = pinchStep(zoom, scrollX, scrollY, focus, pan, zoomChange)
        val scale = step.zoom / zoom
        return Offset(
            (scrollX + focus.x) * scale - (scrollX + step.dx),
            (scrollY + focus.y) * scale - (scrollY + step.dy),
        )
    }

    @Test
    fun `zooming keeps the point between the fingers under them`() {
        val focus = Offset(300f, 500f)

        val landed = focusAfter(
            zoom = 1f,
            scrollX = 200f,
            scrollY = 900f,
            focus = focus,
            pan = Offset.Zero,
            zoomChange = 2f,
        )

        assertEquals(focus.x, landed.x, 0.01f)
        assertEquals(focus.y, landed.y, 0.01f)
    }

    @Test
    fun `it holds at the top-left corner of an unscrolled page too`() {
        // The degenerate case that passes whatever the arithmetic is, which is exactly why the
        // scrolled case above is the one that matters — this is here so a fix for one is not a
        // regression in the other.
        val landed = focusAfter(1f, 0f, 0f, Offset.Zero, Offset.Zero, 2f)

        assertEquals(0f, landed.x, 0.01f)
        assertEquals(0f, landed.y, 0.01f)
    }

    @Test
    fun `moving both fingers takes the page with them`() {
        val focus = Offset(400f, 400f)
        val pan = Offset(60f, -25f)

        val landed = focusAfter(1.5f, 120f, 340f, focus, pan, zoomChange = 1f)

        // The fingers ended at focus + pan, and so should what they were holding.
        assertEquals(focus.x + pan.x, landed.x, 0.01f)
        assertEquals(focus.y + pan.y, landed.y, 0.01f)
    }

    @Test
    fun `spreading and sliding at once is one movement, not two`() {
        val focus = Offset(250f, 610f)
        val pan = Offset(-40f, 30f)

        val landed = focusAfter(1f, 500f, 500f, focus, pan, zoomChange = 1.4f)

        assertEquals(focus.x + pan.x, landed.x, 0.01f)
        assertEquals(focus.y + pan.y, landed.y, 0.01f)
    }

    @Test
    fun `a pinch stops at the ends of the zoom range`() {
        assertEquals(
            ViewSettings.MAX_ZOOM,
            pinchStep(ViewSettings.MAX_ZOOM, 0f, 0f, Offset(10f, 10f), Offset.Zero, 2f).zoom,
        )
        assertEquals(
            ViewSettings.MIN_ZOOM,
            pinchStep(ViewSettings.MIN_ZOOM, 0f, 0f, Offset(10f, 10f), Offset.Zero, 0.5f).zoom,
        )
    }

    /**
     * Clamping has to reach the scroll as well. A pinch that keeps spreading at 400% would otherwise
     * go on scrolling toward a magnification it is never going to get, and the page would crawl
     * sideways under fingers that are not asking it to.
     */
    @Test
    fun `a pinch past the end of the range stops scrolling too`() {
        val step = pinchStep(
            zoom = ViewSettings.MAX_ZOOM,
            scrollX = 800f,
            scrollY = 1200f,
            focus = Offset(300f, 400f),
            pan = Offset.Zero,
            zoomChange = 2f,
        )

        assertEquals(0f, step.dx, 0.01f)
        assertEquals(0f, step.dy, 0.01f)
    }

    /** Two fingers resting still are not a gesture, and must not nudge the page. */
    @Test
    fun `holding still changes nothing`() {
        val step = pinchStep(2f, 640f, 480f, Offset(100f, 100f), Offset.Zero, zoomChange = 1f)

        assertEquals(2f, step.zoom)
        assertEquals(0f, step.dx, 0.01f)
        assertEquals(0f, step.dy, 0.01f)
    }
}
