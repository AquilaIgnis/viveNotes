package com.vivenotes.fileimport

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A `.pptx` through the whole importer: what is left to the device is drawing its slides. */
@RunWith(AndroidJUnit4::class)
class PptxImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val importer = FileImporter(context)
    private val file = File(context.cacheDir, "pptx-import-test")

    @After
    fun tearDown() {
        importer.release()
        file.delete()
    }

    private fun opened(): ImportedFile = runBlocking {
        file.writeBytes(instrumentation.context.assets.open("fileimport/sample.pptx").use { it.readBytes() })
        importer.openFile(file, "sample.pptx")
    }

    @Test
    fun eachSlideIsAPageOfTheSlidesShape() {
        val deck = opened()

        assertEquals(2, deck.pages.size)
        assertEquals(4f / 3f, deck.pages[0].widthDp / deck.pages[0].heightDp, 0.01f)
    }

    @Test
    fun aSlideDrawsWhatIsOnIt() = runBlocking {
        val picture = requireNotNull(importer.preview(opened(), 0, 720))
        val pictureX = 6_000_000 / 12700 + 36
        val pictureY = 4_000_000 / 12700 + 36

        assertEquals(720, picture.width)
        assertEquals(540, picture.height)
        assertEquals(Color.WHITE, picture.getPixel(2, 2))
        assertEquals(0xFFD02020.toInt(), picture.getPixel(pictureX, pictureY))
    }

    @Test
    fun aSlideComesInAsTextATablePicturesAndItsDrawing() = runBlocking {
        val items = importer.objects(opened(), listOf(0), emptySet())

        val text = items.filterIsInstance<CanvasPasteItem.Text>().flatMap { it.blocks }.map { it.text }
        assertEquals(listOf("Slide heading", "First bullet", "Second bullet"), text.filter { it.isNotBlank() })
        val table = items.filterIsInstance<CanvasPasteItem.Table>().single().outline
        assertEquals(2, table.rowCount)
        assertEquals(listOf("Dustin", "45"), table.rows[1].cells.map { it.plainText })
        // The picture, and the arrow cut out as a picture of a drawing.
        val pictures = items.filterIsInstance<CanvasPasteItem.Image>()
        assertEquals(2, pictures.size)
        assertTrue(pictures.all { requireNotNull(it.bytes).isNotEmpty() })
    }

    @Test
    fun eachSlideIsItsOwnTextBox() = runBlocking {
        val items = importer.objects(opened(), listOf(0, 1), emptySet())

        assertEquals("Slide heading", (items.last() as CanvasPasteItem.Text).blocks.single().text)
    }
}
