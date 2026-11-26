package com.vivenotes.ui.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.vivenotes.data.AUTOMATIC_DARK
import com.vivenotes.data.AUTOMATIC_LIGHT
import com.vivenotes.data.HIGHLIGHTER_COLORS
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.ui.panel.PenPanelTags
import com.vivenotes.ui.theme.DarkCanvasColors
import com.vivenotes.ui.theme.LightCanvasColors
import com.vivenotes.ui.theme.LocalCanvasColors
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Document tab's Font colour and Highlight buttons, which use the Draw tab's colour rows. */
class RibbonColorButtonsTest {

    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<Int>()
    private val added = mutableListOf<Int>()
    private var cleared = 0

    private fun setFontColor(current: Int?, dark: Boolean = false) {
        compose.setContent {
            ViveNotesTheme {
                CompositionLocalProvider(
                    LocalCanvasColors provides if (dark) DarkCanvasColors else LightCanvasColors,
                ) {
                    FontColorButton(
                        palette = PEN_COLORS,
                        current = current,
                        onPick = { picked += it },
                        onClear = { cleared++ },
                        onAddColor = { added += it },
                    )
                }
            }
        }
        compose.onNodeWithTag(HomeTags.FONT_COLOR).performClick()
    }

    private fun setHighlight(current: Int?) {
        compose.setContent {
            ViveNotesTheme {
                HighlightButton(current = current, onPick = { picked += it }, onClear = { cleared++ })
            }
        }
        compose.onNodeWithTag(HomeTags.HIGHLIGHT).performClick()
    }

    @Test
    fun fontColorOffersTheInkRowAndClosesOnAPick() {
        val red = PEN_COLORS[2]
        setFontColor(current = red)

        compose.onNodeWithTag(HomeTags.fontColor(red)).assertIsSelected()
        compose.onNodeWithTag(HomeTags.fontColor(PEN_COLORS[3])).performClick()

        assertEquals(listOf(PEN_COLORS[3]), picked)
        compose.onNodeWithTag(HomeTags.fontColor(red)).assertDoesNotExist()
    }

    /** Text with no colour follows the theme, so the automatic swatch is both its ring and its way back. */
    @Test
    fun theAutomaticSwatchStandsForNoColor() {
        setFontColor(current = null, dark = true)

        compose.onNodeWithTag(HomeTags.fontColor(AUTOMATIC_LIGHT)).assertIsSelected()
        compose.onNodeWithTag(HomeTags.fontColor(AUTOMATIC_DARK)).assertIsNotSelected()
        compose.onNodeWithTag(HomeTags.fontColor(AUTOMATIC_LIGHT)).performClick()

        assertEquals(1, cleared)
        assertTrue("clearing is not a colour", picked.isEmpty())
    }

    /** On a light page black is the automatic one, and white is an ordinary choice. */
    @Test
    fun onALightPageWhiteIsAColor() {
        setFontColor(current = null)

        compose.onNodeWithTag(HomeTags.fontColor(AUTOMATIC_LIGHT)).performClick()

        assertEquals(listOf(AUTOMATIC_LIGHT), picked)
        assertEquals(0, cleared)
    }

    /** A colour mixed for text joins the shared row, once, and Done closes the whole menu. */
    @Test
    fun aMixedColorJoinsTheRowAndDoneClosesTheMenu() {
        setFontColor(current = PEN_COLORS[2])
        compose.onNodeWithTag(HomeTags.FONT_COLOR_CUSTOM).performClick()
        compose.onNodeWithTag(PenPanelTags.WHEEL).performTouchInput {
            down(Offset(width - 1f, height / 2f))
            up()
        }
        compose.onNodeWithText("Done").performClick()

        assertEquals(1, picked.size)
        assertEquals(picked, added)
        compose.onNodeWithTag(PenPanelTags.WHEEL).assertDoesNotExist()
        compose.onNodeWithTag(HomeTags.FONT_COLOR_CUSTOM).assertDoesNotExist()
    }

    @Test
    fun highlightOffersTheHighlighterInks() {
        setHighlight(current = HIGHLIGHTER_COLORS[0])

        compose.onNodeWithTag(HomeTags.highlight(HIGHLIGHTER_COLORS[0])).assertIsSelected()
        compose.onNodeWithTag(HomeTags.HIGHLIGHT_NONE).assertIsNotSelected()
        compose.onNodeWithTag(HomeTags.highlight(HIGHLIGHTER_COLORS[2])).performClick()

        assertEquals(listOf(HIGHLIGHTER_COLORS[2]), picked)
        compose.onNodeWithTag(HomeTags.HIGHLIGHT_NONE).assertDoesNotExist()
    }

    @Test
    fun noneTakesTheHighlightOff() {
        setHighlight(current = null)

        compose.onNodeWithTag(HomeTags.HIGHLIGHT_NONE).assertIsSelected()
        compose.onNodeWithTag(HomeTags.HIGHLIGHT_NONE).performClick()

        assertEquals(1, cleared)
        assertTrue(picked.isEmpty())
    }
}
