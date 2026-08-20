package com.vivenotes.ui.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FileTabTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun versionHistoryOpensForAnOpenPage() {
        var opened = false
        setTab(pageOpen = true, history = { opened = true })

        compose.onNodeWithTag(FileTags.VERSION_HISTORY).performClick()

        assertTrue(opened)
    }

    @Test
    fun versionHistoryIsInertWithoutAnOpenPage() {
        var opened = false
        setTab(pageOpen = false, history = { opened = true })

        compose.onNodeWithTag(FileTags.VERSION_HISTORY).performTouchInput { click() }

        assertFalse(opened)
    }

    @Test
    fun deletedItemsIsAvailableWithoutAnOpenNotebookOrPage() {
        var opened = false
        setTab(
            pageOpen = false,
            notebookOpen = false,
            deletedItems = { opened = true },
        )

        compose.onNodeWithTag(FileTags.DELETED_ITEMS).performClick()

        assertTrue(opened)
    }

    @Test
    fun exportOpensForASelectedNotebook() {
        var opened = false
        setTab(pageOpen = false, notebookOpen = true, export = { opened = true })

        compose.onNodeWithTag(FileTags.EXPORT_NOTEBOOK).performClick()

        assertTrue(opened)
    }

    @Test
    fun exportIsInertWithoutASelectedNotebook() {
        var opened = false
        setTab(pageOpen = false, notebookOpen = false, export = { opened = true })

        compose.onNodeWithTag(FileTags.EXPORT_NOTEBOOK).performTouchInput { click() }

        assertFalse(opened)
    }

    @Test
    fun importIsAlwaysAvailable() {
        var opened = false
        setTab(pageOpen = false, notebookOpen = false, import = { opened = true })

        compose.onNodeWithTag(FileTags.IMPORT_NOTEBOOK).performClick()

        assertTrue(opened)
    }

    @Test
    fun deleteAsksForASelectedNotebook() {
        var asked = false
        setTab(pageOpen = false, notebookOpen = true, delete = { asked = true })

        compose.onNodeWithTag(FileTags.DELETE_NOTEBOOK).performClick()

        assertTrue(asked)
    }

    @Test
    fun deleteIsInertWithoutASelectedNotebook() {
        var asked = false
        setTab(pageOpen = false, notebookOpen = false, delete = { asked = true })

        compose.onNodeWithTag(FileTags.DELETE_NOTEBOOK).performTouchInput { click() }

        assertFalse(asked)
    }

    @Test
    fun closeNotebookActsOnASelectedNotebook() {
        var closed = false
        setTab(pageOpen = false, notebookOpen = true, close = { closed = true })

        compose.onNodeWithTag(FileTags.CLOSE_NOTEBOOK).performClick()

        assertTrue(closed)
    }

    /**
     * The same rule Export and Delete follow: with nothing selected there is no notebook for the
     * command to name, and a press that silently picked one would be worse than an inert button.
     */
    @Test
    fun closeNotebookIsInertWithoutASelectedNotebook() {
        var closed = false
        setTab(pageOpen = false, notebookOpen = false, close = { closed = true })

        compose.onNodeWithTag(FileTags.CLOSE_NOTEBOOK).performTouchInput { click() }

        assertFalse(closed)
    }

    /** The shelf is a place, not a command about the open file, so it needs nothing selected. */
    @Test
    fun closedNotebooksIsAvailableWithoutAnOpenNotebookOrPage() {
        var opened = false
        setTab(pageOpen = false, notebookOpen = false, closedNotebooks = { opened = true })

        compose.onNodeWithTag(FileTags.CLOSED_NOTEBOOKS).performClick()

        assertTrue(opened)
    }

    private fun setTab(
        pageOpen: Boolean,
        notebookOpen: Boolean = pageOpen,
        history: () -> Unit = {},
        export: () -> Unit = {},
        import: () -> Unit = {},
        delete: () -> Unit = {},
        deletedItems: () -> Unit = {},
        close: () -> Unit = {},
        closedNotebooks: () -> Unit = {},
    ) {
        compose.setContent {
            ViveNotesTheme {
                FileTab(
                    actions = FileActions(
                        openVersionHistory = history,
                        exportNotebook = export,
                        importNotebook = import,
                        deleteNotebook = delete,
                        openDeletedItems = deletedItems,
                        closeNotebook = close,
                        openClosedNotebooks = closedNotebooks,
                    ),
                    pageOpen = pageOpen,
                    notebookOpen = notebookOpen,
                )
            }
        }
    }
}
