package com.vivenotes.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.vivenotes.data.EditorDefaults
import com.vivenotes.ink.CANVAS_PASTE_MARGIN
import com.vivenotes.ink.CanvasPasteLayoutRequest
import com.vivenotes.ink.PasteFrames
import com.vivenotes.ink.stackPastedOutlines
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Outline
import com.vivenotes.model.PageStyle
import com.vivenotes.model.newTable
import com.vivenotes.ui.CanvasScrollTarget
import com.vivenotes.ui.OutlineBox
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A paste of several objects is measured by the canvas and stacked, and what the canvas then draws
 * leaves [CANVAS_PASTE_MARGIN] between each one's frame and the next — however the text wraps.
 */
class CanvasPasteSpacingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun wrappedTextAroundATableLeavesTheMarginBetweenEveryFrame() {
        val before = Outline.Text.empty().copy(blocks = listOf(
            Block.of("A large heading wraps across the canvas. ".repeat(4), BlockType.Heading1),
            Block.of("And a paragraph under it. ".repeat(12)),
        ))
        val grid = newTable(2, 2)
        val table = grid.copy(rows = grid.rows.mapIndexed { r, row ->
            row.copy(cells = row.cells.mapIndexed { c, cell ->
                cell.copy(blocks = listOf(Block.of(if (r == 1 && c == 0) "Wrapped cell text. ".repeat(10) else "cell")))
            })
        })
        val after = Outline.Text.empty().copy(blocks = listOf(Block.of("After")))
        val pasted = listOf(before, table, after)
        val blocks = (listOf(before, after).associate { it.id to it.blocks } +
            table.rows.flatMap { it.cells }.associate { it.id to it.blocks })

        val request = CanvasPasteLayoutRequest(pasted)
        val pending = mutableStateOf<CanvasPasteLayoutRequest?>(request)
        val stacked = mutableStateOf<List<Outline>>(emptyList())
        lateinit var density: Density
        compose.setContent {
            density = LocalDensity.current
            ViveNotesTheme {
                val placed = stacked.value.map { it.atPastePoint() }
                EditorPane(
                    title = "",
                    createdAt = 0L,
                    defaults = EditorDefaults(),
                    style = PageStyle(hideTitle = true),
                    zoom = 1f,
                    onTitleChange = {},
                    outlines = placed.filterIsInstance<Outline.Text>()
                        .map { OutlineBox(it.id, it.x, it.y, it.width, it.minHeight) },
                    pageRevision = 0,
                    initialBlocksFor = { blocks[it] ?: listOf(Block.empty()) },
                    commands = MutableSharedFlow(),
                    onBlocksChanged = { _, _ -> },
                    onSelectionChanged = {},
                    onMarkArmed = {},
                    onCreateOutline = { _, _ -> "" },
                    textArmed = false,
                    onMoveOutline = { _, _, _ -> },
                    onResizeOutline = { _, _ -> },
                    onSetOutlineMinHeight = { _, _ -> },
                    onOutlineBlurred = {},
                    tables = placed.filterIsInstance<Outline.Table>(),
                    onCanvasMeasured = { _, _ -> },
                    showPrintMargins = false,
                    pendingPasteLayout = pending.value,
                    onPasteMeasured = { measured, frames: PasteFrames ->
                        stacked.value = stackPastedOutlines(measured.outlines, frames.heights, frames.insets)
                        pending.value = null
                    },
                )
            }
        }
        compose.waitUntil(5_000) { stacked.value.isNotEmpty() }
        compose.waitForIdle()

