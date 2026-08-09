package com.vivenotes.ui.editor

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke as InkStroke
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.RulerKind
import com.vivenotes.data.RulerSettings
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.TableSettings
import com.vivenotes.model.Outline
import com.vivenotes.model.canRemoveColumn
import com.vivenotes.model.canRemoveRow
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.canFill
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkLassoMove
import com.vivenotes.ink.InkLassoResize
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.Ruler
import com.vivenotes.ink.RulerPlacement
import com.vivenotes.ink.TableBounds
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PrintMargins
import com.vivenotes.model.RuleLines
import com.vivenotes.richtext.EditorStyle
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.OutlineEditText
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.OutlineBox
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.theme.CanvasColors
import com.vivenotes.ui.theme.LocalCanvasColors
import com.vivenotes.ui.theme.paintedWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

private val CANVAS_MIN_HEIGHT = 700.dp
private val CANVAS_TRAILING_SPACE = 320.dp

/** Blank canvas kept to the right of the widest container, so there is room to start another. */
private val CANVAS_TRAILING_WIDTH = 200.dp
private val CANVAS_MIN_WIDTH = 720.dp
private val GRIP_HEIGHT = 18.dp
private val RESIZE_HANDLE_WIDTH = 18.dp

/**
 * Ceilings imposed by [Constraints], which packs both axes into a single Int.
 *
 * The allowance on one axis depends on the other: while one stays under [CONSTRAINT_NARROW_PX] the
 * other may reach [CONSTRAINT_SOLE_PX], and past that both drop to [CONSTRAINT_PAIRED_PX]. A page
 * that grows as you scroll will find that edge eventually, and the old fixed 20,000dp height was
 * already only legal because the canvas happened to stay narrow — a wide sheet carrying tall content
 * would have thrown at layout time.
 */
private const val CONSTRAINT_NARROW_PX = 8_191
private const val CONSTRAINT_SOLE_PX = 262_143
private const val CONSTRAINT_PAIRED_PX = 32_767

/**
 * The largest canvas that can actually be laid out, at this zoom.
 *
 * Zoom belongs in the arithmetic because [Zoomed] reports the *scaled* size to its parent, so it is
 * the scaled size that has to be packable — a canvas that is legal at 100% can overflow at 400%.
 */
private fun Density.clampToConstraints(size: DpSize, zoom: Float): DpSize {
    fun limit(dp: Dp, ceiling: Int): Dp = minOf(dp, (ceiling / zoom).toDp())

    val width = limit(size.width, CONSTRAINT_PAIRED_PX)
    val widthPx = width.toPx() * zoom
    val heightCeiling = if (widthPx <= CONSTRAINT_NARROW_PX) CONSTRAINT_SOLE_PX else CONSTRAINT_PAIRED_PX
    return DpSize(width, limit(size.height, heightCeiling))
}

/**
 * The page canvas: title, timestamp, ruled background, and free-form text containers.
 *
 * A page is not one linear document. Text lives in independently positioned containers
 * ("outlines"): tapping empty canvas starts a new one, and each can be dragged and resized.
 * Each container — and each table cell — is an [OutlineEditText] hosted through [NoteEditor], the one
 * place the Compose shell hands off to a View, so that span-based editing, IME handling, selection UI
 * and accessibility come from the platform rather than being reimplemented.
 */
