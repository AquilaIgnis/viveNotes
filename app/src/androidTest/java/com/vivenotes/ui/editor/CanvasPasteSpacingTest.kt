package com.vivenotes.ui.editor

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
import com.vivenotes.ui.OutlineBox
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
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

    private fun Outline.atPastePoint(): Outline = when (this) {
        is Outline.Text -> copy(x = 40f, y = 200f + y)
        is Outline.Table -> copy(x = 40f, y = 200f + y)
        else -> this
    }
}
