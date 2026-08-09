package com.vivenotes.ui.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.vivenotes.ui.panel.ToolPane
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsTabTest {
    @get:Rule
    val compose = createComposeRule()

    private var opened = false
    private var pane: ToolPane? = null

    @Test
    fun integratedOpensTheModelPane() {
        setTab()

        compose.onNodeWithTag(SettingsTags.INTEGRATED).performClick()

        assertTrue(opened)
    }

    @Test
    fun hardwareOpensTheHardwarePane() {
        setTab()

        compose.onNodeWithTag(SettingsTags.HARDWARE).performClick()

        assertEquals(ToolPane.Hardware, pane)
    }

    private fun setTab() {
        compose.setContent {
            ViveNotesTheme {
                SettingsTab(
                    ai = AiActions(openIntegrated = { opened = true }),
                    openPane = { pane = it },
                )
            }
        }
    }
}
