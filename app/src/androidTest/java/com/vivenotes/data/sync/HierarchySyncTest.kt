package com.vivenotes.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.NotesRepository
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class HierarchySyncTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var server: InMemorySyncServer
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
        hierarchy = HierarchySync(db, server)
    }

    @After
    fun tearDown() = db.close()

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
            val peerResult = HierarchySync(peer, server).run(account()) as SyncRunResult.Succeeded
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
        private var cursor = 0L

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

            val applied = mutableListOf<AppliedServerChange>()
            val rejected = mutableListOf<RejectedServerChange>()
            val accepted = mutableListOf<Pair<JsonObject, Long>>()
            changes.forEach { incoming ->
                val key = kindOf(incoming) to idOf(incoming)
                val current = rows[key]
                val currentVersion = current?.getValue("version")?.jsonPrimitive?.long ?: 0
                val baseVersion = incoming.getValue("baseVersion").jsonPrimitive.long
                if (baseVersion != currentVersion) {
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
    }
}

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

private fun decodeDocument(change: JsonObject): String =
    Base64.getDecoder().decode(change.getValue("doc").jsonPrimitive.content).decodeToString()
