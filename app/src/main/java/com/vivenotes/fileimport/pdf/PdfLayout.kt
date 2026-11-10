package com.vivenotes.fileimport.pdf

import com.vivenotes.fileimport.docx.PagePiece
import com.vivenotes.fileimport.docx.ParagraphPiece
import com.vivenotes.fileimport.docx.SeparatorPiece
import com.vivenotes.fileimport.docx.TablePiece
import com.vivenotes.fileimport.docx.WordAlign
import com.vivenotes.fileimport.docx.WordBlock
import com.vivenotes.fileimport.docx.WordCell
import com.vivenotes.fileimport.docx.WordCellAlign
import com.vivenotes.fileimport.docx.WordInline
import com.vivenotes.fileimport.docx.WordListLabel
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordParagraphStyle
import com.vivenotes.fileimport.docx.WordPicture
import com.vivenotes.fileimport.docx.WordRow
import com.vivenotes.fileimport.docx.WordRunStyle
import com.vivenotes.fileimport.docx.WordScript
import com.vivenotes.fileimport.docx.WordTab
import com.vivenotes.fileimport.docx.WordTable
import com.vivenotes.fileimport.docx.WordText
import com.vivenotes.fileimport.docx.WordVerticalMerge
import com.vivenotes.richtext.isChromatic
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What is known about one page before it is put in reading order. */
internal class PdfPageInput(
    val width: Float,
    val height: Float,
    val lines: List<PdfLine>,
    val ink: PdfInk,
    /** Names a figure's picture, so it can be drawn when it is needed. */
    val figurePart: (PdfRect) -> String,
)

/**
 * A page's content in reading order, in the document model the objects import already speaks.
 * A [slide] stands on its own, where a page of a document runs on into the next.
 */
internal class PdfPageLayout(val pieces: List<PagePiece>, val endsMidSentence: Boolean, val slide: Boolean)

private const val TWIPS_PER_POINT = 20

/** One thing in reading order, once whatever shares a row has been put together. */
private sealed interface Row

private sealed interface Atom {
    val box: PdfRect

    class Text(val line: PdfLine) : Atom {
        override val box: PdfRect get() = line.box
    }

    class Table(val table: WordTable, override val box: PdfRect, val em: Float) : Atom, Row

    class Figure(val picture: WordPicture, override val box: PdfRect) : Atom, Row
}

private class Placed(val atom: Atom, val columnLeft: Float, val columnRight: Float)

/** The size of most of the words on these pages. Headings are measured against it. */
internal fun bodyEmOf(pages: List<PdfPageInput>): Float {
    // Not the tables, which are set small and can have more words than everything else put together.
    val sizes = pages.flatMap { page ->
        page.lines.flatMap { line ->
            line.words.filter { word -> page.ink.grids.none { it.box.containsCenterOf(word.box) } }.map { line.em }
        }
    }.sorted()
    return if (sizes.isEmpty()) 11f else sizes[sizes.size / 2]
}

/**
 * The usual size of a slide's text below its first line, which is the one being measured against it.
 * Each slide goes by its own, because one deck's slides can differ more than a document's headings do.
 */
private fun slideBodyEm(lines: List<PdfLine>): Float? {
    val sizes = lines.sortedBy { it.box.top }.drop(1).flatMap { line -> line.words.map { line.em } }.sorted()
    return sizes.getOrNull(sizes.size / 2)
}

/**
 * Turns one page into paragraphs, tables and pictures, in the order a reader would meet them.
 *
 * The file's own order is no use: a PDF writes things in whatever order its producer liked, and the
 * LibreOffice export of a lecture puts every text box after all the body text.
 */
internal fun layoutPage(page: PdfPageInput, bodyEm: Float): PdfPageLayout {
    var words: Set<PdfWord> = page.lines.flatMapTo(HashSet()) { it.words }
    val atoms = ArrayList<Atom>()

    // Tables first: they claim their words, even ones a row's line has run together.
    val tables = ArrayList<PdfGrid>()
    for (grid in page.ink.grids) {
        val inside = words.filter { grid.box.containsCenterOf(it.box) }
        // Lines and no words is a picture of a table, and stays a picture.
        if (inside.isEmpty()) continue
        words = words - inside.toSet()
        atoms += Atom.Table(tableOf(grid, inside, bodyEm), grid.box, PdfLine(inside).em)
        tables += grid
    }

    var lines = page.lines.mapNotNull { line ->
        val left = line.words.filter { it in words }
        when {
            left.isEmpty() -> null
            left.size == line.words.size -> line
            else -> PdfLine(left)
        }
    }

    // Then figures, which take the labels drawn over and around them.
    val figures = page.ink.figures.filterNot { figure -> isBackgroundArt(figure, lines, page) }
        .filterNot { figure -> tables.any { it.box.containsCenterOf(figure) } }
        .filterNot { figure -> figure.right < page.width * SIDE_MARGIN_SHARE || figure.left > page.width * (1f - SIDE_MARGIN_SHARE) }
    // With nothing else on a slide to compare it with, its one line is its title.
    val slide = page.width > page.height
    val measure = if (slide) slideBodyEm(lines) ?: 1f else bodyEm
    val grouped = groupFigures(figures, lines, page.width)
    val formulas = formulasOf(lines, grouped)
    val symbols = formulas.flatMap { it.symbols }.toSet()
    lines = lines - formulas.flatMap { it.lines }.toSet()
    formulas.forEach { formula -> atoms += Atom.Figure(pictureOf(formula.box, page, formulaScale(formula, measure)), formula.box) }

    for (original in grouped.filterNot { it in symbols }) {
        var figure = original
        repeat(2) {
            val taken = lines.filter { isLabelOf(it, figure) }
            lines = lines - taken.toSet()
            taken.forEach { figure = figure.union(it.box) }
        }
        atoms += Atom.Figure(pictureOf(figure, page), figure)
    }

    lines.forEach { atoms += Atom.Text(it) }
    if (atoms.isEmpty()) return PdfPageLayout(emptyList(), endsMidSentence = false, slide)

    val placed = ArrayList<Placed>()
    orderAtoms(atoms, atoms.minOf { it.box.left }, atoms.maxOf { it.box.right }, placed)

    val bodyWidth = max(atoms.maxOf { it.box.right } - atoms.minOf { it.box.left }, page.width * 0.5f)
    val bodyTwips = (bodyWidth * TWIPS_PER_POINT).roundToInt()
    return Assembler(measure, bodyTwips, page.width / 2f, slide).assemble(placed)
}

