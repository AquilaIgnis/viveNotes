package com.vivenotes.ui.panel

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.data.StylusAction
import com.vivenotes.data.StylusButtonMap
import com.vivenotes.data.StylusKeys
import com.vivenotes.keyboard.KeyBindings
import com.vivenotes.keyboard.KeyChord
import com.vivenotes.keyboard.ShortcutAction
import com.vivenotes.ui.ProbeEvent
import com.vivenotes.ui.StylusPress
import com.vivenotes.ui.StylusProbe
import com.vivenotes.ui.TABLE_ROWS
import com.vivenotes.ui.actionFor
import com.vivenotes.ui.codeFor
import com.vivenotes.ui.codeLine
import com.vivenotes.ui.copyRecognizedText
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.probeReportHeader
import com.vivenotes.ui.report

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
enum class HardwareKind(@param:StringRes val label: Int) {
    Stylus(R.string.hardware_stylus),
    Keyboard(R.string.hardware_keyboard),
}

internal object HardwareTags {
    fun kind(kind: HardwareKind) = "hardware-${kind.name.lowercase()}"
    const val SHORTCUTS = "hardware-shortcuts"
    const val RESET_ALL = "hardware-shortcuts-reset-all"
    const val BUTTON_TEST = "hardware-button-test"
    const val BUTTON_TEST_CLEAR = "hardware-button-test-clear"
    const val BUTTON_TEST_COPY = "hardware-button-test-copy"
    fun keyGear(press: StylusPress) = "hardware-pen-key-gear-${press.name}"
    fun keyLearned(press: StylusPress) = "hardware-pen-key-learned-${press.name}"
    const val KEY_WELL = "hardware-pen-key-well"
    const val KEY_RESET = "hardware-pen-key-reset"
    const val KEY_CANCEL = "hardware-pen-key-cancel"
    fun shortcut(action: ShortcutAction) = "hardware-shortcut-${action.name}"
    fun reset(action: ShortcutAction) = "hardware-shortcut-reset-${action.name}"
}

/**
 * The Hardware pane: pick a device along the top, and the settings below are that device's.
 *
 * A row rather than a drop-down because there are exactly two and they are not a list that will
 * grow. Showing both at once also answers the question the pane exists to answer without a tap.
 *
 * The pane's subject is hardware; the scope of each setting in it is still decided one setting at a
 * time. *Let a finger draw* describes this device — whether a stylus is in the room is not a
 * property of a notebook, and must never travel to another device with one. The button bindings
 * below it describe the user: "double click means highlighter" is a working habit like "pen 2 is
 * red", and it should follow its owner to a second tablet. The keys learned behind each row's gear
 * describe this device again — the keycode comes from this tablet's key layout.
 */
@Composable
internal fun ColumnScope.HardwarePanelContent(
    allowFinger: Boolean,
    onSetDrawWithFinger: (Boolean) -> Unit,
    buttons: StylusButtonMap,
    onSetButtons: (StylusButtonMap) -> Unit,
    /** The keyboard shortcuts, a property of the user as the pen button's are. */
    keyBindings: KeyBindings = KeyBindings.Default,
    onSetKeyBindings: (KeyBindings) -> Unit = {},
    /** What the pen sends, fed by `MainActivity`; the button test shows it. */
    probe: StylusProbe = remember { StylusProbe() },
    penKeys: PenKeys = PenKeys(),
    /** Open a row's gear: listen for the pen's key for it. */
    onLearnKey: (StylusPress) -> Unit = {},
    /** Stop listening for a row's key — if that row is still the one listening. */
    onStopLearning: (StylusPress) -> Unit = {},
    /** Forget the key learned for a row, back to the built-in ones. */
    onResetKey: (StylusPress) -> Unit = {},
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
            probe = probe,
            penKeys = penKeys,
            onLearnKey = onLearnKey,
            onStopLearning = onStopLearning,
            onResetKey = onResetKey,
        )
        HardwareKind.Keyboard -> KeyboardSettings(keyBindings, onSetKeyBindings)
    }
}

/**
 * The keys learned for this tablet's pen, and which row's gear is open waiting for one.
 *
 * The listening itself happens in `MainActivity` — see `captureStylusKey` — so this is only what the
 * pane shows about it.
 */
internal data class PenKeys(
    val learned: StylusKeys = StylusKeys(),
    /** The row waiting for the pen's key, or null. */
    val capturing: StylusPress? = null,
    /** The last key offered came from a keyboard and was turned away. */
    val refused: Boolean = false,
)

