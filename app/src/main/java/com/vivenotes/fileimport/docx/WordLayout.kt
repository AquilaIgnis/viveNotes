package com.vivenotes.fileimport.docx

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import com.vivenotes.richtext.createEquationRenderer
import io.ratex.RaTeXRenderer
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

// Layout runs in tenths of a point. A twip is half a unit, and at 720 to the inch a line height
// rounded to a whole unit is off by less than anyone can see, even added up over a page.
internal const val UNITS_PER_INCH = 720f
private const val UNITS_PER_HALF_POINT = 5f

internal fun twipsToUnits(twips: Int): Int = (twips / 2f).roundToInt()

private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
private const val OBJECT = "￼"
private const val MIN_TEXT_WIDTH = 240
private const val MIN_TAB_UNITS = 1f

/** Drawing state spans reach, since a span is handed a canvas and nothing else. */
internal class RenderState(val media: DocxMedia) {
    /** Device pixels per layout unit in the render under way. */
    var scale: Float = 1f
}

/** The page numbers a header's fields show. */
internal class FieldValues(val page: String, val total: String)

/**
 * Something measured that can be drawn in pieces.
 *
 * Paragraphs, tables and text boxes all look the same to the paginator: a height, the `y`s they may be
 * cut at, and a way to draw themselves. Drawing is always into the block's own coordinates, clipped by
 * the caller to the slice this page holds.
 */
internal abstract class LaidBlock {
    abstract val height: Int

    /** Where a page break may fall, ascending and strictly inside the block. */
    open val cuts: IntArray get() = NO_CUTS

    /** Height of what is repeated above a continuation: a table's header rows. */
    open val repeatHeight: Int get() = 0

    var spaceBefore: Int = 0
    var spaceAfter: Int = 0
    var keepWithNext: Boolean = false
    var pageBreakBefore: Boolean = false
    var columnBreakBefore: Boolean = false

    /** Word had a page start here the last time it laid this document out. */
    var wordBrokeHere: Boolean = false

    /** Set on the paragraph or table this came from; a band for a floating picture has none. */
    open val source: WordBlock? get() = null

    /** A paragraph with nothing visible in it, which holds space and no content. */
    open val isBlank: Boolean get() = false

    abstract fun draw(canvas: Canvas, from: Int, to: Int)

    open fun drawRepeat(canvas: Canvas) = Unit
}

internal val NO_CUTS = IntArray(0)

/** A stack of blocks: a table cell's contents, a text box's, a header's. */
internal class LaidFlow(blocks: List<LaidBlock>) {
    private val items: List<Pair<LaidBlock, Int>>
    val height: Int

    init {
        var y = 0
        val placed = ArrayList<Pair<LaidBlock, Int>>(blocks.size)
        for (block in blocks) {
            y += block.spaceBefore
            placed += block to y
            y += block.height + block.spaceAfter
        }
        items = placed
        height = y
    }

    /** Where the last block with anything in it ends — trailing empty paragraphs are only spacing. */
    val visibleHeight: Int
        get() = items.lastOrNull { !it.first.isBlank }?.let { (block, top) -> top + block.height } ?: 0

    /** Between items, and wherever each item could itself be cut. */
    val cuts: IntArray by lazy {
        val out = ArrayList<Int>()
        for ((block, top) in items) {
            if (top > 0) out += top
            block.cuts.forEach { out += top + it }
        }
        out.filter { it in 1 until height }.distinct().sorted().toIntArray()
    }

    fun draw(canvas: Canvas, from: Int = 0, to: Int = height) {
        for ((block, top) in items) {
            val bottom = top + block.height
            if (bottom <= from || top >= to) continue
            val count = canvas.save()
            canvas.translate(0f, top.toFloat())
            block.draw(canvas, (from - top).coerceAtLeast(0), (to - top).coerceAtMost(block.height))
            canvas.restoreToCount(count)
        }
    }
}

/** An object drawn in its own rectangle, inline or floating. */
/** How much wider than its box text that must not wrap may run. */
private const val NO_WRAP_ROOM = 1.5f

internal abstract class LaidBox(val width: Int, val height: Int) {
    abstract fun draw(canvas: Canvas)
}

/** One slice of a block on a sheet. */
internal class SheetPiece(val block: LaidBlock, val x: Int, val y: Int, val from: Int, val to: Int)

internal class SheetFloat(val box: LaidBox, val x: Int, val y: Int, val behind: Boolean, val rotationDegrees: Float = 0f)

internal class PlacedFlow(val flow: LaidFlow, val x: Int, val y: Int)

/** One page of the document, in layout units. */
internal class DocxSheet(
    val widthUnits: Int,
    val heightUnits: Int,
    val bodyWidthTwips: Int,
    val pieces: List<SheetPiece>,
    val floats: List<SheetFloat>,
    var header: PlacedFlow? = null,
    var footer: PlacedFlow? = null,
) {
    val widthDp: Float get() = widthUnits / UNITS_PER_INCH * com.vivenotes.model.PageStyle.DP_PER_INCH
    val heightDp: Float get() = heightUnits / UNITS_PER_INCH * com.vivenotes.model.PageStyle.DP_PER_INCH

    /** What this page contributes to an import as objects — see [PagePiece]. */
    fun objectPieces(): List<PagePiece> = pieces.mapNotNull { piece ->
        when (val source = piece.block.source) {
            is WordParagraph -> if (piece.from == 0) ParagraphPiece(source, bodyWidthTwips) else null
            is WordTable -> (piece.block as LaidTable).rowsStartingIn(piece.from, piece.to)
                ?.let { (first, end) -> TablePiece(source, first, end, bodyWidthTwips) }
            is WordBreak, null -> null
        }
    }
}

