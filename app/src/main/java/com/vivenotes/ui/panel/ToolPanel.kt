package com.vivenotes.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.shell.swipeRight
import kotlin.math.floor
import kotlin.math.roundToInt

/** Width of a docked pane where there is room to dock one. */
val TOOL_PANEL_WIDTH = 320.dp

private val LABEL_WIDTH = 96.dp
private val FIELD_HEIGHT = 32.dp

/** The panes the ribbon can open. */
enum class ToolPane(val title: String) {
    PaperSize("Paper Size"),
    AiModels("Integrated AI"),
    Recognition("Recognition"),
    Hardware("Hardware"),
}

/** A pane's fields are addressable by the label beside them. */
internal object PanelTags {
    fun field(label: String) = "panel-field-$label"
    fun sizePreview(label: String) = "panel-size-preview-$label"

    /** The pane itself, which the dismiss swipe is performed on. */
    const val PANE = "tool-pane"
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
            .background(MaterialTheme.colorScheme.surface)
            // Dismissed the way it arrived: this pane is docked on the right, so it is pushed off to
            // the right. The same gesture and threshold the notebook rail and page list use to hide
            // themselves leftward — see `shell/SwipeToHide.kt`.
            .swipeRight(onClose)
            .testTag(PanelTags.PANE),
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

/**
 * A group of related settings, named the way the reference names them.
 *
 * [info] adds the same (i) a single setting row can carry, for an explanation that belongs to the
 * whole group rather than to one row of it — the pen-button bindings are three rows explained by one
 * fact about how a pen reports its clicks.
 */
@Composable
fun ColumnScope.PanelSection(
    title: String,
    info: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var explaining by remember { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (info != null) {
            Spacer(Modifier.width(6.dp))
            InfoIcon(title) { explaining = !explaining }
        }
    }
    if (info != null && explaining) {
        PanelExplanation(info)
    }
    Spacer(Modifier.height(4.dp))
    content()
    Spacer(Modifier.height(6.dp))
}

/**
 * The (i) that reveals a line of explanation in place, rather than floating a tooltip: "Hold to draw
 * shape" is not self-evident, and a description that has to be hovered is no use on a tablet.
 *
 * Shared by [PanelSetting] and [PanelSection] so the affordance is one size, one colour and one
 * content description wherever it appears. The expanded state stays with the caller, which is what
 * lets a row and its section disclose independently.
 */
@Composable
private fun InfoIcon(label: String, onClick: () -> Unit) {
    Icon(
        imageVector = MaterialSymbols.Info,
        contentDescription = "About $label",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(17.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun PanelExplanation(info: String) {
    Text(
        text = info,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * One labelled control. The label column is fixed so every field in the pane lines up.
 *
 * [labelWidth] is per-*group*, not per-row: widening it for one row of a group would break the
 * alignment the fixed column exists for. The default fits Paper Size, whose labels are one word; the
 * pen-button bindings name a click count ("Double click") and need more.
 */
@Composable
fun PanelRow(
    label: String,
    labelWidth: Dp = LABEL_WIDTH,
    content: @Composable () -> Unit,
) {
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
            modifier = Modifier.width(labelWidth),
        )
        content()
    }
}

/** Wide enough for "Double click:" — see [PanelRow]. */
val WIDE_LABEL_WIDTH = 104.dp

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
 * `docs/references/pen-tooltip.jpeg`. [info] adds the (i) beside the label — see [InfoIcon].
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
                    InfoIcon(label) { explaining = !explaining }
                }
            }
            trailing()
        }
        if (info != null && explaining) {
            PanelExplanation(info)
        }
    }
}

/**
 * Material's switch at [SWITCH_SCALE], because at full size it is the heaviest thing in a pane of
 * text and reads as the point of the row rather than the answer to it.
 *
 * Drawn small rather than built small: `Switch` has no size parameter, so this is `requiredSize` to
 * keep its own geometry, `scale` to draw it down, and a box of the scaled size so the row lays out
 * against what is actually on screen — without that last part every toggle would sit 10dp shy of the
 * right edge that the other controls line up with.
 */
