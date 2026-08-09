package com.vivenotes.ui.panel

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Dismissing a docked pane by pushing it off the edge it is docked to.
 *
 * The close button is not the only way out: the notebook rail and page list already hide on a
 * leftward drag, so the pane on the right closes on a rightward one and the shell reads the same in
 * both directions.
 */
class ToolPanelDismissTest {

    @get:Rule
    val compose = createComposeRule()

    private var closed = 0

    @Test
    fun swipingRightClosesThePane() {
        setPanel()

        compose.onNodeWithTag(PanelTags.PANE).performTouchInput { swipeRight() }

        assertEquals(1, closed)
    }

    /** The opposite drag is not a dismissal — it would fight the canvas it is docked beside. */
    @Test
    fun swipingLeftLeavesThePaneOpen() {
        setPanel()

        compose.onNodeWithTag(PanelTags.PANE).performTouchInput { swipeLeft() }

        assertEquals(0, closed)
    }

    /** The gesture is an addition, not a replacement. */
    @Test
    fun theCloseButtonStillWorks() {
        setPanel()

        compose.onNodeWithContentDescription("Close ${ToolPane.Hardware.title}").performClick()

        assertEquals(1, closed)
    }

    private fun setPanel() {
        compose.setContent {
            ViveNotesTheme {
                ToolPanel(pane = ToolPane.Hardware, onClose = { closed++ }) {
                    Text("Anything at all")
                }
            }
        }
    }
}
