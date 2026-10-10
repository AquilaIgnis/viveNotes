package com.vivenotes.ui

import android.content.res.Resources
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.annotation.StringRes
import com.vivenotes.R

/**
 * The app's hardware-keyboard shortcuts.
 *
 * One table, two readers: [APP_SHORTCUTS] is both what [handleShortcut] dispatches and what
 * [shortcutGroups] hands the system's Meta + / helper panel. A shortcut that works but is not listed
 * is one nobody finds; one that is listed but does not work is worse.
 *
 * These are the global ones — they act on the page, the notebook or the view, so they must fire
 * wherever the focus is. The formatting shortcuts are not here: Ctrl+B and Tab act on the caret, so
 * they live in `richtext/OutlineEditText.onKeyDown`. They appear below with a null
 * [AppShortcut.action], which lists them in the helper without dispatching them.
 *
 * `Activity.onKeyDown` is deliberately the last stop: it runs only after the focused view has
 * declined the key, which is what makes Ctrl+Z do the right thing in both places. Dispatching from
 * `dispatchKeyEvent` would take Ctrl+Z away from the editor, and Ctrl+A with it.
 */
internal data class AppShortcut(
    /** How the helper panel names it. */
    @StringRes val label: Int,
    /** The helper panel's heading this sits under. */
    @StringRes val group: Int,
    val keyCode: Int,
    /**
     * Modifiers that must be held, and *only* these — matched with [KeyEvent.hasModifiers], which is
     * exact. That is what keeps Ctrl+Shift+Z from also firing Ctrl+Z.
     */
    val modifiers: Int = KeyEvent.META_CTRL_ON,
    /**
     * Whether holding the key repeats the action. True only where repeating is the point: holding
     * Ctrl+= should keep zooming, holding Ctrl+N should not keep making pages.
     */
    val repeatable: Boolean = false,
    /** False for a second key binding onto the same action, so the panel lists one row, not two. */
    val listed: Boolean = true,
    /** Null for the shortcuts the focused view handles itself; those are listed, never dispatched. */
    val action: ((NotesViewModel) -> Unit)? = null,
)

private const val CTRL = KeyEvent.META_CTRL_ON
private const val CTRL_SHIFT = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON

private val PAGES = R.string.shortcut_group_pages
private val EDIT = R.string.shortcut_group_edit
private val VIEW = R.string.shortcut_group_view
private val FORMATTING = R.string.shortcut_group_formatting
private val PARAGRAPH = R.string.shortcut_group_paragraph
private val TABLE = R.string.shortcut_group_table

internal val APP_SHORTCUTS: List<AppShortcut> = listOf(
    AppShortcut(R.string.shortcut_new_page, PAGES, KeyEvent.KEYCODE_N, CTRL) { it.addPage() },

    AppShortcut(R.string.shortcut_undo, EDIT, KeyEvent.KEYCODE_Z, CTRL, repeatable = true) { it.undoCanvas() },
    AppShortcut(R.string.shortcut_redo, EDIT, KeyEvent.KEYCODE_Z, CTRL_SHIFT, repeatable = true) { it.redoCanvas() },

    AppShortcut(R.string.shortcut_zoom_in, VIEW, KeyEvent.KEYCODE_EQUALS, CTRL, repeatable = true) { it.zoomIn() },
    AppShortcut(R.string.shortcut_zoom_out, VIEW, KeyEvent.KEYCODE_MINUS, CTRL, repeatable = true) { it.zoomOut() },
    AppShortcut(R.string.shortcut_actual_size, VIEW, KeyEvent.KEYCODE_0, CTRL) { it.setZoom(1f) },

    // The same three keys as most people actually press them. A keyboard's "+" is Shift+= on the
    // main block and a key of its own on the numpad, and neither reaches the row above: matching is
    // exact, so Ctrl+Shift+= is a different chord from Ctrl+=. Unlisted, because the panel should
    // name one way to zoom in rather than three.
    AppShortcut(R.string.shortcut_zoom_in, VIEW, KeyEvent.KEYCODE_EQUALS, CTRL_SHIFT, repeatable = true, listed = false) { it.zoomIn() },
    AppShortcut(R.string.shortcut_zoom_in, VIEW, KeyEvent.KEYCODE_NUMPAD_ADD, CTRL, repeatable = true, listed = false) { it.zoomIn() },
    AppShortcut(R.string.shortcut_zoom_out, VIEW, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, CTRL, repeatable = true, listed = false) { it.zoomOut() },
    AppShortcut(R.string.shortcut_actual_size, VIEW, KeyEvent.KEYCODE_NUMPAD_0, CTRL, listed = false) { it.setZoom(1f) },

    // Handled by the focused editor, listed here so the panel tells the whole truth — see the KDoc.
    AppShortcut(R.string.shortcut_bold, FORMATTING, KeyEvent.KEYCODE_B, CTRL),
    AppShortcut(R.string.shortcut_italic, FORMATTING, KeyEvent.KEYCODE_I, CTRL),
    AppShortcut(R.string.shortcut_underline, FORMATTING, KeyEvent.KEYCODE_U, CTRL),
    AppShortcut(R.string.shortcut_indent, PARAGRAPH, KeyEvent.KEYCODE_TAB, modifiers = 0),
    AppShortcut(R.string.shortcut_outdent, PARAGRAPH, KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON),

    // The same key, listed twice on purpose. Inside a table Tab walks the
    // grid and only indents where the walk runs out, so a panel that named one meaning would be
    // wrong wherever the caret actually was.
    AppShortcut(R.string.shortcut_next_cell, TABLE, KeyEvent.KEYCODE_TAB, modifiers = 0),
    AppShortcut(R.string.shortcut_previous_cell, TABLE, KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON),
)