private fun formulaScale(formula: Formula, bodyEm: Float): Float {
    val em = formula.lines.maxBy { line -> line.words.sumOf { it.located.size } }.em
    return (bodyEm * FORMULA_MAX_SIZE_RATIO / em).coerceIn(FORMULA_MIN_SCALE, 1f)
}

/**
 * Puts the parts of one drawing back together.
 *
 * Words are blanked out before figures are looked for, so a diagram that is mostly labelled boxes falls
 * apart into the lines between them. What says the pieces are one thing is that no prose runs between.
 */
private fun groupFigures(figures: List<PdfRect>, lines: List<PdfLine>, pageWidth: Float): List<PdfRect> {
    val prose = lines.filter { it.words.size >= PROSE_MIN_WORDS || it.box.width > pageWidth * PROSE_MIN_PAGE_SHARE }
    var out = figures
    while (true) {
        val pair = out.indices.firstNotNullOfOrNull { first ->
            ((first + 1) until out.size).firstOrNull { second -> belongTogether(out[first], out[second], prose) }?.let { first to it }
        } ?: return out
        out = out.filterIndexed { index, _ -> index != pair.first && index != pair.second } + out[pair.first].union(out[pair.second])
    }
}

private fun belongTogether(a: PdfRect, b: PdfRect, prose: List<PdfLine>): Boolean {
    if (a.distanceTo(b) > FIGURE_GROUP_GAP) return false
    val union = a.union(b)
    if (prose.any { union.containsCenterOf(it.box) }) return false
    val sideBySide = a.right <= b.left || b.right <= a.left
    return !sideBySide || !isColumnGutter(min(a.right, b.right), max(a.left, b.left), union, prose)
}

/** Two figures level with each other, either side of a gap no text nearby ever crosses, are in two columns. */
private fun isColumnGutter(from: Float, to: Float, around: PdfRect, prose: List<PdfLine>): Boolean {
    val above = prose.filter { it.box.bottom <= around.top }.sortedByDescending { it.box.bottom }.take(GUTTER_LINES)
    val below = prose.filter { it.box.top >= around.bottom }.sortedBy { it.box.top }.take(GUTTER_LINES)
    val nearby = above + below
    return nearby.isNotEmpty() && nearby.none { it.box.left < from && it.box.right > to }
}

private const val FIGURE_GROUP_GAP = 30f
private const val PROSE_MIN_WORDS = 6
private const val PROSE_MIN_PAGE_SHARE = 0.4f
private const val GUTTER_LINES = 3

/** A formula to be cut out of the page, and what on the page it is made of. */
private class Formula(val box: PdfRect, val lines: List<PdfLine>, val symbols: List<PdfRect>)

/** One line of a formula, or a [symbol] drawn as a shape rather than set as a letter. */
private class FormulaPiece(val box: PdfRect, val em: Float, val line: PdfLine?, val symbol: PdfRect?)

/**
 * Display math that would not survive as text: pieces above and below each other, like a fraction or
 * the limits of a sum, or a symbol a slide has drawn as a shape among its letters.
 *
 * The first comes out scrambled, one piece per line, and the second with a hole in it, so both come
 * in as pictures of themselves. Going by these two is what spares an equation that reads fine as one
 * line of text, and sentences stay out of it so that a stray overlap can't take a paragraph along.
 */
private fun formulasOf(lines: List<PdfLine>, figures: List<PdfRect>): List<Formula> {
    val candidates = lines.filterNot(::isSentence)
    val symbols = figures.filter { figure -> candidates.any { isSetBeside(figure, it) } }
    val pieces = candidates.map { FormulaPiece(it.box, it.em, it, null) } + symbols.map { FormulaPiece(it, it.height, null, it) }
    val parent = IntArray(pieces.size) { it }
    fun find(item: Int): Int {
        var at = item
        while (parent[at] != at) {
            parent[at] = parent[parent[at]]
            at = parent[at]
        }
        return at
    }
    for (first in pieces.indices) {
        for (second in first + 1 until pieces.size) {
            if (partOfOneFormula(pieces[first], pieces[second])) parent[find(first)] = find(second)
        }
    }
    return pieces.indices.groupBy(::find).values.mapNotNull { indices ->
        val group = indices.map(pieces::get)
        val groupLines = group.mapNotNull { it.line }
        val groupSymbols = group.mapNotNull { it.symbol }
        val stacked = groupLines.size >= FORMULA_MIN_PIECES && hasStackedPieces(groupLines)
        if (groupLines.isEmpty() || (groupSymbols.isEmpty() && !stacked)) return@mapNotNull null
        // Its number sits far out at the margin, and is no use as a paragraph on its own.
        val body = (groupLines.map { it.box } + groupSymbols).reduce(PdfRect::union)
        val numbers = lines.filter { line ->
            EQUATION_NUMBER.matches(line.text) && sharesRow(body, line.box) &&
                lines.none { other -> other !in groupLines && other !== line && sharesRow(body, other.box) && other.box.left >= body.right && other.box.right <= line.box.left }
        }
        val all = (groupLines + numbers).distinct()
        val box = (all.map { it.box } + groupSymbols).reduce(PdfRect::union).expanded(FORMULA_MARGIN)
        Formula(box, all, groupSymbols)
    }
}

