package com.vivenotes.ui.panel

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vivenotes.R
import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins

/**
 * The Paper Size pane, laid out as `memory/references/views-pages.png` does it.
 *
 * Width and Height are readouts for a named size and fields for [PaperSize.Custom] — the same two
 * rows either way, because a size *is* a width and a height, and hiding them for the named sizes
 * would leave the user guessing what "B5" means in inches.
 *
 * "Save current page as template" appears in the reference but is deliberately absent: page
 * templates are crossed out in `insertTab.png`.
 */
@Composable
fun ColumnScope.PaperSizePanelContent(
    style: PageStyle,
    onPickSize: (PaperSize) -> Unit,
    onPickOrientation: (Orientation) -> Unit,
    onSetCustomPaper: (PaperDimensions) -> Unit,
    onSetMargins: (PrintMargins) -> Unit,
) {
    val custom = style.paper == PaperSize.Custom
    val inches = style.paperInches ?: PaperDimensions.DEFAULT
    val paperRange = PaperDimensions.MIN_INCHES..PaperDimensions.MAX_INCHES
    val marginRange = 0f..PrintMargins.MAX_INCHES
    val resources = LocalResources.current

    PanelSection(stringResource(R.string.paper_section_size)) {
        PanelRow(stringResource(R.string.paper_size)) {
            PanelChoice(
                field = "Size",
                current = style.paper,
                options = PaperSize.entries,
                label = { it.label?.let(resources::getString) ?: it.name },
                onPick = onPickSize,
            )
        }
        PanelRow(stringResource(R.string.paper_orientation)) {
            PanelChoice(
                field = "Orientation",
                current = style.orientation,
                options = Orientation.entries,
                label = { resources.getString(it.label) },
                onPick = onPickOrientation,
                // An unbounded page has no orientation to turn; the canvas grows either way.
                enabled = style.paper != PaperSize.Auto,
            )
        }
        PanelRow(stringResource(R.string.paper_width)) {
            PanelMeasure(
                field = "Width",
                value = inches.widthInches,
                onCommit = { onSetCustomPaper(inches.copy(widthInches = it)) },
                enabled = custom,
                range = paperRange,
            )
        }
        PanelRow(stringResource(R.string.paper_height)) {
            PanelMeasure(
                field = "Height",
                value = inches.heightInches,
                onCommit = { onSetCustomPaper(inches.copy(heightInches = it)) },
                enabled = custom,
                range = paperRange,
            )
        }
    }

    PanelSection(stringResource(R.string.paper_section_margins)) {
        PanelRow(stringResource(R.string.paper_margin_top)) {
            PanelMeasure(
                field = "Top",
                value = style.margins.topInches,
                onCommit = { onSetMargins(style.margins.copy(topInches = it)) },
                range = marginRange,
            )
        }
        PanelRow(stringResource(R.string.paper_margin_bottom)) {
            PanelMeasure(
                field = "Bottom",
                value = style.margins.bottomInches,
                onCommit = { onSetMargins(style.margins.copy(bottomInches = it)) },
                range = marginRange,
            )
        }
        PanelRow(stringResource(R.string.paper_margin_left)) {
            PanelMeasure(
                field = "Left",
                value = style.margins.leftInches,
                onCommit = { onSetMargins(style.margins.copy(leftInches = it)) },
                range = marginRange,
            )
        }
        PanelRow(stringResource(R.string.paper_margin_right)) {
            PanelMeasure(
                field = "Right",
                value = style.margins.rightInches,
                onCommit = { onSetMargins(style.margins.copy(rightInches = it)) },
                range = marginRange,
            )
        }
    }
}

/** Null for the ISO sizes, whose names are the same in every language. */
@get:StringRes
internal val PaperSize.label: Int?
    get() = when (this) {
        PaperSize.Auto -> R.string.paper_size_auto
        PaperSize.Billfold -> R.string.paper_size_billfold
        PaperSize.Custom -> R.string.paper_size_custom
        PaperSize.A3, PaperSize.A4, PaperSize.A5, PaperSize.A6,
        PaperSize.B4, PaperSize.B5, PaperSize.B6 -> null
    }

@get:StringRes
internal val Orientation.label: Int
    get() = when (this) {
        Orientation.Portrait -> R.string.paper_orientation_portrait
        Orientation.Landscape -> R.string.paper_orientation_landscape
    }
