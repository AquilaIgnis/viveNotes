package st.unamedtba.data

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import st.unamedtba.data.db.NotesDatabase
import st.unamedtba.model.Block
import st.unamedtba.model.BlockType
import st.unamedtba.model.CborDocumentCodec
import st.unamedtba.model.JsonDocumentCodec
import st.unamedtba.model.Mark
import st.unamedtba.model.Outline
import st.unamedtba.model.PageDoc
import st.unamedtba.model.Run
import st.unamedtba.model.newId

/**
 * Measures where time actually goes when a page is saved and loaded.
 *
 * Exists to answer a design question with numbers rather than intuition: whether the document
 * encoding is worth optimising for local storage, or whether the SQLite write dominates it. A
 * faster codec that removes 5% of the cost is not worth losing a database you can read with
 * `sqlite3` when notes go missing.
 */
@RunWith(AndroidJUnit4::class)
class DocumentCostTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository

    private lateinit var file: java.io.File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // On disk, not in memory: an in-memory database does no I/O, which would flatter the
        // codec by removing the very cost it is being compared against.
        file = java.io.File(context.cacheDir, "cost-test.db")
        listOf("", "-wal", "-shm").forEach { java.io.File(file.path + it).delete() }
        db = Room.databaseBuilder(context, NotesDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .build()
        repository = NotesRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        listOf("", "-wal", "-shm").forEach { java.io.File(file.path + it).delete() }
    }

    private fun document(outlines: Int, blocksEach: Int): PageDoc = PageDoc(
        outlines = (0 until outlines).map { outline ->
            Outline.Text(
                id = newId(),
                x = outline * 300f,
                y = 0f,
                blocks = (0 until blocksEach).map { i ->
                    Block(
                        id = newId(),
                        type = if (i % 10 == 0) BlockType.Heading2 else BlockType.Bullet,
                        indent = i % 3,
                        runs = listOf(
                            Run("Item $i with some ordinary prose in it, "),
                            Run("emphasised", setOf(Mark.Bold, Mark.Italic)),
                            Run(" and "),
                            Run("highlighted", setOf(Mark.Highlight(0x66FFEB3B), Mark.FontSize(18))),
                            Run(" trailing text to give the line realistic length."),
                        ),
                    )
                },
            )
        },
    )

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private inline fun median(runs: Int, body: () -> Unit): Double {
        repeat(5) { body() } // warm up
        val samples = (0 until runs).map {
            val start = System.nanoTime()
            body()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return samples[samples.size / 2]
    }

    @Test
    fun measureEncodingAgainstStorage() = runBlocking {
        val notebookId = repository.createNotebook("nb")
        val sectionId = repository.createSection(notebookId, "sec")

        // A typical note and a deliberately extreme one, so the answer is not read off a single
        // unrepresentative size.
        listOf("typical" to document(1, 15), "large" to document(4, 100)).forEach { (label, doc) ->
            val json = JsonDocumentCodec.encodeToString(doc)
            val pageId = repository.createPage(sectionId, label)

            val cborBytes = CborDocumentCodec.encode(doc)
            val jsonGz = gzip(json.encodeToByteArray()).size
            val cborGz = gzip(cborBytes).size
            val encodeMs = median(20) { JsonDocumentCodec.encodeToString(doc) }
            val decodeMs = median(20) { JsonDocumentCodec.decodeFromString(json) }
            val cborEncodeMs = median(20) { CborDocumentCodec.encode(doc) }
            val cborDecodeMs = median(20) { CborDocumentCodec.decode(cborBytes) }
            val saveMs = median(20) { runBlocking { repository.saveDoc(pageId, doc) } }
            val loadMs = median(20) { runBlocking { repository.loadDoc(pageId) } }

            val line = "$label json=${json.length}B cbor=${cborBytes.size}B " +
                "(${"%.0f".format(cborBytes.size * 100.0 / json.length)}%) " +
                "gzip(json)=${jsonGz}B (${"%.0f".format(jsonGz * 100.0 / json.length)}%) " +
                "gzip(cbor)=${cborGz}B (${"%.0f".format(cborGz * 100.0 / json.length)}%) | " +
                "cborEncode=${"%.2f".format(cborEncodeMs)}ms cborDecode=${"%.2f".format(cborDecodeMs)}ms | " +
                "encode=${"%.2f".format(encodeMs)}ms decode=${"%.2f".format(decodeMs)}ms | " +
                "saveDoc=${"%.2f".format(saveMs)}ms loadDoc=${"%.2f".format(loadMs)}ms | " +
                "storage share of save=${"%.0f".format((1 - encodeMs / saveMs) * 100)}%"
            Log.i("DocumentCost", line)

            assertTrue("encoding $label took ${encodeMs}ms", encodeMs < 250.0)
            assertTrue("decoding $label took ${decodeMs}ms", decodeMs < 250.0)
        }
    }
}
