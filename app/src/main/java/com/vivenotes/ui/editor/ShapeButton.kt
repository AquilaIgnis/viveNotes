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
import com.vivenotes.data.ShapeSettings
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.FloatingSettingsPanel
import com.vivenotes.ui.panel.ShapePanelContent

internal const val SHAPE_BUTTON_TAG = "insert-shape"

/**
 * Insert Shape — `docs/inkPlan.md` §5.4.
 *
 * Lives in its own file because it is composed by **two** tabs. It belongs on Draw, beside the
 * things that make marks; and on Insert, because that is where someone goes looking to put a shape
 * on a page. Neither is the "real" home, so neither owns it.
 *
 * The interaction is the pen tray's: a tap arms the tool, and holding — or tapping the tool already
 * in hand — opens its settings. Consistent with [PenButton] and the eraser, and for the same reason
 * given there: a gesture nobody discovers is a gesture nobody uses.
 *
 * Icon-only, matching the Equation button beside it on Insert. The glyph is an ordinary monochrome
 * Material Symbol, so it is tinted normally — the sixteen shapes themselves are drawn by
 * `ShapePanelContent` from the geometry, not from icon assets.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShapeButton(
    shape: ShapeSettings,
    palette: List<Int>,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onChange: (ShapeSettings) -> Unit,
    onAddColor: (Int) -> Unit = {},
) {
    var settingsOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(horizontal = 1.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .testTag(SHAPE_BUTTON_TAG)
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
                imageVector = MaterialSymbols.Category,
                contentDescription = "Shapes",
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
            title = "Shape",
        ) {
            ShapePanelContent(
                shape = shape,
                palette = palette,
                onChange = onChange,
                onAddColor = onAddColor,
            )
        }
    }
}
