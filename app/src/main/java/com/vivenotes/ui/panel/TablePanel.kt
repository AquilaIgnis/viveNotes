package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.TableSettings
import com.vivenotes.model.Outline

/** Test tags for the parts of the pane that show state without showing text. */
object TablePanelTags {
    const val PREVIEW = "table-preview"
    const val FILL = "table-fill"
    const val NO_FILL = "table-fill-none"
    const val CUSTOM_COLOR = "table-color-custom"

    fun color(argb: Int) = "table-color-$argb"
}

/**
 * The Insert Table pane, laid out as `memory/references/table-opts.jpeg` lays it out —
 * `memory/tablePlan.md` TA7.
 *
 * Field for field, in the plate's order: header column, header row, column count, row count, border
 * width, border colour, fill colour. The plate's counts are chips showing a number, which is
 * [PanelStepper]; its headers are switches, which is [PanelToggle]; its fill reads "none", which is
 * where a table starts.
 *
 * Ahead of the plate's own fields is one it has no equivalent for: **write in with a pen**, which
 * chooses between a grid of text fields and a ruling with nothing in its cells. That used to be the
 * difference between two Table buttons on two tabs (TA15) and is a setting here instead, because it
 * is a question about the table you are about to make — which is what everything else in this pane
 * is.
 *
 * **These are the user's defaults, not an edit.** Everything here says how the *next* table arrives
 * (ID5). Changing a table already on the page is the object toolkit's job, and the two must not be
 * merged however alike they look — the Shape pane carries the same warning for the same reason.
 *
 * The border colour row shares the pens' rolling palette, as every other colour row in the app does.
 */
@Composable
fun ColumnScope.TablePanelContent(
    table: TableSettings,
    palette: List<Int>,
    onChange: (TableSettings) -> Unit,
    onAddColor: (Int) -> Unit = {},
) {
    TablePreview(table)

    Spacer(Modifier.height(10.dp))
    // First, because it is the only field that changes what the table *is* rather than how it looks,
    // and every field under it means the same thing either way. Off gives the typed table that used
    // to be the Insert tab's own button — TA15, folded in here.
    PanelSetting(
        label = "Write in with a pen",
        info = "Off, each cell is a text field you type into."
    ) {
        PanelToggle(
            field = "Write in with a pen",
            checked = table.inkOnly,
            onCheckedChange = { onChange(table.copy(inkOnly = it)) },
        )
    }

    PanelSetting(label = "Header column") {
        PanelToggle(
            field = "Header column",
            checked = table.headerColumn,
            onCheckedChange = { onChange(table.copy(headerColumn = it)) },
        )
    }
    PanelSetting(label = "Header row") {
        PanelToggle(
            field = "Header row",
            checked = table.headerRow,
            onCheckedChange = { onChange(table.copy(headerRow = it)) },
        )
    }
    PanelSetting(label = "Column count") {
        PanelStepper(
            field = "Column count",
            value = table.columns,
            range = 1..Outline.Table.MAX_COLUMNS,
            onPick = { onChange(table.copy(columns = it)) },
        )
    }
    PanelSetting(label = "Row count") {
        PanelStepper(
            field = "Row count",
            value = table.rows,
            // Capped by the cell budget against the columns already chosen, not by the row cap
            // alone — TA9. Offering 50 rows beside 12 columns would be offering 600 editors.
            range = 1..maxOf(
                1,
                minOf(
                    Outline.Table.MAX_ROWS,
                    Outline.Table.MAX_CELLS / table.columns.coerceAtLeast(1),
                ),
            ),
            onPick = { onChange(table.copy(rows = it)) },
        )
    }

    Spacer(Modifier.height(4.dp))
    PanelSlider(
        field = "Border width",
        label = "Border width",
        value = table.borderWidth,
        range = TableSettings.MIN_BORDER_WIDTH..TableSettings.MAX_BORDER_WIDTH,
        onChange = { onChange(table.copy(borderWidth = it)) },
        sizePreviewColor = Color(table.borderColorArgb),
    )

    Spacer(Modifier.height(2.dp))
    Text(
        text = "Border color",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 2.dp),
    )
    ColorSwatches(
        palette = palette,
        current = table.borderColorArgb,
        // A tap is an explicit choice and must survive a later theme change, as it does for a pen.
        onPick = { onChange(table.copy(borderColorArgb = it, colorFollowsTheme = false)) },
        onAddColor = onAddColor,
        colorTag = TablePanelTags::color,
        customTag = TablePanelTags.CUSTOM_COLOR,
    )

    Spacer(Modifier.height(6.dp))
    PanelSetting(label = "Fill color") {
        FillSwatch(
            fill = table.fillArgb,
            seedArgb = table.borderColorArgb,
            onChange = { onChange(table.copy(fillArgb = it)) },
            onAddColor = onAddColor,
            tag = TablePanelTags.FILL,
            noFillTag = TablePanelTags.NO_FILL,
        )
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * The grid the settings below will produce, drawn rather than described.
 *
 * Worth the space for the reason the shape and stroke previews are: "header column, 3 × 3, border
 * width 4" is four numbers, and the plate answers them with a picture. It is the one place the header
 * flags are visible as *shading* rather than as two switches that look identical when off.
 *
 * Capped at what fits the box — a 12-column preview would be a smear — so beyond that it shows the
 * shape of the table rather than every line of it.
 */
@Composable
private fun TablePreview(table: TableSettings) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .testTag(TablePanelTags.PREVIEW),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .padding(horizontal = 36.dp),
        ) {
            val columns = table.columns.coerceIn(1, PREVIEW_MAX)
            val rows = table.rows.coerceIn(1, PREVIEW_MAX)
            val cellWidth = size.width / columns
            val cellHeight = size.height / rows

            table.fillArgb?.let { drawRect(Color(it)) }

            // Headers first, so the rules are drawn over them rather than under.
            if (table.headerRow) {
                drawRect(headerTint, Offset.Zero, Size(size.width, cellHeight))
            }
            if (table.headerColumn) {
                drawRect(headerTint, Offset.Zero, Size(cellWidth, size.height))
            }

            // The border's own colour and width, scaled the way the page would show them at a
            // comfortable zoom — the preview is a small page, not a small table.
            val stroke = (table.borderWidth.dp.toPx() * PREVIEW_SCALE).coerceAtLeast(1f)
            val border = Color(table.borderColorArgb)
            for (column in 0..columns) {
                val x = (column * cellWidth).coerceAtMost(size.width - stroke / 2f)
                drawLine(border, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            }
            for (row in 0..rows) {
                val y = (row * cellHeight).coerceAtMost(size.height - stroke / 2f)
                drawLine(border, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
        }
    }
}

/** Past this the preview stops being a picture of a table and becomes hatching. */
private const val PREVIEW_MAX = 6

/** How much of its true page width a rule keeps in a box this size. */
private const val PREVIEW_SCALE = 0.7f