private fun sharesRow(a: PdfRect, b: PdfRect): Boolean =
    min(a.bottom, b.bottom) - max(a.top, b.top) >= min(a.height, b.height) * FORMULA_ROW_OVERLAP

private fun gapAcross(a: PdfRect, b: PdfRect): Float = max(a.left - b.right, b.left - a.right)

/** About the size of a letter and level with some text, which makes a drawing part of the line. */
private fun isSetBeside(figure: PdfRect, line: PdfLine): Boolean =
    figure.height <= line.em * SYMBOL_MAX_HEIGHT_EMS && figure.width <= line.em * SYMBOL_MAX_WIDTH_EMS &&
        sharesRow(figure, line.box) && gapAcross(figure, line.box) <= line.em * FORMULA_MAX_GAP_EMS

private fun partOfOneFormula(a: FormulaPiece, b: FormulaPiece): Boolean {
    // The two's average: a small subscript belongs to the big letter it hangs off, however small it is.
    if (sharesRow(a.box, b.box) && gapAcross(a.box, b.box) <= (a.em + b.em) / 2f * FORMULA_MAX_GAP_EMS) return true
    val symbol = a.symbol ?: b.symbol ?: return false
    val other = (if (a.symbol != null) b else a).box
    // What a bowtie is subscripted with sits under it, not level with it.
    return other.distanceTo(symbol) <= max(a.em, b.em) * SYMBOL_LABEL_REACH_EMS &&
        other.centerX in symbol.left..symbol.right && other.width <= symbol.width * SYMBOL_LABEL_MAX_WIDTH
}

private fun hasStackedPieces(group: List<PdfLine>): Boolean = group.any { a ->
    group.any { b ->
        b !== a && b.box.top >= a.box.centerY && min(a.box.right, b.box.right) - max(a.box.left, b.box.left) >= FORMULA_MIN_STACK
    }
}

/** A run of ordinary words. A formula's names are few, however long its line gets. */
private fun isSentence(line: PdfLine): Boolean =
    line.words.count { word -> word.text.count(Char::isLowerCase) >= SENTENCE_WORD_LETTERS } >= SENTENCE_MIN_WORDS

private val EQUATION_NUMBER = Regex("""^\(\d{1,3}[a-z]?\)$""")
private const val FORMULA_ROW_OVERLAP = 0.3f

/** Tighter than the gap between two columns, which would otherwise join a line in each. */
private const val FORMULA_MAX_GAP_EMS = 1.5f

/** Text arrives at the canvas's size whatever the page had it at; a formula can stay a little bigger. */
private const val FORMULA_MAX_SIZE_RATIO = 1.3f
private const val FORMULA_MIN_SCALE = 0.5f
private const val FORMULA_MIN_PIECES = 3
private const val FORMULA_MIN_STACK = 1.5f
private const val FORMULA_MARGIN = 2f
private const val SYMBOL_MAX_HEIGHT_EMS = 2.5f
private const val SYMBOL_MAX_WIDTH_EMS = 5f
private const val SYMBOL_LABEL_REACH_EMS = 0.5f
private const val SYMBOL_LABEL_MAX_WIDTH = 3f
private const val SENTENCE_WORD_LETTERS = 4
private const val SENTENCE_MIN_WORDS = 4

/** A drawing behind most of a page's words is decoration, or the scan under a recognised page. */
private fun isBackgroundArt(figure: PdfRect, lines: List<PdfLine>, page: PdfPageInput): Boolean {
    if (figure.area < page.width * page.height * BACKGROUND_MIN_SHARE) return false
    val covered = lines.filter { figure.containsCenterOf(it.box) }.sumOf { it.box.area.toDouble() }
    return covered >= figure.area * BACKGROUND_MIN_TEXT
}

/** A mark wholly out in the side margins is a stamp, like the date down an arXiv paper's edge. */
private const val SIDE_MARGIN_SHARE = 0.12f
private const val BACKGROUND_MIN_SHARE = 0.5f
private const val BACKGROUND_MIN_TEXT = 0.06f

/** Text on a figure, or a short label close enough to be part of it: axis ticks, a legend. */
private fun isLabelOf(line: PdfLine, figure: PdfRect): Boolean {
    if (figure.expanded(LABEL_OVERLAP).containsCenterOf(line.box)) return true
    if (line.box.distanceTo(figure) > LABEL_REACH) return false
    val numeric = line.text.all { it.isDigit() || it in " .,-+%" }
    return numeric || (line.words.size <= LABEL_MAX_WORDS && line.box.width <= figure.width * LABEL_MAX_WIDTH)
}

private const val LABEL_OVERLAP = 2f
private const val LABEL_REACH = 12f
private const val LABEL_MAX_WORDS = 3
private const val LABEL_MAX_WIDTH = 0.5f

private fun pictureOf(figure: PdfRect, page: PdfPageInput, scale: Float = 1f): WordPicture = WordPicture(
    part = page.figurePart(figure),
    widthTwips = (figure.width * TWIPS_PER_POINT * scale).roundToInt(),
    heightTwips = (figure.height * TWIPS_PER_POINT * scale).roundToInt(),
    crop = null,
    anchor = null,
)

// --- tables ----------------------------------------------------------------------------------------

