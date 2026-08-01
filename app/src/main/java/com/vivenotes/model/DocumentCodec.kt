package com.vivenotes.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * A wire or storage representation for [PageDoc].
 *
 * `kotlinx.serialization` separates the *shape* of a type from its *encoding*: `@Serializable`
 * generates a `KSerializer`, and a format consumes it. Naming that seam here means the local
 * database and the sync protocol can use different formats — JSON on disk where being able to read
 * a note with `sqlite3` is worth a lot, something compact like MessagePack or CBOR on the wire —
 * without either choice reaching into the model.
 */
interface DocumentCodec {

    /**
     * Stable identifier stored alongside encoded bytes.
     *
     * Recording the format that wrote a row is what makes changing formats a rolling change rather
     * than a migration: old rows keep decoding with the codec that produced them.
     */
    val id: String

    fun encode(doc: PageDoc): ByteArray

    fun decode(bytes: ByteArray): PageDoc
}

/**
 * A codec whose output is valid text, and so can live in a TEXT column, a log line or a diff.
 * Binary formats deliberately do not implement this: their bytes are not round-trip safe through
 * a string.
 */
interface TextDocumentCodec : DocumentCodec {

    fun encodeToString(doc: PageDoc): String

    fun decodeFromString(text: String): PageDoc

    override fun encode(doc: PageDoc): ByteArray = encodeToString(doc).encodeToByteArray()

    override fun decode(bytes: ByteArray): PageDoc = decodeFromString(bytes.decodeToString())
}

object JsonDocumentCodec : TextDocumentCodec {

    override val id: String = "json/1"

    override fun encodeToString(doc: PageDoc): String =
        DocumentJson.encodeToString(PageDoc.serializer(), doc)

    override fun decodeFromString(text: String): PageDoc =
        DocumentJson.decodeFromString(PageDoc.serializer(), text)
}

/**
 * Compact binary encoding, for the sync protocol and optionally for local storage.
 *
 * CBOR rather than MessagePack: the two are near-equivalent on the wire — both are map-based,
 * self-describing binary formats with wide cross-language support — but CBOR ships in
 * `kotlinx-serialization` itself and is standardised as RFC 8949, whereas every MessagePack
 * binding for kotlinx is community-maintained. For the format your entire note database is written
 * in, that difference is the whole argument.
 *
 * Note this is not a [TextDocumentCodec]: its output is arbitrary bytes and is not round-trip safe
 * through a `String`, so storing it needs a BLOB column rather than TEXT.
 */
@OptIn(ExperimentalSerializationApi::class)
object CborDocumentCodec : DocumentCodec {

    override val id: String = "cbor/1"

    private val cbor = Cbor {
        encodeDefaults = false
        // Same forward-compatibility guarantee as JSON: an older build must degrade rather than
        // fail when it meets a document written by a newer one.
        ignoreUnknownKeys = true
    }

    override fun encode(doc: PageDoc): ByteArray = cbor.encodeToByteArray(PageDoc.serializer(), doc)

    override fun decode(bytes: ByteArray): PageDoc = cbor.decodeFromByteArray(PageDoc.serializer(), bytes)
}

/**
 * Codecs this build can read.
 *
 * Adding MessagePack means implementing [DocumentCodec] over a msgpack `SerialFormat` and
 * registering it here — the model, the repository and the editor are unaffected. Two caveats
 * carried over from the JSON configuration: `classDiscriminator` is JSON-specific, so sealed types
 * are tagged differently by binary formats, and any replacement must support ignoring unknown keys
 * or a document written by a newer build will fail to decode instead of degrading gracefully.
 */
object DocumentCodecs {

    private val known: Map<String, DocumentCodec> =
        listOf(JsonDocumentCodec, CborDocumentCodec).associateBy { it.id }

    /** Format written for newly stored documents. */
    val default: TextDocumentCodec = JsonDocumentCodec

    fun byId(id: String): DocumentCodec? = known[id]
}
