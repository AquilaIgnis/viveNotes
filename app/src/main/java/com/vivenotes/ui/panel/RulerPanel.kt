package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vivenotes.data.RulerKind
import com.vivenotes.data.RulerSettings
import com.vivenotes.model.PageStyle

object RulerPanelTags {
    fun kind(kind: RulerKind) = "ruler-kind-${kind.name}"
}

/**
 * The floating settings shown under the ruler in the Draw ribbon — `memory/rulerPlan.md` RD7.
 *
 * The eraser's pane in shape: cards choosing what the tool is, then how big it is. What differs is
 * that the cards draw miniatures of the two rulers instead of wearing icons — the thing being chosen
 * *is* a shape, and showing it says more than any glyph or label would.
 */
@Composable
fun ColumnScope.RulerPanelContent(
    settings: RulerSettings,
    onChange: (RulerSettings) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
    ) {
        RulerKindButton(
            kind = RulerKind.Straight,
            selected = settings.kind == RulerKind.Straight,
            onClick = { onChange(settings.copy(kind = RulerKind.Straight)) },
        )
        RulerKindButton(
            kind = RulerKind.Protractor,
            selected = settings.kind == RulerKind.Protractor,
            onClick = { onChange(settings.copy(kind = RulerKind.Protractor)) },
        )
    }

    // Absent for the straightedge, which has no size to set: it spans the viewport, always. An
    // action a kind cannot perform is missing for it rather than shown and dead — the rule the
    // object toolkit already follows for a line's fill.
    if (settings.kind == RulerKind.Protractor) {
        Spacer(Modifier.height(6.dp))
        PanelSlider(
            field = "Ruler size",
            label = "Diameter",
            value = settings.diameterDp,
            range = RulerSettings.MIN_DIAMETER..RulerSettings.MAX_DIAMETER,
            onChange = { onChange(settings.copy(diameterDp = it)) },
            showTicks = false,
            // The page is laid out at 160dp to the inch, so this is a real measurement rather than
            // a number — the whole reason the ruler is placed in page units (RD3).
            format = { "%.1f in".format(it / PageStyle.DP_PER_INCH) },
            // A ruler spans hundreds of dp, so stepping by one would make these buttons ornamental.
            step = 40,
        )
    }
}

@Composable
private fun RulerKindButton(
    kind: RulerKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val ink = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .width(64.dp)
            .testTag(RulerPanelTags.kind(kind))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .border(
                    width = 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = shape,
                )
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(24.dp)) {
                when (kind) {
                    RulerKind.Straight -> drawStraightSample(ink)
                    RulerKind.Protractor -> drawProtractorSample(ink)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = kind.label,
            style = MaterialTheme.typography.labelSmall,
            color = ink,
        )
    }
}

/** A band with ticks along its drawing edge — the ruler seen from above. */
private fun DrawScope.drawStraightSample(ink: Color) {
    val top = size.height * 0.3f
    val bottom = size.height * 0.72f
    drawRect(
        color = ink.copy(alpha = 0.16f),
        topLeft = Offset(0f, top),
        size = Size(size.width, bottom - top),
    )
    drawRect(
        color = ink,
        topLeft = Offset(0f, top),
        size = Size(size.width, bottom - top),
        style = Stroke(width = 1.dp.toPx()),
    )
    repeat(5) { index ->
        val x = size.width * (index + 1) / 6f
        drawLine(
            color = ink,
            start = Offset(x, top),
            end = Offset(x, top + (bottom - top) * if (index == 2) 0.6f else 0.34f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/** The same, bent into a half-disc: flat edge down, arc up, which is how one is held. */
private fun DrawScope.drawProtractorSample(ink: Color) {
    val radius = size.minDimension * 0.46f
    val centre = Offset(size.width / 2f, size.height * 0.74f)
    val box = androidx.compose.ui.geometry.Rect(
        left = centre.x - radius,
        top = centre.y - radius,
        right = centre.x + radius,
        bottom = centre.y + radius,
    )
    drawArc(
        color = ink.copy(alpha = 0.16f),
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = box.topLeft,
        size = box.size,
    )
    drawArc(
        color = ink,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = box.topLeft,
        size = box.size,
        style = Stroke(width = 1.dp.toPx()),
    )
    // Three marks off the arc, the protractor's own reason for existing.
    listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
        val angle = Math.PI * fraction
        val dx = -kotlin.math.cos(angle).toFloat()
        val dy = -kotlin.math.sin(angle).toFloat()
        drawLine(
            color = ink,
            start = Offset(centre.x + dx * radius, centre.y + dy * radius),
            end = Offset(centre.x + dx * radius * 0.7f, centre.y + dy * radius * 0.7f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}
