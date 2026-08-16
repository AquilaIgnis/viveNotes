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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PenPreset
import com.vivenotes.data.RulerSettings
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.TableSettings
import com.vivenotes.ui.ScrollingRow
import com.vivenotes.ui.icons.LocalRibbonIcons
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.EraserPanelContent
import com.vivenotes.ui.panel.FloatingSettingsPanel
import com.vivenotes.ui.panel.HighlighterPanelContent
import com.vivenotes.ui.panel.PenPanelContent
import com.vivenotes.ui.panel.RulerPanelContent

/**
 * What the Draw tab can do. Gathered into one object for the reason [ViewActions] is: the ribbon
 * holds no reference to the ViewModel, so it can be composed in a test with no database behind it.
 */
@Immutable
data class DrawActions(
    val selectTool: (DrawTool) -> Unit,
    val updatePen: (Int, PenPreset) -> Unit,
    val updateEraser: (EraserSettings) -> Unit,
    val updateHighlighter: (HighlighterSettings) -> Unit = {},
    val updateShape: (ShapeSettings) -> Unit = {},
    /** How the next table arrives — `docs/tablePlan.md` TA7. A preference, never an edit. */
    val updateTable: (TableSettings) -> Unit = {},
    val updateRuler: (RulerSettings) -> Unit = {},
    /** Lays the ruler on the page, or picks it up again — `docs/rulerPlan.md` RD1. */
    val toggleRuler: () -> Unit = {},
    /**
     * Takes a composed formula in hand: the next tap on bare canvas places it.
     *
     * Unlike every other entry here it carries *content* rather than a setting, which is why it is an
     * action and not an `update…` — see `NotesViewModel.pendingEquation`.
     */
    val armEquation: (String, MeasuredEquation) -> Unit = { _, _ -> },
    val setDrawWithFinger: (Boolean) -> Unit,
    /** Puts a colour off the wheel at the head of the swatch row. Not the same write as a pen. */
    val addPaletteColor: (Int) -> Unit = {},
    val undo: () -> Unit = {},
    val redo: () -> Unit = {},
)

/** Test tags for the tools, which show their state as a colour rather than as text. */
internal object DrawTags {
    const val UNDO = "draw-undo"
    const val REDO = "draw-redo"
    const val ERASER = "draw-eraser"
    const val HIGHLIGHTER = "draw-highlighter"
    const val LASSO = "draw-lasso"
    const val NONE = "draw-none"
    const val RULER = "draw-ruler"
    const val INSERT_SPACE = "draw-insert-space"
    fun pen(index: Int) = "draw-pen-$index"
}

/**
 * The Draw tab: undo and redo, three pens, an eraser.
 *
 * The tray opens with the empty hand — the one button that arms nothing, so putting a tool down is a
 * gesture of its own rather than something you get to by picking up a different tool.
 *
 * The pens are deliberately identical — they exist so that three colours are one tap apart instead
 * of a trip through a menu, which is the whole reason a pen tray has more than one pen in it. What
 * distinguishes them on screen is therefore the colour of each pencil itself.
 *
 * Holding a pen opens its settings (`docs/references/pen-tooltip.jpeg`). Tapping the pen that is
 * already selected does the same thing, because a gesture nobody discovers is a gesture nobody uses.
 *
 * Undo and redo are icon-only — their glyphs are universal, so a label would only cost width in a
 * row that scrolls. Each reverses one complete ink gesture on the current page.
 *
 * Insert Space is the odd one out and has no settings at all: it edits the emptiness rather than any
 * object, and how much of it is the drag itself — see `com.vivenotes.model.PageSpace`.
 *
 * The finger button decides whether a direct touch draws or scrolls. It is a device property rather
 * than a pen setting — whether you own a stylus is not an attribute of pen 2 — and defaults to off,
 * which is what lets a finger pan the page while the pen draws on it. On a device with no stylus,
 * including an emulator, turning it on is the only way to draw at all.
 */
