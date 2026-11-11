package com.vivenotes.fileimport

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.model.BlockType
import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real documents through the whole importer on a device: the pages its layout makes of two Word
 * handouts, what two PowerPoint decks and the same course's lectures as PDF come to as objects.
 * They are kept in `src/realDocuments`; the JVM `RealDocumentsTest` reads the Word and PowerPoint
 * ones on CI, and only a device can render the PDFs.
 *
 * PDF layout is read off positions and pixels, and a fix for one producer has broken another before:
 * these slides, with their coloured tables and drawn join symbols, are what it was tuned on.
 */
@RunWith(AndroidJUnit4::class)
class RealDocumentsImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val importer = FileImporter(context)
    private val staged = ArrayList<File>()

    @After
    fun tearDown() {
        importer.release()
        staged.forEach(File::delete)
    }

    private fun open(name: String): ImportedFile = runBlocking {
        val file = File(context.cacheDir, "real-document-$name")
        file.writeBytes(instrumentation.context.assets.open("fileimport/$name").use { it.readBytes() })
        staged += file
        importer.openFile(file, name)
    }

    /** What page [number], counted from one as the window counts, comes to as objects. */
    private fun objects(file: ImportedFile, number: Int): List<CanvasPasteItem> =
        runBlocking { importer.objects(file, listOf(number - 1), emptySet()) }

    private fun tables(items: List<CanvasPasteItem>): List<String> = items.filterIsInstance<CanvasPasteItem.Table>().map { table ->
        "${table.outline.rowCount}x${table.outline.columnCount}" + if (table.outline.headerRow) " header" else ""
    }

    /** Word's own count for both, which this layout reaches with stand-in fonts. */
    @Test
    fun theHandoutsBreakIntoTheirPagesAsWordDid() {
        assertEquals(5, open("lec1.docx").pages.size)
        assertEquals(7, open("lec2.docx").pages.size)
    }

    @Test
    fun aDeckIsAPageForEachSlide() {
        val deck = open("Graphs-1.pptx")
        assertEquals(122, deck.pages.size)
        assertEquals(4f / 3f, deck.pages.first().widthDp / deck.pages.first().heightDp, 0.001f)
        assertEquals(70, open("Trees-1.pptx").pages.size)
    }

    /** Slide 22 shows one picture whole and again with all but its top-left corner cut away. */
    @Test
    fun aCroppedSlidePictureComesInAsTheRegionTheSlideShows() {
        val pictures = objects(open("Trees-1.pptx"), 22).filterIsInstance<CanvasPasteItem.Image>()

        val sizes = pictures.map { picture ->
            val bytes = requireNotNull(picture.bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            bounds.outWidth to bounds.outHeight
        }
        assertEquals(listOf(429 to 720, 159 to 198), sizes)
    }

    @Test
    fun theLecturesAsPdfComeInSlideBySlide() {
        val first = open("L1.pdf")
        assertEquals(23, first.pages.size)
        val title = (objects(first, 1).single() as CanvasPasteItem.Text).blocks.first()
        assertEquals(BlockType.Heading1, title.type)
        assertEquals("Database Fundamentals", title.text.trim())
        // PowerPoint's tables are coloured cells with white between them, and a dark header row.
        assertEquals(listOf("4x7 header", "3x3 header", "6x4 header", "11x7 header"), tables(objects(first, 10)))

        val second = open("L2.pdf")
        assertEquals(30, second.pages.size)
        assertEquals(listOf("10x8 header", "6x7 header"), tables(objects(second, 15)))
        assertEquals(listOf("2x3 header", "6x3 header", "3x3 header", "3x3 header", "4x2 header"), tables(objects(second, 28)))
    }

    @Test
    fun everyPdfSlideBringsSomethingIn() {
        for (name in listOf("L1.pdf", "L2.pdf")) {
            val pdf = open(name)
            for (number in 1..pdf.pages.size) assertTrue("$name slide $number", objects(pdf, number).isNotEmpty())
        }
    }
}
