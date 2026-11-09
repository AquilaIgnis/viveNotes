package com.vivenotes.fileimport.pdf

import kotlin.math.abs
import kotlin.math.max

/** One character of a page's text, and where it is; whitespace and line ends have no box. */
internal class PdfGlyph(val char: Char, val box: PdfRect?)

internal enum class PdfScript { Baseline, Super, Sub }

/** A word: the characters between two spaces, and what the page's pixels say it looks like. */
internal class PdfWord(val glyphs: List<PdfGlyph>) {
    val located: List<PdfGlyph> = glyphs.filter { it.box != null }
    val box: PdfRect = located.map { it.box!! }.reduce(PdfRect::union)
    val text: String = buildString { glyphs.forEach { append(it.char) } }

    var bold: Boolean = false
    var underline: Boolean = false
    var colorArgb: Int? = null
    var link: String? = null
}

/**
 * Words running along one line with only word spaces between them.
 *
 * A table row or a tab stop leaves wider gaps than that, so those come out as several of these — and
 * two side-by-side columns do, whatever order the file wrote them in.
 */
internal class PdfLine(val words: List<PdfWord>) {
    val box: PdfRect = words.map { it.box }.reduce(PdfRect::union)
    val text: String get() = words.joinToString(" ") { it.text }

    /** The font size this line appears to be set in, in points. */
    val em: Float = estimateEm(words)

    /** The biggest any word on the line is set: a title and a note can share one. */
    val largestEm: Float = words
        .filter { word -> word.located.count { it.char.isUpperCase() || it.char.isDigit() || it.char in CAP_LIKE_LOWERCASE || it.char in X_LIKE } >= MIN_GLYPHS_FOR_WORD_EM }
        .maxOfOrNull { estimateEm(listOf(it)) } ?: em

    /** Where the letters sit, which is the bottom of everything that has no descender. */
    val baseline: Float = estimateBaseline(words, box)

    /** Whether every letter is the same width; null with too few letters to tell. */
    val measuredMonospace: Boolean? = estimateMonospace(words)

    /** Code, and worth keeping as code. See [settleMonospace] for the lines too short to measure. */
    var monospace: Boolean = measuredMonospace == true

    /** Set up the page rather than along it, like the arXiv stamp down a paper's margin. */
    val rotated: Boolean = box.height > box.width * ROTATED_MIN_RATIO && words.sumOf { it.located.size } >= ROTATED_MIN_GLYPHS
}

private const val ROTATED_MIN_RATIO = 3f
private const val ROTATED_MIN_GLYPHS = 6

private const val MIN_GLYPHS_FOR_WORD_EM = 3
private const val CAP_HEIGHT_RATIO = 0.72f
private const val X_HEIGHT_RATIO = 0.5f
private const val CAP_LIKE_LOWERCASE = "bdfhklt"
private const val X_LIKE = "acemnorsuvwxz"
private const val DESCENDERS = "gjpqy,;()[]{}/\\|@Q$"

private fun medianOf(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}

/** An average and not a median: positions come in whole points, and only an average sees past that. */
private fun estimateEm(words: List<PdfWord>): Float {
    val glyphs = words.flatMap { it.located }
    val capHeights = glyphs
        .filter { it.char.isUpperCase() || it.char.isDigit() || it.char in CAP_LIKE_LOWERCASE }
        .map { it.box!!.height }
    if (capHeights.isNotEmpty()) return capHeights.average().toFloat() / CAP_HEIGHT_RATIO
    val xHeights = glyphs.filter { it.char in X_LIKE }.map { it.box!!.height }
    if (xHeights.isNotEmpty()) return xHeights.average().toFloat() / X_HEIGHT_RATIO
    return words.maxOf { it.box.height }
}

private fun estimateBaseline(words: List<PdfWord>, box: PdfRect): Float =
    medianOf(words.flatMap { it.located }.filter { it.char !in DESCENDERS }.map { it.box!!.bottom }) ?: box.bottom

