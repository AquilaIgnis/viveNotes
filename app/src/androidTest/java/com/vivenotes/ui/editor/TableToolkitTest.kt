package com.vivenotes.ui.editor

import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.TableSettings
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.newTable
import com.vivenotes.richtext.EditorStyle
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.panel.TablePanelTags
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Table Class — `docs/diagram.md`, planned in `docs/tablePlan.md`.
 *
 * Three things are worth pinning here and none of them is arithmetic; the arithmetic is in
 * `TableOpsTest`, which runs on the JVM. This covers the parts that need a device: that **Insert
 * Table arms a tool** rather than dropping a table (TA7), that **a cell is a real editor** (TA2), and
 * that the bar's **Row and Column menus** act where the caret is and hide Delete at the last one
 * (TA6).
 */
class TableToolkitTest {

    @get:Rule
    val compose = createComposeRule()

    private var armed: DrawTool? = null
    private var settings: TableSettings? = null

    // -----------------------------------------------------------------------------------------
    // The tool — TA7
    // -----------------------------------------------------------------------------------------

    private fun setInsertTab(tool: DrawTool = DrawTool.None, table: TableSettings = TableSettings()) {
        armed = null
        settings = null
        compose.setContent {
            ViveNotesTheme {
                InsertTab(
                    selection = SelectionState(),
                    onCommand = {},
                    pageOpen = true,
                    shape = ShapeSettings(),
                    table = table,
                    palette = PEN_COLORS,
                    tool = tool,
                    onSelectTool = { armed = it },
                    onChangeShape = {},
                    onChangeTable = { settings = it },
                )
            }
        }
    }

    @Test
    fun theInsertTabArmsTheTableTool() {
        setInsertTab()

        compose.onNodeWithTag(TABLE_BUTTON_TAG).performClick()

        assertEquals(DrawTool.Table, armed)
    }

