package com.vivenotes.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.PenPreset
import com.vivenotes.ui.ScrollingRow
import com.vivenotes.ui.icons.LocalRibbonIcons
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.icons.penGlyph
import com.vivenotes.ui.panel.EraserPanelContent
import com.vivenotes.ui.panel.FloatingSettingsPanel
import com.vivenotes.ui.panel.PenPanelContent

/**
 * What the Draw tab can do. Gathered into one object for the reason [ViewActions] is: the ribbon
 * holds no reference to the ViewModel, so it can be composed in a test with no database behind it.
 */
@Immutable
data class DrawActions(
    val selectTool: (DrawTool) -> Unit,
    val updatePen: (Int, PenPreset) -> Unit,
    val updateEraser: (EraserSettings) -> Unit,
    val setDrawWithFinger: (Boolean) -> Unit,
)

/** Test tags for the tools, which show their state as a colour rather than as text. */
internal object DrawTags {
    const val UNDO = "draw-undo"
    const val REDO = "draw-redo"
    const val ERASER = "draw-eraser"
    const val FINGER = "draw-finger"
    fun pen(index: Int) = "draw-pen-$index"
}

/**
 * The Draw tab: undo and redo, three pens, an eraser.
 *
 * The pens are deliberately identical — they exist so that three colours are one tap apart instead
 * of a trip through a menu, which is the whole reason a pen tray has more than one pen in it. What
 * distinguishes them on screen is therefore colour alone, which is why [penGlyph] takes its swatch
 * as a parameter rather than being a static asset.
 *
 * Holding a pen opens its settings (`docs/references/pen-tooltip.jpeg`). Tapping the pen that is
 * already selected does the same thing, because a gesture nobody discovers is a gesture nobody uses.
 *
 * Undo and redo are icon-only — their glyphs are universal, so a label would only cost width in a
 * row that scrolls. They are also inert: the undo stack is feature C6, which is not built. Same
 * treatment the View tab gives Full Page View — holding the spot the design gives them and plainly
 * not working beats accepting a tap and doing nothing.
 *
 * The finger button decides whether a direct touch draws or scrolls. It is a device property rather
 * than a pen setting — whether you own a stylus is not an attribute of pen 2 — and defaults to off,
 * which is what lets a finger pan the page while the pen draws on it. On a device with no stylus,
 * including an emulator, turning it on is the only way to draw at all.
 */
@Composable
internal fun DrawTab(
    pens: List<PenPreset>,
    eraser: EraserSettings,
    tool: DrawTool,
    allowFinger: Boolean,
    actions: DrawActions,
) {
    var openPenIndex by remember { mutableStateOf<Int?>(null) }
    var eraserSettingsOpen by remember { mutableStateOf(false) }

    ScrollingRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        RibbonButton(
            icon = MaterialSymbols.Undo,
            label = "Undo",
            enabled = false,
            onClick = {},
        )
        RibbonButton(
            icon = MaterialSymbols.Redo,
            label = "Redo",
            enabled = false,
            onClick = {},
        )

        Divider()

        pens.forEachIndexed { index, pen ->
            PenButton(
                index = index,
                pen = pen,
                selected = tool == DrawTool.Pen(index),
                onSelect = { actions.selectTool(DrawTool.Pen(index)) },
                settingsOpen = openPenIndex == index,
                onOpen = { openPenIndex = index },
                onDismiss = { openPenIndex = null },
                onChange = { actions.updatePen(index, it) },
            )
        }

        Divider()

        Box(Modifier.testTag(DrawTags.FINGER)) {
            RibbonButton(
                icon = MaterialSymbols.TouchApp,
                label = if (allowFinger) "Drawing with finger and stylus" else "Stylus only",
                active = allowFinger,
                onClick = { actions.setDrawWithFinger(!allowFinger) },
            )
        }

        Divider()

        EraserButton(
            settings = eraser,
            selected = tool == DrawTool.Eraser,
            settingsOpen = eraserSettingsOpen,
            onSelect = { actions.selectTool(DrawTool.Eraser) },
            onOpen = { eraserSettingsOpen = true },
            onDismiss = { eraserSettingsOpen = false },
            onChange = actions.updateEraser,
        )
    }
}

/** Same select/configure interaction as a pen, with its popup anchored under the eraser itself. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EraserButton(
    settings: EraserSettings,
    selected: Boolean,
    settingsOpen: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onChange: (EraserSettings) -> Unit,
) {
    val icons = LocalRibbonIcons.current
    Box(modifier = Modifier.padding(horizontal = 1.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .testTag(DrawTags.ERASER)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                )
                .combinedClickable(
                    onClick = { if (selected) onOpen() else onSelect() },
                    onLongClick = {
                        onSelect()
                        onOpen()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) icons.active.eraser else icons.idle.eraser,
                contentDescription = "Eraser",
                tint = Color.Unspecified,
                modifier = Modifier.size(18.dp),
            )
        }

        FloatingSettingsPanel(
            expanded = settingsOpen,
            onDismissRequest = onDismiss,
            title = "Eraser",
        ) {
            EraserPanelContent(settings = settings, onChange = onChange)
        }
    }
}

/**
 * A pen in the tray.
 *
 * Not [RibbonButtonSlot], because that takes a single click and this control has two meanings for
 * one target: select, and configure. The glyph is built through [remember] on its two colours —
 * building an `ImageVector` in a composable body would rebuild it on every recomposition, and the
 * ribbon recomposes whenever the selection moves.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PenButton(
    index: Int,
    pen: PenPreset,
    selected: Boolean,
    onSelect: () -> Unit,
    settingsOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onChange: (PenPreset) -> Unit,
) {
    val neutral = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val swatch = Color(pen.colorArgb)
    val glyph = remember(neutral, swatch) { penGlyph(neutral, swatch) }

    Box(modifier = Modifier.padding(horizontal = 1.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .testTag(DrawTags.pen(index))
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                )
                .combinedClickable(
                    onClick = { if (selected) onOpen() else onSelect() },
                    // Configuring a pen picks it up as well: the settings you are about to change
                    // are the ones you would then be drawing with.
                    onLongClick = {
                        onSelect()
                        onOpen()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = glyph,
                contentDescription = "Pen ${index + 1}",
                // See TwoToneRibbonButton: a tint would flatten the swatch into the barrel.
                tint = Color.Unspecified,
                modifier = Modifier.size(18.dp),
            )
        }

        FloatingSettingsPanel(
            expanded = settingsOpen,
            onDismissRequest = onDismiss,
            title = "Pen ${index + 1}",
        ) {
            PenPanelContent(pen = pen, onChange = onChange)
        }
    }
}
