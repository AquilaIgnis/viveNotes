package com.vivenotes.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.StylusAction
import com.vivenotes.data.StylusButtonMap
import com.vivenotes.ui.StylusPress
import com.vivenotes.ui.chord
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.shortcutRows

/**
 * The two kinds of hardware you write at this app with.
 *
 * A mouse is deliberately absent. It had a section, and that section said there was nothing to set —
 * a mouse points, drags and scrolls exactly as a finger does, and is governed by the stylus rules
 * beside it. A tab that exists only to say "nothing here" costs a third of the picker's width and
 * teaches the user to stop reading it.
 *
 * Deliberately not persisted, for the reason the open pane is not: which one you are *looking at* is
 * where you are in a panel, not something about you. Reopening Hardware starting on Stylus is right.
 */
enum class HardwareKind(val label: String) {
    Stylus("Stylus"),
    Keyboard("Keyboard"),
}

internal object HardwareTags {
    fun kind(kind: HardwareKind) = "hardware-${kind.name.lowercase()}"
    const val SHORTCUTS = "hardware-shortcuts"
}

/**
 * The Hardware pane: pick a device along the top, and the settings below are that device's.
 *
 * A row rather than a drop-down because there are exactly two and they are not a list that will grow
 * — a tablet is written on with a pen and typed on with a keyboard, and that is the whole set.
 * Showing both at once also answers the question the pane exists to answer ("what can I configure?")
 * without a tap.
 *
 * **The pane's subject is hardware; the scope of each setting in it is still decided one setting at a
 * time** — the three-way split in `CLAUDE.md` and `docs/inkPlan.md` ID5. *Let a finger draw* describes
 * **this device**: whether a stylus is in the room is not a property of a notebook, and must never
 * travel to another device with one. The button bindings below it describe **the user** (SB3): "double
 * click means highlighter" is a working habit like "pen 2 is red", and it should follow its owner to a
 * second tablet. This paragraph used to claim the whole pane was device scope, which was true when the
 * pane held one toggle.
 */
@Composable
fun ColumnScope.HardwarePanelContent(
    allowFinger: Boolean,
    onSetDrawWithFinger: (Boolean) -> Unit,
    buttons: StylusButtonMap,
    onSetButtons: (StylusButtonMap) -> Unit,
) {
    var kind by remember { mutableStateOf(HardwareKind.Stylus) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HardwareKind.entries.forEach { entry ->
            HardwareTab(
                kind = entry,
                icon = when (entry) {
                    HardwareKind.Stylus -> MaterialSymbols.Stylus
                    HardwareKind.Keyboard -> MaterialSymbols.Keyboard
                },
                selected = entry == kind,
                modifier = Modifier.weight(1f),
                onClick = { kind = entry },
            )
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(10.dp))

    when (kind) {
        HardwareKind.Stylus -> StylusSettings(
            allowFinger = allowFinger,
            onSetDrawWithFinger = onSetDrawWithFinger,
            buttons = buttons,
            onSetButtons = onSetButtons,
        )
        HardwareKind.Keyboard -> KeyboardSettings()
    }
}

/**
 * One device in the picker — `docs/expressivePlan.md` EX7.
 *
 * A real [ToggleButton] rather than the hand-built `Column` with a swapped background colour this
 * used to be. It is the same control Material already has a name for — a segmented, mutually
 * exclusive choice — and the expressive one brings the shape morph on press and check that a
 * background swap cannot express.
 *
 * Two `ToggleButton`s in a `Row` rather than a `ButtonGroup`: that component wants an overflow
 * indicator and exists to collapse a row too long to fit. There are exactly two devices and no third
 * coming — see [HardwareKind] — so its machinery would answer a question this row does not ask.
 */
@Composable
private fun HardwareTab(
    kind: HardwareKind,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ToggleButton(
        checked = selected,
        // A picker, not a set of switches: pressing the selected one again must not clear it and
        // leave the pane showing nothing, so an already-checked press is dropped.
        onCheckedChange = { if (it) onClick() },
        modifier = modifier.testTag(HardwareTags.kind(kind)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            // Null here, unlike the version this replaced: the label is now *inside* the button and
            // TalkBack reads it, so describing the icon too would announce the device twice (L3).
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = kind.label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ColumnScope.StylusSettings(
    allowFinger: Boolean,
    onSetDrawWithFinger: (Boolean) -> Unit,
    buttons: StylusButtonMap,
    onSetButtons: (StylusButtonMap) -> Unit,
) {
    // **The only way to reach this in a shipped build.** The ribbon's finger button is compiled out
    // of release by `BuildConfig.DEBUG` — see `TabStrip` in `ui/editor/Ribbon.kt` — so this row is
    // the setting rather than a second copy of a control already on screen.
    PanelSetting(
        label = "Let a finger draw"
    ) {
        PanelToggle("Let a finger draw", allowFinger, onSetDrawWithFinger)
    }

    PenButtonSettings(buttons, onSetButtons)
}

/**
 * What the barrel button's clicks do — `docs/stylusPlan.md` SB8.
 *
 * Three rows always, even on a pen with no triple click. The app cannot know how many clicks the pen
 * in your pocket reports — a keycode only says what has been pressed, never what *could* be — and a
 * row that never fires is cheaper than a missing row nobody can find.
 *
 * The (i) carries the one thing a user cannot deduce from the rows: the pen counts its own clicks, so
 * these are what it reports rather than a window this app guesses at. That is also the answer to the
 * setting people expect to find here and will not — there is no double-click speed to tune.
 */
@Composable
private fun ColumnScope.PenButtonSettings(
    buttons: StylusButtonMap,
    onSetButtons: (StylusButtonMap) -> Unit,
) {
    PanelSection(
        title = "Pen button",
        info = "Your pen firmware counts its own clicks "
    ) {
        PenButtonRow(StylusPress.Single, buttons.single) { onSetButtons(buttons.copy(single = it)) }
        PenButtonRow(StylusPress.Double, buttons.double) { onSetButtons(buttons.copy(double = it)) }
        PenButtonRow(StylusPress.Triple, buttons.triple) { onSetButtons(buttons.copy(triple = it)) }
    }
}

/**
 * One binding: the click count on the left, what it does on the right.
 *
 * [PanelRow] rather than [PanelSetting], which is what the toggle above uses: these are named choices
 * of very different widths, and only the fixed label column lines the drop-downs up with each other.
 */
@Composable
private fun PenButtonRow(
    press: StylusPress,
    action: StylusAction,
    onPick: (StylusAction) -> Unit,
) {
    PanelRow(press.label, labelWidth = WIDE_LABEL_WIDTH) {
        PanelChoice(
            field = press.label,
            current = action,
            options = StylusAction.entries,
            label = { it.label },
            onPick = onPick,
        )
    }
}

/**
 * Every hardware-keyboard shortcut, read off the one table that also dispatches them.
 *
 * A reference rather than a setting, and worth the space: the shortcuts work today but the only way
 * to find them was the system's Meta + / panel, which nobody presses by accident. Rebinding is not
 * offered because nothing in the app can rebind them yet — see `docs/features.md` L2.
 */
@Composable
private fun ColumnScope.KeyboardSettings() {
    Column(Modifier.fillMaxWidth().testTag(HardwareTags.SHORTCUTS)) {
        shortcutRows().forEach { (group, shortcuts) ->
            Text(
                text = group,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
            shortcuts.forEach { shortcut ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = shortcut.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = shortcut.chord,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