@Composable
internal fun DrawTab(
    pens: List<PenPreset>,
    palette: List<Int>,
    eraser: EraserSettings,
    highlighter: HighlighterSettings,
    shape: ShapeSettings,
    /** How the next table arrives, kind included — see [TableButton]. */
    table: TableSettings = TableSettings(),
    tool: DrawTool,
    actions: DrawActions,
    pageOpen: Boolean = true,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    ruler: RulerSettings = RulerSettings(),
    /** Whether the ruler is lying on the page. Not a tool — see [DrawActions.toggleRuler]. */
    rulerOut: Boolean = false,
) {
    var openPenIndex by remember { mutableStateOf<Int?>(null) }
    var eraserSettingsOpen by remember { mutableStateOf(false) }
    var highlighterSettingsOpen by remember { mutableStateOf(false) }
    var rulerSettingsOpen by remember { mutableStateOf(false) }

    ScrollingRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        RibbonButton(
            icon = MaterialSymbols.Undo,
            label = "Undo",
            enabled = canUndo,
            onClick = actions.undo,
        )
        RibbonButton(
            icon = MaterialSymbols.Redo,
            label = "Redo",
            enabled = canRedo,
            onClick = actions.redo,
        )

        Divider()

        // The off position of the tray. Every other button here arms something, so without it the
        // only way to stop drawing was to arm a different tool — and `DrawTool.None` was reachable
        // only as a side effect, from the Home tab's T toggle or from finishing a shape. Heads the
        // group rather than sitting apart from it: it is the same choice as the pens, made empty.
        Box(Modifier.testTag(DrawTags.NONE)) {
            RibbonButton(
                icon = MaterialSymbols.ArrowSelectorTool,
                label = "No tool",
                active = tool == DrawTool.None,
                onClick = { actions.selectTool(DrawTool.None) },
            )
        }

        pens.forEachIndexed { index, pen ->
            PenButton(
                index = index,
                pen = pen,
                palette = palette,
                selected = tool == DrawTool.Pen(index),
                onSelect = { actions.selectTool(DrawTool.Pen(index)) },
                settingsOpen = openPenIndex == index,
                onOpen = { openPenIndex = index },
                onDismiss = { openPenIndex = null },
                onChange = { actions.updatePen(index, it) },
                onAddColor = actions.addPaletteColor,
            )
        }

        HighlighterButton(
            settings = highlighter,
            selected = tool == DrawTool.Highlighter,
            settingsOpen = highlighterSettingsOpen,
            onSelect = { actions.selectTool(DrawTool.Highlighter) },
            onOpen = { highlighterSettingsOpen = true },
            onDismiss = { highlighterSettingsOpen = false },
            onChange = actions.updateHighlighter,
        )

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

        Box(Modifier.testTag(DrawTags.LASSO)) {
            RibbonButton(
                icon = MaterialSymbols.LassoSelect,
                label = "Lasso",
                active = tool == DrawTool.Lasso,
                onClick = { actions.selectTool(DrawTool.Lasso) },
            )
        }

        // Beside the lasso rather than beside the shapes, because it belongs to the same half of the
        // tray: these two rearrange what is already on the page, while everything past the divider
        // puts something new on it. Nothing configures it — the drag is the whole setting — so it is
        // a plain button with no held-open panel, unlike every other tool on either side of it.
        Box(Modifier.testTag(DrawTags.INSERT_SPACE)) {
            RibbonButton(
                icon = MaterialSymbols.Expand,
                label = "Insert space",
                active = tool == DrawTool.InsertSpace,
                enabled = pageOpen,
                onClick = { actions.selectTool(DrawTool.InsertSpace) },
            )
        }

        Divider()

        ShapeButton(
            shape = shape,
            palette = palette,
            selected = tool == DrawTool.Shape,
            enabled = pageOpen,
            onSelect = { actions.selectTool(DrawTool.Shape) },
            onChange = actions.updateShape,
            onAddColor = actions.addPaletteColor,
        )

        TableButton(
            table = table,
            palette = palette,
            selected = tool == DrawTool.Table,
            enabled = pageOpen,
            onSelect = { actions.selectTool(DrawTool.Table) },
            onChange = actions.updateTable,
            onAddColor = actions.addPaletteColor,
        )

        EquationButton(
            enabled = pageOpen,
            active = tool == DrawTool.Equation,
            tag = EquationTags.OBJECT,
            label = "Equation",
            onSubmit = { latex, measured -> actions.armEquation(latex, measured) },
        )

        Divider()

        RulerButton(
            settings = ruler,
            out = rulerOut,
            settingsOpen = rulerSettingsOpen,
            onToggle = actions.toggleRuler,
            onOpen = { rulerSettingsOpen = true },
            onDismiss = { rulerSettingsOpen = false },
            onChange = actions.updateRuler,
        )
    }
}

