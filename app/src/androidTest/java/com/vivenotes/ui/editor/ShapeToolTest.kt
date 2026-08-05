package com.vivenotes.ui.editor

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.model.ink.LineType
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.ShapeSettings
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkPoint
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.PAGE_BASIC
import com.vivenotes.model.ink.PAGE_SOLID
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.panel.ShapePanelContent
import com.vivenotes.ui.panel.ShapePanelTags
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Insert Shape — `docs/inkPlan.md` §5.4.
 *
 * The button has two homes and one armed tool behind them, which is exactly the shape of code where
 * one tab silently stops arming anything. The pane's grid is the other risk: sixteen chips whose
 * only difference is the geometry they draw, so picking one and getting its neighbour would look
 * entirely plausible on screen.
 */
class ShapeToolTest {

    @get:Rule
    val compose = createComposeRule()

    private var selected: DrawTool? = null
    private var changed: ShapeSettings? = null
    private var inserted: Pair<InkPoint, InkPoint>? = null

    // -----------------------------------------------------------------------------------------
    // The button, on both tabs
    // -----------------------------------------------------------------------------------------

    private fun setDrawTab(tool: DrawTool = DrawTool.None, pageOpen: Boolean = true) {
        compose.setContent {
            ViveNotesTheme {
                DrawTab(
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    tool = tool,
                    allowFinger = false,
                    actions = DrawActions(
                        selectTool = { selected = it },
                        updatePen = { _, _ -> },
                        updateEraser = {},
                        updateShape = { changed = it },
                        setDrawWithFinger = {},
                    ),
                    pageOpen = pageOpen,
                )
            }
        }
    }

    private fun setInsertTab(tool: DrawTool = DrawTool.None, pageOpen: Boolean = true) {
        compose.setContent {
            ViveNotesTheme {
                InsertTab(
                    selection = SelectionState(),
                    onCommand = {},
                    pageOpen = pageOpen,
                    shape = ShapeSettings(),
                    palette = PEN_COLORS,
                    tool = tool,
                    onSelectTool = { selected = it },
                    onChangeShape = { changed = it },
                )
            }
        }
    }

    @Test
    fun theDrawTabArmsTheShapeTool() {
        setDrawTab()
        compose.onNodeWithTag(SHAPE_BUTTON_TAG).performClick()

        assertEquals(DrawTool.Shape, selected)
    }

    @Test
    fun theInsertTabArmsTheSameShapeTool() {
        // The point of the feature having two homes: one tool, not one per tab.
        setInsertTab()
        compose.onNodeWithTag(SHAPE_BUTTON_TAG).performClick()

        assertEquals(DrawTool.Shape, selected)
    }

