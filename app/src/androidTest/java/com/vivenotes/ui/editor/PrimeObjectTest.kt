package com.vivenotes.ui.editor

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.EditorDefaults
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageStyle
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.Run
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.ui.OutlineBox
import com.vivenotes.ui.theme.ViveNotesTheme

/**
 * Prime Object, as the whole page assembles it — `docs/diagram.md`, Prime Object Class:
 *
 * > *prime behavior : can be moved around canvas freely, taping on object selects it bring "base
 * > object toolkit", on being selected sow a rectangle around object with 4 vertices that allow re
 * > sizing. Selecting any other tool removes selection of object.*
 *
 * **Through [EditorPane] rather than through a layer**, which is the point of this file existing
 * beside `ShapeToolTest`. That one hosts [ShapeLayer] by itself and proves the gesture arithmetic;
 * it cannot see either of the two things Prime Object is actually about here — the selection lives
 * in the pane and the toolkit is raised out of the layers entirely (AD7) — and, as
 * [aTapOnAShapeReachesTheShapesRatherThanThePageBeneath] records, it kept passing for a day while
 * tapping a shape in the real app did nothing at all.
 */
class PrimeObjectTest {

    @get:Rule
    val compose = createComposeRule()

    private val shapes = mutableStateOf(emptyList<Outline.Shape>())
    private val outlines = mutableStateOf(emptyList<OutlineBox>())
    private val textArmed = mutableStateOf(false)
    private val commands = MutableSharedFlow<FormatCommand>(extraBufferCapacity = 4)
    private var created: Pair<Float, Float>? = null
    private lateinit var density: Density

    private fun setPage(
        shapes: List<Outline.Shape>,
        textArmed: Boolean = false,
        outlines: List<OutlineBox> = emptyList(),
    ) {
        this.shapes.value = shapes
        this.outlines.value = outlines
        this.textArmed.value = textArmed
        created = null
        compose.setContent {
            density = LocalDensity.current
            ViveNotesTheme {
                EditorPane(
                    title = "A page",
                    createdAt = 0L,
                    defaults = EditorDefaults(),
                    // No title band, so nothing stands between the page's origin and the window's.
                    style = PageStyle(hideTitle = true),
                    zoom = 1f,
                    onZoomPinched = {},
                    onZoomCommitted = {},
                    onTitleChange = {},
                    outlines = this@PrimeObjectTest.outlines.value,
                    pageRevision = 0,
                    // Non-empty on purpose: a container's chrome is shown only when it is *focused
                    // and holds text*, so the move grip is the one node a test can assert the caret
                    // by — an empty container is a caret position and shows nothing at all.
                    initialBlocksFor = { listOf(Block(id = "b", runs = listOf(Run("A note")))) },
                    commands = commands,
                    onBlocksChanged = { _, _ -> },
                    onSelectionChanged = {},
                    onMarkArmed = {},
                    onCreateOutline = { x, y -> created = x to y; "outline" },
                    textArmed = this@PrimeObjectTest.textArmed.value,
                    onMoveOutline = { _, _, _ -> },
                    onResizeOutline = { _, _ -> },
                    onSetOutlineMinHeight = { _, _ -> },
                    onOutlineBlurred = {},
                    shapes = this@PrimeObjectTest.shapes.value,
                    onCanvasMeasured = { _, _ -> },
                    showPrintMargins = false,
                )
            }
        }
        compose.waitForIdle()
    }

    /**
     * Taps a point in **page** coordinates.
     *
     * On the shape layer's own node rather than on the root at window coordinates: the layer fills
     * the page canvas, so its local space *is* page space at zoom 1, and where the page sits inside
     * the window stops being something the test has to model.
     *
     * Down and up as separate injections, the way `ShapeToolTest` drives this layer: the handler
     * decides what it is holding on the down and reports the selection on the up.
     */
    private fun tapPage(xDp: Float, yDp: Float) {
        val offset = with(density) { Offset(xDp.dp.toPx(), yDp.dp.toPx()) }
        val layer = compose.onNodeWithTag(SHAPE_LAYER_TAG)
        layer.performTouchInput { down(offset) }
        compose.waitForIdle()
        layer.performTouchInput { up() }
        compose.waitForIdle()
    }

