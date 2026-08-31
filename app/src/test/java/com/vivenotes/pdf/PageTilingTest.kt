package com.vivenotes.pdf

import com.vivenotes.ink.InkBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How an infinite canvas becomes sheets — `memory/pdfExportPlan.md` PD3 and PD5, read off
 * `memory/screenshots/canvastopdf.jpg`.
 *
 * These are the parts of the export that no screenshot catches. A column emitted before the row
 * below it looks like a correct PDF with its pages shuffled; a box nudged the wrong way looks like a
 * correct PDF that has moved somebody's diagram. Both are silent, and both are arithmetic.
 */
class PageTilingTest {

    private val tile = 1000f

    private fun item(
        id: String,
        left: Float,
        top: Float,
        width: Float = 100f,
        height: Float = 100f,
        kind: PdfItemKind = PdfItemKind.Content,
    ) = PdfItem(id, kind, InkBounds(left, top, left + width, top + height))

    private fun plan(items: List<PdfItem>, fit: Boolean = true) =
        PageTiling.plan(items, tileWidthDp = tile, tileHeightDp = tile, fit = fit)

    // --- the grid ------------------------------------------------------------------------------

    /**
     * The reference drawing's C1, C2, C3, C4: **down the column, then right**, not reading order.
     * Written on the drawing twice, and the one thing about the order anybody would notice.
     */
    @Test
    fun sheetsComeOutDownAColumnThenRight() {
        val plan = plan(
            listOf(
                item("top-left", 10f, 10f),
                item("top-right", 1100f, 10f),
                item("bottom-left", 10f, 1100f),
                item("bottom-right", 1100f, 1100f),
            ),
            fit = false,
        )
        assertEquals(4, plan.tiles.size)
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), plan.tiles.map { it.column to it.row })
    }

    /** The grid starts at the content, not at the page origin: no blank sheets before the writing. */
    @Test
    fun theGridIsAnchoredToTheContentsOwnCorner() {
        val plan = plan(listOf(item("far", 4000f, 6000f)), fit = false)
        assertEquals(1, plan.tiles.size)
        assertEquals(4000f, plan.tiles.single().area.left, 0.01f)
        assertEquals(6000f, plan.tiles.single().area.top, 0.01f)
    }

    /**
     * A canvas is mostly empty, and a tile nothing overlaps is not a page. Two diagrams on a
     * diagonal occupy two of the four tiles their bounding box covers.
     */
    @Test
    fun emptyTilesAreNotEmitted() {
        val plan = plan(
            listOf(item("one", 10f, 10f), item("two", 1100f, 1100f)),
            fit = false,
        )
        assertEquals(listOf(0 to 0, 1 to 1), plan.tiles.map { it.column to it.row })
    }

    /** A page holding nothing at all is still a page: a PDF with no pages is not a document. */
    @Test
    fun anEmptyPageStillProducesOneSheet() {
        assertEquals(1, plan(emptyList()).tiles.size)
    }

    /**
     * PD3's exception. A page already bound to the paper being exported to is returned whole, at its
     * own origin, because re-anchoring would move a layout the user placed on a sheet deliberately.
     */
    @Test
    fun aBoundPageIsOneSheetAtItsOwnOrigin() {
        val sheet = InkBounds(0f, 0f, 1323f, 1870f)
        val plan = PageTiling.plan(
            items = listOf(item("something", 600f, 900f)),
            tileWidthDp = tile,
            tileHeightDp = tile,
            boundSheet = sheet,
        )
        assertEquals(listOf(sheet), plan.tiles.map { it.area })
        assertTrue(plan.bound)
        assertTrue(plan.shifts.isEmpty())
    }

    // --- the fit -------------------------------------------------------------------------------

    /**
     * The right-hand half of the drawing: a box hanging over the page edge is pulled back by exactly
     * its overhang, and by nothing more. Its other axis is left alone.
     */
    @Test
    fun aStraddlingItemIsPulledBackByItsOverhangAlone() {
        val plan = plan(listOf(item("origin", 0f, 0f), item("straddles", 950f, 300f, width = 100f)))
        assertEquals(PdfShift(-50f, 0f), plan.shiftFor("straddles"))
        assertEquals(PdfShift.NONE, plan.shiftFor("origin"))
    }

    /** Both axes at once, when a box hangs over the corner. */
    @Test
    fun anItemOverTheCornerIsPulledBackOnBothAxes() {
        val plan = plan(listOf(item("origin", 0f, 0f), item("corner", 940f, 930f, 100f, 100f)))
        assertEquals(PdfShift(-40f, -30f), plan.shiftFor("corner"))
    }

    /**
     * Nothing to put it on. A drawing wider than the paper spans sheets whatever anybody does, and
     * shrinking it would be redrawing the user's work rather than laying it out.
     */
    @Test
    fun anItemLargerThanASheetIsLeftWhereItIs() {
        val plan = plan(listOf(item("origin", 0f, 0f), item("huge", 500f, 100f, width = 2000f)))
        assertEquals(PdfShift.NONE, plan.shiftFor("huge"))
    }

    /** The page's own header is not content placed on the canvas, so the fit never moves it. */
    @Test
    fun theTitleBandIsNeverMoved() {
        val plan = plan(
            listOf(
                item("title", 950f, 0f, width = 100f, kind = PdfItemKind.Title),
                item("origin", 0f, 0f),
            ),
        )
        assertEquals(PdfShift.NONE, plan.shiftFor("title"))
    }

    /** Switched off, every shift is zero and the cuts fall where they fall. That is the whole toggle. */
    @Test
    fun theFitDoesNothingWhenItIsOff() {
        val items = listOf(item("origin", 0f, 0f), item("straddles", 950f, 300f, width = 100f))
        assertTrue(plan(items, fit = false).shifts.isEmpty())
    }

    /** Pulling content inward can empty a tile; it can never need a new one. */
    @Test
    fun theFitCanEmptyATileAndNeverAddsOne() {
        val withoutFit = plan(
            listOf(item("origin", 0f, 0f), item("straddles", 950f, 300f, width = 100f)),
            fit = false,
        )
        val withFit = plan(listOf(item("origin", 0f, 0f), item("straddles", 950f, 300f, width = 100f)))
        assertEquals(listOf(0 to 0, 1 to 0), withoutFit.tiles.map { it.column to it.row })
        assertEquals(listOf(0 to 0), withFit.tiles.map { it.column to it.row })
    }

    // --- the content box -----------------------------------------------------------------------

    @Test
    fun contentBoundsEncloseEverything() {
        val bounds = listOf(item("a", 100f, 200f), item("b", 900f, 50f)).contentBounds()
        assertEquals(InkBounds(100f, 50f, 1000f, 300f), bounds)
    }

    @Test
    fun nothingOnThePageHasNoBounds() {
        assertNull(emptyList<PdfItem>().contentBounds())
    }
}
