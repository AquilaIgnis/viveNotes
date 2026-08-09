package com.vivenotes.ui

import com.vivenotes.data.DrawTool
import com.vivenotes.data.StylusAction
import com.vivenotes.data.StylusButtonMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a stylus barrel-button press arms — the bindings, and the two actions that are rules rather
 * than answers.
 *
 * A JVM test rather than an instrumented one out of necessity, not preference: the emulator has no
 * stylus and cannot produce these presses at all — `CLAUDE.md` records how far `uinput` gets and where
 * it stops — so the rules are verified here and the dispatch that carries them is verified by
 * `StylusButtonDispatchTest`.
 */
class StylusButtonTest {

    // --- the default map: nobody who ignores the pane can tell this became configurable ----------

    /**
     * `docs/stylusPlan.md` SB4. These three assertions are the whole promise of the defaults: single
     * click toggles pen and eraser, double click reaches the lasso, and a third click is unbound —
     * exactly what the hard-coded version shipped with.
     */
    @Test
    fun theDefaultMapIsTheBehaviourTheHardCodedVersionHad() {
        val map = StylusButtonMap()

        assertEquals(StylusAction.TogglePenEraser, map.actionFor(StylusPress.Single))
        assertEquals(StylusAction.Lasso, map.actionFor(StylusPress.Double))
        assertEquals(StylusAction.None, map.actionFor(StylusPress.Triple))
    }

    @Test
    fun theDefaultSingleClickTogglesAndTheDefaultDoubleClickLassos() {
        val map = StylusButtonMap()

        assertEquals(DrawTool.Eraser, map.armed(StylusPress.Single, from = DrawTool.Pen(0)))
        assertEquals(DrawTool.Pen(0), map.armed(StylusPress.Single, from = DrawTool.Eraser))
        assertEquals(DrawTool.Lasso, map.armed(StylusPress.Double, from = DrawTool.Pen(0)))
    }

    /** Unbound arms nothing, which is what leaves the keycode to fall through — SB5. */
    @Test
    fun theDefaultTripleClickArmsNothing() {
        assertNull(StylusButtonMap().armed(StylusPress.Triple, from = DrawTool.Pen(0)))
    }

    /** A binding is read from its own field, so one row cannot answer for another. */
    @Test
    fun eachClickCountReadsItsOwnBinding() {
        val map = StylusButtonMap(
            single = StylusAction.Highlighter,
            double = StylusAction.Undo,
            triple = StylusAction.Pen3,
        )

        assertEquals(StylusAction.Highlighter, map.actionFor(StylusPress.Single))
        assertEquals(StylusAction.Undo, map.actionFor(StylusPress.Double))
        assertEquals(StylusAction.Pen3, map.actionFor(StylusPress.Triple))
    }

    // --- the pen/eraser toggle, which is a rule and not a tool (SB2a) ----------------------------

    @Test
    fun aPenInHandReachesForTheEraser() {
        assertEquals(DrawTool.Eraser, toggle(DrawTool.Pen(0)))
    }

    /** Any pen, not just the first — the button is about writing versus erasing. */
    @Test
    fun theSecondAndThirdPensAlsoReachForTheEraser() {
        assertEquals(DrawTool.Eraser, toggle(DrawTool.Pen(1)))
        assertEquals(DrawTool.Eraser, toggle(DrawTool.Pen(2)))
    }

    @Test
    fun anEraserInHandReachesBackForPenOne() {
        assertEquals(DrawTool.Pen(0), toggle(DrawTool.Eraser))
    }

    /**
     * Every other tool arms pen 1, which is what makes the button useful from anywhere rather than
     * only from the eraser. The empty hand is the case that matters most: it is what the canvas is
     * left in after a lasso or a text container is dismissed.
     */
    @Test
    fun everyOtherToolArmsPenOne() {
        EVERY_TOOL.filterNot { it is DrawTool.Pen || it == DrawTool.Eraser }.forEach { tool ->
            assertEquals("from $tool", DrawTool.Pen(0), toggle(tool))
        }
    }

    /** Clicked twice from a pen, you are back where you started. */
    @Test
    fun theToggleTogglesRatherThanLatching() {
        val once = toggle(DrawTool.Pen(0))
        val twice = toggle(once!!)

        assertEquals(DrawTool.Eraser, once)
        assertEquals(DrawTool.Pen(0), twice)
    }

