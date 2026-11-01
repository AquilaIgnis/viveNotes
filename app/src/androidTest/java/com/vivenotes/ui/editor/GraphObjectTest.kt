package com.vivenotes.ui.editor

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.data.EditorDefaults
import com.vivenotes.graph.GraphDrag
import com.vivenotes.model.Outline
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A graph on the page, through the whole [EditorPane]: selected by a tap with its toolkit raised,
 * moved by a drag, and — with mutate on — rewritten by dragging one of its points.
 *
 * The arithmetic of points and drags is `GraphMathTest`, on the JVM. What needs a device is that the
 * layer sits where the page asks it to and the touches reach it.
 */
class GraphObjectTest {

    @get:Rule
    val compose = createComposeRule()

    private val graphs = mutableStateOf(emptyList<Outline.Graph>())
    private val commands = MutableSharedFlow<FormatCommand>(extraBufferCapacity = 4)
    private var moved: Pair<Float, Float>? = null
    private var mutated: Pair<String, GraphDrag>? = null
    private lateinit var density: Density

    /**
     * x² − 4 on −5…5 in both directions, filling a 320 × 260 box at (40, 40) with no header, so a
     * point's place on the page is plain arithmetic: x maps to 40 + (x + 5) × 32, y to 170 − y × 26.
     */
    private fun parabola(mutable: Boolean) = Outline.Graph(
        id = "graph",
        x = 40f,
        y = 40f,
        width = 320f,
        height = 260f,
        expression = "((x^2)+(-4))",
        latex = "x^{2} - 4",
        xMin = -5.0,
        xMax = 5.0,
        yMin = -5.0,
        yMax = 5.0,
        showEquation = false,
        mutable = mutable,
    )

    private fun pageX(x: Double): Float = (40 + (x + 5) * 32).toFloat()
    private fun pageY(y: Double): Float = (170 - y * 26).toFloat()

    private fun setPage(vararg page: Outline.Graph) {
        graphs.value = page.toList()
        moved = null
        mutated = null
        compose.setContent {
            density = LocalDensity.current
            ViveNotesTheme {
                EditorPane(
                    title = "A page",
                    createdAt = 0L,
                    defaults = EditorDefaults(),
                    style = PageStyle(hideTitle = true),
                    zoom = 1f,
                    onZoomPinched = {},
                    onZoomCommitted = {},
                    onTitleChange = {},
                    outlines = emptyList(),
                    pageRevision = 0,
                    initialBlocksFor = { emptyList() },
                    commands = commands,
                    onBlocksChanged = { _, _ -> },
                    onSelectionChanged = {},
                    onCanvasSelectionChanged = {},
                    onMarkArmed = {},
                    onCreateOutline = { _, _ -> "outline" },
                    textArmed = false,
                    onMoveOutline = { _, _, _ -> },
                    onResizeOutline = { _, _ -> },
                    onSetOutlineMinHeight = { _, _ -> },
                    onOutlineBlurred = {},
                    graphs = graphs.value,
                    onMoveGraphs = { _, dx, dy -> moved = dx to dy },
                    onUpdateGraphs = { ids, change ->
                        graphs.value = graphs.value.map { if (it.id in ids) change(it) else it }
                    },
                    onMutateGraph = { graph, drag -> mutated = graph.id to drag },
                    onCanvasMeasured = { _, _ -> },
                    showPrintMargins = false,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun offset(xDp: Float, yDp: Float): Offset = with(density) { Offset(xDp.dp.toPx(), yDp.dp.toPx()) }

    private fun tapPage(xDp: Float, yDp: Float) {
        val layer = compose.onNodeWithTag(GRAPH_LAYER_TAG)
        layer.performTouchInput { down(offset(xDp, yDp)) }
        compose.waitForIdle()
        layer.performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun dragPage(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val layer = compose.onNodeWithTag(GRAPH_LAYER_TAG)
        layer.performTouchInput { down(offset(fromX, fromY)) }
        compose.waitForIdle()
        // Two moves, so the first carries the gesture past the touch slop before the one that counts.
        layer.performTouchInput { moveTo(offset((fromX + toX) / 2, (fromY + toY) / 2)) }
        compose.waitForIdle()
        layer.performTouchInput { moveTo(offset(toX, toY)) }
        compose.waitForIdle()
        layer.performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun aTapSelectsTheGraphAndRaisesItsToolkit() {
        setPage(parabola(mutable = false))

        tapPage(100f, 80f)

        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GraphToolkitTags.MUTATE).assertIsDisplayed()
        compose.onNodeWithTag(GraphToolkitTags.OPTIONS).assertIsDisplayed()
    }

    @Test
    fun theMutateButtonTurnsMutateOn() {
        setPage(parabola(mutable = false))
        tapPage(100f, 80f)

        compose.onNodeWithTag(GraphToolkitTags.MUTATE).performClick()
        compose.waitForIdle()

        assertTrue("mutate stayed off", graphs.value.single().mutable)
    }

    @Test
    fun withMutateOffADragOnAPointMovesTheGraph() {
        setPage(parabola(mutable = false))
        tapPage(100f, 80f)

        dragPage(pageX(2.0), pageY(0.0), pageX(2.0) + 40f, pageY(0.0) + 30f)

        // Measured from where the drag crossed the slop — the midpoint, in [dragPage] — as an
        // equation's is, so the slop itself is never part of the move.
        val travel = assertNotNull("the graph did not move", moved).let { moved!! }
        assertEquals(20f, travel.first, 2f)
        assertEquals(15f, travel.second, 2f)
        assertNull("a point was dragged with mutate off", mutated)
    }

    @Test
    fun withMutateOnDraggingAZeroMovesThatRootAlone() {
        setPage(parabola(mutable = true))
        tapPage(100f, 80f)

        // The zero at x = 2, dragged to x = 3 and somewhat off the axis — a zero stays on it.
        dragPage(pageX(2.0), pageY(0.0), pageX(3.0), pageY(0.0) - 15f)

        assertNull("the box moved instead of the point", moved)
        val (id, drag) = assertNotNull("no point was dragged", mutated).let { mutated!! }
        assertEquals("graph", id)
        drag as GraphDrag.Root
        assertEquals("3", drag.to.toPlainString())
        assertEquals(0.0, drag.evaluate(-2.0), 1e-9)
        assertEquals(0.0, drag.evaluate(3.0), 1e-9)
    }

    @Test
    fun withMutateOnDraggingTheVertexTranslatesTheCurve() {
        setPage(parabola(mutable = true))
        tapPage(100f, 80f)

        // The vertex (0, −4) to (1, −2).
        dragPage(pageX(0.0), pageY(-4.0), pageX(1.0), pageY(-2.0))

        val drag = assertNotNull("no point was dragged", mutated).let { mutated!!.second }
        drag as GraphDrag.Shift
        assertEquals("1", drag.h.toPlainString())
        assertEquals("2", drag.k.toPlainString())
    }
}
