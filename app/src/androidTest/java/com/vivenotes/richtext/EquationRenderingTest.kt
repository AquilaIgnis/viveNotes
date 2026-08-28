package com.vivenotes.richtext

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the shipped native parser handles the feature's representative equation. */
@RunWith(AndroidJUnit4::class)
class EquationRenderingTest {

    @Test
    fun rendersTheFundamentalTheoremIntegral() = runBlocking {
        val renderer = createEquationRenderer(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            latex = "{\\displaystyle \\int _{a}^{b}f'(t)\\,dt=f(b)-f(a)}",
            fontSizePx = 30f,
            color = Color.BLACK,
        )

        assertTrue(renderer.widthPx > 0f)
        assertTrue(renderer.totalHeightPx > 0f)
    }

    @Test
    fun fitsAResultWiderThanItsPaneToTheWidthItHas() = runBlocking {
        val renderer = wideSolutionSet()
        assertTrue("the fixture has to overflow to be worth fitting", renderer.widthPx > PANE_WIDTH)

        val fitted = renderer.fittedTo(PANE_WIDTH, minFontSizePx = 4f)

        assertEquals(PANE_WIDTH, fitted.widthPx, 0.5f)
        assertTrue(fitted.fontSize < renderer.fontSize)
    }

    @Test
    fun stopsShrinkingAtTheFloorAndStaysWide() = runBlocking {
        val renderer = wideSolutionSet()
        // An eighth of the width it wants, so the size that would fit is 3px whatever the font
        // metrics turn out to be — the floor has to be what stops it, not the arithmetic.
        val cramped = renderer.widthPx / 8f

        val fitted = renderer.fittedTo(cramped, minFontSizePx = FLOOR)

        // Still wider than the space, on purpose: past the floor the answer is on screen without
        // being readable, so what does not fit is left to be scrolled at a size that can be read.
        assertEquals(FLOOR, fitted.fontSize, 0.01f)
        assertTrue(fitted.widthPx > cramped)
    }

    @Test
    fun leavesAFormulaThatAlreadyFitsUntouched() = runBlocking {
        val renderer = createEquationRenderer(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            latex = "x = 2",
            fontSizePx = 24f,
            color = Color.BLACK,
        )

        assertSame(renderer, renderer.fittedTo(PANE_WIDTH, minFontSizePx = FLOOR))
    }

    /** The listed roots of a quadratic — the result that ran off the side of the panel. */
    private suspend fun wideSolutionSet() = createEquationRenderer(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        latex = "\\left[ \\left\\{ x : -1 - 2 i\\right\\}, \\  \\left\\{ x : -1 + 2 i\\right\\}\\right]",
        fontSizePx = 24f,
        color = Color.BLACK,
    )

    private companion object {
        /** About what the formula tools pane offers on the test tablets, in pixels. */
        const val PANE_WIDTH = 300f
        const val FLOOR = 9.6f
    }
}