@Composable
fun PanelToggle(field: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier.size(SWITCH_WIDTH * SWITCH_SCALE, SWITCH_HEIGHT * SWITCH_SCALE),
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .requiredSize(SWITCH_WIDTH, SWITCH_HEIGHT)
                .scale(SWITCH_SCALE)
                .testTag(PanelTags.field(field)),
        )
    }
}

/** 40% smaller. */
private const val SWITCH_SCALE = 0.6f

/** Material 3's own track size, which `Switch` gives no way to ask for. */
private val SWITCH_WIDTH = 52.dp
private val SWITCH_HEIGHT = 32.dp

/**
 * A button at the smallest size M3 Expressive defines, for panes that are a column of controls.
 *
 * **Sized through the expressive API rather than by hand.** `ButtonDefaults.ExtraSmallContainerHeight`
 * comes with `contentPaddingFor`, which is the padding Material intends at that height — writing
 * `height(32.dp)` and guessing the padding instead is how a button ends up with its label off-centre
 * or clipped in another locale.
 *
 * A default-size button is 40dp plus its padding, which is right for a dialog and wasteful in a 320dp
 * pane where four of them stack under a preview. This gets a third of that height back per row.
 *
 * [colors] is the escape hatch for a row that should read as its own family — pass
 * `ButtonDefaults.buttonColors(containerColor = colorScheme.tertiary, contentColor = onTertiary)` and
 * the whole row fills with the complement instead of the brand azure. Taking a `ButtonColors` rather
 * than a single accent keeps the container/label pairing together, which is the pairing that has to
 * stay legible.
 */
@Composable
fun PanelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        contentPadding = ButtonDefaults.contentPaddingFor(PANEL_BUTTON_HEIGHT),
        modifier = modifier.heightIn(min = PANEL_BUTTON_HEIGHT),
        content = content,
    )
}

private val PANEL_BUTTON_HEIGHT = ButtonDefaults.ExtraSmallContainerHeight

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
 *
 * Fractional because a pen's nib is — `PenPreset.thickness` steps by half a dp. Every other setting
 * on a panel is a whole number and reaches this through the `Int` overload below.
 */
