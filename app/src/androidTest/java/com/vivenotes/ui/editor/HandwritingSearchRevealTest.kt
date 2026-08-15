package com.vivenotes.ui.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.data.EditorDefaults
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.PageStroke
import com.vivenotes.model.Block
import com.vivenotes.model.PageStyle
import com.vivenotes.model.search.ContentKind
import com.vivenotes.ui.ContentReveal
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HandwritingSearchRevealTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun openingAHandwritingResultDoesNotCreateAnEditableInkSelection() {
        val ink = InkCodec.eraseMask(
            MutableStrokeInputBatch().apply {
                add(InputToolType.UNKNOWN, 100f, 120f, 0L)
                add(InputToolType.UNKNOWN, 180f, 120f, 16L)
            }.toImmutable(),
            sizeDp = 6f,
        )
        val stroke = PageStroke("source-stroke", ink)
        val bounds = InkBounds(100f, 110f, 180f, 130f)
        var handled = false

        compose.setContent {
            ViveNotesTheme {
                EditorPane(
                    title = "Page",
                    createdAt = 0L,
                    defaults = EditorDefaults(),
                    style = PageStyle(hideTitle = true),
                    zoom = 1f,
                    onTitleChange = {},
                    outlines = emptyList(),
                    pageRevision = 0,
                    pageId = "page",
                    initialBlocksFor = { listOf(Block.empty()) },
                    commands = emptyFlow(),
                    onBlocksChanged = { _, _ -> },
                    onSelectionChanged = { _ -> },
                    onMarkArmed = { _ -> },
                    onCreateOutline = { _, _ -> "unused" },
                    textArmed = false,
                    onMoveOutline = { _, _, _ -> },
                    onResizeOutline = { _, _ -> },
                    onSetOutlineMinHeight = { _, _ -> },
                    onOutlineBlurred = {},
                    onCanvasMeasured = { _, _ -> },
                    showPrintMargins = false,
                    reveal = ContentReveal(
                        pageId = "page",
                        kind = ContentKind.Ink,
                        boxId = "line-1",
                        tableId = null,
                        start = 0,
                        end = 4,
                        inkStrokeIds = setOf(stroke.id),
                        inkBounds = bounds,
                    ),
                    onRevealHandled = { handled = true },
                    strokes = listOf(stroke),
                    inkReady = true,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) { handled }
        compose.waitForIdle()

        assertTrue(handled)
        compose.onNodeWithTag(OBJECT_TOOLTIP_TAG).assertDoesNotExist()
    }
}
