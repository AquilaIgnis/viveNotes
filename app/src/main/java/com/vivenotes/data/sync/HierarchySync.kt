package com.vivenotes.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.vivenotes.data.EraserMode
import com.vivenotes.data.DocumentRevisionPayload
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.LocalMetadataEntity
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.PageRevisionEntity
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.data.db.SyncEntityStateEntity
import com.vivenotes.data.db.SyncOutboxEntity
import com.vivenotes.data.db.SyncStateEntity
import com.vivenotes.model.DocumentCodecs
import com.vivenotes.model.Outline
import com.vivenotes.model.migrated
import com.vivenotes.model.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** What one complete hierarchy synchronization accomplished. */
data class SyncSummary(
    val pulled: Int,
    val pushed: Int,
    val conflictsResolved: Int,
    /**
     * Attachment *bytes* moved, in either direction — S5's other half.
     *
     * One number rather than two because a picture is the unit a reader cares about and the
     * direction is not: what the count answers is "why did that run take a minute", and both
     * answers are "it was carrying photographs". The change counts above never include them; a
     * picture's metadata row is one `pulled`/`pushed` like any other entity, and its megabytes go
     * up and down a route of their own.
     */
    val pictures: Int = 0,
)

enum class PermanentSyncFailure {
    InvalidServerResponse,

    /**
     * The server holds a change of a kind this build cannot store, so the cursor cannot advance past
     * it without losing it. Only an upgrade clears this.
     */
    UnsupportedKind,
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
 * Offline-first synchronization for the hierarchy and document kinds OpenAPI 0.3.0 exposes.
 *
 * Room is always the UI's source of truth. This class snapshots its durable outbox, performs the
 * network request without a database transaction held open, then conditionally acknowledges the
 * exact generations it sent. [run] is serialized because two workers using the same cursor and
 * pending idempotency batch would otherwise be indistinguishable from a lost response.
 */
class HierarchySync(
    private val db: NotesDatabase,
    private val client: SyncTransport = SyncServerClient(),
    /** Where remotely applied ink is announced, so an open canvas can absorb it — IS5. */
    private val remoteInk: RemoteInkSignal = RemoteInkSignal(),
    /**
     * Attachment bytes, which never travel through the change protocol — S5.
     *
     * Required rather than optional: a build that synced documents without their pictures would
     * push pages the server refuses (`missing_blob`) and pull pages it cannot draw, and both look
     * like a working sync from the outside.
     */
    private val blobs: AttachmentBlobSync,
) {

    private val sync = db.syncDao()
    private val metadata = db.localMetadataDao()
    private val notebooks = db.notebookDao()
    private val sections = db.sectionDao()
    private val pages = db.pageDao()
    private val contents = db.pageContentDao()
    private val revisions = db.pageRevisionDao()
    private val inkStrokes = db.inkStrokeDao()
    private val inkErases = db.inkEraseDao()
    private val inkMoves = db.inkMoveDao()
    private val inkText = db.inkTextDao()
    private val attachments = db.attachmentDao()
    private val mutex = Mutex()

    /**
     * Pages this run has written remote ink into, held until the transaction carrying them commits.
     *
     * A plain set because [run] is serialized by [mutex] and nothing outside a run ever touches it.
     */
    private val remoteInkPages = mutableSetOf<String>()

    /**
     * Digests this run has given up on delivering, and everything that names one must stop naming
     * it — see [undeliverable].
     *
     * Cleared at the start of every run because "this device cannot produce those bytes" is a fact
     * about a moment: an import, a `.vive` restore or a download from another device puts the file
     * back, and the next run should try again rather than carry a verdict from an hour ago.
     */
    private val undeliverableBlobs = mutableSetOf<String>()

    /** Digests already re-uploaded for a `missing_blob` this run, so one cannot be chased forever. */
    private val repairedBlobs = mutableSetOf<String>()

    /**
     * Bodies written by this transaction whose pictures have still to be counted. A plain list for
     * the reason [remoteInkPages] is a plain set: a run is serialized and nothing outside one ever
     * touches it.
     */
    private val pictureRecounts = mutableListOf<PictureRecount>()

    /**
     * Whether a download pass left something behind — a partition mid-transfer, a token that had
     * just been revoked.
     *
     * True to begin with, so the first run of a process looks once: a picture whose download was
     * interrupted by the app being closed has no other way to be noticed, since the row that
     * schedules it arrived in a pull that will never be replayed.
     */
    private var downloadsOutstanding = true

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
            undeliverableBlobs.clear()
            repairedBlobs.clear()

            var pulled = 0
            var pushed = 0
            var conflicts = 0
            var pictures = 0

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
                    pictures += push.pictures
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

            // After the push and never after the pull. A device that was offline while another one
            // moved a notebook to the cloud may be holding an edit nobody else has, and evicting on
            // the pull would destroy it; by here those bytes have gone up.
            enforceCloudOnly()

            // **Last, after this device's own writes have gone out.** A first sync of a
            // photographed notebook is tens of megabytes of pictures, and putting them ahead of the
            // push would hold a page somebody just typed behind the pictures on a page nobody has
            // opened. Nothing else in a run waits on bytes, and a picture that does not arrive this
            // minute arrives the next.
            //
            // Skipped entirely on an idle run, which SD6 requires to cost one `GET /v1/cursor` and
            // nothing else: with nothing pulled, nothing pushed and no gap left by an earlier pass,
            // there is nothing new to be missing.
            if (pulled > 0 || pushed > 0 || conflicts > 0 || downloadsOutstanding) {
                val downloads = blobs.downloadMissing(account)
                pictures += downloads.downloaded
                downloadsOutstanding = downloads.workRemains
            }

            SyncRunResult.Succeeded(SyncSummary(pulled, pushed, conflicts, pictures))
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
        } finally {
            // The phases publish as they commit; this is for the paths that leave early — a pull
            // that applied rows and then stopped on the row after them still wrote ink somebody is
            // looking at, and a set left full here would announce it on the next run instead.
            publishRemoteInk()
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
            sync.enqueueAllPageContents()
            // On a drawn corpus this is by far the largest of the six — one row per stroke, per
            // erase, per lasso — which is what makes connecting an already-used installation take a
            // while once and nothing thereafter.
            sync.enqueueAllInkStrokes()
            sync.enqueueAllInkErases()
            sync.enqueueAllInkMoves()
            // Metadata rows only. Their bytes are offered to the server by the push that names
            // them, one `HEAD` and at most one `PUT` per picture, however many pages show it.
            sync.enqueueAllAttachments()
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

            // The other half of the response: ids the account erased for good in the same window,
            // which is the only way a device that is not pushing ever hears about one. Read out
            // here, beside the changes, because both are applied under the one cursor below and a
            // window applied by halves is a window this device would never be asked for again.
            val purged = try {
                page.purges.map { purge ->
                    purge.requiredString("kind") to purge.requiredString("id")
                }
            } catch (malformed: IllegalArgumentException) {
                return PhaseResult.Stop(
                    SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse),
                )
            }
            if (purged.isNotEmpty()) {
                Log.i(TAG, "Pull carried ${purged.size} purge(s) the account erased for good")
            }

