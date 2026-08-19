package com.vivenotes.data

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.SyncStateEntity
import com.vivenotes.ink.InkCodec
import com.vivenotes.model.PageDoc
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
 * Ink rubbed out down to nothing is collected, and nothing else is.
 *
 * An append-only log means erasing has always *added* rows while the stroke kept every point it was
 * drawn with — two thirds of the stroke rows on the test corpus had no geometry left at all. A row
 * whose replay leaves no projection draws nothing and is reachable by nothing, so it is tombstoned
 * and the seven-day purge that already exists collects it.
 */
@RunWith(AndroidJUnit4::class)
class ErasedInkCollectionTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var loader: InkPageLoader
    private var now = 10_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            // Without these the outbox stays empty whatever happens, which would make the test that
            // collection does not queue a sync pass for the wrong reason.
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .allowMainThreadQueries()
            .build()
        repository = NotesRepository(db, clock = { now })
        loader = InkPageLoader(repository)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aStrokeErasedAwayEntirelyIsReportedAndCollected() = runBlocking {
        val pageId = newPage()
        val strokeId = addStroke(pageId)
        eraseAcross(pageId, strokeId, halfway = false)

        val loaded = loader.load(pageId)

        assertTrue("the page still draws it", loaded.strokes.isEmpty())
        assertEquals(listOf(strokeId), loaded.erasedAway)

        repository.collectErasedAwayStrokes(loaded.erasedAway)

        assertNotNull("the row should be tombstoned", deletedAt(strokeId))
        assertTrue("and it should stop loading", repository.inkFor(pageId).isEmpty())
    }

    @Test
    fun aStrokeWithInkLeftIsNeverCollected() = runBlocking {
        val pageId = newPage()
        val strokeId = addStroke(pageId)
        eraseAcross(pageId, strokeId, halfway = true)

        val loaded = loader.load(pageId)

        assertTrue("the surviving pieces are still drawn", loaded.strokes.isNotEmpty())
        assertEquals(emptyList<String>(), loaded.erasedAway)
    }

    /** A row this build cannot read draws nothing either, and must never be mistaken for erased. */
    @Test
    fun anUnreadableStrokeIsNeverCollected() = runBlocking {
        val pageId = newPage()
        val strokeId = addStroke(pageId)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE ink_strokes SET enc = 'ink/from-the-future' WHERE id = ?",
            arrayOf(strokeId),
        )

        val loaded = loader.load(pageId)

        assertTrue(loaded.strokes.isEmpty())
        assertEquals(emptyList<String>(), loaded.erasedAway)
    }

    /** Deadness is a conclusion every device reaches for itself, so it is never pushed. */
    @Test
    fun collectingDoesNotQueueTheStrokeForSyncTheWayADeleteDoes() = runBlocking {
        db.syncDao().putState(SyncStateEntity(accountId = "account"))
        val pageId = newPage()
        val collected = addStroke(pageId)
        val deleted = addStroke(pageId)
        db.syncDao().clearOutbox()

        repository.collectErasedAwayStrokes(listOf(collected))
        assertEquals(emptyList<String>(), queuedStrokeIds())

        repository.eraseStrokes(listOf(deleted))
        assertEquals(listOf(deleted), queuedStrokeIds())
    }

    /** Undo puts the erase back in the past tense, and the ink it finished off has to come back. */
    @Test
    fun undoingTheEraseRestoresTheStrokeItCollected() = runBlocking {
        val pageId = newPage()
        val strokeId = addStroke(pageId)
        val eraseId = eraseAcross(pageId, strokeId, halfway = false)
        repository.collectErasedAwayStrokes(loader.load(pageId).erasedAway)
        assertNotNull(deletedAt(strokeId))

        repository.setPartialEraseActive(eraseId, active = false)

        assertNull("undo left the ink tombstoned", deletedAt(strokeId))
        assertTrue("and it draws again", loader.load(pageId).strokes.isNotEmpty())
    }

    /** The point of using a tombstone: the seven-day purge already knows what to do with one. */
    @Test
    fun theSevenDayPurgeCollectsIt() = runBlocking {
        val pageId = newPage()
        val strokeId = addStroke(pageId)
        eraseAcross(pageId, strokeId, halfway = false)
        repository.collectErasedAwayStrokes(loader.load(pageId).erasedAway)

        now += NotesRepository.DELETION_RETENTION_MILLIS + 1
        val purged = repository.purgeExpiredDeletions(now)

        assertEquals(1, purged.inkStrokes)
        assertTrue(db.inkStrokeDao().byIds(listOf(strokeId)).isEmpty())
    }

    /** The one thing collection must not cost: a revision that names the stroke still restores it. */
    @Test
    fun aRevisionTakenBeforeTheEraseStillRestoresTheStroke() = runBlocking {
        val pageId = newPage()
        repository.saveDoc(pageId, PageDoc.empty())
        val strokeId = addStroke(pageId)
        // Past the coalescing window, so the erase below takes its own checkpoint — and that one
        // holds the page as it was a moment ago, with the stroke still on it.
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS + 1
        eraseAcross(pageId, strokeId, halfway = false)
        repository.collectErasedAwayStrokes(loader.load(pageId).erasedAway)
        assertNotNull(deletedAt(strokeId))
        val revision = repository.revisionHistory(pageId)
            .first { revision -> strokeId in strokeIdsNamedBy(pageId, revision.id) }

        repository.restoreRevision(pageId, revision.id)

        assertNull("a revision naming the stroke restored without it", deletedAt(strokeId))
        assertTrue("and it draws again", loader.load(pageId).strokes.isNotEmpty())
    }

    // --- fixtures -------------------------------------------------------------------------

    private suspend fun newPage(): String {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        return repository.createPage(sectionId, "Page")
    }

    /** One real stroke, straight across the page, stored the way the canvas stores one. */
    private suspend fun addStroke(pageId: String): String {
        val ink = InkCodec.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 6f)
        val row = InkCodec.encode(ink, pageId, seq = 0, pen = PenPreset.starting(0), now = now)
        return repository.addStroke(row).id
    }

    /**
     * An eraser stroke over [strokeId], either through its middle or along the whole of it.
     *
     * The mask is wide enough to take everything it passes over, so [halfway] is the difference
     * between a stroke with two pieces left and a stroke with nothing left.
     */
    private suspend fun eraseAcross(pageId: String, strokeId: String, halfway: Boolean): String {
        val mask = if (halfway) {
            InkCodec.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        } else {
            InkCodec.eraseMask(inputs(0f to 50f, 100f to 50f), sizeDp = 40f)
        }
        val erase = InkCodec.encodeErase(mask, pageId, now = now)
        repository.addPartialErase(erase, listOf(strokeId))
        return erase.id
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) ->
            add(InputToolType.UNKNOWN, x, y, index * 10L)
        }
    }.toImmutable()

    private suspend fun deletedAt(strokeId: String): Long? =
        db.inkStrokeDao().byIds(listOf(strokeId)).firstOrNull()?.deletedAt

    private fun queuedStrokeIds(): List<String> =
        db.query("SELECT entityId FROM sync_outbox WHERE kind = 'inkStroke'", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    /** The strokes a stored revision claims were on the page, read out of its own snapshot. */
    private suspend fun strokeIdsNamedBy(pageId: String, revisionId: String): List<String> {
        val row = db.pageRevisionDao().byId(pageId, revisionId) ?: return emptyList()
        return InkRevisionPayload.unpack(row).strokes.map { it.id }
    }
}
