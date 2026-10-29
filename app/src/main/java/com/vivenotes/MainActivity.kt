package com.vivenotes

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vivenotes.keyboard.ShortcutScope
import com.vivenotes.ui.NotesApp
import com.vivenotes.ui.NotesViewModel
import com.vivenotes.ui.handleShortcut
import com.vivenotes.ui.claimsStylusButton
import com.vivenotes.ui.handleStylusButton
import com.vivenotes.ui.shortcutGroups
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: NotesViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as NotesApplication
        viewModel = ViewModelProvider(
            this,
            NotesViewModel.factory(
                app.repository,
                app.attachments,
                app.editorDefaults,
                app.viewSettings,
                app.penSettings,
                app.databaseBackups,
                app.notebookTransfers,
                app.imageText,
                app.inkText,
                remoteInk = app.syncAccounts.remoteInk,
                maySeedStarter = { app.syncAccounts.maySeedStarter() },
                systemClipboard = app.systemClipboard,
            ),
        )[NotesViewModel::class.java]

        setContent {
            ViveNotesTheme {
                NotesApp(
                    viewModel,
                    app.attachments,
                    app.videoThumbnails,
                    app.aiModels,
                    app.recognitionEngine,
                    app.mathEngine,
                    app.syncAccounts,
                    app.managedSubscription,
                    app.pdfExporter,
                    app.onlineImages,
                    onNotebookClosed = {
                        lifecycleScope.launch { app.reviewPrompt.afterNotebookClosed(this@MainActivity) }
                    },
                )
            }
        }

        // The 20th-session rating ask. STARTED rather than RESUMED: Play's sheet is its own
        // Activity, and pausing this one under it must not cancel the request that opened it.
        // A sideloaded build's flow is a single false that then completes, so running out without
        // a true means "never", not an error.
        // Whether a paste onto the canvas has another app's copy to put there. Android tells only
        // the app in focus of a new clip, so it is asked again each time this comes back as well.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.refreshClipboard()
                val changes = app.systemClipboard.observe(viewModel::refreshClipboard)
                try {
                    awaitCancellation()
                } finally {
                    changes.close()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (app.reviewPrompt.sessionsReached.firstOrNull { it } == true) {
                    app.reviewPrompt.askForSessions(this@MainActivity)
                }
            }
        }
    }

    /**
     * The shortcuts that go ahead of the focused view — Esc and Ctrl+F; see `ui/KeyboardShortcuts.kt`.
     * Everything else waits for [onKeyDown].
     *
     * The suppression is for a false positive: this overrides the public `Activity` method, but
     * `androidx.core`'s `ComponentActivity` redeclares it under `@RestrictTo`, which lint reads as
     * the override and the `super` call reaching into a library-private API.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        viewModel.handleShortcut(event, ShortcutScope.Anywhere) || super.dispatchKeyEvent(event)

    /**
     * The app-wide half of L2 — `ui/KeyboardShortcuts.kt` holds the dispatch and says why there are
     * three points of it.
     *
     * `onKeyDown` rather than `dispatchKeyEvent` for these: this runs *after* the focused view has
     * had the key and declined it, so a text container keeps its own keys and only the presses
     * nothing else wanted reach the canvas.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        // Claimed but not acted on: a stylus button is handled at up — `ui/StylusButtons.kt` says
        // why. Claiming the down-press keeps anything else from acting on the same click. Only a
        // *bound* press is claimed, or an unbound one would be swallowed rather than fall through.
        viewModel.claimsStylusButton(keyCode) ||
            viewModel.handleShortcut(event, ShortcutScope.Workspace) ||
            (!textHasKeyboard() && viewModel.handleShortcut(event, ShortcutScope.OutsideText)) ||
            super.onKeyDown(keyCode, event)

    /**
     * Whether a text field holds the keyboard, where a key that types is a letter and never a
     * shortcut. A text box says so itself; a Compose field — the title, the search query — is seen
     * through the input connection it opens while it has the focus.
     */
    private fun textHasKeyboard(): Boolean =
        viewModel.selection.value.editorFocused ||
            currentFocus is EditText ||
            getSystemService(InputMethodManager::class.java)?.isAcceptingText == true

    /** Where a stylus barrel button is acted on — see `ui/StylusButtons.kt`. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        viewModel.handleStylusButton(keyCode) || super.onKeyUp(keyCode, event)

    /** Back in focus, possibly with something new copied elsewhere — in split screen, say. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) viewModel.refreshClipboard()
    }

    /** What the system's Meta + / shortcut panel lists for this app. */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        data?.addAll(shortcutGroups(resources, viewModel.keyBindings.value))
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
    }
}
