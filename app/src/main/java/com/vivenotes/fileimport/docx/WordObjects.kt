package com.vivenotes.fileimport.docx

import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.Outline
import com.vivenotes.model.Run
import com.vivenotes.model.newId
import com.vivenotes.model.newTable
import com.vivenotes.richtext.CanvasPasteItem
import com.vivenotes.richtext.HIGHLIGHT_ALPHA
import com.vivenotes.richtext.isChromatic

/**
 * What a page holds, for the import as objects.
 *
 * A paragraph belongs to the page it starts on and a table's rows to the page each starts on, so a
 * chosen page never brings the tail of a sentence with it and never loses one either.
 */
internal sealed interface PagePiece {
    val bodyWidthTwips: Int
}

internal class ParagraphPiece(val paragraph: WordParagraph, override val bodyWidthTwips: Int) : PagePiece

/** Where what comes before and what comes after stay in separate text boxes, like two slides. */
internal object SeparatorPiece : PagePiece {
    override val bodyWidthTwips: Int = 0
}

internal class TablePiece(
    val table: WordTable,
    val firstRow: Int,
    val endRow: Int,
    override val bodyWidthTwips: Int,
) : PagePiece

/** The monospace faces word processors offer, which stay monospace whatever else is dropped. */
internal fun isMonospaceFontName(name: String?): Boolean {
    val font = name?.lowercase() ?: return false
    return MONOSPACE_NAME_PARTS.any { it in font }
}

private val MONOSPACE_NAME_PARTS = listOf(
    "mono", "courier", "consolas", "menlo", "monaco", "lucida console", "cascadia", "fira code", "inconsolata",
)

/**
 * The chosen pages as the clipboard would have delivered them.
 *
 * The rules are `HtmlPaste`'s, so a document and a copied web page land the same way: paragraph styles,
 * lists, alignment, emphasis, links and real colours stay; fonts, sizes, greys, blacks and whites go,
 * so the text reads in the page's own ink. Two differences come from having the document rather than
 * its HTML: tables keep their column proportions, and pictures keep their size.
 */
