package com.vivenotes.data.sync

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PenPreset
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.InkCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * What the first upload of a drawn notebook costs, and the first download of one.
 *
 * Connecting an installation that has already been drawn on seeds every ink row into the outbox at
 * once, so the first push is by far the largest thing sync ever does. Its mirror is the first pull
 * into an empty device, at its worst when the tree arrives after the ink hanging from it.
 *
 * The assertions are the invariants that hold whatever the numbers are — every row leaves exactly
 * once, no request exceeds the transport's cap, the outbox drains — and the numbers themselves are
 * logged under [TAG]. A test that asserted on measured bytes would fail the first time the wire
 * shape improved.
 */
@RunWith(AndroidJUnit4::class)
class InkSyncCostTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var server: AcceptingTransport
    private lateinit var attachmentDirectory: File
    private lateinit var hierarchy: HierarchySync

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .build()
        repository = NotesRepository(db)
        server = AcceptingTransport()
        attachmentDirectory = File(context.cacheDir, "ink-cost-attachments-${UUID.randomUUID()}")
        hierarchy = HierarchySync(
            db,
            server,
            RemoteInkSignal(),
            AttachmentBlobSync(db, server, TemporaryAttachmentBytes(attachmentDirectory)),
        )
    }

    @After
    fun tearDown() {
        db.close()
        attachmentDirectory.deleteRecursively()
    }

    @Test
    fun firstUploadOfADrawnNotebook() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Drawn")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")

        // One real encode, reused as the payload of every row: `ink/v1` on the wire is base64 of
        // exactly these bytes, so the request is the size a real page's would be without paying for
        // STROKE_COUNT native meshes to find that out.
        val points = requireNotNull(sampleStroke(pageId).points)
        val rows = (0 until STROKE_COUNT).map { index ->
            InkStrokeEntity(
                id = UUID.randomUUID().toString(),
                pageId = pageId,
                seq = index,
                brushFamily = "pressure-pen",
                brushVersion = InkCodec.BRUSH_VERSION,
                sizeDp = 2.5f,
                colorArgb = 0xFF101010.toInt(),
                colorFollowsTheme = null,
                epsilon = 0.1f,
                stabilization = 1,
                minX = 0f,
                minY = 0f,
                maxX = 100f,
                maxY = 100f,
                points = points,
                enc = InkCodec.ENCODING,
                createdAt = 1_000L + index,
            )
        }
        db.inkStrokeDao().upsert(rows)

        val startedAt = System.nanoTime()
        val result = hierarchy.run(account()) as SyncRunResult.Succeeded
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        val strokesSent = server.pushes.flatten().filter { kindOf(it) == "inkStroke" }
        assertEquals(
            "every stroke has to leave exactly once, and none of them twice",
            STROKE_COUNT,
            strokesSent.map(::idOf).distinct().size,
        )
        assertEquals(STROKE_COUNT, strokesSent.size)
        assertEquals(STROKE_COUNT + HIERARCHY_ROWS, result.summary.pushed)
        assertTrue("the outbox did not drain", db.syncDao().outbox(512).isEmpty())

        val bodies = server.pushes.map(::encodedBody)
        bodies.forEach {
            assertTrue(
                "a request of ${it.size} B exceeds the transport's $MAX_SYNC_PUSH_BYTES B cap",
                it.size <= MAX_SYNC_PUSH_BYTES,
            )
        }
        val total = bodies.sumOf { it.size }
        val compressed = bodies.sumOf { gzipped(it) }
        // Base64 is 4 bytes per 3, so what is left over is the envelope: the ids, the kind, the
        // draw order, the brush and the eleven other columns a stroke carries.
        val payloadPerStroke = 4 * ((points.size + 2) / 3)
        val envelopePerStroke = total / STROKE_COUNT - payloadPerStroke
        Log.i(
            TAG,
            """
            First upload of $STROKE_COUNT strokes (${points.size} B of points each)
              requests            ${bodies.size}, capped at $MAX_SYNC_PUSH_BYTES B each
              rows per request    ${server.pushes.map { it.size }}
              stored points       ${STROKE_COUNT.toLong() * points.size} B
              sent                $total B, ${total / STROKE_COUNT} B per stroke
              of which points     $payloadPerStroke B base64, envelope $envelopePerStroke B
              sent gzipped        $compressed B, ${compressed / STROKE_COUNT} B per stroke
              elapsed             $elapsedMs ms, in-memory Room and a transport that only counts
              projected at the reference page's 565 B of points per stroke:
                                  ${envelopePerStroke + 4 * ((565 + 2) / 3)} B per stroke,
                                  ${(envelopePerStroke + 4 * ((565 + 2) / 3)) * 9_553L / 1_048_576} MiB for its 9,553
            """.trimIndent(),
        )
    }

    @Test
    fun firstPullOfADrawnNotebookWhosePageArrivesLast() = runBlocking<Unit> {
        val points = requireNotNull(sampleStroke(PULLED_PAGE).points)
        val transport = ParentsLastTransport(PULLED_STROKES, points)
        val pulling = HierarchySync(
            db,
            transport,
            RemoteInkSignal(),
            AttachmentBlobSync(db, transport, TemporaryAttachmentBytes(attachmentDirectory)),
        )

        val startedAt = System.nanoTime()
        val result = pulling.run(account()) as SyncRunResult.Succeeded
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(PULLED_STROKES + PULLED_TREE_ROWS, result.summary.pulled)
        assertEquals(PULLED_STROKES, db.inkStrokeDao().countForPages(listOf(PULLED_PAGE)))
        assertEquals(
            "the cursor must commit once nothing is held",
            transport.finalCursor,
            db.syncDao().state()!!.cursor,
        )
        Log.i(
            TAG,
            """
            First pull of $PULLED_STROKES strokes (${points.size} B of points each), page last
              pages               ${transport.finalCursor}, every stroke held until the last
              elapsed             $elapsedMs ms, ${elapsedMs * 1_000 / PULLED_STROKES} µs per stroke,
                                  in-memory Room and a transport that only serves
            """.trimIndent(),
        )
    }

    /** One genuine stroke, for its encoded inputs and nothing else. */
    private fun sampleStroke(pageId: String): InkStrokeEntity {
        val inputs = MutableStrokeInputBatch().apply {
            repeat(SAMPLE_POINTS) { index ->
                val t = index.toFloat()
                add(InputToolType.STYLUS, 10f + t, 40f + t * 0.7f, index * 8L, pressure = 0.5f)
            }
        }.toImmutable()
        return InkCodec.encode(
            InkCodec.eraseMask(inputs, sizeDp = 2.5f),
            pageId,
            seq = 0,
            pen = PenPreset(),
        )
    }

    /** The exact bytes [SyncServerClient] would put on the wire for one batch. */
    private fun encodedBody(changes: List<JsonObject>): ByteArray = JsonObject(
        linkedMapOf(
            "batchId" to JsonPrimitive(UUID.randomUUID().toString()),
            "changes" to JsonArray(changes),
        ),
    ).toString().encodeToByteArray()

    private fun gzipped(body: ByteArray): Int = ByteArrayOutputStream().also { sink ->
        GZIPOutputStream(sink).use { it.write(body) }
    }.size()

    private fun kindOf(change: JsonObject) = change.getValue("kind").jsonPrimitive.content

    private fun idOf(change: JsonObject) = change.getValue("id").jsonPrimitive.content

    private fun account() = SyncAccount(
        serverUrl = "http://unused",
        accountId = "account",
        deviceId = "device",
        token = "vive_test",
        deviceName = "Test",
    )

    /**
     * A server that accepts everything, so what is being measured is this device's cost alone.
     *
     * Deliberately not [HierarchySyncTest]'s in-memory server: that one models versions, conflicts
     * and a pull log, and a pull that echoed the whole corpus back would be measuring the fake.
     */
    private class AcceptingTransport : SyncTransport {
        val pushes = mutableListOf<List<JsonObject>>()
        private var cursor = 0L

        override suspend fun getCursor(serverBaseUrl: String, token: String) =
            ServerResult.Success(cursor)

        override suspend fun pullChanges(
            serverBaseUrl: String,
            token: String,
            since: Long,
            limit: Int,
        ): ServerResult<PullChangesPage> =
            ServerResult.Success(PullChangesPage(emptyList(), cursor, hasMore = false))

        override suspend fun pushChanges(
            serverBaseUrl: String,
            token: String,
            batchId: String,
            changes: List<JsonObject>,
        ): ServerResult<PushChangesReply> {
            pushes += changes
            cursor++
            return ServerResult.Success(
                PushChangesReply(
                    applied = changes.map {
                        AppliedServerChange(
                            it.getValue("kind").jsonPrimitive.content,
                            it.getValue("id").jsonPrimitive.content,
                            1L,
                        )
                    },
                    rejected = emptyList(),
                    serverCursor = cursor,
                ),
            )
        }

        override suspend fun revokeDevice(
            serverBaseUrl: String,
            token: String,
            deviceId: String,
        ): ServerResult<Unit> = ServerResult.Success(Unit)

        // A drawn notebook has no pictures in it, and a measurement that quietly grew a byte route
        // would be measuring something else. These fail rather than answering.

        override suspend fun hasBlob(
            serverBaseUrl: String,
            token: String,
            digest: String,
        ): ServerResult<Boolean> = error("the ink cost measurement has no attachments")

        override suspend fun uploadBlob(
            serverBaseUrl: String,
            token: String,
            digest: String,
            file: File,
        ): ServerResult<Boolean> = error("the ink cost measurement has no attachments")

        override suspend fun downloadBlob(
            serverBaseUrl: String,
            token: String,
            digest: String,
            target: File,
        ): ServerResult<Boolean> = error("the ink cost measurement has no attachments")
    }

    /**
     * An account whose notebook, section and page were all last written after the ink on that page,
     * served in the server's order: every stroke first, [PULL_PAGE] to a response, then the three
     * rows they hang from.
     *
     * The worst case for a first pull, since this device has to hold every stroke until the end.
     * Rows are built per request, so the transport holds nothing a real server's response would not.
     */
    private class ParentsLastTransport(
        private val strokeCount: Int,
        points: ByteArray,
    ) : SyncTransport {
        private val encodedPoints = JsonPrimitive(Base64.getEncoder().encodeToString(points))
        private val strokePages = (strokeCount + PULL_PAGE - 1) / PULL_PAGE
        val finalCursor = strokePages + 1L

        override suspend fun getCursor(serverBaseUrl: String, token: String) =
            ServerResult.Success(finalCursor)

        override suspend fun pullChanges(
            serverBaseUrl: String,
            token: String,
            since: Long,
            limit: Int,
        ): ServerResult<PullChangesPage> {
            val served = since.toInt()
            if (served >= strokePages) {
                return ServerResult.Success(PullChangesPage(tree(), finalCursor, hasMore = false))
            }
            val first = served * PULL_PAGE
            val strokes = (first until minOf(first + PULL_PAGE, strokeCount)).map(::stroke)
            return ServerResult.Success(PullChangesPage(strokes, served + 1L, hasMore = true))
        }

        private fun tree(): List<JsonObject> = listOf(
            row(
                "notebook",
                PULLED_NOTEBOOK,
                "name" to JsonPrimitive("Drawn"),
                "colorArgb" to JsonPrimitive(1),
                "sortIndex" to JsonPrimitive(0),
                "expanded" to JsonPrimitive(true),
            ),
            row(
                "section",
                PULLED_SECTION,
                "notebookId" to JsonPrimitive(PULLED_NOTEBOOK),
                "name" to JsonPrimitive("Section"),
                "colorArgb" to JsonPrimitive(2),
                "sortIndex" to JsonPrimitive(0),
            ),
            row(
                "page",
                PULLED_PAGE,
                "sectionId" to JsonPrimitive(PULLED_SECTION),
                "title" to JsonPrimitive("Page"),
                "preview" to JsonPrimitive(""),
                "sortIndex" to JsonPrimitive(0),
            ),
        )

        private fun stroke(index: Int): JsonObject = row(
            "inkStroke",
            "stroke-$index",
            "pageId" to JsonPrimitive(PULLED_PAGE),
            "drawOrder" to JsonPrimitive(index),
            "brushFamily" to JsonPrimitive("pressure-pen"),
            "brushVersion" to JsonPrimitive(InkCodec.BRUSH_VERSION),
            "sizeDp" to JsonPrimitive(2.5f),
            "colorArgb" to JsonPrimitive(0xFF101010.toInt()),
            "colorFollowsTheme" to JsonNull,
            "epsilon" to JsonPrimitive(0.1f),
            "stabilization" to JsonPrimitive(1),
            "minX" to JsonPrimitive(0f),
            "minY" to JsonPrimitive(0f),
            "maxX" to JsonPrimitive(100f),
            "maxY" to JsonPrimitive(100f),
            "points" to encodedPoints,
            "enc" to JsonPrimitive(InkCodec.ENCODING),
            "groupId" to JsonNull,
        )

        /** The fields every kind carries, then [fields]. */
        private fun row(kind: String, id: String, vararg fields: Pair<String, JsonElement>) =
            JsonObject(
                linkedMapOf<String, JsonElement>(
                    "kind" to JsonPrimitive(kind),
                    "id" to JsonPrimitive(id),
                    "version" to JsonPrimitive(1),
                    "deletedAt" to JsonNull,
                    "updatedAt" to JsonPrimitive(1_000L),
                    "createdAt" to JsonPrimitive(1_000L),
                ).apply { putAll(fields) },
            )

        override suspend fun pushChanges(
            serverBaseUrl: String,
            token: String,
            batchId: String,
            changes: List<JsonObject>,
        ): ServerResult<PushChangesReply> = error("a first pull into an empty device pushes nothing")

        override suspend fun revokeDevice(
            serverBaseUrl: String,
            token: String,
            deviceId: String,
        ): ServerResult<Unit> = ServerResult.Success(Unit)

        override suspend fun hasBlob(
            serverBaseUrl: String,
            token: String,
            digest: String,
        ): ServerResult<Boolean> = error("the ink cost measurement has no attachments")

        override suspend fun uploadBlob(
            serverBaseUrl: String,
            token: String,
            digest: String,
            file: File,
        ): ServerResult<Boolean> = error("the ink cost measurement has no attachments")

        override suspend fun downloadBlob(
            serverBaseUrl: String,
            token: String,
            digest: String,
            target: File,
        ): ServerResult<Boolean> = error("the ink cost measurement has no attachments")
    }

    private companion object {
        const val TAG = "InkSyncCost"

        /**
         * Strokes on the one page the pull measurement downloads: forty responses of ink before
         * the page they belong to, a scale at which holding them has a visible cost. The real
         * account that prompted the measurement held 49,331 this way.
         */
        const val PULLED_STROKES = 20_000

        /** Rows to a pull response, the server's default `limit`. */
        const val PULL_PAGE = 512

        /** The notebook, the section and the page, served after every stroke. */
        const val PULLED_TREE_ROWS = 3
        const val PULLED_NOTEBOOK = "pulled-notebook"
        const val PULLED_SECTION = "pulled-section"
        const val PULLED_PAGE = "pulled-page"

        /**
         * Enough strokes to cross the row cap several times and to make a per-stroke number mean
         * something, and few enough that the suite stays a suite. The reference page in
         * The measured corpus has 9,553; this scales linearly in rows, and the per-stroke
         * figures are what carry across.
         */
        const val STROKE_COUNT = 2_000

        /** A short handwritten mark. Long enough that the envelope is not most of the payload. */
        const val SAMPLE_POINTS = 60

        /** The notebook, the section, the page and its (empty) body, all pushed ahead of the ink. */
        const val HIERARCHY_ROWS = 4
    }
}
