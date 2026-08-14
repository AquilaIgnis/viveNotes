package com.vivenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.AttachmentTextEntity
import com.vivenotes.data.db.ImageTextStatus
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.search.ContentKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The feature the user asked for, end to end minus the model: a word inside a picture is findable,
 * and it is findable **once per place it actually is** — `memory/imageOcrPlan.md` IO2, IO5, IO6.
 *
 * `ContentSearchTest` covers the same rules over the pure model. This covers them over the real
 * query path: stored documents, the cache table, the index's per-page decode, and the live page.
 */
@RunWith(AndroidJUnit4::class)
class ContentSearchIndexPicturesTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var index: ContentSearchIndex
    private lateinit var file: File

    private lateinit var notebookId: String
    private lateinit var sectionId: String

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "content-search-pictures.db")
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
        db = Room.databaseBuilder(context, NotesDatabase::class.java, file.absolutePath).build()
        repository = NotesRepository(db)
        index = ContentSearchIndex(repository)

        notebookId = repository.createNotebook("Notebook")
        sectionId = repository.createSection(notebookId, "Section")
        Unit
    }

    @After
    fun tearDown() {
        db.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private suspend fun storePicture(id: String, text: String, status: ImageTextStatus = ImageTextStatus.Read) {
        db.attachmentDao().insert(
            AttachmentEntity(
                id = id,
                mimeType = "image/webp",
                pixelWidth = 800,
                pixelHeight = 600,
                byteCount = 1024,
                refCount = 1,
                createdAt = 1L,
            ),
        )
        repository.saveImageText(
            AttachmentTextEntity(
                attachmentId = id,
                text = text,
                lineCount = text.lines().size,
                confidence = 0.9f,
                engine = ImageTextIndexer.ENGINE,
                status = status,
                durationMs = 1,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun page(title: String, vararg outlines: Outline): String {
        val pageId = repository.createPage(sectionId, title)
        repository.saveDoc(pageId, PageDoc(outlines = outlines.toList()))
        return pageId
    }

    private fun picture(id: String, attachmentId: String, y: Float = 0f) =
        Outline.Image(id = id, y = y, attachmentId = attachmentId, height = 100f)

    @Test
    fun aWordInsideAPictureIsFound() = runBlocking {
        storePicture("sha-one", "Quarterly revenue\nup 14 percent")
        val pageId = page("Slides", picture("frame", "sha-one"))

        val outcome = index.search(notebookId, "revenue", livePageId = null, liveUnits = emptyList())

        val hit = outcome.results.pages.single { it.pageId == pageId }.hits.single()
        assertEquals(ContentKind.Image, hit.unit.kind)
        // The line, not the whole picture: the snippet a panel shows is a real line of it.
        assertEquals("Quarterly revenue", hit.unit.text)
        // The outline, so opening the hit can select the picture on the page.
        assertEquals("frame", hit.unit.boxId)
        assertEquals("sha-one", hit.unit.attachmentId)
        assertEquals(setOf("sha-one"), outcome.pictures)
    }

    @Test
    fun aPicturePlacedTwiceOnOnePageIsOneResult() = runBlocking {
        storePicture("sha-one", "Quarterly revenue")
        page("Slides", picture("frame-a", "sha-one"), picture("frame-b", "sha-one", y = 400f))

        val outcome = index.search(notebookId, "revenue", livePageId = null, liveUnits = emptyList())

        assertEquals(1, outcome.results.hitCount)
        assertEquals("frame-a", outcome.results.pages.single().hits.single().unit.boxId)
    }

    @Test
    fun theSamePictureOnTwoPagesIsOneResultPerPage() = runBlocking {
        storePicture("sha-one", "Quarterly revenue")
        val first = page("Slides", picture("frame-a", "sha-one"))
        val second = page("Notes", picture("frame-b", "sha-one"))

        val outcome = index.search(notebookId, "revenue", livePageId = null, liveUnits = emptyList())

        assertEquals(2, outcome.results.hitCount)
        assertEquals(
            setOf(first, second),
            outcome.results.pages.mapTo(mutableSetOf()) { it.pageId },
        )
        // One picture, one row of stored text, two places to go.
        assertEquals(setOf("sha-one"), outcome.pictures)
    }

    @Test
    fun anUnreadPictureIsReportedButFindsNothing() = runBlocking {
        db.attachmentDao().insert(
            AttachmentEntity(
                id = "sha-unread",
                mimeType = "image/webp",
                pixelWidth = 8,
                pixelHeight = 8,
                byteCount = 8,
                refCount = 1,
                createdAt = 1L,
            ),
        )
        page("Slides", picture("frame", "sha-unread"))

        val outcome = index.search(notebookId, "revenue", livePageId = null, liveUnits = emptyList())

        assertEquals(0, outcome.results.hitCount)
        // Reported so the indexer knows to read it — which is how the result appears later.
        assertEquals(setOf("sha-unread"), outcome.pictures)
    }

    @Test
    fun aPictureReadAsEmptyFindsNothing() = runBlocking {
        storePicture("sha-blank", "", ImageTextStatus.Empty)
        page("Slides", picture("frame", "sha-blank"))

        val outcome = index.search(notebookId, "revenue", livePageId = null, liveUnits = emptyList())

        assertEquals(0, outcome.results.hitCount)
    }

    @Test
    fun aTypedLineOutranksTheSameWordInAPicture() = runBlocking {
        storePicture("sha-one", "Quarterly revenue")
        page(
            "Slides",
            picture("frame", "sha-one"),
            Outline.Text(id = "box", y = 300f, blocks = listOf(Block.of("Quarterly revenue"))),
        )

        val outcome = index.search(notebookId, "revenue", livePageId = null, liveUnits = emptyList())

        val hits = outcome.results.pages.single().hits
        assertEquals(2, hits.size)
        assertEquals(ContentKind.Text, hits.first().unit.kind)
        assertEquals(ContentKind.Image, hits.last().unit.kind)
    }

    @Test
    fun theOpenPagesPicturesAreSearchedFromLiveStateNotStorage() = runBlocking {
        storePicture("sha-one", "Quarterly revenue")
        // Stored with no pictures at all: the page has just been edited and not yet autosaved.
        val pageId = page("Slides")

        val outcome = index.search(
            notebookId,
            "revenue",
            livePageId = pageId,
            liveUnits = emptyList(),
            liveImages = listOf(
                com.vivenotes.model.search.ImagePlacement(pageId, sectionId, "frame", "sha-one"),
            ),
        )

        assertEquals(1, outcome.results.hitCount)
        assertTrue("sha-one" in outcome.pictures)
    }
}
