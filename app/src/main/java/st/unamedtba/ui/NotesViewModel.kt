package st.unamedtba.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import st.unamedtba.data.EditorDefaults
import st.unamedtba.data.EditorDefaultsStore
import st.unamedtba.data.NotesRepository
import st.unamedtba.data.PageLoad
import st.unamedtba.data.TabsLayout
import st.unamedtba.data.ViewSettings
import st.unamedtba.data.ViewSettingsStore
import st.unamedtba.data.db.NotebookWithSections
import st.unamedtba.data.db.PageEntity
import st.unamedtba.model.Block
import st.unamedtba.model.Mark
import st.unamedtba.model.Orientation
import st.unamedtba.model.Outline
import st.unamedtba.model.PageDoc
import st.unamedtba.model.PageStyle
import st.unamedtba.model.PaperDimensions
import st.unamedtba.model.PaperSize
import st.unamedtba.model.PrintMargins
import st.unamedtba.model.RuleLines
import st.unamedtba.model.newId
import st.unamedtba.richtext.FormatCommand
import st.unamedtba.richtext.SelectionState
import st.unamedtba.richtext.sameKindAs

/**
 * Position and size of one text container on the page canvas.
 *
 * Deliberately excludes the container's text: this is UI state and changes on every drag frame,
 * whereas block content changes on every keystroke. Keeping them apart means typing does not
 * recompose the canvas.
 */
data class OutlineBox(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val minHeight: Float = 0f,
)

data class NotesUiState(
    val tree: List<NotebookWithSections> = emptyList(),
    val selectedSectionId: String? = null,
    val pages: List<PageEntity> = emptyList(),
    val selectedPageId: String? = null,
    val title: String = "",
    val createdAt: Long = 0L,
    val outlines: List<OutlineBox> = emptyList(),
    /** The open page's own appearance, loaded and saved with its content. */
    val pageStyle: PageStyle = PageStyle(),
    /** Bumped when a different page loads, so the canvas rebuilds its editors. */
    val pageRevision: Int = 0,
    val loading: Boolean = true,
    /** Set when the page body could not be decoded; the page is shown read-only. */
    val contentError: String? = null,
)

