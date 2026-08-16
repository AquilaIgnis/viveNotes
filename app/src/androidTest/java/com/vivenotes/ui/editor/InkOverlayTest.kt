package com.vivenotes.ui.editor

import android.graphics.Matrix
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
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
import androidx.ink.strokes.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.EraserMode
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkLassoMove
import com.vivenotes.ink.InkLassoResize
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageStroke
import com.vivenotes.data.RulerKind
import com.vivenotes.ink.Ruler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkBounds
import com.vivenotes.model.Outline
import com.vivenotes.model.PageSpace
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.math.abs
import kotlin.math.hypot

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
    private var lastStroke: Stroke? = null
    private var partialErases = 0
    private var objectEraseCalls = 0
    private var lassoMove: InkLassoMove? = null
    private var lassoResize: InkLassoResize? = null
    private var deletedIds = emptySet<String>()
    private var recolorArgb: Int? = null
    private var requestedPaste: InkPoint? = null
    private var spaceCut: SpaceCut? = null

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
        insertingSpace: Boolean = false,
        eraser: EraserSettings = EraserSettings(),
        pageStrokes: List<PageStroke> = emptyList(),
        hasClipboard: Boolean = false,
        ruler: Ruler? = null,
    ) {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.fillMaxSize()) {
                    // The page owns the selection, the gesture and the tooltip now, so the test plays
                    // that part — the same three lines `EditorPane` has.
                    var selection by remember { mutableStateOf<CanvasSelection?>(null) }
                    val lasso = remember { LassoGesture() }
                    InkOverlay(
                        strokes = pageStrokes,
                        selection = selection,
                        onSelect = { selection = it },
                        lassoGesture = lasso,
                        brush = if (lassoing || insertingSpace) null else Brush.createWithColorIntArgb(
                            family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
                            colorIntArgb = android.graphics.Color.BLACK,
                            size = 5f,
                            epsilon = 0.25f,
                        ),
                        erasing = erasing,
                        lassoing = lassoing,
                        shaping = null,
                        insertingSpace = insertingSpace,
                        ruler = ruler,
                        eraser = eraser,
                        allowFinger = allowFinger,
                        pageToView = { Matrix() },
                        onStrokeFinished = {
                            strokes++
                            lastStroke = it
                        },
                        onObjectErase = { objectEraseCalls++ },
                        onPartialErase = { partialErases++ },
                        onInsertSpace = { spaceCut = it },
                        onMoveSelection = { lassoMove = it },
                        onResizeSelection = { lassoResize = it },
                        onDeleteSelection = { deletedIds = it },
                        onRecolorSelection = { _, color -> recolorArgb = color },
                        hasClipboard = hasClipboard,
                        onRequestPaste = { requestedPaste = it },
                        pan = recordingPan,
                        modifier = Modifier.fillMaxSize(),
                    )
                    selection?.takeIf { !it.isEmpty }?.let { held ->
                        ObjectTooltip(
                            swatch = Color.White,
                            selectionBoundsInView = { lasso.previewBoundsInView(held, Matrix()) },
                            viewportSize = IntSize(1000, 1000),
                            onDelete = {
                                deletedIds = held.inkIds
                                selection = null
                            },
                            onCopy = {},
                            onRecolor = { recolorArgb = it },
                            extras = {
                                if (held.isInkOnly && held.inkIds.size > 1) {
                                    GroupAction(isOneGroup = false, onGroup = {}, onUngroup = {})
                                }
                            },
                        )
                    }
                }
            }
        }
    }


    // --- Insert Space (E2) -----------------------------------------------------------------------
    //
    // The overlay's transform is identity in these, so view pixels and page units are the same
    // number and the assertions can be read straight off the gesture.

    @Test
    fun aDownwardDragInsertsVerticalSpaceAtWhereItBegan() {
        setOverlay(allowFinger = true, insertingSpace = true)

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(200f, 300f))
            moveTo(Offset(200f, 360f))
            moveTo(Offset(200f, 420f))
            up()
        }
        compose.waitForIdle()

        val cut = spaceCut ?: error("the drag committed no cut")
        assertEquals(PageSpace.Axis.Vertical, cut.axis)
        assertEquals("the line is where the drag began, not where it ended", 300f, cut.at, 0.5f)
        assertEquals(120f, cut.amount, 0.5f)
    }

    /** Dragging back the other way takes space away, which is the same gesture with a sign. */
    @Test
    fun anUpwardDragClosesSpace() {
        setOverlay(allowFinger = true, insertingSpace = true)

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(200f, 400f))
            moveTo(Offset(200f, 320f))
            up()
        }
        compose.waitForIdle()

        val cut = spaceCut ?: error("the drag committed no cut")
        assertEquals(PageSpace.Axis.Vertical, cut.axis)
        assertEquals(-80f, cut.amount, 0.5f)
    }

    /** The other axis, reached by travelling that way rather than by starting near a page edge. */
    @Test
    fun aSidewaysDragInsertsHorizontalSpace() {
        setOverlay(allowFinger = true, insertingSpace = true)

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(200f, 300f))
            moveTo(Offset(280f, 302f))
            moveTo(Offset(350f, 305f))
            up()
        }
        compose.waitForIdle()

        val cut = spaceCut ?: error("the drag committed no cut")
        assertEquals(PageSpace.Axis.Horizontal, cut.axis)
        assertEquals("a sideways line is placed by x", 200f, cut.at, 0.5f)
        assertEquals(150f, cut.amount, 0.5f)
    }

    /**
     * Once the direction is settled it stays settled. A gesture that could still change its mind
     * halfway would turn a long downward drag into a sideways one on a wobble, and shove the whole
     * page across instead of down.
     */
    @Test
    fun theAxisLocksOnceTheDragHasChosenOne() {
        setOverlay(allowFinger = true, insertingSpace = true)

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(200f, 300f))
            moveTo(Offset(200f, 500f))
            // Further sideways than it ever went down, and far too late to matter.
            moveTo(Offset(600f, 500f))
            up()
        }
        compose.waitForIdle()

        val cut = spaceCut ?: error("the drag committed no cut")
        assertEquals(PageSpace.Axis.Vertical, cut.axis)
        assertEquals(200f, cut.amount, 0.5f)
    }

    /**
     * The band is the affordance: it shows the gap that is about to appear, in the place it will
     * appear, while the pointer is still down. And it is a preview rather than a mark, so it must be
     * gone the moment the gesture ends.
     */
    @Test
    fun theGuideShowsTheGapItIsAboutToOpenAndThenGoes() {
        setInsertSpaceOnWhite()
        val overlay = compose.onNodeWithTag(INK_OVERLAY_TAG)

        overlay.performTouchInput {
            down(Offset(200f, 200f))
            moveTo(Offset(200f, 300f))
        }
        compose.waitForIdle()

        assertTrue("nothing was drawn between the line and the pointer", inkPixels(150, 220, 250, 280) > 0)
        assertEquals("the guide reached back past the line", 0, inkPixels(150, 100, 250, 180))

        overlay.performTouchInput { up() }
        compose.waitForIdle()

        assertEquals("the guide outlived the gesture", 0, inkPixels(150, 220, 250, 280))
    }

    private fun setInsertSpaceOnWhite() {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    InkOverlay(
                        strokes = emptyList(),
                        lassoGesture = remember { LassoGesture() },
                        brush = null,
                        erasing = false,
                        lassoing = false,
                        shaping = null,
                        insertingSpace = true,
                        eraser = EraserSettings(),
                        allowFinger = true,
                        pageToView = { Matrix() },
                        onStrokeFinished = {},
                        onInsertSpace = { spaceCut = it },
                        onPartialErase = {},
                        onObjectErase = {},
                        onMoveSelection = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** A tap says where but not how much, and how much is the entire question. */
    @Test
    fun aTapInsertsNothing() {
        setOverlay(allowFinger = true, insertingSpace = true)

        compose.onNodeWithTag(INK_OVERLAY_TAG).performClick()
        compose.waitForIdle()

        assertEquals(null, spaceCut)
    }

    /**
     * The same bargain the lasso and the shape tool strike: a finger that is not allowed to draw
     * moves the page instead, so it can still be scrolled one-handed with the tool in hand.
     */
    @Test
    fun aDisallowedFingerPansRatherThanInsertingSpace() {
        setOverlay(allowFinger = false, insertingSpace = true)

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals(null, spaceCut)
        assertTrue("the page did not pan", abs(dragged) > 0f)
    }

    // --- the ruler -------------------------------------------------------------------------------

    /**
     * A ruled stroke comes out straight however badly the hand wobbles — `docs/rulerPlan.md` RD5.
     *
     * The one assertion that says the feature works. The gesture below wanders 30px either side of
     * the ruler's edge as it travels; unruled, the recorded stroke follows that wander, and the
     * cross product below is nowhere near zero.
     *
     * The overlay's transform is identity here, so view pixels and page units are the same thing.
     */
    @Test
    fun aWobblingStrokeAlongTheRulerIsRecordedStraight() {
        val ruler = Ruler(
            centerX = 400f,
            centerY = 300f,
            angleRadians = 0f,
            kind = RulerKind.Straight,
            sizeDp = 800f,
        )
        setOverlay(allowFinger = true, ruler = ruler)
        val edge = 300f - Ruler.BAND_DP / 2f

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(120f, edge - 6f))
            (1..8).forEach { step ->
                moveTo(Offset(120f + step * 60f, edge - 6f + if (step % 2 == 0) 26f else -22f))
            }
            up()
        }
        compose.waitForIdle()

        val inputs = lastStroke?.inputs ?: error("the ruled gesture recorded no stroke")
        assertTrue("a stroke needs more than one point to be straight", inputs.size >= 2)

        val first = inputs.get(0)
        val last = inputs.get(inputs.size - 1)
        val length = hypot(last.x - first.x, last.y - first.y)
        assertTrue("the stroke went nowhere", length > 100f)
        (0 until inputs.size).forEach { index ->
            val point = inputs.get(index)
            val cross = (last.x - first.x) * (point.y - first.y) -
                (last.y - first.y) * (point.x - first.x)
            assertEquals("point $index is off the ruler's line", 0f, cross / length, 1.5f)
        }
    }

    /** Start away from it and the ruler is not involved at all — RD5's engagement rule. */
    @Test
    fun aStrokeStartedAwayFromTheRulerIsNotSnapped() {
        val ruler = Ruler(400f, 300f, 0f, RulerKind.Straight, 800f)
        setOverlay(allowFinger = true, ruler = ruler)

        // Well below the band, and travelling further away.
        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(200f, 700f))
            moveTo(Offset(300f, 780f))
            moveTo(Offset(400f, 860f))
            up()
        }
        compose.waitForIdle()

        val inputs = lastStroke?.inputs ?: error("the gesture recorded no stroke")
        val onTheRuler = (0 until inputs.size).count {
            abs(inputs.get(it).y - (300f - Ruler.BAND_DP / 2f)) < 1f
        }
        assertEquals("freehand ink was dragged onto the ruler", 0, onTheRuler)
    }

    /** The semicircle bends a stroke onto its arc instead of a line — every point at the radius. */
    @Test
    fun theSemicircleRecordsItsStrokeOnTheArc() {
        val ruler = Ruler(400f, 500f, 0f, RulerKind.Protractor, 600f)
        setOverlay(allowFinger = true, ruler = ruler)

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(150f, 480f))
            moveTo(Offset(300f, 300f))
            moveTo(Offset(430f, 260f))
            moveTo(Offset(600f, 400f))
            up()
        }
        compose.waitForIdle()

        val inputs = lastStroke?.inputs ?: error("the ruled gesture recorded no stroke")
        (0 until inputs.size).forEach { index ->
            val point = inputs.get(index)
            assertEquals(
                "point $index is off the arc",
                300f,
                hypot(point.x - 400f, point.y - 500f),
                1.5f,
            )
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
        setOverlay(allowFinger = false, hasClipboard = true)

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

    /**
     * The half of this bug that geometry cannot see. A cut highlighter kept correct bounds and a
     * correct hole, and drew as nothing: it is path-rendered, `split` returns pieces with no
     * outlines, and the path renderer draws from outlines. So read the screen, not the mesh.
     */
    @Test
    fun aPartlyErasedHighlighterIsStillOnThePage() {
        val ink = Stroke(
            brush = InkCodec.brushFor(HighlighterSettings()),
            inputs = strokeInputs(60f to 200f, 400f to 200f),
        )
        val mask = InkCodec.eraseMask(strokeInputs(230f to 160f, 230f to 240f), sizeDp = 24f)

        setInkOnWhite(listOf(PageStroke("highlighter", ink)).previewErase(mask, EraserMode.Normal))

        assertTrue("the cut highlighter vanished", inkPixels(60, 185, 200, 215) > 0)
        assertTrue("its far end vanished", inkPixels(260, 185, 400, 215) > 0)
        assertEquals("the eraser cut nothing", 0, inkPixels(224, 185, 236, 215))
    }

    private fun setInkOnWhite(pageStrokes: List<PageStroke>) {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    InkOverlay(
                        strokes = pageStrokes,
                        lassoGesture = remember { LassoGesture() },
                        brush = null,
                        erasing = false,
                        lassoing = false,
                        shaping = null,
                        eraser = EraserSettings(),
                        allowFinger = true,
                        pageToView = { Matrix() },
                        onStrokeFinished = {},
                        onPartialErase = {},
                        onObjectErase = {},
                        onMoveSelection = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** Ink pixels inside a page-space rectangle, the page being drawn 1:1 onto the screen here. */
    private fun inkPixels(left: Int, top: Int, right: Int, bottom: Int): Int {
        val pixels = compose.onNodeWithTag(INK_OVERLAY_TAG).captureToImage().toPixelMap()
        var count = 0
        for (x in left until minOf(right, pixels.width)) {
            for (y in top until minOf(bottom, pixels.height)) {
                if (pixels[x, y] != Color.White) count++
            }
        }
        return count
    }

    private fun strokeInputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) ->
            add(InputToolType.UNKNOWN, x, y, index * 10L)
        }
    }.toImmutable()

    @Test
    fun lassoSelectsThenMovesInkWhenFingerInputIsEnabled() {
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.UNKNOWN, 90f, 100f, 0L)
            add(InputToolType.UNKNOWN, 110f, 100f, 10L)
        }.toImmutable()
        val ink = PageStroke("stroke", InkCodec.eraseMask(inputs, 6f))
        setOverlay(
            allowFinger = true,
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
    fun aFingerPansInsteadOfLassoingWhenFingerInputIsOff() {
        setOverlay(allowFinger = false, lassoing = true)

        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue("lasso claimed a finger drag instead of panning", dragged != 0f)
        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertDoesNotExist()
        assertEquals(null, lassoMove)
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
        setOverlay(allowFinger = true, lassoing = true, pageStrokes = listOf(first, second))

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
        setOverlay(allowFinger = true, lassoing = true, pageStrokes = listOf(ink))

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
        setOverlay(allowFinger = true, lassoing = true, pageStrokes = listOf(ink))
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
        val lasso = LassoHarness(listOf(ink))

        lasso.send(MotionEvent.ACTION_DOWN, 70f, 70f)
        val beforeTrace = lasso.gesture.renderRevision
        lasso.send(MotionEvent.ACTION_MOVE, 130f, 70f)
        assertTrue(
            "lasso samples did not invalidate the live trace",
            lasso.gesture.renderRevision > beforeTrace,
        )
        assertTrue(lasso.gesture.drawingPath().size > 1)
        lasso.send(MotionEvent.ACTION_MOVE, 130f, 130f)
        lasso.send(MotionEvent.ACTION_MOVE, 70f, 130f)
        lasso.send(MotionEvent.ACTION_UP, 70f, 70f)
        assertTrue("the completed lasso trace remained visible", lasso.gesture.drawingPath().isEmpty())
        assertEquals("the loop did not take the stroke", setOf("only"), lasso.selection?.inkIds)

        val selected = lasso.bounds()!!
        lasso.send(MotionEvent.ACTION_DOWN, selected.center.x, selected.center.y)
        lasso.send(MotionEvent.ACTION_MOVE, selected.center.x + 30f, selected.center.y + 25f)
        val moving = lasso.bounds()!!
        assertEquals(selected.center.x + 30f, moving.center.x, 0.01f)
        assertEquals(selected.center.y + 25f, moving.center.y, 0.01f)
        lasso.send(MotionEvent.ACTION_UP, selected.center.x + 30f, selected.center.y + 25f)

        val moved = lasso.bounds()!!
        lasso.send(MotionEvent.ACTION_DOWN, moved.right, moved.bottom)
        lasso.send(MotionEvent.ACTION_MOVE, moved.right + 25f, moved.bottom + 20f)
        val resizing = lasso.bounds()!!
        assertTrue("corner resize had no live horizontal preview", resizing.right > moved.right)
        assertTrue("corner resize had no live vertical preview", resizing.bottom > moved.bottom)
    }

    // -----------------------------------------------------------------------------------------
    // One loop, both kinds — AD7's first row
    // -----------------------------------------------------------------------------------------

    private fun stroke(id: String, x: Float): PageStroke = PageStroke(
        id,
        InkCodec.eraseMask(
            MutableStrokeInputBatch().apply {
                add(InputToolType.UNKNOWN, x, 100f, 0L)
                add(InputToolType.UNKNOWN, x + 20f, 100f, 10L)
            }.toImmutable(),
            6f,
        ),
    )

    private fun square(id: String, left: Float, top: Float, side: Float): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = id,
            kind = ShapeKind.Rectangle,
            segments = seedSegments(
                ShapeKind.Rectangle, left, top, left + side, top + side,
            ) { "$id-seg-${'$'}{next++}" },
        ).withRecomputedBounds()
    }

    /** Traces a rectangular loop through the four corners given, in page units. */
    private fun LassoHarness.loop(left: Float, top: Float, right: Float, bottom: Float) {
        send(MotionEvent.ACTION_DOWN, left, top)
        send(MotionEvent.ACTION_MOVE, right, top)
        send(MotionEvent.ACTION_MOVE, right, bottom)
        send(MotionEvent.ACTION_MOVE, left, bottom)
        send(MotionEvent.ACTION_UP, left, top)
    }

    @Test
    fun aLoopAroundAShapeSelectsIt() {
        // The gap AD7 recorded: `InkLassoSelection` named stroke ids, so a loop round a shape
        // returned nothing at all.
        val lasso = LassoHarness(strokes = emptyList(), shapes = listOf(square("box", 60f, 60f, 40f)))

        lasso.loop(20f, 20f, 160f, 160f)

        assertEquals("the loop did not take the shape", setOf("box"), lasso.selection?.shapeIds)
        assertTrue("a shapes-only loop reported ink", lasso.selection!!.inkIds.isEmpty())
        assertTrue(lasso.selection!!.isShapeOnly)
    }

    @Test
    fun oneLoopTakesAStrokeAndAShapeTogether() {
        // "One lasso, one selection, however many kinds of thing are in it."
        val lasso = LassoHarness(
            strokes = listOf(stroke("line", 30f)),
            shapes = listOf(square("box", 60f, 60f, 40f)),
        )

        lasso.loop(10f, 10f, 200f, 200f)

        val held = lasso.selection!!
        assertEquals(setOf("line"), held.inkIds)
        assertEquals(setOf("box"), held.shapeIds)
        assertTrue("a mixed selection claimed to be one kind", !held.isInkOnly && !held.isShapeOnly)
        // The rectangle has to cover both, or the handles would describe only half of what moves.
        assertTrue(held.bounds.left <= 30f && held.bounds.right >= 100f)
    }

    @Test
    fun aHalfEnclosedShapeIsLeftAloneJustAsAHalfEnclosedStrokeIs() {
        val lasso = LassoHarness(
            strokes = listOf(stroke("line", 30f)),
            shapes = listOf(square("box", 60f, 60f, 80f)),
        )

        // Around the stroke, clipping the shape's right half.
        lasso.loop(10f, 10f, 100f, 200f)

        assertEquals(setOf("line"), lasso.selection?.inkIds)
        assertTrue("a partly circled shape was taken whole", lasso.selection!!.shapeIds.isEmpty())
    }

    @Test
    fun draggingAMixedSelectionMovesBothKinds() {
        val lasso = LassoHarness(
            strokes = listOf(stroke("line", 30f)),
            shapes = listOf(square("box", 60f, 60f, 40f)),
        )
        lasso.loop(10f, 10f, 200f, 200f)
        val before = lasso.bounds()!!

        lasso.send(MotionEvent.ACTION_DOWN, before.center.x, before.center.y)
        lasso.send(MotionEvent.ACTION_MOVE, before.center.x + 40f, before.center.y + 30f)
        lasso.send(MotionEvent.ACTION_UP, before.center.x + 40f, before.center.y + 30f)

        assertEquals("the ink half of the move never fired", 1, lasso.inkMoves)
        assertEquals("the shape half of the move never fired", setOf("box"), lasso.movedShapes)
        assertEquals(40f, lasso.shapeDx, 0.01f)
        assertEquals(30f, lasso.shapeDy, 0.01f)
    }

    /**
     * Stands in for `EditorPane`: it holds the selection the gesture reads and writes.
     *
     * Which is the point of the split — the gesture owns how far the finger has travelled, the page
     * owns what is selected, and a shape can be in that selection without the gesture knowing.
     */
    private class LassoHarness(
        private val strokes: List<PageStroke>,
        private val shapes: List<Outline.Shape> = emptyList(),
    ) {
        val gesture = LassoGesture()
        var selection: CanvasSelection? = null
            private set
        var inkMoves = 0
            private set
        var movedShapes: Set<String> = emptySet()
            private set
        var shapeDx = 0f
            private set
        var shapeDy = 0f
            private set

        fun send(action: Int, x: Float, y: Float) {
            gesture.handle(
                event = MotionEvent.obtain(0L, 16L, action, x, y, 0),
                toPage = Matrix(),
                strokes = strokes,
                shapes = shapes,
                selection = selection,
                onSelect = { selection = it },
                onMove = { inkMoves++ },
                onResize = {},
                onMoveShapes = { ids, dx, dy ->
                    movedShapes = ids
                    shapeDx = dx
                    shapeDy = dy
                },
            )
        }

        fun bounds(): InkBounds? = gesture.previewBounds(selection)
    }

    private fun motion(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 16L, action, x, y, 0)

    /** [motion] with a tool type and a controllable clock, which the double-tap window needs. */
    private fun toolMotion(
        action: Int,
        x: Float,
        y: Float,
        toolType: Int,
        eventTime: Long,
    ): MotionEvent {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                this.toolType = toolType
            },
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        return MotionEvent.obtain(
            0L,
            eventTime,
            action,
            1,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                InputDevice.SOURCE_STYLUS
            } else {
                InputDevice.SOURCE_TOUCHSCREEN
            },
            0,
        )
    }

    /** Two stationary taps of [toolType], spaced to land inside the double-tap window. */
    private fun doubleTapWith(gesture: DoubleTapGesture, toolType: Int, acceptStylus: Boolean): Boolean {
        var fired = false
        listOf(
            MotionEvent.ACTION_DOWN to 0L,
            MotionEvent.ACTION_UP to 10L,
            MotionEvent.ACTION_DOWN to 100L,
            MotionEvent.ACTION_UP to 110L,
        ).forEach { (action, at) ->
            val event = toolMotion(action, 120f, 140f, toolType, at)
            if (gesture.observe(event, acceptStylus = acceptStylus)) fired = true
            event.recycle()
        }
        return fired
    }

    private fun doubleTapGesture() = DoubleTapGesture(
        minimumIntervalMillis = 40L,
        maximumIntervalMillis = 300L,
        touchSlop = 8f,
    )

    /** The ask: with the lasso up, the pen may raise the paste button — it deposits nothing. */
    @Test
    fun aStylusDoubleTapIsADoubleTapWhileTheLassoIsActive() {
        assertTrue(
            "the pen could not raise paste under the lasso",
            doubleTapWith(doubleTapGesture(), MotionEvent.TOOL_TYPE_STYLUS, acceptStylus = true),
        )
    }

    /**
     * And the reason it is scoped to the lasso: under a brush those two taps are two marks, so
     * offering to paste on top of them would fire while someone is drawing.
     */
    @Test
    fun aStylusDoubleTapIsInkWhileADrawingToolIsActive() {
        assertFalse(
            "the pen raised paste while a drawing tool owned the page",
            doubleTapWith(doubleTapGesture(), MotionEvent.TOOL_TYPE_STYLUS, acceptStylus = false),
        )
    }

    /** The finger never depended on the tool, and must not start depending on it now. */
    @Test
    fun aFingerDoubleTapStillCountsWithoutTheLasso() {
        assertTrue(
            doubleTapWith(doubleTapGesture(), MotionEvent.TOOL_TYPE_FINGER, acceptStylus = false),
        )
    }

    /** A flipped pen is erasing, not gesturing, even though the lasso is what is selected. */
    @Test
    fun aFlippedPenDoubleTapIsNotAPasteGesture() {
        assertFalse(
            doubleTapWith(doubleTapGesture(), MotionEvent.TOOL_TYPE_ERASER, acceptStylus = true),
        )
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
