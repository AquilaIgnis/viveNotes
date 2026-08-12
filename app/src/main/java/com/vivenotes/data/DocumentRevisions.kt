package com.vivenotes.data

import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.data.db.PageRevisionEntity
import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.model.DocumentCodecs
import com.vivenotes.model.PageDoc
import com.vivenotes.model.migrated
import com.vivenotes.model.newId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Result of reading or restoring one historical page checkpoint. */
sealed interface PageRevisionLoad {
    data class Loaded(
        val revision: PageRevisionSummary,
        val doc: PageDoc,
    ) : PageRevisionLoad
    data object NotFound : PageRevisionLoad
    data class Unreadable(val revision: PageRevisionSummary, val cause: Throwable) : PageRevisionLoad
}

/** Compression and integrity boundary for revision payloads. */
internal object DocumentRevisionPayload {
    const val ENCODING = "gzip/1"

    fun pack(
        row: PageContentEntity,
        createdAt: Long,
        ink: PackedInkSnapshot,
    ): PageRevisionEntity {
        val raw = row.docJson.encodeToByteArray()
        val compressed = ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).use { it.write(raw) }
            bytes.toByteArray()
        }
        return PageRevisionEntity(
            id = newId(),
            pageId = row.pageId,
            createdAt = createdAt,
            format = row.format,
            encoding = ENCODING,
            byteCount = raw.size,
            sha256 = raw.sha256(),
            payload = compressed,
            inkFormat = ink.format,
            inkEncoding = ink.encoding,
            inkByteCount = ink.byteCount,
            inkSha256 = ink.sha256,
            inkPayload = ink.payload,
        )
    }

    fun unpack(row: PageRevisionEntity): PageDoc {
        require(row.encoding == ENCODING) { "unknown revision encoding '${row.encoding}'" }
        val raw = GZIPInputStream(ByteArrayInputStream(row.payload)).use { it.readBytes() }
        require(raw.size == row.byteCount) {
            "revision ${row.id} is ${raw.size} bytes, expected ${row.byteCount}"
        }
        require(MessageDigest.isEqual(raw.sha256().encodeToByteArray(), row.sha256.encodeToByteArray())) {
            "revision ${row.id} failed its SHA-256 check"
        }
        val codec = DocumentCodecs.byId(row.format)
            ?: error("unknown revision document format '${row.format}'")
        return codec.decode(raw).migrated()
    }

    fun summary(row: PageRevisionEntity): PageRevisionSummary =
        PageRevisionSummary(
            row.id,
            row.pageId,
            row.createdAt,
            row.byteCount + row.inkByteCount,
        )
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
