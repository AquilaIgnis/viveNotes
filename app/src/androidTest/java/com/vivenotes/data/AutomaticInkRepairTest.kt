package com.vivenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one-shot pass that gives back ink recorded as deliberately white or black.
 *
 * What it is repairing is a picker that could not tell "white" from "the ink this dark canvas is
 * showing me": every stroke drawn after that tap was stored as a deliberate choice, and a deliberate
 * white is kept — including on the white sheet a PDF export always is, where it does not appear at
 * all. The rules themselves are pinned on the JVM in `AutomaticInkRepairRulesTest`; what needs a
 * database is that the pass reaches both kinds of mark and runs exactly once.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticInkRepairTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var repair: AutomaticInkRepair

    private val red = 0xFFE53935.toInt()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NotesRepository(db)
        repair = AutomaticInkRepair(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun deliberateWhiteInkFollowsTheCanvasAgainAndChosenColorsDoNot() = runBlocking {
        val pageId = newPage()
        db.inkStrokeDao().insert(
            listOf(
                stroke("white", pageId, seq = 0, colorArgb = AUTOMATIC_LIGHT, followsTheme = false),
                stroke("black", pageId, seq = 1, colorArgb = AUTOMATIC_DARK, followsTheme = false),
                stroke("red", pageId, seq = 2, colorArgb = red, followsTheme = false),
                // A highlight is deliberate by definition, and its alpha keeps it out of the match.
                stroke("wash", pageId, seq = 3, colorArgb = 0x66FFFFFF, followsTheme = false),
                stroke("automatic", pageId, seq = 4, colorArgb = AUTOMATIC_LIGHT, followsTheme = true),
                stroke("legacy", pageId, seq = 5, colorArgb = AUTOMATIC_LIGHT, followsTheme = null),
            ),
        )

        val result = repair.runIfNeeded()

        assertEquals(2, result.strokes)
        val byId = db.inkStrokeDao()
            .byIds(listOf("white", "black", "red", "wash", "automatic", "legacy"))
            .associateBy { it.id }
        assertNull(byId.getValue("white").colorFollowsTheme)
        assertNull(byId.getValue("black").colorFollowsTheme)
        // The colour itself is never rewritten — only whether it is still a statement of intent.
        assertEquals(AUTOMATIC_LIGHT, byId.getValue("white").colorArgb)
        assertEquals(false, byId.getValue("red").colorFollowsTheme)
        assertEquals(false, byId.getValue("wash").colorFollowsTheme)
        assertEquals(true, byId.getValue("automatic").colorFollowsTheme)
        assertNull(byId.getValue("legacy").colorFollowsTheme)
    }

    /** A line held out of a stroke is a shape carrying the pen's flag, so it has to be repaired too. */
    @Test
    fun deliberatelyWhiteBordersAreRepairedOnStoredPages() = runBlocking {
        val pageId = newPage()
        repository.saveDoc(
            pageId,
            PageDoc(
                outlines = listOf(
                    Outline.Shape(id = "line", borderArgb = AUTOMATIC_LIGHT, borderFollowsTheme = false),
                    Outline.Shape(id = "chosen", borderArgb = red, borderFollowsTheme = false),
                ),
            ),
        )

        val result = repair.runIfNeeded()

        assertEquals(1, result.pages)
        val doc = (repository.loadDoc(pageId) as PageLoad.Loaded).doc
        val shapes = doc.outlines.filterIsInstance<Outline.Shape>().associateBy { it.id }
        assertNull(shapes.getValue("line").borderFollowsTheme)
        assertEquals(false, shapes.getValue("chosen").borderFollowsTheme)
    }

    /**
     * Once per installation, so that a colour picked *after* the repair is still the user's to keep.
     * The marker is what makes the pass a correction of the past rather than a standing policy.
     */
    @Test
    fun theRepairRunsOnlyOnce() = runBlocking {
        val pageId = newPage()
        db.inkStrokeDao().insert(
            stroke("white", pageId, seq = 0, colorArgb = AUTOMATIC_LIGHT, followsTheme = false),
        )

        assertEquals(1, repair.runIfNeeded().strokes)

        db.inkStrokeDao().insert(
            stroke("deliberate", pageId, seq = 1, colorArgb = AUTOMATIC_LIGHT, followsTheme = false),
        )
        val second = repair.runIfNeeded()

        assertEquals(true, second.isEmpty)
        assertEquals(
            false,
            db.inkStrokeDao().byIds(listOf("deliberate")).single().colorFollowsTheme,
        )
    }

    private suspend fun newPage(): String {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        return repository.createPage(sectionId, "Page")
    }

    private fun stroke(
        id: String,
        pageId: String,
        seq: Int,
        colorArgb: Int,
        followsTheme: Boolean?,
    ) = InkStrokeEntity(
        id = id,
        pageId = pageId,
        seq = seq,
        brushFamily = "marker",
        brushVersion = 1,
        sizeDp = 4f,
        colorArgb = colorArgb,
        colorFollowsTheme = followsTheme,
        epsilon = 0.1f,
        stabilization = 0,
        minX = 0f,
        minY = 0f,
        maxX = 1f,
        maxY = 1f,
        points = byteArrayOf(1),
        enc = "test/1",
        createdAt = 0L,
        deletedAt = null,
    )
}
