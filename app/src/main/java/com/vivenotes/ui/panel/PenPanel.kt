package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.data.LineType
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenKind
import com.vivenotes.data.PenPreset

/** Test tags for the parts of the pane that show state without showing text. */
object PenPanelTags {
    const val PREVIEW = "pen-preview"
    const val ADD_COLOR = "pen-add-color"
    fun kind(kind: PenKind) = "pen-kind-${kind.name}"
    fun color(argb: Int) = "pen-color-$argb"
}

/**
 * The pen settings pane, laid out as `docs/references/pen-tooltip.jpeg` lays it out.
 *
 * Sized to fit a tablet pane without scrolling — every control visible at once, because a settings
 * form you have to scroll to compare is a settings form you cannot compare. The scroll in
 * [ToolPanel] stays as a fallback for a phone-width pane, a large font scale, or a description
 * opened by one of the (i) buttons.
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
 * the reference gives it and plainly does not work yet.
 */
@Composable
fun ColumnScope.PenPanelContent(pen: PenPreset, onChange: (PenPreset) -> Unit) {
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

    Spacer(Modifier.height(2.dp))
    PanelSetting("Line type") {
        Box(Modifier.width(120.dp)) {
            PanelChoice(
                field = "Line type",
                current = pen.lineType,
                options = LineType.entries,
                label = { it.label },
                onPick = { onChange(pen.copy(lineType = it)) },
            )
        }
    }

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
    PanelSetting(
        label = "Circle to lasso",
        info = "Draw a closed loop around strokes to select them instead of inking over them.",
    ) {
        PanelToggle("Circle to lasso", pen.circleToLasso) {
            onChange(pen.copy(circleToLasso = it))
        }
    }

    PanelSlider(
        field = "Thickness",
        label = "Thickness",
        value = pen.thickness,
        range = PenPreset.MIN_THICKNESS..PenPreset.MAX_THICKNESS,
        onChange = { onChange(pen.copy(thickness = it)) },
    )

    Spacer(Modifier.height(2.dp))
    // Absent for the fountain pen rather than disabled: that pen draws one width by definition, so
    // a pressure control on it is not a setting turned off, it is a setting that does not exist.
    if (pen.kind != PenKind.Fountain) {
        PanelSetting(
            label = "Pressure sensitivity",
            info = "How much harder pressing thickens the line. 0 draws at one width however you " +
                "press.",
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
    ColorSwatches(pen.colorArgb) { onChange(pen.copy(colorArgb = it)) }
    Spacer(Modifier.height(6.dp))
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
 * The colour row.
 *
 * A ring rather than a tick marks the current colour: a tick has to be drawn in something, and no
 * one colour reads on both the black swatch and the yellow one.
 */
@Composable
private fun ColorSwatches(current: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PEN_COLORS.forEach { argb ->
            val selected = argb == current
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .testTag(PenPanelTags.color(argb))
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(50),
                    )
                    .padding(if (selected) 4.dp else 2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(argb))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                    .clickable { onPick(argb) },
            )
        }
    }
}

/** Placed but not wired, so it holds the reference's layout without pretending to work. */
private const val INERT_ALPHA = 0.42f