/**
 * Whether advances are all alike. Measured between letters' centres, because the boxes hug the ink
 * and in a monospaced font an `i` is as narrow as anywhere else; it is the step that is the same.
 */
private fun estimateMonospace(words: List<PdfWord>): Boolean? {
    val steps = words.flatMap { word ->
        word.located.zipWithNext { a, b -> b.box!!.centerX - a.box!!.centerX }
    }.filter { it > 0f }.sorted()
    if (steps.size < MIN_MONOSPACE_STEPS) return null
    val spread = steps[steps.size * 9 / 10] - steps[steps.size / 10]
    return spread <= MONOSPACE_STEP_SPREAD && spread <= steps[steps.size / 2] * MONOSPACE_RELATIVE_SPREAD
}

/** A line too short to measure, like `j loop2`, is code if the nearest line that could be measured is. */
internal fun settleMonospace(lines: List<PdfLine>) {
    val measured = lines.filter { it.measuredMonospace != null }
    for (line in lines) {
        if (line.measuredMonospace != null) continue
        val nearest = measured.filter { other ->
            val across = max(other.box.left - line.box.right, line.box.left - other.box.right)
            abs(other.box.centerY - line.box.centerY) <= line.em * SETTLE_REACH_DOWN_EMS && across <= line.em * SETTLE_REACH_ACROSS_EMS
        }.minByOrNull { abs(it.box.centerY - line.box.centerY) }
        line.monospace = nearest?.measuredMonospace == true
    }
}

private const val MIN_MONOSPACE_STEPS = 8
private const val SETTLE_REACH_DOWN_EMS = 2.5f
private const val SETTLE_REACH_ACROSS_EMS = 8f

/** Positions come in whole points, so a real font's identical steps can differ by one or so. */
private const val MONOSPACE_STEP_SPREAD = 1f

/** Small proportional type is the same few points apart too, but not the same share of its step. */
private const val MONOSPACE_RELATIVE_SPREAD = 0.2f

/** A gap this many times the font size is not a word space. */
private const val SEGMENT_GAP_EMS = 1.15f
private const val MIN_SEGMENT_GAP = 10f

/**
 * Splits a page's characters into lines, then breaks each line wherever it has a gap too wide to be a
 * word space. [isFurniture] takes a whole line and says whether to leave it out.
 */
internal fun readLines(
    glyphs: List<PdfGlyph>,
    isFurniture: (String, PdfRect) -> Boolean = { _, _ -> false },
): List<PdfLine> {
    val out = ArrayList<PdfLine>()
    val joined = withoutFalseLineEnds(glyphs)
    var start = 0
    for (index in 0..joined.size) {
        val isEnd = index == joined.size || isLineEnd(joined[index].char)
        if (!isEnd) continue
        if (index > start) addLine(joined.subList(start, index), isFurniture, out)
        start = index + 1
    }
    return out
}

private fun isLineEnd(char: Char): Boolean = char == '\n' || char == '\r'

/**
 * Puts back together lines a producer wrote a letter at a time.
 *
 * Skia draws every glyph as its own object, so anything printed from Chrome or saved by Android comes
 * out with a line end after every letter and no spaces at all. Where two letters are is the only thing
 * that can say whether a line really ended there, or a word did.
 */
