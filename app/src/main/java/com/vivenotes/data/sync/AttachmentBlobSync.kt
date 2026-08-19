package com.vivenotes.data.sync

import android.util.Log
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The bytes half of an attachment, as synchronisation needs it.
 *
 * An interface rather than a direct dependency on `AttachmentStore` for the reason [SyncTransport]
 * is one: this is the seam a test hands a temporary directory, so a suite that proves a picture
 * crosses between devices does not write into the real `filesDir` of whatever tablet it runs on.
 * `AttachmentStore` is the only production implementation and owns every decision about *what* gets
 * stored; this is only about where the bytes are and when they arrive.
 */
interface AttachmentBytes {

    /** The content-addressed file for [id], which may not exist yet. */
    fun fileFor(id: String): File

    /** Where a download is written before its digest is proved. Beside [fileFor], so it can rename. */
    fun stagingFor(id: String): File

    /** Moves proved bytes into place under [id] and announces them. False if it could not. */
    fun publish(staged: File, id: String): Boolean

    /** Bumped whenever bytes appear. Collected by anything showing a picture that would not draw. */
    val arrivals: StateFlow<Long>
}

/** Whether the server can be made to hold one digest, and what to do if it cannot. */
sealed interface BlobPresence {

    /**
     * The server holds the bytes. [uploaded] is true only when this call is what put them there,
     * so a run can say how many pictures it actually carried rather than how many it checked.
     */
    data class Present(val uploaded: Boolean) : BlobPresence

    /**
     * This device cannot supply these bytes and no retry will change that — the file is not here, or
     * the server refused them permanently. The change naming the digest has to stop naming it.
     */
    data object Undeliverable : BlobPresence

    /** The connection failed, or the token did. The whole phase stops on [result]. */
    data class Stopped(val result: SyncRunResult) : BlobPresence
}

/** What one download pass moved, and whether it left anything behind. */
data class BlobDownloads(val downloaded: Int, val workRemains: Boolean)

/**
 * Attachment bytes, both directions — `viveCServer/memory/syncPlan.md` S5 and SD7.
 *
 * **The rule the whole phase exists to keep is the server's: a live row never points at bytes the
 * server cannot serve.** It enforces that by rejecting a `pageContent` whose `blobRefs` name a
 * digest it does not hold, and an `attachment` row whose id names one — so on this side, uploading
 * comes *before* the change that references it, and a `missing_blob` rejection is the one failure a
 * client can always clear on its own.
 *
 * Two memos, both in memory and both deliberately not persisted:
 *
 *  - [serverHolds] is what makes a steady-state run cost nothing. The durable half of the same fact
 *    is `sync_entity_states`: an `attachment` row the server accepted, or one this device pulled,
 *    proves the bytes are there, because the server refuses the row otherwise and never sweeps a
 *    blob while a live row names it. This set only saves the round trip *within* a process.
 *  - [deferred] stops a picture the server does not have from being asked for every 60 s for the
 *    rest of the installation's life. Forgotten when the process restarts, which is the cheapest
 *    retry policy that cannot spin: a repair is a launch away rather than a request away.
 */