/** Turns a [WordDocument] into sheets. Holds paints, so it belongs to one thread at a time. */
internal class WordLayoutEngine(
    private val context: Context,
    private val document: WordDocument,
    private val media: DocxMedia,
    fontTable: Map<String, String> = emptyMap(),
) {
    private val fonts = WordFonts(fontTable)
    val state = RenderState(media)

    private val measurePaint = TextPaint(DOCX_PAINT_FLAGS).apply {
        color = Color.BLACK
        hinting = Paint.HINTING_OFF
    }

    // --- paragraphs ----------------------------------------------------------------------------

    private inner class ParagraphBuild(
        val paragraph: WordParagraph,
        val width: Int,
        val maxHeight: Int,
        val ink: Int,
        val fields: FieldValues?,
    ) {
        val style = paragraph.style
        val left = twipsToUnits(style.leftTwips)
        val right = twipsToUnits(style.rightTwips)
        val firstLine = left + twipsToUnits(style.firstLineTwips)
        val indentOrigin = min(left, firstLine)
        val textWidth = (width - indentOrigin - right).coerceAtLeast(MIN_TEXT_WIDTH)
        val text = SpannableStringBuilder()
        val tabs = ArrayList<PendingTab>()
        val floats = ArrayList<Pair<LaidBox, WordAnchor>>()
        val bands = ArrayList<LaidBlock>()
        var displayEquation = false

        fun appendRun(value: String, runStyle: WordRunStyle) {
            if (value.isEmpty()) return
            if (runStyle.smallCaps) {
                appendSmallCaps(value, runStyle)
                return
            }
            appendPlain(value, runStyle)
        }

        private fun appendSmallCaps(value: String, runStyle: WordRunStyle) {
            var start = 0
            while (start < value.length) {
                val lower = value[start].isLowerCase()
                var end = start
                while (end < value.length && value[end].isLowerCase() == lower) end++
                val piece = value.substring(start, end)
                val shrunk = if (lower) (runStyle.halfPoints * SMALL_CAPS_SCALE).roundToInt() else runStyle.halfPoints
                appendPlain(piece.uppercase(), runStyle.copy(halfPoints = shrunk))
                start = end
            }
        }

        private fun appendPlain(value: String, runStyle: WordRunStyle) {
            val face = fonts.face(runStyle.font, runStyle.bold, runStyle.italic)
            val size = runStyle.halfPoints * UNITS_PER_HALF_POINT * face.sizeScale
            val color = runStyle.colorArgb ?: ink
            val start = text.length
            text.append(value)
            text.setSpan(WordRunSpan(face, size, runStyle, color), start, text.length, SPAN_FLAGS)
        }

        fun appendSpan(span: ReplacementSpan) {
            val start = text.length
            text.append(OBJECT)
            text.setSpan(span, start, text.length, SPAN_FLAGS)
        }

        fun appendTab(tabStyle: WordRunStyle, absolute: WordAlign? = null) {
            val span = TabSpan(tabStyle.colorArgb ?: ink)
            tabs += PendingTab(text.length, span, absolute)
            appendRunSpan(span, tabStyle)
        }

        /** A tab takes its height from its own run: an object span alone would measure nothing. */
        private fun appendRunSpan(span: ReplacementSpan, runStyle: WordRunStyle) {
            val face = fonts.face(runStyle.font, runStyle.bold, runStyle.italic)
            val size = runStyle.halfPoints * UNITS_PER_HALF_POINT * face.sizeScale
            val start = text.length
            text.append(OBJECT)
            text.setSpan(WordRunSpan(face, size, runStyle, runStyle.colorArgb ?: ink), start, text.length, SPAN_FLAGS)
            text.setSpan(span, start, text.length, SPAN_FLAGS)
        }

        /** Word's stops are measured from the margin, so they are resolved in that frame. */
        fun resolveTabs() {
            if (tabs.isEmpty()) return
            var x = firstLine.toFloat()
            var segmentStart = 0
            tabs.forEachIndexed { index, tab ->
                x += Layout.getDesiredWidth(text, segmentStart, tab.index, measurePaint)
                val nextEnd = tabs.getOrNull(index + 1)?.index ?: text.length
                val following = Layout.getDesiredWidth(text, tab.index + 1, nextEnd, measurePaint)
                val stop = nextStop(x, tab.absolute)
                val target = when (stop.kind) {
                    WordTabKind.Left, WordTabKind.Bar -> stop.position
                    WordTabKind.Center -> stop.position - following / 2f
                    WordTabKind.Right -> stop.position - following
                    WordTabKind.Decimal -> stop.position - beforeDecimal(tab.index + 1, nextEnd)
                }
                val tabWidth = max(target - x, MIN_TAB_UNITS)
                tab.span.width = tabWidth.roundToInt()
                tab.span.leader = stop.leader
                x += tabWidth
                segmentStart = tab.index + 1
                // Past the line's end the tab lands on the next line, measured from its start.
                if (x > left + textWidth) x = left + (x - (left + textWidth))
            }
        }

        private fun beforeDecimal(start: Int, end: Int): Float {
            val point = (start until end).firstOrNull { text[it] == '.' } ?: end
            return Layout.getDesiredWidth(text, start, point, measurePaint)
        }

        private inner class Stop(val position: Float, val kind: WordTabKind, val leader: Char?)

        private fun nextStop(x: Float, absolute: WordAlign?): Stop {
            if (absolute != null) {
                val position = when (absolute) {
                    WordAlign.Center -> width / 2f
                    WordAlign.Right -> width.toFloat()
                    else -> 0f
                }
                val kind = when (absolute) {
                    WordAlign.Center -> WordTabKind.Center
                    WordAlign.Right -> WordTabKind.Right
                    else -> WordTabKind.Left
                }
                return Stop(position, kind, null)
            }
            style.tabs.firstOrNull { twipsToUnits(it.positionTwips) > x + TAB_EPSILON }?.let { custom ->
                return Stop(twipsToUnits(custom.positionTwips).toFloat(), custom.kind, custom.leader)
            }
            // A hanging indent is a stop of its own: it is where a list item's text starts.
            if (firstLine < left && x < left - TAB_EPSILON) return Stop(left.toFloat(), WordTabKind.Left, null)
            val step = twipsToUnits(document.settings.defaultTabTwips).coerceAtLeast(1)
            val next = (ceil((x + TAB_EPSILON) / step) * step)
            return Stop(next, WordTabKind.Left, null)
        }
    }

    private class PendingTab(val index: Int, val span: TabSpan, val absolute: WordAlign?)

    private suspend fun layoutParagraph(build: ParagraphBuild): LaidParagraph {
        val paragraph = build.paragraph
        val mark = paragraph.markStyle

        paragraph.label?.let { label ->
            build.appendRun(label.text, label.style)
            if (label.followedByTab) build.appendTab(label.style) else if (label.followedBySpace) build.appendRun(" ", label.style)
        }

        for (inline in paragraph.inlines) {
            when (inline) {
                is WordText -> build.appendRun(inline.text, inline.style)
                is WordTab -> build.appendTab(inline.style, inline.absolute)
                is WordLineBreak -> build.appendRun("\n", inline.style)
                is WordField -> build.appendRun(
                    when (inline.kind) {
                        WordFieldKind.PageNumber -> build.fields?.page ?: "1"
                        WordFieldKind.PageCount -> build.fields?.total ?: "1"
                    },
                    inline.style,
                )
                is WordNoteRef -> build.appendRun(
                    inline.id.toString(),
                    inline.style.copy(script = WordScript.Super),
                )
                is WordEquation -> appendEquation(build, inline)
                is WordBoxed -> appendBoxed(build, inline)
                WordRenderedBreak -> Unit
            }
        }

        // An empty paragraph still has a height: its mark's. Only the height: an underlined mark
        // must not draw an underlined space.
        val text = build.text
        val dominant = dominantFont(paragraph) ?: mark.font
        if (text.isEmpty()) build.appendRun(" ", mark.copy(underline = false, strike = false, highlightArgb = null))

        build.resolveTabs()

        val lineFactor = fonts.face(dominant, false, false).lineHeightFactor
        text.setSpan(WordLineHeight(paragraph.style, lineFactor), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val align = if (build.displayEquation) WordAlign.Center else paragraph.style.align
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, measurePaint, build.textWidth)
            .setAlignment(alignmentFor(align, paragraph.style.rightToLeft))
            .setTextDirection(
                if (paragraph.style.rightToLeft) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR,
            )
            .setIndents(
                intArrayOf(build.firstLine - build.indentOrigin, build.left - build.indentOrigin),
                null,
            )
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(
                if (align == WordAlign.Justify) LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE,
            )
            .setUseLineSpacingFromFallbacks(false)
            .build()

        return LaidParagraph(paragraph, layout, build.indentOrigin, build.width, build.floats).apply {
            bandsBefore = build.bands
            keepWithNext = paragraph.style.keepNext
            pageBreakBefore = paragraph.style.pageBreakBefore || paragraph.breakBefore == WordBreakBefore.Page
            columnBreakBefore = paragraph.breakBefore == WordBreakBefore.Column
            wordBrokeHere = paragraph.inlines.firstOrNull() === WordRenderedBreak
        }
    }

    private fun alignmentFor(align: WordAlign, rightToLeft: Boolean): Layout.Alignment = when (align) {
        WordAlign.Center -> Layout.Alignment.ALIGN_CENTER
        WordAlign.Right -> if (rightToLeft) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
        WordAlign.Left, WordAlign.Justify -> if (rightToLeft) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
    }

    /** The font most of a paragraph is set in, whose line height decides the paragraph's. */
    private fun dominantFont(paragraph: WordParagraph): String? = paragraph.inlines
        .filterIsInstance<WordText>()
        .groupBy { it.style.font }
        .maxByOrNull { (_, runs) -> runs.sumOf { it.text.length } }
        ?.key

    private suspend fun appendEquation(build: ParagraphBuild, equation: WordEquation) {
        if (equation.display) build.displayEquation = true
        val face = fonts.face(equation.style.font, false, false)
        val size = equation.style.halfPoints * UNITS_PER_HALF_POINT * face.sizeScale
        val renderer = equation.latex.takeIf { it.isNotEmpty() }?.let { latex ->
            runCatching {
                createEquationRenderer(context, latex, size, equation.style.colorArgb ?: build.ink)
            }.getOrNull()
        }
        if (renderer != null) build.appendSpan(EquationBoxSpan(renderer)) else build.appendRun(equation.plainText, equation.style)
    }

    private suspend fun appendBoxed(build: ParagraphBuild, boxed: WordBoxed) {
        val box = layoutBox(boxed, build.width, build.maxHeight, build.fields)
        val anchor = boxed.anchor
        when {
            anchor == null -> build.appendSpan(InlineBoxSpan(box))
            anchor.wrap == WordWrap.None || anchor.behindText -> build.floats += box to anchor
            // Text flowing around a float is not something `StaticLayout` can do; a band of its own
            // keeps every word of the page visible, at the price of a little more length.
            else -> build.bands += LaidBand(box, horizontalPosition(anchor, box, build.width))
        }
    }

    private fun horizontalPosition(anchor: WordAnchor, box: LaidBox, width: Int): Int =
        anchor.horizontalOffsetTwips?.let(::twipsToUnits)?.coerceIn(0, max(0, width - box.width))
            ?: when (anchor.horizontalAlign) {
                "center" -> (width - box.width) / 2
                "right", "outside" -> width - box.width
                else -> 0
            }

    // --- boxes ---------------------------------------------------------------------------------

    private suspend fun layoutBox(boxed: WordBoxed, maxWidth: Int, maxHeight: Int, fields: FieldValues?): LaidBox =
        when (boxed) {
            is WordPicture -> {
                val (width, height) = fittedPicture(boxed, maxWidth, maxHeight)
                PictureBox(state, width, height, boxed.part, boxed.crop)
            }
            is WordPlaceholder -> PlaceholderBox(twipsToUnits(boxed.widthTwips), twipsToUnits(boxed.heightTwips).coerceAtLeast(1))
            is WordShape -> layoutShape(boxed, maxHeight, fields)
            is WordGroup -> GroupBox(
                twipsToUnits(boxed.widthTwips),
                twipsToUnits(boxed.heightTwips),
                boxed.children.map { child ->
                    Triple(layoutBox(child.boxed, maxWidth, maxHeight, fields), twipsToUnits(child.xTwips), twipsToUnits(child.yTwips))
                },
            )
        }

    /** A picture shrunk, keeping its shape, to fit what is around it. */
    private fun fittedPicture(picture: WordPicture, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val width = twipsToUnits(picture.widthTwips).coerceAtLeast(1)
        val height = twipsToUnits(picture.heightTwips).coerceAtLeast(1)
        val scale = min(1f, min(maxWidth.toFloat() / width, maxHeight.toFloat() / height))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    private suspend fun layoutShape(shape: WordShape, maxHeight: Int, fields: FieldValues?): LaidBox {
        val width = twipsToUnits(shape.widthTwips).coerceAtLeast(1)
        val insetLeft = twipsToUnits(shape.insetLeftTwips)
        val insetTop = twipsToUnits(shape.insetTopTwips)
        val insetRight = twipsToUnits(shape.insetRightTwips)
        val insetBottom = twipsToUnits(shape.insetBottomTwips)
        val fitted = (width - insetLeft - insetRight).coerceAtLeast(MIN_TEXT_WIDTH)
        // Given room to spare, because the document's own font may have been narrower than ours.
        val inner = if (shape.noWrap) (fitted * NO_WRAP_ROOM).roundToInt() else fitted
        val ink = inkOn(shape.fillArgb)
        val flow = if (shape.blocks.isEmpty()) {
            null
        } else {
            LaidFlow(layoutBlocks(shape.blocks, inner, maxHeight, ink, fields, promoteFloats = false))
        }
        // A box never clips its own text, since the document's font may have been narrower than
        // ours; the empty paragraphs Word lets run out of the bottom are not text.
        val textHeight = flow?.visibleHeight ?: 0
        val needed = textHeight + insetTop + insetBottom
        val height = max(twipsToUnits(shape.heightTwips), needed).coerceAtLeast(1)
        val free = height - needed
        val textTop = insetTop + when (shape.textAlign) {
            WordCellAlign.Top -> 0
            WordCellAlign.Center -> free / 2
            WordCellAlign.Bottom -> free
        }
        // The extra room goes on whichever side the text is not aligned to, so it stays where it was put.
        val spare = inner - fitted
        val textLeft = insetLeft - when ((shape.blocks.firstOrNull() as? WordParagraph)?.style?.align) {
            WordAlign.Center -> spare / 2
            WordAlign.Right -> spare
            else -> 0
        }
        return ShapeBox(width, height, shape, flow, textLeft, textTop)
    }

    // --- tables --------------------------------------------------------------------------------

    private fun inkOn(backgroundArgb: Int?): Int {
        backgroundArgb ?: return Color.BLACK
        val r = Color.red(backgroundArgb)
        val g = Color.green(backgroundArgb)
        val b = Color.blue(backgroundArgb)
        return if ((0.299f * r + 0.587f * g + 0.114f * b) < 110f) Color.WHITE else Color.BLACK
    }

    private suspend fun layoutTable(
        table: WordTable,
        available: Int,
        maxHeight: Int,
        ink: Int,
        fields: FieldValues?,
    ): LaidTable {
        val columnCount = table.columnCount
        val columns = tableColumns(table, columnCount, available)
        val columnX = IntArray(columnCount + 1)
        for (index in 0 until columnCount) columnX[index + 1] = columnX[index] + columns[index]
        val width = columnX[columnCount]

        val cellMargin = table.rows.firstOrNull()?.cells?.firstOrNull()?.let { twipsToUnits(it.marginLeftTwips) } ?: 0
        val indent = twipsToUnits(table.indentTwips) - if (document.settings.compatibilityMode < 15) cellMargin else 0
        val left = when (table.align) {
            WordAlign.Center -> (available - width) / 2
            WordAlign.Right -> available - width
            else -> indent
        }

        val rows = table.rows.map { row ->
            var column = row.gridBefore
            val cells = row.cells.map { cell ->
                coroutineContext.ensureActive()
                val x = columnX[column.coerceAtMost(columnCount)]
                val end = columnX[(column + cell.gridSpan).coerceAtMost(columnCount)]
                val inner = (end - x - twipsToUnits(cell.marginLeftTwips) - twipsToUnits(cell.marginRightTwips))
                    .coerceAtLeast(MIN_TEXT_WIDTH)
                val cellInk = cell.shadingArgb?.let(::inkOn) ?: ink
                val flow = if (cell.verticalMerge == WordVerticalMerge.Continue) {
                    LaidFlow(emptyList())
                } else {
                    LaidFlow(layoutBlocks(cell.blocks, inner, maxHeight, cellInk, fields, promoteFloats = false))
                }
                val laid = LaidCell(cell, column, x, end - x, flow)
                // Past the grid, cells pile up at its edge rather than run off the end of it.
                column = (column + cell.gridSpan).coerceAtMost(columnCount)
                laid
            }
            LaidRow(row, cells)
        }
        return LaidTable(table, left, width, columnCount, rows)
    }

    private fun tableColumns(table: WordTable, columnCount: Int, available: Int): IntArray {
        val widths = table.columnTwips(columnCount)
        val grid = IntArray(columnCount) { twipsToUnits(widths[it]) }
        val total = grid.sum()
        if (total <= 0) {
            val requested = table.widthTwips?.let(::twipsToUnits) ?: available
            val target = table.widthPercent?.let { (available * it).roundToInt() } ?: min(requested, available)
            return IntArray(columnCount) { target / columnCount }
        }
        val target = table.widthPercent?.let { (available * it).roundToInt() }
        val scale = when {
            target != null -> target.toFloat() / total
            total > available * TABLE_OVERFLOW_TOLERANCE -> available.toFloat() / total
            else -> 1f
        }
        return IntArray(columnCount) { (grid[it] * scale).roundToInt() }
    }

    // --- flows ---------------------------------------------------------------------------------

    private suspend fun layoutBlocks(
        blocks: List<WordBlock>,
        width: Int,
        maxHeight: Int,
        ink: Int,
        fields: FieldValues?,
        promoteFloats: Boolean,
    ): List<LaidBlock> {
        val out = ArrayList<LaidBlock>(blocks.size)
        blocks.forEachIndexed { index, block ->
            coroutineContext.ensureActive()
            when (block) {
                is WordParagraph -> {
                    val build = ParagraphBuild(block, width, maxHeight, ink, fields)
                    val laid = layoutParagraph(build)
                    val before = blocks.getOrNull(index - 1) as? WordParagraph
                    val after = blocks.getOrNull(index + 1) as? WordParagraph
                    val style = block.style
                    laid.spaceBefore = if (style.contextualSpacing && before?.style?.name == style.name) 0 else twipsToUnits(style.beforeTwips)
                    laid.spaceAfter = if (style.contextualSpacing && after?.style?.name == style.name) 0 else twipsToUnits(style.afterTwips)
                    laid.drawsOwnFloats = !promoteFloats
                    out += laid.bandsBefore
                    out += laid
                }
                is WordTable -> out += layoutTable(block, width, maxHeight, ink, fields)
                is WordBreak -> out += LaidBreak().apply {
                    pageBreakBefore = block.kind == WordBreakBefore.Page
                    columnBreakBefore = block.kind == WordBreakBefore.Column
                }
            }
        }
        return out
    }

    private suspend fun layoutFlow(blocks: List<WordBlock>, width: Int, maxHeight: Int, fields: FieldValues?): LaidFlow =
        LaidFlow(layoutBlocks(blocks, width, maxHeight, Color.BLACK, fields, promoteFloats = false))

    // --- pagination ----------------------------------------------------------------------------

    private inner class Draft(val section: WordSection, val pageNumber: Int) {
        val pieces = ArrayList<SheetPiece>()
        val floats = ArrayList<SheetFloat>()
    }

    /** The body's geometry on a sheet: its frame depends on how tall that sheet's header came out. */
    private class Frames(val left: Int, val width: Int, val gap: Int, val columns: Int, val top: Int, val bottom: Int) {
        fun x(column: Int): Int = left + column * (width + gap)
    }

    suspend fun layout(): List<DocxSheet> {
        val drafts = ArrayList<Draft>()
        var pageNumber = 0
        var draft: Draft? = null
        var frames: Frames? = null
        var column = 0
        var y = 0
        var frameTop = 0
        var afterAutoBreak = false

        suspend fun beginSheet(section: WordSection, restartAt: Int? = null) {
            pageNumber = restartAt ?: (pageNumber + 1)
            val firstOfSection = drafts.lastOrNull()?.section !== section
            val next = Draft(section, pageNumber)
            drafts += next
            draft = next
            val page = section.page
            val textWidth = page.widthTwips - page.leftTwips - page.rightTwips
            val headerBlocks = headerFor(section, section.headers, pageNumber, firstOfSection)
            val footerBlocks = headerFor(section, section.footers, pageNumber, firstOfSection)
            val headerHeight = headerBlocks?.let { layoutFlow(it, twipsToUnits(textWidth), twipsToUnits(page.heightTwips), null).height } ?: 0
            val footerHeight = footerBlocks?.let { layoutFlow(it, twipsToUnits(textWidth), twipsToUnits(page.heightTwips), null).height } ?: 0
            val top = max(twipsToUnits(page.topTwips), twipsToUnits(page.headerTwips) + headerHeight)
            val bottom = twipsToUnits(page.heightTwips) -
                max(twipsToUnits(page.bottomTwips), twipsToUnits(page.footerTwips) + footerHeight)
            val columns = section.columns.coerceIn(1, MAX_SECTION_COLUMNS)
            val gap = twipsToUnits(section.columnGapTwips)
            val columnWidth = (twipsToUnits(textWidth) - gap * (columns - 1)) / columns
            frames = Frames(twipsToUnits(page.leftTwips), columnWidth, gap, columns, top, bottom)
            column = 0
            y = top
            frameTop = top
        }

        suspend fun nextFrame(section: WordSection) {
            val current = frames!!
            afterAutoBreak = true
            if (column + 1 < current.columns) {
                column++
                y = current.top
                frameTop = current.top
            } else {
                beginSheet(section)
                afterAutoBreak = true
            }
        }

        suspend fun newPage(section: WordSection) {
            beginSheet(section)
            afterAutoBreak = false
        }

        for (section in document.sections) {
            coroutineContext.ensureActive()
            val previous = draft
            val sameSheet = previous != null && section.start == WordSectionStart.Continuous &&
                previous.section.page.widthTwips == section.page.widthTwips &&
                previous.section.page.heightTwips == section.page.heightTwips
            when {
                previous == null -> beginSheet(section, section.pageNumberStart ?: 1)
                sameSheet -> {
                    // Keeps the page and goes on from where the last section stopped, under a new
                    // frame; the old draft stays the sheet, with this section's geometry.
                    val current = frames!!
                    val page = section.page
                    val textWidth = page.widthTwips - page.leftTwips - page.rightTwips
                    val columns = section.columns.coerceIn(1, MAX_SECTION_COLUMNS)
                    val gap = twipsToUnits(section.columnGapTwips)
                    val columnWidth = (twipsToUnits(textWidth) - gap * (columns - 1)) / columns
                    frames = Frames(twipsToUnits(page.leftTwips), columnWidth, gap, columns, current.top, current.bottom)
                    column = 0
                    frameTop = y
                }
                section.start == WordSectionStart.NextColumn -> nextFrame(section)
                else -> beginSheet(section, section.pageNumberStart)
            }

            val page = section.page
            val current = frames!!
            val blocks = layoutBlocks(
                section.blocks,
                current.width,
                current.bottom - current.top,
                Color.BLACK,
                null,
                promoteFloats = true,
            )

            fun keepNextFits(index: Int): Boolean {
                var needed = 0
                var at = index
                while (at < blocks.size) {
                    val block = blocks[at]
                    needed += block.spaceBefore
                    if (block.keepWithNext && at + 1 < blocks.size) {
                        needed += block.height + block.spaceAfter
                        at++
                    } else {
                        needed += block.cuts.firstOrNull() ?: block.height
                        break
                    }
                }
                val frame: Frames = frames
                return y + needed <= frame.bottom || needed > frame.bottom - frame.top
            }

            for (index in blocks.indices) {
                coroutineContext.ensureActive()
                val block = blocks[index]
                if (block.pageBreakBefore && (draft!!.pieces.isNotEmpty() || y > frames.top)) newPage(section)
                if (block.columnBreakBefore && y > frameTop) nextFrame(section)
                if (block.keepWithNext && y > frameTop && !keepNextFits(index)) nextFrame(section)
                // Where Word itself broke the page, and this page is well on its way, take its word
                // for it: a font we only approximate then drifts by a few lines and no further. A
                // stale marker can do no worse than end one page a little early.
                if (block.wordBrokeHere && column + 1 == frames.columns &&
                    y - frameTop > (frames.bottom - frameTop) * WORD_BREAK_MIN_FILL
                ) {
                    nextFrame(section)
                }

                var from = 0
                var first = true
                while (true) {
                    val frame: Frames = frames
                    val repeat = if (from > 0) block.repeatHeight else 0
                    val before = if (first && !(y == frameTop && afterAutoBreak)) block.spaceBefore else 0
                    val top = y + before
                    val room = frame.bottom - top - repeat
                    val remaining = block.height - from
                    // An empty line that lands on a page break is spent by the break. Kept, it starts
                    // the next page a line low, and that pushes everything after it.
                    if (block.isBlank && (remaining > room || (y == frameTop && afterAutoBreak))) {
                        break
                    }
                    fun add(to: Int) {
                        val sheet = draft!!
                        sheet.pieces += SheetPiece(block, frame.x(column), top, from, to)
                        if (from == 0 && block is LaidParagraph) {
                            block.floats.forEach { (box, anchor) ->
                                sheet.floats += placeFloat(box, anchor, page, frame, column, top)
                            }
                        }
                    }
                    if (remaining <= room) {
                        add(block.height)
                        y = top + repeat + remaining + block.spaceAfter
                        afterAutoBreak = false
                        break
                    }
                    val cut = lastCutWithin(block.cuts, from, from + room)
                    if (cut != null) {
                        add(cut)
                        from = cut
                        first = false
                        nextFrame(section)
                        continue
                    }
                    if (y > frame.top) {
                        nextFrame(section)
                        continue
                    }
                    // Nothing fits even on an empty page: cut where the page ends rather than lose it.
                    val forced = (from + room.coerceAtLeast(MIN_FORCED_CUT)).coerceAtMost(block.height)
                    add(forced)
                    from = forced
                    first = false
                    if (from >= block.height) {
                        y = frame.bottom
                        break
                    }
                    nextFrame(section)
                }
            }
        }

        return finishSheets(drafts)
    }

    private fun lastCutWithin(cuts: IntArray, from: Int, limit: Int): Int? {
        var answer: Int? = null
        for (cut in cuts) {
            if (cut > limit) break
            if (cut > from) answer = cut
        }
        return answer
    }

    private fun placeFloat(
        box: LaidBox,
        anchor: WordAnchor,
        page: WordPageGeometry,
        frame: Frames,
        column: Int,
        paragraphTop: Int,
    ): SheetFloat {
        val pageWidth = twipsToUnits(page.widthTwips)
        val pageHeight = twipsToUnits(page.heightTwips)
        val (originX, spanX) = when (anchor.horizontalFrom) {
            "page" -> 0 to pageWidth
            "margin", "leftMargin", "rightMargin", "insideMargin", "outsideMargin" -> twipsToUnits(page.leftTwips) to (pageWidth - twipsToUnits(page.leftTwips) - twipsToUnits(page.rightTwips))
            else -> frame.x(column) to frame.width
        }
        val (originY, spanY) = when (anchor.verticalFrom) {
            "page" -> 0 to pageHeight
            "margin", "topMargin", "bottomMargin", "insideMargin", "outsideMargin" -> frame.top to (frame.bottom - frame.top)
            else -> paragraphTop to (frame.bottom - paragraphTop)
        }
        fun aligned(value: String?, origin: Int, span: Int, size: Int): Int = when (value) {
            "center" -> origin + (span - size) / 2
            "right", "bottom", "outside" -> origin + span - size
            else -> origin
        }
        val x = anchor.horizontalOffsetTwips?.let { originX + twipsToUnits(it) }
            ?: aligned(anchor.horizontalAlign, originX, spanX, box.width)
        val y = anchor.verticalOffsetTwips?.let { originY + twipsToUnits(it) }
            ?: aligned(anchor.verticalAlign, originY, spanY, box.height)
        return SheetFloat(box, x, y, anchor.behindText, anchor.rotationDegrees)
    }

    private fun headerFor(section: WordSection, set: WordHeaderSet, pageNumber: Int, firstSheetOfSection: Boolean): List<WordBlock>? {
        val even = document.settings.evenAndOddHeaders && pageNumber % 2 == 0
        return when {
            section.titlePage && firstSheetOfSection -> set.first
            even -> set.even ?: set.default
            else -> set.default
        }?.takeIf { it.isNotEmpty() }
    }

    /** Headers go on last, once there is a page count for them to show. */
    private suspend fun finishSheets(all: List<Draft>): List<DocxSheet> {
        // A page with nothing on it at the very end is the shadow of a final page break.
        val drafts = all.dropLastWhile { draft ->
            draft !== all.first() && draft.floats.isEmpty() && draft.pieces.all { it.block.isBlank }
        }
        var previous: WordSection? = null
        return drafts.map { draft ->
            coroutineContext.ensureActive()
            val section = draft.section
            val page = section.page
            val firstOfSection = section !== previous
            previous = section
            val textWidth = page.widthTwips - page.leftTwips - page.rightTwips
            val widthUnits = twipsToUnits(page.widthTwips)
            val heightUnits = twipsToUnits(page.heightTwips)
            val fields = FieldValues(
                formatNumber(draft.pageNumber, section.pageNumberFormat),
                drafts.size.toString(),
            )
            val sheet = DocxSheet(widthUnits, heightUnits, textWidth, draft.pieces, draft.floats)
            headerFor(section, section.headers, draft.pageNumber, firstOfSection)?.let { blocks ->
                val flow = layoutFlow(blocks, twipsToUnits(textWidth), heightUnits, fields)
                sheet.header = PlacedFlow(flow, twipsToUnits(page.leftTwips), twipsToUnits(page.headerTwips))
            }
            headerFor(section, section.footers, draft.pageNumber, firstOfSection)?.let { blocks ->
                val flow = layoutFlow(blocks, twipsToUnits(textWidth), heightUnits, fields)
                val y = heightUnits - twipsToUnits(page.footerTwips) - flow.height
                sheet.footer = PlacedFlow(flow, twipsToUnits(page.leftTwips), y)
            }
            sheet
        }
    }

    private companion object {
        const val SMALL_CAPS_SCALE = 0.8f

        /** How full a page must be before Word's own break is believed over ours. */
        const val WORD_BREAK_MIN_FILL = 0.6f
        const val TAB_EPSILON = 0.5f

        /** A table a sliver wider than its room is what Word computed; only a real overflow is scaled. */
        const val TABLE_OVERFLOW_TOLERANCE = 1.02f
        const val MIN_FORCED_CUT = 200

        /** More columns than this to a page leaves none wide enough to read; Word's own limit is lower. */
        const val MAX_SECTION_COLUMNS = 16
    }
}

/** A run's look. One span instead of a stack of platform ones: sizes here are fractional. */
private class WordRunSpan(
    private val face: WordFace,
    private val size: Float,
    private val style: WordRunStyle,
    private val color: Int,
) : MetricAffectingSpan() {
    private fun applyMetrics(paint: TextPaint) {
        paint.typeface = face.typeface
        val shrunk = if (style.script == WordScript.Baseline) size else size * SCRIPT_SCALE
        paint.textSize = shrunk
        paint.letterSpacing = if (style.spacingTwips != 0) (style.spacingTwips / 2f) / shrunk else 0f
        paint.textScaleX = style.scalePercent / 100f
        paint.baselineShift = when (style.script) {
            WordScript.Baseline -> 0
            WordScript.Super -> -(size * SUPER_SHIFT).roundToInt()
            WordScript.Sub -> (size * SUB_SHIFT).roundToInt()
        }
    }

    override fun updateMeasureState(paint: TextPaint) = applyMetrics(paint)

    override fun updateDrawState(paint: TextPaint) {
        applyMetrics(paint)
        paint.color = color
        paint.isUnderlineText = style.underline
        paint.isStrikeThruText = style.strike
        style.highlightArgb?.let { paint.bgColor = it }
    }

    private companion object {
        const val SCRIPT_SCALE = 0.65f
        const val SUPER_SHIFT = 0.33f
        const val SUB_SHIFT = 0.14f
    }
}

/** Word's line spacing, applied to every line including the last, which platform spacing skips. */
private class WordLineHeight(style: WordParagraphStyle, private val lineFactor: Float) : LineHeightSpan {
    private val rule = style.lineRule
    private val value = if (style.lineRule == WordLineRule.Auto) style.lineValue / 240f else style.lineValue / 2f

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val hasBox = text is Spanned && text.getSpans(start, end, InlineBoxSpan::class.java).isNotEmpty()
        val natural = (fm.descent - fm.ascent).toFloat()
        val textHeight = if (hasBox) natural else natural * lineFactor
        val target = when (rule) {
            WordLineRule.Auto -> textHeight * value
            WordLineRule.AtLeast -> max(textHeight, value)
            WordLineRule.Exact -> value
        }
        val extra = (target - natural).roundToInt()
        // Word puts extra leading above the text, which is where a line's height shows.
        fm.ascent -= extra
        fm.top = min(fm.top, fm.ascent)
    }
}

