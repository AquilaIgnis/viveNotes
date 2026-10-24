package com.vivenotes.keyboard

import android.view.KeyEvent

/**
 * The chord this key press makes with the modifiers held; null for a key no shortcut can use — a
 * modifier on its own, a stylus button, a media key.
 */
fun KeyEvent.toChord(): KeyChord? {
    val key = AndroidKeys[keyCode] ?: return null
    return KeyChord(key, ctrl = isCtrlPressed, shift = isShiftPressed, alt = isAltPressed, meta = isMetaPressed)
}

/** The key code that types [ShortcutKey], for the system's Meta + / helper. */
val ShortcutKey.keyCode: Int get() = KeyCodes.getValue(this)

/** [KeyChord]'s modifiers as a `KeyEvent` meta state, for the same helper. */
val KeyChord.metaState: Int
    get() = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
        (if (shift) KeyEvent.META_SHIFT_ON else 0) or
        (if (alt) KeyEvent.META_ALT_ON else 0) or
        (if (meta) KeyEvent.META_META_ON else 0)

private val KeyCodes: Map<ShortcutKey, Int> = mapOf(
    ShortcutKey.A to KeyEvent.KEYCODE_A, ShortcutKey.B to KeyEvent.KEYCODE_B, ShortcutKey.C to KeyEvent.KEYCODE_C,
    ShortcutKey.D to KeyEvent.KEYCODE_D, ShortcutKey.E to KeyEvent.KEYCODE_E, ShortcutKey.F to KeyEvent.KEYCODE_F,
    ShortcutKey.G to KeyEvent.KEYCODE_G, ShortcutKey.H to KeyEvent.KEYCODE_H, ShortcutKey.I to KeyEvent.KEYCODE_I,
    ShortcutKey.J to KeyEvent.KEYCODE_J, ShortcutKey.K to KeyEvent.KEYCODE_K, ShortcutKey.L to KeyEvent.KEYCODE_L,
    ShortcutKey.M to KeyEvent.KEYCODE_M, ShortcutKey.N to KeyEvent.KEYCODE_N, ShortcutKey.O to KeyEvent.KEYCODE_O,
    ShortcutKey.P to KeyEvent.KEYCODE_P, ShortcutKey.Q to KeyEvent.KEYCODE_Q, ShortcutKey.R to KeyEvent.KEYCODE_R,
    ShortcutKey.S to KeyEvent.KEYCODE_S, ShortcutKey.T to KeyEvent.KEYCODE_T, ShortcutKey.U to KeyEvent.KEYCODE_U,
    ShortcutKey.V to KeyEvent.KEYCODE_V, ShortcutKey.W to KeyEvent.KEYCODE_W, ShortcutKey.X to KeyEvent.KEYCODE_X,
    ShortcutKey.Y to KeyEvent.KEYCODE_Y, ShortcutKey.Z to KeyEvent.KEYCODE_Z,
    ShortcutKey.Digit0 to KeyEvent.KEYCODE_0, ShortcutKey.Digit1 to KeyEvent.KEYCODE_1,
    ShortcutKey.Digit2 to KeyEvent.KEYCODE_2, ShortcutKey.Digit3 to KeyEvent.KEYCODE_3,
    ShortcutKey.Digit4 to KeyEvent.KEYCODE_4, ShortcutKey.Digit5 to KeyEvent.KEYCODE_5,
    ShortcutKey.Digit6 to KeyEvent.KEYCODE_6, ShortcutKey.Digit7 to KeyEvent.KEYCODE_7,
    ShortcutKey.Digit8 to KeyEvent.KEYCODE_8, ShortcutKey.Digit9 to KeyEvent.KEYCODE_9,
    ShortcutKey.F1 to KeyEvent.KEYCODE_F1, ShortcutKey.F2 to KeyEvent.KEYCODE_F2, ShortcutKey.F3 to KeyEvent.KEYCODE_F3,
    ShortcutKey.F4 to KeyEvent.KEYCODE_F4, ShortcutKey.F5 to KeyEvent.KEYCODE_F5, ShortcutKey.F6 to KeyEvent.KEYCODE_F6,
    ShortcutKey.F7 to KeyEvent.KEYCODE_F7, ShortcutKey.F8 to KeyEvent.KEYCODE_F8, ShortcutKey.F9 to KeyEvent.KEYCODE_F9,
    ShortcutKey.F10 to KeyEvent.KEYCODE_F10, ShortcutKey.F11 to KeyEvent.KEYCODE_F11, ShortcutKey.F12 to KeyEvent.KEYCODE_F12,
    ShortcutKey.Tab to KeyEvent.KEYCODE_TAB, ShortcutKey.Space to KeyEvent.KEYCODE_SPACE,
    ShortcutKey.Enter to KeyEvent.KEYCODE_ENTER,
    // Android's names are the other way round from the keys': DEL is Backspace, FORWARD_DEL is Delete.
    ShortcutKey.Backspace to KeyEvent.KEYCODE_DEL, ShortcutKey.Delete to KeyEvent.KEYCODE_FORWARD_DEL,
    ShortcutKey.Insert to KeyEvent.KEYCODE_INSERT,
    ShortcutKey.Home to KeyEvent.KEYCODE_MOVE_HOME, ShortcutKey.End to KeyEvent.KEYCODE_MOVE_END,
    ShortcutKey.PageUp to KeyEvent.KEYCODE_PAGE_UP, ShortcutKey.PageDown to KeyEvent.KEYCODE_PAGE_DOWN,
    ShortcutKey.Up to KeyEvent.KEYCODE_DPAD_UP, ShortcutKey.Down to KeyEvent.KEYCODE_DPAD_DOWN,
    ShortcutKey.Left to KeyEvent.KEYCODE_DPAD_LEFT, ShortcutKey.Right to KeyEvent.KEYCODE_DPAD_RIGHT,
    ShortcutKey.Escape to KeyEvent.KEYCODE_ESCAPE,
    ShortcutKey.Equals to KeyEvent.KEYCODE_EQUALS, ShortcutKey.Minus to KeyEvent.KEYCODE_MINUS,
    ShortcutKey.Plus to KeyEvent.KEYCODE_PLUS, ShortcutKey.Comma to KeyEvent.KEYCODE_COMMA,
    ShortcutKey.Period to KeyEvent.KEYCODE_PERIOD, ShortcutKey.Slash to KeyEvent.KEYCODE_SLASH,
    ShortcutKey.Backslash to KeyEvent.KEYCODE_BACKSLASH, ShortcutKey.Semicolon to KeyEvent.KEYCODE_SEMICOLON,
    ShortcutKey.Apostrophe to KeyEvent.KEYCODE_APOSTROPHE, ShortcutKey.Grave to KeyEvent.KEYCODE_GRAVE,
    ShortcutKey.LeftBracket to KeyEvent.KEYCODE_LEFT_BRACKET, ShortcutKey.RightBracket to KeyEvent.KEYCODE_RIGHT_BRACKET,
    ShortcutKey.NumPad0 to KeyEvent.KEYCODE_NUMPAD_0, ShortcutKey.NumPad1 to KeyEvent.KEYCODE_NUMPAD_1,
    ShortcutKey.NumPad2 to KeyEvent.KEYCODE_NUMPAD_2, ShortcutKey.NumPad3 to KeyEvent.KEYCODE_NUMPAD_3,
    ShortcutKey.NumPad4 to KeyEvent.KEYCODE_NUMPAD_4, ShortcutKey.NumPad5 to KeyEvent.KEYCODE_NUMPAD_5,
    ShortcutKey.NumPad6 to KeyEvent.KEYCODE_NUMPAD_6, ShortcutKey.NumPad7 to KeyEvent.KEYCODE_NUMPAD_7,
    ShortcutKey.NumPad8 to KeyEvent.KEYCODE_NUMPAD_8, ShortcutKey.NumPad9 to KeyEvent.KEYCODE_NUMPAD_9,
    ShortcutKey.NumPadAdd to KeyEvent.KEYCODE_NUMPAD_ADD, ShortcutKey.NumPadSubtract to KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
    ShortcutKey.NumPadMultiply to KeyEvent.KEYCODE_NUMPAD_MULTIPLY, ShortcutKey.NumPadDivide to KeyEvent.KEYCODE_NUMPAD_DIVIDE,
    ShortcutKey.NumPadDot to KeyEvent.KEYCODE_NUMPAD_DOT, ShortcutKey.NumPadEnter to KeyEvent.KEYCODE_NUMPAD_ENTER,
)

private val AndroidKeys: Map<Int, ShortcutKey> = KeyCodes.entries.associate { (key, code) -> code to key }
