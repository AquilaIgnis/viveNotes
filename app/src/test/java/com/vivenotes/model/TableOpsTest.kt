package com.vivenotes.model

import com.vivenotes.model.Outline.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Table Class's grid arithmetic — `docs/tablePlan.md`, step 0 of §3.
 *
 * Deliberately the largest piece of pure logic in the feature, because it is the piece that *runs on
 * this host* (R10) and the piece where a mistake is silent: a column removed from `columns` but not
 * from every row is a table that renders fine until it is saved, and a row inserted without cells is
 * a table that loses its shape on reload.
 */
class TableOpsTest {

    /** Ids that say what they are, so a failure names the thing that moved. */
    private fun grid(columns: Int = 3, rows: Int = 3): Table {
        var next = 0
        return newTable(columns = columns, rows = rows) { "id-${next++}" }
    }

    private fun Table.textGrid(): List<List<String>> =
        rows.map { row -> row.cells.map { it.plainText } }

    private fun Table.withText(row: Int, column: Int, text: String): Table = copy(
        rows = rows.mapIndexed { rowIndex, tableRow ->
            if (rowIndex != row) {
                tableRow
            } else {
                tableRow.copy(
                    cells = tableRow.cells.mapIndexed { columnIndex, cell ->
                        if (columnIndex == column) cell.copy(blocks = listOf(Block.of(text))) else cell
                    },
                )
            }
        },
    )

    // -------------------------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------------------------

    @Test
    fun aNewTableIsRectangularAndFullOfCells() {
        val table = grid(columns = 4, rows = 2)

        assertEquals(4, table.columnCount)
        assertEquals(2, table.rowCount)
        assertEquals(8, table.cellCount)
        table.rows.forEach { assertEquals(4, it.cells.size) }
        assertEquals(8, table.cellIds().distinct().size)
    }

    @Test
    fun widthIsTheSumOfTheColumns() {
        val table = grid(columns = 3)

        assertEquals(Table.DEFAULT_COLUMN_WIDTH * 3, table.width, 0.001f)
    }

    @Test
    fun heightIsTheSumOfTheRowFloors() {
        // The document's honest approximation — the canvas is authoritative once text overflows,
        // which is what `CanvasSelection.TableBounds` exists for.
        val table = grid(rows = 3)

        assertEquals(Table.DEFAULT_ROW_HEIGHT * 3, table.height, 0.001f)
    }

    @Test
    fun aRequestBiggerThanTheCapIsTrimmedRatherThanRefused() {
        val table = newTable(columns = 40, rows = 400)

        assertEquals(Table.MAX_COLUMNS, table.columnCount)
        assertTrue("cells within budget", table.cellCount <= Table.MAX_CELLS)
        assertTrue("still a table", table.rowCount >= 1)
    }

    // -------------------------------------------------------------------------------------------
    // Rows and columns — the four actions the diagram asks for
    // -------------------------------------------------------------------------------------------

    @Test
    fun insertingARowKeepsEveryOtherRowsText() {
        val table = grid().withText(0, 0, "top").withText(2, 2, "bottom")

        val grown = table.withRowInserted(1)

        assertEquals(4, grown.rowCount)
        assertEquals("top", grown.cellAt(0, 0)?.plainText)
        assertEquals("", grown.cellAt(1, 0)?.plainText)
        assertEquals("bottom", grown.cellAt(3, 2)?.plainText)
    }

    @Test
    fun insertingAColumnAddsOneCellToEveryRow() {
        val table = grid().withText(1, 1, "middle")

        val grown = table.withColumnInserted(0)

        assertEquals(4, grown.columnCount)
        grown.rows.forEach { assertEquals(4, it.cells.size) }
        assertEquals(Table.DEFAULT_COLUMN_WIDTH * 4, grown.width, 0.001f)
        // Everything shifted right by one, text and all.
        assertEquals("middle", grown.cellAt(1, 2)?.plainText)
    }

    @Test
    fun removingAColumnTakesItsCellFromEveryRow() {
        val table = grid().withText(0, 0, "a").withText(0, 2, "c")

        val shrunk = table.withColumnRemoved(1)

        assertEquals(2, shrunk.columnCount)
        shrunk.rows.forEach { assertEquals(2, it.cells.size) }
        assertEquals(listOf("a", "c"), shrunk.textGrid().first())
        assertEquals(Table.DEFAULT_COLUMN_WIDTH * 2, shrunk.width, 0.001f)
    }