/** A tab, sized once the tab stops are known. */
private class TabSpan(private val color: Int) : ReplacementSpan() {
    var width: Int = 0
    var leader: Char? = null

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = width

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val fill = leader?.toString() ?: return
        val step = paint.measureText(fill)
        if (step <= 0f) return
        val previous = paint.color
        paint.color = color
        var at = x + step / 2f
        while (at + step <= x + width) {
            canvas.drawText(fill, at, y.toFloat(), paint)
            at += step
        }
        paint.color = previous
    }
}

private class InlineBoxSpan(private val box: LaidBox) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let {
            it.ascent = -box.height
            it.top = it.ascent
            it.descent = 0
            it.bottom = 0
        }
        return box.width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val count = canvas.save()
        canvas.translate(x, (y - box.height).toFloat())
        box.draw(canvas)
        canvas.restoreToCount(count)
    }
}

private class EquationBoxSpan(private val equation: RaTeXRenderer) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let {
            val ascent = -ceil(equation.heightPx).toInt()
            val descent = ceil(equation.depthPx).toInt()
            it.ascent = min(it.ascent, ascent)
            it.top = min(it.top, ascent)
            it.descent = max(it.descent, descent)
            it.bottom = max(it.bottom, descent)
        }
        return ceil(equation.widthPx).toInt().coerceAtLeast(1)
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val count = canvas.save()
        canvas.translate(x, y - equation.heightPx)
        equation.draw(canvas)
        canvas.restoreToCount(count)
    }
}

