package com.vivenotes.ui

import android.view.KeyEvent
import com.vivenotes.data.DrawTool
import com.vivenotes.ui.editor.RibbonTab

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
 * A press this table does not name is not claimed, so it falls through to whatever else wants it
 * rather than doing something arbitrary.
 */
internal fun NotesViewModel.handleStylusButton(keyCode: Int): Boolean {
    val press = stylusPressFor(keyCode) ?: return false
    pressStylusButton(press)
    return true
}

/** Whether a keycode is one of ours, for claiming the down-press we act on at up. */
internal fun isStylusButton(keyCode: Int): Boolean = stylusPressFor(keyCode) != null

/** What the pen said it was — already counted, never inferred from timing here. */
enum class StylusPress { Single, Double }

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
 * Three clicks is left unbound on purpose — nothing has asked for a third action, and an unclaimed
 * keycode falls through to whatever else wants it.
 */
private const val KEYCODE_PEN_ONE_CLICK = 600
private const val KEYCODE_PEN_TWO_CLICK = 601

private fun stylusPressFor(keyCode: Int): StylusPress? = when (keyCode) {
    KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, KEYCODE_PEN_ONE_CLICK -> StylusPress.Single
    KEYCODE_PEN_TWO_CLICK -> StylusPress.Double
    else -> null
}

/** Pen 1, the notebook's default — [DrawTool.Pen] is indexed from zero. */
private const val FIRST_PEN = 0

/**
 * What the button arms next, given what is in hand and what the pen reported.
 *
 * A single click swaps between writing and rubbing out: with a pen in hand it reaches for the
 * eraser, with anything else in hand it reaches for pen 1. That makes one button a *toggle* over the
 * two tools a stylus is actually held for, rather than a key that only ever arms one of them and
 * then has nothing left to do. A double click goes to the lasso, from wherever it was — a selection
 * is what you want *after* drawing, so it is as likely to be wanted with a pen in hand as with
 * anything else and cannot sensibly be another position in the toggle.
 *
 * Pure so the rule is testable without a device: the emulator has no stylus and cannot generate
 * these presses at all — see the tooling note in `CLAUDE.md` — which makes a JVM test the *only*
 * test available here rather than merely the cheap one.
 */
internal fun nextToolForStylusButton(current: DrawTool, press: StylusPress): DrawTool = when {
    press == StylusPress.Double -> DrawTool.Lasso
    current is DrawTool.Pen -> DrawTool.Eraser
    else -> DrawTool.Pen(FIRST_PEN)
}
