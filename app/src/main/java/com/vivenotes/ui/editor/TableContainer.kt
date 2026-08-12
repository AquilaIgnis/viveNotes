package com.vivenotes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivenotes.data.EditorDefaults
import com.vivenotes.ink.PageBounds
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.richtext.EditorStyle
import com.vivenotes.richtext.OutlineEditText
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.data.automaticColorOr
import com.vivenotes.ui.theme.LocalCanvasColors

/**
 * How much of the table's box is reserved above and to the left of the grid, for the handles.
 *
 * Reserved always, not only while the table is selected — the same thing `OutlineContainer` does for
 * its grip strip, and for the same two reasons: a table at the very top of the page would otherwise
 * put its handles off-canvas where they can never be grabbed, and chrome that appears on selection
 * must not shift the page under the finger that selected it.
 *
 * The document's `(x, y)` is therefore the corner of the *box*, and the grid starts this far in.
 */
internal val TABLE_GUTTER: Dp = 16.dp

/**
 * One row or one column of one table, held — `docs/tablePlan.md` TA16.
 *
 * **Not a `CanvasSelection`.** That one holds *objects on the page*, across kinds; this is a place
 * inside a single object, and it exists for one reason: so that Insert below and Delete row know
 * which row they mean without a caret. The two coexist — holding a column implies its table is
 * selected, which is what raises the bar the buttons live on.
 *
 * Transient UI state, held by `EditorPane` beside `focusedCellId` and never persisted: where you are
 * in a table is not a property of the table.
 */
internal sealed interface TableAxis {
    val tableId: String
    val index: Int

    data class Row(override val tableId: String, override val index: Int) : TableAxis
    data class Column(override val tableId: String, override val index: Int) : TableAxis
}

/** Test tags for a table's parts, which are geometry and show no text of their own. */
internal object TableTags {
    const val CONTAINER = "table-container"
    const val GRID = "table-grid"
    const val MOVE = "table-move-handle"
    const val RESIZE = "table-resize-handle"

    fun cell(cellId: String) = "table-cell-$cellId"
    fun columnHandle(index: Int) = "table-column-handle-$index"
    fun rowHandle(index: Int) = "table-row-handle-$index"
}

/**
 * One table on the page — `docs/tablePlan.md`.
 *
 * **A composable rather than a layer, unlike `ShapeLayer`.** A shape is a vector and can be drawn by
 * one canvas for the whole page; a table is a grid of real `EditText`s, so it has to be composed. That
 * one fact decides most of what follows.
 *
 * The grid's **lines are drawn behind the cells**, from the column widths and the row heights the
 * layout reports, rather than as a border on each cell. Cells would have had to be stretched to their
 * row's height for that to work, which means intrinsic measurement across an `AndroidView`; drawing
 * behind needs none of it, gives every rule one width and one colour, and puts the header tint and
 * the fill in the same place as the lines that cross them.
 *
 * **Nothing draggable is ever laid over a cell** (TA5). Every handle is in the reserved gutters or
 * outside the grid entirely, because a cell is an `EditText` and a handle on top of one either eats
 * the drag or loses the caret — the lesson `OutlineContainer` already records about its bottom edge.
 */
