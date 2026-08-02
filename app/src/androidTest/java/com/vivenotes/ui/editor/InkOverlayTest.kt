package com.vivenotes.ui.editor

import android.graphics.Matrix
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipe
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.EraserMode
import com.vivenotes.data.EraserSettings
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkLassoMove
import com.vivenotes.ink.InkLassoResize
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageStroke
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
    private var lassoMove: InkLassoMove? = null
    private var lassoResize: InkLassoResize? = null
    private var deletedIds = emptySet<String>()
    private var recolorArgb: Int? = null
    private var requestedPaste: InkPoint? = null

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
        lassoing: Boolean = false,
        eraser: EraserSettings = EraserSettings(),
        pageStrokes: List<PageStroke> = emptyList(),
        hasInkClipboard: Boolean = false,
    ) {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.fillMaxSize()) {
                    InkOverlay(
                        strokes = pageStrokes,
                        brush = if (lassoing) null else Brush.createWithColorIntArgb(
                            family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
                            colorIntArgb = android.graphics.Color.BLACK,
                            size = 5f,
                            epsilon = 0.25f,
                        ),
                        erasing = erasing,
                        lassoing = lassoing,
                        eraser = eraser,
                        allowFinger = allowFinger,
                        pageToView = { Matrix() },
                        onStrokeFinished = { strokes++ },
                        onObjectErase = { objectEraseCalls++ },
                        onPartialErase = { partialErases++ },
                        onMoveSelection = { lassoMove = it },
                        onResizeSelection = { lassoResize = it },
                        onDeleteSelection = { deletedIds = it },
                        onRecolorSelection = { _, color -> recolorArgb = color },
                        hasInkClipboard = hasInkClipboard,
                        onRequestPaste = { requestedPaste = it },
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

    @Test
    fun aFingerDoubleTapRequestsPasteWhileThePenOverlayOwnsTouch() {
        setOverlay(allowFinger = false, hasInkClipboard = true)

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            doubleClick(Offset(160f, 180f))
        }
        compose.waitForIdle()

        assertEquals(160f, requestedPaste?.x ?: Float.NaN, 1f)
        assertEquals(180f, requestedPaste?.y ?: Float.NaN, 1f)
        assertEquals("the paste gesture drew ink", 0, strokes)
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
    fun eraserPublishesItsExactCursorAndCutGeometryBeforePointerUp() {
        val ink = PageStroke(
            "stroke",
            InkCodec.eraseMask(
                MutableStrokeInputBatch().apply {
                    add(InputToolType.UNKNOWN, 40f, 100f, 0L)
                    add(InputToolType.UNKNOWN, 160f, 100f, 10L)
                }.toImmutable(),
                6f,
            ),
        )
        val gesture = EraseGesture()
        var finished = 0
        val identity = Matrix()

        gesture.handle(motion(MotionEvent.ACTION_DOWN, 100f, 75f), identity, 18f) { finished++ }
        gesture.handle(motion(MotionEvent.ACTION_MOVE, 100f, 125f), identity, 18f) { finished++ }

        val indicator = gesture.indicator
        assertNotNull("eraser size cursor was not visible during contact", indicator)
        assertEquals(100f, indicator!!.center.x, 0.001f)
        assertEquals(125f, indicator.center.y, 0.001f)
        assertEquals(18f, indicator.diameterDp, 0.001f)
        val liveMask = gesture.previewMask
        assertNotNull("eraser did not expose geometry until release", liveMask)
        val normalPreview = listOf(ink).previewErase(liveMask!!, EraserMode.Normal)
        assertTrue("normal eraser did not cut the stroke during the drag", normalPreview.size >= 2)
        assertTrue(
            "object eraser did not remove the touched object during the drag",
            listOf(ink).previewErase(liveMask, EraserMode.Object).isEmpty(),
        )
        assertEquals("live preview persisted before release", 0, finished)

        gesture.handle(motion(MotionEvent.ACTION_UP, 100f, 125f), identity, 18f) { finished++ }

        assertEquals("one drag must still persist as one undoable erase", 1, finished)
        assertFalse("finger cursor remained after release", gesture.indicator != null)
    }

    @Test
    fun lassoSelectsThenMovesInkEvenWhenFingerDrawingIsOff() {
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.UNKNOWN, 90f, 100f, 0L)
            add(InputToolType.UNKNOWN, 110f, 100f, 10L)
        }.toImmutable()
        val ink = PageStroke("stroke", InkCodec.eraseMask(inputs, 6f))
        setOverlay(
            allowFinger = false,
            lassoing = true,
            pageStrokes = listOf(ink),
        )
        val overlay = compose.onNodeWithTag(INK_OVERLAY_TAG)

        overlay.performTouchInput {
            down(Offset(70f, 70f))
            moveTo(Offset(130f, 70f))
            moveTo(Offset(130f, 130f))
            moveTo(Offset(70f, 130f))
            moveTo(Offset(70f, 70f))
            up()
        }
        overlay.performTouchInput {
            swipe(Offset(100f, 100f), Offset(180f, 150f), durationMillis = 200L)
        }
        compose.waitForIdle()

        assertEquals(setOf("stroke"), lassoMove?.targetIds)
        assertEquals(80f, lassoMove!!.dx, 2f)
        assertEquals(50f, lassoMove!!.dy, 2f)
        assertEquals("lasso should not pan the page", 0f, dragged, 0.001f)
    }

    @Test
    fun aCompletedInkSelectionShowsTheReferenceStyleActions() {
        val first = PageStroke(
            "first",
            InkCodec.eraseMask(
                MutableStrokeInputBatch().apply {
                    add(InputToolType.UNKNOWN, 90f, 100f, 0L)
                    add(InputToolType.UNKNOWN, 110f, 100f, 10L)
                }.toImmutable(),
                6f,
            ),
        )
        val second = PageStroke(
            "second",
            InkCodec.eraseMask(
                MutableStrokeInputBatch().apply {
                    add(InputToolType.UNKNOWN, 150f, 100f, 0L)
                    add(InputToolType.UNKNOWN, 170f, 100f, 10L)
                }.toImmutable(),
                6f,
            ),
        )
        setOverlay(allowFinger = false, lassoing = true, pageStrokes = listOf(first, second))

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(60f, 60f))
            moveTo(Offset(200f, 60f))
            moveTo(Offset(200f, 140f))
            moveTo(Offset(60f, 140f))
            up()
        }
        compose.waitForIdle()

        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_COLOR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_COLOR_TAG).performClick()
        compose.onNodeWithContentDescription("White").assertIsDisplayed()
        compose.onNodeWithContentDescription("Black").assertIsDisplayed().performClick()
        assertEquals(0xFF000000.toInt(), recolorArgb)
        compose.onNodeWithTag(OBJECT_COPY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_GROUP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_DELETE_TAG).assertIsDisplayed().performClick()
        assertEquals(setOf("first", "second"), deletedIds)
        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertDoesNotExist()
    }

    @Test
    fun groupIsHiddenForASingleSelectedObject() {
        val ink = PageStroke(
            "only",
            InkCodec.eraseMask(
                MutableStrokeInputBatch().apply {
                    add(InputToolType.UNKNOWN, 90f, 100f, 0L)
                    add(InputToolType.UNKNOWN, 110f, 100f, 10L)
                }.toImmutable(),
                6f,
            ),
        )
        setOverlay(allowFinger = false, lassoing = true, pageStrokes = listOf(ink))

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(70f, 70f))
            moveTo(Offset(130f, 70f))
            moveTo(Offset(130f, 130f))
            moveTo(Offset(70f, 130f))
            up()
        }
        compose.waitForIdle()

        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_GROUP_TAG).assertDoesNotExist()
    }

    @Test
    fun draggingASelectionCornerRequestsAResize() {
        val ink = PageStroke(
            "only",
            InkCodec.eraseMask(
                MutableStrokeInputBatch().apply {
                    add(InputToolType.UNKNOWN, 90f, 100f, 0L)
                    add(InputToolType.UNKNOWN, 110f, 100f, 10L)
                }.toImmutable(),
                6f,
            ),
        )
        setOverlay(allowFinger = false, lassoing = true, pageStrokes = listOf(ink))
        val overlay = compose.onNodeWithTag(INK_OVERLAY_TAG)
        overlay.performTouchInput {
            down(Offset(70f, 70f))
            moveTo(Offset(130f, 70f))
            moveTo(Offset(130f, 130f))
            moveTo(Offset(70f, 130f))
            up()
        }
        compose.waitForIdle()

        overlay.performTouchInput {
            down(Offset(113f, 103f))
            moveTo(Offset(160f, 145f))
            up()
        }
        compose.waitForIdle()

        assertEquals(setOf("only"), lassoResize?.targetIds)
        assertTrue(lassoResize!!.scaleX > 1f)
        assertTrue(lassoResize!!.scaleY > 1f)
    }

    @Test
    fun lassoMoveAndResizeExposeLivePreviewBeforeRelease() {
        val ink = PageStroke(
            "only",
            InkCodec.eraseMask(
                MutableStrokeInputBatch().apply {
                    add(InputToolType.UNKNOWN, 90f, 100f, 0L)
                    add(InputToolType.UNKNOWN, 110f, 100f, 10L)
                }.toImmutable(),
                6f,
            ),
        )
        val gesture = LassoGesture()
        val identity = Matrix()
        val noMove: (InkLassoMove) -> Unit = {}
        val noResize: (InkLassoResize) -> Unit = {}

        gesture.handle(motion(MotionEvent.ACTION_DOWN, 70f, 70f), identity, listOf(ink), noMove, noResize)
        val beforeTrace = gesture.renderRevision
        gesture.handle(motion(MotionEvent.ACTION_MOVE, 130f, 70f), identity, listOf(ink), noMove, noResize)
        assertTrue("lasso samples did not invalidate the live trace", gesture.renderRevision > beforeTrace)
        assertTrue(gesture.drawingPath().size > 1)
        gesture.handle(motion(MotionEvent.ACTION_MOVE, 130f, 130f), identity, listOf(ink), noMove, noResize)
        gesture.handle(motion(MotionEvent.ACTION_MOVE, 70f, 130f), identity, listOf(ink), noMove, noResize)
        gesture.handle(motion(MotionEvent.ACTION_UP, 70f, 70f), identity, listOf(ink), noMove, noResize)
        assertTrue("the completed lasso trace remained visible", gesture.drawingPath().isEmpty())

        val selected = gesture.selectionBounds()!!
        gesture.handle(motion(MotionEvent.ACTION_DOWN, selected.center.x, selected.center.y), identity, listOf(ink), noMove, noResize)
        gesture.handle(
            motion(MotionEvent.ACTION_MOVE, selected.center.x + 30f, selected.center.y + 25f),
            identity,
            listOf(ink),
            noMove,
            noResize,
        )
        val moving = gesture.selectionBounds()!!
        assertEquals(selected.center.x + 30f, moving.center.x, 0.01f)
        assertEquals(selected.center.y + 25f, moving.center.y, 0.01f)
        gesture.handle(
            motion(MotionEvent.ACTION_UP, selected.center.x + 30f, selected.center.y + 25f),
            identity,
            listOf(ink),
            noMove,
            noResize,
        )

        val moved = gesture.selectionBounds()!!
        gesture.handle(motion(MotionEvent.ACTION_DOWN, moved.right, moved.bottom), identity, listOf(ink), noMove, noResize)
        gesture.handle(
            motion(MotionEvent.ACTION_MOVE, moved.right + 25f, moved.bottom + 20f),
            identity,
            listOf(ink),
            noMove,
            noResize,
        )
        val resizing = gesture.selectionBounds()!!
        assertTrue("corner resize had no live horizontal preview", resizing.right > moved.right)
        assertTrue("corner resize had no live vertical preview", resizing.bottom > moved.bottom)
    }

    private fun motion(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 16L, action, x, y, 0)

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
