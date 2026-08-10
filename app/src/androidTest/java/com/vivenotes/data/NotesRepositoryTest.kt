package com.vivenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.newId
import com.vivenotes.model.plainText

@RunWith(AndroidJUnit4::class)
class NotesRepositoryTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NotesRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun newPage(): String = runBlocking {
        val notebookId = repository.createNotebook("nb")
        val sectionId = repository.createSection(notebookId, "sec")
        repository.createPage(sectionId, "page")
    }

    @Test
    fun roundTripsADocumentThroughStorage() = runBlocking {
        val pageId = newPage()
        val doc = PageDoc(
            outlines = listOf(
                Outline.Text(id = newId(), x = 30f, y = 90f, width = 500f, blocks = listOf(Block.of("kept"))),
                Outline.Text(id = newId(), x = 400f, y = 10f, width = 300f, blocks = listOf(Block.of("second"))),
            ),
        )

        repository.saveDoc(pageId, doc)
        val load = repository.loadDoc(pageId)

        assertTrue("expected a clean load, got $load", load is PageLoad.Loaded)
        assertEquals(doc, (load as PageLoad.Loaded).doc)
    }

    /**
     * Regression: content that cannot be decoded used to come back as an empty document, which
     * autosave then wrote over the original. A read failure must never look like an empty page.
     */
    @Test
    fun reportsUnreadableContentRatherThanReturningAnEmptyDocument() = runBlocking {
        val pageId = newPage()
        val garbage = """{"outlines":[{"t":"text","id":"x","blocks":"not-an-array"}]}"""
        db.pageContentDao().upsert(PageContentEntity(pageId, garbage, System.currentTimeMillis()))

        val load = repository.loadDoc(pageId)

        assertTrue("unreadable content was reported as $load", load is PageLoad.Unreadable)
        assertEquals(garbage, (load as PageLoad.Unreadable).rawJson)
    }

    @Test
    fun storedRowsRecordTheCodecThatWroteThem() = runBlocking {
        val pageId = newPage()
        repository.saveDoc(pageId, PageDoc(outlines = listOf(Outline.Text(id = "o", blocks = listOf(Block.of("x"))))))

        assertEquals("json/1", db.pageContentDao().byId(pageId)!!.format)
    }

    /**
     * A row written by a format this build does not know must be reported, not guessed at. Without
     * this, switching the sync protocol to MessagePack would silently corrupt older rows.
     */
    @Test
    fun aRowInAnUnknownFormatIsReportedRatherThanDecodedAnyway() = runBlocking {
        val pageId = newPage()
        db.pageContentDao().upsert(
            PageContentEntity(pageId, """{"outlines":[]}""", 0L, format = "msgpack/1"),
        )

        val load = repository.loadDoc(pageId)

        assertTrue("an unknown format decoded anyway: $load", load is PageLoad.Unreadable)
    }

    @Test
    fun aFreshPageLoadsAsAnEmptyDocument() = runBlocking {
        val load = repository.loadDoc(newPage())

        assertTrue("expected a clean load, got $load", load is PageLoad.Loaded)
        assertTrue((load as PageLoad.Loaded).doc.plainText().isBlank())
    }

    @Test
    fun aFreshInstallSeedsTheBundledTabletInkAsPageTwo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = StarterInkPageFixture.load(context)
        repository = NotesRepository(db, starterInkPage = fixture)

        repository.seedIfEmpty()

        val pages = db.query(
            "SELECT id, title FROM pages WHERE deletedAt IS NULL ORDER BY sortIndex",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
            }
        }
        assertEquals(listOf("Welcome", "Recognition Test"), pages.map(Pair<String, String>::second))

        val fixturePageId = pages[1].first
        val strokes = repository.inkFor(fixturePageId)
        val erases = repository.partialErasesFor(fixturePageId)
        val moves = repository.inkMovesFor(fixturePageId)
        assertEquals(433, strokes.size)
        assertEquals(87, erases.size)
        assertEquals(330, erases.sumOf { it.targets.size })
        assertEquals(19, moves.size)
        assertEquals(589, moves.sumOf { it.targets.size })
        assertTrue(strokes.all { it.pageId == fixturePageId && it.deletedAt == null })
        assertTrue(erases.all { it.erase.pageId == fixturePageId && it.erase.deletedAt == null })
        assertTrue(moves.all { it.move.pageId == fixturePageId && it.move.deletedAt == null })

        val sourcesBySequence = fixture.strokes.associateBy { it.seq }
        strokes.forEach { stored ->
            val source = sourcesBySequence.getValue(stored.seq)
            assertNotEquals("source ids must be remapped per install", source.id, stored.id)
            assertEquals(source.brushFamily, stored.brushFamily)
            assertEquals(source.sizeDp, stored.sizeDp)
            assertEquals(source.minX, stored.minX)
            assertEquals(source.minY, stored.minY)
            assertEquals(source.maxX, stored.maxX)
            assertEquals(source.maxY, stored.maxY)
            assertArrayEquals(source.pointsHex.hexBytesForTest(), stored.points)
        }
        fixture.erases.sortedBy { it.createdAt }.zip(erases).forEach { (source, stored) ->
            assertNotEquals("source erase ids must be remapped per install", source.id, stored.erase.id)
            assertEquals(source.mode, stored.erase.mode)
            assertEquals(source.sizeDp, stored.erase.sizeDp)
            assertEquals(source.createdAt, stored.erase.createdAt)
            assertArrayEquals(source.pointsHex.hexBytesForTest(), stored.erase.points)
            assertEquals(
                fixture.eraseTargets.count { it.eraseId == source.id },
                stored.targets.size,
            )
        }
        // The transforms decide where the ink sits, so a fixture that seeds the strokes but loses
        // these seeds a scattered page that still passes every stroke assertion above.
        fixture.moves.sortedBy { it.createdAt }.zip(moves).forEach { (source, stored) ->
            assertNotEquals("source move ids must be remapped per install", source.id, stored.move.id)
            assertEquals(source.dxDp, stored.move.dxDp)
            assertEquals(source.dyDp, stored.move.dyDp)
            assertEquals(source.scaleX, stored.move.scaleX)
            assertEquals(source.scaleY, stored.move.scaleY)
            assertEquals(source.anchorX, stored.move.anchorX)
            assertEquals(source.anchorY, stored.move.anchorY)
            assertEquals(source.createdAt, stored.move.createdAt)
            assertArrayEquals(source.pointsHex.hexBytesForTest(), stored.move.points)
            assertEquals(
                fixture.moveTargets.count { it.moveId == source.id },
                stored.targets.size,
            )
        }

        val load = repository.loadDoc(fixturePageId)
        assertTrue(load is PageLoad.Loaded)
        assertTrue((load as PageLoad.Loaded).doc.plainText().isBlank())
    }

    /** Deletion is soft, so a future sync can replicate it rather than silently resurrecting rows. */
    @Test
    fun deletingAPageLeavesATombstone() = runBlocking {
        val notebookId = repository.createNotebook("nb")
        val sectionId = repository.createSection(notebookId, "sec")
        val pageId = repository.createPage(sectionId, "doomed")

        repository.deletePage(pageId)

        val rows = db.query("SELECT deletedAt FROM pages WHERE id = ?", arrayOf(pageId))
        rows.use {
            assertTrue("the row was hard-deleted", it.moveToFirst())
            assertTrue("deletedAt was not stamped", !it.isNull(0))
        }
    }
}

private fun String.hexBytesForTest(): ByteArray = ByteArray(length / 2) { index ->
    val offset = index * 2
    ((this[offset].digitToInt(16) shl 4) or this[offset + 1].digitToInt(16)).toByte()
}
