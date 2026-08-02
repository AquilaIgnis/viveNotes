package com.vivenotes.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.MaterialSymbols

/** Width of a docked pane where there is room to dock one. */
val TOOL_PANEL_WIDTH = 320.dp

private val LABEL_WIDTH = 96.dp
private val FIELD_HEIGHT = 32.dp

/** The panes the ribbon can open. */
enum class ToolPane(val title: String) {
    PaperSize("Paper Size"),
    Pen("Pen"),
}

/** A pane's fields are addressable by the label beside them. */
internal object PanelTags {
    fun field(label: String) = "panel-field-$label"
}

/**
 * A tool pane docked to the right of the canvas.
 *
 * Some controls do not belong in a drop-down: Paper Size is four labelled fields and two groups,
 * and a menu that size stops being a menu and becomes a form floating over the page it describes.
 * The reference does the same thing — `docs/references/views-pages.png` is exactly this pane — and
 * a docked panel also stays open while you watch the page change under it, which is the whole point
 * of a control that alters the page's geometry.
 */
@Composable
fun ToolPanel(
    pane: ToolPane,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = pane.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = MaterialSymbols.Close,
                    contentDescription = "Close ${pane.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            content = content,
        )
    }
}

/** A group of related settings, named the way the reference names them. */
@Composable
fun ColumnScope.PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    content()
    Spacer(Modifier.height(6.dp))
}

/** One labelled control. The label column is fixed so every field in the pane lines up. */
@Composable
fun PanelRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        content()
    }
}

/** A drop-down that fills the field column, for choices with names rather than numbers. */
@Composable
fun <T> PanelChoice(
    field: String,
    current: T,
    options: List<T>,
    label: (T) -> String,
    onPick: (T) -> Unit,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FIELD_HEIGHT)
                .testTag(PanelTags.field(field))
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .then(if (enabled) Modifier.clickable { open = true } else Modifier)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label(current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Icon(
                imageVector = MaterialSymbols.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/**
 * A measurement in inches.
 *
 * Edits are held as text while they are being typed, because "8." and "" are states a number field
 * passes through on the way to a valid value and reformatting them mid-keystroke fights the user.
 * Only a parse that lands inside [range] is committed; anything else stays on screen, marked, until
 * it becomes a number.
 */
@Composable
fun PanelMeasure(
    field: String,
    value: Float,
    onCommit: (Float) -> Unit,
    enabled: Boolean = true,
    range: ClosedFloatingPointRange<Float>,
) {
    var text by remember(value, enabled) { mutableStateOf(value.trimZero()) }
    val parsed = text.trim().toFloatOrNull()
    val valid = parsed != null && parsed in range

    Row(
        modifier = Modifier
            .width(110.dp)
            .height(FIELD_HEIGHT)
            .clip(RoundedCornerShape(4.dp))
            .border(
                width = 1.dp,
                color = if (valid) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
                shape = RoundedCornerShape(4.dp),
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                val next = it.trim().toFloatOrNull()
                if (next != null && next in range) onCommit(next)
            },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .weight(1f)
                .testTag(PanelTags.field(field)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "in",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A setting named on the left with its control against the right edge.
 *
 * The Pen pane is laid out this way rather than with [PanelRow]'s fixed label column, because its
 * controls are switches and chips of different widths that the reference aligns to the margin —
 * `docs/references/pen-tooltip.jpeg`. [info] adds the (i) beside the label, which reveals a line of
 * explanation in place rather than floating a tooltip: "Hold to draw shape" is not self-evident, and
 * a description that has to be hovered is no use on a tablet.
 */
@Composable
fun PanelSetting(label: String, info: String? = null, trailing: @Composable () -> Unit) {
    var explaining by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (info != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = MaterialSymbols.Info,
                        contentDescription = "About $label",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(17.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { explaining = !explaining },
                    )
                }
            }
            trailing()
        }
        if (info != null && explaining) {
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
fun PanelToggle(field: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.testTag(PanelTags.field(field)),
    )
}

/**
 * A whole number chosen from a short range, shown as the chip the reference shows.
 *
 * A menu rather than a spinner: these ranges are six values wide, so picking is one tap where
 * stepping from 5 to 0 would be five, and it shows the whole range at once.
 */
@Composable
fun PanelStepper(field: String, value: Int, range: IntRange, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(FIELD_HEIGHT)
                .testTag(PanelTags.field(field))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            range.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == 0) "0 — off" else option.toString()) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/**
 * A value on a track with a step either side, laid out as the reference's Thickness row.
 *
 * The buttons exist because a slider this short cannot be nudged by one: dragging picks a
 * neighbourhood, and − and + pick a number.
 */
@Composable
fun ColumnScope.PanelSlider(
    field: String,
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(MaterialSymbols.Remove, "Decrease $label", value > range.first) {
            onChange(value - 1)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .testTag(PanelTags.field(field)),
        )
        StepButton(MaterialSymbols.Add, "Increase $label", value < range.last) {
            onChange(value + 1)
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(50))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 8.5 stays 8.5; 11.0 shows as 11, because nobody writes a page size that way. */
private fun Float.trimZero(): String =
    if (this == toInt().toFloat()) toInt().toString() else toString()

private const val DISABLED_ALPHA = 0.42f
