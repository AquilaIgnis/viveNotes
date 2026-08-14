package com.vivenotes.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.ai.ImageTextLine
import com.vivenotes.ai.ImageTextResult
import com.vivenotes.ai.FormulaRecognitionResult
import com.vivenotes.ai.InkRecognitionEngine
import com.vivenotes.ai.TextRecognitionResult
import com.vivenotes.data.db.ImageTextStatus
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The three promises of `memory/imageOcrPlan.md` that only a database can keep: a picture is read
 * **once** however many times it is placed (IO2), a reading dies with its picture (IO3), and a
 * picture that cannot be read is not retried forever (IO6).
 *
 * The recognizer is a fake. What is under test is the schedule and the storage, and running the real
 * ONNX graphs here would make a correctness test into a slow accuracy test that measures neither
 * well — `simulations/image-ocr/` is where the models are exercised, and `OnnxRecognitionSmokeTest`
 * is where the wiring to them is.
 */
@RunWith(AndroidJUnit4::class)
class ImageTextIndexerTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var attachments: AttachmentStore
    private lateinit var file: File

    /** Counts how many times a picture reached the recognizer. */
    private class CountingEngine(
        private val reading: () -> ImageTextResult? = {
            ImageTextResult(listOf(ImageTextLine("read", 0.9f, emptyList())), 0.9f)
        },
    ) : InkRecognitionEngine {
        val calls = AtomicInteger()

        override suspend fun recognizeText(image: Bitmap) = TextRecognitionResult("", 0f)
        override suspend fun recognizeFormula(image: Bitmap) = FormulaRecognitionResult("")

        override suspend fun recognizeImageText(image: Bitmap): ImageTextResult {
            calls.incrementAndGet()
            return reading() ?: error("recognition failed")
        }
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "image-text-indexer.db")
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
        db = Room.databaseBuilder(context, NotesDatabase::class.java, file.absolutePath).build()
        repository = NotesRepository(db)
        attachments = AttachmentStore(context, db)
    }

    @After
    fun tearDown() {
        db.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    /** Imports a solid-colour picture and returns its content hash. */
    private fun importPicture(color: Int, size: Int = 64): String = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            0f, 0f, size.toFloat(), size.toFloat(),
            Paint().apply { this.color = color },
        )
        val staged = File(context.cacheDir, "picture-$color.png")
        staged.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri: Uri = staged.toUri()
        val imported = attachments.import(uri) ?: error("import failed")
        imported.id
    }

    @Test
    fun readsEachPictureOnceHoweverManyTimesItIsPlaced() = runBlocking {
        val id = importPicture(Color.RED)
        // Three placements of the same bytes: `AttachmentStore` already collapses them to one row,
        // and reading is keyed on that row.
        attachments.retain(id)
        attachments.retain(id)
        val engine = CountingEngine()
        val indexer = ImageTextIndexer(repository, attachments, engine)

        indexer.request(setOf(id, id, id))
        indexer.awaitIdle()

        assertEquals(1, engine.calls.get())
        val row = repository.imageTextFor(listOf(id)).getValue(id)
        assertEquals(ImageTextStatus.Read, row.status)
        assertEquals("read", row.text)
        assertEquals(ImageTextIndexer.ENGINE, row.engine)
    }

    @Test
    fun aSecondPassDoesNotReadWhatIsAlreadyStored() = runBlocking {
        val id = importPicture(Color.GREEN)
        val engine = CountingEngine()
        val indexer = ImageTextIndexer(repository, attachments, engine)

        indexer.request(setOf(id))
        indexer.awaitIdle()
        indexer.request(setOf(id))
        indexer.awaitIdle()

        assertEquals(1, engine.calls.get())
    }

    @Test
    fun aPictureThatCannotBeReadIsRecordedRatherThanRetriedForever() = runBlocking {
        val id = importPicture(Color.BLUE)
        val engine = CountingEngine(reading = { null })
        val indexer = ImageTextIndexer(repository, attachments, engine)

        indexer.request(setOf(id))
        indexer.awaitIdle()
        indexer.request(setOf(id))
        indexer.awaitIdle()

        assertEquals(1, engine.calls.get())
        assertEquals(
            ImageTextStatus.Failed,
            repository.imageTextFor(listOf(id)).getValue(id).status,
        )
    }

    @Test
    fun aPictureWithNoTextIsStoredAsEmptySoItIsNotTriedAgain() = runBlocking {
        val id = importPicture(Color.WHITE)
        val engine = CountingEngine(reading = { ImageTextResult.Empty })
        val indexer = ImageTextIndexer(repository, attachments, engine)

        indexer.request(setOf(id))
        indexer.awaitIdle()
        indexer.request(setOf(id))
        indexer.awaitIdle()

        assertEquals(1, engine.calls.get())
        val row = repository.imageTextFor(listOf(id)).getValue(id)
        assertEquals(ImageTextStatus.Empty, row.status)
        assertEquals(0, row.lineCount)
    }

    @Test
    fun releasingTheLastReferenceTakesTheReadingWithIt() = runBlocking {
        val id = importPicture(Color.MAGENTA)
        val indexer = ImageTextIndexer(repository, attachments, CountingEngine())

        indexer.request(setOf(id))
        indexer.awaitIdle()
        assertTrue(repository.imageTextFor(listOf(id)).containsKey(id))

        // `import` claims one reference; releasing it sweeps the row and the file — IO3.
        attachments.release(id)

        assertNull(attachments.metadata(id))
        assertTrue(repository.imageTextFor(listOf(id)).isEmpty())
        assertEquals(0, repository.deleteOrphanImageText())
    }

    @Test
    fun theSwitchStopsPicturesBeingReadAtAll() = runBlocking {
        val id = importPicture(Color.CYAN)
        val engine = CountingEngine()
        val indexer = ImageTextIndexer(repository, attachments, engine)

        indexer.setEnabled(false)
        indexer.request(setOf(id))
        indexer.awaitIdle()

        assertEquals(0, engine.calls.get())
        assertTrue(repository.imageTextFor(listOf(id)).isEmpty())
    }

    @Test
    fun rebuildingClearsEveryReadingAndKeepsEveryPicture() = runBlocking {
        val id = importPicture(Color.YELLOW)
        val engine = CountingEngine()
        val indexer = ImageTextIndexer(repository, attachments, engine)

        indexer.request(setOf(id))
        indexer.awaitIdle()
        indexer.clear()

        assertTrue(repository.imageTextFor(listOf(id)).isEmpty())
        assertEquals(0, repository.imageTextCount(ImageTextIndexer.ENGINE))
        // The picture itself is untouched: this table is derived and costs only time to rebuild.
        assertTrue(attachments.fileFor(id).isFile)

        indexer.request(setOf(id))
        indexer.awaitIdle()
        assertEquals(2, engine.calls.get())
    }

    private suspend fun ImageTextIndexer.awaitIdle() =
        withTimeout(AWAIT_TIMEOUT_MS) { awaitPass() }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 30_000L
    }
}
