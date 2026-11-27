package com.vivenotes.ui

import android.view.KeyEvent
import com.vivenotes.data.StylusKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Learning a pen's key behind a row's gear: the capture rule, and how a learned key sits beside the
 * built-in ones.
 *
 * The rule decides at key *up* because that is all some pens send — the Lenovo's one click and three
 * clicks arrive as a bare up — so a capture that waited for a down would never hear them.
 */
class StylusKeyCaptureTest {

    private fun capture(
        keyCode: Int = 612,
        up: Boolean = true,
        capturing: StylusPress? = StylusPress.Single,
        repeatCount: Int = 0,
        fromKeyboard: Boolean = false,
    ) = stylusKeyCapture(capturing, keyCode, up, repeatCount, fromKeyboard)

    // --- the capture rule --------------------------------------------------------------------------

    @Test
    fun nothingHappensWhileNoRowIsLearning() {
        assertEquals(StylusKeyCapture.Ignore, capture(capturing = null))
        assertEquals(StylusKeyCapture.Ignore, capture(capturing = null, keyCode = KeyEvent.KEYCODE_BACK))
    }

    /** A bare up is a whole press, so it is learned on its own. */
    @Test
    fun anUpIsLearnedForTheRowThatIsListening() {
        assertEquals(StylusKeyCapture.Learn(StylusPress.Triple, 612), capture(capturing = StylusPress.Triple))
    }

    /** The down is held back so nothing else acts on it, and its up is the one learned. */
    @Test
    fun aDownIsOnlyHeldBack() {
        assertEquals(StylusKeyCapture.Consume, capture(up = false))
    }

    @Test
    fun aRepeatIsOnlyHeldBack() {
        assertEquals(StylusKeyCapture.Consume, capture(repeatCount = 1))
    }

    @Test
    fun backAndEscapeCancelOnTheirUp() {
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE).forEach { key ->
            assertEquals(StylusKeyCapture.Consume, capture(keyCode = key, up = false))
            assertEquals(StylusKeyCapture.Cancel, capture(keyCode = key, up = true))
        }
    }

    @Test
    fun aKeyFromAFullKeyboardIsRefused() {
        assertEquals(StylusKeyCapture.Refuse, capture(keyCode = KeyEvent.KEYCODE_A, fromKeyboard = true))
    }

    /** Cheap Bluetooth pen buttons often send a volume key, and that is a pen button too. */
    @Test
    fun aVolumeKeyFromSomethingThatIsNotAKeyboardIsLearned() {
        assertEquals(
            StylusKeyCapture.Learn(StylusPress.Single, KeyEvent.KEYCODE_VOLUME_UP),
            capture(keyCode = KeyEvent.KEYCODE_VOLUME_UP),
        )
    }

    // --- a learned key beside the built-in ones ----------------------------------------------------

    @Test
    fun aLearnedKeyPressesItsRow() {
        assertEquals(StylusPress.Double, stylusPressFor(612, StylusKeys(double = 612)))
    }

    /** Learned on top, not instead: the built-in codes still work. */
    @Test
    fun theBuiltInKeysStillWorkBesideALearnedOne() {
        val learned = StylusKeys(single = 612)

        assertEquals(StylusPress.Single, stylusPressFor(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, learned))
        assertEquals(StylusPress.Single, stylusPressFor(600, learned))
        assertEquals(StylusPress.Double, stylusPressFor(601, learned))
    }

    /** A pen whose single click sends what the Lenovo sends for two: the tablet's own answer wins. */
    @Test
    fun aLearnedKeyWinsOverAnotherRowsBuiltInCode() {
        assertEquals(StylusPress.Single, stylusPressFor(601, StylusKeys(single = 601)))
    }

    @Test
    fun anUnknownKeyIsStillNoPress() {
        assertNull(stylusPressFor(612))
    }

    @Test
    fun forgettingAKeyLeavesTheOthers() {
        val keys = StylusKeys(single = 612, triple = 614).with(StylusPress.Single, null)

        assertNull(keys.codeFor(StylusPress.Single))
        assertEquals(614, keys.codeFor(StylusPress.Triple))
    }
}
