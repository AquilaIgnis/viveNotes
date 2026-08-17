package com.vivenotes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalMetadataDao {

    @Query("SELECT value FROM local_metadata WHERE `key` = :key")
    suspend fun value(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(metadata: LocalMetadataEntity)

    @Query("DELETE FROM local_metadata WHERE `key` = :key")
    suspend fun delete(key: String)
}

/** Durable hierarchy-sync bookkeeping. Network DTO mapping stays in `data/sync`. */
@Dao
interface SyncDao {

    @Query("SELECT * FROM sync_state WHERE singleton = 0")
    suspend fun state(): SyncStateEntity?

    @Upsert
    suspend fun putState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clearState()

    @Query("UPDATE sync_state SET applyingRemote = :applying WHERE singleton = 0")
    suspend fun setApplyingRemote(applying: Boolean)

    @Query("UPDATE sync_state SET cursor = :cursor WHERE singleton = 0")
    suspend fun setCursor(cursor: Long)

    @Query("DELETE FROM sync_entity_states")
    suspend fun clearEntityStates()

    @Query("SELECT * FROM sync_entity_states WHERE kind = :kind AND entityId = :entityId")
    suspend fun entityState(kind: String, entityId: String): SyncEntityStateEntity?

    @Upsert
    suspend fun putEntityState(state: SyncEntityStateEntity)

    @Query("DELETE FROM sync_entity_states WHERE kind = :kind AND entityId = :entityId")
    suspend fun deleteEntityState(kind: String, entityId: String)

    @Query("DELETE FROM sync_outbox")
    suspend fun clearOutbox()

    @Query(
        "SELECT * FROM sync_outbox ORDER BY " +
            "CASE kind " +
            "WHEN 'notebook' THEN 0 WHEN 'section' THEN 1 WHEN 'page' THEN 2 " +
            "WHEN 'pageContent' THEN 3 ELSE 4 END, entityId " +
            "LIMIT :limit",
    )
    suspend fun outbox(limit: Int): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE kind = :kind AND entityId = :entityId")
    suspend fun outboxEntry(kind: String, entityId: String): SyncOutboxEntity?

    @Query(
        "DELETE FROM sync_outbox WHERE kind = :kind AND entityId = :entityId " +
            "AND generation = :generation",
    )
    suspend fun deleteOutboxGeneration(kind: String, entityId: String, generation: Long): Int

    @Query("DELETE FROM sync_outbox WHERE kind = :kind AND entityId = :entityId")
    suspend fun deleteOutbox(kind: String, entityId: String)

    /**
     * Drops queued work for rows that no longer exist.
     *
     * The outbox holds keys, not copies, and nothing joins it to the tables it names — so a row
     * removed outright rather than tombstoned leaves an entry the next push can only answer with
     * "dirty notebook disappeared", failing every push from then on. Removing rows and pruning here
     * belong in one transaction.
     */
    @Query(
        "DELETE FROM sync_outbox WHERE " +
            "(kind = 'notebook' AND entityId NOT IN (SELECT id FROM notebooks)) OR " +
            "(kind = 'section' AND entityId NOT IN (SELECT id FROM sections)) OR " +
            "(kind = 'page' AND entityId NOT IN (SELECT id FROM pages)) OR " +
            "(kind = 'pageContent' AND entityId NOT IN (SELECT pageId FROM page_content))",
    )
    suspend fun pruneOrphanedOutbox()

    @Query(
        "INSERT OR IGNORE INTO sync_outbox(kind, entityId, generation, changedAt) " +
            "SELECT :kind, :entityId, 1, " +
            "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)",
    )
    suspend fun enqueueIfAbsent(kind: String, entityId: String)

    @Query(
        "INSERT OR IGNORE INTO sync_outbox(kind, entityId, generation, changedAt) " +
            "SELECT 'notebook', id, 1, updatedAt FROM notebooks",
    )
    suspend fun enqueueAllNotebooks()

    @Query(
        "INSERT OR IGNORE INTO sync_outbox(kind, entityId, generation, changedAt) " +
            "SELECT 'section', id, 1, updatedAt FROM sections",
    )
    suspend fun enqueueAllSections()

    @Query(
        "INSERT OR IGNORE INTO sync_outbox(kind, entityId, generation, changedAt) " +
            "SELECT 'page', id, 1, updatedAt FROM pages",
    )
    suspend fun enqueueAllPages()

    @Query(
        "INSERT OR IGNORE INTO sync_outbox(kind, entityId, generation, changedAt) " +
            "SELECT 'pageContent', pageId, 1, updatedAt FROM page_content",
    )
    suspend fun enqueueAllPageContents()
}

/** Raw Room projection for the typed recovery model in `data/DeletionRecovery.kt`. */
data class DeletedItemRow(
    val id: String,
    val kind: String,
    val name: String,
    val notebookName: String?,
    val sectionName: String?,
    val deletedAt: Long,
    val sectionCount: Int,
    val pageCount: Int,
)

/**
 * The app-wide soft-delete view.
 *
 * Only the highest deleted ancestor is returned. If a notebook is gone, its section/page rows are
 * implementation detail until it is restored; likewise a page inside a deleted section. This makes
 * each row correspond to one user action and prevents a parent restore from overwriting older child
 * deletion decisions.
 */
@Dao
interface DeletionRecoveryDao {

    @Query(
        """
        SELECT
            n.id AS id,
            'Notebook' AS kind,
            n.name AS name,
            NULL AS notebookName,
            NULL AS sectionName,
            COALESCE(n.deletedAt, 0) AS deletedAt,
            (
                SELECT COUNT(*) FROM sections s
                WHERE s.notebookId = n.id AND s.deletedAt IS NULL
            ) AS sectionCount,
            (
                SELECT COUNT(*) FROM pages p
                JOIN sections s ON s.id = p.sectionId
                WHERE s.notebookId = n.id
                    AND s.deletedAt IS NULL
                    AND p.deletedAt IS NULL
            ) AS pageCount
        FROM notebooks n
        WHERE n.deletedAt IS NOT NULL

        UNION ALL

        SELECT
            s.id AS id,
            'Section' AS kind,
            s.name AS name,
            n.name AS notebookName,
            NULL AS sectionName,
            COALESCE(s.deletedAt, 0) AS deletedAt,
            0 AS sectionCount,
            (
                SELECT COUNT(*) FROM pages p
                WHERE p.sectionId = s.id AND p.deletedAt IS NULL
            ) AS pageCount
        FROM sections s
        JOIN notebooks n ON n.id = s.notebookId
        WHERE s.deletedAt IS NOT NULL AND n.deletedAt IS NULL

        UNION ALL

        SELECT
            p.id AS id,
            'Page' AS kind,
            CASE WHEN p.title = '' THEN 'Untitled page' ELSE p.title END AS name,
            n.name AS notebookName,
            s.name AS sectionName,
            COALESCE(p.deletedAt, 0) AS deletedAt,
            0 AS sectionCount,
            0 AS pageCount
        FROM pages p
        JOIN sections s ON s.id = p.sectionId
        JOIN notebooks n ON n.id = s.notebookId
        WHERE p.deletedAt IS NOT NULL
            AND s.deletedAt IS NULL
            AND n.deletedAt IS NULL

        ORDER BY deletedAt DESC, name
        """,
    )
    fun observeRoots(): Flow<List<DeletedItemRow>>

    @Query(
        "UPDATE notebooks SET deletedAt = NULL, updatedAt = :now " +
            "WHERE id = :id AND deletedAt IS NOT NULL",
    )
    suspend fun restoreNotebook(id: String, now: Long): Int

    @Query(
        "UPDATE sections SET deletedAt = NULL, updatedAt = :now " +
            "WHERE id = :id AND deletedAt IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM notebooks n " +
            "WHERE n.id = sections.notebookId AND n.deletedAt IS NULL)",
    )
    suspend fun restoreSection(id: String, now: Long): Int

    @Query(
        "UPDATE pages SET deletedAt = NULL, updatedAt = :now " +
            "WHERE id = :id AND deletedAt IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM sections s JOIN notebooks n ON n.id = s.notebookId " +
            "WHERE s.id = pages.sectionId AND s.deletedAt IS NULL AND n.deletedAt IS NULL)",
    )
    suspend fun restorePage(id: String, now: Long): Int
}

/**
 * The hard-delete half of the recovery policy.
 *
 * Recovery and purge deliberately have separate DAOs: the former is user initiated and clears one
 * tombstone, while this one is scheduled maintenance and may remove many expired rows at once.
 * Every child table already has an `ON DELETE CASCADE` foreign key, so these six parent deletes are
 * the complete operation rather than the first half of manual orphan cleanup.
 */
@Dao
interface DeletionPurgeDao {

    // The three ink deletes carry the same outbox guard as the hierarchy ones below, even though no
    // ink kind is queued yet: the row that must never be hard-deleted is one the server has not
    // acknowledged, and the day ink becomes a sync kind is the day this table starts holding those.
    // A guard added with the kind would be a guard that had to be remembered, and the seven-day
    // window is long enough that nobody would notice it missing until deletes stopped propagating.

    @Query(
        "DELETE FROM ink_erases WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM sync_outbox o " +
            "WHERE o.kind = 'inkErase' AND o.entityId = ink_erases.id)",
    )
    suspend fun expiredInkErases(cutoff: Long): Int

    @Query(
        "DELETE FROM ink_moves WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM sync_outbox o " +
            "WHERE o.kind = 'inkMove' AND o.entityId = ink_moves.id)",
    )
    suspend fun expiredInkMoves(cutoff: Long): Int

    @Query(
        "DELETE FROM ink_strokes WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM sync_outbox o " +
            "WHERE o.kind = 'inkStroke' AND o.entityId = ink_strokes.id)",
    )
    suspend fun expiredInkStrokes(cutoff: Long): Int

    @Query(
        "DELETE FROM notebooks WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM sync_outbox o " +
            "WHERE o.kind = 'notebook' AND o.entityId = notebooks.id)",
    )
    suspend fun expiredNotebooks(cutoff: Long): Int

    @Query(
        "DELETE FROM sections WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM sync_outbox o " +
            "WHERE o.kind = 'section' AND o.entityId = sections.id)",
    )
    suspend fun expiredSections(cutoff: Long): Int

    @Query(
        "DELETE FROM pages WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM sync_outbox o " +
            "WHERE o.kind = 'page' AND o.entityId = pages.id)",
    )
    suspend fun expiredPages(cutoff: Long): Int
}

@Dao
interface NotebookDao {

    @Transaction
    @Query("SELECT * FROM notebooks WHERE deletedAt IS NULL ORDER BY sortIndex")
    fun observeTree(): Flow<List<NotebookWithSections>>

    @Query("SELECT * FROM notebooks WHERE deletedAt IS NULL ORDER BY sortIndex")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notebook: NotebookEntity)

    @Upsert
    suspend fun upsert(notebook: NotebookEntity)

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun byId(id: String): NotebookEntity?

    /** Retires an installation-generated placeholder without hiding its removal from active sync. */
    @Query("UPDATE notebooks SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun retirePlaceholder(id: String, now: Long)

    @Query("UPDATE notebooks SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE notebooks SET expanded = :expanded WHERE id = :id")
    suspend fun setExpanded(id: String, expanded: Boolean)

    @Query("UPDATE notebooks SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM notebooks")
    suspend fun nextSortIndex(): Int

    @Query("SELECT COUNT(*) FROM notebooks WHERE deletedAt IS NULL")
    suspend fun count(): Int

    /**
     * Removes a notebook outright and, by cascade, its sections, pages and their content.
     *
     * Deliberately not a tombstone. A tombstone is how a row that other devices have *seen* is
     * deleted, because they have to learn that it went; this is for a notebook no server and no
     * other device has ever held — the seeded starter that [com.vivenotes.data.sync.HierarchySync]
     * discards when this installation turns out to be joining an account that already has a tree.
     */
    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface SectionDao {

    @Query("SELECT * FROM sections WHERE notebookId = :notebookId AND deletedAt IS NULL ORDER BY sortIndex")
    fun observeIn(notebookId: String): Flow<List<SectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(section: SectionEntity)

    @Upsert
    suspend fun upsert(section: SectionEntity)

    @Query("UPDATE sections SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE sections SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM sections WHERE notebookId = :notebookId")
    suspend fun nextSortIndex(notebookId: String): Int

    /** See [PageDao.setSortIndex] for why this one column moves without touching `updatedAt`. */
    @Query("UPDATE sections SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun setSortIndex(id: String, sortIndex: Int)

    @Query("SELECT * FROM sections WHERE id = :id")
    suspend fun byId(id: String): SectionEntity?

    @Query("SELECT * FROM sections WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SectionEntity>

    @Query("SELECT * FROM sections WHERE notebookId = :notebookId AND deletedAt IS NULL ORDER BY sortIndex")
    suspend fun inNotebook(notebookId: String): List<SectionEntity>

    /** Transfer reconciliation needs tombstones too: the archive owns the whole notebook state. */
    @Query("SELECT * FROM sections WHERE notebookId = :notebookId ORDER BY sortIndex")
    suspend fun allInNotebook(notebookId: String): List<SectionEntity>
}

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE sectionId = :sectionId AND deletedAt IS NULL ORDER BY sortIndex")
    fun observeIn(sectionId: String): Flow<List<PageEntity>>

    /** [observeIn] read once, for a reorder that needs the authoritative membership up front. */
    @Query("SELECT * FROM pages WHERE sectionId = :sectionId AND deletedAt IS NULL ORDER BY sortIndex")
    suspend fun inSection(sectionId: String): List<PageEntity>

    @Query("SELECT COUNT(*) FROM pages WHERE sectionId = :sectionId AND deletedAt IS NULL")
    suspend fun countIn(sectionId: String): Int

    @Query("SELECT * FROM pages WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<PageEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity)

    @Upsert
    suspend fun upsert(page: PageEntity)

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun byId(id: String): PageEntity?

    @Query("SELECT * FROM pages WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<PageEntity>

    @Query("UPDATE pages SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE pages SET preview = :preview, updatedAt = :now WHERE id = :id")
    suspend fun updatePreview(id: String, preview: String, now: Long)

    @Query("UPDATE pages SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM pages WHERE sectionId = :sectionId")
    suspend fun nextSortIndex(sectionId: String): Int

    /**
     * Moves a row within its section.
     *
     * **`updatedAt` is deliberately left alone**, unlike every other write in this DAO. It is the
     * one the list renders under each title as "date modified" and the one `PageSort.Recent` orders
     * by, so bumping it would make a single drag stamp every page in the section "Just now" and
     * flatten the very ordering the user might be about to switch to. Where a page sits is not when
     * it was last written.
     */
    @Query("UPDATE pages SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun setSortIndex(id: String, sortIndex: Int)

    @Query("SELECT * FROM pages WHERE deletedAt IS NULL AND (title LIKE '%' || :query || '%' OR preview LIKE '%' || :query || '%') ORDER BY updatedAt DESC LIMIT 50")
    fun search(query: String): Flow<List<PageEntity>>

    /**
     * Every live page of a notebook, in reading order — the corpus the Content panel searches
     * (`docs/searchPlan.md` CS2).
     *
     * Metadata only: `page_content` is a separate table precisely so that listing pages does not drag
     * every document body along, and the search index relies on that to decide which bodies it
     * actually needs (CS7).
     */
    @Query(
        "SELECT p.* FROM pages p JOIN sections s ON s.id = p.sectionId " +
            "WHERE s.notebookId = :notebookId AND p.deletedAt IS NULL AND s.deletedAt IS NULL " +
            "ORDER BY s.sortIndex, p.sortIndex",
    )
    suspend fun inNotebook(notebookId: String): List<PageEntity>

    /** Every page row belonging to the notebook, including both page and section tombstones. */
    @Query(
        "SELECT p.* FROM pages p JOIN sections s ON s.id = p.sectionId " +
            "WHERE s.notebookId = :notebookId ORDER BY s.sortIndex, p.sortIndex",
    )
    suspend fun allInNotebook(notebookId: String): List<PageEntity>
}

@Dao
interface PageContentDao {

    @Query("SELECT * FROM page_content WHERE pageId = :pageId")
    suspend fun byId(pageId: String): PageContentEntity?

    /** Keeps an already-open editor in step when sync or import replaces its stored body. */
    @Query("SELECT * FROM page_content WHERE pageId = :pageId")
    fun observeById(pageId: String): Flow<PageContentEntity?>

    /** The bodies of named pages, for the search index's incremental rebuild — CS7. */
    @Query("SELECT * FROM page_content WHERE pageId IN (:pageIds)")
    suspend fun byIds(pageIds: List<String>): List<PageContentEntity>

    @Upsert
    suspend fun upsert(content: PageContentEntity)

    @Query("DELETE FROM page_content WHERE pageId = :pageId")
    suspend fun delete(pageId: String)
}

@Dao
interface PageRevisionDao {

    @Insert
    suspend fun insert(revision: PageRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(revision: PageRevisionEntity): Long

    @Query("SELECT * FROM page_revisions WHERE id IN (:ids)")
    suspend fun byGlobalIds(ids: List<String>): List<PageRevisionEntity>

    @Query("SELECT * FROM page_revisions WHERE pageId IN (:pageIds)")
    suspend fun byPageIds(pageIds: List<String>): List<PageRevisionEntity>

    @Query("SELECT * FROM page_revisions WHERE id = :id AND pageId = :pageId")
    suspend fun byId(pageId: String, id: String): PageRevisionEntity?

    @Query(
        "SELECT id, pageId, createdAt, byteCount + inkByteCount AS byteCount " +
            "FROM page_revisions " +
            "WHERE pageId = :pageId ORDER BY createdAt DESC, id DESC",
    )
    suspend fun history(pageId: String): List<PageRevisionSummary>

    @Query("SELECT MAX(createdAt) FROM page_revisions WHERE pageId = :pageId")
    suspend fun newestTimestamp(pageId: String): Long?

    /** Exact-content candidates used to keep restore toggles from cloning the same checkpoints. */
    @Query(
        "SELECT * FROM page_revisions WHERE pageId = :pageId AND format = :format " +
            "AND byteCount = :byteCount AND sha256 = :sha256 AND inkFormat = :inkFormat " +
            "AND inkByteCount = :inkByteCount AND inkSha256 = :inkSha256 " +
            "ORDER BY createdAt DESC, id DESC",
    )
    suspend fun matchingContent(
        pageId: String,
        format: String,
        byteCount: Int,
        sha256: String,
        inkFormat: String,
        inkByteCount: Int,
        inkSha256: String,
    ): List<PageRevisionEntity>

    @Query("DELETE FROM page_revisions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query(
        "DELETE FROM page_revisions WHERE pageId = :pageId AND id NOT IN " +
            "(SELECT id FROM page_revisions WHERE pageId = :pageId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimToNewest(pageId: String, keep: Int)
}

@Dao
interface InkStrokeDao {

    /**
     * A page's live ink, in draw order. Tombstones are excluded, never removed.
     *
     * `seq` alone is not a total order: two devices drawing on one page while offline allocate the
     * same value, and SQLite would then settle the tie by rowid — which differs per device, so the
     * same rows would paint in a different order on each one. `id` is the tiebreak the erase and
     * move streams already use. See [InkStrokeEntity.seq].
     */
    @Query(
        "SELECT * FROM ink_strokes WHERE pageId = :pageId AND deletedAt IS NULL ORDER BY seq, id",
    )
    suspend fun byPage(pageId: String): List<InkStrokeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: InkStrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(strokes: List<InkStrokeEntity>)

    @Upsert
    suspend fun upsert(strokes: List<InkStrokeEntity>)

    @Query("SELECT * FROM ink_strokes WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<InkStrokeEntity>

    @Query("UPDATE ink_strokes SET deletedAt = :now WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<String>, now: Long)

    @Query("UPDATE ink_strokes SET deletedAt = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: List<String>)

    @Query("SELECT DISTINCT pageId FROM ink_strokes WHERE id IN (:ids)")
    suspend fun pageIdsFor(ids: List<String>): List<String>

    @Query(
        "UPDATE ink_strokes SET deletedAt = :now " +
            "WHERE pageId = :pageId AND deletedAt IS NULL",
    )
    suspend fun softDeletePage(pageId: String, now: Long)

    @Query(
        "UPDATE ink_strokes SET deletedAt = NULL, colorArgb = :colorArgb, " +
            "colorFollowsTheme = :followsTheme, groupId = :groupId " +
            "WHERE pageId = :pageId AND id = :id",
    )
    suspend fun restoreSnapshotState(
        pageId: String,
        id: String,
        colorArgb: Int,
        followsTheme: Boolean?,
        groupId: String?,
    )

    /**
     * Sets a stroke's colour and whether it is automatic.
     *
     * Both, because undo has to restore the pair — a recolour writes `followsTheme = false`, and
     * undoing it must put back what the stroke was before rather than leaving it deliberate. See
     * `PageStroke.recolor`, which makes the same change to the in-memory copy.
     */
    @Query(
        "UPDATE ink_strokes SET colorArgb = :colorArgb, colorFollowsTheme = :followsTheme " +
            "WHERE id = :id",
    )
    suspend fun setColor(id: String, colorArgb: Int, followsTheme: Boolean?)

    @Query("UPDATE ink_strokes SET groupId = :groupId WHERE id = :id")
    suspend fun setGroup(id: String, groupId: String?)

    /**
     * The next draw-order value for a page — the allocator for [InkStrokeEntity.seq].
     *
     * Deliberately over **every** row of the page, tombstones and rows pulled from another device
     * included: that is what makes it `max(local, incoming) + 1` rather than a count, so a stroke
     * drawn here lands above everything this device has seen. It runs on every stroke commit, which
     * is why `(pageId, seq, id)` exists — the maximum of an equality-constrained prefix is one seek
     * rather than a walk of the page's rows.
     */
    @Query("SELECT COALESCE(MAX(seq), -1) + 1 FROM ink_strokes WHERE pageId = :pageId")
    suspend fun nextSeq(pageId: String): Int
}

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun byId(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(attachment: AttachmentEntity): Long

    /** Claims a reference. Inserting the same picture twice adds a claim rather than a second copy. */
    @Query("UPDATE attachments SET refCount = refCount + 1 WHERE id = :id")
    suspend fun retain(id: String)

    /** Never below zero: a double release must not make a live attachment look sweepable. */
    @Query("UPDATE attachments SET refCount = MAX(refCount - 1, 0) WHERE id = :id")
    suspend fun release(id: String)

    @Query("SELECT * FROM attachments WHERE refCount <= 0")
    suspend fun unreferenced(): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE id = :id AND refCount <= 0")
    suspend fun deleteIfUnreferenced(id: String)
}

/** The recognized-text cache of [AttachmentTextEntity] — `memory/imageOcrPlan.md` IO2, IO3, IO6. */
@Dao
interface ImageTextDao {

    /**
     * Rows for the pictures a notebook contains.
     *
     * Only `Read` rows carry text, but every status is returned: the caller needs to know that a
     * picture has already been tried, or it will queue it again on the next keystroke.
     */
    @Query("SELECT * FROM attachment_text WHERE attachmentId IN (:attachmentIds)")
    suspend fun byIds(attachmentIds: List<String>): List<AttachmentTextEntity>

    @Upsert
    suspend fun upsert(row: AttachmentTextEntity)

    @Query("SELECT COUNT(*) FROM attachment_text WHERE engine = :engine")
    suspend fun countForEngine(engine: String): Int

    /** Clears the cache. Nothing a user made is in here, so this needs no confirmation beyond the UI's. */
    @Query("DELETE FROM attachment_text")
    suspend fun clear()

    /**
     * Deletes rows whose attachment is gone, and returns how many that was.
     *
     * **This must always return zero.** The foreign key cascades, so an orphan can only exist if
     * something wrote this table outside Room or deleted an attachment with foreign keys off. It is
     * one cheap statement per indexing pass, and it is the difference between believing the cascade
     * fires and knowing it — see `AttachmentTextEntity`.
     */
    @Query("DELETE FROM attachment_text WHERE attachmentId NOT IN (SELECT id FROM attachments)")
    suspend fun deleteOrphans(): Int
}

/** Narrow change projection: observing it never drags the regions JSON through every search. */
data class InkTextStamp(
    val pageId: String,
    val layoutHash: String,
    val engine: String,
    val status: InkTextStatus,
    val updatedAt: Long,
)

/** The per-page derived handwriting cache. */
@Dao
interface InkTextDao {

    @Query("SELECT * FROM ink_text WHERE pageId IN (:pageIds)")
    suspend fun byPageIds(pageIds: List<String>): List<InkTextEntity>

    @Upsert
    suspend fun upsert(row: InkTextEntity)

    @Query("SELECT COALESCE((SELECT generation FROM ink_text_generation WHERE pageId = :pageId), 0)")
    suspend fun generation(pageId: String): Long

    @Query(
        "INSERT INTO ink_text_generation(pageId, generation) VALUES(:pageId, 1) " +
            "ON CONFLICT(pageId) DO UPDATE SET generation = generation + 1",
    )
    suspend fun bumpGeneration(pageId: String)

    @Query("SELECT COUNT(*) FROM ink_text WHERE engine = :engine")
    suspend fun countForEngine(engine: String): Int

    @Query("DELETE FROM ink_text WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: String)

    @Query("DELETE FROM ink_text WHERE pageId IN (:pageIds)")
    suspend fun deleteForPages(pageIds: List<String>)

    @Query("DELETE FROM ink_text")
    suspend fun clear()

    @Query(
        "SELECT pageId, layoutHash, engine, status, updatedAt FROM ink_text ORDER BY pageId",
    )
    fun observeStamps(): Flow<List<InkTextStamp>>
}

@Dao
interface InkEraseDao {

    @Transaction
    @Query("SELECT * FROM ink_erases WHERE pageId = :pageId AND deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun byPage(pageId: String): List<InkEraseWithTargets>

    @Insert
    suspend fun insert(erase: InkEraseEntity)

    @Upsert
    suspend fun upsert(erase: InkEraseEntity)

    @Insert
    suspend fun insertTargets(targets: List<InkEraseTargetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTargetsIfAbsent(targets: List<InkEraseTargetEntity>)

    @Query("SELECT * FROM ink_erase_targets WHERE eraseId IN (:eraseIds)")
    suspend fun targetsForErases(eraseIds: List<String>): List<InkEraseTargetEntity>

    @Query("DELETE FROM ink_erase_targets WHERE eraseId IN (:eraseIds)")
    suspend fun deleteTargetsForErases(eraseIds: List<String>)

    @Query("SELECT * FROM ink_erases WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<InkEraseEntity>

    @Query("UPDATE ink_erases SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("SELECT pageId FROM ink_erases WHERE id = :id")
    suspend fun pageId(id: String): String?

    @Query(
        "UPDATE ink_erases SET deletedAt = :now " +
            "WHERE pageId = :pageId AND deletedAt IS NULL",
    )
    suspend fun softDeletePage(pageId: String, now: Long)

    @Query("UPDATE ink_erases SET deletedAt = NULL WHERE pageId = :pageId AND id IN (:ids)")
    suspend fun restoreSnapshotIds(pageId: String, ids: List<String>)
}

@Dao
interface InkMoveDao {

    @Transaction
    @Query("SELECT * FROM ink_moves WHERE pageId = :pageId AND deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun byPage(pageId: String): List<InkMoveWithTargets>

    @Insert
    suspend fun insert(move: InkMoveEntity)

    @Upsert
    suspend fun upsert(move: InkMoveEntity)

    @Insert
    suspend fun insertTargets(targets: List<InkMoveTargetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTargetsIfAbsent(targets: List<InkMoveTargetEntity>)

    @Query("SELECT * FROM ink_move_targets WHERE moveId IN (:moveIds)")
    suspend fun targetsForMoves(moveIds: List<String>): List<InkMoveTargetEntity>

    @Query("DELETE FROM ink_move_targets WHERE moveId IN (:moveIds)")
    suspend fun deleteTargetsForMoves(moveIds: List<String>)

    @Query("SELECT * FROM ink_moves WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<InkMoveEntity>

    @Query("UPDATE ink_moves SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("SELECT pageId FROM ink_moves WHERE id = :id")
    suspend fun pageId(id: String): String?

    @Query(
        "UPDATE ink_moves SET deletedAt = :now " +
            "WHERE pageId = :pageId AND deletedAt IS NULL",
    )
    suspend fun softDeletePage(pageId: String, now: Long)

    @Query("UPDATE ink_moves SET deletedAt = NULL WHERE pageId = :pageId AND id IN (:ids)")
    suspend fun restoreSnapshotIds(pageId: String, ids: List<String>)
}
