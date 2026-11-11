package com.vivenotes.fileimport

import com.vivenotes.fileimport.docx.DocxPackage
import com.vivenotes.fileimport.docx.DocxReader
import com.vivenotes.fileimport.docx.ParagraphPiece
import com.vivenotes.fileimport.docx.TablePiece
import com.vivenotes.fileimport.docx.WordCrop
import com.vivenotes.fileimport.docx.WordObjects
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordTable
import com.vivenotes.fileimport.docx.WordText
import com.vivenotes.fileimport.pptx.PptxObject
import com.vivenotes.fileimport.pptx.PptxRead
import com.vivenotes.fileimport.pptx.PptxReader
import com.vivenotes.fileimport.pptx.piecesOf
import com.vivenotes.model.BlockType
import com.vivenotes.richtext.CanvasPasteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What real documents come to as objects: two lecture handouts from Word and two decks from
 * PowerPoint, kept in `src/realDocuments`. The same course's lectures as PDF, and the pages these
 * break into, need a device and are in the instrumented `RealDocumentsImportTest`.
 *
 * The other tests here pin one rule each against a file made for it. These pin what the rules add up
 * to on files nobody made for them, which is where a fix for one producer has broken another before.
 */
class RealDocumentsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun real(name: String): File {
        val bytes = requireNotNull(RealDocumentsTest::class.java.getResourceAsStream("/fileimport/$name")) { "$name is not in src/realDocuments" }
            .use { it.readBytes() }
        return folder.newFile(name).apply { writeBytes(bytes) }
    }

    /** Everything in a Word file, as the import window's Select all brings it in. */
    private fun wordItems(name: String): List<CanvasPasteItem> = DocxPackage.open(real(name)).use { pkg ->
        val document = DocxReader(pkg).read()
        val pieces = document.sections.flatMap { it.blocks }.mapNotNull { block ->
            when (block) {
                is WordParagraph -> ParagraphPiece(block, BODY_WIDTH_TWIPS)
                is WordTable -> TablePiece(block, 0, block.rows.size, BODY_WIDTH_TWIPS)
                else -> null
            }
        }
        WordObjects(document, emptySet(), { pkg.bytes(it) }).items(pieces)
    }

    private fun <T> deck(name: String, use: (PptxRead, DocxPackage) -> T): T =
        DocxPackage.open(real(name), DocxPackage.PRESENTATION_CONTENT_TYPES).use { pkg -> use(PptxReader(pkg).read(), pkg) }

    private fun tables(items: List<CanvasPasteItem>): List<Triple<Int, Int, Boolean>> =
        items.filterIsInstance<CanvasPasteItem.Table>().map { Triple(it.outline.rowCount, it.outline.columnCount, it.outline.headerRow) }

    private fun blocks(items: List<CanvasPasteItem>) = items.filterIsInstance<CanvasPasteItem.Text>().flatMap { it.blocks }

    private fun headings(items: List<CanvasPasteItem>, type: BlockType): List<String> =
        blocks(items).filter { it.type == type }.map { it.text.trim() }

    /**
     * lec1's questions sit in one-cell tables that are only a box drawn round them, and its real
     * tables sit inside text boxes and those boxes. The boxes go and what is in them stays.
     */
    @Test
    fun lec1sTablesComeInAsTablesAndItsBoxedQuestionsAsText() {
        val items = wordItems("lec1.docx")

        assertEquals(listOf(Triple(3, 7, true), Triple(5, 2, true), Triple(2, 1, false), Triple(4, 4, false)), tables(items))
        assertTrue(blocks(items).any { it.text.startsWith("Think about differences in efficiency") })
        assertEquals(
            listOf("A. IEEE Floating Point Standard", "B. Precision and Range", "C. Performance", "D. Big Picture"),
            headings(items, BlockType.Heading2),
        )
        assertEquals(listOf("E. Submission"), headings(items, BlockType.Heading1))
    }

    @Test
    fun lec2sPicturesAndTablesComeIn() {
        val items = wordItems("lec2.docx")

        assertEquals(3, items.count { it is CanvasPasteItem.Image })
        assertEquals(listOf(Triple(4, 2, false), Triple(2, 2, false)), tables(items))
        assertTrue(headings(items, BlockType.Heading1).containsAll(listOf("Introduction", "MIPS To Binary", "Register File", "Submission")))
    }

    @Test
    fun aDeckIsOnePageForEachSlide() {
        deck("Graphs-1.pptx") { read, _ ->
            assertEquals(122, read.slides.size)
            assertEquals(122, read.document.sections.size)
            assertEquals(14_400, read.document.sections.first().page.widthTwips)
            assertEquals(10_800, read.document.sections.first().page.heightTwips)
        }
        deck("Trees-1.pptx") { read, _ -> assertEquals(70, read.slides.size) }
    }

    @Test
    fun aSlidesTitleIsAHeadingAndItsTablesAreTables() {
        deck("Graphs-1.pptx") { read, pkg ->
            fun slide(number: Int): List<CanvasPasteItem> =
                WordObjects(read.document, emptySet(), { pkg.bytes(it) }).items(piecesOf(read.slides[number - 1], read.slideWidthTwips, read.figureParts))

            assertEquals(listOf("Erdős numbers"), headings(slide(67), BlockType.Heading1))
            assertEquals(listOf(Triple(15, 2, false)), tables(slide(67)).map { (rows, columns, _) -> Triple(rows, columns, false) })
            assertEquals(listOf(11 to 2), tables(slide(68)).map { (rows, columns, _) -> rows to columns })
        }
    }

    /**
     * Trees-1 shows one picture twice on slide 22, the second time with all but its top-left corner
     * cut away. The canvas stretches what it is given to the frame, so the crop is made on the way in.
     */
    @Test
    fun aCroppedSlidePictureIsCutToWhatTheSlideShows() {
        deck("Trees-1.pptx") { read, pkg ->
            val asked = ArrayList<WordCrop>()
            fun slide(number: Int): List<CanvasPasteItem.Image> {
                val cropped = { bytes: ByteArray, crop: WordCrop -> bytes.also { asked += crop } }
                return WordObjects(read.document, emptySet(), { pkg.bytes(it) }, cropped)
                    .items(piecesOf(read.slides[number - 1], read.slideWidthTwips, read.figureParts))
                    .filterIsInstance<CanvasPasteItem.Image>()
            }

            val pictures = slide(22)
            assertEquals(2, pictures.size)
            val crop = asked.single()
            assertEquals(0.62915f, crop.right, 0.0001f)
            assertEquals(0.7251f, crop.bottom, 0.0001f)
            // The frame is the slide's, which stretches both copies alike; the uncropped one keeps it too.
            assertEquals(0.650f, pictures[0].widthDp!! / pictures[0].heightDp!!, 0.005f)
            assertEquals(0.876f, pictures[1].widthDp!! / pictures[1].heightDp!!, 0.005f)

            asked.clear()
            slide(23)
            assertEquals(0.11351f, asked.single().bottom, 0.0001f)
        }
    }

    /** Slide 31 has an arrow straight down between two text boxes: no pixels wide, it was a sliver of a picture. */
    @Test
    fun anArrowWithNoBreadthComesToNothing() {
        deck("Graphs-1.pptx") { read, _ ->
            val objects = read.slides[30]

            // The other arrow runs on a slant, and is a drawing like any lone shape.
            assertEquals(listOf(723 to 551), objects.filterIsInstance<PptxObject.Figure>().map { it.width to it.height })
            val opening = objects.filterIsInstance<PptxObject.Text>().map { item ->
                item.paragraphs.first().inlines.filterIsInstance<WordText>().joinToString("") { it.text }.take(12)
            }
            assertEquals(listOf("Degree of Ve", "Theorem 2: A", "must be even", "This sum mus"), opening)
        }
    }

    private companion object {
        /** Letter with an inch either side, which is what both handouts are set on. */
        const val BODY_WIDTH_TWIPS = 9360
    }
}
