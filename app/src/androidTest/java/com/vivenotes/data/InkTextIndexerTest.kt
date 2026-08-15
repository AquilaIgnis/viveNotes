package com.vivenotes.data

import android.graphics.Bitmap
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.ai.FormulaRecognitionResult
import com.vivenotes.ai.ImageTextResult
import com.vivenotes.ai.InkRecognitionEngine
import com.vivenotes.ai.InkTextRegionsCodec
import com.vivenotes.ai.TextRecognitionResult
import com.vivenotes.ai.inkPageLayout
import com.vivenotes.data.db.InkTextStatus
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.InkCodec
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Storage, invalidation and race guarantees around the derived handwriting cache. */
@RunWith(AndroidJUnit4::class)
class InkTextIndexerTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var file: File
    private lateinit var pageId: String

    private class TextEngine(
        private val read: suspend (Int) -> TextRecognitionResult = {
            TextRecognitionResult("handwritten words", 0.95f)
        },
    ) : InkRecognitionEngine {
        val calls = AtomicInteger()

        override suspend fun recognizeText(image: Bitmap): TextRecognitionResult =
            read(calls.incrementAndGet())

        override suspend fun recognizeFormula(image: Bitmap) = FormulaRecognitionResult("")

        override suspend fun recognizeImageText(image: Bitmap) = ImageTextResult.Empty
    }

    @Before
    fun setUp() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            file = File(context.cacheDir, "ink-text-indexer.db")
            listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
            db = Room.databaseBuilder(context, NotesDatabase::class.java, file.absolutePath).build()
            repository = NotesRepository(db)
            val notebookId = repository.createNotebook("Notebook")
            val sectionId = repository.createSection(notebookId, "Section")
            pageId = repository.createPage(sectionId, "Page")
            repository.addStroke(stroke("first", 10f, 20f))
        }
    }

    @After
    fun tearDown() {
        db.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    @Test
    fun aCachedPageIsNotReadAgainUntilItsInkChanges() = runBlocking {
        val engine = TextEngine()
        val indexer = InkTextIndexer(repository, engine)
        val request = InkTextPageRequest(pageId, inkPageLayout(emptyList()))
        val loaded = InkPageLoader(repository).load(pageId).strokes
        assertEquals(1, loaded.size)
        assertTrue(loaded.single().pageBounds != null)

        indexer.request(listOf(request))
        indexer.awaitIdle()
        val callsAfterFirstPass = engine.calls.get()
        indexer.request(listOf(request))
        indexer.awaitIdle()

        assertEquals(1, callsAfterFirstPass)
        assertEquals(callsAfterFirstPass, engine.calls.get())
        val first = repository.inkTextFor(listOf(pageId)).getValue(pageId)
        assertEquals(InkTextStatus.Read, first.status)
        assertEquals("handwritten words", InkTextRegionsCodec.decode(first.regionsJson).single().text)

        repository.addStroke(stroke("second", 50f, 20f))
        assertFalse(repository.inkTextFor(listOf(pageId)).containsKey(pageId))
        indexer.request(listOf(request))
        indexer.awaitIdle()

        assertTrue(engine.calls.get() > callsAfterFirstPass)
        assertTrue(repository.inkTextFor(listOf(pageId)).containsKey(pageId))
    }

    @Test
    fun recognitionFinishingAfterAnEditCannotRestoreTheStaleReading() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = TextEngine { call ->
            if (call == 1) {
                entered.complete(Unit)
                release.await()
            }
            TextRecognitionResult("reading $call", 0.95f)
        }
        val indexer = InkTextIndexer(repository, engine)
        val request = InkTextPageRequest(pageId, inkPageLayout(emptyList()))

        indexer.request(listOf(request))
        withTimeout(AWAIT_TIMEOUT_MS) { entered.await() }
        repository.addStroke(stroke("arrived-during-recognition", 90f, 20f))
        release.complete(Unit)
        indexer.awaitIdle()

        assertTrue(repository.inkTextFor(listOf(pageId)).isEmpty())

        indexer.request(listOf(request))
        indexer.awaitIdle()
        val reading = repository.inkTextFor(listOf(pageId)).getValue(pageId)
        val texts = InkTextRegionsCodec.decode(reading.regionsJson).map { it.text }
        assertTrue(texts.isNotEmpty())
        assertFalse("reading 1" in texts)
        assertEquals("reading 2", texts.first())
    }

    @Test
    fun disablingHandwritingSearchLeavesAnUnreadPageUntouched() = runBlocking {
        val engine = TextEngine()
        val indexer = InkTextIndexer(repository, engine)

        indexer.setEnabled(false)
        indexer.request(listOf(InkTextPageRequest(pageId, inkPageLayout(emptyList()))))
        indexer.awaitIdle()

        assertEquals(0, engine.calls.get())
        assertTrue(repository.inkTextFor(listOf(pageId)).isEmpty())
    }

    private fun stroke(id: String, x: Float, y: Float) = InkCodec.encode(
        stroke = Stroke(
            brush = InkCodec.brushFor(PEN),
            inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.UNKNOWN, x, y, 0L)
                add(InputToolType.UNKNOWN, x + 12f, y + 8f, 8L)
                add(InputToolType.UNKNOWN, x + 24f, y, 16L)
            }.toImmutable(),
        ),
        pageId = pageId,
        seq = 0,
        pen = PEN,
        now = 1L,
    ).copy(id = id)

    private suspend fun InkTextIndexer.awaitIdle() =
        withTimeout(AWAIT_TIMEOUT_MS) { awaitPass() }

    private companion object {
        val PEN = PenPreset(colorArgb = 0xFF111111.toInt(), colorFollowsTheme = false)
        const val AWAIT_TIMEOUT_MS = 30_000L
    }
}
