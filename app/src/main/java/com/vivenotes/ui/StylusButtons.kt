package com.vivenotes.ui

import android.view.InputDevice
import android.view.KeyEvent
import com.vivenotes.data.DrawTool
import com.vivenotes.data.PenPreset
import com.vivenotes.data.StylusAction
import com.vivenotes.data.StylusButtonMap
import com.vivenotes.data.StylusKeys

/**
 * The stylus's own buttons — the barrel buttons and, on pens that have one, the tail sensor.
 *
 * They arrive as key events, not motion events. Since Android 14 the platform reports a stylus
 * button as `KEYCODE_STYLUS_BUTTON_*`, whether the pen is hovering or touching, so this is ordinary
 * key dispatch. Before Android 14 a digitizer pen's barrel button is only motion-event button state,
 * which this does not read, so on Android 12 and 13 only pens with keycodes of their own reach it:
 * the Bluetooth pens below.
 *
 * The pen counts its own clicks: a single click, a double click and a long press arrive as different
 * keycodes, because the firmware has already done the timing. So there is no double-press window
 * here and no timer — one keycode, one meaning. Bluetooth pens name these in their own key layout; a
 * Lenovo Tab Pen Plus ships `/system/usr/keylayout/Vendor_17ef_Product_617f.kl` with `PEN_ONE_CLICK`
 * through `PEN_LONG_CLICK`.
 *
 * This file holds the two pure halves: which press a keycode is, and what a bound [StylusAction]
 * arms. The stored bindings are [StylusButtonMap] in `data/PenSettings.kt`, and the `when` that
 * turns an action into a call is `NotesViewModel.pressStylusButton`.
 *
 * Acted on at key up. Measured on the Lenovo pen: one click and three clicks deliver only
 * `ACTION_UP` to the app while two clicks delivers both, so `onKeyDown` misses two of the three
 * outright. Up is also the honest moment for a button whose meaning is a completed click count.
 *
 * Dispatched from `MainActivity.onKeyUp` beside [handleShortcut]'s `onKeyDown`, and last for the
 * same reason. Kept out of `APP_SHORTCUTS` deliberately: that table feeds the system's Meta + /
 * panel, and a barrel button is not a keyboard shortcut to list there.
 *
 * A press with no action bound to it is not claimed, so it falls through.
 */
internal fun NotesViewModel.handleStylusButton(keyCode: Int): Boolean {
    val action = boundStylusAction(keyCode) ?: return false
    pressStylusButton(action)
    return true
}

/**
 * Whether a keycode is one of ours *and* currently bound, for claiming the down-press we act on at up.
 *
 * **Both halves read the same bindings, and must**. Claiming every stylus
 * keycode here while acting on only the bound ones at up would turn an unbound press into a
 * *swallowed* press: it would stop falling through to whatever else wanted it, which is the property
 * the feature deliberately has. The two cannot disagree in practice, since changing a binding between
 * one press's down and up would mean holding the barrel button while tapping the panel.
 */
internal fun NotesViewModel.claimsStylusButton(keyCode: Int): Boolean =
    boundStylusAction(keyCode) != null

/** The action this keycode is bound to, or null if it is not ours or is bound to nothing. */
private fun NotesViewModel.boundStylusAction(keyCode: Int): StylusAction? =
    stylusPressFor(keyCode, stylusKeys.value)
        ?.let { stylusButtons.value.actionFor(it) }
        ?.takeUnless { it == StylusAction.None }

/**
 * Learns a pen key while a row's gear is open, from `MainActivity.dispatchKeyEvent` ahead of
 * everything else — so the key being learned does not also run whatever it is bound to now.
 *
 * Not a Compose key handler, and not in a dialog: Compose drops a key-up that had no key-down, and
 * one click and three clicks on the Lenovo pen deliver *only* the up. A dialog is a window of its own
 * as well, whose keys never reach the activity.
 */
internal fun NotesViewModel.captureStylusKey(event: KeyEvent): Boolean {
    val outcome = stylusKeyCapture(
        capturing = capturingStylusKey.value,
        keyCode = event.keyCode,
        up = event.action == KeyEvent.ACTION_UP,
        repeatCount = event.repeatCount,
        fromKeyboard = event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC,
    )
    when (outcome) {
        StylusKeyCapture.Ignore -> return false
        StylusKeyCapture.Consume -> Unit
        StylusKeyCapture.Cancel -> cancelStylusKeyCapture()
        StylusKeyCapture.Refuse -> refuseStylusKey()
        is StylusKeyCapture.Learn -> learnStylusKey(outcome.press, outcome.keyCode)
    }
    return true
}

/** What a key does while a row is learning its key — [stylusKeyCapture]. */
internal sealed interface StylusKeyCapture {
    /** Nothing is being learned: the key goes on as usual. */
    data object Ignore : StylusKeyCapture

    /** Held back and otherwise ignored — a down-press, whose up is the one that counts. */
    data object Consume : StylusKeyCapture

    /** Back or Esc: stop learning, change nothing. */
    data object Cancel : StylusKeyCapture

    /** From a full keyboard, so not the pen: ask again. */
    data object Refuse : StylusKeyCapture

    data class Learn(val press: StylusPress, val keyCode: Int) : StylusKeyCapture
}

/**
 * The capture rule, pure so it is testable without a pen.
 *
 * Decided at key *up*, for the reason dispatch is: one click and three clicks on the Lenovo pen deliver
 * only the up. Every key is held back while learning, down and up both, so nothing else acts on it.
 * A full keyboard is refused so a stray keypress cannot become a pen button; anything else is
 * accepted, volume keys included — cheap Bluetooth pen buttons often send those.
 */
