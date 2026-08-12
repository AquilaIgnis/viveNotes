package com.vivenotes.model

import com.vivenotes.model.Outline.Table

/**
 * Everything the Table Class's toolkit does to a grid — `docs/diagram.md`, `docs/tablePlan.md` TA6.
 *
 * Pure functions on the document model, with no Android in sight, for the reason `ShapeSegment`'s
 * geometry is: this is the half of the feature that can be *tested on this host* (R10), and it is the
 * half where a mistake is silent — a row removed from `rows` but not from the block map is a table
 * that looks right and has lost its text.
 *
 * Every one of them returns a table with [Table.withRecomputedWidth] already applied where the
 * columns changed, so a caller can never leave [Outline.width] disagreeing with the grid.
 */

/**
 * A cell with nothing in it — which is two different things.
 *
 * A typed table's empty cell holds one empty paragraph, because that is what a caret needs to land
 * in. An ink table's holds **no blocks at all** (TA15): nothing types in it, so a paragraph there
 * would be a promise of text that never arrives — and it is exactly the entry that
 * `Table.contentCellIds` exists to keep out of the ViewModel's block map.
 */
private fun emptyCell(id: String, inkOnly: Boolean): TableCell =
    if (inkOnly) TableCell(id = id) else TableCell.empty(id)

/** Whether another row would fit under the caps — TA9. */
val Table.canAddRow: Boolean
    get() = rowCount < Table.MAX_ROWS && (rowCount + 1) * columnCount.coerceAtLeast(1) <= Table.MAX_CELLS

val Table.canAddColumn: Boolean
    get() = columnCount < Table.MAX_COLUMNS && rowCount.coerceAtLeast(1) * (columnCount + 1) <= Table.MAX_CELLS

/** A table always has a row and a column, so the last of either cannot be removed — TA6. */
val Table.canRemoveRow: Boolean get() = rowCount > 1

val Table.canRemoveColumn: Boolean get() = columnCount > 1

/**
 * A new grid, seeded with empty cells.
 *
 * [newId] is passed rather than reached for so the JVM tests can produce a table whose ids are
 * predictable — the same reason `seedSegments` takes one.
 */
fun newTable(
    columns: Int,
    rows: Int,
    x: Float = 0f,
    y: Float = 0f,
    columnWidth: Float = Table.DEFAULT_COLUMN_WIDTH,
    rowHeight: Float = Table.DEFAULT_ROW_HEIGHT,
    headerRow: Boolean = false,
    headerColumn: Boolean = false,
    borderArgb: Int = 0xFF000000.toInt(),
    borderFollowsTheme: Boolean? = null,
    borderWidth: Float = 1f,
    fillArgb: Int? = null,
    /** A ruling for the stylus rather than a grid of text fields — TA15. */
    inkOnly: Boolean = false,
    newId: () -> String = ::newId,
): Table {
    val columnCount = columns.coerceIn(1, Table.MAX_COLUMNS)
    // Clamped against the cell budget as well as against the row cap, so an oversized request is
    // trimmed rather than refused: someone asking for a 12×50 table gets the biggest one that fits.
    val rowCount = rows.coerceIn(1, minOf(Table.MAX_ROWS, Table.MAX_CELLS / columnCount))
    return Table(
        id = newId(),
        x = x,
        y = y,
        columns = List(columnCount) { columnWidth.coerceIn(Table.MIN_COLUMN_WIDTH, Table.MAX_COLUMN_WIDTH) },
        rows = List(rowCount) {
            TableRow(
                id = newId(),
                minHeight = rowHeight.coerceIn(Table.MIN_ROW_HEIGHT, Table.MAX_ROW_HEIGHT),
                cells = List(columnCount) { emptyCell(newId(), inkOnly) },
            )
        },
        headerRow = headerRow,
        headerColumn = headerColumn,
        borderArgb = borderArgb,
        borderFollowsTheme = borderFollowsTheme,
        borderWidth = borderWidth,
        fillArgb = fillArgb,
        inkOnly = inkOnly,
    ).withRecomputedWidth()
}

/**
 * A row of empty cells inserted at [at], clamped into the grid.
 *
 * The new row takes the height of the one it was inserted beside rather than the default, so
 * "insert below" in a table whose rows have been dragged taller does not produce one thin row.
 */
