package com.vivenotes.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.vivenotes.ui.chord
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.shortcutRows

/**
 * The three kinds of hardware you can point, write or type at this app with.
 *
 * Deliberately not persisted, for the reason the open pane is not: which one you are *looking at* is
 * where you are in a panel, not something about you. Reopening Hardware starting on Stylus is right.
 */
enum class HardwareKind(val label: String) {
    Stylus("Stylus"),
    Keyboard("Keyboard"),
    Mouse("Mouse"),
}

internal object HardwareTags {
    fun kind(kind: HardwareKind) = "hardware-${kind.name.lowercase()}"
    const val SHORTCUTS = "hardware-shortcuts"
}

/**
 * The Hardware pane: pick a device along the top, and the settings below are that device's.
 *
 * A row of three rather than a drop-down because there are exactly three and they are not a list
 * that will grow — a tablet is written on with a pen, typed on with a keyboard and pointed at with a
 * mouse, and that is the whole set. Showing all three at once also answers the question the pane
 * exists to answer ("what can I configure?") without a tap.
 *
 * Everything here describes **this device**, never a page and never the user's taste — the middle
 * case of the three-way split in `CLAUDE.md`. Whether a stylus is in the room is not a property of
 * a notebook, and must never travel to another device with one.
 */
@Composable
fun ColumnScope.HardwarePanelContent(
    allowFinger: Boolean,
    onSetDrawWithFinger: (Boolean) -> Unit,
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
                    HardwareKind.Mouse -> MaterialSymbols.Mouse
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
        HardwareKind.Stylus -> StylusSettings(allowFinger, onSetDrawWithFinger)
        HardwareKind.Keyboard -> KeyboardSettings()
        HardwareKind.Mouse -> MouseSettings()
    }
}

@Composable
private fun HardwareTab(
    kind: HardwareKind,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val foreground = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .testTag(HardwareTags.kind(kind))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            // Labelled rather than null: the icon *is* the control, so with no text beside it there
            // would be nothing for TalkBack to announce (L3).
            contentDescription = kind.label,
            tint = foreground,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = kind.label,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
    }
}

@Composable
private fun ColumnScope.StylusSettings(
    allowFinger: Boolean,
    onSetDrawWithFinger: (Boolean) -> Unit,
) {
    PanelSetting(
        label = "Let a finger draw",
        info = "Off, a finger scrolls the page and only the pen marks it, so a resting palm cannot " +
            "leave a stroke. On is what makes drawing possible with no stylus in the room.",
    ) {
        PanelToggle("Let a finger draw", allowFinger, onSetDrawWithFinger)
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

/**
 * Nothing yet, said plainly.
 *
 * The app has no mouse-specific setting to offer: a mouse currently arrives as an ordinary pointer
 * and is governed by the stylus rules beside it. An empty section that says so beats one padded with
 * controls that do nothing — the same call `RibbonCommand`'s `enabled` flag makes.
 */
@Composable
private fun ColumnScope.MouseSettings() {
    Text(
        text = "Nothing to set for a mouse yet. A mouse points, drags and scrolls the same way a " +
            "finger does, so it follows the stylus settings beside it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
