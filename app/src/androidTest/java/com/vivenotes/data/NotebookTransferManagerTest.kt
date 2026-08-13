package com.vivenotes.data

import android.graphics.Bitmap
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.ink.InkCodec
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.plainText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotebookTransferManagerTest {

    private lateinit var root: File
    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var attachmentStore: AttachmentStore
    private lateinit var transfers: NotebookTransferManager
    private var attachmentFile: File? = null
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "notebook-transfer-manager-test")
        root.deleteRecursively()
        root.mkdirs()
        db = Room.databaseBuilder(
            context,
            NotesDatabase::class.java,
            File(root, "source.db").absolutePath,
        ).allowMainThreadQueries().build()
        repository = NotesRepository(db, clock = { now })
        attachmentStore = AttachmentStore(context, db)
        transfers = NotebookTransferManager(
            context = context,
            db = db,
            attachmentStore = attachmentStore,
            transferRoot = File(root, "transfers"),
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        db.close()
        attachmentFile?.delete()
        root.deleteRecursively()
    }

    @Test
    fun importPickerSupportsProviderMimeFallbacks() {
        assertTrue(
            NotebookTransferManager.importMimeTypes().contains("application/octet-stream"),
        )
        assertTrue(NotebookTransferManager.importMimeTypes().contains("application/zip"))
    }

    @Test
    fun importReplacesUntouchedCleanInstallStarterByItsRecordedUuid() = runBlocking {
        val importedId = repository.createNotebook("Restored Notebook")
        val importedSection = repository.createSection(importedId, "Pages")
        repository.createPage(importedSection, "From backup")
        val bundle = ByteArrayOutputStream().also { transfers.exportNotebook(importedId, it) }
            .toByteArray()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destinationDb = Room.databaseBuilder(
            context,
            NotesDatabase::class.java,
            File(root, "clean-install.db").absolutePath,
        ).allowMainThreadQueries().build()
        try {
            val destinationRepository = NotesRepository(destinationDb, clock = { now })
            destinationRepository.seedIfEmpty()
            val starterId = destinationDb.localMetadataDao()
                .value(NotesRepository.REPLACEABLE_STARTER_KEY)!!
            assertTrue(starterId != importedId)

            val result = NotebookTransferManager(
                context,
                destinationDb,
                AttachmentStore(context, destinationDb),
                transferRoot = File(root, "clean-install-transfers"),
                clock = { now },
            ).importNotebook(ByteArrayInputStream(bundle))

            assertEquals(importedId, result.notebookId)
            assertEquals(1, destinationDb.notebookDao().count())
            assertEquals(null, destinationDb.notebookDao().byId(starterId))
            assertTrue(destinationDb.notebookDao().byId(importedId) != null)
        } finally {
            destinationDb.close()
        }
    }

    @Test
    fun importKeepsStarterAfterItHasBeenEdited() = runBlocking {
        val importedId = repository.createNotebook("Restored Notebook")
        val importedSection = repository.createSection(importedId, "Pages")
        repository.createPage(importedSection, "From backup")
        val bundle = ByteArrayOutputStream().also { transfers.exportNotebook(importedId, it) }
            .toByteArray()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destinationDb = Room.databaseBuilder(
            context,
            NotesDatabase::class.java,
            File(root, "edited-install.db").absolutePath,
        ).allowMainThreadQueries().build()
        try {
            val destinationRepository = NotesRepository(destinationDb, clock = { now })
            destinationRepository.seedIfEmpty()
            val starterId = destinationDb.localMetadataDao()
                .value(NotesRepository.REPLACEABLE_STARTER_KEY)!!
            destinationRepository.renameNotebook(starterId, "My real notes")

            NotebookTransferManager(
                context,
                destinationDb,
                AttachmentStore(context, destinationDb),
                transferRoot = File(root, "edited-install-transfers"),
                clock = { now },
            ).importNotebook(ByteArrayInputStream(bundle))

            assertEquals(2, destinationDb.notebookDao().count())
            assertEquals("My real notes", destinationDb.notebookDao().byId(starterId)?.name)
            assertTrue(destinationDb.notebookDao().byId(importedId) != null)
        } finally {
            destinationDb.close()
        }
    }

    @Test
    fun exportThenImportCopiesTextInkHistoryAndAttachments() = runBlocking {
        val notebookId = repository.createNotebook("Field Notes")
        val sectionId = repository.createSection(notebookId, "Observations")
        val pageId = repository.createPage(sectionId, "Heron")
        val attachment = makeAttachment()
        repository.saveDoc(
            pageId,
            PageDoc(
                outlines = listOf(
                    Outline.Text(id = "text", blocks = listOf(Block.of("river bank"))),
                    Outline.Image(
                        id = "photo",
                        attachmentId = attachment.id,
                        width = 120f,
                        height = 80f,
                    ),
                ),
            ),
        )

        val pen = PenPreset(colorArgb = 0xFF112233.toInt(), colorFollowsTheme = false)
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.UNKNOWN, 10f, 20f, 0L)
            add(InputToolType.UNKNOWN, 30f, 40f, 10L)
        }.toImmutable()
        val storedStroke = repository.addStroke(
            InkCodec.encode(Stroke(InkCodec.brushFor(pen), inputs), pageId, 0, pen, now),
        )
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
        repository.setInkColors(
            mapOf(storedStroke.id to StrokeColor(0xFF556677.toInt(), false)),
        )
        val sourceRevisionCount = repository.revisionHistory(pageId).size

        val firstBundle = ByteArrayOutputStream()
            .also { transfers.exportNotebook(notebookId, it) }
            .toByteArray()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destinationDb = Room.databaseBuilder(
            context,
            NotesDatabase::class.java,
            File(root, "destination.db").absolutePath,
        ).allowMainThreadQueries().build()
        try {
            val destinationStore = AttachmentStore(context, destinationDb)
            val destinationTransfers = NotebookTransferManager(
                context,
                destinationDb,
                destinationStore,
                transferRoot = File(root, "destination-transfers"),
                clock = { now },
            )
            val destinationRepository = NotesRepository(destinationDb, clock = { now })
            val result = destinationTransfers.importNotebook(ByteArrayInputStream(firstBundle))

            assertTrue(result.created)
            assertEquals(notebookId, result.notebookId)
            assertEquals("Field Notes", result.notebookName)
            val importedPageId = result.firstPageId!!
            val imported = destinationRepository.loadDoc(importedPageId) as PageLoad.Loaded
            assertEquals("river bank", imported.doc.plainText())
            assertEquals(
                attachment.id,
                imported.doc.outlines.filterIsInstance<Outline.Image>().single().attachmentId,
            )
            assertEquals(0xFF556677.toInt(), destinationRepository.inkFor(importedPageId).single().colorArgb)
            assertEquals(sourceRevisionCount, destinationRepository.revisionHistory(importedPageId).size)

            val revisionRows = destinationRepository.revisionHistory(importedPageId).map { summary ->
                destinationDb.pageRevisionDao().byId(importedPageId, summary.id)!!
            }
            assertTrue(
                "ink history did not retain the stable stroke id",
                revisionRows.map(InkRevisionPayload::unpack).any { snapshot ->
                    snapshot.strokes.any {
                        it.id == storedStroke.id && it.colorArgb == 0xFF112233.toInt()
                    }
                },
            )

            now += 1
            repository.saveDoc(
                pageId,
                PageDoc(
                    outlines = listOf(
                        Outline.Text(id = "text", blocks = listOf(Block.of("river bank updated"))),
                        Outline.Image(
                            id = "photo",
                            attachmentId = attachment.id,
                            width = 120f,
                            height = 80f,
                        ),
                    ),
                ),
            )
            val changedBundle = ByteArrayOutputStream()
                .also { transfers.exportNotebook(notebookId, it) }
                .toByteArray()
            val synced = destinationTransfers.importNotebook(ByteArrayInputStream(changedBundle))

            assertFalse(synced.created)
            assertEquals(1, destinationDb.notebookDao().count())
            assertEquals(
                "river bank updated",
                (destinationRepository.loadDoc(pageId) as PageLoad.Loaded).doc.plainText(),
            )
            val revisionsAfterSync = destinationRepository.revisionHistory(pageId).size

            destinationTransfers.importNotebook(ByteArrayInputStream(changedBundle))

            assertEquals("reimport duplicated the notebook", 1, destinationDb.notebookDao().count())
            assertEquals(
                "reimport duplicated version history",
                revisionsAfterSync,
                destinationRepository.revisionHistory(pageId).size,
            )
            assertEquals(1, destinationDb.attachmentDao().byId(attachment.id)!!.refCount)
            assertTrue(destinationStore.fileFor(attachment.id).isFile)

            val destinationOnlyPage = destinationRepository.createPage(sectionId, "destination only")
            now += 1
            repository.deletePage(pageId)
            val deletionBundle = ByteArrayOutputStream()
                .also { transfers.exportNotebook(notebookId, it) }
                .toByteArray()

            destinationTransfers.importNotebook(ByteArrayInputStream(deletionBundle))

            assertTrue("newer deletion did not sync", destinationDb.pageDao().byId(pageId)!!.deletedAt != null)
            assertTrue(
                "the merge removed a destination-only page",
                destinationDb.pageDao().byId(destinationOnlyPage)!!.deletedAt == null,
            )
        } finally {
            destinationDb.close()
        }
    }

    @Test
    fun pathTraversalIsRejectedBeforeTheLiveDatabaseChanges() = runBlocking {
        val before = db.notebookDao().count()
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("../escape"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()

        val failure = runCatching {
            transfers.importNotebook(ByteArrayInputStream(archive))
        }.exceptionOrNull()

        assertTrue(failure is NotebookTransferException)
        assertEquals(before, db.notebookDao().count())
    }

    @Test
    fun corruptedManifestIsRejectedBeforeTheLiveDatabaseChanges() = runBlocking {
        val notebookId = repository.createNotebook("Original")
        val sectionId = repository.createSection(notebookId, "Section")
        repository.createPage(sectionId, "Page")
        val exported = ByteArrayOutputStream().also {
            transfers.exportNotebook(notebookId, it)
        }.toByteArray()
        val corrupted = rewriteEntry(exported, "manifest.json") { bytes ->
            bytes.copyOf().also { it[it.lastIndex] = '!'.code.toByte() }
        }
        val before = db.notebookDao().count()

        val failure = runCatching {
            transfers.importNotebook(ByteArrayInputStream(corrupted))
        }.exceptionOrNull()

        assertTrue(failure is NotebookTransferException)
        assertEquals(before, db.notebookDao().count())
    }

    private suspend fun makeAttachment(): AttachmentEntity {
        val bytes = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xFF336699.toInt())
                check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, output))
            } finally {
                bitmap.recycle()
            }
            output.toByteArray()
        }
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        attachmentFile = attachmentStore.fileFor(id).also { it.writeBytes(bytes) }
        return AttachmentEntity(
            id = id,
            mimeType = "image/webp",
            pixelWidth = 2,
            pixelHeight = 2,
            byteCount = bytes.size.toLong(),
            refCount = 1,
            createdAt = now,
        ).also { db.attachmentDao().insert(it) }
    }

    private fun rewriteEntry(
        archive: ByteArray,
        target: String,
        transform: (ByteArray) -> ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { destination ->
            ZipInputStream(ByteArrayInputStream(archive)).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val bytes = source.readBytes()
                    destination.putNextEntry(ZipEntry(entry.name))
                    destination.write(if (entry.name == target) transform(bytes) else bytes)
                    destination.closeEntry()
                    source.closeEntry()
                }
            }
        }
    }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
