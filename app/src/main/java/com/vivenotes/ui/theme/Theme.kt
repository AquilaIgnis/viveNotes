package com.vivenotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vivenotes.ui.icons.LocalRibbonIcons
import com.vivenotes.ui.icons.rememberRibbonIcons

/**
 * Chrome greys, matching the reference screenshots' dark shell, and **azure** as the brand colour.
 *
 * Every value in the primary family is taken from the Azure ramp at
 * <https://www.figma.com/colors/azure/> rather than mixed by hand, so the tints and shades are
 * consistent with each other and with the base — `#007FFF`.
 *
 * The dark scheme wears the base itself: it reaches 4.25:1 against the `#1F1F1F` background, which
 * is what the accent is used for — handles, selection chrome, active states — rather than body text.
 * The light scheme takes a shade instead; see [LightColors].
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF007FFF),
    onPrimary = Color.White,
    // A shade rather than the near-black end of the ramp. This is the background of a *pressed*
    // ribbon button, sitting on a `#2B2B2B` surface, and the two deepest azures are darker than that
    // surface — a selected button that reads as a hole rather than as a highlight.
    primaryContainer = Color(0xFF00478E),
    onPrimaryContainer = Color(0xFFB3D9FF),
    // A step down in emphasis from [primary], which is what secondary is for — so a shade, not a
    // tint: a secondary brighter than the primary inverts the hierarchy it exists to express.
    secondary = Color(0xFF0063C6),
    // **The exact complement of the brand azure**, and it belongs in `tertiary` for that reason —
    // Material's third slot exists for an accent that balances the primary rather than extends it.
    // #007FFF and #FF8000 are complements to the digit, which is why the pairing reads as deliberate
    // rather than as a second brand colour. Chosen by the user as the *fill* of the math action
    // buttons, which is what decides [onTertiary]: a near-black label rides it at 6.55:1.
    //
    // Both schemes carry it unaltered — see [LightColors] for why a fill escapes the darkening every
    // other accent in this file needs.
    tertiary = Color(0xFFFF8000),
    onTertiary = Color(0xFF1F1F1F),
    tertiaryContainer = Color(0xFF7A3D00),
    onTertiaryContainer = Color(0xFFFFD9B3),
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

/**
 * The same azure, one shade down.
 *
 * **`#007FFF` itself is not used here, and the reason is contrast.** The base manages only 3.65:1
 * against the light background — enough for a graphic, short of the 4.5:1 that body text needs, and
 * `primary` is text here: it is what colours the ribbon's active tab. `#0063C6` is the next shade on
 * the same ramp and reaches 5.6:1.
 *
 * The scheme this replaced did exactly the same thing with purple — `#8B5CF6` dark, the darker
 * `#6D3FD1` light — so this is the existing pattern in a new hue rather than a new rule.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0063C6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB3D9FF),
    onPrimaryContainer = Color(0xFF001C39),
    secondary = Color(0xFF0055AA),
    // **The same #FF8000 as the dark scheme, unshaded** — and the reason is that it is a *fill*.
    //
    // It was briefly darkened to #B35900 here, on the rule that governs every other accent in this
    // file: a colour used as a label or an outline must clear 4.5:1 against the surface behind it, and
    // #FF8000 manages only 2.52:1 on white. That rule does not apply to a button's container. What has
    // to be legible is the label *on* the fill, which is [onTertiary] at 6.55:1 in both schemes, so
    // the light theme can wear the exact colour the user picked instead of an approximation of it.
    //
    // The one thing it costs: the button's edge against a white pane is 2.52:1, under the 3:1 that
    // WCAG 1.4.11 asks of a component boundary. A hairline outline would fix that if it ever matters;
    // the label carries the identification in the meantime.
    tertiary = Color(0xFFFF8000),
    onTertiary = Color(0xFF1F1F1F),
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF3A1A00),
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
 * belongs to the icon, not to the theme. Tying them to `primary` would have turned the green "+"
 * purple the moment the app theme changed, and azure the moment it changed again, which is not what
 * the reference does: its ribbon accents stay put while the brand colour appears only on primary
 * actions like Add Page.
 *
 * **That rule now costs something worth knowing about.** With the brand colour azure, [blue] and
 * `primary` are neighbours rather than opposites, so a glyph's "this is emphasis" blue and the
 * shell's "this is the app" blue no longer separate on hue alone. They still separate on *place* —
 * an accent is inside an icon, the brand colour is chrome around it — and the sampled blue is what
 * `docs/references/generalUI.png` actually shows. Left alone deliberately; if the two ever read as
 * one thing, the fix is to move this blue, never to point it at the scheme.
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
    val red: Color,
)

/** Sampled directly from `generalUI.png` and `viewsTab.png`. */
private val DarkAccents = IconAccents(
    blue = Color(0xFF3B9ADC),   // bullets, numerals, subscript digit, Styles brush, page geometry
    gold = Color(0xFFE6A545),   // Tag star — unused until the Tag button exists
    green = Color(0xFF73DD83),  // the "+" that adds something: a section tab, a notebook
    red = Color(0xFFE94C4F),    // what a glyph removes or hides: the Hide Page Title cross
)