    /** Down the same bus the ribbon uses, on the main thread, as `selectTool` emits it. */
    private fun pickAnotherTool() {
        var emitted = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            emitted = commands.tryEmit(FormatCommand.ClearCanvasSelection)
        }
        assertTrue("the command bus dropped the clear", emitted)
        compose.waitForIdle()
    }

    /** A rectangle whose top edge runs through (120, 60) — the point every tap below aims at. */
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
     * The regression that made this file worth writing.
     *
     * `EquationLayer` arrived as a *sibling* of [ShapeLayer], both filling the page. Compose hands a
     * pointer event to the topmost node under it and to that node's ancestors — overlapping siblings
     * do not both get a say — so the equation layer took every touch on the page and declined it,
     * and the tap fell past the shapes to the bare-canvas tap target that is their ancestor. Every
     * one of Prime Object's first three rules was dead for shapes: no selection, no move, no corner
     * handles, and a tap on a shape opened a text container where the shape was.
     *
     * Both halves are asserted, because either alone is satisfiable by the bug: the container is
     * what the touch did instead, and the toolkit is what it should have done.
     */
    @Test
    fun aTapOnAShapeReachesTheShapesRatherThanThePageBeneath() {
        setPage(listOf(square(left = 60f, top = 60f, side = 120f)), textArmed = true)

        tapPage(120f, 60f)

        assertNull("the tap fell past the shape and opened a text container", created)
        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertIsDisplayed()
    }

    @Test
    fun tappingAnObjectSelectsItAndPickingAnotherToolDropsIt() {
        setPage(listOf(square(left = 60f, top = 60f, side = 120f)))

        // On the shape's own edge: a rectangle is a border, and `topmostNear` hit-tests the segments.
        tapPage(120f, 60f)
        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertIsDisplayed()

        pickAnotherTool()

        // The bar is the half of the selection with a semantics node to assert on; the dashed box
        // and its four handles are painted into the layer's canvas from the same `selection`.
        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertDoesNotExist()
    }

    @Test
    fun theSelectionSurvivesEverythingThatIsNotAToolChange() {
        // The guard the rule needs on the other side: were the clear keyed on the *armed tool*
        // rather than on the user picking one, placing an object would deselect it — every insert
        // path disarms itself (`DrawTool.None`) and only then hands the new object's id back to be
        // selected. `NotesViewModelTest` holds the ViewModel end of this; here it is enough that a
        // selection nothing has interrupted survives the page being rewritten under it.
        setPage(listOf(square(left = 60f, top = 60f, side = 120f)))

        tapPage(120f, 60f)
        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertIsDisplayed()

        shapes.value = listOf(square(left = 60f, top = 60f, side = 120f))
        compose.waitForIdle()

        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertIsDisplayed()
    }

    /**
     * The bug this order was changed for: *"if i move an object on top of a text box it's
     * impossible to click on the object without moving the whole text box"*.
     *
     * A container's editor is a real Android View. Compose tunnels the DOWN to it before any
     * bubbling-pass gesture handler runs, and `pointerInteropFilter` consumes it there — so while
     * the object layers were composed beneath the containers, a shape dragged onto one could not be
     * selected, moved or resized at all, and the only way back to it was to drag the text box off
     * it. The layers are in front now, and this is the assertion that says so.
     */
    @Test
    fun aTapOnAShapeLyingOverATextContainerReachesTheShape() {
        setPage(
            shapes = listOf(square(left = 240f, top = 240f, side = 60f)),
            outlines = listOf(OutlineBox(id = "note", x = 200f, y = 200f, width = 220f)),
        )

        // On the square's own top edge, and well inside the container's box — a rectangle is a
        // border, and `topmostNear` hit-tests the segments rather than the filled area.
        tapPage(270f, 240f)

        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OutlineTags.MOVE).assertDoesNotExist()
    }

    /**
     * The other half, and the one that makes the change survivable: a full-page layer placed in
     * front of the containers would otherwise swallow every tap on the page. It does not, because
     * the layer shares its touches with the siblings underneath and consumes only where it has
     * something of its own — see `sharingTouchesWithSiblings`.
     */
    @Test
    fun aTapOnTheContainerBesideTheShapeStillReachesItsEditor() {
        setPage(
            shapes = listOf(square(left = 240f, top = 240f, side = 60f)),
            outlines = listOf(OutlineBox(id = "note", x = 200f, y = 200f, width = 220f)),
        )

        // Inside the container's editor — below the grip strip it starts under, above the strip the
        // bottom handle is reserved in, and well to the left of the square.
        tapPage(215f, 234f)

        // The grip is the assertion: a container shows its chrome only once it is focused and holds
        // text, so the grip existing *is* the caret having landed in it. Not the tooltip — that bar
        // is raised for every kind of selection, a focused text container included (TD3), so it
        // cannot tell this apart from a shape having been picked up.
        compose.onNodeWithTag(OutlineTags.MOVE).assertIsDisplayed()
    }

    /**
     * The other direction of the same rule: a caret takes over from a selection.
     *
     * Selecting an object has always taken the caret out of wherever it was; without the mirror of
     * it, a shape kept its handles and its bar while the writing had the keyboard, and the ribbon
     * went on offering Border and Fill for something the next keystroke could not touch.
     *
     * Asserted on the *thickness* control rather than on the bar itself, because the bar is raised
     * for a focused text container too (TD3) and so says nothing about which of the two is up.
     * Border belongs to a shape alone.
     */
    @Test
    fun puttingTheCaretInAContainerDropsTheSelectedObject() {
        setPage(
            shapes = listOf(square(left = 240f, top = 240f, side = 60f)),
            outlines = listOf(OutlineBox(id = "note", x = 200f, y = 200f, width = 220f)),
        )

        tapPage(270f, 240f)
        compose.onNodeWithTag(OBJECT_THICKNESS_TAG).assertIsDisplayed()

        tapPage(215f, 234f)

        compose.onNodeWithTag(OutlineTags.MOVE).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_THICKNESS_TAG).assertDoesNotExist()
    }
}