class AttachmentBlobSync(
    db: NotesDatabase,
    private val client: SyncTransport,
    private val bytes: AttachmentBytes,
) {

    private val attachments = db.attachmentDao()
    private val sync = db.syncDao()

    private val serverHolds = mutableSetOf<String>()
    private val deferred = mutableSetOf<String>()

    /**
     * Makes the server hold [digest], or says why it never will.
     *
     * [force] is set by the `missing_blob` repair path, where the server has just said in as many
     * words that it does not have these bytes: every memo is wrong at that moment, and the `HEAD`
     * that would normally save an upload can only repeat what the rejection already said. That is
     * also the path that repairs a server whose blob volume was lost while its database survived —
     * §13.12 designed the 404 for exactly this.
     */
    suspend fun ensureUploaded(
        account: SyncAccount,
        digest: String,
        force: Boolean = false,
    ): BlobPresence {
        if (!isBlobDigest(digest)) {
            // Not a content address, so it can never name a blob. Only a local row written by
            // something other than AttachmentStore could produce one.
            Log.e(TAG, "Attachment id \"$digest\" is not a SHA-256 and cannot be uploaded")
            return BlobPresence.Undeliverable
        }
        if (!force && knownPresent(digest)) return BlobPresence.Present(uploaded = false)

        val file = bytes.fileFor(digest)
        if (!file.isFile) {
            // The picture is broken on this device already. Nothing here can put it on the server,
            // and holding the change back would stop every other change with it.
            Log.w(TAG, "No local bytes for attachment $digest; the change naming it cannot carry it")
            return BlobPresence.Undeliverable
        }

        if (!force) {
            when (val present = client.hasBlob(account.serverUrl, account.token, digest)) {
                is ServerResult.Success -> if (present.value) {
                    serverHolds += digest
                    return BlobPresence.Present(uploaded = false)
                }
                ServerResult.Unauthorized -> return BlobPresence.Stopped(SyncRunResult.Revoked)
                is ServerResult.Failed -> return BlobPresence.Stopped(present.asSyncResult())
            }
        }

        return when (val upload = client.uploadBlob(account.serverUrl, account.token, digest, file)) {
            is ServerResult.Success -> {
                serverHolds += digest
                // False when the server answered 204: another device had already uploaded the same
                // photograph, and one file is what both of them now reference.
                BlobPresence.Present(uploaded = upload.value)
            }
            ServerResult.Unauthorized -> BlobPresence.Stopped(SyncRunResult.Revoked)
            is ServerResult.Failed -> if (upload.retryable) {
                BlobPresence.Stopped(upload.asSyncResult())
            } else {
                // A picture the server will not take however many times it is offered: over the
                // per-attachment cap, or bytes it refuses to hash to their own name.
                Log.e(TAG, "Server permanently refused attachment $digest: ${upload.reason}")
                BlobPresence.Undeliverable
            }
        }
    }

    /**
     * Fetches the bytes for every picture this device knows about and does not have.
     *
     * **The `attachments` table is the queue.** A row whose file is absent *is* a pending download,
     * so there is no second table to keep in step with the filesystem, nothing to drain twice, and
     * nothing stranded when a process dies mid-transfer. A pulled `attachment` row therefore
     * schedules its own bytes by existing.
     *
     * The first transport failure ends the pass rather than trying the rest. They are all going to
     * the same server: a tablet that has lost its wifi with two hundred pictures outstanding would
     * otherwise spend two hundred connect timeouts finding that out, on a clock that ticks every
     * 60 s. A 404 or a digest that does not match is about *that* picture and only defers it.
     */
    suspend fun downloadMissing(account: SyncAccount): BlobDownloads {
        var downloaded = 0
        val pending = missingBytes()
        pending.forEach { id ->
            val staged = bytes.stagingFor(id)
            when (val result = client.downloadBlob(account.serverUrl, account.token, id, staged)) {
                is ServerResult.Success -> {
                    when {
                        // The server has no such attachment for this account. The row describes a
                        // picture whose bytes are gone, which is a fact about it rather than a
                        // failure worth repeating every minute.
                        !result.value -> {
                            Log.w(TAG, "Server has no bytes for attachment $id")
                            deferred += id
                        }
                        bytes.publish(staged, id) -> downloaded++
                        else -> {
                            Log.e(TAG, "Downloaded attachment $id could not be published")
                            deferred += id
                        }
                    }
                }

                ServerResult.Unauthorized -> return BlobDownloads(downloaded, workRemains = true)

                is ServerResult.Failed -> {
                    if (result.retryable) return BlobDownloads(downloaded, workRemains = true)
                    Log.e(TAG, "Attachment $id could not be downloaded: ${result.reason}")
                    deferred += id
                }
            }
        }
        return BlobDownloads(downloaded, workRemains = false)
    }

    /** Whether anything at all is waiting to be fetched, without asking the network. */
    suspend fun hasMissingBytes(): Boolean = missingBytes().isNotEmpty()

    /**
     * The pictures this device knows about and does not have, minus the ones it has stopped asking
     * for. An id that cannot be a digest is skipped rather than requested: it could not name a blob,
     * and it must never reach a URL or a file name.
     */
    private suspend fun missingBytes(): List<String> = attachments.allIds().filter { id ->
        id !in deferred && isBlobDigest(id) && !bytes.fileFor(id).exists()
    }

    /**
     * Whether the server is already known to hold [digest], without asking it.
     *
     * The durable half is the entity state: the server accepts an `attachment` row only when it has
     * the bytes, and it never frees a blob while a live row names it, so a state row for the digest
     * — pushed here or pulled from another device — is proof that a `HEAD` would answer 204.
     */
    private suspend fun knownPresent(digest: String): Boolean =
        digest in serverHolds || sync.entityState(ATTACHMENT_KIND, digest) != null

    private fun ServerResult.Failed.asSyncResult(): SyncRunResult = if (retryable) {
        SyncRunResult.Retryable(reason)
    } else {
        SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse)
    }

    private companion object {
        const val TAG = "AttachmentBlobSync"
        const val ATTACHMENT_KIND = "attachment"
    }
}
