package st.unamedtba.data

import kotlinx.coroutines.flow.Flow
import st.unamedtba.data.db.NotebookEntity
import st.unamedtba.data.db.NotebookWithSections
import st.unamedtba.data.db.NotesDatabase
import st.unamedtba.data.db.PageContentEntity
import st.unamedtba.data.db.PageEntity
import st.unamedtba.data.db.SectionEntity
import st.unamedtba.model.Block
import st.unamedtba.model.BlockType
import st.unamedtba.model.DocumentCodecs
import st.unamedtba.model.Outline
import st.unamedtba.model.PageDoc
import st.unamedtba.model.PageStyle
import st.unamedtba.model.migrated
import st.unamedtba.model.TextDocumentCodec
import st.unamedtba.model.newId
import st.unamedtba.model.plainText

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
) {

    private val notebooks = db.notebookDao()
    private val sections = db.sectionDao()
    private val pages = db.pageDao()
    private val contents = db.pageContentDao()

    fun observeTree(): Flow<List<NotebookWithSections>> = notebooks.observeTree()

    fun observePages(sectionId: String): Flow<List<PageEntity>> = pages.observeIn(sectionId)

    fun observePage(pageId: String): Flow<PageEntity?> = pages.observeById(pageId)

    fun searchPages(query: String): Flow<List<PageEntity>> = pages.search(query)

    // --- notebooks -------------------------------------------------------------------------

    suspend fun createNotebook(name: String): String {
        val now = System.currentTimeMillis()
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

    suspend fun renameNotebook(id: String, name: String) =
        notebooks.rename(id, name, System.currentTimeMillis())

    suspend fun setNotebookExpanded(id: String, expanded: Boolean) =
        notebooks.setExpanded(id, expanded)

    suspend fun deleteNotebook(id: String) =
        notebooks.softDelete(id, System.currentTimeMillis())

    // --- sections --------------------------------------------------------------------------

    suspend fun createSection(notebookId: String, name: String): String {
        val now = System.currentTimeMillis()
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

    suspend fun renameSection(id: String, name: String) =
        sections.rename(id, name, System.currentTimeMillis())

    suspend fun deleteSection(id: String) =
        sections.softDelete(id, System.currentTimeMillis())

    // --- pages -----------------------------------------------------------------------------

    suspend fun createPage(sectionId: String, title: String = ""): String {
        val now = System.currentTimeMillis()
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

    suspend fun renamePage(id: String, title: String) =
        pages.rename(id, title, System.currentTimeMillis())

    suspend fun deletePage(id: String) =
        pages.softDelete(id, System.currentTimeMillis())

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

    suspend fun saveDoc(pageId: String, doc: PageDoc) {
        val now = System.currentTimeMillis()
        contents.upsert(PageContentEntity(pageId, codec.encodeToString(doc), now, codec.id))
        pages.updatePreview(pageId, doc.plainText().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140), now)
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
    }
}
