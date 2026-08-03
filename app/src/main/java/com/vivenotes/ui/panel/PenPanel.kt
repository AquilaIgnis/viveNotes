package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.data.LineType
import com.vivenotes.data.PenKind
import com.vivenotes.data.PenPreset

/** Test tags for the parts of the pane that show state without showing text. */
object PenPanelTags {
    const val PREVIEW = "pen-preview"
    const val ADD_COLOR = "pen-add-color"

    /** The rainbow swatch that ends the palette, and the picker it opens. */
    const val CUSTOM_COLOR = "pen-color-custom"
    const val WHEEL = "pen-color-wheel"
    const val WHEEL_PREVIEW = "pen-color-wheel-preview"
    const val BRIGHTNESS = "pen-color-brightness"

    fun kind(kind: PenKind) = "pen-kind-${kind.name}"
    fun lineType(lineType: LineType) = "pen-line-${lineType.name}"
    fun color(argb: Int) = "pen-color-$argb"
}

/**
 * The pen settings pane, laid out as `docs/references/pen-tooltip.jpeg` lays it out.
 *
 * Sized to fit the floating settings surface without scrolling in the common tablet layout. The
 * popup itself constrains and scrolls the content when the screen or font scale leaves less room.
 *
 * Reached by holding a pen in the Draw tab rather than by a ribbon button, because the pen it edits
 * is the one you held — there is no "which pen?" question to answer first.
 *
 * Pressure sensitivity shows only for the calligraphy pen. The fountain pen is this app's plain
 * pen — one width for the whole stroke — so the control is absent rather than disabled.
 *
 * Two things in the reference are deliberately not here. The middle pen type is crossed out, so it
 * is absent entirely rather than disabled; and the overflow beside Hold to draw shape, which chooses
 * *which* shapes are recognised, waits for shape recognition to exist at all (`docs/inkPlan.md` §5).
 * Add colour is placed and inert, the same way the View tab places Full Page View: it holds the spot
 * the reference gives it and plainly does not work yet. It is not the wheel at the end of the
 * palette in disguise — that picks an ink, where + would add a swatch to the row and keep it, which
 * needs a stored custom palette this does not have.
 */
@Composable
fun ColumnScope.PenPanelContent(
    pen: PenPreset,
    palette: List<Int>,
    onChange: (PenPreset) -> Unit,
    onAddColor: (Int) -> Unit = {},
) {
    StrokePreview(pen)

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PenKindCard(
            kind = PenKind.Fountain,
            icon = MaterialSymbols.Edit,
            selected = pen.kind == PenKind.Fountain,
            modifier = Modifier.weight(1f),
        ) { onChange(pen.copy(kind = PenKind.Fountain)) }
        PenKindCard(
            kind = PenKind.Calligraphy,
            icon = MaterialSymbols.Brush,
            selected = pen.kind == PenKind.Calligraphy,
            modifier = Modifier.weight(1f),
        ) { onChange(pen.copy(kind = PenKind.Calligraphy)) }
    }

    Spacer(Modifier.height(8.dp))
    LineTypePicker(pen.lineType) { onChange(pen.copy(lineType = it)) }
    Spacer(Modifier.height(4.dp))

    PanelSetting(
        label = "Hold to draw shape",
        info = "Pause with the pen still down at the end of a stroke and a rough circle, box or " +
            "line is replaced by a clean one.",
    ) {
        PanelToggle("Hold to draw shape", pen.holdToDrawShape) {
            onChange(pen.copy(holdToDrawShape = it))
        }
    }
    PanelSetting(
        label = "Scribble to erase",
        info = "Scrub back and forth over ink to rub it out, without switching to the eraser.",
    ) {
        PanelToggle("Scribble to erase", pen.scribbleToErase) {
            onChange(pen.copy(scribbleToErase = it))
        }
    }
    PanelSlider(
        field = "Thickness",
        label = "Thickness",
        value = pen.thickness,
        range = PenPreset.MIN_THICKNESS..PenPreset.MAX_THICKNESS,
        onChange = { onChange(pen.copy(thickness = it)) },
        sizePreviewColor = Color(pen.colorArgb),
    )

    Spacer(Modifier.height(2.dp))
    // Absent for the fountain pen rather than disabled: that pen draws one width by definition, so
    // a pressure control on it is not a setting turned off, it is a setting that does not exist.
    if (pen.kind != PenKind.Fountain) {
        PanelSetting(
            label = "Pressure sensitivity",
            info = "How much harder pressing thickens the line. 0 turns off pressure response; " +
                "the broad nib still makes downstrokes and cross-strokes different.",
        ) {
            PanelStepper("Pressure sensitivity", pen.pressure, 0..PenPreset.MAX_PRESSURE) {
                onChange(pen.copy(pressure = it))
            }
        }
    }
    PanelSetting(
        label = "Stabilization",
        info = "Smooths out shake. A little goes a long way — high settings make the ink lag behind " +
            "the pen.",
    ) {
        PanelStepper("Stabilization", pen.stabilization, 0..PenPreset.MAX_STABILIZATION) {
            onChange(pen.copy(stabilization = it))
        }
    }

    Spacer(Modifier.height(2.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Color",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = MaterialSymbols.Add,
            contentDescription = "Add color",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .testTag(PenPanelTags.ADD_COLOR)
                .alpha(INERT_ALPHA),
        )
    }
    ColorSwatches(
        palette = palette,
        current = pen.colorArgb,
        // A tap is an explicit choice. It must stay black or white across future theme changes.
        onPick = { onChange(pen.copy(colorArgb = it, colorFollowsTheme = false)) },
        onAddColor = onAddColor,
    )
    Spacer(Modifier.height(6.dp))
}

