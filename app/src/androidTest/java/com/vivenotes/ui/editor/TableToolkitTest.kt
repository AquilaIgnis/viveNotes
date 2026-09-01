package com.vivenotes.ui.editor

import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.vivenotes.ui.panel.TablePanelTags
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Table Class — `memory/diagram.md`, planned in `memory/tablePlan.md`.
 *
 * Four things are worth pinning here and none of them is arithmetic; the arithmetic is in
 * `TableOpsTest`, which runs on the JVM. This covers the parts that need a device: that **Insert
 * Table arms a tool** rather than dropping a table (TA7), that **which kind of table is a setting**
 * rather than a second button (TA15), that **a cell is a real editor** (TA2), and that the bar's
 * **Row and Column menus** act where the caret is and hide Delete at the last one (TA6).
 */
class TableToolkitTest {

    @get:Rule
    val compose = createComposeRule()

    private var armed: DrawTool? = null
    private var settings: TableSettings? = null

    // -----------------------------------------------------------------------------------------
    // The tool — TA7
    // -----------------------------------------------------------------------------------------

    private fun setDrawTab(tool: DrawTool = DrawTool.None, table: TableSettings = TableSettings()) {
        armed = null
        settings = null
        compose.setContent {
            ViveNotesTheme {
                DrawTab(
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    table = table,
                    tool = tool,
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

    @Test
    fun theTableButtonArmsAToolRatherThanPlacingATable() {
        setDrawTab()

        compose.onNodeWithTag(TABLE_BUTTON_TAG).performClick()

        assertEquals(DrawTool.Table, armed)
    }

    /**
     * Tapping the tool already in hand opens its settings — [ShapeButton]'s interaction, deliberately
     * shared. Two buttons on one tab behaving differently would be worse than either behaviour.
     */
    @Test
    fun tappingTheArmedToolOpensItsPane() {
        setDrawTab(tool = DrawTool.Table)

        compose.onNodeWithTag(TABLE_BUTTON_TAG).performClick()

        compose.onNodeWithTag(TablePanelTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun thePaneChangesTheSettingsForTheNextTableRatherThanAnyOnThePage() {
        setDrawTab(tool = DrawTool.Table)
        compose.onNodeWithTag(TABLE_BUTTON_TAG).performClick()

        compose.onNodeWithTag("panel-field-Header row").performClick()

        // The default is on, so one tap turns it off — and it is a *preference*, which is the whole
        // point of TA7: nothing on the page moved.
        assertEquals(false, settings?.headerRow)
    }

    // -----------------------------------------------------------------------------------------
    // Which kind of table — TA15, now a setting rather than a second tool
    // -----------------------------------------------------------------------------------------

    /**
     * The ruling to write in and the grid of text fields are **one tool with one pane**.
     *
     * They were two buttons on two tabs, which is what this replaces: the kind is a `TableSettings`
     * field now, so the single button on Draw covers both and nothing else in the app has to know
     * there are two.
     */
    @Test
    fun theKindOfTableIsAnOptionInThePane() {
        setDrawTab(tool = DrawTool.Table)
        compose.onNodeWithTag(TABLE_BUTTON_TAG).performClick()

        compose.onNodeWithTag("panel-field-Write in with a pen").performClick()

        // On by default, so one tap asks for the typed grid instead — and only for the *next* table.
        assertEquals(false, settings?.inkOnly)
    }

    /** Whichever kind the pane is set to, the button still arms the one tool. */
    @Test
    fun theTypedTableIsArmedByTheSameButtonAsTheRuling() {
        setDrawTab(table = TableSettings(inkOnly = false))

        compose.onNodeWithTag(TABLE_BUTTON_TAG).performClick()

        assertEquals(DrawTool.Table, armed)
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

    /** Which cell holds the caret, as the container reports it — what Tab is asserted through. */
    private var focusedCell: String? = null

    private fun setTable(
        table: Outline.Table,
        selected: Boolean = false,
        onCellBlocks: (String, List<Block>) -> Unit = { _, _ -> },
        onColumnWidth: (Int, Float) -> Unit = { _, _ -> },
        onRowMinHeight: (Int, Float) -> Unit = { _, _ -> },
    ) {
        selectedState.value = selected
        heldState.value = null
        focusedCell = null
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
                    onCellFocused = { cellId, _ -> focusedCell = cellId },
                    onCellBlurred = { if (focusedCell == it) focusedCell = null },
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

    // -----------------------------------------------------------------------------------------
    // Tab walks the grid — TA17
    // -----------------------------------------------------------------------------------------

    /**
     * Sent to the window that holds the caret, and not through a Compose node.
     *
     * A cell is a real `EditText` (AD6) and it is the *window's* focused view, so what has to be
     * proved is that a key arriving at the window reaches that editor and walks the grid.
     * Dispatching into the Compose node tree instead would prove that Compose can be made to route
     * a key, which is not the claim; this enters at `Activity.dispatchKeyEvent`, above every line
     * of the app's own key handling and below nothing that belongs to it.
     *
     * **It used to go through `Instrumentation.sendKeySync`, which is one hop too high.** That
     * hands the event to the system input router and lets the router pick a window, and the pick is
     * not always this one: on a loaded device — a full release suite, or the moment after an
     * install — [focusCell] leaves an editor holding view focus while the key lands somewhere else
     * entirely. `onKeyDown` is then never called and the caret sits where it started, which reads
     * exactly like Tab being ignored. All three Tab cases failed that way in one run and passed
     * individually in the next; probes in `onKeyDown` and `moveCaret` showed that on a failing run
     * neither ever fired, so nothing about the grid was being tested at all. `DrawTabTest`'s
     * `tapOutsidePopup` addresses its own window for the same reason.
     *
     * The `check` is the other half: a key this test believes it sent and the app never saw is the
     * failure that took a release run to notice, and it should name itself.
     */
    private fun pressTab(shift: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .single()
            val now = SystemClock.uptimeMillis()
            check(
                activity.dispatchKeyEvent(
                    KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, meta),
                ),
            ) { "Tab reached the window and nothing in it took the key" }
            // Not checked: Tab's whole meaning is on the way down, and the editor leaves the release
            // to `TextView`, which has no reason to claim it.
            activity.dispatchKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, meta),
            )
        }
        compose.waitForIdle()
    }

    private fun focusCell(table: Outline.Table, row: Int, column: Int) {
        val id = table.cellAt(row, column)!!.id
        compose.onNodeWithTag(TableTags.cell(id)).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { focusedCell == id }
    }

    /**
     * Waits for the caret rather than asserting on the frame after the key, the way [focusCell]
     * already waits for the click it sends.
     *
     * A cell's editor is a View registered on `onViewCreated`, and `moveCaret` gives up quietly
     * when the destination is not attached yet — the reveal path retries across frames for exactly
     * that reason. So Tab across a row boundary can take a frame longer than `waitForIdle` waits,
     * which on a loaded emulator read as Tab doing nothing at all. What a user gets is the caret
     * arriving, not the caret arriving this frame.
     *
     * The wait is allowed to time out so the assertion below reports which cell actually holds the
     * caret; a bare `waitUntil` would fail with nothing but a duration.
     */
    private fun assertCaretReaches(expected: String?) {
        runCatching { compose.waitUntil(timeoutMillis = 2_000) { focusedCell == expected } }
        assertEquals(expected, focusedCell)
    }

    @Test
    fun tabMovesTheCaretToTheNextColumn() {
        val table = table(columns = 3, rows = 2)
        setTable(table)
        focusCell(table, row = 0, column = 0)

        pressTab()

        assertCaretReaches(table.cellAt(0, 1)?.id)
    }

    /** The half that makes the key worth having: the end of a row is not the end of the table. */
    @Test
    fun tabAtTheLastColumnWrapsToTheStartOfTheNextRow() {
        val table = table(columns = 3, rows = 2)
        setTable(table)
        focusCell(table, row = 0, column = 2)

        pressTab()

        assertCaretReaches(table.cellAt(1, 0)?.id)
    }

    @Test
    fun shiftTabWalksBackTheSameWay() {
        val table = table(columns = 3, rows = 2)
        setTable(table)
        focusCell(table, row = 1, column = 0)

        pressTab(shift = true)

        assertCaretReaches(table.cellAt(0, 2)?.id)
    }

    /**
     * The last cell has nowhere to hand on to, so the caret stays put and Tab is the indent it is
     * everywhere else — TA17 declines to grow a row here, because a keystroke that edits the
     * document is a different promise from one that moves the caret.
     */
    @Test
    fun tabInTheLastCellKeepsTheCaretWhereItIs() {
        val table = table(columns = 2, rows = 2)
        setTable(table)
        focusCell(table, row = 1, column = 1)

        pressTab()

        assertCaretReaches(table.cellAt(1, 1)?.id)
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
                                onInsertBelow = { rowAt = (at?.first ?: table.rowCount - 1) + 1 },
                                onDelete = { rowAt = -1 },
                            )
                            TableColumnAction(
                                canDelete = table.columnCount > 1,
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
        var below = false
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
                                onInsertBelow = { below = true },
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
        compose.onNodeWithTag(TableActionTags.ROW_BELOW).performClick()

        assertTrue(below)
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
                                onInsertRight = {},
                                onDelete = {},
                            )
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag(TableActionTags.COLUMN_RIGHT).assertIsDisplayed()
        compose.onNodeWithTag(TableActionTags.COLUMN_DELETE).assertDoesNotExist()
    }

    /** With no caret the verbs still mean something: the far end of the table. */
    @Test
    fun withNoCaretInsertBelowMeansTheBottom() {
        val table = table(rows = 4)
        setBar(table, focusedCell = null)

        compose.onNodeWithTag(TableActionTags.ROW).performClick()
        compose.onNodeWithTag(TableActionTags.ROW_BELOW).performClick()

        assertEquals(4, rowAt)
    }

    /**
     * **Insertion goes one way only.** "Insert above" and "insert left" did nothing on the device
     * and were removed on 2026-08-08; these two keep them from drifting back in as dead entries.
     */
    @Test
    fun theRowMenuOffersNoInsertAbove() {
        setBar(table(rows = 3), focusedCell = null)

        compose.onNodeWithTag(TableActionTags.ROW).performClick()

        compose.onNodeWithText("Insert above").assertDoesNotExist()
        compose.onNodeWithText("Insert below").assertIsDisplayed()
    }

    @Test
    fun theColumnMenuOffersNoInsertLeft() {
        setBar(table(columns = 3), focusedCell = null)

        compose.onNodeWithTag(TableActionTags.COLUMN).performClick()

        compose.onNodeWithText("Insert left").assertDoesNotExist()
        compose.onNodeWithText("Insert right").assertIsDisplayed()
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
        compose.onNodeWithTag(TableActionTags.ROW_BELOW).assertIsDisplayed()
        compose.onNodeWithTag(TableActionTags.ROW_DELETE).assertDoesNotExist()

        assertNull(rowAt)
        assertTrue(single.columnCount == 1)
    }
}
