package com.vivenotes

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.ui.panel.ContentPanelTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shortcuts through the real activity: which of its two points of dispatch a key reaches, and
 * that it gets there. The commands themselves are `NotesViewModelTest`'s; the text box's keys are
 * `OutlineEditTextTest`'s.
 *
 * On the app as installed, so only keys that change nothing in a notebook are pressed here: the
 * search pane, and which tool is in hand, neither of which is kept.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardShortcutDispatchTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun key(keyCode: Int, meta: Int = 0): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
    }

    /** Ctrl+F goes ahead of the focused view and opens the Content pane with its query focused. */
    @Test
    fun ctrlFOpensSearchWithTheQueryFocused() {
        compose.runOnUiThread {
            assertTrue(compose.activity.dispatchKeyEvent(key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)))
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(ContentPanelTags.QUERY) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The bare I reaches the canvas only after the focused view declined it; Esc then puts it down. */
    @Test
    fun iArmsTheTextToolFromOnKeyDownAndEscPutsItDownFromDispatch() {
        compose.runOnUiThread {
            assertTrue("I was not taken as the Text box tool", compose.activity.onKeyDown(KeyEvent.KEYCODE_I, key(KeyEvent.KEYCODE_I)))
            assertTrue("Esc had a tool to put down and did not",
                compose.activity.dispatchKeyEvent(key(KeyEvent.KEYCODE_ESCAPE)))
        }
    }
}