    @Test
    fun tappingTheArmedToolOpensItsSettings() {
        setDrawTab(tool = DrawTool.Shape)
        compose.onNodeWithTag(SHAPE_BUTTON_TAG).performClick()

        compose.onNodeWithText("Shape").assertIsDisplayed()
        compose.onNodeWithTag(ShapePanelTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun holdingTheButtonBothArmsItAndOpensItsSettings() {
        setDrawTab()
        compose.onNodeWithTag(SHAPE_BUTTON_TAG).performTouchInput { longClick() }

        assertEquals("holding did not pick the tool up", DrawTool.Shape, selected)
        compose.onNodeWithTag(ShapePanelTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun withNoPageOpenTheButtonIsInert() {
        // Same treatment Equation gets beside it: shown, and plainly not usable.
        setInsertTab(pageOpen = false)
        compose.onNodeWithTag(SHAPE_BUTTON_TAG).performClick()

        assertNull("a shape was armed with no page to draw it on", selected)
    }

    // -----------------------------------------------------------------------------------------
    // The pane
    // -----------------------------------------------------------------------------------------

    private fun setPanel(shape: ShapeSettings = ShapeSettings()) {
        compose.setContent {
            ViveNotesTheme {
                var current by remember { mutableStateOf(shape) }
                Column {
                    ShapePanelContent(
                        shape = current,
                        palette = PEN_COLORS,
                        onChange = {
                            current = it
                            changed = it
                        },
                    )
                }
            }
        }
    }

    @Test
    fun everyBasicShapeIsOnThefirstPageAndPicksItself() {
        setPanel()
        ShapeKind.onPage(PAGE_BASIC).forEach { kind ->
            compose.onNodeWithTag(ShapePanelTags.kind(kind)).assertIsDisplayed()
        }

        compose.onNodeWithTag(ShapePanelTags.kind(ShapeKind.Hexagon)).performClick()
        assertEquals(ShapeKind.Hexagon, changed?.kind)
    }

    @Test
    fun theSecondPageHoldsTheSolidsAndNothingCrossedOut() {
        setPanel()
        compose.onNodeWithTag(ShapePanelTags.page(PAGE_SOLID)).performClick()

        val solids = ShapeKind.onPage(PAGE_SOLID)
        assertEquals(6, solids.size)
        solids.forEach { compose.onNodeWithTag(ShapePanelTags.kind(it)).assertIsDisplayed() }
        // Page 1's shapes are gone rather than merely scrolled past.
        compose.onNodeWithTag(ShapePanelTags.kind(ShapeKind.Rectangle)).assertDoesNotExist()

        compose.onNodeWithTag(ShapePanelTags.kind(ShapeKind.Cube)).performClick()
        assertEquals(ShapeKind.Cube, changed?.kind)
    }

    @Test
    fun swipingTheGridTurnsThePage() {
        setPanel()

        // Left, i.e. dragging the page's contents leftwards, brings the next page in — the direction
        // every paged surface on the platform uses.
        compose.onNodeWithTag(ShapePanelTags.GRID).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithTag(ShapePanelTags.kind(ShapeKind.Cylinder)).assertIsDisplayed()
        compose.onNodeWithTag(ShapePanelTags.kind(ShapeKind.Rectangle)).assertDoesNotExist()

        compose.onNodeWithTag(ShapePanelTags.GRID).performTouchInput { swipeRight() }
        compose.waitForIdle()

        compose.onNodeWithTag(ShapePanelTags.kind(ShapeKind.Rectangle)).assertIsDisplayed()
    }

    @Test
    fun swipingPastTheLastPageStaysThere() {
        // The pages are a short list, not a carousel: swiping off the end must not wrap round to the
        // start, which would make "which page am I on" unanswerable without counting.
        setPanel(ShapeSettings(kind = ShapeKind.Cube))

        repeat(2) {
            compose.onNodeWithTag(ShapePanelTags.GRID).performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }

        compose.onNodeWithTag(ShapePanelTags.kind(ShapeKind.Cube)).assertIsDisplayed()
    }

    @Test
    fun thePaneOpensOnThePageHoldingTheArmedShape() {
        // Opening on page 1 while a cube is armed would show a grid with nothing selected in it.
        setPanel(ShapeSettings(kind = ShapeKind.Pyramid))

        compose.onNodeWithTag(ShapePanelTags.kind(ShapeKind.Pyramid)).assertIsDisplayed()
        compose.onNodeWithTag(ShapePanelTags.kind(ShapeKind.Rectangle)).assertDoesNotExist()
    }

    @Test
    fun theBorderControlsWriteTheFieldTheyName() {
        setPanel()

        compose.onNodeWithTag(ShapePanelTags.lineType(LineType.Dashed)).performClick()
        assertEquals(LineType.Dashed, changed?.lineType)
        assertEquals("line type moved something else", ShapeKind.DEFAULT, changed?.kind)

        val red = PEN_COLORS.first { it == 0xFFE53935.toInt() }
        compose.onNodeWithTag(ShapePanelTags.color(red)).performClick()
        assertEquals(red, changed?.borderColorArgb)
        assertEquals(
            "picking a colour left it following the theme",
            false,
            changed?.colorFollowsTheme,
        )
    }

    @Test
    fun fillColourIsPlacedButDoesNothing() {
        // Crossed-out controls are absent; not-yet-built ones hold their place and plainly do not
        // work. Fill is the second kind, because a stroke has no inside — §5.4 SD7.
        setPanel()
        compose.onNodeWithTag(ShapePanelTags.FILL).assertIsDisplayed()
        compose.onNodeWithTag(ShapePanelTags.FILL).assertHasNoClickAction()
    }

    @Test
    fun showThreeDLinesIsAbsentEntirely() {
        setPanel()
        compose.onNodeWithText("Show 3D lines").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------------------------
    // The gesture
    // -----------------------------------------------------------------------------------------

    private fun setOverlay(shape: ShapeSettings? = ShapeSettings()) {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.fillMaxSize()) {
                    InkOverlay(
                        strokes = emptyList(),
                        lassoGesture = remember { LassoGesture() },
                        brush = null,
                        erasing = false,
                        lassoing = false,
                        shaping = shape,
                        eraser = EraserSettings(),
                        // A mouse arrives as a direct touch, so the gesture is unreachable without
                        // this on an emulator — the same reason the ink tests set it.
                        allowFinger = true,
                        pageToView = { Matrix() },
                        onStrokeFinished = {},
                        onInsertShape = { start, end -> inserted = start to end },
                        onPartialErase = {},
                        onObjectErase = {},
                        onMoveSelection = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    @Test
    fun aDragBecomesTheBoxTheShapeIsTracedInto() {
        setOverlay()
        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(40f, 60f))
            moveTo(Offset(180f, 200f))
            up()
        }
        compose.waitForIdle()

        val (start, end) = checkNotNull(inserted) { "the drag inserted nothing" }
        assertEquals(40f, start.x, 1f)
        assertEquals(60f, start.y, 1f)
        assertEquals(180f, end.x, 1f)
        assertEquals(200f, end.y, 1f)
    }

    @Test
    fun aTapDropsADefaultSizedShapeCentredOnIt() {
        // A tap is not a mis-drag: the tool would feel dead if nothing happened.
        setOverlay()
        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput { click(Offset(100f, 100f)) }
        compose.waitForIdle()

        val (start, end) = checkNotNull(inserted) { "a tap inserted nothing" }
        assertEquals("the drop was not centred on the tap", 100f, (start.x + end.x) / 2f, 1f)
        assertEquals("the drop was not centred on the tap", 100f, (start.y + end.y) / 2f, 1f)
        assertTrue("the drop had no size", end.x - start.x > 1f && end.y - start.y > 1f)
    }

    @Test
    fun aDragInsertsNothingWhileTheToolIsNotArmed() {
        setOverlay(shape = null)
        compose.onNodeWithTag(INK_OVERLAY_TAG).performTouchInput {
            down(Offset(40f, 60f))
            moveTo(Offset(180f, 200f))
            up()
        }
        compose.waitForIdle()

        assertNull("a shape landed with no shape tool in hand", inserted)
    }

    // -----------------------------------------------------------------------------------------
    // The layer: selecting, moving and resizing a shape that is already on the page
    // -----------------------------------------------------------------------------------------

    private lateinit var vertical: ScrollState
    private lateinit var horizontal: ScrollState
    private var onPage: List<Outline.Shape> = emptyList()
    private var selectedId: String? = null
    private var canvasTaps = 0
    private var borderWidth: Int? = null
    private var resizeCalls = 0

    /** A square, in page dp, seeded the way [com.vivenotes.ui.NotesViewModel.insertShape] seeds one. */
    private fun square(left: Float, top: Float, side: Float): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = "square",
            kind = ShapeKind.Rectangle,
            segments = seedSegments(
                ShapeKind.Rectangle, left, top, left + side, top + side,
            ) { "seg-${next++}" },
        ).withRecomputedBounds()
    }

    /**
     * The layer where it actually lives: inside both scroll containers, over the bare-canvas tap
     * target, with the moves applied — which is the whole point. Handing the callbacks to a sink
     * would pass while the real thing failed, because the bug was that *applying* a move rewrote the
     * shape list and so restarted the gesture handler that had asked for it.
     */
    private fun setPage(shape: Outline.Shape, selected: String? = null) {
        onPage = listOf(shape)
        selectedId = selected
        canvasTaps = 0
        compose.setContent {
            ViveNotesTheme {
                var shapes by remember { mutableStateOf(listOf(shape)) }
                var selection by remember {
                    mutableStateOf(
                        selected?.let { id ->
                            shapes.firstOrNull { it.id == id }?.let(CanvasSelection::ofShape)
                        },
                    )
                }
                vertical = rememberScrollState()
                horizontal = rememberScrollState()
                SideEffect {
                    onPage = shapes
                    selectedId = selection?.shapeIds?.singleOrNull()
                }
                Column(Modifier.size(VIEWPORT).verticalScroll(vertical)) {
                    Box(Modifier.horizontalScroll(horizontal)) {
                        Box(Modifier.size(PAGE)) {
                            // The layer's parent in EditorPane: the tap on bare canvas that opens a
                            // text container. Its being the parent is what orders the two.
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTapGestures { canvasTaps++ }
                                    },
                            ) {
                                ShapeLayer(
                                    shapes = shapes,
                                    selection = selection,
                                    interactive = true,
                                    onSelect = { selection = it },
                                    onMoveShape = { id, dx, dy ->
                                        shapes = shapes.map {
                                            if (it.id == id) it.translated(dx, dy) else it
                                        }
                                    },
                                    // Exactly what NotesViewModel.resizeShape does with it, so the
                                    // harness cannot be right about a contract the app gets wrong.
                                    onResizeShape = { id, ax, ay, sx, sy ->
                                        resizeCalls++
                                        shapes = shapes.map {
                                            if (it.id == id) it.scaledAbout(ax, ay, sx, sy) else it
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Page dp to pixels on the node, which is where the touch goes. */
    private fun at(x: Float, y: Float): Offset = with(compose.density) {
        Offset(x.dp.toPx(), y.dp.toPx())
    }

    /**
     * One sample per frame, as a finger produces them.
     *
     * A gesture injected as a single block never recomposes in the middle of itself, which is exactly
     * where this used to break — so the recomposition between samples is the test.
     */
    private fun drag(from: Offset, through: List<Offset>) {
        val layer = compose.onNodeWithTag(SHAPE_LAYER_TAG)
        layer.performTouchInput { down(from) }
        compose.waitForIdle()
        through.forEach { point ->
            layer.performTouchInput { moveTo(point) }
            compose.waitForIdle()
        }
        layer.performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun draggingASelectedShapeMovesItAndLeavesThePageWhereItWas() {
        // The regression: a move rewrote the shape list, the keyed `pointerInput` restarted on it,
        // and the scroll containers picked the half-finished drag up and panned the page instead.
        setPage(square(left = 40f, top = 40f, side = 80f), selected = "square")
        val start = at(80f, 80f)

        drag(start, listOf(start + Offset(60f, 0f), start + Offset(200f, 0f)))

        val moved = onPage.single()
        assertEquals("a sideways drag moved it down the page", 40f, moved.y, 1f)
        assertTrue(
            "the shape barely moved: dragged 200px and it went ${moved.x - 40f}dp",
            moved.x - 40f > with(compose.density) { 60f.toDp().value },
        )
        assertEquals("the page panned instead of the shape moving", 0, horizontal.value)
        assertEquals("the page panned instead of the shape moving", 0, vertical.value)
    }

    @Test
    fun draggingAnUnselectedShapeSelectsItAndMovesItInOneMotion() {
        // Requiring a tap first is indistinguishable from the drag being broken, because what
        // happens instead is that the page pans.
        setPage(square(left = 40f, top = 40f, side = 80f))
        val onTheEdge = at(80f, 40f)

        drag(onTheEdge, listOf(onTheEdge + Offset(0f, 60f), onTheEdge + Offset(0f, 200f)))

        assertEquals("the drag did not take the shape it started on", "square", selectedId)
        assertTrue(
            "the shape did not move: it is still at y=${onPage.single().y}dp",
            onPage.single().y - 40f > with(compose.density) { 60f.toDp().value },
        )
        assertEquals("the page panned instead of the shape moving", 0, vertical.value)
    }

    @Test
    fun draggingACornerResizesAndStillDoesNotPanThePage() {
        setPage(square(left = 40f, top = 40f, side = 80f), selected = "square")
        val bottomRight = at(120f, 120f)

        // Waypoints in page units, not pixels, because the size this asserts is in page units. Two
        // of them, because one frame could never have caught the bug this pins: each frame of a drag
        // reports the scale since the finger went down, so applying them one after another
        // multiplied them together and the shape left the page. The corner ends at 320dp over an
        // anchor at 40dp, so the shape is exactly 280dp — whatever route the finger took there.
        drag(bottomRight, listOf(at(180f, 180f), at(320f, 320f)))

        val resized = onPage.single()
        assertEquals("the shape is not the size the corner was dragged to", 280f, resized.width, 1f)
        assertEquals("the shape is not the size the corner was dragged to", 280f, resized.height, 1f)
        assertEquals("the anchored corner moved", 40f, resized.x, 1f)
        assertEquals("the anchored corner moved", 40f, resized.y, 1f)
        assertEquals("the page panned instead of the shape resizing", 0, horizontal.value)
        assertEquals("the page panned instead of the shape resizing", 0, vertical.value)
    }

    @Test
    fun aCornerDragIsOneEditNoMatterHowManyFramesItTakes() {
        // The other half of it: the resize is drawn while the finger is down and written once when
        // it lifts. A write per frame is what let the frames compound in the first place, and it put
        // a page's worth of autosaves and undo steps through a single drag.
        setPage(square(left = 40f, top = 40f, side = 80f), selected = "square")
        val bottomRight = at(120f, 120f)
        resizeCalls = 0

        drag(
            bottomRight,
            (1..8).map { bottomRight + Offset(it * 25f, it * 25f) },
        )

        assertEquals("a corner drag should commit exactly once", 1, resizeCalls)
    }

    @Test
    fun aDragOnBareCanvasStillPansThePage() {
        // The other half of the arbitration: the layer covers the page, so if it took every gesture
        // the page could no longer be scrolled at all.
        setPage(square(left = 40f, top = 40f, side = 80f))
        val empty = at(220f, 220f)

        drag(empty, listOf(empty + Offset(0f, -60f), empty + Offset(0f, -200f)))

        assertEquals("the shape moved on a drag that never touched it", 40f, onPage.single().y, 1f)
        assertTrue("bare canvas no longer pans the page", vertical.value > 0)
    }

    // The object tooltip moved out of this layer and up to `EditorPane`, where one bar covers every
    // kind of selection (AD7). It is a sibling of the page content there rather than a child of this
    // layer, so nothing here can swallow its taps and there is nothing left for this file to guard.

    @Test
    fun aTapOnBareCanvasStillReachesTheTargetBeneathTheLayer() {
        // Hit testing stops at the topmost sibling that takes pointer input, so a full-size gesture
        // layer hides the tap that opens a text container — with one shape on the page, tapping
        // empty canvas did nothing at all.
        setPage(square(left = 40f, top = 40f, side = 80f))

        compose.onNodeWithTag(SHAPE_LAYER_TAG).performTouchInput { click(at(220f, 220f)) }
        compose.waitForIdle()

        assertEquals("the canvas never saw the tap", 1, canvasTaps)
        assertNull("empty canvas selected a shape", selectedId)
    }

    @Test
    fun aTapOnAShapeSelectsItAndDoesNotAlsoOpenAContainer() {
        setPage(square(left = 40f, top = 40f, side = 80f))

        compose.onNodeWithTag(SHAPE_LAYER_TAG).performTouchInput { click(at(80f, 40f)) }
        compose.waitForIdle()

        assertEquals("the tap did not select the shape", "square", selectedId)
        assertEquals("the tap fell through to the canvas as well", 0, canvasTaps)
    }
    // -----------------------------------------------------------------------------------------
    // The toolkit: base plus what a shape adds — `docs/diagram.md`
    // -----------------------------------------------------------------------------------------

    private fun setToolkit(extras: @Composable RowScope.() -> Unit) {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.size(400.dp)) {
                    ObjectTooltip(
                        swatch = Color.Black,
                        selectionBoundsInView = { RectF(60f, 200f, 160f, 260f) },
                        viewportSize = IntSize(1000, 1000),
                        onDelete = {},
                        onCopy = {},
                        onRecolor = {},
                        extras = extras,
                    )
                }
            }
        }
    }

    @Test
    fun aShapeExtendsTheBaseToolkitWithThicknessAndNotWithGrouping() {
        setToolkit { ThicknessAction(width = 3) { borderWidth = it } }

        // The base, which every kind gets.
        compose.onNodeWithTag(OBJECT_COLOR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_COPY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_DELETE_TAG).assertIsDisplayed()
        // What a shape adds, and what it does not: a shape is already one object.
        compose.onNodeWithTag(OBJECT_THICKNESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_GROUP_TAG).assertDoesNotExist()
    }

    @Test
    fun inkExtendsTheSameBaseWithGroupingAndNotWithThickness() {
        // A stroke's width is baked into its mesh, so there is nothing for a thickness control to
        // change — the two kinds extend the same bar in opposite directions.
        setToolkit { GroupAction(isOneGroup = false, onGroup = {}, onUngroup = {}) }

        compose.onNodeWithTag(OBJECT_GROUP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_THICKNESS_TAG).assertDoesNotExist()
    }

    @Test
    fun aSelectionOfBothKindsShowsTheBaseAlone() {
        // Nothing both halves agree on, so the bar falls back to what is true of anything.
        setToolkit { }

        compose.onNodeWithTag(OBJECT_COLOR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_DELETE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_GROUP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(OBJECT_THICKNESS_TAG).assertDoesNotExist()
    }

    @Test
    fun theToolkitsThicknessEditsTheSelectedShape() {
        setToolkit { ThicknessAction(width = 3) { borderWidth = it } }

        compose.onNodeWithTag(OBJECT_THICKNESS_TAG).performClick()
        compose.onNodeWithContentDescription("Increase Border width").performClick()
        compose.waitForIdle()

        assertEquals("the toolkit did not change the shape's border", 4, borderWidth)
    }
}

/** Big enough to scroll in both directions, small enough to be all on screen. */
private val VIEWPORT = 300.dp
private val PAGE = 1200.dp
