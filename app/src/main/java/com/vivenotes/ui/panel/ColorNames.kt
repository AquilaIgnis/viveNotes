package com.vivenotes.ui.panel

import androidx.annotation.StringRes
import com.vivenotes.R

/**
 * The plain name of a colour, for a screen reader to say in place of the swatch it cannot see.
 *
 * Worked out from the hue rather than looked up, because the pen's row takes any colour off the
 * wheel. Each shipped palette still comes out as different names — `ColorNamesTest` holds them to
 * that — while a mixed colour gets the name of the hue it is nearest, which may repeat one already in
 * the row. A palette of tints too pale for a hue to tell apart names its colours outright instead,
 * as the page colours do.
 *
 * Alpha is ignored: a highlighter ink is named by the colour it tints.
 */
@StringRes
internal fun colorName(argb: Int): Int {
    val red = (argb shr 16 and 0xFF) / 255f
    val green = (argb shr 8 and 0xFF) / 255f
    val blue = (argb and 0xFF) / 255f
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val chroma = max - min

    if (chroma < GRAY_CHROMA) {
        return when {
            lightness > 0.94f -> R.string.color_white
            lightness > 0.8f -> R.string.color_light_gray
            lightness < 0.15f -> R.string.color_black
            else -> R.string.color_gray
        }
    }
    if (lightness < 0.1f) return R.string.color_black

    val hue = when (max) {
        red -> 60f * ((green - blue) / chroma).mod(6f)
        green -> 60f * ((blue - red) / chroma + 2f)
        else -> 60f * ((red - green) / chroma + 4f)
    }
    // Brown is a dark or a dull orange, not a hue of its own, and it is a common ink.
    if (hue in 10f..50f && (lightness < 0.42f || (chroma < 0.35f && lightness < 0.7f))) {
        return R.string.color_brown
    }
    return when {
        hue < 15f || hue >= 345f -> R.string.color_red
        hue < 45f -> R.string.color_orange
        hue < 70f -> R.string.color_yellow
        hue < 165f -> R.string.color_green
        hue < 195f -> R.string.color_cyan
        hue < 255f -> R.string.color_blue
        hue < 300f -> R.string.color_purple
        else -> R.string.color_pink
    }
}

/** Below this spread between the strongest and weakest channel, a colour reads as a gray. */
private const val GRAY_CHROMA = 0.1f
