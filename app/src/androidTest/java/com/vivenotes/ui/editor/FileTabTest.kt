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

    private fun setTab(
        pageOpen: Boolean,
        notebookOpen: Boolean = pageOpen,
        history: () -> Unit = {},
        export: () -> Unit = {},
        import: () -> Unit = {},
    ) {
        compose.setContent {
            ViveNotesTheme {
                FileTab(
                    actions = FileActions(history, export, import),
                    pageOpen = pageOpen,
                    notebookOpen = notebookOpen,
                )
            }
        }
    }
}