/** The same hues darkened to hold contrast against light surfaces. */
private val LightAccents = IconAccents(
    blue = Color(0xFF1B6FA8),
    gold = Color(0xFF8A5B0F),
    green = Color(0xFF1E7A32),
    red = Color(0xFFC12F32),
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
    /** What Switch Background is currently showing, so the ribbon can flip it. */
    val isDark: Boolean,
)

val DarkCanvasColors = CanvasColors(
    background = Color(0xFF1F1F1F),
    ruleLine = Color(0xFF2C3947),
    text = Color(0xFFE6E6E6),
    secondaryText = Color(0xFF9A9A9A),
    isDark = true,
)

val LightCanvasColors = CanvasColors(
    background = Color(0xFFFFFFFF),
    ruleLine = Color(0xFFD8E4F0),
    text = Color(0xFF1B1B1B),
    secondaryText = Color(0xFF6B6B6B),
    isDark = false,
)

fun canvasColorsFor(dark: Boolean): CanvasColors = if (dark) DarkCanvasColors else LightCanvasColors

/**
 * Re-derives the page's ink for a background the user chose from the Page Color menu.
 *
 * Taking the colour alone would be a trap: a dark page picked while the shell is light would keep
 * near-black text and swallow it. The ink follows the *page*, which is the whole point of the
 * canvas colours being separate from the theme in the first place.
 */
fun CanvasColors.paintedWith(argb: Int?): CanvasColors {
    val background = argb?.let(::Color) ?: return this
    return canvasColorsFor(background.luminance() < 0.45f).copy(background = background)
}

val LocalCanvasColors = staticCompositionLocalOf { DarkCanvasColors }

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

/**
 * **M3 Expressive, applied at the root** — `docs/expressivePlan.md` EX1–EX3.
 *
 * [MaterialExpressiveTheme] rather than `MaterialTheme` is nearly the whole of the adoption. Most of
 * expressive is not a component you call: this flips the CompositionLocal that the ordinary components
 * read to choose expressive shape, size and motion, so `Slider`, `Switch`, `Button`,
 * `OutlinedTextField`, `DropdownMenu` and `AlertDialog` all change with no call-site edit. The handful
 * of places where a *different* component is the expressive answer — wavy progress, the loading
 * indicators, the Hardware pane's toggle row — are listed in that plan.
 *
 * Calling expressive components under a plain `MaterialTheme` would be the half-measure: their
 * geometry without their motion, which is the part that makes them read as expressive at all.
 *
 * **The colour schemes stay hand-written (EX2).** `expressiveLightColorScheme()` is deliberately not
 * used: the azure ramp below is sampled and contrast-checked — `#0063C6` on light precisely because
 * the base fails 4.5:1 for text — and [IconAccents] is sampled against these surfaces and nothing
 * else. Expressive here means shape, motion and component choice; colour is ours.
 *
 * [MotionScheme.expressive] is named explicitly rather than left to default (EX3), so a deliberate
 * decision does not look accidental. Its spatial specs are springs, so chrome overshoots slightly and
 * settles; nothing on the ink path uses Material animation, which keeps that away from the stylus.
 */
@Composable
fun ViveNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val accents = if (darkTheme) DarkAccents else LightAccents
    // The starting point only. Switch Background and Page Color both re-provide this lower down,
    // where the setting and the open page are known.
    val canvas = canvasColorsFor(darkTheme)

    // Built here rather than at the call sites so the vectors are constructed once per theme
    // instead of once per recomposition of a ribbon that redraws on every cursor move.
    val ribbonIcons = rememberRibbonIcons(
        idleNeutral = colors.onSurfaceVariant,
        activeNeutral = colors.onPrimaryContainer,
        accent = accents.blue,
        warn = accents.red,
    )

    CompositionLocalProvider(
        LocalCanvasColors provides canvas,
        LocalIconAccents provides accents,
        LocalRibbonIcons provides ribbonIcons,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            // Shapes left null on purpose: the expressive defaults are the point of the switch.
            // Typography is not — see [AppTypography], and EX10.
            typography = AppTypography,
            content = content,
        )
    }
}