private fun indexIn(edges: FloatArray, at: Float): Int {
    for (index in 0 until edges.size - 1) if (at < edges[index + 1]) return index.takeIf { at >= edges[0] } ?: -1
    return -1
}

private fun tableOf(grid: PdfGrid, words: List<PdfWord>, bodyEm: Float): WordTable {
    val rows = grid.rows
    val columns = grid.columns
    val groups = grid.cellGroups()
    val fills = grid.fills
    // A dark band across the top with pale letters on it is what a header looks like on a slide.
    val darkHeader = (0 until columns).all { column -> fills.getOrNull(column)?.let(::isDarkFill) == true }
    val contents = Array(rows * columns) { ArrayList<PdfWord>() }
    for (word in words) {
        val row = indexIn(grid.ys, word.box.centerY)
        val column = indexIn(grid.xs, word.box.centerX)
        if (row >= 0 && column >= 0) contents[groups[row * columns + column]] += word
    }
    val tableRows = (0 until rows).map { row ->
        val cells = ArrayList<WordCell>()
        var column = 0
        while (column < columns) {
            val index = row * columns + column
            val group = groups[index]
            var span = 1
            while (column + span < columns && groups[row * columns + column + span] == group) span++
            val startsHere = group == index
            val startsAbove = !startsHere && group % columns == column && group < index
            val fill = fills.getOrNull(group) ?: NO_FILL
            cells += WordCell(
                blocks = if (startsHere) cellParagraphs(contents[group], bodyEm, fill) else emptyList(),
                gridSpan = span,
                verticalMerge = when {
                    startsAbove -> WordVerticalMerge.Continue
                    startsHere && index + columns < groups.size && groups[index + columns] == group -> WordVerticalMerge.Restart
                    else -> WordVerticalMerge.None
                },
                widthTwips = null,
                shadingArgb = fill.takeIf { it != NO_FILL },
                borders = null,
                marginLeftTwips = 0,
                marginTopTwips = 0,
                marginRightTwips = 0,
                marginBottomTwips = 0,
                align = WordCellAlign.Top,
            )
            column += span
        }
        WordRow(
            cells = cells,
            heightTwips = ((grid.ys[row + 1] - grid.ys[row]) * TWIPS_PER_POINT).roundToInt(),
            exactHeight = false,
            isHeader = row == 0 && darkHeader,
            cantSplit = false,
            gridBefore = 0,
            gridAfter = 0,
        )
    }
    return WordTable(
        gridTwips = IntArray(columns) { ((grid.xs[it + 1] - grid.xs[it]) * TWIPS_PER_POINT).roundToInt() },
        rows = tableRows,
        borders = null,
        indentTwips = 0,
        align = WordAlign.Left,
        widthTwips = null,
        widthPercent = null,
    )
}

private fun isDarkFill(fill: Int): Boolean {
    if (fill == NO_FILL) return false
    val luminance = (((fill shr 16) and 0xFF) * 299 + ((fill shr 8) and 0xFF) * 587 + (fill and 0xFF) * 114) / 1000
    return luminance < DARK_FILL_LUMINANCE
}

private const val DARK_FILL_LUMINANCE = 150

/**
 * The stored table has one fill for all its cells, so a cell that stands out in a colour keeps it as
 * a highlight on its text; those are usually the rows a slide is pointing at. Not a dark one, whose
 * letters are pale and are coming in black.
 */
private fun cellHighlight(fill: Int): Int? = fill.takeIf { it != NO_FILL && it.isChromatic() && !isDarkFill(it) }

private fun cellParagraphs(words: List<PdfWord>, bodyEm: Float, fill: Int): List<WordBlock> {
    if (words.isEmpty()) return emptyList()
    val highlight = cellHighlight(fill)
    val rows = ArrayList<MutableList<PdfWord>>()
    for (word in words.sortedBy { it.box.centerY }) {
        val row = rows.lastOrNull()?.takeIf { row -> sameRow(row.last().box, word.box) }
        if (row != null) row += word else rows += mutableListOf(word)
    }
    val lines = rows.map { row -> PdfLine(row.sortedBy { it.box.left }) }
    val paragraphs = ArrayList<WordParagraph>()
    var current = ArrayList<PdfLine>()
    fun finish() {
        if (current.isEmpty()) return
        val inlines = inlinesOf(current.map(::listOf), bodyEm).map { inline ->
            if (inline is WordText && highlight != null) WordText(inline.text, inline.style.copy(highlightArgb = highlight), inline.link) else inline
        }
        paragraphs += WordParagraph(WordParagraphStyle(), WordRunStyle(), inlines, null)
        current = ArrayList()
    }
    for (line in lines) {
        val above = current.lastOrNull()
        if (above != null && line.box.top - above.box.bottom > line.em * PARAGRAPH_GAP_EMS) finish()
        current += line
    }
    finish()
    return paragraphs
}

private fun sameRow(a: PdfRect, b: PdfRect): Boolean = overlapsMostly(a.top, a.bottom, b.top, b.bottom)

// --- reading order ---------------------------------------------------------------------------------

/**
 * Recursive whitespace cuts: a lane running clear down the block makes columns, otherwise the widest
 * gap across it divides top from bottom.
 */