internal class LaidParagraph(
    override val source: WordParagraph,
    private val layout: StaticLayout,
    private val left: Int,
    private val width: Int,
    val floats: List<Pair<LaidBox, WordAnchor>>,
) : LaidBlock() {
    /** Bands for floats this paragraph anchors, placed ahead of it. */
    var bandsBefore: List<LaidBlock> = emptyList()

    /** Off the page's own float list, as inside a cell or a text box. */
    var drawsOwnFloats: Boolean = false

    override val height: Int get() = layout.height

    override val isBlank: Boolean
        get() = floats.isEmpty() && source.label == null && source.inlines.all { inline ->
            inline is WordRenderedBreak || (inline is WordText && inline.text.isBlank())
        }

    override val cuts: IntArray by lazy {
        val style = source.style
        val lines = layout.lineCount
        if (style.keepLines || lines < 2) {
            NO_CUTS
        } else {
            val first = if (style.widowControl) 2 else 1
            val last = if (style.widowControl) lines - 2 else lines - 1
            if (first > last) NO_CUTS else IntArray(last - first + 1) { layout.getLineBottom(first - 1 + it) }
        }
    }

    override fun draw(canvas: Canvas, from: Int, to: Int) {
        val style = source.style
        val insetRight = twipsToUnits(style.rightTwips)
        style.shadingArgb?.let { argb ->
            SHARED_FILL.color = argb
            canvas.drawRect(left.toFloat(), 0f, (width - insetRight).toFloat(), height.toFloat(), SHARED_FILL)
        }
        style.borders?.let { drawParagraphBorders(canvas, it, left.toFloat(), (width - insetRight).toFloat(), height.toFloat()) }
        val count = canvas.save()
        canvas.translate(left.toFloat(), 0f)
        layout.draw(canvas)
        canvas.restoreToCount(count)
        if (drawsOwnFloats) {
            floats.forEach { (box, anchor) ->
                val inner = canvas.save()
                canvas.translate(
                    (anchor.horizontalOffsetTwips?.let(::twipsToUnits) ?: 0).toFloat(),
                    (anchor.verticalOffsetTwips?.let(::twipsToUnits) ?: 0).toFloat(),
                )
                box.draw(canvas)
                canvas.restoreToCount(inner)
            }
        }
    }

    private fun drawParagraphBorders(canvas: Canvas, borders: WordBorders, x0: Float, x1: Float, bottom: Float) {
        drawBorderLine(canvas, borders.top, x0, 0f, x1, 0f)
        drawBorderLine(canvas, borders.bottom, x0, bottom, x1, bottom)
        drawBorderLine(canvas, borders.left, x0, 0f, x0, bottom)
        drawBorderLine(canvas, borders.right, x1, 0f, x1, bottom)
    }
}

