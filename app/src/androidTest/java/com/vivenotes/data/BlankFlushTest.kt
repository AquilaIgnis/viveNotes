package com.vivenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.LocalMetadataEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.SyncEntityStateEntity
import com.vivenotes.data.db.SyncStateEntity
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deleting something that never held anything — `memory/blankFlushPlan.md`.
 *
 * Two halves, and they fail in opposite directions. **Flushing too eagerly loses data with no
 * tombstone to recover it from**, which is why the blank tests below are mostly about the things
 * that must *not* be flushed: a page with a picture on it, a page with ink, a section holding an
 * older deleted page somebody may still restore, a notebook whose bytes are on the server. Flushing
 * too rarely only leaves rows around, which is the complaint this feature answers.
 *
 * The database is built with the sync triggers installed, because "nothing is sent to the server" is
 * half of what a flush means and the outbox is where that is visible.
 */
@RunWith(AndroidJUnit4::class)
class BlankFlushTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private var now = 4_000_000_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .allowMainThreadQueries()
            .build()
        repository = NotesRepository(db, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    // --- what a flush does --------------------------------------------------------------------

    /**
     * The complaint itself: `createNotebook` makes a notebook and a section, and somebody who looks
     * at that and changes their mind should be left with nothing at all.
     */
    @Test
    fun deletingANotebookNobodyWroteInLeavesNothingBehind() = runBlocking {
        val notebookId = repository.createNotebook("New Notebook")
        val sectionId = repository.createSection(notebookId, "New Section")
        val pageId = repository.createPage(sectionId)

        assertEquals(DeletionOutcome.Flushed, repository.deleteNotebook(notebookId))

        assertNull(db.notebookDao().byId(notebookId))
        assertNull(db.sectionDao().byId(sectionId))
        assertNull(db.pageDao().byId(pageId))
        assertNull(db.pageContentDao().byId(pageId))
        assertTrue(repository.observeDeletedItems().first().isEmpty())
    }

    @Test
    fun anEmptySectionAndAnUntouchedPageAreFlushedToo() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val kept = repository.createSection(notebookId, "Kept")
        repository.saveDoc(repository.createPage(kept), typed("keeps the notebook alive"))
        val emptySection = repository.createSection(notebookId, "Empty")
        val untouchedPage = repository.createPage(kept)

        assertEquals(DeletionOutcome.Flushed, repository.deletePage(untouchedPage))
        assertEquals(DeletionOutcome.Flushed, repository.deleteSection(emptySection))

        assertNull(db.pageDao().byId(untouchedPage))
        assertNull(db.sectionDao().byId(emptySection))
        assertTrue(repository.observeDeletedItems().first().isEmpty())
    }

    /** The control: something with writing in it is tombstoned and recoverable, as it always was. */
    @Test
    fun aNotebookWithWritingInItIsStillTombstoned() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        repository.saveDoc(repository.createPage(sectionId), typed("something worth keeping"))

        assertEquals(DeletionOutcome.Tombstoned, repository.deleteNotebook(notebookId))

        assertNotNull(db.notebookDao().byId(notebookId)!!.deletedAt)
        assertEquals(notebookId, repository.observeDeletedItems().first().single().key.id)
    }

    // --- what is not blank --------------------------------------------------------------------

    /** A title is the one thing a page carries outside its body, and somebody typed it. */
    @Test
    fun aTitledPageIsNotBlank() = runBlocking {
        val page = repository.createPage(newSection(), "Groceries")

        assertEquals(DeletionOutcome.Tombstoned, repository.deletePage(page))
        assertNotNull(db.pageDao().byId(page))
    }

    /**
     * The mistake that would matter most: a page holding one photograph has no text in it, so
     * anything reading `pages.preview` — or `plainText` — would call it empty.
     */
    @Test
    fun aPageHoldingOnlyAPictureIsNotBlank() = runBlocking {
        val page = repository.createPage(newSection())
        repository.saveDoc(
            page,
            PageDoc(outlines = listOf(Outline.Image(id = "img", attachmentId = "sha256", height = 80f))),
        )

        assertEquals(DeletionOutcome.Tombstoned, repository.deletePage(page))
        assertNotNull(db.pageDao().byId(page))
    }

    /** Ink lives in its own tables, so a page of pure handwriting has an empty document. */
    @Test
    fun aPageHoldingOnlyInkIsNotBlank() = runBlocking {
        val page = repository.createPage(newSection())
        db.inkStrokeDao().insert(stroke("drawn", page, deletedAt = null))

        assertEquals(DeletionOutcome.Tombstoned, repository.deletePage(page))
        assertNotNull(db.pageDao().byId(page))
    }

    /** An erased stroke is still ink somebody drew, and the erase that removed it can be undone. */
    @Test
    fun aPageWhoseInkWasAllErasedIsNotBlank() = runBlocking {
        val page = repository.createPage(newSection())
        db.inkStrokeDao().insert(stroke("erased", page, deletedAt = now))

        assertEquals(DeletionOutcome.Tombstoned, repository.deletePage(page))
        assertNotNull(db.pageDao().byId(page))
    }

    /** Version history is content the page no longer shows, and it is one pane away. */
    @Test
    fun aPageEmptiedByHandKeepsItsVersionHistoryAndIsNotBlank() = runBlocking {
        val page = repository.createPage(newSection())
        repository.saveDoc(page, typed("written"))
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
        repository.saveDoc(page, PageDoc.empty())

        assertTrue(repository.revisionHistory(page).isNotEmpty())
        assertEquals(DeletionOutcome.Tombstoned, repository.deletePage(page))
        assertNotNull(db.pageDao().byId(page))
    }

    /**
     * The tombstoned page under it is the whole point: it is still listed in Deleted Items on its
     * own, and flushing the section around it would take it away without ever naming it.
     */
    @Test
    fun aSectionHoldingAnOlderDeletedPageWithTextIsNotBlank() = runBlocking {
        val sectionId = newSection()
        val page = repository.createPage(sectionId, "Older delete")
        repository.saveDoc(page, typed("recover me"))
        repository.deletePage(page)

        assertEquals(DeletionOutcome.Tombstoned, repository.deleteSection(sectionId))
        assertNotNull(db.sectionDao().byId(sectionId)!!.deletedAt)
    }

    /**
     * A cloud-only notebook has no bodies, no ink and no versions *on this device* while the server
     * holds every one of them, so it is the emptiest-looking thing in the database and the one that
     * must never be flushed.
     */
    @Test
    fun aNotebookWhoseContentsAreInTheCloudIsNeverBlank() = runBlocking {
        val notebookId = repository.createNotebook("Archived")
        repository.createPage(repository.createSection(notebookId, "Section"))
        db.notebookDao().setClosed(notebookId, now, now)
        db.notebookDao().setCloudOnly(notebookId, now, now)

        assertEquals(DeletionOutcome.Tombstoned, repository.deleteNotebook(notebookId))
        assertNotNull(db.notebookDao().byId(notebookId)!!.deletedAt)
    }

    /**
     * And a merely closed one is not flushed either: the server stores a delete of a closed notebook
     * as a live cloud-only row rather than a tombstone, so a device that had thrown its rows away
     * would meet it again on the next pull with nothing left to draw it from.
     */
    @Test
    fun aClosedNotebookIsNeverBlank() = runBlocking {
        val notebookId = repository.createNotebook("Shelved")
        repository.createSection(notebookId, "Section")
        repository.closeNotebook(notebookId)

        assertEquals(DeletionOutcome.Tombstoned, repository.deleteNotebook(notebookId))
        assertNotNull(db.notebookDao().byId(notebookId)!!.deletedAt)
    }

    // --- the recovery window ------------------------------------------------------------------

    /**
     * A flush is a tombstone born expired, and the pane lists only what is still inside the window —
     * which is also correct on its own terms, since an expired row is one the next purge removes.
     */
    @Test
    fun anExpiredTombstoneIsNotOfferedForRecovery() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        repository.saveDoc(
            repository.createPage(repository.createSection(notebookId, "Section")),
            typed("text"),
        )
        repository.deleteNotebook(notebookId)
        assertEquals(1, repository.observeDeletedItems().first().size)

        now += NotesRepository.DELETION_RETENTION_MILLIS

        assertTrue(repository.observeDeletedItems().first().isEmpty())
    }

    // --- and what the server hears ------------------------------------------------------------
    //
    // Against the tables here; against a server in `HierarchySyncTest`, which is where the key this
    // file writes by hand is pinned to the one the sync layer actually reads.

    @Test
    fun theFlushDropsQueuedWorkTheServerHasNeverSeen() = runBlocking {
        registered()
        val notebookId = repository.createNotebook("Never pushed")
        repository.createPage(repository.createSection(notebookId, "Section"))
        assertTrue("nothing was queued at all", db.syncDao().outbox(64).isNotEmpty())

        repository.deleteNotebook(notebookId)

        assertEquals(emptyList<String>(), db.syncDao().outbox(64).map { it.kind })
    }

    /**
     * The other direction, and the one that would be a notebook standing on the server that no
     * device holds a delete for: an acknowledged row's tombstone stays queued, and the rows stay
     * with it until it has actually been delivered.
     */
    @Test
    fun theFlushKeepsTheTombstoneWhenTheServerAlreadyHasTheRow() = runBlocking {
        registered()
        val notebookId = repository.createNotebook("Already pushed")
        val sectionId = repository.createSection(notebookId, "Section")
        acknowledge("notebook", notebookId)

        assertEquals(DeletionOutcome.Flushed, repository.deleteNotebook(notebookId))

        val queued = db.syncDao().outboxEntry("notebook", notebookId)
        assertNotNull("the delete would never reach the server", queued)
        val row = db.notebookDao().byId(notebookId)!!
        assertEquals("the tombstone is born expired", now - NotesRepository.DELETION_RETENTION_MILLIS, row.deletedAt)
        assertEquals("but it did change now", now, row.updatedAt)
        assertNotNull("its branch is held until the delete is delivered", db.sectionDao().byId(sectionId))
        assertTrue("a flush is never offered for recovery", repository.observeDeletedItems().first().isEmpty())
    }

    /**
     * A batch already serialized is re-sent byte for byte after a lost response, so an id inside one
     * has to be treated as already on the server even with no state row for it yet.
     */
    @Test
    fun theFlushKeepsTheTombstoneWhileASerializedBatchStillNamesIt() = runBlocking {
        registered()
        val notebookId = repository.createNotebook("In flight")
        db.localMetadataDao().put(
            LocalMetadataEntity(
                NotesRepository.PENDING_SYNC_BATCH_KEY,
                """{"batchId":"b","changes":[{"kind":"notebook","id":"$notebookId"}]}""",
            ),
        )

        repository.deleteNotebook(notebookId)

        assertNotNull(db.syncDao().outboxEntry("notebook", notebookId))
        assertNotNull(db.notebookDao().byId(notebookId))
    }

    // --- helpers ------------------------------------------------------------------------------

    private suspend fun newSection(): String =
        repository.createSection(repository.createNotebook("Notebook"), "Section")

    private fun typed(text: String) = PageDoc(
        outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(text)))),
    )

    /** Turns the sync triggers on: they only queue while this database belongs to an account. */
    private suspend fun registered() =
        db.syncDao().putState(SyncStateEntity(accountId = "account"))

    private suspend fun acknowledge(kind: String, id: String) = db.syncDao().putEntityState(
        SyncEntityStateEntity(kind, id, serverVersion = 1, serverJson = """{"id":"$id"}"""),
    )

    private fun stroke(id: String, pageId: String, deletedAt: Long?) = InkStrokeEntity(
        id = id,
        pageId = pageId,
        seq = 0,
        brushFamily = "marker",
        brushVersion = 1,
        sizeDp = 4f,
        colorArgb = 0xFF000000.toInt(),
        epsilon = 0.1f,
        stabilization = 0,
        minX = 0f,
        minY = 0f,
        maxX = 1f,
        maxY = 1f,
        points = byteArrayOf(1),
        enc = "test/1",
        createdAt = 0L,
        deletedAt = deletedAt,
    )
}
