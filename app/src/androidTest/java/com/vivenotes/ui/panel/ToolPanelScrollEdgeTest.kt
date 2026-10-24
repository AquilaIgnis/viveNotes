package com.vivenotes.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ScrollEdgeTags
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A docked pane taller than its window says there is more above or below it, the way the ribbon's
 * rows say there is more to either side — and says nothing when it all fits.
 */
class ToolPanelScrollEdgeTest {

    @get:Rule
    val compose = createComposeRule()

    private val clicked = mutableSetOf<Int>()

    @Test
    fun aTallPaneShowsMoreBelowUntilItIsScrolledToTheEnd() {
        setPanel(rows = 40)

        compose.onNodeWithTag(ScrollEdgeTags.BOTTOM).assertIsDisplayed()
        compose.onNodeWithTag(ScrollEdgeTags.TOP).assertDoesNotExist()

        // Dragged to the end, as a person does: scrolling only until the last row shows leaves the
        // pane's bottom padding below it, and the edge is right to say so.
        repeat(6) { compose.onNodeWithTag(PanelTags.PANE).performTouchInput { swipeUp() } }
        compose.waitForIdle()
        compose.onNodeWithTag(row(39)).assertIsDisplayed()

        compose.onNodeWithTag(ScrollEdgeTags.TOP).assertIsDisplayed()
        compose.onNodeWithTag(ScrollEdgeTags.BOTTOM).assertDoesNotExist()
    }

    @Test
    fun aPaneThatFitsShowsNeitherEdge() {
        setPanel(rows = 2)

        compose.onNodeWithTag(ScrollEdgeTags.TOP).assertDoesNotExist()
        compose.onNodeWithTag(ScrollEdgeTags.BOTTOM).assertDoesNotExist()
    }

    /** The fade only indicates: a press on it lands on the row underneath, as on the ribbon. */
    @Test
    fun aPressOnTheEdgeReachesTheRowUnderIt() {
        setPanel(rows = 40)

        compose.onNodeWithTag(ScrollEdgeTags.BOTTOM).performClick()

        assertTrue("the edge took the press", clicked.isNotEmpty())
    }

    private fun row(index: Int) = "row-$index"

    private fun setPanel(rows: Int) {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.height(420.dp)) {
                    ToolPanel(pane = ToolPane.Hardware, onClose = {}) {
                        repeat(rows) { index ->
                            Text(
                                text = "Setting $index",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .clickable { clicked += index }
                                    .testTag(row(index)),
                            )
                        }
                    }
                }
            }
        }
    }
}