private fun orderAtoms(atoms: List<Atom>, left: Float, right: Float, out: MutableList<Placed>) {
    if (atoms.size == 1) {
        out += Placed(atoms[0], left, right)
        return
    }
    columnLane(atoms)?.let { lane ->
        val (first, second) = atoms.partition { it.box.centerX < lane }
        orderAtoms(first, first.minOf { it.box.left }, first.maxOf { it.box.right }, out)
        orderAtoms(second, second.minOf { it.box.left }, second.maxOf { it.box.right }, out)
        return
    }
    val cut = widestRowGap(atoms)
    if (cut != null) {
        val (above, below) = atoms.partition { it.box.centerY < cut }
        if (above.isNotEmpty() && below.isNotEmpty()) {
            orderAtoms(above, left, right, out)
            orderAtoms(below, left, right, out)
            return
        }
    }
    // Nothing clear between them: one row of things.
    atoms.sortedBy { it.box.left }.forEach { out += Placed(it, left, right) }
}

private fun widestRowGap(atoms: List<Atom>): Float? {
    val sorted = atoms.sortedBy { it.box.top }
    var bottom = sorted[0].box.bottom
    var best: Float? = null
    var widest = 0f
    for (atom in sorted.drop(1)) {
        val gap = atom.box.top - bottom
        if (gap > widest) {
            widest = gap
            best = bottom + gap / 2f
        }
        bottom = max(bottom, atom.box.bottom)
    }
    return best
}

/**
 * The middle of a clear lane down the block, if the two sides read as columns of text.
 *
 * What rules the others out is that a column of text fills its width, line after line. A list's
 * markers beside its text, or two columns of short table cells, have lanes as clear, and neither one
 * wants reading all the way down the left before starting the right.
 */
private fun columnLane(atoms: List<Atom>): Float? {
    val sorted = atoms.sortedBy { it.box.left }
    var right = sorted[0].box.right
    var laneFrom = 0f
    var laneTo = 0f
    for (atom in sorted.drop(1)) {
        if (atom.box.left - right > laneTo - laneFrom) {
            laneFrom = right
            laneTo = atom.box.left
        }
        right = max(right, atom.box.right)
    }
    if (laneTo - laneFrom < MIN_COLUMN_LANE) return null
    val middle = (laneFrom + laneTo) / 2f
    val first = atoms.filter { it.box.centerX < middle }
    val second = atoms.filter { it.box.centerX >= middle }
    if (first.size < MIN_COLUMN_ATOMS || second.size < MIN_COLUMN_ATOMS) return null
    val span = sorted.maxOf { it.box.right } - sorted.first().box.left
    fun reads(side: List<Atom>): Boolean {
        val width = side.maxOf { it.box.right } - side.minOf { it.box.left }
        // The labels down the side of a code listing are a tidy column too, but of single words.
        val lines = side.filterIsInstance<Atom.Text>()
        if (lines.sumOf { it.line.words.size } < lines.size * MIN_COLUMN_WORDS) return false
        return width >= span * MIN_COLUMN_SHARE &&
            side.count { it !is Atom.Text || it.box.width >= width * COLUMN_LINE_FILL } >= side.size * 0.5f
    }
    return middle.takeIf { reads(first) && reads(second) }
}

private const val MIN_COLUMN_LANE = 10f
private const val MIN_COLUMN_ATOMS = 3
private const val MIN_COLUMN_SHARE = 0.2f
private const val COLUMN_LINE_FILL = 0.65f
private const val MIN_COLUMN_WORDS = 3

// --- paragraphs ------------------------------------------------------------------------------------

/** A gap under a line wider than this share of the font size is the end of a paragraph. */
private const val PARAGRAPH_GAP_EMS = 0.9f

private const val FULL_LINE_MARGIN_EMS = 1.5f
private const val INDENT_CHANGE_EMS = 0.8f
private const val SIZE_CHANGE = 1.2f

/** Sizes are worked out from positions in whole points, so a short line's is only a rough one. */
private const val MIN_GLYPHS_FOR_SIZE = 8

/** Rounded positions can put the next piece of a line a point or two before the last one ended. */
private const val SAME_ROW_OVERLAP = 3f

/** How many lines either side say how far this one could have run. */
private const val REACH_LINES = 3
private const val REACH_SAME_START_EMS = 2f
private const val FIT_SLACK_EMS = 0.5f
private const val HEADING_BOLD_SHARE = 0.8f
private const val NOMINAL_BODY_TWIPS = 9360
private const val NOMINAL_BODY_EM = 11f
private const val SLIDE_TITLE_RATIO = 1.15f
private const val SLIDE_TITLE_MAX_LINES = 3
private const val SLIDE_TITLE_MAX_WORDS = 30
private const val SLIDE_CENTER_MAX_SHARE = 0.8f
private const val CENTER_MIN_INSET_EMS = 1.5f
private const val CENTER_TOLERANCE_EMS = 1.5f
private const val LIST_LEVEL_EMS = 1.8f
private const val MIN_INDENT_EMS = 1.2f
private const val HEADING_MAX_LINES = 2
private const val HEADING_MAX_WORDS = 14
private const val BLANK_LINE_MIN_GAP_EMS = 0.75f
private const val TAB_GAP_EMS = 1.5f
private const val WORD_GAP_EMS = 0.15f
private const val WORD_SPACE_EMS = 0.25f

private val BULLETS = setOf('•', '·', '▪', '▫', '◦', '‣', '■', '□', '●', '○', '◆', '–', '—', '-', '➢', '✓', '⁃')
private val NUMBER_MARKER = Regex("""^\(?(\d{1,3}|[A-Za-z]|[ivxIVX]{2,5})[.)]$""")
private val SECTION_NUMBER = Regex("""^([A-Z]\.)?\d{1,2}(\.\d{1,2})*\.?$""")
private val LIGATURES = mapOf("ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl", "ﬅ" to "st", "ﬆ" to "st")


/** The label a word is, if it is the marker of a list item: and whether that label is a bullet. */
private fun listMarker(word: PdfWord): Pair<String, Boolean>? {
    val text = word.text
    if (text.length == 1 && (text[0] in BULLETS || isBulletGlyph(text[0]))) return "•" to true
    if (NUMBER_MARKER.matches(text)) return text to false
    return null
}

