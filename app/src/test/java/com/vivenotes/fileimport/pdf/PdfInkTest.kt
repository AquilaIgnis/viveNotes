package com.vivenotes.fileimport.pdf

import com.vivenotes.fileimport.pdf.PdfFixture.line
import com.vivenotes.fileimport.pdf.PdfTestRaster.Companion.BLACK
import com.vivenotes.fileimport.pdf.PdfTestRaster.Companion.WHITE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfInkTest {
    private val blue = 0xFF4472C4.toInt()
    private val paleBlue = 0xFFE9EBF5.toInt()

    /** Rules at [xs] and [ys] running the whole way across and down, in [color]. */
    private fun PdfTestRaster.rules(xs: List<Float>, ys: List<Float>, color: Int, thickness: Float = 1f) {
        for (x in xs) fill(PdfRect(x, ys.first(), x + thickness, ys.last() + thickness), color)
        for (y in ys) fill(PdfRect(xs.first(), y, xs.last() + thickness, y + thickness), color)
    }

    @Test
    fun ruledLinesThatMeetAreATable() {
        val page = PdfTestRaster(300, 300)
        page.rules(listOf(50f, 120f, 190f), listOf(50f, 90f, 130f), BLACK)

        val grid = readInk(page.raster(), emptyList()).grids.single()

        assertEquals(2, grid.rows)
        assertEquals(2, grid.columns)
    }

    /** What a slide's table is: coloured cells, and the white of the page showing between them. */
    @Test
    fun whiteLinesBetweenColouredCellsAreATable() {
        val page = PdfTestRaster(300, 300)
        page.fill(PdfRect(50f, 50f, 250f, 170f), blue)
        page.rules(listOf(100f, 150f, 200f), listOf(90f, 130f), WHITE)
        page.fill(PdfRect(50f, 90f, 251f, 91f), WHITE)
        page.fill(PdfRect(50f, 130f, 251f, 131f), WHITE)
        for (x in listOf(100f, 150f, 200f)) page.fill(PdfRect(x, 50f, x + 1f, 170f), WHITE)

        val grid = readInk(page.raster(), emptyList()).grids.single()

        assertEquals(3, grid.rows)
        assertEquals(4, grid.columns)
    }

    @Test
    fun aCellsColourIsReadFromThePage() {
        val page = PdfTestRaster(300, 300)
        page.fill(PdfRect(50f, 50f, 250f, 170f), blue)
        page.fill(PdfRect(50f, 90f, 251f, 91f), WHITE)
        page.fill(PdfRect(50f, 130f, 251f, 131f), WHITE)
        for (x in listOf(100f, 150f, 200f)) page.fill(PdfRect(x, 50f, x + 1f, 170f), WHITE)

        val grid = readInk(page.raster(), emptyList()).grids.single()

        assertEquals(0xFF4070C0.toInt(), grid.fills[0])
    }

    /** The palest of PowerPoint's banding is only twenty levels from white. */
    @Test
    fun theLinesShowOnThePalestTints() {
        val page = PdfTestRaster(300, 300)
        page.fill(PdfRect(50f, 50f, 250f, 170f), paleBlue)
        page.fill(PdfRect(50f, 90f, 251f, 91f), WHITE)
        page.fill(PdfRect(50f, 130f, 251f, 131f), WHITE)
        for (x in listOf(100f, 150f, 200f)) page.fill(PdfRect(x, 50f, x + 1f, 170f), WHITE)

        assertEquals(1, readInk(page.raster(), emptyList()).grids.size)
    }

    /** That band is wider than a line is allowed to skip, and cuts every column's line in two. */
    @Test
    fun theWiderBandUnderAHeaderDoesNotSplitTheColumns() {
        val page = PdfTestRaster(300, 300)
        page.fill(PdfRect(50f, 50f, 250f, 170f), blue)
        page.fill(PdfRect(50f, 75f, 251f, 77.5f), WHITE)
        page.fill(PdfRect(50f, 120f, 251f, 121f), WHITE)
        for (x in listOf(100f, 150f, 200f)) page.fill(PdfRect(x, 50f, x + 1f, 170f), WHITE)

        val grid = readInk(page.raster(), emptyList()).grids.single()

        assertEquals(4, grid.columns)
        assertEquals(3, grid.rows)
    }

    @Test
    fun aRectangleAroundNothingIsNeitherATableNorAFigure() {
        val page = PdfTestRaster(300, 300)
        page.rules(listOf(50f, 250f), listOf(50f, 200f), BLACK)

        val ink = readInk(page.raster(), emptyList())

        assertTrue(ink.grids.isEmpty())
        assertTrue(ink.figures.isEmpty())
    }

    /** Boxes joined by lines cross each other too, but leave most of the grid they imply undrawn. */
    @Test
    fun aDiagramsBoxesAreNotATable() {
        val page = PdfTestRaster(400, 400)
        page.fill(PdfRect(50f, 50f, 150f, 51f), BLACK)
        page.fill(PdfRect(50f, 100f, 150f, 101f), BLACK)
        page.fill(PdfRect(50f, 50f, 51f, 101f), BLACK)
        page.fill(PdfRect(150f, 50f, 151f, 101f), BLACK)
        page.fill(PdfRect(250f, 200f, 350f, 201f), BLACK)
        page.fill(PdfRect(250f, 250f, 350f, 251f), BLACK)
        page.fill(PdfRect(250f, 200f, 251f, 251f), BLACK)
        page.fill(PdfRect(350f, 200f, 351f, 251f), BLACK)
        page.fill(PdfRect(151f, 75f, 250f, 76f), BLACK)
        page.fill(PdfRect(249f, 75f, 250f, 225f), BLACK)

        assertTrue(readInk(page.raster(), emptyList()).grids.isEmpty())
    }

    @Test
    fun aSolidShapeIsAFigure() {
        val page = PdfTestRaster(300, 300)
        page.fill(PdfRect(80f, 80f, 180f, 160f), blue)

        val figure = readInk(page.raster(), emptyList()).figures.single()

        assertEquals(PdfRect(80f, 80f, 180f, 160f), figure)
    }

    @Test
    fun partsCloseTogetherAreOneFigure() {
        val page = PdfTestRaster(300, 300)
        page.fill(PdfRect(80f, 80f, 120f, 120f), blue)
        page.fill(PdfRect(123f, 80f, 163f, 120f), blue)

        assertEquals(1, readInk(page.raster(), emptyList()).figures.size)
    }

    @Test
    fun partsFarApartAreTwoFigures() {
        val page = PdfTestRaster(300, 300)
        page.fill(PdfRect(40f, 80f, 80f, 120f), blue)
        page.fill(PdfRect(200f, 80f, 240f, 120f), blue)

        assertEquals(2, readInk(page.raster(), emptyList()).figures.size)
    }

    @Test
    fun wordsAreNotMistakenForADrawing() {
        val page = PdfTestRaster(300, 300)
        val word = line("Heading", 80f, 100f, size = 20f).words.single()
        page.fill(word.box, BLACK)

        assertTrue(readInk(page.raster(), listOf(word)).figures.isEmpty())
    }

    /** A shaded box behind a paragraph is background, and the words on it are what matters. */
    @Test
    fun aColouredBackgroundUnderWordsIsNotAFigure() {
        val page = PdfTestRaster(400, 300)
        val words = line("words sitting on a shaded background box", 70f, 100f, size = 12f).words
        page.fill(PdfRect(50f, 60f, 350f, 180f), paleBlue)
        words.forEach { page.fill(it.box, BLACK) }

        assertTrue(readInk(page.raster(), words).figures.isEmpty())
    }

    @Test
    fun aRuleBrokenByNothingButItsCrossingsStaysWhole() {
        val pieces = listOf(
            PdfRule(horizontal = true, at = 50f, from = 10f, to = 100f),
            PdfRule(horizontal = true, at = 50.5f, from = 102f, to = 200f),
            PdfRule(horizontal = true, at = 150f, from = 10f, to = 200f),
        )
        val down = listOf(
            PdfRule(horizontal = false, at = 10f, from = 50f, to = 150f),
            PdfRule(horizontal = false, at = 200f, from = 50f, to = 150f),
            PdfRule(horizontal = false, at = 105f, from = 50f, to = 150f),
        )

        val found = buildGrids(pieces, down, minFullRules = 2)

        assertEquals(1, found.size)
        assertEquals(2, found.single().grid.columns)
    }
}
