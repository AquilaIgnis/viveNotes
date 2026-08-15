package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vivenotes.data.DeletedItem
import com.vivenotes.data.DeletedItemKey
import com.vivenotes.data.DeletedItemKind
import com.vivenotes.ui.DeletedItemsState
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeletedItemsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val page = DeletedItem(
        key = DeletedItemKey("page-1", DeletedItemKind.Page),
        name = "Project notes",
        notebookName = "Work",
        sectionName = "Planning",
        deletedAt = 1_700_000_000_000,
    )

    @Test
    fun restoreHandsBackTheSelectedItem() {
        var restored: DeletedItem? = null
        setPanel(
            state = DeletedItemsState(loading = false, items = listOf(page)),
            onRestore = { restored = it },
        )

        compose.onNodeWithText("Items are permanently deleted after 7 days.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("In Planning · Work").assertIsDisplayed()
        compose.onNodeWithTag(DeletedItemsPanelTags.restore(page.key.id)).performClick()

        assertEquals(page, restored)
    }

    @Test
    fun emptyRecoveryListIsReported() {
        setPanel(DeletedItemsState(loading = false))

        compose.onNodeWithTag(DeletedItemsPanelTags.EMPTY).assertIsDisplayed()
    }

    private fun setPanel(
        state: DeletedItemsState,
        onRestore: (DeletedItem) -> Unit = {},
    ) {
        compose.setContent {
            ViveNotesTheme {
                Column {
                    DeletedItemsPanelContent(
                        state = state,
                        onRestore = onRestore,
                        onClearStatus = {},
                    )
                }
            }
        }
    }
}
