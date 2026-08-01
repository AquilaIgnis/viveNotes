package st.unamedtba.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import st.unamedtba.ui.theme.UnamedTbaTheme

/**
 * The ribbon overflows on any window narrower than its widest tab, and a clipped strip of buttons
 * is indistinguishable from a strip that ends. These check that the arrows appear exactly when
 * there is something to reach.
 */
class ScrollingRowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: ScrollState

    private fun setRow(items: Int, width: Int = 200) {
        compose.setContent {
            UnamedTbaTheme {
                state = rememberScrollState()
                Box(Modifier.width(width.dp).height(48.dp)) {
                    ScrollingRow(state = state) {
                        repeat(items) { Item("item $it") }
                    }
                }
            }
        }
    }

    @Composable
    private fun Item(label: String) {
        Box(Modifier.width(80.dp).height(32.dp)) { Text(label) }
    }

    @Test
    fun contentThatOverflowsOffersAWayToReachIt() {
        setRow(items = 10)

        compose.onNodeWithTag(ScrollEdgeTags.END).assertIsDisplayed()
    }

    @Test
    fun thereIsNothingBehindTheStartUntilTheRowHasMoved() {
        setRow(items = 10)

        compose.onNodeWithTag(ScrollEdgeTags.START).assertDoesNotExist()
    }

    @Test
    fun contentThatFitsShowsNoArrowsAtAll() {
        setRow(items = 1, width = 300)

        compose.onNodeWithTag(ScrollEdgeTags.END).assertDoesNotExist()
        compose.onNodeWithTag(ScrollEdgeTags.START).assertDoesNotExist()
    }

    @Test
    fun scrollingRevealsTheArrowBehindYou() {
        setRow(items = 10)

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertTrue("the row did not scroll, offset ${state.value}", state.value > 0)
        compose.onNodeWithTag(ScrollEdgeTags.START).assertIsDisplayed()
    }

    @Test
    fun theEndArrowGoesAwayOnceThereIsNothingLeftToReach() {
        // Three 80dp items in a 200dp window: one swipe takes it past the end.
        setRow(items = 3)

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertTrue("expected to be at the end, offset ${state.value}", !state.canScrollForward)
        compose.onNodeWithTag(ScrollEdgeTags.END).assertDoesNotExist()
    }

    /**
     * The arrow sits on top of whatever control is at the edge, so it must be invisible to touch.
     * An earlier version made these buttons, and the last control in the View tab stopped working.
     */
    @Test
    fun anArrowDoesNotSwallowPressesMeantForTheControlUnderIt() {
        var pressed = false
        compose.setContent {
            UnamedTbaTheme {
                state = rememberScrollState()
                Box(Modifier.width(200.dp).height(48.dp)) {
                    ScrollingRow(state = state) {
                        repeat(4) { Item("item $it") }
                        Box(
                            Modifier
                                .testTag("edge-control")
                                .width(80.dp)
                                .height(32.dp)
                                .clickable { pressed = true },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag(ScrollEdgeTags.END).assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("edge-control").performClick()

        assertTrue("the scroll indicator ate the press", pressed)
    }
}
