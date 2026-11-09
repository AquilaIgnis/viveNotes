package com.vivenotes.fileimport.pdf

import com.vivenotes.fileimport.pdf.PdfFixture.glyphs
import com.vivenotes.fileimport.pdf.PdfFixture.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextTest {
    @Test
    fun aGapWiderThanAWordSpaceDividesALine() {
        val row = glyphs("Name", 72f, 100f) + PdfGlyph(' ', null) + glyphs("Value", 200f, 100f)

        assertEquals(listOf("Name", "Value"), readLines(row).map { it.text })
    }

    @Test
    fun aLineEndStartsANewLine() {
        val text = glyphs("first", 72f, 100f) + PdfGlyph('\n', null) + glyphs("second", 72f, 112f)

        assertEquals(listOf("first", "second"), readLines(text).map { it.text })
    }

    /** Skia's output: a line end after every letter, nothing between the words but the distance. */
    @Test
    fun lettersWrittenOneToALineAreStillWordsOnALine() {
        fun spelled(word: String, left: Float): List<PdfGlyph> =
            glyphs(word, left, 100f).flatMap { listOf(it, PdfGlyph('\r', null), PdfGlyph('\n', null)) }

        val row = spelled("Hi", 72f) + spelled("there", 88f)

        assertEquals(listOf("Hi", "there"), readLines(row).single().words.map { it.text })
    }

    @Test
    fun aLineEndBetweenColumnsOnOneRowStaysALineEnd() {
        val row = glyphs("Dustin", 72f, 100f) + PdfGlyph('\n', null) + glyphs("45", 300f, 100f)

        assertEquals(listOf("Dustin", "45"), readLines(row).map { it.text })
    }

    /** PowerPoint sets some bullets so close to their text that no space is reported between them. */
    @Test
    fun aBulletTightAgainstItsTextIsItsOwnWord() {
        assertEquals(listOf("•", "Union"), line("•Union", 72f, 100f).words.map { it.text })
    }

    @Test
    fun aHyphenAtTheStartOfAWordStaysInIt() {
        assertEquals(listOf("-5"), line("-5", 72f, 100f).words.map { it.text })
    }

    /** A shadow, or bold faked by printing twice, is the same words at almost the same place. */
    @Test
    fun textDrawnTwiceComesOutOnce() {
        val row = glyphs("Title", 72f, 100f) + PdfGlyph(' ', null) + glyphs("Title", 72.5f, 100.5f)

        assertEquals(listOf("Title"), line(row))
    }

    private fun line(row: List<PdfGlyph>): List<String> = readLines(row).single().words.map { it.text }

    @Test
    fun theSameWordElsewhereOnTheLineIsKept() {
        assertEquals(listOf("very", "very"), line("very very", 72f, 100f).words.map { it.text })
    }

    @Test
    fun letterHeightsGiveTheFontSize() {
        assertEquals(14f, line("Heading", 72f, 100f, size = 14f).em, 0.01f)
    }

    @Test
    fun theLargestWordIsMeasuredOnItsOwn() {
        val row = glyphs("Example", 72f, 100f, size = 28f) + PdfGlyph(' ', null) + glyphs("of something small", 190f, 100f, size = 12f)

        assertEquals(28f, readLines(row).single().largestEm, 0.01f)
    }

    @Test
    fun evenStepsAreMonospacedAndUnevenOnesAreNot() {
        assertEquals(true, line("addi s0, zero, 512", 72f, 100f, mono = true).measuredMonospace)
        assertEquals(false, line("illustrative thumbnail wording", 72f, 100f).measuredMonospace)
    }

    @Test
    fun aLineTooShortToMeasureSaysNothing() {
        assertNull(line("j loop2", 72f, 100f, mono = true).measuredMonospace)
    }

    @Test
    fun aShortLineFollowsTheNearestLineThatCouldBeMeasured() {
        val code = line("addi s0, zero, 512", 72f, 100f, mono = true)
        val short = line("j loop2", 72f, 112f, mono = true)

        settleMonospace(listOf(code, short))

        assertTrue(short.monospace)
    }

    @Test
    fun aShortLineAmongProseIsNotCode() {
        val prose = line("illustrative thumbnail wording", 72f, 100f)
        val short = line("the end", 72f, 112f)

        settleMonospace(listOf(prose, short))

        assertFalse(short.monospace)
    }

    @Test
    fun aRaisedSmallLetterIsASuperscript() {
        val main = line("Energy equals", 72f, 100f)
        val raised = glyphs("2", 150f, 96f, size = 6f).single()

        assertEquals(PdfScript.Super, scriptOf(raised, main))
    }

    @Test
    fun aFullSizeLetterIsNotAScript() {
        val main = line("Energy equals", 72f, 100f)

        assertEquals(PdfScript.Baseline, scriptOf(main.words[0].located[0], main))
    }

    private fun footerPage(number: Int): Pair<List<Pair<String, PdfRect>>, Float> = listOf(
        "Chapter one" to PdfRect(72f, 30f, 200f, 40f),
        "Body text $number differs" to PdfRect(72f, 300f, 300f, 310f),
        "Draft copy $number" to PdfRect(72f, 760f, 200f, 770f),
    ) to 792f

    @Test
    fun whatRepeatsInTheMarginsIsFurniture() {
        val furniture = PdfFurniture.find(listOf(footerPage(1), footerPage(2), footerPage(3)))

        assertTrue(furniture.matches("Chapter one", PdfRect(72f, 30f, 200f, 40f), 792f))
        assertTrue("digits may differ", furniture.matches("Draft copy 9", PdfRect(72f, 760f, 200f, 770f), 792f))
    }

    @Test
    fun whatRepeatsInTheBodyIsNotFurniture() {
        val furniture = PdfFurniture.find(listOf(footerPage(1), footerPage(2), footerPage(3)))

        assertFalse(furniture.matches("Body text 4 differs", PdfRect(72f, 300f, 300f, 310f), 792f))
    }

    @Test
    fun aNumberAloneInTheMarginIsAPageNumber() {
        assertTrue(PdfFurniture(emptySet()).matches("12", PdfRect(300f, 760f, 310f, 770f), 792f))
    }

    @Test
    fun twoPagesAreTooFewToSayWhatRepeats() {
        val furniture = PdfFurniture.find(listOf(footerPage(1), footerPage(2)))

        assertFalse(furniture.matches("Chapter one", PdfRect(72f, 30f, 200f, 40f), 792f))
    }
}