/** Nothing but a place where the page ends. */
private class LaidBreak : LaidBlock() {
    override val height: Int get() = 0

    override val isBlank: Boolean get() = true

    override fun draw(canvas: Canvas, from: Int, to: Int) = Unit
}

private class LaidBand(private val box: LaidBox, private val x: Int) : LaidBlock() {
    override val height: Int get() = box.height

    override fun draw(canvas: Canvas, from: Int, to: Int) {
        val count = canvas.save()
        canvas.translate(x.toFloat(), 0f)
        box.draw(canvas)
        canvas.restoreToCount(count)
    }
}

internal class LaidCell(
    val cell: WordCell,
    val column: Int,
    val x: Int,
    val width: Int,
    val flow: LaidFlow,
) {
    val contentHeight: Int
        get() = flow.height + twipsToUnits(cell.marginTopTwips) + twipsToUnits(cell.marginBottomTwips)

    /** Rows this cell covers, once the table has counted its vertical merges. */
    var rowSpan: Int = 1

    var top: WordBorder? = null
    var left: WordBorder? = null
    var bottom: WordBorder? = null
    var right: WordBorder? = null
}

internal class LaidRow(val row: WordRow, val cells: List<LaidCell>)

internal class LaidTable(
    override val source: WordTable,
    private val left: Int,
    private val width: Int,
    columnCount: Int,
    private val rows: List<LaidRow>,
) : LaidBlock() {
    private val rowHeights = IntArray(rows.size)
    private val rowTops = IntArray(rows.size + 1)
    private val headerRows: Int

    init {
        rows.forEachIndexed { r, row -> resolveCells(r, row, columnCount) }
        computeRowHeights()
        headerRows = rows.takeWhile { it.row.isHeader }.size.let { if (it == rows.size) 0 else it }
    }

    /** Vertical merges and which border each edge of each cell shows. */
    private fun resolveCells(r: Int, row: LaidRow, columnCount: Int) {
        val edges = source.borders
        row.cells.forEach { cell ->
            if (cell.cell.verticalMerge == WordVerticalMerge.Restart) {
                var last = r + 1
                while (last < rows.size) {
                    val next = rows[last].cells.firstOrNull { it.column == cell.column } ?: break
                    if (next.cell.verticalMerge != WordVerticalMerge.Continue) break
                    last++
                }
                cell.rowSpan = last - r
            }
            val own = cell.cell.borders
            cell.top = own?.top ?: if (r == 0) edges?.top else edges?.insideHorizontal
            cell.bottom = own?.bottom ?: if (r + cell.rowSpan == rows.size) edges?.bottom else edges?.insideHorizontal
            cell.left = own?.left ?: if (cell.column == 0) edges?.left else edges?.insideVertical
            cell.right = own?.right ?: if (cell.column + cell.cell.gridSpan >= columnCount) edges?.right else edges?.insideVertical
        }
    }

    private fun computeRowHeights() {
        rows.forEachIndexed { r, row ->
            val floor = row.row.heightTwips?.let(::twipsToUnits) ?: 0
            val content = row.cells
                .filter { it.rowSpan == 1 && it.cell.verticalMerge != WordVerticalMerge.Continue }
                .maxOfOrNull { it.contentHeight } ?: 0
            rowHeights[r] = if (row.row.exactHeight) floor else max(floor, content)
        }
        // A vertically merged cell's extra height falls in its last row, where Word puts it too.
        rows.forEachIndexed { r, row ->
            row.cells.filter { it.rowSpan > 1 }.forEach { cell ->
                val end = r + cell.rowSpan
                var spanned = 0
                for (index in r until end) spanned += rowHeights[index]
                if (cell.contentHeight > spanned && !rows[end - 1].row.exactHeight) {
                    rowHeights[end - 1] += cell.contentHeight - spanned
                }
            }
        }
        var y = 0
        for (r in rows.indices) {
            rowTops[r] = y
            y += rowHeights[r]
        }
        rowTops[rows.size] = y
    }

    override val height: Int get() = rowTops[rows.size]

    override val repeatHeight: Int get() = rowTops[headerRows]

    override val cuts: IntArray by lazy {
        val out = ArrayList<Int>()
        rows.forEachIndexed { r, row ->
            if (r > 0 && r >= headerRows) out += rowTops[r]
            if (!row.row.cantSplit && !row.row.exactHeight && r >= headerRows) out += insideRow(r, row)
        }
        out.filter { it in 1 until height }.distinct().sorted().toIntArray()
    }

    /** The `y`s where every cell of a row can stop at a line boundary at once. */
    private fun insideRow(r: Int, row: LaidRow): List<Int> {
        val cells = row.cells.filter { it.rowSpan == 1 && it.cell.verticalMerge != WordVerticalMerge.Continue }
        if (cells.isEmpty()) return emptyList()
        fun offsets(cell: LaidCell): List<Int> = cell.flow.cuts.map { it + twipsToUnits(cell.cell.marginTopTwips) }
        val candidates = cells.flatMap(::offsets).distinct().sorted()
        return candidates.filter { candidate ->
            cells.all { cell ->
                candidate >= cell.contentHeight || candidate in offsets(cell).toSet()
            }
        }.map { rowTops[r] + it }
    }

    fun rowsStartingIn(from: Int, to: Int): Pair<Int, Int>? {
        var first = -1
        var end = -1
        for (r in rows.indices) {
            if (rowTops[r] >= from && rowTops[r] < to) {
                if (first < 0) first = r
                end = r + 1
            }
        }
        return if (first < 0) null else first to end
    }

    override fun draw(canvas: Canvas, from: Int, to: Int) {
        val count = canvas.save()
        canvas.translate(left.toFloat(), 0f)
        for (r in rows.indices) {
            if (rowTops[r + 1] <= from || rowTops[r] >= to) continue
            drawRow(canvas, r)
        }
        canvas.restoreToCount(count)
    }

    override fun drawRepeat(canvas: Canvas) {
        val count = canvas.save()
        canvas.translate(left.toFloat(), 0f)
        for (r in 0 until headerRows) drawRow(canvas, r)
        canvas.restoreToCount(count)
    }

    private fun drawRow(canvas: Canvas, r: Int) {
        val top = rowTops[r].toFloat()
        rows[r].cells.forEach { cell ->
            if (cell.cell.verticalMerge == WordVerticalMerge.Continue) return@forEach
            val bottom = rowTops[r + cell.rowSpan].toFloat()
            val left = cell.x.toFloat()
            val right = (cell.x + cell.width).toFloat()
            cell.cell.shadingArgb?.let { argb ->
                SHARED_FILL.color = argb
                canvas.drawRect(left, top, right, bottom, SHARED_FILL)
            }
            val marginLeft = twipsToUnits(cell.cell.marginLeftTwips)
            val marginTop = twipsToUnits(cell.cell.marginTopTwips)
            val free = (bottom - top - cell.contentHeight).coerceAtLeast(0f)
            val shift = when (cell.cell.align) {
                WordCellAlign.Top -> 0f
                WordCellAlign.Center -> free / 2f
                WordCellAlign.Bottom -> free
            }
            val count = canvas.save()
            canvas.clipRect(left, top, right, bottom)
            canvas.translate(left + marginLeft, top + marginTop + shift)
            cell.flow.draw(canvas)
            canvas.restoreToCount(count)
            drawBorderLine(canvas, cell.top, left, top, right, top)
            drawBorderLine(canvas, cell.bottom, left, bottom, right, bottom)
            drawBorderLine(canvas, cell.left, left, top, left, bottom)
            drawBorderLine(canvas, cell.right, right, top, right, bottom)
        }
    }
}

