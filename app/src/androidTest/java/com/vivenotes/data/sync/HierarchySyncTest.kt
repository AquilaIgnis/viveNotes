package com.vivenotes.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.EraserMode
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkTextEntity
import com.vivenotes.data.db.InkTextStatus
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.LocalMetadataEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.JsonDocumentCodec
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HierarchySyncTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var server: InMemorySyncServer
    private lateinit var remoteInk: RemoteInkSignal
    private lateinit var pictures: TemporaryAttachmentBytes
    private lateinit var attachmentDirectory: File
    private lateinit var hierarchy: HierarchySync
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .allowMainThreadQueries()
            .build()
        repository = NotesRepository(db, clock = { now })
        server = InMemorySyncServer()
        remoteInk = RemoteInkSignal()
        attachmentDirectory = File(context.cacheDir, "sync-attachments-${UUID.randomUUID()}")
        pictures = TemporaryAttachmentBytes(attachmentDirectory)
        hierarchy = HierarchySync(db, server, remoteInk, AttachmentBlobSync(db, server, pictures))
    }

    @After
    fun tearDown() {
        db.close()
        attachmentDirectory.deleteRecursively()
    }

    @Test
    fun activatingAnOfflineTreePushesParentsFirstThenCommitsItsPullCursor() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        repository.createPage(sectionId, "Page")

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(4, result.summary.pushed)
        assertEquals(4, result.summary.pulled)
        assertEquals(
            listOf("notebook", "section", "page", "pageContent"),
            server.pushes.first().map(::kindOf),
        )
        assertTrue(db.syncDao().outbox(512).isEmpty())
        assertEquals(1L, db.syncDao().state()!!.cursor)
    }

    @Test
    fun pulledRowsStayLocalAndUnknownFieldsSurviveTheNextPush() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(
            notebookChange("n", "Remote", changedAt, extra = "future-field"),
            sectionChange("s", "n", "Remote section", changedAt),
            pageChange("p", "s", "Remote page", changedAt),
        )

        val first = hierarchy.run(account()) as SyncRunResult.Succeeded
        assertEquals(3, first.summary.pulled)
        assertEquals("Remote", db.notebookDao().byId("n")!!.name)
        assertTrue(db.syncDao().outbox(512).isEmpty())

        now = changedAt + 10_000
        repository.renameNotebook("n", "Local rename")
        val second = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(1, second.summary.pushed)
        val sent = server.pushes.last().single()
        assertEquals("future-field", sent.getValue("future").jsonPrimitive.content)
        assertEquals("Local rename", server.current("notebook", "n")!!.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun anUntouchedStarterIsDroppedRatherThanPushedWhenTheAccountAlreadyHasATree() = runBlocking {
        // A clean install as the user finds it: one seeded notebook, marked replaceable because
        // nothing in it has been touched. Marked last, since creating rows clears the marker.
        val starterId = repository.createNotebook("My Notebook")
        repository.createSection(starterId, "Getting Started")
        db.localMetadataDao().put(
            LocalMetadataEntity(NotesRepository.REPLACEABLE_STARTER_KEY, starterId),
        )
        server.seed(notebookChange("n", "The real one", System.currentTimeMillis()))

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        // Nothing was uploaded: the account does not grow a second "My Notebook" per device.
        assertEquals(0, result.summary.pushed)
        assertTrue(server.pushes.isEmpty())
        assertNull(db.notebookDao().byId(starterId))
        assertTrue(db.sectionDao().allInNotebook(starterId).isEmpty())
        assertEquals("The real one", db.notebookDao().byId("n")!!.name)
        // And the queue it was already sitting in went with it, or every later push would fail.
        assertTrue(db.syncDao().outbox(512).isEmpty())
    }

    @Test
    fun anUntouchedStarterIsStillPushedToAnEmptyAccount() = runBlocking {
        val starterId = repository.createNotebook("My Notebook")
        db.localMetadataDao().put(
            LocalMetadataEntity(NotesRepository.REPLACEABLE_STARTER_KEY, starterId),
        )

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        // The first device to connect still furnishes the account, starter and all.
        assertEquals(1, result.summary.pushed)
        assertNotNull(db.notebookDao().byId(starterId))
        assertNotNull(server.current("notebook", starterId))
    }

    @Test
    fun aChildIsHeldBackUntilThePageCarryingItsParentArrives() = runBlocking {
        val changedAt = System.currentTimeMillis()
        // Seeded child-first and then read one row at a time, so the section and the page each land
        // in a page of the delta before the notebook they hang from. Sorting inside a page cannot
        // fix that; only holding them back can.
        server.seed(sectionChange("s", "n", "Remote section", changedAt))
        server.seed(pageChange("p", "s", "Remote page", changedAt))
        server.seed(notebookChange("n", "Renamed last", changedAt + 1_000))
        server.pageLimit = 1

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(3, result.summary.pulled)
        assertEquals("Renamed last", db.notebookDao().byId("n")!!.name)
        assertEquals("n", db.sectionDao().byId("s")!!.notebookId)
        assertEquals("s", db.pageDao().byId("p")!!.sectionId)
        // Committed only once nothing was still waiting, so a crash mid-delta re-reads the held rows
        // rather than starting above them.
        assertEquals(3L, db.syncDao().state()!!.cursor)
    }

    @Test
    fun aChildWhoseParentNeverArrivesFailsInsteadOfSkippingIt() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(sectionChange("s", "notebook-that-never-comes", "Orphan", changedAt))

        val result = hierarchy.run(account())

        assertTrue(result is SyncRunResult.Failed)
        // The cursor stays put: the next run asks for the same delta instead of advancing past a row
        // this device could not place.
        assertEquals(0L, db.syncDao().state()!!.cursor)
    }

    @Test
    fun anInstallationIsNotCaughtUpUntilItHasPulledThatAccount() = runBlocking {
        server.seed(notebookChange("n", "Remote", System.currentTimeMillis()))

        // Before the first pull an empty local tree proves nothing, so a joining device must not
        // read it as "this account is empty, seed a starter notebook into it".
        assertFalse(hierarchy.hasCaughtUp("account"))

        hierarchy.run(account())
        assertTrue(hierarchy.hasCaughtUp("account"))

        // The marker names an account rather than recording that syncing has happened at all.
        assertFalse(hierarchy.hasCaughtUp("a-different-account"))

        hierarchy.deactivate("account")
        assertFalse(hierarchy.hasCaughtUp("account"))
    }

    @Test
    fun aParentEditedAfterItsChildrenIsStillAppliedBeforeThem() = runBlocking {
        val changedAt = System.currentTimeMillis()
        // The children, at the sequence value that created them.
        server.seed(
            sectionChange("s", "n", "Remote section", changedAt),
            pageChange("p", "s", "Remote page", changedAt),
        )
        // Their notebook, renamed afterwards — so it carries a *higher* change_seq than its own
        // children and reaches this device behind them. Applying the delta in stream order puts a
        // section into Room before the notebook its foreign key names, which SQLite refuses; the
        // transaction rolls back, the cursor never commits, and the device re-pulls for ever.
        server.seed(notebookChange("n", "Renamed later", changedAt + 1_000))

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(3, result.summary.pulled)
        assertEquals("Renamed later", db.notebookDao().byId("n")!!.name)
        assertEquals("n", db.sectionDao().byId("s")!!.notebookId)
        assertEquals("s", db.pageDao().byId("p")!!.sectionId)
        assertEquals(2L, db.syncDao().state()!!.cursor)
    }

    @Test
    fun anEditCommittedWhileAPushIsInFlightRemainsQueuedAndIsSentNext() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Before")
        server.beforeFirstPush = {
            now += 10_000
            repository.renamePage(pageId, "After")
        }

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(5, result.summary.pushed)
        val pageTitles = server.pushes.flatten()
            .filter { kindOf(it) == "page" }
            .map { it.getValue("title").jsonPrimitive.content }
        assertEquals(listOf("Before", "After"), pageTitles)
        assertEquals("After", db.pageDao().byId(pageId)!!.title)
        assertTrue(db.syncDao().outbox(512).isEmpty())
    }

    @Test
    fun aServerVersionWinsEvenWhenTheDirtyLocalWallClockIsLater() = runBlocking {
        val localNewer = System.currentTimeMillis() + 60_000
        now = localNewer
        val localWinsId = repository.createNotebook("Local wins")
        val serverWinsId = repository.createNotebook("Local loses")
        server.seed(
            notebookChange(localWinsId, "Remote old", localNewer - 10_000),
            notebookChange(serverWinsId, "Remote new", localNewer + 10_000),
        )

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals("Remote old", db.notebookDao().byId(localWinsId)!!.name)
        assertEquals("Remote new", db.notebookDao().byId(serverWinsId)!!.name)
        assertTrue(server.pushes.isEmpty())
        assertEquals(0, result.summary.pushed)
    }

    @Test
    fun documentBodiesRoundTripAsOpaqueBytesAfterTheirPage() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        val document = """{"schemaVersion":1,"outlines":[{"text":"opaque ✓"}]}"""
        db.pageContentDao().upsert(PageContentEntity(pageId, document, now, "json/1"))

        hierarchy.run(account())

        val pushed = server.pushes.flatten().single { kindOf(it) == "pageContent" }
        assertEquals(pageId, pushed.getValue("pageId").jsonPrimitive.content)
        assertEquals(document, decodeDocument(pushed))
        assertEquals("none/1", pushed.getValue("enc").jsonPrimitive.content)

        val peer = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            NotesDatabase::class.java,
        ).addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK).allowMainThreadQueries().build()
        try {
            val peerBytes = TemporaryAttachmentBytes(File(attachmentDirectory, "peer"))
            val peerSync = HierarchySync(
                peer,
                server,
                RemoteInkSignal(),
                AttachmentBlobSync(peer, server, peerBytes),
            )
            val peerResult = peerSync.run(account()) as SyncRunResult.Succeeded
            assertEquals(4, peerResult.summary.pulled)
            assertEquals(document, peer.pageContentDao().byId(pageId)!!.docJson)
        } finally {
            peer.close()
        }
    }

    @Test
    fun validLargeDocumentsAreSplitBelowTheRequestByteCap() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val firstPage = repository.createPage(sectionId, "First")
        val secondPage = repository.createPage(sectionId, "Second")
        val document = "x".repeat(1_600_000)
        db.pageContentDao().upsert(PageContentEntity(firstPage, document, now, "json/1"))
        db.pageContentDao().upsert(PageContentEntity(secondPage, document, now, "json/1"))

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(6, result.summary.pushed)
        assertEquals(2, server.pushes.size)
        server.pushes.forEach { changes ->
            val encoded = JsonObject(
                mapOf(
                    "batchId" to JsonPrimitive("00000000-0000-0000-0000-000000000000"),
                    "changes" to JsonArray(changes),
                ),
            ).toString().encodeToByteArray()
            assertTrue(encoded.size <= MAX_SYNC_PUSH_BYTES)
        }
    }

    @Test
    fun aLaterRemoteDocumentWinsAWholeBodyVersionConflict() = runBlocking {
        now = System.currentTimeMillis()
        val pageId = createAndSyncPage("base")
        now += 10_000
        db.pageContentDao().upsert(PageContentEntity(pageId, "local", now, "json/1"))
        server.beforeFirstPush = {
            server.seed(pageContentChange(pageId, "remote", now + 10_000))
        }

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(1, result.summary.conflictsResolved)
        assertEquals(0, result.summary.pushed)
        assertEquals("remote", db.pageContentDao().byId(pageId)!!.docJson)
    }

    @Test
    fun aStaleLaterAutosaveCannotResurrectARemotelyDeletedTextBox() = runBlocking {
        now = System.currentTimeMillis()
        val presentDocument = """{"outlines":[{"id":"box","t":"text"}]}"""
        val deletedDocument = """{"outlines":[]}"""
        val pageId = createAndSyncPage(presentDocument)
        val remoteTime = now + 10_000
        now += 20_000
        // This simulates the other device autosaving the still-visible box later by wall clock,
        // while its base remains the older server version that still contained the box.
        db.pageContentDao().upsert(PageContentEntity(pageId, presentDocument, now, "json/1"))
        server.beforeFirstPush = {
            // The delete reaches the server first and becomes the causally newer version.
            server.seed(pageContentChange(pageId, deletedDocument, remoteTime))
        }

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(1, result.summary.conflictsResolved)
        assertEquals(0, result.summary.pushed)
        assertEquals(deletedDocument, db.pageContentDao().byId(pageId)!!.docJson)
        assertEquals(deletedDocument, decodeDocument(server.current("pageContent", pageId)!!))
    }

    private suspend fun createAndSyncPage(document: String): String {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        db.pageContentDao().upsert(PageContentEntity(pageId, document, now, "json/1"))
        hierarchy.run(account())
        server.pushes.clear()
        return pageId
    }

    @Test
    fun aDrawnPageUploadsItsStrokesAndOperationsWithTheirTargets() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        val stroke = repository.addStroke(strokeRow("stroke-1", pageId, seq = 0))
        repository.addPartialErase(eraseRow("erase-1", pageId), listOf(stroke.id))
        repository.addInkMove(moveRow("move-1", pageId), listOf(stroke.id))

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        // Parents first, and the three ink kinds after the page they hang from.
        assertEquals(
            listOf("notebook", "section", "page", "pageContent", "inkStroke", "inkErase", "inkMove"),
            server.pushes.first().map(::kindOf),
        )
        assertEquals(7, result.summary.pushed)
        assertTrue(db.syncDao().outbox(512).isEmpty())

        val sentStroke = server.current("inkStroke", stroke.id)!!
        assertEquals(pageId, sentStroke.getValue("pageId").jsonPrimitive.content)
        // `drawOrder`, never `seq`: `seq` is the envelope's account sequence, and a stroke that sent
        // its draw order under that name would have it silently dropped.
        assertEquals(0, sentStroke.getValue("drawOrder").jsonPrimitive.content.toInt())
        assertEquals("pressure-pen", sentStroke.getValue("brushFamily").jsonPrimitive.content)
        assertArrayEquals(
            byteArrayOf(7, 8, 9),
            Base64.getDecoder().decode(sentStroke.getValue("points").jsonPrimitive.content),
        )

        // The mask without its targets would erase ink drawn later when it is replayed, so the pair
        // has to travel as one entity.
        val sentErase = server.current("inkErase", "erase-1")!!
        assertEquals(
            listOf(stroke.id),
            (sentErase.getValue("targetIds") as JsonArray).map { it.jsonPrimitive.content },
        )
        val sentMove = server.current("inkMove", "move-1")!!
        assertEquals(
            listOf(stroke.id),
            (sentMove.getValue("targetIds") as JsonArray).map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun pulledInkNamesItsPageSoAnOpenCanvasCanAbsorbIt() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(
            notebookChange("n", "Remote", changedAt),
            sectionChange("s", "n", "Remote section", changedAt),
            pageChange("p", "s", "Remote page", changedAt),
            pageChange("q", "s", "Another remote page", changedAt),
            inkStrokeChange("stroke-1", "p", drawOrder = 4, updatedAt = changedAt),
            inkEraseChange("erase-1", "p", listOf("stroke-1"), changedAt),
        )

        hierarchy.run(account()) as SyncRunResult.Succeeded

        // Once per row rather than once per page would be a canvas rebuilt twice for one delta; the
        // generation is what the ViewModel compares against, and the page set is what it filters by.
        assertEquals(mapOf("p" to 1L), remoteInk.pages.value)
    }

    @Test
    fun pulledInkDropsTheHandwritingItsPageWasAlreadyReadAs() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(
            notebookChange("n", "Remote", changedAt),
            sectionChange("s", "n", "Remote section", changedAt),
            pageChange("p", "s", "Remote page", changedAt),
        )
        hierarchy.run(account()) as SyncRunResult.Succeeded
        db.inkTextDao().upsert(
            InkTextEntity(
                pageId = "p",
                regionsJson = "[]",
                regionCount = 1,
                confidence = 1f,
                layoutHash = "",
                engine = "test",
                status = InkTextStatus.Read,
                durationMs = 1,
                updatedAt = changedAt,
            ),
        )

        server.seed(inkStrokeChange("stroke-1", "p", drawOrder = 0, updatedAt = changedAt + 1))
        hierarchy.run(account()) as SyncRunResult.Succeeded

        // The cache is keyed by a generation every *local* ink write bumps through the repository.
        // Rows written here go to the DAOs directly, so without this the search would answer with
        // the handwriting the page held before another device drew on it.
        assertTrue(db.inkTextDao().byPageIds(listOf("p")).isEmpty())
        assertTrue(db.inkTextDao().generation("p") > 0)
    }

    @Test
    fun pulledInkLandsOnThePageIncludingTargetsThisDeviceHasNeverSeen() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(
            notebookChange("n", "Remote", changedAt),
            sectionChange("s", "n", "Remote section", changedAt),
            pageChange("p", "s", "Remote page", changedAt),
            inkStrokeChange("stroke-1", "p", drawOrder = 4, updatedAt = changedAt),
            // Names a stroke this device does not have and never will — the other device purged it
            // seven days after erasing it. Inert, not a reason to refuse the operation.
            inkEraseChange("erase-1", "p", listOf("stroke-1", "stroke-gone"), changedAt),
            inkMoveChange("move-1", "p", listOf("stroke-1"), changedAt),
        )

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(6, result.summary.pulled)
        val stored = db.inkStrokeDao().byPage("p").single()
        assertEquals(4, stored.seq)
        assertEquals("pressure-pen", stored.brushFamily)
        assertArrayEquals(byteArrayOf(7, 8, 9), stored.points)
        assertEquals(2.5f, stored.sizeDp, 0f)
        assertNull(stored.colorFollowsTheme)

        val erase = db.inkEraseDao().byPage("p").single()
        assertEquals(
            listOf("stroke-1", "stroke-gone"),
            erase.targets.map { it.strokeId }.sorted(),
        )
        assertEquals(listOf("stroke-1"), db.inkMoveDao().byPage("p").single().targets.map { it.strokeId })
        // The cursor moved, which is the part a foreign key on those targets would have broken.
        assertEquals(1L, db.syncDao().state()!!.cursor)
    }

    @Test
    fun anErasedStrokeAndItsUndoBothCross() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(
            notebookChange("n", "Remote", changedAt),
            sectionChange("s", "n", "Remote section", changedAt),
            pageChange("p", "s", "Remote page", changedAt),
            inkStrokeChange("stroke-1", "p", drawOrder = 0, updatedAt = changedAt),
        )
        hierarchy.run(account())
        assertEquals(1, db.inkStrokeDao().byPage("p").size)

        // Erasing is a tombstone on the row, not a delete: the row has to survive to carry the undo.
        server.seed(
            inkStrokeChange("stroke-1", "p", drawOrder = 0, updatedAt = changedAt + 1, deletedAt = changedAt + 1),
        )
        hierarchy.run(account())
        assertTrue(db.inkStrokeDao().byPage("p").isEmpty())
        assertNotNull(db.inkStrokeDao().byIds(listOf("stroke-1")).single().deletedAt)

        server.seed(inkStrokeChange("stroke-1", "p", drawOrder = 0, updatedAt = changedAt + 2))
        hierarchy.run(account())
        assertEquals(1, db.inkStrokeDao().byPage("p").size)
        assertNull(db.inkStrokeDao().byIds(listOf("stroke-1")).single().deletedAt)
    }

    @Test
    fun theStoredServerCopyOfAStrokeDoesNotHoldItsPointsTwice() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(
            notebookChange("n", "Remote", changedAt),
            sectionChange("s", "n", "Remote section", changedAt),
            pageChange("p", "s", "Remote page", changedAt),
            inkStrokeChange("stroke-1", "p", drawOrder = 0, updatedAt = changedAt, extra = "future"),
        )
        hierarchy.run(account())

        // Ink is the largest table in the app; keeping the server's copy of every stroke as base64
        // beside the row itself would store the corpus twice at a third again the size.
        val state = db.syncDao().entityState("inkStroke", "stroke-1")!!
        assertFalse(state.serverJson.contains("points"))
        assertTrue(state.serverJson.contains("future"))

        // The push still carries points, re-attached from the row, and still preserves the field a
        // newer client sent that this build has no column for.
        db.inkStrokeDao().setColor("stroke-1", 0x00FF00, followsTheme = false)
        hierarchy.run(account())
        val sent = server.pushes.last().single()
        assertArrayEquals(
            byteArrayOf(7, 8, 9),
            Base64.getDecoder().decode(sent.getValue("points").jsonPrimitive.content),
        )
        assertEquals("future", sent.getValue("future").jsonPrimitive.content)
        assertEquals(0x00FF00, sent.getValue("colorArgb").jsonPrimitive.content.toInt())
    }

    @Test
    fun inkWaitsForThePageThatArrivesInALaterPullPage() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(inkStrokeChange("stroke-1", "p", drawOrder = 0, updatedAt = changedAt))
        server.seed(notebookChange("n", "Remote", changedAt))
        server.seed(sectionChange("s", "n", "Remote section", changedAt))
        server.seed(pageChange("p", "s", "Remote page", changedAt))
        server.pageLimit = 1

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(4, result.summary.pulled)
        assertEquals(1, db.inkStrokeDao().byPage("p").size)
        assertEquals(4L, db.syncDao().state()!!.cursor)
    }

    @Test
    fun aKindThisBuildCannotStoreParksTheCursorRatherThanSkippingIt() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(notebookChange("n", "Remote", changedAt))
        // A kind from a build newer than this one. It was `attachment` until S5 landed, which is
        // the point: every kind named here eventually becomes one this build stores, and the case
        // has to keep testing the *unknown* branch rather than quietly testing a known one.
        server.seed(
            JsonObject(
                linkedMapOf(
                    "kind" to JsonPrimitive("pageRevision"),
                    "id" to JsonPrimitive("revision-1"),
                    "deletedAt" to JsonNull,
                    "updatedAt" to JsonPrimitive(changedAt),
                ),
            ),
        )

        val result = hierarchy.run(account())

        // Skipping the row and committing the cursor is silent, permanent loss: the cursor promises
        // everything below it has been applied, so the next pull starts above what was dropped. A
        // tablet on the build before ink did exactly that with 67 strokes on 2026-08-17.
        assertEquals(SyncRunResult.Failed(PermanentSyncFailure.UnsupportedKind), result)
        assertEquals(0L, db.syncDao().state()!!.cursor)
    }

    // --- attachments (S5) -----------------------------------------------------------------------

    @Test
    fun aPicturesBytesReachTheServerBeforeTheChangeThatNamesThem() = runBlocking {
        val bytes = "a photograph".toByteArray()
        val digest = sha256(bytes)
        pictures.write(digest, bytes)
        val pageId = seedPageWithPicture(digest)

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        // The fake server refuses a `pageContent` or an `attachment` naming bytes it does not hold,
        // exactly as viveCServer does. Everything applying is therefore the assertion that the
        // upload happened first — there is no ordering to check separately.
        assertEquals(listOf("HEAD $digest", "PUT $digest"), server.blobCalls)
        assertArrayEquals(bytes, server.blobs.getValue(digest))
        assertEquals(1, result.summary.pictures)

        val pushed = server.pushes.flatten()
        val body = pushed.single { kindOf(it) == "pageContent" && idOf(it) == pageId }
        assertEquals(
            listOf(digest),
            (body.getValue("blobRefs") as JsonArray).map { it.jsonPrimitive.content },
        )
        val attachment = pushed.single { kindOf(it) == "attachment" }
        assertEquals(digest, idOf(attachment))
        assertEquals("image/webp", attachment.getValue("mimeType").jsonPrimitive.content)
        assertEquals(bytes.size.toLong(), attachment.getValue("byteCount").jsonPrimitive.long)
        // refCount is per-device reachability and never leaves it — SD7.
        assertNull(attachment["refCount"])
        assertTrue(db.syncDao().outbox(512).isEmpty())
    }

    @Test
    fun aSecondRunUploadsNothingBecauseTheAcceptedRowIsProofTheServerHasTheBytes() = runBlocking {
        val bytes = "a photograph".toByteArray()
        val digest = sha256(bytes)
        pictures.write(digest, bytes)
        val pageId = seedPageWithPicture(digest)
        hierarchy.run(account())
        server.blobCalls.clear()

        now += 1_000
        repository.saveDoc(pageId, docWithPicture(digest, x = 40f))
        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        // The picture moved on the page, so the body is pushed again with the same reference — and
        // the byte route is not touched at all. A `HEAD` per picture per edit would be one request
        // per 60 s tick on a notebook full of photographs.
        assertEquals(
            listOf(digest),
            (server.current("pageContent", pageId)!!.getValue("blobRefs") as JsonArray)
                .map { it.jsonPrimitive.content },
        )
        assertEquals(0, result.summary.pictures)
        assertEquals(emptyList<String>(), server.blobCalls)
    }

    @Test
    fun aServerThatLostItsBytesIsRepairedByTheRejectionItSends() = runBlocking {
        val bytes = "a photograph".toByteArray()
        val digest = sha256(bytes)
        pictures.write(digest, bytes)
        val pageId = seedPageWithPicture(digest)
        hierarchy.run(account())

        // The database survived, the blob volume did not: the state row still says the server holds
        // these bytes, so nothing this device knows would make it upload them again.
        server.lostBlobs += digest
        server.blobCalls.clear()
        now += 1_000
        repository.saveDoc(pageId, docWithPicture(digest, x = 80f))

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        // No HEAD: the rejection already said what a HEAD would have said.
        assertEquals(listOf("PUT $digest"), server.blobCalls)
        assertArrayEquals(bytes, server.blobs.getValue(digest))
        assertEquals(1, result.summary.pictures)
        // And the edit it rejected went on to be accepted in the same run rather than being left
        // for the next one.
        assertEquals(
            JsonDocumentCodec.encodeToString(docWithPicture(digest, x = 80f)),
            decodeDocument(server.current("pageContent", pageId)!!),
        )
        assertTrue(db.syncDao().outbox(512).isEmpty())
    }

    @Test
    fun aPictureThisDeviceCannotSupplyIsDroppedFromTheReferencesRatherThanWedgingEveryPush() =
        runBlocking {
            // A broken picture: the row and the outline are here, the file is not. Before S5 this
            // could not happen; a download interrupted halfway is how it happens now.
            val bytes = "a photograph".toByteArray()
            val digest = sha256(bytes)
            val pageId = seedPageWithPicture(digest)

            val result = hierarchy.run(account()) as SyncRunResult.Succeeded

            // The page still syncs, without claiming the server should keep bytes nobody has.
            val body = server.pushes.flatten()
                .last { kindOf(it) == "pageContent" && idOf(it) == pageId }
            assertEquals(0, (body.getValue("blobRefs") as JsonArray).size)
            assertEquals(0, server.blobs.size)
            // And the metadata row is not left queued: there is no version of it the server would
            // ever take, so every later push would end on the same rejection.
            assertTrue(db.syncDao().outbox(512).none { it.kind == "attachment" })
            assertNull(server.current("attachment", digest))
            assertTrue(result.summary.pushed > 0)
        }

    @Test
    fun aPulledPictureIsFetchedAndCountedAgainstTheDocumentThatShowsIt() = runBlocking {
        val bytes = "another device's photograph".toByteArray()
        val digest = sha256(bytes)
        val changedAt = System.currentTimeMillis()
        server.blobs[digest] = bytes
        server.seed(
            notebookChange("n", "Remote", changedAt),
            sectionChange("s", "n", "Remote section", changedAt),
            pageChange("p", "s", "Remote page", changedAt),
            pageContentChange("p", JsonDocumentCodec.encodeToString(docWithPicture(digest)), changedAt),
            attachmentChange(digest, bytes.size.toLong(), changedAt),
        )

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(listOf("GET $digest"), server.blobCalls)
        assertArrayEquals(bytes, pictures.fileFor(digest).readBytes())
        assertEquals(1, result.summary.pictures)
        val row = db.attachmentDao().byId(digest)!!
        assertEquals(bytes.size.toLong(), row.byteCount)
        // The pulled document places the picture once, and this device's own reachability count
        // follows it — nothing in the editor ran to do that.
        assertEquals(1, row.refCount)
    }

    @Test
    fun aPulledAttachmentRowDoesNotOverwriteThisDevicesReferenceCount() = runBlocking {
        val bytes = "a shared photograph".toByteArray()
        val digest = sha256(bytes)
        pictures.write(digest, bytes)
        seedPageWithPicture(digest)
        hierarchy.run(account())
        val local = db.attachmentDao().byId(digest)!!.refCount

        // The same picture comes back from the server as another device's row.
        server.seed(attachmentChange(digest, bytes.size.toLong(), System.currentTimeMillis()))
        hierarchy.run(account())

        // `refCount` is not in the protocol, and a pulled row carrying a zero must not be allowed to
        // read as "nothing here points at this picture" — that is what a future sweep would act on.
        assertEquals(local, db.attachmentDao().byId(digest)!!.refCount)
    }

    @Test
    fun anAttachmentIdThatIsNotADigestIsRefusedBeforeItCanBecomeAFileName() = runBlocking {
        val changedAt = System.currentTimeMillis()
        server.seed(attachmentChange("../../etc/passwd", 12L, changedAt))

        val result = hierarchy.run(account())

        // The id is a path segment on the wire and a file name in `filesDir/attachments` here. The
        // server pins the pattern; this build does not take its word for it.
        assertEquals(
            SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse),
            result,
        )
        assertEquals(0L, db.syncDao().state()!!.cursor)
        assertEquals(emptyList<String>(), server.blobCalls)
    }

    // --- the closed-notebook shelf, and moving one to the cloud -------------------------------
    //
    // `memory/closedNotebooksPlan.md`.

    @Test
    fun closingANotebookTravelsToTheServerAndBackAsAnOrdinaryField() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        repository.createSection(notebookId, "Section")
        hierarchy.run(account())

        repository.closeNotebook(notebookId)
        hierarchy.run(account())

        // Both keys are written on every push, and `closedAt` carries the shelf.
        val pushed = server.current("notebook", notebookId)!!
        assertEquals(now, pushed.getValue("closedAt").jsonPrimitive.long)
        assertTrue(pushed.getValue("cloudOnlyAt") is JsonNull)

        // And a device pulling it applies it, so the notebook leaves that rail too.
        db.notebookDao().setClosed(notebookId, null, now)
        db.syncDao().deleteOutbox("notebook", notebookId)
        db.syncDao().deleteEntityState("notebook", notebookId)
        db.syncDao().setCursor(0)
        hierarchy.run(account())
        assertEquals(now, db.notebookDao().byId(notebookId)!!.closedAt)
    }

    /**
     * Reopening has to travel as well, and the only way it can is by the key being written when it
     * is null. A push that omitted it would leave the retained `serverJson`'s old value standing and
     * the notebook would stay closed on every other device for ever.
     */
    @Test
    fun reopeningANotebookClearsTheFieldOnTheServerRatherThanOmittingIt() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        repository.closeNotebook(notebookId)
        hierarchy.run(account())
        assertEquals(now, server.current("notebook", notebookId)!!.getValue("closedAt").jsonPrimitive.long)

        repository.reopenNotebook(notebookId)
        hierarchy.run(account())

        val reopened = server.current("notebook", notebookId)!!
        assertTrue("closedAt must be cleared, not dropped", reopened.getValue("closedAt") is JsonNull)
    }

    @Test
    fun movingANotebookToTheCloudLeavesTheIndexAndTakesEverythingElse() = runBlocking {
        val bytes = "a photograph".toByteArray()
        val digest = sha256(bytes)
        pictures.write(digest, bytes)
        val pageId = seedPageWithPicture(digest)
        val notebookId = db.sectionDao().byId(db.pageDao().byId(pageId)!!.sectionId)!!.notebookId
        db.inkStrokeDao().upsert(listOf(strokeRow("stroke-1", pageId, seq = 0)))
        repository.closeNotebook(notebookId)
        hierarchy.run(account())
        assertTrue("everything must be on the server first", db.syncDao().outbox(512).isEmpty())

        assertEquals(CloudArchiveResult.Moved, hierarchy.evictToCloud(notebookId))

        // The index survives: a `sections` or `pages` row deleted here is a parent a pulled change
        // cannot find, and a pull that cannot place a row never advances its cursor again.
        assertNotNull(db.notebookDao().byId(notebookId))
        assertEquals(1, db.sectionDao().allInNotebook(notebookId).size)
        assertEquals(1, db.pageDao().allInNotebook(notebookId).size)

        // The payload does not.
        assertNull(db.pageContentDao().byId(pageId))
        assertEquals(0, db.inkStrokeDao().countForPages(listOf(pageId)))
        assertNull(db.attachmentDao().byId(digest))
        assertFalse(pictures.fileFor(digest).exists())

        // And the notebook says where its bytes went, in a row that is queued for the account.
        val row = db.notebookDao().byId(notebookId)!!
        assertNotNull(row.cloudOnlyAt)
        assertNotNull(row.closedAt)
        assertTrue(db.syncDao().outbox(512).any { it.kind == "notebook" && it.entityId == notebookId })
    }

    /**
     * The whole safety argument, asserted directly. An empty outbox is the server's own statement
     * that it holds every byte this device could offer, and without one there is nothing entitling
     * this to delete a local copy.
     */
    @Test
    fun aNotebookWithUnsentChangesIsNotEvicted() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        hierarchy.run(account())
        repository.saveDoc(pageId, PageDoc(outlines = emptyList()))

        val result = hierarchy.evictToCloud(notebookId)

        assertTrue(result is CloudArchiveResult.NotUploaded)
        assertNotNull("nothing may be deleted", db.pageContentDao().byId(pageId))
        assertNull(db.notebookDao().byId(notebookId)!!.cloudOnlyAt)
    }

    /**
     * A picture two notebooks show survives the eviction of one of them.
     *
     * The reachable set is computed from the documents rather than from `refCount`, because a pulled
     * row is documented as arriving at zero — trusting the count here would delete the file behind a
     * picture the other notebook is still drawing.
     */
    @Test
    fun aPictureAnotherNotebookStillShowsIsNotEvictedWithThisOne() = runBlocking {
        val bytes = "a shared photograph".toByteArray()
        val digest = sha256(bytes)
        pictures.write(digest, bytes)
        val leavingPage = seedPageWithPicture(digest)
        val leavingNotebook = db.sectionDao()
            .byId(db.pageDao().byId(leavingPage)!!.sectionId)!!.notebookId

        val stayingNotebook = repository.createNotebook("Staying")
        val stayingSection = repository.createSection(stayingNotebook, "Section")
        val stayingPage = repository.createPage(stayingSection, "Page")
        repository.saveDoc(stayingPage, docWithPicture(digest))

        repository.closeNotebook(leavingNotebook)
        hierarchy.run(account())

        assertEquals(CloudArchiveResult.Moved, hierarchy.evictToCloud(leavingNotebook))

        assertNotNull("the row must survive", db.attachmentDao().byId(digest))
        assertTrue("the bytes must survive", pictures.fileFor(digest).exists())
        assertNotNull("the other notebook is untouched", db.pageContentDao().byId(stayingPage))
    }

    @Test
    fun bringingANotebookBackDownloadsItsBodiesInkAndPicturesWithoutMovingTheCursor() = runBlocking {
        val bytes = "a photograph".toByteArray()
        val digest = sha256(bytes)
        pictures.write(digest, bytes)
        val pageId = seedPageWithPicture(digest)
        val notebookId = db.sectionDao().byId(db.pageDao().byId(pageId)!!.sectionId)!!.notebookId
        db.inkStrokeDao().upsert(listOf(strokeRow("stroke-1", pageId, seq = 0)))
        repository.closeNotebook(notebookId)
        hierarchy.run(account())
        val cursorBeforeEviction = db.syncDao().state()!!.cursor
        hierarchy.evictToCloud(notebookId)

        // Another device writes something unrelated while this one is fetching its notebook back.
        // The replay reads *past* that row — it starts at zero and ends at the server's cursor —
        // and committing what it read would be a promise that a row nobody applied had been.
        server.seed(notebookChange("elsewhere", "Another device", now + 1))

        assertEquals(CloudArchiveResult.BroughtBack, hierarchy.restoreFromCloud(account(), notebookId))

        assertNotNull(db.pageContentDao().byId(pageId))
        assertEquals(1, db.inkStrokeDao().countForPages(listOf(pageId)))
        assertNotNull(db.attachmentDao().byId(digest))
        assertArrayEquals(bytes, pictures.fileFor(digest).readBytes())

        // Back on the rail, and no longer claiming its bytes are elsewhere.
        val row = db.notebookDao().byId(notebookId)!!
        assertNull(row.cloudOnlyAt)
        assertNull(row.closedAt)

        // The replay is not a pull, and this is the loss that proves it: the concurrent notebook was
        // read by the replay and deliberately not applied, so the cursor has to stay below it or the
        // next ordinary run starts above it and it is gone for good.
        assertEquals(cursorBeforeEviction, db.syncDao().state()!!.cursor)
        assertNull(db.notebookDao().byId("elsewhere"))

        hierarchy.run(account())
        assertNotNull("the row the replay skipped must still be pullable", db.notebookDao().byId("elsewhere"))
    }

    /**
     * The replay writes under `applyingRemote`, or this device queues a push of the entire notebook
     * it has just finished downloading — and the server would take every row as a fresh version.
     */
    @Test
    fun bringingANotebookBackQueuesNothingForTheServer() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        repository.closeNotebook(notebookId)
        hierarchy.run(account())
        hierarchy.evictToCloud(notebookId)
        // Only the shelf columns are queued by the eviction itself; send them and start clean.
        hierarchy.run(account())
        assertTrue(db.syncDao().outbox(512).isEmpty())

        hierarchy.restoreFromCloud(account(), notebookId)

        assertNotNull(db.pageContentDao().byId(pageId))
        assertEquals(
            "only the notebook row, carrying the cleared columns",
            listOf("notebook"),
            db.syncDao().outbox(512).map { it.kind },
        )
    }

    /**
     * The other half of an account-wide move: a device that pulls the flag evicts its own copy.
     *
     * After the push and never after the pull — a device offline when the move happened may hold an
     * edit nobody else has, and the empty-outbox check is what stops this taking it away. The next
     * test is that half.
     */
    @Test
    fun aDeviceThatPullsTheCloudOnlyFlagEvictsItsOwnCopy() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        hierarchy.run(account())

        // Another device moved it: the notebook row comes back with both columns set.
        val moved = server.current("notebook", notebookId)!!.toMutableMap().apply {
            this["closedAt"] = JsonPrimitive(now)
            this["cloudOnlyAt"] = JsonPrimitive(now)
            this["updatedAt"] = JsonPrimitive(now + 1)
        }.let(::JsonObject)
        server.seed(moved)

        hierarchy.run(account())

        assertNull("the body has to go", db.pageContentDao().byId(pageId))
        assertNotNull("the index has to stay", db.pageDao().byId(pageId))
        assertNotNull(db.sectionDao().byId(sectionId))

        // And it says nothing back. The flag arrived from the server, so restamping it with this
        // device's clock would push a notebook row carrying no news — once per device that enforces.
        assertEquals(now, db.notebookDao().byId(notebookId)!!.cloudOnlyAt)
        assertTrue(db.syncDao().outbox(512).isEmpty())
    }

    @Test
    fun anUnsentEditUnderACloudOnlyNotebookIsPushedRatherThanDestroyed() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        hierarchy.run(account())

        // This device was offline: it has an edit nobody has seen, and the flag is waiting for it.
        repository.saveDoc(pageId, PageDoc(outlines = listOf(Outline.Text.empty())))
        val moved = server.current("notebook", notebookId)!!.toMutableMap().apply {
            this["closedAt"] = JsonPrimitive(now)
            this["cloudOnlyAt"] = JsonPrimitive(now)
            this["updatedAt"] = JsonPrimitive(now + 1)
        }.let(::JsonObject)
        server.seed(moved)

        hierarchy.run(account())

        // The edit reached the server before anything local was deleted, which is the whole reason
        // the enforcement runs after the push phase.
        val body = server.current("pageContent", pageId)
        assertNotNull("the unsent edit must have gone up", body)
        assertNull("and only then may the local copy go", db.pageContentDao().byId(pageId))
    }

    /**
     * A page showing one picture, with its metadata row, as an import would leave them. The bytes
     * are the caller's business: half these tests are about what happens when they are missing.
     */
    private suspend fun seedPageWithPicture(digest: String): String {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        db.attachmentDao().insert(
            AttachmentEntity(
                id = digest,
                mimeType = "image/webp",
                pixelWidth = 8,
                pixelHeight = 8,
                byteCount = pictures.fileFor(digest).takeIf(File::exists)?.length()
                    ?: "a photograph".toByteArray().size.toLong(),
                refCount = 0,
                createdAt = now,
            ),
        )
        db.attachmentDao().retain(digest)
        repository.saveDoc(pageId, docWithPicture(digest))
        return pageId
    }

    private fun docWithPicture(digest: String, x: Float = 0f) = PageDoc(
        outlines = listOf(
            Outline.Image(
                id = "image-1",
                x = x,
                y = 0f,
                width = 100f,
                height = 100f,
                attachmentId = digest,
            ),
        ),
    )

    private fun strokeRow(id: String, pageId: String, seq: Int) = InkStrokeEntity(
        id = id,
        pageId = pageId,
        seq = seq,
        brushFamily = "pressure-pen",
        brushVersion = 1,
        sizeDp = 2.5f,
        colorArgb = 0xFF0000,
        epsilon = 0.1f,
        stabilization = 3,
        minX = 1f,
        minY = 2f,
        maxX = 3f,
        maxY = 4f,
        points = byteArrayOf(7, 8, 9),
        enc = "ink/v1",
        createdAt = now,
    )

    private fun eraseRow(id: String, pageId: String) = InkEraseEntity(
        id = id,
        pageId = pageId,
        mode = EraserMode.Object,
        sizeDp = 8f,
        points = byteArrayOf(1, 2),
        enc = "ink/v1",
        createdAt = now,
    )

    private fun moveRow(id: String, pageId: String) = InkMoveEntity(
        id = id,
        pageId = pageId,
        dxDp = 5f,
        dyDp = -6f,
        points = byteArrayOf(3, 4),
        enc = "ink/v1",
        createdAt = now,
    )

    // --- deleting something blank -------------------------------------------------------------
    //
    // `memory/blankFlushPlan.md`. The local half is in `BlankFlushTest`; what is here is the half
    // only a server can answer, and it is the half that can go wrong quietly: a delete that never
    // travels leaves a notebook standing on the account for ever, and a create that travels after
    // its delete was dropped comes back on the next pull.

    @Test
    fun aNotebookFlushedBeforeItsFirstPushIsNeverMentionedToTheServer() = runBlocking {
        hierarchy.run(account())
        val notebookId = repository.createNotebook("New Notebook")
        repository.createSection(notebookId, "New Section")
        assertTrue("nothing was queued to begin with", db.syncDao().outbox(64).isNotEmpty())

        repository.deleteNotebook(notebookId)
        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(0, result.summary.pushed)
        assertTrue(server.pushes.isEmpty())
        assertNull(server.current("notebook", notebookId))
    }

    /**
     * The same notebook one sync later. The server has it, so the delete has to travel — and only
     * once it has been acknowledged may the rows go, which is what the purge's outbox guard means.
     */
    @Test
    fun aNotebookTheServerAlreadyHoldsIsFlushedByATombstoneAndThenCollected() = runBlocking {
        val notebookId = repository.createNotebook("New Notebook")
        val sectionId = repository.createSection(notebookId, "New Section")
        hierarchy.run(account())
        // `JsonNull`, not an absent key: every push writes the envelope whole.
        assertEquals(JsonNull, server.current("notebook", notebookId)!!["deletedAt"])

        repository.deleteNotebook(notebookId)
        assertNotNull("the rows left before the delete was sent", db.notebookDao().byId(notebookId))

        hierarchy.run(account())

        assertNotNull(server.current("notebook", notebookId)!!.getValue("deletedAt").jsonPrimitive.long)
        repository.purgeExpiredDeletions()
        assertNull(db.notebookDao().byId(notebookId))
        assertNull(db.sectionDao().byId(sectionId))
        assertTrue("the queue outlived the rows", db.syncDao().outbox(64).isEmpty())
    }

    /**
     * The race the flush is actually careful about: a batch already serialized is re-sent byte for
     * byte after a lost response, so its rows are on their way to the server whatever this device
     * decides next. Dropping the queued delete here would land the create with nothing behind it.
     */
    @Test
    fun aFlushDoesNotDropTheDeleteOfSomethingAlreadyInAStrandedBatch() = runBlocking {
        hierarchy.run(account())
        val notebookId = repository.createNotebook("New Notebook")
        server.failNextPush = ServerResult.Failed(ConnectFailure.Unreachable, retryable = true)
        hierarchy.run(account())
        assertNotNull(
            "the batch should have been kept for a byte-identical retry",
            db.localMetadataDao().value(NotesRepository.PENDING_SYNC_BATCH_KEY),
        )

        repository.deleteNotebook(notebookId)
        hierarchy.run(account())
        hierarchy.run(account())

        assertNotNull(
            "the server was left holding a notebook nothing would ever delete",
            server.current("notebook", notebookId)!!.getValue("deletedAt").jsonPrimitive.long,
        )
    }

    private fun account() = SyncAccount(
        serverUrl = "http://unused",
        accountId = "account",
        deviceId = "device",
        token = "vive_test",
        deviceName = "Test",
    )

    private class InMemorySyncServer : SyncTransport {
        private val rows = linkedMapOf<Pair<String, String>, JsonObject>()
        private val log = mutableListOf<JsonObject>()
        val pushes = mutableListOf<List<JsonObject>>()
        var beforeFirstPush: (suspend () -> Unit)? = null

        /** Consumed by the next push, so a test can leave a serialized batch stranded on disk. */
        var failNextPush: ServerResult.Failed? = null
        private var cursor = 0L

        /** The account's blobs, by digest — the `blobs` table and the file behind each row. */
        val blobs = linkedMapOf<String, ByteArray>()

        /** Every byte-route call, in order, so a test can show what a run did *not* ask for. */
        val blobCalls = mutableListOf<String>()

        /**
         * Digests to answer `missing_blob` for even while [blobs] holds them, cleared by an upload.
         *
         * This is the server whose database survived and whose blob volume did not — the case
         * `syncPlan.md` §13.12 designed the 404 and the rejection for. Cleared by the upload
         * because that is precisely what repairs it.
         */
        val lostBlobs = mutableSetOf<String>()

        fun seed(vararg changes: JsonObject) {
            cursor++
            changes.forEach { raw ->
                val key = kindOf(raw) to idOf(raw)
                val previousVersion = rows[key]?.getValue("version")?.jsonPrimitive?.long ?: 0
                val stored = raw.toMutableMap().apply {
                    this["version"] = JsonPrimitive(previousVersion + 1)
                    this["seq"] = JsonPrimitive(cursor)
                }.let(::JsonObject)
                rows[key] = stored
                log += stored
            }
        }

        fun current(kind: String, id: String): JsonObject? = rows[kind to id]

        override suspend fun getCursor(serverBaseUrl: String, token: String) =
            ServerResult.Success(cursor)

        /** Rows per pull, so a test can put a parent and its child in different pages. */
        var pageLimit: Int = Int.MAX_VALUE

        override suspend fun pullChanges(
            serverBaseUrl: String,
            token: String,
            since: Long,
            limit: Int,
        ): ServerResult<PullChangesPage> {
            val remaining = log.filter { it.getValue("seq").jsonPrimitive.long > since }
            if (remaining.size <= pageLimit) {
                return ServerResult.Success(PullChangesPage(remaining, cursor, hasMore = false))
            }
            val page = remaining.take(pageLimit)
            return ServerResult.Success(
                PullChangesPage(
                    changes = page,
                    cursor = page.last().getValue("seq").jsonPrimitive.long,
                    hasMore = true,
                ),
            )
        }

        override suspend fun pushChanges(
            serverBaseUrl: String,
            token: String,
            batchId: String,
            changes: List<JsonObject>,
        ): ServerResult<PushChangesReply> {
            pushes += changes
            beforeFirstPush?.also { callback ->
                beforeFirstPush = null
                callback()
            }
            failNextPush?.also { failure ->
                failNextPush = null
                return failure
            }

            val applied = mutableListOf<AppliedServerChange>()
            val rejected = mutableListOf<RejectedServerChange>()
            val accepted = mutableListOf<Pair<JsonObject, Long>>()
            changes.forEach { incoming ->
                val key = kindOf(incoming) to idOf(incoming)
                val current = rows[key]
                val currentVersion = current?.getValue("version")?.jsonPrimitive?.long ?: 0
                val baseVersion = incoming.getValue("baseVersion").jsonPrimitive.long
                val unheld = digestsRequiredBy(incoming)
                    .filter { it !in blobs || it in lostBlobs }
                if (unheld.isNotEmpty()) {
                    // The invariant the whole phase exists to keep: a live row never points at
                    // bytes the server cannot serve, so it refuses to store one that would.
                    rejected += RejectedServerChange(
                        key.first,
                        key.second,
                        "missing_blob",
                        unheld.joinToString(),
                        null,
                    )
                } else if (baseVersion != currentVersion) {
                    rejected += RejectedServerChange(
                        key.first,
                        key.second,
                        "version_conflict",
                        null,
                        current,
                    )
                } else {
                    accepted += incoming to (currentVersion + 1)
                }
            }

            if (accepted.isNotEmpty()) cursor++
            accepted.forEach { (incoming, version) ->
                val stored = incoming.toMutableMap().apply {
                    remove("baseVersion")
                    this["version"] = JsonPrimitive(version)
                    this["seq"] = JsonPrimitive(cursor)
                }.let(::JsonObject)
                val key = kindOf(stored) to idOf(stored)
                rows[key] = stored
                log += stored
                applied += AppliedServerChange(key.first, key.second, version)
            }
            return ServerResult.Success(PushChangesReply(applied, rejected, cursor))
        }

        override suspend fun revokeDevice(
            serverBaseUrl: String,
            token: String,
            deviceId: String,
        ): ServerResult<Unit> = ServerResult.Success(Unit)

        override suspend fun hasBlob(
            serverBaseUrl: String,
            token: String,
            digest: String,
        ): ServerResult<Boolean> {
            blobCalls += "HEAD $digest"
            return ServerResult.Success(digest in blobs && digest !in lostBlobs)
        }

        override suspend fun uploadBlob(
            serverBaseUrl: String,
            token: String,
            digest: String,
            file: File,
        ): ServerResult<Boolean> {
            blobCalls += "PUT $digest"
            val bytes = file.readBytes()
            // The real server hashes what arrives and refuses a body that does not match its name.
            if (sha256(bytes) != digest) {
                return ServerResult.Failed(ConnectFailure.InvalidRequest, retryable = false)
            }
            // A digest whose file was lost is stored again, not deduplicated: the 201/204 the
            // real server answers is about the *bytes on disk*, which is exactly what was missing.
            val wasLost = lostBlobs.remove(digest)
            val held = blobs.put(digest, bytes) != null
            return ServerResult.Success(wasLost || !held)
        }

        override suspend fun downloadBlob(
            serverBaseUrl: String,
            token: String,
            digest: String,
            target: File,
        ): ServerResult<Boolean> {
            blobCalls += "GET $digest"
            val bytes = blobs[digest]?.takeIf { digest !in lostBlobs }
                ?: return ServerResult.Success(false)
            target.writeBytes(bytes)
            return ServerResult.Success(true)
        }

        private fun digestsRequiredBy(change: JsonObject): List<String> = when (kindOf(change)) {
            "attachment" -> listOf(idOf(change))
            "pageContent" -> (change["blobRefs"] as? JsonArray)
                .orEmpty()
                .map { it.jsonPrimitive.content }
            else -> emptyList()
        }
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

private fun kindOf(change: JsonObject): String = change.getValue("kind").jsonPrimitive.content
private fun idOf(change: JsonObject): String = change.getValue("id").jsonPrimitive.content

private fun notebookChange(id: String, name: String, updatedAt: Long, extra: String? = null): JsonObject =
    linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "kind" to JsonPrimitive("notebook"),
        "id" to JsonPrimitive(id),
        "deletedAt" to JsonNull,
        "updatedAt" to JsonPrimitive(updatedAt),
        "name" to JsonPrimitive(name),
        "colorArgb" to JsonPrimitive(1),
        "sortIndex" to JsonPrimitive(0),
        "expanded" to JsonPrimitive(true),
        "createdAt" to JsonPrimitive(updatedAt),
    ).apply { if (extra != null) this["future"] = JsonPrimitive(extra) }.let(::JsonObject)

