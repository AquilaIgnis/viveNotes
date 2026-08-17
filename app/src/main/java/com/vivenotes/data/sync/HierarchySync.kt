package com.vivenotes.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.LocalMetadataEntity
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.data.db.SyncEntityStateEntity
import com.vivenotes.data.db.SyncOutboxEntity
import com.vivenotes.data.db.SyncStateEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/** What one complete hierarchy synchronization accomplished. */
data class SyncSummary(
    val pulled: Int,
    val pushed: Int,
    val conflictsResolved: Int,
)

enum class PermanentSyncFailure {
    InvalidServerResponse,
    LocalData,
    ChangeTooLarge,
    MalformedChange,
    MissingParent,
}

sealed interface SyncRunResult {
    data class Succeeded(val summary: SyncSummary) : SyncRunResult
    data class Retryable(val reason: ConnectFailure) : SyncRunResult
    data class Failed(val reason: PermanentSyncFailure) : SyncRunResult
    data object Revoked : SyncRunResult
}

/**
 * Offline-first synchronization for the entity kinds OpenAPI 0.2.0 actually exposes.
 *
 * Room is always the UI's source of truth. This class snapshots its durable outbox, performs the
 * network request without a database transaction held open, then conditionally acknowledges the
 * exact generations it sent. [run] is serialized because two workers using the same cursor and
 * pending idempotency batch would otherwise be indistinguishable from a lost response.
 */
