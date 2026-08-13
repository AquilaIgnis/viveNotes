package com.vivenotes.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.LocalMetadataEntity
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.PageRevisionEntity
import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.DocumentCodecs
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
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

class NotesRepository(
    private val db: NotesDatabase,
    /**
     * Format for newly written documents. Text rather than binary because the local database
     * being readable with `sqlite3` is worth more than the bytes saved; the sync protocol is free
     * to use a compact binary codec independently.
     */
    private val codec: TextDocumentCodec = DocumentCodecs.default,
    /** Optional real-ink page bundled by the application for clean-install recognition tests. */
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
    private val localMetadata = db.localMetadataDao()

    fun observeTree(): Flow<List<NotebookWithSections>> = notebooks.observeTree()

    fun observePages(sectionId: String): Flow<List<PageEntity>> = pages.observeIn(sectionId)

    fun observePage(pageId: String): Flow<PageEntity?> = pages.observeById(pageId)

    fun searchPages(query: String): Flow<List<PageEntity>> = pages.search(query)

    // --- content search --------------------------------------------------------------------
    //
    // The Content panel's corpus, in the two halves `docs/searchPlan.md` CS7 splits it into: the page
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

    suspend fun deleteNotebook(id: String) {
        clearReplaceableStarter()
        notebooks.softDelete(id, clock())
    }

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

    suspend fun deleteSection(id: String) {
        clearReplaceableStarter()
        sections.softDelete(id, clock())
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

    suspend fun deletePage(id: String) {
        clearReplaceableStarter()
        pages.softDelete(id, clock())
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
        val row = contents.byId(pageId) ?: return PageLoad.Loaded(PageDoc.empty())
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
     * page. See `docs/inkPlan.md` ID2.
     */
    suspend fun inkFor(pageId: String): List<InkStrokeEntity> = ink.byPage(pageId)

    /** Appends one stroke. Strokes are immutable, so this is the only way ink is ever written. */
    suspend fun addStroke(stroke: InkStrokeEntity): InkStrokeEntity = db.withTransaction {
        clearReplaceableStarter()
        checkpointBeforeInkMutation(stroke.pageId, clock())
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
            ink.pageIdsFor(ids).forEach { checkpointBeforeInkMutation(it, now) }
            ink.softDelete(ids, now)
        }
    }

    /** Restores stroke rows tombstoned by Draw-toolbar undo. */
    suspend fun restoreStrokes(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = clock()
        db.withTransaction {
            clearReplaceableStarter()
            ink.pageIdsFor(ids).forEach { checkpointBeforeInkMutation(it, now) }
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
     * Stores a normal-eraser gesture and its target set atomically. The mask without its targets
     * would be unsafe: replaying it against every page stroke would also erase ink drawn later.
     */
    suspend fun addPartialErase(erase: InkEraseEntity, strokeIds: List<String>) {
        if (strokeIds.isEmpty()) return
        db.withTransaction {
            clearReplaceableStarter()
            checkpointBeforeInkMutation(erase.pageId, clock())
            inkErases.insert(erase)
            inkErases.insertTargets(strokeIds.distinct().map { InkEraseTargetEntity(erase.id, it) })
        }
    }

    /** Includes or excludes an existing erase operation from page-open replay. */
    suspend fun setPartialEraseActive(id: String, active: Boolean) {
        val now = clock()
        db.withTransaction {
            clearReplaceableStarter()
            inkErases.pageId(id)?.let { checkpointBeforeInkMutation(it, now) }
            inkErases.setDeletedAt(id, if (active) null else now)
        }
    }

    /** Stores a lasso move or resize with the source rows it was allowed to transform. */
    suspend fun addInkMove(move: InkMoveEntity, strokeIds: Collection<String>) {
        if (strokeIds.isEmpty()) return
        db.withTransaction {
            clearReplaceableStarter()
            checkpointBeforeInkMutation(move.pageId, clock())
            inkMoves.insert(move)
            inkMoves.insertTargets(strokeIds.distinct().map { InkMoveTargetEntity(move.id, it) })
        }
    }

    /** Includes or excludes an existing lasso transform from page-open replay. */
    suspend fun setInkMoveActive(id: String, active: Boolean) {
        val now = clock()
        db.withTransaction {
            clearReplaceableStarter()
            inkMoves.pageId(id)?.let { checkpointBeforeInkMutation(it, now) }
            inkMoves.setDeletedAt(id, if (active) null else now)
        }
    }

    // --- first run -------------------------------------------------------------------------

    /**
     * Seeds a starter notebook so the first launch is not an empty void. Only ever runs when the
     * database has no notebooks at all, so it cannot resurrect content the user deleted.
     */
    suspend fun seedIfEmpty() {
        if (notebooks.count() > 0) return

        val notebookId = createNotebook("My Notebook")
        val gettingStarted = createSection(notebookId, "Getting Started")
        createSection(notebookId, "Ideas")

        val pageId = createPage(gettingStarted, "Welcome")
        saveDoc(
            pageId,
            PageDoc(
                outlines = listOf(
                    Outline.Text(
                        id = newId(),
                        // Clear of the title band: outline coordinates start at the page's own
                        // top-left corner, so a seeded page has to place itself below the header
                        // rather than being pushed down by it.
                        y = PageStyle.TITLE_BAND_DP,
                        blocks = listOf(
                            Block.of("This is a page. Type anywhere to start writing.", BlockType.Paragraph),
                            Block.of("", BlockType.Paragraph),
                            Block.of("Formatting", BlockType.Heading2),
                            Block.of("Use the ribbon above to style text.", BlockType.Bullet),
                            Block.of("Bold, italic, underline, highlight and colour all work.", BlockType.Bullet),
                            Block.of("Tab and Shift+Tab change indent level.", BlockType.Bullet),
                            Block.of("", BlockType.Paragraph),
                            Block.of("Organising", BlockType.Heading2),
                            Block.of("Notebooks hold sections, sections hold pages.", BlockType.Bullet),
                            Block.of("Add a page with the button above the page list.", BlockType.Bullet),
                        ),
                    ),
                ),
            ),
        )

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
        const val REPLACEABLE_STARTER_KEY = "replaceableStarterNotebookId"
        /** Coalesces the editor's 400 ms autosaves into useful checkpoints instead of near-duplicates. */
        const val REVISION_CHECKPOINT_INTERVAL_MS = 30_000L
        const val MAX_REVISIONS_PER_PAGE = 100
    }
}