    // --- cycling the pens, the other rule (SB2) --------------------------------------------------

    @Test
    fun cyclingWalksThePensAndWraps() {
        assertEquals(DrawTool.Pen(1), cycle(DrawTool.Pen(0)))
        assertEquals(DrawTool.Pen(2), cycle(DrawTool.Pen(1)))
        assertEquals(DrawTool.Pen(0), cycle(DrawTool.Pen(2)))
    }

    /** From anything that is not a pen, cycling picks the pen up rather than skipping to pen 2. */
    @Test
    fun cyclingFromAnythingElseArrivesAtPenOne() {
        EVERY_TOOL.filterNot { it is DrawTool.Pen }.forEach { tool ->
            assertEquals("from $tool", DrawTool.Pen(0), cycle(tool))
        }
    }

    // --- the fixed actions ----------------------------------------------------------------------

    /** A named pen arms that pen from anywhere, including from itself. */
    @Test
    fun aNamedPenArmsThatPen() {
        assertEquals(DrawTool.Pen(0), armed(StylusAction.Pen1, DrawTool.Eraser))
        assertEquals(DrawTool.Pen(1), armed(StylusAction.Pen2, DrawTool.Pen(1)))
        assertEquals(DrawTool.Pen(2), armed(StylusAction.Pen3, DrawTool.Text))
    }

    /**
     * The highlighter, the eraser and the lasso are reached from anywhere rather than from a position
     * in a toggle — a selection is what you want *after* drawing, so it is as likely to be wanted
     * with a pen in hand as with anything else.
     */
    @Test
    fun theFixedToolsAreReachedFromEveryTool() {
        mapOf(
            StylusAction.Highlighter to DrawTool.Highlighter,
            StylusAction.Eraser to DrawTool.Eraser,
            StylusAction.Lasso to DrawTool.Lasso,
        ).forEach { (action, expected) ->
            EVERY_TOOL.forEach { tool ->
                assertEquals("$action from $tool", expected, armed(action, tool))
            }
        }
    }

    // --- the actions that arm no tool ------------------------------------------------------------

    /**
     * Undo and Redo act on the page, not on the hand, so they must not disturb the armed tool — and
     * that is also what keeps them from dragging the Draw tab forward (SB7), since the view model
     * moves the tab only on the branch that produced a tool.
     */
    @Test
    fun undoAndRedoArmNoTool() {
        listOf(StylusAction.Undo, StylusAction.Redo).forEach { action ->
            EVERY_TOOL.forEach { tool ->
                assertNull("$action from $tool", armed(action, tool))
            }
        }
    }

    @Test
    fun nothingArmsNoTool() {
        EVERY_TOOL.forEach { tool ->
            assertNull("from $tool", armed(StylusAction.None, tool))
        }
    }

    /** Every action is either a tool or one of the three that are deliberately not — no gaps. */
    @Test
    fun everyActionEitherArmsAToolOrIsOneOfTheThreeThatDoNot() {
        val armsNothing = setOf(StylusAction.None, StylusAction.Undo, StylusAction.Redo)

        StylusAction.entries.forEach { action ->
            val tool = armed(action, DrawTool.None)
            if (action in armsNothing) {
                assertNull("$action must arm nothing", tool)
            } else {
                assertEquals("$action must arm a tool", true, tool != null)
            }
        }
    }

    private fun toggle(current: DrawTool) = armed(StylusAction.TogglePenEraser, current)

    private fun cycle(current: DrawTool) = armed(StylusAction.CyclePens, current)

    private fun armed(action: StylusAction, current: DrawTool) = action.toolFrom(current)

    private fun StylusButtonMap.armed(press: StylusPress, from: DrawTool) =
        actionFor(press).toolFrom(from)

    private companion object {
        /** One of each, so a rule that forgets a tool fails here rather than on the tablet. */
        val EVERY_TOOL = listOf(
            DrawTool.Pen(0),
            DrawTool.Pen(1),
            DrawTool.Pen(2),
            DrawTool.Highlighter,
            DrawTool.Eraser,
            DrawTool.Shape,
            DrawTool.Table,
            DrawTool.InkTable,
            DrawTool.Lasso,
            DrawTool.Text,
            DrawTool.None,
        )
    }
}
