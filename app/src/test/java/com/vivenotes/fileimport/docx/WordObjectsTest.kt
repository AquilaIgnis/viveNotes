package com.vivenotes.fileimport.docx

import com.vivenotes.model.Align
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.Outline
import com.vivenotes.richtext.CanvasPasteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordObjectsTest {
    private val document = WordDocument(emptyList(), footnotes = mapOf(5 to listOf(para("the note"))), emptyMap(), WordSettings())
    private val bodyWidth = 9360

    private fun para(
        text: String,
        style: WordParagraphStyle = WordParagraphStyle(),
        run: WordRunStyle = WordRunStyle(),
        label: WordListLabel? = null,
    ): WordParagraph = WordParagraph(style, WordRunStyle(), if (text.isEmpty()) emptyList() else listOf(WordText(text, run, null)), label)

    private fun items(vararg pieces: PagePiece, marks: Set<Mark> = emptySet(), bytes: (String) -> ByteArray? = { byteArrayOf(7) }): List<CanvasPasteItem> =
        WordObjects(document, marks, bytes).items(pieces.toList())

    private fun paragraphs(vararg paragraphs: WordParagraph): List<CanvasPasteItem> =
        items(*paragraphs.map { ParagraphPiece(it, bodyWidth) }.toTypedArray())

    private fun textOf(item: CanvasPasteItem): List<String> = (item as CanvasPasteItem.Text).blocks.map { it.text }

    @Test
    fun consecutiveParagraphsAreOneTextBox() {
        val items = paragraphs(para("one"), para("two"))
        assertEquals(1, items.size)
        assertEquals(listOf("one", "two"), textOf(items[0]))
    }

    @Test
    fun headingsComeFromOutlineLevelAndTitle() {
        val items = paragraphs(
            para("big", WordParagraphStyle(name = "Title")),
            para("h1", WordParagraphStyle(outlineLevel = 0)),
            para("h2", WordParagraphStyle(outlineLevel = 1)),
            para("h3", WordParagraphStyle(outlineLevel = 2)),
            para("h7", WordParagraphStyle(outlineLevel = 6)),
        )
        assertEquals(
            listOf(BlockType.Heading1, BlockType.Heading1, BlockType.Heading2, BlockType.Heading3, BlockType.Heading3),
            (items.single() as CanvasPasteItem.Text).blocks.map { it.type },
        )
    }

    /** A heading is bold by being one; marking it would outlast the heading. */
    @Test
    fun aHeadingIsNotAlsoMarkedBold() {
        val items = paragraphs(para("h", WordParagraphStyle(outlineLevel = 0), WordRunStyle(bold = true)))
        val block = (items.single() as CanvasPasteItem.Text).blocks.single()
        assertFalse(Mark.Bold in block.runs.single().marks)
    }

    @Test
    fun fontsAndSizesGoAndTheCaretsMarksComeInstead() {
        val marks = setOf(Mark.FontFamily("serif"), Mark.FontSize(18))
        val items = items(ParagraphPiece(para("x", run = WordRunStyle(font = "Arial", halfPoints = 40)), bodyWidth), marks = marks)
        val run = (items.single() as CanvasPasteItem.Text).blocks.single().runs.single()
        assertEquals(marks, run.marks)
    }

    @Test
    fun monospaceStaysMonospace() {
        val items = paragraphs(para("code", run = WordRunStyle(font = "Courier New")))
        val run = (items.single() as CanvasPasteItem.Text).blocks.single().runs.single()
        assertTrue(Mark.FontFamily("monospace") in run.marks)
    }

    /** Greys and blacks are the page's ink; only a colour that is a colour is kept. */
    @Test
    fun onlyRealColoursSurvive() {
        fun marksFor(color: Int?, highlight: Int?): Set<Mark> {
            val items = paragraphs(para("x", run = WordRunStyle(colorArgb = color, highlightArgb = highlight)))
            return (items.single() as CanvasPasteItem.Text).blocks.single().runs.single().marks
        }
        assertEquals(setOf<Mark>(), marksFor(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(setOf<Mark>(), marksFor(0xFF777777.toInt(), null))
        assertEquals(setOf<Mark>(Mark.TextColor(0xFFC45911.toInt())), marksFor(0xFFC45911.toInt(), null))
        assertEquals(setOf<Mark>(Mark.Highlight(0x66FFFF00)), marksFor(null, 0xFFFFFF00.toInt()))
    }

    @Test
    fun emphasisAndScriptsAreKept() {
        val items = paragraphs(para("x", run = WordRunStyle(bold = true, italic = true, underline = true, strike = true, script = WordScript.Super)))
        assertEquals(
            setOf(Mark.Bold, Mark.Italic, Mark.Underline, Mark.Strikethrough, Mark.Superscript),
            (items.single() as CanvasPasteItem.Text).blocks.single().runs.single().marks,
        )
    }

    @Test
    fun aLinkIsNotAlsoUnderlinedOrColoured() {
        val paragraph = WordParagraph(
            WordParagraphStyle(),
            WordRunStyle(),
            listOf(WordText("site", WordRunStyle(underline = true, colorArgb = 0xFF0563C1.toInt()), "https://example.com")),
            null,
        )
        val run = (paragraphs(paragraph).single() as CanvasPasteItem.Text).blocks.single().runs.single()
        assertEquals(setOf<Mark>(Mark.Link("https://example.com")), run.marks)
    }

    @Test
    fun alignmentIsKeptAndJustifiedIsPlain() {
        val items = paragraphs(
            para("a", WordParagraphStyle(align = WordAlign.Center)),
            para("b", WordParagraphStyle(align = WordAlign.Right)),
            para("c", WordParagraphStyle(align = WordAlign.Justify)),
        )
        assertEquals(listOf(Align.Center, Align.End, Align.Start), (items.single() as CanvasPasteItem.Text).blocks.map { it.align })
    }

    private fun bullet(level: Int = 0) = WordListLabel("•", WordRunStyle(), followedByTab = true, followedBySpace = false, bullet = true, level = level)

    private fun number(text: String, level: Int = 0) = WordListLabel(text, WordRunStyle(), followedByTab = true, followedBySpace = false, bullet = false, level = level)

    @Test
    fun bulletsAreListBlocksAtTheirLevel() {
        val items = paragraphs(para("a", label = bullet()), para("b", label = bullet(1)))
        val blocks = (items.single() as CanvasPasteItem.Text).blocks
        assertEquals(listOf(BlockType.Bullet, BlockType.Bullet), blocks.map { it.type })
        assertEquals(listOf(0, 1), blocks.map { it.indent })
    }

    @Test
    fun aPlainCountBecomesANumberedList() {
        val blocks = (paragraphs(para("a", label = number("1.")), para("b", label = number("2."))).single() as CanvasPasteItem.Text).blocks
        assertEquals(listOf(BlockType.Numbered, BlockType.Numbered), blocks.map { it.type })
        assertEquals(listOf("a", "b"), blocks.map { it.text })
    }

    /** The canvas only counts "1." from one; `(a)` or a list resuming at 5 stays what it was. */
    @Test
    fun aMarkerTheCanvasCannotDrawIsWrittenOut() {
        val blocks = (paragraphs(para("a", label = number("(a)")), para("e", label = number("5."))).single() as CanvasPasteItem.Text).blocks
        assertEquals(listOf(BlockType.Paragraph, BlockType.Paragraph), blocks.map { it.type })
        assertEquals(listOf("(a) a", "5. e"), blocks.map { it.text })
    }

    @Test
    fun indentBecomesLevels() {
        val blocks = (paragraphs(
            para("a", WordParagraphStyle(leftTwips = 1440)),
            para("b", WordParagraphStyle(leftTwips = 720, firstLineTwips = 720)),
            para("c", WordParagraphStyle(leftTwips = 100)),
        ).single() as CanvasPasteItem.Text).blocks
        assertEquals(listOf(2, 2, 0), blocks.map { it.indent })
    }

    /** More than 6pt of space between paragraphs reads as a blank line, as a pasted page's does. */
    @Test
    fun spacingBetweenParagraphsBecomesOneBlankLine() {
        val spaced = WordParagraphStyle(name = "Normal", afterTwips = 160)
        val blocks = (paragraphs(para("a", spaced), para("b", spaced)).single() as CanvasPasteItem.Text).blocks
        assertEquals(listOf("a", "", "b"), blocks.map { it.text })
    }

    @Test
    fun contextualSpacingSuppressesTheBlankLine() {
        val spaced = WordParagraphStyle(name = "List", afterTwips = 160, contextualSpacing = true)
        val blocks = (paragraphs(para("a", spaced), para("b", spaced)).single() as CanvasPasteItem.Text).blocks
        assertEquals(listOf("a", "b"), blocks.map { it.text })
    }

    @Test
    fun runsOfEmptyParagraphsCollapseAndTheEndsAreTrimmed() {
        val items = paragraphs(para(""), para(""), para("a"), para(""), para(""), para(""), para("b"), para(""))
        assertEquals(listOf("a", "", "b"), textOf(items.single()))
    }

    @Test
    fun aLineBreakIsANewBlockAndAListContinuesUnderItself() {
        val paragraph = WordParagraph(
            WordParagraphStyle(),
            WordRunStyle(),
            listOf(WordText("a", WordRunStyle(), null), WordLineBreak(WordRunStyle()), WordText("b", WordRunStyle(), null)),
            bullet(),
        )
        val blocks = (paragraphs(paragraph).single() as CanvasPasteItem.Text).blocks
        assertEquals(listOf("a", "b"), blocks.map { it.text })
        assertEquals(listOf(BlockType.Bullet, BlockType.Paragraph), blocks.map { it.type })
        assertEquals(listOf(0, 1), blocks.map { it.indent })
    }

    @Test
    fun anEquationIsAnEquationRun() {
        val paragraph = WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(WordEquation("x^2", "x2", WordRunStyle(), display = true)), null)
        val block = (paragraphs(paragraph).single() as CanvasPasteItem.Text).blocks.single()
        assertEquals(setOf<Mark>(Mark.Equation("x^2")), block.runs.single().marks)
        assertEquals(OBJECT_REPLACEMENT_CHARACTER.toString(), block.runs.single().text)
        assertEquals(Align.Center, block.align)
    }

    @Test
    fun aFormulaWithNoLatexIsItsText() {
        val paragraph = WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(WordEquation("", "x2", WordRunStyle(), display = false)), null)
        assertEquals(listOf("x2"), textOf(paragraphs(paragraph).single()))
    }

    // --- objects --------------------------------------------------------------------------------

    private fun picture(part: String? = "word/media/a.png", width: Int = 4680) = WordPicture(part, width, width / 2, null, null)

    @Test
    fun aPictureInTheFlowSplitsTheTextAroundIt() {
        val middle = WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(picture()), null)
        val items = paragraphs(para("above"), middle, para("below"))
        assertEquals(3, items.size)
        assertEquals(listOf("above"), textOf(items[0]))
        assertTrue(items[1] is CanvasPasteItem.Image)
        assertEquals(listOf("below"), textOf(items[2]))
    }

    /** Half the document's page is half of a text box, not a fixed size. */
    @Test
    fun aPictureKeepsItsShareOfThePage() {
        val paragraph = WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(picture()), null)
        val image = paragraphs(paragraph).single() as CanvasPasteItem.Image
        assertEquals(360f, image.widthDp!!, 0.5f)
        assertEquals(180f, image.heightDp!!, 0.5f)
    }

    @Test
    fun aPictureWithNothingToReadIsLeftOut() {
        val paragraph = WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(picture(part = null)), null)
        assertTrue(paragraphs(paragraph).isEmpty())
    }

    private fun pictureItems(picture: WordPicture, cropped: (ByteArray, WordCrop) -> ByteArray?): List<CanvasPasteItem> {
        val paragraph = WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(picture), null)
        return WordObjects(document, emptySet(), { byteArrayOf(7) }, cropped).items(listOf(ParagraphPiece(paragraph, bodyWidth)))
    }

    /**
     * The canvas stretches a picture to its frame. Handed the whole picture at the crop's shape, it
     * would show the parts the document cuts away, squeezed in. Trees-1.pptx crops some to a third.
     */
    @Test
    fun aCroppedPictureComesInCroppedAtTheShapeThePageShows() {
        val crop = WordCrop(0f, 0f, 0.6f, 0.7f)
        var asked: WordCrop? = null
        val image = pictureItems(WordPicture("word/media/a.png", 4680, 2340, crop, null)) { bytes, wanted ->
            asked = wanted
            bytes + 1
        }.single() as CanvasPasteItem.Image

        assertTrue(asked === crop)
        assertEquals(listOf<Byte>(7, 1), image.bytes!!.toList())
        assertEquals(360f, image.widthDp!!, 0.5f)
        assertEquals(180f, image.heightDp!!, 0.5f)
    }

    /** Where it cannot be cut, as for a metafile, the picture keeps its own shape rather than the crop's. */
    @Test
    fun aPictureThatCannotBeCroppedKeepsItsOwnShape() {
        val image = pictureItems(WordPicture("word/media/a.emf", 4680, 2340, WordCrop(0.25f, 0f, 0f, 0f), null)) { _, _ -> null }
            .single() as CanvasPasteItem.Image

        assertEquals(listOf<Byte>(7), image.bytes!!.toList())
        assertEquals(360f, image.widthDp!!, 0.5f)
        assertEquals(null, image.heightDp)
    }

    /** PowerPoint writes a crop of nothing on most pictures; that is no reason to decode one. */
    @Test
    fun aCropOfNothingIsNoCrop() {
        var asked = false
        pictureItems(WordPicture("word/media/a.png", 4680, 2340, WordCrop(0f, 0f, 0f, 0f), null)) { _, _ ->
            asked = true
            null
        }

        assertFalse(asked)
    }

    /** A drawing that gives no size would otherwise be a sliver as tall as its height times the narrowest width. */
    @Test
    fun aPictureWithNoSizeTakesItsOwn() {
        val image = pictureItems(WordPicture("word/media/a.png", 0, 2340, null, null)) { _, _ -> null }.single() as CanvasPasteItem.Image

        assertEquals(null, image.widthDp)
        assertEquals(null, image.heightDp)
    }

    private fun shape(vararg blocks: WordBlock) =
        WordShape(blocks.toList(), 4000, 2000, null, null, WordShapeForm.Rectangle, 0, 0, 0, 0, null)

    @Test
    fun aTextBoxIsItsOwnTextBox() {
        val middle = WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(shape(para("in the box"))), null)
        val items = paragraphs(para("above"), middle, para("below"))
        assertEquals(listOf(listOf("above"), listOf("in the box"), listOf("below")), items.map(::textOf))
    }

    @Test
    fun aGroupComesOutTopToBottom() {
        val low = WordGroupChild(shape(para("lower")), 0, 1000)
        val high = WordGroupChild(shape(para("upper")), 0, 0)
        val paragraph = WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(WordGroup(listOf(low, high), 500, 500, null)), null)
        assertEquals(listOf(listOf("upper"), listOf("lower")), paragraphs(paragraph).map(::textOf))
    }

    // --- tables ---------------------------------------------------------------------------------

    private fun cell(
        text: String,
        span: Int = 1,
        merge: WordVerticalMerge = WordVerticalMerge.None,
        shading: Int? = null,
        vararg more: WordBlock,
    ) = WordCell(listOf<WordBlock>(para(text)) + more, span, merge, null, shading, null, 108, 0, 108, 0, WordCellAlign.Top)

    private fun row(vararg cells: WordCell, header: Boolean = false, height: Int? = null) =
        WordRow(cells.toList(), height, false, header, false, 0, 0)

    private fun table(grid: IntArray, vararg rows: WordRow) =
        WordTable(grid, rows.toList(), null, 0, WordAlign.Left, null, null)

    private fun piece(table: WordTable, first: Int = 0, end: Int = table.rows.size) = TablePiece(table, first, end, bodyWidth)

    /** A file can hold a table of rows with no cells in them, or of no rows; there is no table to make of it. */
    @Test
    fun aTableWithNoCellsOrNoRowsBringsNothing() {
        val empty = WordRow(emptyList(), null, false, false, false, 0, 0)

        assertTrue(items(piece(table(IntArray(0), empty, empty))).isEmpty())
        assertTrue(items(piece(table(intArrayOf(1000)))).isEmpty())
    }

    /** Spans are a file's word for it; added up across a row they must not wrap round to a small number. */
    @Test
    fun spansTooWideToCountAreHeldToTheWidestTable() {
        val wide = WordCell(listOf(para("x")), Int.MAX_VALUE, WordVerticalMerge.None, null, null, null, 0, 0, 0, 0, WordCellAlign.Top)
        val built = table(intArrayOf(1000), WordRow(listOf(wide, wide, wide), null, false, false, false, Int.MAX_VALUE, -5))

        assertEquals(MAX_GRID_COLUMNS, built.columnCount)
        assertEquals(0, built.rows.single().gridAfter)
        // Too wide to be a table on the canvas, it is still its text.
        assertEquals(listOf("x\tx\tx"), textOf(items(piece(built)).single()))
    }

    @Test
    fun aTableKeepsItsProportionsAtTheScaleOfTheText() {
        val item = items(piece(table(intArrayOf(2340, 7020), row(cell("a"), cell("b"))))).single() as CanvasPasteItem.Table
        assertEquals(listOf(180f, 540f), item.outline.columns)
        assertEquals(720f, item.outline.width, 0.5f)
    }

    /** A generator that writes no grid still says, cell by cell, how wide its columns are. */
    @Test
    fun aTableWithNoGridTakesItsFirstRowsWidths() {
        fun sized(width: Int) =
            WordCell(listOf(para("x")), 1, WordVerticalMerge.None, width, null, null, 0, 0, 0, 0, WordCellAlign.Top)
        val item = items(piece(table(IntArray(0), row(sized(2340), sized(7020))))).single() as CanvasPasteItem.Table
        assertEquals(listOf(180f, 540f), item.outline.columns)
    }

    @Test
    fun spannedAndMergedCellsLeaveBlanksAndContentStaysPut() {
        val item = items(
            piece(
                table(
                    intArrayOf(1000, 1000),
                    row(cell("wide", span = 2)),
                    row(cell("tall", merge = WordVerticalMerge.Restart), cell("b")),
                    row(cell("hidden", merge = WordVerticalMerge.Continue), cell("c")),
                ),
            ),
        ).single() as CanvasPasteItem.Table
        val text = item.outline.rows.map { row -> row.cells.map { it.plainText } }
        assertEquals(listOf(listOf("wide", ""), listOf("tall", "b"), listOf("", "c")), text)
    }

    @Test
    fun aRowsHeightFloorIsKept() {
        val item = items(piece(table(intArrayOf(9360), row(cell("a")), row(cell("b"), height = 1440)))).single() as CanvasPasteItem.Table
        // 1440 twips is a sixth of the 9360-wide body twice over: 720 dp across makes it 110.8 dp.
        assertEquals(1440 * 720f / 9360, item.outline.rows[1].minHeight, 0.5f)
        assertEquals(Outline.Table.MIN_ROW_HEIGHT, item.outline.rows[0].minHeight, 0.01f)
    }

    @Test
    fun aHeaderRowIsKeptAsOne() {
        val item = items(piece(table(intArrayOf(1000), row(cell("h"), header = true), row(cell("b"))))).single() as CanvasPasteItem.Table
        assertTrue(item.outline.headerRow)
    }

    @Test
    fun aFillIsKeptOnlyWhenTheWholeTableHasIt() {
        val peach = 0xFFFFC000.toInt()
        fun fill(vararg colors: Int?): Int? = (items(
            piece(table(IntArray(colors.size) { 1000 }, row(*colors.map { cell("x", shading = it) }.toTypedArray()))),
        ).single() as CanvasPasteItem.Table).outline.fillArgb
        assertEquals(peach, fill(peach, peach))
        assertEquals(null, fill(peach, null))
    }

    /** One cell is a box drawn around something, so what is in it comes out on its own. */
    @Test
    fun aOneCellTableIsUnwrappedAndATableInsideItIsARealTable() {
        val inner = table(intArrayOf(1000, 1000), row(cell("x"), cell("y")))
        val outer = table(intArrayOf(9360), row(cell("before", 1, WordVerticalMerge.None, null, inner, para("after"))))
        val items = items(piece(outer))
        assertEquals(3, items.size)
        assertEquals(listOf("before"), textOf(items[0]))
        assertEquals(2, (items[1] as CanvasPasteItem.Table).outline.columnCount)
        assertEquals(listOf("after"), textOf(items[2]))
    }

    @Test
    fun aTableInsideACellIsThatCellsText() {
        val inner = table(intArrayOf(500, 500), row(cell("x"), cell("y")))
        val outer = table(intArrayOf(1000, 1000), row(cell("a", 1, WordVerticalMerge.None, null, inner), cell("b")))
        val item = items(piece(outer)).single() as CanvasPasteItem.Table
        assertEquals("a\nx\ty", item.outline.rows[0].cells[0].plainText)
    }

    @Test
    fun aPictureInACellIsPlacedAfterTheTable() {
        val inCell = WordCell(
            listOf(WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(picture()), null)),
            1, WordVerticalMerge.None, null, null, null, 0, 0, 0, 0, WordCellAlign.Top,
        )
        val items = items(piece(table(intArrayOf(1000, 1000), row(inCell, cell("b")))))
        assertEquals(2, items.size)
        assertTrue(items[0] is CanvasPasteItem.Table)
        assertTrue(items[1] is CanvasPasteItem.Image)
    }

    /** Past the stored grid's width a table is its text, none of its cells dropped. */
    @Test
    fun aTableTooWideForTheGridIsItsText() {
        val cells = Array(Outline.Table.MAX_COLUMNS + 1) { cell("c$it") }
        val item = items(piece(table(IntArray(cells.size) { 500 }, row(*cells)))).single()
        val line = (item as CanvasPasteItem.Text).blocks.single().text
        assertEquals(cells.indices.joinToString("\t") { "c$it" }, line)
    }

    @Test
    fun aTableTooLongForTheGridBecomesSeveralWithEveryRowKept() {
        val rows = Array(Outline.Table.MAX_ROWS + 10) { row(cell("r$it")) }
        val items = items(piece(table(intArrayOf(1000), *rows)))
        assertEquals(2, items.size)
        val tables = items.map { (it as CanvasPasteItem.Table).outline }
        assertEquals(rows.size, tables.sumOf { it.rowCount })
    }

    @Test
    fun theHalvesOfATableOnAdjacentChosenPagesJoinUp() {
        val whole = table(intArrayOf(1000), row(cell("a")), row(cell("b")), row(cell("c")))
        val items = items(piece(whole, 0, 2), piece(whole, 2, 3))
        assertEquals(3, (items.single() as CanvasPasteItem.Table).outline.rowCount)
    }

    @Test
    fun theHalvesOfDifferentTablesStayApart() {
        val first = table(intArrayOf(1000), row(cell("a")))
        val second = table(intArrayOf(1000), row(cell("b")))
        assertEquals(2, items(piece(first), piece(second)).size)
    }

    @Test
    fun aFootnoteReferenceIsNumberedAndItsTextComesAtTheEnd() {
        val paragraph = WordParagraph(
            WordParagraphStyle(),
            WordRunStyle(),
            listOf(WordText("see", WordRunStyle(), null), WordNoteRef(5, endnote = false, WordRunStyle())),
            null,
        )
        val items = paragraphs(paragraph)
        assertEquals(2, items.size)
        val runs = (items[0] as CanvasPasteItem.Text).blocks.single().runs
        assertEquals("1", runs[1].text)
        assertTrue(Mark.Superscript in runs[1].marks)
        assertEquals(listOf("1. the note"), textOf(items[1]))
    }
}
