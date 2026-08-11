package com.vivenotes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import androidx.room.withTransaction
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Where imported pictures live.
 *
 * Bytes go to `filesDir/attachments/<sha256>`; what is known about them goes to the `attachments`
 * table. `AttachmentEntity` argues for that split. This class owns the other half of the decision —
 * **what gets stored in the first place.**
 *
 * **Every import is re-encoded, and that is the point.** A phone camera produces something like
 * 4000 × 3000; a page shows it about 700 dp wide. Storing the original would keep ten times the bytes
 * to draw the same picture, and — the part that actually hurts — every decode of it would allocate
 * the full bitmap: 4000 × 3000 at 4 bytes a pixel is **48 MB of native memory for one photograph**,
 * against roughly 3 MB as a JPEG on disk. A page with a handful of those does not run out of disk, it
 * runs out of process.
 *
 * So [MAX_DIMENSION] is a memory budget written as a length, and the re-encode is where an image
 * feature is made affordable. It is applied once, at the door, rather than at every draw.
 */
class AttachmentStore(
    private val context: Context,
    private val db: NotesDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val attachments = db.attachmentDao()

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    fun fileFor(id: String): File = File(directory, id)

    /**
     * Imports what a picker returned, and reports what to put on the page.
     *
     * Returns null when the bytes cannot be read as an image at all — a picker can hand back a URI
     * whose content has since gone, and a placeholder outline pointing at nothing would be worse than
     * nothing on the page.
     */
    suspend fun import(uri: Uri): ImportedAttachment? = withContext(io) {
        val decoded = decodeDownscaled(uri) ?: return@withContext null
        val bytes = decoded.compress()
        // Recycled here rather than left to the collector: this is a native allocation the size of
        // the whole picture, and the caller has no use for it — what goes on the page is drawn from
        // the stored file, at the size the page needs.
        decoded.recycle()

        val id = bytes.sha256()
        val file = fileFor(id)
        // Content-addressed, so a file that is already there is already correct. Rewriting it would
        // be the same bytes at best, and a torn file at worst if something is reading it.
        if (!file.exists()) {
            // Written beside and moved into place, so a crash mid-write cannot leave a truncated
            // file sitting under the name its own hash promises.
            val staging = File(directory, "$id.part")
            staging.writeBytes(bytes)
            staging.renameTo(file)
        }

        db.withTransaction {
            attachments.insert(
                AttachmentEntity(
                    id = id,
                    mimeType = MIME_TYPE,
                    pixelWidth = decoded.width,
                    pixelHeight = decoded.height,
                    byteCount = bytes.size.toLong(),
                    refCount = 0,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            attachments.retain(id)
        }
        ImportedAttachment(id, decoded.width, decoded.height)
    }

    /** Claims a reference — a picture pasted or duplicated shares the file rather than copying it. */
    suspend fun retain(id: String) = withContext(io) { attachments.retain(id) }

    /**
     * Drops a reference, and sweeps the file if that was the last one.
     *
     * Deliberately *not* called when an outline is deleted, only when a delete can no longer be
     * undone. Sweeping on delete would make undo restore an outline pointing at a file that had been
     * removed underneath it.
     */
    suspend fun release(id: String) = withContext(io) {
        attachments.release(id)
        if (attachments.byId(id)?.refCount == 0) {
            fileFor(id).delete()
            attachments.deleteIfUnreferenced(id)
        }
    }

    suspend fun metadata(id: String): AttachmentEntity? = withContext(io) { attachments.byId(id) }

    /**
     * Reads a picture back at no more than [maxDimension] on its longer side.
     *
     * The sample size is chosen from the file's header before any pixels are read, so a picture shown
     * small is never fully decoded to be immediately scaled down — that allocation is the one this
     * whole class exists to avoid.
     */
    suspend fun loadBitmap(id: String, maxDimension: Int): Bitmap? = withContext(io) {
        val file = fileFor(id)
        if (!file.exists()) return@withContext null
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val sample = sampleFor(info.size, maxDimension)
                decoder.setTargetSize(
                    (info.size.width / sample).coerceAtLeast(1),
                    (info.size.height / sample).coerceAtLeast(1),
                )
                // Drawn into a Compose canvas, which cannot use a hardware bitmap as a source for
                // every operation; software keeps it usable everywhere at this size.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        }.getOrNull()
    }

    private fun decodeDownscaled(uri: Uri): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }.getOrNull()

    private fun Bitmap.compress(): ByteArray = ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        out.toByteArray()
    }

    companion object {
        private const val DIRECTORY = "attachments"

        /**
         * The longest side an imported picture is kept at.
         *
         * 2048 is roughly twice what a page can show at 100% on this tablet, which leaves room to
         * zoom in without the picture going soft, and costs 16 MB decoded at the very worst rather
         * than a camera original's 48.
         */
        const val MAX_DIMENSION = 2048

        /** Re-encoded rather than stored as received, so [MAX_DIMENSION] is what reaches the disk. */
        private const val MIME_TYPE = "image/jpeg"
        private const val QUALITY = 88

        /** The power-of-two divisor `BitmapFactory` and `ImageDecoder` both understand. */
        internal fun sampleFor(size: Size, maxDimension: Int): Int {
            if (maxDimension <= 0) return 1
            var sample = 1
            var longest = maxOf(size.width, size.height)
            while (longest / 2 >= maxDimension) {
                longest /= 2
                sample *= 2
            }
            return sample
        }

        internal fun ByteArray.sha256(): String =
            MessageDigest.getInstance("SHA-256").digest(this)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** What an import produced: the id to put in an [com.vivenotes.model.Outline.Image], and its shape. */
data class ImportedAttachment(val id: String, val pixelWidth: Int, val pixelHeight: Int)

/** Kept so `BitmapFactory` stays available for anything that needs a header-only read. */
internal fun boundsOf(file: File): Size? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    return if (options.outWidth > 0) Size(options.outWidth, options.outHeight) else null
}