fun Table.withRowInserted(at: Int, newId: () -> String = ::newId): Table {
    if (!canAddRow) return this
    val index = at.coerceIn(0, rowCount)
    val neighbour = rows.getOrNull(index) ?: rows.lastOrNull()
    val row = TableRow(
        id = newId(),
        minHeight = neighbour?.minHeight ?: Table.DEFAULT_ROW_HEIGHT,
        cells = List(columnCount) { emptyCell(newId(), inkOnly) },
    )
    return copy(rows = rows.toMutableList().apply { add(index, row) })
}

/** Removes a row and every cell on it. The last row survives — a table with no rows is not one. */
fun Table.withRowRemoved(at: Int): Table {
    if (!canRemoveRow) return this
    val index = at.coerceIn(0, rowCount - 1)
    return copy(rows = rows.filterIndexed { position, _ -> position != index })
}

/**
 * A column of empty cells inserted at [at].
 *
 * It takes its width from the column it was inserted beside, for the reason a row takes its height:
 * the table someone is looking at is the one the new part should match.
 */
fun Table.withColumnInserted(at: Int, newId: () -> String = ::newId): Table {
    if (!canAddColumn) return this
    val index = at.coerceIn(0, columnCount)
    val width = columns.getOrNull(index) ?: columns.lastOrNull() ?: Table.DEFAULT_COLUMN_WIDTH
    return copy(
        columns = columns.toMutableList().apply { add(index, width) },
        rows = rows.map { row ->
            row.copy(cells = row.cells.toMutableList().apply { add(index, emptyCell(newId(), inkOnly)) })
        },
    ).withRecomputedWidth()
}

/** Removes a column and its cell on every row. The last column survives, as the last row does. */
fun Table.withColumnRemoved(at: Int): Table {
    if (!canRemoveColumn) return this
    val index = at.coerceIn(0, columnCount - 1)
    return copy(
        columns = columns.filterIndexed { position, _ -> position != index },
        rows = rows.map { row ->
            row.copy(cells = row.cells.filterIndexed { position, _ -> position != index })
        },
    ).withRecomputedWidth()
}

/** One column's width, from dragging its handle in the top gutter — TA5. */
fun Table.withColumnWidth(at: Int, width: Float): Table {
    if (at !in columns.indices) return this
    val clamped = width.coerceIn(Table.MIN_COLUMN_WIDTH, Table.MAX_COLUMN_WIDTH)
    if (columns[at] == clamped) return this
    return copy(columns = columns.mapIndexed { index, value -> if (index == at) clamped else value })
        .withRecomputedWidth()
}

/** One row's floor, from dragging its handle in the left gutter. A floor, never a height — TA3. */
fun Table.withRowMinHeight(at: Int, minHeight: Float): Table {
    if (at !in rows.indices) return this
    val clamped = minHeight.coerceIn(Table.MIN_ROW_HEIGHT, Table.MAX_ROW_HEIGHT)
    if (rows[at].minHeight == clamped) return this
    return copy(
        rows = rows.mapIndexed { index, row -> if (index == at) row.copy(minHeight = clamped) else row },
    )
}

/**
 * The same grid with every cell given a fresh id — what a paste needs.
 *
 * Ids are minted for the table, its rows and its cells alike: two tables sharing a cell id would
 * share the block map entry behind it, so typing in one would appear in the other.
 */
fun Table.withNewIds(newId: () -> String = ::newId): Table = copy(
    id = newId(),
    rows = rows.map { row ->
        row.copy(id = newId(), cells = row.cells.map { it.copy(id = newId()) })
    },
)

/**
 * The table with its cells' content replaced from [blocks], and blank cells kept.
 *
 * A blank cell is written where a blank *container* is not — TA12. An empty container is a caret
 * position that nobody typed in; an empty cell is part of the grid's shape, and dropping it would
 * change the table's size the next time the page loads.
 *
 * A cell missing from [blocks] keeps what it already had, which is what makes this safe to call with
 * a partial map.
 */
fun Table.withCellBlocks(blocks: Map<String, List<Block>>): Table = copy(
    rows = rows.map { row ->
        row.copy(
            cells = row.cells.map { cell ->
                blocks[cell.id]?.let { cell.copy(blocks = it) } ?: cell
            },
        )
    },
)
