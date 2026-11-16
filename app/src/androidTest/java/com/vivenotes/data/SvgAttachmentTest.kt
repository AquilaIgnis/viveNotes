package com.vivenotes.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.AttachmentStore.Companion.sha256
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class SvgAttachmentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java).build()
    private val store = AttachmentStore(context, db)
    private val imported = mutableListOf<String>()
    private val source = """<svg xmlns="http://www.w3.org/2000/svg" width="80" height="40" viewBox="0 0 80 40"><path fill="#ff0000" d="M40 0h40v40H40z"/></svg>""".encodeToByteArray()

    @After fun close() {
        imported.forEach { store.fileFor(it).delete() }
        db.close()
    }

    @Test fun svgBytesArePreservedDeduplicatedAndRenderedAsVectors() = runBlocking {
        val first = store.import(source)!!
        imported += first.id
        val second = store.import(source)!!
        assertEquals(first, second)
        assertEquals(source.sha256(), first.id)
        assertArrayEquals(source, store.fileFor(first.id).readBytes())
        val metadata = db.attachmentDao().byId(first.id)!!
        assertEquals("image/svg+xml", metadata.mimeType)
        assertEquals(source.size.toLong(), metadata.byteCount)
        assertEquals(2, metadata.refCount)
        val svg = store.loadSvg(first.id)!!
        // Render well beyond the source's nominal pixel size; no stored raster is involved.
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        try {
            svg.draw(Canvas(bitmap), 0f, 0f, 800f, 400f)
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(200, 200))
            assertEquals(Color.RED, bitmap.getPixel(600, 200))
        } finally { bitmap.recycle() }
        val ocr = store.loadBitmap(first.id, 160)!!
        assertEquals(160 to 80, ocr.width to ocr.height)
        ocr.recycle()
        assertArrayEquals(source, store.fileFor(first.id).readBytes())
        assertEquals(80, boundsOf(store.fileFor(first.id), "image/svg+xml")!!.width)
    }

    @Test fun pickingAnSvgFileKeepsItsOriginalBytes() = runBlocking {
        val file = File(context.cacheDir, "import-fixture.svg").apply { writeBytes(source) }
        try {
            val picture = store.import(Uri.fromFile(file))!!
            imported += picture.id
            assertArrayEquals(source, store.fileFor(picture.id).readBytes())
        } finally { file.delete() }
    }

    @Test fun largeSvgWithoutViewBoxScalesItsWholeViewport() {
        val svg = SvgPicture.read("""<svg xmlns="http://www.w3.org/2000/svg" width="8192" height="4096"><path fill="red" d="M4096 0h4096v4096H4096z"/></svg>""".encodeToByteArray())!!
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            svg.draw(Canvas(bitmap), 0f, 0f, 80f, 40f)
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(20, 20))
            assertEquals(Color.RED, bitmap.getPixel(60, 20))
        } finally { bitmap.recycle() }
    }

    @Test fun svgDrawsAsPathsInPdfWithoutAnEmbeddedBitmap() {
        val svg = SvgPicture.read(source)!!
        val pdf = PdfDocument()
        val bytes = try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(800, 400, 1).create())
            svg.draw(page.canvas, 0f, 0f, 800f, 400f)
            pdf.finishPage(page)
            ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray()
        } finally { pdf.close() }
        assertTrue(bytes.toString(Charsets.ISO_8859_1).startsWith("%PDF-"))
        assertFalse(Regex("/Subtype\\s*/Image").containsMatchIn(bytes.toString(Charsets.ISO_8859_1)))
    }
}
