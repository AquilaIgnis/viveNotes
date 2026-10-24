package com.vivenotes.ui

import android.content.res.Resources
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.vivenotes.R
import com.vivenotes.keyboard.KeyBindings
import com.vivenotes.keyboard.ShortcutAction
import com.vivenotes.keyboard.ShortcutDecision
import com.vivenotes.keyboard.ShortcutScope
import com.vivenotes.keyboard.keyCode
import com.vivenotes.keyboard.metaState
import com.vivenotes.keyboard.toChord

/**
 * The app's hardware-keyboard shortcuts, dispatched from the one table in `keyboard/KeyBindings.kt`
 * with the user's changes from Hardware ▸ Keyboard on top. The table is both what runs here and what
 * [shortcutGroups] hands the system's Meta + / helper: a shortcut that works but is not listed is one
 * nobody finds; one that is listed but does not work is worse.
 *
 * Three points of dispatch, one per [ShortcutScope] that is not the text box's own:
 * - [ShortcutScope.Anywhere] from `MainActivity.dispatchKeyEvent`, ahead of the focused view: Esc
 *   leaves a text box as readily as it puts a pen down.
 * - [ShortcutScope.Workspace] from `MainActivity.onKeyDown`, which runs only after the focused view
 *   has declined the key. That is what lets a text box keep its own keys, and lets Ctrl+Z with
 *   nothing on the canvas to undo go on to the text's own undo.
 * - [ShortcutScope.OutsideText] from the same place, and only while no text field has the keyboard.
 *
 * [ShortcutScope.TextBox] is `richtext/OutlineEditText.onKeyDown`'s, ahead of `TextView`'s own keys.
 */
internal fun NotesViewModel.handleShortcut(event: KeyEvent, scope: ShortcutScope): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN) return false
    val chord = event.toChord() ?: return false
    return when (val decision = keyBindings.value.decide(chord, scope, repeat = event.repeatCount > 0)) {
        ShortcutDecision.Pass -> false
        ShortcutDecision.Swallow -> true
        is ShortcutDecision.Run -> runShortcut(decision.action)
    }
}

/**
 * What each workspace command does — the same thing the ribbon, the toolkit and the panes do.
 * Returns whether the key was used: an undo with nothing to undo leaves the key to whatever else
 * wants it, which in a text box is the text's own undo.
 *
 * The text box's commands are `OutlineEditText`'s and are not run from here; it hands this only the
 * workspace commands a text box key was given.
 */
internal fun NotesViewModel.runShortcut(action: ShortcutAction): Boolean {
    when (action) {
        ShortcutAction.Search -> requestSearch()
        ShortcutAction.NewPage -> addPage()
        ShortcutAction.Undo -> if (canvasUndoState.value.canUndo) undoCanvas() else return false
        ShortcutAction.Redo -> if (canvasUndoState.value.canRedo) redoCanvas() else return false
        ShortcutAction.DeleteSelection -> return deleteCanvasSelection()
        ShortcutAction.ZoomIn -> zoomIn()
        ShortcutAction.ZoomOut -> zoomOut()
        ShortcutAction.ActualSize -> setZoom(1f)
        ShortcutAction.SelectTool -> return putDownTool()
        ShortcutAction.TextTool -> toggleTextTool()
        ShortcutAction.Cut,
        ShortcutAction.Copy,
        ShortcutAction.Paste,
        ShortcutAction.PastePlainText,
        ShortcutAction.SelectAll,
        ShortcutAction.Bold,
        ShortcutAction.Italic,
        ShortcutAction.Underline,
        ShortcutAction.Indent,
        ShortcutAction.Outdent,
        ShortcutAction.ExitList,
        -> return false
    }
    return true
}

/**
 * The two rows the Hardware pane and the Meta + / helper add under Table: Indent's and Outdent's
 * keys, as they act in a table cell. Not commands of their own — inside a table those keys walk the
 * grid and indent only where the walk runs out, so a list naming one meaning would be wrong wherever
 * the caret actually was — and so they follow whatever Indent and Outdent are bound to.
 */
internal val TABLE_ROWS: List<Pair<Int, ShortcutAction>> = listOf(
    R.string.shortcut_next_cell to ShortcutAction.Indent,
    R.string.shortcut_previous_cell to ShortcutAction.Outdent,
)

/** The bindings as the system's Meta + / helper wants them — grouped, in the table's order. */
internal fun shortcutGroups(resources: Resources, bindings: KeyBindings): List<KeyboardShortcutGroup> {
    fun info(label: Int, action: ShortcutAction): KeyboardShortcutInfo? = bindings.primary(action)?.let { chord ->
        KeyboardShortcutInfo(resources.getString(label), chord.key.keyCode, chord.metaState)
    }
    return ShortcutAction.groups.map { (group, actions) ->
        KeyboardShortcutGroup(resources.getString(group.label), actions.mapNotNull { info(it.label, it) })
    } + KeyboardShortcutGroup(
        resources.getString(R.string.shortcut_group_table),
        TABLE_ROWS.mapNotNull { (label, action) -> info(label, action) },
    )
}