@Composable
internal fun TableContainer(
    table: Outline.Table,
    selected: Boolean,
    editorStyle: EditorStyle,
    defaults: EditorDefaults = EditorDefaults(),
    initialBlocksFor: (String) -> List<Block>,
    onCellFocused: (String, OutlineEditText) -> Unit,
    onCellBlurred: (String) -> Unit,
    onCellBlocksChanged: (String, List<Block>) -> Unit,
    onSelectionChanged: (SelectionState) -> Unit,
    onMarkArmed: (Mark) -> Unit = {},
    /** The whole travel of one drag, reported on the lift — see [onResize]. */
    onMove: (dx: Float, dy: Float) -> Unit,
    /**
     * A scale about the table's top-left corner, reported **once, on the lift**.
     *
     * Absolute against the geometry the drag started with, so applying it per frame would multiply a
     * drag's own scales together — the bug `ShapeLayer` records in full. The frames in between are
     * drawn from the preview held here and nothing is written until the finger leaves.
     */
    onResize: (scaleX: Float, scaleY: Float) -> Unit,
    onColumnWidth: (column: Int, width: Float) -> Unit,
    onRowMinHeight: (row: Int, minHeight: Float) -> Unit,
    /**
     * The row or column currently held, if it belongs to this table — TA16.
     *
     * Drawn as a band across the grid and a filled handle, so what the bar's row and column verbs
     * are about is visible rather than remembered.
     */
    held: TableAxis? = null,
    /** A tap on a gutter handle. Null clears the hold, which a second tap on the same handle does. */
    onHold: (TableAxis?) -> Unit = {},
    /**
     * A tap on the grid, for an [Outline.Table.inkOnly] one only — TA15.
     *
     * A table of text fields needs no such thing: putting a caret in a cell is what selects it. A
     * ruling has no cell to put a caret in, so without this there would be no way to reach its
     * toolkit short of drawing a lasso round it.
     */
    onSelect: () -> Unit = {},
    onMeasured: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface
    val canvas = LocalCanvasColors.current

    // Drag callbacks outlive the gesture that started them, so they read current geometry rather
    // than what was captured when the finger went down.
    val current by rememberUpdatedState(table)

    /** The live move and scale, drawn but not written — see [onResize]. */
    var travel by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf<Offset?>(null) }

    /** What each row actually measured to, which is what the rules are drawn from. */
    val rowHeights = remember(table.id) { mutableStateMapOf<Int, Int>() }

    val scaling = scale
    val live = when {
        scaling != null -> table.scaledAbout(table.x, table.y, scaling.x, scaling.y)
        travel != Offset.Zero -> table.translated(travel.x, travel.y)
        else -> table
    }

    /** The grid's own height: what the rows measured to, with their floors standing in until they do. */
    val gridHeight = with(density) {
        live.rows.indices
            .sumOf { index -> rowHeights[index] ?: live.rows[index].minHeight.dp.roundToPx() }
            .toDp()
    }

    Box(
        modifier = Modifier
            .offset(x = live.x.dp, y = live.y.dp)
            .testTag(TableTags.CONTAINER)
            .onSizeChanged { onMeasured(it.height) },
    ) {
        Column {
            // The top gutter: one handle per interior column boundary, and the move grip in the
            // square where the two gutters meet.
            Row {
                Box(
                    modifier = Modifier
                        .size(TABLE_GUTTER)
                        .then(if (selected) Modifier.testTag(TableTags.MOVE) else Modifier)
                        .then(
                            if (!selected) {
                                Modifier
                            } else {
                                Modifier
                                    .clip(RoundedCornerShape(topStart = 4.dp))
                                    .background(accent)
                                    .pointerInputMove(
                                        onDrag = { dx, dy ->
                                            // Accumulated *through* the wall rather than against
                                            // it: the origin corner is a hard edge — [PageBounds] —
                                            // and clamping each frame's total, rather than each
                                            // frame's delta, is what lets a drag that overshot the
                                            // corner come back out of it instead of having thrown
                                            // the overshoot away.
                                            travel = PageBounds.clampTranslation(
                                                current.x,
                                                current.y,
                                                travel.x + dx,
                                                travel.y + dy,
                                            ).let { Offset(it.x, it.y) }
                                        },
                                        onEnd = {
                                            if (travel != Offset.Zero) onMove(travel.x, travel.y)
                                            travel = Offset.Zero
                                        },
                                        density = density.density,
                                    )
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // The grip says what it is rather than being a plain square: the six-dot drag
                    // indicator is the platform's own word for "take hold of this and move it", and
                    // `docs/references/table-tooltip1.jpeg` puts the same dotted handle in the same
                    // corner. Tinted onto the accent block, so it reads at 16dp.
                    if (selected) {
                        Icon(
                            imageVector = MaterialSymbols.DragIndicator,
                            contentDescription = "Move table",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(TABLE_GUTTER - 2.dp),
                        )
                    }
                }
                live.columns.forEachIndexed { index, width ->
                    Box(Modifier.width(width.dp).height(TABLE_GUTTER)) {
                        if (selected) {
                            ColumnHandle(
                                index = index,
                                accent = accent,
                                held = (held as? TableAxis.Column)?.index == index,
                                onDrag = { dx ->
                                    onColumnWidth(index, current.columns[index] + dx)
                                },
                                onTap = {
                                    val already = (held as? TableAxis.Column)?.index == index
                                    onHold(if (already) null else TableAxis.Column(table.id, index))
                                },
                            )
                        }
                    }
                }
            }

            Row {
                // The left gutter: one handle per interior row boundary.
                Column(Modifier.width(TABLE_GUTTER)) {
                    live.rows.forEachIndexed { index, row ->
                        val measured = rowHeights[index]
                        val height = measured?.let { with(density) { it.toDp() } } ?: row.minHeight.dp
                        Box(Modifier.width(TABLE_GUTTER).height(height)) {
                            if (selected) {
                                RowHandle(
                                    index = index,
                                    accent = accent,
                                    held = (held as? TableAxis.Row)?.index == index,
                                    onDrag = { dy ->
                                        // From the height the row actually occupies, so the first
                                        // frame continues from where the row is rather than from
                                        // its floor — which may be far above the text in it.
                                        val base = measured
                                            ?.let { with(density) { it.toDp().value } }
                                            ?: current.rows[index].minHeight
                                        onRowMinHeight(index, base + dy)
                                    },
                                    onTap = {
                                        val already = (held as? TableAxis.Row)?.index == index
                                        onHold(if (already) null else TableAxis.Row(table.id, index))
                                    },
                                )
                            }
                        }
                    }
                }

                TableGrid(
                    table = live,
                    editorStyle = editorStyle,
                    defaults = defaults,
                    initialBlocksFor = initialBlocksFor,
                    onCellFocused = onCellFocused,
                    onCellBlurred = onCellBlurred,
                    onCellBlocksChanged = onCellBlocksChanged,
                    onSelectionChanged = onSelectionChanged,
                    onMarkArmed = onMarkArmed,
                    rowHeightPx = { index -> rowHeights[index] },
                    onRowMeasured = { index, height -> rowHeights[index] = height },
                    onTap = onSelect.takeIf { table.inkOnly },
                    heldRow = (held as? TableAxis.Row)?.index,
                    heldColumn = (held as? TableAxis.Column)?.index,
                    heldTint = accent.copy(alpha = HELD_TINT_ALPHA),
                    // A ruling drawn with the automatic border follows the page it is on, as the
                    // text in its cells already does — see `automaticColorOr`.
                    ruleColor = Color(
                        automaticColorOr(
                            live.borderArgb,
                            live.borderFollowsTheme,
                            canvas.text.toArgb(),
                        ),
                    ),
                    ruleWidth = live.borderWidth,
                    fill = live.fillArgb?.let(::Color),
                    headerTint = canvas.text.copy(alpha = HEADER_TINT_ALPHA),
                )
            }
        }

        if (selected) {
            SelectionOutline(
                accent = accent,
                left = TABLE_GUTTER,
                top = TABLE_GUTTER,
                width = live.width.dp,
                height = gridHeight,
            )

            // **One scale handle, at the bottom right** — TA4, and what
            // `docs/references/table-tooltip1.jpeg` shows. A table's top and left edges carry the
            // row and column gutters, so a handle at either of those corners would sit within a
            // finger's width of a handle that does something else entirely. The gesture is the
            // shape's own: drag to scale, anchored at the opposite corner.
            Box(
                Modifier
                    .offset(
                        x = TABLE_GUTTER + live.width.dp - HANDLE_RADIUS,
                        y = TABLE_GUTTER + gridHeight - HANDLE_RADIUS,
                    )
                    .size(HANDLE_RADIUS * 2)
                    .testTag(TableTags.RESIZE)
                    .clip(RoundedCornerShape(50))
                    .background(handleFill)
                    .drawBehind {
                        drawCircle(accent, size.minDimension / 2f, style = Stroke(width = 1.5.dp.toPx()))
                    }
                    .pointerInputScale(
                        widthDp = { current.width },
                        heightDp = { gridHeight.value },
                        onScale = { x, y -> scale = Offset(x, y) },
                        onEnd = {
                            scale?.let { onResize(it.x, it.y) }
                            scale = null
                        },
                        density = density.density,
                    ),
            )
        }
    }
}

/**
 * The cells, and the rules drawn behind them.
 *
 * One `drawBehind` for the whole grid rather than a border per cell: it is what makes every rule the
 * same width whatever meets it, and it is the only way to lay the header tint under lines that cross
 * it. The row heights it draws from are reported by the rows themselves, so on the very first frame —
 * before any of them has measured — the floors stand in and the next frame corrects it.
 */
@Composable
private fun TableGrid(
    table: Outline.Table,
    editorStyle: EditorStyle,
    defaults: EditorDefaults,
    initialBlocksFor: (String) -> List<Block>,
    onCellFocused: (String, OutlineEditText) -> Unit,
    onCellBlurred: (String) -> Unit,
    onCellBlocksChanged: (String, List<Block>) -> Unit,
    onSelectionChanged: (SelectionState) -> Unit,
    onMarkArmed: (Mark) -> Unit,
    /** What a row measured to, or null on the frame before it has. */
    rowHeightPx: (Int) -> Int?,
    onRowMeasured: (Int, Int) -> Unit,
    /** Selects an ink table. Null for one made of editors, which selects by taking a caret. */
    onTap: (() -> Unit)?,
    /** The held row or column, banded across the grid so the bar's verbs have a visible subject. */
    heldRow: Int?,
    heldColumn: Int?,
    heldTint: Color,
    ruleColor: Color,
    ruleWidth: Float,
    fill: Color?,
    headerTint: Color,
) {
    /**
     * Every cell's editor, so Tab can put the caret in the next one — `docs/tablePlan.md` TA17.
     *
     * Held here because this is the one place that knows both halves: the grid says which cell comes
     * next, and only the composition that made the editors can reach the one that renders it. The
     * alternative — hoisting a "focus this cell" flag up to `EditorPane` and waiting a frame for it
     * to come back down — turns one keystroke into a recomposition, and a key press has to be
     * answered before the next character arrives.
     *
     * Entries are removed as their cells leave, so a deleted column takes its Views with it.
     */
    val cellEditors = remember(table.id) { mutableStateMapOf<String, OutlineEditText>() }

    // Read through a holder: the callback below is assigned to a View that outlives the composition
    // that built it, and a captured grid would still be the one the cell was created in.
    val currentTable = rememberUpdatedState(table)

    Column(
        Modifier
            .width(table.width.dp)
            .testTag(TableTags.GRID)
            .then(if (onTap == null) Modifier else Modifier.selectOnTap(onTap))
            .drawBehind {
                fill?.let(::drawRect)

                // Where each row actually starts. Measured, with the floor standing in on the one
                // frame before a row has reported itself — the next frame corrects it.
                var y = 0f
                val rowTops = FloatArray(table.rowCount + 1)
                table.rows.forEachIndexed { index, row ->
                    rowTops[index] = y
                    y += rowHeightPx(index)?.toFloat() ?: row.minHeight.dp.toPx()
                }
                rowTops[table.rowCount] = size.height

                // Headers first, so the rules cross them rather than sit under them.
                if (table.headerRow && table.rowCount > 0) {
                    drawRect(headerTint, Offset.Zero, Size(size.width, rowTops[1]))
                }
                if (table.headerColumn && table.columnCount > 0) {
                    drawRect(headerTint, Offset.Zero, Size(table.columns[0].dp.toPx(), size.height))
                }

                // The held band, over the header tint and under the rules — TA16. Painted here
                // rather than as a box over the cells for the reason the rules are: a band drawn on
                // top of a text cell would grey out the writing it is meant to point at.
                heldRow?.takeIf { it in table.rows.indices }?.let { index ->
                    drawRect(
                        heldTint,
                        Offset(0f, rowTops[index]),
                        Size(size.width, rowTops[index + 1] - rowTops[index]),
                    )
                }
                heldColumn?.takeIf { it in table.columns.indices }?.let { index ->
                    val left = table.columns.take(index).sumOf { it.toDouble() }.toFloat().dp.toPx()
                    drawRect(
                        heldTint,
                        Offset(left, 0f),
                        Size(table.columns[index].dp.toPx(), size.height),
                    )
                }

                val stroke = ruleWidth.dp.toPx().coerceAtLeast(1f)
                var x = 0f
                for (index in 0..table.columnCount) {
                    // Half a stroke in at each end, so the outer rules are not clipped in half by
                    // the edge of the box they bound.
                    val at = x.coerceIn(stroke / 2f, size.width - stroke / 2f)
                    drawLine(ruleColor, Offset(at, 0f), Offset(at, size.height), strokeWidth = stroke)
                    if (index < table.columnCount) x += table.columns[index].dp.toPx()
                }
                for (index in 0..table.rowCount) {
                    val at = rowTops[index].coerceIn(stroke / 2f, size.height - stroke / 2f)
                    drawLine(ruleColor, Offset(0f, at), Offset(size.width, at), strokeWidth = stroke)
                }
            },
    ) {
        table.rows.forEachIndexed { rowIndex, row ->
            Row(
                Modifier
                    .heightIn(min = row.minHeight.dp)
                    .onSizeChanged { onRowMeasured(rowIndex, it.height) },
            ) {
                row.cells.forEachIndexed { columnIndex, cell ->
                    // Keyed by the cell rather than by its position, so that removing a column can
                    // never hand one cell's editor — and the text in it — to the cell that shifted
                    // into its place.
                    key(cell.id) {
                        Box(
                            Modifier
                                .width(table.columns.getOrElse(columnIndex) { 0f }.dp)
                                .padding(CELL_PADDING),
                        ) {
                            if (table.inkOnly) {
                                // **Empty space, and empty on purpose** — `docs/tablePlan.md` TA15.
                                //
                                // Not a disabled editor, not a read-only one: nothing at all. A cell
                                // with any pointer input in it consumes the touch, and the whole
                                // point of this table is that a pen reaches the page through it. The
                                // box is here only to hold the row open to its column's width.
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag(TableTags.cell(cell.id)),
                                )
                            } else {
                                DisposableEffect(cell.id) {
                                    onDispose { cellEditors.remove(cell.id) }
                                }
                                NoteEditor(
                                    initialBlocks = initialBlocksFor(cell.id),
                                    editorStyle = editorStyle,
                                    defaults = defaults,
                                    onFocused = { editor -> onCellFocused(cell.id, editor) },
                                    onBlurred = { onCellBlurred(cell.id) },
                                    onBlocksChanged = { onCellBlocksChanged(cell.id, it) },
                                    onSelectionChanged = onSelectionChanged,
                                    onMarkArmed = onMarkArmed,
                                    onTabNavigate = { forward ->
                                        moveCaret(currentTable.value, cellEditors, cell.id, forward)
                                    },
                                    onViewCreated = { editor -> cellEditors[cell.id] = editor },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag(TableTags.cell(cell.id)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab's whole behaviour inside a grid — `docs/tablePlan.md` TA17.
 *
 * The caret lands at the *end* of the destination's text rather than selecting it. Selecting is what
 * a spreadsheet does, because there a cell holds one value that Tab is usually about to replace; this
 * is a note, the cell holds prose, and arriving with everything highlighted means the next keystroke
 * silently deletes a sentence.
 *
 * Returns false when there is nowhere to go — the last cell going forward, the first coming back, or
 * a destination whose editor has not been composed — which is what leaves Tab as the indent it is
 * everywhere else in the app.
 */
private fun moveCaret(
    table: Outline.Table,
    editors: Map<String, OutlineEditText>,
    from: String,
    forward: Boolean,
): Boolean {
    val to = if (forward) table.cellAfter(from) else table.cellBefore(from)
    val editor = to?.let(editors::get) ?: return false
    if (!editor.requestFocus()) return false
    editor.setSelection(editor.text?.length ?: 0)
    return true
}

/** A dashed box around the selected table, drawn the way a shape's selection is drawn (AD7). */
@Composable
private fun SelectionOutline(accent: Color, left: Dp, top: Dp, width: Dp, height: Dp) {
    Box(
        Modifier
            .offset(x = left, y = top)
            .width(width)
            .height(height)
            .drawBehind {
                drawRect(
                    color = accent,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                        ),
                    ),
                )
            },
    )
}

/**
 * One column's width handle: a bar lying across the direction it drags in.
 *
 * **Two gestures on one target** — `docs/tablePlan.md` TA16. Dragging it sets the column's width,
 * which is what it has always done; *tapping* it holds that column, which is what makes Insert right
 * and Delete column mean something without a caret. The two cannot collide: one has travelled and
 * the other has not.
 *
 * A held handle is filled rather than tinted, and the column it names is banded across the grid — a
 * hold nobody can see is a hold nobody acts on with confidence.
 */
@Composable
private fun ColumnHandle(
    index: Int,
    accent: Color,
    held: Boolean,
    onDrag: (Float) -> Unit,
    onTap: () -> Unit,
) {
    val density = LocalDensity.current
    // Read through a holder, never captured: `pointerInput` is keyed on nothing that changes, so the
    // handler built on the first composition is the one that runs for ever — and a captured `onTap`
    // would still be deciding the toggle against the hold as it was when the table first appeared.
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .fillMaxSize()
            .testTag(TableTags.columnHandle(index))
            .padding(horizontal = 1.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(accent.copy(alpha = if (held) 1f else 0.3f))
            .pointerInputAxis(
                horizontal = true,
                density = density.density,
                onDrag = onDrag,
                onTap = { currentOnTap() },
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Spacer(
            Modifier
                .width(3.dp)
                .height(TABLE_GUTTER - 6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(HANDLE_KNOB),
        )
    }
}

/** One row's floor handle, the same bar turned through a right angle, and held the same way. */
@Composable
private fun RowHandle(
    index: Int,
    accent: Color,
    held: Boolean,
    onDrag: (Float) -> Unit,
    onTap: () -> Unit,
) {
    val density = LocalDensity.current
    /** Through a holder for the reason [ColumnHandle] gives, which is the toggle's whole correctness. */
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .fillMaxSize()
            .testTag(TableTags.rowHandle(index))
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(accent.copy(alpha = if (held) 1f else 0.3f))
            .pointerInputAxis(
                horizontal = false,
                density = density.density,
                onDrag = onDrag,
                onTap = { currentOnTap() },
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Spacer(
            Modifier
                .height(3.dp)
                .width(TABLE_GUTTER - 6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(HANDLE_KNOB),
        )
    }
}

/**
 * A tap that selects, and a drag that is left entirely alone — TA15.
 *
 * **Consumes the up, never the down**, which is the whole of why this is hand-written rather than a
 * `detectTapGestures`. That one claims the down, and a claimed down is one the scroll containers
 * around the page never see: dragging from inside an ink table would stop panning the page, and since
 * a table declines the body-drag (TA4) the gesture would do nothing at all — a dead rectangle in the
 * middle of the canvas.
 *
 * Consuming the up is still enough to keep the tap: the bare-canvas tap detector is a *lower sibling*
 * of this, so it is dispatched to second, and its `waitForUpOrCancellation` gives up on a consumed
 * one. Tapping a ruling therefore selects it rather than also opening a text container on top of it.
 *
 * A press that never travelled is a tap, whatever it landed on — `ShapeLayer`'s rule, and its phrase.
 */
private fun Modifier.selectOnTap(onTap: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val slop = viewConfiguration.touchSlop
            var travelled = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) travelled = true
                if (!change.pressed) {
                    if (!travelled) {
                        change.consume()
                        onTap()
                    }
                    break
                }
            }
        }
    },
)

/**
 * A drag on one axis, reported as page dp per frame — and a tap, reported once on the lift.
 *
 * Hand-written rather than `detectDragGestures` beside `detectTapGestures`, because those two cannot
 * share a target: the tap arm consumes the down to keep it from what is underneath, and
 * `detectDragGestures` waits for an *unconsumed* down, so the drag silently never fires at all. AD7
 * records that trap; this is the same one, with the same answer — decide once, on the up, from
 * whether the finger travelled.
 *
 * Everything is consumed, unlike the grid's own tap target: a gutter handle is chrome belonging to
 * the table, and nothing underneath it has a claim on the gesture.
 */
private fun Modifier.pointerInputAxis(
    horizontal: Boolean,
    density: Float,
    onDrag: (Float) -> Unit,
    onTap: () -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(horizontal) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val slop = viewConfiguration.touchSlop
            var travelled = false
            var last = down.position
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) {
                    if (!travelled) onTap()
                    break
                }
                if (!travelled && (change.position - down.position).getDistance() > slop) {
                    travelled = true
                    // Measured from here rather than from the down, so the slop the gesture spent
                    // deciding what it was is not also applied as a resize.
                    last = change.position
                }
                if (travelled) {
                    val amount = change.position - last
                    last = change.position
                    onDrag(if (horizontal) amount.x / density else amount.y / density)
                }
            }
        }
    },
)

/** A drag that reports its whole travel on the lift, so one gesture is one thing to undo. */
private fun Modifier.pointerInputMove(
    onDrag: (Float, Float) -> Unit,
    onEnd: () -> Unit,
    density: Float,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragEnd = onEnd,
            onDragCancel = onEnd,
        ) { change, amount ->
            change.consume()
            onDrag(amount.x / density, amount.y / density)
        }
    },
)

/**
 * The corner drag, as a scale measured from the table's own top-left corner.
 *
 * The travel is accumulated rather than read from the pointer's position, because the handle moves
 * with the preview it produces: reading where the finger is against a box that is being resized
 * under it is a feedback loop, and the scale runs away.
 */
private fun Modifier.pointerInputScale(
    widthDp: () -> Float,
    heightDp: () -> Float,
    onScale: (Float, Float) -> Unit,
    onEnd: () -> Unit,
    density: Float,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        var travelX = 0f
        var travelY = 0f
        detectDragGestures(
            onDragStart = {
                travelX = 0f
                travelY = 0f
            },
            onDragEnd = onEnd,
            onDragCancel = onEnd,
        ) { change, amount ->
            change.consume()
            travelX += amount.x / density
            travelY += amount.y / density
            val width = widthDp()
            val height = heightDp()
            onScale(
                if (width <= 0f) 1f else ((width + travelX) / width).coerceAtLeast(MIN_SCALE),
                if (height <= 0f) 1f else ((height + travelY) / height).coerceAtLeast(MIN_SCALE),
            )
        }
    },
)

private val CELL_PADDING: Dp = 6.dp
private val HANDLE_RADIUS: Dp = 7.dp

/**
 * The grab bar inside a gutter handle — the little knob you actually put a finger on.
 *
 * A literal rather than a scheme colour, and it does not follow the theme, because what it has to
 * read against is not the page: it sits on the handle's own azure field, which is azure in both
 * themes. That is the same reasoning the object toolkit's `#E8EAED` follows — chrome painted on
 * chrome answers to the thing underneath it, not to the app around it.
 *
 * It was `primary` before, which meant an accent bar on a 30%-accent background: the knob and its
 * handle were the same hue, and the part you grab was the part hardest to see.
 */
private val HANDLE_KNOB: Color = Color(0xFFF2F0EF)

/** Faint enough to read as paper, strong enough to tell a header row from the rest. */
private const val HEADER_TINT_ALPHA = 0.10f

/** Stronger than the header's, because a hold is a thing you did rather than a property of the page. */
private const val HELD_TINT_ALPHA = 0.22f

/** Never through zero: a table flipped inside out by a fast drag cannot be dragged back. */
private const val MIN_SCALE = 0.15f
