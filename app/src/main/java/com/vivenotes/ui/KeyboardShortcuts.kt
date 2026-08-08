package com.vivenotes.ui

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo

/**
 * The app's hardware-keyboard shortcuts — feature **L2**, `docs/features.md`.
 *
 * **One table, two readers.** [APP_SHORTCUTS] is both what [handleShortcut] dispatches and what
 * [shortcutGroups] hands the system's Meta + / helper panel. A shortcut that works but is not listed
 * is one nobody finds; a shortcut that is listed but does not work is worse, and keeping the two
 * halves in one list is what stops either from happening.
 *
 * **Where a shortcut belongs.** These are the *global* ones — they act on the page, the notebook or
 * the view, so they must fire wherever the focus is. The formatting shortcuts are not here: Ctrl+B
 * and Tab act on the caret, so they live in `richtext/OutlineEditText.onKeyDown` where the caret is.
 * They appear below with a null [AppShortcut.action], which lists them in the helper without
 * dispatching them from the Activity.
 *
 * **`Activity.onKeyDown` is deliberately the last stop.** It runs only after the focused view has
 * declined the key, which is what makes Ctrl+Z do the right thing in both places: inside a text
 * container `EditText` takes it for its own undo, and everywhere else it falls through to here and
 * reverses the last canvas action. Dispatching earlier — from `dispatchKeyEvent` — would take Ctrl+Z
 * away from the editor, and Ctrl+A with it.
 */
internal data class AppShortcut(
    /** How the helper panel names it. */
    val label: String,
    /** The helper panel's heading this sits under. */
    val group: String,
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

internal val APP_SHORTCUTS: List<AppShortcut> = listOf(
    AppShortcut("New page", "Pages", KeyEvent.KEYCODE_N, CTRL) { it.addPage() },

    AppShortcut("Undo", "Edit", KeyEvent.KEYCODE_Z, CTRL, repeatable = true) { it.undoCanvas() },
    AppShortcut("Redo", "Edit", KeyEvent.KEYCODE_Z, CTRL_SHIFT, repeatable = true) { it.redoCanvas() },

    AppShortcut("Zoom in", "View", KeyEvent.KEYCODE_EQUALS, CTRL, repeatable = true) { it.zoomIn() },
    AppShortcut("Zoom out", "View", KeyEvent.KEYCODE_MINUS, CTRL, repeatable = true) { it.zoomOut() },
    AppShortcut("Actual size", "View", KeyEvent.KEYCODE_0, CTRL) { it.setZoom(1f) },

    // The same three keys as most people actually press them. A keyboard's "+" is Shift+= on the
    // main block and a key of its own on the numpad, and neither reaches the row above: matching is
    // exact, so Ctrl+Shift+= is a different chord from Ctrl+=. Unlisted, because the panel should
    // name one way to zoom in rather than three.
    AppShortcut("Zoom in", "View", KeyEvent.KEYCODE_EQUALS, CTRL_SHIFT, repeatable = true, listed = false) { it.zoomIn() },
    AppShortcut("Zoom in", "View", KeyEvent.KEYCODE_NUMPAD_ADD, CTRL, repeatable = true, listed = false) { it.zoomIn() },
    AppShortcut("Zoom out", "View", KeyEvent.KEYCODE_NUMPAD_SUBTRACT, CTRL, repeatable = true, listed = false) { it.zoomOut() },
    AppShortcut("Actual size", "View", KeyEvent.KEYCODE_NUMPAD_0, CTRL, listed = false) { it.setZoom(1f) },

    // Handled by the focused editor, listed here so the panel tells the whole truth — see the KDoc.
    AppShortcut("Bold", "Formatting", KeyEvent.KEYCODE_B, CTRL),
    AppShortcut("Italic", "Formatting", KeyEvent.KEYCODE_I, CTRL),
    AppShortcut("Underline", "Formatting", KeyEvent.KEYCODE_U, CTRL),
    AppShortcut("Indent", "Paragraph", KeyEvent.KEYCODE_TAB, modifiers = 0),
    AppShortcut("Outdent", "Paragraph", KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON),
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

/** The same table as the system's Meta + / panel wants it — grouped, in declaration order. */
internal fun shortcutGroups(): List<KeyboardShortcutGroup> =
    APP_SHORTCUTS.filter { it.listed }
        .groupBy { it.group }
        .map { (group, shortcuts) ->
            KeyboardShortcutGroup(
                group,
                shortcuts.map { KeyboardShortcutInfo(it.label, it.keyCode, it.modifiers) },
            )
        }
