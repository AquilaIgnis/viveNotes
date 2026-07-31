package st.unamedtba.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Two-tone ribbon glyphs.
 *
 * The reference UI accents the part of a glyph that carries its meaning — the bullet markers, the
 * numerals, the tick — and leaves the surrounding scaffolding neutral. Material's icons cannot
 * express that: each is a single path, and [androidx.compose.material3.Icon] flattens whatever it
 * is given to one `tint`. So the icons that need two colours are built here instead.
 *
 * Built in Kotlin rather than as XML vector drawables because XML pays runtime inflation — parse,
 * attribute resolution, `TypedArray` churn — on every load, while these are plain object
 * allocation. It is also the only form that can take a colour as a parameter, which the font and
 * highlight glyphs need: their bar shows the *currently selected* colour, so no static asset can
 * express them.
 *
 * Colours are supplied by the caller rather than baked in, so the neutral can follow the theme and
 * the active-button state. See [AppIcons] for where they get built and cached.
 */

/** Row centres shared by the bulleted and numbered list glyphs, so the two line up in the ribbon. */
private val ListRows = floatArrayOf(6f, 12f, 18f)

private inline fun glyph(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

/** The three "text" rules that both list glyphs share. */
private fun ImageVector.Builder.listRules(neutral: Color) {
    ListRows.forEach { cy ->
        path(
            stroke = SolidColor(neutral),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(10f, cy)
            lineTo(20.5f, cy)
        }
    }
}

/**
 * Numerals and ticks are stroked rather than filled. At the 18dp the ribbon renders them, a
 * stroked path is both far easier to author by hand and closer to the reference's thin glyphs
 * than an outlined fill would be.
 */
private fun ImageVector.Builder.strokedAccent(
    accent: Color,
    width: Float,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
) {
    path(
        stroke = SolidColor(accent),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}

fun bulletListGlyph(neutral: Color, accent: Color): ImageVector = glyph("BulletList") {
    ListRows.forEach { cy ->
        path(fill = SolidColor(accent)) {
            moveTo(3.2f, cy - 1.8f)
            lineTo(6.8f, cy - 1.8f)
            lineTo(6.8f, cy + 1.8f)
            lineTo(3.2f, cy + 1.8f)
            close()
        }
    }
    listRules(neutral)
}

/**
 * Numerals are kept to roughly 4 units tall and stroked thin. Anything larger closes the gaps
 * between the three rows, and they smear into one another at the 18dp the ribbon draws them at.
 */
fun numberedListGlyph(neutral: Color, accent: Color): ImageVector = glyph("NumberedList") {
    strokedAccent(accent, 1.15f) {             // 1
        moveTo(3.75f, 4.85f)
        lineTo(4.85f, 4.0f)
        lineTo(4.85f, 8.0f)
    }
    strokedAccent(accent, 1.15f) {             // 2
        moveTo(3.2f, 10.85f)
        quadTo(3.5f, 9.7f, 4.8f, 9.75f)
        quadTo(6.1f, 9.85f, 5.6f, 11.3f)
        quadTo(5.1f, 12.6f, 3.2f, 14.0f)
        lineTo(5.95f, 14.0f)
    }
    strokedAccent(accent, 1.15f) {             // 3
        moveTo(3.3f, 16.3f)
        quadTo(4.9f, 15.5f, 5.4f, 16.9f)
        quadTo(5.7f, 17.9f, 4.5f, 17.95f)
        quadTo(6.1f, 18.0f, 5.8f, 19.3f)
        quadTo(5.3f, 20.5f, 3.35f, 19.75f)
    }
    listRules(neutral)
}

fun todoListGlyph(neutral: Color, accent: Color): ImageVector = glyph("TodoList") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.7f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4.2f, 4.2f)
        lineTo(19.8f, 4.2f)
        lineTo(19.8f, 19.8f)
        lineTo(4.2f, 19.8f)
        close()
    }
    strokedAccent(accent, 2.1f) {
        moveTo(7.6f, 12.2f)
        lineTo(10.7f, 15.3f)
        lineTo(16.6f, 8.6f)
    }
}

/** The neutral X shared by sub- and superscript, positioned by its vertical extent. */
private fun ImageVector.Builder.cross(neutral: Color, top: Float, bottom: Float) {
    path(stroke = SolidColor(neutral), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(2.8f, top)
        lineTo(12.6f, bottom)
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(12.6f, top)
        lineTo(2.8f, bottom)
    }
}

/** The accented 2, drawn from [top] so one shape serves both scripts. */
private fun ImageVector.Builder.smallTwo(accent: Color, top: Float) {
    strokedAccent(accent, 1.5f) {
        moveTo(15.2f, top + 1.1f)
        curveTo(15.5f, top - 0.4f, 19.6f, top - 0.3f, 19.2f, top + 1.8f)
        curveTo(19.0f, top + 3.1f, 15.5f, top + 4.4f, 15.3f, top + 5.6f)
        lineTo(19.8f, top + 5.6f)
    }
}

fun subscriptGlyph(neutral: Color, accent: Color): ImageVector = glyph("Subscript") {
    cross(neutral, 4.4f, 15.6f)
    smallTwo(accent, 14.4f)
}

fun superscriptGlyph(neutral: Color, accent: Color): ImageVector = glyph("Superscript") {
    cross(neutral, 8.4f, 19.6f)
    smallTwo(accent, 4.0f)
}

fun stylesGlyph(neutral: Color, accent: Color): ImageVector = glyph("Styles") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(2.8f, 16.8f)
        lineTo(8.2f, 4.2f)
        lineTo(13.6f, 16.8f)
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
        moveTo(5.2f, 12.2f)
        lineTo(11.2f, 12.2f)
    }
    strokedAccent(accent, 2.3f) {              // the brush stroke, tucked under the A's baseline
        moveTo(8.8f, 19.3f)
        curveTo(13.2f, 20.8f, 18.4f, 18.2f, 20.9f, 13.4f)
    }
}

/** The bar both colour glyphs sit on. Shows the selected colour, so it is never a theme value. */
private fun ImageVector.Builder.swatchBar(swatch: Color) {
    path(fill = SolidColor(swatch)) {
        moveTo(3.2f, 19.2f)
        lineTo(20.8f, 19.2f)
        lineTo(20.8f, 22.0f)
        lineTo(3.2f, 22.0f)
        close()
    }
}

fun fontColorGlyph(neutral: Color, swatch: Color): ImageVector = glyph("FontColor") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4.4f, 16.4f)
        lineTo(10.4f, 4.2f)
        lineTo(16.4f, 16.4f)
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
        moveTo(7.0f, 11.8f)
        lineTo(13.8f, 11.8f)
    }
    swatchBar(swatch)
}

fun highlightGlyph(neutral: Color, swatch: Color): ImageVector = glyph("Highlight") {
    path(fill = SolidColor(neutral)) {         // pen body
        moveTo(9.0f, 13.4f)
        lineTo(14.6f, 4.6f)
        lineTo(18.4f, 7.0f)
        lineTo(12.8f, 15.8f)
        close()
    }
    path(fill = SolidColor(neutral)) {         // pen tip
        moveTo(9.0f, 13.4f)
        lineTo(12.8f, 15.8f)
        lineTo(7.8f, 17.4f)
        close()
    }
    swatchBar(swatch)
}
