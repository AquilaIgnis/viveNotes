package com.vivenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.SyncEntityStateEntity
import com.vivenotes.data.db.SyncStateEntity
import com.vivenotes.data.sync.TemporaryAttachmentBytes
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * What [AttachmentSweep] lets go of, and — the half that matters more — what it must never take.
 *
 * Every test starts from a picture imported long enough ago to be past the fresh-import guard, so
 * the only question each one asks is whether something still places it.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentSweepTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var pictures: TemporaryAttachmentBytes
    private lateinit var pictureDirectory: File
    private var held: Set<String> = emptySet()
    private var now = 10 * DAY

    private val sweep by lazy { AttachmentSweep(db, pictures, { held }, clock = { now }) }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .allowMainThreadQueries()
            .build()
        repository = NotesRepository(db, clock = { now })
        pictureDirectory = File(context.cacheDir, "sweep-pictures-${UUID.randomUUID()}")
        pictures = TemporaryAttachmentBytes(pictureDirectory)
    }

    @After
    fun tearDown() {
        db.close()
        pictureDirectory.deleteRecursively()
    }

    @Test
    fun aPictureNoPagePlacesIsReleasedAndOneAPagePlacesIsNot() = runBlocking {
        val shown = importPicture("shown")
        val dropped = importPicture("dropped")
        val pageId = newPage()
        repository.saveDoc(pageId, docShowing(shown))

        val result = sweep.sweep()

        assertEquals(1, result.released)
        assertNull("a picture on a page must stay", row(shown).releasedAt)
        assertEquals(now, row(dropped).releasedAt)
        // Released, not deleted: the file outlives the release by a day.
        assertTrue(pictures.fileFor(dropped).exists())
    }

    @Test
    fun aPictureOnlyADeletedPageStillPlacesIsKeptUntilDeletedItemsLetsGoOfIt() = runBlocking {
        val picture = importPicture("on a deleted page")
        val pageId = newPage()
        repository.saveDoc(pageId, docShowing(picture))
        repository.deletePage(pageId)

        sweep.sweep()

        // Restoring the page from Deleted Items must find its picture, here and on the server.
        assertNull(row(picture).releasedAt)
    }

    @Test
    fun aPictureTheEditorCanStillUndoIsKept() = runBlocking {
        val picture = importPicture("one tap of Undo away")
        held = setOf(picture)

        sweep.sweep()

        assertNull(row(picture).releasedAt)
    }

    @Test
    fun aReleasedPictureThatIsPlacedAgainIsTakenBack() = runBlocking {
        val picture = importPicture("restored")
        sweep.sweep()
        assertNotNull(row(picture).releasedAt)

        repository.saveDoc(newPage(), docShowing(picture))
        val result = sweep.sweep()

        assertEquals(1, result.placedAgain)
        assertNull(row(picture).releasedAt)
    }

    @Test
    fun aJustImportedPictureIsNotReleasedBeforeTheEditorPlacesIt() = runBlocking {
        val picture = importPicture("arriving", createdAt = now - 1_000)

        sweep.sweep()

        assertNull(row(picture).releasedAt)
    }

    @Test
    fun theFileGoesOnlyADayAfterTheReleaseAndNeverWhileASavedVersionShowsIt() = runBlocking {
        val gone = importPicture("gone")
        val versioned = importPicture("in version history")
        val pageId = newPage()
        repository.saveDoc(pageId, docShowing(versioned))
        db.pageRevisionDao().deleteForPages(listOf(pageId))
        storeVersionOf(pageId)
        repository.saveDoc(pageId, PageDoc.empty())

        sweep.sweep()
        now += AttachmentSweep.RELEASED_FILE_GRACE_MILLIS - 1
        assertEquals(0, sweep.sweep().filesDeleted)
        assertTrue(pictures.fileFor(gone).exists())

        now += 1
        val result = sweep.sweep()

        assertEquals(1, result.filesDeleted)
        assertFalse(pictures.fileFor(gone).exists())
        assertNull(db.attachmentDao().byId(gone))
        // Released from the server's quota, but the file stays so restoring the version works.
        assertNotNull(row(versioned).releasedAt)
        assertTrue(pictures.fileFor(versioned).exists())
    }

    @Test
    fun aSavedVersionWrittenBeforeTheIndexIsIndexedAndKeepsItsPicture() = runBlocking {
        val picture = importPicture("old version")
        val pageId = newPage()
        repository.saveDoc(pageId, docShowing(picture))
        db.pageRevisionDao().deleteForPages(listOf(pageId))
        storeVersionOf(pageId, indexed = false)
        repository.saveDoc(pageId, PageDoc.empty())

        val first = sweep.sweep()
        now += 2 * AttachmentSweep.RELEASED_FILE_GRACE_MILLIS
        sweep.sweep()

        assertEquals(1, first.versionsIndexed)
        assertEquals(0, db.pageRevisionDao().unindexedCount())
        assertTrue(pictures.fileFor(picture).exists())
    }

    @Test
    fun aReleaseIsPushedOnlyForARowTheServerHolds() = runBlocking {
        val synced = importPicture("synced")
        val neverSynced = importPicture("never synced")
        startSyncing()
        serverHolds(synced)

        sweep.sweep()

        val outbox = db.syncDao().outbox(64).map { it.kind to it.entityId }
        assertTrue(("attachment" to synced) in outbox)
        assertFalse("a server with no row needs no tombstone", ("attachment" to neverSynced) in outbox)
    }

    @Test
    fun aReleaseTheServerNeverHeardIsSentAgain() = runBlocking {
        val picture = importPicture("lost push")
        sweep.sweep()
        startSyncing()
        serverHolds(picture)
        assertTrue(db.syncDao().outbox(64).isEmpty())

        sweep.sweep()

        assertEquals(listOf("attachment" to picture), db.syncDao().outbox(64).map { it.kind to it.entityId })
    }

    @Test
    fun aReleasedPictureKeepsItsFileWhileItsTombstoneIsStillQueued() = runBlocking {
        val picture = importPicture("queued")
        startSyncing()
        serverHolds(picture)
        sweep.sweep()

        now += 2 * AttachmentSweep.RELEASED_FILE_GRACE_MILLIS
        sweep.sweep()

        // The tombstone is built from the row, so the row cannot go before the push does.
        assertNotNull(db.attachmentDao().byId(picture))
        assertTrue(pictures.fileFor(picture).exists())
    }

    private suspend fun importPicture(content: String, createdAt: Long = now - 2 * DAY): String {
        val bytes = content.toByteArray()
        val id = sha256Hex(bytes)
        pictures.write(id, bytes)
        db.attachmentDao().insert(
            AttachmentEntity(
                id = id,
                mimeType = "image/webp",
                pixelWidth = 8,
                pixelHeight = 8,
                byteCount = bytes.size.toLong(),
                createdAt = createdAt,
            ),
        )
        return id
    }

    private suspend fun newPage(): String {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        return repository.createPage(sectionId, "Page")
    }

    private suspend fun row(id: String): AttachmentEntity =
        checkNotNull(db.attachmentDao().byId(id)) { "attachment $id is gone" }

    /** Saves the page's current body as a version, as a checkpoint would. */
    private suspend fun storeVersionOf(pageId: String, indexed: Boolean = true) {
        val body = checkNotNull(db.pageContentDao().byId(pageId))
        val version = DocumentRevisionPayload.pack(
            row = body,
            createdAt = now,
            ink = InkRevisionPayload.pack(InkSnapshot.from(emptyList(), emptyList(), emptyList())),
        )
        db.pageRevisionDao().insert(if (indexed) version else version.copy(pictureIds = null))
    }

    private suspend fun startSyncing() {
        db.syncDao().putState(SyncStateEntity(accountId = "account"))
    }

    /** What a pushed or pulled live `attachment` row leaves behind. */
    private suspend fun serverHolds(id: String) {
        db.syncDao().putEntityState(
            SyncEntityStateEntity(
                kind = "attachment",
                entityId = id,
                serverVersion = 1,
                serverJson = """{"kind":"attachment","id":"$id","version":1,"deletedAt":null}""",
            ),
        )
    }

    private fun docShowing(attachmentId: String) = PageDoc(
        outlines = listOf(
            Outline.Image(
                id = "image-$attachmentId",
                x = 0f,
                y = 0f,
                width = 100f,
                height = 100f,
                attachmentId = attachmentId,
            ),
        ),
    )

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
