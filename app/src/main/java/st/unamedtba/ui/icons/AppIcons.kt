package st.unamedtba.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The two-tone glyphs, built once for one neutral colour.
 *
 * Construction is cheap — a few [androidx.compose.ui.graphics.vector.VectorPath] nodes each, no
 * rasterisation — but it is not free, and the ribbon recomposes on every cursor move because each
 * button's `active` flag is derived from the selection. Building inside a composable body would
 * therefore rebuild every glyph on every keystroke, so they are hoisted to the theme instead.
 */
@Immutable
class AppIcons(neutral: Color, accent: Color, warn: Color) {
    val bulletList = bulletListGlyph(neutral, accent)
    val numberedList = numberedListGlyph(neutral, accent)
    val todoList = todoListGlyph(neutral, accent)
    val subscript = subscriptGlyph(neutral, accent)
    val superscript = superscriptGlyph(neutral, accent)
    val styles = stylesGlyph(neutral, accent)
    val tabsLayout = tabsLayoutGlyph(neutral, accent)
    val ruleLines = ruleLinesGlyph(neutral, accent, warn)
    val paperSize = paperSizeGlyph(neutral, accent)
    val hidePageTitle = hidePageTitleGlyph(neutral, warn)
    val pageWidth = pageWidthGlyph(neutral, accent)
}

/**
 * Idle and active variants of the same glyphs.
 *
 * A two-tone icon cannot be recoloured at draw time — that is the whole point of it — so the
 * pressed state of a ribbon button needs its own set, built against `onPrimaryContainer` instead
 * of `onSurfaceVariant`. The accent is identical in both: it belongs to the glyph's meaning, not
 * to the button's state.
 */
@Immutable
class RibbonIcons(val idle: AppIcons, val active: AppIcons)

val LocalRibbonIcons = staticCompositionLocalOf<RibbonIcons> {
    error("No RibbonIcons provided — wrap the content in UnamedTbaTheme()")
}

@Composable
fun rememberRibbonIcons(
    idleNeutral: Color,
    activeNeutral: Color,
    accent: Color,
    warn: Color,
): RibbonIcons =
    remember(idleNeutral, activeNeutral, accent, warn) {
        RibbonIcons(
            idle = AppIcons(idleNeutral, accent, warn),
            active = AppIcons(activeNeutral, accent, warn),
        )
    }