private fun sectionChange(
    id: String,
    notebookId: String,
    name: String,
    updatedAt: Long,
): JsonObject = JsonObject(
    linkedMapOf(
        "kind" to JsonPrimitive("section"),
        "id" to JsonPrimitive(id),
        "deletedAt" to JsonNull,
        "updatedAt" to JsonPrimitive(updatedAt),
        "notebookId" to JsonPrimitive(notebookId),
        "name" to JsonPrimitive(name),
        "colorArgb" to JsonPrimitive(2),
        "sortIndex" to JsonPrimitive(0),
        "createdAt" to JsonPrimitive(updatedAt),
    ),
)

private fun pageChange(
    id: String,
    sectionId: String,
    title: String,
    updatedAt: Long,
): JsonObject = JsonObject(
    linkedMapOf(
        "kind" to JsonPrimitive("page"),
        "id" to JsonPrimitive(id),
        "deletedAt" to JsonNull,
        "updatedAt" to JsonPrimitive(updatedAt),
        "sectionId" to JsonPrimitive(sectionId),
        "title" to JsonPrimitive(title),
        "sortIndex" to JsonPrimitive(0),
        "preview" to JsonPrimitive("Preview"),
        "createdAt" to JsonPrimitive(updatedAt),
    ),
)

private fun inkStrokeChange(
    id: String,
    pageId: String,
    drawOrder: Int,
    updatedAt: Long,
    deletedAt: Long? = null,
    extra: String? = null,
): JsonObject = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
    "kind" to JsonPrimitive("inkStroke"),
    "id" to JsonPrimitive(id),
    "deletedAt" to (deletedAt?.let(::JsonPrimitive) ?: JsonNull),
    "updatedAt" to JsonPrimitive(updatedAt),
    "pageId" to JsonPrimitive(pageId),
    "drawOrder" to JsonPrimitive(drawOrder),
    "brushFamily" to JsonPrimitive("pressure-pen"),
    "brushVersion" to JsonPrimitive(1),
    "sizeDp" to JsonPrimitive(2.5f),
    "colorArgb" to JsonPrimitive(0xFF0000),
    "colorFollowsTheme" to JsonNull,
    "epsilon" to JsonPrimitive(0.1f),
    "stabilization" to JsonPrimitive(3),
    "minX" to JsonPrimitive(1f),
    "minY" to JsonPrimitive(2f),
    "maxX" to JsonPrimitive(3f),
    "maxY" to JsonPrimitive(4f),
    "points" to JsonPrimitive(Base64.getEncoder().encodeToString(byteArrayOf(7, 8, 9))),
    "enc" to JsonPrimitive("ink/v1"),
    "createdAt" to JsonPrimitive(updatedAt),
    "groupId" to JsonNull,
).apply { if (extra != null) this["future"] = JsonPrimitive(extra) }.let(::JsonObject)

