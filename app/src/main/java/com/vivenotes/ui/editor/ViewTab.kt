package com.vivenotes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.data.TabsLayout
import com.vivenotes.data.ViewSettings
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines
import com.vivenotes.ui.ScrollingRow
import com.vivenotes.ui.icons.AppIcons
import com.vivenotes.ui.icons.LocalRibbonIcons
import com.vivenotes.ui.icons.pageColorGlyph
import com.vivenotes.ui.panel.ToolPane
import com.vivenotes.ui.theme.LocalCanvasColors
import kotlin.math.roundToInt

/**
 * What the View tab can do, gathered into one object.
 *
 * The ribbon deliberately holds no reference to the ViewModel — it is handed values and callbacks,
 * so it can be composed in a test with neither a database nor a preferences store. Eleven separate
 * lambda parameters would make that principle unreadable, so they travel together.
 */
@Immutable
data class ViewActions(
    val setRuleLines: (RuleLines) -> Unit,
    val setPageColor: (Int?) -> Unit,
    val setHideTitle: (Boolean) -> Unit,
    val setZoom: (Float) -> Unit,
    val zoomIn: () -> Unit,
    val zoomOut: () -> Unit,
    val zoomToPageWidth: () -> Unit,
    val setTabsLayout: (TabsLayout) -> Unit,
    val setCanvasDark: (Boolean) -> Unit,
    /** Settings-tab command, carried here because it is stored beside the rest of these. */
    val setLinkPreviews: (Boolean) -> Unit,
    /** Opens a docked pane for the settings too involved to sit in a drop-down. */
    val openPane: (ToolPane) -> Unit,
)

/** Page colours, and the "no colour" that hands the page back to the theme. */
private val PAGE_COLORS = listOf(
    0xFFFFFFFF, 0xFFFFF8E7, 0xFFFDF1F4, 0xFFEFF5FC,
    0xFFEFF7EF, 0xFFF5F0FA, 0xFFF7F3EC, 0xFFECF6F6,
    0xFF1F1F1F, 0xFF17232E, 0xFF1D2A1D, 0xFF2A1E2A,
).map { it.toInt() }

private val RULE_LINE_LABELS = listOf(
    RuleLines.None to "None",
    RuleLines.Standard to "Standard Ruled",
    RuleLines.Wide to "Wide Ruled",
    RuleLines.Dotted to "Dotted Paper",
    RuleLines.Hexagonal to "Hexagonal Paper",
    RuleLines.GridMedium to "Medium Grid",
    RuleLines.GridLarge to "Large Grid",
)

/**
 * The View tab from `memory/references/viewsTab.png`.
 *
 * Dock to Desktop, New Docked Window, New Window and New Quick Note are crossed out in that
 * screenshot and so are not here at all.
 *
 * **Full Page View and Normal View are gone too**, removed at the user's request on 2026-08-09. They
 * were placed and inert, holding the spot the reference gives them, and that was the wrong trade for
 * this pair: this app has no chrome to hide that the two would toggle between — the ribbon and the
 * panes are already the whole window, and the panes hide themselves by swipe. Two dead buttons at the
 * head of the tab pushed the controls that do work off to the right. Feature F1 in
 * `memory/features.md` is dropped, not deferred.
 */
@Composable
internal fun ViewTab(
    style: PageStyle,
    settings: ViewSettings,
    actions: ViewActions,
    pageOpen: Boolean,
) {
    val canvas = LocalCanvasColors.current

    ScrollingRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        TabsLayoutMenu(settings.tabsLayout, actions.setTabsLayout)

        Divider()

        Text(
            text = "Zoom:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        ZoomPicker(settings.zoom, actions.setZoom)
        RibbonButton(MaterialSymbols.ZoomIn, "Zoom in", onClick = actions.zoomIn)
        RibbonButton(MaterialSymbols.ZoomOut, "Zoom out", onClick = actions.zoomOut)
        RibbonCommand(
            label = "100%",
            onClick = { actions.setZoom(1f) },
            icon = { MonoIcon(MaterialSymbols.Article) },
        )
        RibbonCommand(
            label = "Page Width",
            onClick = actions.zoomToPageWidth,
            icon = { active -> TwoToneIcon({ it.pageWidth }, active) },
        )

        Divider()

        RuleLinesMenu(style.ruleLines, pageOpen, actions.setRuleLines)
        PageColorMenu(style.backgroundArgb, pageOpen, actions.setPageColor)
        RibbonCommand(
            label = "Paper Size",
            // A pane rather than a menu: this one is six fields in two groups, and it has to stay
            // open while the page changes shape underneath it.
            onClick = { actions.openPane(ToolPane.PaperSize) },
            active = style.paper != PaperSize.Auto,
            enabled = pageOpen,
            icon = { active -> TwoToneIcon({ it.paperSize }, active) },
        )
        RibbonCommand(
            label = "Hide Page Title",
            onClick = { actions.setHideTitle(!style.hideTitle) },
            active = style.hideTitle,
            enabled = pageOpen,
            icon = { active -> TwoToneIcon({ it.hidePageTitle }, active) },
        )
        RibbonCommand(
            label = "Switch Background",
            // Reads the canvas rather than the theme: once this has been used the two differ, and
            // what the button flips is what the user is actually looking at.
            onClick = { actions.setCanvasDark(!canvas.isDark) },
            icon = { MonoIcon(MaterialSymbols.WbSunny) },
        )
    }
}

