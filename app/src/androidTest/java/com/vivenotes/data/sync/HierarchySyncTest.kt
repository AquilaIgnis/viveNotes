package com.vivenotes.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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

        assertEquals(3, result.summary.pushed)
        assertEquals(3, result.summary.pulled)
        assertEquals(listOf("notebook", "section", "page"), server.pushes.first().map(::kindOf))
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
    fun anEditCommittedWhileAPushIsInFlightRemainsQueuedAndIsSentNext() = runBlocking {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Before")
        server.beforeFirstPush = {
            now += 10_000
            repository.renamePage(pageId, "After")
        }

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals(4, result.summary.pushed)
        val pageTitles = server.pushes.flatten()
            .filter { kindOf(it) == "page" }
            .map { it.getValue("title").jsonPrimitive.content }
        assertEquals(listOf("Before", "After"), pageTitles)
        assertEquals("After", db.pageDao().byId(pageId)!!.title)
        assertTrue(db.syncDao().outbox(512).isEmpty())
    }

    @Test
    fun aNewerRemoteRowWinsWhileAnOlderRemoteRowRebasesAndPushesLocal() = runBlocking {
        val localNewer = System.currentTimeMillis() + 60_000
        now = localNewer
        val localWinsId = repository.createNotebook("Local wins")
        val serverWinsId = repository.createNotebook("Local loses")
        server.seed(
            notebookChange(localWinsId, "Remote old", localNewer - 10_000),
            notebookChange(serverWinsId, "Remote new", localNewer + 10_000),
        )

        val result = hierarchy.run(account()) as SyncRunResult.Succeeded

        assertEquals("Local wins", db.notebookDao().byId(localWinsId)!!.name)
        assertEquals("Remote new", db.notebookDao().byId(serverWinsId)!!.name)
        val rebased = server.pushes.flatten().single { it.getValue("id").jsonPrimitive.content == localWinsId }
        assertEquals(1L, rebased.getValue("baseVersion").jsonPrimitive.long)
        assertEquals(1, result.summary.pushed)
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
                val stored = raw.toMutableMap().apply {
                    this["version"] = JsonPrimitive(1)
                    this["seq"] = JsonPrimitive(cursor)
                }.let(::JsonObject)
                rows[kindOf(stored) to idOf(stored)] = stored
                log += stored
            }
        }

        fun current(kind: String, id: String): JsonObject? = rows[kind to id]

        override suspend fun getCursor(serverBaseUrl: String, token: String) =
            ServerResult.Success(cursor)

        override suspend fun pullChanges(
            serverBaseUrl: String,
            token: String,
            since: Long,
            limit: Int,
        ): ServerResult<PullChangesPage> = ServerResult.Success(
            PullChangesPage(
                changes = log.filter { it.getValue("seq").jsonPrimitive.long > since },
                cursor = cursor,
                hasMore = false,
            ),
        )

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