        val editors = compose.onAllNodesWithTag(OutlineTags.EDITOR).fetchSemanticsNodes()
            .map { it.boundsInRoot }.sortedBy { it.top }
        val gridFrame = compose.onNodeWithTag(TableTags.GRID).fetchSemanticsNode().boundsInRoot
        val frames = listOf(editors[0], gridFrame, editors[1])
        val margin = CANVAS_PASTE_MARGIN * density.density
        frames.zipWithNext().forEach { (upper, lower) ->
            assertEquals("each frame leaves the paste margin to the next", margin, lower.top - upper.bottom, 1.5f)
        }
    }

    @Composable
    private fun Editor(
        outlines: List<OutlineBox>,
        blocks: Map<String, List<Block>> = emptyMap(),
        request: CanvasPasteLayoutRequest? = null,
        onMeasured: (PasteFrames) -> Unit = {},
        scrollTarget: CanvasScrollTarget? = null,
        onScrollTargetHandled: () -> Unit = {},
        onViewport: (Float, Float, Float) -> Unit = { _, _, _ -> },
    ) {
        ViveNotesTheme {
            EditorPane(
                title = "",
                createdAt = 0L,
                defaults = EditorDefaults(),
                style = PageStyle(hideTitle = true),
                zoom = 1f,
                pageId = "page",
                onTitleChange = {},
                outlines = outlines,
                pageRevision = 0,
                initialBlocksFor = { blocks[it] ?: listOf(Block.empty()) },
                commands = MutableSharedFlow(),
                onBlocksChanged = { _, _ -> },
                onSelectionChanged = {},
                onMarkArmed = {},
                onCreateOutline = { _, _ -> "" },
                textArmed = false,
                onMoveOutline = { _, _, _ -> },
                onResizeOutline = { _, _ -> },
                onSetOutlineMinHeight = { _, _ -> },
                onOutlineBlurred = {},
                onCanvasMeasured = { _, _ -> },
                showPrintMargins = false,
                pendingPasteLayout = request,
                onPasteMeasured = { _, frames -> onMeasured(frames) },
                scrollTarget = scrollTarget,
                onScrollTargetHandled = onScrollTargetHandled,
                onViewport = onViewport,
            )
        }
    }

    /** Where an import goes depends on this, and it has to count the lines the text wrapped to. */
    @Test
    fun theCanvasSaysWhereItsContentEnds() {
        val box = OutlineBox("existing", 40f, 300f, 400f, 0f)
        val blocks = mapOf("existing" to listOf(Block.of("A line of text that wraps. ".repeat(30))))
        val request = mutableStateOf<CanvasPasteLayoutRequest?>(null)
        var answer: PasteFrames? = null
        lateinit var density: Density
        compose.setContent {
            density = LocalDensity.current
            Editor(listOf(box), blocks, request.value, onMeasured = { answer = it })
        }
        compose.waitForIdle()

        // After the first layout, as in the app: nothing imports onto a page that has not been drawn.
        request.value = CanvasPasteLayoutRequest(listOf(Outline.Text.empty().copy(blocks = listOf(Block.of("new")))))
        compose.waitUntil(5_000) { answer != null }

        val editorBottom = compose.onNodeWithTag(OutlineTags.EDITOR).fetchSemanticsNode().boundsInRoot.bottom / density.density
        assertTrue("the box should have wrapped well past one line", editorBottom > 400f)
        // Past the text by the box's own frame around it, and no further.
        val bottom = answer!!.contentBottom!!
        assertTrue("$bottom should be just under the text at $editorBottom", bottom >= editorBottom && bottom <= editorBottom + 24f)
    }

    private fun scrollTest(targetY: Float): Float {
        val box = OutlineBox("tall", 40f, 200f, 400f, 6000f)
        val target = mutableStateOf<CanvasScrollTarget?>(null)
        var handled = false
        var top = 0f
        compose.setContent {
            Editor(
                outlines = listOf(box),
                scrollTarget = target.value,
                onScrollTargetHandled = { handled = true },
                onViewport = { _, y, _ -> top = y },
            )
        }
        compose.waitForIdle()

        target.value = CanvasScrollTarget("page", targetY)
        compose.waitUntil(5_000) { handled }
        compose.waitForIdle()
        return top
    }

    /** Below the window nothing on screen would say the import arrived. */
    @Test
    fun aLandingBelowTheWindowIsScrolledTo() {
        assertEquals(4952f, scrollTest(5000f), 8f)
    }

    /** A fresh page must not jump: there is nothing to go and find. */
    @Test
    fun aLandingAlreadyInViewLeavesThePageWhereItIs() {
        assertEquals(0f, scrollTest(150f), 0.01f)
    }

    private fun Outline.atPastePoint(): Outline = when (this) {
        is Outline.Text -> copy(x = 40f, y = 200f + y)
        is Outline.Table -> copy(x = 40f, y = 200f + y)
        else -> this
    }
}
