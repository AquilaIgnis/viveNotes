package com.vivenotes.fileimport.pdf

import com.vivenotes.fileimport.docx.ParagraphPiece
import com.vivenotes.fileimport.docx.SeparatorPiece
import com.vivenotes.fileimport.docx.TablePiece
import com.vivenotes.fileimport.docx.WordAlign
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordPicture
import com.vivenotes.fileimport.docx.WordText
import com.vivenotes.fileimport.pdf.PdfFixture.line
import com.vivenotes.fileimport.pdf.PdfFixture.page
import com.vivenotes.fileimport.pdf.PdfFixture.paragraphs
import com.vivenotes.fileimport.pdf.PdfFixture.plainText
import com.vivenotes.fileimport.pdf.PdfFixture.texts
import com.vivenotes.fileimport.pdf.PdfFixture.wrapped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
    private val prose = "The quick brown fox jumps over the lazy dog and keeps running across the wide open field " +
        "until the evening comes and the moon rises above the distant hills where nobody has been for many years now"

    private fun layout(lines: List<PdfLine>, bodyEm: Float = 10f): PdfPageLayout = layoutPage(page(lines), bodyEm)

    private fun pictures(layout: PdfPageLayout): List<WordPicture> =
        layout.paragraphs().flatMap { it.inlines.filterIsInstance<WordPicture>() }

    @Test
    fun wrappedLinesAreOneParagraph() {
        val lines = wrapped(prose, 72f, 400f, 100f)
        assertTrue(lines.size >= 3)

        assertEquals(listOf(prose), layout(lines).texts())
    }

    @Test
    fun aHardLineBreakLeavesAShortLineThatEndsItsParagraph() {
        val lines = listOf(line("float f;", 72f, 100f), line("double g;", 72f, 112f)) +
            wrapped(prose, 72f, 400f, 124f)

        assertEquals(listOf("float f;", "double g;", prose), layout(lines).texts())
    }

    @Test
    fun aBlankLineEndsAParagraph() {
        val first = wrapped(prose, 72f, 400f, 100f)
        val second = wrapped(prose, 72f, 400f, first.last().baseline + 30f)

        assertEquals(2, layout(first + second).texts().size)
    }

    @Test
    fun anIndentedLineStartsAParagraph() {
        val first = wrapped(prose, 72f, 400f, 100f)
        val indented = wrapped(prose, 90f, 400f, first.last().baseline + 12f)

        assertEquals(2, layout(first + indented).texts().size)
    }

    @Test
    fun aHyphenatedWordIsRejoined() {
        val lines = listOf(line("the quick brown fox jumps over the lazy dog and wan-", 72f, 100f), line("ders off home", 72f, 112f))

        assertEquals(listOf("the quick brown fox jumps over the lazy dog and wanders off home"), layout(lines).texts())
    }

    @Test
    fun codeKeepsItsLines() {
        val lines = listOf(
            line("addi s0, zero, 512", 72f, 100f, mono = true),
            line("addi s1, zero, 11111", 72f, 112f, mono = true),
            line("addi s2, zero, 33333", 72f, 124f, mono = true),
        )

        val result = layout(lines)

        assertEquals(3, result.texts().size)
        assertEquals("monospace", (result.paragraphs()[0].inlines[0] as WordText).style.font)
    }

    @Test
    fun aListsMarkerBecomesALabelAndNestingALevel() {
        val lines = listOf(line("• First item", 72f, 100f), line("• Deeper item", 110f, 112f), line("• Last item", 72f, 124f))

        val paragraphs = layout(lines).paragraphs()

        assertEquals(listOf("First item", "Deeper item", "Last item"), paragraphs.map { it.plainText() })
        assertTrue(paragraphs.all { it.label?.bullet == true })
        assertEquals(listOf(0, 2, 0), paragraphs.map { it.label?.level })
    }

    /** A slide can put a list anywhere on itself, and that says nothing about how deep it is. */
    @Test
    fun aListFarAcrossThePageIsStillTopLevel() {
        val lines = listOf(line("• First item", 350f, 100f), line("• Other item", 350f, 112f))

        assertEquals(listOf(0, 0), layout(lines).paragraphs().map { it.label?.level })
    }

    @Test
    fun aNumberedItemKeepsItsNumber() {
        val paragraph = layout(listOf(line("1. Find all red cars", 72f, 100f))).paragraphs().single()

        assertEquals("1.", paragraph.label?.text)
        assertEquals(false, paragraph.label?.bullet)
    }

    @Test
    fun aTwoLineBulletIsOneItem() {
        val lines = wrapped("• $prose", 72f, 400f, 100f)

        val paragraph = layout(lines).paragraphs().single()

        assertEquals(prose, paragraph.plainText())
    }

    @Test
    fun aLargeLineIsAHeadingByHowMuchLarger() {
        fun levelOf(size: Float): Int? =
            layout(listOf(line("Section name", 72f, 100f, size = size))).paragraphs().single().style.outlineLevel

        assertEquals(0, levelOf(18f))
        assertEquals(1, levelOf(14f))
        assertEquals(2, levelOf(12.5f))
        assertNull(levelOf(10f))
    }

    @Test
    fun aHeadingCanBeSetAtBodySizeWhenBoldAndNumbered() {
        val heading = line("3.1 Residual Learning", 72f, 100f)
        heading.words.forEach { it.bold = true }

        assertEquals(2, layout(listOf(heading)).paragraphs().single().style.outlineLevel)
    }

    @Test
    fun boldAloneIsNotAHeading() {
        val bold = line("Please read this first", 72f, 100f)
        bold.words.forEach { it.bold = true }

        assertNull(layout(listOf(bold)).paragraphs().single().style.outlineLevel)
    }

    @Test
    fun aBulletIsNeverAHeading() {
        val paragraph = layout(listOf(line("• Big bullet", 72f, 100f, size = 24f))).paragraphs().single()

        assertNull(paragraph.style.outlineLevel)
        assertEquals("Big bullet", paragraph.plainText())
    }

    @Test
    fun columnsAreReadOneAfterTheOther() {
        val other = prose.split(" ").reversed().joinToString(" ")
        val left = wrapped(prose, 72f, 290f, 100f)
        val right = wrapped(other, 322f, 540f, 100f)

        assertEquals(listOf(prose, other), layout(left + right).texts())
    }

    /** The labels down the side of a listing line up as neatly as a column, one word at a time. */
    @Test
    fun aLabelStaysOnTheRowOfTheCodeItNames() {
        val code = (0 until 6).map { line("slti t0, s2, 11111", 130f, 100f + it * 12f, mono = true) }
        val labels = listOf(0, 2, 4).map { line("loop$it:", 72f, 100f + it * 12f, mono = true) }

        val texts = layout(code + labels).texts()

        assertTrue(texts.joinToString(), texts[0].startsWith("loop0:"))
        assertTrue(texts[0].endsWith("11111"))
    }

    private val slideLines = listOf(
        line("Summary", 60f, 60f, size = 32f),
        line("• Renaming", 60f, 140f, size = 20f),
        line("• Selection and projection", 60f, 170f, size = 20f),
    )

    private fun slide(lines: List<PdfLine>) = layoutPage(page(lines, width = 720f, height = 540f), 12f)

    @Test
    fun aSlidesFirstLineIsItsTitle() {
        val paragraphs = slide(slideLines).paragraphs()

        assertEquals(0, paragraphs.first().style.outlineLevel)
        assertTrue(paragraphs.drop(1).all { it.style.outlineLevel == null })
    }

    @Test
    fun bigLettersFurtherDownASlideAreNotHeadings() {
        val lines = slideLines + line("E = m c", 300f, 300f, size = 36f)

        assertNull(slide(lines).paragraphs().last().style.outlineLevel)
    }

    @Test
    fun aSlideWithOneLineHasItAsItsTitle() {
        assertEquals(0, slide(listOf(line("Part Two", 60f, 250f, size = 24f))).paragraphs().single().style.outlineLevel)
    }

    @Test
    fun aCentredLineOnASlideIsCentred() {
        val width = 200f
        val title = line("Database Fundamentals", 360f - width / 2f, 250f, size = 20f)

        assertEquals(WordAlign.Center, slide(listOf(title)).paragraphs().single().style.align)
    }

    @Test
    fun slidesStayInTextBoxesOfTheirOwn() {
        val pieces = joinPages(listOf(slide(slideLines), slide(slideLines)))

        assertEquals(1, pieces.count { it === SeparatorPiece })
    }

    @Test
    fun documentPagesRunOn() {
        val pages = listOf(layout(wrapped(prose, 72f, 400f, 100f)), layout(wrapped(prose, 72f, 400f, 100f)))

        assertTrue(joinPages(pages).none { it === SeparatorPiece })
    }

    @Test
    fun aSentenceRunningOverAPageIsPutBackTogether() {
        // Filled to the margin, as a line that runs on into the next page would be.
        val full = "the quick brown fox jumps over the lazy dog and then"
        val first = layout(listOf(line(full, 72f, 100f), line(full, 72f, 112f)))
        val second = layout(listOf(line("continues on the next page.", 72f, 100f)))

        val joined = joinPages(listOf(first, second))

        assertEquals(1, joined.size)
        assertTrue((joined.single() as ParagraphPiece).paragraph.plainText().endsWith("and then continues on the next page."))
    }

    @Test
    fun aFullStopEndsThePageAndTheNextPageStartsFresh() {
        val first = layout(wrapped("$prose.", 72f, 400f, 100f))
        val second = layout(listOf(line("continues on the next page.", 72f, 100f)))

        assertEquals(2, joinPages(listOf(first, second)).size)
    }

    private fun fractionLines(): List<PdfLine> = listOf(
        line("f(x) =", 72f, 100f),
        line("a+b", 105f, 96.5f),
        line("cd", 107f, 103.5f),
    )

    @Test
    fun aFractionComesInAsAPicture() {
        val result = layout(fractionLines())

        assertTrue(result.texts().isEmpty())
        assertEquals(1, pictures(result).size)
    }

    @Test
    fun anEquationReadableAsALineStaysText() {
        val result = layout(listOf(line("y = F(x) + x", 200f, 100f)))

        assertEquals(listOf("y = F(x) + x"), result.texts())
    }

    @Test
    fun anEquationsNumberGoesInItsPicture() {
        val result = layout(fractionLines() + line("(1)", 500f, 100f))

        assertTrue(result.texts().isEmpty())
    }

    @Test
    fun sentencesAreNeverPartOfAFormula() {
        val sentence = line("every single sentence stays readable text here", 72f, 100f)
        val result = layout(listOf(sentence, line("a+b", 105f, 96.5f), line("cd", 107f, 103.5f)))

        assertTrue(result.texts().contains("every single sentence stays readable text here"))
    }

    /** PowerPoint draws a bowtie as a shape, and a formula with a hole in it is no use to anyone. */
    @Test
    fun aDrawnSymbolAmongLettersIsCutOutWithThem() {
        val result = layoutPage(
            page(
                listOf(line("R", 100f, 100f, size = 24f), line("S = D", 150f, 100f, size = 24f)),
                figures = listOf(PdfRect(120f, 82f, 145f, 102f)),
            ),
            10f,
        )

        assertTrue(result.texts().isEmpty())
        assertEquals(1, pictures(result).size)
    }

    @Test
    fun aDrawingBigEnoughToBeAFigureStaysApartFromTheTextBesideIt() {
        val result = layoutPage(
            page(listOf(line("Beside it", 300f, 200f)), figures = listOf(PdfRect(100f, 100f, 280f, 300f))),
            10f,
        )

        assertEquals(listOf("Beside it"), result.texts())
    }

    @Test
    fun piecesOfOneDrawingWithNoProseBetweenAreOnePicture() {
        val result = layoutPage(
            page(emptyList(), figures = listOf(PdfRect(100f, 100f, 300f, 200f), PdfRect(100f, 215f, 300f, 300f))),
            10f,
        )

        assertEquals(1, pictures(result).size)
    }

    @Test
    fun proseBetweenTwoDrawingsKeepsThemApart() {
        val result = layoutPage(
            page(
                listOf(line("A sentence of ordinary prose between two figures.", 100f, 212f)),
                figures = listOf(PdfRect(100f, 100f, 300f, 200f), PdfRect(100f, 220f, 300f, 300f)),
            ),
            10f,
        )

        assertEquals(2, pictures(result).size)
    }

    /** Merged across the gutter, they would span it and the page could no longer be read as columns. */
    @Test
    fun drawingsEitherSideOfAColumnGutterStayApart() {
        val left = wrapped(prose, 72f, 290f, 310f)
        val right = wrapped(prose, 322f, 540f, 310f)
        val result = layoutPage(
            page(left + right, figures = listOf(PdfRect(72f, 100f, 290f, 200f), PdfRect(310f, 100f, 540f, 200f))),
            10f,
        )

        assertEquals(2, pictures(result).size)
    }

    @Test
    fun drawingsSideBySideUnderOneColumnOfProseAreOnePicture() {
        val above = wrapped(prose, 72f, 540f, 60f)
        val result = layoutPage(
            page(above, figures = listOf(PdfRect(72f, 100f, 290f, 200f), PdfRect(310f, 100f, 540f, 200f))),
            10f,
        )

        assertEquals(1, pictures(result).size)
    }

    private fun gridOf(xs: List<Float>, ys: List<Float>): PdfGrid {
        val across = ys.map { PdfRule(horizontal = true, at = it, from = xs.first(), to = xs.last()) }
        val down = xs.map { PdfRule(horizontal = false, at = it, from = ys.first(), to = ys.last()) }
        return buildGrids(across, down, minFullRules = 2).single().grid
    }

    private fun PdfPageLayout.table() = pieces.filterIsInstance<TablePiece>().single().table

    private fun cellText(block: List<com.vivenotes.fileimport.docx.WordBlock>): String =
        block.filterIsInstance<WordParagraph>().joinToString("\n") { it.plainText() }

    @Test
    fun aTableTakesTheWordsInsideItsLines() {
        val grid = gridOf(listOf(72f, 200f, 300f), listOf(100f, 120f, 140f))
        val lines = listOf(line("Name", 80f, 115f), line("Age", 210f, 115f), line("Dustin", 80f, 135f), line("45", 210f, 135f))

        val table = layoutPage(page(lines, grids = listOf(grid)), 10f).table()

        assertEquals(listOf("Name", "Age"), table.rows[0].cells.map { cellText(it.blocks) })
        assertEquals(listOf("Dustin", "45"), table.rows[1].cells.map { cellText(it.blocks) })
    }

    @Test
    fun wordsOutsideATableAreLeftAsText() {
        val grid = gridOf(listOf(72f, 200f, 300f), listOf(100f, 120f, 140f))
        val lines = listOf(line("Caption over it", 72f, 90f), line("Name", 80f, 115f))

        assertEquals(listOf("Caption over it"), layoutPage(page(lines, grids = listOf(grid)), 10f).texts())
    }

    /** Ruled lines drawn inside a picture, with no text to read, are that picture's business. */
    @Test
    fun aPictureOfATableStaysAPicture() {
        val grid = gridOf(listOf(72f, 200f, 300f), listOf(100f, 120f, 140f))
        val result = layoutPage(page(emptyList(), grids = listOf(grid), figures = listOf(PdfRect(70f, 98f, 302f, 142f))), 10f)

        assertTrue(result.pieces.none { it is TablePiece })
        assertEquals(1, pictures(result).size)
    }

    private val orange = 0xFFFFC000.toInt()
    private val navy = 0xFF0B2545.toInt()

    private fun paintedTable(fills: List<Int>): PdfPageLayout {
        val grid = gridOf(listOf(72f, 200f, 300f), listOf(100f, 120f, 140f)).also { it.fills = fills.toIntArray() }
        val lines = listOf(line("Name", 80f, 115f), line("Age", 210f, 115f), line("Dustin", 80f, 135f), line("45", 210f, 135f))
        return layoutPage(page(lines, grids = listOf(grid)), 10f)
    }

    private fun firstRunOf(cell: com.vivenotes.fileimport.docx.WordCell): WordText =
        (cell.blocks.first() as WordParagraph).inlines.first() as WordText

    @Test
    fun aDarkFirstRowIsAHeader() {
        val table = paintedTable(listOf(navy, navy, NO_FILL, NO_FILL)).table()

        assertTrue(table.rows[0].isHeader)
        assertFalse(table.rows[1].isHeader)
    }

    @Test
    fun aPlainFirstRowIsNotAHeader() {
        assertFalse(paintedTable(listOf(NO_FILL, NO_FILL, NO_FILL, NO_FILL)).table().rows[0].isHeader)
    }

    /** The stored table has one fill, so a row the slide is pointing at would otherwise look like any other. */
    @Test
    fun aColouredCellKeepsItsColourAsAHighlight() {
        val table = paintedTable(listOf(navy, navy, orange, NO_FILL)).table()

        assertEquals(orange, firstRunOf(table.rows[1].cells[0]).style.highlightArgb)
        assertNull(firstRunOf(table.rows[1].cells[1]).style.highlightArgb)
    }

    /** Its letters are pale and are coming in black, which would be unreadable on it. */
    @Test
    fun aDarkCellKeepsNoHighlight() {
        val table = paintedTable(listOf(navy, navy, NO_FILL, NO_FILL)).table()

        assertNull(firstRunOf(table.rows[0].cells[0]).style.highlightArgb)
    }

    @Test
    fun tablesDoNotCountTowardsTheBodySize() {
        val grid = gridOf(listOf(72f, 286f, 500f), listOf(100f, 200f, 300f))
        val words = (0 until 10).map { line("tiny tiny tiny tiny", 80f, 110f + it * 12f, size = 6f) }
        val body = line("just this body line", 72f, 400f, size = 12f)

        assertEquals(12f, bodyEmOf(listOf(page(words + body, grids = listOf(grid)))), 0.01f)
    }

    @Test
    fun emptyPagesHaveNothingToPlace() {
        assertTrue(layout(emptyList()).pieces.isEmpty())
    }
}
