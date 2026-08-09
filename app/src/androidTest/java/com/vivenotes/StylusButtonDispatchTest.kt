package com.vivenotes

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        // The app opens on Home, so the Draw tab's tools are not on screen to begin with.
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
        compose.runOnUiThread {
            val consumed = compose.activity.onKeyDown(
                KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY),
            )
            assertTrue("the down-press must still be consumed", consumed)
        }
        compose.waitForIdle()

        // Still on Home: the down-press changed nothing, so the Draw tab never came forward.
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

    private fun pressStylusButton(keyCode: Int = KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY) {
        compose.runOnUiThread {
            compose.activity.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        compose.waitForIdle()
    }
}
