package com.vivenotes.data

import com.vivenotes.data.db.PageRevisionEntity
import com.vivenotes.model.DocumentCodecs
import com.vivenotes.model.Outline
import com.vivenotes.model.migrated

/**
 * Which pictures a document places.
 *
 * The document is the only record of which pictures a page shows. `refCount` cannot answer it — a
 * paste does not count, and a pulled picture arrives at zero — so everything that has to decide
 * whether a picture is still wanted reads the documents through here: sync's `blobRefs`, cloud
 * eviction, and [AttachmentSweep].
 */
internal object DocumentPictures {

    /**
     * A field name only [Outline.Image] has, used to skip decoding a body that cannot mention a
     * picture. Both codecs write field names as text, so it holds for `cbor/1` as well, and
     * `PageContentDao.picturePlacingBodies` applies the same test in SQL.
     */
    const val IMAGE_FIELD_HINT = "attachmentId"

    /**
     * The attachments a stored document places, in the order it places them.
     *
     * Guarded by a substring test before the decode. Most pages have no picture at all, and a full
     * parse per page is the most expensive thing a pull or a sweep would otherwise do. A page whose
     * text happens to contain the word costs one wasted decode.
     *
     * An undecodable body yields nothing rather than throwing: the editor already refuses to write
     * to a page it cannot read.
     */
    fun idsIn(docJson: String, format: String): List<String> {
        if (!docJson.contains(IMAGE_FIELD_HINT)) return emptyList()
        val codec = DocumentCodecs.byId(format) ?: return emptyList()
        return runCatching {
            codec.decode(docJson.encodeToByteArray()).migrated().outlines
                .filterIsInstance<Outline.Image>()
                .map { it.attachmentId }
        }.getOrDefault(emptyList())
    }

    /** The pictures a saved version places, or none if its payload cannot be read. */
    fun idsIn(revision: PageRevisionEntity): List<String> = runCatching {
        DocumentRevisionPayload.unpack(revision).outlines
            .filterIsInstance<Outline.Image>()
            .map { it.attachmentId }
    }.getOrDefault(emptyList())

    /**
     * The form `page_revisions.pictureIds` stores: distinct ids, comma-separated, empty for none.
     * A digest is 64 hex characters, so no id can contain the separator.
     */
    fun encode(ids: Collection<String>): String = ids.distinct().joinToString(SEPARATOR)

    fun decode(stored: String): List<String> =
        if (stored.isEmpty()) emptyList() else stored.split(SEPARATOR)

    private const val SEPARATOR = ","
}
