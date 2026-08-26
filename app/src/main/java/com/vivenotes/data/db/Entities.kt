package com.vivenotes.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.vivenotes.data.EraserMode

/** Installation-local state. This table is deliberately removed from portable notebook bundles. */
@Entity(tableName = "local_metadata")
data class LocalMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * The one server account whose hierarchy this database currently mirrors.
 *
 * There is deliberately one row rather than one row per account: the app has one local notebook
 * corpus, so switching accounts resets the sync metadata rather than making the same rows silently
 * belong to two servers. [applyingRemote] is transaction-local suppression for the hierarchy
 * triggers; it is set and cleared in the same transaction as an incoming delta.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val singleton: Int = 0,
    val accountId: String,
    val cursor: Long = 0,
    val applyingRemote: Boolean = false,
)

/** Last authoritative server state known for one hierarchy row. */
@Entity(
    tableName = "sync_entity_states",
    primaryKeys = ["kind", "entityId"],
)
data class SyncEntityStateEntity(
    val kind: String,
    val entityId: String,
    val serverVersion: Long,
    /**
     * The complete top-level object last received from the server.
     *
     * OpenAPI 0.2.0 requires clients to retain fields they do not recognise. A later local push
     * overlays this build's known fields on this JSON, so a newer client's extension is not erased.
     */
    val serverJson: String,
)

/**
 * One locally dirty hierarchy row.
 *
 * [generation] changes on every Room insert/update. An acknowledgement deletes only the generation
 * it sent, so an edit committed while the request was in flight stays queued.
 */
@Entity(
    tableName = "sync_outbox",
    primaryKeys = ["kind", "entityId"],
)
data class SyncOutboxEntity(
    val kind: String,
    val entityId: String,
    val generation: Long,
    /** Wall-clock time of the local mutation, including order/expansion writes that do not alter UI `updatedAt`. */
    val changedAt: Long,
)

/**
 * These entities are plain data and double as the domain model — there is no separate mapping
 * layer while the app is single-module.
 *
 * Three properties exist for a sync server that does not exist yet, because they are the
 * expensive ones to retrofit:
 *  - ids are client-generated (see `model.newId`), so offline creation never awaits a server;
 *  - `deletedAt` soft-deletes, because tombstones cannot be reconstructed after a hard delete;
 *  - `updatedAt` orders concurrent edits.
 */
@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int,
    val sortIndex: Int,
    val expanded: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /**
     * When this notebook was taken off the rail, or null while it is on it.
     *
     * Not a second kind of tombstone: nothing under here is deleted, purged or hidden from search,
     * and [deletedAt] stays null. It is a shelf — see `memory/closedNotebooksPlan.md`.
     *
     * Synced, like [expanded] and for the same reason: it lives on the entity, so it travels with
     * it. The server carries it as an unrecognised property of `NotebookFields`, which
     * `additionalProperties: true` permits and the client's retained `serverJson` preserves.
     */
    val closedAt: Long? = null,
    /**
     * When this notebook's contents were moved to the server and removed from this device.
     *
     * Always set together with [closedAt], and always the narrower claim of the two: the notebook,
     * its sections and its pages are all still here. What is gone is `page_content`,
     * `page_revisions`, the three ink tables and the pictures no remaining page reaches — which is
     * effectively all of the bytes, and none of the rows a pulled change can name as a parent. That
     * distinction is the whole reason the skeleton survives; `NotebookCloudArchive` explains what
     * evicting it would do to the cursor.
     */
    val cloudOnlyAt: Long? = null,
)

@Entity(
    tableName = "sections",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("notebookId")],
)
data class SectionEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val name: String,
    val colorArgb: Int,
    val sortIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = SectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sectionId")],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val sectionId: String,
    val title: String,
    val sortIndex: Int,
    /** First line of body text, kept denormalised so the page list never decodes documents. */
    val preview: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * Split from [PageEntity] so listing a section's pages does not load every document body.
 */
