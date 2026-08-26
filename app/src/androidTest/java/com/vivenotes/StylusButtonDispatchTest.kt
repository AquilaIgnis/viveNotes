package com.vivenotes

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stylus's first barrel button, dispatched through the real activity.
 *
 * **Why the key is delivered by hand.** `adb shell input keyevent 522` does not work: the shell's
 * injector does not know `KEYCODE_STYLUS_BUTTON_PRIMARY` and delivers keycode 0 instead — verified
 * on this emulator, where an ordinary keycode in the same breath arrived intact. Nor does the
 * emulator have a stylus to press. So the press is handed to `onKeyDown` directly, which exercises
 * everything this app owns: the activity's dispatch, `handleStylusButton`, the tool change and the
 * tab that follows it.
 *
 * What it cannot prove is the platform's half — that a real pen's button arrives as this keycode.
 * That is Android 14+ documented behaviour and `minSdk` is 35, but it wants a stylus to confirm.
 */
@RunWith(AndroidJUnit4::class)
class StylusButtonDispatchTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theButtonBringsTheDrawTabForward() {
        openDocumentTab()
        compose.onNodeWithContentDescription("Pen 1").assertDoesNotExist()

        pressStylusButton()

        compose.onNodeWithContentDescription("Pen 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Eraser").assertIsDisplayed()
    }

    /** Pressed from the Draw tab it stays there, rather than toggling the tab along with the tool. */
    @Test
    fun aSecondPressLeavesTheDrawTabUp() {
        pressStylusButton()
        pressStylusButton()

        compose.onNodeWithContentDescription("Pen 1").assertIsDisplayed()
    }

    /**
     * The down-press is claimed but does nothing, so nothing else can act on the same click and the
     * tool does not change twice.
     */
    @Test
    fun theDownPressIsClaimedWithoutActing() {
        openDocumentTab()
        compose.runOnUiThread {
            val consumed = compose.activity.onKeyDown(
                KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY),
            )
            assertTrue("the down-press must still be consumed", consumed)
        }
        compose.waitForIdle()

        // Still on Document: the down-press changed nothing, so Draw never came forward.
        compose.onNodeWithContentDescription("Pen 1").assertDoesNotExist()
    }

    /** The Lenovo pen's one-click keycode, which never delivers a down-press at all. */
    @Test
    fun theVendorOneClickKeycodeAlsoArmsTheTool() {
        pressStylusButton(keyCode = 600)

        compose.onNodeWithContentDescription("Pen 1").assertIsDisplayed()
    }

    /** And its two-click keycode reaches the lasso without any timing on this side. */
    @Test
    fun theVendorTwoClickKeycodeReachesTheLasso() {
        pressStylusButton(keyCode = 601)

        compose.onNodeWithContentDescription("Lasso").assertIsDisplayed()
    }

    /**
     * A press bound to nothing is **not consumed, at either end** — `memory/stylusPlan.md` SB5.
     *
     * Three clicks is unbound by default, and the failure this guards is the tempting one: claiming
     * every stylus keycode at key-down while acting only on the bound ones at up. That would leave an
     * unbound press swallowed rather than falling through to whatever else wanted it, which is a
     * property the feature deliberately has and which nothing else would notice.
     */
    @Test
    fun anUnboundClickCountIsLeftToFallThrough() {
        openDocumentTab()
        compose.runOnUiThread {
            assertFalse(
                "an unbound press must not be claimed at down",
                compose.activity.onKeyDown(
                    VENDOR_THREE_CLICK,
                    KeyEvent(KeyEvent.ACTION_DOWN, VENDOR_THREE_CLICK),
                ),
            )
            assertFalse(
                "an unbound press must not be consumed at up",
                compose.activity.onKeyUp(
                    VENDOR_THREE_CLICK,
                    KeyEvent(KeyEvent.ACTION_UP, VENDOR_THREE_CLICK),
                ),
            )
        }
        compose.waitForIdle()

        // And it did nothing: still on Document, with no tool armed by it.
        compose.onNodeWithContentDescription("Pen 1").assertDoesNotExist()
    }

    /** Draw is the startup tab now, so tests of tab forwarding first move somewhere else. */
    private fun openDocumentTab() {
        compose.onNodeWithText("Document").performClick()
        compose.waitForIdle()
    }

    private fun pressStylusButton(keyCode: Int = KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY) {
        compose.runOnUiThread {
            compose.activity.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        compose.waitForIdle()
    }

    private companion object {
        /** `PEN_THREE_CLICK` on the Lenovo pen — `docs/stylusCodes.md`. */
        const val VENDOR_THREE_CLICK = 602
    }
}
