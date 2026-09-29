package com.vivenotes.ui.panel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.ui.VersionHistoryState
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VersionHistoryPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val first = PageRevisionSummary("rev-1", "page-1", 1_700_000_000_000, 240)
    private val second = PageRevisionSummary("rev-2", "page-1", 1_699_000_000_000, 180)
    private val decoded = PageDoc(
        outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of("Earlier text")))),
    )

    @Test
    fun selectingARevisionHandsBackItsId() {
        var selected: String? = null
        setPanel(
            VersionHistoryState(pageId = "page-1", revisions = listOf(first, second)),
            onSelect = { selected = it },
        )

        compose.onNodeWithTag(VersionHistoryPanelTags.revision(second.id)).performClick()

        assertEquals(second.id, selected)
    }

    @Test
    fun restoreRequiresConfirmation() {
        var restored = false
        setPanel(
            VersionHistoryState(
                pageId = "page-1",
                revisions = listOf(first),
                selectedRevision = first,
                preview = decoded,
            ),
            onRestore = { restored = true },
        )

        compose.onNodeWithTag(VersionHistoryPanelTags.RESTORE).performClick()
        assertTrue(!restored)
        compose.onNodeWithText("Restore this version?").assertIsDisplayed()

        compose.onNodeWithTag(VersionHistoryPanelTags.CONFIRM).performClick()

        assertTrue(restored)
    }

    @Test
    fun restoreStaysOnScreenAboveAFullHistory() {
        val revisions = (0 until NotesRepository.MAX_REVISIONS_PER_PAGE).map {
            PageRevisionSummary("rev-$it", "page-1", 1_700_000_000_000 - it * 30_000L, 240)
        }
        setPanel(
            VersionHistoryState(
                pageId = "page-1",
                revisions = revisions,
                selectedRevision = revisions.first(),
                preview = decoded,
            ),
        )
        val oldest = compose.onNodeWithTag(VersionHistoryPanelTags.revision(revisions.last().id))

        // Otherwise the list fits and this proves nothing about scrolling.
        oldest.assertIsNotDisplayed()
        compose.onNodeWithTag(VersionHistoryPanelTags.RESTORE).assertIsDisplayed()

        oldest.performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(VersionHistoryPanelTags.RESTORE).assertIsDisplayed()
    }

    @Test
    fun emptyHistoryIsReported() {
        setPanel(VersionHistoryState(pageId = "page-1"))

        compose.onNodeWithText("No earlier versions yet").assertIsDisplayed()
    }

    private fun setPanel(
        state: VersionHistoryState,
        onSelect: (String) -> Unit = {},
        onRestore: () -> Unit = {},
    ) {
        compose.setContent {
            ViveNotesTheme {
                ToolPanel(
                    pane = ToolPane.VersionHistory,
                    onClose = {},
                    footer = { VersionHistoryPanelFooter(state = state, onRestore = onRestore) },
                ) {
                    VersionHistoryPanelContent(state = state, onSelect = onSelect)
                }
            }
        }
    }
}
