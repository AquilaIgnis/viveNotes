package com.vivenotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pen pane's swatch row rolls: a colour mixed on the wheel takes the front and the row keeps the
 * length that fits the panel. The rule is pure list arithmetic, so it is tested here rather than
 * through a rendered pane — what the pane has to get right is calling it, which `DrawTabTest` covers.
 */
class PenPaletteTest {

    private val orange = 0xFFEF6C00.toInt()

    @Test
    fun aNewColorTakesTheFrontAndTheTailFallsOff() {
        val rolled = PEN_COLORS.withColorInFront(orange)

        assertEquals(orange, rolled.first())
        assertEquals(PEN_COLORS.dropLast(1), rolled.drop(1))
        assertTrue("the last swatch made room", PEN_COLORS.last() !in rolled)
    }

    @Test
    fun theRowNeverOutgrowsTheSpaceItHas() {
        var row = PEN_COLORS
        repeat(PALETTE_SIZE * 2) { row = row.withColorInFront(0xFF000000.toInt() or it) }

        assertEquals(PALETTE_SIZE, row.size)
    }

    /**
     * Re-picking a colour already in the row promotes it instead of repeating it. Without this a
     * colour used twice would cost the row two entries and appear in it twice.
     */
    @Test
    fun aColorAlreadyInTheRowMovesRatherThanRepeats() {
        val existing = PEN_COLORS.last()
        val rolled = PEN_COLORS.withColorInFront(existing)

        assertEquals(existing, rolled.first())
        assertEquals("nothing was displaced", PEN_COLORS.size, rolled.size)
        assertEquals("and nothing was lost", PEN_COLORS.toSet(), rolled.toSet())
        assertEquals("nor duplicated", 1, rolled.count { it == existing })
    }

    @Test
    fun rePickingTheHeadChangesNothing() {
        assertEquals(PEN_COLORS, PEN_COLORS.withColorInFront(PEN_COLORS.first()))
    }

    /**
     * Nothing is pinned, so nine custom colours empty the shipped palette out — black and white
     * included. That is the price of "the newest colour is always first", and it is recoverable:
     * every one of them is still reachable from the wheel.
     */
    @Test
    fun enoughCustomColorsPushTheShippedPaletteOutEntirely() {
        var row = PEN_COLORS
        repeat(PALETTE_SIZE) { row = row.withColorInFront(0xFF102030.toInt() + it) }

        assertTrue("nothing shipped survives", row.none { it in PEN_COLORS })
    }

    /** White and black lead the row as shipped, which is the state a fresh install opens in. */
    @Test
    fun whiteAndBlackLeadTheStartingPalette() {
        assertEquals(0xFFFFFFFF.toInt(), PEN_COLORS[0])
        assertEquals(0xFF000000.toInt(), PEN_COLORS[1])
    }
}
