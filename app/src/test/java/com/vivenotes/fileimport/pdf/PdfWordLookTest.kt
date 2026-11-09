package com.vivenotes.fileimport.pdf

import com.vivenotes.fileimport.pdf.PdfFixture.line
import com.vivenotes.fileimport.pdf.PdfTestRaster.Companion.BLACK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfWordLookTest {
    private val red = 0xFFD00000.toInt()

    /** Draws each letter as an upright stroke [stem] points wide, the way type looks to the measure. */
    private fun PdfTestRaster.draw(word: PdfWord, stem: Float, color: Int = BLACK) {
        for (glyph in word.located) {
            val box = glyph.box!!
            fill(PdfRect(box.left + 1f, box.top, box.left + 1f + stem, box.bottom), color)
        }
    }

    private fun sentence(): PdfLine = line("these several plain words surround another single example thing", 40f, 100f, size = 14f)

    @Test
    fun thickerStrokesThanTheRestOfThePageAreBold() {
        val page = PdfTestRaster(700, 200)
        val text = sentence()
        text.words.forEachIndexed { index, word -> page.draw(word, stem = if (index == 2) 2f else 1f) }

        readWordLooks(page.raster(), listOf(text))

        assertEquals(listOf("plain"), text.words.filter { it.bold }.map { it.text })
    }

    @Test
    fun aPageOfOneWeightHasNothingBold() {
        val page = PdfTestRaster(700, 200)
        val text = sentence()
        text.words.forEach { page.draw(it, stem = 1f) }

        readWordLooks(page.raster(), listOf(text))

        assertTrue(text.words.none { it.bold })
    }

    /** The narrow letters of one word and the wide ones of the next have the same strokes. */
    @Test
    fun whichLettersAWordHasDoesNotMakeItBold() {
        val page = PdfTestRaster(700, 200)
        val text = line("illicit minimum million willing mammoth illness woman mummy", 40f, 100f, size = 14f)
        text.words.forEach { page.draw(it, stem = 1f) }

        readWordLooks(page.raster(), listOf(text))

        assertTrue(text.words.none { it.bold })
    }

    @Test
    fun theWordsColourComesFromItsInk() {
        val page = PdfTestRaster(300, 200)
        val text = line("coloured", 40f, 100f, size = 14f)
        page.draw(text.words.single(), stem = 2f, red)

        readWordLooks(page.raster(), listOf(text))

        assertEquals(red, text.words.single().colorArgb)
    }

    @Test
    fun aLineUnderAWordIsAnUnderline() {
        val page = PdfTestRaster(300, 200)
        val text = line("noted", 40f, 100f, size = 14f)
        val word = text.words.single()
        page.draw(word, stem = 2f)
        page.fill(PdfRect(word.box.left, text.baseline + 1.5f, word.box.right, text.baseline + 2.5f), BLACK)

        readWordLooks(page.raster(), listOf(text))

        assertTrue(word.underline)
    }

    @Test
    fun noLineUnderAWordIsNoUnderline() {
        val page = PdfTestRaster(300, 200)
        val text = line("plain", 40f, 100f, size = 14f)
        page.draw(text.words.single(), stem = 2f)

        readWordLooks(page.raster(), listOf(text))

        assertFalse(text.words.single().underline)
    }

    /** A recognised scan's text layer lies over a picture of itself, or over nothing at all. */
    @Test
    fun wordsWithNoInkAreLeftAlone() {
        val page = PdfTestRaster(300, 200)
        val text = line("invisible", 40f, 100f, size = 14f)

        readWordLooks(page.raster(), listOf(text))

        assertEquals(null, text.words.single().colorArgb)
        assertFalse(text.words.single().bold)
    }
}
