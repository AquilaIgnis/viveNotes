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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.data.EditorDefaults
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
 * so it can be composed in a test with neither a database nor a preferences store. Twelve separate
 * lambda parameters would make that principle unreadable, so they travel together.
 */
@Immutable
data class ViewActions(
    val setRuleLines: (RuleLines) -> Unit,
    /** The ruling new pages start on. Holding an entry in the Paper menu, never picking one. */
    val setDefaultRuleLines: (RuleLines) -> Unit,
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
    RuleLines.None to R.string.view_paper_none,
    RuleLines.Standard to R.string.view_paper_standard_ruled,
    RuleLines.Wide to R.string.view_paper_wide_ruled,
    RuleLines.Dotted to R.string.view_paper_dotted,
    RuleLines.Hexagonal to R.string.view_paper_hexagonal,
    RuleLines.GridMedium to R.string.view_paper_grid_medium,
    RuleLines.GridLarge to R.string.view_paper_grid_large,
)

/**
 * The View tab, from the reference screenshot.
 *
 * Dock to Desktop, New Docked Window, New Window and New Quick Note are crossed out in that
 * screenshot and so are not here at all.
 *
 * Full Page View and Normal View are gone too, removed on 2026-08-09. They were placed and inert,
 * holding the spot the reference gives them, and that was the wrong trade for this pair: this app
 * has no chrome to hide that the two would toggle between, and two dead buttons at the head of the
 * tab pushed the controls that do work off to the right. Dropped, not deferred.
 */
@Composable
internal fun ViewTab(
    style: PageStyle,
    settings: ViewSettings,
    actions: ViewActions,
    pageOpen: Boolean,
    /** Tagged in the Paper menu, so what a new page will look like is visible there. */
    defaultRuleLines: RuleLines = EditorDefaults.FALLBACK_RULE_LINES,
) {
    val canvas = LocalCanvasColors.current

    ScrollingRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        TabsLayoutMenu(settings.tabsLayout, actions.setTabsLayout)

        Divider()

        Text(
            text = stringResource(R.string.view_zoom_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        ZoomPicker(settings.zoom, actions.setZoom)
        RibbonButton(MaterialSymbols.ZoomIn, stringResource(R.string.view_zoom_in), onClick = actions.zoomIn)
        RibbonButton(MaterialSymbols.ZoomOut, stringResource(R.string.view_zoom_out), onClick = actions.zoomOut)
        RibbonCommand(
            label = stringResource(R.string.view_zoom_percent, 100),
            onClick = { actions.setZoom(1f) },
            icon = { MonoIcon(MaterialSymbols.Article) },
        )
        RibbonCommand(
            label = stringResource(R.string.view_page_width),
            onClick = actions.zoomToPageWidth,
            icon = { active -> TwoToneIcon({ it.pageWidth }, active) },
        )

        Divider()

        RuleLinesMenu(
            current = style.ruleLines,
            default = defaultRuleLines,
            pageOpen = pageOpen,
            onPick = actions.setRuleLines,
            onSetDefault = actions.setDefaultRuleLines,
        )
        PageColorMenu(style.backgroundArgb, pageOpen, actions.setPageColor)
        RibbonCommand(
            label = stringResource(R.string.view_paper_size),
            // A pane rather than a menu: this one is six fields in two groups, and it has to stay
            // open while the page changes shape underneath it.
            onClick = { actions.openPane(ToolPane.PaperSize) },
            active = style.paper != PaperSize.Auto,
            enabled = pageOpen,
            icon = { active -> TwoToneIcon({ it.paperSize }, active) },
        )
        RibbonCommand(
            label = stringResource(R.string.view_hide_page_title),
            onClick = { actions.setHideTitle(!style.hideTitle) },
            active = style.hideTitle,
            enabled = pageOpen,
            icon = { active -> TwoToneIcon({ it.hidePageTitle }, active) },
        )
        RibbonCommand(
            label = stringResource(R.string.view_switch_background),
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
        ComboBox(text = stringResource(R.string.view_zoom_percent, (zoom * 100).roundToInt()), width = 74.dp) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ViewSettings.ZOOM_STEPS.forEach { step ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.view_zoom_percent, (step * 100).roundToInt())) },
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
            label = stringResource(R.string.view_tabs_layout),
            onClick = { open = true },
            dropdown = true,
            icon = { active -> TwoToneIcon({ it.tabsLayout }, active) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CheckableItem(stringResource(R.string.view_tabs_vertical), current == TabsLayout.Vertical) {
                open = false
                onPick(TabsLayout.Vertical)
            }
            CheckableItem(stringResource(R.string.view_tabs_horizontal), current == TabsLayout.Horizontal) {
                open = false
                onPick(TabsLayout.Horizontal)
            }
        }
    }
}

/**
 * The ruling picker: a tap rules the open page, a hold makes that ruling the one new pages start on.
 *
 * Two marks because they answer different questions — the tick is this page, the tag is the next
 * one — and a page ruled differently from the default is exactly when both are worth seeing.
 */
@Composable
private fun RuleLinesMenu(
    current: RuleLines,
    default: RuleLines,
    pageOpen: Boolean,
    onPick: (RuleLines) -> Unit,
    onSetDefault: (RuleLines) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonCommand(
            label = stringResource(R.string.view_paper),
            onClick = { open = true },
            active = current != RuleLines.None,
            enabled = pageOpen,
            dropdown = true,
            icon = { active -> TwoToneIcon({ it.ruleLines }, active) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DefaultHint(stringResource(R.string.view_paper_hint))
            HorizontalDivider()
            RULE_LINE_LABELS.forEach { (rule, label) ->
                if (rule == RuleLines.Dotted) HorizontalDivider()
                MenuRow(
                    label = stringResource(label),
                    isDefault = rule == default,
                    onClick = {
                        open = false
                        onPick(rule)
                    },
                    onLongClick = { onSetDefault(rule) },
                    leadingIcon = { CheckSlot(rule == current) },
                )
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
            label = stringResource(R.string.view_page_color),
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
                CheckableItem(stringResource(R.string.view_page_color_none), current == null) {
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
        leadingIcon = { CheckSlot(selected) },
        onClick = onClick,
    )
}

@Composable
private fun CheckSlot(selected: Boolean) {
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
}
