package com.vivenotes.model.ocr

import com.vivenotes.model.search.MatchSpan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PictureHighlightsTest {

    /** A straight line from x 0.1 to 0.9 and y 0.2 to 0.3, its characters evenly spread. */
    private fun straightLine(text: String) = ImageLineLayout(
        corners = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.3f, 0.1f, 0.3f),
        edges = FloatArray(text.length + 1) { it.toFloat() / text.length },
    )

    @Test
    fun aMatchIsBoxedFromItsFirstEdgeToItsLast() {
        val line = "ab cd"

        val quads = PictureHighlights.forSpans(line, straightLine(line), listOf(MatchSpan(3, 5)))!!.quads

        assertArrayEquals(
            floatArrayOf(0.58f, 0.2f, 0.9f, 0.2f, 0.9f, 0.3f, 0.58f, 0.3f),
            quads,
            0.0001f,
        )
    }

    @Test
    fun aRotatedLineGivesARotatedBox() {
        val tilted = ImageLineLayout(
            corners = floatArrayOf(0f, 0.5f, 0.8f, 0.1f, 0.85f, 0.2f, 0.05f, 0.6f),
            edges = floatArrayOf(0f, 0.5f, 1f),
        )

        val quads = PictureHighlights.forSpans("hi", tilted, listOf(MatchSpan(1, 2)))!!.quads

        assertArrayEquals(
            floatArrayOf(0.4f, 0.3f, 0.8f, 0.1f, 0.85f, 0.2f, 0.45f, 0.4f),
            quads,
            0.0001f,
        )
    }

    @Test
    fun aBoxReportsWhereAScrollShouldAim() {
        val line = "ab cd"

        val highlight = PictureHighlights.forSpans(line, straightLine(line), listOf(MatchSpan(0, 2)))!!

        assertEquals(1, highlight.count)
        assertEquals(0.1f, highlight.left, 0.0001f)
        assertEquals(0.2f, highlight.top, 0.0001f)
    }

    @Test
    fun aSpanPastTheEndIsClampedToTheLine() {
        val line = "ab"

        val quads = PictureHighlights.forSpans(line, straightLine(line), listOf(MatchSpan(1, 9)))!!.quads

        assertEquals(0.9f, quads[2], 0.0001f)
    }

    @Test
    fun aSpanWithNoWidthDrawsNothing() {
        val line = "ab"

        assertNull(PictureHighlights.forSpans(line, straightLine(line), listOf(MatchSpan(1, 1))))
    }

    @Test
    fun onlyTheLinesThatMatchAreBoxed() {
        val lines = listOf("nothing here", "invoice total", "still nothing")
        val layouts = lines.map(::straightLine)

        val highlight = PictureHighlights.forQuery("invoice", lines, layouts)!!

        assertEquals(1, highlight.count)
        assertEquals(0.1f, highlight.quads[0], 0.0001f)
    }

    @Test
    fun aWordShownThreeTimesGetsThreeBoxes() {
        val lines = listOf("cat dog cat", "cat")

        val highlight = PictureHighlights.forQuery("cat", lines, lines.map(::straightLine))!!

        assertEquals(3, highlight.count)
    }

    @Test
    fun everyTermOfAQueryHasToBeOnTheLine() {
        val lines = listOf("red fox", "red hen")

        val highlight = PictureHighlights.forQuery("red fox", lines, lines.map(::straightLine))!!

        assertEquals("only the first line has both, and each term is its own box", 2, highlight.count)
    }

    @Test
    fun nothingMatchingIsNoHighlightAtAll() {
        val lines = listOf("hello")

        assertNull(PictureHighlights.forQuery("zebra", lines, lines.map(::straightLine)))
    }

    @Test
    fun theBoxesPerPictureAreCapped() {
        val lines = List(50) { "e e e e e e e e e e" }

        val highlight = PictureHighlights.forQuery("e", lines, lines.map(::straightLine), limit = 40)!!

        assertEquals(40, highlight.count)
    }

    @Test
    fun aLayoutWithTheWrongNumberOfLinesIsIgnored() {
        assertNull(PictureHighlights.forQuery("a", listOf("a", "a"), listOf(straightLine("a"))))
    }

    @Test
    fun aLineWhoseEdgesDoNotFitItsTextIsSkipped() {
        val lines = listOf("cat", "cat")
        val layouts = listOf(straightLine("catalog"), straightLine("cat"))

        val highlight = PictureHighlights.forQuery("cat", lines, layouts)!!

        assertEquals(1, highlight.count)
    }
}
