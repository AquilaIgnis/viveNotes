package com.vivenotes.data

import android.util.Log
import androidx.room.withTransaction
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.SyncEntityStateEntity
import com.vivenotes.data.sync.AttachmentBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** What one [AttachmentSweep.sweep] did. */
data class AttachmentSweepResult(
    val released: Int,
    val placedAgain: Int,
    val filesDeleted: Int,
    val versionsIndexed: Int,
)

/**
 * Lets go of pictures nothing on this device can put back on a page.
 *
 * A picture has two lifetimes, and they end at different times:
 *
 *  - **The server's copy**, which counts against the account's storage quota. It is released — the
 *    `attachment` row pushed with `deletedAt` — once no stored document places the picture and the
 *    editor's undo and redo cannot bring it back. "Stored document" includes the pages sitting in
 *    Deleted Items, which still hold their bodies until the seven-day purge removes them, so a
 *    restored page never finds its pictures gone. The server frees the bytes a day after nothing
 *    reaches them, and only once no other account holds the same bytes.
 *  - **The file on this device**, which goes later: only once, in addition, no saved version shows
 *    the picture, and a day has passed since it was released. Version history is device-local, so
 *    keeping the file is enough to keep it working. Restoring such a version places the picture
 *    again, the next sweep takes the release back, and the push that follows meets `missing_blob`
 *    if the server already freed the bytes — which this device answers by uploading them from the
 *    file it kept.
 *
 * Every decision is read from the documents, never from `refCount`, which a paste does not count
 * and a pulled picture starts at zero.
 */
class AttachmentSweep(
    private val db: NotesDatabase,
    private val bytes: AttachmentBytes,
    /** Pictures something in memory can restore — the editor's undo and redo, its open page. */
    private val heldPictures: suspend () -> Set<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val attachments = db.attachmentDao()
    private val contents = db.pageContentDao()
    private val revisions = db.pageRevisionDao()
    private val sync = db.syncDao()

    suspend fun sweep(): AttachmentSweepResult {
        val versionsIndexed = indexSavedVersions()

        // Asked before the documents are read, never after. A picture that leaves memory between the
        // two readings can only have left it by being written into a document, which the read below
        // then sees; the other order would miss a picture deleted, saved and held only by Undo
        // during the gap.
        val held = heldPictures()
        val now = clock()

        var released = 0
        var placedAgain = 0
        val deleted = db.withTransaction {
            val placed = hashSetOf<String>()
            contents.picturePlacingBodies().forEach { body ->
                placed += DocumentPictures.idsIn(body.docJson, body.format)
            }
            placed += held

            val rows = attachments.all()

            val toPlace = rows.filter { it.releasedAt != null && it.id in placed }.map { it.id }
            toPlace.chunked(SQLITE_BIND_CHUNK).forEach { attachments.markPlaced(it) }
            placedAgain = toPlace.size

            val toRelease = rows
                .filter { it.releasedAt == null && it.id !in placed && it.createdAt <= now - FRESH_IMPORT_MILLIS }
                .map { it.id }
            toRelease.chunked(SQLITE_BIND_CHUNK).forEach { attachments.markReleased(it, now) }
            released = toRelease.size
            toRelease.forEach { id ->
                // A server with no record of this picture's row needs no tombstone for it; the bytes,
                // if they ever arrived, are already unreferenced there and go on their own.
                if (sync.entityState(ATTACHMENT_KIND, id) == null) sync.deleteOutbox(ATTACHMENT_KIND, id)
            }

            // A release whose push was lost — dropped above while the row's first push was still in
            // flight, say — is told again whenever the server still holds the row live and nothing is
            // queued. Without this a picture could stay on the account's quota for ever.
            rows.filter { it.releasedAt != null && it.id !in placed }.forEach { row ->
                val state = sync.entityState(ATTACHMENT_KIND, row.id) ?: return@forEach
                if (serverHoldsLive(state) && sync.outboxEntry(ATTACHMENT_KIND, row.id) == null) {
                    sync.enqueueIfAbsent(ATTACHMENT_KIND, row.id)
                }
            }

            // Nothing is deleted while a saved version has yet to be indexed: an unindexed version
            // is one whose pictures are not known, and only knowing makes deleting safe.
            if (revisions.unindexedCount() > 0) return@withTransaction emptyList()
            val versioned = revisions.indexedPictureIds().flatMapTo(hashSetOf(), DocumentPictures::decode)

            val releasedBefore = now - RELEASED_FILE_GRACE_MILLIS
            val toDelete = rows.filter { row ->
                val releasedAt = row.releasedAt ?: return@filter false
                row.id !in placed &&
                    row.id !in versioned &&
                    releasedAt <= releasedBefore &&
                    // The tombstone is sent from this row, so it waits for the push to land.
                    sync.outboxEntry(ATTACHMENT_KIND, row.id) == null
            }.map { it.id }
            // `attachment_text` goes with the rows by foreign key.
            toDelete.chunked(SQLITE_BIND_CHUNK).forEach { attachments.deleteByIds(it) }
            toDelete
        }

        // After the transaction commits, so a rollback can never leave a surviving row pointing at a
        // file that is gone.
        deleted.forEach { id -> bytes.fileFor(id).delete() }

        if (released > 0 || placedAgain > 0 || deleted.isNotEmpty()) {
            Log.i(TAG, "Released $released picture(s), placed $placedAgain again, deleted ${deleted.size} file(s)")
        }
        return AttachmentSweepResult(
            released = released,
            placedAgain = placedAgain,
            filesDeleted = deleted.size,
            versionsIndexed = versionsIndexed,
        )
    }

    /**
     * Records which pictures each saved version written before the index existed places.
     *
     * Once per version: one whose payload cannot be read is recorded as placing nothing, the same
     * answer restoring it would give, rather than retried on every sweep.
     */
    private suspend fun indexSavedVersions(): Int {
        var indexed = 0
        while (true) {
            val batch = revisions.unindexed(INDEX_BATCH)
            if (batch.isEmpty()) return indexed
            db.withTransaction {
                batch.forEach { revision ->
                    revisions.setPictureIds(revision.id, DocumentPictures.encode(DocumentPictures.idsIn(revision)))
                }
            }
            indexed += batch.size
        }
    }

    private fun serverHoldsLive(state: SyncEntityStateEntity): Boolean = runCatching {
        val server = Json.parseToJsonElement(state.serverJson) as? JsonObject ?: return false
        val deletedAt = server["deletedAt"]
        deletedAt == null || deletedAt is JsonNull
    }.getOrDefault(false)

    companion object {
        private const val TAG = "AttachmentSweep"
        private const val ATTACHMENT_KIND = "attachment"

        /**
         * A picture imported this recently is not released even if nothing places it yet: an import
         * writes its row a moment before the editor puts it on the page. A release is reversible, so
         * this only saves a pointless round trip to the server.
         */
        const val FRESH_IMPORT_MILLIS = 60 * 60 * 1000L

        /**
         * How long a released picture's file outlives the release. Matches the server's own grace
         * period, and covers what this sweep cannot see — an export or a share reading the file.
         */
        const val RELEASED_FILE_GRACE_MILLIS = 24 * 60 * 60 * 1000L

        private const val INDEX_BATCH = 64

        /** The chunk the rest of the data layer binds lists in, well below SQLite's limit. */
        private const val SQLITE_BIND_CHUNK = 400
    }
}
