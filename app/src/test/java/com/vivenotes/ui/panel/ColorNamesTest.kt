package com.vivenotes.ui.panel

import com.vivenotes.R
import com.vivenotes.data.HIGHLIGHTER_COLORS
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.ui.editor.HIGHLIGHT_COLORS
import com.vivenotes.ui.editor.TEXT_COLORS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A swatch's name is all a screen reader has to tell it from its neighbours, so within any one
 * picker no two may share a name — and each has to be the name a sighted person would give it.
 */
class ColorNamesTest {

    @Test
    fun thePenPaletteIsNamedSwatchBySwatch() {
        assertEquals(
            listOf(
                R.string.color_white, R.string.color_black, R.string.color_red, R.string.color_cyan,
                R.string.color_green, R.string.color_yellow, R.string.color_orange,
                R.string.color_purple, R.string.color_blue,
            ),
            PEN_COLORS.map(::colorName),
        )
    }

    /** Translucent inks are named by what they tint, so their alpha must not turn them grey. */
    @Test
    fun theHighlighterInksAreNamedByTheirTint() {
        assertEquals(
            listOf(
                R.string.color_yellow, R.string.color_green, R.string.color_blue,
                R.string.color_pink, R.string.color_orange, R.string.color_purple,
            ),
            HIGHLIGHTER_COLORS.map(::colorName),
        )
    }

    @Test
    fun theRibbonFontColorsAreNamedSwatchBySwatch() {
        assertEquals(
            listOf(
                R.string.color_white, R.string.color_light_gray, R.string.color_gray,
                R.string.color_black, R.string.color_red, R.string.color_orange,
                R.string.color_yellow, R.string.color_green, R.string.color_blue,
                R.string.color_purple, R.string.color_cyan, R.string.color_pink,
            ),
            TEXT_COLORS.map(::colorName),
        )
    }

    /** The clear swatch at the end is named by the picker, not here. */
    @Test
    fun theRibbonHighlightsAreNamedSwatchBySwatch() {
        assertEquals(
            listOf(
                R.string.color_yellow, R.string.color_green, R.string.color_blue,
                R.string.color_pink, R.string.color_orange, R.string.color_purple,
                R.string.color_white,
            ),
            HIGHLIGHT_COLORS.filter { it != 0 }.map(::colorName),
        )
    }

    /** A colour mixed on the wheel can be anything; these are the ones a hue alone would misname. */
    @Test
    fun mixedColorsGetTheNameTheirHueSuggests() {
        assertEquals(R.string.color_brown, colorName(0xFF795548.toInt()))
        assertEquals(R.string.color_brown, colorName(0xFF8B4513.toInt()))
        assertEquals(R.string.color_blue, colorName(0xFF1A237E.toInt()))
        assertEquals(R.string.color_black, colorName(0xFF111111.toInt()))
        assertEquals(R.string.color_gray, colorName(0xFF6B7280.toInt()))
        assertEquals(R.string.color_white, colorName(0xFFFAF9F6.toInt()))
    }
}