@Entity(
    tableName = "page_content",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PageContentEntity(
    @PrimaryKey val pageId: String,
    val docJson: String,
    val updatedAt: Long,
    /**
     * Id of the [com.vivenotes.model.DocumentCodec] that wrote [docJson].
     *
     * Stored per row so the format can change without rewriting every document: rows keep decoding
     * with the codec that produced them, and only new writes use the new format.
     */
    val format: String = "json/1",
)

/**
 * One recoverable checkpoint of a complete page.
 *
 * [PageContentEntity] remains the cheap current state. This table holds older states only, so a
 * normal page load never walks history and saving can atomically preserve the value it replaces.
 * The document payload and compact ink state vector are compressed by the repository. Keeping each
 * codec and checksum beside its bytes lets formats change independently and detects corruption
 * before restore.
 */
@Entity(
    tableName = "page_revisions",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["pageId", "createdAt"])],
)
data class PageRevisionEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val createdAt: Long,
    val format: String,
    val encoding: String,
    val byteCount: Int,
    val sha256: String,
    val payload: ByteArray,
    /**
     * The SQL defaults were what SQLite demanded when these columns were added to a table that
     * already held rows. Nothing reads them — Room writes every column — and they stay because
     * removing one is a schema change for no gain.
     */
    @ColumnInfo(defaultValue = "'none/1'") val inkFormat: String = "none/1",
    @ColumnInfo(defaultValue = "'none'") val inkEncoding: String = "none",
    @ColumnInfo(defaultValue = "0") val inkByteCount: Int = 0,
    @ColumnInfo(defaultValue = "''") val inkSha256: String = "",
    @ColumnInfo(defaultValue = "X''") val inkPayload: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PageRevisionEntity &&
                id == other.id &&
                pageId == other.pageId &&
                createdAt == other.createdAt &&
                format == other.format &&
                encoding == other.encoding &&
                byteCount == other.byteCount &&
                sha256 == other.sha256 &&
                payload.contentEquals(other.payload) &&
                inkFormat == other.inkFormat &&
                inkEncoding == other.inkEncoding &&
                inkByteCount == other.inkByteCount &&
                inkSha256 == other.inkSha256 &&
                inkPayload.contentEquals(other.inkPayload))

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + payload.contentHashCode()) + inkPayload.contentHashCode()
}

/** Lightweight history row for lists; compressed document bytes stay out of ordinary queries. */
data class PageRevisionSummary(
    val id: String,
    val pageId: String,
    val createdAt: Long,
    val byteCount: Int,
)

/** A notebook with its sections, for the navigation rail's expandable tree. */
data class NotebookWithSections(
    @androidx.room.Embedded val notebook: NotebookEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "notebookId",
    )
    val sections: List<SectionEntity>,
) {
    /** Room cannot filter or order a @Relation, so tombstones are removed here. */
    val liveSections: List<SectionEntity>
        get() = sections.filter { it.deletedAt == null }.sortedBy { it.sortIndex }
}

/**
 * One ink stroke, in its own table rather than inside `page_content.docJson`.
 *
 * Autosave rewrites the whole document column on a 400ms debounce, so ink living there would rewrite
 * hundreds of kilobytes because someone typed a character. Ink is also append-mostly and immutable —
 * a stroke, once lifted, never changes — which is what makes row-per-stroke match both the access
 * pattern and, later, the merge: a grow-only set of immutable strokes converges without conflict.
 * See `memory/inkPlan.md` ID2 and §8.
 *
 * Only [points] is opaque. Brush, bounds and order stay in readable columns, so `sqlite3` still
 * answers what is on a page.
 */
