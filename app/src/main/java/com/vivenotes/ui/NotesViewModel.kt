package com.vivenotes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EditorDefaultsStore
import com.vivenotes.data.EraserMode
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import androidx.ink.strokes.Stroke
import com.vivenotes.data.PenPreset
import com.vivenotes.data.PenSettingsStore
import com.vivenotes.data.TabsLayout
import com.vivenotes.data.ViewSettings
import com.vivenotes.data.ViewSettingsStore
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkLassoMove
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.eraseObjects
import com.vivenotes.ink.moveSelected
import com.vivenotes.ink.replayMove
import com.vivenotes.ink.subtract
import com.vivenotes.ink.targetsFor
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Orientation
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins
import com.vivenotes.model.RuleLines
import com.vivenotes.model.newId
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.SelectionState
import com.vivenotes.richtext.sameKindAs

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

private sealed interface StoredInkOperation {
    val createdAt: Long
    val id: String

    data class Erase(val stored: InkEraseWithTargets) : StoredInkOperation {
        override val createdAt: Long get() = stored.erase.createdAt
        override val id: String get() = stored.erase.id
    }

    data class Move(val stored: InkMoveWithTargets) : StoredInkOperation {
        override val createdAt: Long get() = stored.move.createdAt
        override val id: String get() = stored.move.id
    }
}

/** Whether the open page has a Draw-toolbar action in either direction. */
data class InkUndoState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

private sealed interface InkHistoryMutation {
    /** A draw adds these rows; undo hides them and redo restores them. */
    data class AddStrokes(val ids: List<String>) : InkHistoryMutation

    /** A whole-object deletion hides these rows; undo restores them. */
    data class EraseStrokes(val ids: List<String>) : InkHistoryMutation

    data class EraseOperation(val id: String) : InkHistoryMutation
    data class MoveOperation(val id: String) : InkHistoryMutation
}

private data class InkHistoryEntry(
    val before: List<PageStroke>,
    val after: List<PageStroke>,
    val mutation: InkHistoryMutation,
)

