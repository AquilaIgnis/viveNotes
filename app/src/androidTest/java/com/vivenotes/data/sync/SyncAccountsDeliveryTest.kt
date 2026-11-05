package com.vivenotes.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.NotesDatabase
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How [SyncAccounts.remoteChanges] gets a device from nothing to a live stream, against a real HTTP
 * transport on loopback: what it asks for, in what order, and what it does when one answer is bad.
 *
 * [HierarchySyncTest] covers what a run does with the answers. What is here is the decision around
 * the run, which is what leaves a device stuck when it is wrong.
 */
@RunWith(AndroidJUnit4::class)
class SyncAccountsDeliveryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var scratch: File
    private lateinit var db: NotesDatabase
    private lateinit var server: LoopbackServer
    private lateinit var store: SyncAccountStore
    private lateinit var accounts: SyncAccounts
    private lateinit var account: SyncAccount

    @Before
    fun setUp() = runBlocking {
        scratch = File(context.cacheDir, "delivery-${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK)
            .build()
        server = LoopbackServer()
        store = SyncAccountStore(
            context,
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(scratch, "sync.preferences_pb")
            },
        )
        account = SyncAccount(
            serverUrl = server.baseUrl,
            accountId = "account",
            deviceId = "device",
            token = "vive_test",
            deviceName = "Test",
        )
        store.setAccount(account)
        accounts = SyncAccounts(
            context = context,
            client = SyncServerClient(),
            database = db,
            attachments = TemporaryAttachmentBytes(File(scratch, "attachments")),
            store = store,
        )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
        storeScope.cancel()
        scratch.deleteRecursively()
    }

    private fun CoroutineScope.startDelivery(): Job =
        launch(Dispatchers.Default) { accounts.remoteChanges.collect { } }

    private suspend fun awaitStream() {
        withTimeout(WAIT_MILLIS) { accounts.streamReady.first { it } }
    }

    /** The two rows every first sync below is given, and what it has applied by the end of one. */
    private fun firstSyncReply(query: String, cursor: Long = 7): Reply = when {
        "scope=tree" in query -> json(pageOf(notebookRow("kept", 3), notebookRow("gone", 7), cursor = cursor))
        else -> json(pageOf(notebookRow("kept", 3), notebookRow("gone", 7), cursor = cursor))
    }

    @Test
    fun aFirstSyncRunsOverPullsAndTheStreamOpensWhereItFinished() = runBlocking<Unit> {
        server.respond = { path, query ->
            when (path) {
                CURSOR -> json("""{"cursor":7}""")
                CHANGES -> firstSyncReply(query)
                else -> stream(pageOf(cursor = 7))
            }
        }

        val delivery = startDelivery()
        awaitStream()
        delivery.cancelAndJoin()

        assertEquals(
            listOf(
                CURSOR,
                "$CHANGES?since=0&limit=512&scope=tree",
                "$CHANGES?since=0&limit=2048&reconcile=true",
                "$WATCH?since=7",
            ),
            server.requests.toList(),
        )
        assertNotNull(db.notebookDao().byId("kept"))
        assertEquals(7L, db.syncDao().state()!!.cursor)
    }

    /** One bad page must not lose the tree already applied, or stop the device trying again. */
    @Test
    fun aFirstSyncInterruptedByAServerErrorRetriesAndThenOpensTheStream() = runBlocking<Unit> {
        var failedOnce = false
        server.respond = { path, query ->
            when {
                path == CURSOR -> json("""{"cursor":7}""")
                path == CHANGES && "scope=tree" !in query && !failedOnce -> {
                    failedOnce = true
                    json("""{"error":"internal","message":"m"}""", status = 503)
                }
                path == CHANGES -> firstSyncReply(query)
                else -> stream(pageOf(cursor = 7))
            }
        }

        val delivery = startDelivery()
        awaitStream()
        delivery.cancelAndJoin()

        assertTrue(failedOnce)
        assertEquals("the tree is not read twice", 1, server.requests.count { "scope=tree" in it })
        assertEquals(1, server.requests.count { WATCH in it })
        assertNull("recovered, so nothing is left on the status line", accounts.status.value.failure)
        assertEquals(7L, db.syncDao().state()!!.cursor)
    }

    /** Retrying cannot help a lapsed membership, and opening the stream would only ask again. */
    @Test
    fun aFirstSyncRefusedForGoodStopsAndSaysSo() = runBlocking<Unit> {
        server.respond = { path, _ ->
            when (path) {
                CURSOR -> json("""{"error":"membership_required","message":"m"}""", status = 402)
                // Answered rather than thrown: this runs on the server's thread, where an exception
                // takes the whole test process down. The assertion below sees the request anyway.
                else -> json("""{"error":"internal","message":"m"}""", status = 500)
            }
        }

        val delivery = startDelivery()
        withTimeout(WAIT_MILLIS) { accounts.status.first { it.failure != null } }
        // Longer than the first retry would wait, so a retry would be on the list below.
        delay(RETRY_WOULD_HAVE_HAPPENED_MILLIS)
        delivery.cancelAndJoin()

        assertEquals(listOf(CURSOR), server.requests.toList())
        assertFalse(accounts.streamReady.value)
        assertEquals(
            SyncRunResult.Failed(PermanentSyncFailure.MembershipRequired),
            accounts.status.value.failure,
        )
    }

    /**
     * Forgetting the credential is what cancels the flow this runs in, so the cleanup behind it has
     * to survive that. Left half done, the database still belongs to an account the device has just
     * been locked out of, and its triggers go on queueing every edit for it.
     */
    @Test
    fun aDeviceRevokedDuringItsFirstSyncClearsItsCredentialAndItsDatabaseState() = runBlocking<Unit> {
        server.respond = { _, _ -> json("""{"error":"unauthenticated","message":"m"}""", status = 401) }

        val delivery = startDelivery()
        withTimeout(WAIT_MILLIS) { store.account.first { it == null } }
        val detached = withTimeoutOrNull(RETRY_WOULD_HAVE_HAPPENED_MILLIS) {
            while (db.syncDao().state() != null) delay(POLL_MILLIS)
            true
        }
        delivery.cancelAndJoin()

        assertNotNull("the database must not stay attached to that account", detached)
        assertEquals(SyncRunResult.Revoked, accounts.status.value.failure)
    }

    /** The same trap from the other side: the foreground scheduler restarts on the account too. */
    @Test
    fun aDeviceRevokedDuringAPushClearsItsDatabaseStateEvenThoughThatCancelsThePush() = runBlocking<Unit> {
        server.respond = { path, query ->
            when (path) {
                CURSOR -> json("""{"cursor":7}""")
                else -> firstSyncReply(query)
            }
        }
        assertTrue(accounts.synchronize() is SyncRunResult.Succeeded)
        NotesRepository(db).createNotebook("Written here")
        server.respond = { _, _ -> json("""{"error":"unauthenticated","message":"m"}""", status = 401) }

        // The shape of ForegroundSyncScheduler: pushes run inside a collector of the registration.
        val scheduler = launch(Dispatchers.Default) {
            store.account.map { it != null }.distinctUntilChanged().collectLatest { registered ->
                if (registered) accounts.pushPending()
            }
        }
        withTimeout(WAIT_MILLIS) { store.account.first { it == null } }
        val detached = withTimeoutOrNull(RETRY_WOULD_HAVE_HAPPENED_MILLIS) {
            while (db.syncDao().state() != null) delay(POLL_MILLIS)
            true
        }
        scheduler.cancelAndJoin()

        assertNotNull("the database must not stay attached to that account", detached)
    }

    @Test
    fun aStreamToldItsCursorExpiredReconcilesAndReconnectsFromTheNewCursor() = runBlocking<Unit> {
        var swept = false
        server.respond = { path, query ->
            when {
                path == CURSOR -> json(if (swept) """{"cursor":9}""" else """{"cursor":7}""")
                path == WATCH && query == "since=7" -> {
                    swept = true
                    expired()
                }
                path == WATCH -> stream(pageOf(cursor = 9))
                path == CHANGES && !swept -> firstSyncReply(query)
                path == CHANGES && "since=7" in query -> expired()
                else -> json(pageOf(notebookRow("kept", 3), cursor = 9))
            }
        }

        val delivery = startDelivery()
        awaitStream()
        delivery.cancelAndJoin()

        assertEquals("$WATCH?since=9", server.requests.last())
        assertEquals(9L, db.syncDao().state()!!.cursor)
        assertNotNull(db.notebookDao().byId("kept"))
        assertNull("the account no longer holds it", db.notebookDao().byId("gone"))
        assertNull(accounts.status.value.failure)
    }

    @Test
    fun aReconciliationInterruptedByAServerErrorIsTriedAgainFromTheStream() = runBlocking<Unit> {
        var swept = false
        var failedOnce = false
        server.respond = { path, query ->
            when {
                path == CURSOR -> json(if (swept) """{"cursor":9}""" else """{"cursor":7}""")
                path == WATCH && query == "since=7" -> {
                    swept = true
                    expired()
                }
                path == WATCH -> stream(pageOf(cursor = 9))
                path == CHANGES && !swept -> firstSyncReply(query)
                path == CHANGES && "since=7" in query -> expired()
                !failedOnce -> {
                    failedOnce = true
                    json("""{"error":"internal","message":"m"}""", status = 503)
                }
                else -> json(pageOf(notebookRow("kept", 3), cursor = 9))
            }
        }

        val delivery = startDelivery()
        awaitStream()
        delivery.cancelAndJoin()

        assertTrue(failedOnce)
        assertEquals(9L, db.syncDao().state()!!.cursor)
        assertNull(db.notebookDao().byId("gone"))
        assertEquals("$WATCH?since=9", server.requests.last())
    }

    private sealed interface Reply {
        data class Body(val status: Int, val body: String) : Reply

        /** A stream that sends its one `ready` page and then stays open and silent. */
        data class Events(val readyPage: String) : Reply
    }

    private fun json(body: String, status: Int = 200): Reply = Reply.Body(status, body)

    private fun stream(readyPage: String): Reply = Reply.Events(readyPage)

    private fun expired(): Reply = json(
        """{"error":"cursor_expired","message":"m","minimumCursor":8,"currentCursor":9}""",
        status = 410,
    )

    private fun pageOf(vararg rows: String, cursor: Long, hasMore: Boolean = false): String =
        """{"changes":[${rows.joinToString(",")}],"purges":[],"cursor":$cursor,"hasMore":$hasMore}"""

    private fun notebookRow(id: String, seq: Long): String =
        """{"kind":"notebook","id":"$id","version":1,"seq":$seq,"updatedAt":1,"deletedAt":null,""" +
            """"name":"$id","colorArgb":1,"sortIndex":0,"expanded":true,"createdAt":1}"""

    /** Just enough HTTP for one request per connection, with every request recorded in order. */
    private class LoopbackServer : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val openStreams = CopyOnWriteArrayList<Socket>()
        val baseUrl = "http://127.0.0.1:${listener.localPort}"
        val requests = CopyOnWriteArrayList<String>()

        @Volatile
        var respond: (path: String, query: String) -> Reply = { _, _ -> Reply.Body(404, "{}") }

        init {
            thread(isDaemon = true, name = "delivery-test-server") {
                while (!listener.isClosed) {
                    val client = try {
                        listener.accept()
                    } catch (_: java.io.IOException) {
                        break
                    }
                    thread(isDaemon = true) { handle(client) }
                }
            }
        }

        private fun handle(client: Socket) {
            try {
                val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val target = reader.readLine()?.split(" ")?.getOrNull(1) ?: return
                var bodyLength = 0
                while (true) {
                    val header = reader.readLine().orEmpty()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        bodyLength = header.substringAfter(':').trim().toInt()
                    }
                }
                // Read whatever was sent: closing on unread bytes resets the connection, and the
                // client would see a dropped connection rather than this answer.
                repeat(bodyLength) { reader.read() }

                requests += target
                val reply = respond(target.substringBefore('?'), target.substringAfter('?', ""))
                val output = client.getOutputStream()
                when (reply) {
                    is Reply.Body -> {
                        val bytes = reply.body.toByteArray()
                        output.write(
                            ("HTTP/1.1 ${reply.status} X\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray(),
                        )
                        output.write(bytes)
                        output.flush()
                        client.close()
                    }
                    is Reply.Events -> {
                        openStreams += client
                        output.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                "Connection: close\r\n\r\nevent: ready\ndata: ${reply.readyPage}\n\n")
                                .toByteArray(),
                        )
                        output.flush()
                    }
                }
            } catch (_: java.io.IOException) {
                // The client hung up, which is how every stream in this suite ends.
            }
        }

        override fun close() {
            listener.close()
            openStreams.forEach { it.close() }
        }
    }

    private companion object {
        const val CURSOR = "/v1/cursor"
        const val CHANGES = "/v1/changes"
        const val WATCH = "/v1/changes/watch"
        const val WAIT_MILLIS = 20_000L
        const val RETRY_WOULD_HAVE_HAPPENED_MILLIS = 1_500L
        const val POLL_MILLIS = 20L
    }
}
