package com.vivenotes.data

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.richtext.CanvasPasteItem
import com.vivenotes.richtext.canvasPasteItems
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Base64

/**
 * The system clipboard as the canvas reads it. Hosted in an activity: Android lets only the app in
 * focus read the clipboard.
 */
class SystemClipboardContentTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val reader = SystemClipboardContent(context, download = { null })

    @Before
    fun focus() {
        compose.setContent { Text("clipboard") }
        compose.waitForIdle()
    }

    /** Set and read on the main thread in one go: the emulator's host sharing may replace the clip. */
    private fun <T> withClip(clip: ClipData?, read: () -> T): T {
        var result: T? = null
        compose.runOnIdle {
            if (clip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(clip)
            result = read()
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun aBrowsersHtmlTableIsReadAndPastesAsATable() {
        val html = "<table><tr><th>Café</th></tr><tr><td>日本 😀</td></tr></table>"
        val content = withClip(ClipData.newHtmlText("page", "Café\n日本 😀", html)) {
            assertTrue(reader.hasContent())
            runBlocking { reader.readContent() }
        }
        assertEquals(html, content.html)
        assertEquals("Café\n日本 😀", content.text)
        val table = (canvasPasteItems(content).single() as CanvasPasteItem.Table).outline
        assertEquals("日本 😀", table.cellAt(1, 0)!!.plainText)
    }

    /** A picture another app shares through the clip arrives as a `content://` address, read through its grant. */
    @Test
    fun aPictureSharedByAddressIsReadAsItsBytes() {
        val folder = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val file = java.io.File(folder, "clipboard-picture.png")
        val png = java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(4, 2, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        file.writeBytes(png)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
            val content = withClip(ClipData.newUri(context.contentResolver, "picture", uri)) {
                assertTrue(reader.hasContent())
                runBlocking { reader.readContent() }
            }
            assertTrue(png.contentEquals(content.image))
            assertTrue(canvasPasteItems(content).single() is CanvasPasteItem.Image)
        } finally {
            file.delete()
        }
    }

    @Test
    fun plainTextIsReadAsText() {
        val content = withClip(ClipData.newPlainText("note", "Café 日本 😀")) { runBlocking { reader.readContent() } }
        assertEquals("Café 日本 😀", content.text)
        assertNull(content.html)
        assertNull(content.image)
    }

    @Test
    fun anObjectCopyIsOwnedUntilAnotherCopyReplacesIt() {
        withClip(null) {
            reader.markInternalCopy()
            assertTrue(reader.ownsInternalCopy())
            assertFalse("an object copy holds nothing another app could paste", reader.hasContent())
            assertEquals(ClipboardContent(), runBlocking { reader.readContent() })
        }
        withClip(ClipData.newPlainText("note", "external text")) {
            assertFalse(reader.ownsInternalCopy())
            assertTrue(reader.hasContent())
        }
        val other = SystemClipboardContent(context)
        withClip(null) {
            other.markInternalCopy()
            assertFalse("another reader's copy is not this one's", reader.ownsInternalCopy())
        }
        withClip(null) {
            reader.markInternalCopy()
            assertTrue(reader.ownsInternalCopy())
        }
    }

    @Test
    fun dataAddressesDecodeAndUnsafeSchemesAreRefused() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        assertTrue(bytes.contentEquals(reader.imageBytes("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))))
        assertNull(reader.imageBytes("data:image/png,raw"))
        assertNull(reader.imageBytes("file:///etc/passwd"))
        assertNull(reader.imageBytes("javascript:alert(1)"))
        assertNull(reader.imageBytes("content://com.vivenotes/picture"))
    }

    @Test
    fun aPicturesAddressGoesToTheDownloader() = runBlocking {
        val asked = mutableListOf<String>()
        val fetching = SystemClipboardContent(context, download = { asked += it; byteArrayOf(9) })
        assertTrue(byteArrayOf(9).contentEquals(fetching.imageBytes("http://example.org/photo.png")))
        assertEquals(listOf("http://example.org/photo.png"), asked)
        assertTrue(canvasPasteItems(ClipboardContent(text = "https://example.org/photo.png")).single() is CanvasPasteItem.Image)
    }
}