private fun inkEraseChange(
    id: String,
    pageId: String,
    targetIds: List<String>,
    updatedAt: Long,
): JsonObject = JsonObject(
    linkedMapOf(
        "kind" to JsonPrimitive("inkErase"),
        "id" to JsonPrimitive(id),
        "deletedAt" to JsonNull,
        "updatedAt" to JsonPrimitive(updatedAt),
        "pageId" to JsonPrimitive(pageId),
        "mode" to JsonPrimitive("Normal"),
        "sizeDp" to JsonPrimitive(8f),
        "points" to JsonPrimitive(Base64.getEncoder().encodeToString(byteArrayOf(1, 2))),
        "enc" to JsonPrimitive("ink/v1"),
        "createdAt" to JsonPrimitive(updatedAt),
        "targetIds" to JsonArray(targetIds.map(::JsonPrimitive)),
    ),
)

private fun inkMoveChange(
    id: String,
    pageId: String,
    targetIds: List<String>,
    updatedAt: Long,
): JsonObject = JsonObject(
    linkedMapOf(
        "kind" to JsonPrimitive("inkMove"),
        "id" to JsonPrimitive(id),
        "deletedAt" to JsonNull,
        "updatedAt" to JsonPrimitive(updatedAt),
        "pageId" to JsonPrimitive(pageId),
        "dxDp" to JsonPrimitive(5f),
        "dyDp" to JsonPrimitive(-6f),
        "scaleX" to JsonPrimitive(1f),
        "scaleY" to JsonPrimitive(1f),
        "anchorX" to JsonPrimitive(0f),
        "anchorY" to JsonPrimitive(0f),
        "points" to JsonPrimitive(Base64.getEncoder().encodeToString(byteArrayOf(3, 4))),
        "enc" to JsonPrimitive("ink/v1"),
        "createdAt" to JsonPrimitive(updatedAt),
        "targetIds" to JsonArray(targetIds.map(::JsonPrimitive)),
    ),
)

