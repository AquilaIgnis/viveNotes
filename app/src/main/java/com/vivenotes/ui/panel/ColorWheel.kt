package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Every hue at full saturation, one stop each 30°, for a sweep gradient.
 *
 * Built from [Color.hsv] rather than written out as six named corners: a sweep gradient interpolates
 * straight through sRGB between its stops, so the wider the gap the more the midpoint sags away from
 * the spectrum. Thirteen stops keep it close enough that the wheel matches the colour it hands back.
 *
 * The last entry repeats the first, which is what closes the seam at 0°/360°.
 */
internal val HUE_STOPS: List<Color> = List(13) { Color.hsv(it * 30f, 1f, 1f) }

/**
 * The custom colour picker behind the wheel swatch at the end of the pen palette.
 *
 * Hue and saturation come off the disc and brightness off the bar below it, because those are the
 * three numbers a colour is, and a disc alone can only show two of them — without the bar the whole
 * lower half of every colour is unreachable, which is where most usable ink lives.
 *
 * [onPick] reports the colour when a gesture ends rather than on every sampled point. Each one is a
 * DataStore write, and a drag across the disc is hundreds of points; end-of-gesture is both the
 * moment the choice is meant and the only rate that is sane to persist at.
 *
 * It fires for every gesture, though, because trying colours is how the wheel is used — so it means
 * "this is what the wheel is showing now", not "this is the colour". Anything that a near-miss must
 * not cost, the swatch row above all, belongs on [onDone] instead. Done itself only closes; the pen
 * is already wearing the colour by then.
 *
 * State is seeded once, when the popup opens, and not re-keyed on [initialArgb]. Dragging brightness
 * to zero makes the colour black, and black has no hue to read back — re-seeding from it would drop
 * the pointer to red the moment the user reached the bottom of the bar.
 */
@Composable
fun ColumnScope.ColorWheelContent(
    initialArgb: Int,
    onPick: (Int) -> Unit,
    onDone: () -> Unit,
) {
    val seed = remember { initialArgb.toHsv() }
    var hue by remember { mutableFloatStateOf(seed[0]) }
    var saturation by remember { mutableFloatStateOf(seed[1]) }
    var brightness by remember { mutableFloatStateOf(seed[2]) }

    // The pointer blocks below are started once and keep whatever they captured, so the callback has
    // to be read through a state that recomposition can refresh. Without this, the second colour a
    // user picks would be written onto the pen as it stood before the first.
    val latestPick by rememberUpdatedState(onPick)
    fun commit() = latestPick(argbOf(hue, saturation, brightness))

    val current = Color.hsvSafe(hue, saturation, brightness)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .size(WHEEL_DIAMETER)
                .testTag(PenPanelTags.WHEEL)
                .semantics { contentDescription = "Color wheel" }
                .pointerInput(Unit) {
                    trackPointer(
                        onSample = { position ->
                            val radius = minOf(size.width, size.height) / 2f
                            val dx = position.x - size.width / 2f
                            val dy = position.y - size.height / 2f
                            hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0)
                                .toFloat()
                            saturation = if (radius <= 0f) {
                                0f
                            } else {
                                (hypot(dx, dy) / radius).coerceIn(0f, 1f)
                            }
                        },
                        onEnd = { commit() },
                    )
                },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(brush = Brush.sweepGradient(HUE_STOPS, center), radius = radius)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
            )
            // Brightness dims the whole disc rather than being drawn into the gradient, so the wheel
            // always shows the family of the colour being chosen, not a slice of it.
            if (brightness < 1f) {
                drawCircle(color = Color.Black.copy(alpha = 1f - brightness), radius = radius)
            }

            val angle = Math.toRadians(hue.toDouble())
            val thumb = Offset(
                x = center.x + (cos(angle) * saturation * radius).toFloat(),
                y = center.y + (sin(angle) * saturation * radius).toFloat(),
            )
            drawThumb(thumb, 9.dp.toPx())
        }

        Spacer(Modifier.height(14.dp))
        BrightnessBar(
            hue = hue,
            saturation = saturation,
            brightness = brightness,
            onChange = { brightness = it },
            onEnd = { commit() },
        )

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(50))
                        .background(current)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                        .testTag(PenPanelTags.WHEEL_PREVIEW),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = hexOf(argbOf(hue, saturation, brightness)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

/**
 * Black to the fully lit version of the colour on the disc.
 *
 * The track shows the hue and saturation currently chosen so the bar answers "how dark do I want
 * *this* colour", which is the question being asked, rather than showing a generic grey ramp.
 */
@Composable
private fun BrightnessBar(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
) {
    val latestChange by rememberUpdatedState(onChange)
    val latestEnd by rememberUpdatedState(onEnd)
    val lit = Color.hsvSafe(hue, saturation, 1f)
    val thumbOutline = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .testTag(PenPanelTags.BRIGHTNESS)
            .semantics { contentDescription = "Brightness" }
            .pointerInput(Unit) {
                trackPointer(
                    onSample = { position ->
                        val width = size.width.toFloat()
                        latestChange(if (width <= 0f) 0f else (position.x / width).coerceIn(0f, 1f))
                    },
                    onEnd = { latestEnd() },
                )
            },
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Color.Black, lit)),
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = thumbOutline,
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 1.dp.toPx()),
        )
        val x = (brightness * size.width).coerceIn(radius, size.width - radius)
        drawThumb(Offset(x, radius), radius - 3.dp.toPx())
    }
}

/**
 * A two-ring marker, light inside and dark outside.
 *
 * One ring cannot work here: white vanishes on the pale centre of the disc and black vanishes at the
 * bottom of the brightness bar, so the marker carries both and one of them always shows.
 */
private fun DrawScope.drawThumb(center: Offset, radius: Float) {
    drawCircle(
        color = Color.Black.copy(alpha = 0.55f),
        radius = radius + 1.5.dp.toPx(),
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawCircle(
        color = Color.White,
        radius = radius,
        center = center,
        style = Stroke(width = 3.dp.toPx()),
    )
}

/**
 * Reports where one finger is for as long as it is down, then that it has lifted.
 *
 * The first touch is sampled immediately instead of waiting for drag slop, because a tap on a colour
 * is the common gesture here, not a drag. Every change is consumed so that the scrolling column the
 * panel sits in cannot decide mid-drag that this was a scroll and take the pointer away.
 */
private suspend fun PointerInputScope.trackPointer(
    onSample: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onSample(down.position)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            onSample(change.position)
        }
        onEnd()
    }
}

/** HSV as Android reads it: hue in degrees, the other two as fractions. */
private fun Int.toHsv(): FloatArray = FloatArray(3).also {
    android.graphics.Color.colorToHSV(this, it)
}

private fun argbOf(hue: Float, saturation: Float, brightness: Float): Int =
    android.graphics.Color.HSVToColor(
        floatArrayOf(
            hue.coerceIn(0f, 360f),
            saturation.coerceIn(0f, 1f),
            brightness.coerceIn(0f, 1f),
        ),
    )

/** The colour as it would be written down, alpha dropped because ink here is always opaque. */
private fun hexOf(argb: Int): String =
    String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

/** Compose's [Color.hsv] rejects a hue outside 0..360, and a wheel samples right up to the edge. */
private fun Color.Companion.hsvSafe(hue: Float, saturation: Float, value: Float): Color =
    hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))

private val WHEEL_DIAMETER = 206.dp
private val BAR_HEIGHT = 26.dp
