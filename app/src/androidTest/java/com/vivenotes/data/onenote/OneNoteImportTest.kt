package com.vivenotes.data.onenote

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.NotebookTransferException
import com.vivenotes.data.NotebookTransferManager
import com.vivenotes.data.db.NotesDatabase
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * File ▸ Import ▸ Notebook given OneNote's files: converted by the Rust library, then installed by
 * the same validated `.vive` import.
 *
 * Fixtures, in `assets/onenote/`, are onenote.rs's own samples (MPL-2.0, see the NOTICE there):
 * `section.one` is one section of one page with one stroke; `notebook.onepkg` is a cabinet of a
 * table of contents and two sections, three pages in all and one picture; `toc.onetoc2` is a
 * table of contents alone.
 */
@RunWith(AndroidJUnit4::class)
class OneNoteImportTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var db: NotesDatabase
    private lateinit var attachmentStore: AttachmentStore
    private lateinit var transfers: NotebookTransferManager

    @Before
    fun setUp() {
        val context = instrumentation.targetContext
        root = File(context.cacheDir, "onenote-import-test")
        root.deleteRecursively()
        root.mkdirs()
        db = Room.databaseBuilder(context, NotesDatabase::class.java, File(root, "notes.db").absolutePath)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .allowMainThreadQueries()
            .build()
        attachmentStore = AttachmentStore(context, db)
        transfers = NotebookTransferManager(
            context = context,
            db = db,
            attachmentStore = attachmentStore,
            transferRoot = File(root, "transfers"),
        )
    }

    @After
    fun tearDown() {
        // The store writes into the app's own attachment directory, not under [root].
        runBlocking { db.attachmentDao().all() }.forEach { attachmentStore.fileFor(it.id).delete() }
        db.close()
        root.deleteRecursively()
    }

    private fun fixture(name: String): ByteArray =
        instrumentation.context.assets.open("onenote/$name").use { it.readBytes() }

    private suspend fun import(fixture: String, fileName: String?) =
        transfers.importNotebook(ByteArrayInputStream(fixture(fixture)), fileName)

    @Test
    fun theConverterIsInThisBuild() {
        assertTrue(OneNoteConverter.available)
    }

    @Test
    fun aPackageBecomesANotebookOfItsSections() = runBlocking {
        val result = import("notebook.onepkg", "Biology.onepkg")

        assertTrue(result.created)
        assertEquals("Biology", result.notebookName)
        val conversion = checkNotNull(result.oneNote) { "The package was not converted" }
        assertEquals(2, conversion.sections)
        assertEquals(3, conversion.pages)
        assertEquals(1, conversion.attachments)

        val sections = db.sectionDao().inNotebook(result.notebookId)
        assertEquals(listOf("New Section 1", "New Section 2"), sections.map { it.name })
        assertEquals(
            listOf(listOf("Test Page 2"), listOf("Test Page 3", "Test Page 4")),
            sections.map { section -> db.pageDao().inSection(section.id).map { it.title } },
        )
        assertEquals(result.firstSectionId, sections.first().id)
        assertEquals(1, db.attachmentDao().all().size)
        assertTrue(attachmentStore.hasFile(db.attachmentDao().all().single().id))
    }

    @Test
    fun aPackageIsKnownByItsContentsNotItsName() = runBlocking {
        // Some providers hand over a display name with no extension, or none at all.
        val named = import("notebook.onepkg", "download")
        assertEquals("download", named.notebookName)
        assertEquals(2, named.oneNote?.sections)

        val nameless = import("notebook.onepkg", null)
        assertEquals("OneNote notebook", nameless.notebookName)
        assertEquals(2, nameless.oneNote?.sections)
    }

    @Test
    fun aSectionBecomesANotebookOfOneSectionWithItsInk() = runBlocking {
        val result = import("section.one", "Lecture.one")

        assertEquals("Lecture", result.notebookName)
        val section = db.sectionDao().inNotebook(result.notebookId).single()
        val page = db.pageDao().inSection(section.id).single()
        assertEquals("Test Page", page.title)
        assertEquals(result.firstPageId, page.id)
        assertEquals(1, db.inkStrokeDao().byPage(page.id).size)
    }

    @Test
    fun importingTheSameFileTwiceGivesTwoNotebooks() = runBlocking {
        val first = import("section.one", "Lecture.one")
        val second = import("section.one", "Lecture.one")

        assertTrue(first.created)
        assertTrue(second.created)
        assertNotEquals(first.notebookId, second.notebookId)
        assertNotNull(db.notebookDao().byId(first.notebookId))
        assertEquals(1, db.sectionDao().inNotebook(first.notebookId).size)
        assertEquals(2, db.notebookDao().count())
    }

    @Test
    fun aTableOfContentsIsRefusedWithTheAdviceToExportAPackage() = runBlocking {
        assertRefused("toc.onetoc2", "Open Notebook.onetoc2", ".onepkg")
        // Its extension lost on the way, it is still known for what it is.
        assertRefused("toc.onetoc2", "Open Notebook", ".onepkg")
    }

    @Test
    fun aFileThatIsNeitherIsRefusedWithoutTouchingTheDatabase() = runBlocking {
        val pdf = "%PDF-1.7\n%âãÏÓ\n".toByteArray()
        try {
            transfers.importNotebook(ByteArrayInputStream(pdf), "paper.pdf")
            fail("A PDF was imported as a notebook")
        } catch (refused: NotebookTransferException) {
            assertTrue(refused.message, refused.message!!.contains("neither a .vive notebook nor a OneNote"))
        }
        assertEquals(0, db.notebookDao().count())
    }

    @Test
    fun thePickerOffersOneNotesFiles() {
        assertTrue("application/onenote" in NotebookTransferManager.importMimeTypes())
    }

    private suspend fun assertRefused(fixture: String, fileName: String, advice: String) {
        try {
            import(fixture, fileName)
            fail("$fileName was imported")
        } catch (refused: NotebookTransferException) {
            assertTrue(refused.message, refused.message!!.contains(advice))
        }
        assertEquals(0, db.notebookDao().count())
    }
}