class HierarchySync(
    private val db: NotesDatabase,
    private val client: SyncTransport = SyncServerClient(),
) {

    private val sync = db.syncDao()
    private val metadata = db.localMetadataDao()
    private val notebooks = db.notebookDao()
    private val sections = db.sectionDao()
    private val pages = db.pageDao()
    private val mutex = Mutex()

    /** Makes [accountId] the owner of this database's one hierarchy corpus. */
    suspend fun activate(accountId: String) = mutex.withLock {
        activateLocked(accountId)
    }

    /** Clears account-specific versions and queued work after the credential is intentionally gone. */
    suspend fun deactivate(accountId: String) = mutex.withLock {
        db.withTransaction {
            if (sync.state()?.accountId != accountId) return@withTransaction
            // Turn the triggers off first; clearing their own tables never touches the hierarchy,
            // but this ordering keeps future cleanup additions safe by construction.
            sync.clearState()
            sync.clearOutbox()
            sync.clearEntityStates()
            metadata.delete(PENDING_BATCH_KEY)
            metadata.delete(CAUGHT_UP_KEY)
        }
    }

    /**
     * Whether this installation has seen [accountId]'s tree at least once.
     *
     * Read by [SyncAccounts.maySeedStarter]: a device that has not yet caught up cannot tell an empty
     * account from an account it simply has not pulled, and guessing wrong is how a second starter
     * notebook gets created and pushed.
     */
    suspend fun hasCaughtUp(accountId: String): Boolean = metadata.value(CAUGHT_UP_KEY) == accountId

    suspend fun run(account: SyncAccount): SyncRunResult = mutex.withLock {
        try {
            activateLocked(account.accountId)

            var pulled = 0
            var pushed = 0
            var conflicts = 0

            when (val firstPull = pullIfNeeded(account)) {
                is PhaseResult.Done -> pulled += firstPull.count
                is PhaseResult.ConflictDone -> error("pull cannot return conflict count")
                is PhaseResult.Stop -> return@withLock firstPull.result
            }
            markCaughtUp(account.accountId)
            dropStarterSupersededByAccount()

            when (val push = pushOutbox(account)) {
                is PhaseResult.Done -> pushed += push.count
                is PhaseResult.ConflictDone -> {
                    pushed += push.count
                    conflicts += push.conflicts
                }
                is PhaseResult.Stop -> return@withLock push.result
            }

            // The push cursor is explicitly not a pull cursor. Pull this device's accepted writes
            // and anything another device committed concurrently before reporting convergence.
            //
            // Only when the push phase actually moved something, though. SD6 requires an idle run to
            // cost one `GET /v1/cursor` and nothing else, and idle is the common case now that a
            // clock ticks whether or not there is anything to send: with an empty outbox there are
            // no accepted writes to consume and no window below a push response's cursor to close,
            // and the first pull already left this device where the server is.
            if (pushed > 0 || conflicts > 0) {
                when (val finalPull = pullIfNeeded(account)) {
                    is PhaseResult.Done -> pulled += finalPull.count
                    is PhaseResult.ConflictDone -> error("pull cannot return conflict count")
                    is PhaseResult.Stop -> return@withLock finalPull.result
                }
            }

            SyncRunResult.Succeeded(SyncSummary(pulled, pushed, conflicts))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (badLocalState: Exception) {
            // Logged, not just classified. [PermanentSyncFailure.LocalData] tells the screen that
            // this run cannot succeed by being retried, and tells whoever has to fix it nothing at
            // all — and this is the one failure whose cause is a stack trace rather than a status
            // code. A run that fails here also leaves the cursor where it was, so a silent version
            // of this is a device that re-pulls the same delta for ever with nothing to show for it.
            Log.e(TAG, "Hierarchy sync could not apply a change locally", badLocalState)
            SyncRunResult.Failed(PermanentSyncFailure.LocalData)
        }
    }

    private suspend fun activateLocked(accountId: String) {
        db.withTransaction {
            val current = sync.state()
            if (current?.accountId == accountId) {
                // This should never survive a transaction rollback, but clearing it makes recovery
                // deterministic if a database was copied while a transaction was being inspected.
                if (current.applyingRemote) sync.setApplyingRemote(false)
                return@withTransaction
            }

            sync.clearState()
            sync.clearOutbox()
            sync.clearEntityStates()
            metadata.delete(PENDING_BATCH_KEY)
            // A different account is a different tree, so "I have seen this account's tree" cannot
            // carry over — the new one has not been pulled even once.
            metadata.delete(CAUGHT_UP_KEY)
            sync.putState(SyncStateEntity(accountId = accountId))
            // Existing offline rows are the first outbox. Inserts performed before the state row
            // existed could not have fired the triggers, so this is explicit rather than magical.
            sync.enqueueAllNotebooks()
            sync.enqueueAllSections()
            sync.enqueueAllPages()
        }
    }

    /**
     * Records that a pull has completed, which is what makes an empty local tree *mean* something.
     *
     * Written outside the pull's transaction deliberately: the cheapest and most common way to be
     * caught up is `PhaseResult.Done(0)` — the cursor already matched — and that path has no
     * transaction to join. A crash between the two costs one repeated marker write on the next run.
     * The read first is so a device that syncs every 60 s does not write a row every 60 s.
     */
    private suspend fun markCaughtUp(accountId: String) {
        if (metadata.value(CAUGHT_UP_KEY) == accountId) return
        metadata.put(LocalMetadataEntity(CAUGHT_UP_KEY, accountId))
    }

    /**
     * Throws away this installation's seeded starter notebook once the first pull shows it is
     * joining an account that already has a tree.
     *
     * Gating [com.vivenotes.data.NotesRepository.seedIfEmpty] is not enough on its own, because the
     * order that actually happens is the other way round: a clean install seeds "My Notebook" on its
     * first launch so the app does not open on a void, and only *then* can its owner open Account
     * and connect — the UI offers no earlier moment. Activation enqueues that starter like any other
     * offline row, pushes it, and the account grows one more identical "My Notebook" for every
     * device that ever joins. Three of them is how this was found.
     *
     * The starter is not a contribution, it is packaging, and `REPLACEABLE_STARTER_KEY` is what says
     * so: it is written when the starter is seeded and cleared by the first content mutation of any
     * kind, so its presence means nothing under here has ever been touched. Rows are removed outright
     * rather than tombstoned because no server has ever seen them — there is nothing for anyone to
     * learn — and the queued push is pruned in the same transaction.
     *
     * Runs after the pull and before the push, which is the only window where "the account has a
     * tree of its own" is a fact rather than a guess. An account that really is empty keeps its
     * starter and uploads it, exactly as the first device did.
     */
    private suspend fun dropStarterSupersededByAccount() {
        val starterId = metadata.value(NotesRepository.REPLACEABLE_STARTER_KEY) ?: return
        if (notebooks.count() <= 1) return

        db.withTransaction {
            // The triggers must not read this as the user deleting something worth telling the
            // server about.
            sync.setApplyingRemote(true)
            notebooks.hardDelete(starterId)
            sync.pruneOrphanedOutbox()
            metadata.delete(NotesRepository.REPLACEABLE_STARTER_KEY)
            sync.setApplyingRemote(false)
        }
    }

    private suspend fun pullIfNeeded(account: SyncAccount): PhaseResult {
        val localCursor = sync.state()?.cursor
            ?: return PhaseResult.Stop(SyncRunResult.Failed(PermanentSyncFailure.LocalData))
        val serverCursor = when (val result = client.getCursor(account.serverUrl, account.token)) {
            is ServerResult.Success -> result.value
            ServerResult.Unauthorized -> return PhaseResult.Stop(SyncRunResult.Revoked)
            is ServerResult.Failed -> return PhaseResult.Stop(result.asSyncResult())
        }
        if (serverCursor == localCursor) return PhaseResult.Done(0)

        var cursor = localCursor
        var pulled = 0
        var hasMore: Boolean
        // Changes whose parent has not arrived yet, carried into the next page rather than applied.
        var carried: List<RemoteChange> = emptyList()
        do {
            val page = when (
                val result = client.pullChanges(account.serverUrl, account.token, cursor)
            ) {
                is ServerResult.Success -> result.value
                ServerResult.Unauthorized -> return PhaseResult.Stop(SyncRunResult.Revoked)
                is ServerResult.Failed -> return PhaseResult.Stop(result.asSyncResult())
            }

            val parsed = try {
                page.changes.mapNotNull(::parseRemoteChange)
            } catch (malformed: IllegalArgumentException) {
                return PhaseResult.Stop(
                    SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse),
                )
            }

            // Parents before children, which the stream order does not give.
            //
            // The server orders a delta by `(change_seq, kind_rank, id)`, so a notebook precedes its
            // sections *within one sequence value* — but a row's `change_seq` is the seq of its last
            // write, not of its creation. Rename a notebook after its sections were created and the
            // notebook moves to a higher seq than its own children, so a client pulling from below
            // both receives the sections first and Room refuses them: `SQLiteConstraintException:
            // FOREIGN KEY constraint failed`. That aborts the transaction, the cursor is not
            // committed, and the device re-pulls the same delta for ever.
            //
            // A stable sort by kind is enough for a two-level hierarchy and keeps the server's
            // ordering inside each kind.
            val ordered = (carried + parsed).sortedBy { change -> change.kind.rank }

            // Sorting cannot help when a parent lands in a *later page* than its child, which needs
            // more than a page of changes between the two writes but is not impossible. Such a row
            // is held back rather than applied: Room would refuse it, and refusing it inside the
            // transaction that commits the cursor is what turns one straddling row into a device
            // that re-pulls the same delta for ever.
            val applicable = mutableListOf<RemoteChange>()
            val orphans = mutableListOf<RemoteChange>()
            // Held back transitively: a page whose section is itself waiting for its notebook has to
            // wait with it, or the page is written against a section Room does not have either.
            // One pass is enough because the batch is already sorted parents-first.
            val applying = HashSet<String>()
            for (change in ordered) {
                if (parentIsAvailable(change, applying)) {
                    applicable += change
                    applying += change.kind.wire + ":" + change.id
                } else {
                    orphans += change
                }
            }
            carried = orphans

            db.withTransaction {
                sync.setApplyingRemote(true)
                applicable.forEach { change -> applyPulledChange(change) }
                // Only when nothing is being held. The cursor is a promise that everything below it
                // has been applied, so advancing it past a row still waiting for its parent would
                // lose that row for good — the next pull starts above it.
                if (orphans.isEmpty()) sync.setCursor(page.cursor)
                sync.setApplyingRemote(false)
            }
            pulled += applicable.size
            cursor = page.cursor
            hasMore = page.hasMore

            if (hasMore && page.changes.isEmpty()) {
                return PhaseResult.Stop(
                    SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse),
                )
            }
        } while (hasMore)

        if (carried.isNotEmpty()) {
            // The delta ended and a child's parent never arrived. The server rejects a push whose
            // parent it does not hold, so this cannot happen to a well-behaved pair — and leaving
            // the cursor where it is means the next run tries the same delta rather than skipping
            // rows nothing here can place.
            return PhaseResult.Stop(SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse))
        }

        return PhaseResult.Done(pulled)
    }

    /**
     * Whether a change can be written now: its parent is already in Room, or is one of the rows
     * [applying] is about to write in the same transaction. A notebook has no parent and is
     * therefore always applicable.
     */
    private suspend fun parentIsAvailable(change: RemoteChange, applying: Set<String>): Boolean {
        val parentKind = when (change.kind) {
            SyncKind.Notebook -> return true
            SyncKind.Section -> SyncKind.Notebook
            SyncKind.Page -> SyncKind.Section
        }
        val parentID = when (change.kind) {
            SyncKind.Notebook -> return true
            SyncKind.Section -> change.raw.requiredString("notebookId")
            SyncKind.Page -> change.raw.requiredString("sectionId")
        }
        if (applying.contains(parentKind.wire + ":" + parentID)) return true
        return when (parentKind) {
            SyncKind.Notebook -> notebooks.byId(parentID) != null
            SyncKind.Section -> sections.byId(parentID) != null
            SyncKind.Page -> false
        }
    }

    private suspend fun applyPulledChange(remote: RemoteChange) {
        val dirty = sync.outboxEntry(remote.kind.wire, remote.id)
        sync.putEntityState(remote.asEntityState())

        // A local mutation later than the remote write remains the source of truth for now. It is
        // rebased onto the new server version and the outbox generation is intentionally untouched.
        if (dirty != null && dirty.changedAt > remote.updatedAt) return

        applyRemoteRow(remote)
        if (dirty != null) sync.deleteOutbox(remote.kind.wire, remote.id)
    }

    private suspend fun pushOutbox(account: SyncAccount): PhaseResult {
        var pushed = 0
        var conflicts = 0
        var batchCount = 0

        while (batchCount++ < MAX_BATCHES_PER_RUN) {
            val pending = loadOrCreatePendingBatch()
                ?: return PhaseResult.ConflictDone(pushed, conflicts)

            val response = when (
                val result = client.pushChanges(
                    serverBaseUrl = account.serverUrl,
                    token = account.token,
                    batchId = pending.batchId,
                    changes = pending.changes.map(PendingChange::payload),
                )
            ) {
                is ServerResult.Success -> result.value
                ServerResult.Unauthorized -> return PhaseResult.Stop(SyncRunResult.Revoked)
                is ServerResult.Failed -> {
                    // A retryable transport result keeps the serialized batch and its batchId on
                    // disk. WorkManager's next run sends byte-for-byte the same logical request.
                    if (!result.retryable) metadata.delete(PENDING_BATCH_KEY)
                    return PhaseResult.Stop(result.asSyncResult())
                }
            }

            val sentByKey = pending.changes.associateBy { it.kind to it.id }
            val answered = buildSet {
                response.applied.forEach { add(it.kind to it.id) }
                response.rejected.forEach { add(it.kind to it.id) }
            }
            if (
                answered != sentByKey.keys ||
                response.applied.size + response.rejected.size != pending.changes.size
            ) {
                metadata.delete(PENDING_BATCH_KEY)
                return PhaseResult.Stop(
                    SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse),
                )
            }

            var permanent: PermanentSyncFailure? = null
            db.withTransaction {
                sync.setApplyingRemote(true)
                response.applied.forEach { applied ->
                    val sent = sentByKey.getValue(applied.kind to applied.id)
                    sync.putEntityState(sent.asAcceptedState(applied.version))
                    sync.deleteOutboxGeneration(sent.kind, sent.id, sent.generation)
                    pushed++
                }
                response.rejected.forEach { rejected ->
                    val sent = sentByKey.getValue(rejected.kind to rejected.id)
                    when (rejected.reason) {
                        "version_conflict" -> {
                            conflicts++
                            resolveVersionConflict(sent, rejected.current)
                        }
                        "missing_parent" -> {
                            enqueueParent(sent)
                            if (sent.parentId == null) permanent = PermanentSyncFailure.MissingParent
                        }
                        "too_large" -> permanent = PermanentSyncFailure.ChangeTooLarge
                        "malformed" -> permanent = PermanentSyncFailure.MalformedChange
                        else -> permanent = PermanentSyncFailure.InvalidServerResponse
                    }
                }
                metadata.delete(PENDING_BATCH_KEY)
                sync.setApplyingRemote(false)
            }

            permanent?.let { return PhaseResult.Stop(SyncRunResult.Failed(it)) }
        }

        return PhaseResult.Stop(SyncRunResult.Failed(PermanentSyncFailure.MissingParent))
    }

    private suspend fun resolveVersionConflict(sent: PendingChange, currentJson: JsonObject?) {
        if (currentJson == null) {
            // The server no longer has the row. Base the still-dirty local row at zero next time.
            sync.deleteEntityState(sent.kind, sent.id)
            return
        }
        val current = parseRemoteChange(currentJson)
            ?: throw IllegalArgumentException("unsupported current kind")
        val currentDirty = sync.outboxEntry(sent.kind, sent.id)
        sync.putEntityState(current.asEntityState())

        // A new local edit landed after this batch was snapshotted. It wins this decision without
        // consulting the old generation's timestamp and stays queued on the current server version.
        if (currentDirty?.generation != sent.generation) return
        if (sent.updatedAt > current.updatedAt) return

        applyRemoteRow(current)
        sync.deleteOutboxGeneration(sent.kind, sent.id, sent.generation)
    }

    private suspend fun enqueueParent(sent: PendingChange) {
        val parentId = sent.parentId ?: return
        val parentKind = when (sent.kind) {
            SyncKind.Section.wire -> SyncKind.Notebook.wire
            SyncKind.Page.wire -> SyncKind.Section.wire
            else -> return
        }
        sync.enqueueIfAbsent(parentKind, parentId)
    }

    private suspend fun loadOrCreatePendingBatch(): PendingBatch? = db.withTransaction {
        metadata.value(PENDING_BATCH_KEY)?.let { encoded ->
            return@withTransaction hierarchyJson.decodeFromString(PendingBatch.serializer(), encoded)
        }

        val rows = sync.outbox(MAX_PUSH_CHANGES)
        if (rows.isEmpty()) return@withTransaction null
        val pending = PendingBatch(
            batchId = UUID.randomUUID().toString(),
            changes = rows.map { row -> snapshot(row) },
        )
        metadata.put(
            LocalMetadataEntity(
                PENDING_BATCH_KEY,
                hierarchyJson.encodeToString(PendingBatch.serializer(), pending),
            ),
        )
        pending
    }

    private suspend fun snapshot(outbox: SyncOutboxEntity): PendingChange {
        val kind = SyncKind.fromWire(outbox.kind)
            ?: throw IllegalStateException("unknown local sync kind ${outbox.kind}")
        val state = sync.entityState(outbox.kind, outbox.entityId)
        val base = state?.serverJson?.let(::decodeObject).orEmpty().toMutableMap().apply {
            remove("version")
            remove("seq")
            remove("baseVersion")
        }
        base["kind"] = JsonPrimitive(kind.wire)
        base["id"] = JsonPrimitive(outbox.entityId)
        base["baseVersion"] = JsonPrimitive(state?.serverVersion ?: 0)

        val parentId = when (kind) {
            SyncKind.Notebook -> {
                val row = notebooks.byId(outbox.entityId)
                    ?: throw IllegalStateException("dirty notebook disappeared")
                base.putEnvelope(row.deletedAt, maxOf(row.updatedAt, outbox.changedAt), row.updatedAt)
                base["name"] = JsonPrimitive(row.name)
                base["colorArgb"] = JsonPrimitive(row.colorArgb)
                base["sortIndex"] = JsonPrimitive(row.sortIndex)
                base["expanded"] = JsonPrimitive(row.expanded)
                base["createdAt"] = JsonPrimitive(row.createdAt)
                null
            }
            SyncKind.Section -> {
                val row = sections.byId(outbox.entityId)
                    ?: throw IllegalStateException("dirty section disappeared")
                base.putEnvelope(row.deletedAt, maxOf(row.updatedAt, outbox.changedAt), row.updatedAt)
                base["notebookId"] = JsonPrimitive(row.notebookId)
                base["name"] = JsonPrimitive(row.name)
                base["colorArgb"] = JsonPrimitive(row.colorArgb)
                base["sortIndex"] = JsonPrimitive(row.sortIndex)
                base["createdAt"] = JsonPrimitive(row.createdAt)
                row.notebookId
            }
            SyncKind.Page -> {
                val row = pages.byId(outbox.entityId)
                    ?: throw IllegalStateException("dirty page disappeared")
                base.putEnvelope(row.deletedAt, maxOf(row.updatedAt, outbox.changedAt), row.updatedAt)
                base["sectionId"] = JsonPrimitive(row.sectionId)
                base["title"] = JsonPrimitive(row.title)
                base["sortIndex"] = JsonPrimitive(row.sortIndex)
                base["preview"] = JsonPrimitive(row.preview)
                base["createdAt"] = JsonPrimitive(row.createdAt)
                row.sectionId
            }
        }
        val wireUpdatedAt = base.getValue("updatedAt").jsonPrimitive.longOrNull
            ?: throw IllegalStateException("outgoing updatedAt is not an integer")

        return PendingChange(
            kind = kind.wire,
            id = outbox.entityId,
            generation = outbox.generation,
            updatedAt = wireUpdatedAt,
            parentId = parentId,
            payload = JsonObject(base),
        )
    }

    private fun MutableMap<String, kotlinx.serialization.json.JsonElement>.putEnvelope(
        deletedAt: Long?,
        updatedAt: Long,
        displayUpdatedAt: Long,
    ) {
        this["deletedAt"] = deletedAt?.let(::JsonPrimitive) ?: JsonNull
        this["updatedAt"] = JsonPrimitive(updatedAt)
        this[DISPLAY_UPDATED_AT] = JsonPrimitive(displayUpdatedAt)
    }

    private suspend fun applyRemoteRow(change: RemoteChange) {
        val displayUpdatedAt = change.displayUpdatedAt
        when (change.kind) {
            SyncKind.Notebook -> notebooks.upsert(
                NotebookEntity(
                    id = change.id,
                    name = change.raw.requiredString("name"),
                    colorArgb = change.raw.requiredInt("colorArgb"),
                    sortIndex = change.raw.requiredInt("sortIndex"),
                    expanded = change.raw.requiredBoolean("expanded"),
                    createdAt = change.raw.requiredLong("createdAt"),
                    updatedAt = displayUpdatedAt,
                    deletedAt = change.deletedAt,
                ),
            )
            SyncKind.Section -> sections.upsert(
                SectionEntity(
                    id = change.id,
                    notebookId = change.raw.requiredString("notebookId"),
                    name = change.raw.requiredString("name"),
                    colorArgb = change.raw.requiredInt("colorArgb"),
                    sortIndex = change.raw.requiredInt("sortIndex"),
                    createdAt = change.raw.requiredLong("createdAt"),
                    updatedAt = displayUpdatedAt,
                    deletedAt = change.deletedAt,
                ),
            )
            SyncKind.Page -> pages.upsert(
                PageEntity(
                    id = change.id,
                    sectionId = change.raw.requiredString("sectionId"),
                    title = change.raw.requiredString("title"),
                    sortIndex = change.raw.requiredInt("sortIndex"),
                    preview = change.raw.requiredString("preview"),
                    createdAt = change.raw.requiredLong("createdAt"),
                    updatedAt = displayUpdatedAt,
                    deletedAt = change.deletedAt,
                ),
            )
        }
    }

    private fun parseRemoteChange(raw: JsonObject): RemoteChange? {
        val kind = SyncKind.fromWire(raw.requiredString("kind")) ?: return null
        val version = raw.requiredLong("version")
        require(version >= 1) { "server version must be positive" }
        // Validate the complete known shape before a transaction begins, so malformed server data
        // is reported as a protocol failure and cannot leave the cursor half-applied.
        raw.requiredString("id")
        raw.requiredLong("updatedAt")
        raw.optionalLong("deletedAt")
        raw.requiredInt("sortIndex")
        raw.requiredLong("createdAt")
        when (kind) {
            SyncKind.Notebook -> {
                raw.requiredString("name")
                raw.requiredInt("colorArgb")
                raw.requiredBoolean("expanded")
            }
            SyncKind.Section -> {
                raw.requiredString("notebookId")
                raw.requiredString("name")
                raw.requiredInt("colorArgb")
            }
            SyncKind.Page -> {
                raw.requiredString("sectionId")
                raw.requiredString("title")
                raw.requiredString("preview")
            }
        }
        return RemoteChange(
            kind = kind,
            id = raw.requiredString("id"),
            version = version,
            deletedAt = raw.optionalLong("deletedAt"),
            updatedAt = raw.requiredLong("updatedAt"),
            displayUpdatedAt = raw[DISPLAY_UPDATED_AT]?.jsonPrimitive?.longOrNull
                ?: raw.requiredLong("updatedAt"),
            raw = raw,
        )
    }

    private data class RemoteChange(
        val kind: SyncKind,
        val id: String,
        val version: Long,
        val deletedAt: Long?,
        val updatedAt: Long,
        val displayUpdatedAt: Long,
        val raw: JsonObject,
    ) {
        fun asEntityState() = SyncEntityStateEntity(
            kind = kind.wire,
            entityId = id,
            serverVersion = version,
            serverJson = raw.toString(),
        )
    }

    @Serializable
    private data class PendingBatch(
        val batchId: String,
        val changes: List<PendingChange>,
    )

    @Serializable
    private data class PendingChange(
        val kind: String,
        val id: String,
        val generation: Long,
        val updatedAt: Long,
        val parentId: String?,
        val payload: JsonObject,
    ) {
        fun asAcceptedState(version: Long): SyncEntityStateEntity {
            val accepted = payload.toMutableMap().apply {
                remove("baseVersion")
                this["version"] = JsonPrimitive(version)
            }
            return SyncEntityStateEntity(kind, id, version, JsonObject(accepted).toString())
        }
    }

    /**
     * [rank] is depth in the hierarchy, and matches the `kind_rank` the server orders a delta by
     * (`viveCServer/internal/store/synctables.go`). Applying in this order is what keeps a child
     * from reaching Room before the row its foreign key points at.
     */
    private enum class SyncKind(val wire: String, val rank: Int) {
        Notebook("notebook", 0),
        Section("section", 1),
        Page("page", 2);

        companion object {
            fun fromWire(value: String): SyncKind? = entries.firstOrNull { it.wire == value }
        }
    }

    private sealed interface PhaseResult {
        data class Done(val count: Int) : PhaseResult
        data class ConflictDone(val count: Int, val conflicts: Int) : PhaseResult
        data class Stop(val result: SyncRunResult) : PhaseResult
    }

    private fun ServerResult.Failed.asSyncResult(): SyncRunResult = if (retryable) {
        SyncRunResult.Retryable(reason)
    } else {
        SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse)
    }

    private fun decodeObject(encoded: String): JsonObject =
        hierarchyJson.parseToJsonElement(encoded) as? JsonObject
            ?: throw IllegalArgumentException("stored server state is not an object")

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("missing $name")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("missing $name")

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("missing $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("missing $name")

    private fun JsonObject.optionalLong(name: String): Long? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        return value.jsonPrimitive.longOrNull
            ?: throw IllegalArgumentException("invalid $name")
    }

    private companion object {
        const val TAG = "HierarchySync"
        const val MAX_PUSH_CHANGES = 512
        const val MAX_BATCHES_PER_RUN = 1_024
        const val PENDING_BATCH_KEY = "syncPendingHierarchyBatch"

        /**
         * Holds the account id this installation has caught up with, not a boolean: the question is
         * always "caught up with *which* tree", and storing the id makes a stale marker for a
         * previous account unusable rather than merely wrong.
         */
        const val CAUGHT_UP_KEY = "syncFirstPullCompletedFor"
        const val DISPLAY_UPDATED_AT = "viveDisplayUpdatedAt"
    }
}

private val hierarchyJson = Json { ignoreUnknownKeys = true }