/**
 * One device in the picker.
 *
 * A real [ToggleButton] rather than a hand-built `Column` with a swapped background colour: it is
 * the same control Material already has a name for — a segmented, mutually exclusive choice — and
 * the expressive one brings the shape morph on press and check that a background swap cannot
 * express.
 *
 * Two `ToggleButton`s in a `Row` rather than a `ButtonGroup`: that component wants an overflow
 * indicator and exists to collapse a row too long to fit, and there are exactly two devices.
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
        Text(text = stringResource(kind.label), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ColumnScope.StylusSettings(
    allowFinger: Boolean,
    onSetDrawWithFinger: (Boolean) -> Unit,
    buttons: StylusButtonMap,
    onSetButtons: (StylusButtonMap) -> Unit,
    probe: StylusProbe,
    penKeys: PenKeys,
    onLearnKey: (StylusPress) -> Unit,
    onStopLearning: (StylusPress) -> Unit,
    onResetKey: (StylusPress) -> Unit,
) {
    // **The only way to reach this in a shipped build.** The ribbon's finger button is compiled out
    // of release by `BuildConfig.DEBUG` — see `TabStrip` in `ui/editor/Ribbon.kt` — so this row is
    // the setting rather than a second copy of a control already on screen.
    PanelSetting(
        label = stringResource(R.string.hardware_finger_draws)
    ) {
        PanelToggle("Let a finger draw", allowFinger, onSetDrawWithFinger)
    }

    PenButtonSettings(buttons, onSetButtons, penKeys, onLearnKey, onStopLearning, onResetKey)
    PenButtonTest(probe, penKeys.learned)
}

/**
 * Press the pen's button and see what arrived: the keycode or button state, the device it came from,
 * and whether this app reads it. Copy turns that into a report for a pen this app does not understand
 * yet — the only other way to learn what a pen sends is `adb`.
 *
 * Listens only while it is on screen, and forgets when it leaves.
 */