/** Lines on one row, and the column they are in. */
private class VisualLine(val parts: List<PdfLine>, val columnLeft: Float, val columnRight: Float) : Row {
    val box: PdfRect = parts.map { it.box }.reduce(PdfRect::union)

    /** The part with the most in it: a raised `2` that the file wrote as its own line has no say. */
    val main: PdfLine = parts.maxBy { part -> part.words.sumOf { it.located.size } }
    val em: Float = main.em
    val largestEm: Float = parts.maxOf { it.largestEm }
    val first: PdfWord = parts.first().words.first()
    val glyphs: Int = parts.sumOf { part -> part.words.sumOf { it.located.size } }

    /** How far right this line could have run, going by the lines around it. */
    var reach: Float = box.right
}

private fun readableText(word: PdfWord, glyphs: List<PdfGlyph>): String {
    var text = buildString {
        glyphs.forEach { if (it.char != SOFT_HYPHEN_MARK && !it.char.isISOControl()) append(it.char) }
    }
    if (LIGATURES.keys.any { it in text }) LIGATURES.forEach { (ligature, letters) -> text = text.replace(ligature, letters) }
    return text
}

private fun runStyle(word: PdfWord, line: PdfLine, script: PdfScript, bodyEm: Float): WordRunStyle = WordRunStyle(
    bold = word.bold,
    underline = word.underline,
    script = when (script) {
        PdfScript.Baseline -> WordScript.Baseline
        PdfScript.Super -> WordScript.Super
        PdfScript.Sub -> WordScript.Sub
    },
    colorArgb = word.colorArgb,
    halfPoints = (max(line.em, bodyEm) * 2f).roundToInt(),
    font = if (line.monospace) "monospace" else null,
)

/** Text runs for [line]: a change of look, or a superscript inside a word, starts a new one. */
private fun runsOf(line: PdfLine, main: PdfLine, bodyEm: Float, sink: (String, WordRunStyle, String?) -> Unit) {
    line.words.forEachIndexed { index, word ->
        if (index > 0) sink(" ", runStyle(line.words[index - 1], line, PdfScript.Baseline, bodyEm).copy(underline = false), null)
        var start = 0
        val glyphs = word.glyphs
        while (start < glyphs.size) {
            val script = scriptOf(glyphs[start], main)
            var end = start + 1
            while (end < glyphs.size && scriptOf(glyphs[end], main) == script) end++
            val text = readableText(word, glyphs.subList(start, end))
            if (text.isNotEmpty()) sink(text, runStyle(word, line, script, bodyEm), word.link)
            start = end
        }
    }
}

private fun inlinesOf(lines: List<List<PdfLine>>, bodyEm: Float, skipFirstWord: Boolean = false): List<WordInline> {
    val out = ArrayList<WordInline>()
    fun add(text: String, style: WordRunStyle, link: String?) {
        val last = out.lastOrNull()
        if (last is WordText && last.style == style && last.link == link) {
            out[out.lastIndex] = WordText(last.text + text, style, link)
        } else {
            out += WordText(text, style, link)
        }
    }
    lines.forEachIndexed { row, parts ->
        val main = parts.maxBy { part -> part.words.sumOf { it.located.size } }
        parts.forEachIndexed { partIndex, part ->
            if (partIndex > 0) {
                // A file can write a superscript or the full stop after it as a line of its own, and
                // those stay part of the sentence. Only a gap too wide to be one is a tab.
                val gap = part.box.left - parts[partIndex - 1].box.right
                when {
                    gap > main.em * TAB_GAP_EMS -> out += WordTab(WordRunStyle())
                    gap >= main.em * WORD_GAP_EMS -> add(" ", WordRunStyle(), null)
                }
            }
            val source = if (skipFirstWord && row == 0 && partIndex == 0 && part.words.size > 1) PdfLine(part.words.drop(1)) else part
            runsOf(source, main, bodyEm, ::add)
        }
        if (row != lines.lastIndex) {
            val last = out.lastOrNull() as? WordText
            val next = lines[row + 1].first().words.first().text
            val hyphenated = last != null && last.text.endsWith("-") && next.firstOrNull()?.isLowerCase() == true
            val softHyphen = lines[row].last().words.last().glyphs.lastOrNull()?.char == SOFT_HYPHEN_MARK
            when {
                hyphenated -> out[out.lastIndex] = WordText(last!!.text.dropLast(1), last.style, last.link)
                !softHyphen -> add(" ", WordRunStyle(), null)
            }
        }
    }
    return out
}

