package com.vivenotes.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.vivenotes.data.db.AttachmentTextEntity
import com.vivenotes.data.db.InkTextEntity
import com.vivenotes.data.db.InkTextStamp
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.LocalMetadataEntity
import com.vivenotes.data.db.ClosedNotebook
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.PageRevisionEntity
import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.model.DocumentCodecs
import com.vivenotes.model.PageDoc
import com.vivenotes.model.isBlank
import com.vivenotes.model.migrated
import com.vivenotes.model.TextDocumentCodec
import com.vivenotes.model.newId
import com.vivenotes.model.plainText

/** Palette used for new notebooks and sections, cycled by creation order. */
val ACCENT_PALETTE = listOf(
    0xFF4CAF50.toInt(), // green
    0xFF2196F3.toInt(), // blue
    0xFFE91E63.toInt(), // pink
    0xFF9C27B0.toInt(), // purple
    0xFFFF9800.toInt(), // orange
    0xFF00BCD4.toInt(), // cyan
    0xFFFFC107.toInt(), // amber
)

/** Outcome of reading a page body. */
sealed interface PageLoad {
    data class Loaded(val doc: PageDoc) : PageLoad

    /**
     * The stored JSON could not be decoded. The raw text is carried along so it can be recovered
     * or exported; callers must not overwrite the page while in this state.
     */
    data class Unreadable(val rawJson: String, val cause: Throwable) : PageLoad
}

/**
 * What a delete did with the row it was given — `memory/blankFlushPlan.md`.
 *
 * The distinction is the user's, not the database's: one of these can be taken back and the other
 * never happened as far as anything downstream is concerned.
 */
enum class DeletionOutcome {
    /** Tombstoned: listed in Deleted Items for the retention window, and pushed as a tombstone. */
    Tombstoned,

    /** Held nothing, so nothing was kept. The rows are gone, and there is nothing to restore. */
    Flushed,
}

/** Direct tombstone rows removed by one maintenance transaction; cascaded child counts are omitted. */
data class DeletionPurgeResult(
    val cutoff: Long,
    val inkErases: Int,
    val inkMoves: Int,
    val inkStrokes: Int,
    val notebooks: Int,
    val sections: Int,
    val pages: Int,
) {
    val tombstones: Int
        get() = inkErases + inkMoves + inkStrokes + notebooks + sections + pages
}

