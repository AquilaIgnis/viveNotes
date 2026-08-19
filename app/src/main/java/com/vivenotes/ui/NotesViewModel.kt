package com.vivenotes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.net.Uri
import android.util.Log
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.ContentSearchIndex
import com.vivenotes.data.ContentSearchOutcome
import com.vivenotes.data.ContentSearchResults
import com.vivenotes.data.ImageTextIndexer
import com.vivenotes.data.ImageTextProgress
import com.vivenotes.data.InkPageLoader
import com.vivenotes.data.InkTextIndexer
import com.vivenotes.data.InkTextProgress
import com.vivenotes.data.DatabaseBackupManager
import com.vivenotes.data.DeletedItem
import com.vivenotes.data.DeletedItemKey
import com.vivenotes.data.DeletedItemKind
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EditorDefaultsStore
import com.vivenotes.data.EraserMode
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.NotebookTransferManager
import com.vivenotes.data.PageLoad
import com.vivenotes.data.PageRevisionLoad
import androidx.ink.strokes.Stroke
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.PenSettingsStore
import com.vivenotes.data.RulerSettings
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.StylusAction
import com.vivenotes.data.StylusButtonMap
import com.vivenotes.data.TableSettings
import com.vivenotes.data.TabsLayout
import com.vivenotes.data.ViewSettings
import com.vivenotes.data.ViewSettingsStore
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.unionBounds
import com.vivenotes.ink.CanvasClipboard
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.InkLassoMove
import com.vivenotes.ink.InkLassoResize
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageBounds
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.eraseObjects
import com.vivenotes.ink.keepingProjectionsOf
import com.vivenotes.ink.moveSelected
import com.vivenotes.ink.pageBounds
import com.vivenotes.ink.projectionKey
import com.vivenotes.ink.recolor
import com.vivenotes.ink.regroup
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
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.newId
import com.vivenotes.model.newTable
import com.vivenotes.model.search.ContentHit
import com.vivenotes.model.search.ContentKind
import com.vivenotes.model.search.ContentUnit
import com.vivenotes.model.search.ImagePlacement
import com.vivenotes.model.search.blockUnits
import com.vivenotes.model.search.titleUnit
import com.vivenotes.ai.inkPageLayout
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

/**
 * A hit the user asked to see — `docs/searchPlan.md` CS9.
 *
 * Carries where to go rather than what was found: the page it is on, the box that holds it, and the
 * range to select once that box has an editor. [tableId] is set for a cell, because a cell has no
 * geometry of its own and the canvas scrolls to the table it sits in (TA2).
 *
 * [start] and [end] are offsets into the box's editor text, which is what `Block.editorText` exists
 * to make true (CS5).
 */
data class ContentReveal(
    val pageId: String,
    val kind: ContentKind,
    val boxId: String,
    val tableId: String?,
    val start: Int,
    val end: Int,
    val inkStrokeIds: Set<String> = emptySet(),
    val inkBounds: InkBounds? = null,
)

/** What the Content panel is showing: a query, whether it is still running, and what it found. */
data class ContentSearchState(
    val query: String = "",
    val running: Boolean = false,
    /** Null until a query has finished. Non-null with no pages is "nothing matched". */
    val results: ContentSearchResults? = null,
)

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
        val before: Map<String, StrokeColor>,
        val after: Map<String, StrokeColor>,
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
     * The page's pictures before and after — the same whole-list snapshot [Shapes] takes, and safe
     * for the same reason: an `Outline.Image` is immutable and is a frame, not a photograph. Undoing
     * back past an insert therefore costs nothing and, in particular, does not touch the stored file
     * — which is why an attachment's reference is not released until the delete leaves the history.
     */
    data class Images(
        val before: List<Outline.Image>,
        val after: List<Outline.Image>,
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

    /**
     * An edit to the equations on the canvas.
     *
     * [Shapes]'s shape exactly, and for the same reasons — whole lists over immutable data, because
     * one edit can add, remove or alter any number of them and the document is saved whole anyway.
     * It needs none of [Tables]' second half: an equation's content *is* the object, so there is no
     * block map for it to reach into and nothing that typing can change behind its back.
     */
    data class Equations(
        val before: List<Outline.Equation>,
        val after: List<Outline.Equation>,
        val coalesceKey: String? = null,
        val atMillis: Long = 0L,
    ) : CanvasHistoryEntry

    /**
     * Several kinds' entries that are one action, and must be undone as one.
     *
     * **The exception to what `pasteObjects` decided, not a change of mind about it.** Paste leaves
     * one entry per kind and says so: each press of Undo visibly takes back half of a paste, so a
     * two-press unwind is a curiosity rather than a fault. Insert Space cannot make that bargain —
     * the whole point of it is that everything past the line moves *together*, so an entry per kind
     * would leave the page half-shifted between presses, with ink sitting where the text used to be.
     * A gesture whose correctness is the relationship between the kinds has to be one entry.
     *
     * Deliberately not the default for cross-kind work, and deliberately not built by scanning the
     * ring afterwards: it is opted into around one call ([NotesViewModel.asOneAction]), so the entries
     * that belong together are the ones that were produced together.
     */
    data class Composite(val parts: List<CanvasHistoryEntry>) : CanvasHistoryEntry
}

/**
 * A formula composed but not yet placed — what [DrawTool.Equation] is holding.
 *
 * Carries the box RaTeX measured for it, in page units, so that the tap which finally places it does
 * not have to render anything to find out how big it is. The panel rendered the formula once already
 * to check that it parses; this is that measurement, kept rather than thrown away.
 */
data class PendingEquation(
    val latex: String,
    val width: Float,
    val height: Float,
)

private data class PageCanvasHistory(
    val undo: MutableList<CanvasHistoryEntry> = mutableListOf(),
    val redo: MutableList<CanvasHistoryEntry> = mutableListOf(),
)

/** An immutable document captured before navigation clears or replaces the editor state. */
private data class PendingPageSave(
    val pageId: String,
    val doc: PageDoc,
)

