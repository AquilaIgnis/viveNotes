package st.unamedtba.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import st.unamedtba.ui.icons.LocalRibbonIcons
import st.unamedtba.ui.icons.rememberRibbonIcons

/** Chrome greys, matching the reference screenshots' dark shell. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B5CF6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B2E5A),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF7E57C2),
    background = Color(0xFF1F1F1F),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF2B2B2B),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFFB8B8B8),
    surfaceContainer = Color(0xFF262626),
    surfaceContainerHigh = Color(0xFF303030),
    surfaceContainerHighest = Color(0xFF383838),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF3A3A3A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D3FD1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF23005C),
    secondary = Color(0xFF6750A4),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF4A4A4A),
    surfaceContainer = Color(0xFFF3F3F3),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE7E7E7),
    outline = Color(0xFFC6C6C6),
    outlineVariant = Color(0xFFDDDDDD),
)

/**
 * Accents for the two-tone icons, sampled from the ribbon in `docs/references/`.
 *
 * These are deliberately *not* part of the [androidx.compose.material3.ColorScheme]. An accent
 * here marks what a glyph means — blue for emphasis, green for create, gold for tag — so it
 * belongs to the icon, not to the theme. Tying them to `primary` would turn the green "+" purple
 * the moment the app theme changed, which is not what the reference does: its ribbon accents stay
 * put while the brand colour appears only on primary actions like Add Page.
 *
 * They are still theme-*aware*, because a colour picked to read against the dark ribbon does not
 * survive a white surface — the sampled `#3B9ADC` manages only about 2.5:1 on the light theme's
 * background.
 */
@Immutable
data class IconAccents(
    val blue: Color,
    val gold: Color,
    val green: Color,
)

/** Sampled directly from `generalUI.png` and `viewsTab.png`. */
private val DarkAccents = IconAccents(
    blue = Color(0xFF3B9ADC),   // bullets, numerals, subscript digit, Styles brush
    gold = Color(0xFFE6A545),   // Tag star — unused until the Tag button exists
    green = Color(0xFF73DD83),  // New Window "+" — unused until the View tab is built
)

/** The same hues darkened to hold contrast against light surfaces. */
private val LightAccents = IconAccents(
    blue = Color(0xFF1B6FA8),
    gold = Color(0xFF8A5B0F),
    green = Color(0xFF1E7A32),
)

val LocalIconAccents = staticCompositionLocalOf { DarkAccents }

/**
 * Colours the note canvas rather than the app chrome. The View tab exposes these separately from
 * the app theme, because OneNote lets a page stay light while the shell is dark.
 */
data class CanvasColors(
    val background: Color,
    val ruleLine: Color,
    val text: Color,
    val secondaryText: Color,
)

val LocalCanvasColors = staticCompositionLocalOf {
    CanvasColors(
        background = Color(0xFF1F1F1F),
        ruleLine = Color(0xFF2E3A4A),
        text = Color(0xFFE6E6E6),
        secondaryText = Color(0xFF9A9A9A),
    )
}

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun UnamedTbaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val accents = if (darkTheme) DarkAccents else LightAccents
    val canvas = if (darkTheme) {
        CanvasColors(
            background = Color(0xFF1F1F1F),
            ruleLine = Color(0xFF2C3947),
            text = Color(0xFFE6E6E6),
            secondaryText = Color(0xFF9A9A9A),
        )
    } else {
        CanvasColors(
            background = Color(0xFFFFFFFF),
            ruleLine = Color(0xFFD8E4F0),
            text = Color(0xFF1B1B1B),
            secondaryText = Color(0xFF6B6B6B),
        )
    }

    // Built here rather than at the call sites so the vectors are constructed once per theme
    // instead of once per recomposition of a ribbon that redraws on every cursor move.
    val ribbonIcons = rememberRibbonIcons(
        idleNeutral = colors.onSurfaceVariant,
        activeNeutral = colors.onPrimaryContainer,
        accent = accents.blue,
    )

    CompositionLocalProvider(
        LocalCanvasColors provides canvas,
        LocalIconAccents provides accents,
        LocalRibbonIcons provides ribbonIcons,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            content = content,
        )
    }
}
