package com.vivenotes.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vivenotes.model.Outline
import com.vivenotes.model.ocr.PictureHighlight
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** The matched words of a picture hit just opened, on the one placement it was opened on. */
internal data class ImageSearchFocus(val imageId: String, val highlight: PictureHighlight)

/**
 * What the pictures' search boxes are currently showing, read only by the canvas while it draws.
 *
 * Holds what is on screen rather than what was asked for, so boxes can fade out after the search
 * that made them has already gone.
 */
@Stable
internal class ImageSearchHighlightState {
    var matches: Map<String, PictureHighlight> by mutableStateOf(emptyMap())
    val matchStrength = Animatable(0f)

    var focus: ImageSearchFocus? by mutableStateOf(null)
    val focusStrength = Animatable(0f)
    val focusScale = Animatable(1f)
}

/**
 * Drives the fades, off the composition.
 *
 * [matches] is read in a `snapshotFlow` rather than here, so a new query reaches the canvas without
 * recomposing this layer or the page it is on. System "remove animations" is honoured by [Animatable]
 * itself: every `animateTo` below finishes at once.
 */
@Composable
internal fun rememberImageSearchHighlights(
    matches: () -> Map<String, PictureHighlight>,
    focus: ImageSearchFocus?,
): ImageSearchHighlightState {
    val state = remember { ImageSearchHighlightState() }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val currentMatches = rememberUpdatedState(matches)

    LaunchedEffect(state) {
        snapshotFlow { currentMatches.value() }.collectLatest { current ->
            if (current.isNotEmpty()) {
                state.matches = current
                state.matchStrength.animateTo(1f, effects)
            } else {
                state.matchStrength.animateTo(0f, effects)
                state.matches = emptyMap()
            }
        }
    }

    LaunchedEffect(focus) {
        if (focus != null) {
            state.focus = focus
            state.focusScale.snapTo(FOCUS_POP_SCALE)
            launch { state.focusScale.animateTo(1f, spatial) }
            state.focusStrength.animateTo(1f, effects)
        } else if (state.focus != null) {
            state.focusStrength.animateTo(0f, effects)
            state.focus = null
        }
    }
    return state
}

/** The strokes and the one path every box is built in. Remembered, so a frame allocates nothing. */
internal class ImageHighlightPaint(density: Float) {
    val path = Path()
    val matchStroke = Stroke(width = MATCH_STROKE_DP * density, join = StrokeJoin.Round)
    val focusStroke = Stroke(width = FOCUS_STROKE_DP * density, join = StrokeJoin.Round)
}

/**
 * Draws one picture's boxes over the frame it is at right now, in the layer's device pixels.
 *
 * Polygons rather than rounded rectangles because a line in a photograph is rotated, and a frame can
 * be stretched by a different amount each way, which turns a rotated box into a parallelogram. The
 * rounded joins on the stroke are what soften the corners.
 */
internal fun DrawScope.drawPictureHighlight(
    highlight: PictureHighlight,
    image: Outline.Image,
    density: Float,
    window: Rect,
    path: Path,
    fill: Color,
    border: Color,
    stroke: Stroke,
    scale: Float = 1f,
) {
    val quads = highlight.quads
    val originX = image.x * density
    val originY = image.y * density
    val width = image.width * density
    val height = image.height * density
    var at = 0
    while (at < quads.size) {
        val x0 = originX + quads[at] * width
        val y0 = originY + quads[at + 1] * height
        val x1 = originX + quads[at + 2] * width
        val y1 = originY + quads[at + 3] * height
        val x2 = originX + quads[at + 4] * width
        val y2 = originY + quads[at + 5] * height
        val x3 = originX + quads[at + 6] * width
        val y3 = originY + quads[at + 7] * height
        at += PictureHighlight.QUAD_VALUES

        val left = min(min(x0, x1), min(x2, x3))
        val right = max(max(x0, x1), max(x2, x3))
        val top = min(min(y0, y1), min(y2, y3))
        val bottom = max(max(y0, y1), max(y2, y3))
        // The picture being on screen says nothing about this box when the picture is a tall
        // screenshot and the page is zoomed in.
        if (right < window.left || left > window.right || bottom < window.top || top > window.bottom) {
            continue
        }

        val centerX = (x0 + x1 + x2 + x3) / 4f
        val centerY = (y0 + y1 + y2 + y3) / 4f
        path.rewind()
        path.moveTo(centerX + (x0 - centerX) * scale, centerY + (y0 - centerY) * scale)
        path.lineTo(centerX + (x1 - centerX) * scale, centerY + (y1 - centerY) * scale)
        path.lineTo(centerX + (x2 - centerX) * scale, centerY + (y2 - centerY) * scale)
        path.lineTo(centerX + (x3 - centerX) * scale, centerY + (y3 - centerY) * scale)
        path.close()
        drawPath(path, fill)
        drawPath(path, border, style = stroke)
    }
}

internal const val MATCH_FILL_ALPHA = 0.24f
internal const val MATCH_BORDER_ALPHA = 0.72f
internal const val FOCUS_FILL_ALPHA = 0.42f
internal const val FOCUS_BORDER_ALPHA = 1f
private const val MATCH_STROKE_DP = 1.5f
private const val FOCUS_STROKE_DP = 2.5f

/** How much bigger a box starts when a hit is opened, before it springs back onto the words. */
private const val FOCUS_POP_SCALE = 1.35f