private val SHARED_FILL = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
private val SHARED_LINE = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

/** A border's width on the page: a word-processor eighth of a point is 1.25 layout units. */
private fun borderWidth(border: WordBorder): Float = (border.widthEighths * 1.25f).coerceAtLeast(MIN_BORDER_UNITS)

private const val MIN_BORDER_UNITS = 2.5f

internal fun drawBorderLine(canvas: Canvas, border: WordBorder?, x0: Float, y0: Float, x1: Float, y1: Float) {
    border ?: return
    if (border.style == WordBorderStyle.None) return
    val width = borderWidth(border)
    SHARED_LINE.color = border.colorArgb ?: Color.BLACK
    SHARED_LINE.pathEffect = when (border.style) {
        WordBorderStyle.Dashed -> android.graphics.DashPathEffect(floatArrayOf(width * 4f, width * 3f), 0f)
        WordBorderStyle.Dotted -> android.graphics.DashPathEffect(floatArrayOf(width, width * 2f), 0f)
        else -> null
    }
    val vertical = abs(x0 - x1) < 0.5f
    when (border.style) {
        WordBorderStyle.Double, WordBorderStyle.ThinThick -> {
            val thin = width / 3f
            val thick = if (border.style == WordBorderStyle.Double) thin else width * 2f / 3f
            val nx = if (vertical) (width / 2f) else 0f
            val ny = if (vertical) 0f else (width / 2f)
            SHARED_LINE.strokeWidth = thick
            canvas.drawLine(x0 - nx, y0 - ny, x1 - nx, y1 - ny, SHARED_LINE)
            SHARED_LINE.strokeWidth = thin
            canvas.drawLine(x0 + nx, y0 + ny, x1 + nx, y1 + ny, SHARED_LINE)
        }
        else -> {
            SHARED_LINE.strokeWidth = if (border.style == WordBorderStyle.Thick) width * 1.5f else width
            canvas.drawLine(x0, y0, x1, y1, SHARED_LINE)
        }
    }
    SHARED_LINE.pathEffect = null
}

