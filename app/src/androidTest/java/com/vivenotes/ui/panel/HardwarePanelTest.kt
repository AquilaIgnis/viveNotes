package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Hardware pane: three devices along the top, and the settings below belong to whichever is
 * selected.
 *
 * The tests worth having here are the ones about the *switch*, since that is the whole structure —
 * a section that shows for the wrong device, or shows for all three, is the failure this pane has.
 */
class HardwarePanelTest {

    @get:Rule
    val compose = createComposeRule()

    private var allowFinger = false

    @Test
    fun itOpensOnStylus() {
        setPanel()

        compose.onNodeWithText("Let a finger draw").assertIsDisplayed()
    }

    @Test
    fun keyboardShowsTheShortcutsAndNotTheStylusSettings() {
        setPanel()

        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Keyboard)).performClick()

        compose.onNodeWithTag(HardwareTags.SHORTCUTS).assertIsDisplayed()
        compose.onNodeWithText("Let a finger draw").assertDoesNotExist()
    }

    /** The table is the live one, so a shortcut that exists is a shortcut that is listed. */
    @Test
    fun theShortcutListNamesRealChords() {
        setPanel()

        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Keyboard)).performClick()

        compose.onNodeWithText("New page").assertIsDisplayed()
        compose.onNodeWithText("Ctrl+N").assertIsDisplayed()
        compose.onNodeWithText("Ctrl+Shift+Z").assertIsDisplayed()
    }

    @Test
    fun mouseSaysThereIsNothingToSet() {
        setPanel()

        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Mouse)).performClick()

        compose.onNodeWithTag(HardwareTags.SHORTCUTS).assertDoesNotExist()
        compose.onNodeWithText("Let a finger draw").assertDoesNotExist()
    }

    @Test
    fun theStylusSectionTogglesWhoMayDraw() {
        setPanel()

        compose.onNodeWithTag(PanelTags.field("Let a finger draw")).performClick()

        assertEquals(true, allowFinger)
    }

    /** Coming back to a device shows its settings again, rather than the one selected in between. */
    @Test
    fun switchingBackToStylusRestoresItsSettings() {
        setPanel()

        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Mouse)).performClick()
        compose.onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).performClick()

        compose.onNodeWithText("Let a finger draw").assertIsDisplayed()
    }

    private fun setPanel() {
        compose.setContent {
            ViveNotesTheme {
                Column {
                    HardwarePanelContent(
                        allowFinger = allowFinger,
                        onSetDrawWithFinger = { allowFinger = it },
                    )
                }
            }
        }
    }
}
