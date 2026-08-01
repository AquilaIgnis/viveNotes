package st.unamedtba.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Zoom arithmetic, kept out of the ViewModel so it can be checked without an Android runtime.
 * Every case here is one the buttons hit in ordinary use.
 */
class ViewSettingsTest {

    @Test
    fun `stepping moves to the neighbouring preset`() {
        assertEquals(1.25f, ViewSettings.zoomStepUp(1f))
        assertEquals(0.75f, ViewSettings.zoomStepDown(1f))
    }

    @Test
    fun `stepping from a value between presets lands on the next one either way`() {
        // Page Width produces exactly this: whatever fits, not a round number.
        assertEquals(1.25f, ViewSettings.zoomStepUp(1.13f))
        assertEquals(1f, ViewSettings.zoomStepDown(1.13f))
    }

    @Test
    fun `stepping stops at the ends rather than wrapping or standing still`() {
        assertEquals(ViewSettings.MAX_ZOOM, ViewSettings.zoomStepUp(ViewSettings.MAX_ZOOM))
        assertEquals(ViewSettings.MIN_ZOOM, ViewSettings.zoomStepDown(ViewSettings.MIN_ZOOM))
    }

    @Test
    fun `page width divides the window by the page`() {
        assertEquals(0.5f, ViewSettings.fitZoom(viewportWidthDp = 600f, contentWidthDp = 1200f))
        assertEquals(1.5f, ViewSettings.fitZoom(viewportWidthDp = 1200f, contentWidthDp = 800f))
    }

    @Test
    fun `page width is clamped to what the zoom control can express`() {
        // A postcard in a wide window would otherwise ask for a zoom no preset can step back from.
        assertEquals(ViewSettings.MAX_ZOOM, ViewSettings.fitZoom(4000f, 100f))
        assertEquals(ViewSettings.MIN_ZOOM, ViewSettings.fitZoom(100f, 4000f))
    }

    @Test
    fun `page width does nothing until the canvas has been measured`() {
        assertNull(ViewSettings.fitZoom(0f, 800f))
        assertNull(ViewSettings.fitZoom(800f, 0f))
    }

    @Test
    fun `every step is one the combo box offers`() {
        // The +/- buttons and the drop-down have to agree, or stepping would show a percentage
        // that cannot be chosen back.
        ViewSettings.ZOOM_STEPS.forEach { step ->
            val up = ViewSettings.zoomStepUp(step)
            val down = ViewSettings.zoomStepDown(step)
            assertEquals(true, up in ViewSettings.ZOOM_STEPS)
            assertEquals(true, down in ViewSettings.ZOOM_STEPS)
        }
    }
}