private fun pageContentChange(id: String, document: String, updatedAt: Long): JsonObject {
    val bytes = document.encodeToByteArray()
    return JsonObject(
        linkedMapOf(
            "kind" to JsonPrimitive("pageContent"),
            "id" to JsonPrimitive(id),
            "deletedAt" to JsonNull,
            "updatedAt" to JsonPrimitive(updatedAt),
            "pageId" to JsonPrimitive(id),
            "doc" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
            "docSha256" to JsonPrimitive(
                Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)),
            ),
            "format" to JsonPrimitive("json/1"),
            "enc" to JsonPrimitive("none/1"),
        ),
    )
}

/** One picture's metadata as the server returns it. The id is the digest — there is no `sha256`. */
private fun attachmentChange(id: String, byteCount: Long, updatedAt: Long): JsonObject = JsonObject(
    linkedMapOf(
        "kind" to JsonPrimitive("attachment"),
        "id" to JsonPrimitive(id),
        "deletedAt" to JsonNull,
        "updatedAt" to JsonPrimitive(updatedAt),
        "mimeType" to JsonPrimitive("image/webp"),
        "pixelWidth" to JsonPrimitive(8),
        "pixelHeight" to JsonPrimitive(8),
        "byteCount" to JsonPrimitive(byteCount),
        "createdAt" to JsonPrimitive(updatedAt),
    ),
)

private fun decodeDocument(change: JsonObject): String =
    Base64.getDecoder().decode(change.getValue("doc").jsonPrimitive.content).decodeToString()
