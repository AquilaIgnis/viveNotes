package com.vivenotes.ui.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AiTabTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun integratedOpensTheModelPane() {
        var opened = false
        compose.setContent {
            ViveNotesTheme {
                AiTab(AiActions(openIntegrated = { opened = true }))
            }
        }

        compose.onNodeWithTag(AiTabTags.INTEGRATED).performClick()

        assertTrue(opened)
    }
}