@Composable
private fun ColumnScope.PenButtonTest(probe: StylusProbe, learned: StylusKeys) {
    DisposableEffect(probe) {
        probe.start()
        onDispose { probe.stop() }
    }
    val events by probe.events.collectAsState()
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.hardware_button_test_clip)

    PanelSection(
        title = stringResource(R.string.hardware_button_test),
        info = stringResource(R.string.hardware_button_test_info),
    ) {
        Surface(
            // Highest, as the other panes' wells are: in the dark scheme High is the pane's own colour.
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(HardwareTags.BUTTON_TEST),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (events.isEmpty()) {
                    Text(
                        text = stringResource(R.string.hardware_button_test_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Newest first: the press just made is the one being looked for.
                    events.asReversed().forEach { event -> key(event.id) { ProbeRow(event, learned) } }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            PanelButton(
                onClick = probe::clear,
                enabled = events.isNotEmpty(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                modifier = Modifier.testTag(HardwareTags.BUTTON_TEST_CLEAR),
            ) { Text(stringResource(R.string.hardware_button_test_clear)) }
            PanelButton(
                onClick = {
                    val report = events.report(probeReportHeader(), learned, KeyEvent::keyCodeToString)
                    copyRecognizedText(context, clipLabel, report)
                },
                enabled = events.isNotEmpty(),
                modifier = Modifier.testTag(HardwareTags.BUTTON_TEST_COPY),
            ) {
                Icon(
                    imageVector = MaterialSymbols.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.hardware_button_test_copy))
            }
        }
    }
}

/** One event: its codes, the device that sent it, and what this app makes of it. */
@Composable
private fun ProbeRow(event: ProbeEvent, learned: StylusKeys) {
    val verdict = when (event) {
        is ProbeEvent.Key -> event.press(learned)?.let {
            stringResource(R.string.hardware_button_test_read, stringResource(it.label))
        }
        is ProbeEvent.Pointer -> null
    }
    val notRead = when (event) {
        is ProbeEvent.Key -> R.string.hardware_button_test_not_read
        is ProbeEvent.Pointer ->
            if (event.pressed) R.string.hardware_button_test_not_read else R.string.hardware_button_test_no_button
    }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(
            text = event.codeLine(KeyEvent::keyCodeToString),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = event.device,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = verdict ?: stringResource(notRead),
            style = MaterialTheme.typography.labelMedium,
            color = if (verdict != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the barrel button's clicks do.
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
    penKeys: PenKeys,
    onLearnKey: (StylusPress) -> Unit,
    onStopLearning: (StylusPress) -> Unit,
    onResetKey: (StylusPress) -> Unit,
) {
    PanelSection(
        title = stringResource(R.string.hardware_pen_button),
        info = stringResource(R.string.hardware_pen_button_info),
    ) {
        StylusPress.entries.forEach { press ->
            PenButtonRow(
                press = press,
                action = buttons.actionFor(press),
                onPick = { onSetButtons(buttons.bound(press, it)) },
                learned = penKeys.learned.codeFor(press),
                learning = penKeys.capturing == press,
                refused = penKeys.refused,
                onGear = { if (penKeys.capturing == press) onStopLearning(press) else onLearnKey(press) },
                onCancel = { onStopLearning(press) },
                onReset = { onResetKey(press) },
            )
        }
    }
}

/** [press]'s binding set to [action]. */
private fun StylusButtonMap.bound(press: StylusPress, action: StylusAction): StylusButtonMap = when (press) {
    StylusPress.Single -> copy(single = action)
    StylusPress.Double -> copy(double = action)
    StylusPress.Triple -> copy(triple = action)
}

/**
 * One binding: the click count on the left, what it does in the middle, and a gear on the right that
 * learns which key this tablet's pen sends for it.
 *
 * [PanelRow] rather than [PanelSetting], which is what the toggle above uses: these are named choices
 * of very different widths, and only the fixed label column lines the drop-downs up with each other.
 *
 * The learning happens in a well under the row rather than in a dialog. A dialog is a window of its
 * own, so the pen's key would go to it instead of to `MainActivity`, where it is caught — and caught
 * there because Compose drops a key-up with no key-down, which is all some pens send.
 */
@Composable
private fun PenButtonRow(
    press: StylusPress,
    action: StylusAction,
    onPick: (StylusAction) -> Unit,
    /** The key learned for this row on this tablet, or null where only the built-in keys apply. */
    learned: Int?,
    /** This row's gear is open, waiting for the pen. */
    learning: Boolean,
    refused: Boolean,
    onGear: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    val resources = LocalResources.current
    val name = stringResource(press.label)
    Column(Modifier.fillMaxWidth()) {
        PanelRow(name, labelWidth = WIDE_LABEL_WIDTH) {
            Box(Modifier.weight(1f)) {
                PanelChoice(
                    field = press.name,
                    current = action,
                    options = StylusAction.entries,
                    label = { resources.getString(it.label) },
                    onPick = onPick,
                )
            }
            IconButton(onClick = onGear, modifier = Modifier.testTag(HardwareTags.keyGear(press))) {
                Icon(
                    imageVector = MaterialSymbols.Settings,
                    contentDescription = stringResource(R.string.hardware_pen_key_set, name),
                    tint = if (learning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (learned != null && !learning) {
            Text(
                text = stringResource(R.string.hardware_pen_key_learned, learned),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = WIDE_LABEL_WIDTH + 10.dp)
                    .testTag(HardwareTags.keyLearned(press)),
            )
        }
        AnimatedVisibility(
            visible = learning,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            PenKeyWell(name, learned, refused, onCancel, onReset)
        }
    }
}

/**
 * Waiting for the pen: what to do, what this row answers to now, and the two ways out.
 *
 * Leaving it any other way — closing the pane, switching to Keyboard — stops the listening too, so
 * a key pressed later in a text box is never taken for a pen button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PenKeyWell(
    name: String,
    learned: Int?,
    refused: Boolean,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    DisposableEffect(Unit) { onDispose(onCancel) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 6.dp)
            .testTag(HardwareTags.KEY_WELL),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                LoadingIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (refused) {
                        stringResource(R.string.hardware_pen_key_refused)
                    } else {
                        stringResource(R.string.hardware_pen_key_prompt, name)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = learned?.let { stringResource(R.string.hardware_pen_key_learned, it) }
                    ?: stringResource(R.string.hardware_pen_key_built_in),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, top = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onReset,
                    enabled = learned != null,
                    modifier = Modifier.testTag(HardwareTags.KEY_RESET),
                ) { Text(stringResource(R.string.hardware_pen_key_reset)) }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag(HardwareTags.KEY_CANCEL),
                ) { Text(stringResource(R.string.dialog_cancel)) }
            }
        }
    }
}

/**
 * Every hardware-keyboard shortcut, read off the one table that also dispatches them, and changed
 * here: a row opens its key capture, a changed one has its own reset, and Reset all asks first.
 *
 * The Table rows are Indent's and Outdent's keys as they act in a cell, so they are shown but not
 * changed here — they follow whatever those two are given.
 */
@Composable
private fun ColumnScope.KeyboardSettings(bindings: KeyBindings, onSetBindings: (KeyBindings) -> Unit) {
    var editing by remember { mutableStateOf<ShortcutAction?>(null) }
    var confirmingReset by remember { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.hardware_shortcuts_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.fillMaxWidth().testTag(HardwareTags.SHORTCUTS)) {
        ShortcutAction.groups.forEach { (group, actions) ->
            ShortcutGroupHeading(stringResource(group.label))
            actions.forEach { action ->
                ShortcutRow(
                    label = stringResource(action.label),
                    chord = bindings.primary(action),
                    customized = bindings.isCustomized(action),
                    modifier = Modifier.testTag(HardwareTags.shortcut(action)),
                    onEdit = { editing = action },
                    onReset = { onSetBindings(bindings.reset(action)) },
                    resetTag = HardwareTags.reset(action),
                )
            }
        }
        ShortcutGroupHeading(stringResource(R.string.shortcut_group_table))
        TABLE_ROWS.forEach { (label, action) ->
            ShortcutRow(label = stringResource(label), chord = bindings.primary(action), customized = false)
        }
        Text(
            text = stringResource(R.string.hardware_shortcuts_table_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    OutlinedButton(
        onClick = { confirmingReset = true },
        enabled = bindings.isCustomized,
        modifier = Modifier.align(Alignment.End).padding(top = 12.dp).testTag(HardwareTags.RESET_ALL),
    ) { Text(stringResource(R.string.hardware_shortcuts_reset_all)) }

    editing?.let { action ->
        ShortcutCaptureDialog(
            action = action,
            bindings = bindings,
            onSet = { chord ->
                onSetBindings(bindings.rebind(action, chord))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    if (confirmingReset) {
        ResetAllShortcutsDialog(
            onConfirm = {
                onSetBindings(bindings.resetAll())
                confirmingReset = false
            },
            onDismiss = { confirmingReset = false },
        )
    }
}

@Composable
private fun ShortcutGroupHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/**
 * One shortcut: its name, its keys (or Disabled), and a reset once it has been changed. [onEdit]
 * null is a row to read, not to change.
 */
@Composable
private fun ShortcutRow(
    label: String,
    chord: KeyChord?,
    customized: Boolean,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onReset: () -> Unit = {},
    resetTag: String = "",
) {
    val change = stringResource(R.string.hardware_shortcut_change)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onEdit != null) Modifier.clickable(onClickLabel = change, role = Role.Button, onClick = onEdit)
                else Modifier,
            )
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (chord != null) {
            Keycap(chord.label)
        } else {
            Text(
                text = stringResource(R.string.hardware_shortcut_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The slot stays when empty, so every row's keys line up.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (customized) {
                IconButton(onClick = onReset, modifier = Modifier.testTag(resetTag)) {
                    Icon(
                        imageVector = MaterialSymbols.RestartAlt,
                        contentDescription = stringResource(R.string.hardware_shortcut_reset, label),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@get:StringRes
private val StylusPress.label: Int
    get() = when (this) {
        StylusPress.Single -> R.string.stylus_press_single
        StylusPress.Double -> R.string.stylus_press_double
        StylusPress.Triple -> R.string.stylus_press_triple
    }

@get:StringRes
private val StylusAction.label: Int
    get() = when (this) {
        StylusAction.None -> R.string.stylus_action_none
        StylusAction.TogglePenEraser -> R.string.stylus_action_toggle_pen_eraser
        StylusAction.CyclePens -> R.string.stylus_action_cycle_pens
        StylusAction.Pen1 -> R.string.stylus_action_pen_1
        StylusAction.Pen2 -> R.string.stylus_action_pen_2
        StylusAction.Pen3 -> R.string.stylus_action_pen_3
        StylusAction.Highlighter -> R.string.stylus_action_highlighter
        StylusAction.Eraser -> R.string.stylus_action_eraser
        StylusAction.Lasso -> R.string.stylus_action_lasso
        StylusAction.Undo -> R.string.stylus_action_undo
        StylusAction.Redo -> R.string.stylus_action_redo
    }