private class PictureBox(
    private val state: RenderState,
    width: Int,
    height: Int,
    private val part: String?,
    private val crop: WordCrop?,
) : LaidBox(width, height) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    override fun draw(canvas: Canvas) {
        val needed = ceil(max(width, height) * state.scale).toInt().coerceAtLeast(1)
        val bitmap = part?.let { state.media.bitmap(it, needed) }
        if (bitmap == null) {
            drawPlaceholder(canvas, width.toFloat(), height.toFloat())
            return
        }
        val source = crop?.let {
            android.graphics.Rect(
                (bitmap.width * it.left).roundToInt(),
                (bitmap.height * it.top).roundToInt(),
                (bitmap.width * (1f - it.right)).roundToInt(),
                (bitmap.height * (1f - it.bottom)).roundToInt(),
            )
        }
        canvas.drawBitmap(bitmap, source, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    }
}

private class PlaceholderBox(width: Int, height: Int) : LaidBox(width, height) {
    override fun draw(canvas: Canvas) = drawPlaceholder(canvas, width.toFloat(), height.toFloat())
}

/** A rectangle that says something is here and could not be drawn; the page's layout is unchanged. */
private fun drawPlaceholder(canvas: Canvas, width: Float, height: Float) {
    SHARED_FILL.color = PLACEHOLDER_FILL
    canvas.drawRect(0f, 0f, width, height, SHARED_FILL)
    SHARED_LINE.color = PLACEHOLDER_LINE
    SHARED_LINE.strokeWidth = 2.5f
    canvas.drawRect(0f, 0f, width, height, SHARED_LINE)
    canvas.drawLine(0f, 0f, width, height, SHARED_LINE)
    canvas.drawLine(width, 0f, 0f, height, SHARED_LINE)
}