@Composable
fun ColumnScope.PanelSlider(
    field: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    showTicks: Boolean = true,
    sizePreviewColor: Color? = null,
    outlineSizePreview: Boolean = false,
    /** How far − and + move, and how far apart the stops are when [showTicks] is on. */
    step: Float = 1f,
    /** How the number reads. A ruler's length means nothing in dp and everything in inches. */
    format: (Float) -> String = ::trimTrailingZero,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    var pointerHeld by remember { mutableStateOf(false) }

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
            text = format(value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(MaterialSymbols.Remove, "Decrease $label", value > range.start) {
            onChange((value - step).coerceAtLeast(range.start))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                // Slider's interaction source starts at drag slop, which is too late for a tap-and-
                // hold preview. Observe the initial pointer pass without consuming it so the slider
                // still owns all value changes.
                .pointerInput(Unit) {
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                pointerHeld = awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .any { it.pressed }
                            }
                        }
                    } finally {
                        pointerHeld = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = value,
                // Material lands the thumb on a stop, but in track pixels rather than in steps, so
                // snap before storing: a nib is 5.5, never 5.499998.
                onValueChange = { onChange(if (showTicks) snapToStep(it, range, step) else it) },
                valueRange = range,
                // The eraser is a continuous physical size, while the compact pen range benefits
                // from discrete stops. Passing false removes Material's tick marks as well as the
                // stops without changing the setting stored by the editor.
                steps = if (showTicks) {
                    (stopCount(range, step) - 1).coerceAtLeast(0)
                } else {
                    0
                },
                interactionSource = interactionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .testTag(PanelTags.field(field)),
            )
            if (sizePreviewColor != null && (pointerHeld || pressed || dragged)) {
                val fraction = (value - range.start) /
                    (range.endInclusive - range.start).coerceAtLeast(1f)
                SliderSizePreview(
                    field = field,
                    diameter = value,
                    color = sizePreviewColor,
                    outlined = outlineSizePreview,
                    fraction = fraction.coerceIn(0f, 1f),
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        StepButton(MaterialSymbols.Add, "Increase $label", value < range.endInclusive) {
            onChange((value + step).coerceAtMost(range.endInclusive))
        }
    }
}

/**
 * The whole-number settings — border widths, eraser and ruler sizes — which are most of them.
 *
 * Kept as its own signature rather than pushing `Float` out to every call site: a border is two dp
 * wide, not 2.0, and a panel that offered half a pixel of table rule would be offering nothing.
 */
@Composable
fun ColumnScope.PanelSlider(
    field: String,
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    showTicks: Boolean = true,
    sizePreviewColor: Color? = null,
    outlineSizePreview: Boolean = false,
    /** How far − and + move. One is right for a nib; a ruler is measured in hundreds of dp. */
    step: Int = 1,
    format: (Int) -> String = { it.toString() },
) {
    PanelSlider(
        field = field,
        label = label,
        value = value.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        onChange = { onChange(it.toInt()) },
        showTicks = showTicks,
        sizePreviewColor = sizePreviewColor,
        outlineSizePreview = outlineSizePreview,
        step = step.toFloat(),
        format = { format(it.toInt()) },
    )
}

/** How many stops [step] divides a range into — one more than Material's `steps`, which counts gaps. */
private fun stopCount(range: ClosedFloatingPointRange<Float>, step: Float): Int =
    if (step <= 0f) 0 else ((range.endInclusive - range.start) / step).roundToInt()

private fun snapToStep(raw: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float =
    if (step <= 0f) {
        raw
    } else {
        (range.start + ((raw - range.start) / step).roundToInt() * step)
            .coerceIn(range.start, range.endInclusive)
    }

/** Reads a half-step as "5" and "5.5" rather than "5.0" — the trailing zero is noise on a nib. */
private fun trimTrailingZero(value: Float): String =
    if (value == floor(value)) value.toInt().toString() else value.toString()

/** A transient, true-to-size sample drawn at the live thumb position while it is held. */
@Composable
private fun SliderSizePreview(
    field: String,
    diameter: Float,
    color: Color,
    outlined: Boolean,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.testTag(PanelTags.sizePreview(field))) {
        val indicatorRadius = diameter.dp.toPx() / 2f
        val bubbleDiameter = (diameter + SIZE_PREVIEW_PADDING).coerceAtLeast(
            MIN_SIZE_PREVIEW_CONTAINER,
        ).dp.toPx()
        val bubbleRadius = bubbleDiameter / 2f
        val trackInset = SLIDER_HORIZONTAL_PADDING.toPx()
        val intendedX = trackInset + (size.width - trackInset * 2f) * fraction
        val center = Offset(
            x = if (bubbleDiameter <= size.width) {
                intendedX.coerceIn(bubbleRadius, size.width - bubbleRadius)
            } else {
                size.width / 2f
            },
            y = size.height / 2f,
        )

        drawCircle(color = surfaceColor, radius = bubbleRadius, center = center)
        drawCircle(
            color = borderColor,
            radius = (bubbleRadius - 0.5.dp.toPx()).coerceAtLeast(0f),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
        if (outlined) {
            drawCircle(color = color.copy(alpha = 0.12f), radius = indicatorRadius, center = center)
            drawCircle(
                color = color,
                radius = (indicatorRadius - 0.75.dp.toPx()).coerceAtLeast(0f),
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        } else {
            drawCircle(color = color, radius = indicatorRadius, center = center)
            // Keep very light pen colours legible against the preview surface.
            if (color.red + color.green + color.blue > 2.55f) {
                drawCircle(
                    color = borderColor,
                    radius = (indicatorRadius - 0.5.dp.toPx()).coerceAtLeast(0f),
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}

private const val SIZE_PREVIEW_PADDING = 8f
private const val MIN_SIZE_PREVIEW_CONTAINER = 28f
private val SLIDER_HORIZONTAL_PADDING = 6.dp

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
