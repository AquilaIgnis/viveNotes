package com.vivenotes.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * A busy mark small enough to sit beside a line of text or inside a button: M3 Expressive's wavy
 * ring at 24dp.
 *
 * `CircularWavyProgressIndicator`'s defaults are drawn for its 48dp container, with a 4dp stroke, a
 * 15dp wave and a 4dp gap. Shrunk to 24dp it keeps that stroke, and the wave smears into a lump.
 * This is the same ring at half scale, every one of those halved, and it still reads as a wave.
 *
 * The track is named because its default resolves through `secondaryContainer`, the one slot
 * `ViveNotesTheme` leaves to Material, whose violet reads as another app's colour. On a filled
 * button, pass the button's content colour and a faint version of it.
 */
@Composable
internal fun InlineWavyProgress(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val density = LocalDensity.current
    val stroke = remember(density) {
        Stroke(width = with(density) { InlineStroke.toPx() }, cap = StrokeCap.Round)
    }
    CircularWavyProgressIndicator(
        modifier = modifier.size(InlineSize),
        color = color,
        trackColor = trackColor,
        stroke = stroke,
        trackStroke = stroke,
        gapSize = InlineGap,
        wavelength = InlineWavelength,
    )
}

private val InlineSize = 24.dp
private val InlineStroke = 2.dp
private val InlineGap = 2.dp
private val InlineWavelength = 7.5.dp
