package com.vivenotes.ui

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.CanvasClipboardSource
import com.vivenotes.data.ClipboardContent
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaultsStore
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PenSettingsStore
import com.vivenotes.data.ViewSettingsStore
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.CANVAS_PASTE_MARGIN
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PasteFrames
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageStyle
import com.vivenotes.model.newTable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * What another app copied, pasted onto the canvas: text becomes a box, a picture a picture, an HTML
 * table an editable table — each one step of Undo, and none of it landing on a page opened since.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CanvasExternalPasteTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var db: NotesDatabase
    private lateinit var attachments: AttachmentStore
    private lateinit var vm: NotesViewModel
    private val source = Source()
    private lateinit var scratch: java.io.File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val repository = NotesRepository(db)
        runBlocking { repository.seedIfEmpty() }
        // On the test's own scheduler, so a picture's import has finished when the scheduler idles —
        // and in a folder of its own, not among the installed app's pictures.
        val files = java.io.File(context.cacheDir, "canvas-paste-test").also { scratch = it }
        attachments = AttachmentStore(object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = files
        }, db, io = dispatcher)
        vm = NotesViewModel(repository, attachments, EditorDefaultsStore(context), ViewSettingsStore(context),
            PenSettingsStore(context), inkDispatcher = dispatcher, systemClipboard = source,
            pasteDispatcher = dispatcher)
        scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
        db.close()
        Dispatchers.resetMain()
    }

    private val text = Outline.Text.empty().copy(blocks = listOf(Block.of("Copied text")))
    private val table = newTable(2, 2).copy(y = 120f)

    private fun pageId() = vm.uiState.value.selectedPageId!!

    /** Pastes, and answers the canvas's measurement the way [heights] says it would. */
    private fun kotlinx.coroutines.test.TestScope.paste(at: InkPoint, heights: (Outline) -> Float = { 80f }) {
        vm.refreshClipboard()
        vm.pasteObjects(at)
        // Not idle yet: that would let the wait for a measurement run out in virtual time.
        runCurrent()
        vm.pendingPasteLayout.value?.let { request ->
            vm.completePasteLayout(request, PasteFrames(request.outlines.associate { it.id to heights(it) }))
        }
        advanceUntilIdle()
    }

    @Test
    fun insertsObjectsAtThePastePointInOneUndoStep() = runTest(dispatcher) {
        val before = vm.uiState.value
        assertTrue(vm.insertClipboardOutlines(pageId(), listOf(text, table), 80f, 250f))
        val box = vm.uiState.value.outlines.last()
        val pasted = vm.uiState.value.tables.single()
        assertEquals(80f, box.x)
        assertEquals(250f, box.y)
        assertEquals(370f, pasted.y)
        assertEquals("Copied text", vm.initialBlocksFor(box.id).single().text)
        vm.undoCanvas()
        assertEquals("one Undo takes the whole paste back", before.outlines, vm.uiState.value.outlines)
        assertTrue(vm.uiState.value.tables.isEmpty())
        vm.redoCanvas()
        assertEquals(1, vm.uiState.value.tables.size)
        assertEquals(before.outlines.size + 1, vm.uiState.value.outlines.size)
    }

    @Test
    fun repeatedPastesGetFreshOutlineRowAndCellIds() = runTest(dispatcher) {
        vm.insertClipboardOutlines(pageId(), listOf(text, table), 20f, 200f)
        vm.insertClipboardOutlines(pageId(), listOf(text, table), 60f, 300f)
        val tables = vm.uiState.value.tables
        assertEquals(2, tables.map { it.id }.distinct().size)
        assertEquals(4, tables.flatMap { it.rows }.map { it.id }.distinct().size)
        assertEquals(8, tables.flatMap { it.cellIds() }.distinct().size)
        assertEquals(2, vm.uiState.value.outlines.takeLast(2).map { it.id }.distinct().size)
    }

    @Test
    fun anotherPageOrBadCoordinatesRejectThePasteAndTheTitleStaysClear() = runTest(dispatcher) {
        assertFalse(vm.insertClipboardOutlines("another-page", listOf(text), 20f, 200f))
        assertFalse(vm.insertClipboardOutlines(pageId(), listOf(text), Float.NaN, 200f))
        assertFalse(vm.insertClipboardOutlines(pageId(), emptyList(), 20f, 200f))
        vm.selectTool(DrawTool.Text)
        assertTrue(vm.insertClipboardOutlines(pageId(), listOf(text), -20f, -100f))
        val added = vm.uiState.value.outlines.last()
        assertEquals(0f, added.x)
        assertTrue(added.y >= PageStyle.TITLE_BAND_DP)
        assertEquals("pasting puts the placement tool down", DrawTool.None, vm.tool.value)
    }

    @Test
    fun anotherAppsTextBecomesABoxInTheDefaultFont() = runTest(dispatcher) {
        source.content = ClipboardContent(text = "A new box\r\nSecond line")
        paste(InkPoint(300f, 400f))
        val box = vm.uiState.value.outlines.last()
        assertEquals(listOf("A new box", "Second line"), vm.initialBlocksFor(box.id).map { it.text })
        assertEquals(300f, box.x)
        assertEquals(400f, box.y)
        vm.undoCanvas()
        assertTrue(vm.uiState.value.outlines.none { it.id == box.id })
    }

    @Test
    fun aBrowserTableBecomesAnEditableTableWithItsMarks() = runTest(dispatcher) {
        source.content = ClipboardContent(html = "<table><tr><th>Name</th><th>Value</th></tr><tr><td><b>Alpha</b></td><td>42</td></tr></table>")
        paste(InkPoint(200f, 300f))
        val pasted = vm.uiState.value.tables.single()
        assertFalse(pasted.inkOnly)
        assertTrue(pasted.headerRow)
        assertEquals("42", vm.initialBlocksFor(pasted.cellAt(1, 1)!!.id).single().text)
        assertTrue(vm.initialBlocksFor(pasted.cellAt(1, 0)!!.id).flatMap { it.runs }.all { Mark.Bold in it.marks })
    }

    @Test
    fun textAroundATableIsStackedByWhatTheCanvasMeasured() = runTest(dispatcher) {
        source.content = ClipboardContent(html = "<p>Before</p><table><tr><td>A</td></tr></table><p>After</p>")
        paste(InkPoint(100f, 200f)) { if (it is Outline.Table) 120f else 50f }
        val boxes = vm.uiState.value.outlines.takeLast(2)
        val pasted = vm.uiState.value.tables.single()
        assertEquals(200f, boxes[0].y)
        assertEquals(200f + 50f + CANVAS_PASTE_MARGIN, pasted.y)
        assertEquals(pasted.y + 120f + CANVAS_PASTE_MARGIN, boxes[1].y)
        assertNull(vm.pendingPasteLayout.value)
        vm.undoCanvas()
        assertTrue("one Undo for text, table and text", vm.uiState.value.tables.isEmpty())
    }

    /** With no canvas to measure it — the editor left the screen — a paste still lands, by the document's sizes. */
    @Test
    fun aPasteNoCanvasMeasuresStillLands() = runTest(dispatcher) {
        source.content = ClipboardContent(html = "<p>Before</p><table><tr><td>A</td></tr></table>")
        vm.refreshClipboard()
        vm.pasteObjects(InkPoint(100f, 200f))
        advanceUntilIdle()
        assertNull(vm.pendingPasteLayout.value)
        val pasted = vm.uiState.value.tables.single()
        assertTrue(pasted.y > vm.uiState.value.outlines.last().y + CANVAS_PASTE_MARGIN)
    }

    @Test
    fun aCopiedPictureIsStoredAndPlacedWithItsAspectRatio() = runTest(dispatcher) {
        source.content = ClipboardContent(image = png(40, 20))
        paste(InkPoint(150f, 250f))
        val image = vm.uiState.value.images.single()
        assertEquals(Outline.Image.DEFAULT_WIDTH, image.width)
        assertEquals(Outline.Image.DEFAULT_WIDTH / 2, image.height)
        assertTrue(attachments.hasFile(image.attachmentId))
        assertTrue("a picture on the clipboard needs no download", source.downloads.isEmpty())
    }

    @Test
    fun aPictureThatCannotBeFetchedPastesItsAddressAsText() = runTest(dispatcher) {
        source.content = ClipboardContent(text = "https://example.org/missing.png")
        source.picture = null
        paste(InkPoint(150f, 250f))
        assertEquals(listOf("https://example.org/missing.png"), source.downloads)
        assertTrue(vm.uiState.value.images.isEmpty())
        val box = vm.uiState.value.outlines.last()
        assertEquals("https://example.org/missing.png", vm.initialBlocksFor(box.id).single().text)
    }

    @Test
    fun anotherAppsCopyTakesPrecedenceOverAnOlderObjectCopy() = runTest(dispatcher) {
        val box = vm.createOutline(120f, 300f)
        vm.onBlocksChanged(box, listOf(Block.of("ours")))
        vm.copyOutline(box)
        assertTrue(source.internal)
        assertTrue(vm.hasClipboard.value)
        val count = vm.uiState.value.outlines.size
        vm.pasteObjects(InkPoint(400f, 500f))
        advanceUntilIdle()
        assertEquals(count + 1, vm.uiState.value.outlines.size)
        assertEquals("ours", vm.initialBlocksFor(vm.uiState.value.outlines.last().id).single().text)

        source.internal = false
        source.content = ClipboardContent(text = "Copied in the browser")
        paste(InkPoint(400f, 700f))
        assertEquals("Copied in the browser", vm.initialBlocksFor(vm.uiState.value.outlines.last().id).single().text)
    }

    @Test
    fun aPictureStillDownloadingDoesNotLandOnAPageOpenedMeanwhile() = runTest(dispatcher) {
        val original = pageId()
        val ready = CompletableDeferred<Unit>()
        source.ready = ready
        source.picture = png(4, 2)
        source.content = ClipboardContent(text = "https://example.org/slow.png")
        vm.refreshClipboard()
        vm.pasteObjects(InkPoint(150f, 250f))
        advanceUntilIdle()
        assertEquals(1, source.downloads.size)
        vm.addPage()
        advanceUntilIdle()
        assertTrue(pageId() != original)
        ready.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.images.isEmpty())
        vm.openPage(original)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.images.isEmpty())
    }

    /** Both pictures are asked for before either has arrived: downloads run side by side. */
    @Test
    fun picturesDownloadAtOnceAndThePasteShowsWhereItWillLand() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        source.ready = gate
        source.picture = png(4, 2)
        source.content = ClipboardContent(html = "<img src='https://example.org/a.png'><p>Between</p><img src='https://example.org/b.png'>")
        vm.refreshClipboard()
        vm.pasteObjects(InkPoint(150f, 250f))
        runCurrent()
        assertEquals(listOf("https://example.org/a.png", "https://example.org/b.png"), source.downloads)
        assertEquals(listOf(PendingPaste(pageId(), InkPoint(150f, 250f))), vm.pendingPastes.value)

        gate.complete(Unit)
        runCurrent()
        vm.pendingPasteLayout.value?.let { request ->
            vm.completePasteLayout(request, PasteFrames(request.outlines.associate { it.id to 40f }))
        }
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.images.size)
        assertTrue("landed, so nothing is pending", vm.pendingPastes.value.isEmpty())
    }

    @Test
    fun aCopiedBitmapLeavesTheMarkupsPicturesUndownloaded() = runTest(dispatcher) {
        source.content = ClipboardContent(html = "<p>Preview</p><img src='https://example.org/preview.png'>", image = png(40, 20))
        paste(InkPoint(150f, 250f))
        assertEquals(1, vm.uiState.value.images.size)
        assertTrue(source.downloads.isEmpty())
    }

    @Test
    fun aFailedPasteIsNoLongerPending() = runTest(dispatcher) {
        source.failing = true
        source.content = ClipboardContent(text = "anything")
        paste(InkPoint(150f, 250f))
        assertTrue(vm.pendingPastes.value.isEmpty())
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private class Source : CanvasClipboardSource {
        var content = ClipboardContent()
        var internal = false
        var picture: ByteArray? = null
        var ready: CompletableDeferred<Unit>? = null
        val downloads = mutableListOf<String>()
        override fun hasContent() = !internal &&
            (content.text != null || content.html != null || content.image != null || content.imageUrl != null)
        var failing = false
        override suspend fun readContent(): ClipboardContent = if (failing) error("unreadable") else content
        override suspend fun imageBytes(address: String): ByteArray? {
            downloads += address
            ready?.await()
            return picture
        }
        override fun markInternalCopy() { internal = true }
        override fun ownsInternalCopy() = internal
    }
}
