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

/**
 * Insert Table — `docs/tablePlan.md` TA7.
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
 * **One button, one tool, both kinds of table.** This *was* two buttons on two tabs — a grid of text
 * fields on Insert and a ruling to write in on Draw (TA15) — wearing the same glyph and the same
 * pane, distinguished only by which tab you found them on. The kind is now
 * [TableSettings.inkOnly], asked in the pane beside the rows and columns, and the Insert tab's copy
 * is gone. What survives lives here on Draw, with the things a stylus uses.
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
) {
    var settingsOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(horizontal = 1.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .testTag(TABLE_BUTTON_TAG)
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
                // Named for what it will make, not for what it is. One glyph now covers both kinds,
                // so the label is all a screen reader gets to tell them apart — and it has to follow
                // the setting, because the setting is what decides.
                contentDescription = if (table.inkOnly) "Table to write in" else "Table",
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
            title = "Table",
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
