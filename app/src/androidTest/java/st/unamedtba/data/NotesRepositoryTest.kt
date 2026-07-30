package st.unamedtba.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import st.unamedtba.data.db.NotesDatabase
import st.unamedtba.data.db.PageContentEntity
import st.unamedtba.model.Block
import st.unamedtba.model.Outline
import st.unamedtba.model.PageDoc
import st.unamedtba.model.newId
import st.unamedtba.model.plainText

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
    fun aFreshPageLoadsAsAnEmptyDocument() = runBlocking {
        val load = repository.loadDoc(newPage())

        assertTrue("expected a clean load, got $load", load is PageLoad.Loaded)
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
