package com.vivenotes.ui

import com.vivenotes.data.DrawTool
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the stylus's first barrel button arms next.
 *
 * A JVM test rather than an instrumented one out of necessity, not preference: the emulator has no
 * stylus and cannot produce a `KEYCODE_STYLUS_BUTTON_PRIMARY` press at all — `CLAUDE.md` records how
 * far `uinput` gets and where it stops — so the rule is verified here and the dispatch that carries
 * it is verified by `StylusButtonDispatchTest`.
 */
class StylusButtonTest {

    // --- single press: writing versus rubbing out ------------------------------------------------

    @Test
    fun aPenInHandReachesForTheEraser() {
        assertEquals(DrawTool.Eraser, single(DrawTool.Pen(0)))
    }

    /** Any pen, not just the first — the button is about writing versus erasing. */
    @Test
    fun theSecondAndThirdPensAlsoReachForTheEraser() {
        assertEquals(DrawTool.Eraser, single(DrawTool.Pen(1)))
        assertEquals(DrawTool.Eraser, single(DrawTool.Pen(2)))
    }

    @Test
    fun anEraserInHandReachesBackForPenOne() {
        assertEquals(DrawTool.Pen(0), single(DrawTool.Eraser))
    }

    /**
     * Every other tool arms pen 1, which is what makes the button useful from anywhere rather than
     * only from the eraser. The empty hand is the case that matters most: it is what the canvas is
     * left in after a lasso or a text container is dismissed.
     */
    @Test
    fun everyOtherToolArmsPenOne() {
        listOf(
            DrawTool.None,
            DrawTool.Text,
            DrawTool.Lasso,
            DrawTool.Shape,
            DrawTool.Table,
            DrawTool.InkTable,
            DrawTool.Highlighter,
        ).forEach { tool ->
            assertEquals("from $tool", DrawTool.Pen(0), single(tool))
        }
    }

    /** Clicked twice from a pen, you are back where you started. */
    @Test
    fun singleClicksToggleRatherThanLatching() {
        val once = single(DrawTool.Pen(0))
        val twice = single(once)

        assertEquals(DrawTool.Eraser, once)
        assertEquals(DrawTool.Pen(0), twice)
    }

    // --- double press: the lasso -----------------------------------------------------------------

    /**
     * The lasso is reached from anywhere, not from a position in the toggle: a selection is what you
     * want *after* drawing, so it is as likely to be wanted with a pen in hand as with anything else.
     */
    @Test
    fun aDoublePressArmsTheLassoFromAnyTool() {
        listOf(
            DrawTool.Pen(0),
            DrawTool.Pen(2),
            DrawTool.Eraser,
            DrawTool.None,
            DrawTool.Text,
            DrawTool.Highlighter,
            DrawTool.Lasso,
        ).forEach { tool ->
            assertEquals("from $tool", DrawTool.Lasso, double(tool))
        }
    }

    private fun single(current: DrawTool) = nextToolForStylusButton(current, StylusPress.Single)

    private fun double(current: DrawTool) = nextToolForStylusButton(current, StylusPress.Double)
}