class NotesRepository(
    private val db: NotesDatabase,
    /**
     * Format for newly written documents. Text rather than binary because the local database
     * being readable with `sqlite3` is worth more than the bytes saved; the sync protocol is free
     * to use a compact binary codec independently.
     */
    private val codec: TextDocumentCodec = DocumentCodecs.default,
    /**
     * Optional real-ink page seeded beside the Welcome page — `memory/ai.md`.
     *
     * Null in the app: `NotesApplication` stopped passing it, so a clean install seeds one empty
     * page rather than a stranger's handwriting. The asset and [StarterInkPageFixture] stay for the
     * recognition suites, which need real persisted ink with its erase and lasso operations intact.
     */
    private val starterInkPage: StarterInkPageFixture? = null,
    /** Injectable so checkpoint-window behavior has deterministic instrumentation tests. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val notebooks = db.notebookDao()
    private val sections = db.sectionDao()
    private val pages = db.pageDao()
    private val contents = db.pageContentDao()
    private val revisions = db.pageRevisionDao()
    private val ink = db.inkStrokeDao()
    private val inkErases = db.inkEraseDao()
    private val inkMoves = db.inkMoveDao()
    private val imageText = db.imageTextDao()
    private val inkText = db.inkTextDao()
    private val localMetadata = db.localMetadataDao()
    private val deletionRecovery = db.deletionRecoveryDao()
    private val deletionPurge = db.deletionPurgeDao()
    private val sync = db.syncDao()

    fun observeTree(): Flow<List<NotebookWithSections>> = notebooks.observeTree()

    fun observePages(sectionId: String): Flow<List<PageEntity>> = pages.observeIn(sectionId)

    fun observePage(pageId: String): Flow<PageEntity?> = pages.observeById(pageId)

    /**
     * The selected page's body, including replacements written directly by hierarchy sync.
     *
     * Distinctness belongs on the row before decoding: Room invalidates a query when *any* row in
     * `page_content` changes. Without this guard, saving another page could re-emit the selected
     * page's older stored body while its editor still has unsaved typing and roll that typing back.
     */
    fun observeDoc(pageId: String): Flow<PageLoad> = contents.observeById(pageId)
        .distinctUntilChanged()
        .map(::decodeDoc)

    fun searchPages(query: String): Flow<List<PageEntity>> = pages.search(query)

    /**
     * Every currently actionable deletion, newest first.
     *
     * Room invalidates this flow when any hierarchy table changes, so the recovery pane survives
     * process death and stays current without a UI-owned refresh protocol.
     *
     * **Only tombstones still inside the recovery window.** The pane tells the user that recovery
     * lasts [DELETION_RETENTION_DAYS] days, and an expired row is one the next purge removes — it is
     * listed today and gone tomorrow with nothing having happened in between, which is worse than
     * not listing it. It is also what makes a flush invisible without a marker of its own: a flushed
     * row is a tombstone written already expired. `memory/blankFlushPlan.md`.
     *
     * The cutoff is read per emission rather than per collector. A row that expires while the pane
     * is open therefore stays on screen until something else changes a hierarchy table — which is
     * the same staleness the purge itself has, since it runs daily and not at the instant of expiry.
     */
    fun observeDeletedItems(): Flow<List<DeletedItem>> = deletionRecovery.observeRoots().map { rows ->
        val recoverableSince = clock() - DELETION_RETENTION_MILLIS
        rows.filter { it.deletedAt > recoverableSince }.map { row ->
            DeletedItem(
                key = DeletedItemKey(row.id, DeletedItemKind.valueOf(row.kind)),
                name = row.name,
                notebookName = row.notebookName,
                sectionName = row.sectionName,
                deletedAt = row.deletedAt,
                sectionCount = row.sectionCount,
                pageCount = row.pageCount,
            )
        }
    }

    /**
     * Clears exactly one recovery-root tombstone.
     *
     * Descendant rows are intentionally untouched. They were never tombstoned by a parent delete,
     * and any child that *does* have a tombstone represents an older independent deletion that must
     * remain deleted. Clearing the first-run marker and restoring the row are one transaction so a
     * crash cannot leave a real restored notebook looking like a replaceable placeholder.
     */
    suspend fun restoreDeletedItem(key: DeletedItemKey): Boolean {
        val now = clock()
        return db.withTransaction {
            val restored = when (key.kind) {
                DeletedItemKind.Notebook -> deletionRecovery.restoreNotebook(key.id, now)
                DeletedItemKind.Section -> deletionRecovery.restoreSection(key.id, now)
                DeletedItemKind.Page -> deletionRecovery.restorePage(key.id, now)
            } == 1
            if (restored) clearReplaceableStarter()
            restored
        }
    }

    /**
     * Permanently removes tombstones whose recovery window has elapsed.
     *
     * All statements share one Room transaction, so a process death cannot leave (for example) an
     * erase operation gone with its target links still present. Ink operations go before strokes so
     * their direct cascade is exercised first; hierarchy parents go before children so deleting an
     * expired notebook removes its whole hidden branch in one foreign-key cascade.
     *
     * A target row naming a purged *stroke* is deliberately left behind: the target tables
     * reference their operation and not the stroke, because a cascade from the stroke side
     * would edit an operation that is supposed to be immutable, and a replicated operation has to
     * arrive with the same payload it was pushed with. Replay ignores a target it cannot find, so
     * the leftover is two short strings and no behaviour. `memory/inkSyncPlan.md` §2.2.
     *
     * SQLite retains freed pages for reuse. That is intentional: running `VACUUM` here would rewrite
     * and exclusively lock the complete database every day merely to shorten the file immediately.
     */
    suspend fun purgeExpiredDeletions(now: Long = clock()): DeletionPurgeResult {
        val cutoff = now - DELETION_RETENTION_MILLIS
        return db.withTransaction {
            DeletionPurgeResult(
                cutoff = cutoff,
                inkErases = deletionPurge.expiredInkErases(cutoff),
                inkMoves = deletionPurge.expiredInkMoves(cutoff),
                inkStrokes = deletionPurge.expiredInkStrokes(cutoff),
                notebooks = deletionPurge.expiredNotebooks(cutoff),
                sections = deletionPurge.expiredSections(cutoff),
                pages = deletionPurge.expiredPages(cutoff),
            ).also {
                // In the same transaction as the deletes, for the reason `HierarchySync.evictToCloud`
                // does it: the six statements above are guarded against removing a row whose *own*
                // generation is queued, but nothing guards what they cascade. An expired page takes
                // its `page_content` with it, and a `pageContent` generation still queued for that
                // body would answer the next push with "dirty page content disappeared" and fail
                // every push from then on.
                sync.pruneOrphanedOutbox()
            }
        }
    }

    // --- content search --------------------------------------------------------------------
    //
    // The Content panel's corpus, in the two halves `memory/searchPlan.md` CS7 splits it into: the page
    // rows, which are cheap and tell the index what has changed, and the bodies of only those pages
    // whose stamp has moved.

    /** Every live page of a notebook, in reading order. Metadata only — no document bodies. */
    suspend fun pagesInNotebook(notebookId: String): List<PageEntity> = pages.inNotebook(notebookId)

    /** A notebook's live sections, so a result can say which one it came from. */
    suspend fun sectionsInNotebook(notebookId: String): List<SectionEntity> =
        sections.inNotebook(notebookId)

    /**
     * Decodes the named pages' documents, skipping any that cannot be read.
     *
     * A page whose body is corrupt is left out of the search rather than reported: the editor already
     * refuses to write to it and says so when it is opened, and a search box is not the place to
     * learn about it. Decoding uses the codec each row records, exactly as [loadDoc] does, so a
     * format change does not blind the index to everything written before it.
     */
    suspend fun docsFor(pageIds: List<String>): Map<String, PageDoc> {
        if (pageIds.isEmpty()) return emptyMap()
        return contents.byIds(pageIds).mapNotNull { row ->
            val rowCodec = DocumentCodecs.byId(row.format) ?: return@mapNotNull null
            runCatching { rowCodec.decode(row.docJson.encodeToByteArray()).migrated() }
                .getOrNull()
                ?.let { row.pageId to it }
        }.toMap()
    }

    // --- recognized picture text ------------------------------------------------------------
    //
    // `memory/imageOcrPlan.md`. Keyed by attachment, so the same picture in ten places is one row;
    // deleted with its attachment by the foreign key, so it can never outlive what it describes.

    /**
     * What is known about the named pictures, whether or not any text came out of them.
     *
     * Chunked because a notebook can hold more pictures than SQLite will bind parameters for, and
     * the failure mode of not chunking is an exception on somebody's largest notebook only.
     */
    suspend fun imageTextFor(attachmentIds: Collection<String>): Map<String, AttachmentTextEntity> {
        if (attachmentIds.isEmpty()) return emptyMap()
        return attachmentIds.distinct().chunked(SQLITE_BIND_CHUNK)
            .flatMap { imageText.byIds(it) }
            .associateBy { it.attachmentId }
    }

    suspend fun saveImageText(row: AttachmentTextEntity) = imageText.upsert(row)

    suspend fun imageTextCount(engine: String): Int = imageText.countForEngine(engine)

    suspend fun clearImageText() = imageText.clear()

    /** See `ImageTextDao.deleteOrphans`: this must always return zero. */
    suspend fun deleteOrphanImageText(): Int = imageText.deleteOrphans()

    /** What is known about the named pages' handwriting, chunked below SQLite's bind limit. */
    suspend fun inkTextFor(pageIds: Collection<String>): Map<String, InkTextEntity> {
        if (pageIds.isEmpty()) return emptyMap()
        return pageIds.distinct().chunked(SQLITE_BIND_CHUNK)
            .flatMap { inkText.byPageIds(it) }
            .associateBy { it.pageId }
    }

    suspend fun inkTextGeneration(pageId: String): Long = inkText.generation(pageId)

    /** Saves only if no ink mutation committed while recognition was running. */
    suspend fun saveInkText(row: InkTextEntity, expectedGeneration: Long): Boolean =
        db.withTransaction {
            if (inkText.generation(row.pageId) != expectedGeneration) {
                false
            } else {
                inkText.upsert(row)
                true
            }
        }

    suspend fun inkTextCount(engine: String): Int = inkText.countForEngine(engine)

    suspend fun clearInkText() = inkText.clear()

    fun observeInkTextStamps(): Flow<List<InkTextStamp>> = inkText.observeStamps()

    /** One installation-local setting. Never travels in a notebook bundle — see `local_metadata`. */
    suspend fun localValue(key: String): String? = localMetadata.value(key)

    suspend fun putLocalValue(key: String, value: String) =
        localMetadata.put(LocalMetadataEntity(key, value))

    // --- notebooks -------------------------------------------------------------------------

    suspend fun createNotebook(name: String): String {
        clearReplaceableStarter()
        val now = clock()
        val index = notebooks.nextSortIndex()
        val id = newId()
        notebooks.insert(
            NotebookEntity(
                id = id,
                name = name,
                colorArgb = ACCENT_PALETTE[index % ACCENT_PALETTE.size],
                sortIndex = index,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun renameNotebook(id: String, name: String) {
        clearReplaceableStarter()
        notebooks.rename(id, name, clock())
    }

    suspend fun setNotebookExpanded(id: String, expanded: Boolean) =
        notebooks.setExpanded(id, expanded)

    /**
     * Tombstones a notebook, or flushes it if it never held anything.
     *
     * See [flush] for what the second answer means and [notebookIsBlank] for when it is given. The
     * caller has to look at the outcome: a flush cannot be undone, so offering an Undo for one is
     * offering a button that does nothing.
     */
    suspend fun deleteNotebook(id: String): DeletionOutcome {
        clearReplaceableStarter()
        // Deciding and acting in one transaction, because Room serializes transactions against each
        // other: a pull writing a page into this notebook cannot land between the two and turn a
        // correct flush into a delete of something that now holds content.
        return db.withTransaction {
            if (notebookIsBlank(id)) return@withTransaction flush(DeletedItemKind.Notebook, id)
            notebooks.softDelete(id, clock())
            DeletionOutcome.Tombstoned
        }
    }

    /**
     * Takes a notebook off the rail without deleting anything.
     *
     * [clearReplaceableStarter] for the same reason every other mutation calls it: shelving the
     * seeded "My Notebook" is a decision about it, so a device that connects afterwards must not
     * treat it as untouched packaging and throw it away.
     */
    suspend fun closeNotebook(id: String) {
        clearReplaceableStarter()
        val now = clock()
        notebooks.setClosed(id, now, now)
    }

    /** Puts it back in the rail. A cloud-only notebook has to be brought back before this. */
    suspend fun reopenNotebook(id: String) {
        clearReplaceableStarter()
        notebooks.setClosed(id, null, clock())
    }

    fun observeClosedNotebooks(): Flow<List<ClosedNotebook>> = notebooks.observeClosed()

    suspend fun notebookById(id: String): NotebookEntity? = notebooks.byId(id)

    // --- sections --------------------------------------------------------------------------

    suspend fun createSection(notebookId: String, name: String): String {
        clearReplaceableStarter()
        val now = clock()
        val index = sections.nextSortIndex(notebookId)
        val id = newId()
        sections.insert(
            SectionEntity(
                id = id,
                notebookId = notebookId,
                name = name,
                colorArgb = ACCENT_PALETTE[index % ACCENT_PALETTE.size],
                sortIndex = index,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun renameSection(id: String, name: String) {
        clearReplaceableStarter()
        sections.rename(id, name, clock())
    }

    /** Tombstones a section, or flushes an empty one — see [deleteNotebook] and [flush]. */
    suspend fun deleteSection(id: String): DeletionOutcome {
        clearReplaceableStarter()
        return db.withTransaction {
            if (sectionIsBlank(id)) return@withTransaction flush(DeletedItemKind.Section, id)
            sections.softDelete(id, clock())
            DeletionOutcome.Tombstoned
        }
    }

    /** How much a section takes with it when deleted — what the confirmation is worth reading for. */
    suspend fun pageCount(sectionId: String): Int = pages.countIn(sectionId)

    /** Rewrites a notebook's section order. See [reorderPages], which this mirrors exactly. */
    suspend fun reorderSections(notebookId: String, orderedIds: List<String>) {
        clearReplaceableStarter()
        db.withTransaction {
            resequence(
                live = sections.inNotebook(notebookId),
                orderedIds = orderedIds,
                id = SectionEntity::id,
                sortIndex = SectionEntity::sortIndex,
                write = sections::setSortIndex,
            )
        }
    }

    // --- pages -----------------------------------------------------------------------------

    suspend fun createPage(sectionId: String, title: String = ""): String {
        clearReplaceableStarter()
        val now = clock()
        val index = pages.nextSortIndex(sectionId)
        val id = newId()
        pages.insert(
            PageEntity(
                id = id,
                sectionId = sectionId,
                title = title,
                sortIndex = index,
                createdAt = now,
                updatedAt = now,
            ),
        )
        contents.upsert(
            PageContentEntity(id, codec.encodeToString(PageDoc.empty()), now, codec.id),
        )
        return id
    }

    suspend fun renamePage(id: String, title: String) {
        clearReplaceableStarter()
        pages.rename(id, title, clock())
    }

    /** Tombstones a page, or flushes one that was never written on — see [deleteNotebook], [flush]. */
    suspend fun deletePage(id: String): DeletionOutcome {
        clearReplaceableStarter()
        return db.withTransaction {
            if (pageIsBlank(id)) return@withTransaction flush(DeletedItemKind.Page, id)
            pages.softDelete(id, clock())
            DeletionOutcome.Tombstoned
        }
    }

    /**
     * Rewrites a section's page order.
     *
     * [orderedIds] is the list as it looked to the user, which is not necessarily what the table
     * holds — a page can be created, deleted or imported while a finger is still down. So the live
     * rows stay the authority on *membership* and [orderedIds] is consulted only for *sequence*:
     * anything the caller never saw keeps its own relative place at the end rather than colliding
     * on an index with something else.
     *
     * One transaction, so a list is never half-renumbered; and indices are rewritten from zero
     * rather than shuffled, which is what keeps them dense however many drags have happened.
     */
    suspend fun reorderPages(sectionId: String, orderedIds: List<String>) {
        clearReplaceableStarter()
        db.withTransaction {
            resequence(
                live = pages.inSection(sectionId),
                orderedIds = orderedIds,
                id = PageEntity::id,
                sortIndex = PageEntity::sortIndex,
                write = pages::setSortIndex,
            )
        }
    }

    /**
     * Applies [orderedIds] to [live] and writes the resulting positions, skipping the rows already
     * sitting where they belong — a drag moves one row past a handful of others, so renumbering the
     * whole list would be mostly no-op writes that still wake every observer of the table.
     */
    private suspend fun <T> resequence(
        live: List<T>,
        orderedIds: List<String>,
        id: (T) -> String,
        sortIndex: (T) -> Int,
        write: suspend (String, Int) -> Unit,
    ) {
        val byId = live.associateBy(id)
        val requested = orderedIds.mapNotNull(byId::get)
        val requestedIds = requested.mapTo(mutableSetOf(), id)
        val resolved = requested + live.filterNot { id(it) in requestedIds }
        resolved.forEachIndexed { index, row ->
            if (sortIndex(row) != index) write(id(row), index)
        }
    }

    // --- flushing what was never written ------------------------------------------------------
    //
    // `memory/blankFlushPlan.md`. Making a notebook makes a section; making a section makes a page.
    // Somebody who makes one of those, looks at it and deletes it has produced nothing, and a week
    // of it in Deleted Items, a push to the server and a copy of it on every other device are all
    // answers to a question nobody asked.

    /**
     * Whether deleting this notebook would throw nothing away.
     *
     * Answered from the pages, because that is where content lives: a section holds a name, and a
     * notebook of five carefully named empty sections is still a notebook of nothing. Tombstoned
     * pages count too — see [pagesAreBlank].
     *
     * **A closed or cloud-only notebook is never blank**, whatever it holds. A cloud-only one has no
     * bodies, no ink and no versions *on this device* while the server holds all of them, so it
     * would look emptier than anything else in the database; and the server stores a delete of a
     * closed notebook as a live cloud-only row rather than a tombstone (`viveCServer/docs/openapi.yaml`,
     * "Deleting a closed notebook keeps it"), so a device that flushed its rows would meet the
     * notebook again on its next pull with nothing left to draw it from.
     */
    suspend fun notebookIsBlank(id: String): Boolean {
        val notebook = notebooks.byId(id) ?: return false
        if (!notebook.holdsItsOwnContents) return false
        return pagesAreBlank(pages.allInNotebook(id))
    }

    /** Whether deleting this section would throw nothing away — see [notebookIsBlank]. */
    suspend fun sectionIsBlank(id: String): Boolean {
        val section = sections.byId(id) ?: return false
        val notebook = notebooks.byId(section.notebookId) ?: return false
        if (!notebook.holdsItsOwnContents) return false
        return pagesAreBlank(pages.allInSection(id))
    }

    /** Whether deleting this page would throw nothing away — see [notebookIsBlank]. */
    suspend fun pageIsBlank(id: String): Boolean {
        val page = pages.byId(id) ?: return false
        val section = sections.byId(page.sectionId) ?: return false
        val notebook = notebooks.byId(section.notebookId) ?: return false
        if (!notebook.holdsItsOwnContents) return false
        return pagesAreBlank(listOf(page))
    }

    private val NotebookEntity.holdsItsOwnContents: Boolean
        get() = closedAt == null && cloudOnlyAt == null

    /**
     * Whether every one of these pages is empty of everything worth keeping.
     *
     * Tombstones are included by every caller on purpose: a deleted page with text on it is still
     * restorable from Deleted Items, and flushing the section around it would take it away without
     * ever naming it.
     *
     * The four tests, and why each is the one it is:
     * - **Title.** Typed by a person, and the only content a page carries outside its body.
     * - **Body.** Decoded and asked [com.vivenotes.model.isBlank], not matched against
     *   `pages.preview`: the preview is the document's first line of *text*, so a page holding one
     *   photograph has an empty one. A body that will not decode is never blank — unreadable is not
     *   empty, and `PageLoad.Unreadable` exists precisely so that such a page is never overwritten.
     *   A page with no `page_content` row at all is blank, which is what the missing row means for a
     *   page that has never been saved.
     * - **Ink**, tombstones included. An erased stroke is still ink somebody drew, and the erase
     *   that removed it can be undone.
     * - **Versions.** A page with a history has been written on, whatever it says now. This is the
     *   blunt end of the rule and it stays blunt: the *first* edit of a page checkpoints the empty
     *   body it replaced, so a page typed on once and emptied again keeps a revision holding nothing
     *   and will never be flushed. Keeping it costs a row. Guessing the other way loses version
     *   history somebody could still have opened.
     */
    private suspend fun pagesAreBlank(rows: List<PageEntity>): Boolean {
        if (rows.any { it.title.isNotBlank() }) return false
        return rows.map { it.id }.chunked(SQLITE_BIND_CHUNK).all { chunk ->
            ink.countForPages(chunk) == 0 &&
                revisions.countForPages(chunk) == 0 &&
                contents.byIds(chunk).all { row ->
                    (decodeDoc(row) as? PageLoad.Loaded)?.doc?.isBlank() == true
                }
        }
    }

    /**
     * Deletes something blank without keeping any of it.
     *
     * The tombstone is written **already expired** — `deletedAt` a full retention window in the
     * past, `updatedAt` at now — and that one number is the whole mechanism. Nothing had to be
     * taught what a flush is: [observeDeletedItems] lists tombstones inside the window and so never
     * sees it, [purgeExpiredDeletions] collects tombstones outside the window and so takes it on the
     * first run, which happens here before this function returns; and the push carries the same
     * backdated stamp through the ordinary protocol, so the other devices apply a delete that is
     * expired when it lands and flush it in turn. No new kind, no marker table, no migration.
     *
     * **What the server has never heard of, it is never told.** The entity's own queued generation
     * is dropped when there is no `sync_entity_states` row for it *and* the durable pending batch
     * does not name it — the second half because a batch already serialized will be re-sent byte for
     * byte after a lost response, and a create that lands on the server with no delete queued behind
     * it is a notebook that comes back on the next pull. Both reads and the write sit in this
     * transaction, and `HierarchySync.loadOrCreatePendingBatch` builds a batch inside one of its
     * own, so SQLite's single writer settles the race in whichever direction it happens: either the
     * batch exists and is seen, or it is built after the row has already gone from the outbox.
     *
     * The subtree below needs nothing pruned by name. The purge removes the flushed row, foreign
     * keys cascade its sections, pages, bodies, ink and versions away, and the
     * `pruneOrphanedOutbox` in that same transaction drops whatever those rows had queued. A blank
     * notebook whose creation was never pushed therefore reaches the server as nothing at all.
     *
     * When the server *has* acknowledged it, the tombstone stays queued and the purge steps over it
     * — its outbox entry is the guard — so the rows survive until the delete has actually been
     * delivered. It is invisible from this moment either way; the next flush, the next app start or
     * the daily purge worker is what finally collects it.
     */
    private suspend fun flush(kind: DeletedItemKind, id: String): DeletionOutcome {
        val now = clock()
        val expiredAt = now - DELETION_RETENTION_MILLIS
        db.withTransaction {
            when (kind) {
                DeletedItemKind.Notebook -> notebooks.flush(id, expiredAt, now)
                DeletedItemKind.Section -> sections.flush(id, expiredAt, now)
                DeletedItemKind.Page -> pages.flush(id, expiredAt, now)
            }
            if (!serverKnowsOf(kind, id)) sync.deleteOutbox(kind.syncKind, id)
            // The same `now` the tombstone was backdated from, so `deletedAt <= cutoff` is exact
            // rather than a race against the clock advancing between two calls.
            purgeExpiredDeletions(now)
        }
        return DeletionOutcome.Flushed
    }

    /**
     * Whether the server has already been told this entity exists, or is about to be.
     *
     * The pending batch is tested by substring rather than by decoding it. `HierarchySync`'s change
     * model is private to it, and reaching into the sync layer from here to parse one field would
     * invert the dependency for a question a `String.contains` answers: ids are UUIDs, so a false
     * match is not a practical possibility, and a false match would only cost one pushed tombstone
     * for something the server was going to be told about anyway.
     */
    private suspend fun serverKnowsOf(kind: DeletedItemKind, id: String): Boolean {
        if (sync.knownEntityIds(kind.syncKind, listOf(id)).isNotEmpty()) return true
        return localMetadata.value(PENDING_SYNC_BATCH_KEY)?.contains(id) == true
    }

    /**
     * The wire name `HierarchySync.SyncKind` pushes this row under.
     *
     * Spelled out here rather than imported for the reason [serverKnowsOf] gives; `DeletionPurgeDao`
     * writes the same three strings into its SQL for the same reason. Both these and
     * [PENDING_SYNC_BATCH_KEY] are pinned to the sync layer's own spelling by behaviour rather than
     * by a string comparison: `HierarchySyncTest` flushes against a real `HierarchySync` and a
     * server, so a name that drifted would show up as a delete that never travelled.
     */
    private val DeletedItemKind.syncKind: String
        get() = when (this) {
            DeletedItemKind.Notebook -> "notebook"
            DeletedItemKind.Section -> "section"
            DeletedItemKind.Page -> "page"
        }

    /**
     * Loads a page body.
     *
     * A decode failure is reported rather than swallowed. Returning an empty document here would
     * be silently destructive: the editor would show a blank page and autosave would write that
     * blank page over content that was merely unreadable, not actually gone.
     *
     * What decodes is then brought up to the current schema. Migrating on read rather than in a bulk
     * pass means a page nobody opens is never rewritten, and an older build still reads what a newer
     * one saved — the same rolling-change property the per-row codec id gives the format.
     */
    suspend fun loadDoc(pageId: String): PageLoad {
        return decodeDoc(contents.byId(pageId))
    }

    private fun decodeDoc(row: PageContentEntity?): PageLoad {
        if (row == null) return PageLoad.Loaded(PageDoc.empty())
        // Decode with the codec that wrote the row, not the current default, so a format change
        // does not orphan everything written before it.
        val rowCodec = DocumentCodecs.byId(row.format)
            ?: return PageLoad.Unreadable(row.docJson, IllegalStateException("unknown format '${row.format}'"))
        return runCatching { rowCodec.decode(row.docJson.encodeToByteArray()) }.fold(
            onSuccess = { PageLoad.Loaded(it.migrated()) },
            onFailure = { PageLoad.Unreadable(row.docJson, it) },
        )
    }

    suspend fun saveDoc(pageId: String, doc: PageDoc) = writeDoc(pageId, doc)

    /** Newest first. The compressed payloads deliberately do not travel with the list. */
    suspend fun revisionHistory(pageId: String): List<PageRevisionSummary> =
        revisions.history(pageId)

    suspend fun loadRevision(pageId: String, revisionId: String): PageRevisionLoad {
        val row = revisions.byId(pageId, revisionId) ?: return PageRevisionLoad.NotFound
        val summary = DocumentRevisionPayload.summary(row)
        return runCatching {
            val doc = DocumentRevisionPayload.unpack(row)
            InkRevisionPayload.unpack(row)
            PageRevisionLoad.Loaded(summary, doc)
        }.fold(
            onSuccess = { it },
            onFailure = { PageRevisionLoad.Unreadable(summary, it) },
        )
    }

    /**
     * Makes a checkpoint current and first checkpoints the state it replaces, even when the normal
     * coalescing window has not elapsed. A restore therefore never destroys the route back.
     */
    suspend fun restoreRevision(pageId: String, revisionId: String): PageRevisionLoad {
        val row = revisions.byId(pageId, revisionId) ?: return PageRevisionLoad.NotFound
        val summary = DocumentRevisionPayload.summary(row)
        val doc = runCatching { DocumentRevisionPayload.unpack(row) }.getOrElse {
            return PageRevisionLoad.Unreadable(summary, it)
        }
        val restoredInk = runCatching { InkRevisionPayload.unpack(row) }.getOrElse {
            return PageRevisionLoad.Unreadable(summary, it)
        }

        val now = clock()
        val encoded = codec.encodeToString(doc)
        val preview = previewOf(doc)
        db.withTransaction {
            val previous = contents.byId(pageId) ?: return@withTransaction
            val current = checkpointOf(previous, now)
            if (!current.sameContentAs(row)) {
                clearReplaceableStarter()
                // Forced even inside the coalescing window: the complete page being replaced must
                // remain reachable. Content identity keeps repeated A <-> B restores idempotent.
                storeCheckpoint(current)
                contents.upsert(PageContentEntity(pageId, encoded, now, codec.id))
                restoreInkLocked(pageId, restoredInk, now)
                pages.updatePreview(pageId, preview, now)
            }
        }
        return PageRevisionLoad.Loaded(summary, doc)
    }

    private suspend fun writeDoc(pageId: String, doc: PageDoc) {
        val now = clock()
        val encoded = codec.encodeToString(doc)
        val preview = previewOf(doc)

        db.withTransaction {
            val previous = contents.byId(pageId)
            if (previous?.docJson == encoded && previous.format == codec.id) return@withTransaction
            clearReplaceableStarter()

            if (previous != null && shouldCheckpoint(pageId, now)) {
                storeCheckpoint(checkpointOf(previous, now))
            }
            contents.upsert(PageContentEntity(pageId, encoded, now, codec.id))
            pages.updatePreview(pageId, preview, now)
        }
    }

    private suspend fun shouldCheckpoint(pageId: String, now: Long): Boolean {
        val newest = revisions.newestTimestamp(pageId) ?: return true
        return now - newest >= REVISION_CHECKPOINT_INTERVAL_MS
    }

    private fun previewOf(doc: PageDoc): String = doc.plainText().lineSequence()
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
        .take(140)

    /** Must run inside the caller's transaction so document and ink describe one instant. */
    private suspend fun checkpointOf(content: PageContentEntity, now: Long) =
        DocumentRevisionPayload.pack(
            row = content,
            createdAt = now,
            ink = InkRevisionPayload.pack(
                InkSnapshot.from(
                    strokes = ink.byPage(content.pageId),
                    erases = inkErases.byPage(content.pageId),
                    moves = inkMoves.byPage(content.pageId),
                ),
            ),
        )

    private suspend fun storeCheckpoint(checkpoint: PageRevisionEntity) {
        val healthyMatches = revisions.matchingContent(
            pageId = checkpoint.pageId,
            format = checkpoint.format,
            byteCount = checkpoint.byteCount,
            sha256 = checkpoint.sha256,
            inkFormat = checkpoint.inkFormat,
            inkByteCount = checkpoint.inkByteCount,
            inkSha256 = checkpoint.inkSha256,
        ).filter { candidate ->
            runCatching {
                DocumentRevisionPayload.unpack(candidate)
                InkRevisionPayload.unpack(candidate)
            }.isSuccess
        }
        if (healthyMatches.isEmpty()) {
            revisions.insert(checkpoint)
            revisions.trimToNewest(checkpoint.pageId, MAX_REVISIONS_PER_PAGE)
        } else if (healthyMatches.size > 1) {
            revisions.deleteByIds(healthyMatches.drop(1).map { it.id })
        }
    }

    private fun PageRevisionEntity.sameContentAs(other: PageRevisionEntity): Boolean =
        format == other.format && byteCount == other.byteCount && sha256 == other.sha256 &&
            inkFormat == other.inkFormat && inkByteCount == other.inkByteCount &&
            inkSha256 == other.inkSha256

    private suspend fun checkpointBeforeInkMutation(pageId: String, now: Long) {
        if (!shouldCheckpoint(pageId, now)) return
        contents.byId(pageId)?.let { storeCheckpoint(checkpointOf(it, now)) }
    }

    /** Replaces the active ink view while retaining later rows as tombstoned history. */
    private suspend fun restoreInkLocked(pageId: String, snapshot: InkSnapshot, now: Long) {
        inkText.deleteForPage(pageId)
        inkText.bumpGeneration(pageId)
        ink.softDeletePage(pageId, now)
        inkErases.softDeletePage(pageId, now)
        inkMoves.softDeletePage(pageId, now)
        snapshot.strokes.forEach { stroke ->
            ink.restoreSnapshotState(
                pageId = pageId,
                id = stroke.id,
                colorArgb = stroke.colorArgb,
                followsTheme = stroke.colorFollowsTheme,
                groupId = stroke.groupId,
            )
        }
        if (snapshot.eraseIds.isNotEmpty()) {
            inkErases.restoreSnapshotIds(pageId, snapshot.eraseIds)
        }
        if (snapshot.moveIds.isNotEmpty()) {
            inkMoves.restoreSnapshotIds(pageId, snapshot.moveIds)
        }
    }

    // --- ink -------------------------------------------------------------------------------

    /**
     * A page's live strokes, in draw order.
     *
     * Rows rather than the document: ink does not travel through [saveDoc], so drawing never
     * rewrites the document column and autosave latency stays independent of how much ink is on the
     * page. See `memory/inkPlan.md` ID2.
     */
    suspend fun inkFor(pageId: String): List<InkStrokeEntity> = ink.byPage(pageId)

    /** Appends one stroke. Strokes are immutable, so this is the only way ink is ever written. */
    suspend fun addStroke(stroke: InkStrokeEntity): InkStrokeEntity = db.withTransaction {
        clearReplaceableStarter()
        checkpointBeforeInkMutation(stroke.pageId, clock())
        inkText.deleteForPage(stroke.pageId)
        inkText.bumpGeneration(stroke.pageId)
        stroke.copy(seq = ink.nextSeq(stroke.pageId)).also { ink.insert(it) }
    }

    /** Appends a copied selection as one contiguous draw-order block. */
    suspend fun addStrokes(strokes: List<InkStrokeEntity>): List<InkStrokeEntity> {
        if (strokes.isEmpty()) return emptyList()
        return db.withTransaction {
            clearReplaceableStarter()
            val pageId = strokes.first().pageId
            require(strokes.all { it.pageId == pageId }) { "copied strokes span multiple pages" }
            checkpointBeforeInkMutation(pageId, clock())
            inkText.deleteForPage(pageId)
            inkText.bumpGeneration(pageId)
            var sequence = ink.nextSeq(pageId)
            strokes.map { stroke -> stroke.copy(seq = sequence++).also { ink.insert(it) } }
        }
    }

    /**
     * Erases strokes by tombstone.
     *
     * Never a hard delete, for the same reason nothing else here is: a row that is gone cannot be
     * replicated, so an erase on one device would silently reappear from another.
     */
    suspend fun eraseStrokes(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = clock()
        db.withTransaction {
            clearReplaceableStarter()
            val pageIds = ink.pageIdsFor(ids)
            pageIds.forEach { checkpointBeforeInkMutation(it, now) }
            pageIds.chunked(SQLITE_BIND_CHUNK).forEach { inkText.deleteForPages(it) }
            pageIds.forEach { inkText.bumpGeneration(it) }
            ink.softDelete(ids, now)
        }
    }

    /** Restores stroke rows tombstoned by Draw-toolbar undo. */
    suspend fun restoreStrokes(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = clock()
        db.withTransaction {
            clearReplaceableStarter()
            val pageIds = ink.pageIdsFor(ids)
            pageIds.forEach { checkpointBeforeInkMutation(it, now) }
            pageIds.chunked(SQLITE_BIND_CHUNK).forEach { inkText.deleteForPages(it) }
            pageIds.forEach { inkText.bumpGeneration(it) }
            ink.restore(ids)
        }
    }

    suspend fun setInkColors(colors: Map<String, StrokeColor>) {
        if (colors.isEmpty()) return
        val now = clock()
        db.withTransaction {
            clearReplaceableStarter()
            ink.pageIdsFor(colors.keys.toList()).forEach { checkpointBeforeInkMutation(it, now) }
            colors.forEach { (id, color) -> ink.setColor(id, color.argb, color.followsTheme) }
        }
    }

    suspend fun setInkGroups(groups: Map<String, String?>) {
        if (groups.isEmpty()) return
        val now = clock()
        db.withTransaction {
            clearReplaceableStarter()
            ink.pageIdsFor(groups.keys.toList()).forEach { checkpointBeforeInkMutation(it, now) }
            groups.forEach { (id, group) -> ink.setGroup(id, group) }
        }
    }

    suspend fun partialErasesFor(pageId: String): List<InkEraseWithTargets> =
        inkErases.byPage(pageId)

    suspend fun inkMovesFor(pageId: String): List<InkMoveWithTargets> =
        inkMoves.byPage(pageId)

    /**
     * What this page's operation clock has to start above — `memory/inkSyncPlan.md` §1.
     *
     * Read from the tables rather than derived from the operations that were replayed, because the
     * two differ by exactly the rows that make it a clock: an undone or pulled-and-tombstoned
     * operation is not replayed and still has to be counted. Two indexed maxima over a page's
     * operations, which number in the hundreds where its strokes number in the thousands.
     */
    suspend fun latestInkOperationAt(pageId: String): Long = maxOf(
        inkErases.latestCreatedAt(pageId) ?: 0L,
        inkMoves.latestCreatedAt(pageId) ?: 0L,
    )

    /**
     * Stores a normal-eraser gesture and its target set atomically. The mask without its targets
     * would be unsafe: replaying it against every page stroke would also erase ink drawn later.
     */
    suspend fun addPartialErase(erase: InkEraseEntity, strokeIds: List<String>) {
        if (strokeIds.isEmpty()) return
        db.withTransaction {
            clearReplaceableStarter()
            checkpointBeforeInkMutation(erase.pageId, clock())
            inkText.deleteForPage(erase.pageId)
            inkText.bumpGeneration(erase.pageId)
            inkErases.insert(erase)
            inkErases.insertTargets(strokeIds.distinct().map { InkEraseTargetEntity(erase.id, it) })
        }
    }

    /**
     * Tombstones strokes the eraser has taken the last piece of, so the seven-day purge collects
     * them.
     *
     * Ink is an append-only log, so rubbing a stroke out has always *added* rows — the erase and its
     * targets — while the stroke kept every point it was drawn with, for good. On a page drawn and
     * redrawn on, that is most of what the database holds: two thirds of the stroke rows on the test
     * corpus had no geometry left at all. This is the collector for exactly those: a row whose live
     * projection set is empty draws nothing, is reachable by nothing, and is not ink any more.
     *
     * **[deletedAt] rather than a delete of its own**, so it lands in the machinery that already
     * exists: `DeletionPurgeWorker` hard-deletes it after the seven-day recovery window, cascading
     * its target rows; [restoreRevision] un-tombstones exactly the strokes a revision names, so a
     * page restored inside that window still comes back whole; and `InkStrokeDao.byPage` stops
     * loading it immediately, which costs nothing because it had nothing to draw.
     *
     * **Never told to the server.** Deadness is not an edit, it is a conclusion, and every device
     * reaches it from the same replicated erases — so pushing it would be one deletion per device per
     * stroke, all saying what the erase already said. Worse, a deletion is the one message a device
     * cannot disagree with: a peer that has not yet pulled the erase would lose ink it can still see.
     * The triggers are therefore held off exactly as `HierarchySync` holds them off when it drops a
     * joining device's starter notebook. (A stroke whose *insert* is still queued pushes the
     * tombstone with it; that peer holds the erase too, so it draws the same page either way.)
     *
     * No checkpoint, no recognition invalidation, no starter clearing: nothing a reader could
     * observe has changed, and a revision spent on a garbage collection would evict a real one.
     */
    suspend fun collectErasedAwayStrokes(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = clock()
        val collected = ids.distinct()
        db.withTransaction {
            // Set and cleared inside one transaction, as the pull does, so a sync running beside
            // this can neither see the flag nor have its own cleared: SQLite serialises writers.
            sync.setApplyingRemote(true)
            ink.softDelete(collected, now)
            sync.setApplyingRemote(false)
        }
    }

    /**
     * Includes or excludes an existing erase operation from page-open replay.
     *
     * Undoing one restores every stroke it named, because [collectErasedAwayStrokes] may have
     * collected the ones it finished off and replay is about to give them their geometry back. The
     * targets it did not kill are already live, so this is a no-op for them.
     *
     * That cannot resurrect ink the *user* deleted: the canvas history is one linear ring, so a
     * delete made after this erase has to be undone before this erase can be, and a revision restore
     * drops the ring outright (`NotesViewModel.restoreRevision`).
     */
    suspend fun setPartialEraseActive(id: String, active: Boolean) {
        val now = clock()
        db.withTransaction {
            clearReplaceableStarter()
            inkErases.pageId(id)?.let {
                checkpointBeforeInkMutation(it, now)
                inkText.deleteForPage(it)
                inkText.bumpGeneration(it)
            }
            inkErases.setDeletedAt(id, if (active) null else now)
            if (!active) {
                ink.restore(inkErases.targetsForErases(listOf(id)).map { it.strokeId })
            }
        }
    }

    /** Stores a lasso move or resize with the source rows it was allowed to transform. */
    suspend fun addInkMove(move: InkMoveEntity, strokeIds: Collection<String>) {
        if (strokeIds.isEmpty()) return
        db.withTransaction {
            clearReplaceableStarter()
            checkpointBeforeInkMutation(move.pageId, clock())
            inkText.deleteForPage(move.pageId)
            inkText.bumpGeneration(move.pageId)
            inkMoves.insert(move)
            inkMoves.insertTargets(strokeIds.distinct().map { InkMoveTargetEntity(move.id, it) })
        }
    }

    /** Includes or excludes an existing lasso transform from page-open replay. */
    suspend fun setInkMoveActive(id: String, active: Boolean) {
        val now = clock()
        db.withTransaction {
            clearReplaceableStarter()
            inkMoves.pageId(id)?.let {
                checkpointBeforeInkMutation(it, now)
                inkText.deleteForPage(it)
                inkText.bumpGeneration(it)
            }
            inkMoves.setDeletedAt(id, if (active) null else now)
        }
    }

    // --- first run -------------------------------------------------------------------------

    /**
     * Seeds a starter notebook so the first launch is not an empty void. Only ever runs when the
     * database has no notebooks at all, so it cannot resurrect content the user deleted.
     *
     * The Welcome page is deliberately **empty** — [createPage] already writes `PageDoc.empty()`.
     * It used to open with a text outline explaining the ribbon, which put a container under the
     * pen before the owner had drawn anything and made the first gesture on a stylus-first app a
     * text selection. A blank page teaches the same lesson faster.
     */
    suspend fun seedIfEmpty() {
        if (notebooks.count() > 0) return

        val notebookId = createNotebook("My Notebook")
        val gettingStarted = createSection(notebookId, "Getting Started")
        createSection(notebookId, "Ideas")

        createPage(gettingStarted, "Welcome")

        starterInkPage?.let { fixture ->
            val fixturePageId = createPage(gettingStarted, fixture.title)
            val rows = fixture.materialize(fixturePageId)
            db.withTransaction {
                ink.insert(rows.strokes)
                rows.erases.forEach { inkErases.insert(it) }
                inkErases.insertTargets(rows.eraseTargets)
                rows.moves.forEach { inkMoves.insert(it) }
                inkMoves.insertTargets(rows.moveTargets)
            }
        }
        // Written last: all calls above are seed construction, while any later content mutation
        // clears this marker. The UUID lets import remove only this installation's placeholder.
        localMetadata.put(LocalMetadataEntity(REPLACEABLE_STARTER_KEY, notebookId))
    }

    private suspend fun clearReplaceableStarter() {
        localMetadata.delete(REPLACEABLE_STARTER_KEY)
    }

    companion object {
        const val DELETION_RETENTION_DAYS = 7L
        const val DELETION_RETENTION_MILLIS = DELETION_RETENTION_DAYS * 24L * 60L * 60L * 1_000L

        const val REPLACEABLE_STARTER_KEY = "replaceableStarterNotebookId"

        /**
         * `HierarchySync.PENDING_BATCH_KEY`, repeated rather than imported — see
         * [NotesRepository.serverKnowsOf]. The two are asserted equal by the flush suite.
         */
        const val PENDING_SYNC_BATCH_KEY = "syncPendingHierarchyBatch"
        /** Coalesces the editor's 400 ms autosaves into useful checkpoints instead of near-duplicates. */
        const val REVISION_CHECKPOINT_INTERVAL_MS = 30_000L
        const val MAX_REVISIONS_PER_PAGE = 40

        /**
         * How many ids go into one `IN (…)`.
         *
         * SQLite's default parameter limit is 999. Well under it, because a query is built from
         * these *plus* whatever else it binds.
         */
        private const val SQLITE_BIND_CHUNK = 400
    }
}