internal fun stylusKeyCapture(
    capturing: StylusPress?,
    keyCode: Int,
    up: Boolean,
    repeatCount: Int,
    fromKeyboard: Boolean,
): StylusKeyCapture = when {
    capturing == null -> StylusKeyCapture.Ignore
    keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE ->
        if (up) StylusKeyCapture.Cancel else StylusKeyCapture.Consume
    !up || repeatCount > 0 -> StylusKeyCapture.Consume
    fromKeyboard -> StylusKeyCapture.Refuse
    else -> StylusKeyCapture.Learn(capturing, keyCode)
}

/** The key learned for [press], or null where the built-in keys alone apply. */
internal fun StylusKeys.codeFor(press: StylusPress): Int? = when (press) {
    StylusPress.Single -> single
    StylusPress.Double -> double
    StylusPress.Triple -> triple
}

/** [press] learning [code], or forgetting what it learned when [code] is null. */
internal fun StylusKeys.with(press: StylusPress, code: Int?): StylusKeys = when (press) {
    StylusPress.Single -> copy(single = code)
    StylusPress.Double -> copy(double = code)
    StylusPress.Triple -> copy(triple = code)
}

/** What the pen said it was — already counted, never inferred from timing here. */
enum class StylusPress {
    Single,
    Double,
    Triple,
}

/** Which of the three bindings a press reads. */
internal fun StylusButtonMap.actionFor(press: StylusPress): StylusAction = when (press) {
    StylusPress.Single -> single
    StylusPress.Double -> double
    StylusPress.Triple -> triple
}

/**
 * Bluetooth pen buttons are vendor keycodes, not the AOSP ones.
 *
 * Measured on the Lenovo Tab Pen Plus (`vendor 0x17ef product 0x617f`), whose key layout names them
 * `PEN_ONE_CLICK`/`PEN_TWO_CLICK`/`PEN_THREE_CLICK`: the HID usages `0x0c0600`–`0x0c0602` surface as
 * keycodes 600, 601 and 602. Numbers rather than named constants because these are outside the
 * public SDK, and AOSP's own keycodes stop well below 600.
 *
 * [KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY] is kept beside them rather than replaced: it is what a
 * pen wired through the digitizer sends, and both kinds should work.
 *
 * `_SECONDARY` and `_TERTIARY` are deliberately absent. Those are what a second and third button
 * send, not what a second and third click send, so binding them to the double- and triple-click rows
 * would bind the wrong physical thing.
 *
 * `PEN_LONG_CLICK` is missing because its keycode has never been measured, and it is not 603 by
 * assumption.
 */
private const val KEYCODE_PEN_ONE_CLICK = 600
private const val KEYCODE_PEN_TWO_CLICK = 601
private const val KEYCODE_PEN_THREE_CLICK = 602

/**
 * Which binding a keycode presses, or null if it is not a pen button. The button test reads it too.
 *
 * A key [learned] on this tablet is looked at first, so it wins even where it is another row's
 * built-in code; the built-in codes still work beside it.
 */
internal fun stylusPressFor(keyCode: Int, learned: StylusKeys = StylusKeys()): StylusPress? =
    StylusPress.entries.firstOrNull { learned.codeFor(it) == keyCode } ?: when (keyCode) {
        KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, KEYCODE_PEN_ONE_CLICK -> StylusPress.Single
        KEYCODE_PEN_TWO_CLICK -> StylusPress.Double
        KEYCODE_PEN_THREE_CLICK -> StylusPress.Triple
        else -> null
    }

/** Pen 1, the notebook's default — [DrawTool.Pen] is indexed from zero. */
private const val FIRST_PEN = 0

/**
 * The tool a bound action arms, given what is in hand — or null for the actions that arm nothing.
 *
 * Null is three different things that come to the same: [StylusAction.None] is unbound, and
 * [StylusAction.Undo] and [StylusAction.Redo] act on the page rather than on the hand. The caller
 * distinguishes them; this only answers "what tool does this leave me holding".
 *
 * [StylusAction.TogglePenEraser] is the one entry with a rule rather than an answer, and it is the
 * default single click: with a pen in hand it reaches for the eraser, with anything else in hand it
 * reaches back for [lastPen] — the pen last in hand — so writing in blue, erasing, and clicking again
 * carries on in blue. [StylusAction.CyclePens] is the other rule — it walks the three pens and wraps.
 *
 * Pure so the rules are testable without a device: the emulator has no stylus and cannot generate
 * these presses at all, which makes a JVM test the only test available here.
 */
internal fun StylusAction.toolFrom(
    current: DrawTool,
    lastPen: Int = FIRST_PEN,
): DrawTool? = when (this) {
    StylusAction.None, StylusAction.Undo, StylusAction.Redo -> null
    StylusAction.TogglePenEraser ->
        if (current is DrawTool.Pen) DrawTool.Eraser else DrawTool.Pen(lastPen)
    StylusAction.CyclePens -> DrawTool.Pen(
        if (current is DrawTool.Pen) (current.index + 1) % PenPreset.COUNT else FIRST_PEN,
    )
    StylusAction.Pen1 -> DrawTool.Pen(0)
    StylusAction.Pen2 -> DrawTool.Pen(1)
    StylusAction.Pen3 -> DrawTool.Pen(2)
    StylusAction.Highlighter -> DrawTool.Highlighter
    StylusAction.Eraser -> DrawTool.Eraser
    StylusAction.Lasso -> DrawTool.Lasso
}