/**
 * The highlighter, wearing the ink it will lay down.
 *
 * A marker held the way [PenButton]'s stylus is — tip down, whole glyph in its own ink — because the
 * two are the same kind of thing and the tray should say so before either label does. What separates
 * them is the shape: a chisel body against a nib.
 *
 * The glyph is drawn opaque even though the ink is not. It is a small shape against ribbon chrome
 * with nothing behind it, and at 40% alpha every ink in the palette would read as the same grey —
 * the icon names the colour, the canvas shows the transparency.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlighterButton(
    settings: HighlighterSettings,
    selected: Boolean,
    settingsOpen: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onChange: (HighlighterSettings) -> Unit,
) {
    val swatch = Color(settings.colorArgb).copy(alpha = 1f)
    val marker = MaterialSymbols.StylusHighlighter

    Box(modifier = Modifier.padding(horizontal = 1.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .testTag(DrawTags.HIGHLIGHTER)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                )
                .combinedClickable(
                    onClick = {
                        onSelect()
                        if (selected) onOpen()
                    },
                    onLongClick = {
                        onSelect()
                        onOpen()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = marker,
                contentDescription = "Highlighter",
                tint = swatch,
                modifier = Modifier
                    .size(19.dp)
                    .rotate(180f),
            )
        }

        FloatingSettingsPanel(
            expanded = settingsOpen,
            onDismissRequest = onDismiss,
            title = "Highlighter",
        ) {
            HighlighterPanelContent(settings = settings, onChange = onChange)
        }
    }
}

/**
 * The ruler — `docs/rulerPlan.md` RD7.
 *
 * **Tap toggles, hold configures**, which is deliberately not the eraser's bargain. Every other
 * control in this tray is picked *up*, so tapping an already-selected one is free to mean "show me
 * your settings". This one is a toggle: its tap has to be able to mean *away*, or the ruler could be
 * laid on the page and never taken off it. So the pane is on the hold alone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RulerButton(
    settings: RulerSettings,
    out: Boolean,
    settingsOpen: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onChange: (RulerSettings) -> Unit,
) {
    Box(modifier = Modifier.padding(horizontal = 1.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .testTag(DrawTags.RULER)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (out) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                )
                .combinedClickable(
                    onClick = onToggle,
                    // Holding it brings it out as well as configuring it, for the reason holding a
                    // pen picks it up: the ruler you are about to change is the one you would use.
                    onLongClick = {
                        if (!out) onToggle()
                        onOpen()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MaterialSymbols.Straighten,
                contentDescription = if (out) "Put the ruler away" else "Ruler",
                tint = if (out) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
        }

        FloatingSettingsPanel(
            expanded = settingsOpen,
            onDismissRequest = onDismiss,
            title = "Ruler",
        ) {
            RulerPanelContent(settings = settings, onChange = onChange)
        }
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
                    onClick = {
                        onSelect()
                        if (selected) onOpen()
                    },
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
 * one target: select, and configure.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PenButton(
    index: Int,
    pen: PenPreset,
    palette: List<Int>,
    selected: Boolean,
    onSelect: () -> Unit,
    settingsOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onChange: (PenPreset) -> Unit,
    onAddColor: (Int) -> Unit,
) {
    val swatch = Color(pen.colorArgb)
    val stylus = MaterialSymbols.Stylus

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
                    onClick = {
                        onSelect()
                        if (selected) onOpen()
                    },
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
                imageVector = stylus,
                contentDescription = "Pen ${index + 1}",
                tint = swatch,
                modifier = Modifier
                    .size(19.dp)
                    .rotate(180f),
            )
        }

        FloatingSettingsPanel(
            expanded = settingsOpen,
            onDismissRequest = onDismiss,
            title = "Pen ${index + 1}",
        ) {
            PenPanelContent(
                pen = pen,
                palette = palette,
                onChange = onChange,
                onAddColor = onAddColor,
            )
        }
    }
}
