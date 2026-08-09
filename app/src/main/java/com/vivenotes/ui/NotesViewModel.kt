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
import kotlinx.coroutines.flow.combine
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
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import androidx.ink.strokes.Stroke
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.PenSettingsStore
import com.vivenotes.data.RulerSettings
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.TableSettings
import com.vivenotes.data.TabsLayout
import com.vivenotes.data.ViewSettings
import com.vivenotes.data.ViewSettingsStore
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.unionBounds
import com.vivenotes.ink.CanvasClipboard
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.InkLassoMove
import com.vivenotes.ink.InkLassoResize
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.eraseObjects
import com.vivenotes.ink.moveSelected
import com.vivenotes.ink.pageBounds
import com.vivenotes.ink.recolor
import com.vivenotes.ink.regroup
import com.vivenotes.ink.replayMove
import com.vivenotes.ink.replayResize
import com.vivenotes.ink.resizeSelected
import com.vivenotes.ink.subtract
import com.vivenotes.ink.targetsFor
import com.vivenotes.ui.editor.RibbonTab
import com.vivenotes.ink.translatedCopy
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Orientation
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.model.ink.arms
import com.vivenotes.model.ink.canFill
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.ink.withArm
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins
import com.vivenotes.model.RuleLines
import com.vivenotes.model.newId
import com.vivenotes.model.newTable
import com.vivenotes.model.withCellBlocks
import com.vivenotes.model.withColumnInserted
import com.vivenotes.model.withColumnRemoved
import com.vivenotes.model.withColumnWidth
import com.vivenotes.model.withNewIds
import com.vivenotes.model.withRowInserted
import com.vivenotes.model.withRowMinHeight
import com.vivenotes.model.withRowRemoved
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
data class CanvasUndoState(
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

    data class RecolorStrokes(
        val before: Map<String, Int>,
        val after: Map<String, Int>,
    ) : InkHistoryMutation

    data class RegroupStrokes(
        val before: Map<String, String?>,
        val after: Map<String, String?>,
    ) : InkHistoryMutation
}

/**
 * One reversible action on the canvas, of whatever kind — `docs/inkPlan.md` §5.4 SD10.
 *
 * **One ring across kinds, not one ring per kind.** Undo is a button, not a mode: what it reverses is
 * the last thing you did on this page, and a user who draws a stroke, drops a shape and presses Undo
 * expects the shape back — not the stroke, and not nothing. Two rings could only ever guess which of
 * them a press belonged to, and would have got it wrong every time the two kinds were interleaved.
 * The same argument AD7 makes for one selection and one tooltip, made again for history.
 *
 * The two arms differ in where the truth lives, which is why this is a sealed type rather than a list
 * of lambdas: ink is rows in `ink_strokes` and its entry names a *mutation* to replay against them,
 * while a shape is part of the document and its entry is simply the page's shape list on either side.
 */
private sealed interface CanvasHistoryEntry {

    data class Ink(
        val before: List<PageStroke>,
        val after: List<PageStroke>,
        val mutation: InkHistoryMutation,
    ) : CanvasHistoryEntry

    /**
     * The page's shapes before and after. Whole lists, because a shape edit can add, remove or alter
     * any number of them and the document is saved whole regardless — the same shallow-snapshot trade
     * the ink arm makes, over data that is already immutable.
     *
     * [coalesceKey] names actions that arrive as a stream but read as one: dragging the tooltip's
     * border-width slider fires per step, and thirty undo entries for one slider is not a history,
     * it is a nuisance. Consecutive entries sharing a key, within [SHAPE_COALESCE_MS], are merged.
     * Null — every other edit — always pushes its own entry.
     */
    data class Shapes(
        val before: List<Outline.Shape>,
        val after: List<Outline.Shape>,
        val coalesceKey: String? = null,
        val atMillis: Long = 0L,
    ) : CanvasHistoryEntry

    /**
     * A structural edit to the text containers — `docs/textBoxPlan.md` TD5: delete and paste, the two
     * things the TextBox toolkit can do.
     *
     * **The blocks half is scoped to the containers this edit touched, and the geometry half is not.**
     * The whole outline list is safe to snapshot because typing never changes it; the blocks map is
     * not, because typing changes it constantly. A snapshot of the whole map, restored later, would
     * quietly take back every keystroke made in *other* containers since — an undo that reaches
     * sideways into text nobody was undoing.
     *
     * A null value in either map means "this container did not exist", which is what restores a
     * delete and takes back a paste.
     */
    data class Texts(
        val before: List<OutlineBox>,
        val after: List<OutlineBox>,
        val blocksBefore: Map<String, List<Block>?>,
        val blocksAfter: Map<String, List<Block>?>,
    ) : CanvasHistoryEntry

    /**
     * An edit to the tables — `docs/tablePlan.md` TA10.
     *
     * The same two-halves shape [Texts] has, for the same reason: the table list is safe to snapshot
     * whole because typing never changes it, and the block map is not, because typing changes it
     * constantly. A snapshot of the whole map would take back every keystroke made anywhere else on
     * the page since.
     *
     * [touchedCells] is scoped to the cells this edit added or removed. A null value means "this cell
     * did not exist", which is what restores a deleted row's text and takes away an undone insert's
     * blank cells.
     *
     * [coalesceKey] does the job it does for [Shapes]: a dragged column boundary reports every step
     * it passes through, and thirty undo entries for one drag is a nuisance rather than a history.
     */
    data class Tables(
        val before: List<Outline.Table>,
        val after: List<Outline.Table>,
        val blocksBefore: Map<String, List<Block>?> = emptyMap(),
        val blocksAfter: Map<String, List<Block>?> = emptyMap(),
        val coalesceKey: String? = null,
        val atMillis: Long = 0L,
    ) : CanvasHistoryEntry
}

private data class PageCanvasHistory(
    val undo: MutableList<CanvasHistoryEntry> = mutableListOf(),
    val redo: MutableList<CanvasHistoryEntry> = mutableListOf(),
)