            // A kind this build does not know stops the run, and stops it *before* the cursor moves.
            //
            // `parseRemoteChange` returns null for an unrecognised kind, and dropping those rows
            // while committing the cursor is silent, permanent data loss: the cursor is a promise
            // that everything below it has been applied, so the next pull starts above rows this
            // device skipped and never asks for them again. Found on 2026-08-17 for real — a tablet
            // still running the build before ink pulled a delta carrying 67 strokes, advanced past
            // them, and reported itself caught up while a page it shows was missing every stroke
            // another device had drawn on it.
            //
            // Refusing to advance turns that into a device that stops syncing until it is upgraded,
            // which is the honest failure: recoverable by installing a newer build, where losing the
            // rows is not recoverable by anything.
            if (parsed.size != page.changes.size) {
                Log.e(
                    TAG,
                    "Pull carried ${page.changes.size - parsed.size} change(s) of a kind this " +
                        "build cannot store; the cursor stays at $cursor until this device is upgraded",
                )
                return PhaseResult.Stop(SyncRunResult.Failed(PermanentSyncFailure.UnsupportedKind))
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

            val discardedPictures = mutableListOf<String>()
            db.withTransaction {
                sync.setApplyingRemote(true)
                applicable.forEach { change -> applyPulledChange(change) }
                invalidateInkText(remoteInkPages)
                applyPictureCounts()
                // Last, and after both passes above. A purge is the account's final word on an id,
                // so anything this same window wrote under one of these notebooks is written first
                // and taken away here rather than the other way round — and `invalidateInkText`
                // *inserts* a row keyed by page, which a page this had already deleted would refuse.
                purged.forEach { (kind, id) -> discardedPictures += applyPurgeOrRemap(kind, id) }
                // Only when nothing is being held. The cursor is a promise that everything below it
                // has been applied, so advancing it past a row still waiting for its parent would
                // lose that row for good — the next pull starts above it.
                if (orphans.isEmpty()) sync.setCursor(page.cursor)
                sync.setApplyingRemote(false)
            }
            // Once the transaction has committed, for the reason the push phase discards there.
            if (discardedPictures.isNotEmpty()) blobs.discard(discardedPictures)
            // Per page of the delta rather than at the end of the run: a first reconcile can carry
            // tens of thousands of strokes, and a canvas that shows them as they land beats one that
            // stays empty until the whole corpus has been written.
            publishRemoteInk()
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
            // Neither has a parent: a notebook is the root, and a picture belongs to no one page.
            SyncKind.Notebook, SyncKind.Attachment -> return true
            SyncKind.Section -> SyncKind.Notebook
            SyncKind.Page -> SyncKind.Section
            SyncKind.PageContent,
            SyncKind.InkStroke,
            SyncKind.InkErase,
            SyncKind.InkMove,
            -> SyncKind.Page
        }
        val parentID = when (change.kind) {
            SyncKind.Notebook, SyncKind.Attachment -> return true
            SyncKind.Section -> change.raw.requiredString("notebookId")
            SyncKind.Page -> change.raw.requiredString("sectionId")
            SyncKind.PageContent,
            SyncKind.InkStroke,
            SyncKind.InkErase,
            SyncKind.InkMove,
            -> change.raw.requiredString("pageId")
        }
        if (applying.contains(parentKind.wire + ":" + parentID)) return true
        return when (parentKind) {
            SyncKind.Notebook -> notebooks.byId(parentID) != null
            SyncKind.Section -> sections.byId(parentID) != null
            SyncKind.Page -> pages.byId(parentID) != null
            // A stroke is never a parent. An operation's targets are ids it replays against, and one
            // naming nothing stored is normal: the stroke may arrive later, or have been purged
            // after seven days on the device that made the operation. Waiting for it would stall the
            // cursor on a row that can never come.
            SyncKind.PageContent,
            SyncKind.InkStroke,
            SyncKind.InkErase,
            SyncKind.InkMove,
            SyncKind.Attachment,
            -> false
        }
    }

    private suspend fun applyPulledChange(remote: RemoteChange) {
        sync.putEntityState(remote.asEntityState())

        // A server row newer than this device's base wins the plain whole-entity OCC decision.
        // Device wall clocks cannot answer causality: a stale editor can autosave an old document
        // after another device deleted an outline, giving the old body a later updatedAt and
        // resurrecting it. Discarding the dirty whole row loses a simultaneous offline edit, which
        // is the deliberate interim trade-off until outline-keyed three-way merge can preserve both.
        applyRemoteRow(remote)
        sync.deleteOutbox(remote.kind.wire, remote.id)
    }

    /**
     * Applies one purge: the account erased this entity for good, so this device drops it and
     * everything beneath it and stops offering any of it to the server.
     *
     * Two routes carry the same news. The `purges` array of a pull is the ordinary one; a `purged`
     * rejection is what a device sees when its own push got there before that pull did, and it is
     * the one that matters, because a device holding queued work under an erased notebook is
     * otherwise refused for ever — every push, every run, with nothing it can do about it.
     *
     * Applying it is not negotiable and nothing is asked. The id is retired on the server: it will
     * not store anything under it again, so there is no version to reconcile, nothing to merge, and
     * no later moment at which this could be reconsidered. Whatever this device holds under the id
     * and never uploaded goes with it — the operator erased the notebook knowing what was in it.
     *
     * **A hard delete, never a tombstone.** A tombstone is a change, so it would be queued and
     * pushed, and the server refuses one naming a purged id exactly as it refuses an edit. There is
     * nothing left to be told and nobody left to tell.
     *
     * Returns the digests nothing on this device still reaches, for the caller to discard once the
     * enclosing transaction has committed. Must run inside a transaction with `applyingRemote` set:
     * every delete below would otherwise fire the outbox triggers and queue a push of the deletion.
     */
    private suspend fun applyPurge(kind: String, id: String): List<String> {
        if (kind != SyncKind.Notebook.wire) {
            // Only `notebook` is erased this way today, and only a notebook's subtree is something
            // this build knows how to take apart. A kind a later server purges still gets the half
            // that is always right — the queued work goes, so the push stops repeating a verdict
            // this build cannot act on — and says so, rather than silently doing nothing.
            Log.w(TAG, "Purge names \"$kind\", which this build cannot cascade; dropping only its queued work")
            sync.deleteOutbox(kind, id)
            sync.deleteEntityState(kind, id)
            return emptyList()
        }

        // Read before the delete, and from the documents rather than from `refCount`, for the
        // reasons [picturesReachedOnlyBy] gives. A notebook that is already gone from this device —
        // a purge pulled twice, or one for a notebook this device never held — answers with no
        // pages, no pictures and a delete that removes nothing, which is the whole of what it should
        // do: the bookkeeping below is then the only thing left to clear.
        val pageIds = pages.allInNotebook(id).map { it.id }
        val orphaned = if (pageIds.isEmpty()) emptyList() else picturesReachedOnlyBy(pageIds)

        // Sections, pages, bodies, revisions, ink and the derived text all cascade from here. Only
        // the pictures do not, because a picture belongs to no one notebook.
        notebooks.hardDelete(id)
        orphaned.chunked(SQLITE_BIND_CHUNK).forEach { attachments.deleteByIds(it) }

        // The two tables that hold ids rather than rows, so neither goes with the cascade. Queued
        // work naming a notebook that no longer exists is what the next push would fail on, and a
        // state row for one is a claim about a version of something that cannot exist again.
        sync.pruneOrphanedOutbox()
        sync.pruneOrphanedEntityStates()

        Log.i(TAG, "Notebook $id was erased for good by the account; removed it and its ${pageIds.size} page(s)")
        return orphaned
    }

    /**
     * Routes one purge verdict to the only two things it can honestly mean.
     *
     * A `purged` verdict says the id is retired, never how this device came to be holding something
     * under it, and the two ways are opposites:
     *
     *  - **This device still had the notebook.** The account erased it and this copy is a straggler,
     *    so it goes — [applyPurge], unchanged. Anything else would make Permanently delete undoable
     *    by whichever device happened to be offline when it was pressed, which is the failure
     *    `syncengine/engine.go` refuses a resurrecting push to prevent.
     *  - **Someone imported a `.vive` archive of it since.** A bundle carries the notebook's stable
     *    id, so importing one the account erased re-creates precisely the retired id and the push
     *    that follows is refused for ever. The bytes are not a straggler — they were chosen, from a
     *    file, after the erasure — and the id is the only thing wrong with them.
     *
     * [NotebookTransferManager] marks the second case at import and the first accepted push clears
     * the mark, so the window in which a purge is answered by [remapPurgedImport] is exactly the
     * window in which the notebook has never been on the server.
     */
    private suspend fun applyPurgeOrRemap(kind: String, id: String): List<String> {
        if (kind == SyncKind.Notebook.wire &&
            metadata.value(NotesRepository.importedNotebookKey(id)) != null &&
            remapPurgedImport(id)
        ) {
            return emptyList()
        }
        return applyPurge(kind, id)
    }

    /**
     * Carries a freshly imported notebook out from under an id the account retired.
     *
     * Only the notebook id moves. `purges` is keyed by entity id and the schema is explicit that
     * only `notebook` is ever written to it, so every section, page, body, revision and stroke below
     * is a perfectly acceptable id that was refused `missing_parent` for one reason: its parent was
     * gone. Give them a parent the server will take and they push as they are. `notebookId` lives on
     * `sections` alone, which is why the whole subtree moves in one `UPDATE`.
     *
     * Order is forced by the schema: insert the new row, repoint the sections, and only then drop
     * the old one — `sections.notebookId` is `ON DELETE CASCADE`, so deleting first would take the
     * subtree with it.
     *
     * The mark travels to the new id rather than being dropped, so this is idempotent: if the answer
     * to the retry were somehow `purged` again, the notebook moves again instead of being erased.
     *
     * Returns false when the notebook is already gone — a purge pulled twice, or one naming a
     * notebook this device never held — leaving [applyPurge]'s bookkeeping to run as it would have.
     */
    private suspend fun remapPurgedImport(id: String): Boolean {
        val notebook = notebooks.byId(id) ?: return false
        val replacementId = newId()

        notebooks.upsert(notebook.copy(id = replacementId))
        sections.repointNotebook(id, replacementId)
        notebooks.hardDelete(id)

        // The retired id stops being offered: its queued change and its version claim both go. The
        // replacement is queued explicitly because this runs with `applyingRemote` set, which is
        // what suppresses the outbox triggers that would otherwise have noticed the insert above.
        sync.deleteOutbox(SyncKind.Notebook.wire, id)
        sync.deleteEntityState(SyncKind.Notebook.wire, id)
        sync.enqueueIfAbsent(SyncKind.Notebook.wire, replacementId)
        // Queued work naming the retired id — including the `missing_parent` re-queue this same
        // response provoked, which cannot know the parent it asked for is the thing being retired.
        sync.pruneOrphanedOutbox()
        sync.pruneOrphanedEntityStates()

        metadata.delete(NotesRepository.importedNotebookKey(id))
        metadata.put(
            LocalMetadataEntity(
                NotesRepository.importedNotebookKey(replacementId),
                "${System.currentTimeMillis()}",
            ),
        )

        // Where the archive's notebook went, so re-importing the same file updates this copy rather
        // than installing a second one — [NotebookTransferManager] reads it before it decides what
        // the bundle collides with. Repointed first and recorded second, which is what makes the map
        // transitive: a notebook moved, purged again and re-imported is moved twice, and the archive
        // id recorded against the first replacement has to end up naming the second.
        metadata.repointValues(NotesRepository.IMPORT_REMAP_KEY_PREFIX, id, replacementId)
        metadata.put(LocalMetadataEntity(NotesRepository.importRemapKey(id), replacementId))

        Log.i(
            TAG,
            "Notebook $id was erased for good by the account, but was imported since; " +
                "moved the imported copy to $replacementId and queued it",
        )
        return true
    }

    private suspend fun pushOutbox(account: SyncAccount): PhaseResult {
        var pushed = 0
        var conflicts = 0
        var pictures = 0
        var batchCount = 0

        while (batchCount++ < MAX_BATCHES_PER_RUN) {
            val pending = loadOrCreatePendingBatch()
                ?: return PhaseResult.ConflictDone(pushed, conflicts, pictures)

            // The bytes go up before the change that names them. The server refuses it otherwise —
            // that refusal is what makes reachability knowable to it at all — so this is not an
            // optimisation of the `missing_blob` path below but the ordinary way a picture syncs.
            when (val preflight = uploadBlobsFor(account, pending.changes)) {
                is BlobPhase.Ready -> pictures += preflight.uploaded
                BlobPhase.Rebuild -> {
                    // A digest in this batch cannot be delivered. The batch was serialized naming
                    // it, so it is thrown away and built again without it rather than sent to be
                    // rejected: the durable batch is what a retry re-sends byte for byte.
                    metadata.delete(PENDING_BATCH_KEY)
                    continue
                }
                is BlobPhase.Stop -> return PhaseResult.Stop(preflight.result)
            }

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
            // Handled after the transaction: clearing one means uploading megabytes, and a database
            // transaction held open across a network transfer would block every writer on the
            // device for the length of it.
            val missingBlobs = mutableListOf<PendingChange>()
            // What this response says the account erased for good, applied after the loop below.
            val purged = mutableListOf<Pair<String, String>>()
            val discardedPictures = mutableListOf<String>()
            db.withTransaction {
                sync.setApplyingRemote(true)
                response.applied.forEach { applied ->
                    val sent = sentByKey.getValue(applied.kind to applied.id)
                    sync.putEntityState(sent.asAcceptedState(applied.version))
                    sync.deleteOutboxGeneration(sent.kind, sent.id, sent.generation)
                    // The account now holds these bytes, so an import of them is no longer the only
                    // copy and its marker has done its job. Clearing it here is what keeps
                    // [remapPurgedImport] narrow: a notebook that ever synced is erased by a later
                    // purge like any other, rather than coming back under a new id years on.
                    if (applied.kind == SyncKind.Notebook.wire) {
                        metadata.delete(NotesRepository.importedNotebookKey(applied.id))
                    }
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
                        // The one rejection a client can always clear on its own. The queued
                        // generation is deliberately left where it is: the entity is unchanged and
                        // correct, it is the server that is missing the bytes it names, so the next
                        // batch re-sends exactly these fields once they are up.
                        "missing_blob" -> missingBlobs += sent
                        // The account erased this entity for good, and unlike every rejection above
                        // it this one is not about the change: the entity may be perfectly valid and
                        // its version right. The id is retired, so re-sending it — as an edit, or as
                        // the tombstone a local delete would queue — earns the same answer for ever.
                        // Not a failure, then. Standing still here *is* the failure this clears.
                        "purged" -> purged += rejected.kind to rejected.id
                        "too_large" -> permanent = PermanentSyncFailure.ChangeTooLarge
                        "malformed" -> permanent = PermanentSyncFailure.MalformedChange
                        // A reason no build this old has a branch for, which is what a server newer
                        // than this app looks like. The whole run stops rather than the entity being
                        // dropped: an unrecognised verdict is one this device cannot honour, and
                        // pretending otherwise is how a rejection becomes silent data loss.
                        else -> permanent = PermanentSyncFailure.InvalidServerResponse
                    }
                }
                invalidateInkText(remoteInkPages)
                applyPictureCounts()
                // After the whole loop, and after both passes above.
                //
                // After the loop, because a child of a purged notebook is rejected `missing_parent`
                // in the same response — the server cascaded its parent away — and `enqueueParent`
                // cannot know that the parent it is queueing is the very thing being erased. Purging
                // last, with the queue pruned as its final act, leaves nothing behind whichever
                // order the server listed the two in.
                //
                // After the passes, because both reach rows by page id and `invalidateInkText`
                // *inserts* one, which a page this had already deleted would refuse.
                purged.forEach { (kind, id) -> discardedPictures += applyPurgeOrRemap(kind, id) }
                metadata.delete(PENDING_BATCH_KEY)
                sync.setApplyingRemote(false)
            }
            // A conflict the server won writes its row over this device's, ink included.
            publishRemoteInk()
            // Once the transaction has committed, so a rollback can never leave a surviving row
            // pointing at bytes that are gone — the ordering [AttachmentBlobSync.discard] documents.
            if (discardedPictures.isNotEmpty()) blobs.discard(discardedPictures)

            permanent?.let { return PhaseResult.Stop(SyncRunResult.Failed(it)) }

            if (missingBlobs.isNotEmpty()) {
                when (val repair = repairMissingBlobs(account, missingBlobs)) {
                    is BlobPhase.Ready -> pictures += repair.uploaded
                    // Never produced here: the rejected entities are still queued, so the next turn
                    // of this loop snapshots them again with whatever the repair could not deliver
                    // already dropped. Named to keep the branch exhaustive.
                    BlobPhase.Rebuild -> Unit
                    is BlobPhase.Stop -> return PhaseResult.Stop(repair.result)
                }
            }
        }

        return PhaseResult.Stop(SyncRunResult.Failed(PermanentSyncFailure.MissingParent))
    }

    /**
     * Puts the bytes this batch is about to name on the server.
     *
     * Reads the digests back out of the serialized batch rather than out of Room, because that is
     * what will actually be sent: a `pageContent` snapshot has already had this run's undeliverable
     * digests dropped from its `blobRefs`, and an `attachment` change *is* its digest.
     *
     * A picture the server already holds costs nothing here — the state row that proves it is the
     * one the push wrote when it was first accepted — so a steady-state run makes no byte requests
     * at all, and a first sync makes one `HEAD` per picture and one `PUT` per picture it has.
     */
    private suspend fun uploadBlobsFor(
        account: SyncAccount,
        changes: List<PendingChange>,
    ): BlobPhase {
        var uploaded = 0
        var rebuild = false
        changes.flatMap(::digestsNamedBy).distinct().forEach { digest ->
            if (digest in undeliverableBlobs) {
                // Given up on earlier in this same run. A `pageContent` snapshot has already had it
                // filtered out, so this is reached by a change serialized before that verdict — the
                // batch is rebuilt rather than sent to be rejected for a reason already known.
                rebuild = true
                return@forEach
            }
            when (val presence = blobs.ensureUploaded(account, digest)) {
                is BlobPresence.Present -> if (presence.uploaded) uploaded++
                BlobPresence.Undeliverable -> {
                    markUndeliverable(digest)
                    rebuild = true
                }
                is BlobPresence.Stopped -> return BlobPhase.Stop(presence.result)
            }
        }
        return if (rebuild) BlobPhase.Rebuild else BlobPhase.Ready(uploaded)
    }

    /**
     * Answers a `missing_blob` rejection the only way it can be answered: by uploading.
     *
     * Forced past every memo, because the server has just said it does not hold these bytes — which
     * is also how a server whose blob volume was lost, but whose database survived, is repaired by
     * the first device that pushes a change naming a picture.
     *
     * [repairedBlobs] is the loop stop. A digest uploaded successfully and then rejected for the
     * same reason again means the two sides disagree about something this code cannot fix, and
     * chasing it would spend a thousand round trips per run doing so.
     */
    private suspend fun repairMissingBlobs(
        account: SyncAccount,
        rejected: List<PendingChange>,
    ): BlobPhase {
        var uploaded = 0
        rejected.flatMap(::digestsNamedBy).distinct().forEach { digest ->
            if (digest in undeliverableBlobs) return@forEach
            if (!repairedBlobs.add(digest)) {
                Log.e(TAG, "Attachment $digest was uploaded and still rejected; dropping the reference")
                markUndeliverable(digest)
                return@forEach
            }
            when (val presence = blobs.ensureUploaded(account, digest, force = true)) {
                is BlobPresence.Present -> if (presence.uploaded) uploaded++
                BlobPresence.Undeliverable -> markUndeliverable(digest)
                is BlobPresence.Stopped -> return BlobPhase.Stop(presence.result)
            }
        }
        return BlobPhase.Ready(uploaded)
    }

    /**
     * Stops naming a digest this device cannot deliver, everywhere it is named.
     *
     * The alternative is a wedged account, and it is worth being explicit about the trade: a page
     * that keeps a picture nobody can supply would be rejected on every push for ever, and it is
     * pushed in the same batches as everything else the user writes — so one broken picture would
     * stop notebooks, sections and text from syncing at all. Dropping the reference costs a picture
     * that is *already* broken on this device the ability to be repaired from here; another device
     * that still has the bytes uploads them and the reference comes back with its page.
     *
     * An `attachment` row is removed from the outbox outright rather than left dirty, because its
     * id is the digest: there is no version of that row the server would take.
     */
    private suspend fun markUndeliverable(digest: String) {
        if (!undeliverableBlobs.add(digest)) return
        Log.e(TAG, "Attachment $digest cannot be delivered; pages will be pushed without it")
        sync.deleteOutbox(SyncKind.Attachment.wire, digest)
    }

    /** The attachment digests one queued change will name on the wire. */
    private fun digestsNamedBy(change: PendingChange): List<String> = when (change.kind) {
        SyncKind.Attachment.wire -> listOf(change.id)
        SyncKind.PageContent.wire ->
            (change.payload[BLOB_REFS] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        else -> emptyList()
    }

    private suspend fun resolveVersionConflict(sent: PendingChange, currentJson: JsonObject?) {
        if (currentJson == null) {
            // The server no longer has the row. Base the still-dirty local row at zero next time.
            sync.deleteEntityState(sent.kind, sent.id)
            return
        }
        val current = parseRemoteChange(currentJson)
            ?: throw IllegalArgumentException("unsupported current kind")
        sync.putEntityState(current.asEntityState())

        // The rejected payload was based on an older server version, including a new local edit
        // made while this request was in flight. Retrying it against `current.version` would turn a
        // stale whole document into a fresh accepted write and can resurrect remotely deleted text.
        applyRemoteRow(current)
        sync.deleteOutbox(sent.kind, sent.id)
    }

    private suspend fun enqueueParent(sent: PendingChange) {
        val parentId = sent.parentId ?: return
        val parentKind = when (sent.kind) {
            SyncKind.Section.wire -> SyncKind.Notebook.wire
            SyncKind.Page.wire -> SyncKind.Section.wire
            SyncKind.PageContent.wire,
            SyncKind.InkStroke.wire,
            SyncKind.InkErase.wire,
            SyncKind.InkMove.wire,
            -> SyncKind.Page.wire
            // An attachment has no parent, so `missing_parent` cannot name one; it never reaches
            // here because `parentId` is null for that kind.
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
        val batchId = UUID.randomUUID().toString()
        val changes = mutableListOf<PendingChange>()
        // A row-count cap stopped bounding the request once pageContent joined the protocol, so the
        // exact compact JSON shape SyncServerClient writes is measured instead and the batch ends
        // before the row that would cross 4 MiB.
        //
        // **Accumulated, because encoding the candidate batch per row is quadratic.** That is what it
        // used to do, and on a first upload — where every batch is a full 512 rows — it encoded
        // ~131,000 rows to admit 512, which `InkSyncCostTest` measured as 12.5 ms per stroke, most of
        // the run. The sum is exact rather than an estimate: the envelope is
        // `{"batchId":"…","changes":[…]}`, so its length is the empty envelope plus each element's
        // own length plus one comma between elements, and UTF-8 concatenates without interacting.
        var encodedSize = encodedPushSize(batchId, emptyList())
        for (row in rows) {
            val change = snapshot(row)
            val addition = change.payload.toString().encodeToByteArray().size +
                if (changes.isEmpty()) 0 else 1
            // A first row is still admitted so the transport/server can return the permanent size
            // verdict instead of leaving it queued behind an empty batch.
            if (changes.isNotEmpty() && encodedSize + addition > MAX_SYNC_PUSH_BYTES) break
            changes += change
            encodedSize += addition
        }
        val pending = PendingBatch(
            batchId = batchId,
            changes = changes,
        )
        metadata.put(
            LocalMetadataEntity(
                PENDING_BATCH_KEY,
                hierarchyJson.encodeToString(PendingBatch.serializer(), pending),
            ),
        )
        pending
    }

    private fun encodedPushSize(batchId: String, changes: List<PendingChange>): Int = JsonObject(
        linkedMapOf(
            "batchId" to JsonPrimitive(batchId),
            "changes" to JsonArray(changes.map(PendingChange::payload)),
        ),
    ).toString().encodeToByteArray().size

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
                // The shelf, and whether the bytes are still here. Both written **always**, as
                // `JsonNull` when null, rather than omitted: `base` starts from the retained
                // `serverJson`, so an omitted key leaves the previous value standing and reopening a
                // notebook — or bringing one back from the cloud — would never propagate.
                //
                // The server carries them as unrecognised properties of `NotebookFields`, which
                // `additionalProperties: true` allows. That same retention is why a build without
                // the shelf pushing a rename does not reopen the notebook everywhere: it sends back
                // the value it never parsed. `memory/closedNotebooksPlan.md`.
                base["closedAt"] = row.closedAt?.let(::JsonPrimitive) ?: JsonNull
                base["cloudOnlyAt"] = row.cloudOnlyAt?.let(::JsonPrimitive) ?: JsonNull
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
            SyncKind.PageContent -> {
                val row = contents.byId(outbox.entityId)
                    ?: throw IllegalStateException("dirty page content disappeared")
                val documentBytes = row.docJson.encodeToByteArray()
                val digest = MessageDigest.getInstance("SHA-256").digest(documentBytes)
                base.putEnvelope(null, maxOf(row.updatedAt, outbox.changedAt), row.updatedAt)
                base["pageId"] = JsonPrimitive(row.pageId)
                base["doc"] = JsonPrimitive(Base64.getEncoder().encodeToString(documentBytes))
                base["docSha256"] = JsonPrimitive(Base64.getEncoder().encodeToString(digest))
                base["format"] = JsonPrimitive(row.format)
                base["enc"] = JsonPrimitive(PLAIN_ENCODING)
                // SD7: the server cannot read the document, so this is the only way it can know
                // which pictures the page still shows — and therefore the only way it can ever free
                // one. Every push replaces the whole set, so removing a picture from a page is
                // enough to release it. Always written, never inherited from the stored server row:
                // a stale set left over from a previous push would keep bytes alive that this page
                // no longer draws.
                base[BLOB_REFS] = JsonArray(
                    pictureIdsIn(row.docJson, row.format)
                        .asSequence()
                        .filter(::isBlobDigest)
                        .filterNot(undeliverableBlobs::contains)
                        .distinct()
                        .sorted()
                        .take(MAX_BLOB_REFS)
                        .map(::JsonPrimitive)
                        .toList(),
                )
                row.pageId
            }
            SyncKind.InkStroke -> {
                val row = inkStrokes.byIds(listOf(outbox.entityId)).firstOrNull()
                    ?: throw IllegalStateException("dirty ink stroke disappeared")
                base.putInkEnvelope(row.deletedAt, row.createdAt, outbox.changedAt)
                base["pageId"] = JsonPrimitive(row.pageId)
                // `drawOrder`, not `seq`: the envelope owns `seq`, and a stroke sending its draw
                // order under that name would have it stripped and stored nowhere — ink would
                // arrive and paint in the wrong order with nothing failing.
                base["drawOrder"] = JsonPrimitive(row.seq)
                base["brushFamily"] = JsonPrimitive(row.brushFamily)
                base["brushVersion"] = JsonPrimitive(row.brushVersion)
                base["sizeDp"] = JsonPrimitive(row.sizeDp)
                base["colorArgb"] = JsonPrimitive(row.colorArgb)
                base["colorFollowsTheme"] =
                    row.colorFollowsTheme?.let(::JsonPrimitive) ?: JsonNull
                base["epsilon"] = JsonPrimitive(row.epsilon)
                base["stabilization"] = JsonPrimitive(row.stabilization)
                base["minX"] = JsonPrimitive(row.minX)
                base["minY"] = JsonPrimitive(row.minY)
                base["maxX"] = JsonPrimitive(row.maxX)
                base["maxY"] = JsonPrimitive(row.maxY)
                base["points"] = JsonPrimitive(encodePoints(row.points))
                base["enc"] = JsonPrimitive(row.enc)
                base["createdAt"] = JsonPrimitive(row.createdAt)
                base["groupId"] = row.groupId?.let(::JsonPrimitive) ?: JsonNull
                row.pageId
            }
            SyncKind.InkErase -> {
                val row = inkErases.byIds(listOf(outbox.entityId)).firstOrNull()
                    ?: throw IllegalStateException("dirty ink erase disappeared")
                base.putInkEnvelope(row.deletedAt, row.createdAt, outbox.changedAt)
                base["pageId"] = JsonPrimitive(row.pageId)
                base["mode"] = JsonPrimitive(row.mode.name)
                base["sizeDp"] = JsonPrimitive(row.sizeDp)
                base["points"] = JsonPrimitive(encodePoints(row.points))
                base["enc"] = JsonPrimitive(row.enc)
                base["createdAt"] = JsonPrimitive(row.createdAt)
                // Read at push time rather than carried with the queued key, and sent whole: the
                // mask without its targets would erase ink drawn later when it is replayed.
                base["targetIds"] = JsonArray(
                    inkErases.targetsForErases(listOf(row.id)).map { JsonPrimitive(it.strokeId) },
                )
                row.pageId
            }
            SyncKind.InkMove -> {
                val row = inkMoves.byIds(listOf(outbox.entityId)).firstOrNull()
                    ?: throw IllegalStateException("dirty ink move disappeared")
                base.putInkEnvelope(row.deletedAt, row.createdAt, outbox.changedAt)
                base["pageId"] = JsonPrimitive(row.pageId)
                base["dxDp"] = JsonPrimitive(row.dxDp)
                base["dyDp"] = JsonPrimitive(row.dyDp)
                base["scaleX"] = JsonPrimitive(row.scaleX)
                base["scaleY"] = JsonPrimitive(row.scaleY)
                base["anchorX"] = JsonPrimitive(row.anchorX)
                base["anchorY"] = JsonPrimitive(row.anchorY)
                base["points"] = JsonPrimitive(encodePoints(row.points))
                base["enc"] = JsonPrimitive(row.enc)
                base["createdAt"] = JsonPrimitive(row.createdAt)
                base["targetIds"] = JsonArray(
                    inkMoves.targetsForMoves(listOf(row.id)).map { JsonPrimitive(it.strokeId) },
                )
                row.pageId
            }
            SyncKind.Attachment -> {
                val row = attachments.byId(outbox.entityId)
                    ?: throw IllegalStateException("dirty attachment disappeared")
                // No `deletedAt` to send and no `updatedAt` column to send it from: an attachment is
                // immutable in every field the protocol carries, so its creation time is the only
                // honest stamp and the envelope's display value is the same number.
                base.putEnvelope(null, maxOf(row.createdAt, outbox.changedAt), row.createdAt)
                base["mimeType"] = JsonPrimitive(row.mimeType)
                base["pixelWidth"] = JsonPrimitive(row.pixelWidth)
                base["pixelHeight"] = JsonPrimitive(row.pixelHeight)
                // Checked against the stored blob by the server on every push and rejected
                // `malformed` if it disagrees, which is why it is read from the row rather than
                // from the file: the two are the same number, and the row is what the bytes were
                // measured as when they were written.
                base["byteCount"] = JsonPrimitive(row.byteCount)
                base["createdAt"] = JsonPrimitive(row.createdAt)
                // `refCount` is deliberately absent — SD7.
                null
            }
        }
        return PendingChange(
            kind = kind.wire,
            id = outbox.entityId,
            generation = outbox.generation,
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

    /**
     * The envelope for a kind that stores no `updatedAt` of its own.
     *
     * The protocol requires the field and the server keeps it, but under plain OCC it is display
     * metadata that nothing here reads back — convergence is the server's version and nothing else.
     * A real column on the ink tables would have to be maintained by every erase, restore, recolour
     * and regroup for a value with no reader, so it is synthesised from what the row does carry.
     * `viveCServer/memory/syncPlan.md` §10 item 8 is answered "no, and here is why".
     */
    private fun MutableMap<String, kotlinx.serialization.json.JsonElement>.putInkEnvelope(
        deletedAt: Long?,
        createdAt: Long,
        changedAt: Long,
    ) {
        val stamp = maxOf(createdAt, deletedAt ?: 0L, changedAt)
        putEnvelope(deletedAt, stamp, stamp)
    }

    private fun encodePoints(points: ByteArray): String =
        Base64.getEncoder().encodeToString(points)

    /**
     * Hands the pages this device just had ink written into to whoever is showing one.
     *
     * **After the transaction, never inside it.** The canvas answers this by reading the page back,
     * so announcing a row that is still uncommitted would have it rebuild from the state before the
     * write. Draining as it publishes is what keeps a rolled-back transaction from announcing rows
     * that were never written — and an announcement that turns out to be empty costs one rebuild
     * that finds Room exactly as the canvas already has it.
     */
    /**
     * Throws away what this device had read out of the pages remote ink just landed on.
     *
     * `ink_text` is a cache of the handwriting on a page, keyed by a generation that every *local*
     * ink write bumps through [com.vivenotes.data.NotesRepository]. Rows written here bypass that —
     * they go to the DAOs directly, deliberately, so the outbox triggers can be suppressed — so
     * without this a page whose ink arrived from another device would keep answering content search
     * with the handwriting it held before. Derived data, so dropping it costs a re-read and nothing
     * else; the bump is what discards a recognition pass already in flight against the old ink.
     *
     * Inside the transaction that wrote the ink: a cache surviving a rolled-back apply would be
     * describing rows that are still there.
     */
    private suspend fun invalidateInkText(pageIds: Collection<String>) {
        if (pageIds.isEmpty()) return
        inkText.deleteForPages(pageIds.toList())
        pageIds.forEach { inkText.bumpGeneration(it) }
    }

    private fun publishRemoteInk() {
        if (remoteInkPages.isEmpty()) return
        val pages = remoteInkPages.toList()
        remoteInkPages.clear()
        remoteInk.record(pages)
    }

    private suspend fun applyRemoteRow(change: RemoteChange) {
        val displayUpdatedAt = change.displayUpdatedAt
        // Recorded here rather than in the pull loop because this is also the server-wins half of a
        // push conflict: both paths write a row this device did not, and an open canvas showing the
        // page has to hear about either. Published only once the enclosing transaction commits.
        when (change.kind) {
            SyncKind.InkStroke, SyncKind.InkErase, SyncKind.InkMove ->
                remoteInkPages += change.raw.requiredString("pageId")
            SyncKind.Notebook,
            SyncKind.Section,
            SyncKind.Page,
            SyncKind.PageContent,
            SyncKind.Attachment,
            -> Unit
        }
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
                    // Optional, not required: a row last written by a build without the shelf
                    // carries neither, and absent is exactly what "open, and on this device" means.
                    closedAt = change.raw.optionalLong("closedAt"),
                    cloudOnlyAt = change.raw.optionalLong("cloudOnlyAt"),
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
            SyncKind.PageContent -> {
                // Read before the write, because the two documents together are what say which
                // pictures this page stopped showing and which it started.
                val replaced = contents.byId(change.id)
                if (change.deletedAt != null) {
                    contents.delete(change.id)
                    pictureRecounts += PictureRecount(before = replaced, after = null)
                } else {
                    val incoming = PageContentEntity(
                        pageId = change.id,
                        docJson = requireNotNull(change.documentJson),
                        updatedAt = displayUpdatedAt,
                        format = change.raw.requiredString("format"),
                    )
                    contents.upsert(incoming)
                    pictureRecounts += PictureRecount(before = replaced, after = incoming)
                }
            }
            // Ink tombstones are upserted like any other field rather than deleting the row: a
            // stroke's `deletedAt` toggles — an erase sets it and an undo clears it again — so the
            // row has to survive to carry the next value.
            SyncKind.InkStroke -> inkStrokes.upsert(
                listOf(
                    InkStrokeEntity(
                        id = change.id,
                        pageId = change.raw.requiredString("pageId"),
                        seq = change.raw.requiredInt("drawOrder"),
                        brushFamily = change.raw.requiredString("brushFamily"),
                        brushVersion = change.raw.requiredInt("brushVersion"),
                        sizeDp = change.raw.requiredFloat("sizeDp"),
                        colorArgb = change.raw.requiredInt("colorArgb"),
                        colorFollowsTheme = change.raw.optionalBoolean("colorFollowsTheme"),
                        epsilon = change.raw.requiredFloat("epsilon"),
                        stabilization = change.raw.requiredInt("stabilization"),
                        minX = change.raw.requiredFloat("minX"),
                        minY = change.raw.requiredFloat("minY"),
                        maxX = change.raw.requiredFloat("maxX"),
                        maxY = change.raw.requiredFloat("maxY"),
                        points = requireNotNull(change.inkPoints),
                        enc = change.raw.optionalString("enc") ?: INK_ENCODING,
                        createdAt = change.raw.requiredLong("createdAt"),
                        groupId = change.raw.optionalString("groupId"),
                        deletedAt = change.deletedAt,
                    ),
                ),
            )
            SyncKind.InkErase -> {
                inkErases.upsert(
                    InkEraseEntity(
                        id = change.id,
                        pageId = change.raw.requiredString("pageId"),
                        mode = eraserModeOf(change.raw.requiredString("mode")),
                        sizeDp = change.raw.requiredFloat("sizeDp"),
                        points = requireNotNull(change.inkPoints),
                        enc = change.raw.optionalString("enc") ?: INK_ENCODING,
                        createdAt = change.raw.requiredLong("createdAt"),
                        deletedAt = change.deletedAt,
                    ),
                )
                // Replaced wholesale, and never filtered against the strokes this device holds. An
                // id naming nothing here is inert at replay, and dropping it would silently un-erase
                // that stroke the moment it arrived — the target set is the operation's payload, not
                // a set of references.
                inkErases.deleteTargetsForErases(listOf(change.id))
                inkErases.insertTargetsIfAbsent(
                    change.targetIds.map { InkEraseTargetEntity(change.id, it) },
                )
            }
            SyncKind.InkMove -> {
                inkMoves.upsert(
                    InkMoveEntity(
                        id = change.id,
                        pageId = change.raw.requiredString("pageId"),
                        dxDp = change.raw.requiredFloat("dxDp"),
                        dyDp = change.raw.requiredFloat("dyDp"),
                        scaleX = change.raw.optionalFloat("scaleX") ?: 1f,
                        scaleY = change.raw.optionalFloat("scaleY") ?: 1f,
                        anchorX = change.raw.optionalFloat("anchorX") ?: 0f,
                        anchorY = change.raw.optionalFloat("anchorY") ?: 0f,
                        points = requireNotNull(change.inkPoints),
                        enc = change.raw.optionalString("enc") ?: INK_ENCODING,
                        createdAt = change.raw.requiredLong("createdAt"),
                        deletedAt = change.deletedAt,
                    ),
                )
                inkMoves.deleteTargetsForMoves(listOf(change.id))
                inkMoves.insertTargetsIfAbsent(
                    change.targetIds.map { InkMoveTargetEntity(change.id, it) },
                )
            }

            /*
             * A picture another device imported. The row is the metadata; the bytes are fetched
             * afterwards by the download pass, which finds this row by looking for one whose file
             * is not there.
             *
             * **Inserted, never upserted, and `refCount` is why.** Every synced field describes the
             * bytes the id is the hash of and cannot have changed — but `refCount` is this device's
             * own count of the outlines pointing at the picture, deliberately outside the protocol
             * (`viveCServer/memory/syncPlan.md` SD7), and an upsert carrying the pulled row's zero
             * would overwrite it. `AttachmentDao.insert` ignores a conflict, so a row already here
             * keeps the count this device computed.
             *
             * **A tombstone is applied by doing nothing.** No build produces one — nothing calls
             * `AttachmentStore.release`, so a picture is never removed from this database — but one
             * from a future build would mean "the device that sent it has no outline pointing here
             * any more", which says nothing about this device: deleting the row would take away a
             * picture a page open on this screen is still drawing. When local sweeping exists, this
             * becomes a release of one reference and not a delete.
             */
            SyncKind.Attachment -> if (change.deletedAt == null) {
                attachments.insert(
                    AttachmentEntity(
                        id = change.id,
                        mimeType = change.raw.requiredString("mimeType"),
                        pixelWidth = change.raw.requiredInt("pixelWidth"),
                        pixelHeight = change.raw.requiredInt("pixelHeight"),
                        byteCount = change.raw.requiredLong("byteCount"),
                        refCount = 0,
                        createdAt = change.raw.requiredLong("createdAt"),
                    ),
                )
            }
        }
    }

    /** One pulled body, with the one it replaced, waiting to be counted — [applyPictureCounts]. */
    private data class PictureRecount(
        val before: PageContentEntity?,
        val after: PageContentEntity?,
    )

    /**
     * Moves this device's picture reference counts to match documents it did not write.
     *
     * The same bookkeeping `NotebookTransferManager` does when a `.vive` bundle replaces a body,
     * and for the same reason: `refCount` is a local count of the outlines pointing at a picture,
     * so a document that arrives from outside changes it without any of the editor's code running.
     * It never sweeps anything — `AttachmentDao.release` floors at zero and the file is left alone —
     * because a count is not a licence to delete on a device where the page that dropped the picture
     * may be undone a second later.
     *
     * **Drained at the end of the transaction, not as each body is applied**, because `attachment`
     * is the *last* kind in apply order and `retain` is an `UPDATE`: a picture arriving with the
     * page that shows it would otherwise be counted before its row existed, and the update would
     * match nothing. The residual case is a metadata row that lands in a *later* delta page than the
     * body placing it, which starts at zero and stays there — accepted, because nothing acts on the
     * count yet (`NotesViewModel.deleteImages` explains why nothing sweeps) and the alternative is
     * holding a body back for a row that is not its parent.
     */
    private suspend fun applyPictureCounts() {
        if (pictureRecounts.isEmpty()) return
        val deltas = linkedMapOf<String, Int>()
        pictureRecounts.forEach { recount ->
            recount.before?.let { row ->
                pictureIdsIn(row.docJson, row.format)
                    .forEach { id -> deltas[id] = deltas.getOrDefault(id, 0) - 1 }
            }
            recount.after?.let { row ->
                pictureIdsIn(row.docJson, row.format)
                    .forEach { id -> deltas[id] = deltas.getOrDefault(id, 0) + 1 }
            }
        }
        pictureRecounts.clear()
        deltas.forEach { (id, delta) ->
            repeat(delta.coerceAtLeast(0)) { attachments.retain(id) }
            repeat((-delta).coerceAtLeast(0)) { attachments.release(id) }
        }
    }

    /**
     * The attachments a stored document places, in the order it places them.
     *
     * **The document is the only record of which pictures a page shows**, which is the whole reason
     * `blobRefs` exists: the server cannot read `docJson`, so it cannot know what to keep, and this
     * is the extraction SD7 puts on the client.
     *
     * Guarded by a substring test before the decode. A body is decoded twice per pulled row here —
     * the one being replaced and the one replacing it — and the overwhelming majority of pages have
     * no picture on them at all, so paying a full parse per page of a first sync to discover that
     * would be the most expensive thing in the pull. `attachmentId` is a field name of
     * [Outline.Image] and of nothing else, and it survives both codecs, which write field names as
     * text. A page whose *text* happens to contain the word costs one wasted decode.
     *
     * An undecodable body yields nothing rather than throwing: the editor already refuses to write
     * to a page it cannot read and says so, and a document nobody can decode cannot be shown to
     * reference anything.
     */
    private fun pictureIdsIn(docJson: String, format: String): List<String> {
        if (!docJson.contains(IMAGE_FIELD_HINT)) return emptyList()
        val codec = DocumentCodecs.byId(format) ?: return emptyList()
        return runCatching {
            codec.decode(docJson.encodeToByteArray()).migrated().outlines
                .filterIsInstance<Outline.Image>()
                .map { it.attachmentId }
        }.getOrDefault(emptyList())
    }

    /**
     * The stored eraser mode, defaulting rather than failing.
     *
     * A mode this build has never heard of comes from a newer app, and the choice is between an
     * erase replayed with the wrong shape and a device that stops syncing on a row that will never
     * change. The same trade `DocumentJson` already makes with `coerceInputValues` for unknown enum
     * *values*, and for the same reason. Logged, because a silently reshaped erase is exactly the
     * kind of thing that gets reported as "the app ate my drawing" a week later.
     */
    private fun eraserModeOf(mode: String): EraserMode =
        EraserMode.entries.firstOrNull { it.name == mode } ?: run {
            Log.w(TAG, "Unknown eraser mode \"$mode\" replayed as Normal; upgrade this device")
            EraserMode.Normal
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
        var documentJson: String? = null
        var inkPoints: ByteArray? = null
        var targetIds: List<String> = emptyList()
        when (kind) {
            SyncKind.Notebook -> {
                raw.requiredInt("sortIndex")
                raw.requiredLong("createdAt")
                raw.requiredString("name")
                raw.requiredInt("colorArgb")
                raw.requiredBoolean("expanded")
                // Absent on anything a build without the shelf wrote, so optional — but validated here
                // rather than at apply time like everything else, because a non-numeric value would
                // otherwise throw inside the transaction that commits the cursor.
                raw.optionalLong("closedAt")
                raw.optionalLong("cloudOnlyAt")
            }
            SyncKind.Section -> {
                raw.requiredInt("sortIndex")
                raw.requiredLong("createdAt")
                raw.requiredString("notebookId")
                raw.requiredString("name")
                raw.requiredInt("colorArgb")
            }
            SyncKind.Page -> {
                raw.requiredInt("sortIndex")
                raw.requiredLong("createdAt")
                raw.requiredString("sectionId")
                raw.requiredString("title")
                raw.requiredString("preview")
            }
            SyncKind.PageContent -> {
                val id = raw.requiredString("id")
                require(raw.requiredString("pageId") == id) { "pageContent id must equal pageId" }
                require(raw.requiredString("format").isNotEmpty()) { "format must not be empty" }
                val encoding = raw["enc"]?.jsonPrimitive?.content ?: PLAIN_ENCODING
                require(encoding == PLAIN_ENCODING) { "unsupported document encoding" }

                val documentBytes = raw.decodeBase64("doc")
                require(documentBytes.size <= MAX_DOCUMENT_BYTES) { "document is too large" }
                val claimedDigest = raw.decodeBase64("docSha256")
                val actualDigest = MessageDigest.getInstance("SHA-256").digest(documentBytes)
                require(
                    claimedDigest.size == actualDigest.size &&
                        MessageDigest.isEqual(claimedDigest, actualDigest),
                ) { "document checksum does not match" }
                documentJson = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(documentBytes))
                    .toString()
            }
            SyncKind.InkStroke -> {
                raw.requiredString("pageId")
                raw.requiredInt("drawOrder")
                raw.requiredString("brushFamily")
                raw.requiredInt("brushVersion")
                raw.requiredInt("stabilization")
                listOf("sizeDp", "epsilon", "minX", "minY", "maxX", "maxY")
                    .forEach { raw.requiredFloat(it) }
                raw.requiredInt("colorArgb")
                raw.requiredLong("createdAt")
                inkPoints = raw.decodeInkPoints()
            }
            SyncKind.InkErase -> {
                raw.requiredString("pageId")
                raw.requiredString("mode")
                raw.requiredFloat("sizeDp")
                raw.requiredLong("createdAt")
                inkPoints = raw.decodeInkPoints()
                targetIds = raw.requiredStringArray("targetIds")
            }
            SyncKind.InkMove -> {
                raw.requiredString("pageId")
                raw.requiredFloat("dxDp")
                raw.requiredFloat("dyDp")
                raw.requiredLong("createdAt")
                inkPoints = raw.decodeInkPoints()
                targetIds = raw.requiredStringArray("targetIds")
            }
            SyncKind.Attachment -> {
                // The id is a content address and, on this side, a file name: `filesDir/attachments`
                // is where these bytes land. So it is checked here rather than trusted because the
                // contract declares a pattern — a server that sent `../` for an id would otherwise
                // have this build write outside its own attachment directory.
                require(isBlobDigest(raw.requiredString("id"))) {
                    "attachment id must be 64 lowercase hex characters"
                }
                raw.requiredString("mimeType")
                raw.requiredInt("pixelWidth")
                raw.requiredInt("pixelHeight")
                raw.requiredLong("byteCount")
                raw.requiredLong("createdAt")
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
            documentJson = documentJson,
            inkPoints = inkPoints,
            targetIds = targetIds,
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
        /** Decoded only for pageContent; null for hierarchy rows. */
        val documentJson: String?,
        /** Decoded only for the three ink kinds; null for everything else. */
        val inkPoints: ByteArray?,
        /** The ids an ink operation names, empty for every other kind. */
        val targetIds: List<String>,
        val raw: JsonObject,
    ) {
        fun asEntityState() = SyncEntityStateEntity(
            kind = kind.wire,
            entityId = id,
            serverVersion = version,
            serverJson = strippedServerJson(),
        )

        /**
         * The stored copy of the server's row, without the one field that would double the size of
         * the database.
         *
         * The JSON is kept so a later push can overlay only the fields this build owns rather than
         * erasing what a newer client wrote. `points` is not one of those: it is immutable, it is
         * already in the ink row beside it, and the push re-attaches it from there. Keeping it here
         * as base64 would store every stroke twice at a third again the size — on a page of 9,553
         * strokes that is 7 MB of duplicate, and a notebook may hold 500,000.
         */
        private fun strippedServerJson(): String = when (kind) {
            SyncKind.InkStroke, SyncKind.InkErase, SyncKind.InkMove ->
                JsonObject(raw.filterKeys { it != "points" }).toString()
            else -> raw.toString()
        }
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
        Page("page", 2),
        PageContent("pageContent", 3),

        // Ink hangs from a page and never from another stroke. An operation names the strokes it
        // affected, but as inert ids: a target that has not arrived, or that a purge removed years
        // ago, must not hold the operation back — see `applyRemoteRow`.
        InkStroke("inkStroke", 4),
        InkErase("inkErase", 5),
        InkMove("inkMove", 6),

        // Last, and the only kind with no parent at all: one picture can appear on several pages,
        // so there is no page that owns it. Its id is the SHA-256 of the bytes it describes, which
        // is why there is no `sha256` field — a second place to write an identity is a first chance
        // for the two to disagree.
        Attachment("attachment", 7);

        companion object {
            fun fromWire(value: String): SyncKind? = entries.firstOrNull { it.wire == value }
        }
    }

    private sealed interface PhaseResult {
        data class Done(val count: Int) : PhaseResult
        data class ConflictDone(
            val count: Int,
            val conflicts: Int,
            val pictures: Int = 0,
        ) : PhaseResult
        data class Stop(val result: SyncRunResult) : PhaseResult
    }

    /** What making the server hold this batch's pictures did to the batch. */
    private sealed interface BlobPhase {
        data class Ready(val uploaded: Int) : BlobPhase

        /** A digest cannot be delivered, so what named it has to be snapshotted again without it. */
        data object Rebuild : BlobPhase
        data class Stop(val result: SyncRunResult) : BlobPhase
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

    private fun JsonObject.decodeBase64(name: String): ByteArray = try {
        Base64.getDecoder().decode(requiredString(name))
    } catch (malformed: IllegalArgumentException) {
        throw IllegalArgumentException("invalid $name", malformed)
    }

    private fun JsonObject.requiredFloat(name: String): Float =
        this[name]?.jsonPrimitive?.floatOrNull
            ?: throw IllegalArgumentException("missing $name")

    private fun JsonObject.optionalFloat(name: String): Float? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        return value.jsonPrimitive.floatOrNull
            ?: throw IllegalArgumentException("invalid $name")
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        return value.jsonPrimitive.content
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        return value.jsonPrimitive.booleanOrNull
            ?: throw IllegalArgumentException("invalid $name")
    }

    private fun JsonObject.requiredStringArray(name: String): List<String> {
        val value = this[name] ?: return emptyList()
        if (value === JsonNull) return emptyList()
        val array = value as? JsonArray ?: throw IllegalArgumentException("invalid $name")
        return array.map { element ->
            element.jsonPrimitive.contentOrNull
                ?: throw IllegalArgumentException("invalid $name")
        }
    }

    /**
     * The encoded points of a stroke, mask or lasso.
     *
     * Bounded before anything is written, like a document body: the transport already refuses a
     * response bigger than one batch, but a single row claiming more than a `.vive` bundle would
     * carry is a protocol failure worth naming rather than a row worth storing.
     */
    private fun JsonObject.decodeInkPoints(): ByteArray {
        val points = decodeBase64("points")
        require(points.size <= MAX_INK_POINT_BYTES) { "ink points are too large" }
        return points
    }


    // --- moving a notebook to the cloud, and bringing it back ---------------------------------
    //
    // `memory/closedNotebooksPlan.md`. Both live in this class rather than in one of their own for a
    // reason that is not negotiable: they have to be serialized against ordinary runs by the same
    // [mutex]. A replay writing rows while a tick applies a delta, or an eviction deleting rows a
    // push is part-way through snapshotting, is a corrupt account rather than a slow one.

    /**
     * Removes a notebook's contents from this device, leaving them on the server.
     *
     * The whole safety argument is the first check: **the outbox must be empty**. The server accepts
     * a `pageContent` only when it already holds the pictures it names — `missing_blob` is the
     * refusal — so an empty outbox is the server saying, in its own words, that it has every byte
     * this device could offer it. Nothing is deleted before it has said so. It is deliberately the
     * whole outbox rather than the rows under this notebook: a queued `attachment` names no
     * notebook, because its id is a digest and one picture can appear anywhere.
     *
     * What goes: `page_content`, `page_revisions`, the three ink tables, the derived handwriting
     * cache, and the `attachments` rows and files nothing else on this device reaches. What stays:
     * the notebook, its sections and its pages. That is not squeamishness about a few hundred bytes
     * a row — [parentIsAvailable] holds back a pulled row whose parent is missing, and
     * [pullIfNeeded] will not advance the cursor while anything is held. A `sections` row deleted
     * here would meet the next `page` change another device makes for it and stop this device
     * syncing at all, for every notebook, silently. The payload is effectively all of the bytes.
     *
     * No trigger suppression and no tombstones. The sync triggers are `AFTER INSERT` and
     * `AFTER UPDATE`, so a delete queues nothing; and a tombstone is how a client asks the server to
     * forget a row, which is the exact opposite of what this asks of it. What the deletes *do*
     * require is [SyncDao.pruneOrphanedOutbox] in the same transaction — [snapshot] answers a queued
     * row whose table row has gone with "dirty pageContent disappeared", and fails every push from
     * then on.
     *
     * The shelf columns are written in a **second** transaction, with the triggers live so the other
     * devices learn. Second, because the eviction has to be free to fail without leaving a notebook
     * claiming its bytes are somewhere they are not.
     */
    suspend fun evictToCloud(notebookId: String): CloudArchiveResult = mutex.withLock {
        val notebook = notebooks.byId(notebookId)
            ?: return@withLock CloudArchiveResult.UnknownNotebook
        evictToCloudLocked(notebook)
    }

    private suspend fun evictToCloudLocked(notebook: NotebookEntity): CloudArchiveResult {
        if (notebook.deletedAt != null) return CloudArchiveResult.UnknownNotebook
        if (sync.state() == null) return CloudArchiveResult.NoAccount
        val pending = sync.outboxSize()
        if (pending > 0) return CloudArchiveResult.NotUploaded(pending)

        val evicted = try {
            db.withTransaction {
                val pageIds = pages.allInNotebook(notebook.id).map { it.id }
                if (pageIds.isEmpty()) return@withTransaction emptyList()
                val orphaned = picturesReachedOnlyBy(pageIds)
                // Chunked below SQLite's bind limit, which a notebook of a few thousand pages would
                // otherwise cross — and the failure mode of not chunking is an exception on
                // somebody's largest notebook only.
                pageIds.chunked(SQLITE_BIND_CHUNK).forEach { chunk ->
                    contents.deleteForPages(chunk)
                    revisions.deleteForPages(chunk)
                    inkStrokes.deleteForPages(chunk)
                    inkErases.deleteForPages(chunk)
                    inkMoves.deleteForPages(chunk)
                    // Derived from ink that has just gone, and rebuilt from the strokes when they
                    // come back. Its picture twin, `attachment_text`, needs no line here: it
                    // cascades from the `attachments` row below.
                    inkText.deleteForPages(chunk)
                }
                orphaned.chunked(SQLITE_BIND_CHUNK).forEach { attachments.deleteByIds(it) }
                sync.pruneOrphanedOutbox()
                orphaned
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Log.e(TAG, "Could not evict notebook ${notebook.id} to the cloud", failed)
            return CloudArchiveResult.Failed(SyncRunResult.Failed(PermanentSyncFailure.LocalData))
        }

        // After the transaction commits, so a rollback can never leave a surviving row pointing at
        // a file that is gone.
        blobs.discard(evicted)

        // Only what is missing, and only because this also runs from [enforceCloudOnly]: a device
        // evicting what another one moved would otherwise stamp the columns with its own clock and
        // push a notebook row saying nothing new. Rewriting a value that arrived from the server is
        // how a field nobody edited acquires a version history.
        val now = System.currentTimeMillis()
        db.withTransaction {
            if (notebook.closedAt == null) notebooks.setClosed(notebook.id, now, now)
            if (notebook.cloudOnlyAt == null) notebooks.setCloudOnly(notebook.id, now, now)
        }
        return CloudArchiveResult.Moved
    }

    /**
     * The digests this notebook places that nothing left on the device still reaches.
     *
     * Computed from the documents, not from `refCount`, which cannot answer it: a pulled picture is
     * documented as arriving with a count of zero and the ones already in Room were never
     * backfilled. A wrong count here deletes the file behind a picture another notebook still draws.
     *
     * **Only *current* bodies are consulted on the surviving side**, and that is a deliberate,
     * bounded inexactness rather than an oversight. A `page_revisions` payload is gzipped, so the
     * substring test cannot see into it and answering exactly would mean inflating up to
     * [NotesRepository.MAX_REVISIONS_PER_PAGE] documents for every page on the device — hundreds of
     * megabytes of blobs read to answer a question about a handful of digests. What that costs is
     * narrow and self-healing: a saved version of a page in *another* notebook, showing the
     * byte-identical picture that its current body no longer shows, renders it missing until this
     * notebook is brought back — at which point the same digest is downloaded to the same file name
     * and the old version draws again. The evicted notebook's own revisions are read, because they
     * are being deleted in the same breath and their pictures would otherwise be stranded.
     *
     * The surviving-side scan is one pass, guarded by the substring test in [pictureIdsIn]: the
     * overwhelming majority of pages have no picture on them at all. It runs for a press somebody
     * made and on no sync path.
     */
    private suspend fun picturesReachedOnlyBy(pageIds: List<String>): List<String> {
        val leaving = buildSet {
            pageIds.chunked(SQLITE_BIND_CHUNK).forEach { chunk ->
                contents.byIds(chunk).forEach { addAll(pictureIdsIn(it.docJson, it.format)) }
                revisions.byPageIds(chunk).forEach { addAll(picturesIn(it)) }
            }
        }
        if (leaving.isEmpty()) return emptyList()

        val evicting = pageIds.toSet()
        val staying = buildSet {
            contents.picturePlacingBodies().forEach { row ->
                if (row.pageId !in evicting) addAll(pictureIdsIn(row.docJson, row.format))
            }
        }
        return (leaving - staying).toList()
    }

    /** The pictures a saved version places, or none if its payload cannot be read. */
    private fun picturesIn(revision: PageRevisionEntity): List<String> = runCatching {
        DocumentRevisionPayload.unpack(revision).outlines
            .filterIsInstance<Outline.Image>()
            .map { it.attachmentId }
    }.getOrDefault(emptyList())

    /**
     * Downloads a cloud-only notebook's contents again and puts it back on this device.
     *
     * There is no per-notebook read in the contract — `GET /v1/changes` takes `since` and `limit`
     * and nothing else — so this replays the account from zero and keeps only what belongs to the
     * notebook. The cost is worth stating plainly: it reads the account's whole current state to
     * extract one notebook. It is a deliberate, once-in-a-while, user-pressed action, and it works
     * against the server that is deployed today. The fix is an optional `notebookId` on that
     * operation, which is a server change and is therefore not assumed here.
     *
     * Three things make this a replay rather than a pull:
     *
     *  - **The cursor never moves.** These rows all sit at or below it. This device already promised
     *    it had accounted for them, and it had — it accounted for them by deciding not to keep them.
     *  - **Only the evicted kinds are applied.** Notebooks, sections and pages were never evicted
     *    and are kept current by ordinary sync, so writing a replayed copy of one would undo a
     *    rename made since. Their entity states are left alone for the same reason.
     *  - **Purges are left to the ordinary pull.** Reading the log from zero shows every purge the
     *    account has ever recorded, including the ones this device applied long ago. This replay
     *    commits no cursor and so speaks for no window; the run that does commit one is the run
     *    that applies them.
     *  - **`applyingRemote` is set.** Every write here is an insert, and inserts fire the outbox
     *    triggers: without it this device would immediately queue a push of everything it has just
     *    finished downloading.
     *
     * An `attachment` has no parent and the protocol cannot say which notebook it belongs to, so
     * those rows are buffered through the replay and then narrowed to the digests the restored
     * documents actually name. Applying the rest would be worse than wasteful: a row whose file is
     * absent *is* a pending download, so it would schedule bytes for notebooks still in the cloud.
     */
    suspend fun restoreFromCloud(
        account: SyncAccount,
        notebookId: String,
    ): CloudArchiveResult = mutex.withLock {
        val notebook = notebooks.byId(notebookId)
            ?: return@withLock CloudArchiveResult.UnknownNotebook
        if (notebook.deletedAt != null) return@withLock CloudArchiveResult.UnknownNotebook
        if (notebook.cloudOnlyAt == null) return@withLock CloudArchiveResult.AlreadyDone
        if (sync.state() == null) return@withLock CloudArchiveResult.NoAccount

        val wanted = pages.allInNotebook(notebookId).map { it.id }.toSet()
        val pictureRows = mutableMapOf<String, RemoteChange>()
        val restored = mutableListOf<Pair<String, String>>()

        var cursor = 0L
        var hasMore: Boolean
        var replayed = 0
        do {
            val page = when (
                val result = client.pullChanges(account.serverUrl, account.token, cursor)
            ) {
                is ServerResult.Success -> result.value
                ServerResult.Unauthorized ->
                    return@withLock CloudArchiveResult.Failed(SyncRunResult.Revoked)
                is ServerResult.Failed ->
                    return@withLock CloudArchiveResult.Failed(result.asSyncResult())
            }

            val parsed = try {
                // A kind this build cannot store is dropped rather than fatal, which is the opposite
                // of what [pullIfNeeded] does and is right for the opposite reason: that loop is
                // about to commit a cursor promising the row was applied, and this one commits
                // nothing. The next ordinary run still refuses to advance past it, loudly.
                page.changes.mapNotNull(::parseRemoteChange)
            } catch (malformed: IllegalArgumentException) {
                return@withLock CloudArchiveResult.Failed(
                    SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse),
                )
            }

            val mine = parsed.filter { change ->
                when (change.kind) {
                    SyncKind.PageContent,
                    SyncKind.InkStroke,
                    SyncKind.InkErase,
                    SyncKind.InkMove,
                    -> change.raw.requiredString("pageId") in wanted
                    SyncKind.Attachment -> {
                        pictureRows[change.id] = change
                        false
                    }
                    // Never evicted, so never restored. A replayed copy is by definition not newer
                    // than what ordinary sync has already put in Room.
                    SyncKind.Notebook, SyncKind.Section, SyncKind.Page -> false
                }
            }
            mine.forEach { change ->
                if (change.kind == SyncKind.PageContent && change.deletedAt == null) {
                    restored += requireNotNull(change.documentJson) to
                        change.raw.requiredString("format")
                }
            }

            try {
                db.withTransaction {
                    sync.setApplyingRemote(true)
                    mine.forEach { change ->
                        sync.putEntityState(change.asEntityState())
                        applyRemoteRow(change)
                    }
                    invalidateInkText(remoteInkPages)
                    applyPictureCounts()
                    sync.setApplyingRemote(false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                Log.e(TAG, "Could not write a replayed change for notebook $notebookId", failed)
                return@withLock CloudArchiveResult.Failed(
                    SyncRunResult.Failed(PermanentSyncFailure.LocalData),
                )
            }
            publishRemoteInk()

            cursor = page.cursor
            hasMore = page.hasMore
            if (hasMore && page.changes.isEmpty()) {
                return@withLock CloudArchiveResult.Failed(
                    SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse),
                )
            }
        } while (hasMore && ++replayed < MAX_REPLAY_PAGES)

        if (hasMore) {
            Log.e(TAG, "Replay for notebook $notebookId did not finish in $MAX_REPLAY_PAGES pages")
            return@withLock CloudArchiveResult.Failed(
                SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse),
            )
        }

        val needed = restored.flatMapTo(mutableSetOf()) { (json, format) ->
            pictureIdsIn(json, format)
        }
        try {
            db.withTransaction {
                sync.setApplyingRemote(true)
                pictureRows.values.filter { it.id in needed }.forEach { change ->
                    sync.putEntityState(change.asEntityState())
                    applyRemoteRow(change)
                }
                sync.setApplyingRemote(false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Log.e(TAG, "Could not write the pictures replayed for notebook $notebookId", failed)
            return@withLock CloudArchiveResult.Failed(
                SyncRunResult.Failed(PermanentSyncFailure.LocalData),
            )
        }

        // Triggers live: the other devices have to learn this notebook is worth holding again, and
        // putting it back on the rail is the press somebody just made.
        val now = System.currentTimeMillis()
        db.withTransaction {
            notebooks.setCloudOnly(notebookId, null, now)
            notebooks.setClosed(notebookId, null, now)
        }

        val downloads = blobs.downloadMissing(account)
        downloadsOutstanding = downloads.workRemains
        CloudArchiveResult.BroughtBack
    }

    /**
     * Evicts what another device moved to the cloud.
     *
     * Called after the push phase and never after the pull, which is the whole of what makes it
     * safe: a device that was offline while the move happened may hold an edit nobody else has, and
     * evicting on the pull would destroy it. By the time the push has run those bytes are on the
     * server, and [evictToCloudLocked]'s empty-outbox check is the proof of it — a notebook whose
     * rows are still queued is simply left alone until the run that manages to send them.
     *
     * The cheap emptiness test comes first so that a steady-state run over a device that settled
     * into this state months ago costs two indexed reads per cloud-only notebook and nothing else.
     */
    private suspend fun enforceCloudOnly() {
        for (notebook in notebooks.cloudOnly()) {
            val pageIds = pages.allInNotebook(notebook.id).map { it.id }
            if (pageIds.isEmpty()) continue
            val stillHere = pageIds.chunked(SQLITE_BIND_CHUNK).any { chunk ->
                contents.byIds(chunk).isNotEmpty() || inkStrokes.countForPages(chunk) > 0
            }
            if (!stillHere) continue
            val result = evictToCloudLocked(notebook)
            Log.i(TAG, "Notebook ${notebook.id} is cloud-only on the account: $result")
        }
    }

    private companion object {
        const val TAG = "HierarchySync"
        const val MAX_PUSH_CHANGES = 512
        const val MAX_BATCHES_PER_RUN = 1_024

        /**
         * A bound on the replay [restoreFromCloud] performs, so a server that answers `hasMore`
         * for ever cannot spin one press into an endless download. At the transport's page size
         * this is far more of an account than anybody has.
         */
        const val MAX_REPLAY_PAGES = 4_096

        /** `NotesRepository.SQLITE_BIND_CHUNK`, which is private to it. SQLite's limit is 999. */
        const val SQLITE_BIND_CHUNK = 400
        const val MAX_DOCUMENT_BYTES = 2 shl 20
        const val PENDING_BATCH_KEY = "syncPendingHierarchyBatch"
        const val PLAIN_ENCODING = "none/1"

        /** `InkCodec`'s encoder id, and what a row that names none is taken to mean. */
        const val INK_ENCODING = "ink/v1"

        /** NotebookTransferManager.MAX_POINT_BYTES, so a bundle that imports also syncs. */
        const val MAX_INK_POINT_BYTES = 4 * 1024 * 1024

        /**
         * Holds the account id this installation has caught up with, not a boolean: the question is
         * always "caught up with *which* tree", and storing the id makes a stale marker for a
         * previous account unusable rather than merely wrong.
         */
        const val CAUGHT_UP_KEY = "syncFirstPullCompletedFor"
        const val DISPLAY_UPDATED_AT = "viveDisplayUpdatedAt"

        const val BLOB_REFS = "blobRefs"

        /** `PageContentFields.blobRefs` is `maxItems: 512`. A page with more is not a page. */
        const val MAX_BLOB_REFS = 512

        /**
         * A field name only [Outline.Image] has, used to skip decoding a body that cannot mention a
         * picture. Both codecs write field names as text, so it holds for `cbor/1` as well.
         */
        const val IMAGE_FIELD_HINT = "attachmentId"
    }
}

private val hierarchyJson = Json { ignoreUnknownKeys = true }