@Composable
fun EditorPane(
    title: String,
    createdAt: Long,
    defaults: EditorDefaults,
    /** The page's own appearance, from the View tab. */
    style: PageStyle,
    zoom: Float,
    /**
     * A pinch in flight: the zoom it has reached, not yet written down.
     *
     * Separate from the View tab's own `setZoom` because the two ask different things of the store.
     * A ribbon button is one decision and one write; a pinch is one decision reported sixty times a
     * second, and putting that through DataStore would be sixty file writes for one gesture.
     * [onZoomCommitted] is where it lands.
     */
    onZoomPinched: (Float) -> Unit = {},
    onZoomCommitted: () -> Unit = {},
    onTitleChange: (String) -> Unit,
    outlines: List<OutlineBox>,
    pageRevision: Int,
    /**
     * Which page is open. **Not the same signal as [pageRevision]**, and the difference is what the
     * selection hangs on: a revision bump means "the containers were rebuilt", which a row added to a
     * table is, and clearing the selection there would take the toolbar away from under the finger
     * that had just used it. A page *change* is what makes ids from somewhere else meaningless.
     */
    pageId: String? = null,
    initialBlocksFor: (String) -> List<Block>,
    commands: Flow<FormatCommand>,
    onBlocksChanged: (String, List<Block>) -> Unit,
    onSelectionChanged: (SelectionState) -> Unit,
    /** A mark applied with no selection — the editor's new default, not an edit. */
    onMarkArmed: (Mark) -> Unit,
    onCreateOutline: (Float, Float) -> String,
    /**
     * Whether the Home tab's **T** is pressed — `docs/textBoxPlan.md` TD2.
     *
     * A tap on bare canvas opens a container only while it is, which is the whole of what the toggle
     * toggles. Defaulted true so the canvas can be exercised in isolation, and because a test that
     * taps to make a container should not have to arm anything first.
     */
    textArmed: Boolean = true,
    onMoveOutline: (String, Float, Float) -> Unit,
    onResizeOutline: (String, Float) -> Unit,
    onSetOutlineMinHeight: (String, Float) -> Unit,
    onOutlineBlurred: (String) -> Unit,
    /** The TextBox toolkit — `docs/textBoxPlan.md` TD3–TD5. */
    onCopyOutline: (String) -> Unit = {},
    onDeleteOutlines: (Set<String>) -> Unit = {},
    /**
     * Back into the command bus, for the toolkit's Select all.
     *
     * The bar is raised a few dp from the editor it is about and could reach for it directly; it does
     * not, because AD6's point is that there is one way to drive the editor and a second shorter one
     * is how the two drift apart. This goes out to the ViewModel and comes back through [commands].
     */
    onCommand: (FormatCommand) -> Unit = {},
    /** Window width and page width in dp, which is all Zoom to Page Width needs. */
    onCanvasMeasured: (Float, Float) -> Unit,
    /** Drawn while the Paper Size pane is open, so the margins being edited are visible. */
    showPrintMargins: Boolean,
    /**
     * The page's ink, and what the armed tool does with it.
     *
     * Defaulted to a page with no ink and nothing in hand, so the canvas can be exercised in
     * isolation the way [OutlineContainer] can — and because those defaults leave the overlay
     * transparent to touch, which is what the tap and zoom tests depend on.
     */
    strokes: List<PageStroke> = emptyList(),
    brush: Brush? = null,
    /** The armed shape's settings, or null when Insert Shape is not the tool in hand. */
    shaping: ShapeSettings? = null,
    /**
     * The ruler's settings while it is out, or null while it is away — `docs/rulerPlan.md`.
     *
     * Only *which* ruler and how big, per RD2. Where it is lying is this composable's business,
     * because it is a fact about this moment and nothing outside the canvas has any use for it.
     */
    ruler: RulerSettings? = null,
    /**
     * The tables on the page, and everything the Table Class can do to one — `docs/tablePlan.md`.
     *
     * `tableArmed` is Insert Table in hand (TA7): the next tap on bare canvas puts one there, and
     * [onInsertTable] returns its id so the page can select what it just made.
     */
    tables: List<Outline.Table> = emptyList(),
    tableArmed: Boolean = false,
    onInsertTable: (Float, Float) -> String? = { _, _ -> null },
    onMoveTables: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeTables: (Set<String>, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onDeleteTables: (Set<String>) -> Unit = {},
    onRecolorTables: (Set<String>, Int) -> Unit = { _, _ -> },
    onSetTableBorderWidth: (Set<String>, Float) -> Unit = { _, _ -> },
    onSetTableFill: (Set<String>, Int?) -> Unit = { _, _ -> },
    onSetTableColumnWidth: (String, Int, Float) -> Unit = { _, _, _ -> },
    onSetTableRowMinHeight: (String, Int, Float) -> Unit = { _, _, _ -> },
    onInsertTableRow: (String, Int) -> Unit = { _, _ -> },
    onDeleteTableRow: (String, Int) -> Unit = { _, _ -> },
    onInsertTableColumn: (String, Int) -> Unit = { _, _ -> },
    onDeleteTableColumn: (String, Int) -> Unit = { _, _ -> },
    shapes: List<Outline.Shape> = emptyList(),
    onMoveShape: (String, Float, Float) -> Unit = { _, _, _ -> },
    onResizeShape: (String, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onResizeShapeArm: (String, String, Boolean, Float) -> Unit = { _, _, _, _ -> },
    /**
     * The lasso's halves, taking the whole set at once: one gesture is one edit and so one Undo,
     * however many shapes it caught.
     */
    onMoveShapes: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeShapes: (Set<String>, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onDeleteShapes: (Set<String>) -> Unit = {},
    onRecolorShapes: (Set<String>, Int) -> Unit = { _, _ -> },
    onSetShapeBorderWidth: (Set<String>, Float) -> Unit = { _, _ -> },
    onSetShapeLineType: (Set<String>, LineType) -> Unit = { _, _ -> },
    /** Null clears the fill: an absent inside, not a transparent one. */
    onSetShapeFill: (Set<String>, Int?) -> Unit = { _, _ -> },
    erasing: Boolean = false,
    lassoing: Boolean = false,
    eraser: EraserSettings = EraserSettings(),
    allowFinger: Boolean = false,
    onStrokeFinished: (InkStroke) -> Unit = {},
    /** Returns the new shape's id, so the page can select it — see the call site below. */
    onInsertShape: (ShapeSettings, Float, Float, Float, Float) -> String? = { _, _, _, _, _ -> null },
    onPartialErase: (InkStroke) -> Unit = {},
    onObjectErase: (InkStroke) -> Unit = {},
    onMoveSelection: (InkLassoMove) -> Unit = {},
    onResizeSelection: (InkLassoResize) -> Unit = {},
    onDeleteInkSelection: (Set<String>) -> Unit = {},
    /** Puts the whole selection on the shared clipboard — every kind it holds, in one call. */
    onCopySelection: (CanvasSelection) -> Unit = {},
    hasClipboard: Boolean = false,
    onPaste: (InkPoint) -> Unit = {},
    onRecolorInkSelection: (Set<String>, Int) -> Unit = { _, _ -> },
    onGroupInkSelection: (Set<String>) -> Unit = {},
    onUngroupInkSelection: (Set<String>) -> Unit = {},
    formulaRecognitionAvailable: Boolean = false,
    recognitionRunning: Boolean = false,
    onRecognizeFormula: (CanvasSelection) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val shell = LocalCanvasColors.current
    val canvas = remember(shell, style.backgroundArgb) { shell.paintedWith(style.backgroundArgb) }
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary

    var focusedEditor by remember { mutableStateOf<OutlineEditText?>(null) }
    var focusedOutlineId by remember { mutableStateOf<String?>(null) }

    /**
     * The table cell with the caret in it, if any — `docs/tablePlan.md` TA6.
     *
     * Beside [focusedOutlineId] rather than folded into it: the two never both hold something (one
     * editor has focus), but they mean different things to everything downstream. A container id
     * names something the text toolkit is about; a cell id names *where in a grid* the Row and Column
     * menus should insert.
     */
    var focusedCellId by remember { mutableStateOf<String?>(null) }

    /**
     * Puts text input away, wherever on the page it is — `docs/tablePlan.md` TA11.
     *
     * **Nothing else on this canvas ever would.** An editor is a real `EditText` (AD6), and Compose
     * does not touch the focus of a View it is only hosting, so a tap that lands anywhere but in
     * another editor leaves the caret exactly where it was with the keyboard still up. TA11 had the
     * *selection* cleared by such a tap and stopped there, which is how a table finished with could
     * keep the caret in a cell that no longer showed any chrome saying so.
     *
     * The ids and the ribbon are left to the blur that follows: `clearFocus` fires the same listener
     * a tap on another editor would, so there is one path out of a focused editor rather than two.
     *
     * Reads through the state itself rather than being captured, because the gesture handlers that
     * call it are keyed on page geometry and outlive the composition that built them.
     */
    val dismissTextInput: () -> Unit = { focusedEditor?.deactivateTextInput() }

    /**
     * The row or column held by a tap on its gutter handle — `docs/tablePlan.md` TA16.
     *
     * Beside the selection rather than inside it, for the reason [TableAxis] gives: a `CanvasSelection`
     * holds objects on the page, and this is a place inside one of them.
     */
    var heldAxis by remember(pageId) { mutableStateOf<TableAxis?>(null) }
    var lastFocusedEditor by remember { mutableStateOf<OutlineEditText?>(null) }
    var lastFocusedOutlineId by remember { mutableStateOf<String?>(null) }
    var retainedEquationEditor by remember { mutableStateOf<OutlineEditText?>(null) }
    var retainedEquationOutlineId by remember { mutableStateOf<String?>(null) }
    /** Container to grab focus once composed — the one the user just created by tapping. */
    var pendingFocusId by remember { mutableStateOf<String?>(null) }
    var pastePopupAt by remember { mutableStateOf<InkPoint?>(null) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    /**
     * Whether the text tool is armed, reachable from a gesture handler that outlives it.
     *
     * The canvas tap detector is keyed on page geometry and is deliberately not rebuilt when a tool
     * is picked up or put down — `ShapeLayer` learned the same lesson the harder way, where keying a
     * handler on state that changes mid-gesture killed the gesture that had asked for the change.
     */
    val currentTextArmed = rememberUpdatedState(textArmed)

    /** Insert Table in hand, read the same way and for the same reason. */
    val currentTableArmed = rememberUpdatedState(tableArmed)
    val currentOnInsertTable = rememberUpdatedState(onInsertTable)

    /**
     * Which containers currently hold text — `docs/textBoxPlan.md` TD3.
     *
     * The toolkit appears under the same rule the container's own chrome does, *focused and
     * non-empty*, and that second half is known here rather than in the ViewModel: an empty
     * container is a caret position, and a page of stray taps must not sprout toolbars.
     */
    val nonEmpty = remember { mutableStateMapOf<String, Boolean>() }

    /**
     * What is selected on this page, across kinds — AD7's "selection is a page-level concept".
     *
     * Held here rather than in the ViewModel because nothing about it is persisted and a live drag
     * rewrites its bounds: a `StateFlow` write per gesture end is fine, one per frame is not. Cleared
     * with the page, since ids from the last page mean nothing on this one.
     */
    var selection by remember(pageId) { mutableStateOf<CanvasSelection?>(null) }
    val lassoGesture = remember { LassoGesture() }

    /**
     * The tables as rectangles, measured — `docs/tablePlan.md` TA3 and [TableBounds].
     *
     * A table's height is whatever its cells' text wraps to and the document stores only each row's
     * floor, so the model runs short the moment a cell overflows. The canvas laid the table out, so
     * the canvas is what says how tall it is; the floors stand in for the one frame before it has.
     */
    val tableBounds = remember(tables, heights.toMap()) {
        tables.map { table ->
            val measured = heights[table.id]?.let { with(density) { it.toDp().value } }
            TableBounds(
                id = table.id,
                bounds = InkBounds(
                    left = table.x,
                    top = table.y,
                    right = table.x + TABLE_GUTTER.value + table.width,
                    bottom = table.y + (measured ?: (TABLE_GUTTER.value + table.height)),
                ),
            )
        }
    }

    // Re-read against the page whenever any kind changes, so a deleted or undone object takes its
    // handles with it instead of leaving a rectangle over nothing.
    LaunchedEffect(strokes, shapes, tableBounds) {
        selection = selection?.reconcile(strokes, shapes, tableBounds)
    }

    // A hold outlives neither its table's selection nor the row it named. Selecting something else
    // is the common way out; an index past the end is the one that would otherwise turn a shrinking
    // table into a bar whose buttons act on a row that is not there.
    LaunchedEffect(selection, tables) {
        heldAxis = heldAxis?.takeIf { axis ->
            val table = tables.firstOrNull { it.id == axis.tableId }
            selection?.holdsTable(axis.tableId) == true && when (axis) {
                is TableAxis.Row -> axis.index in table?.rows.orEmpty().indices
                is TableAxis.Column -> axis.index in table?.columns.orEmpty().indices
            }
        }
    }
    LaunchedEffect(lassoing) {
        if (!lassoing) lassoGesture.clear()
    }

    LaunchedEffect(pageRevision, hasClipboard) {
        pastePopupAt = null
    }

    val editorStyle = remember(density, primary) {
        with(density) {
            EditorStyle(
                indentStepPx = 28.dp.roundToPx(),
                listGapPx = 34.dp.roundToPx(),
                bulletRadiusPx = 3.dp.roundToPx(),
                accentColor = primary.toArgb(),
                codeBackgroundColor = Color.White.copy(alpha = 0.07f).toArgb(),
                quoteColor = primary.toArgb(),
            )
        }
    }

    // Commands are one-shot events, so they are collected rather than read from state —
    // replaying them on recomposition would re-apply formatting.
    LaunchedEffect(Unit) {
        commands.collect { command ->
            when (command) {
                FormatCommand.DeactivateTextInput -> {
                    val editor = focusedEditor ?: retainedEquationEditor
                    val outlineId = focusedOutlineId ?: retainedEquationOutlineId
                    val editorWasFocused = editor?.hasFocus() == true
                    pendingFocusId = null
                    focusedEditor = null
                    focusedOutlineId = null
                    focusedCellId = null
                    lastFocusedEditor = null
                    lastFocusedOutlineId = null
                    retainedEquationEditor = null
                    retainedEquationOutlineId = null
                    editor?.deactivateTextInput()
                    // A retained equation target has already lost View focus to its popup, so it
                    // will not produce another blur callback when the Draw tool releases it.
                    if (!editorWasFocused && outlineId != null) onOutlineBlurred(outlineId)
                    onSelectionChanged(SelectionState())
                }
                FormatCommand.RetainEquationTarget -> {
                    retainedEquationEditor = focusedEditor ?: lastFocusedEditor
                    retainedEquationOutlineId = focusedOutlineId ?: lastFocusedOutlineId
                }
                FormatCommand.ReleaseEquationTarget -> {
                    val releasedId = retainedEquationOutlineId
                    retainedEquationEditor = null
                    retainedEquationOutlineId = null
                    if (releasedId != null && focusedOutlineId != releasedId) onOutlineBlurred(releasedId)
                }
                is FormatCommand.InsertEquation -> {
                    (retainedEquationEditor ?: focusedEditor)?.apply(command)
                    retainedEquationEditor = null
                    retainedEquationOutlineId = null
                }
                else -> {
                    val editor = focusedEditor
                    if (editor != null) {
                        editor.apply(command)
                    } else if (command is FormatCommand.SetMark) {
                        // Nothing focused, so there is nothing to format — but choosing a font or
                        // size still says what the next text should look like.
                        onMarkArmed(command.mark)
                    }
                }
            }
        }
    }

    LaunchedEffect(pageRevision) {
        lastFocusedEditor = null
        lastFocusedOutlineId = null
        retainedEquationEditor = null
        retainedEquationOutlineId = null
    }

    val sheet = style.pageSizeDp?.let { (w, h) -> DpSize(w.dp, h.dp) }

    // What the content occupies, measured from the page's own top-left corner and including the
    // band the title sits in. Derived rather than recomputed: the canvas now grows as the user
    // scrolls, so this is read on far more recompositions than it used to be.
    val contentBounds by remember(outlines, tables, style.hideTitle, density) {
        derivedStateOf {
            var width = 0.dp
            var height = if (style.hideTitle) 0.dp else PageStyle.TITLE_BAND_DP.dp
            outlines.forEach { box ->
                width = maxOf(width, (box.x + box.width).dp)
                height = maxOf(height, box.y.dp + with(density) { (heights[box.id] ?: 0).toDp() })
            }
            // A table's box is its grid plus the gutter reserved for its handles, and its height is
            // measured for the reason `tableBounds` gives.
            tables.forEach { table ->
                width = maxOf(width, table.x.dp + TABLE_GUTTER + table.width.dp)
                height = maxOf(height, table.y.dp + with(density) { (heights[table.id] ?: 0).toDp() })
            }
            DpSize(width, height)
        }
    }

    // A chosen paper size binds the page only while the page can hold the content. Once something
    // sits outside it, clipping to it would hide the user's work, so the sheet steps back to being
    // a guide and the canvas covers the content instead. Live state, so dragging a container over
    // the edge and back flips it both ways.
    val fits = sheet != null &&
        contentBounds.width <= sheet.width && contentBounds.height <= sheet.height

    // What the page *is*, as opposed to how much of it can be scrolled: the sheet when one holds
    // the content, otherwise whatever the content needs. This is what Zoom to Page Width fits.
    val pageSize = if (fits) {
        sheet!!
    } else {
        DpSize(
            maxOf(contentBounds.width, sheet?.width ?: 0.dp, CANVAS_MIN_WIDTH),
            maxOf(contentBounds.height, sheet?.height ?: 0.dp, CANVAS_MIN_HEIGHT),
        )
    }

    // Hoisted above the measurement below because the pinch handler is a modifier *on* it, and a
    // modifier is built before the block that would otherwise declare these.
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val scope = rememberCoroutineScope()
    // The platform's own fling curve, so letting go of the page decelerates the way letting go
    // of anything else on the device does.
    val flingSpec = rememberSplineBasedDecay<Float>()

    // Read through holders, never captured: the pinch handler is keyed on nothing and outlives every
    // recomposition, so a captured zoom would be frozen at whatever it was when the page opened.
    val currentZoom = rememberUpdatedState(zoom)
    val currentOnZoomPinched = rememberUpdatedState(onZoomPinched)
    val currentOnZoomCommitted = rememberUpdatedState(onZoomCommitted)

    /**
     * Where the ruler is lying — RD2, held here because it is transient and page-scoped in units
     * only, not in ownership: it stays put across a page switch, the way a ruler stays on the desk.
     *
     * Seeded once, the first time it comes out, from the middle of what is on screen. Kept when it
     * is put away, so bringing it back does not lose the angle you set.
     */
    var rulerPlacement by remember { mutableStateOf<RulerPlacement?>(null) }

    /**
     * The window, in view dp. A layout fact, captured out of [BoxWithConstraints] because the
     * straightedge's length is measured from it — RD3a.
     */
    var viewport by remember { mutableStateOf(DpSize.Zero) }

    val laidRuler = ruler?.let { settings ->
        rulerPlacement?.let {
            // The straightedge spans the viewport's *diagonal*, so it still crosses the whole window
            // at any rotation rather than falling short of the corners at 45°. Divided by the zoom
            // because it is placed in page units and has to keep covering the screen as the page
            // grows under it. The semicircle keeps its own diameter — it is a protractor, not a
            // horizon.
            val span = when (settings.kind) {
                RulerKind.Straight ->
                    hypot(viewport.width.value, viewport.height.value) / zoom
                RulerKind.Protractor -> settings.diameterDp.toFloat()
            }
            Ruler(it.centerX, it.centerY, it.angleRadians, settings.kind, span)
        }
    }
    val currentRuler = rememberUpdatedState(laidRuler)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Only a page bound by a sheet has an outside. One the content has outgrown is canvas
            // all the way to its edge, and painting a surround there would say otherwise.
            .background(if (fits) MaterialTheme.colorScheme.surfaceContainer else canvas.background)
            // Ahead of the pinch on the same node, which is the whole of how the two are kept
            // apart: on the Initial pass modifiers are asked in order, so a gesture that began on
            // the ruler is claimed here and the pinch below stands down. RD6.
            .pointerInput(Unit) {
                detectRulerDrag(
                    rulerAt = { currentRuler.value },
                    toPage = {
                        android.graphics.Matrix().also {
                            inkPageToView(
                                zoom = currentZoom.value,
                                density = density.density,
                                scrollX = horizontal.value.toFloat(),
                                scrollY = vertical.value.toFloat(),
                            ).invert(it)
                        }
                    },
                    onMove = { dx, dy ->
                        rulerPlacement = rulerPlacement?.let {
                            it.copy(centerX = it.centerX + dx, centerY = it.centerY + dy)
                        }
                    },
                    onTurn = { radians ->
                        rulerPlacement = rulerPlacement?.let {
                            it.copy(angleRadians = it.angleRadians + radians)
                        }
                    },
                    onTapDial = {
                        currentRuler.value?.let { held ->
                            rulerPlacement = rulerPlacement
                                ?.copy(angleRadians = held.turnedToNextEighth().angleRadians)
                        }
                    },
                )
            }
            // On the outermost node of the pane, because that is the only one that is an ancestor of
            // both the scrolling page and the ink overlay — see `detectPinchZoom` for why nothing
            // less than an ancestor can take a gesture off either of them.
            .pointerInput(Unit) {
                /**
                 * Where the gesture has got to, seeded from the page on the first sample.
                 *
                 * Not re-read from [zoom] each time, because pointers arrive faster than frames do:
                 * two samples in one frame both see the zoom the *last* frame composed, so chaining
                 * from the prop would apply the second one's scale to the first one's zoom and quietly
                 * drop a step. The scroll below composes correctly against this without waiting for a
                 * layout — it is all one scaling of the same content space.
                 */
                var live: Float? = null
                detectPinchZoom(
                    onPinch = { focus, pan, zoomChange ->
                        val step = pinchStep(
                            zoom = live ?: currentZoom.value,
                            scrollX = horizontal.value.toFloat(),
                            scrollY = vertical.value.toFloat(),
                            focus = focus,
                            pan = pan,
                            zoomChange = zoomChange,
                        )
                        live = step.zoom
                        currentOnZoomPinched.value(step.zoom)
                        // Raw deltas for the reason `ScrollStatePan` uses them: the page has to keep
                        // up with the fingers exactly. Each is clamped to the scroll range the
                        // *previous* zoom laid out, so a pinch at the far edge of the page lags by a
                        // frame — the next sample measures from where the scroll actually got to, so
                        // it corrects itself rather than accumulating.
                        horizontal.dispatchRawDelta(step.dx)
                        vertical.dispatchRawDelta(step.dy)
                    },
                    onEnd = {
                        live = null
                        currentOnZoomCommitted.value()
                    },
                )
            },
    ) {
        // The window onto the page, in page units: what the user can see at this zoom.
        val window = DpSize(maxWidth / zoom, maxHeight / zoom)

        // How far the canvas has been extended to meet the user, counted in whole screenfuls.
        //
        // A high-water mark, so scrolling back does not shrink the canvas out from under the
        // scroll position, and quantised to the screen so this changes about once per screenful
        // rather than once per frame — the difference between one recomposition and sixty.
        var reachedX by remember(pageRevision) { mutableIntStateOf(0) }
        var reachedY by remember(pageRevision) { mutableIntStateOf(0) }
        LaunchedEffect(pageRevision, window, density) {
            snapshotFlow {
                val across = with(density) { window.width.toPx() }
                val down = with(density) { window.height.toPx() }
                val x = if (across > 0f) ((horizontal.value / zoom + across) / across).toInt() else 0
                val y = if (down > 0f) ((vertical.value / zoom + down) / down).toInt() else 0
                x to y
            }.collect { (x, y) ->
                if (x > reachedX) reachedX = x
                if (y > reachedY) reachedY = y
            }
        }

        // A bound page stops at its sheet plus a surround — that is what choosing a size buys. An
        // unbounded one always keeps a screenful in front of wherever the user has got to, which is
        // what makes it feel like it has no end.
        val room = DpSize(pageSize.width + CANVAS_TRAILING_WIDTH, pageSize.height + CANVAS_TRAILING_SPACE)
        val canvasSize = density.clampToConstraints(
            if (fits) {
                DpSize(maxOf(room.width, window.width), maxOf(room.height, window.height))
            } else {
                DpSize(
                    maxOf(room.width, window.width * (reachedX + 1)),
                    maxOf(room.height, window.height * (reachedY + 1)),
                )
            },
            zoom,
        )

        // Reported rather than derived by the caller: these are layout facts, known here and
        // nowhere else. Writing them costs nothing and nothing recomposes from them.
        SideEffect { onCanvasMeasured(maxWidth.value, pageSize.width.value) }

        // Guarded, because writing state from a SideEffect on every pass would recompose for ever.
        val measured = DpSize(maxWidth, maxHeight)
        if (viewport != measured) SideEffect { viewport = measured }

        // Laid across the middle of what is on screen the first time it is asked for, because a
        // ruler that arrives off the edge of the window looks like a button that does nothing.
        LaunchedEffect(ruler != null) {
            if (ruler != null && rulerPlacement == null) {
                rulerPlacement = RulerPlacement(
                    centerX = horizontal.value / (zoom * density.density) + window.width.value / 2f,
                    centerY = vertical.value / (zoom * density.density) + window.height.value / 2f,
                    angleRadians = 0f,
                )
            }
        }

        // Read while drawing rather than while composing, so scrolling re-runs the ruling's draw
        // and nothing above it. Given as a lambda for exactly that reason — calling it here would
        // subscribe the whole page to every scrolled pixel.
        val visibleWindow: () -> Rect = {
            val left = horizontal.value / zoom
            val top = vertical.value / zoom
            with(density) {
                Rect(left, top, left + window.width.toPx(), top + window.height.toPx())
            }
        }
        val canPlaceAt: (InkPoint) -> Boolean = { point ->
            val offSheet = fits && (point.x > pageSize.width.value || point.y > pageSize.height.value)
            val onTitle = !style.hideTitle && point.y < PageStyle.TITLE_BAND_DP
            !offSheet && !onTitle
        }
        val offsetToPage: (Offset) -> InkPoint = { offset ->
            with(density) { InkPoint(offset.x.toDp().value, offset.y.toDp().value) }
        }
        val requestPasteAt: (InkPoint) -> Unit = { point ->
            if (hasClipboard && canPlaceAt(point)) pastePopupAt = point
        }

        // Provided so containers and the title read the page's colours, which may be nothing like
        // the shell's — a white page inside a dark app, or a page painted from the Page Color menu.
        CompositionLocalProvider(LocalCanvasColors provides canvas) {
            PageViewport(zoom, horizontal, vertical) {
                // One coordinate space. An outline's stored (x, y) is measured from this corner, and
                // so are the sheet, the ruling and the margin guides drawn behind it — before, the
                // content sat in a box below the title and the two disagreed by the height of the
                // header, which is why the guides never lined up with anything.
                Box(
                    Modifier
                        .size(canvasSize)
                        .testTag(PageTags.CANVAS),
                ) {
                    PageSurface(
                        sheet = sheet,
                        bound = fits,
                        colors = canvas,
                        ruleLines = style.ruleLines,
                        margins = style.margins.takeIf { showPrintMargins },
                        window = visibleWindow,
                    )

                    // Placed above the surface but beneath the containers: taps that land on a
                    // container reach its editor, and only taps on bare canvas create a new one.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(
                                pageRevision,
                                fits,
                                pageSize,
                                style.hideTitle,
                                hasClipboard,
                            ) {
                                val onTap: (Offset) -> Unit = tap@ { offset ->
                                    pastePopupAt = null
                                    // Dismissing the popup is not a tool's business, so it happens
                                    // either way; placing something is, so it does not.
                                    //
                                    // Read through the holders, never captured. This block is keyed
                                    // on page geometry and outlives every other recomposition, so a
                                    // captured `textArmed` would be frozen at whatever it was when
                                    // the page first composed — false, since the app opens with a
                                    // pen — and arming Text would then do nothing at all, for ever.
                                    val point = offsetToPage(offset)
                                    // A tap that is not about to open a container is a dismissal: it
                                    // takes the caret out of whatever holds it and puts the keyboard
                                    // away. Only the text tool is excepted, and only because the
                                    // container it is about to open wants both — hiding the keyboard
                                    // a frame before something asks for it again is worse than
                                    // leaving it up. Ahead of `canPlaceAt`, since a tap off the sheet
                                    // is still a tap away from the writing.
                                    if (!currentTextArmed.value) dismissTextInput()
                                    if (!canPlaceAt(point)) return@tap
                                    if (currentTableArmed.value) {
                                        // Selected on arrival, as an inserted shape is: the handles
                                        // are the point of it being an object. The rectangle here is
                                        // the document's own guess at its height; the measurement
                                        // corrects it on the next frame, through `tableBounds`.
                                        currentOnInsertTable.value(point.x - 8f, point.y - 8f)
                                            ?.let { id ->
                                                selection = CanvasSelection.ofTable(
                                                    TableBounds(id, InkBounds(point.x, point.y, point.x, point.y)),
                                                )
                                            }
                                        return@tap
                                    }
                                    if (!currentTextArmed.value) return@tap
                                    pendingFocusId = onCreateOutline(point.x - 8f, point.y - 8f)
                                }
                                if (hasClipboard) {
                                    detectCanvasTapGestures(
                                        onTap = onTap,
                                        onFingerDoubleTap = { requestPasteAt(offsetToPage(it)) },
                                    )
                                } else {
                                    detectTapGestures(onTap = onTap)
                                }
                            },
                    ) {
                        // A child of the tap target rather than a sibling above it, which is what
                        // orders the two: the main pass bubbles, so a child is asked first, and the
                        // detectors above wait for an *unconsumed* down. A shape therefore takes the
                        // gesture off the bare canvas, and everything the layer declines falls
                        // through to the tap that opens a text container. As siblings, hit testing
                        // stopped at whichever was on top and the other never saw the gesture at all.
                        //
                        // Inside the zoom, too — see ShapeLayer for why a shape can live in the page
                        // where ink cannot — and still beneath the text containers, so a shape drawn
                        // behind one cannot steal its caret.
                        ShapeLayer(
                            shapes = shapes,
                            selection = selection,
                            lassoGesture = lassoGesture.takeIf { lassoing },
                            // While the shape tool is armed a drag draws a new shape, and while the
                            // lasso is the overlay owns every gesture on the page — in neither case
                            // may this layer also try to edit what is under the pointer.
                            interactive = shaping == null && !lassoing,
                            // This layer's tap is what clears the page's selection (TA11), and with
                            // a table selected it is the *only* thing that hears a tap on bare
                            // canvas — it consumes the down, so the detector above never runs. The
                            // caret has to leave here too, or it stays behind in a cell whose
                            // handles have just disappeared.
                            onSelect = {
                                dismissTextInput()
                                selection = it
                            },
                            onMoveShape = onMoveShape,
                            onResizeShape = onResizeShape,
                            onResizeShapeArm = onResizeShapeArm,
                        )
                    }

                    if (!style.hideTitle) {
                        PageHeader(
                            title = title,
                            createdAt = createdAt,
                            onTitleChange = onTitleChange,
                            modifier = Modifier
                                .width(pageSize.width)
                                .padding(start = 40.dp, end = 40.dp, top = 24.dp),
                        )
                    }

                    // Between the shapes and the text containers — `docs/tablePlan.md` TA11. Compose
                    // hit-tests the last child first, so this is also the order in which the three
                    // compete for a touch: a table takes its own taps from a shape drawn under it,
                    // and a container drawn over a table keeps its caret.
                    tables.forEach { table ->
                        key(pageRevision, table.id) {
                            TableContainer(
                                table = table,
                                selected = selection?.holdsTable(table.id) == true,
                                editorStyle = editorStyle,
                                defaults = defaults,
                                initialBlocksFor = initialBlocksFor,
                                held = heldAxis?.takeIf { it.tableId == table.id },
                                onHold = { axis ->
                                    heldAxis = axis
                                    // A hold is also a selection of its table: the handles are only
                                    // reachable while it is selected, but a tap on one must not
                                    // *lose* that selection either.
                                    tableBounds.firstOrNull { it.id == table.id }
                                        ?.let { selection = CanvasSelection.ofTable(it) }
                                },
                                onCellFocused = { cellId, view ->
                                    focusedEditor = view
                                    focusedOutlineId = null
                                    focusedCellId = cellId
                                    // A caret is a different way of saying where you are, so it
                                    // takes over from a held row rather than sitting beside it.
                                    heldAxis = null
                                    lastFocusedEditor = view
                                    lastFocusedOutlineId = null
                                    // Putting a caret in a cell selects the table it belongs to —
                                    // TA11. It costs one tap where AD7's double-tap row asks for
                                    // two, and it is what raises the bar the Row and Column menus
                                    // live on.
                                    if (selection?.holdsTable(table.id) != true) {
                                        tableBounds.firstOrNull { it.id == table.id }
                                            ?.let { selection = CanvasSelection.ofTable(it) }
                                    }
                                },
                                onCellBlurred = { cellId ->
                                    if (focusedCellId == cellId) {
                                        focusedCellId = null
                                        focusedEditor = null
                                    }
                                },
                                onCellBlocksChanged = onBlocksChanged,
                                onSelectionChanged = onSelectionChanged,
                                onMarkArmed = onMarkArmed,
                                onMove = { dx, dy -> onMoveTables(setOf(table.id), dx, dy) },
                                onResize = { scaleX, scaleY ->
                                    onResizeTables(setOf(table.id), table.x, table.y, scaleX, scaleY)
                                },
                                onColumnWidth = { column, width ->
                                    onSetTableColumnWidth(table.id, column, width)
                                },
                                onRowMinHeight = { row, height ->
                                    onSetTableRowMinHeight(table.id, row, height)
                                },
                                // An ink table has no caret to select it by — TA15.
                                onSelect = {
                                    tableBounds.firstOrNull { it.id == table.id }
                                        ?.let { selection = CanvasSelection.ofTable(it) }
                                },
                                onMeasured = { heightPx -> heights[table.id] = heightPx },
                            )
                        }
                    }

                    outlines.forEach { box ->
                        key(pageRevision, box.id) {
                            OutlineContainer(
                                box = box,
                                initialBlocks = initialBlocksFor(box.id),
                                editorStyle = editorStyle,
                                defaults = defaults,
                                focused = focusedOutlineId == box.id,
                                requestFocus = pendingFocusId == box.id,
                                onFocusHandled = { pendingFocusId = null },
                                onFocused = { view ->
                                    focusedEditor = view
                                    focusedOutlineId = box.id
                                    lastFocusedEditor = view
                                    lastFocusedOutlineId = box.id
                                },
                                onBlurred = {
                                    if (focusedOutlineId == box.id) {
                                        focusedOutlineId = null
                                        focusedEditor = null
                                    }
                                    if (retainedEquationOutlineId != box.id) onOutlineBlurred(box.id)
                                },
                                onBlocksChanged = { blocks ->
                                    nonEmpty[box.id] = blocks.any { it.text.isNotBlank() }
                                    onBlocksChanged(box.id, blocks)
                                },
                                onSelectionChanged = onSelectionChanged,
                                onMarkArmed = onMarkArmed,
                                onMove = { x, y -> onMoveOutline(box.id, x, y) },
                                onResize = { width -> onResizeOutline(box.id, width) },
                                onSetMinHeight = { height -> onSetOutlineMinHeight(box.id, height) },
                                onMeasured = { heightPx -> heights[box.id] = heightPx },
                            )
                        }
                    }
                }
            }

            // Above the page, and outside the zoom — see InkOverlay for why a front-buffered
            // surface cannot be scaled by a graphics layer. It is transparent to touch unless a
            // tool is armed, so with nothing in hand a tap still reaches the canvas beneath and
            // opens a text container.
            InkOverlay(
                strokes = strokes,
                shapes = shapes,
                tables = tableBounds,
                selection = selection,
                onSelect = { selection = it },
                lassoGesture = lassoGesture,
                brush = brush,
                erasing = erasing,
                lassoing = lassoing,
                shaping = shaping,
                ruler = laidRuler,
                eraser = eraser,
                allowFinger = allowFinger,
                pageToView = {
                    // Read here rather than captured, so scrolling re-runs the draw and not the
                    // composition — the same reason PageRuling takes its window as a lambda.
                    inkPageToView(
                        zoom = zoom,
                        density = density.density,
                        scrollX = horizontal.value.toFloat(),
                        scrollY = vertical.value.toFloat(),
                    )
                },
                onStrokeFinished = onStrokeFinished,
                onInsertShape = { start, end ->
                    // Selected on arrival: the handles are the point of a shape being an object, and
                    // the insert is the only thing that knows which id it just created.
                    shaping?.let { settings ->
                        onInsertShape(settings, start.x, start.y, end.x, end.y)?.let { id ->
                            selection = shapes.firstOrNull { it.id == id }
                                ?.let(CanvasSelection::ofShape)
                        }
                    }
                },
                onPartialErase = onPartialErase,
                onObjectErase = onObjectErase,
                onMoveSelection = onMoveSelection,
                onResizeSelection = onResizeSelection,
                onMoveShapes = onMoveShapes,
                onResizeShapes = { ids, anchor, scaleX, scaleY ->
                    onResizeShapes(ids, anchor.x, anchor.y, scaleX, scaleY)
                },
                onMoveTables = onMoveTables,
                onResizeTables = { ids, anchor, scaleX, scaleY ->
                    onResizeTables(ids, anchor.x, anchor.y, scaleX, scaleY)
                },
                onDeleteSelection = onDeleteInkSelection,
                onRecolorSelection = onRecolorInkSelection,
                onGroupSelection = onGroupInkSelection,
                onUngroupSelection = onUngroupInkSelection,
                pan = remember(horizontal, vertical, scope, flingSpec) {
                    ScrollStatePan(horizontal, vertical, scope, flingSpec)
                },
                modifier = Modifier.fillMaxSize(),
                hasClipboard = hasClipboard,
                onRequestPaste = requestPasteAt,
            )

            // One bar over whatever is selected, whatever kind it is — AD7. Raised here rather than
            // inside a layer because a selection can hold both kinds, and because the bar is chrome:
            // out here it keeps its own size at any zoom and is clamped against the *window*, where a
            // bar drawn inside the zoomed page grew with it and could be clamped off-screen.
            selection?.takeIf { !it.isEmpty }?.let { held ->
                ObjectTooltip(
                    swatch = held.swatch(strokes, shapes, tables),
                    selectionBoundsInView = {
                        lassoGesture.previewBoundsInView(held, inkPageToView(
                            zoom = zoom,
                            density = density.density,
                            scrollX = horizontal.value.toFloat(),
                            scrollY = vertical.value.toFloat(),
                        ))?.also { bounds ->
                            // What the bar has to clear is the *selection*, not the geometry inside
                            // it: an arm handle stands off the top or bottom edge, and a bar placed
                            // against the edge covers it. Page units scale into view units by the
                            // same zoom × density the matrix above applies.
                            val (above, below) = shapes
                                .singleOrNull { held.isShapeOnly && it.id in held.shapeIds }
                                ?.armChromeExtent()
                                ?: (0f to 0f)
                            val scale = zoom * density.density
                            bounds.top -= above * scale
                            bounds.bottom += below * scale
                        }
                    },
                    viewportSize = with(density) {
                        IntSize(window.width.roundToPx(), window.height.roundToPx())
                    },
                    onDelete = {
                        if (held.inkIds.isNotEmpty()) onDeleteInkSelection(held.inkIds)
                        if (held.shapeIds.isNotEmpty()) onDeleteShapes(held.shapeIds)
                        if (held.tableIds.isNotEmpty()) onDeleteTables(held.tableIds)
                        selection = null
                        lassoGesture.clear()
                    },
                    onCopy = {
                        onCopySelection(held)
                        lassoGesture.clear()
                    },
                    onRecolor = { color ->
                        if (held.inkIds.isNotEmpty()) onRecolorInkSelection(held.inkIds, color)
                        if (held.shapeIds.isNotEmpty()) onRecolorShapes(held.shapeIds, color)
                        if (held.tableIds.isNotEmpty()) onRecolorTables(held.tableIds, color)
                    },
                    // The kind-specific half. Only a selection of one kind has one: over a mixed
                    // loop there is nothing both halves agree on, so the bar shows its base alone.
                    extras = {
                        if (held.isInkOnly) {
                            RecognitionAction(
                                formulaAvailable = formulaRecognitionAvailable,
                                enabled = !recognitionRunning,
                                onFormula = { onRecognizeFormula(held) },
                            )
                        }
                        if (held.isInkOnly && held.inkIds.size > 1) {
                            GroupAction(
                                isOneGroup = held.isOneInkGroup(strokes),
                                onGroup = { onGroupInkSelection(held.inkIds) },
                                onUngroup = { onUngroupInkSelection(held.inkIds) },
                            )
                        }
                        if (held.isShapeOnly) {
                            val selectedShapes = shapes.filter { it.id in held.shapeIds }
                            val widths = selectedShapes.map { it.borderWidth.roundToInt() }
                            ThicknessAction(
                                width = widths.distinct().singleOrNull()
                                    ?: ShapeSettings.MIN_BORDER_WIDTH,
                                onChange = { onSetShapeBorderWidth(held.shapeIds, it.toFloat()) },
                            )
                            LineTypeAction(
                                // A mixed selection has no one answer, so it shows the default rather
                                // than one member's — picking any type then sets all of them.
                                current = selectedShapes.map { it.lineType }.distinct().singleOrNull()
                                    ?: LineType.Solid,
                                onChange = { onSetShapeLineType(held.shapeIds, it) },
                            )
                            // Absent for a line, an arrow or an L: an open figure has no inside, and
                            // the rule for the whole bar is that an action a kind cannot perform is
                            // missing for it rather than shown and dead.
                            if (selectedShapes.any { it.canFill }) {
                                FillAction(
                                    fill = selectedShapes.map { it.fillArgb }.distinct().singleOrNull(),
                                    onChange = { onSetShapeFill(held.shapeIds, it) },
                                )
                            }
                        }
                        // The Table Class's half — `docs/tablePlan.md` TA6. The row and column
                        // actions need *one* table to act on and a place in it, so they appear for a
                        // single held table; the rules and the fill apply to any number.
                        if (held.isTableOnly) {
                            val selectedTables = tables.filter { it.id in held.tableIds }
                            ThicknessAction(
                                width = selectedTables.map { it.borderWidth.roundToInt() }
                                    .distinct().singleOrNull() ?: TableSettings.MIN_BORDER_WIDTH,
                                range = TableSettings.MIN_BORDER_WIDTH..TableSettings.MAX_BORDER_WIDTH,
                                onChange = { onSetTableBorderWidth(held.tableIds, it.toFloat()) },
                            )
                            FillAction(
                                fill = selectedTables.map { it.fillArgb }.distinct().singleOrNull(),
                                onChange = { onSetTableFill(held.tableIds, it) },
                            )
                            selectedTables.singleOrNull()?.let { table ->
                                // Where the caret is, or the edges of the table when there is none:
                                // "below" then means the bottom and "right" the far edge, so the
                                // verbs always mean something.
                                val at = focusedCellId?.let(table::locate)
                                val axis = heldAxis?.takeIf { it.tableId == table.id }
                                val row = (axis as? TableAxis.Row)?.index ?: at?.first
                                val column = (axis as? TableAxis.Column)?.index ?: at?.second

                                val insertRowBelow =
                                    { onInsertTableRow(table.id, (row ?: table.rowCount - 1) + 1) }
                                val deleteRow = {
                                    onDeleteTableRow(table.id, row ?: table.rowCount - 1)
                                    heldAxis = null
                                }
                                val insertColumnRight = {
                                    onInsertTableColumn(table.id, (column ?: table.columnCount - 1) + 1)
                                }
                                val deleteColumn = {
                                    onDeleteTableColumn(table.id, column ?: table.columnCount - 1)
                                    heldAxis = null
                                }

                                // **The bar follows what is held** — `docs/tablePlan.md` TA16, and
                                // what `table-tooltip1.jpeg` and `2` differ by. With a row or a
                                // column held there is one thing the verbs are about, so they stop
                                // needing a menu and become the Material Symbols that draw them.
                                // With nothing held there are two axes and only a caret to go on,
                                // which is what the menus are for.
                                when (axis) {
                                    is TableAxis.Row -> HeldRowActions(
                                        canDelete = table.canRemoveRow,
                                        onInsertBelow = insertRowBelow,
                                        onDelete = deleteRow,
                                    )
                                    is TableAxis.Column -> HeldColumnActions(
                                        canDelete = table.canRemoveColumn,
                                        onInsertRight = insertColumnRight,
                                        onDelete = deleteColumn,
                                    )
                                    null -> {
                                        TableRowAction(
                                            canDelete = table.canRemoveRow,
                                            onInsertBelow = insertRowBelow,
                                            onDelete = deleteRow,
                                        )
                                        TableColumnAction(
                                            canDelete = table.canRemoveColumn,
                                            onInsertRight = insertColumnRight,
                                            onDelete = deleteColumn,
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }

            // The TextBox toolkit — `docs/textBoxPlan.md` TD3. It hangs off the *focused* container
            // rather than off a selection, because TD1 declined the object-selection half of AD7:
            // there is exactly one container a bar could be about, and it is the one you are in.
            //
            // Suppressed while a canvas selection is up, so a shape's bar and a text box's bar are
            // never on screen together arguing about which object "copy" means.
            outlines
                .firstOrNull {
                    // The map only hears about text as it is *typed*; a container loaded with
                    // writing already in it never fires that callback, so the document is the
                    // fallback and the map is the live override. `OutlineContainer` seeds its own
                    // chrome from exactly the same pair.
                    it.id == focusedOutlineId &&
                        (nonEmpty[it.id] ?: initialBlocksFor(it.id).any { block -> block.text.isNotBlank() })
                }
                ?.takeIf { selection?.isEmpty != false }
                ?.let { box ->
                    ObjectTooltip(
                        // Absent, per the diagram: a text box's colour is a mark on a run, and the
                        // Home tab already owns it.
                        swatch = null,
                        selectionBoundsInView = {
                            // Page units into view pixels, the same conversion the canvas selection
                            // uses. The height is the measured one — the model stores a floor, and a
                            // bar placed against that would sit inside a container of any real size.
                            val heightPx = heights[box.id] ?: 0
                            val rect = RectF(
                                box.x,
                                box.y,
                                box.x + box.width,
                                box.y + with(density) { heightPx.toDp().value },
                            )
                            inkPageToView(
                                zoom = zoom,
                                density = density.density,
                                scrollX = horizontal.value.toFloat(),
                                scrollY = vertical.value.toFloat(),
                            ).mapRect(rect)
                            rect
                        },
                        viewportSize = with(density) {
                            IntSize(window.width.roundToPx(), window.height.roundToPx())
                        },
                        onDelete = { onDeleteOutlines(setOf(box.id)) },
                        onCopy = { onCopyOutline(box.id) },
                        // Unreachable with no swatch, and passed rather than defaulted so that
                        // deleting the colour button never silently deletes a behaviour with it.
                        onRecolor = {},
                        extras = { SelectAllAction { onCommand(FormatCommand.SelectAll) } },
                    )
                }

            pastePopupAt?.takeIf { hasClipboard }?.let { point ->
                ObjectPastePopup(
                    point = point,
                    onDismiss = { pastePopupAt = null },
                    onPaste = {
                        onPaste(point)
                        pastePopupAt = null
                    },
                )
            }
        }
    }
}

/**
 * The colour the tooltip's swatch shows: the selection's own, when it has exactly one.
 *
 * White otherwise, because a bar over a red stroke and a blue shape cannot claim either. Reads both
 * kinds through their own accessor — a stroke's colour lives on its brush, a shape's on its border —
 * which is the whole of what "the same bar over every kind" costs.
 */
private fun CanvasSelection.swatch(
    strokes: List<PageStroke>,
    shapes: List<Outline.Shape>,
    tables: List<Outline.Table>,
): Color {
    val inkColors = strokes.filter { it.id in inkIds }.map { it.stroke.brush.colorIntArgb }
    val shapeColors = shapes.filter { it.id in shapeIds }.map(Outline.Shape::borderArgb)
    val tableColors = tables.filter { it.id in tableIds }.map(Outline.Table::borderArgb)
    return (inkColors + shapeColors + tableColors).distinct().singleOrNull()?.let(::Color)
        ?: Color.White
}

/** Whether the ink held is one existing group, which is what makes the button say Ungroup. */
private fun CanvasSelection.isOneInkGroup(strokes: List<PageStroke>): Boolean {
    if (inkIds.size <= 1) return false
    val groups = strokes.filter { it.id in inkIds }.map(PageStroke::groupId).distinct()
    return groups.size == 1 && groups.single() != null
}

/**
 * The window onto the page: it pans in both directions and scales.
 *
 * Both axes scroll because a page has a size of its own — a sheet, or whatever the containers
 * occupy — and at any zoom above what fits, the rest of it still has to be reachable.
 */
@Composable
private fun PageViewport(
    zoom: Float,
    horizontal: ScrollState,
    vertical: ScrollState,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vertical),
    ) {
        Box(Modifier.horizontalScroll(horizontal)) {
            Zoomed(zoom, content)
        }
    }
}

/**
 * The writable page: its colour, its ruling, and the marks describing the sheet.
 *
 * Drawn behind the content rather than as a background on the same box, because a page bound by a
 * sheet is a rectangle of a fixed size sitting on a larger canvas — the ruling stops at the paper's
 * edge and the area beyond has to read as "off the page".
 *
 * A page the content has outgrown is the other case: every part of it is writable, so it is ruled
 * to its own edge and the sheet is reduced to a dashed outline saying where it would have ended.
 * [PageTags.SURFACE] is therefore the writable area, which is the sheet exactly when [bound].
 */
@Composable
private fun BoxScope.PageSurface(
    sheet: DpSize?,
    bound: Boolean,
    colors: CanvasColors,
    ruleLines: RuleLines,
    margins: PrintMargins?,
    window: () -> Rect,
) {
    Box(
        Modifier
            .then(if (bound && sheet != null) Modifier.size(sheet) else Modifier.matchParentSize())
            .background(colors.background)
            .then(if (bound && sheet != null) Modifier.border(1.dp, colors.ruleLine) else Modifier)
            .testTag(PageTags.SURFACE),
    ) {
        if (ruleLines != RuleLines.None) {
            PageRuling(color = colors.ruleLine, rules = ruleLines, window = window)
        }
    }

    if (sheet != null) {
        // Anchored to the page's corner rather than drawn inside the surface, so they still describe
        // the sheet on a page whose writable area has grown past it.
        Box(Modifier.size(sheet)) {
            if (!bound) SheetEdgeGuide(colors.secondaryText)
            // Nothing prints yet, so the guides are the only thing that makes a margin setting
            // observable. They are drawn only while the pane that edits them is open.
            if (margins != null) MarginGuides(margins, colors.secondaryText)
        }
    }
}

/**
 * Where the chosen sheet ends, on a page whose content has spilled past it.
 *
 * One rectangle, not a run of page breaks: it says "this is where A4 stops", which is the question
 * choosing a size asks. Nothing is clipped and the content past it is as writable as the rest.
 */
@Composable
private fun SheetEdgeGuide(color: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(PageTags.SHEET_GUIDE)
            .drawBehind {
                drawRect(
                    color = color,
                    style = Stroke(
                        width = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f)),
                    ),
                )
            },
    )
}

/** Dashed rules where the printable area would begin. */
@Composable
private fun MarginGuides(margins: PrintMargins, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        // The page is laid out at a known dp-per-inch, so a margin in inches is a distance on it.
        fun inches(value: Float) = (value * PageStyle.DP_PER_INCH).dp.toPx()

        val inset = Rect(
            left = inches(margins.leftInches),
            top = inches(margins.topInches),
            right = size.width - inches(margins.rightInches),
            bottom = size.height - inches(margins.bottomInches),
        )
        // Margins wider than the sheet leave no printable area; there is nothing to draw, and the
        // field is already flagged as out of range.
        if (inset.width <= 0f || inset.height <= 0f) return@Canvas

        drawRect(
            color = color.copy(alpha = 0.7f),
            topLeft = inset.topLeft,
            size = inset.size,
            style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
        )
    }
}

/**
 * Scales the page.
 *
 * `graphicsLayer` on its own would scale what is drawn but not the space it takes up, so a zoomed
 * page would be clipped to its old bounds and the rest could never be scrolled to. Measuring the
 * content unbounded and reporting its *scaled* size instead makes zoom a layout fact, which is what
 * the surrounding scroll containers need to see. The content keeps its own coordinate system, so
 * taps and drags inside it need no conversion.
 */
@Composable
private fun Zoomed(zoom: Float, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, _ ->
        val placeable = measurables.first().measure(Constraints())
        layout((placeable.width * zoom).roundToInt(), (placeable.height * zoom).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = zoom
                scaleY = zoom
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** Test tags for the page itself, whose geometry the View tab changes. */
internal object PageTags {
    /** The writable area — the sheet exactly when the content still fits inside one. */
    const val SURFACE = "page-surface"

    /** The dashed outline of a sheet the content has outgrown; absent while it still fits. */
    const val SHEET_GUIDE = "sheet-edge-guide"

    /** Everything that can be scrolled to, which on an unbounded page grows as you scroll. */
    const val CANVAS = "page-canvas"
    const val PASTE_MENU = "ink-paste-menu"
    const val PASTE = "ink-paste"
}

/** A touch-only double tap; mouse and stylus clicks retain the canvas's normal single-tap action. */
private suspend fun PointerInputScope.detectCanvasTapGestures(
    onTap: (Offset) -> Unit,
    onFingerDoubleTap: (Offset) -> Unit,
) {
    awaitEachGesture {
        val firstDown = awaitFirstDown()
        firstDown.consume()
        val firstUp = waitForUpOrCancellation()
        if (firstUp == null) return@awaitEachGesture
        firstUp.consume()
        if (firstDown.type != PointerType.Touch) {
            onTap(firstUp.position)
            return@awaitEachGesture
        }

        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            val earliest = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
            var candidate = awaitFirstDown()
            while (candidate.uptimeMillis < earliest) candidate = awaitFirstDown()
            candidate
        }
        if (secondDown == null || secondDown.type != PointerType.Touch) {
            onTap(firstUp.position)
            return@awaitEachGesture
        }
        secondDown.consume()
        val secondUp = waitForUpOrCancellation()
        if (secondUp == null) {
            onTap(firstUp.position)
            return@awaitEachGesture
        }
        secondUp.consume()
        onFingerDoubleTap(secondUp.position)
    }
}

@Composable
private fun BoxScope.ObjectPastePopup(
    point: InkPoint,
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
) {
    Box(
        Modifier
            .offset(x = point.x.dp, y = point.y.dp)
            .size(1.dp),
    ) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag(PageTags.PASTE_MENU),
        ) {
            DropdownMenuItem(
                text = { Text("Paste") },
                leadingIcon = { Icon(MaterialSymbols.ContentPaste, contentDescription = null) },
                onClick = onPaste,
                modifier = Modifier.testTag(PageTags.PASTE),
            )
        }
    }
}

/** Test tags for the container's drag targets. */
internal object OutlineTags {
    /** The container's own box, whose top-left *is* the outline's stored coordinate. */
    const val CONTAINER = "outline-container"
    const val MOVE = "outline-move-handle"
    const val RESIZE_WIDTH = "outline-width-handle"
    const val RESIZE_HEIGHT = "outline-height-handle"
    const val EDITOR = "outline-editor"
}

@Composable
internal fun OutlineContainer(
    box: OutlineBox,
    initialBlocks: List<Block>,
    editorStyle: EditorStyle,
    /** Defaulted so the container can be exercised in isolation without a preferences store. */
    defaults: EditorDefaults = EditorDefaults(),
    focused: Boolean,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onFocused: (OutlineEditText) -> Unit,
    onBlurred: () -> Unit,
    onBlocksChanged: (List<Block>) -> Unit,
    onSelectionChanged: (SelectionState) -> Unit,
    onMarkArmed: (Mark) -> Unit = {},
    onMove: (Float, Float) -> Unit,
    onResize: (Float) -> Unit,
    onSetMinHeight: (Float) -> Unit,
    onMeasured: (Int) -> Unit,
) {
    val canvas = LocalCanvasColors.current
    val density = LocalDensity.current
    val outline = MaterialTheme.colorScheme.primary

    // Drag callbacks live for the lifetime of the pointerInput, so they must read current
    // geometry rather than the values captured when the gesture started.
    val current by rememberUpdatedState(box)
    var view by remember { mutableStateOf<OutlineEditText?>(null) }
    var hasContent by remember { mutableStateOf(initialBlocks.any { it.text.isNotBlank() }) }
    /** Height of the text area alone, used as the baseline the first time it is dragged. */
    var textHeightDp by remember { mutableStateOf(0f) }

    // An empty container is just a caret position, so it shows no chrome at all. Otherwise
    // tapping around the page would litter it with empty rectangles.
    val showChrome = focused && hasContent

    LaunchedEffect(requestFocus, view) {
        if (requestFocus && view != null) {
            view?.requestFocus()
            onFocusHandled()
        }
    }

    Box(
        modifier = Modifier
            .offset(x = box.x.dp, y = box.y.dp)
            .width(box.width.dp + RESIZE_HANDLE_WIDTH)
            .onSizeChanged { onMeasured(it.height) }
            .testTag(OutlineTags.CONTAINER),
    ) {
        Column(Modifier.width(box.width.dp)) {
            // Reserved strip for the move grip. It cannot be floated above the container with a
            // negative offset: a container at the top of the page would put its grip off-canvas
            // where it can never be grabbed.
            Spacer(Modifier.height(GRIP_HEIGHT))

            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged {
                        textHeightDp = with(density) { it.height.toDp().value }
                    },
            ) {
                NoteEditor(
                    initialBlocks = initialBlocks,
                    editorStyle = editorStyle,
                    defaults = defaults,
                    minHeightPx = with(density) { box.minHeight.dp.roundToPx() },
                    onFocused = onFocused,
                    onBlurred = onBlurred,
                    onBlocksChanged = { blocks ->
                        hasContent = blocks.any { it.text.isNotBlank() }
                        onBlocksChanged(blocks)
                    },
                    onSelectionChanged = onSelectionChanged,
                    onMarkArmed = onMarkArmed,
                    onViewCreated = { view = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OutlineTags.EDITOR)
                        // Transparent rather than absent so showing the border never shifts text.
                        .border(
                            width = 1.dp,
                            color = if (showChrome) outline.copy(alpha = 0.55f) else Color.Transparent,
                        )
                        .padding(6.dp),
                )
            }

            // Reserved strip for the bottom resize handle, mirroring the spare width kept on the
            // right. The handle cannot simply be overlaid on the editor: that is a real Android
            // View and it consumes the touch, so the drag gesture would never fire. Always
            // present, so focusing a container does not shift it.
            Spacer(Modifier.height(RESIZE_HANDLE_WIDTH))
        }

        if (showChrome) {
            // All three handles are overlays inside a matchParentSize box rather than children of
            // the Column.
            //
            // matchParentSize, not fillMaxHeight: the container's height comes from its children
            // and the canvas's height comes from the container, so a child that sizes itself from
            // the incoming height constraint feeds back into the canvas and grows every frame.
            // matchParentSize measures against the parent without contributing to it. Overlaying
            // also means chrome appearing on focus never reflows the text.
            Box(modifier = Modifier.matchParentSize()) {

                // Move: a grip bar floated above the container's top edge.
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .width(box.width.dp)
                        .height(GRIP_HEIGHT)
                        .testTag(OutlineTags.MOVE)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(outline.copy(alpha = 0.24f))
                        .pointerInput(box.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dx = with(density) { dragAmount.x.toDp().value }
                                val dy = with(density) { dragAmount.y.toDp().value }
                                onMove(current.x + dx, current.y + dy)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(outline.copy(alpha = 0.85f)),
                    )
                }

                // Width: right edge.
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(RESIZE_HANDLE_WIDTH)
                        .fillMaxHeight()
                        .testTag(OutlineTags.RESIZE_WIDTH)
                        .pointerInput(box.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dx = with(density) { dragAmount.x.toDp().value }
                                onResize(current.width + dx)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(outline.copy(alpha = 0.85f)),
                    )
                }

                // Height: bottom edge. Sets a floor rather than a fixed height, so dragging it
                // shorter than the text can never clip what the user wrote.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .width(box.width.dp)
                        .height(RESIZE_HANDLE_WIDTH)
                        .testTag(OutlineTags.RESIZE_HEIGHT)
                        .pointerInput(box.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dy = with(density) { dragAmount.y.toDp().value }
                                // Start from the height the text actually occupies, so the first
                                // drag frame continues from where the box is rather than from 0.
                                val base = if (current.minHeight > 0f) current.minHeight else textHeightDp
                                onSetMinHeight(base + dy)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(34.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(outline.copy(alpha = 0.85f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageHeader(
    title: String,
    createdAt: Long,
    onTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canvas = LocalCanvasColors.current
    Column(modifier) {
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            textStyle = TextStyle(
                color = canvas.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.Normal,
            ),
            cursorBrush = SolidColor(canvas.text),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (title.isEmpty()) {
                        Text(
                            text = "Untitled page",
                            style = LocalTextStyle.current.copy(
                                color = canvas.secondaryText,
                                fontSize = 26.sp,
                            ),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp),
        )

        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(canvas.ruleLine),
        )
        Spacer(Modifier.height(6.dp))

        Text(
            text = formatCreated(createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = canvas.secondaryText,
        )
    }
}

/**
 * The ruled, squared, or dotted page background from the View menu.
 *
 * Spacing comes from the document, so a page ruled on a tablet is ruled identically on a phone —
 * the lines are part of the page, not of the window it is being read in.
 *
 * Only the lines the window can show are drawn. Ruling the whole page instead cost one line per
 * step of its *height*, which on a page that extends as you scroll has no upper bound; this is
 * bounded by the screen instead, whatever the page has grown to. [window] is called inside the draw
 * scope on purpose — that is what keeps the scroll position off the composition path, so scrolling
 * re-runs this lambda and nothing above it.
 */
@Composable
private fun PageRuling(color: Color, rules: RuleLines, window: () -> Rect) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stepPx = rules.spacingDp.dp.toPx()
        if (stepPx <= 0f) return@Canvas
        val visible = window().intersect(Rect(Offset.Zero, size))
        if (visible.isEmpty) return@Canvas

        if (rules.hexagonal) {
            val side = stepPx
            val hexHeight = side * HEXAGON_HEIGHT_RATIO
            val columnStep = side * 1.5f
            val firstColumn = floor((visible.left - side * 2f) / columnStep)
                .toInt()
                .coerceAtLeast(0)
            val lastColumn = ceil((visible.right + side) / columnStep).toInt()
            val hexagons = Path()

            for (column in firstColumn..lastColumn) {
                val centerX = side + column * columnStep
                val columnOffset = if (column % 2 == 0) 0f else hexHeight / 2f
                val firstCenterY = hexHeight / 2f + columnOffset
                val firstRow = floor(
                    (visible.top - firstCenterY - hexHeight / 2f) / hexHeight,
                ).toInt().coerceAtLeast(0)
                val lastRow = ceil(
                    (visible.bottom - firstCenterY + hexHeight / 2f) / hexHeight,
                ).toInt()

                for (row in firstRow..lastRow) {
                    val centerY = firstCenterY + row * hexHeight
                    hexagons.moveTo(centerX + side, centerY)
                    hexagons.lineTo(centerX + side / 2f, centerY + hexHeight / 2f)
                    hexagons.lineTo(centerX - side / 2f, centerY + hexHeight / 2f)
                    hexagons.lineTo(centerX - side, centerY)
                    hexagons.lineTo(centerX - side / 2f, centerY - hexHeight / 2f)
                    hexagons.lineTo(centerX + side / 2f, centerY - hexHeight / 2f)
                    hexagons.close()
                }
            }
            drawPath(
                path = hexagons,
                color = color.copy(alpha = color.alpha * HEXAGON_RULE_ALPHA),
                style = Stroke(width = 1f),
            )
            return@Canvas
        }

        if (rules.dotted) {
            val radius = DOTTED_RULE_RADIUS_DP.dp.toPx()
            var y = maxOf(stepPx, ceil(visible.top / stepPx) * stepPx)
            while (y < visible.bottom) {
                var x = maxOf(stepPx, ceil(visible.left / stepPx) * stepPx)
                while (x < visible.right) {
                    drawCircle(color = color, radius = radius, center = Offset(x, y))
                    x += stepPx
                }
                y += stepPx
            }
            return@Canvas
        }

        // Snapped to the ruling's own grid, not to the window, so the lines stay where the page puts
        // them however far it has been scrolled. The first line is a whole step in, as it always was.
        var y = maxOf(stepPx, ceil(visible.top / stepPx) * stepPx)
        while (y < visible.bottom) {
            drawLine(color, Offset(visible.left, y), Offset(visible.right, y), strokeWidth = 1f)
            y += stepPx
        }
        if (!rules.squared) return@Canvas
        var x = maxOf(stepPx, ceil(visible.left / stepPx) * stepPx)
        while (x < visible.right) {
            drawLine(color, Offset(x, visible.top), Offset(x, visible.bottom), strokeWidth = 1f)
            x += stepPx
        }
    }
}

private const val DOTTED_RULE_RADIUS_DP = 0.8f
private const val HEXAGON_HEIGHT_RATIO = 1.7320508f
private const val HEXAGON_RULE_ALPHA = 0.5f

private val createdFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
private val createdTimeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

private fun formatCreated(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val date = Date(timestamp)
    return "${createdFormat.format(date)}    ${createdTimeFormat.format(date)}"
}

/**
 * Pans the page by driving its two scroll states directly.
 *
 * [ScrollState.dispatchRawDelta] rather than an animation while the finger is down, so the page
 * tracks it exactly; the fling afterwards is a decay animation feeding the same method, which is
 * how the platform's own scrollables do it.
 */
private class ScrollStatePan(
    private val horizontal: ScrollState,
    private val vertical: ScrollState,
    private val scope: CoroutineScope,
    private val flingSpec: DecayAnimationSpec<Float>,
) : CanvasPan {

    override fun by(dx: Float, dy: Float) {
        horizontal.dispatchRawDelta(dx)
        vertical.dispatchRawDelta(dy)
    }

    override fun fling(vx: Float, vy: Float) {
        decay(horizontal, vx)
        decay(vertical, vy)
    }

    private fun decay(state: ScrollState, velocity: Float) {
        // Below this a "fling" is just the noise at the end of a deliberate drag, and animating it
        // makes the page drift after the finger has stopped.
        if (kotlin.math.abs(velocity) < MIN_FLING_VELOCITY) return
        scope.launch {
            var last = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(flingSpec) {
                state.dispatchRawDelta(value - last)
                last = value
            }
        }
    }

    private companion object {
        const val MIN_FLING_VELOCITY = 50f
    }
}
