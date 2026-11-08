package com.vivenotes.model.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CtcDecoderTest {

    private val characters = listOf("", "a", "b", " ", "é́")

    /** One output row per value, with that class on top at [score]. */
    private fun logits(vararg classes: Int, score: Float = 0.9f): Array<FloatArray> =
        Array(classes.size) { column ->
            FloatArray(characters.size + 1) { index -> if (index == classes[column]) score else 0.01f }
        }

    @Test
    fun aCharacterHeldOverSeveralColumnsIsReadOnce() {
        assertEquals("ab", CtcDecoder.decode(logits(1, 1, 1, 2, 2), characters).text)
    }

    @Test
    fun aRepeatedLetterNeedsABlankBetweenItsTwoRuns() {
        assertEquals("a", CtcDecoder.decode(logits(1, 1), characters).text)
        assertEquals("aa", CtcDecoder.decode(logits(1, 0, 1), characters).text)
    }

    @Test
    fun aClassBeyondTheAlphabetIsSkippedAndBreaksTheRun() {
        val reading = CtcDecoder.decode(logits(1, 5, 1), characters)
        assertEquals("aa", reading.text)
    }

    @Test
    fun confidenceIsTheMeanOfEachCharactersFirstColumn() {
        val rows = logits(1, 1, 2)
        rows[1][1] = 0.1f
        rows[2][2] = 0.5f
        assertEquals(0.7f, CtcDecoder.decode(rows, characters).confidence, 0.0001f)
    }

    @Test
    fun nothingReadIsEmptyAtZeroConfidence() {
        val reading = CtcDecoder.decode(logits(0, 0, 0), characters)
        assertEquals("", reading.text)
        assertEquals(0f, reading.confidence, 0f)
        assertEquals(0, reading.edges(0.1f, 0.1f).size)
    }

    @Test
    fun aBoundaryIsHalfwayAcrossTheBlankBetweenTwoRuns() {
        // "a" holds columns 1-2, "b" holds column 5, so the blank is columns 3 and 4 and its middle
        // is the start of column 4. Ten columns make one column a tenth of the strip.
        val reading = CtcDecoder.decode(logits(0, 1, 1, 0, 0, 2, 0, 0, 0, 0), characters)

        assertEquals("ab", reading.text)
        assertArrayEquals(floatArrayOf(0f, 0.4f, 1f), reading.edges(0.1f, 0.1f), 0.0001f)
    }

    @Test
    fun theOuterEdgesAreTheLinesOwnEnds() {
        val edges = CtcDecoder.decode(logits(0, 0, 1, 0, 2, 0, 0, 0), characters).edges(0.125f, 0.125f)

        assertEquals(0f, edges.first(), 0f)
        assertEquals(1f, edges.last(), 0f)
    }

    @Test
    fun edgesAreForTheTrimmedTextBecauseThatIsWhatIsStored() {
        // " ab " reads the space in columns 0 and 6.
        val reading = CtcDecoder.decode(logits(3, 0, 1, 0, 2, 0, 3, 0), characters)

        assertEquals(" ab ", reading.text)
        val edges = reading.edges(0.125f, 0.125f)
        assertEquals("one more edge than characters of the trimmed text", 3, edges.size)
        assertEquals(0f, edges[0], 0f)
        assertEquals(0.4375f, edges[1], 0.0001f)
        assertEquals(1f, edges[2], 0f)
    }

    @Test
    fun aWordsEdgesReachIntoTheSpaceBesideIt() {
        // "a b": the letter ends at column 1, the space is columns 4-5, the next letter starts at 8.
        val reading = CtcDecoder.decode(logits(1, 1, 0, 0, 3, 3, 0, 0, 2, 0), characters)
        assertEquals("a b", reading.text)

        assertArrayEquals("no padding, the midpoints", floatArrayOf(0f, 0.3f, 0.7f, 1f), reading.edges(0.1f, 0f), 0.0001f)
        // 0.15 of a line height, and here the line height is the whole strip.
        assertArrayEquals("into the space", floatArrayOf(0f, 0.45f, 0.55f, 1f), reading.edges(0.1f, 1f), 0.0001f)
        assertArrayEquals("but never past its middle", floatArrayOf(0f, 0.5f, 0.5f, 1f), reading.edges(0.1f, 100f), 0.0001f)
    }

    @Test
    fun aBoundaryBetweenTwoLettersDoesNotMove() {
        val reading = CtcDecoder.decode(logits(1, 0, 0, 2, 0), characters)

        assertArrayEquals(reading.edges(0.2f, 0f), reading.edges(0.2f, 1f), 0f)
    }

    @Test
    fun edgesNeverLeaveTheStripOrRunBackwards() {
        // A column share this big only happens if padding was miscounted; the box must still hold.
        val edges = CtcDecoder.decode(logits(1, 0, 2, 0, 1, 0, 2), characters).edges(0.5f, 0.5f)

        edges.forEach { assert(it in 0f..1f) { "edge $it left the strip" } }
        edges.toList().zipWithNext().forEach { (a, b) -> assert(a <= b) { "$a then $b runs backwards" } }
    }

    @Test
    fun aSymbolOfSeveralUnitsHasAnEntryForEachSoOffsetsStillLineUp() {
        val reading = CtcDecoder.decode(logits(1, 0, 4, 4, 0, 2), characters)

        assertEquals("aé́b", reading.text)
        assertEquals("one edge per UTF-16 unit, plus one", 5, reading.edges(1f / 6f, 1f / 6f).size)
    }
}
