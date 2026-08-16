package com.vivenotes.model

import com.vivenotes.model.PageSpace.Axis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Insert Space's arithmetic — feature E2.
 *
 * The two rules worth pinning down are the ones a user would notice being wrong: which side of the
 * line an object is judged to be on, and how far a closing drag is allowed to go. Both are stated in
 * [PageSpace]; these are the statements as tests.
 */
class PageSpaceTest {

    private fun down(at: Float, amount: Float) = SpaceCut(Axis.Vertical, at, amount)

    private fun right(at: Float, amount: Float) = SpaceCut(Axis.Horizontal, at, amount)

    // --- which side of the line ------------------------------------------------------------------

    @Test
    fun aVerticalCutMovesWhatStartsBelowIt() {
        val cut = down(at = 200f, amount = 50f)
        assertTrue(cut.moves(x = 0f, y = 260f))
        assertFalse(cut.moves(x = 0f, y = 199f))
    }

    /** The line is inclusive, so content resting exactly on it goes with the drag rather than staying. */
    @Test
    fun contentExactlyOnTheLineMoves() {
        assertTrue(down(at = 200f, amount = 50f).moves(x = 0f, y = 200f))
    }

    /**
     * The near edge decides and nothing else, so a tall object that begins above the line stays put
     * however far past it the object reaches — [PageSpace] for why that is the only rule the document
     * can answer.
     */
    @Test
    fun anObjectStraddlingTheLineStaysPut() {
        assertFalse(down(at = 200f, amount = 50f).moves(x = 0f, y = 150f))
    }

    @Test
    fun aHorizontalCutReadsXWhereAVerticalOneReadsY() {
        val cut = right(at = 300f, amount = 40f)
        assertTrue(cut.moves(x = 320f, y = 0f))
        assertFalse(cut.moves(x = 280f, y = 900f))
        assertEquals(320f, cut.nearEdge(x = 320f, y = 0f))
    }

    // --- which way it pushes ---------------------------------------------------------------------

    @Test
    fun aVerticalCutTranslatesOnlyDown() {
        val cut = down(at = 100f, amount = 60f)
        assertEquals(0f, cut.dx)
        assertEquals(60f, cut.dy)
    }

    @Test
    fun aHorizontalCutTranslatesOnlyAcross() {
        val cut = right(at = 100f, amount = -25f)
        assertEquals(-25f, cut.dx)
        assertEquals(0f, cut.dy)
    }

    @Test
    fun aTapIsNotAGesture() {
        assertTrue(down(at = 100f, amount = 0f).isEmpty)
        assertFalse(down(at = 100f, amount = 0.5f).isEmpty)
    }

    // --- the closing limit -----------------------------------------------------------------------

    /** Opening space is unbounded: the canvas grows down and to the right without limit. */
    @Test
    fun openingSpaceIsNeverLimited() {
        val cut = down(at = 100f, amount = 5000f)
        assertEquals(cut, cut.limitedTo(nearestMovedEdge = 120f))
    }

    @Test
    fun closingSpaceStopsAtTheLine() {
        // 60 dp of gap between the line and the nearest thing below it, and a drag asking for 200.
        val limited = down(at = 100f, amount = -200f).limitedTo(nearestMovedEdge = 160f)
        assertEquals(-60f, limited.amount)
    }

    @Test
    fun closingSpaceShorterThanTheGapIsLeftAlone() {
        val limited = down(at = 100f, amount = -20f).limitedTo(nearestMovedEdge = 160f)
        assertEquals(-20f, limited.amount)
    }

    /** Nothing on the moving side means nothing to limit — and nothing to move, which the caller sees. */
    @Test
    fun closingSpaceWithNothingBelowIsLeftAlone() {
        val limited = down(at = 100f, amount = -200f).limitedTo(nearestMovedEdge = null)
        assertEquals(-200f, limited.amount)
    }

    /**
     * The limit is what keeps the origin-corner invariant intact for free: content held off the line
     * is held off zero too, because a line is never drawn at a negative coordinate.
     */
    @Test
    fun contentClosedAgainstTheLineNeverCrossesIt() {
        val cut = down(at = 0f, amount = -400f)
        val limited = cut.limitedTo(nearestMovedEdge = 90f)
        assertEquals(-90f, limited.amount)
        assertEquals(0f, 90f + limited.dy)
    }

    /** A cut placed exactly on the content it would move has no room at all, and says so. */
    @Test
    fun aClosingDragAgainstContentOnTheLineDoesNothing() {
        val limited = down(at = 240f, amount = -80f).limitedTo(nearestMovedEdge = 240f)
        assertTrue(limited.isEmpty)
    }

    @Test
    fun theLimitAppliesOnTheHorizontalAxisToo() {
        val limited = right(at = 50f, amount = -300f).limitedTo(nearestMovedEdge = 130f)
        assertEquals(-80f, limited.dx)
        assertEquals(0f, limited.dy)
    }

    /** Limiting changes only the amount: the line and the axis are what the user drew. */
    @Test
    fun limitingKeepsTheLineAndTheAxis() {
        val limited = right(at = 50f, amount = -300f).limitedTo(nearestMovedEdge = 130f)
        assertEquals(Axis.Horizontal, limited.axis)
        assertEquals(50f, limited.at)
    }
}
