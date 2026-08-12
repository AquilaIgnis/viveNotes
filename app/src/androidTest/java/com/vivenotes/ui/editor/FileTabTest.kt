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
        setTab(pageOpen = true) { opened = true }

        compose.onNodeWithTag(FileTags.VERSION_HISTORY).performClick()

        assertTrue(opened)
    }

    @Test
    fun versionHistoryIsInertWithoutAnOpenPage() {
        var opened = false
        setTab(pageOpen = false) { opened = true }

        compose.onNodeWithTag(FileTags.VERSION_HISTORY).performTouchInput { click() }

        assertFalse(opened)
    }

    private fun setTab(pageOpen: Boolean, onOpen: () -> Unit) {
        compose.setContent {
            ViveNotesTheme {
                FileTab(
                    actions = FileActions(openVersionHistory = onOpen),
                    pageOpen = pageOpen,
                )
            }
        }
    }
}