/** Three compact line samples; their shape is the label, so no redundant "Line type" row. */
@Composable
private fun LineTypePicker(current: LineType, onPick: (LineType) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineType.entries.forEach { lineType ->
            val selected = current == lineType
            val lineColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(width = 58.dp, height = 34.dp)
                    .testTag(PenPanelTags.lineType(lineType))
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .selectable(selected = selected, onClick = { onPick(lineType) })
                    .semantics {
                        contentDescription = "${lineType.label} line"
                        this.selected = selected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(width = 38.dp, height = 12.dp)) {
                    val strokeWidth = 2.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(1.dp.toPx(), center.y),
                        end = Offset(size.width - 1.dp.toPx(), center.y),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                        pathEffect = lineType.pathEffect(strokeWidth),
                    )
                }
            }
        }
    }
}

/**
 * What this pen draws like, drawn with this pen's own settings.
 *
 * Worth the pixels because most of the pane is numbers: thickness 5 and stabilization 1 mean nothing
 * until you see them. The curve is fixed so that changing a setting changes only what the setting
 * does — a preview that also wobbled would make every comparison useless.
 */
@Composable
private fun StrokePreview(pen: PenPreset) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(38.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .testTag(PenPanelTags.PREVIEW),
    ) {
        Canvas(Modifier.fillMaxWidth().height(76.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.14f, size.height * 0.70f)
                cubicTo(
                    size.width * 0.34f, size.height * 0.12f,
                    size.width * 0.48f, size.height * 0.18f,
                    size.width * 0.58f, size.height * 0.52f,
                )
                cubicTo(
                    size.width * 0.66f, size.height * 0.80f,
                    size.width * 0.74f, size.height * 0.86f,
                    size.width * 0.86f, size.height * 0.62f,
                )
            }
            drawPath(
                path = path,
                color = Color(pen.colorArgb),
                style = Stroke(
                    // Thickness is a pen setting, not a page measurement, so it reads as a width in
                    // dp here and will mean the same number of dp on the page.
                    width = pen.thickness.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = pen.lineType.pathEffect(pen.thickness.dp.toPx()),
                ),
            )
        }
    }
}

private fun LineType.pathEffect(widthPx: Float): PathEffect? = when (this) {
    LineType.Solid -> null
    LineType.Dashed -> PathEffect.dashPathEffect(floatArrayOf(widthPx * 2.6f, widthPx * 1.8f))
    // A dot is a zero-length dash under a round cap, which is what makes the gap the whole story.
    LineType.Dotted -> PathEffect.dashPathEffect(floatArrayOf(0.01f, widthPx * 2f))
}