@Entity(
    tableName = "ink_strokes",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // (pageId, seq, id) rather than pageId alone, which is two things at once and replaces it
    // instead of joining it, so a stroke insert still maintains one index. It is the exact order
    // `byPage` reads in, so the query has no sorter — the alternative pushes whole rows, `points`
    // blobs included, through SQLite's sorter and spills a densely drawn page to a temp file. And
    // it is the fast path for `nextSeq`: `MAX(seq)` over an equality-constrained prefix is one seek
    // to the end of the range instead of a walk of every row on the page. `pageId` is still the
    // leading column, so the foreign key to `pages` remains indexed.
    indices = [Index("pageId", "seq", "id")],
)
data class InkStrokeEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    /**
     * Draw order within the page: later strokes sit on top, ties broken by [id].
     *
     * **This is a logical clock, not a count.** `nextSeq` allocates `MAX(seq) + 1` over every row of
     * the page including tombstones and including strokes pulled from another device, which is
     * exactly `max(local, incoming) + 1` — so a stroke drawn here is always above everything this
     * device has seen, without trusting anybody's wall clock. Ordering by [createdAt] instead would
     * let a tablet running ten minutes fast paint its ink over strokes drawn after it.
     *
     * Two devices drawing on one page while offline both allocate the same value, which is correct:
     * neither saw the other, so neither is meant to be on top. The tie is broken by [id], a UUIDv7
     * whose hex form sorts chronologically under BINARY collation — deterministic on every device,
     * and within one logical tick it is real draw order. `memory/inkSyncPlan.md` §1.
     */
    val seq: Int,
    val brushFamily: String,
    /** Pinned at creation, never "latest": a stock brush that gains a V2 must not restyle old ink. */
    val brushVersion: Int,
    val sizeDp: Float,
    val colorArgb: Int,
    /**
     * Whether [colorArgb] was the automatic colour rather than one the user picked.
     *
     * Recorded so Switch Background can re-resolve it — see [com.vivenotes.data.automaticColorOr],
     * which owns the rule and explains the tri-state. Stored *beside* the resolved colour rather
     * than instead of it, so a build that does not know this column still finds a colour that was
     * correct when it was written.
     *
     * Null means the intent was never recorded: a row from a build that predates the column, or
     * one imported from a `.vive` such a build wrote.
     */
    val colorFollowsTheme: Boolean? = null,
    val epsilon: Float,
    /**
     * The pen's stabilization level when the stroke was drawn, 0–5.
     *
     * **Applied since 2026-08-10**, which is what this column was always for — it was recorded from
     * the start precisely so the filter could arrive without a storage migration, and it did.
     * `InkCodec.inputModelFor` turns it into the `BrushFamily.InputModel` the stroke is rebuilt
     * through; a stroke's mesh is derived from its inputs *via* that model, so replaying with the
     * wrong level reshapes ink already on the page.
     *
     * Per-stroke rather than per-pen, and read from here rather than from the pen, for the reason
     * `brushVersion` is: a stroke has to come back looking like the stroke that was drawn, not like
     * whatever the pen is set to today.
     *
     * A highlighter row stores 0 meaning **not applicable** rather than *off* — it has no such
     * control — and `InkCodec.family` exempts that family so the value is never read as passthrough.
     */
    val stabilization: Int,
    /** Page-unit bounds, so a draw pass can skip a stroke without decoding it. */
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val points: ByteArray,
    /**
     * Which encoder wrote [points]. Recorded per row for the reason `page_content.format` is:
     * changing format becomes a rolling change rather than a migration.
     */
    val enc: String,
    val createdAt: Long,
    /** Optional logical object group. Geometry remains immutable when membership changes. */
    val groupId: String? = null,
    /** Erasing is a tombstone. Ink is never hard-deleted, so an erase can be replicated. */
    val deletedAt: Long? = null,
) {
    // ByteArray gives identity equals, which would make two rows with the same ink unequal and
    // break any test that compares them. Room does not care; callers do.
    override fun equals(other: Any?): Boolean =
        this === other || (other is InkStrokeEntity && id == other.id && points.contentEquals(other.points))

    override fun hashCode(): Int = 31 * id.hashCode() + points.contentHashCode()
}

/**
 * One eraser gesture. Its round brush and input path rebuild the exact mask on load.
 *
 * The affected stroke ids live in [InkEraseTargetEntity], rather than applying this mask to every
 * stroke on the page: ink drawn later through the same spot must remain visible.
 */
@Entity(
    tableName = "ink_erases",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId")],
)
data class InkEraseEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    /** Normal subtracts the mask; Object removes every disconnected component the mask touches. */
    @ColumnInfo(defaultValue = "'Normal'") val mode: EraserMode = EraserMode.Normal,
    /** Eraser diameter in page dp. */
    val sizeDp: Float,
    val points: ByteArray,
    val enc: String,
    val createdAt: Long,
    /** Undo is a tombstone, matching strokes: redo clears it without replacing this operation. */
    val deletedAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is InkEraseEntity && id == other.id && points.contentEquals(other.points))

    override fun hashCode(): Int = 31 * id.hashCode() + points.contentHashCode()
}