private fun withoutFalseLineEnds(glyphs: List<PdfGlyph>): List<PdfGlyph> {
    val out = ArrayList<PdfGlyph>(glyphs.size)
    val recentHeights = ArrayDeque<Float>()
    var last: PdfRect? = null
    var index = 0
    while (index < glyphs.size) {
        val glyph = glyphs[index]
        if (!isLineEnd(glyph.char)) {
            out += glyph
            glyph.box?.let { box ->
                last = box
                recentHeights.addLast(box.height)
                if (recentHeights.size > RECENT_LETTERS) recentHeights.removeFirst()
            }
            index++
            continue
        }
        var end = index
        while (end < glyphs.size && isLineEnd(glyphs[end].char)) end++
        val before = last
        val after = glyphs.getOrNull(end)?.box
        // The tallest of the last few letters, because a row of small letters would make any gap look wide.
        val em = max(recentHeights.maxOrNull() ?: 0f, after?.height ?: 0f) / CAP_HEIGHT_RATIO
        when {
            before == null || after == null || !continuesLine(before, after, em) -> out.addAll(glyphs.subList(index, end))
            after.left - before.right >= em * WORD_GAP_EMS -> out += PdfGlyph(' ', null)
        }
        index = end
    }
    return out
}

/** Level with the last letter and just after it, as the next letter of a line would be. */
private fun continuesLine(before: PdfRect, after: PdfRect, em: Float): Boolean {
    val level = minOf(before.bottom, after.bottom) - maxOf(before.top, after.top) >= minOf(before.height, after.height) * SAME_LINE_OVERLAP
    val gap = after.left - before.right
    return level && gap >= -NEGATIVE_GAP_TOLERANCE && gap <= max(em * SEGMENT_GAP_EMS, MIN_SEGMENT_GAP)
}

private const val RECENT_LETTERS = 8
private const val SAME_LINE_OVERLAP = 0.5f
private const val NEGATIVE_GAP_TOLERANCE = 2f

/** Letters' boxes hug their ink and are rounded to whole points, which puts a word space near a fifth of the font size. */
private const val WORD_GAP_EMS = 0.2f

/** A page's lines as the file wrote them, with no gaps broken and nothing left out. */
internal fun wholeLines(glyphs: List<PdfGlyph>): List<Pair<String, PdfRect>> {
    val out = ArrayList<Pair<String, PdfRect>>()
    readLines(glyphs) { text, box ->
        out += text to box
        true
    }
    return out
}

private fun addLine(
    glyphs: List<PdfGlyph>,
    isFurniture: (String, PdfRect) -> Boolean,
    out: MutableList<PdfLine>,
) {
    val words = wordsOf(glyphs)
    if (words.isEmpty()) return
    val whole = PdfLine(words)
    if (isFurniture(whole.text, whole.box)) return
    val limit = max(whole.em * SEGMENT_GAP_EMS, MIN_SEGMENT_GAP)
    var current = ArrayList<PdfWord>()
    for (word in words) {
        val last = current.lastOrNull()
        if (last != null && word.box.left - last.box.right > limit) {
            out += PdfLine(current)
            current = ArrayList()
        }
        current += word
    }
    out += PdfLine(current)
}

private fun wordsOf(glyphs: List<PdfGlyph>): List<PdfWord> {
    val words = ArrayList<PdfWord>()
    var current = ArrayList<PdfGlyph>()
    fun finish() {
        if (current.any { it.box != null }) {
            val word = PdfWord(current)
            // A shadow or a faked bold is the same text drawn twice, and the file says it twice too.
            val drawnAgain = words.any { it.text == word.text && abs(it.box.left - word.box.left) <= SAME_PLACE && abs(it.box.top - word.box.top) <= SAME_PLACE }
            if (!drawnAgain) words += word
        }
        current = ArrayList()
    }
    for (glyph in glyphs) {
        if (glyph.char.isWhitespace() || glyph.char == ' ') {
            finish()
        } else if (!glyph.char.isISOControl() || glyph.char == SOFT_HYPHEN_MARK) {
            current += glyph
            // A slide's bullet can sit tight against its text, and is still no part of the word.
            if (current.size == 1 && isBulletGlyph(glyph.char)) finish()
        }
    }
    finish()
    return words
}

private const val SAME_PLACE = 2f

private val BULLET_GLYPHS = setOf('•', '·', '▪', '▫', '◦', '‣', '■', '□', '●', '○', '◆', '➢', '✓', '⁃')

