package com.vivenotes.fileimport

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A `.doc` and an `.odt` through the whole importer: what is left to the device is laying them out. */
@RunWith(AndroidJUnit4::class)
class LegacyFormatsImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val importer = FileImporter(context)
    private val file = File(context.cacheDir, "legacy-import-test")

    @After
    fun tearDown() {
        importer.release()
        file.delete()
    }

    private fun fixture(name: String): ByteArray = instrumentation.context.assets.open("fileimport/$name").use { it.readBytes() }

    private fun assertImports(name: String) = runBlocking {
        file.writeBytes(fixture(name))
        val opened = importer.openFile(file, name)

        assertEquals(1, opened.pages.size)
        assertEquals(11f / 8.5f, opened.pages[0].heightDp / opened.pages[0].widthDp, 0.01f)
        val page = requireNotNull(importer.preview(opened, 0, 300))
        assertEquals(Color.WHITE, page.getPixel(2, 2))

        val items = importer.objects(opened, listOf(0), emptySet())
        val text = items.filterIsInstance<CanvasPasteItem.Text>().flatMap { it.blocks }.map { it.text }
        assertTrue(text.toString(), text.any { it.startsWith("Fixture heading") })
        assertTrue(text.toString(), text.any { it == "First bullet" })
        val table = items.filterIsInstance<CanvasPasteItem.Table>().single().outline
        assertEquals(2, table.rowCount)
        assertEquals(1, items.count { it is CanvasPasteItem.Image })
    }

    @Test
    fun aDocImports() = assertImports("sample.doc")

    @Test
    fun anOdtImports() = assertImports("sample.odt")

    private fun failure(bytes: ByteArray): ImportFailure {
        file.writeBytes(bytes)
        return assertThrows(ImportException::class.java) { runBlocking { importer.openFile(file, "file") } }.failure
    }

    /** Where the WordDocument stream's flags are, found by the two bytes that open the stream. */
    private fun encryptedDoc(): ByteArray {
        val bytes = fixture("sample.doc")
        val start = (0 until bytes.size - 1).first { bytes[it] == 0xEC.toByte() && bytes[it + 1] == 0xA5.toByte() }
        bytes[start + 11] = (bytes[start + 11].toInt() or 0x01).toByte()
        return bytes
    }

    @Test
    fun anEncryptedDocSaysSo() {
        assertEquals(ImportFailure.PasswordProtected, failure(encryptedDoc()))
    }

    @Test
    fun aCompoundFileThatIsNotADocumentIsNotWord() {
        val bytes = fixture("sample.doc")
        val name = "WordDocument".toByteArray(Charsets.UTF_16LE)
        val at = (0..bytes.size - name.size).first { index -> name.indices.all { bytes[index + it] == name[it] } }
        bytes[at] = 'X'.code.toByte()

        assertEquals(ImportFailure.NotWord, failure(bytes))
    }
}