/** Gathers ordered atoms into the pieces of a page. */
private class Assembler(
    private val bodyEm: Float,
    private val bodyTwips: Int,
    private val pageCenter: Float,
    private val slide: Boolean,
) {
    private val pieces = ArrayList<PagePiece>()
    private var draft = ArrayList<VisualLine>()
    private var draftListed = false
    private var sawText = false
    private var listLeft: Float? = null
    private var endsMidSentence = false

    fun assemble(placed: List<Placed>): PdfPageLayout {
        val rows = rowsOf(placed)
        val lines = rows.filterIsInstance<VisualLine>()
        lines.forEachIndexed { index, line -> line.reach = reachOf(lines, index) }
        for (row in rows) {
            when (row) {
                is Atom.Table -> {
                    finish(null)
                    pieces += TablePiece(row.table, 0, row.table.rows.size, if (slide) slideTableTwips(row.em) else bodyTwips)
                }
                is Atom.Figure -> {
                    finish(null)
                    pieces += ParagraphPiece(WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(row.picture), null), bodyTwips)
                }
                is VisualLine -> addLine(row)
            }
        }
        finish(null)
        return PdfPageLayout(pieces, endsMidSentence, slide)
    }

    /**
     * The width of page a table set at [em] would have been sized for. A slide's letters are much
     * bigger for its width than a document's, and left alone its columns are too narrow for its words.
     */
    private fun slideTableTwips(em: Float): Int = (NOMINAL_BODY_TWIPS * em / NOMINAL_BODY_EM).roundToInt()

    private fun rowsOf(placed: List<Placed>): List<Row> {
        val rows = ArrayList<Row>()
        var index = 0
        while (index < placed.size) {
            val item = placed[index]
            when (val atom = item.atom) {
                is Atom.Table -> {
                    rows += atom
                    index++
                }
                is Atom.Figure -> {
                    rows += atom
                    index++
                }
                is Atom.Text -> {
                    // Whatever else in reading order sits on this same row is more of this line.
                    val parts = arrayListOf(atom.line)
                    var next = index + 1
                    while (next < placed.size) {
                        val other = placed[next].atom as? Atom.Text ?: break
                        if (!sameRow(atom.box, other.box) || other.box.left < parts.last().box.right - SAME_ROW_OVERLAP) break
                        parts += other.line
                        next++
                    }
                    rows += VisualLine(parts, item.columnLeft, item.columnRight)
                    index = next
                }
            }
        }
        return rows
    }

    /**
     * The furthest right any nearby line starting in about the same place got.
     *
     * A paragraph's own lines can't answer this, because its first line has only itself to go by, and
     * a short line that follows a shorter one would look full.
     */
    private fun reachOf(lines: List<VisualLine>, index: Int): Float {
        val line = lines[index]
        return (max(0, index - REACH_LINES)..min(lines.lastIndex, index + REACH_LINES))
            .map { lines[it] }
            .filter { other ->
                other.columnLeft == line.columnLeft && other.columnRight == line.columnRight &&
                    abs(other.box.left - line.box.left) <= line.em * REACH_SAME_START_EMS
            }
            .maxOf { it.box.right }
    }

    private fun addLine(line: VisualLine) {
        val last = draft.lastOrNull()
        if (last != null && startsNewParagraph(last, line)) finish(line)
        if (draft.isEmpty()) draftListed = listMarker(line.first) != null && line.parts.first().words.size + line.parts.size > 1
        draft += line
    }

    private fun startsNewParagraph(above: VisualLine, line: VisualLine): Boolean {
        val em = max(above.em, line.em)
        val bigger = max(above.largestEm, line.largestEm)
        val smaller = min(above.largestEm, line.largestEm)
        if (above.glyphs >= MIN_GLYPHS_FOR_SIZE && line.glyphs >= MIN_GLYPHS_FOR_SIZE && bigger / max(smaller, 1f) > SIZE_CHANGE) return true
        if (line.box.top - above.box.bottom > em * PARAGRAPH_GAP_EMS) return true
        if (listMarker(line.first) != null) return true
        // Code is laid out by hand, line by line.
        if (above.main.monospace && line.main.monospace) return true
        if (endedEarly(above, line, em)) return true
        val shift = line.box.left - above.box.left
        return when {
            draftListed && draft.size == 1 -> abs(line.box.left - textLeft(draft.first())) > em * INDENT_CHANGE_EMS
            // Further left is the rest of a paragraph whose first line was indented.
            draft.size == 1 -> shift > em * INDENT_CHANGE_EMS
            else -> abs(shift) > em * INDENT_CHANGE_EMS
        }
    }

    /** The next line's first word would have fitted on this one, so whoever wrote it ended the line. */
    private fun endedEarly(above: VisualLine, line: VisualLine, em: Float): Boolean {
        val lastLetter = above.parts.last().words.last().glyphs.lastOrNull()?.char
        if (lastLetter == '-' || lastLetter == SOFT_HYPHEN_MARK) return false
        val next = line.first.box.width + em * WORD_SPACE_EMS
        val centered = abs(above.box.centerX - line.box.centerX) <= em * CENTER_TOLERANCE_EMS &&
            abs(above.box.left - line.box.left) > em * INDENT_CHANGE_EMS
        // Widths are known to a point or two, and a word that only just fits may well have been wrapped.
        val slack = em * FIT_SLACK_EMS
        // Centred lines only ever reach the margins together, so it is their width that says it.
        return if (centered) {
            above.box.width + next < above.columnRight - above.columnLeft - slack
        } else {
            above.box.right + next < above.reach - slack
        }
    }

    private fun textLeft(line: VisualLine): Float =
        line.parts.first().words.getOrNull(1)?.box?.left ?: line.parts.getOrNull(1)?.box?.left ?: line.box.left

    private fun finish(next: VisualLine?) {
        if (draft.isEmpty()) return
        val lines = draft
        val first = lines.first()
        val paragraph = paragraphOf(lines, next)
        pieces += ParagraphPiece(paragraph, bodyTwips)
        val text = lines.last().box
        endsMidSentence = next == null && !slide && !lastCharacterEnds(lines.last()) &&
            text.right >= first.columnRight - first.em * FULL_LINE_MARGIN_EMS
        draft = ArrayList()
        draftListed = false
        sawText = true
    }

    private fun lastCharacterEnds(line: VisualLine): Boolean =
        line.parts.last().words.last().text.lastOrNull()?.let { it in ".:;?!)" } == true

    private fun paragraphOf(lines: List<VisualLine>, next: VisualLine?): WordParagraph {
        val first = lines.first()
        val em = lines.maxOf { it.em }
        val left = lines.minOf { it.box.left }
        val right = lines.maxOf { it.box.right }
        val insetLeft = left - first.columnLeft
        val insetRight = first.columnRight - right
        val centeredOnPage = abs((left + right) / 2f - pageCenter) <= em * CENTER_TOLERANCE_EMS
        val clearOfEdges = insetLeft >= em * CENTER_MIN_INSET_EMS && insetRight >= em * CENTER_MIN_INSET_EMS
        val centered = lines.size <= 3 && !draftListed && (
            (clearOfEdges && (centeredOnPage || abs(insetLeft - insetRight) <= em * CENTER_TOLERANCE_EMS)) ||
                // A slide's widest line is often the centred one, leaving nothing to measure it against.
                (slide && centeredOnPage && right - left <= pageCenter * 2f * SLIDE_CENTER_MAX_SHARE)
            )
        val align = when {
            centered -> WordAlign.Center
            !draftListed && lines.size == 1 && insetRight <= em * 0.5f && insetLeft >= (first.columnRight - first.columnLeft) * 0.35f -> WordAlign.Right
            else -> WordAlign.Left
        }
        val words = lines.flatMap { line -> line.parts.flatMap { it.words } }
        val bold = words.count { it.bold } >= words.size * HEADING_BOLD_SHARE
        val bulleted = draftListed && listMarker(first.first)?.second == true
        val heading = (em / bodyEm).let { ratio ->
            when {
                bulleted -> null
                // A slide has one title. The big letters further down it are its content, not its sections.
                slide -> 0.takeIf {
                    !sawText && (bold || lines.maxOf { it.largestEm } / bodyEm >= SLIDE_TITLE_RATIO) &&
                        lines.size <= SLIDE_TITLE_MAX_LINES && words.size <= SLIDE_TITLE_MAX_WORDS
                }
                lines.size > HEADING_MAX_LINES || words.size > HEADING_MAX_WORDS -> null
                ratio >= 1.55f -> 0
                ratio >= 1.32f -> 1
                ratio >= 1.2f || (ratio >= 1.13f && bold) -> 2
                // A paper's smaller section titles are its body size, and only the bold and number show it.
                bold && ratio >= 0.95f && SECTION_NUMBER.matches(first.first.text) -> 2
                else -> null
            }
        }
        val marker = if (heading == null && draftListed) listMarker(first.first) else null
        // Counted from the list's own left edge, because a slide can put a list anywhere on itself.
        listLeft = marker?.let { min(listLeft ?: first.box.left, first.box.left) }
        val level = ((first.box.left - (listLeft ?: first.box.left)) / (em * LIST_LEVEL_EMS)).toInt().coerceIn(0, 8)
        val label = marker?.let { (text, bullet) ->
            WordListLabel(text, WordRunStyle(), followedByTab = true, followedBySpace = false, bullet = bullet, level = level)
        }
        // A list keeps together however a slide has spaced it.
        val listContinues = label != null && next != null && listMarker(next.first) != null
        val gapBelow = if (listContinues) 0f else next?.let { it.box.top - lines.last().box.bottom } ?: 0f
        // Where a slide's text box happens to sit says nothing about indentation.
        val indent = if (!slide && label == null && heading == null && align == WordAlign.Left && insetLeft >= em * MIN_INDENT_EMS) insetLeft else 0f
        val style = WordParagraphStyle(
            outlineLevel = heading,
            align = align,
            leftTwips = (indent * TWIPS_PER_POINT).roundToInt(),
            afterTwips = if (gapBelow > em * BLANK_LINE_MIN_GAP_EMS) (gapBelow * TWIPS_PER_POINT).roundToInt() else 0,
        )
        val parts = lines.map { it.parts }
        val inlines = if (label == null) inlinesOf(parts, bodyEm) else {
            // A marker set off by a wide gap is a whole part of the first line, not just a word of it.
            val onItsOwn = lines.first().parts.first().words.size == 1 && lines.first().parts.size > 1
            if (onItsOwn) inlinesOf(listOf(lines.first().parts.drop(1)) + parts.drop(1), bodyEm) else inlinesOf(parts, bodyEm, skipFirstWord = true)
        }
        return WordParagraph(style, WordRunStyle(), inlines, label)
    }
}

