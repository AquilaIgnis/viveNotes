package com.vivenotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule Switch Background turns on: which marks re-resolve their colour and which keep it.
 *
 * The bug this pins is that ink and objects drawn with the automatic pen kept the colour the canvas
 * happened to be when they were drawn, so flipping the background left white ink on white paper
 * while the text beside it flipped correctly. What makes it worth a test rather than a comment is
 * the third state: a mark written before any of this was recorded has to be *inferred*, and the
 * inference must not leak onto marks whose intent is known.
 */
class AutomaticColorTest {

    private val canvasInk = 0xFF1B1B1B.toInt()
    private val red = 0xFFE53935.toInt()

    @Test
    fun automaticFollowsTheCanvas() {
        assertEquals(
            canvasInk,
            automaticColorOr(AUTOMATIC_LIGHT, followsTheme = true, canvasInk = canvasInk),
        )
    }

    /** The standing rule: a colour the user picked survives the theme changing under it. */
    @Test
    fun chosenColorIsLeftAlone() {
        assertEquals(red, automaticColorOr(red, followsTheme = false, canvasInk = canvasInk))
    }

    /**
     * The case that makes `false` different from `null` — without it, a stroke deliberately painted
     * white would be indistinguishable from an automatic one forever.
     */
    @Test
    fun deliberateWhiteIsNotTreatedAsAutomatic() {
        assertEquals(
            AUTOMATIC_LIGHT,
            automaticColorOr(AUTOMATIC_LIGHT, followsTheme = false, canvasInk = canvasInk),
        )
    }

    @Test
    fun legacyBlackAndWhiteAreInferredAutomatic() {
        assertEquals(
            canvasInk,
            automaticColorOr(AUTOMATIC_LIGHT, followsTheme = null, canvasInk = canvasInk),
        )
        assertEquals(
            canvasInk,
            automaticColorOr(AUTOMATIC_DARK, followsTheme = null, canvasInk = canvasInk),
        )
    }

    /** The inference is confined to the two colours the automatic pen could ever have produced. */
    @Test
    fun legacyColorIsLeftAlone() {
        assertEquals(red, automaticColorOr(red, followsTheme = null, canvasInk = canvasInk))
    }

    /**
     * The picker's half of the rule: which swatch *is* automatic on this canvas.
     *
     * Pairs with the resolution above, and the two must agree — a tap on the colour automatic is
     * already producing is what `PenPanel` records as automatic rather than as a choice, so if this
     * ever named a colour [automaticColorOr] would not resolve to, picking it would pin ink to a
     * canvas again.
     */
    @Test
    fun automaticInkIsTheColorTheAutomaticPenIsShowing() {
        assertEquals(AUTOMATIC_LIGHT, automaticInkFor(isDark = true))
        assertEquals(AUTOMATIC_DARK, automaticInkFor(isDark = false))
    }

    /**
     * Alpha is part of the match, so a translucent highlight that happens to be white-ish is not
     * mistaken for automatic ink and repainted opaque over the writing it marks.
     */
    @Test
    fun translucentWhiteIsNotAutomatic() {
        val wash = 0x66FFFFFF
        assertEquals(wash, automaticColorOr(wash, followsTheme = null, canvasInk = canvasInk))
    }
}