private data class PageInkHistory(
    val undo: MutableList<InkHistoryEntry> = mutableListOf(),
    val redo: MutableList<InkHistoryEntry> = mutableListOf(),
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
    private val penSettingsStore: PenSettingsStore,
    private val inkDispatcher: CoroutineDispatcher = Dispatchers.Default,
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

    /** The Draw tab's three pens. How the user likes to draw, so preferences rather than document. */
    val pens: StateFlow<List<PenPreset>> = penSettingsStore.pens
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            List(PenPreset.COUNT) { PenPreset.starting(it) },
        )

    /** Partial/object mode and diameter are user preferences, not properties of a page. */
    val eraser: StateFlow<EraserSettings> = penSettingsStore.eraser
        .stateIn(viewModelScope, SharingStarted.Eagerly, EraserSettings())

    /** Whether a finger draws or scrolls. A property of this device, so it persists. */
    val drawWithFinger: StateFlow<Boolean> = penSettingsStore.drawWithFinger
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The tool in hand. Held here rather than in preferences: it is where you are, not what you
     * have, and an app that reopens with an eraser armed would be startling.
     *
     * A pen by default, because this is a notebook you draw in — text is what the Home tab's T
     * button is for. With a pen armed a tap on bare canvas leaves a mark rather than opening a
     * caret, which is why [createOutline] is reached only in [DrawTool.None].
     */
    private val _tool = MutableStateFlow<DrawTool>(DrawTool.Pen(0))
    val tool: StateFlow<DrawTool> = _tool.asStateFlow()

    /** The open page's ink, in draw order. Empty while no page is open. */
    private val _strokes = MutableStateFlow<List<PageStroke>>(emptyList())
    val strokes: StateFlow<List<PageStroke>> = _strokes.asStateFlow()

    /** Availability for the open page's bounded, session-local ink history. */
    private val _inkUndoState = MutableStateFlow(InkUndoState())
    val inkUndoState: StateFlow<InkUndoState> = _inkUndoState.asStateFlow()

    /** Snapshots are shallow lists: native strokes are immutable and safely shared between entries. */
    private val inkHistoryByPage = mutableMapOf<String, PageInkHistory>()

    /** An erase resolves native geometry off-thread; history pauses until its action is committed. */
    private val pendingInkEditsByPage = mutableMapOf<String, Int>()

    /** Keeps stroke inserts, whole erases and replayable partial erases in gesture order. */
    private val inkMutations = Mutex()

    /** Wall time made strictly increasing so erase/move replay never has an ambiguous tie. */
    private var lastInkOperationAt = 0L

    /** Commands travel to the focused editor, the only thing that can act on them. */
    private val _commands = MutableSharedFlow<FormatCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<FormatCommand> = _commands

    private val _compactPane = MutableStateFlow(CompactPane.Editor)
    val compactPane: StateFlow<CompactPane> = _compactPane.asStateFlow()

    /** Whether the page list, and therefore the docked navigation area, is showing. */
    private val _navigationVisible = MutableStateFlow(true)
    val navigationVisible: StateFlow<Boolean> = _navigationVisible.asStateFlow()

    /** The notebook rail can collapse independently while its selected section's pages stay open. */
    private val _notebookRailVisible = MutableStateFlow(true)
    val notebookRailVisible: StateFlow<Boolean> = _notebookRailVisible.asStateFlow()

    /**
     * Live block content per outline. Held outside [uiState] on purpose — see [OutlineBox].
     */
    private val blocksByOutline = mutableMapOf<String, List<Block>>()

    /**
     * Outlines this ViewModel does not manage — images and ink — with the position each held in the
     * document it was loaded from.
     *
     * [persist] rebuilds `PageDoc.outlines` from the text containers it tracks, so an outline it did
     * not put there is not merely ignored: it is written out of existence by the next autosave, 400ms
     * after the next keystroke. Nothing produces those variants yet, which is exactly why the loss
     * would be silent when something does — the write succeeds and the page looks fine until the
     * drawing is gone. Carrying them through untouched keeps load → save → load the identity for a
     * document this ViewModel only half understands.
     */
    private var unmanagedOutlines: List<IndexedValue<Outline>> = emptyList()

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
        _strokes.value = emptyList()
        publishInkUndoState(null)
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
                // A page with nothing on it still needs somewhere to put the caret, placed clear of
                // the title rather than under it — outline coordinates start at the page's corner.
                .ifEmpty { listOf(Outline.Text.empty(y = if (doc.style.hideTitle) 0f else PageStyle.TITLE_BAND_DP)) }

            blocksByOutline.clear()
            loaded.forEach { blocksByOutline[it.id] = it.blocks }
            // Taken from the document rather than from [loaded], which is the text containers alone
            // and may have been substituted for an empty one. An unreadable page decodes to
            // PageDoc.empty(), so this clears — the outgoing page's outlines must not follow it.
            unmanagedOutlines = doc.outlines.withIndex().filterNot { it.value is Outline.Text }
            // Loading joins the same serialization lane as edits. Otherwise a fast page switch can
            // read an operation between its immediate canvas update and its database tombstone.
            _strokes.value = inkMutations.withLock { loadInk(pageId) }

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
            publishInkUndoState(pageId)
            _selection.value = SelectionState()
            _compactPane.value = CompactPane.Editor
        }
    }

    fun showCompactPane(pane: CompactPane) {
        _compactPane.value = pane
    }

    fun toggleNavigation() {
        if (_navigationVisible.value) {
            _navigationVisible.value = false
        } else {
            _notebookRailVisible.value = true
            _navigationVisible.value = true
        }
    }

    fun hideNotebookRail() {
        _notebookRailVisible.value = false
    }

    fun hideNavigation() {
        // The next explicit reveal starts from the complete navigation, not its previously
        // collapsed intermediate state.
        _notebookRailVisible.value = true
        _navigationVisible.value = false
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
     * Shows a font or size picked with nothing focused, so the ribbon reflects the choice.
     *
     * The readout only. The editor arms the mark itself for whatever is typed next, and text typed
     * against existing writing inherits from it, so nothing here needs to reach the document.
     *
     * It used to persist the pick as the app-wide default as well, which meant choosing a size to
     * write one sentence in silently changed what every page opened afterwards started at. Moving
     * the default is now its own gesture — see [setDefaultFont].
     */
    fun onMarkArmed(mark: Mark) {
        _selection.value = _selection.value.let { state ->
            state.copy(
                marks = state.marks.filterNot { it.sameKindAs(mark) }.toSet() + mark,
                fontSize = (mark as? Mark.FontSize)?.sp ?: state.fontSize,
                fontFamily = (mark as? Mark.FontFamily)?.name ?: state.fontFamily,
            )
        }
    }

    /**
     * Makes a font or size the one new writing starts in — the ribbon's press-and-hold.
     *
     * Reaches only text with nothing to inherit from: a fresh container, or a page opened later.
     * Existing writing is never restyled, which is why this is a preference and not a document
     * property — see [com.vivenotes.data.EditorDefaults].
     */
    fun setDefaultFont(mark: Mark) {
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

    // --- draw ----------------------------------------------------------------------------------

    /** Rebuilds one page from its live stroke rows and active replay operations. */
    private suspend fun loadInk(pageId: String): List<PageStroke> {
        // A stroke or operation that cannot be decoded costs only that item, not the page.
        val baseStrokes = repository.inkFor(pageId).mapNotNull { row ->
            InkCodec.decode(row)?.let { PageStroke(row.id, it) }
        }
        val storedOperations = buildList {
            repository.partialErasesFor(pageId).forEach { add(StoredInkOperation.Erase(it)) }
            repository.inkMovesFor(pageId).forEach { add(StoredInkOperation.Move(it)) }
        }.sortedWith(compareBy(StoredInkOperation::createdAt, StoredInkOperation::id))
        lastInkOperationAt = maxOf(
            lastInkOperationAt,
            storedOperations.maxOfOrNull { it.createdAt } ?: 0L,
        )
        if (storedOperations.isEmpty()) return baseStrokes
        return withContext(inkDispatcher) {
            storedOperations.fold(baseStrokes) { current, operation ->
                when (operation) {
                    is StoredInkOperation.Erase -> {
                        val stored = operation.stored
                        val mask = InkCodec.decodeErase(stored.erase) ?: return@fold current
                        val targets = stored.targets.map { it.strokeId }
                        when (stored.erase.mode) {
                            EraserMode.Normal -> current.subtract(mask, targets)
                            EraserMode.Object -> current.eraseObjects(mask, targets)
                        }
                    }
                    is StoredInkOperation.Move -> {
                        val stored = operation.stored
                        val path = InkCodec.decodeMove(stored.move) ?: return@fold current
                        current.replayMove(
                            path = path,
                            targetIds = stored.targets.map { it.strokeId },
                            dx = stored.move.dxDp,
                            dy = stored.move.dyDp,
                        )
                    }
                }
            }
        }
    }

    fun selectTool(tool: DrawTool) {
        if (tool != DrawTool.None) _commands.tryEmit(FormatCommand.DeactivateTextInput)
        _tool.value = tool
    }

    fun setDrawWithFinger(enabled: Boolean) {
        viewModelScope.launch { penSettingsStore.setDrawWithFinger(enabled) }
    }

    fun updateEraser(settings: EraserSettings) {
        viewModelScope.launch { penSettingsStore.setEraser(settings) }
    }

    /** The pen currently in hand, or null when the armed tool is not a pen. */
    fun activePen(): PenPreset? =
        (_tool.value as? DrawTool.Pen)?.let { pens.value.getOrNull(it.index) }

    /**
     * Records a finished stroke.
     *
     * Added to the in-memory list first and written second: the canvas draws from that list, and the
     * authoring view stops drawing the stroke the instant it hands it over, so waiting for the
     * database would leave a frame with the stroke on neither.
     */
    fun onStrokeFinished(stroke: Stroke) {
        val pageId = _uiState.value.selectedPageId ?: return
        // An unreadable page is never written to, and that has to include its ink.
        if (readOnlyPageId == pageId) return
        val pen = activePen() ?: return
        val entity = InkCodec.encode(stroke, pageId, seq = 0, pen = pen)
        val before = _strokes.value
        commitInkEdit(
            pageId = pageId,
            before = before,
            after = before + PageStroke(entity.id, stroke),
            mutation = InkHistoryMutation.AddStrokes(listOf(entity.id)),
        )
        viewModelScope.launch {
            inkMutations.withLock { repository.addStroke(entity) }
        }
    }

    /** Erases by tombstone. Same ordering as [onStrokeFinished], for the same reason. */
    fun eraseStrokes(ids: List<String>) {
        if (ids.isEmpty()) return
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val gone = ids.toSet()
        val before = _strokes.value
        if (before.none { it.id in gone }) return
        commitInkEdit(
            pageId = pageId,
            before = before,
            after = before.filterNot { it.id in gone },
            mutation = InkHistoryMutation.EraseStrokes(ids.distinct()),
        )
        viewModelScope.launch {
            inkMutations.withLock { repository.eraseStrokes(ids) }
        }
    }

    /**
     * Applies a normal eraser mask to only the strokes that existed when the gesture ended.
     * Geometry work stays off the input thread; the immutable mask and target set are then stored
     * so reopening the page reconstructs the same cut mesh.
     */
    fun eraseStrokeParts(mask: Stroke) = erase(mask, EraserMode.Normal)

    /** Deletes only the disconnected stroke regions touched by an Object-mode mask. */
    fun eraseStrokeObjects(mask: Stroke) = erase(mask, EraserMode.Object)

    /** Moves selected live projections immediately, then records the replayable lasso operation. */
    fun moveInk(move: InkLassoMove) {
        if (move.projections.isEmpty() || (move.dx == 0f && move.dy == 0f)) return
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val before = _strokes.value
        if (before.none { it.id in move.targetIds }) return
        val entity = InkCodec.encodeMove(
            path = move.path,
            pageId = pageId,
            dx = move.dx,
            dy = move.dy,
            now = nextInkOperationTime(),
        )
        commitInkEdit(
            pageId = pageId,
            before = before,
            after = before.moveSelected(move),
            mutation = InkHistoryMutation.MoveOperation(entity.id),
        )
        viewModelScope.launch {
            inkMutations.withLock {
                repository.addInkMove(entity, move.targetIds)
            }
        }
    }

    private fun erase(mask: Stroke, mode: EraserMode) {
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val candidates = _strokes.value
        changePendingInkEdits(pageId, 1)
        viewModelScope.launch {
            try {
                inkMutations.withLock {
                    val targetIds = withContext(inkDispatcher) { candidates.targetsFor(mask) }
                    if (targetIds.isEmpty()) return@withLock
                    // A stroke completed while the mask geometry was being calculated was not a
                    // target, but it is part of both snapshots and must not disappear from the canvas.
                    val before = if (_uiState.value.selectedPageId == pageId) _strokes.value else candidates
                    val updated = withContext(inkDispatcher) {
                        when (mode) {
                            EraserMode.Normal -> before.subtract(mask, targetIds)
                            EraserMode.Object -> before.eraseObjects(mask, targetIds)
                        }
                    }
                    val erase = InkCodec.encodeErase(mask, pageId, mode, now = nextInkOperationTime())
                    repository.addPartialErase(erase, targetIds)
                    commitInkEdit(
                        pageId = pageId,
                        before = before,
                        after = updated,
                        mutation = InkHistoryMutation.EraseOperation(erase.id),
                    )
                }
            } finally {
                changePendingInkEdits(pageId, -1)
            }
        }
    }

    /** Reverts the last committed ink gesture on the open page. */
    fun undoInk() {
        val pageId = _uiState.value.selectedPageId ?: return
        if ((pendingInkEditsByPage[pageId] ?: 0) > 0) return
        val history = inkHistoryByPage[pageId] ?: return
        if (history.undo.isEmpty()) return
        val entry = history.undo.removeAt(history.undo.lastIndex)
        history.redo += entry
        _strokes.value = entry.before
        publishInkUndoState(pageId)
        persistHistoryEntry(entry, applied = false)
    }

    /** Reapplies the next ink gesture previously removed by [undoInk]. */
    fun redoInk() {
        val pageId = _uiState.value.selectedPageId ?: return
        if ((pendingInkEditsByPage[pageId] ?: 0) > 0) return
        val history = inkHistoryByPage[pageId] ?: return
        if (history.redo.isEmpty()) return
        val entry = history.redo.removeAt(history.redo.lastIndex)
        history.undo += entry
        _strokes.value = entry.after
        publishInkUndoState(pageId)
        persistHistoryEntry(entry, applied = true)
    }

    private fun commitInkEdit(
        pageId: String,
        before: List<PageStroke>,
        after: List<PageStroke>,
        mutation: InkHistoryMutation,
    ) {
        val history = inkHistoryByPage.getOrPut(pageId, ::PageInkHistory)
        history.undo += InkHistoryEntry(before, after, mutation)
        if (history.undo.size > INK_HISTORY_LIMIT) history.undo.removeAt(0)
        // The abandoned operations are already tombstoned by their undo. Keeping their database
        // rows preserves sync history; only their in-memory route back is discarded.
        history.redo.clear()
        if (_uiState.value.selectedPageId == pageId) {
            _strokes.value = after
            publishInkUndoState(pageId)
        }
    }

    private fun publishInkUndoState(pageId: String? = _uiState.value.selectedPageId) {
        val history = pageId?.let(inkHistoryByPage::get)
        val pending = pageId != null && (pendingInkEditsByPage[pageId] ?: 0) > 0
        _inkUndoState.value = InkUndoState(
            canUndo = !pending && history?.undo?.isNotEmpty() == true,
            canRedo = !pending && history?.redo?.isNotEmpty() == true,
        )
    }

    private fun changePendingInkEdits(pageId: String, delta: Int) {
        val count = ((pendingInkEditsByPage[pageId] ?: 0) + delta).coerceAtLeast(0)
        if (count == 0) pendingInkEditsByPage.remove(pageId) else pendingInkEditsByPage[pageId] = count
        if (_uiState.value.selectedPageId == pageId) publishInkUndoState(pageId)
    }

    private fun persistHistoryEntry(entry: InkHistoryEntry, applied: Boolean) {
        viewModelScope.launch {
            inkMutations.withLock {
                when (val mutation = entry.mutation) {
                    is InkHistoryMutation.AddStrokes -> if (applied) {
                        repository.restoreStrokes(mutation.ids)
                    } else {
                        repository.eraseStrokes(mutation.ids)
                    }
                    is InkHistoryMutation.EraseStrokes -> if (applied) {
                        repository.eraseStrokes(mutation.ids)
                    } else {
                        repository.restoreStrokes(mutation.ids)
                    }
                    is InkHistoryMutation.EraseOperation ->
                        repository.setPartialEraseActive(mutation.id, applied)
                    is InkHistoryMutation.MoveOperation ->
                        repository.setInkMoveActive(mutation.id, applied)
                }
            }
        }
    }

    private fun nextInkOperationTime(): Long =
        maxOf(System.currentTimeMillis(), lastInkOperationAt + 1).also { lastInkOperationAt = it }

    /**
     * Persists a pen. Every field on it is a preference, so the write goes to DataStore and the
     * open document is not touched — changing your pen is not an edit to the page.
     */
    fun updatePen(index: Int, preset: PenPreset) {
        viewModelScope.launch { penSettingsStore.setPen(index, preset) }
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
                _strokes.value = emptyList()
                publishInkUndoState(null)
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
        repository.saveDoc(pageId, PageDoc(outlines = merged(outlines), style = state.pageStyle))
    }

    /**
     * Puts the outlines [persist] does not manage back where they were.
     *
     * Recorded indices are positions in the document as loaded — the combined list — so reinserting
     * them into the text-only list in ascending order lands each one at its original position, which
     * is what makes the round trip an identity. They are clamped rather than trusted: containers are
     * created and deleted while the page is open, so a recorded position can be past the end of the
     * list it is being restored into.
     */
    private fun merged(text: List<Outline.Text>): List<Outline> {
        if (unmanagedOutlines.isEmpty()) return text
        val outlines = ArrayList<Outline>(text)
        unmanagedOutlines.forEach { (index, outline) ->
            outlines.add(index.coerceAtMost(outlines.size), outline)
        }
        return outlines
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 400L
        private const val INK_HISTORY_LIMIT = 100
        const val MIN_OUTLINE_WIDTH = 120f
        const val MAX_OUTLINE_WIDTH = 2000f
        const val MAX_OUTLINE_HEIGHT = 4000f

        fun factory(
            repository: NotesRepository,
            editorDefaultsStore: EditorDefaultsStore,
            viewSettingsStore: ViewSettingsStore,
            penSettingsStore: PenSettingsStore,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotesViewModel(
                    repository,
                    editorDefaultsStore,
                    viewSettingsStore,
                    penSettingsStore,
                ) as T
        }
    }
}