/**
 * The immutable link between an erase gesture and a stroke that existed when it was made.
 *
 * [strokeId] is deliberately **not** a foreign key, and an id naming no stored row is inert —
 * `InkPageLoader` replays an operation against the strokes it can find and ignores the rest.
 * A reference would be a wedge the moment ink replicates: a target recoloured after the erase was
 * made lands in a later delta page than the operation naming it, and a target the seven-day purge
 * has already removed can never arrive at all, so the insert would fail, the transaction would roll
 * back with the sync cursor uncommitted, and the device would re-pull the same delta for ever. It
 * would also let a purge silently rewrite an operation's payload, which is meant to be immutable.
 * `memory/inkSyncPlan.md` §2.2.
 *
 * There is no index on [strokeId] either: nothing queries by it, it existed only because Room asks
 * for one on a foreign key's child column, and an erase across a dense page writes hundreds of these
 * rows inside a latency-sensitive transaction.
 */
@Entity(
    tableName = "ink_erase_targets",
    primaryKeys = ["eraseId", "strokeId"],
    foreignKeys = [
        ForeignKey(
            entity = InkEraseEntity::class,
            parentColumns = ["id"],
            childColumns = ["eraseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class InkEraseTargetEntity(
    val eraseId: String,
    val strokeId: String,
)

data class InkEraseWithTargets(
    @Embedded val erase: InkEraseEntity,
    @Relation(parentColumn = "id", entityColumn = "eraseId")
    val targets: List<InkEraseTargetEntity>,
)

/** One lasso transform, stored as its original page-space polygon and affine parameters. */
@Entity(
    tableName = "ink_moves",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId")],
)
data class InkMoveEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val dxDp: Float,
    val dyDp: Float,
    /** Optional resize composed after the translation, around a page-space anchor. */
    @ColumnInfo(defaultValue = "1") val scaleX: Float = 1f,
    @ColumnInfo(defaultValue = "1") val scaleY: Float = 1f,
    @ColumnInfo(defaultValue = "0") val anchorX: Float = 0f,
    @ColumnInfo(defaultValue = "0") val anchorY: Float = 0f,
    /** Closed lasso vertices encoded in page coordinates. */
    val points: ByteArray,
    val enc: String,
    val createdAt: Long,
    /** Null while this translation participates in replay; stamped while it is undone. */
    val deletedAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is InkMoveEntity && id == other.id && points.contentEquals(other.points))

    override fun hashCode(): Int = 31 * id.hashCode() + points.contentHashCode()
}

/**
 * The source rows that existed inside a lasso when its transform was committed.
 *
 * [strokeId] is an inert id rather than a foreign key, for the reasons spelled out on
 * [InkEraseTargetEntity].
 */
@Entity(
    tableName = "ink_move_targets",
    primaryKeys = ["moveId", "strokeId"],
    foreignKeys = [
        ForeignKey(
            entity = InkMoveEntity::class,
            parentColumns = ["id"],
            childColumns = ["moveId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class InkMoveTargetEntity(
    val moveId: String,
    val strokeId: String,
)

data class InkMoveWithTargets(
    @Embedded val move: InkMoveEntity,
    @Relation(parentColumn = "id", entityColumn = "moveId")
    val targets: List<InkMoveTargetEntity>,
)

/**
 * What is known *about* an imported picture. The pixels are not here.
 *
 * **The bytes are a file, not a column** — `filesDir/attachments/<sha256>`, with this row pointing at
 * it. Ink argues for a blob column because a stroke is a few hundred bytes; a photograph is a few
 * million, and the two do not want the same home:
 *
 *  - Every query that touches a table pays for the width of the rows it walks. A page's picture list
 *    is a handful of numbers; putting megabytes in the same rows makes reading those numbers cost
 *    what reading the pictures costs.
 *  - `notes.db` is one file that gets copied, backed up and — eventually — synced whole. Content that
 *    is already immutable and already addressed by its hash has no business inflating it.
 *
 * **Keyed by the hash of the bytes, so the same picture inserted twice is one file.** That is not
 * only thrift: it is what lets a page be duplicated, or a picture copied and pasted, without deciding
 * who owns the pixels. [refCount] is what makes deleting safe — the file goes when the last outline
 * referencing it does, and never while another still points at it.
 *
 * Not tied to a page by a foreign key, deliberately. One picture can appear on several pages, which a
 * `pageId` column would have to lie about.
 */
/**
 * A stroke's colour together with whether it was automatic.
 *
 * The pair travels as one because the two are only meaningful together: undoing a recolour has to
 * put back the colour *and* whether it was a choice, or the stroke comes back looking right and
 * stops following the canvas. See [com.vivenotes.data.automaticColorOr].
 */
data class StrokeColor(val argb: Int, val followsTheme: Boolean?)

@Entity(tableName = "attachments")
data class AttachmentEntity(
    /** SHA-256 of the stored bytes, lowercase hex. Also the file's name on disk. */
    @PrimaryKey val id: String,
    val mimeType: String,
    /** Pixel dimensions of what was stored, after any downscale at import. */
    val pixelWidth: Int,
    val pixelHeight: Int,
    val byteCount: Long,
    /** How many outlines currently point at this. Zero means the file may be swept. */
    val refCount: Int = 0,
    val createdAt: Long,
)

/** What happened the last time a picture was read. */
enum class ImageTextStatus {
    /** Text was found and is in the row. */
    Read,

    /** The picture was read successfully and holds no searchable text. */
    Empty,

    /**
     * Reading failed — the file was missing, undecodable, or inference threw.
     *
     * Stored rather than left absent so a picture that cannot be read is not retried on every query
     * for the rest of the notebook's life. A new engine id retries it; nothing else does.
     */
    Failed,
}

/**
 * The text PP-OCRv5 read out of one picture — `memory/imageOcrPlan.md` IO2, IO3, IO11.
 *
 * **Keyed by the attachment, which is keyed by the hash of the pixels.** So the same screenshot
 * pasted on nine pages is one row, one inference and one copy of the text, by construction rather
 * than by a de-duplication pass somebody has to remember to run. There is deliberately no `pageId`
 * and no outline id here: this is what the *picture* says, and where it is placed is the document's
 * business.
 *
 * **It is a child of `attachments` with `ON DELETE CASCADE`.** `AttachmentStore.release` deletes the
 * attachment row and its file together once the last outline pointing at it is gone for good, and
 * this row goes with them. A derived cache outliving its subject is the bug this table is most
 * likely to grow, so the invariant is checked as well as declared — see `ImageTextDao.deleteOrphans`.
 *
 * Derived, and therefore regenerable: [engine] records which model and preprocessing wrote [text],
 * so changing the pipeline is a rolling re-read rather than a migration, and the whole table can be
 * deleted without losing anything a user typed or drew. That is also why the export in
 * `NotebookTransferManager` drops it.
 *
 * The line boxes are **not** stored. Highlighting the matched line on the picture would need them;
 * opening a hit selects the picture instead (IO10), and a row that stays narrow is a row that stays
 * cheap to read for every page of a notebook on every keystroke.
 */
@Entity(
    tableName = "attachment_text",
    foreignKeys = [
        ForeignKey(
            entity = AttachmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["attachmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AttachmentTextEntity(
    @PrimaryKey val attachmentId: String,
    /** The recognized lines, joined by newlines, in reading order. Empty unless [status] is Read. */
    val text: String,
    val lineCount: Int,
    /** Mean per-character confidence over the lines that were kept. */
    val confidence: Float,
    /** Model plus preprocessing version, e.g. `ppocrv5-en/1`. A row from another id is stale. */
    val engine: String,
    val status: ImageTextStatus,
    /** How long the read took, kept because it is the only latency measurement from real devices. */
    val durationMs: Long,
    val updatedAt: Long,
)

/** What happened the last time one page's replayed ink was read. */
enum class InkTextStatus {
    Read,
    Empty,
    Failed,
}

/**
 * Derived handwriting text for one page — `memory/handwritingSearchPlan.md`.
 *
 * Unlike picture OCR this is keyed by page: the subject is the page's replayed ink together with
 * the geometry of any ink-only table on it. Repository ink mutations delete this row in the same
 * transaction, and [layoutHash] catches the other half changing through a document edit.
 */
@Entity(
    tableName = "ink_text",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class InkTextEntity(
    @PrimaryKey val pageId: String,
    /** JSON-encoded recognized regions, including source ids, bounds and an optional alternate. */
    val regionsJson: String,
    val regionCount: Int,
    val confidence: Float,
    /** Stable signature of ink-only table cell geometry in the current page document. */
    val layoutHash: String,
    val engine: String,
    val status: InkTextStatus,
    val durationMs: Long,
    val updatedAt: Long,
)

/** Monotonic guard preventing an OCR pass from saving after the ink it read was edited. */
@Entity(
    tableName = "ink_text_generation",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class InkTextGenerationEntity(
    @PrimaryKey val pageId: String,
    val generation: Long,
)