/** Which pane is showing when the window is too narrow to show them side by side. */
enum class CompactPane { Notebooks, Pages, Editor }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NotesViewModel(
    private val repository: NotesRepository,
    private val editorDefaultsStore: EditorDefaultsStore,
    private val viewSettingsStore: ViewSettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    private val _selection = MutableStateFlow(SelectionState())
    val selection: StateFlow<SelectionState> = _selection.asStateFlow()

    /** Font and size for text with no mark of its own — the ribbon's readout and the editor's base. */
    val editorDefaults: StateFlow<EditorDefaults> = editorDefaultsStore.defaults
        .stateIn(viewModelScope, SharingStarted.Eagerly, EditorDefaults())

    /** Zoom, navigation layout and canvas brightness — this device's view, not the document's. */
    val viewSettings: StateFlow<ViewSettings> = viewSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ViewSettings())

    /** Commands travel to the focused editor, the only thing that can act on them. */
    private val _commands = MutableSharedFlow<FormatCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<FormatCommand> = _commands

    private val _compactPane = MutableStateFlow(CompactPane.Editor)
    val compactPane: StateFlow<CompactPane> = _compactPane.asStateFlow()

    private val _railVisible = MutableStateFlow(true)
    val railVisible: StateFlow<Boolean> = _railVisible.asStateFlow()

    /**
     * Live block content per outline. Held outside [uiState] on purpose — see [OutlineBox].
     */
    private val blocksByOutline = mutableMapOf<String, List<Block>>()

    /** Signals an edit that autosave should pick up; the payload is irrelevant. */
    private val edits = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private val selectedSection = MutableStateFlow<String?>(null)

    /** Page whose stored body failed to decode. Never written to. */
    private var readOnlyPageId: String? = null

    /** Last measured (viewport, canvas) width in dp, for [zoomToPageWidth]. */
    private var canvasWidths: Pair<Float, Float> = 0f to 0f

    init {
        viewModelScope.launch {
            // Nothing may observe the tree until first-run seeding has finished. A page's row is
            // created before its content is written, so a page opened inside that gap loads the
            // empty document the row was created with — and the next save writes that emptiness
            // over the seeded content. On any later launch this returns immediately.
            repository.seedIfEmpty()

            repository.observeTree()
                .onEach { tree ->
                    _uiState.value = _uiState.value.copy(tree = tree, loading = false)
                    // Land on something real on first launch rather than an empty editor.
                    if (selectedSection.value == null) {
                        tree.firstOrNull()?.liveSections?.firstOrNull()?.let { selectSection(it.id) }
                    }
                }
                .launchIn(this)

            selectedSection
                .filterNotNull()
                .flatMapLatest { repository.observePages(it) }
                .onEach { pages ->
                    _uiState.value = _uiState.value.copy(pages = pages)
                    val current = _uiState.value.selectedPageId
                    if (current == null || pages.none { it.id == current }) {
                        pages.firstOrNull()?.let { openPage(it.id) }
                            ?: run { _uiState.value = _uiState.value.copy(selectedPageId = null, title = "", outlines = emptyList()) }
                    }
                }
                .launchIn(this)

            // Debounced so a burst of keystrokes is one write, not one per character.
            edits
                .debounce(AUTOSAVE_DELAY_MS)
                .onEach { persist() }
                .launchIn(this)
        }
    }

    // --- navigation ----------------------------------------------------------------------------

    fun selectSection(sectionId: String) {
        if (selectedSection.value == sectionId) return
        viewModelScope.launch { persist() }
        selectedSection.value = sectionId
        _uiState.value = _uiState.value.copy(selectedSectionId = sectionId, selectedPageId = null)
        _compactPane.value = CompactPane.Pages
    }

    fun openPage(pageId: String) {
        viewModelScope.launch {
            persist()
            val page = _uiState.value.pages.firstOrNull { it.id == pageId }

            val doc = when (val load = repository.loadDoc(pageId)) {
                is PageLoad.Loaded -> {
                    readOnlyPageId = null
                    load.doc
                }
                is PageLoad.Unreadable -> {
                    // Show an empty canvas but refuse to write, so unreadable content is not
                    // replaced by the blank page standing in for it.
                    readOnlyPageId = pageId
                    PageDoc.empty()
                }
            }

            val loaded = doc.outlines.filterIsInstance<Outline.Text>()
                .ifEmpty { listOf(Outline.Text.empty()) }

            blocksByOutline.clear()
            loaded.forEach { blocksByOutline[it.id] = it.blocks }

            _uiState.value = _uiState.value.copy(
                selectedPageId = pageId,
                title = page?.title.orEmpty(),
                createdAt = page?.createdAt ?: System.currentTimeMillis(),
                outlines = loaded.map { OutlineBox(it.id, it.x, it.y, it.width, it.minHeight) },
                pageStyle = doc.style,
                pageRevision = _uiState.value.pageRevision + 1,
                contentError = if (readOnlyPageId == pageId) {
                    "This page could not be read, so editing is disabled to protect its contents."
                } else {
                    null
                },
            )
            _selection.value = SelectionState()
            _compactPane.value = CompactPane.Editor
        }
    }

    fun showCompactPane(pane: CompactPane) {
        _compactPane.value = pane
    }

    fun toggleRail() {
        _railVisible.value = !_railVisible.value
    }

    fun toggleNotebookExpanded(notebookId: String, expanded: Boolean) {
        viewModelScope.launch { repository.setNotebookExpanded(notebookId, expanded) }
    }

    // --- outlines ------------------------------------------------------------------------------

    fun initialBlocksFor(outlineId: String): List<Block> =
        blocksByOutline[outlineId] ?: listOf(Block.empty())

    /**
     * Creates a container where the user tapped empty canvas. This is what makes a page a canvas
     * rather than a document: text goes where you put it.
     */
    fun createOutline(x: Float, y: Float): String {
        val id = newId()
        blocksByOutline[id] = listOf(Block.empty())
        _uiState.value = _uiState.value.copy(
            outlines = _uiState.value.outlines + OutlineBox(
                id = id,
                x = x.coerceAtLeast(0f),
                y = y.coerceAtLeast(0f),
                width = Outline.Text.DEFAULT_WIDTH,
            ),
        )
        return id
    }

    fun moveOutline(outlineId: String, x: Float, y: Float) {
        _uiState.value = _uiState.value.copy(
            outlines = _uiState.value.outlines.map {
                if (it.id == outlineId) it.copy(x = x.coerceAtLeast(0f), y = y.coerceAtLeast(0f)) else it
            },
        )
        edits.tryEmit(Unit)
    }

    fun resizeOutline(outlineId: String, width: Float) {
        _uiState.value = _uiState.value.copy(
            outlines = _uiState.value.outlines.map {
                if (it.id == outlineId) it.copy(width = width.coerceIn(MIN_OUTLINE_WIDTH, MAX_OUTLINE_WIDTH)) else it
            },
        )
        edits.tryEmit(Unit)
    }

    fun setOutlineMinHeight(outlineId: String, minHeight: Float) {
        _uiState.value = _uiState.value.copy(
            outlines = _uiState.value.outlines.map {
                if (it.id == outlineId) it.copy(minHeight = minHeight.coerceIn(0f, MAX_OUTLINE_HEIGHT)) else it
            },
        )
        edits.tryEmit(Unit)
    }

    /**
     * Discards a container the user tapped into but never typed in, so clicking around the page
     * does not litter it with empty boxes. The last remaining container always survives.
     */
    fun onOutlineBlurred(outlineId: String) {
        // No recorded content means "unknown", not "empty" — an absent entry must never be
        // grounds for deleting a container, since `emptyList().all { }` is vacuously true.
        val blocks = blocksByOutline[outlineId] ?: return
        if (blocks.isEmpty()) return
        val isEmpty = blocks.all { it.text.isBlank() }
        if (!isEmpty || _uiState.value.outlines.size <= 1) return

        blocksByOutline.remove(outlineId)
        _uiState.value = _uiState.value.copy(
            outlines = _uiState.value.outlines.filterNot { it.id == outlineId },
        )
        edits.tryEmit(Unit)
    }

    fun onBlocksChanged(outlineId: String, blocks: List<Block>) {
        blocksByOutline[outlineId] = blocks
        edits.tryEmit(Unit)
    }

    fun onSelectionChanged(state: SelectionState) {
        _selection.value = state
    }

    fun send(command: FormatCommand) {
        _commands.tryEmit(command)
    }

    /**
     * Records a font or size chosen with nothing selected as the new default.
     *
     * Called by the editor when a mark had no range to apply to, never inferred from the last
     * reported [SelectionState]. That distinction is the whole point: the ribbon's view of the
     * selection can be a beat behind the editor's — a container re-render or the dropdown's focus
     * round-trip is enough — and acting on a stale "nothing is selected" rewrote the default while
     * the user had text selected, restyling every unmarked block on the page.
     *
     * The editor already arms the mark for characters typed immediately after, and adjacent text
     * inherits it because inline spans are end-inclusive. This covers what that cannot reach: text
     * with no neighbour to inherit from, in a fresh container or a page opened later.
     */
    fun rememberDefaultMark(mark: Mark) {
        // The ribbon describes the text at the caret, so a pick made with no editor focused would
        // otherwise leave the combo reading whatever it read before. Reflecting it here makes the
        // choice visible immediately; clicking into text replaces it with that text's own marks.
        _selection.value = _selection.value.let { state ->
            state.copy(marks = state.marks.filterNot { it.sameKindAs(mark) }.toSet() + mark)
        }
        viewModelScope.launch {
            when (mark) {
                is Mark.FontFamily -> editorDefaultsStore.setFontFamily(mark.name)
                is Mark.FontSize -> editorDefaultsStore.setFontSize(mark.sp)
                else -> Unit
            }
        }
    }

    // --- view ----------------------------------------------------------------------------------

    fun setRuleLines(rule: RuleLines) = updatePageStyle { it.copy(ruleLines = rule) }

    /** Null restores the theme's canvas colour rather than painting a light page dark. */
    fun setPageColor(argb: Int?) = updatePageStyle { it.copy(backgroundArgb = argb) }

    /**
     * Choosing Custom seeds the dimensions from the size being left behind, so the Width and Height
     * fields open on the page the user is already looking at rather than on a guess.
     */
    fun setPaperSize(paper: PaperSize) = updatePageStyle { style ->
        val seeded = if (paper == PaperSize.Custom && style.customPaper == null) {
            style.paperInches ?: PaperDimensions.DEFAULT
        } else {
            style.customPaper
        }
        style.copy(paper = paper, customPaper = seeded)
    }

    fun setCustomPaper(dimensions: PaperDimensions) =
        updatePageStyle { it.copy(paper = PaperSize.Custom, customPaper = dimensions) }

    fun setMargins(margins: PrintMargins) = updatePageStyle { it.copy(margins = margins) }

    fun setOrientation(orientation: Orientation) = updatePageStyle { it.copy(orientation = orientation) }

    fun setHideTitle(hidden: Boolean) = updatePageStyle { it.copy(hideTitle = hidden) }

    /**
     * Page appearance is part of the document, so a change goes through the same autosave path as
     * typing rather than being written immediately — switching pages mid-change still persists it.
     */
    private fun updatePageStyle(block: (PageStyle) -> PageStyle) {
        if (_uiState.value.selectedPageId == null) return
        val updated = block(_uiState.value.pageStyle)
        if (updated == _uiState.value.pageStyle) return
        _uiState.value = _uiState.value.copy(pageStyle = updated)
        edits.tryEmit(Unit)
    }

    fun setZoom(zoom: Float) {
        viewModelScope.launch { viewSettingsStore.setZoom(zoom) }
    }

    fun zoomIn() = setZoom(ViewSettings.zoomStepUp(viewSettings.value.zoom))

    fun zoomOut() = setZoom(ViewSettings.zoomStepDown(viewSettings.value.zoom))

    /**
     * Fits the page's width to the window.
     *
     * The two widths this needs are known only where the canvas is laid out, so the canvas reports
     * them and the ribbon stays ignorant of geometry — the same one-way arrangement as AD6's
     * command bus. They are held outside [uiState] because they change with every layout pass and
     * nothing renders from them.
     */
    fun zoomToPageWidth() {
        val (viewport, content) = canvasWidths
        ViewSettings.fitZoom(viewport, content)?.let(::setZoom)
    }

    fun onCanvasMeasured(viewportWidthDp: Float, contentWidthDp: Float) {
        canvasWidths = viewportWidthDp to contentWidthDp
    }

    fun setTabsLayout(layout: TabsLayout) {
        viewModelScope.launch { viewSettingsStore.setTabsLayout(layout) }
    }

    /**
     * Pins the canvas light or dark, independently of the app's own theme — OneNote lets a light
     * page sit inside a dark shell. The caller passes what the canvas should become, because until
     * this is used the canvas simply follows the theme and only the UI knows what that resolved to.
     */
    fun setCanvasDark(dark: Boolean) {
        viewModelScope.launch { viewSettingsStore.setCanvasDark(dark) }
    }

    // --- pages, sections, notebooks -------------------------------------------------------------

    fun createNotebook(name: String) {
        viewModelScope.launch {
            val id = repository.createNotebook(name.ifBlank { "New Notebook" })
            val sectionId = repository.createSection(id, "New Section")
            selectSection(sectionId)
        }
    }

    fun createSection(notebookId: String, name: String) {
        viewModelScope.launch {
            val id = repository.createSection(notebookId, name.ifBlank { "New Section" })
            selectSection(id)
            addPage()
        }
    }

    fun addPage() {
        val sectionId = selectedSection.value ?: return
        viewModelScope.launch {
            persist()
            val id = repository.createPage(sectionId)
            openPage(id)
        }
    }

    fun renameSection(sectionId: String, name: String) {
        viewModelScope.launch { repository.renameSection(sectionId, name) }
    }

    fun renameNotebook(notebookId: String, name: String) {
        viewModelScope.launch { repository.renameNotebook(notebookId, name) }
    }

    fun deleteSection(sectionId: String) {
        viewModelScope.launch {
            repository.deleteSection(sectionId)
            if (selectedSection.value == sectionId) {
                selectedSection.value = null
                _uiState.value = _uiState.value.copy(
                    selectedSectionId = null,
                    selectedPageId = null,
                    pages = emptyList(),
                    outlines = emptyList(),
                )
                _uiState.value.tree.firstOrNull()?.liveSections?.firstOrNull()?.let { selectSection(it.id) }
            }
        }
    }

    fun deletePage(pageId: String) {
        viewModelScope.launch { repository.deletePage(pageId) }
    }

    fun setTitle(title: String) {
        val pageId = _uiState.value.selectedPageId ?: return
        _uiState.value = _uiState.value.copy(title = title)
        viewModelScope.launch { repository.renamePage(pageId, title) }
    }

    /** Writes the current document, so switching pages cannot lose the last keystrokes. */
    private suspend fun persist() {
        val state = _uiState.value
        val pageId = state.selectedPageId ?: return
        if (state.outlines.isEmpty()) return
        // The page's stored content could not be read, so anything shown is a placeholder rather
        // than the user's work. Writing it would destroy the real content.
        if (readOnlyPageId == pageId) return

        val outlines = mutableListOf<Outline.Text>()
        for (box in state.outlines) {
            // A missing entry means "content not known", never "content is empty". Writing an
            // empty outline for one would silently blank it, so an incomplete picture skips the
            // write entirely and waits for the next edit.
            val blocks = blocksByOutline[box.id] ?: return
            // Containers the user tapped into but never typed in are not written; they exist only
            // as a caret position until there is something to hold.
            if (blocks.all { it.text.isBlank() }) continue
            outlines += Outline.Text(
                id = box.id,
                x = box.x,
                y = box.y,
                width = box.width,
                minHeight = box.minHeight,
                blocks = blocks,
            )
        }
        repository.saveDoc(pageId, PageDoc(outlines = outlines, style = state.pageStyle))
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 400L
        const val MIN_OUTLINE_WIDTH = 120f
        const val MAX_OUTLINE_WIDTH = 2000f
        const val MAX_OUTLINE_HEIGHT = 4000f

        fun factory(
            repository: NotesRepository,
            editorDefaultsStore: EditorDefaultsStore,
            viewSettingsStore: ViewSettingsStore,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotesViewModel(repository, editorDefaultsStore, viewSettingsStore) as T
        }
    }
}
