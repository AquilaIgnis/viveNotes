package com.vivenotes

import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.vivenotes.ui.NotesApp
import com.vivenotes.ui.NotesViewModel
import com.vivenotes.ui.handleShortcut
import com.vivenotes.ui.shortcutGroups
import com.vivenotes.ui.theme.ViveNotesTheme

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
                app.editorDefaults,
                app.viewSettings,
                app.penSettings,
            ),
        )[NotesViewModel::class.java]

        setContent {
            ViveNotesTheme {
                NotesApp(viewModel, app.aiModels, app.recognitionEngine, app.mathEngine)
            }
        }
    }

    /**
     * The app-wide half of L2 — `ui/KeyboardShortcuts.kt` holds the table and says why it is a table.
     *
     * `onKeyDown` rather than `dispatchKeyEvent` on purpose: this runs *after* the focused view has
     * had the key and declined it, so a text container keeps Ctrl+Z for its own undo and only the
     * presses nothing else wanted reach the canvas.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        viewModel.handleShortcut(keyCode, event) || super.onKeyDown(keyCode, event)

    /** What the system's Meta + / shortcut panel lists for this app. */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        data?.addAll(shortcutGroups())
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
    }
}