data class NotesUiState(
    val tree: List<NotebookWithSections> = emptyList(),
    val selectedSectionId: String? = null,
    val pages: List<PageEntity> = emptyList(),
    val selectedPageId: String? = null,
    val title: String = "",
    val createdAt: Long = 0L,
    val outlines: List<OutlineBox> = emptyList(),
    /** Shapes on the canvas. Whole objects, so unlike ink they live in the document. */
    val shapes: List<Outline.Shape> = emptyList(),
    /**
     * The tables on the canvas — `docs/tablePlan.md`. **The grid, not the writing in it.**
     *
     * Split the way a text container is split, and for that reason: this changes when a row is added
     * or a column dragged, and a cell's text changes on every keystroke, so keeping the two together
     * would recompose the whole canvas as someone types. The cells carried here therefore hold
     * whatever they were *loaded* with and go stale the moment anything is typed; the live content is
     * in the ViewModel's block map, and `withCellBlocks` is what puts them back together at save time.
     *
     * Nothing else may read a cell's `blocks` from here. It is the one trap this shape sets.
     */
    val tables: List<Outline.Table> = emptyList(),
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

    /**
     * The zoom the canvas is actually drawn at, which is not always the one on disk.
     *
     * A pinch reports a new zoom every frame, and a preference store is the wrong place to hold
     * something changing at that rate — sixty file writes for one gesture, with the canvas waiting
     * on a disk round trip to redraw. So this is where zoom lives while the app is running and the
     * store is only where it is remembered: every setter writes here first and lands it there
     * afterwards, and a pinch skips the second half until the fingers come off.
     *
     * Null until something sets it, which is what lets the stored value be the starting point
     * without this having to wait for it.
     */
    private val liveZoom = MutableStateFlow<Float?>(null)

    /** Zoom, navigation layout and canvas brightness — this device's view, not the document's. */
    val viewSettings: StateFlow<ViewSettings> = combine(
        viewSettingsStore.settings,
        liveZoom,
    ) { stored, live -> if (live == null) stored else stored.copy(zoom = live) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ViewSettings())

    /** The Draw tab's three pens. How the user likes to draw, so preferences rather than document. */
    val pens: StateFlow<List<PenPreset>> = penSettingsStore.pens
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            List(PenPreset.COUNT) { PenPreset.starting(it) },
        )

    /** The pen pane's swatch row. Shared by the three pens, and rolling — see [addPaletteColor]. */
    val palette: StateFlow<List<Int>> = penSettingsStore.palette
        .stateIn(viewModelScope, SharingStarted.Eagerly, PEN_COLORS)

    /** Partial/object mode and diameter are user preferences, not properties of a page. */
    val eraser: StateFlow<EraserSettings> = penSettingsStore.eraser
        .stateIn(viewModelScope, SharingStarted.Eagerly, EraserSettings())

    /** Colour and width of the one highlighter, on the same terms as the pens and the eraser. */
    val highlighter: StateFlow<HighlighterSettings> = penSettingsStore.highlighter
        .stateIn(viewModelScope, SharingStarted.Eagerly, HighlighterSettings())

    /** The armed shape and how it is drawn — `docs/inkPlan.md` §5.4. A property of the user (ID5). */
    val shape: StateFlow<ShapeSettings> = penSettingsStore.shape
        .stateIn(viewModelScope, SharingStarted.Eagerly, ShapeSettings())

    /** Which ruler and how big — `docs/rulerPlan.md` RD2. A property of the user, like [shape]. */
    val ruler: StateFlow<RulerSettings> = penSettingsStore.ruler
        .stateIn(viewModelScope, SharingStarted.Eagerly, RulerSettings())

    /** How the next table arrives — `docs/tablePlan.md` TA7. A property of the user, like [shape]. */
    val table: StateFlow<TableSettings> = penSettingsStore.table
        .stateIn(viewModelScope, SharingStarted.Eagerly, TableSettings())

    /**
     * Whether the ruler is lying on the page — see [toggleRuler].
     *
     * Beside [_tool] rather than in preferences, and for its reason: this is where you are, not what
     * you have. Which ruler it is *is* what you have, and that persists.
     */
    private val _rulerOut = MutableStateFlow(false)
    val rulerOut: StateFlow<Boolean> = _rulerOut.asStateFlow()

    /** Whether a finger draws or scrolls. A property of this device, so it persists. */
    val drawWithFinger: StateFlow<Boolean> = penSettingsStore.drawWithFinger
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The tool in hand. Held here rather than in preferences: it is where you are, not what you
     * have, and an app that reopens with an eraser armed would be startling.
     *
     * A pen by default, because this is a notebook you draw in — text is what the Home tab's T
     * button is for. With a pen armed a tap on bare canvas leaves a mark rather than opening a
     * caret, which is why [createOutline] is reached only in [DrawTool.Text].
     */
    private val _tool = MutableStateFlow<DrawTool>(DrawTool.Pen(0))
    val tool: StateFlow<DrawTool> = _tool.asStateFlow()

    /**
     * Which ribbon tab is open.
     *
     * Held here rather than in `NotesApp`'s `remember` because it is no longer only the tab strip's
     * business: a stylus button changes the tool, and the tab that shows tools has to come forward
     * with it — see `ui/StylusButtons.kt`. Transient like [_tool], and for the same reason.
     */
    private val _activeTab = MutableStateFlow(RibbonTab.Home)
    val activeTab: StateFlow<RibbonTab> = _activeTab.asStateFlow()

    fun selectRibbonTab(tab: RibbonTab) {
        _activeTab.value = tab
    }

    /**
     * A stylus barrel-button press — `ui/StylusButtons.kt` decides what it arms and says why.
     *
     * Stateless, because the pen has already done the counting: a double click arrives as its own
     * keycode rather than as two presses this had to time.
     *
     * The Draw tab comes forward on every press. A button that silently changes what the pen does,
     * while the ribbon still shows Home, is a tool swap you have to discover by drawing.
     */
    fun pressStylusButton(press: StylusPress) {
        selectTool(nextToolForStylusButton(_tool.value, press))
        selectRibbonTab(RibbonTab.Draw)
    }

    /** The open page's ink, in draw order. Empty while no page is open. */
    private val _strokes = MutableStateFlow<List<PageStroke>>(emptyList())
    val strokes: StateFlow<List<PageStroke>> = _strokes.asStateFlow()

    /** Availability for the open page's bounded, session-local canvas history — ink and shapes. */
    private val _canvasUndoState = MutableStateFlow(CanvasUndoState())
    val canvasUndoState: StateFlow<CanvasUndoState> = _canvasUndoState.asStateFlow()

    /**
     * The shared prime object clipboard — `docs/diagram.md`.
     *
     * One clipboard for every kind on the canvas, not one per kind: copy a stroke and a shape in the
     * same loop and both come back on the same paste. Session-local, and shallow — native strokes are
     * immutable and an `Outline.Shape` is a data class, so snapshots are safe to share.
     */
    private var clipboard = CanvasClipboard()
    private val _hasClipboard = MutableStateFlow(false)
    val hasClipboard: StateFlow<Boolean> = _hasClipboard.asStateFlow()

    /**
     * Snapshots are shallow lists: native strokes are immutable, an `Outline.Shape` is a data class,
     * and both are safely shared between entries.
     */
    private val canvasHistoryByPage = mutableMapOf<String, PageCanvasHistory>()

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
     * Live block content per **content box** — a text container, or one cell of a table.
     *
     * Held outside [uiState] on purpose, for the reason [OutlineBox] gives. Keyed by an id rather
     * than by an outline since `docs/tablePlan.md` TA2: a cell is a box that holds blocks and has no
     * geometry of its own, so it belongs in the same map a container's content does — which is what
     * puts the whole Home ribbon inside a table without a second content path to keep in step.
     */
    private val blocksById = mutableMapOf<String, List<Block>>()

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
        publishCanvasUndoState(null)
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

            val tables = doc.outlines.filterIsInstance<Outline.Table>()

            blocksById.clear()
            loaded.forEach { blocksById[it.id] = it.blocks }
            // Cells join the same map, per TA2 — one content path for containers and cells alike,
            // which is what lets `initialBlocksFor` and `onBlocksChanged` serve both unchanged. An
            // ink table's cells are not in it at all (TA15): nothing types in them, so an entry
            // would be a promise of content that never arrives.
            tables.forEach { table ->
                val cells = table.contentCellIds().toSet()
                table.rows.forEach { row ->
                    row.cells.forEach { cell ->
                        if (cell.id in cells) blocksById[cell.id] = cell.blocks
                    }
                }
            }
            // Taken from the document rather than from [loaded], which is the text containers alone
            // and may have been substituted for an empty one. An unreadable page decodes to
            // PageDoc.empty(), so this clears — the outgoing page's outlines must not follow it.
            unmanagedOutlines = doc.outlines.withIndex().filterNot {
                it.value is Outline.Text || it.value is Outline.Shape || it.value is Outline.Table
            }
            // Loading joins the same serialization lane as edits. Otherwise a fast page switch can
            // read an operation between its immediate canvas update and its database tombstone.
            _strokes.value = inkMutations.withLock { loadInk(pageId) }

            _uiState.value = _uiState.value.copy(
                selectedPageId = pageId,
                title = page?.title.orEmpty(),
                createdAt = page?.createdAt ?: System.currentTimeMillis(),
                outlines = loaded.map { OutlineBox(it.id, it.x, it.y, it.width, it.minHeight) },
                shapes = doc.outlines.filterIsInstance<Outline.Shape>(),
                tables = tables,
                pageStyle = doc.style,
                pageRevision = _uiState.value.pageRevision + 1,
                contentError = if (readOnlyPageId == pageId) {
                    "This page could not be read, so editing is disabled to protect its contents."
                } else {
                    null
                },
            )
            publishCanvasUndoState(pageId)
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
        blocksById[outlineId] ?: listOf(Block.empty())

    /**
     * Creates a container where the user tapped empty canvas. This is what makes a page a canvas
     * rather than a document: text goes where you put it.
     */
    fun createOutline(x: Float, y: Float): String {
        val id = newId()
        blocksById[id] = listOf(Block.empty())
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
     * Deletes containers outright — the TextBox toolkit's Delete, `docs/textBoxPlan.md` TD5.
     *
     * **The "last container always survives" rule in [onOutlineBlurred] does not apply here.** That
     * one exists to sweep up boxes nobody asked for; this is a box someone asked to be rid of, and a
     * page with no text container on it is not broken — the next tap with the text tool armed makes
     * another.
     */
    fun deleteOutlines(outlineIds: Set<String>) {
        if (outlineIds.isEmpty()) return
        editTexts(outlineIds) { outlines ->
            outlineIds.forEach(blocksById::remove)
            outlines.filterNot { it.id in outlineIds }
        }
    }

    /**
     * Puts one container on the shared clipboard, text and all.
     *
     * Separate from [copySelection] because a text box is not in a `CanvasSelection` — TD1 declined
     * the object-selection half of AD7 — so what the toolkit is about is the *focused* container, and
     * that is an id rather than a selection.
     */
    fun copyOutline(outlineId: String) {
        val box = _uiState.value.outlines.firstOrNull { it.id == outlineId } ?: return
        val blocks = blocksById[outlineId].orEmpty()
        if (blocks.all { it.text.isBlank() }) return
        clipboard = CanvasClipboard(
            texts = listOf(
                Outline.Text(
                    id = box.id,
                    x = box.x,
                    y = box.y,
                    width = box.width,
                    minHeight = box.minHeight,
                    blocks = blocks,
                ),
            ),
        )
        _hasClipboard.value = true
    }

    /**
     * The one door every structural text edit goes through: page guard, both halves of the state,
     * history, autosave — `editShapes`' counterpart, and for the same reason.
     *
     * [touched] names the containers whose *blocks* this edit adds, removes or replaces, and is what
     * keeps the history entry from reaching sideways into text that was only being typed in. Geometry
     * is snapshotted whole, which is safe because typing never moves a box.
     */
    private inline fun editTexts(
        touched: Set<String>,
        change: (List<OutlineBox>) -> List<OutlineBox>,
    ) {
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val state = _uiState.value
        val before = state.outlines
        // Read before the change runs, because a caller is entitled to rewrite `blocksById`
        // inside it — which is how a delete takes the text with the box.
        val blocksBefore = touched.associateWith { blocksById[it] }
        val after = change(before)
        if (after == before && touched.all { blocksById[it] == blocksBefore[it] }) return

        _uiState.value = state.copy(outlines = after, pageRevision = state.pageRevision + 1)
        pushHistory(
            pageId,
            CanvasHistoryEntry.Texts(
                before = before,
                after = after,
                blocksBefore = blocksBefore,
                // Read after the change, so a caller that rewrote `blocksById` inside [change]
                // is recorded by what it left behind rather than by what it promised.
                blocksAfter = touched.associateWith { blocksById[it] },
            ),
        )
        edits.tryEmit(Unit)
    }

    /**
     * Discards a container the user tapped into but never typed in, so clicking around the page
     * does not litter it with empty boxes. The last remaining container always survives.
     */
    fun onOutlineBlurred(outlineId: String) {
        // Containers only. Since TA2 the block map also holds table cells, and a cell that happened
        // to be blank would otherwise be swept away here — taking its entry with it and leaving the
        // grid with a hole the next save would write out.
        if (_uiState.value.outlines.none { it.id == outlineId }) return
        // No recorded content means "unknown", not "empty" — an absent entry must never be
        // grounds for deleting a container, since `emptyList().all { }` is vacuously true.
        val blocks = blocksById[outlineId] ?: return
        if (blocks.isEmpty()) return
        val isEmpty = blocks.all { it.text.isBlank() }
        if (!isEmpty || _uiState.value.outlines.size <= 1) return

        blocksById.remove(outlineId)
        _uiState.value = _uiState.value.copy(
            outlines = _uiState.value.outlines.filterNot { it.id == outlineId },
        )
        edits.tryEmit(Unit)
    }

    fun onBlocksChanged(outlineId: String, blocks: List<Block>) {
        blocksById[outlineId] = blocks
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
        val clamped = zoom.coerceIn(ViewSettings.MIN_ZOOM, ViewSettings.MAX_ZOOM)
        liveZoom.value = clamped
        viewModelScope.launch { viewSettingsStore.setZoom(clamped) }
    }

    /**
     * One sample of a pinch — see [liveZoom].
     *
     * Deliberately does not write. The gesture is a hundred of these and one decision, and the
     * decision is [commitZoom].
     */
    fun pinchZoom(zoom: Float) {
        liveZoom.value = zoom.coerceIn(ViewSettings.MIN_ZOOM, ViewSettings.MAX_ZOOM)
    }

    /** The fingers came off: remember where they left it. */
    fun commitZoom() {
        val zoom = liveZoom.value ?: return
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
            InkCodec.decode(row)?.let {
                PageStroke(
                    id = row.id,
                    stroke = it,
                    brushFamily = row.brushFamily,
                    brushVersion = row.brushVersion,
                    stabilization = row.stabilization,
                    groupId = row.groupId,
                )
            }
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
                        val moved = current.replayMove(
                            path = path,
                            targetIds = stored.targets.map { it.strokeId },
                            dx = stored.move.dxDp,
                            dy = stored.move.dyDp,
                        )
                        moved.replayResize(
                            path = path,
                            targetIds = stored.targets.map { it.strokeId },
                            anchor = InkPoint(stored.move.anchorX, stored.move.anchorY),
                            scaleX = stored.move.scaleX,
                            scaleY = stored.move.scaleY,
                        )
                    }
                }
            }
        }
    }

    fun selectTool(tool: DrawTool) {
        // Text is the one tool that wants the caret and the IME; every other one — including
        // nothing at all — takes the page's gestures and should put them away first.
        if (tool != DrawTool.Text) _commands.tryEmit(FormatCommand.DeactivateTextInput)
        _tool.value = tool
    }

    fun setDrawWithFinger(enabled: Boolean) {
        viewModelScope.launch { penSettingsStore.setDrawWithFinger(enabled) }
    }

    fun updateEraser(settings: EraserSettings) {
        viewModelScope.launch { penSettingsStore.setEraser(settings) }
    }

    fun updateHighlighter(settings: HighlighterSettings) {
        viewModelScope.launch { penSettingsStore.setHighlighter(settings) }
    }

    fun updateShape(settings: ShapeSettings) {
        viewModelScope.launch { penSettingsStore.setShape(settings) }
    }

    fun updateTable(settings: TableSettings) {
        viewModelScope.launch { penSettingsStore.setTable(settings) }
    }

    fun updateRuler(settings: RulerSettings) {
        viewModelScope.launch { penSettingsStore.setRuler(settings) }
    }

    /**
     * Lays the ruler on the page, or picks it up — `docs/rulerPlan.md` RD1.
     *
     * Not [selectTool], and that is the whole design: a ruler is not something you draw *with*, it is
     * something you draw *against*, so it composes with whatever is in hand instead of replacing it.
     * Unpersisted for the reason the armed tool is — reopening the app should not leave a ruler lying
     * across the page.
     */
    fun toggleRuler() {
        _rulerOut.value = !_rulerOut.value
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
        // Which tool laid the stroke down decides how it is stored, because the brush family and
        // the stabilization on the row come from the tool rather than from the geometry.
        val entity = when (val tool = _tool.value) {
            is DrawTool.Pen -> pens.value.getOrNull(tool.index)
                ?.let { InkCodec.encode(stroke, pageId, seq = 0, pen = it) }
            DrawTool.Highlighter ->
                InkCodec.encode(stroke, pageId, seq = 0, highlighter = highlighter.value)
            else -> null
        } ?: return
        val before = _strokes.value
        commitInkEdit(
            pageId = pageId,
            before = before,
            after = before + PageStroke(
                id = entity.id,
                stroke = stroke,
                brushFamily = entity.brushFamily,
                brushVersion = entity.brushVersion,
                stabilization = entity.stabilization,
            ),
            mutation = InkHistoryMutation.AddStrokes(listOf(entity.id)),
        )
        viewModelScope.launch {
            inkMutations.withLock { repository.addStroke(entity) }
        }
    }

    // --- shapes ---------------------------------------------------------------------------------

    /**
     * Seeds a shape into the box just dragged and adds it to the document.
     *
     * A document edit rather than an ink write: a shape is an object, so it goes through the same
     * autosave the text containers do rather than through `ink_strokes`. It is still one entry on the
     * canvas history ring, which spans both kinds (SD10): where a shape is *stored* and what Undo
     * reverses are two different questions, and the answer to the second is always "the last thing
     * you did".
     *
     * The shape is selected on arrival, because the handles are how it is adjusted and a shape you
     * have to hunt for before you can move a corner is a shape you would rather have redrawn.
     */
    fun insertShape(
        shape: ShapeSettings,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): String? {
        val pageId = _uiState.value.selectedPageId ?: return null
        if (readOnlyPageId == pageId) return null

        val segments = seedSegments(shape.kind, startX, startY, endX, endY, ::newId)
        if (segments.isEmpty()) return null

        val created = Outline.Shape(
            id = newId(),
            kind = shape.kind,
            segments = segments,
            borderArgb = shape.borderColorArgb,
            borderWidth = shape.borderWidth.toFloat(),
            lineType = shape.lineType,
            fillArgb = shape.fillArgb,
        ).withRecomputedBounds()

        editShapes { it + created }
        // Drawing one shape puts the tool down. The handles are the point of a shape being an
        // object, and they are unreachable while the tool that draws new ones is still armed —
        // so placing one hands it straight to you, ready to adjust. Nothing armed genuinely means
        // nothing since TD2, so the next stray tap no longer opens a text container either.
        _tool.value = DrawTool.None
        return created.id
    }

    /**
     * Moves a shape by a delta — **once per gesture, not once per frame.**
     *
     * A delta composes safely, so per-frame calls were correct arithmetic; what they were not was one
     * *action*. Undo reverses actions, and sixty entries for one drag makes the button useless — as
     * did sixty autosaves. Both callers now report the whole travel on the lift, the layer from its
     * own preview and the lasso as it always did.
     */
    fun moveShape(shapeId: String, dx: Float, dy: Float) {
        moveShapes(setOf(shapeId), dx, dy)
    }

    /**
     * The lasso's half of a move: every shape it holds, in **one** edit.
     *
     * Not `ids.forEach(::moveShape)`, which is what it was. One gesture that moved three shapes then
     * cost three presses of Undo to take back, each putting one shape where the others no longer
     * were — a history that describes the implementation rather than what the user did.
     */
    fun moveShapes(shapeIds: Set<String>, dx: Float, dy: Float) {
        if (shapeIds.isEmpty() || (dx == 0f && dy == 0f)) return
        editShapes { shapes ->
            shapes.map { if (it.id in shapeIds) it.translated(dx, dy) else it }
        }
    }

    /**
     * Scales a shape about the corner opposite the one being dragged — AD7's four-corner resize.
     *
     * **Once per gesture, not once per frame.** The scale is absolute — where the finger ended up,
     * against the geometry the drag started from — so it is only correct applied to that starting
     * geometry, which is the shape this still holds precisely because the drag wrote nothing while
     * it was in flight. Both callers are built that way: the corner handles draw a preview and
     * commit on the lift, and the lasso has always done the same. Calling this per frame multiplies
     * a drag's scales into each other and the shape explodes.
     */
    fun resizeShape(shapeId: String, anchorX: Float, anchorY: Float, scaleX: Float, scaleY: Float) {
        resizeShapes(setOf(shapeId), anchorX, anchorY, scaleX, scaleY)
    }

    /** The lasso's half of a resize: every shape it holds, in one edit — see [moveShapes]. */
    fun resizeShapes(
        shapeIds: Set<String>,
        anchorX: Float,
        anchorY: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        if (shapeIds.isEmpty() || (scaleX == 1f && scaleY == 1f)) return
        editShapes { shapes ->
            shapes.map {
                if (it.id in shapeIds) it.scaledAbout(anchorX, anchorY, scaleX, scaleY) else it
            }
        }
    }

    /**
     * Moves one arm's free end along its own axis — the L's per-arm handles, `docs/inkPlan.md` SD9.
     *
     * The arm is looked up again here rather than passed in, so what is edited is an arm of the
     * shape as it stands now. That matters because the caller measured it a gesture ago: an arm the
     * shape no longer has is one this leaves alone rather than one it recreates.
     *
     * Absolute, like [resizeShape] and unlike [moveShape] — it says where the tip goes, not how far
     * it travelled — but unlike a scale it does not compound, so applying it per frame would be
     * harmless. It is still committed once, on the lift, because a drag that wrote every frame would
     * be a drag that autosaved every frame.
     */
    fun resizeShapeArm(shapeId: String, segmentId: String, atEnd: Boolean, along: Float) {
        updateShapeOutline(shapeId) { shape ->
            val arm = shape.arms().firstOrNull { it.segmentId == segmentId && it.atEnd == atEnd }
            arm?.let { shape.withArm(it, along) } ?: shape
        }
    }

    /**
     * Sets the border width of every selected shape — the shape half of the object toolkit.
     *
     * Not the same thing as `ShapeSettings.borderWidth`, which is how the *user* likes to draw shapes
     * and lives in DataStore (`docs/inkPlan.md` SD4). This edits the document. Changing one must never
     * change the other: one travels with the page, the other with the person.
     */
    fun setShapeBorderWidth(shapeIds: Set<String>, width: Float) {
        if (shapeIds.isEmpty()) return
        val clamped = width.coerceIn(
            ShapeSettings.MIN_BORDER_WIDTH.toFloat(),
            ShapeSettings.MAX_BORDER_WIDTH.toFloat(),
        )
        // The slider reports every step it passes through, so the run of them is one action to undo.
        editShapes(coalesceKey = "border-width:${shapeIds.sorted().joinToString(",")}") { shapes ->
            shapes.map { if (it.id in shapeIds) it.copy(borderWidth = clamped) else it }
        }
    }

    /**
     * Fills every selected shape, or clears the fill with null.
     *
     * Null is not transparent black: it is the *absence* of a fill, which is what a shape starts with
     * and what "None" on the toolkit's palette puts back. A shape with no inside — a line, an arrow,
     * an L — is filtered out here rather than trusted not to arrive, because the bar hides Fill for
     * those and a hidden control is not a guarantee.
     */
    fun setShapeFill(shapeIds: Set<String>, argb: Int?) {
        if (shapeIds.isEmpty()) return
        editShapes { shapes ->
            shapes.map { if (it.id in shapeIds && it.canFill) it.copy(fillArgb = argb) else it }
        }
    }

    /** Sets the border's line type on every selected shape — solid, dashed or dotted. */
    fun setShapeLineType(shapeIds: Set<String>, lineType: LineType) {
        if (shapeIds.isEmpty()) return
        editShapes { shapes ->
            shapes.map { if (it.id in shapeIds) it.copy(lineType = lineType) else it }
        }
    }

    /** Recolours the border of every selected shape — the tooltip's swatch, per AD7. */
    fun recolorShapes(shapeIds: Set<String>, argb: Int) {
        if (shapeIds.isEmpty()) return
        editShapes { shapes ->
            shapes.map { if (it.id in shapeIds) it.copy(borderArgb = argb) else it }
        }
    }

    /** Deletes every selected shape, in one document edit rather than one per shape. */
    fun deleteShapes(shapeIds: Set<String>) {
        if (shapeIds.isEmpty()) return
        editShapes { shapes -> shapes.filterNot { it.id in shapeIds } }
    }

    private inline fun updateShapeOutline(shapeId: String, change: (Outline.Shape) -> Outline.Shape) {
        editShapes { shapes ->
            shapes.map { if (it.id == shapeId) change(it) else it }
        }
    }

    /**
     * The one door every shape edit goes through: page guard, state, history, autosave.
     *
     * Having exactly one is what stopped shapes being the kind of object that is *almost* undoable —
     * before this, each mutation wrote the state and emitted an autosave by hand, and adding the ring
     * to five call sites would have meant forgetting it on the sixth.
     *
     * An edit that changes nothing is not an edit: it records no history and wakes no autosave, which
     * matters because a drag ending exactly where it began still reports itself.
     */
    private inline fun editShapes(
        coalesceKey: String? = null,
        change: (List<Outline.Shape>) -> List<Outline.Shape>,
    ) {
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val state = _uiState.value
        val before = state.shapes
        val after = change(before)
        if (after == before) return

        _uiState.value = state.copy(shapes = after)
        pushHistory(
            pageId,
            CanvasHistoryEntry.Shapes(
                before = before,
                after = after,
                coalesceKey = coalesceKey,
                atMillis = System.currentTimeMillis(),
            ),
        )
        edits.tryEmit(Unit)
    }

    // --- tables ---------------------------------------------------------------------------------

    /**
     * Puts a table where the user tapped — `docs/tablePlan.md` TA7.
     *
     * A document edit on the same footing as [insertShape], and it ends the same way: the tool goes
     * back down and the new object is handed to the caller so the page can select it. The handles are
     * the point of it being an object, and they are unreachable while the tool that makes new ones is
     * still armed.
     */
    fun insertTable(
        settings: TableSettings,
        x: Float,
        y: Float,
        /** A ruling for the stylus rather than a grid of text fields — `docs/tablePlan.md` TA15. */
        inkOnly: Boolean = false,
    ): String? {
        val pageId = _uiState.value.selectedPageId ?: return null
        if (readOnlyPageId == pageId) return null

        val created = newTable(
            columns = settings.columns,
            rows = settings.rows,
            x = x.coerceAtLeast(0f),
            y = y.coerceAtLeast(0f),
            headerRow = settings.headerRow,
            headerColumn = settings.headerColumn,
            borderArgb = settings.borderColorArgb,
            borderWidth = settings.borderWidth.toFloat(),
            fillArgb = settings.fillArgb,
            inkOnly = inkOnly,
        )

        editTables(created.contentCellIds().toSet()) { tables ->
            created.contentCellIds().forEach { blocksById[it] = listOf(Block.empty()) }
            tables + created
        }
        _tool.value = DrawTool.None
        return created.id
    }

    /** Deletes whole tables, cells and all — the toolkit's Delete. */
    fun deleteTables(tableIds: Set<String>) {
        if (tableIds.isEmpty()) return
        val gone = _uiState.value.tables.filter { it.id in tableIds }
        if (gone.isEmpty()) return
        editTables(gone.flatMap { it.contentCellIds() }.toSet()) { tables ->
            gone.forEach { table -> table.contentCellIds().forEach(blocksById::remove) }
            tables.filterNot { it.id in tableIds }
        }
    }

    fun moveTables(tableIds: Set<String>, dx: Float, dy: Float) {
        if (tableIds.isEmpty() || (dx == 0f && dy == 0f)) return
        editTables { tables ->
            tables.map { if (it.id in tableIds) it.translated(dx, dy) else it }
        }
    }

    /** The corner handles, and the lasso's half of a resize — once per gesture, per [resizeShapes]. */
    fun resizeTables(
        tableIds: Set<String>,
        anchorX: Float,
        anchorY: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        if (tableIds.isEmpty() || (scaleX == 1f && scaleY == 1f)) return
        editTables { tables ->
            tables.map {
                if (it.id in tableIds) it.scaledAbout(anchorX, anchorY, scaleX, scaleY) else it
            }
        }
    }

    /**
     * One column's width, from its handle in the top gutter — TA5.
     *
     * Coalesced, like the border-width slider: a drag reports every step it passes through, and one
     * drag is one thing to undo.
     */
    fun setTableColumnWidth(tableId: String, column: Int, width: Float) {
        editTables(coalesceKey = "column-width:$tableId:$column") { tables ->
            tables.map { if (it.id == tableId) it.withColumnWidth(column, width) else it }
        }
    }

    /** One row's floor, from its handle in the left gutter. A floor, never a height — TA3. */
    fun setTableRowMinHeight(tableId: String, row: Int, minHeight: Float) {
        editTables(coalesceKey = "row-height:$tableId:$row") { tables ->
            tables.map { if (it.id == tableId) it.withRowMinHeight(row, minHeight) else it }
        }
    }

    /**
     * The four actions the diagram asks of the class — `docs/diagram.md`, Table Class.
     *
     * All four take the row or column to act *at*, which the bar works out from where the caret is
     * (TA6). The model refuses what the caps or the last-row rule forbid, and [editTables] treats an
     * edit that changed nothing as no edit at all — so a refused action leaves no history entry
     * behind and wakes no autosave.
     */
    fun insertTableRow(tableId: String, at: Int) {
        val table = _uiState.value.tables.firstOrNull { it.id == tableId } ?: return
        val grown = table.withRowInserted(at)
        val added = grown.contentCellIds().toSet() - table.contentCellIds().toSet()
        editTables(added) { tables ->
            added.forEach { blocksById[it] = listOf(Block.empty()) }
            tables.map { if (it.id == tableId) grown else it }
        }
    }

    fun deleteTableRow(tableId: String, at: Int) {
        val table = _uiState.value.tables.firstOrNull { it.id == tableId } ?: return
        val shrunk = table.withRowRemoved(at)
        val removed = table.contentCellIds().toSet() - shrunk.contentCellIds().toSet()
        editTables(removed) { tables ->
            removed.forEach(blocksById::remove)
            tables.map { if (it.id == tableId) shrunk else it }
        }
    }

    fun insertTableColumn(tableId: String, at: Int) {
        val table = _uiState.value.tables.firstOrNull { it.id == tableId } ?: return
        val grown = table.withColumnInserted(at)
        val added = grown.contentCellIds().toSet() - table.contentCellIds().toSet()
        editTables(added) { tables ->
            added.forEach { blocksById[it] = listOf(Block.empty()) }
            tables.map { if (it.id == tableId) grown else it }
        }
    }

    fun deleteTableColumn(tableId: String, at: Int) {
        val table = _uiState.value.tables.firstOrNull { it.id == tableId } ?: return
        val shrunk = table.withColumnRemoved(at)
        val removed = table.contentCellIds().toSet() - shrunk.contentCellIds().toSet()
        editTables(removed) { tables ->
            removed.forEach(blocksById::remove)
            tables.map { if (it.id == tableId) shrunk else it }
        }
    }

    /** The border colour of every selected table — the tooltip's swatch, as it is for a shape. */
    fun recolorTables(tableIds: Set<String>, argb: Int) {
        if (tableIds.isEmpty()) return
        editTables { tables ->
            tables.map { if (it.id in tableIds) it.copy(borderArgb = argb) else it }
        }
    }

    /** Not the same setting as `TableSettings.borderWidth`, which is how the *next* table arrives. */
    fun setTableBorderWidth(tableIds: Set<String>, width: Float) {
        if (tableIds.isEmpty()) return
        val clamped = width.coerceIn(
            TableSettings.MIN_BORDER_WIDTH.toFloat(),
            TableSettings.MAX_BORDER_WIDTH.toFloat(),
        )
        editTables(coalesceKey = "table-border:${tableIds.sorted().joinToString(",")}") { tables ->
            tables.map { if (it.id in tableIds) it.copy(borderWidth = clamped) else it }
        }
    }

    /** Null is the absence of a fill, not a transparent one — the same value "None" restores. */
    fun setTableFill(tableIds: Set<String>, argb: Int?) {
        if (tableIds.isEmpty()) return
        editTables { tables ->
            tables.map { if (it.id in tableIds) it.copy(fillArgb = argb) else it }
        }
    }

    /**
     * The one door every table edit goes through: page guard, state, cell blocks, history, autosave —
     * `editShapes`' counterpart, and `docs/tablePlan.md` TA10.
     *
     * [touched] names the cells whose *blocks* this edit adds or removes, and is what keeps the
     * history entry from reaching sideways into cells that were only being typed in. The grid is
     * snapshotted whole, which is safe because typing never changes it.
     *
     * Structural edits bump `pageRevision`: an `OutlineEditText` holds its own text and will not
     * notice that the grid around it changed shape.
     */
    private inline fun editTables(
        touched: Set<String> = emptySet(),
        coalesceKey: String? = null,
        change: (List<Outline.Table>) -> List<Outline.Table>,
    ) {
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val state = _uiState.value
        val before = state.tables
        // Read before the change runs, because a caller is entitled to rewrite `blocksById` inside
        // it — which is how a deleted row takes its text with it.
        val blocksBefore = touched.associateWith { blocksById[it] }
        val after = change(before)
        if (after == before && touched.all { blocksById[it] == blocksBefore[it] }) return

        _uiState.value = state.copy(
            tables = after,
            // Only when the grid gained or lost cells. A column dragged wider must not rebuild every
            // editor on the page and take the caret with it.
            pageRevision = if (touched.isEmpty()) state.pageRevision else state.pageRevision + 1,
        )
        pushHistory(
            pageId,
            CanvasHistoryEntry.Tables(
                before = before,
                after = after,
                blocksBefore = blocksBefore,
                blocksAfter = touched.associateWith { blocksById[it] },
                coalesceKey = coalesceKey,
                atMillis = System.currentTimeMillis(),
            ),
        )
        edits.tryEmit(Unit)
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

    /** Commits one corner-handle drag as a replayable selection transform. */
    fun resizeInk(resize: InkLassoResize) {
        if (resize.projections.isEmpty() || (resize.scaleX == 1f && resize.scaleY == 1f)) return
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val before = _strokes.value
        if (before.none { it.id in resize.targetIds }) return
        val entity = InkCodec.encodeResize(resize, pageId, now = nextInkOperationTime())
        commitInkEdit(
            pageId = pageId,
            before = before,
            after = before.resizeSelected(resize),
            mutation = InkHistoryMutation.MoveOperation(entity.id),
        )
        viewModelScope.launch {
            inkMutations.withLock { repository.addInkMove(entity, resize.targetIds) }
        }
    }

    /**
     * Puts the selection on the shared clipboard, whatever it holds. The page does not change.
     *
     * Copy *is* copy: it does not drop a duplicate on the page, because the diagram pairs it with
     * double-tapping empty space to choose where the copy lands.
     */
    fun copySelection(selection: CanvasSelection) {
        val strokes = _strokes.value.filter { it.id in selection.inkIds }.distinctBy(PageStroke::id)
        val shapes = _uiState.value.shapes.filter { it.id in selection.shapeIds }
        // Read back through the block map, never off `uiState.tables`, whose cells go stale the
        // moment anything is typed — TA2. A table copied without what is in it is a grid of lines.
        val tables = _uiState.value.tables
            .filter { it.id in selection.tableIds }
            .map { table -> table.withCellBlocks(table.contentCellIds().associateWith { blocksById[it].orEmpty() }) }
        if (strokes.isEmpty() && shapes.isEmpty() && tables.isEmpty()) return
        clipboard = CanvasClipboard(strokes = strokes, shapes = shapes, tables = tables)
        _hasClipboard.value = true
    }

    /**
     * Pastes the clipboard with its union centre at [at], whatever kinds it holds.
     *
     * The centre is measured across **both** kinds, so a copied stroke-and-shape pair lands in the
     * same relative arrangement it was copied in rather than each kind centring itself.
     *
     * Each kind commits the way it already does — ink through `ink_strokes`, a shape through the
     * document's autosave — which is exactly AD7's second consequence: the same operation, applied by
     * each kind to its own representation. Both leave one entry on the one history ring (SD10), so a
     * paste of both kinds takes two presses of Undo to unwind. Worth knowing, and not worth a
     * cross-kind entry type to fix: each press does visibly undo half of it.
     */
    fun pasteObjects(at: InkPoint) {
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val sources = clipboard.strokes
        val sourceShapes = clipboard.shapes
        val sourceTexts = clipboard.texts
        val sourceTables = clipboard.tables
        if (sources.isEmpty() && sourceShapes.isEmpty() && sourceTexts.isEmpty() && sourceTables.isEmpty()) return

        val bounds = sources.mapNotNull(PageStroke::pageBounds) +
            sourceShapes.map(Outline.Shape::pageBounds) +
            // The sum of the row floors, which is the height the document can know — TA3. Off by
            // however far a cell's text runs past its row, exactly as a text box's floor is.
            sourceTables.map { InkBounds(it.x, it.y, it.x + it.width, it.y + it.height) } +
            // A container's height is whatever its text wraps to and only the canvas knows it, so
            // the floor stands in. It is off by however far the text runs past it, which moves a
            // pasted box up by half of that — visible only when a text box is pasted together with
            // something else, and cheaper to accept than to plumb a measurement into the ViewModel.
            sourceTexts.map { InkBounds(it.x, it.y, it.x + it.width, it.y + it.minHeight) }
        val union = bounds.unionBounds() ?: return
        val dx = at.x - union.center.x
        val dy = at.y - union.center.y

        if (sourceTexts.isNotEmpty()) {
            val pasted = sourceTexts.map { source ->
                source.copy(
                    id = newId(),
                    x = (source.x + dx).coerceAtLeast(0f),
                    y = (source.y + dy).coerceAtLeast(0f),
                )
            }
            editTexts(pasted.map { it.id }.toSet()) { outlines ->
                pasted.forEach { blocksById[it.id] = it.blocks }
                outlines + pasted.map { text ->
                    OutlineBox(
                        id = text.id,
                        x = text.x,
                        y = text.y,
                        width = text.width,
                        minHeight = text.minHeight,
                    )
                }
            }
        }

        if (sourceTables.isNotEmpty()) {
            // Fresh ids all the way down — table, rows and cells. Two tables sharing a cell id would
            // share the block map entry behind it, so typing in one would appear in the other.
            val pastedTables = sourceTables.map { source ->
                source.withNewIds().copy(
                    x = (source.x + dx).coerceAtLeast(0f),
                    y = (source.y + dy).coerceAtLeast(0f),
                )
            }
            editTables(pastedTables.flatMap { it.contentCellIds() }.toSet()) { tables ->
                pastedTables.forEach { table ->
                    val cells = table.contentCellIds().toSet()
                    table.rows.forEach { row ->
                        row.cells.forEach { if (it.id in cells) blocksById[it.id] = it.blocks }
                    }
                }
                tables + pastedTables
            }
        }

        if (sourceShapes.isNotEmpty()) {
            val pastedShapes = sourceShapes.map { source ->
                source.translated(dx, dy).copy(
                    id = newId(),
                    segments = source.segments
                        .map { it.copy(id = newId()) }
                        .map { it.translated(dx, dy) },
                )
            }
            editShapes { it + pastedShapes }
        }

        if (sources.isEmpty()) return
        val pastedGroups = sources.mapNotNull(PageStroke::groupId).distinct()
            .associateWith { newId() }
        val before = _strokes.value
        val now = nextInkOperationTime()
        val copies = sources.map { source ->
            val stroke = source.translatedCopy(dx, dy)
            val entity = InkCodec.encodeCopy(
                source = source,
                stroke = stroke,
                pageId = pageId,
                groupId = source.groupId?.let(pastedGroups::get),
                now = now,
            )
            entity to PageStroke(
                id = entity.id,
                stroke = stroke,
                brushFamily = entity.brushFamily,
                brushVersion = entity.brushVersion,
                stabilization = entity.stabilization,
                groupId = entity.groupId,
            )
        }
        commitInkEdit(
            pageId = pageId,
            before = before,
            after = before + copies.map { it.second },
            mutation = InkHistoryMutation.AddStrokes(copies.map { it.first.id }),
        )
        viewModelScope.launch {
            inkMutations.withLock { repository.addStrokes(copies.map { it.first }) }
        }
    }

    fun recolorInk(ids: Set<String>, colorArgb: Int) {
        if (ids.isEmpty()) return
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val before = _strokes.value
        val oldColors = before.filter { it.id in ids }
            .associate { it.id to it.stroke.brush.colorIntArgb }
        if (oldColors.isEmpty() || oldColors.values.all { it == colorArgb }) return
        val newColors = oldColors.keys.associateWith { colorArgb }
        commitInkEdit(
            pageId = pageId,
            before = before,
            after = before.recolor(oldColors.keys, colorArgb),
            mutation = InkHistoryMutation.RecolorStrokes(oldColors, newColors),
        )
        viewModelScope.launch {
            inkMutations.withLock { repository.setInkColors(newColors) }
        }
    }

    fun groupInk(ids: Set<String>) = setInkGroup(ids, newId())

    fun ungroupInk(ids: Set<String>) = setInkGroup(ids, null)

    private fun setInkGroup(ids: Set<String>, groupId: String?) {
        if (ids.size < 2 && groupId != null) return
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val before = _strokes.value
        val oldGroups = before.filter { it.id in ids }.associate { it.id to it.groupId }
        if (oldGroups.isEmpty() || oldGroups.values.all { it == groupId }) return
        val newGroups = oldGroups.keys.associateWith { groupId }
        commitInkEdit(
            pageId = pageId,
            before = before,
            after = before.regroup(newGroups),
            mutation = InkHistoryMutation.RegroupStrokes(oldGroups, newGroups),
        )
        viewModelScope.launch {
            inkMutations.withLock { repository.setInkGroups(newGroups) }
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

    /**
     * Reverts the last committed action on the open page's canvas, of whichever kind it was.
     *
     * The pending-ink guard covers the whole ring rather than the ink half alone. An erase resolves
     * its geometry off-thread, and until it commits the page's *last action* is not yet known — so
     * stepping back past it would take out whatever came before while the erase landed on top.
     */
    fun undoCanvas() {
        val pageId = _uiState.value.selectedPageId ?: return
        if ((pendingInkEditsByPage[pageId] ?: 0) > 0) return
        val history = canvasHistoryByPage[pageId] ?: return
        if (history.undo.isEmpty()) return
        val entry = history.undo.removeAt(history.undo.lastIndex)
        history.redo += entry
        applyHistoryEntry(entry, applied = false)
        publishCanvasUndoState(pageId)
    }

    /** Reapplies the next action previously removed by [undoCanvas]. */
    fun redoCanvas() {
        val pageId = _uiState.value.selectedPageId ?: return
        if ((pendingInkEditsByPage[pageId] ?: 0) > 0) return
        val history = canvasHistoryByPage[pageId] ?: return
        if (history.redo.isEmpty()) return
        val entry = history.redo.removeAt(history.redo.lastIndex)
        history.undo += entry
        applyHistoryEntry(entry, applied = true)
        publishCanvasUndoState(pageId)
    }

    /** One step in either direction: [applied] true replays the action, false takes it back. */
    private fun applyHistoryEntry(entry: CanvasHistoryEntry, applied: Boolean) {
        when (entry) {
            is CanvasHistoryEntry.Ink -> {
                _strokes.value = if (applied) entry.after else entry.before
                persistInkHistoryEntry(entry, applied)
            }
            // The document is the shape's storage, so putting the list back *is* the undo — and the
            // same autosave every other document edit rides carries it to disk.
            is CanvasHistoryEntry.Shapes -> {
                _uiState.value = _uiState.value.copy(
                    shapes = if (applied) entry.after else entry.before,
                )
                edits.tryEmit(Unit)
            }
            // A container is two halves in two places, so both move together or a restored box comes
            // back blank — see [CanvasHistoryEntry.Texts].
            is CanvasHistoryEntry.Texts -> {
                val blocks = if (applied) entry.blocksAfter else entry.blocksBefore
                blocks.forEach { (id, value) ->
                    if (value == null) blocksById.remove(id) else blocksById[id] = value
                }
                _uiState.value = _uiState.value.copy(
                    outlines = if (applied) entry.after else entry.before,
                    // The containers are rebuilt from scratch, because an `OutlineEditText` holds its
                    // own text and will not notice that the list behind it changed.
                    pageRevision = _uiState.value.pageRevision + 1,
                )
                edits.tryEmit(Unit)
            }
            // Both halves together, or an undone row deletion comes back with empty cells — the same
            // discipline [Texts] keeps, one dimension larger.
            is CanvasHistoryEntry.Tables -> {
                val blocks = if (applied) entry.blocksAfter else entry.blocksBefore
                blocks.forEach { (id, value) ->
                    if (value == null) blocksById.remove(id) else blocksById[id] = value
                }
                _uiState.value = _uiState.value.copy(
                    tables = if (applied) entry.after else entry.before,
                    pageRevision = _uiState.value.pageRevision + 1,
                )
                edits.tryEmit(Unit)
            }
        }
    }

    private fun commitInkEdit(
        pageId: String,
        before: List<PageStroke>,
        after: List<PageStroke>,
        mutation: InkHistoryMutation,
    ) {
        pushHistory(pageId, CanvasHistoryEntry.Ink(before, after, mutation))
        if (_uiState.value.selectedPageId == pageId) _strokes.value = after
    }

    /**
     * Records one action, dropping the redo branch it leaves behind.
     *
     * Consecutive shape edits that name the same [CanvasHistoryEntry.Shapes.coalesceKey] within
     * [SHAPE_COALESCE_MS] are folded into the entry already on the stack: its *before* is the state
     * the run started from, and its *after* moves forward with each step. That is what makes a slider
     * one undo rather than one per step, without a gesture protocol reaching all the way up here.
     */
    private fun pushHistory(pageId: String, entry: CanvasHistoryEntry) {
        val history = canvasHistoryByPage.getOrPut(pageId, ::PageCanvasHistory)
        val previous = history.undo.lastOrNull()

        val shapesCoalesce = entry is CanvasHistoryEntry.Shapes &&
            entry.coalesceKey != null &&
            previous is CanvasHistoryEntry.Shapes &&
            previous.coalesceKey == entry.coalesceKey &&
            entry.atMillis - previous.atMillis <= SHAPE_COALESCE_MS

        // The same rule for a table's dragged column boundary or border-width slider. Only entries
        // that touched no cells may fold together — one that added or removed a row carries a block
        // map, and merging two of those would drop the first one's.
        val tablesCoalesce = entry is CanvasHistoryEntry.Tables &&
            entry.coalesceKey != null &&
            entry.blocksAfter.isEmpty() &&
            previous is CanvasHistoryEntry.Tables &&
            previous.coalesceKey == entry.coalesceKey &&
            previous.blocksAfter.isEmpty() &&
            entry.atMillis - previous.atMillis <= SHAPE_COALESCE_MS

        if (shapesCoalesce) {
            history.undo[history.undo.lastIndex] = (previous as CanvasHistoryEntry.Shapes).copy(
                after = (entry as CanvasHistoryEntry.Shapes).after,
                atMillis = entry.atMillis,
            )
        } else if (tablesCoalesce) {
            history.undo[history.undo.lastIndex] = (previous as CanvasHistoryEntry.Tables).copy(
                after = (entry as CanvasHistoryEntry.Tables).after,
                atMillis = entry.atMillis,
            )
        } else {
            history.undo += entry
            if (history.undo.size > CANVAS_HISTORY_LIMIT) history.undo.removeAt(0)
        }
        // The abandoned operations are already tombstoned by their undo. Keeping their database
        // rows preserves sync history; only their in-memory route back is discarded.
        history.redo.clear()
        if (_uiState.value.selectedPageId == pageId) publishCanvasUndoState(pageId)
    }

    private fun publishCanvasUndoState(pageId: String? = _uiState.value.selectedPageId) {
        val history = pageId?.let(canvasHistoryByPage::get)
        val pending = pageId != null && (pendingInkEditsByPage[pageId] ?: 0) > 0
        _canvasUndoState.value = CanvasUndoState(
            canUndo = !pending && history?.undo?.isNotEmpty() == true,
            canRedo = !pending && history?.redo?.isNotEmpty() == true,
        )
    }

    private fun changePendingInkEdits(pageId: String, delta: Int) {
        val count = ((pendingInkEditsByPage[pageId] ?: 0) + delta).coerceAtLeast(0)
        if (count == 0) pendingInkEditsByPage.remove(pageId) else pendingInkEditsByPage[pageId] = count
        if (_uiState.value.selectedPageId == pageId) publishCanvasUndoState(pageId)
    }

    private fun persistInkHistoryEntry(entry: CanvasHistoryEntry.Ink, applied: Boolean) {
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
                    is InkHistoryMutation.RecolorStrokes ->
                        repository.setInkColors(if (applied) mutation.after else mutation.before)
                    is InkHistoryMutation.RegroupStrokes ->
                        repository.setInkGroups(if (applied) mutation.after else mutation.before)
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

    /**
     * Puts a colour mixed on the wheel at the head of the swatch row, dropping its tail.
     *
     * Separate from [updatePen] because the two write different things: the pen takes the colour,
     * and the row remembers it. A pen picking an existing swatch does not disturb the row.
     */
    fun addPaletteColor(argb: Int) {
        viewModelScope.launch { penSettingsStore.addPaletteColor(argb) }
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
                publishCanvasUndoState(null)
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
        // "Nothing loaded", which is what an empty page state means before `openPage` has filled it
        // in — a loaded page always has at least one container. Tables count, because deleting the
        // last container on a page that has one is a thing the toolkit can do, and a page that is
        // all table is still a page with work on it.
        if (state.outlines.isEmpty() && state.tables.isEmpty()) return
        // The page's stored content could not be read, so anything shown is a placeholder rather
        // than the user's work. Writing it would destroy the real content.
        if (readOnlyPageId == pageId) return

        val outlines = mutableListOf<Outline.Text>()
        for (box in state.outlines) {
            // A missing entry means "content not known", never "content is empty". Writing an
            // empty outline for one would silently blank it, so an incomplete picture skips the
            // write entirely and waits for the next edit.
            val blocks = blocksById[box.id] ?: return
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

        // The same "content unknown → write nothing at all" guard, over cells. What is *not* the
        // same is the blank case: a blank cell is written where a blank container is skipped —
        // `docs/tablePlan.md` TA12. An empty container is a caret position nobody typed in; an empty
        // cell is part of the grid's shape, and dropping it would resize the table on reload.
        val tables = mutableListOf<Outline.Table>()
        for (table in state.tables) {
            val cells = table.contentCellIds().associateWith { blocksById[it] ?: return }
            tables += table.withCellBlocks(cells)
        }

        repository.saveDoc(
            pageId,
            PageDoc(outlines = merged(state.shapes + tables + outlines), style = state.pageStyle),
        )
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
    private fun merged(managed: List<Outline>): List<Outline> {
        if (unmanagedOutlines.isEmpty()) return managed
        val outlines = ArrayList<Outline>(managed)
        unmanagedOutlines.forEach { (index, outline) ->
            outlines.add(index.coerceAtMost(outlines.size), outline)
        }
        return outlines
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 400L

        /** Far enough that a duplicate is not mistaken for the original not having copied. */
        private const val DUPLICATE_OFFSET = 16f
        private const val CANVAS_HISTORY_LIMIT = 100

        /**
         * How long a run of same-key shape edits keeps folding into one undo step.
         *
         * Long enough to cover the gaps between steps of a slider being dragged, short enough that
         * coming back to the same control after looking at the result is a new action to undo.
         */
        private const val SHAPE_COALESCE_MS = 1200L
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
