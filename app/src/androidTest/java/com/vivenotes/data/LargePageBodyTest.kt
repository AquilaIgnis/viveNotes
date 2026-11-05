package com.vivenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.BODY_PIECE_CHARS
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page body past Android's 2 MB cursor window.
 *
 * Saving one always worked, so a large paste looked fine until the next time the page was opened, when
 * the row could not be read back at all. These all fail with `SQLiteBlobTooBigException` on a query
 * that selects the body whole.
 */
@RunWith(AndroidJUnit4::class)
class LargePageBodyTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var pageId: String

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .build()
        repository = NotesRepository(db)
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        pageId = repository.createPage(sectionId, "Large")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun textDoc(text: String) = PageDoc(
        outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(text)))),
    )

    private fun loadedText(load: PageLoad): String {
        assertTrue("$load".take(200), load is PageLoad.Loaded)
        return ((load as PageLoad.Loaded).doc.outlines.single() as Outline.Text).blocks.single().text
    }

    @Test
    fun aBodyPastTheCursorWindowCanBeOpenedAgainAfterSaving() = runBlocking {
        val text = "x".repeat(5_000_000)

        repository.saveDoc(pageId, textDoc(text))

        assertEquals(text, loadedText(repository.loadDoc(pageId)))
    }

    /** The flow an open editor follows, which fails the same way and on a different query. */
    @Test
    fun anOpenEditorCanFollowABodyPastTheCursorWindow() = runBlocking {
        val text = "y".repeat(3_000_000)

        repository.saveDoc(pageId, textDoc(text))

        assertEquals(text, loadedText(repository.observeDoc(pageId).first()))
    }

    /**
     * Characters that take several bytes, because the pieces are counted in characters and split
     * where SQLite says one ends. Half an emoji here would corrupt the text without any error.
     */
    @Test
    fun charactersOfEveryWidthSurviveBeingSplitIntoPieces() = runBlocking {
        val text = "aé€😀".repeat(600_000)

        repository.saveDoc(pageId, textDoc(text))

        assertEquals(text, loadedText(repository.loadDoc(pageId)))
    }

    @Test
    fun aBodyOfExactlyOnePieceAndOfOneCharacterMoreBothComeBackWhole() = runBlocking {
        val dao = db.pageContentDao()
        for (length in listOf(BODY_PIECE_CHARS, BODY_PIECE_CHARS + 1, BODY_PIECE_CHARS * 2)) {
            val body = "z".repeat(length)
            dao.upsert(PageContentEntity(pageId, body, 7L, "json/1"))

            assertEquals(length, dao.byId(pageId)!!.docJson.length)
            assertEquals(body, dao.byId(pageId)!!.docJson)
        }
    }

    @Test
    fun everythingElseOnTheRowComesBackWithIt() = runBlocking {
        val dao = db.pageContentDao()
        val row = PageContentEntity(pageId, "z".repeat(3_000_000), 42L, "json/1")

        dao.upsert(row)

        assertEquals(row, dao.byId(pageId))
        assertEquals(listOf(row), dao.byIds(listOf(pageId, "no-such-page")))
        assertEquals(3_000_000, dao.byteLength(pageId))
    }

    @Test
    fun aPageWithNoBodyIsStillSimplyAbsent() = runBlocking {
        val dao = db.pageContentDao()

        assertNull(dao.byId("no-such-page"))
        assertNull(dao.byteLength("no-such-page"))
        assertNull(dao.observeById("no-such-page").first())
        assertEquals(emptyList<PageContentEntity>(), dao.byIds(listOf("no-such-page")))
    }

    /** The scans that find bodies by what they mention, which a sweep and a move to the cloud run. */
    @Test
    fun theBodyScansCanReturnABodyPastTheCursorWindow() = runBlocking {
        val dao = db.pageContentDao()
        val padding = "z".repeat(3_000_000)
        val row = PageContentEntity(pageId, "attachmentId borderFollowsTheme $padding", 1L, "json/1")

        dao.upsert(row)

        assertEquals(listOf(row), dao.picturePlacingBodies())
        assertEquals(listOf(row), dao.borderCarryingBodies())
    }

    @Test
    fun aSecondSaveOfALargeBodyStillCheckpointsTheFirst() = runBlocking {
        repository.saveDoc(pageId, textDoc("p".repeat(3_000_000)))
        repository.saveDoc(pageId, textDoc("q".repeat(3_000_000)))

        assertEquals("q".repeat(3_000_000), loadedText(repository.loadDoc(pageId)))
        assertTrue(repository.revisionHistory(pageId).isNotEmpty())
    }
}