    /**
     * Tapping the tool already in hand opens its settings — [ShapeButton]'s interaction, deliberately
     * shared. Two buttons on one tab behaving differently would be worse than either behaviour.
     */
    @Test
    fun tappingTheArmedToolOpensItsPane() {
        setInsertTab(tool = DrawTool.Table)

        compose.onNodeWithTag(TABLE_BUTTON_TAG).performClick()

        compose.onNodeWithTag(TablePanelTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun thePaneChangesTheSettingsForTheNextTableRatherThanAnyOnThePage() {
        setInsertTab(tool = DrawTool.Table)
        compose.onNodeWithTag(TABLE_BUTTON_TAG).performClick()

        compose.onNodeWithTag("panel-field-Header row").performClick()

        // The default is on, so one tap turns it off — and it is a *preference*, which is the whole
        // point of TA7: nothing on the page moved.
        assertEquals(false, settings?.headerRow)
    }

    // -----------------------------------------------------------------------------------------
    // The Draw tab's table — TA15
    // -----------------------------------------------------------------------------------------

    private fun setDrawTab(tool: DrawTool = DrawTool.None) {
        armed = null
        compose.setContent {
            ViveNotesTheme {
                DrawTab(
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    table = TableSettings(),
                    tool = tool,
                    allowFinger = false,
                    actions = DrawActions(
                        selectTool = { armed = it },
                        updatePen = { _, _ -> },
                        updateEraser = {},
                        updateTable = { settings = it },
                        setDrawWithFinger = {},
                    ),
                )
            }
        }
    }

    /**
     * The Draw tab's table is a **different tool**, because it places a different object: a ruling
     * with nothing in its cells, not a grid of text fields.
     */
    @Test
    fun theDrawTabArmsTheInkTableRatherThanTheTypedOne() {
        setDrawTab()

        compose.onNodeWithTag(INK_TABLE_BUTTON_TAG).performClick()

        assertEquals(DrawTool.InkTable, armed)
    }

    @Test
    fun bothTablesShareOneSetOfSettings() {
        setDrawTab(tool = DrawTool.InkTable)
        compose.onNodeWithTag(INK_TABLE_BUTTON_TAG).performClick()

        compose.onNodeWithTag("panel-field-Header row").performClick()

        // The same `TableSettings` the Insert tab writes: how many rows and how thick the rules is
        // the same question of both kinds, so there is one answer to it.
        assertEquals(false, settings?.headerRow)
    }

    /**
     * The point of the whole thing: **a tap on a cell reaches the table, not an editor.**
     *
     * If a cell held a `NoteEditor` it would take the touch and the caret, and this would never fire
     * — which is exactly what the stylus would find too.
     */
    @Test
    fun aTapOnAnInkTablesCellSelectsTheTableBecauseNothingElseIsThere() {
        var selected = false
        val ruling = newTable(columns = 2, rows = 2, inkOnly = true) { "ink-cell" }
        compose.setContent {
            ViveNotesTheme {
                TableContainer(
                    table = ruling,
                    selected = false,
                    editorStyle = style,
                    defaults = EditorDefaults(),
                    initialBlocksFor = { listOf(Block.empty()) },
                    onCellFocused = { _, _ -> },
                    onCellBlurred = {},
                    onCellBlocksChanged = { _, _ -> },
                    onSelectionChanged = {},
                    onMove = { _, _ -> },
                    onResize = { _, _ -> },
                    onColumnWidth = { _, _ -> },
                    onRowMinHeight = { _, _ -> },
                    onSelect = { selected = true },
                    onMeasured = {},
                )
            }
        }

        compose.onNodeWithTag(TableTags.GRID).performClick()

        assertTrue("a ruling has no caret to select it by, so the tap has to", selected)
    }

    // -----------------------------------------------------------------------------------------
    // The grid — TA2, TA5
    // -----------------------------------------------------------------------------------------

    private val style = EditorStyle(
        indentStepPx = 40,
        listGapPx = 40,
        bulletRadiusPx = 6,
        accentColor = 0xFF4CAF50.toInt(),
        codeBackgroundColor = 0x22FFFFFF,
        quoteColor = 0xFF4CAF50.toInt(),
    )

    private fun table(columns: Int = 3, rows: Int = 3): Outline.Table {
        var next = 0
        return newTable(columns = columns, rows = rows) { "cell-${next++}" }
    }

    /** Driven from state rather than from a second `setContent`, which one Activity cannot take. */
    private val selectedState = mutableStateOf(false)

    /**
     * Compose state, not a plain `var` — the container has to *recompose* with the new hold, which
     * is what the second tap of the toggle reads to know it is already held. `EditorPane` holds it
     * the same way, so a plain field here would be testing a shape the app does not have.
     */
    private val heldState = mutableStateOf<TableAxis?>(null)
    private val heldAxis: TableAxis? get() = heldState.value

    private fun setTable(
        table: Outline.Table,
        selected: Boolean = false,
        onCellBlocks: (String, List<Block>) -> Unit = { _, _ -> },
        onColumnWidth: (Int, Float) -> Unit = { _, _ -> },
        onRowMinHeight: (Int, Float) -> Unit = { _, _ -> },
    ) {
        selectedState.value = selected
        heldState.value = null
        compose.setContent {
            ViveNotesTheme {
                TableContainer(
                    table = table,
                    selected = selectedState.value,
                    held = heldState.value,
                    onHold = { heldState.value = it },
                    onRowMinHeight = onRowMinHeight,
                    editorStyle = style,
                    defaults = EditorDefaults(),
                    initialBlocksFor = { listOf(Block.empty()) },
                    onCellFocused = { _, _ -> },
                    onCellBlurred = {},
                    onCellBlocksChanged = onCellBlocks,
                    onSelectionChanged = {},
                    onMove = { _, _ -> },
                    onResize = { _, _ -> },
                    onColumnWidth = onColumnWidth,
                    onMeasured = {},
                )
            }
        }
    }

    @Test
    fun everyCellIsItsOwnEditor() {
        val table = table(columns = 2, rows = 2)
        setTable(table)

        table.cellIds().forEach { id ->
            compose.onNodeWithTag(TableTags.cell(id)).assertIsDisplayed()
        }
    }

    /**
     * The handles appear with the selection and not before — and, TA5, they are all in the gutters.
     * A handle over a cell would either eat the drag or take the caret.
     */
    @Test
    fun theHandlesArriveWithTheSelection() {
        val table = table()
        setTable(table, selected = false)

        compose.onNodeWithTag(TableTags.MOVE).assertDoesNotExist()
        compose.onNodeWithTag(TableTags.RESIZE).assertDoesNotExist()
        compose.onNodeWithTag(TableTags.columnHandle(0)).assertDoesNotExist()

        compose.runOnIdle { selectedState.value = true }

        compose.onNodeWithTag(TableTags.MOVE).assertIsDisplayed()
        compose.onNodeWithTag(TableTags.RESIZE).assertIsDisplayed()
        compose.onNodeWithTag(TableTags.columnHandle(0)).assertIsDisplayed()
        compose.onNodeWithTag(TableTags.rowHandle(0)).assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // Holding a row or a column — TA16
    // -----------------------------------------------------------------------------------------

    @Test
    fun tappingAColumnHandleHoldsThatColumn() {
        setTable(table(columns = 3), selected = true)

        compose.onNodeWithTag(TableTags.columnHandle(1)).performClick()

        assertEquals(TableAxis.Column("cell-0", 1), heldAxis)
    }

    @Test
    fun tappingARowHandleHoldsThatRow() {
        setTable(table(rows = 3), selected = true)

        compose.onNodeWithTag(TableTags.rowHandle(2)).performClick()

        assertEquals(TableAxis.Row("cell-0", 2), heldAxis)
    }

    /** A second tap on the handle already held puts it down, which is how you get back to no hold. */
    @Test
    fun tappingTheHeldHandleAgainClearsIt() {
        setTable(table(columns = 3), selected = true)

        compose.onNodeWithTag(TableTags.columnHandle(0)).performClick()
        compose.runOnIdle { /* the hold is applied */ }
        compose.onNodeWithTag(TableTags.columnHandle(0)).performClick()

        assertNull(heldAxis)
    }

    /**
     * The regression this pairing invites: **a handle that holds must still resize.**
     *
     * Tap and drag share one target, and the classic way to break that is a tap arm that consumes
     * the down — `detectDragGestures` then waits for an unconsumed one that never comes, and the
     * drag silently stops working with nothing in the logs (AD7).
     */
    @Test
    fun draggingAColumnHandleStillResizesAndDoesNotHoldAnything() {
        var width: Float? = null
        setTable(table(columns = 3), selected = true, onColumnWidth = { _, w -> width = w })

        compose.onNodeWithTag(TableTags.columnHandle(0)).performTouchInput {
            down(center)
            moveBy(Offset(120f, 0f))
            up()
        }

        assertNotNull("the drag reported no width", width)
        assertNull("a drag is not a hold", heldAxis)
    }

    private var rowAt: Int? = null
    private var columnAt: Int? = null

    private fun setBar(table: Outline.Table, focusedCell: String?) {
        rowAt = null
        columnAt = null
        val at = focusedCell?.let(table::locate)
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.size(600.dp)) {
                    ObjectTooltip(
                        swatch = null,
                        selectionBoundsInView = { RectF(0f, 200f, 300f, 400f) },
                        viewportSize = IntSize(600, 600),
                        onDelete = {},
                        onCopy = {},
                        onRecolor = {},
                        extras = {
                            TableRowAction(
                                canDelete = table.rowCount > 1,
                                onInsertAbove = { rowAt = at?.first ?: 0 },
                                onInsertBelow = { rowAt = (at?.first ?: table.rowCount - 1) + 1 },
                                onDelete = { rowAt = -1 },
                            )
                            TableColumnAction(
                                canDelete = table.columnCount > 1,
                                onInsertLeft = { columnAt = at?.second ?: 0 },
                                onInsertRight = {
                                    columnAt = (at?.second ?: table.columnCount - 1) + 1
                                },
                                onDelete = { columnAt = -1 },
                            )
                        },
                    )
                }
            }
        }
    }

