package com.vivenotes.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.keyboard.KeyBindings
import com.vivenotes.keyboard.KeyChord
import com.vivenotes.keyboard.ShortcutAction
import com.vivenotes.keyboard.ShortcutKey
import com.vivenotes.keyboard.toChord
import com.vivenotes.ui.icons.MaterialSymbols

internal object ShortcutDialogTags {
    const val CAPTURE = "hardware-shortcut-capture"
    const val CAPTURED = "hardware-shortcut-captured"
    const val MESSAGE = "hardware-shortcut-message"
    const val REPLACE = "hardware-shortcut-replace"
    const val CANCEL = "hardware-shortcut-cancel"
    const val RESET_ALL = "hardware-reset-all-dialog"
    const val CONFIRM_RESET_ALL = "hardware-reset-all-confirm"
    const val CANCEL_RESET_ALL = "hardware-reset-all-cancel"
}

/**
 * Asks for [action]'s new keys, as the desktop port does it and GNOME Settings before it: the next
 * chord pressed becomes the shortcut, Esc cancels, and Backspace disables it. A chord another command
 * already uses is held until Replace (or Enter) confirms taking it; one that needs a modifier to be
 * [action]'s shortcut — a key that types, for a command that can run in a text field — is refused.
 * [onSet] receives the chord, or null to disable.
 *
 * A dialog because it is a window of its own: keys pressed in it never reach `MainActivity`, whose
 * Esc and Ctrl+F would otherwise act before the key could be read.
 */
@Composable
internal fun ShortcutCaptureDialog(
    action: ShortcutAction,
    bindings: KeyBindings,
    onSet: (KeyChord?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember(action) { mutableStateOf<KeyChord?>(null) }
    val focus = remember { FocusRequester() }
    // The keys go to whatever has the focus, so the capture area has to have it. A request is
    // refused until the dialog's window has the focus, which comes a moment after it opens, and the
    // content's view is the activity's, so that window's focus event cannot be heard from here.
    // Asked again on a real-time timer until it is granted, for two seconds at most.
    val view = LocalView.current
    DisposableEffect(view) {
        var attempts = 0
        val request = object : Runnable {
            override fun run() {
                if (focus.requestFocus() || ++attempts >= FOCUS_ATTEMPTS) return
                view.postDelayed(this, FOCUS_RETRY_MS)
            }
        }
        view.post(request)
        onDispose { view.removeCallbacks(request) }
    }
    val held = pending
    val conflict = held?.takeIf(action::accepts)?.let { bindings.conflict(action, it) }
    val name = stringResource(action.label)

    fun capture(chord: KeyChord) {
        when {
            // Its own keys again: nothing changes, and a zoom keeps its numpad and Shift spellings.
            chord in bindings.chords(action) -> onDismiss()
            action.accepts(chord) && bindings.conflict(action, chord) == null -> onSet(chord)
            else -> pending = chord
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(MaterialSymbols.Keyboard, contentDescription = null) },
        title = { Text(stringResource(R.string.shortcut_capture_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                                        .focusable()
                    // Every key with a chord is the answer, so none of them may reach anything else.
                    // Back has none and goes on to close the dialog, as everywhere.
                    .onPreviewKeyEvent { event ->
                        val chord = event.nativeKeyEvent.toChord() ?: return@onPreviewKeyEvent false
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        when {
                            chord == KeyChord(ShortcutKey.Escape) -> onDismiss()
                            chord == KeyChord(ShortcutKey.Backspace) -> onSet(null)
                            chord == KeyChord(ShortcutKey.Enter) && conflict != null -> onSet(held)
                            event.nativeKeyEvent.repeatCount == 0 -> capture(chord)
                        }
                        true
                    }
                    .testTag(ShortcutDialogTags.CAPTURE),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.shortcut_capture_prompt, name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Keycap(
                    label = held?.label ?: "…",
                    large = true,
                    modifier = Modifier.padding(vertical = 4.dp).testTag(ShortcutDialogTags.CAPTURED),
                )
                val refused = held?.takeUnless(action::accepts)
                Text(
                    text = when {
                        refused != null -> stringResource(R.string.shortcut_capture_needs_modifier, refused.label)
                        conflict != null ->
                            stringResource(R.string.shortcut_capture_conflict, held?.label.orEmpty(), stringResource(conflict.label))
                        else -> stringResource(
                            R.string.shortcut_capture_now,
                            bindings.primary(action)?.label ?: stringResource(R.string.hardware_shortcut_disabled),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (refused != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.heightIn(min = 32.dp).testTag(ShortcutDialogTags.MESSAGE),
                )
            }
        },
        confirmButton = {
            if (conflict != null) {
                TextButton(onClick = { onSet(held) }, modifier = Modifier.testTag(ShortcutDialogTags.REPLACE)) {
                    Text(stringResource(R.string.shortcut_capture_replace))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ShortcutDialogTags.CANCEL)) {
                Text(stringResource(R.string.shortcut_capture_cancel))
            }
        },
    )
}

private const val FOCUS_ATTEMPTS = 40
private const val FOCUS_RETRY_MS = 50L

/** Confirms Reset all: it throws away every change at once, and there is no undoing it. */
@Composable
internal fun ResetAllShortcutsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(MaterialSymbols.RestartAlt, contentDescription = null) },
        title = { Text(stringResource(R.string.shortcut_reset_all_title)) },
        text = { Text(stringResource(R.string.shortcut_reset_all_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag(ShortcutDialogTags.CONFIRM_RESET_ALL),
            ) { Text(stringResource(R.string.shortcut_reset_all_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ShortcutDialogTags.CANCEL_RESET_ALL)) {
                Text(stringResource(R.string.shortcut_capture_cancel))
            }
        },
        modifier = Modifier.testTag(ShortcutDialogTags.RESET_ALL),
    )
}

/** A chord as it is printed on the keys: "Ctrl+Shift+Z" in a small raised box. */
@Composable
internal fun Keycap(label: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = if (large) 14.dp else 8.dp, vertical = if (large) 8.dp else 3.dp),
    ) {
        Text(
            text = label,
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            color = if (large) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
