package com.vivenotes.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.vivenotes.data.EraserMode

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
 * See `docs/inkPlan.md` ID2 and §8.
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
    indices = [Index("pageId")],
)
data class InkStrokeEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    /** Draw order within the page. Later strokes sit on top. */
    val seq: Int,
    val brushFamily: String,
    /** Pinned at creation, never "latest": a stock brush that gains a V2 must not restyle old ink. */
    val brushVersion: Int,
    val sizeDp: Float,
    val colorArgb: Int,
    val epsilon: Float,
    /**
     * The pen's stabilization level when the stroke was drawn.
     *
     * Recorded rather than applied: stabilization still needs the reproducible pre-filter described
     * in `docs/inkPlan.md` §4. It is kept here now so that filter can later reproduce the stroke
     * without a storage migration.
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

/** The immutable link between an erase gesture and a stroke that existed when it was made. */
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
        ForeignKey(
            entity = InkStrokeEntity::class,
            parentColumns = ["id"],
            childColumns = ["strokeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("strokeId")],
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

/** One lasso translation, stored as its original page-space polygon and delta. */
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

/** The source rows that existed inside a lasso when its move was committed. */
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
        ForeignKey(
            entity = InkStrokeEntity::class,
            parentColumns = ["id"],
            childColumns = ["strokeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("strokeId")],
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
