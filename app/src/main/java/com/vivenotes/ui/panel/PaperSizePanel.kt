package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins

/**
 * The Paper Size pane, laid out as `docs/references/views-pages.png` does it.
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

    PanelSection("Paper size") {
        PanelRow("Size") {
            PanelChoice(
                field = "Size",
                current = style.paper,
                options = PaperSize.entries,
                label = { it.name },
                onPick = onPickSize,
            )
        }
        PanelRow("Orientation") {
            PanelChoice(
                field = "Orientation",
                current = style.orientation,
                options = Orientation.entries,
                label = { it.name },
                onPick = onPickOrientation,
                // An unbounded page has no orientation to turn; the canvas grows either way.
                enabled = style.paper != PaperSize.Auto,
            )
        }
        PanelRow("Width") {
            PanelMeasure(
                field = "Width",
                value = inches.widthInches,
                onCommit = { onSetCustomPaper(inches.copy(widthInches = it)) },
                enabled = custom,
                range = paperRange,
            )
        }
        PanelRow("Height") {
            PanelMeasure(
                field = "Height",
                value = inches.heightInches,
                onCommit = { onSetCustomPaper(inches.copy(heightInches = it)) },
                enabled = custom,
                range = paperRange,
            )
        }
    }

    PanelSection("Print margins") {
        PanelRow("Top") {
            PanelMeasure(
                field = "Top",
                value = style.margins.topInches,
                onCommit = { onSetMargins(style.margins.copy(topInches = it)) },
                range = marginRange,
            )
        }
        PanelRow("Bottom") {
            PanelMeasure(
                field = "Bottom",
                value = style.margins.bottomInches,
                onCommit = { onSetMargins(style.margins.copy(bottomInches = it)) },
                range = marginRange,
            )
        }
        PanelRow("Left") {
            PanelMeasure(
                field = "Left",
                value = style.margins.leftInches,
                onCommit = { onSetMargins(style.margins.copy(leftInches = it)) },
                range = marginRange,
            )
        }
        PanelRow("Right") {
            PanelMeasure(
                field = "Right",
                value = style.margins.rightInches,
                onCommit = { onSetMargins(style.margins.copy(rightInches = it)) },
                range = marginRange,
            )
        }
    }
}
