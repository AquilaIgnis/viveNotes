package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    private val preview = PageDoc(
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
                preview = preview,
            ),
            onRestore = { restored = true },
        )

        compose.onNodeWithText("Earlier text").assertIsDisplayed()
        compose.onNodeWithTag(VersionHistoryPanelTags.RESTORE).performClick()
        assertTrue(!restored)
        compose.onNodeWithText("Restore this version?").assertIsDisplayed()

        compose.onNodeWithTag(VersionHistoryPanelTags.CONFIRM).performClick()

        assertTrue(restored)
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
                Column {
                    VersionHistoryPanelContent(
                        state = state,
                        onSelect = onSelect,
                        onRestore = onRestore,
                    )
                }
            }
        }
    }
}