    @Test
    fun insertBelowLandsUnderTheRowTheCaretIsIn() {
        val table = table(rows = 3)
        setBar(table, focusedCell = table.cellAt(1, 0)!!.id)

        compose.onNodeWithTag(TableActionTags.ROW).performClick()
        compose.onNodeWithTag(TableActionTags.ROW_BELOW).performClick()

        assertEquals(2, rowAt)
    }

    @Test
    fun insertRightLandsBesideTheColumnTheCaretIsIn() {
        val table = table(columns = 3)
        setBar(table, focusedCell = table.cellAt(0, 2)!!.id)

        compose.onNodeWithTag(TableActionTags.COLUMN).performClick()
        compose.onNodeWithTag(TableActionTags.COLUMN_RIGHT).performClick()

        assertEquals(3, columnAt)
    }

    /**
     * With a row held the menus are gone and the three Material Symbols are there instead — TA16,
     * and the one difference between `table-tooltip1.jpeg` and `table-tooltip2.jpeg`.
     */
    @Test
    fun aHeldRowReplacesTheMenusWithItsOwnIcons() {
        var above = false
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.size(600.dp)) {
                    ObjectTooltip(
                        swatch = null,
                        selectionBoundsInView = { RectF(0f, 200f, 300f, 400f) },
                        viewportSize = IntSize(600, 600),
                        onDelete = {},
                        onCopy = {},
                        onRecolor = {},
                        extras = {
                            HeldRowActions(
                                canDelete = true,
                                onInsertAbove = { above = true },
                                onInsertBelow = {},
                                onDelete = {},
                            )
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag(TableActionTags.ROW).assertDoesNotExist()
        compose.onNodeWithTag(TableActionTags.COLUMN).assertDoesNotExist()
        compose.onNodeWithTag(TableActionTags.ROW_DELETE).assertIsDisplayed()

        // One tap, not a menu and then an item.
        compose.onNodeWithTag(TableActionTags.ROW_ABOVE).performClick()

        assertTrue(above)
    }

    /** Delete stays absent rather than dead at the last row, held or not. */
    @Test
    fun aHeldRowThatIsTheOnlyRowShowsNoDelete() {
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.size(600.dp)) {
                    ObjectTooltip(
                        swatch = null,
                        selectionBoundsInView = { RectF(0f, 200f, 300f, 400f) },
                        viewportSize = IntSize(600, 600),
                        onDelete = {},
                        onCopy = {},
                        onRecolor = {},
                        extras = {
                            HeldColumnActions(
                                canDelete = false,
                                onInsertLeft = {},
                                onInsertRight = {},
                                onDelete = {},
                            )
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag(TableActionTags.COLUMN_LEFT).assertIsDisplayed()
        compose.onNodeWithTag(TableActionTags.COLUMN_RIGHT).assertIsDisplayed()
        compose.onNodeWithTag(TableActionTags.COLUMN_DELETE).assertDoesNotExist()
    }

    /** With no caret the verbs still mean something: the ends of the table. */
    @Test
    fun withNoCaretInsertAboveMeansTheTop() {
        val table = table(rows = 4)
        setBar(table, focusedCell = null)

        compose.onNodeWithTag(TableActionTags.ROW).performClick()
        compose.onNodeWithTag(TableActionTags.ROW_ABOVE).performClick()

        assertEquals(0, rowAt)
    }

    /**
     * Delete is **absent**, not disabled, at the last row — the rule the whole bar follows for an
     * action a kind cannot perform. A table with no rows is not a table.
     */
    @Test
    fun deleteIsMissingRatherThanDeadAtTheLastRowAndColumn() {
        val single = table(columns = 1, rows = 1)
        setBar(single, focusedCell = null)

        compose.onNodeWithTag(TableActionTags.ROW).performClick()
        compose.onNodeWithTag(TableActionTags.ROW_ABOVE).assertIsDisplayed()
        compose.onNodeWithTag(TableActionTags.ROW_DELETE).assertDoesNotExist()

        assertNull(rowAt)
        assertTrue(single.columnCount == 1)
    }
}