/** The File tab's page-version browser. Payloads are decoded only for the selected row. */
data class VersionHistoryState(
    val pageId: String? = null,
    val loading: Boolean = false,
    val revisions: List<PageRevisionSummary> = emptyList(),
    val selectedRevision: PageRevisionSummary? = null,
    val preview: PageDoc? = null,
    val previewLoading: Boolean = false,
    val restoring: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/** Durable recovery rows plus the state of the one restore write currently in flight. */
data class DeletedItemsState(
    val loading: Boolean = true,
    val items: List<DeletedItem> = emptyList(),
    val restoring: DeletedItemKey? = null,
    val message: String? = null,
    val error: String? = null,
)

/** One completed delete offered immediately as a snackbar Undo convenience. */
data class DeletionNotice(
    val key: DeletedItemKey,
    val message: String,
)

private data class DeletedItemsStatus(
    val restoring: DeletedItemKey? = null,
    val message: String? = null,
    val error: String? = null,
)

private val DeletedItemKind.displayName: String
    get() = when (this) {
        DeletedItemKind.Notebook -> "Notebook"
        DeletedItemKind.Section -> "Section"
        DeletedItemKind.Page -> "Page"
    }

/** Progress and the one-shot result message for File-tab notebook transfers. */
data class NotebookTransferState(
    val running: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/** How much a notebook holds, for the wording of its delete confirmation. */
data class NotebookContents(
    val sections: Int,
    val pages: Int,
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
    /** Equations placed on the canvas — the Draw tab's ƒ. Objects, like shapes, not marks. */
    val equations: List<Outline.Equation> = emptyList(),
    /** Pictures on the canvas — E6. Frames only; the pixels are in `attachments`. */
    val images: List<Outline.Image> = emptyList(),
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
    private val attachments: AttachmentStore,
    private val editorDefaultsStore: EditorDefaultsStore,
    private val viewSettingsStore: ViewSettingsStore,
    private val penSettingsStore: PenSettingsStore,
    private val inkDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val databaseBackups: DatabaseBackupManager? = null,
    private val notebookTransfers: NotebookTransferManager? = null,
    /**
     * Reads pictures in the background so the Content panel can find what is written in them.
     *
     * Optional for the same reason [databaseBackups] is: the Compose suites build a ViewModel over a
     * seeded database with no ONNX Runtime behind it, and a search that finds typed text is still a
     * search. Null simply means no picture ever gets read.
     */
    private val imageText: ImageTextIndexer? = null,
    /** Reads replayed handwriting lazily for the same cache-only search path as picture OCR. */
    private val inkText: InkTextIndexer? = null,
    /**
     * Pages the server has written ink into, by generation — `memory/inkSyncPlan.md` IS5.
     *
     * Ink has no Room flow of its own on purpose; see
     * [com.vivenotes.data.sync.RemoteInkSignal] for why the page body has one and this does not.
     * Null for the suites that build a ViewModel with no server behind it, and it means what it says:
     * nothing ever arrives from anywhere else, which is what a device that has never been connected
     * actually experiences.
     */
    private val remoteInk: StateFlow<Map<String, Long>>? = null,
    /**
     * Whether first-run seeding may happen at all — see
     * [com.vivenotes.data.sync.SyncAccounts.maySeedStarter], which says no while this installation
     * is registered with a server it has not pulled from yet.
     *
     * Optional for the reason [databaseBackups] is: the suites build a ViewModel over a database
     * with no server behind it, and null there means "seed as this app always has".
     */
    private val maySeedStarter: (suspend () -> Boolean)? = null,
) : ViewModel() {

    private val inkPageLoader = InkPageLoader(repository, inkDispatcher)

    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    private val _versionHistory = MutableStateFlow(VersionHistoryState())
    val versionHistory: StateFlow<VersionHistoryState> = _versionHistory.asStateFlow()

    private val _deletedItemsStatus = MutableStateFlow(DeletedItemsStatus())
    val deletedItems: StateFlow<DeletedItemsState> = combine(
        repository.observeDeletedItems(),
        _deletedItemsStatus,
    ) { items, status ->
        DeletedItemsState(
            loading = false,
            items = items,
            restoring = status.restoring,
            message = status.message,
            error = status.error,
        )
    }
        .catch {
            emit(
                DeletedItemsState(
                    loading = false,
                    error = "Deleted items could not be loaded.",
                ),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DeletedItemsState())

    private val deletionNoticeChannel = Channel<DeletionNotice>(Channel.BUFFERED)
    val deletionNotices = deletionNoticeChannel.receiveAsFlow()

    private val _notebookTransfer = MutableStateFlow(NotebookTransferState())
    val notebookTransfer: StateFlow<NotebookTransferState> = _notebookTransfer.asStateFlow()

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
     * What the pen's barrel-button clicks do — [StylusButtonMap], and `docs/stylusPlan.md`.
     *
     * `Eagerly` and not lazily, unlike most of the settings flows: key dispatch reads `.value`
     * synchronously on the UI thread from `MainActivity.onKeyDown`, which can happen before anything
     * has collected this. The initial value is the default map, which is the hard-coded behaviour the
     * bindings replaced — so a press arriving in that window does the old thing rather than nothing.
     */
    val stylusButtons: StateFlow<StylusButtonMap> = penSettingsStore.stylusButtons
        .stateIn(viewModelScope, SharingStarted.Eagerly, StylusButtonMap())

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
     * The formula [DrawTool.Equation] is holding, waiting for a tap to say where it goes.
     *
     * **Beside the tool rather than in preferences, because it is content.** Every other object tool
     * carries settings — how many rows, which shape, how thick the rules — and those describe *the
     * user*, so they persist (ID5). A formula is the thing itself. Storing it would mean reopening
     * the app with somebody's half-finished integral still loaded in the ƒ button, which is the same
     * mistake as persisting which pen is in your hand.
     *
     * It carries the measured box with it, so the tap that places the equation already knows how big
     * it is — see [insertEquation].
     */
    private val _pendingEquation = MutableStateFlow<PendingEquation?>(null)
    val pendingEquation: StateFlow<PendingEquation?> = _pendingEquation.asStateFlow()

    /** Takes the formula in hand: the next tap on bare canvas puts it on the page. */
    fun armEquation(pending: PendingEquation) {
        _pendingEquation.value = pending
        selectTool(DrawTool.Equation)
    }

    /**
     * Which ribbon tab is open.
     *
     * Held here rather than in `NotesApp`'s `remember` because it is no longer only the tab strip's
     * business: a stylus button changes the tool, and the tab that shows tools has to come forward
     * with it — see `ui/StylusButtons.kt`. Transient like [_tool], and for the same reason.
     */
    private val _activeTab = MutableStateFlow(RibbonTab.Document)
    val activeTab: StateFlow<RibbonTab> = _activeTab.asStateFlow()

    fun selectRibbonTab(tab: RibbonTab) {
        _activeTab.value = tab
    }

    /**
     * Runs the action a stylus barrel-button press is bound to — `ui/StylusButtons.kt` resolves which
     * one that is and says why the resolution lives there.
     *
     * Stateless, because the pen has already done the counting: a double click arrives as its own
     * keycode rather than as two presses this had to time.
     *
     * **Only a tool action brings the Draw tab forward** — `docs/stylusPlan.md` SB7. A button that
     * silently changes what the pen does, while the ribbon still shows Home, is a tool swap you have
     * to discover by drawing; an undo is already visible on the page, and moving the ribbon for it
     * would be a second change nobody asked for.
     *
     * **A bound Undo is always *canvas* undo** (SB6), even with a caret in a text container. Ctrl+Z is
     * ambiguous on purpose — the focused `EditText` takes it for its own text undo and only the
     * presses it declines reach the canvas — but no view claims a stylus keycode, so this reaches the
     * page from anywhere. That is the right answer for a button on a pen, and an asymmetry with the
     * keyboard worth knowing about.
     */
    fun pressStylusButton(action: StylusAction) {
        when (action) {
            StylusAction.Undo -> undoCanvas()
            StylusAction.Redo -> redoCanvas()
            else -> action.toolFrom(_tool.value)?.let { next ->
                selectTool(next)
                selectRibbonTab(RibbonTab.Draw)
            }
        }
    }

    /** The open page's ink, in draw order. Empty while no page is open. */
    private val _strokes = MutableStateFlow<List<PageStroke>>(emptyList())
    val strokes: StateFlow<List<PageStroke>> = _strokes.asStateFlow()

    /** The page whose final replayed stroke list has landed; streamed prefixes are not ready. */
    private val _inkReadyPageId = MutableStateFlow<String?>(null)
    val inkReadyPageId: StateFlow<String?> = _inkReadyPageId.asStateFlow()

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

    /**
     * Where [pushHistory] puts entries while [asOneAction] is collecting them, or null the rest of
     * the time — which is all of the time except inside one synchronous call.
     *
     * A plain field rather than anything thread-aware on purpose: everything that records history
     * runs on the main thread, and this is only ever set and cleared inside a single stack frame of
     * it — [asOneAction] is its only writer.
     */
    private var historyGroup: MutableList<CanvasHistoryEntry>? = null

    /** An erase resolves native geometry off-thread; history pauses until its action is committed. */
    private val pendingInkEditsByPage = mutableMapOf<String, Int>()

    /** Keeps stroke inserts, whole erases and replayable partial erases in gesture order. */
    private val inkMutations = Mutex()

    /** Wall time made strictly increasing so erase/move replay never has an ambiguous tie. */
    private var lastInkOperationAt = 0L

    /**
     * The [remoteInk] generation the strokes now on screen were built from — IS5.
     *
     * Captured before the open page's ink is read rather than after it, so a pull that commits
     * *during* that read leaves this behind the signal and is absorbed instead of being mistaken for
     * something the canvas already shows.
     */
    private var absorbedInkGeneration = 0L

    /** An absorption that arrived while an ink edit was still resolving, waiting for it to land. */
    private var deferredInkAbsorption = false

    /** The rebuild triggered by pulled ink, so a burst of sync ticks cannot start a second one. */
    private var inkAbsorption: Job? = null

    /**
     * The page currently being opened, so that opening another one stops it.
     *
     * Ink now loads *after* its page is on screen, which means a load can still be running when the
     * next page is opened. Left alone it would finish and publish itself over whatever the user had
     * moved on to — and go on spending eight cores rebuilding a page nobody is looking at.
     */
    private var pageLoad: Job? = null

    /** Cancels an obsolete history read when the user changes pages or picks another version. */
    private var versionHistoryLoad: Job? = null

    /**
     * The page [openPage] was last asked for, set the moment it is asked rather than when its state
     * lands.
     *
     * `uiState.selectedPageId` cannot answer this: it does not become the incoming page until that
     * page's document has been read, and the outgoing page's ink load — cancelled, but cancellation
     * is cooperative — can reach a publication inside that gap and still find its own id there.
     */
    private var openingPageId: String? = null

    /**
     * The top-left of what the canvas is currently showing, in page units.
     *
     * Held as a plain field rather than as state: nothing recomposes on it, and the only reader is an
     * insert that happens to need somewhere to put a new object. Reported by `EditorPane` from a
     * `snapshotFlow` over the scroll, so keeping it up to date costs no recomposition either.
     */
    private var viewportOrigin = InkPoint(0f, 0f)

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
     * Outlines this ViewModel does not manage — an `Outline.Ink` layer, and any kind added later.
     *
     * [persist] rebuilds `PageDoc.outlines` from the objects it tracks, so an outline it did not put
     * there is not merely ignored: it is written out of existence by the next autosave, 400ms after
     * the next keystroke. Nothing produces those variants yet, which is exactly why the loss would be
     * silent when something does — the write succeeds and the page looks fine until the drawing is
     * gone. Carrying them through untouched keeps load → save → load the identity for a document this
     * ViewModel only half understands.
     *
     * Position is not recorded here any more; [documentOrder] holds it for every kind at once.
     */
    private var unmanagedOutlines: List<Outline> = emptyList()

    /**
     * Where each outline sat in the document as loaded, by id — what [persist] sorts back into.
     *
     * **The rebuilt list is grouped by kind, and that is not an order the document ever had.**
     * `persist` concatenates shapes, then equations, then pictures, then tables, then containers,
     * because that is the order the fields happen to be declared in; so a page loaded as
     * `[ink, text, image]` was written back as `[ink, image, text]` and every autosave after the
     * first shuffled the file. It went unnoticed while containers were the only managed kind — the
     * kind-grouped list was then simply *the* list — and each kind added since (shapes, tables,
     * equations, pictures) widened it.
     *
     * Nothing on screen depends on this: the canvas layers by kind with a fixed z-order of its own,
     * so a reordered document draws identically. What it costs is everything that reads the file — a
     * `.vive` export that differs from the one before it, a sync diff over a page nobody edited, and
     * the load → save → load identity [unmanagedOutlines] exists to protect, held for one kind while
     * being broken for the rest.
     *
     * Outlines created since the load are absent here and sort last, keeping the arrival order they
     * already had among themselves — `sortedBy` is stable.
     */
    private var documentOrder: Map<String, Int> = emptyMap()

    /** Signals an edit that autosave should pick up; the payload is irrelevant. */
    private val edits = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private val selectedSection = MutableStateFlow<String?>(null)

    /** Page whose stored body failed to decode. Never written to. */
    private var readOnlyPageId: String? = null

    /**
     * A page to open as soon as the section holding it has listed its pages — CS9.
     *
     * Without it, going to a search result in another section is a race: `selectSection` clears the
     * selection, and the pages flow opens that section's *first* page the moment it arrives, which is
     * not the one that was asked for.
     */
    private var pendingPageId: String? = null

    /** Last measured (viewport, canvas) width in dp, for [zoomToPageWidth]. */
    private var canvasWidths: Pair<Float, Float> = 0f to 0f

    init {
        viewModelScope.launch {
            // Nothing may observe the tree until first-run seeding has finished. A page's row is
            // created before its content is written, so a page opened inside that gap loads the
            // empty document the row was created with — and the next save writes that emptiness
            // over the seeded content. On any later launch this returns immediately.
            //
            // The gate is evaluated once, here, rather than watched: a device that is registered but
            // has not pulled yet simply does not seed on this launch, and by the next one the pull
            // has either filled the tree or proved the account empty. Waiting for the answer instead
            // would leave a tablet with no signal staring at a spinner.
            val maySeed = maySeedStarter?.invoke() ?: true
            if (maySeed) repository.seedIfEmpty()
            // Independent of first paint: SQLite may have a large database to copy, and history is
            // useful precisely because opening a notebook must not wait for backup maintenance.
            databaseBackups?.let { backups ->
                launch {
                    runCatching { backups.createIfDue() }
                        .onFailure { Log.w("DatabaseBackup", "Could not create daily backup", it) }
                }
            }

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
                    // A page asked for by name — a search result in another section (CS9). Honoured
                    // once, on the first list that contains it, and dropped otherwise: a request
                    // that survived a list which did not have the page would fire later, on a
                    // section the user has since chosen for their own reasons.
                    val requested = pendingPageId
                    pendingPageId = null
                    if (requested != null && pages.any { it.id == requested }) {
                        openPage(requested)
                        return@onEach
                    }
                    val current = _uiState.value.selectedPageId
                    if (current == null || pages.none { it.id == current }) {
                        pages.firstOrNull()?.let { openPage(it.id) }
                            ?: run {
                                versionHistoryLoad?.cancel()
                                _versionHistory.value = VersionHistoryState()
                                _uiState.value = _uiState.value.copy(
                                    selectedPageId = null,
                                    title = "",
                                    outlines = emptyList(),
                                )
                            }
                    }
                }
                .launchIn(this)

            // Page metadata already flows into the list, which is why a pulled preview could say
            // "xw" while the open canvas stayed blank. Observe the body independently and rebuild
            // only when the decoded document differs from what the live editors currently hold.
            // A matching local autosave therefore keeps the editor and its caret intact.
            _uiState
                .map { it.selectedPageId }
                .distinctUntilChanged()
                .flatMapLatest { pageId ->
                    if (pageId == null) {
                        emptyFlow()
                    } else {
                        repository.observeDoc(pageId).map { load -> pageId to load }
                    }
                }
                .onEach { (pageId, load) -> acceptStoredDocument(pageId, load) }
                .launchIn(this)

            // And the same for the page's ink, which has no Room flow to observe — the canvas is
            // told when the server writes strokes, erases or lassos into a page rather than when the
            // tables change, because every stroke the user draws changes those tables too.
            remoteInk
                ?.onEach { pages ->
                    val pageId = _uiState.value.selectedPageId ?: return@onEach
                    absorbRemoteInk(pageId, pages[pageId] ?: 0L)
                }
                ?.launchIn(this)

            // Debounced so a burst of keystrokes is one write, not one per character.
            edits
                .debounce(AUTOSAVE_DELAY_MS)
                .onEach { persist() }
                .launchIn(this)
        }
    }

    // --- notebook transfer ---------------------------------------------------------------------

    /** The notebook containing the selected section; used for both its export id and picker name. */
    fun selectedNotebookName(): String? = selectedNotebook()?.notebook?.name

    fun exportCurrentNotebook(destination: Uri) {
        val manager = notebookTransfers
        val notebook = selectedNotebook()?.notebook
        if (manager == null || notebook == null || _notebookTransfer.value.running) return
        viewModelScope.launch {
            _notebookTransfer.value = NotebookTransferState(running = true)
            try {
                persist()
                val result = manager.exportNotebook(notebook.id, destination)
                _notebookTransfer.value = NotebookTransferState(
                    message = "${result.notebookName} was exported as a .vive notebook.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _notebookTransfer.value = NotebookTransferState(
                    error = failure.message ?: "The notebook could not be exported.",
                )
            }
        }
    }

    fun importNotebook(source: Uri) {
        val manager = notebookTransfers
        if (manager == null || _notebookTransfer.value.running) return
        viewModelScope.launch {
            _notebookTransfer.value = NotebookTransferState(running = true)
            try {
                val selectedSectionId = _uiState.value.selectedSectionId
                persist()
                val result = manager.importNotebook(source)
                // An explicit import always opens what it imported. Besides making the result
                // visible, this ensures an authoritative archive document replaces a stale open
                // editor immediately instead of waiting for the user to leave and return.
                result.firstSectionId?.let { importedSectionId ->
                    if (importedSectionId == selectedSectionId) {
                        if (result.restored) {
                            // A restored page can be absent from the list snapshot still in UI
                            // state. Let the Room invalidation publish the authoritative list first.
                            pendingPageId = result.firstPageId
                        } else {
                            result.firstPageId?.let { openPage(it, persistCurrent = false) }
                        }
                    } else {
                        pendingPageId = result.firstPageId
                        // persist() already ran before import. Persisting the outgoing editor again
                        // here could write its stale pre-import document over the archive.
                        selectSection(importedSectionId, persistCurrent = false)
                    }
                }
                _notebookTransfer.value = NotebookTransferState(
                    message = when {
                        result.created -> "${result.notebookName} was imported."
                        result.restored -> "${result.notebookName} was restored from the .vive notebook."
                        else -> "${result.notebookName} was updated from the .vive notebook."
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _notebookTransfer.value = NotebookTransferState(
                    error = failure.message ?: "The notebook could not be imported.",
                )
            }
        }
    }

    fun clearNotebookTransferStatus() {
        if (!_notebookTransfer.value.running) _notebookTransfer.value = NotebookTransferState()
    }

    private fun selectedNotebook(): NotebookWithSections? {
        val sectionId = _uiState.value.selectedSectionId ?: return null
        return _uiState.value.tree.firstOrNull { notebook ->
            notebook.liveSections.any { it.id == sectionId }
        }
    }

    // --- version history ----------------------------------------------------------------------

    /** Opens the current page's lightweight revision list, then previews the newest checkpoint. */
    fun loadVersionHistory() {
        val pageId = _uiState.value.selectedPageId
        versionHistoryLoad?.cancel()
        if (pageId == null) {
            _versionHistory.value = VersionHistoryState()
            return
        }

        _versionHistory.value = VersionHistoryState(pageId = pageId, loading = true)
        versionHistoryLoad = viewModelScope.launch {
            try {
                val revisions = repository.revisionHistory(pageId)
                if (_uiState.value.selectedPageId != pageId) return@launch
                val selected = revisions.firstOrNull()
                _versionHistory.value = VersionHistoryState(
                    pageId = pageId,
                    revisions = revisions,
                    selectedRevision = selected,
                    previewLoading = selected != null,
                )
                if (selected != null) loadVersionPreview(pageId, selected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (_uiState.value.selectedPageId == pageId) {
                    _versionHistory.value = VersionHistoryState(
                        pageId = pageId,
                        error = "Version history could not be loaded.",
                    )
                }
            }
        }
    }

    /** Selects one row without bringing its compressed document through the list query. */
    fun selectVersionRevision(revisionId: String) {
        val state = _versionHistory.value
        val pageId = state.pageId ?: return
        val revision = state.revisions.firstOrNull { it.id == revisionId } ?: return
        if (_uiState.value.selectedPageId != pageId || state.restoring) return

        versionHistoryLoad?.cancel()
        _versionHistory.value = state.copy(
            selectedRevision = revision,
            preview = null,
            previewLoading = true,
            error = null,
            message = null,
        )
        versionHistoryLoad = viewModelScope.launch { loadVersionPreview(pageId, revision) }
    }

    /** Restores the complete page and keeps the page it replaces as a reversible checkpoint. */
    fun restoreSelectedVersion() {
        val history = _versionHistory.value
        val pageId = history.pageId ?: return
        val revision = history.selectedRevision ?: return
        if (history.preview == null || history.restoring || _uiState.value.selectedPageId != pageId) return

        versionHistoryLoad?.cancel()
        _versionHistory.value = history.copy(restoring = true, error = null, message = null)
        versionHistoryLoad = viewModelScope.launch {
            try {
                // The revision API checkpoints what is in SQLite. Land the live editors there first
                // so even changes inside the autosave debounce window are part of that safety copy.
                persist()
                if (_uiState.value.selectedPageId != pageId) return@launch

                val (restored, restoredStrokes) = inkMutations.withLock {
                    val result = repository.restoreRevision(pageId, revision.id)
                    result to if (result is PageRevisionLoad.Loaded) loadInk(pageId) else emptyList()
                }
                when (restored) {
                    is PageRevisionLoad.Loaded -> {
                        if (_uiState.value.selectedPageId != pageId) return@launch
                        pageLoad?.cancel()
                        openingPageId = pageId
                        readOnlyPageId = null
                        canvasHistoryByPage.remove(pageId)
                        _strokes.value = emptyList()
                        _inkReadyPageId.value = null
                        showDocument(
                            pageId = pageId,
                            page = _uiState.value.pages.firstOrNull { it.id == pageId },
                            doc = restored.doc,
                        )
                        publishInk(pageId, restoredStrokes)
                        _inkReadyPageId.value = pageId

                        val revisions = repository.revisionHistory(pageId)
                        val selected = revisions.firstOrNull { it.id == revision.id }
                        _versionHistory.value = VersionHistoryState(
                            pageId = pageId,
                            revisions = revisions,
                            selectedRevision = selected,
                            preview = restored.doc,
                            message = "Version restored. The version it replaced is still in history.",
                        )
                    }
                    PageRevisionLoad.NotFound -> {
                        _versionHistory.value = history.copy(
                            restoring = false,
                            error = "That version no longer exists.",
                        )
                    }
                    is PageRevisionLoad.Unreadable -> {
                        _versionHistory.value = history.copy(
                            restoring = false,
                            error = "That version is damaged and cannot be restored.",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (_uiState.value.selectedPageId == pageId) {
                    _versionHistory.value = _versionHistory.value.copy(
                        restoring = false,
                        error = "The version could not be restored.",
                    )
                }
            }
        }
    }

    private suspend fun loadVersionPreview(pageId: String, revision: PageRevisionSummary) {
        val loaded = try {
            repository.loadRevision(pageId, revision.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
        val current = _versionHistory.value
        if (
            _uiState.value.selectedPageId != pageId ||
            current.pageId != pageId ||
            current.selectedRevision?.id != revision.id
        ) return

        _versionHistory.value = when (loaded) {
            is PageRevisionLoad.Loaded -> current.copy(
                preview = loaded.doc,
                previewLoading = false,
                error = null,
            )
            PageRevisionLoad.NotFound -> current.copy(
                previewLoading = false,
                error = "That version no longer exists.",
            )
            is PageRevisionLoad.Unreadable -> current.copy(
                previewLoading = false,
                error = "That version is damaged and cannot be previewed.",
            )
            null -> current.copy(
                previewLoading = false,
                error = "That version could not be loaded.",
            )
        }
    }

    // --- navigation ----------------------------------------------------------------------------

    fun selectSection(sectionId: String) = selectSection(sectionId, persistCurrent = true)

    private fun selectSection(sectionId: String, persistCurrent: Boolean) {
        if (selectedSection.value == sectionId) return
        // Capture before clearing selectedPageId below. Launching `persist()` and letting the
        // coroutine read uiState later loses the outgoing page when this runs on a queued dispatcher
        // (and can do the same on a busy device). The document is immutable, so the Room write can
        // safely finish after the visible navigation has moved on.
        val outgoing = if (persistCurrent) currentPageSave() else null
        if (outgoing != null) {
            viewModelScope.launch { repository.saveDoc(outgoing.pageId, outgoing.doc) }
        }
        // Whatever page was loading belongs to the section being left. The pages flow will open one
        // from the new section in a moment; until then there is nothing this should still be doing.
        pageLoad?.cancel()
        versionHistoryLoad?.cancel()
        _versionHistory.value = VersionHistoryState()
        openingPageId = null
        selectedSection.value = sectionId
        _uiState.value = _uiState.value.copy(selectedSectionId = sectionId, selectedPageId = null)
        _strokes.value = emptyList()
        _inkReadyPageId.value = null
        publishCanvasUndoState(null)
        _compactPane.value = CompactPane.Pages
    }

    /**
     * Opens a page, and does not wait for its ink to open it.
     *
     * The page, its text and its objects are published first and the ink arrives afterwards, because
     * the two are not the same size of job: reading the document is a couple of milliseconds, while
     * rebuilding a densely drawn page's strokes is seconds — 3.0 s of decoding for the 9,553 strokes
     * on the page this was measured against, all of it in front of the state that puts the page on
     * screen. So a page that was ready to show sat behind a page that was not.
     *
     * Held as one cancellable job rather than left to run: opening a second page while the first is
     * still loading must not let the first finish and publish itself over the second.
     */
    fun openPage(pageId: String) = openPage(pageId, persistCurrent = true)

    private fun openPage(pageId: String, persistCurrent: Boolean) {
        if (_uiState.value.selectedPageId != pageId) {
            // Disable actions for the outgoing page during the short load window before the new id
            // is published. Otherwise a fast Restore tap could cancel the page switch and act on
            // the page the list is visibly leaving.
            versionHistoryLoad?.cancel()
            _versionHistory.value = VersionHistoryState(pageId = pageId, loading = true)
        }
        pageLoad?.cancel()
        openingPageId = pageId
        // The outgoing page's ink goes now rather than when the incoming page's arrives. It is the
        // one piece of the old page that would otherwise stay on screen, drawn over a page it does
        // not belong to, for exactly as long as the load that this change stopped waiting for.
        _strokes.value = emptyList()
        _inkReadyPageId.value = null
        // Read before the load below, not after it — see [absorbedInkGeneration].
        inkAbsorption?.cancel()
        deferredInkAbsorption = false
        absorbedInkGeneration = remoteInkGeneration(pageId)
        pageLoad = viewModelScope.launch {
            // The outgoing page's text is written before anything can replace it, and a page switch
            // arriving mid-write cannot tear that write in half.
            if (persistCurrent) withContext(NonCancellable) { persist() }
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

            showDocument(
                pageId = pageId,
                page = page,
                doc = doc,
                contentError = if (readOnlyPageId == pageId) {
                    "This page could not be read, so editing is disabled to protect its contents."
                } else {
                    null
                },
            )

            // The page is on screen from here; what follows fills it in.
            //
            // Loading joins the same serialization lane as edits. Otherwise a fast page switch can
            // read an operation between its immediate canvas update and its database tombstone.
            val ink = inkMutations.withLock {
                loadInk(pageId) { partial -> publishInk(pageId, partial) }
            }
            publishInk(pageId, ink)
            if (openingPageId == pageId) _inkReadyPageId.value = pageId
        }
    }

    /** Publishes one decoded document without persisting the document currently on screen. */
    private fun showDocument(
        pageId: String,
        page: PageEntity?,
        doc: PageDoc,
        contentError: String? = null,
        revealEditor: Boolean = true,
    ) {
        val loaded = doc.outlines.filterIsInstance<Outline.Text>()
            // A page with nothing on it still needs somewhere to put the caret, placed clear of the
            // title rather than under it — outline coordinates start at the page's corner.
            .ifEmpty {
                listOf(
                    Outline.Text.empty(
                        y = if (doc.style.hideTitle) 0f else PageStyle.TITLE_BAND_DP,
                    ),
                )
            }
        val tables = doc.outlines.filterIsInstance<Outline.Table>()

        blocksById.clear()
        loaded.forEach { blocksById[it.id] = it.blocks }
        // Cells join the same map, per TA2 — one content path for containers and cells alike.
        tables.forEach { table ->
            val cells = table.contentCellIds().toSet()
            table.rows.forEach { row ->
                row.cells.forEach { cell ->
                    if (cell.id in cells) blocksById[cell.id] = cell.blocks
                }
            }
        }
        // Taken from the document rather than from [loaded], which may contain a substitute empty
        // text box. Carrying unknown outline kinds through preserves load -> save -> load identity.
        unmanagedOutlines = doc.outlines.filterNot {
            it is Outline.Text || it is Outline.Shape || it is Outline.Table ||
                it is Outline.Equation || it is Outline.Image
        }
        // Every kind, not just the unmanaged ones — see [documentOrder] for why the rebuilt list
        // cannot be trusted to come back in the order it went out in.
        documentOrder = doc.outlines.withIndex().associate { (index, outline) -> outline.id to index }
        _uiState.value = _uiState.value.copy(
            selectedPageId = pageId,
            title = page?.title.orEmpty(),
            createdAt = page?.createdAt ?: System.currentTimeMillis(),
            outlines = loaded.map { OutlineBox(it.id, it.x, it.y, it.width, it.minHeight) },
            shapes = doc.outlines.filterIsInstance<Outline.Shape>(),
            tables = tables,
            equations = doc.outlines.filterIsInstance<Outline.Equation>(),
            images = doc.outlines.filterIsInstance<Outline.Image>(),
            pageStyle = doc.style,
            pageRevision = _uiState.value.pageRevision + 1,
            contentError = contentError,
        )
        publishCanvasUndoState(pageId)
        _selection.value = SelectionState()
        if (revealEditor) _compactPane.value = CompactPane.Editor
    }

    /**
     * Makes a body replaced underneath the editor authoritative on the canvas as well as in Room.
     *
     * Hierarchy sync writes `page_content` directly under trigger suppression. The pages observer
     * sees the accompanying preview update, but before this observer the editors kept their older
     * blocks and the next blur/navigation save wrote those stale blocks back as a fresh mutation.
     */
    private fun acceptStoredDocument(pageId: String, load: PageLoad) {
        val state = _uiState.value
        if (state.selectedPageId != pageId || openingPageId != pageId) return

        when (load) {
            is PageLoad.Loaded -> {
                val visible = currentPageSave()?.takeIf { it.pageId == pageId }?.doc
                if (visible == load.doc && readOnlyPageId != pageId) return

                readOnlyPageId = null
                showDocument(
                    pageId = pageId,
                    page = state.pages.firstOrNull { it.id == pageId },
                    doc = load.doc,
                    // A background sync must not pull a compact phone/tablet out of its page list.
                    revealEditor = false,
                )
            }
            is PageLoad.Unreadable -> {
                // Preserve the stored bytes and block every save, just as an unreadable initial load
                // does. Keeping the last healthy canvas visible is safer than replacing it with a
                // blank placeholder that looks editable.
                readOnlyPageId = pageId
                _uiState.value = state.copy(
                    contentError =
                        "This page could not be read, so editing is disabled to protect its contents.",
                )
            }
        }
    }

    /**
     * Shows ink, unless the page it belongs to has since been closed.
     *
     * Cancellation alone does not cover this. It is cooperative, so a load told to stop can still
     * reach its next publication before it notices, and this is the check that keeps one page's
     * strokes off another's canvas even then.
     */
    private fun publishInk(pageId: String, strokes: List<PageStroke>) {
        if (openingPageId == pageId) _strokes.value = strokes
    }

    /** The generation of remote ink [pageId] has received, or zero on a device with no server. */
    private fun remoteInkGeneration(pageId: String): Long = remoteInk?.value?.get(pageId) ?: 0L

    /**
     * Puts ink the server wrote into the open page onto the open page — `memory/inkSyncPlan.md` IS5.
     *
     * The ink twin of [acceptStoredDocument], and needed for the same reason: sync writes Room
     * directly, and until this existed a canvas that was already open kept the strokes it was opened
     * with. Two tablets drawing on one page each showed only their own until somebody navigated away
     * and back.
     *
     * **A rebuild, not a delta.** Erases and lasso moves replay in `(createdAt, id)` order and do not
     * commute — a pulled operation can sort *below* one already applied, which offline drawing makes
     * ordinary rather than exotic — and a move's clamp measures the whole selection, so it cannot be
     * replayed against the new strokes alone. Reading the page back is the only answer that is right
     * for every arrival, and it re-seeds the operation clock on the way through [loadInk].
     *
     * Three things make that safe to do underneath somebody's hand:
     * - it joins [inkMutations], the lane page opening and every edit already share;
     * - it stands down while an ink edit is still resolving its geometry off-thread, exactly as undo
     *   does, and is retried by [changePendingInkEdits] when that edit lands;
     * - it re-reads rather than publishes if the canvas moved while Room was being read, because a
     *   stroke finished mid-rebuild is on the page and not yet in the rows the rebuild saw. Its write
     *   is already queued behind the lock, so the next read includes it. After
     *   [INK_ABSORB_ATTEMPTS] it gives up and leaves [absorbedInkGeneration] where it was, so the
     *   next arrival tries again — a page being drawn on continuously absorbs when the pen pauses,
     *   or when it is next opened.
     *
     * What it deliberately does not do is rebase the page's undo ring, whose entries are whole-list
     * snapshots taken before the arrival. An undo immediately after absorbing therefore republishes a
     * list without the pulled strokes: nothing is lost — Room is untouched, and the next absorption
     * or page open shows them again — where rewriting every snapshot on the ring is a large change
     * for a case that heals itself. [acceptStoredDocument] makes the same trade for text.
     */
    private fun absorbRemoteInk(pageId: String, generation: Long) {
        if (generation == absorbedInkGeneration) return
        if (openingPageId != pageId || _uiState.value.selectedPageId != pageId) return
        if ((pendingInkEditsByPage[pageId] ?: 0) > 0) {
            deferredInkAbsorption = true
            return
        }
        deferredInkAbsorption = false
        inkAbsorption?.cancel()
        inkAbsorption = viewModelScope.launch {
            repeat(INK_ABSORB_ATTEMPTS) {
                // Before the read, so a pull committing during it is absorbed by the next signal
                // rather than counted as already shown.
                val seen = remoteInkGeneration(pageId)
                val live = _strokes.value
                val rebuilt = inkMutations.withLock { loadInk(pageId) }
                if (openingPageId != pageId) return@launch
                if ((pendingInkEditsByPage[pageId] ?: 0) > 0) {
                    deferredInkAbsorption = true
                    return@launch
                }
                if (_strokes.value !== live) return@repeat
                _strokes.value = withContext(inkDispatcher) { rebuilt.keepingProjectionsOf(live) }
                absorbedInkGeneration = seen
                return@launch
            }
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

    // --- content search ---------------------------------------------------------------------------
    //
    // `docs/searchPlan.md`. The panel owns the query, this owns everything the query needs: the
    // notebook to search (CS2), the open page's live text (CS8), and where a result leads (CS9).

    private val searchIndex = ContentSearchIndex(repository)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * The current query's results — CS11.
     *
     * `transformLatest` rather than a `debounce` operator, because the wait and the work belong to
     * the same cancellable block: a keystroke arriving mid-search abandons that search where an
     * operator chain would let it finish and race the next one to the state. The previous results
     * stay on screen while a new query is being typed, which is what makes the list feel like it is
     * narrowing rather than blinking.
     */
    val contentSearch: StateFlow<ContentSearchState> = _searchQuery
        .transformLatest { query ->
            if (query.isBlank()) {
                lastResults = null
                emit(ContentSearchState())
                return@transformLatest
            }
            // **Before the wait, not after it.** The panel's field renders from this state, so a
            // keystroke that only reached the UI 180ms later would be a text field that fights the
            // keyboard. The previous query's results ride along until the new ones land, which is
            // what keeps the list from blinking empty between letters.
            emit(ContentSearchState(query = query, running = true, results = lastResults))
            delay(SEARCH_DEBOUNCE_MS)

            // **The picture version is collected here rather than combined with the query upstream.**
            // A picture finishing is a reason to run the query again (`memory/imageOcrPlan.md` IO6),
            // and `combine(_searchQuery, version)` is the obvious way to say so — but `combine`
            // conflates, so three keystrokes in a row became one emission and the field stopped
            // reporting what was typed. Collecting inside the block leaves the keystroke path
            // exactly as it was and adds the re-run underneath it; `transformLatest` cancels this
            // collector on the next keystroke, so a superseded query stops re-running too.
            val imageVersions = imageText?.version ?: MutableStateFlow(0L)
            val inkVersions = inkText?.version ?: MutableStateFlow(0L)
            val versions = combine(
                imageVersions,
                inkVersions,
                repository.observeInkTextStamps(),
            ) { _, _, _ -> Unit }
            versions.collect {
                // Read on the main thread, where the block map is written, and matched off it.
                val live = liveContentUnits()
                val livePictures = liveImagePlacements()
                val notebookId = notebookIdOfSelectedSection()
                val outcome = if (notebookId == null) {
                    ContentSearchOutcome(ContentSearchResults(query), emptySet())
                } else {
                    withContext(Dispatchers.Default) {
                        searchIndex.search(
                            notebookId = notebookId,
                            query = query,
                            livePageId = _uiState.value.selectedPageId,
                            liveUnits = live,
                            liveImages = livePictures,
                            liveInkLayout = inkPageLayout(_uiState.value.tables),
                        )
                    }
                }
                lastResults = outcome.results
                emit(ContentSearchState(query = query, results = outcome.results))
                // After the results, never before them: reading pictures is slow and a search must
                // not wait on it. The index has just decoded every page, so this list is free.
                imageText?.request(outcome.pictures)
                inkText?.request(outcome.inkPages)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContentSearchState())

    /** How far reading this notebook's pictures has got, for the Content and Integrated AI panes. */
    val imageTextProgress: StateFlow<ImageTextProgress> =
        imageText?.progress ?: MutableStateFlow(ImageTextProgress(enabled = false))

    /**
     * How many pictures on this device currently hold a reading.
     *
     * Counted from the indexer's version rather than kept in step by hand, and only while something
     * is collecting — which is while the Integrated AI pane is open. `mapLatest` collapses the burst
     * of bumps a pass produces into one `COUNT`.
     */
    val picturesRead: StateFlow<Int> = (imageText?.version ?: MutableStateFlow(0L))
        .mapLatest { imageText?.readCount() ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setImageTextEnabled(enabled: Boolean) {
        viewModelScope.launch { imageText?.setEnabled(enabled) }
    }

    /** Throws away every reading; the next query reads them again. Pictures are untouched. */
    fun rebuildImageText() {
        viewModelScope.launch { imageText?.clear() }
    }

    val inkTextProgress: StateFlow<InkTextProgress> =
        inkText?.progress ?: MutableStateFlow(InkTextProgress(enabled = false))

    val pagesWithHandwritingText: StateFlow<Int> = combine(
        inkText?.version ?: MutableStateFlow(0L),
        repository.observeInkTextStamps(),
    ) { _, _ -> inkText?.readCount() ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setInkTextEnabled(enabled: Boolean) {
        viewModelScope.launch { inkText?.setEnabled(enabled) }
    }

    fun rebuildInkText() {
        viewModelScope.launch { inkText?.clear() }
    }

    /**
     * The last completed search, shown while the next one is being typed.
     *
     * Held outside the flow because `transformLatest` throws its own previous emissions away with the
     * collector it cancelled, and "what was on screen a moment ago" has to survive that.
     */
    private var lastResults: ContentSearchResults? = null

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * The open page's searchable text, taken from the editors rather than from storage — CS8.
     *
     * Autosave is 400ms behind the keyboard, so the stored copy of this page is the one thing in the
     * notebook that can be out of date. Everything else comes from [ContentSearchIndex].
     */
    private fun liveContentUnits(): List<ContentUnit> {
        val state = _uiState.value
        val pageId = state.selectedPageId ?: return emptyList()
        val sectionId = state.selectedSectionId ?: return emptyList()
        val units = mutableListOf<ContentUnit>()
        titleUnit(pageId, sectionId, state.title)?.let(units::add)
        state.outlines.forEach { box ->
            // A missing entry is "content not known" here exactly as it is in [persist]: a container
            // whose editor has not reported yet contributes nothing rather than an empty box.
            blocksById[box.id]?.let { blocks ->
                units += blockUnits(pageId, sectionId, ContentKind.Text, box.id, blocks = blocks)
            }
        }
        state.tables.forEach { table ->
            table.contentCellIds().forEach { cellId ->
                blocksById[cellId]?.let { blocks ->
                    units += blockUnits(pageId, sectionId, ContentKind.Cell, cellId, table.id, blocks)
                }
            }
        }
        return units
    }

    /**
     * The open page's pictures, for the same reason [liveContentUnits] exists.
     *
     * A picture pasted a moment ago is not in the stored document yet, and the text it holds may
     * well have been read already — the same screenshot on an earlier page reads once for both
     * (IO2). Deduplicated here as it is there: one placement per picture.
     */
    private fun liveImagePlacements(): List<ImagePlacement> {
        val state = _uiState.value
        val pageId = state.selectedPageId ?: return emptyList()
        val sectionId = state.selectedSectionId ?: return emptyList()
        return state.images.distinctBy { it.attachmentId }.map { image ->
            ImagePlacement(pageId, sectionId, image.id, image.attachmentId)
        }
    }

    /** Which notebook the open section belongs to — the scope of a search (CS2). */
    private fun notebookIdOfSelectedSection(): String? {
        val sectionId = _uiState.value.selectedSectionId ?: return null
        return _uiState.value.tree
            .firstOrNull { notebook -> notebook.liveSections.any { it.id == sectionId } }
            ?.notebook
            ?.id
    }

    private val _reveal = MutableStateFlow<ContentReveal?>(null)

    /** The hit the canvas has been asked to show, until it has shown it. */
    val reveal: StateFlow<ContentReveal?> = _reveal.asStateFlow()

    /**
     * Goes to a hit: its section, its page, and then the box that holds it — CS9.
     *
     * The reveal is set *first* and outlives the page load on purpose. It is a standing request that
     * `EditorPane` picks up once the page it names is the open one and the container it names has
     * been laid out, which is several frames after this returns.
     */
    fun openSearchHit(hit: ContentHit) {
        val unit = hit.unit
        _reveal.value = ContentReveal(
            pageId = unit.pageId,
            kind = unit.kind,
            boxId = unit.boxId,
            tableId = unit.tableId,
            start = hit.editorStart,
            end = hit.editorEnd,
            inkStrokeIds = unit.inkStrokeIds,
            inkBounds = unit.inkBounds,
        )
        val state = _uiState.value
        when {
            state.selectedPageId == unit.pageId -> _compactPane.value = CompactPane.Editor
            state.selectedSectionId == unit.sectionId -> openPage(unit.pageId)
            else -> {
                selectSection(unit.sectionId)
                // Set after the switch, not before: `selectSection` hands the choice of page to the
                // pages flow, which cannot have emitted yet — nothing else runs on this thread
                // between here and a database round trip. Clearing it in `selectSection` instead
                // would delete the request this very line is making.
                pendingPageId = unit.pageId
            }
        }
    }

    /** Called by the canvas once it has scrolled to a hit and put the caret on it. */
    fun onRevealHandled() {
        _reveal.value = null
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
        val after = change(before).onPage()
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

    /**
     * Rebuilds one page from its live stroke rows and active replay operations, off the main thread
     * and across every core, reporting each part as it becomes showable.
     *
     * Decoding is the expensive half and is perfectly parallel — a stroke is rebuilt from its own row
     * and nothing else — so it is split into fixed-size chunks and awaited **in row order**, which
     * keeps what is published a prefix of the page in draw order. Later strokes sit on top of
     * earlier ones, so an out-of-order prefix would show ink stacked wrongly and then rearrange
     * itself.
     *
     * [onPartial] is called only when the page's stored operations allow a prefix to be shown at all;
     * see [streamable]. It is always safe to ignore it — the return value is the whole page.
     */
    private suspend fun loadInk(
        pageId: String,
        onPartial: (List<PageStroke>) -> Unit = {},
    ): List<PageStroke> {
        val loaded = inkPageLoader.load(pageId, onPartial)
        lastInkOperationAt = maxOf(
            lastInkOperationAt,
            loaded.latestOperationAt,
        )
        // Opening a page is where the replay that proves a stroke has nothing left already ran, so it
        // is where rubbed-out ink is collected — including everything erased before this existed, and
        // everything an erase pulled from another device finished off. See
        // [NotesRepository.collectErasedAwayStrokes]; it is a tombstone, so a page only pays once.
        repository.collectErasedAwayStrokes(loaded.erasedAway)
        return loaded.strokes
    }

    fun selectTool(tool: DrawTool) {
        // Text is the one tool that wants the caret and the IME; every other one — including
        // nothing at all — takes the page's gestures and should put them away first.
        if (tool != DrawTool.Text) _commands.tryEmit(FormatCommand.DeactivateTextInput)
        // And the same for what is selected *on* the page — `docs/diagram.md`, Prime Object Class:
        // "Selecting any other tool removes selection of object." Only on an actual change, which is
        // what "any other tool" says: re-tapping the tool already in hand has not selected another
        // one, and taking the selection away there would make the armed button feel like a reset.
        if (tool != _tool.value) _commands.tryEmit(FormatCommand.ClearCanvasSelection)
        // Picking up anything else drops the formula that was waiting to be placed. It is content
        // held in the hand, not a setting kept on a shelf, so it has no meaning once the hand is
        // holding a pen — and leaving it would place a formula the user composed minutes ago the
        // next time they came back to ƒ.
        if (tool != DrawTool.Equation) _pendingEquation.value = null
        _tool.value = tool
    }

    fun setDrawWithFinger(enabled: Boolean) {
        viewModelScope.launch { penSettingsStore.setDrawWithFinger(enabled) }
    }

    fun setStylusButtons(map: StylusButtonMap) {
        viewModelScope.launch { penSettingsStore.setStylusButtons(map) }
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
                colorFollowsTheme = entity.colorFollowsTheme,
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
            // Carried from the pane onto the shape, so the border keeps following the canvas after
            // it is drawn rather than freezing at whatever the page was at the time.
            borderFollowsTheme = shape.colorFollowsTheme,
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
            // Picking a colour is what makes it deliberate, so the border stops following the
            // canvas — the same thing `PageStroke.recolor` does to a stroke.
            shapes.map {
                if (it.id in shapeIds) {
                    it.copy(borderArgb = argb, borderFollowsTheme = false)
                } else {
                    it
                }
            }
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
        // Held to the page's origin corner here, at the one door — see [onPage].
        val after = change(before).onPage()
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

    // --- images ---------------------------------------------------------------------------------

    /**
     * Imports a picked picture and puts it on the page — feature E6.
     *
     * The import is the slow half (read, downscale, re-encode, hash, write) and is entirely inside
     * [AttachmentStore], off the main thread. What lands here is a few numbers, so the page edit
     * itself is the same cheap document change every other object makes.
     *
     * **Placed where the user is looking**, not at the page's origin: the ribbon button has no tap to
     * take a position from, and a picture inserted onto a corner of a page scrolled somewhere else is
     * a picture the user has to go and find. [viewportOrigin] is what the canvas last reported.
     *
     * Sized to [Outline.Image.DEFAULT_WIDTH] with the aspect ratio the file actually has, so a
     * portrait photograph arrives portrait.
     */
    fun insertImage(uri: Uri) {
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        viewModelScope.launch {
            val imported = attachments.import(uri) ?: return@launch
            // Re-read: importing suspends, and the page may have been closed or changed underneath.
            if (_uiState.value.selectedPageId != pageId || readOnlyPageId == pageId) {
                attachments.release(imported.id)
                return@launch
            }
            val aspect = if (imported.pixelWidth > 0) {
                imported.pixelHeight.toFloat() / imported.pixelWidth
            } else {
                1f
            }
            val width = Outline.Image.DEFAULT_WIDTH
            // Clear of the title band when the page is scrolled to its top, exactly as a seeded or
            // newly opened text container is: outline coordinates start at the page's own corner, so
            // something placed at the viewport origin lands *on* the header rather than below it.
            // Scrolled anywhere else the viewport wins, which is what the max is for.
            val titleFloor = if (_uiState.value.pageStyle.hideTitle) 0f else PageStyle.TITLE_BAND_DP
            val created = Outline.Image(
                id = newId(),
                x = PageBounds.clampX(viewportOrigin.x + INSERT_MARGIN),
                y = PageBounds.clampY(maxOf(viewportOrigin.y + INSERT_MARGIN, titleFloor)),
                width = width,
                height = (width * aspect).coerceAtLeast(Outline.Image.MIN_SIZE),
                attachmentId = imported.id,
            )
            editImages { it + created }
            // Nothing armed, so the next tap reaches the picture and selects it rather than being
            // taken by a tool. Not auto-selected: the insert happens off a ribbon button, not a
            // canvas gesture, so there is no layer in the call path holding the selection to set.
            _tool.value = DrawTool.None
        }
    }

    /** Where the canvas is currently looking, in page units. Reported by `EditorPane` as it scrolls. */
    fun reportViewport(x: Float, y: Float) {
        viewportOrigin = InkPoint(x, y)
    }

    fun moveImages(imageIds: Set<String>, dx: Float, dy: Float) {
        if (imageIds.isEmpty() || (dx == 0f && dy == 0f)) return
        editImages { images ->
            images.map { if (it.id in imageIds) it.translated(dx, dy) else it }
        }
    }

    /** The corner handles, and the lasso's half of a resize — once per gesture, per [resizeShapes]. */
    fun resizeImages(
        imageIds: Set<String>,
        anchorX: Float,
        anchorY: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        if (imageIds.isEmpty()) return
        editImages { images ->
            images.map {
                if (it.id in imageIds) it.scaledAbout(anchorX, anchorY, scaleX, scaleY) else it
            }
        }
    }

    /**
     * Removes pictures from the page — the toolkit's Delete.
     *
     * **The file stays, and for now it stays for good.** Undo restores this list, and a restored
     * frame pointing at bytes that had been swept would be a hole in the page no further undo could
     * fill — so nothing is released here. Deciding the moment a delete becomes permanent is a real
     * question (the history ring? closing the page? a sweep at launch?) and getting it wrong destroys
     * a picture that is still referenced, so **v1 leaks disk rather than risk that**:
     * `AttachmentStore.release` exists and is correct, and nothing calls it yet. `refCount` is
     * maintained so the sweep can be written without a migration.
     */
    fun deleteImages(imageIds: Set<String>) {
        if (imageIds.isEmpty()) return
        editImages { images -> images.filterNot { it.id in imageIds } }
    }

    private inline fun editImages(change: (List<Outline.Image>) -> List<Outline.Image>) {
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val state = _uiState.value
        val before = state.images
        // Held to the page's origin corner at the one door, exactly as every other kind is.
        val after = change(before).onPage()
        if (after == before) return

        _uiState.value = state.copy(images = after)
        pushHistory(pageId, CanvasHistoryEntry.Images(before = before, after = after))
        edits.tryEmit(Unit)
    }

    // --- equations ------------------------------------------------------------------------------

    /**
     * Puts a formula where the user tapped.
     *
     * [insertShape]'s bargain, kind for kind: a document edit, one entry on the shared history ring,
     * the tool put down afterwards and the new object handed back so the page can select it. The
     * handles are the point of it being an object and they are unreachable while the tool that makes
     * new ones is still in hand.
     *
     * **The size arrives with the formula rather than being discovered later.** The panel has already
     * rendered it once to check it parses, so its measured box comes along for free — which is what
     * spares this the feedback loop a table needs, where only the canvas knows how tall the thing
     * really is.
     */
    fun insertEquation(
        latex: String,
        x: Float,
        y: Float,
        width: Float = Outline.Equation.DEFAULT_WIDTH,
        height: Float = Outline.Equation.DEFAULT_HEIGHT,
    ): String? {
        val pageId = _uiState.value.selectedPageId ?: return null
        if (readOnlyPageId == pageId) return null
        if (latex.isBlank()) return null

        val created = Outline.Equation(
            id = newId(),
            x = x.coerceAtLeast(0f),
            y = y.coerceAtLeast(0f),
            width = width.coerceAtLeast(Outline.Equation.MIN_SIZE),
            height = height.coerceAtLeast(Outline.Equation.MIN_SIZE),
            latex = latex,
        )

        editEquations { it + created }
        _tool.value = DrawTool.None
        _pendingEquation.value = null
        return created.id
    }

    /** One gesture, one edit — [moveShapes]' rule, which is undo's rather than arithmetic's. */
    fun moveEquations(equationIds: Set<String>, dx: Float, dy: Float) {
        if (equationIds.isEmpty() || (dx == 0f && dy == 0f)) return
        editEquations { equations ->
            equations.map { if (it.id in equationIds) it.translated(dx, dy) else it }
        }
    }

    /**
     * Scales about the corner opposite the one being dragged — AD7's four-corner resize.
     *
     * **Absolute, and applied once on the lift.** The same contract [resizeShapes] documents, and the
     * same failure if it is called per frame: each frame's scale is measured from the geometry the
     * drag *started* with, so applying them in sequence multiplies them together.
     */
    fun resizeEquations(
        equationIds: Set<String>,
        anchorX: Float,
        anchorY: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        if (equationIds.isEmpty() || (scaleX == 1f && scaleY == 1f)) return
        editEquations { equations ->
            equations.map {
                if (it.id in equationIds) it.scaledAbout(anchorX, anchorY, scaleX, scaleY) else it
            }
        }
    }

    /** Rewrites the formula in place, keeping the box the user sized. */
    fun setEquationLatex(equationIds: Set<String>, latex: String) {
        if (equationIds.isEmpty() || latex.isBlank()) return
        editEquations { equations ->
            equations.map { if (it.id in equationIds) it.copy(latex = latex) else it }
        }
    }

    /** Colours the formula, and with it drops the automatic that followed the canvas. */
    fun recolorEquations(equationIds: Set<String>, argb: Int) {
        if (equationIds.isEmpty()) return
        editEquations { equations ->
            equations.map { if (it.id in equationIds) it.copy(colorArgb = argb) else it }
        }
    }

    fun deleteEquations(equationIds: Set<String>) {
        if (equationIds.isEmpty()) return
        editEquations { equations -> equations.filterNot { it.id in equationIds } }
    }

    /** The one door every equation edit goes through — see [editShapes] for why there is exactly one. */
    private inline fun editEquations(
        coalesceKey: String? = null,
        change: (List<Outline.Equation>) -> List<Outline.Equation>,
    ) {
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val state = _uiState.value
        val before = state.equations
        val after = change(before).onPage()
        if (after == before) return

        _uiState.value = state.copy(equations = after)
        pushHistory(
            pageId,
            CanvasHistoryEntry.Equations(
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
            borderFollowsTheme = settings.colorFollowsTheme,
            borderWidth = settings.borderWidth.toFloat(),
            fillArgb = settings.fillArgb,
            // A ruling for the stylus rather than a grid of text fields — `docs/tablePlan.md` TA15,
            // and a setting rather than a second tool since it moved into the Table pane.
            inkOnly = settings.inkOnly,
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
            tables.map {
                if (it.id in tableIds) {
                    it.copy(borderArgb = argb, borderFollowsTheme = false)
                } else {
                    it
                }
            }
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
        val after = change(before).onPage()
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

    // --- insert space ---------------------------------------------------------------------------

    /**
     * Insert Space — feature E2, and `com.vivenotes.model.PageSpace` for what the gesture means.
     *
     * Everything whose near edge is past [cut]'s line moves by its amount; everything else stays. That
     * is one translation applied to every kind on the page, which makes this the widest single action
     * in the app: it is the only one that can touch ink, text, shapes, pictures, tables and equations
     * at once, without anything having been selected.
     *
     * **The limit is computed across all six kinds before any of them moves.** A closing drag can only
     * take back as much space as the *nearest* thing to the line has, and asking each kind to stop
     * itself would let the ink slide up 40 dp while the text beside it stopped at 10 — which is the
     * one thing this gesture must never do, since its whole promise is that what moves, moves
     * together. So the smallest near edge decides for all of them, and [SpaceCut.limitedTo] applies it
     * once.
     *
     * Each kind then commits through the door it already has, which is what AD7's second consequence
     * asks for: the same operation, applied by each kind to its own representation. What is different
     * here is that they are wrapped in [asOneAction] — see [CanvasHistoryEntry.Composite] for why this
     * gesture, unlike a paste, cannot be left as one entry per kind.
     */
    fun insertSpace(cut: SpaceCut) {
        if (cut.isEmpty) return
        val pageId = _uiState.value.selectedPageId ?: return
        if (readOnlyPageId == pageId) return
        val state = _uiState.value

        // **A stored row — or a group — is the atom, not a projection.** An erase splits one stroke
        // into several projections that share a row id, and a group is several strokes the user
        // deliberately tied together; in both cases the pieces are one thing on the page, and one
        // thing either straddles the line or does not. Deciding per projection would cut a
        // partially-erased word in half, or push the bottom of a grouped diagram out from under its
        // own top. This is the same reading `selectWithLasso` gives a row and a group.
        val movingInk = _strokes.value
            .groupBy { it.groupId ?: it.id }
            .values
            .filter { unit ->
                val boxes = unit.mapNotNull(PageStroke::pageBounds)
                boxes.isNotEmpty() && boxes.all { cut.moves(it.left, it.top) }
            }
            .flatten()

        val movingTexts = state.outlines.filter { cut.moves(it.x, it.y) }
        val movingShapes = state.shapes.filter { cut.moves(it.x, it.y) }
        val movingImages = state.images.filter { cut.moves(it.x, it.y) }
        val movingTables = state.tables.filter { cut.moves(it.x, it.y) }
        val movingEquations = state.equations.filter { cut.moves(it.x, it.y) }
        val movingOutlines: List<Outline> =
            movingShapes + movingImages + movingTables + movingEquations

        val nearest = (
            movingInk.mapNotNull(PageStroke::pageBounds).map { cut.nearEdge(it.left, it.top) } +
                movingTexts.map { cut.nearEdge(it.x, it.y) } +
                movingOutlines.map { cut.nearEdge(it.x, it.y) }
            ).minOrNull()
        val limited = cut.limitedTo(nearest)
        // A closing drag with nothing between the line and the content it would have moved. The
        // gesture happened; it simply had no room, exactly as pushing a window into a screen corner
        // does nothing.
        if (limited.isEmpty) return
        val dx = limited.dx
        val dy = limited.dy

        asOneAction(pageId) {
            if (movingTexts.isNotEmpty()) {
                val ids = movingTexts.mapTo(mutableSetOf(), OutlineBox::id)
                // No touched containers: this moves boxes and never opens one, so the history entry
                // carries geometry alone and undo cannot reach sideways into text being typed.
                editTexts(touched = emptySet()) { boxes ->
                    boxes.map { if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it }
                }
            }
            moveShapes(movingShapes.mapTo(mutableSetOf(), Outline.Shape::id), dx, dy)
            moveImages(movingImages.mapTo(mutableSetOf(), Outline.Image::id), dx, dy)
            moveTables(movingTables.mapTo(mutableSetOf(), Outline.Table::id), dx, dy)
            moveEquations(movingEquations.mapTo(mutableSetOf(), Outline.Equation::id), dx, dy)
            moveInkPastLine(movingInk, dx, dy)
        }
    }

    /**
     * The ink half of [insertSpace], as an ordinary replayable lasso move.
     *
     * **A rectangle is a lasso, so this needs no new kind of stored operation.** `ink_moves` already
     * holds a page-space polygon plus a delta, and `replayMove` already selects by "enclosed by the
     * polygon *and* named in the targets" — so a box drawn around exactly the strokes that are moving
     * replays as exactly the move that was committed. The alternative was a seventh operation kind in
     * the ink log, a migration, and a second replay path to keep in step with the first, for a
     * translation the existing one already expresses.
     *
     * The box is the union of what is moving rather than the half-plane the gesture describes,
     * deliberately: an unbounded canvas has no far edge to draw a half-plane to, and a polygon has to
     * be finite to be stored. Nothing is lost — the targets are named by id, so a stroke drawn below
     * the line *after* this gesture is not swept up by it on the next load, which is correct.
     *
     * [SPACE_LASSO_MARGIN] keeps the enclosure test off the boundary itself: `pointInPolygon` gives no
     * useful answer for a corner lying exactly on an edge, and a stroke whose top is exactly at the
     * line is the common case rather than a curiosity.
     */
    private fun moveInkPastLine(moving: List<PageStroke>, dx: Float, dy: Float) {
        val bounds = moving.mapNotNull(PageStroke::pageBounds).unionBounds() ?: return
        val left = bounds.left - SPACE_LASSO_MARGIN
        val top = bounds.top - SPACE_LASSO_MARGIN
        val right = bounds.right + SPACE_LASSO_MARGIN
        val bottom = bounds.bottom + SPACE_LASSO_MARGIN
        moveInk(
            InkLassoMove(
                path = listOf(
                    InkPoint(left, top),
                    InkPoint(right, top),
                    InkPoint(right, bottom),
                    InkPoint(left, bottom),
                ),
                targetIds = moving.mapTo(mutableSetOf(), PageStroke::id),
                projections = moving.mapTo(mutableSetOf(), PageStroke::projectionKey),
                dx = dx,
                dy = dy,
            ),
        )
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
        // Read straight off the state, unlike a table: an equation carries its own content, so there
        // is no second half of it anywhere else to go stale.
        val equations = _uiState.value.equations.filter { it.id in selection.equationIds }
        if (strokes.isEmpty() && shapes.isEmpty() && tables.isEmpty() && equations.isEmpty()) return
        clipboard = CanvasClipboard(
            strokes = strokes,
            shapes = shapes,
            tables = tables,
            equations = equations,
        )
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
        val sourceEquations = clipboard.equations
        if (sources.isEmpty() && sourceShapes.isEmpty() && sourceTexts.isEmpty() &&
            sourceTables.isEmpty() && sourceEquations.isEmpty()
        ) {
            return
        }

        val bounds = sources.mapNotNull(PageStroke::pageBounds) +
            sourceShapes.map(Outline.Shape::pageBounds) +
            // Exact, unlike the two approximations below it: an equation's box is its geometry.
            sourceEquations.map(Outline.Equation::pageBounds) +
            // The sum of the row floors, which is the height the document can know — TA3. Off by
            // however far a cell's text runs past its row, exactly as a text box's floor is.
            sourceTables.map { InkBounds(it.x, it.y, it.x + it.width, it.y + it.height) } +
            // A container's height is whatever its text wraps to and only the canvas knows it, so
            // the floor stands in. It is off by however far the text runs past it, which moves a
            // pasted box up by half of that — visible only when a text box is pasted together with
            // something else, and cheaper to accept than to plumb a measurement into the ViewModel.
            sourceTexts.map { InkBounds(it.x, it.y, it.x + it.width, it.y + it.minHeight) }
        val union = bounds.unionBounds() ?: return
        // Horizontally centred on the tap, vertically hung *from* it: the paste grows downward from
        // the point, the way everything else placed on this canvas does. Centring the box vertically
        // put half of what was pasted above the tap, so a paste near the top of the page landed with
        // its head off the sheet and the tap looked like it had chosen the middle of the content
        // rather than its start.
        // Clamped as **one** delta, against the union, rather than each kind coercing its own corner
        // afterwards. Both keep the paste on the page — [PageBounds] — but only this one keeps it in
        // the arrangement it was copied in: coercing per object slides whatever stuck out furthest
        // back to the wall and leaves the rest where it was, so a diagram pasted near the corner
        // arrives with its pieces on top of each other.
        val wanted = InkPoint(at.x - union.center.x, at.y - union.top)
        val (dx, dy) = PageBounds.clampTranslation(union, wanted.x, wanted.y)

        if (sourceTexts.isNotEmpty()) {
            val pasted = sourceTexts.map { source ->
                source.copy(id = newId(), x = source.x + dx, y = source.y + dy)
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
                source.withNewIds().copy(x = source.x + dx, y = source.y + dy)
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

        if (sourceEquations.isNotEmpty()) {
            val pastedEquations = sourceEquations.map { source ->
                source.translated(dx, dy).copy(id = newId())
            }
            editEquations { it + pastedEquations }
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
                colorFollowsTheme = entity.colorFollowsTheme,
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
            .associate { it.id to StrokeColor(it.stroke.brush.colorIntArgb, it.colorFollowsTheme) }
        // An automatic stroke whose resolved colour already matches is still a change: it is about
        // to stop following the canvas, which the colour alone cannot tell you.
        if (oldColors.isEmpty()) return
        if (oldColors.values.all { it.argb == colorArgb && it.followsTheme == false }) return
        val newColors = oldColors.keys.associateWith { StrokeColor(colorArgb, followsTheme = false) }
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
                    // A separate transaction from the erase on purpose: that one is the user's and
                    // travels to the server, this one is bookkeeping and must not. Dying between the
                    // two costs nothing — the next open of this page collects them.
                    val surviving = updated.mapTo(HashSet(updated.size)) { it.id }
                    repository.collectErasedAwayStrokes(targetIds.filterNot { it in surviving })
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
            // One half, one place: an equation's source is the object, so the list is the whole of it.
            is CanvasHistoryEntry.Equations -> {
                _uiState.value = _uiState.value.copy(
                    equations = if (applied) entry.after else entry.before,
                )
                edits.tryEmit(Unit)
            }
            // One half too, and deliberately so: the picture's bytes are not in the history and are
            // not touched by it. An undone delete restores a frame whose file was never removed.
            is CanvasHistoryEntry.Images -> {
                _uiState.value = _uiState.value.copy(
                    images = if (applied) entry.after else entry.before,
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
            // Backwards on the way out, forwards on the way in. The parts of one composite touch
            // different halves of the state and so commute in practice, but a history that only
            // works because its steps happen not to interfere is a history waiting for the first
            // pair that does.
            is CanvasHistoryEntry.Composite -> {
                val parts = if (applied) entry.parts else entry.parts.asReversed()
                parts.forEach { applyHistoryEntry(it, applied) }
            }
        }
    }

    /**
     * Collects everything [body] records into a single entry on the ring — see
     * [CanvasHistoryEntry.Composite] for when that is right and when it is not.
     *
     * **The ring is diverted rather than the entries post-processed.** Each kind's edit funnel is the
     * one door that kind goes through, and it is the funnel that applies the origin-corner invariant,
     * wakes autosave and decides that an edit changing nothing is not an edit. Reaching around them to
     * write history by hand would mean reimplementing all of that per kind and getting it wrong on the
     * sixth; taking their entries as they are produced costs a redirect in [pushHistory] and leaves
     * every funnel exactly as it was.
     *
     * Nesting is a no-op rather than an error: the outermost call owns the group, so a helper that
     * groups internally can still be called from inside a larger action.
     */
    private inline fun asOneAction(pageId: String, body: () -> Unit) {
        if (historyGroup != null) {
            body()
            return
        }
        val collected = mutableListOf<CanvasHistoryEntry>()
        historyGroup = collected
        try {
            body()
        } finally {
            historyGroup = null
        }
        when (collected.size) {
            // Nothing moved — an edit that changes nothing is not an edit, at this level too.
            0 -> Unit
            // One kind's action is that kind's entry. Wrapping it would only cost the coalescing
            // in [pushHistory] the ability to see what it is.
            1 -> pushHistory(pageId, collected.first())
            else -> pushHistory(pageId, CanvasHistoryEntry.Composite(collected.toList()))
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
        // Being collected into one action. Nothing else may happen yet — in particular the redo
        // branch must not be dropped and the buttons must not be republished until the whole action
        // has landed, or a half-built composite would already be on the ring.
        historyGroup?.let {
            it += entry
            return
        }
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

        // And again for an equation, which has one stream worth folding: the toolkit's colour picker.
        val equationsCoalesce = entry is CanvasHistoryEntry.Equations &&
            entry.coalesceKey != null &&
            previous is CanvasHistoryEntry.Equations &&
            previous.coalesceKey == entry.coalesceKey &&
            entry.atMillis - previous.atMillis <= SHAPE_COALESCE_MS

        if (equationsCoalesce) {
            history.undo[history.undo.lastIndex] = (previous as CanvasHistoryEntry.Equations).copy(
                after = (entry as CanvasHistoryEntry.Equations).after,
                atMillis = entry.atMillis,
            )
        } else if (shapesCoalesce) {
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
        // The edit that stood an arrival down has landed, so the page can be rebuilt around it now.
        if (count == 0 && deferredInkAbsorption && _uiState.value.selectedPageId == pageId) {
            absorbRemoteInk(pageId, remoteInkGeneration(pageId))
        }
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

    /** For the delete confirmation, which says how many pages go with the section. */
    suspend fun pageCount(sectionId: String): Int = repository.pageCount(sectionId)

    /**
     * What a notebook would take with it, for its delete confirmation.
     *
     * Both halves are metadata-only reads — `pages` never touches `page_content` — so counting the
     * contents of a large notebook costs no document decoding.
     */
    suspend fun notebookContents(notebookId: String): NotebookContents = NotebookContents(
        sections = repository.sectionsInNotebook(notebookId).size,
        pages = repository.pagesInNotebook(notebookId).size,
    )

    fun deleteSection(sectionId: String) {
        val sectionName = _uiState.value.tree
            .flatMap { it.liveSections }
            .firstOrNull { it.id == sectionId }
            ?.name
            .orEmpty()
        viewModelScope.launch {
            if (selectedSection.value == sectionId) persist()
            repository.deleteSection(sectionId)
            deletionNoticeChannel.send(
                DeletionNotice(
                    key = DeletedItemKey(sectionId, DeletedItemKind.Section),
                    message = "${sectionName.ifBlank { "Section" }} moved to Deleted items.",
                ),
            )
            if (selectedSection.value == sectionId) {
                closeOpenSection()
                _uiState.value.tree.firstOrNull()?.liveSections?.firstOrNull()?.let { selectSection(it.id) }
            }
        }
    }

    /**
     * Tombstones a whole notebook.
     *
     * Only the notebook row is soft-deleted; its sections and pages keep their own `deletedAt` null
     * (see `NotesRepository.deleteNotebook`), which is what makes an eventual undelete a one-row
     * write. The consequence here is that nothing downstream notices the sections have gone out of
     * reach: the tree flow drops the notebook, but `selectedSection` would go on pointing into it
     * and the editor would keep a page of a notebook the user cannot navigate to. So the selection
     * is moved off it explicitly, using the membership read *before* the write.
     */
    fun deleteNotebook(notebookId: String) {
        val notebookName = _uiState.value.tree
            .firstOrNull { it.notebook.id == notebookId }
            ?.notebook
            ?.name
            .orEmpty()
        viewModelScope.launch {
            val owned = _uiState.value.tree
                .firstOrNull { it.notebook.id == notebookId }
                ?.liveSections.orEmpty()
                .map { it.id }
                .toSet()
            if (selectedSection.value in owned) persist()
            repository.deleteNotebook(notebookId)
            deletionNoticeChannel.send(
                DeletionNotice(
                    key = DeletedItemKey(notebookId, DeletedItemKind.Notebook),
                    message = "${notebookName.ifBlank { "Notebook" }} moved to Deleted items.",
                ),
            )
            if (selectedSection.value in owned) {
                closeOpenSection()
                // Explicitly not `tree.firstOrNull()`: the flow may not have re-emitted yet, so the
                // deleted notebook can still be at the front of the list this reads.
                _uiState.value.tree
                    .firstOrNull { it.notebook.id != notebookId }
                    ?.liveSections?.firstOrNull()
                    ?.let { selectSection(it.id) }
            }
        }
    }

    /** Empties the editor of a section that has just been deleted, before anything else is chosen. */
    private fun closeOpenSection() {
        selectedSection.value = null
        _uiState.value = _uiState.value.copy(
            selectedSectionId = null,
            selectedPageId = null,
            pages = emptyList(),
            outlines = emptyList(),
        )
        _strokes.value = emptyList()
        _inkReadyPageId.value = null
        publishCanvasUndoState(null)
    }

    fun deletePage(pageId: String) {
        val pageName = _uiState.value.pages
            .firstOrNull { it.id == pageId }
            ?.title
            .orEmpty()
            .ifBlank { "Untitled page" }
        viewModelScope.launch {
            if (_uiState.value.selectedPageId == pageId) persist()
            repository.deletePage(pageId)
            deletionNoticeChannel.send(
                DeletionNotice(
                    key = DeletedItemKey(pageId, DeletedItemKind.Page),
                    message = "$pageName moved to Deleted items.",
                ),
            )
        }
    }

    /** Restores one item from either the durable pane or a transient snackbar action. */
    fun restoreDeletedItem(key: DeletedItemKey) {
        if (_deletedItemsStatus.value.restoring != null) return
        val item = deletedItems.value.items.firstOrNull { it.key == key }
        val label = item?.name ?: key.kind.displayName
        _deletedItemsStatus.value = DeletedItemsStatus(restoring = key)
        viewModelScope.launch {
            try {
                val restored = repository.restoreDeletedItem(key)
                _deletedItemsStatus.value = if (restored) {
                    DeletedItemsStatus(message = "$label was restored.")
                } else {
                    DeletedItemsStatus(
                        error = "$label is not currently available to restore. Check Deleted Items.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _deletedItemsStatus.value = DeletedItemsStatus(
                    error = "$label could not be restored.",
                )
            }
        }
    }

    fun clearDeletedItemsStatus() {
        if (_deletedItemsStatus.value.restoring == null) {
            _deletedItemsStatus.value = DeletedItemsStatus()
        }
    }

    /**
     * Commits a drag in the page list. [orderedIds] is the whole section in its new order, because
     * that is what a drop produces and it is the one form that cannot be misapplied to a list that
     * moved underneath it — see `NotesRepository.reorderPages`.
     */
    fun reorderPages(orderedIds: List<String>) {
        val sectionId = selectedSection.value ?: return
        viewModelScope.launch { repository.reorderPages(sectionId, orderedIds) }
    }

    /** The same, for a notebook's sections. Order is per-notebook, so the notebook is named. */
    fun reorderSections(notebookId: String, orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderSections(notebookId, orderedIds) }
    }

    fun setTitle(title: String) {
        val pageId = _uiState.value.selectedPageId ?: return
        _uiState.value = _uiState.value.copy(title = title)
        viewModelScope.launch { repository.renamePage(pageId, title) }
    }

    /** Builds one immutable save from the currently loaded editor state. */
    private fun currentPageSave(): PendingPageSave? {
        val state = _uiState.value
        val pageId = state.selectedPageId ?: return null
        // The page's stored content could not be read, so anything shown is a placeholder rather
        // than the user's work. Writing it would destroy the real content.
        if (readOnlyPageId == pageId) return null

        val outlines = mutableListOf<Outline.Text>()
        for (box in state.outlines) {
            // A missing entry means "content not known", never "content is empty". Writing an
            // empty outline for one would silently blank it, so an incomplete picture skips the
            // write entirely and waits for the next edit.
            val blocks = blocksById[box.id] ?: return null
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
            val cells = table.contentCellIds().associateWith { blocksById[it] ?: return null }
            tables += table.withCellBlocks(cells)
        }

        return PendingPageSave(
            pageId = pageId,
            doc = PageDoc(
                outlines = inDocumentOrder(
                    state.shapes + state.equations + state.images + tables + outlines +
                        unmanagedOutlines,
                ),
                style = state.pageStyle,
            ),
        )
    }

    /** Writes the current document, so switching pages cannot lose the last keystrokes. */
    private suspend fun persist() {
        val save = currentPageSave() ?: return
        repository.saveDoc(save.pageId, save.doc)
    }

    /**
     * Puts every outline back where the document had it — [documentOrder].
     *
     * **One rule for all six kinds, replacing a splice that only knew about one.** This used to
     * reinsert the unmanaged outlines at recorded indices, clamped, into a list whose managed half
     * had already been reordered by kind — so the ink landed at the right index of the wrong list,
     * and was only ever accidentally in the right place.
     *
     * Sorting by the loaded position instead needs no clamping and no special case for outlines
     * created or deleted while the page was open: a missing key sorts last, and a deleted one simply
     * is not here to be placed. `sortedBy` is stable, so anything new keeps the order it arrived in.
     */
    private fun inDocumentOrder(outlines: List<Outline>): List<Outline> {
        if (documentOrder.isEmpty()) return outlines
        return outlines.sortedBy { documentOrder[it.id] ?: Int.MAX_VALUE }
    }

    // --- the page's origin corner ---------------------------------------------------------------
    //
    // Applied inside each kind's edit funnel, which is what turns [PageBounds] from a habit into an
    // invariant: **no object is ever stored above or to the left of the page's origin**, whatever
    // asked for it. The gestures clamp too, and have to — a preview that disagreed with this would
    // follow the finger past the wall and spring back on the lift — but they are five places and
    // growing, and a rule enforced only at five places is a rule with a sixth coming.
    //
    // A translation rather than a coerced coordinate, because a shape's (x, y) is *derived* from its
    // segments: writing the corner alone would move the box and leave the drawing behind. It also
    // means the repair is the gentlest one available — the object keeps its size and its shape, and
    // only its position changes, by exactly the distance it was out.

    // The four read as one overloaded name at the call sites and have to be spelled apart for the
    // JVM, whose erasure sees four `List` parameters and one signature.

    @JvmName("shapesOnPage")
    private fun List<Outline.Shape>.onPage(): List<Outline.Shape> = map { shape ->
        val correction = PageBounds.correctionFor(shape.x, shape.y)
        if (correction.x == 0f && correction.y == 0f) shape
        else shape.translated(correction.x, correction.y)
    }

    @JvmName("imagesOnPage")
    private fun List<Outline.Image>.onPage(): List<Outline.Image> = map { image ->
        val correction = PageBounds.correctionFor(image.x, image.y)
        if (correction.x == 0f && correction.y == 0f) image
        else image.translated(correction.x, correction.y)
    }

    @JvmName("equationsOnPage")
    private fun List<Outline.Equation>.onPage(): List<Outline.Equation> = map { equation ->
        val correction = PageBounds.correctionFor(equation.x, equation.y)
        if (correction.x == 0f && correction.y == 0f) equation
        else equation.translated(correction.x, correction.y)
    }

    @JvmName("tablesOnPage")
    private fun List<Outline.Table>.onPage(): List<Outline.Table> = map { table ->
        val correction = PageBounds.correctionFor(table.x, table.y)
        if (correction.x == 0f && correction.y == 0f) table
        else table.translated(correction.x, correction.y)
    }

    @JvmName("textsOnPage")
    private fun List<OutlineBox>.onPage(): List<OutlineBox> = map { box ->
        val correction = PageBounds.correctionFor(box.x, box.y)
        if (correction.x == 0f && correction.y == 0f) box
        else box.copy(x = box.x + correction.x, y = box.y + correction.y)
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 400L

        /**
         * How long the search box waits before running a query — CS11.
         *
         * Shorter than autosave, because this is answering a question rather than protecting work:
         * long enough that typing a word is one search rather than five, short enough that the list
         * has caught up by the time the fingers stop.
         */
        private const val SEARCH_DEBOUNCE_MS = 180L

        /** Far enough that a duplicate is not mistaken for the original not having copied. */
        private const val DUPLICATE_OFFSET = 16f

        /**
         * How far Insert Space's replay box stands off the ink inside it, in page units.
         *
         * Only has to be non-zero — the targets are named by id, so a stroke that is not moving
         * cannot be caught by a generous box. A dp of slack simply keeps the enclosure test away
         * from its own boundary. See [moveInkPastLine].
         */
        private const val SPACE_LASSO_MARGIN = 4f

        /**
         * How far inside the visible corner a ribbon-inserted object lands.
         *
         * Not flush against it: an object placed exactly on the corner has two of its resize handles
         * half off the screen, and the first thing anyone does with a new picture is resize it.
         */
        private const val INSERT_MARGIN = 24f
        private const val CANVAS_HISTORY_LIMIT = 100

        /**
         * How many times a rebuild triggered by pulled ink will re-read a page that moved underneath
         * it before leaving the canvas alone — see [absorbRemoteInk].
         *
         * Three rather than one because the first collision is ordinary (a stroke finishing during
         * the read) and clears immediately, and rather than "until it succeeds" because a page being
         * drawn on without pause would spin rebuilding it. Giving up costs nothing permanent: the
         * generation is not recorded, so the next arrival tries again.
         */
        private const val INK_ABSORB_ATTEMPTS = 3

        /**
         * How long a run of same-key shape edits keeps folding into one undo step.
         *
         * Long enough to cover the gaps between steps of a slider being dragged, short enough that
         * coming back to the same control after looking at the result is a new action to undo.
         */
        private const val SHAPE_COALESCE_MS = 1200L

        /**
         * How many stroke rows one decoding task rebuilds.
         *
         * Sized against the eight-core tablet this was measured on, where a page of 9,553 strokes
         * became nineteen chunks: enough that every core has work and that the first ink appears in
         * a fraction of the total, few enough that a streaming page is not recomposed hundreds of
         * times on the way in.
         */
        const val MIN_OUTLINE_WIDTH = 120f
        const val MAX_OUTLINE_WIDTH = 2000f
        const val MAX_OUTLINE_HEIGHT = 4000f

        fun factory(
            repository: NotesRepository,
            attachments: AttachmentStore,
            editorDefaultsStore: EditorDefaultsStore,
            viewSettingsStore: ViewSettingsStore,
            penSettingsStore: PenSettingsStore,
            databaseBackups: DatabaseBackupManager? = null,
            notebookTransfers: NotebookTransferManager? = null,
            imageText: ImageTextIndexer? = null,
            inkText: InkTextIndexer? = null,
            remoteInk: StateFlow<Map<String, Long>>? = null,
            maySeedStarter: (suspend () -> Boolean)? = null,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotesViewModel(
                    repository,
                    attachments,
                    editorDefaultsStore,
                    viewSettingsStore,
                    penSettingsStore,
                    databaseBackups = databaseBackups,
                    notebookTransfers = notebookTransfers,
                    imageText = imageText,
                    inkText = inkText,
                    remoteInk = remoteInk,
                    maySeedStarter = maySeedStarter,
                ) as T
        }
    }
}