@Composable
internal fun MonoIcon(icon: ImageVector, active: Boolean = false) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(18.dp),
    )
}

/*
 * There is deliberately no third helper that tints a whole Material Symbol with an accent. It was
 * tried for the File and Settings commands and it is not what an accent is here: the reference
 * colours the part of a glyph that carries its meaning and leaves the rest neutral, so a glyph that
 * is entirely accent-coloured says "all of me is the point", which is never true. Splitting the
 * symbol into two paths is the work — see the File and Settings block in `RibbonGlyphs.kt`.
 */

/** See [TwoToneRibbonButton] for why the pressed state is a different vector, not a different tint. */
@Composable
internal fun TwoToneIcon(glyph: (AppIcons) -> ImageVector, active: Boolean) {
    val icons = LocalRibbonIcons.current
    Icon(
        imageVector = glyph(if (active) icons.active else icons.idle),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun ZoomPicker(zoom: Float, onPick: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        // Rounded for display only: Page Width lands on whatever fits, and showing 86% while the
        // page is at 0.857 is the truth at the precision anyone cares about.
        ComboBox(text = "${(zoom * 100).roundToInt()}%", width = 74.dp) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ViewSettings.ZOOM_STEPS.forEach { step ->
                DropdownMenuItem(
                    text = { Text("${(step * 100).roundToInt()}%") },
                    onClick = {
                        open = false
                        onPick(step)
                    },
                )
            }
        }
    }
}

@Composable
private fun TabsLayoutMenu(current: TabsLayout, onPick: (TabsLayout) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonCommand(
            label = "Tabs Layout",
            onClick = { open = true },
            dropdown = true,
            icon = { active -> TwoToneIcon({ it.tabsLayout }, active) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CheckableItem("Vertical Tabs", current == TabsLayout.Vertical) {
                open = false
                onPick(TabsLayout.Vertical)
            }
            CheckableItem("Horizontal Tabs", current == TabsLayout.Horizontal) {
                open = false
                onPick(TabsLayout.Horizontal)
            }
        }
    }
}

@Composable
private fun RuleLinesMenu(current: RuleLines, pageOpen: Boolean, onPick: (RuleLines) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonCommand(
            label = "Paper",
            onClick = { open = true },
            active = current != RuleLines.None,
            enabled = pageOpen,
            dropdown = true,
            icon = { active -> TwoToneIcon({ it.ruleLines }, active) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RULE_LINE_LABELS.forEach { (rule, label) ->
                if (rule == RuleLines.Dotted) HorizontalDivider()
                CheckableItem(label, rule == current) {
                    open = false
                    onPick(rule)
                }
            }
        }
    }
}

@Composable
private fun PageColorMenu(current: Int?, pageOpen: Boolean, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val swatch = current?.let { Color(it) } ?: LocalCanvasColors.current.background
    val icon = remember(neutral, swatch) { pageColorGlyph(neutral, swatch) }
    Box {
        RibbonCommand(
            label = "Page Color",
            onClick = { open = true },
            enabled = pageOpen,
            dropdown = true,
            icon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(8.dp)) {
                PAGE_COLORS.chunked(4).forEach { row ->
                    Row {
                        row.forEach { argb ->
                            Box(
                                modifier = Modifier
                                    .padding(3.dp)
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(argb))
                                    .border(
                                        width = if (argb == current) 2.dp else 1.dp,
                                        color = if (argb == current) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = RoundedCornerShape(3.dp),
                                    )
                                    .clickable {
                                        open = false
                                        onPick(argb)
                                    },
                            )
                        }
                    }
                }
                CheckableItem("No Color", current == null) {
                    open = false
                    onPick(null)
                }
            }
        }
    }
}

/**
 * A menu row that shows whether it is the current choice.
 *
 * The tick occupies its slot whether or not it is drawn, so opening a menu does not shift every
 * label sideways depending on which one is selected.
 */
@Composable
private fun CheckableItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            if (selected) {
                Icon(
                    imageVector = MaterialSymbols.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Spacer(Modifier.width(18.dp))
            }
        },
        onClick = onClick,
    )
}
