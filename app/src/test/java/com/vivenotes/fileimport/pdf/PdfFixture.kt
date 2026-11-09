package com.vivenotes.fileimport.pdf

import com.vivenotes.fileimport.docx.ParagraphPiece
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordText

/** Builds what the platform reports about a page, so the layout can be tested without a device. */
internal object PdfFixture {
    private const val TALL_LOWERCASE = "bdfhklt"
    private const val DESCENDING = "gjpqy,;()"

    private fun widthOf(char: Char): Float = when (char) {
        ' ' -> 0.25f
        in "ijlt.,;:'!" -> 0.28f
        in "frs()-" -> 0.36f
        in "mwMW" -> 0.82f
        in 'A'..'Z' -> 0.7f
        else -> 0.52f
    }

    /** The characters of [text] set on one [baseline], each with the box the platform would give it. */
    fun glyphs(text: String, left: Float, baseline: Float, size: Float = 10f, mono: Boolean = false): List<PdfGlyph> {
        var x = left
        return text.map { char ->
            val advance = size * (if (mono) 0.6f else widthOf(char))
            val glyph = if (char == ' ') {
                PdfGlyph(' ', null)
            } else {
                val tall = char.isUpperCase() || char.isDigit() || char in TALL_LOWERCASE
                val top = baseline - size * (if (tall) 0.72f else 0.5f)
                val bottom = baseline + (if (char in DESCENDING) size * 0.2f else 0f)
                PdfGlyph(char, PdfRect(x, top, x + advance, bottom))
            }
            x += advance
            glyph
        }
    }

    fun line(text: String, left: Float, baseline: Float, size: Float = 10f, mono: Boolean = false): PdfLine =
        readLines(glyphs(text, left, baseline, size, mono)).single()

    /** [text] set as a word processor would, each line taking every word that fits between the margins. */
    fun wrapped(
        text: String,
        left: Float,
        right: Float,
        baseline: Float,
        size: Float = 10f,
        leading: Float = size * 1.2f,
    ): List<PdfLine> {
        fun widthOfText(value: String): Float = value.sumOf { (widthOf(it) * size).toDouble() }.toFloat()
        val rows = ArrayList<String>()
        var row = ""
        for (word in text.split(" ")) {
            val candidate = if (row.isEmpty()) word else "$row $word"
            if (left + widthOfText(candidate) > right && row.isNotEmpty()) {
                rows += row
                row = word
            } else {
                row = candidate
            }
        }
        if (row.isNotEmpty()) rows += row
        return rows.mapIndexed { index, value -> line(value, left, baseline + leading * index, size) }
    }

    fun page(
        lines: List<PdfLine>,
        width: Float = 612f,
        height: Float = 792f,
        grids: List<PdfGrid> = emptyList(),
        figures: List<PdfRect> = emptyList(),
    ): PdfPageInput = PdfPageInput(width, height, lines, PdfInk(grids, figures)) { FIGURE_PART }

    fun PdfPageLayout.paragraphs(): List<WordParagraph> = pieces.filterIsInstance<ParagraphPiece>().map { it.paragraph }

    fun WordParagraph.plainText(): String = inlines.filterIsInstance<WordText>().joinToString("") { it.text }

    /** What the paragraphs of this page say, leaving out any that are only a picture. */
    fun PdfPageLayout.texts(): List<String> = paragraphs().map { it.plainText() }.filter { it.isNotEmpty() }

    const val FIGURE_PART = "figure"
}

/** A blank page of pixels at two to the point, to be painted on. */
internal class PdfTestRaster(widthPt: Int, heightPt: Int, private val scale: Int = 2) {
    private val width = widthPt * scale
    private val height = heightPt * scale
    private val pixels = IntArray(width * height) { WHITE }

    fun fill(box: PdfRect, color: Int) {
        val left = (box.left * scale).toInt().coerceIn(0, width)
        val right = (box.right * scale).toInt().coerceIn(0, width)
        val top = (box.top * scale).toInt().coerceIn(0, height)
        val bottom = (box.bottom * scale).toInt().coerceIn(0, height)
        for (y in top until bottom) pixels.fill(color, y * width + left, y * width + right)
    }

    fun raster(): PdfRaster = PdfRaster(pixels, width, height, scale.toFloat())

    companion object {
        const val WHITE = -1
        const val BLACK = 0xFF000000.toInt()
    }
}