/**
 * Runs the global shortcut this key press names, if it names one.
 *
 * Returns whether it was consumed, which is what the caller returns from `onKeyDown`.
 */
internal fun NotesViewModel.handleShortcut(keyCode: Int, event: KeyEvent): Boolean {
    val hit = APP_SHORTCUTS.firstOrNull { shortcut ->
        shortcut.action != null &&
            shortcut.keyCode == keyCode &&
            event.hasModifiers(shortcut.modifiers) &&
            (shortcut.repeatable || event.repeatCount == 0)
    } ?: return false
    hit.action?.invoke(this)
    return true
}

/**
 * How a shortcut reads on screen — "Ctrl+Shift+Z".
 *
 * Spelled out here rather than in the panel that shows it, beside the [keyCode] and [modifiers] it
 * is derived from: `KeyEvent.keyCodeToString` returns `KEYCODE_EQUALS`, which is not what anyone has
 * printed on their keyboard, so the mapping is a fact about this table.
 */
internal val AppShortcut.chord: String
    get() = buildString {
        if (modifiers and KeyEvent.META_CTRL_ON != 0) append("Ctrl+")
        if (modifiers and KeyEvent.META_SHIFT_ON != 0) append("Shift+")
        if (modifiers and KeyEvent.META_ALT_ON != 0) append("Alt+")
        append(keyLabel(keyCode))
    }

private fun keyLabel(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> "="
    KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "−"
    KeyEvent.KEYCODE_TAB -> "Tab"
    else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").removePrefix("NUMPAD_")
}

/**
 * The listed shortcuts as the Hardware pane draws them: group heading, then rows.
 *
 * Shares [APP_SHORTCUTS] and its `listed` flag with [shortcutGroups] for the reason given in the
 * KDoc above — a third hand-kept list of shortcuts is a third thing to fall out of date.
 */
internal fun shortcutRows(): List<Pair<Int, List<AppShortcut>>> =
    APP_SHORTCUTS.filter { it.listed }
        .groupBy { it.group }
        .map { (group, shortcuts) -> group to shortcuts }

/** The same table as the system's Meta + / panel wants it — grouped, in declaration order. */
internal fun shortcutGroups(resources: Resources): List<KeyboardShortcutGroup> =
    APP_SHORTCUTS.filter { it.listed }
        .groupBy { it.group }
        .map { (group, shortcuts) ->
            KeyboardShortcutGroup(
                resources.getString(group),
                shortcuts.map { KeyboardShortcutInfo(resources.getString(it.label), it.keyCode, it.modifiers) },
            )
        }
