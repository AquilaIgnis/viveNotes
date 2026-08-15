package com.vivenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletionPurgeTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private var now = 10_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NotesRepository(db, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun inkPurgeUsesInclusiveSevenDayBoundaryAndCascadesTargets() = runBlocking {
        val pageId = newPage()
        val cutoff = 50_000L
        val liveStroke = stroke("live", pageId, seq = 0, deletedAt = null)
        val expiredStroke = stroke("expired", pageId, seq = 1, deletedAt = cutoff)
        val recentStroke = stroke("recent", pageId, seq = 2, deletedAt = cutoff + 1)
        db.inkStrokeDao().insert(listOf(liveStroke, expiredStroke, recentStroke))

        val expiredErase = erase("erase-expired", pageId, deletedAt = cutoff)
        val recentErase = erase("erase-recent", pageId, deletedAt = cutoff + 1)
        db.inkEraseDao().insert(expiredErase)
        db.inkEraseDao().insert(recentErase)
        db.inkEraseDao().insertTargets(
            listOf(
                InkEraseTargetEntity(expiredErase.id, liveStroke.id),
                InkEraseTargetEntity(recentErase.id, liveStroke.id),
            ),
        )

        val expiredMove = move("move-expired", pageId, deletedAt = cutoff)
        val recentMove = move("move-recent", pageId, deletedAt = cutoff + 1)
        db.inkMoveDao().insert(expiredMove)
        db.inkMoveDao().insert(recentMove)
        db.inkMoveDao().insertTargets(
            listOf(
                InkMoveTargetEntity(expiredMove.id, liveStroke.id),
                InkMoveTargetEntity(recentMove.id, liveStroke.id),
            ),
        )

        val result = repository.purgeExpiredDeletions(
            now = cutoff + NotesRepository.DELETION_RETENTION_MILLIS,
        )

        assertEquals(cutoff, result.cutoff)
        assertEquals(1, result.inkStrokes)
        assertEquals(1, result.inkErases)
        assertEquals(1, result.inkMoves)
        assertEquals(setOf(liveStroke.id, recentStroke.id), db.inkStrokeDao().byIds(
            listOf(liveStroke.id, expiredStroke.id, recentStroke.id),
        ).map { it.id }.toSet())
        assertEquals(setOf(recentErase.id), db.inkEraseDao().byIds(
            listOf(expiredErase.id, recentErase.id),
        ).map { it.id }.toSet())
        assertEquals(setOf(recentMove.id), db.inkMoveDao().byIds(
            listOf(expiredMove.id, recentMove.id),
        ).map { it.id }.toSet())
        assertEquals(0, db.rowCount("ink_erase_targets", "eraseId", expiredErase.id))
        assertEquals(1, db.rowCount("ink_erase_targets", "eraseId", recentErase.id))
        assertEquals(0, db.rowCount("ink_move_targets", "moveId", expiredMove.id))
        assertEquals(1, db.rowCount("ink_move_targets", "moveId", recentMove.id))
    }

    @Test
    fun hierarchyPurgeCascadesWholeBranchAndKeepsNewerDeletionRecoverable() = runBlocking {
        val expiredAt = now
        val notebookId = repository.createNotebook("Expired notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        repository.saveDoc(
            pageId,
            PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of("body"))))),
        )
        val source = stroke("source", pageId, seq = 0, deletedAt = null)
        db.inkStrokeDao().insert(source)
        val activeErase = erase("active-erase", pageId, deletedAt = null)
        db.inkEraseDao().insert(activeErase)
        db.inkEraseDao().insertTargets(listOf(InkEraseTargetEntity(activeErase.id, source.id)))
        val activeMove = move("active-move", pageId, deletedAt = null)
        db.inkMoveDao().insert(activeMove)
        db.inkMoveDao().insertTargets(listOf(InkMoveTargetEntity(activeMove.id, source.id)))
        repository.deleteNotebook(notebookId)

        val survivorNotebook = repository.createNotebook("Live notebook")
        val survivorSection = repository.createSection(survivorNotebook, "Live section")
        val recentPage = repository.createPage(survivorSection, "Recently deleted")
        now = expiredAt + 1
        repository.deletePage(recentPage)

        val result = repository.purgeExpiredDeletions(
            now = expiredAt + NotesRepository.DELETION_RETENTION_MILLIS,
        )

        assertEquals(1, result.notebooks)
        assertNull(db.notebookDao().byId(notebookId))
        assertNull(db.sectionDao().byId(sectionId))
        assertNull(db.pageDao().byId(pageId))
        assertNull(db.pageContentDao().byId(pageId))
        assertEquals(0, db.pageRows("ink_strokes", pageId))
        assertEquals(0, db.pageRows("ink_erases", pageId))
        assertEquals(0, db.pageRows("ink_moves", pageId))
        assertEquals(0, db.rowCount("ink_erase_targets", "eraseId", activeErase.id))
        assertEquals(0, db.rowCount("ink_move_targets", "moveId", activeMove.id))

        assertNotNull(db.pageDao().byId(recentPage))
        assertEquals(recentPage, repository.observeDeletedItems().first().single().key.id)
    }

    private suspend fun newPage(): String {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        return repository.createPage(sectionId, "Page")
    }

    private fun stroke(id: String, pageId: String, seq: Int, deletedAt: Long?) = InkStrokeEntity(
        id = id,
        pageId = pageId,
        seq = seq,
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

    private fun erase(id: String, pageId: String, deletedAt: Long?) = InkEraseEntity(
        id = id,
        pageId = pageId,
        sizeDp = 8f,
        points = byteArrayOf(1),
        enc = "test/1",
        createdAt = 0L,
        deletedAt = deletedAt,
    )

    private fun move(id: String, pageId: String, deletedAt: Long?) = InkMoveEntity(
        id = id,
        pageId = pageId,
        dxDp = 1f,
        dyDp = 1f,
        points = byteArrayOf(1),
        enc = "test/1",
        createdAt = 0L,
        deletedAt = deletedAt,
    )
}

private fun NotesDatabase.rowCount(table: String, column: String, id: String): Int =
    query("SELECT COUNT(*) FROM $table WHERE $column = ?", arrayOf(id)).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

private fun NotesDatabase.pageRows(table: String, pageId: String): Int =
    rowCount(table, "pageId", pageId)
