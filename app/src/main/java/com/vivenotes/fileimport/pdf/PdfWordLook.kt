package com.vivenotes.fileimport.pdf

import kotlin.math.abs
import kotlin.math.max

/**
 * Reads how words look off the pixels the page drew.
 *
 * The platform will say where a letter is and not what font it is in, so bold, colour and underline
 * come from looking at the page: bold is thicker strokes, colour is the colour of the ink, and an
 * underline is a line under them. Bold is a comparison with the other words on the page, because no
 * one number is "bold" across every typeface.
 */
internal fun readWordLooks(raster: PdfRaster, lines: List<PdfLine>) {
    val looks = lines.flatMap { line -> line.words.map { word -> Look(word, line, measure(raster, word, line)) } }
    markBold(looks)
}

private class Look(val word: PdfWord, val line: PdfLine, val stemPerEm: Float?)

private fun luminanceOf(pixel: Int): Int =
    (((pixel shr 16) and 0xFF) * 299 + ((pixel shr 8) and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000

/** Sets the word's colour and underline, and returns how thick its strokes are for its size. */
private fun measure(raster: PdfRaster, word: PdfWord, line: PdfLine): Float? {
    val scale = raster.pixelsPerPoint
    val width = raster.width
    val height = raster.height
    val box = word.box.expanded(1f)
    fun px(value: Float, limit: Int): Int = (value * scale).toInt().coerceIn(0, limit - 1)
    val left = px(box.left, width)
    val right = px(box.right, width)
    val top = px(box.top, height)
    val bottom = px(box.bottom, height)
    if (right <= left || bottom <= top) return null

    // The middle of what surrounds the word is its paper, even when the paper is dark.
    val edge = IntArray(8)
    edge[0] = raster.pixels[top * width + left]
    edge[1] = raster.pixels[top * width + right]
    edge[2] = raster.pixels[bottom * width + left]
    edge[3] = raster.pixels[bottom * width + right]
    edge[4] = raster.pixels[top * width + (left + right) / 2]
    edge[5] = raster.pixels[bottom * width + (left + right) / 2]
    edge[6] = raster.pixels[(top + bottom) / 2 * width + left]
    edge[7] = raster.pixels[(top + bottom) / 2 * width + right]
    val paper = edge.map(::luminanceOf).sorted()[4]

    var inkiest = 0
    for (y in top..bottom) {
        for (x in left..right) inkiest = max(inkiest, abs(luminanceOf(raster.pixels[y * width + x]) - paper))
    }
    // A recognised scan's invisible text has nothing under it to look at.
    if (inkiest < MIN_INK_CONTRAST) return null

    var red = 0L
    var green = 0L
    var blue = 0L
    var sampled = 0L
    for (glyph in word.located) {
        val glyphBox = glyph.box ?: continue
        val glyphLeft = px(glyphBox.left, width)
        val glyphRight = px(glyphBox.right, width)
        val glyphTop = px(glyphBox.top, height)
        val glyphBottom = px(glyphBox.bottom, height)
        for (y in glyphTop until glyphBottom) {
            for (x in glyphLeft until glyphRight) {
                val pixel = raster.pixels[y * width + x]
                if (abs(luminanceOf(pixel) - paper) * 10 >= inkiest * 7) {
                    red += (pixel shr 16) and 0xFF
                    green += (pixel shr 8) and 0xFF
                    blue += pixel and 0xFF
                    sampled++
                }
            }
        }
    }
    if (sampled > 0) {
        word.colorArgb = (0xFF shl 24) or ((red / sampled).toInt() shl 16) or ((green / sampled).toInt() shl 8) or (blue / sampled).toInt()
    }
    if (isUnderlined(raster, word, line, paper, inkiest)) word.underline = true
    return stemOf(raster, word, line, paper, inkiest)?.let { it / line.em }
}

/**
 * How thick the word's upright strokes are, in points: the typical run of ink along a row of pixels
 * through the middle of the letters.
 *
 * Counting a word's ink would need every word to have the same letters; "illicit" has far less than
 * "combed" in the same font. A stem is the same width in any letter that has one.
 */
private fun stemOf(raster: PdfRaster, word: PdfWord, line: PdfLine, paper: Int, inkiest: Int): Float? {
    val scale = raster.pixelsPerPoint
    val width = raster.width
    val top = ((line.baseline - line.em * STEM_BAND_TOP_EMS) * scale).toInt().coerceIn(0, raster.height - 1)
    val bottom = ((line.baseline - line.em * STEM_BAND_BOTTOM_EMS) * scale).toInt().coerceIn(0, raster.height - 1)
    val left = (word.box.left * scale).toInt().coerceIn(0, width - 1)
    val right = (word.box.right * scale).toInt().coerceIn(0, width - 1)
    val runs = ArrayList<Float>()
    for (y in top..bottom) {
        var run = 0f
        for (x in left..right) {
            val amount = abs(luminanceOf(raster.pixels[y * width + x]) - paper).toFloat() / inkiest
            if (amount >= STEM_EDGE) {
                run += amount
            } else if (run > 0f) {
                runs += run
                run = 0f
            }
        }
        if (run > 0f) runs += run
    }
    if (runs.size < MIN_STEM_RUNS) return null
    runs.sort()
    return runs[runs.size / 2] / scale
}

/** A run of ink just under the letters, nearly as wide as the word and not one of its descenders. */
private fun isUnderlined(raster: PdfRaster, word: PdfWord, line: PdfLine, paper: Int, inkiest: Int): Boolean {
    if (word.text.all { it in DASH_CHARACTERS }) return false
    val scale = raster.pixelsPerPoint
    val width = raster.width
    val top = ((line.baseline + UNDERLINE_FROM) * scale).toInt().coerceIn(0, raster.height - 1)
    val bottom = ((line.baseline + UNDERLINE_TO) * scale).toInt().coerceIn(0, raster.height - 1)
    val left = (word.box.left * scale).toInt().coerceIn(0, width - 1)
    val right = (word.box.right * scale).toInt().coerceIn(0, width - 1)
    val needed = ((right - left + 1) * UNDERLINE_COVER).toInt().coerceAtLeast(MIN_UNDERLINE_PX)
    for (y in top..bottom) {
        var run = 0
        var longest = 0
        for (x in left..right) {
            if (abs(luminanceOf(raster.pixels[y * width + x]) - paper) * 2 >= inkiest) {
                run++
                longest = max(longest, run)
            } else {
                run = 0
            }
        }
        if (longest >= needed) return true
    }
    return false
}

private fun markBold(looks: List<Look>) {
    // A monospaced face is drawn thinner than the prose around it, so each is judged against its own.
    for (group in looks.filter { it.stemPerEm != null }.groupBy { it.line.monospace }.values) {
        if (group.size < MIN_WORDS_TO_COMPARE) continue
        val regular = group.map { it.stemPerEm!! }.sorted()[group.size * 35 / 100]
        group.forEach { look -> look.word.bold = look.stemPerEm!! >= regular * BOLD_RATIO }
    }
    // A short word has too few letters to measure: it goes with its neighbours.
    val lines = looks.groupBy { it.line }
    for (line in lines.values) {
        line.forEachIndexed { index, look ->
            if (look.word.located.size >= MIN_LETTERS_TO_MEASURE) return@forEachIndexed
            val before = line.getOrNull(index - 1)?.word?.bold
            val after = line.getOrNull(index + 1)?.word?.bold
            look.word.bold = (before ?: after ?: false) && (after ?: before ?: false)
        }
    }
}

private const val MIN_INK_CONTRAST = 40
private const val UNDERLINE_FROM = 0.5f
private const val UNDERLINE_TO = 3.5f
private const val UNDERLINE_COVER = 0.8f
private const val MIN_UNDERLINE_PX = 8
private const val MIN_WORDS_TO_COMPARE = 6

/** Bold faces are drawn 1.5 to 1.7 times as thick as their regular cuts. */
private const val BOLD_RATIO = 1.3f

/** Between the top of the small letters and just above the baseline, clear of serifs and underlines. */
private const val STEM_BAND_TOP_EMS = 0.36f
private const val STEM_BAND_BOTTOM_EMS = 0.12f

/** Fainter than this, a pixel is the blur around a stroke rather than part of it. */
private const val STEM_EDGE = 0.15f
private const val MIN_STEM_RUNS = 4
private const val MIN_LETTERS_TO_MEASURE = 4
