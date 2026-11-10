package com.vivenotes.model.ocr

/**
 * A CTC reading of one strip: the text, and the output columns each of its characters came from.
 *
 * [firstColumn] and [lastColumn] hold one entry per UTF-16 unit of [text], so they line up with every
 * offset a search produces.
 */
class CtcReading internal constructor(
    val text: String,
    val confidence: Float,
    private val firstColumn: IntArray,
    private val lastColumn: IntArray,
) {

    /**
     * Where the characters of `text.trim()` begin and end along the strip, as fractions of its width.
     *
     * One more value than there are characters: a match from `start` to `end` runs from
     * `edges[start]` to `edges[end]`. Empty when the text is all whitespace.
     *
     * A character's columns cover only the middle of its glyph, so a boundary between two characters
     * is halfway across the blank between their runs. The two outer edges are the strip's own ends,
     * because the detector's box already wraps the line.
     *
     * Those midpoints sit a little inside the ink, so a boundary beside a space is pushed into the
     * space by [WORD_EDGE_PADDING] line heights, never past the middle of the space. Boundaries
     * between two letters stay put, so a match that stops part way through a word still stops where
     * it should. Measured in `simulations/image-ocr/word_boxes.py`: a mean word overlap of 0.92,
     * where dividing the box evenly by character count gives 0.81 and can miss a word entirely.
     *
     * [columnShare] is how much of the strip one output column covers, and [lineShare] how much of
     * it the strip's own height is, which is the unit the padding is in.
     */
    fun edges(columnShare: Float, lineShare: Float): FloatArray {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return FloatArray(0)
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        val count = end - start
        val edges = FloatArray(count + 1)
        for (index in 1 until count) {
            val gapStart = lastColumn[start + index - 1] + 1
            val gapEnd = firstColumn[start + index]
            edges[index] = ((gapStart + gapEnd) * 0.5f * columnShare).coerceIn(0f, 1f)
        }
        edges[count] = 1f

        val padding = WORD_EDGE_PADDING * lineShare
        for (index in 1 until count) {
            val column = start + index
            if (!text[column].isWhitespace()) continue
            val middle = ((firstColumn[column] + lastColumn[column] + 1) * 0.5f * columnShare).coerceIn(0f, 1f)
            edges[index] = maxOf(edges[index], minOf(edges[index] + padding, middle))
            edges[index + 1] = minOf(edges[index + 1], maxOf(edges[index + 1] - padding, middle))
        }
        return edges
    }

    private companion object {
        /**
         * Line heights. From the sweep in `simulations/image-ocr/word_boxes.py`: at 0.15, 91% of words
         * are wholly inside their box where 14% are with none, and 1.7% of boxes reach the next word.
         */
        const val WORD_EDGE_PADDING = 0.15f
    }
}

/** Greedy CTC decoding. Android-free, like the rest of `model/`, so it runs on the JVM. */
object CtcDecoder {

    /**
     * Collapses [logits], one row per output column, into text.
     *
     * [characters] maps a class index to what it spells; index zero is the CTC blank. A symbol is
     * emitted when it differs from the previous column's, so a character is read once however many
     * columns it spans, and a repeated letter needs a blank between its two runs.
     */
    fun decode(logits: Array<FloatArray>, characters: List<String>): CtcReading {
        val text = StringBuilder()
        var first = IntArray(logits.size.coerceAtLeast(1))
        var last = IntArray(first.size)
        var previous = 0
        var runOpen = false
        var confidence = 0f
        var charactersRead = 0
        logits.forEachIndexed { column, row ->
            var bestIndex = 0
            var bestScore = Float.NEGATIVE_INFINITY
            row.forEachIndexed { index, score ->
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }
            when {
                bestIndex == 0 || bestIndex >= characters.size -> runOpen = false
                bestIndex != previous -> {
                    val symbol = characters[bestIndex]
                    if (text.length + symbol.length > first.size) {
                        first = first.copyOf((text.length + symbol.length) * 2)
                        last = last.copyOf(first.size)
                    }
                    for (unit in text.length until text.length + symbol.length) {
                        first[unit] = column
                        last[unit] = column
                    }
                    text.append(symbol)
                    confidence += bestScore
                    charactersRead++
                    runOpen = true
                }
                runOpen -> {
                    // The same symbol again, so the run grows. Every unit of a multi-unit symbol does.
                    val units = characters[bestIndex].length
                    for (unit in text.length - units until text.length) last[unit] = column
                }
            }
            previous = bestIndex
        }
        return CtcReading(
            text = text.toString(),
            confidence = if (charactersRead == 0) 0f else confidence / charactersRead,
            firstColumn = first,
            lastColumn = last,
        )
    }
}