/**
 * Pages' contents in order, with a sentence running over a page break put back together when both
 * pages were chosen.
 */
internal fun joinPages(pages: List<PdfPageLayout>): List<PagePiece> {
    val out = ArrayList<PagePiece>()
    pages.forEachIndexed { index, page ->
        val previous = pages.getOrNull(index - 1)
        // One text box to a slide, so that each can be moved or kept without the rest.
        if (previous != null && (previous.slide || page.slide)) out += SeparatorPiece
        val continuing = previous?.endsMidSentence == true && out.lastOrNull() is ParagraphPiece &&
            (page.pieces.firstOrNull() as? ParagraphPiece)?.startsLowerCase() == true
        if (continuing) {
            val end = out.removeAt(out.lastIndex) as ParagraphPiece
            val start = page.pieces.first() as ParagraphPiece
            out += ParagraphPiece(joined(end.paragraph, start.paragraph), end.bodyWidthTwips)
            out += page.pieces.drop(1)
        } else {
            out += page.pieces
        }
    }
    return out
}

private fun ParagraphPiece.startsLowerCase(): Boolean =
    (paragraph.inlines.firstOrNull() as? WordText)?.text?.firstOrNull()?.isLowerCase() == true

private fun joined(first: WordParagraph, second: WordParagraph): WordParagraph {
    val space: List<WordInline> = listOf(WordText(" ", WordRunStyle(), null))
    return WordParagraph(first.style, first.markStyle, first.inlines + space + second.inlines, first.label)
}
