package com.vivenotes.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

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