    @Test
    fun theLastRowAndTheLastColumnCannotBeRemoved() {
        val single = grid(columns = 1, rows = 1)

        assertFalse(single.canRemoveRow)
        assertFalse(single.canRemoveColumn)
        assertEquals(single, single.withRowRemoved(0))
        assertEquals(single, single.withColumnRemoved(0))
    }

    @Test
    fun insertsAreRefusedAtTheCapRatherThanTruncatingTheGrid() {
        val wide = grid(columns = Table.MAX_COLUMNS, rows = 1)

        assertFalse(wide.canAddColumn)
        assertEquals(wide, wide.withColumnInserted(0))
    }

    @Test
    fun theCellBudgetStopsGrowthBeforeEitherAxisCapDoes() {
        // 12 columns × 16 rows is 192 cells; a seventeenth row would be 204.
        val table = grid(columns = Table.MAX_COLUMNS, rows = 16)

        assertTrue(table.rowCount < Table.MAX_ROWS)
        assertFalse("the budget, not the row cap, is what refuses this", table.canAddRow)
    }

    @Test
    fun aNewRowMatchesTheHeightOfTheOneItLandsBeside() {
        val table = grid(rows = 2).let { it.withRowMinHeight(0, 120f) }

        val grown = table.withRowInserted(0)

        assertEquals(120f, grown.rows[0].minHeight, 0.001f)
    }

    @Test
    fun anOutOfRangeIndexIsClampedRatherThanThrowing() {
        val table = grid()

        assertEquals(4, table.withRowInserted(99).rowCount)
        assertEquals(2, table.withRowRemoved(99).rowCount)
        assertEquals(4, table.withColumnInserted(-5).columnCount)
    }

    // -------------------------------------------------------------------------------------------
    // Geometry
    // -------------------------------------------------------------------------------------------

    @Test
    fun draggingAColumnHandleResizesThatColumnAndTheTable() {
        val table = grid(columns = 3)

        val wider = table.withColumnWidth(1, 300f)

        assertEquals(300f, wider.columns[1], 0.001f)
        assertEquals(Table.DEFAULT_COLUMN_WIDTH, wider.columns[0], 0.001f)
        assertEquals(Table.DEFAULT_COLUMN_WIDTH * 2 + 300f, wider.width, 0.001f)
    }

    @Test
    fun aColumnCannotBeDraggedNarrowerThanItsMinimum() {
        val table = grid()

        assertEquals(Table.MIN_COLUMN_WIDTH, table.withColumnWidth(0, -400f).columns[0], 0.001f)
    }

    @Test
    fun aCornerDragScalesColumnsAcrossAndRowFloorsDown() {
        // The reason a table may keep the corner handles a text box had to decline: two real axes.
        val table = grid(columns = 2, rows = 2).copy(x = 100f, y = 50f)

        val scaled = table.scaledAbout(anchorX = 100f, anchorY = 50f, scaleX = 2f, scaleY = 0.75f)

        assertEquals(100f, scaled.x, 0.001f)
        assertEquals(50f, scaled.y, 0.001f)
        assertEquals(Table.DEFAULT_COLUMN_WIDTH * 2f, scaled.columns[0], 0.001f)
        // 0.75 rather than a half, because half of the default floor is under the minimum and the
        // clamp — asserted on its own below — would be what the number came from.
        assertEquals(Table.DEFAULT_ROW_HEIGHT * 0.75f, scaled.rows[0].minHeight, 0.001f)
        assertEquals(scaled.columns.sum(), scaled.width, 0.001f)
    }

    @Test
    fun aCornerDragMovesTheTableWhenTheAnchorIsNotItsOrigin() {
        val table = grid(columns = 1, rows = 1).copy(x = 200f, y = 100f)

        val scaled = table.scaledAbout(anchorX = 0f, anchorY = 0f, scaleX = 0.5f, scaleY = 0.5f)

        assertEquals(100f, scaled.x, 0.001f)
        assertEquals(50f, scaled.y, 0.001f)
    }

    @Test
    fun scalingNeverCollapsesARowOrAColumnToNothing() {
        val table = grid()

        val crushed = table.scaledAbout(0f, 0f, 0.0001f, 0.0001f)

        assertEquals(Table.MIN_COLUMN_WIDTH, crushed.columns.first(), 0.001f)
        assertEquals(Table.MIN_ROW_HEIGHT, crushed.rows.first().minHeight, 0.001f)
    }

    // -------------------------------------------------------------------------------------------
    // Identity and content
    // -------------------------------------------------------------------------------------------

    @Test
    fun locateFindsACellAndReportsNothingForAStranger() {
        val table = grid()

        assertEquals(1 to 2, table.locate(table.cellAt(1, 2)!!.id))
        assertNull(table.locate("not-mine"))
    }

