package com.vivenotes.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.TableSettings
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.FloatingSettingsPanel
import com.vivenotes.ui.panel.TablePanelContent

internal const val TABLE_BUTTON_TAG = "insert-table"

/** The Draw tab's twin, which places a ruling for the stylus rather than a grid of fields — TA15. */
internal const val INK_TABLE_BUTTON_TAG = "draw-table"

/**
 * Insert Table — `docs/tablePlan.md` TA7, and the first button of `docs/references/insertTab.png`.
 *
 * The interaction is [ShapeButton]'s, deliberately: a tap arms the tool, and holding — or tapping the
 * tool already in hand — opens its settings. Two buttons on the same tab behaving differently would
 * be worse than either behaviour.
 *
 * **What it arms is a tool, not a drop.** The next tap on bare canvas is what places the table, so a
 * table goes where you put it, the way everything else on this canvas does. The reference's Table
 * button carries a chevron for the same reason this one opens a pane: how many rows and columns is a
 * question asked before the table exists, not after.
 *
 * **One composable, two tabs, two objects.** [inkOnly] is the Draw tab's version (TA15): the same
 * button, the same pane, the same settings — what changes is that the table it places has no editors
 * in it, so a pen writes straight through the cells. Like [ShapeButton], neither home is the "real"
 * one; unlike it, the two are separate tools, because what they put on the page differs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TableButton(
    table: TableSettings,
    palette: List<Int>,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onChange: (TableSettings) -> Unit,
    onAddColor: (Int) -> Unit = {},
    inkOnly: Boolean = false,
) {
    var settingsOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(horizontal = 1.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .testTag(if (inkOnly) INK_TABLE_BUTTON_TAG else TABLE_BUTTON_TAG)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                )
                .then(
                    if (enabled) {
                        Modifier.combinedClickable(
                            onClick = {
                                onSelect()
                                if (selected) settingsOpen = true
                            },
                            onLongClick = {
                                onSelect()
                                settingsOpen = true
                            },
                        )
                    } else {
                        Modifier
                    },
                )
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MaterialSymbols.Table,
                // Named for what it makes, because the two buttons are the same glyph on two tabs
                // and the label is the only thing that tells them apart to a screen reader.
                contentDescription = if (inkOnly) "Table to write in" else "Table",
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
        }

        FloatingSettingsPanel(
            expanded = settingsOpen,
            onDismissRequest = { settingsOpen = false },
            title = if (inkOnly) "Table to write in" else "Table",
        ) {
            TablePanelContent(
                table = table,
                palette = palette,
                onChange = onChange,
                onAddColor = onAddColor,
            )
        }
    }
}
