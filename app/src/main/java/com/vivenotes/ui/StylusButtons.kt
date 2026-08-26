package com.vivenotes.ui

import android.view.KeyEvent
import com.vivenotes.data.DrawTool
import com.vivenotes.data.PenPreset
import com.vivenotes.data.StylusAction
import com.vivenotes.data.StylusButtonMap

/**
 * The stylus's own buttons — the barrel buttons and, on pens that have one, the tail sensor.
 *
 * **They arrive as key events, not as motion events.** Since Android 14 the platform reports a
 * stylus button as `KEYCODE_STYLUS_BUTTON_*`, whether the pen is hovering or touching, so this is
 * ordinary key dispatch rather than something that has to be teased out of `MotionEvent.buttonState`
 * inside the ink overlay. `minSdk` is 35, so there is no older path to keep working.
 *
 * **The pen counts its own clicks.** A single click, a double click and a long press arrive as
 * *different keycodes* — the firmware has already done the timing, which it can do better than this
 * app ever could because it knows the button's own travel and debounce. So there is no double-press
 * window here and no timer: one keycode, one meaning. Bluetooth pens name these in their own key
 * layout — a Lenovo Tab Pen Plus ships `/system/usr/keylayout/Vendor_17ef_Product_617f.kl` with
 * `PEN_ONE_CLICK`, `PEN_TWO_CLICK`, `PEN_THREE_CLICK` and `PEN_LONG_CLICK`.
 *
 * **What each click count *does* is the user's choice** — `memory/stylusPlan.md`, decisions SB1–SB9.
 * This file holds the two pure halves of that: which press a keycode is, and what a bound
 * [StylusAction] arms. The stored bindings are [StylusButtonMap] in `data/PenSettings.kt`, and the
 * `when` that turns an action into a call is `NotesViewModel.pressStylusButton`.
 *
 * **Acted on at key *up*, which is not a detail.** Measured on the Lenovo pen: one click and three
 * clicks deliver only `ACTION_UP` to the app — something upstream keeps the down-press — while two
 * clicks delivers both. `onKeyDown` therefore misses two of the three outright. Up is also the
 * honest moment for a button whose meaning is a completed click count.
 *
 * Dispatched from `MainActivity.onKeyUp` beside [handleShortcut]'s `onKeyDown`, and last for the
 * same reason — a view that wants the press gets it first. Kept out of `APP_SHORTCUTS` deliberately:
 * that table feeds the system's Meta + / panel, and a barrel button is not a keyboard shortcut to
 * list there.
 *
 * A press with no action bound to it is not claimed, so it falls through to whatever else wants it
 * rather than doing something arbitrary.
 */
internal fun NotesViewModel.handleStylusButton(keyCode: Int): Boolean {
    val action = boundStylusAction(keyCode) ?: return false
    pressStylusButton(action)
    return true
}

/**
 * Whether a keycode is one of ours *and* currently bound, for claiming the down-press we act on at up.
 *
 * **Both halves read the same bindings, and must** — `memory/stylusPlan.md` SB5. Claiming every stylus
 * keycode here while acting on only the bound ones at up would turn an unbound press into a
 * *swallowed* press: it would stop falling through to whatever else wanted it, which is the property
 * the feature deliberately has. The two cannot disagree in practice, since changing a binding between
 * one press's down and up would mean holding the barrel button while tapping the panel.
 */
internal fun NotesViewModel.claimsStylusButton(keyCode: Int): Boolean =
    boundStylusAction(keyCode) != null

/** The action this keycode is bound to, or null if it is not ours or is bound to nothing. */
private fun NotesViewModel.boundStylusAction(keyCode: Int): StylusAction? =
    stylusPressFor(keyCode)
        ?.let { stylusButtons.value.actionFor(it) }
        ?.takeUnless { it == StylusAction.None }

/** What the pen said it was — already counted, never inferred from timing here. */
enum class StylusPress(val label: String) {
    Single("Single click"),
    Double("Double click"),
    Triple("Triple click"),
}

/** Which of the three bindings a press reads. */
internal fun StylusButtonMap.actionFor(press: StylusPress): StylusAction = when (press) {
    StylusPress.Single -> single
    StylusPress.Double -> double
    StylusPress.Triple -> triple
}

/**
 * Bluetooth pen buttons are **vendor** keycodes, not the AOSP ones.
 *
 * Measured on the Lenovo Tab Pen Plus (`vendor 0x17ef product 0x617f`), whose key layout names them
 * `PEN_ONE_CLICK`/`PEN_TWO_CLICK`/`PEN_THREE_CLICK`: the HID usages `0x0c0600`–`0x0c0602` surface as
 * keycodes **600, 601 and 602**. Numbers rather than named constants because these are outside the
 * public SDK — AOSP's own keycodes stop well below 600, so the vendor range is not going to collide.
 *
 * [KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY] is kept beside them, not replaced by them: it is what a
 * pen wired through the digitizer sends, and both kinds should work.
 *
 * **`_SECONDARY` and `_TERTIARY` are deliberately absent** — SB1. Those are what a *second and third
 * button* send, not what a second and third click send, so binding them to the double- and
 * triple-click rows would bind the wrong physical thing. A pen with a second barrel button gets its
 * own row when there is one in the room.
 *
 * `PEN_LONG_CLICK` is missing for a plainer reason: its keycode has never been measured, and it is
 * not 603 by assumption — SB9 has the recipe.
 */
private const val KEYCODE_PEN_ONE_CLICK = 600
private const val KEYCODE_PEN_TWO_CLICK = 601
private const val KEYCODE_PEN_THREE_CLICK = 602

private fun stylusPressFor(keyCode: Int): StylusPress? = when (keyCode) {
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
 * Null is three different things and they all come to the same: [StylusAction.None] is unbound, and
 * [StylusAction.Undo] and [StylusAction.Redo] act on the page rather than on the hand. The caller
 * distinguishes them; this function only answers "what tool does this leave me holding".
 *
 * [StylusAction.TogglePenEraser] is the one entry with a rule rather than an answer, and it is the
 * default single click: with a pen in hand it reaches for the eraser, with anything else in hand it
 * reaches for pen 1. That makes one button a *toggle* over the two tools a stylus is actually held
 * for, rather than a key that only ever arms one of them and then has nothing left to do.
 * [StylusAction.CyclePens] is the other rule — it walks the three pens and wraps, and arrives at pen 1
 * from anything that is not a pen.
 *
 * Pure so the rules are testable without a device: the emulator has no stylus and cannot generate
 * these presses at all — see the tooling note in `CLAUDE.md` — which makes a JVM test the *only*
 * test available here rather than merely the cheap one.
 */
internal fun StylusAction.toolFrom(current: DrawTool): DrawTool? = when (this) {
    StylusAction.None, StylusAction.Undo, StylusAction.Redo -> null
    StylusAction.TogglePenEraser ->
        if (current is DrawTool.Pen) DrawTool.Eraser else DrawTool.Pen(FIRST_PEN)
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
