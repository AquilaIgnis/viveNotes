package com.vivenotes.data

import android.database.sqlite.SQLiteDatabase
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
import com.vivenotes.data.db.SyncStateEntity
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

    /**
     * Opens a database wired the way [NotesDatabase.create] wires the real one.
     *
     * The callback is the part that matters and the part that was missing: it installs the sync
     * triggers, so an export taken here carries the same `sqlite_master` a connected device's
     * export carries. Without it these tests exercised a database shape that only exists in tests,
     * and a bundle that no build could import passed them all.
     */
    private fun openDatabase(name: String): NotesDatabase = Room.databaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        NotesDatabase::class.java,
        File(root, name).absolutePath,
    ).addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK).allowMainThreadQueries().build()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "notebook-transfer-manager-test")
        root.deleteRecursively()
        root.mkdirs()
        db = openDatabase("source.db")
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
        val destinationDb = openDatabase("clean-install.db")
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
            // Tombstoned, not erased. `retirePlaceholder` stopped hard-deleting the starter when
            // the sync client landed: the row has to stay so its removal can be pushed, or a device
            // that already saw the placeholder would keep showing it for ever. Gone from the live
            // list — which `count()` above asserts — is the whole of what "replaced" means here.
            assertTrue(destinationDb.notebookDao().byId(starterId)?.deletedAt != null)
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
        val destinationDb = openDatabase("edited-install.db")
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
    fun importingADeletedNotebookRestoresItsStableId() = runBlocking {
        val notebookId = repository.createNotebook("Field Notes")
        val sectionId = repository.createSection(notebookId, "Observations")
        val pageId = repository.createPage(sectionId, "Heron")
        val bundle = ByteArrayOutputStream().also { transfers.exportNotebook(notebookId, it) }
            .toByteArray()

        now += 1
        repository.deleteNotebook(notebookId)
        val deletionTime = db.notebookDao().byId(notebookId)!!.updatedAt
        assertEquals(0, db.notebookDao().count())

        val result = transfers.importNotebook(ByteArrayInputStream(bundle))
        val restored = db.notebookDao().byId(notebookId)!!

        assertFalse(result.created)
        assertTrue(result.restored)
        assertEquals(sectionId, result.firstSectionId)
        assertEquals(pageId, result.firstPageId)
        assertEquals(1, db.notebookDao().count())
        assertEquals(null, restored.deletedAt)
        assertTrue("restore did not supersede the deletion timestamp", restored.updatedAt > deletionTime)
    }

    @Test
    fun importRestoresArchivedPageMetadataAndDocumentOverANewerLocalDeletion() = runBlocking {
        val notebookId = repository.createNotebook("Field Notes")
        val sectionId = repository.createSection(notebookId, "Observations")
        val pageId = repository.createPage(sectionId, "Archived page")
        repository.saveDoc(
            pageId,
            PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of("archived"))))),
        )
        val bundle = ByteArrayOutputStream().also { transfers.exportNotebook(notebookId, it) }
            .toByteArray()

        now += 1
        repository.renamePage(pageId, "Local page")
        repository.saveDoc(
            pageId,
            PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of("local"))))),
        )
        repository.deletePage(pageId)
        val deletionTime = db.pageDao().byId(pageId)!!.updatedAt

        val result = transfers.importNotebook(ByteArrayInputStream(bundle))
        val restoredPage = db.pageDao().byId(pageId)!!
        val restoredDoc = repository.loadDoc(pageId) as PageLoad.Loaded

        assertFalse(result.created)
        assertTrue(result.restored)
        assertEquals("Archived page", restoredPage.title)
        assertEquals(null, restoredPage.deletedAt)
        assertTrue("restored page did not supersede its deletion", restoredPage.updatedAt > deletionTime)
        assertEquals("archived", restoredDoc.doc.plainText())
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
        val destinationDb = openDatabase("destination.db")
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

            val destinationOnlyStroke = destinationRepository.addStroke(
                InkCodec.encode(Stroke(InkCodec.brushFor(pen), inputs), importedPageId, 1, pen, now),
            )
            val destinationOnlySection = destinationRepository.createSection(notebookId, "Local section")
            val pageInDestinationOnlySection = destinationRepository.createPage(
                destinationOnlySection,
                "Local section page",
            )
            assertEquals(2, destinationRepository.inkFor(importedPageId).size)

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
            assertTrue(
                "the authoritative import kept destination-only ink live",
                destinationDb.inkStrokeDao().byIds(listOf(destinationOnlyStroke.id)).single().deletedAt != null,
            )
            assertTrue(
                "the authoritative import kept a destination-only section live",
                destinationDb.sectionDao().byId(destinationOnlySection)!!.deletedAt != null,
            )
            assertTrue(
                "a page under a removed local-only section should no longer be reachable",
                destinationRepository.pagesInNotebook(notebookId)
                    .none { it.id == pageInDestinationOnlySection },
            )
            assertEquals(1, destinationRepository.inkFor(importedPageId).size)
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
                "the authoritative import kept a destination-only page live",
                destinationDb.pageDao().byId(destinationOnlyPage)!!.deletedAt != null,
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

    /**
     * A bundle carries the notebook and nothing about the account that exported it.
     *
     * The three `sync_*` tables and the triggers that feed them exist on every real install, and
     * `VACUUM INTO` copies all of it. Left in, three separate things break: `validateSchema`
     * compares the bundle's table set to `EXPECTED_COLUMNS` exactly and rejects the file, so no
     * build could import what this build wrote; the surviving triggers fire while the export
     * rewrites `attachments`, queueing rows into an outbox that is about to be shipped; and the
     * importer would inherit a cursor belonging to someone else's account and skip deltas it has
     * never seen. Asserted on the bundle rather than through a round trip because a round trip only
     * proves the importer accepts the file, not that the account data is gone from it.
     */
    @Test
    fun exportStripsTheSyncLayerFromTheBundle() = runBlocking {
        db.syncDao().putState(SyncStateEntity(accountId = "account-under-test"))
        val notebookId = repository.createNotebook("Connected")
        val sectionId = repository.createSection(notebookId, "Section")
        repository.createPage(sectionId, "Page")
        makeAttachment()
        // The triggers only fire for a connected database, so a silent no-op here would make the
        // rest of the test vacuous.
        assertTrue(db.syncDao().outbox(limit = 100).isNotEmpty())

        val bundle = ByteArrayOutputStream()
            .also { transfers.exportNotebook(notebookId, it) }
            .toByteArray()

        val extracted = File(root, "bundle.sqlite")
        ZipInputStream(ByteArrayInputStream(bundle)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "notebook.sqlite") extracted.writeBytes(zip.readBytes())
                zip.closeEntry()
            }
        }
        assertTrue(extracted.isFile)

        val objects = SQLiteDatabase.openDatabase(
            extracted.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { bundleDb ->
            bundleDb.rawQuery(
                "SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            }
        }

        assertEquals(emptyList<Pair<String, String>>(), objects.filter { it.first == "trigger" })
        assertEquals(emptyList<Pair<String, String>>(), objects.filter { it.first == "view" })
        assertEquals(
            emptyList<String>(),
            objects.filter { it.first == "table" }.map { it.second }
                .filter { it.startsWith("sync_") },
        )
        // The export edits its own snapshot: what this device still owes its server has to survive
        // having been asked for a file.
        assertTrue(db.syncDao().outbox(limit = 100).isNotEmpty())
        assertTrue(db.syncDao().state() != null)
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