@Composable
private fun PenKindCard(
    kind: PenKind,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .height(56.dp)
            .testTag(PenPanelTags.kind(kind))
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(text = kind.label, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/**
 * The colour row: the swatches, then the wheel that covers everything they leave out.
 *
 * A ring rather than a tick marks the current colour: a tick has to be drawn in something, and no
 * one colour reads on both the black swatch and the yellow one.
 *
 * The row rolls. A colour mixed on the wheel is inserted at the front and the tail is dropped, so
 * the wheel stays a wheel rather than doubling as a swatch for whatever was last mixed — what you
 * picked is a swatch like any other, in the place your eye goes first. The cost is that the row is
 * finite: nine custom colours will push the shipped palette out entirely, black and white included.
 * They are one trip back through the wheel away, which is why nothing here is pinned.
 *
 * That cost is why the row is charged **once per visit to the wheel, when the wheel closes**, and
 * not once per touch on it. Hunting for a colour means trying several, and a row that took a spot
 * for each would spend the whole palette on the near-misses; only the colour someone left the
 * picker on was actually chosen. The pen still follows every touch, because that is the preview.
 */
@Composable
private fun ColorSwatches(
    palette: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
    onAddColor: (Int) -> Unit,
) {
    var wheelOpen by remember { mutableStateOf(false) }
    // What the wheel has been left on. Null until it is touched, which is what makes opening the
    // picker and thinking better of it cost nothing.
    var mixed by remember { mutableStateOf<Int?>(null) }

    fun closeWheel() {
        wheelOpen = false
        mixed?.let(onAddColor)
        mixed = null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        palette.forEach { argb ->
            Swatch(
                selected = argb == current,
                tag = PenPanelTags.color(argb),
                onClick = { onPick(argb) },
            ) {
                drawRect(Color(argb))
            }
        }

        Box {
            Swatch(
                // Normally false, since a colour off the wheel joins the row and is ringed there.
                // It survives for the ink a rolled-off swatch left behind, which has nothing else
                // to mark it.
                selected = current !in palette,
                tag = PenPanelTags.CUSTOM_COLOR,
                description = "Custom color",
                onClick = { wheelOpen = true },
            ) {
                drawRect(Brush.sweepGradient(HUE_STOPS, center))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                    radius = size.minDimension / 2f,
                )
            }

            // Both ways out lead to the same place: Done and a tap outside are the same decision,
            // so a colour must not depend on which one closed the picker.
            FloatingSettingsPanel(
                expanded = wheelOpen,
                onDismissRequest = { closeWheel() },
                title = "Custom color",
            ) {
                ColorWheelContent(
                    initialArgb = current,
                    onPick = {
                        mixed = it
                        onPick(it)
                    },
                    onDone = { closeWheel() },
                )
            }
        }
    }
}

/**
 * One round target in the colour row, painted by [fill] and ringed when it is the current one.
 *
 * The paint is a draw lambda rather than a colour because the last swatch is a gradient, and a
 * background modifier that took only a solid would have forced the wheel to be a different control
 * sitting beside the palette instead of the last member of it.
 */
@Composable
internal fun Swatch(
    selected: Boolean,
    tag: String,
    description: String? = null,
    onClick: () -> Unit,
    fill: DrawScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .size(SWATCH_SIZE)
            .testTag(tag)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .padding(if (selected) 4.dp else 2.dp)
            .clip(RoundedCornerShape(50))
            // Draw modifiers paint in the order they are chained, so the fill goes down first and
            // the hairline outline over it — the same layering the solid swatches had.
            .drawBehind(fill)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .then(
                if (description == null) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = description }
                },
            ),
    )
}

/**
 * Ten targets have to fit one 320dp panel without wrapping, which is what sets this rather than
 * taste. The row stays one line because the reference draws it as one.
 */
internal val SWATCH_SIZE = 26.dp

/** Placed but not wired, so it holds the reference's layout without pretending to work. */
private const val INERT_ALPHA = 0.42f
