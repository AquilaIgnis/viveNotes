package com.vivenotes.data

import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.PageRevisionEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The active ink state at one point in time.
 *
 * Stroke geometry and erase/move operations are immutable rows which this database never hard
 * deletes, so duplicating their point blobs into every revision would be enormous and unnecessary.
 * A snapshot is a compact state vector over those durable rows. Colour and grouping are included
 * because those are the only stroke fields edited in place.
 */
internal data class InkSnapshot(
    val strokes: List<StrokeState>,
    val eraseIds: List<String>,
    val moveIds: List<String>,
) {
    data class StrokeState(
        val id: String,
        val colorArgb: Int,
        val colorFollowsTheme: Boolean?,
        val groupId: String?,
    )

    companion object {
        fun from(
            strokes: List<InkStrokeEntity>,
            erases: List<InkEraseWithTargets>,
            moves: List<InkMoveWithTargets>,
        ) = InkSnapshot(
            strokes = strokes.map {
                StrokeState(it.id, it.colorArgb, it.colorFollowsTheme, it.groupId)
            },
            eraseIds = erases.map { it.erase.id },
            moveIds = moves.map { it.move.id },
        )
    }
}

internal data class PackedInkSnapshot(
    val format: String,
    val encoding: String,
    val byteCount: Int,
    val sha256: String,
    val payload: ByteArray,
)

/** Compact state-vector encoding; the large immutable point rows remain in their normal tables. */
internal object InkRevisionPayload {
    const val FORMAT = "ink-refs/1"
    private const val ENCODING = "gzip/1"
    private const val MAGIC = 0x56494E4B // VINK
    private const val MAX_ROWS = 1_000_000

    fun pack(snapshot: InkSnapshot): PackedInkSnapshot {
        val raw = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(snapshot.strokes.size)
                snapshot.strokes.forEach { stroke ->
                    output.writeUTF(stroke.id)
                    output.writeInt(stroke.colorArgb)
                    output.writeNullableBoolean(stroke.colorFollowsTheme)
                    output.writeNullableString(stroke.groupId)
                }
                output.writeIds(snapshot.eraseIds)
                output.writeIds(snapshot.moveIds)
            }
            bytes.toByteArray()
        }
        val compressed = ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).use { it.write(raw) }
            bytes.toByteArray()
        }
        return PackedInkSnapshot(
            format = FORMAT,
            encoding = ENCODING,
            byteCount = raw.size,
            sha256 = raw.digest(),
            payload = compressed,
        )
    }

    fun unpack(row: PageRevisionEntity): InkSnapshot {
        require(row.inkFormat == FORMAT) { "unknown ink revision format '${row.inkFormat}'" }
        require(row.inkEncoding == ENCODING) {
            "unknown ink revision encoding '${row.inkEncoding}'"
        }
        require(row.inkByteCount in 0..MAX_INK_BYTES) {
            "ink revision ${row.id} has an invalid size ${row.inkByteCount}"
        }
        val raw = GZIPInputStream(ByteArrayInputStream(row.inkPayload)).use {
            it.readBounded(row.inkByteCount)
        }
        require(raw.size == row.inkByteCount) {
            "ink revision ${row.id} is ${raw.size} bytes, expected ${row.inkByteCount}"
        }
        require(MessageDigest.isEqual(raw.digest().encodeToByteArray(), row.inkSha256.encodeToByteArray())) {
            "ink revision ${row.id} failed its SHA-256 check"
        }
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readInt() == MAGIC) { "ink revision ${row.id} has the wrong header" }
            val strokes = List(input.readCount()) {
                InkSnapshot.StrokeState(
                    id = input.readUTF(),
                    colorArgb = input.readInt(),
                    colorFollowsTheme = input.readNullableBoolean(),
                    groupId = input.readNullableString(),
                )
            }
            val erases = input.readIds()
            val moves = input.readIds()
            require(input.available() == 0) { "ink revision ${row.id} has trailing data" }
            InkSnapshot(strokes, erases, moves)
        }
    }

    private fun DataOutputStream.writeIds(ids: List<String>) {
        writeInt(ids.size)
        ids.forEach(::writeUTF)
    }

    private fun DataInputStream.readIds(): List<String> = List(readCount()) { readUTF() }

    private fun DataInputStream.readCount(): Int = readInt().also {
        require(it in 0..MAX_ROWS) { "invalid ink revision row count $it" }
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNullableBoolean(value: Boolean?) = writeByte(
        when (value) {
            null -> -1
            false -> 0
            true -> 1
        },
    )

    private fun DataInputStream.readNullableBoolean(): Boolean? = when (val value = readByte().toInt()) {
        -1 -> null
        0 -> false
        1 -> true
        else -> error("invalid nullable boolean $value")
    }

    internal const val MAX_INK_BYTES = 64 * 1024 * 1024
}

private fun ByteArray.digest(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
