package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.HIGHLIGHTER_COLORS
import com.vivenotes.data.HighlighterSettings

/** Test tags for the parts of the pane that show state without showing text. */
object HighlighterPanelTags {
    const val PREVIEW = "highlighter-preview"
    fun color(argb: Int) = "highlighter-color-$argb"
}

/**
 * The highlighter's settings: what colour, and how wide a band.
 *
 * Short by design. The pen pane is long because a pen has a nib, a line type, a pressure response
 * and a stabilizer; a highlighter has none of those, and inventing controls to fill the surface
 * would be inventing settings. What is here is what K2 asks for.
 *
 * No colour wheel either, unlike the pen. A highlighter ink has to stay light enough to read through
 * — that is the whole job — and an arbitrary colour off a wheel is one drag away from an opaque
 * block that hides the text it was meant to mark. The fixed inks are all the same alpha.
 */
@Composable
fun ColumnScope.HighlighterPanelContent(
    settings: HighlighterSettings,
    onChange: (HighlighterSettings) -> Unit,
) {
    BandPreview(settings)

    Spacer(Modifier.height(10.dp))
    PanelSlider(
        field = "Thickness",
        label = "Thickness",
        value = settings.thickness,
        range = HighlighterSettings.MIN_THICKNESS..HighlighterSettings.MAX_THICKNESS,
        onChange = { onChange(settings.copy(thickness = it)) },
        // A continuous physical width, like the eraser's diameter, rather than a short set of
        // discrete stops — so no tick marks, and the preview shows the true size while held.
        showTicks = false,
        sizePreviewColor = Color(settings.colorArgb),
    )

    Spacer(Modifier.height(8.dp))
    Text(
        text = "Color",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HIGHLIGHTER_COLORS.forEach { argb ->
            Swatch(
                selected = argb == settings.colorArgb,
                tag = HighlighterPanelTags.color(argb),
                onClick = { onChange(settings.copy(colorArgb = argb)) },
            ) {
                // Over paper, not over the panel. These inks are translucent, so on a dark surface
                // every one of them would read as the same near-black smudge — the swatch has to
                // show what the ink looks like where it is actually used.
                drawRect(PAPER)
                drawRect(Color(argb))
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * A band of this highlighter laid over a line of text.
 *
 * The text matters: a highlighter is judged by what stays readable underneath it, so a preview of
 * the ink alone would show the one thing that is not the question.
 */
@Composable
private fun BandPreview(settings: HighlighterSettings) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PAPER)
            .testTag(HighlighterPanelTags.PREVIEW),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            drawLine(
                color = Color(settings.colorArgb),
                start = Offset(size.width * 0.1f, size.height / 2f),
                end = Offset(size.width * 0.9f, size.height / 2f),
                // Thickness is a page measurement in dp, so it previews at the width it will draw.
                strokeWidth = settings.thickness.dp.toPx(),
                cap = StrokeCap.Square,
            )
        }
        Text(
            text = "Highlighted text",
            style = MaterialTheme.typography.bodyMedium,
            color = INK,
        )
    }
}

/**
 * The preview's page, fixed rather than themed.
 *
 * A highlighter is used on a page, and `LocalCanvasColors` is not in scope for a floating panel —
 * but more to the point, a translucent yellow over a dark surface tells the user nothing about what
 * the tool does. Paper is the honest backdrop even inside a dark app.
 */
private val PAPER = Color(0xFFFAF9F6)
private val INK = Color(0xFF1B1B1B)
