package com.vivenotes

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.ui.NotesViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the canvas has another app's copy to paste, through the real activity: asked again when
 * the clip changes and when the app comes back into focus.
 *
 * On the app as installed, so only the clipboard is touched — no notebook is changed.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardRefreshDispatchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val viewModel: NotesViewModel get() = ViewModelProvider(compose.activity)[NotesViewModel::class.java]
    private val clipboard: ClipboardManager get() = compose.activity.getSystemService(ClipboardManager::class.java)

    /** Each in one go on the main thread: no change listener can run in between, only the focus call. */
    @Test
    fun comingBackIntoFocusAsksTheClipboardAgain() {
        compose.runOnUiThread {
            clipboard.clearPrimaryClip()
            compose.activity.onWindowFocusChanged(true)
            assertFalse(viewModel.hasClipboard.value)
            clipboard.setPrimaryClip(ClipData.newPlainText("note", "copied elsewhere"))
            compose.activity.onWindowFocusChanged(true)
            assertTrue(viewModel.hasClipboard.value)
        }
    }

    @Test
    fun aNewClipIsHeardWhileTheAppIsInFocus() {
        compose.runOnUiThread {
            clipboard.clearPrimaryClip()
            compose.activity.onWindowFocusChanged(true)
            assertFalse(viewModel.hasClipboard.value)
            clipboard.setPrimaryClip(ClipData.newPlainText("note", "copied elsewhere"))
        }
        compose.waitUntil(5_000) { viewModel.hasClipboard.value }
    }
}