/** A bullet drawn from a symbol font arrives as a private-use character, whichever one that font picked. */
internal fun isBulletGlyph(char: Char): Boolean = char in BULLET_GLYPHS || char in ''..''

/** What Pdfium writes for a hyphen that exists only because the word ran out of line. */
internal const val SOFT_HYPHEN_MARK = '\u0002'

/**
 * Whether the glyph sits off its line like a superscript or subscript, going by its height and where
 * it stops. A math superscript is small and raised; a plain comma is small and is not.
 */
internal fun scriptOf(glyph: PdfGlyph, line: PdfLine): PdfScript {
    val box = glyph.box ?: return PdfScript.Baseline
    val char = glyph.char
    if (!char.isLetterOrDigit()) return PdfScript.Baseline
    val full = line.em * (if (char.isUpperCase() || char.isDigit() || char in CAP_LIKE_LOWERCASE) CAP_HEIGHT_RATIO else X_HEIGHT_RATIO)
    if (box.height > full * SCRIPT_MAX_HEIGHT) return PdfScript.Baseline
    return when {
        line.baseline - box.bottom >= line.em * SCRIPT_MIN_SHIFT -> PdfScript.Super
        box.bottom - line.baseline >= line.em * SCRIPT_MIN_SHIFT * 0.5f -> PdfScript.Sub
        else -> PdfScript.Baseline
    }
}

private const val SCRIPT_MAX_HEIGHT = 0.82f
private const val SCRIPT_MIN_SHIFT = 0.22f

/** Page furniture: text that repeats down the pages in the margins. */
internal class PdfFurniture(private val signatures: Set<String>) {
    fun matches(text: String, box: PdfRect, pageHeight: Float): Boolean {
        if (!inMargin(box, pageHeight)) return false
        return isPageNumber(text) || signature(text, box) in signatures
    }

    companion object {
        private const val MARGIN_SHARE = 0.11f
        private const val POSITION_BUCKET = 8f

        private fun inMargin(box: PdfRect, pageHeight: Float): Boolean =
            box.centerY < pageHeight * MARGIN_SHARE || box.centerY > pageHeight * (1f - MARGIN_SHARE)

        private fun isPageNumber(text: String): Boolean =
            Regex("""^\s*(page\s+)?\d{1,4}(\s*(/|of)\s*\d{1,4})?\s*$""", RegexOption.IGNORE_CASE).matches(text)

        /** Digits don't count, so that "Page 3" on one sheet is "Page 7" on another. */
        private fun signature(text: String, box: PdfRect): String =
            text.trim().lowercase().replace(Regex("""\d+"""), "#") + "@" + (box.centerY / POSITION_BUCKET).toInt()

        /** What repeats on at least half the [pages], each given as its lines and that page's height. */
        fun find(pages: List<Pair<List<Pair<String, PdfRect>>, Float>>): PdfFurniture {
            if (pages.size < MIN_PAGES) return PdfFurniture(emptySet())
            val counts = HashMap<String, Int>()
            for ((lines, height) in pages) {
                lines.filter { (_, box) -> inMargin(box, height) }
                    .map { (text, box) -> signature(text, box) }
                    .toSet()
                    .forEach { counts[it] = (counts[it] ?: 0) + 1 }
            }
            val needed = max(MIN_PAGES, (pages.size + 1) / 2)
            return PdfFurniture(counts.filterValues { it >= needed }.keys)
        }

        private const val MIN_PAGES = 3
    }
}

/** Whether two ranges along a line overlap by at least half the shorter. */
internal fun overlapsMostly(aFrom: Float, aTo: Float, bFrom: Float, bTo: Float): Boolean {
    val shared = minOf(aTo, bTo) - maxOf(aFrom, bFrom)
    return shared >= minOf(aTo - aFrom, bTo - bFrom) * 0.5f
}

