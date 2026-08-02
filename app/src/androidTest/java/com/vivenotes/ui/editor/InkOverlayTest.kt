package com.vivenotes.ui.editor

import android.graphics.Matrix
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.EraserMode
import com.vivenotes.data.EraserSettings
import com.vivenotes.ui.theme.ViveNotesTheme

/**
 * Who gets a gesture on the drawing surface.
 *
 * This is the seam that broke twice, both times silently. A hit pointer node blocks its siblings
 * from seeing the event at all, so the overlay declining a touch does not hand it to the scroll
 * container underneath — it hands it to nobody, and the page stops panning the moment a pen is in
 * hand. The overlay therefore has to pan the page itself, and this checks that it does.
 */
class InkOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private var dragged = 0f
    private var flung = false
    private var strokes = 0
    private var partialErases = 0
    private var objectEraseCalls = 0

    private val recordingPan = object : CanvasPan {
        override fun by(dx: Float, dy: Float) {
            dragged += dy
        }

        override fun fling(vx: Float, vy: Float) {
            flung = true
        }
    }

    private fun setOverlay(
        allowFinger: Boolean,
        erasing: Boolean = false,
        eraser: EraserSettings = EraserSettings(),
    ) {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.fillMaxSize()) {
                    InkOverlay(
                        strokes = emptyList(),
                        brush = Brush.createWithColorIntArgb(
                            family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
                            colorIntArgb = android.graphics.Color.BLACK,
                            size = 5f,
                            epsilon = 0.25f,
                        ),
                        erasing = erasing,
                        eraser = eraser,
                        allowFinger = allowFinger,
                        pageToView = { Matrix() },
                        onStrokeFinished = { strokes++ },
                        onErase = { objectEraseCalls++ },
                        onPartialErase = { partialErases++ },
                        pan = recordingPan,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** Stylus-only is the default, and it is the setting that has to leave the page scrollable. */
    @Test
    fun aFingerPansThePageWhenItMayNotDraw() {
        setOverlay(allowFinger = false)
        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue("a finger that cannot draw did not pan the page", dragged != 0f)
        assertEquals("a finger that cannot draw laid down ink", 0, strokes)
    }

    @Test
    fun releasingAPanFlingsIt() {
        setOverlay(allowFinger = false)
        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue("letting go of a pan should fling it", flung)
    }

    /** With finger drawing on, the same gesture is ink — so it must *not* also move the page. */
    @Test
    fun aFingerThatMayDrawDoesNotPanThePage() {
        setOverlay(allowFinger = true)
        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals("drawing with a finger also scrolled the page", 0f, dragged, 0.001f)
    }

    /** The eraser is not a pen, but it is still a tool: a finger holding it must not pan either. */
    @Test
    fun anErasingFingerThatMayDrawDoesNotPanThePage() {
        setOverlay(allowFinger = true, erasing = true)
        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals(0f, dragged, 0.001f)
        assertEquals("normal erase should finish as one geometric mask", 1, partialErases)
        assertEquals(0, objectEraseCalls)
    }

    @Test
    fun objectModeUsesWholeStrokeHitTestingInsteadOfAPartialMask() {
        setOverlay(
            allowFinger = true,
            erasing = true,
            eraser = EraserSettings(mode = EraserMode.Object),
        )
        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue(objectEraseCalls > 0)
        assertEquals(0, partialErases)
    }
}