internal class WordObjects(
    private val document: WordDocument,
    textMarks: Set<Mark>,
    private val pictureBytes: (String) -> ByteArray?,
    /** A picture's bytes with its crop cut away, or null where that cannot be done, as off a device. */
    private val croppedPicture: (ByteArray, WordCrop) -> ByteArray? = { _, _ -> null },
) {
    private val base: Set<Mark> = textMarks.filterTo(mutableSetOf()) { it is Mark.FontFamily || it is Mark.FontSize }
    private val items = ArrayList<CanvasPasteItem>()
    private val noteTexts = ArrayList<Block>()
    private var noteNumber = 0

    /** Paragraphs so far of the text box being put together. */
    private val current = TextFlow()

    /** [pieces] in page order; a table cut by a page break joins back up if both halves were chosen. */
    fun items(pieces: List<PagePiece>): List<CanvasPasteItem> {
        for (piece in joinedTables(pieces)) {
            when (piece) {
                is ParagraphPiece -> addParagraph(piece.paragraph, current, piece.bodyWidthTwips)
                is TablePiece -> addTable(piece, piece.bodyWidthTwips)
                SeparatorPiece -> flush()
            }
        }
        flush()
        if (noteTexts.isNotEmpty()) items += CanvasPasteItem.Text(noteTexts.toList())
        return items
    }

    private fun joinedTables(pieces: List<PagePiece>): List<PagePiece> = buildList {
        for (piece in pieces) {
            val last = lastOrNull()
            if (piece is TablePiece && last is TablePiece && last.table === piece.table && last.endRow == piece.firstRow) {
                this[lastIndex] = TablePiece(last.table, last.firstRow, piece.endRow, last.bodyWidthTwips)
            } else {
                add(piece)
            }
        }
    }

    private fun flush() {
        current.take()?.let { items += CanvasPasteItem.Text(it) }
    }

    /** A paragraph's text, with what it holds of pictures and text boxes put out on their own. */
    private fun addParagraph(paragraph: WordParagraph, flow: TextFlow, bodyWidth: Int) {
        val builder = ParagraphConversion(paragraph, flow, bodyWidth)
        builder.run()
    }

    private inner class ParagraphConversion(
        private val paragraph: WordParagraph,
        private val flow: TextFlow,
        private val bodyWidth: Int,
    ) {
        private val runs = ArrayList<Run>()

        /** Whether the editor's own count will say what the document's marker says. */
        private val counted: Boolean = paragraph.label?.let { label ->
            !label.bullet && PLAIN_NUMBER.matches(label.text) && label.text.dropLast(1).toIntOrNull() == flow.nextOrdinal()
        } == true
        private val type = blockType(paragraph, counted)
        private val heading = type == BlockType.Heading1 || type == BlockType.Heading2 || type == BlockType.Heading3
        private var firstLine = true
        private var produced = false
        private val indent = indentOf(paragraph)

        fun run() {
            flow.beforeParagraph(paragraph)
            paragraph.label?.takeUnless { it.bullet || counted }?.let { runs += Run(it.text + " ", base) }
            for (inline in paragraph.inlines) {
                when (inline) {
                    is WordText -> runs += Run(inline.text, marks(inline.style, inline.link))
                    is WordTab -> runs += Run("\t", marks(inline.style, null))
                    is WordLineBreak -> endLine()
                    is WordEquation -> addEquation(inline)
                    is WordNoteRef -> addNote(inline)
                    is WordBoxed -> addBoxed(inline)
                    is WordField, WordRenderedBreak -> Unit
                }
            }
            if (runs.isNotEmpty() || !produced) endLine()
            flow.afterParagraph(paragraph)
        }

        private fun endLine() {
            val merged = merge(runs)
            runs.clear()
            val display = paragraph.inlines.any { it is WordEquation && it.display }
            val listed = type == BlockType.Bullet || type == BlockType.Numbered
            val align = when {
                display || paragraph.style.align == WordAlign.Center -> Align.Center
                paragraph.style.align == WordAlign.Right -> Align.End
                else -> Align.Start
            }
            flow.add(
                Block(
                    id = newId(),
                    type = if (firstLine || !listed) type else BlockType.Paragraph,
                    indent = if (firstLine || !listed) indent else (indent + 1).coerceAtMost(MAX_INDENT),
                    align = align,
                    runs = merged,
                ),
            )
            firstLine = false
            produced = true
        }

        private fun addEquation(equation: WordEquation) {
            if (equation.latex.isEmpty()) {
                runs += Run(equation.plainText, marks(equation.style, null))
            } else {
                runs += Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(Mark.Equation(equation.latex)))
            }
        }

        private fun addNote(note: WordNoteRef) {
            noteNumber++
            runs += Run(noteNumber.toString(), base + Mark.Superscript)
            val body = (if (note.endnote) document.endnotes else document.footnotes)[note.id] ?: return
            val text = plainTextOf(body)
            noteTexts += Block(id = newId(), runs = listOf(Run("$noteNumber. $text", base)))
        }

        private fun addBoxed(boxed: WordBoxed) {
            if (flow !== current) {
                if (boxed is WordPicture) spillPicture(boxed, bodyWidth) else inlineBoxText(boxed)
                return
            }
            if (runs.isNotEmpty()) endLine()
            flush()
            produced = true
            placeBoxed(boxed, bodyWidth)
        }

        /** Inside a table cell there is nowhere for an object to go; a text box's words stay in the cell. */
        private fun inlineBoxText(boxed: WordBoxed) {
            if (boxed is WordShape) {
                boxed.blocks.filterIsInstance<WordParagraph>().forEach { addParagraph(it, flow, bodyWidth) }
            }
            if (boxed is WordGroup) boxed.children.forEach { inlineBoxText(it.boxed) }
        }

        private fun marks(style: WordRunStyle, link: String?): Set<Mark> = buildSet {
            if (isMonospaceFontName(style.font)) {
                addAll(base.filter { it !is Mark.FontFamily })
                add(Mark.FontFamily("monospace"))
            } else {
                addAll(base)
            }
            // A heading is bold by being one; marking it so would outlast the heading.
            if (style.bold && !heading) add(Mark.Bold)
            if (style.italic) add(Mark.Italic)
            if (style.underline && link == null) add(Mark.Underline)
            if (style.strike) add(Mark.Strikethrough)
            when (style.script) {
                WordScript.Super -> add(Mark.Superscript)
                WordScript.Sub -> add(Mark.Subscript)
                WordScript.Baseline -> Unit
            }
            link?.let { add(Mark.Link(it)) }
            if (link == null) style.colorArgb?.takeIf(Int::isChromatic)?.let { add(Mark.TextColor(it)) }
            style.highlightArgb?.takeIf(Int::isChromatic)?.let { add(Mark.Highlight(HIGHLIGHT_ALPHA or (it and 0xFFFFFF))) }
        }
    }

    /**
     * The canvas's list blocks are bullets and "1." counted down the text box, so any other marker —
     * `(a)`, `iv.`, or a count the box would not arrive at itself — is written out as text instead
     * ([counted] says which), and a reference to "item (a)" still points at something.
     */
    private fun blockType(paragraph: WordParagraph, counted: Boolean): BlockType {
        val name = paragraph.style.name.lowercase()
        val level = paragraph.style.outlineLevel
        val label = paragraph.label
        return when {
            name == "title" -> BlockType.Heading1
            name == "subtitle" -> BlockType.Heading3
            level == 0 -> BlockType.Heading1
            level == 1 -> BlockType.Heading2
            level != null -> BlockType.Heading3
            label != null && label.bullet -> BlockType.Bullet
            counted -> BlockType.Numbered
            "quote" in name -> BlockType.Quote
            name in CODE_STYLE_NAMES -> BlockType.Code
            else -> BlockType.Paragraph
        }
    }

    private fun indentOf(paragraph: WordParagraph): Int {
        paragraph.label?.let { return it.level.coerceAtMost(MAX_INDENT) }
        val start = paragraph.style.leftTwips + maxOf(paragraph.style.firstLineTwips, 0)
        return ((start + INDENT_STEP_TWIPS / 2) / INDENT_STEP_TWIPS).coerceIn(0, MAX_INDENT)
    }

    /** A floating or inline object at canvas level: its own item, its text its own text box. */
    private fun placeBoxed(boxed: WordBoxed, bodyWidth: Int) {
        when (boxed) {
            is WordPicture -> pictureItem(boxed, bodyWidth)?.let { items += it }
            is WordShape -> {
                for (block in boxed.blocks) {
                    when (block) {
                        is WordParagraph -> addParagraph(block, current, bodyWidth)
                        is WordTable -> addTable(TablePiece(block, 0, block.rows.size, bodyWidth), bodyWidth)
                        is WordBreak -> Unit
                    }
                }
                flush()
            }
            is WordGroup -> boxed.children.sortedBy { it.yTwips }.forEach { placeBoxed(it.boxed, bodyWidth) }
            is WordPlaceholder -> Unit
        }
    }

    /**
     * A picture as the page shows it: cropped as the document crops it, at the size it has there. The
     * canvas stretches a picture to its frame, so the frame only takes the document's shape when
     * the bytes are of that shape too.
     */
    private fun pictureItem(picture: WordPicture, bodyWidth: Int): CanvasPasteItem.Image? {
        val whole = picture.part?.let(pictureBytes) ?: return null
        val crop = picture.crop?.takeIf { it.cutsAnything }
        val cropped = crop?.let { croppedPicture(whole, it) }
        val bytes = cropped ?: whole
        // A file that gives no size leaves it to the picture's own.
        if (picture.widthTwips <= 0 || picture.heightTwips <= 0) return CanvasPasteItem.Image(bytes = bytes)
        val scale = writingScale(bodyWidth)
        val width = (picture.widthTwips * scale).coerceIn(Outline.Image.MIN_SIZE, Outline.Text.DEFAULT_WIDTH)
        // Not cropped after all, it keeps its own shape rather than take on the crop's.
        val height = if (crop != null && cropped == null) {
            null
        } else {
            (picture.heightTwips * (width / picture.widthTwips)).coerceAtLeast(Outline.Image.MIN_SIZE)
        }
        return CanvasPasteItem.Image(bytes = bytes, widthDp = width, heightDp = height)
    }

    private fun spillPicture(picture: WordPicture, bodyWidth: Int) {
        pictureItem(picture, bodyWidth)?.let { spilled += it }
    }

    /** Pictures found inside table cells, placed after the table they were in. */
    private val spilled = ArrayList<CanvasPasteItem.Image>()

    /** dp per twip, so a table or picture is the same share of a text box as of the document's page. */
    private fun writingScale(bodyWidthTwips: Int): Float =
        Outline.Text.DEFAULT_WIDTH / bodyWidthTwips.coerceAtLeast(1)

    // --- tables --------------------------------------------------------------------------------

    private fun addTable(piece: TablePiece, bodyWidth: Int) {
        val table = piece.table
        val rows = table.rows.subList(piece.firstRow, piece.endRow)
        if (isLayoutTable(table)) {
            unwrap(table, bodyWidth)
            return
        }
        val columnCount = table.columnCount
        flush()
        // A table with no rows or no cells has nothing to bring, and no shape to bring it in.
        if (rows.isEmpty() || columnCount == 0) {
            items += spilled
            spilled.clear()
            return
        }
        if (columnCount > Outline.Table.MAX_COLUMNS) {
            tableAsText(rows)
        } else {
            val chunk = minOf(Outline.Table.MAX_ROWS, Outline.Table.MAX_CELLS / columnCount)
            rows.chunked(chunk).forEachIndexed { index, part ->
                items += CanvasPasteItem.Table(outlineFor(table, part, columnCount, bodyWidth, index == 0))
            }
        }
        items += spilled
        spilled.clear()
    }

    /** One row of one cell is a box drawn around something, not a table of anything. */
    private fun isLayoutTable(table: WordTable): Boolean =
        table.rows.size == 1 && table.rows[0].cells.size == 1

    private fun unwrap(table: WordTable, bodyWidth: Int) {
        flush()
        for (block in table.rows[0].cells[0].blocks) {
            when (block) {
                is WordParagraph -> addParagraph(block, current, bodyWidth)
                is WordTable -> addTable(TablePiece(block, 0, block.rows.size, bodyWidth), bodyWidth)
                is WordBreak -> Unit
            }
        }
        flush()
    }

    private fun outlineFor(
        table: WordTable,
        rows: List<WordRow>,
        columnCount: Int,
        bodyWidth: Int,
        first: Boolean,
    ): Outline.Table {
        val widths = columnWidths(table, columnCount, bodyWidth)
        val outline = newTable(
            columns = columnCount,
            rows = rows.size,
            rowHeight = Outline.Table.MIN_ROW_HEIGHT,
            headerRow = first && rows.first().isHeader,
            borderFollowsTheme = true,
            fillArgb = sharedFill(rows),
        )
        val tableScale = writingScale(bodyWidth)
        return outline.copy(
            columns = widths,
            rows = outline.rows.mapIndexed { r, tableRow ->
                val row = rows[r]
                val contents = arrayOfNulls<List<Block>>(columnCount)
                var column = row.gridBefore
                for (cell in row.cells) {
                    if (column >= columnCount) break
                    if (cell.verticalMerge != WordVerticalMerge.Continue) contents[column] = cellBlocks(cell, bodyWidth)
                    column += cell.gridSpan
                }
                tableRow.copy(
                    minHeight = (((row.heightTwips ?: 0) * tableScale))
                        .coerceIn(Outline.Table.MIN_ROW_HEIGHT, Outline.Table.MAX_ROW_HEIGHT),
                    cells = tableRow.cells.mapIndexed { c, tableCell ->
                        tableCell.copy(blocks = contents[c] ?: listOf(Block.of("")))
                    },
                )
            },
        ).withRecomputedWidth()
    }

    /** The grid's proportions at the size the document gives the table, in the text box's scale. */
    private fun columnWidths(table: WordTable, columnCount: Int, bodyWidth: Int): List<Float> {
        val grid = table.columnTwips(columnCount)
        val total = grid.sum()
        val scale = writingScale(bodyWidth)
        val widths = if (total <= 0) {
            List(columnCount) { Outline.Text.DEFAULT_WIDTH / columnCount }
        } else {
            grid.map { it * scale }
        }
        return widths.map { it.coerceIn(Outline.Table.MIN_COLUMN_WIDTH, Outline.Table.MAX_COLUMN_WIDTH) }
    }

    /** The stored table has one fill, so a colour is kept only when every cell has it. */
    private fun sharedFill(rows: List<WordRow>): Int? {
        val fills = rows.flatMap { row -> row.cells.map { it.shadingArgb } }.distinct()
        return fills.singleOrNull()?.takeIf(Int::isChromatic)
    }

    private fun cellBlocks(cell: WordCell, bodyWidth: Int): List<Block> {
        val flow = TextFlow(blankLines = false)
        for (block in cell.blocks) {
            when (block) {
                is WordParagraph -> addParagraph(block, flow, bodyWidth)
                is WordTable -> flow.addNested(block)
                is WordBreak -> Unit
            }
        }
        return flow.take().orEmpty().ifEmpty { listOf(Block.of("")) }
    }

    private fun tableAsText(rows: List<WordRow>) {
        for (row in rows) {
            val line = row.cells.joinToString("\t") { plainTextOf(it.blocks).replace('\n', ' ') }
            current.add(Block(id = newId(), runs = listOf(Run(line, base))))
        }
        flush()
    }

    private fun plainTextOf(blocks: List<WordBlock>): String = blocks.joinToString("\n") { block ->
        when (block) {
            is WordParagraph -> block.inlines.joinToString("") { inline ->
                when (inline) {
                    is WordText -> inline.text
                    is WordTab -> "\t"
                    is WordEquation -> inline.plainText
                    else -> ""
                }
            }
            is WordTable -> block.rows.joinToString("\n") { row ->
                row.cells.joinToString("\t") { plainTextOf(it.blocks) }
            }
            is WordBreak -> ""
        }
    }

    /** Paragraphs on their way into one text box, spaced as a pasted page is. */
    private inner class TextFlow(private val blankLines: Boolean = true) {
        private val blocks = ArrayList<Block>()
        private var previous: WordParagraph? = null

        /** What a numbered line added now would be counted as: consecutive ones, as the editor counts. */
        fun nextOrdinal(): Int = blocks.takeLastWhile { it.type == BlockType.Numbered }.size + 1

        fun beforeParagraph(paragraph: WordParagraph) {
            val before = previous
            if (blankLines && before != null && blocks.isNotEmpty() && gapBetween(before, paragraph) > BLANK_LINE_GAP_TWIPS) {
                addBlank()
            }
        }

        fun afterParagraph(paragraph: WordParagraph) {
            previous = paragraph
        }

        private fun gapBetween(above: WordParagraph, below: WordParagraph): Int {
            if (below.style.contextualSpacing && above.style.name == below.style.name) return 0
            return above.style.afterTwips + below.style.beforeTwips
        }

        fun add(block: Block) {
            if (block.runs.all { it.text.isBlank() && it.marks.none { mark -> mark is Mark.Equation } }) {
                addBlank()
            } else {
                blocks += block
            }
        }

        private fun addBlank() {
            if (blocks.isNotEmpty() && blocks.last().runs.isEmpty()) return
            blocks += Block(id = newId())
        }

        fun addNested(table: WordTable) {
            table.rows.forEach { row ->
                val line = row.cells.joinToString("\t") { plainTextOf(it.blocks).replace('\n', ' ') }
                blocks += Block(id = newId(), runs = listOf(Run(line, base)))
            }
        }

        /** What has gathered, without the blank lines at its ends; null when that leaves nothing. */
        fun take(): List<Block>? {
            val kept = blocks.dropWhile { it.runs.isEmpty() }.dropLastWhile { it.runs.isEmpty() }
            blocks.clear()
            previous = null
            return kept.ifEmpty { null }
        }
    }

    private fun merge(runs: List<Run>): List<Run> = buildList {
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = lastOrNull()
            if (last != null && last.marks == run.marks && run.marks.none { it is Mark.Equation }) {
                this[lastIndex] = last.copy(text = last.text + run.text)
            } else {
                add(run)
            }
        }
    }

    private companion object {
        const val MAX_INDENT = 8

        /** One step of the editor's indent is as far as Word's tab key goes. */
        const val INDENT_STEP_TWIPS = 720

        /** `HtmlPaste`'s 8 CSS pixels: more space than this between paragraphs is a blank line. */
        const val BLANK_LINE_GAP_TWIPS = 120

        val PLAIN_NUMBER = Regex("""\d+\.""")
        val CODE_STYLE_NAMES = setOf("code", "source code", "html preformatted", "html code", "macro text")
    }
}
