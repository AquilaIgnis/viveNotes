package com.vivenotes.fileimport

import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.fileimport.DocxTestFile.cell
import com.vivenotes.fileimport.DocxTestFile.paragraph
import com.vivenotes.fileimport.DocxTestFile.row
import com.vivenotes.fileimport.DocxTestFile.table
import com.vivenotes.fileimport.DocxTestFile.textBox
import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.DocxPages
import com.vivenotes.fileimport.docx.DocxSheet
import com.vivenotes.fileimport.docx.ParagraphPiece
import com.vivenotes.fileimport.docx.TablePiece
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordTable
import com.vivenotes.fileimport.docx.WordText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocxLayoutTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val importer = FileImporter(context)
    private val file = File(context.cacheDir, "docx-layout-test.docx")

    @After
    fun tearDown() {
        importer.release()
        file.delete()
    }

    private fun open(body: String, footer: String? = null): ImportedFile = runBlocking {
        DocxTestFile.write(file, body, footer)
        importer.openFile(file, file.name)
    }

    private fun sheets(opened: ImportedFile): List<DocxSheet> =
        runBlocking { requireNotNull(importer.withPages(opened, DocxPages::sheets)) }

    private fun WordParagraph.text(): String = inlines.filterIsInstance<WordText>().joinToString("") { it.text }

    private fun paragraphsOn(sheet: DocxSheet): List<String> =
        sheet.objectPieces().filterIsInstance<ParagraphPiece>().map { it.paragraph.text() }

    private val noSpacing = """<w:spacing w:after="0"/>"""

    private val stagingDirectory: File get() = File(context.cacheDir, "file-import")

    /** The way in from a file picker: copied to a private file, which goes when the window does. */
    @Test
    fun aPickedDocumentIsStagedAndTheStagedCopyIsThrownAway() = runBlocking {
        DocxTestFile.write(file, paragraph("from a picker"))

        val opened = importer.open(Uri.fromFile(file))

        assertEquals(1, opened.pages.size)
        assertEquals(file.name, opened.name)
        assertEquals(1, stagingDirectory.listFiles().orEmpty().size)
        importer.release()
        assertEquals(0, stagingDirectory.listFiles().orEmpty().size)
    }

    @Test
    fun aFileThatIsNotADocumentIsRefusedAndNothingIsLeftBehind() = runBlocking {
        file.writeText("just some text, whatever the name says")

        val failure = runCatching { importer.open(Uri.fromFile(file)) }.exceptionOrNull() as ImportException

        assertEquals(ImportFailure.NotWord, failure.failure)
        assertEquals(0, stagingDirectory.listFiles().orEmpty().size)
    }

    @Test
    fun aShortDocumentIsOnePageTheSizeItSays() {
        val opened = open(paragraph("just this"))
        assertEquals(1, opened.pages.size)
        // Letter at the canvas's 160 dp to the inch.
        assertEquals(1360f, opened.pages[0].widthDp, 1f)
        assertEquals(1760f, opened.pages[0].heightDp, 1f)
    }

    @Test
    fun aLongDocumentRunsOntoMorePagesWithEveryParagraphOnExactlyOne() {
        val texts = List(200) { "Paragraph number $it" }
        val pages = sheets(open(texts.joinToString("") { paragraph(it, noSpacing) }))
        assertTrue("expected several pages, got ${pages.size}", pages.size > 2)
        assertEquals(texts, pages.flatMap(::paragraphsOn))
    }

    @Test
    fun aParagraphTooLongForThePageRunsOnAndBelongsToWhereItStarted() {
        val long = List(600) { "word$it" }.joinToString(" ")
        val pages = sheets(open(List(30) { paragraph("filler $it", noSpacing) }.joinToString("") + paragraph(long, noSpacing)))
        assertTrue(pages.size >= 2)
        // Its slices meet exactly, so nothing was dropped at the cut.
        val slices = pages.flatMap { it.pieces }.filter { (it.block.source as? WordParagraph)?.text() == long }
        assertTrue("expected the paragraph to be cut, got ${slices.size}", slices.size >= 2)
        slices.zipWithNext { above, below -> assertEquals(above.to, below.from) }
        assertEquals(slices.first().block.height, slices.last().to)
        val owners = pages.count { sheet -> paragraphsOn(sheet).contains(long) }
        assertEquals(1, owners)
    }

    @Test
    fun aPageBreakStartsANewPage() {
        val pages = sheets(
            open(paragraph("first") + """<w:p><w:r><w:br w:type="page"/></w:r></w:p>""" + paragraph("second")),
        )
        assertEquals(2, pages.size)
        assertEquals(listOf("first"), paragraphsOn(pages[0]).filter(String::isNotEmpty))
        assertEquals(listOf("second"), paragraphsOn(pages[1]))
    }

    /** A page break as the last thing in the document is not a page with nothing on it. */
    @Test
    fun aFinalPageBreakAddsNoEmptyPage() {
        val pages = sheets(open(paragraph("only") + """<w:p><w:r><w:br w:type="page"/></w:r></w:p>"""))
        assertEquals(1, pages.size)
    }

    @Test
    fun aHeadingStaysWithWhatFollowsIt() {
        val filler = List(46) { paragraph("line $it", noSpacing) }.joinToString("")
        val pages = sheets(
            open(filler + paragraph("HEADING", "<w:keepNext/>$noSpacing") + paragraph("body", noSpacing)),
        )
        val withHeading = pages.first { "HEADING" in paragraphsOn(it) }
        assertTrue("body" in paragraphsOn(withHeading))
    }

    /** An empty line that lands on a boundary would otherwise start the next page a line low. */
    @Test
    fun anEmptyLineOnAPageBoundaryDoesNotStartTheNextPage() {
        val text = List(200) { paragraph("line $it", noSpacing) + "<w:p><w:pPr>$noSpacing</w:pPr></w:p>" }.joinToString("")
        val pages = sheets(open(text))
        assertTrue(pages.size > 2)
        pages.drop(1).forEach { sheet -> assertTrue("a page opened on an empty line", sheet.pieces.first().block.isBlank.not()) }
    }

    /** A bounded lookahead: Word's break is taken once the page is well filled, and not before. */
    @Test
    fun wordsOwnPageBreakIsBelievedOnlyOnAFilledPage() {
        val marker = """<w:p><w:r><w:lastRenderedPageBreak/><w:t>marked</w:t></w:r></w:p>"""
        val early = sheets(open(paragraph("just this", noSpacing) + marker))
        assertEquals("a nearly empty page is not ended by a stale marker", 1, early.size)

        val filler = List(36) { paragraph("line $it", noSpacing) }.joinToString("")
        val late = sheets(open(filler + marker))
        assertEquals(2, late.size)
        assertEquals(listOf("marked"), paragraphsOn(late[1]))
    }

    // --- tables ---------------------------------------------------------------------------------

    @Test
    fun aTableIsCutBetweenRowsWithItsHeaderRepeatedAndEveryRowOnOnePage() {
        val header = row(cell(paragraph("Head", noSpacing)), properties = "<w:tblHeader/>")
        val rows = List(120) { row(cell(paragraph("row $it", noSpacing))) }
        val body = table(1, header, *rows.toTypedArray())
        val pages = sheets(open(body))
        assertTrue(pages.size > 1)
        val pieces = pages.flatMap { it.objectPieces() }.filterIsInstance<TablePiece>()
        assertEquals(121, pieces.sumOf { it.endRow - it.firstRow })
        pieces.zipWithNext { above, below -> assertEquals(above.endRow, below.firstRow) }
        val continuation = pages[1].pieces.first()
        assertTrue(continuation.block.repeatHeight > 0)
        assertEquals((pages[0].pieces.first().to), continuation.from)
    }

    /** One row holding a page and a half of text: a layout trick, and it must not be cut off. */
    @Test
    fun aRowTallerThanAPageRunsOntoTheNextPage() {
        val paragraphs = List(90) { paragraph("cell line $it", noSpacing) }.joinToString("")
        val pages = sheets(open(table(1, row(cell(paragraphs)))))
        assertTrue("expected the row to run on, got ${pages.size} pages", pages.size >= 2)
        val slices = pages.flatMap { it.pieces }.filter { it.block.source is WordTable }
        slices.zipWithNext { above, below -> assertEquals(above.to, below.from) }
        assertEquals(slices.first().block.height, slices.last().to)
    }

    // --- boxes ----------------------------------------------------------------------------------

    /** Word lets empty paragraphs run out of the bottom of a box. They are not text to make room for. */
    @Test
    fun trailingEmptyLinesDoNotGrowATextBox() {
        val blanks = List(40) { "<w:p/>" }.joinToString("")
        val pages = sheets(open(textBox(heightPoints = 100, paragraphs = paragraph("inside") + blanks)))
        assertEquals(1, pages.size)
        // 100 points is 1000 layout units; the paragraph holding the box is that plus its spacing.
        assertTrue("box grew to ${pages[0].pieces.first().block.height}", pages[0].pieces.first().block.height < 1100)
    }

    @Test
    fun aTextBoxGrowsRatherThanClippingText() {
        val text = List(20) { paragraph("text line $it") }.joinToString("")
        val pages = sheets(open(textBox(heightPoints = 40, paragraphs = text)))
        assertTrue(pages[0].pieces.first().block.height > 400)
    }

    // --- drawing --------------------------------------------------------------------------------

    private fun inkIn(opened: ImportedFile, index: Int, top: Float, bottom: Float): Int = runBlocking {
        val bitmap = requireNotNull(importer.preview(opened, index, WIDTH_PX))
        var ink = 0
        for (y in (bitmap.height * top).toInt() until (bitmap.height * bottom).toInt()) {
            for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) != Color.WHITE) ink++
        }
        ink
    }

    @Test
    fun aPageDrawsAsInkOnWhitePaper() {
        val opened = open(paragraph("Some words on the page"))
        assertTrue(inkIn(opened, 0, 0f, 1f) > 0)
        // Nothing below the one line of text.
        assertEquals(0, inkIn(opened, 0, 0.5f, 1f))
    }

    @Test
    fun aFooterIsDrawnOnEveryPage() {
        val footer = """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:fldSimple w:instr=" PAGE "><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p>"""
        val body = List(120) { paragraph("line $it", noSpacing) }.joinToString("")
        val opened = open(body, footer)
        assertTrue(opened.pages.size >= 2)
        opened.pages.indices.forEach { assertTrue("no footer on page $it", inkIn(opened, it, 0.93f, 1f) > 0) }
    }

    private companion object {
        const val WIDTH_PX = 612
    }
}
