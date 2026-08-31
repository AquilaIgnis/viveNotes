package com.vivenotes.pdf

import com.vivenotes.ink.InkBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the fit treats as one entity — `memory/pdfExportPlan.md` PD6.
 *
 * The algorithm rather than [groupContent] itself: a `PageStroke` carries a native ink mesh and
 * cannot be built off a device, and this is the part that decides whether a sum written beside a
 * paragraph travels with it or gets left on the previous sheet.
 */
class ContentGroupTest {

    private class Thing(val id: String, val bounds: InkBounds)

    private fun at(id: String, left: Float, top: Float, width: Float = 20f, height: Float = 20f) =
        Thing(id, InkBounds(left, top, left + width, top + height))

    /** A sheet big enough that nothing is cut for size alone, unless a test says otherwise. */
    private fun group(
        vararg things: Thing,
        maxWidth: Float = 10_000f,
        maxHeight: Float = 10_000f,
    ): List<Set<String>> = segmentByWhitespace(
        items = things.toList(),
        bounds = Thing::bounds,
        maxWidthDp = maxWidth,
        maxHeightDp = maxHeight,
    ).map { members -> members.map(Thing::id).toSet() }

    // --- the two axes --------------------------------------------------------------------------

    /**
     * The correction this file exists for. A paragraph on the left and a sum on the right, closer
     * together than the tolerance, are **one entity** — so the fit moves them onto a page together
     * instead of pulling one back and leaving the other behind.
     */
    @Test
    fun contentSideBySideWithinTheToleranceIsOneEntity() {
        assertEquals(
            listOf(setOf("paragraph", "sum")),
            group(
                at("paragraph", 0f, 0f, width = 400f, height = 200f),
                at("sum", 430f, 40f, width = 120f, height = 60f),
            ),
        )
    }

    /** Past the tolerance on x they are two columns, and two entities. */
    @Test
    fun contentSeparatedAcrossThePageIsTwoEntities() {
        val groups = group(
            at("left", 0f, 0f, width = 400f, height = 200f),
            at("right", 600f, 0f, width = 200f, height = 200f),
        )
        assertEquals(2, groups.size)
        assertTrue(setOf("left") in groups)
        assertTrue(setOf("right") in groups)
    }

    /** Lines of writing hold together down the page; the gap between them is nothing like 40 dp. */
    @Test
    fun linesOfWritingBecomeOneParagraph() {
        assertEquals(
            listOf(setOf("line1", "line2", "line3")),
            group(
                at("line1", 0f, 0f, width = 300f, height = 20f),
                at("line2", 0f, 30f, width = 300f, height = 20f),
                at("line3", 0f, 60f, width = 300f, height = 20f),
            ),
        )
    }

    /** Past the tolerance down the page they are two blocks. */
    @Test
    fun blocksSeparatedDownThePageAreTwoEntities() {
        assertEquals(2, group(at("first", 0f, 0f), at("second", 0f, 200f)).size)
    }

    /**
     * The cut has to find a lane clear across the block, so proximity cannot chain two columns
     * together through one item that happens to sit between them — which is exactly what the
     * previous union-find grouping did.
     */
    @Test
    fun aClearLaneSeparatesColumnsEvenWithContentAtBothHeights() {
        val groups = group(
            at("left-top", 0f, 0f, width = 300f, height = 40f),
            at("left-bottom", 0f, 60f, width = 300f, height = 40f),
            at("right-top", 500f, 0f, width = 300f, height = 40f),
            at("right-bottom", 500f, 60f, width = 300f, height = 40f),
        )
        assertEquals(2, groups.size)
        assertTrue(setOf("left-top", "left-bottom") in groups)
        assertTrue(setOf("right-top", "right-bottom") in groups)
    }

    // --- cutting for size ----------------------------------------------------------------------

    /**
     * A page of writing too tall for any sheet is broken at its widest lane — between paragraphs
     * rather than through a line. Without this the whole page is one entity that fits nowhere, and
     * the fit gives up on precisely the page that needed it.
     */
    @Test
    fun aBlockTooTallForASheetIsBrokenAtItsWidestLane() {
        val groups = group(
            at("para1-line1", 0f, 0f, width = 300f, height = 20f),
            at("para1-line2", 0f, 30f, width = 300f, height = 20f),
            // The lane before the next paragraph is wider than the one between lines, and still
            // under the tolerance — so nothing here would be cut at all if the block fitted.
            at("para2-line1", 0f, 75f, width = 300f, height = 20f),
            at("para2-line2", 0f, 105f, width = 300f, height = 20f),
            maxHeight = 80f,
        )
        assertEquals(2, groups.size)
        assertTrue(setOf("para1-line1", "para1-line2") in groups)
        assertTrue(setOf("para2-line1", "para2-line2") in groups)
    }

    /** With no lane anywhere there is nothing to cut, and an overlapping block stays whole. */
    @Test
    fun anUnbrokenBlockIsLeftWholeHoweverLargeItIs() {
        assertEquals(
            1,
            group(
                at("a", 0f, 0f, width = 500f, height = 500f),
                at("b", 100f, 100f, width = 500f, height = 500f),
                maxWidth = 100f,
                maxHeight = 100f,
            ).size,
        )
    }

    // --- edges ---------------------------------------------------------------------------------

    @Test
    fun oneThingIsOneGroup() {
        assertEquals(listOf(setOf("only")), group(at("only", 40f, 90f)))
    }

    @Test
    fun nothingGroupsToNothing() {
        assertEquals(emptyList<Set<String>>(), group())
    }

    @Test
    fun everyThingLandsInExactlyOneGroup() {
        val groups = group(
            at("a", 0f, 0f),
            at("b", 25f, 0f),
            at("c", 500f, 0f),
            at("d", 0f, 400f),
        )
        assertEquals(4, groups.sumOf { it.size })
        assertEquals(setOf("a", "b", "c", "d"), groups.flatten().toSet())
    }
}