    @Test
    fun aPastedCopySharesNoIdWithItsOriginal() {
        var next = 0
        val table = grid().withText(0, 0, "kept")

        val copy = table.withNewIds { "paste-${next++}" }

        assertNotEquals(table.id, copy.id)
        assertTrue(copy.cellIds().none { it in table.cellIds() })
        assertTrue(copy.rows.map { it.id }.none { it in table.rows.map { row -> row.id } })
        // The text is what a paste is for; only the identity changes.
        assertEquals("kept", copy.cellAt(0, 0)?.plainText)
    }

    @Test
    fun cellBlocksAreReplacedWhereGivenAndKeptWhereNot() {
        val table = grid().withText(0, 0, "before")
        val target = table.cellAt(0, 0)!!.id

        val updated = table.withCellBlocks(mapOf(target to listOf(Block.of("after"))))

        assertEquals("after", updated.cellAt(0, 0)?.plainText)
        assertEquals("", updated.cellAt(1, 1)?.plainText)
    }

    // -------------------------------------------------------------------------------------------
    // The Draw tab's table — TA15
    // -------------------------------------------------------------------------------------------

    /**
     * An ink table has cells and **no content cells**, which is the distinction the ViewModel's whole
     * block-map path turns on.
     *
     * Getting this wrong in the safe direction leaves an entry nobody types in; getting it wrong in
     * the other direction stops the page saving at all, because `persist` waits for content that will
     * never arrive.
     */
    @Test
    fun anInkTableHasCellsButNoneOfThemHoldText() {
        var next = 0
        val ruling = newTable(columns = 3, rows = 2, inkOnly = true) { "id-${next++}" }

        assertEquals(6, ruling.cellIds().size)
        assertTrue("nothing in a ruling holds text", ruling.contentCellIds().isEmpty())
        assertTrue(ruling.rows.all { row -> row.cells.all { it.blocks.isEmpty() } })
    }

    @Test
    fun aTypedTableStillReportsEveryCellAsAContentCell() {
        val typed = grid(columns = 2, rows = 2)

        assertEquals(typed.cellIds(), typed.contentCellIds())
    }

    /** Rows and columns are the same operation on either kind — that is why the flag is not a type. */
    @Test
    fun anInkTableGrowsAndShrinksLikeAnyOther() {
        var next = 0
        val ruling = newTable(columns = 2, rows = 2, inkOnly = true) { "id-${next++}" }

        val grown = ruling.withRowInserted(1).withColumnInserted(0)

        assertEquals(3, grown.rowCount)
        assertEquals(3, grown.columnCount)
        assertTrue("the flag has to survive an edit", grown.inkOnly)
        assertTrue(grown.contentCellIds().isEmpty())
    }

    @Test
    fun anInkTablesHeightIsExactBecauseNothingCanOverflowARow() {
        val ruling = newTable(columns = 1, rows = 4, rowHeight = 50f, inkOnly = true)

        assertEquals(200f, ruling.height, 0.001f)
    }

    // -------------------------------------------------------------------------------------------
    // The document round trip
    // -------------------------------------------------------------------------------------------

    @Test
    fun aTableSurvivesEncodingAndDecoding() {
        val table = grid(columns = 2, rows = 2)
            .copy(x = 12f, y = 34f, headerRow = true, fillArgb = 0x11223344)
            .withText(0, 1, "cell")
        val doc = PageDoc(outlines = listOf(table))

        val restored = decodePageDoc(doc.encode()).outlines.filterIsInstance<Table>().single()

        assertEquals(table, restored)
    }

    /** The two kinds are one type, so the flag has to be what comes back — not the default. */
    @Test
    fun anInkTableComesBackAsOneRatherThanAsAGridOfEmptyFields() {
        val doc = PageDoc(outlines = listOf(newTable(columns = 2, rows = 2, inkOnly = true)))

        val restored = decodePageDoc(doc.encode()).outlines.filterIsInstance<Table>().single()

        assertTrue(restored.inkOnly)
        assertTrue(restored.contentCellIds().isEmpty())
    }

    @Test
    fun aTablesTextReachesTheSearchProjection() {
        val doc = PageDoc(
            outlines = listOf(
                Outline.Text(id = "t", blocks = listOf(Block.of("prose"))),
                grid(columns = 2, rows = 1).withText(0, 1, "in a cell"),
            ),
        )

        val text = doc.plainText()

        assertTrue(text.contains("prose"))
        assertTrue(text.contains("in a cell"))
    }
}