private const val PLACEHOLDER_FILL = 0xFFF2F2F2.toInt()
private const val PLACEHOLDER_LINE = 0xFFBDBDBD.toInt()

private class ShapeBox(
    width: Int,
    height: Int,
    private val shape: WordShape,
    private val flow: LaidFlow?,
    private val insetLeft: Int,
    private val insetTop: Int,
) : LaidBox(width, height) {
    /** The outlines, stretched to this box. */
    private val outlines: List<Pair<WordPath, Path>> = shape.outline.map { path -> path to scaledPath(path, width.toFloat(), height.toFloat()) }

    override fun draw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Only the outline turns over. The text in a flipped shape still reads the right way round.
        val flipped = canvas.save()
        canvas.scale(if (shape.flipHorizontal) -1f else 1f, if (shape.flipVertical) -1f else 1f, w / 2f, h / 2f)
        val gradient = shape.gradient
        if (gradient != null) {
            SHARED_FILL.color = Color.BLACK
            SHARED_FILL.shader = shaderOf(gradient, w, h)
            drawForm(canvas, w, h, SHARED_FILL) { it.filled }
            SHARED_FILL.shader = null
        } else {
            shape.fillArgb?.let { argb ->
                SHARED_FILL.color = argb
                drawForm(canvas, w, h, SHARED_FILL) { it.filled }
            }
        }
        shape.line?.let { line ->
            val width = (line.widthEighths * 1.25f).coerceAtLeast(MIN_BORDER_UNITS)
            SHARED_LINE.color = line.colorArgb ?: Color.BLACK
            SHARED_LINE.strokeWidth = width
            SHARED_LINE.pathEffect = when (line.style) {
                WordBorderStyle.Dashed -> DashPathEffect(floatArrayOf(width * 4f, width * 3f), 0f)
                WordBorderStyle.Dotted -> DashPathEffect(floatArrayOf(width, width * 2f), 0f)
                else -> null
            }
            drawForm(canvas, w, h, SHARED_LINE) { it.stroked }
            SHARED_LINE.pathEffect = null
            drawArrowheads(canvas, line.colorArgb ?: Color.BLACK, width)
        }
        canvas.restoreToCount(flipped)
        flow?.let {
            val count = canvas.save()
            canvas.translate(insetLeft.toFloat(), insetTop.toFloat())
            it.draw(canvas)
            canvas.restoreToCount(count)
        }
    }

    private fun drawForm(canvas: Canvas, w: Float, h: Float, paint: Paint, wanted: (WordPath) -> Boolean) {
        if (outlines.isNotEmpty()) {
            outlines.filter { wanted(it.first) }.forEach { canvas.drawPath(it.second, paint) }
            return
        }
        when (shape.form) {
            WordShapeForm.Rectangle -> canvas.drawRect(0f, 0f, w, h, paint)
            WordShapeForm.RoundedRectangle -> canvas.drawRoundRect(0f, 0f, w, h, CORNER_RADIUS, CORNER_RADIUS, paint)
            WordShapeForm.Ellipse -> canvas.drawOval(0f, 0f, w, h, paint)
        }
    }

    private fun drawArrowheads(canvas: Canvas, color: Int, lineWidth: Float) {
        val points = shape.outline.lastOrNull()?.let { pathPoints(it, width.toFloat(), height.toFloat()) } ?: return
        if (points.size < 2) return
        val size = max(lineWidth * ARROW_LINE_WIDTHS, MIN_ARROW)
        SHARED_FILL.color = color
        if (shape.arrowAtStart) drawArrowhead(canvas, points[1], points[0], size)
        if (shape.arrowAtEnd) drawArrowhead(canvas, points[points.size - 2], points[points.size - 1], size)
    }

    private fun drawArrowhead(canvas: Canvas, from: PointF, tip: PointF, size: Float) {
        val dx = tip.x - from.x
        val dy = tip.y - from.y
        val length = kotlin.math.hypot(dx, dy)
        if (length == 0f) return
        val ux = dx / length
        val uy = dy / length
        val base = PointF(tip.x - ux * size, tip.y - uy * size)
        val half = size / 2f
        val head = Path()
        head.moveTo(tip.x, tip.y)
        head.lineTo(base.x - uy * half, base.y + ux * half)
        head.lineTo(base.x + uy * half, base.y - ux * half)
        head.close()
        canvas.drawPath(head, SHARED_FILL)
    }

    private companion object {
        const val CORNER_RADIUS = 80f
        const val ARROW_LINE_WIDTHS = 4f
        const val MIN_ARROW = 60f
    }
}

private fun shaderOf(gradient: WordGradient, w: Float, h: Float): Shader {
    if (gradient.radial) {
        val x = w * gradient.centerX
        val y = h * gradient.centerY
        val radius = hypot(max(x, w - x), max(y, h - y)).coerceAtLeast(1f)
        return RadialGradient(x, y, radius, gradient.colors, gradient.positions, Shader.TileMode.CLAMP)
    }
    val radians = Math.toRadians(gradient.angleDegrees.toDouble())
    val dx = cos(radians).toFloat()
    val dy = sin(radians).toFloat()
    // Far enough along the line that the blend reaches both corners, whichever way it runs.
    val reach = (abs(dx) * w + abs(dy) * h) / 2f
    return LinearGradient(
        w / 2f - dx * reach, h / 2f - dy * reach, w / 2f + dx * reach, h / 2f + dy * reach,
        gradient.colors, gradient.positions, Shader.TileMode.CLAMP,
    )
}

/** [path], in this box's units: a point's place within the path's own space is its place in the box. */
private fun scaledPath(path: WordPath, width: Float, height: Float): Path {
    val sx = if (path.width > 0f) width / path.width else 1f
    val sy = if (path.height > 0f) height / path.height else 1f
    val out = Path()
    for (command in path.commands) {
        when (command) {
            is WordMoveTo -> out.moveTo(command.x * sx, command.y * sy)
            is WordLineTo -> out.lineTo(command.x * sx, command.y * sy)
            is WordCubicTo -> out.cubicTo(command.x1 * sx, command.y1 * sy, command.x2 * sx, command.y2 * sy, command.x * sx, command.y * sy)
            WordClosePath -> out.close()
        }
    }
    return out
}

/** Where [path] goes, corner by corner: what its arrowheads need to know to point the right way. */
private fun pathPoints(path: WordPath, width: Float, height: Float): List<PointF> {
    val sx = if (path.width > 0f) width / path.width else 1f
    val sy = if (path.height > 0f) height / path.height else 1f
    return path.commands.mapNotNull { command ->
        when (command) {
            is WordMoveTo -> PointF(command.x * sx, command.y * sy)
            is WordLineTo -> PointF(command.x * sx, command.y * sy)
            is WordCubicTo -> PointF(command.x * sx, command.y * sy)
            WordClosePath -> null
        }
    }
}

private class GroupBox(
    width: Int,
    height: Int,
    private val children: List<Triple<LaidBox, Int, Int>>,
) : LaidBox(width, height) {
    override fun draw(canvas: Canvas) {
        for ((box, x, y) in children) {
            val count = canvas.save()
            canvas.translate(x.toFloat(), y.toFloat())
            box.draw(canvas)
            canvas.restoreToCount(count)
        }
    }
}
