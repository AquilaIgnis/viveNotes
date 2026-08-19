package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
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

/**
 * [glyph], for artwork traced from a Material Symbol rather than drawn from scratch here.
 *
 * Google's exports are authored in a 960×960 box whose origin sits at the *bottom* left
 * (`viewBox="0 -960 960 960"`), so every y is negative. Keeping that space rather than rescaling to
 * the 24 the hand-drawn glyphs use means an export's path data can be pasted in verbatim: nothing
 * is re-derived by hand, so nothing can be re-derived wrongly, and a redrawn `.svg` drops straight
 * in. The translating group is exactly the correction `res/drawable/ms_rounded_*.xml` already
 * applies for the same reason.
 */
private inline fun materialGlyph(
    name: String,
    block: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).group(translationY = 960f, block = block).build()

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

/**
 * Material Symbols Rounded `insert_text`, separated into two semantic colours.
 *
 * The selection frame is the action — creating a new text container — so it receives the blue
 * accent. The T remains neutral and therefore follows both the theme and the button's active state.
 */
fun insertTextGlyph(neutral: Color, accent: Color): ImageVector = glyph("InsertText") {
    // Bounding frame. Its four short rules disappear beneath the corner handles, matching the
    // official symbol's continuous selection rectangle without baking in a background colour.
    path(
        stroke = SolidColor(accent),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
    ) {
        moveTo(5f, 4f)
        lineTo(19f, 4f)
        moveTo(20f, 5f)
        lineTo(20f, 19f)
        moveTo(19f, 20f)
        lineTo(5f, 20f)
        moveTo(4f, 19f)
        lineTo(4f, 5f)
    }
    listOf(
        2f to 2f,
        18f to 2f,
        18f to 18f,
        2f to 18f,
    ).forEach { (left, top) ->
        path(
            stroke = SolidColor(accent),
            strokeLineWidth = 2f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(left, top)
            lineTo(left + 4f, top)
            lineTo(left + 4f, top + 4f)
            lineTo(left, top + 4f)
            close()
        }
    }
    path(fill = SolidColor(neutral)) {
        moveTo(9f, 8f)
        lineTo(15f, 8f)
        curveTo(15.55f, 8f, 16f, 8.45f, 16f, 9f)
        curveTo(16f, 9.55f, 15.55f, 10f, 15f, 10f)
        lineTo(13f, 10f)
        lineTo(13f, 15f)
        curveTo(13f, 15.55f, 12.55f, 16f, 12f, 16f)
        curveTo(11.45f, 16f, 11f, 15.55f, 11f, 15f)
        lineTo(11f, 10f)
        lineTo(9f, 10f)
        curveTo(8.45f, 10f, 8f, 9.55f, 8f, 9f)
        curveTo(8f, 8.45f, 8.45f, 8f, 9f, 8f)
        close()
    }
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

// --- View tab -----------------------------------------------------------------------------------

/** A window with its navigation column picked out — the choice Tabs Layout offers. */
fun tabsLayoutGlyph(neutral: Color, accent: Color): ImageVector = glyph("TabsLayout") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.7f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(3.2f, 4.2f)
        lineTo(20.8f, 4.2f)
        lineTo(20.8f, 19.8f)
        lineTo(3.2f, 19.8f)
        close()
    }
    path(fill = SolidColor(accent)) {
        moveTo(4.6f, 5.6f)
        lineTo(9.4f, 5.6f)
        lineTo(9.4f, 18.4f)
        lineTo(4.6f, 18.4f)
        close()
    }
    listOf(9.0f, 12.5f, 16.0f).forEach { y ->
        path(stroke = SolidColor(neutral), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
            moveTo(11.6f, y)
            lineTo(18.6f, y)
        }
    }
}

/**
 * Ruled paper: blue rules and the red margin the reference draws down the left of the page. The
 * margin is what distinguishes this from the grid glyph at ribbon size, so it is not decoration.
 */
fun ruleLinesGlyph(neutral: Color, accent: Color, warn: Color): ImageVector = glyph("RuleLines") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.6f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4.2f, 3.4f)
        lineTo(19.8f, 3.4f)
        lineTo(19.8f, 20.6f)
        lineTo(4.2f, 20.6f)
        close()
    }
    listOf(7.6f, 11.2f, 14.8f, 18.0f).forEach { y ->
        path(stroke = SolidColor(accent), strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
            moveTo(9.2f, y)
            lineTo(17.8f, y)
        }
    }
    path(stroke = SolidColor(warn), strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
        moveTo(7.4f, 5.2f)
        lineTo(7.4f, 18.8f)
    }
}

/** A sheet with its width and height called out, the way a dimension drawing marks them. */
fun paperSizeGlyph(neutral: Color, accent: Color): ImageVector = glyph("PaperSize") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.6f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(9.6f, 6.4f)
        lineTo(16.4f, 6.4f)
        lineTo(20.2f, 10.2f)
        lineTo(20.2f, 20.8f)
        lineTo(9.6f, 20.8f)
        close()
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.4f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(16.4f, 6.4f)     // the folded corner
        lineTo(16.4f, 10.2f)
        lineTo(20.2f, 10.2f)
    }
    dimension(accent, 9.6f, 3.4f, 20.2f, 3.4f)   // width
    dimension(accent, 6.4f, 6.4f, 6.4f, 20.8f)   // height
}

/**
 * A dimension line with a tick at each end, drawn horizontally or vertically. Ticks rather than
 * arrowheads: at 18dp an arrowhead is three overlapping strokes and reads as a blob.
 */
private fun ImageVector.Builder.dimension(color: Color, x1: Float, y1: Float, x2: Float, y2: Float) {
    strokedAccent(color, 1.3f) {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }
    val tick = 2.2f
    val horizontal = y1 == y2
    strokedAccent(color, 1.3f) {
        if (horizontal) {
            moveTo(x1, y1 - tick / 2f); lineTo(x1, y1 + tick / 2f)
            moveTo(x2, y2 - tick / 2f); lineTo(x2, y2 + tick / 2f)
        } else {
            moveTo(x1 - tick / 2f, y1); lineTo(x1 + tick / 2f, y1)
            moveTo(x2 - tick / 2f, y2); lineTo(x2 + tick / 2f, y2)
        }
    }
}

/** A page whose title line is struck out. */
fun hidePageTitleGlyph(neutral: Color, warn: Color): ImageVector = glyph("HidePageTitle") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.6f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4.2f, 4.2f)
        lineTo(17.6f, 4.2f)
        lineTo(17.6f, 19.8f)
        lineTo(4.2f, 19.8f)
        close()
    }
    path(fill = SolidColor(neutral)) {           // the title it hides
        moveTo(6.6f, 7.4f)
        lineTo(12.4f, 7.4f)
        lineTo(12.4f, 9.6f)
        lineTo(6.6f, 9.6f)
        close()
    }
    strokedAccent(warn, 2.0f) {
        moveTo(15.0f, 5.0f)
        lineTo(21.4f, 11.4f)
        moveTo(21.4f, 5.0f)
        lineTo(15.0f, 11.4f)
    }
}

/** The page, and the span it is being fitted to. */
fun pageWidthGlyph(neutral: Color, accent: Color): ImageVector = glyph("PageWidth") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.6f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4.4f, 4.6f)
        lineTo(19.6f, 4.6f)
        lineTo(19.6f, 15.4f)
        lineTo(4.4f, 15.4f)
        close()
    }
    strokedAccent(accent, 1.5f) {
        moveTo(2.6f, 19.4f)
        lineTo(21.4f, 19.4f)
    }
    strokedAccent(accent, 1.5f) {                // arrowheads, pointing outward
        moveTo(5.4f, 17.0f)
        lineTo(2.6f, 19.4f)
        lineTo(5.4f, 21.8f)
        moveTo(18.6f, 17.0f)
        lineTo(21.4f, 19.4f)
        lineTo(18.6f, 21.8f)
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

/**
 * Page colour: a tipped bucket over the bar that shows what the page is currently painted.
 *
 * Takes its swatch the same way the font-colour and highlight glyphs do, so the ribbon shows the
 * page's colour without opening the menu.
 */
fun pageColorGlyph(neutral: Color, swatch: Color): ImageVector = glyph("PageColor") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.6f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(6.4f, 10.6f)
        lineTo(12.2f, 4.8f)
        lineTo(18.0f, 10.6f)
        lineTo(12.2f, 16.4f)
        close()
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
        moveTo(9.0f, 8.0f)      // handle
        curveTo(8.2f, 5.4f, 11.4f, 4.0f, 12.6f, 6.2f)
    }
    path(fill = SolidColor(neutral)) {           // the drip
        moveTo(19.6f, 11.4f)
        curveTo(21.4f, 14.0f, 21.4f, 15.6f, 19.6f, 15.6f)
        curveTo(17.8f, 15.6f, 17.8f, 14.0f, 19.6f, 11.4f)
        close()
    }
    swatchBar(swatch)
}

/**
 * The eraser, accented in the colour this icon set reserves for what a glyph removes.
 *
 * It takes only theme colours and is built once with the rest in [AppIcons] rather than at its
 * call site.
 */
fun eraserGlyph(neutral: Color, warn: Color): ImageVector = glyph("Eraser") {
    path(fill = SolidColor(warn)) {             // the end doing the erasing
        moveTo(3.0f, 15.4f)
        lineTo(8.6f, 21.0f)
        lineTo(14.2f, 21.0f)
        lineTo(8.6f, 15.4f)
        close()
    }
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.7f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(3.0f, 15.4f)
        lineTo(13.6f, 4.8f)
        lineTo(21.0f, 12.2f)
        lineTo(14.2f, 19.0f)
        close()
    }
}

/**
 * Path data lifted verbatim from `book_24dp_000000_FILL0_wght400_GRAD0_opsz24.svg` — the Material
 * `book` symbol with its bookmark removed to clear room for the arrow.
 *
 * Parsed once per process rather than once per [AppIcons]. That distinction matters here and
 * nowhere else in this file: every other glyph is already plain node allocation, while these two
 * would otherwise re-run an SVG parser each time the theme rebuilds its two icon sets.
 */
private val ImportNotebookCover = addPathNodes(
    "m 240,-80 c -22,0 -40.83333,-7.833333 -56.5,-23.5 C 167.83333,-119.16667 160,-138 160,-160 " +
        "v -640 c 0,-22 7.83333,-40.83333 23.5,-56.5 15.66667,-15.66667 34.5,-23.5 56.5,-23.5 " +
        "h 480 c 22,0 40.83333,7.83333 56.5,23.5 15.66667,15.66667 23.5,34.5 23.5,56.5 v 640 " +
        "c 0,22 -7.83333,40.83333 -23.5,56.5 C 760.83333,-87.833333 742,-80 720,-80 Z " +
        "m 0,-80 H 720 V -800 H 640 440 240 Z m 0,0 v -640 z",
)

private val ImportNotebookArrow = addPathNodes(
    "m 589.65093,-799.95709 v 240.2522 l 76.71592,-76.64487 41.30858,42.74425 " +
        "-147.53063,147.39399 -147.53062,-147.39399 41.30857,-42.74425 76.71593,76.64487 " +
        "v -240.2522 z",
)

/**
 * Import Notebook — a book with an arrow coming down into it through the cover.
 *
 * Two-tone for the reason this whole file exists: the book is scaffolding shared with Export, and
 * the arrow is the only part that says which direction the notebook is travelling, so the arrow is
 * what takes the accent. Flattened to one tint the two commands would be near-indistinguishable in
 * a row that puts them side by side.
 *
 * The source artwork already draws that arrow in `#007FFF`, which is the azure the whole theme is
 * built from (`ui/theme/Theme.kt`), so passing [accent] here reproduces the drawing rather than
 * reinterpreting it — and gets the light scheme's darker shade for free, where a baked-in hex would
 * have sat at 2.3:1 on white.
 */
fun importNotebookGlyph(neutral: Color, accent: Color): ImageVector =
    materialGlyph("ImportNotebook") {
        addPath(ImportNotebookCover, fill = SolidColor(neutral))
        addPath(ImportNotebookArrow, fill = SolidColor(accent))
    }

/*
 * ---------------------------------------------------------------------------------------------
 * The File and Settings commands, split out of their Material Symbols.
 *
 * Every symbol below is one exported path made of several subpaths, and in each case the subpaths
 * already separate the meaning from the scaffolding: the hands sit apart from the clock face, the
 * arrow apart from the bin, the keys apart from the keyboard's frame. So these are *splits* rather
 * than redrawings — nothing here is traced by hand, and the two halves add back up to exactly the
 * artwork Google ships.
 *
 * **Each subpath's moveto was made absolute when it was lifted out.** The exports chain their
 * subpaths with relative movetos — every one is an offset from where the previous subpath started —
 * so a subpath pulled out of the middle would land in the wrong place, and one whose neighbour was
 * dropped would drag everything after it. The rewrite was done by script rather than by eye, and
 * the trap it exists to avoid is that the coordinate pairs *after* a moveto are implicit linetos in
 * the same case: turning `m` into `M` silently turns those absolute and throws the pen across the
 * viewport, which is why an explicit `l` appears where one was needed.
 *
 * A body and the hole punched through it must stay in the *same* declaration: the hole is a
 * counter-wound subpath, and it only reads as a hole while the winding rule can see both.
 * ---------------------------------------------------------------------------------------------
 */

/** `history`, less its hands: the dial and the arrow that runs back around it. */
private val HistoryDial = addPathNodes(
    "M480-120q-126 0-223-76.5T131-392q-4-15 6-27.5t27-14.5q16-2 29 6t18 24q24 90 99 " +
        "147t170 57q117 0 198.5-81.5T760-480q0-117-81.5-198.5T480-760q-69 0-129 32t-101 " +
        "88h70q17 0 28.5 11.5T360-600q0 17-11.5 28.5T320-560H160q-17 " +
        "0-28.5-11.5T120-600v-160q0-17 11.5-28.5T160-800q17 0 28.5 11.5T200-760v54q51-64 " +
        "124.5-99T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480q0 75-28.5 140.5t-77" +
        " 114q-48.5 48.5-114 77T480-120Z",
)

/** The hour and minute hands, which are the half of a clock face that says what it is for. */
private val HistoryHands = addPathNodes(
    "M520,-496l100 100q11 11 11 28t-11 28q-11 11-28 " +
        "11t-28-11L452-452q-6-6-9-13.5t-3-15.5v-159q0-17 11.5-28.5T480-680q17 0 28.5 " +
        "11.5T520-640v144Z",
)

/** `info`'s ring, outer edge and hole together. */
private val InfoRing = addPathNodes(
    "M480,-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 " +
        "127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 " +
        "156T763-197q-54 54-127 85.5T480-80ZM480,-160q134 0 " +
        "227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Z",
)

/** The stem and the dot — the *i* inside the ring. */
private val InfoMark = addPathNodes(
    "M480-280q17 0 28.5-11.5T520-320v-160q0-17-11.5-28.5T480-520q-17 0-28.5 " +
        "11.5T440-480v160q0 17 11.5 28.5T480-280ZM480,-600q17 0 " +
        "28.5-11.5T520-640q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640q0 17 11.5 " +
        "28.5T480-600Z",
)

/**
 * The bin, lid and hollow, shared by Deleted Items and Delete Notebook.
 *
 * One declaration for both because `restore_from_trash` and `delete` are drawn from the same bin —
 * they differ only in what is inside it, which is exactly the part each of them accents.
 */
private val BinBody = addPathNodes(
    "M280-120q-33 0-56.5-23.5T200-200v-520q-17 0-28.5-11.5T160-760q0-17 " +
        "11.5-28.5T200-800h160q0-17 11.5-28.5T400-840h160q17 0 28.5 11.5T600-800h160q17 0 " +
        "28.5 11.5T800-760q0 17-11.5 28.5T760-720v520q0 33-23.5 " +
        "56.5T680-120H280ZM680,-720H280v520h400v-520Z",
)

/** The arrow climbing out of the bin: the whole difference between restoring and deleting. */
private val BinArrow = addPathNodes(
    "M440,-486v126q0 17 11.5 28.5T480-320q17 0 28.5-11.5T520-360v-126l36 35q11 11 27.5 " +
        "11t28.5-12q11-11 11-28t-11-28L508-612q-12-12-28-12t-28 12L348-508q-11 11-11.5 " +
        "27.5T348-452q11 11 27.5 11.5T404-451l36-35Z",
)

/** The two bars standing in the bin. */
private val BinBars = addPathNodes(
    "M400-280q17 0 28.5-11.5T440-320v-280q0-17-11.5-28.5T400-640q-17 0-28.5 " +
        "11.5T360-600v280q0 17 11.5 28.5T400-280ZM560,-280q17 0 " +
        "28.5-11.5T600-320v-280q0-17-11.5-28.5T560-640q-17 0-28.5 11.5T520-600v280q0 17 11.5 " +
        "28.5T560-280Z",
)

/** `memory`'s outline and legs, with the die-shaped hole through the middle. */
private val ChipBody = addPathNodes(
    "M360,-160v-40h-80q-33 0-56.5-23.5T200-280v-80h-40q-17 0-28.5-11.5T120-400q0-17 " +
        "11.5-28.5T160-440h40v-80h-40q-17 0-28.5-11.5T120-560q0-17 " +
        "11.5-28.5T160-600h40v-80q0-33 23.5-56.5T280-760h80v-40q0-17 11.5-28.5T400-840q17 0 " +
        "28.5 11.5T440-800v40h80v-40q0-17 11.5-28.5T560-840q17 0 28.5 11.5T600-800v40h80q33 0" +
        " 56.5 23.5T760-680v80h40q17 0 28.5 11.5T840-560q0 17-11.5 28.5T800-520h-40v80h40q17 " +
        "0 28.5 11.5T840-400q0 17-11.5 28.5T800-360h-40v80q0 33-23.5 56.5T680-200h-80v40q0 " +
        "17-11.5 28.5T560-120q-17 0-28.5-11.5T520-160v-40h-80v40q0 17-11.5 28.5T400-120q-17 " +
        "0-28.5-11.5T360-160ZM680,-280v-400H280v400h400Z",
)

/** The die at the centre of the chip — the part of the symbol that is doing the computing. */
private val ChipCore = addPathNodes(
    "M360-400v-160q0-17 11.5-28.5T400-600h160q17 0 28.5 11.5T600-560v160q0 17-11.5 " +
        "28.5T560-360H400q-17 0-28.5-11.5T360-400ZM440,-440h80v-80h-80v80Z",
)

/** `keyboard`'s case, with the hole its keys sit in. */
private val KeyboardFrame = addPathNodes(
    "M160-200q-33 0-56.5-23.5T80-280v-400q0-33 23.5-56.5T160-760h640q33 0 56.5 " +
        "23.5T880-680v400q0 33-23.5 56.5T800-200H160ZM160,-280h640v-400H160v400Z",
)

/** The space bar and the ten keys above it. */
private val KeyboardKeys = addPathNodes(
    "M360,-320h240q17 0 28.5-11.5T640-360q0-17-11.5-28.5T600-400H360q-17 0-28.5 " +
        "11.5T320-360q0 17 11.5 28.5T360-320ZM268.5,-571.5Q280-583 280-600t-11.5-28.5Q257-640" +
        " 240-640t-28.5 11.5Q200-617 200-600t11.5 28.5Q223-560 " +
        "240-560t28.5-11.5ZM388.5,-571.5Q400-583 400-600t-11.5-28.5Q377-640 360-640t-28.5 " +
        "11.5Q320-617 320-600t11.5 28.5Q343-560 360-560t28.5-11.5ZM508.5,-571.5Q520-583 " +
        "520-600t-11.5-28.5Q497-640 480-640t-28.5 11.5Q440-617 440-600t11.5 28.5Q463-560 " +
        "480-560t28.5-11.5ZM628.5,-571.5Q640-583 640-600t-11.5-28.5Q617-640 600-640t-28.5 " +
        "11.5Q560-617 560-600t11.5 28.5Q583-560 600-560t28.5-11.5ZM748.5,-571.5Q760-583 " +
        "760-600t-11.5-28.5Q737-640 720-640t-28.5 11.5Q680-617 680-600t11.5 28.5Q703-560 " +
        "720-560t28.5-11.5ZM268.5,-451.5Q280-463 280-480t-11.5-28.5Q257-520 240-520t-28.5 " +
        "11.5Q200-497 200-480t11.5 28.5Q223-440 240-440t28.5-11.5ZM388.5,-451.5Q400-463 " +
        "400-480t-11.5-28.5Q377-520 360-520t-28.5 11.5Q320-497 320-480t11.5 28.5Q343-440 " +
        "360-440t28.5-11.5ZM508.5,-451.5Q520-463 520-480t-11.5-28.5Q497-520 480-520t-28.5 " +
        "11.5Q440-497 440-480t11.5 28.5Q463-440 480-440t28.5-11.5ZM628.5,-451.5Q640-463 " +
        "640-480t-11.5-28.5Q617-520 600-520t-28.5 11.5Q560-497 560-480t11.5 28.5Q583-440 " +
        "600-440t28.5-11.5ZM748.5,-451.5Q760-463 760-480t-11.5-28.5Q737-520 720-520t-28.5 " +
        "11.5Q680-497 680-480t11.5 28.5Q703-440 720-440t28.5-11.5Z",
)

/**
 * The whole `book` symbol, bookmark included — the neutral half of Export Notebook.
 *
 * Not [ImportNotebookCover], which is the same book with the bookmark taken out to clear room for
 * the arrow. Export keeps it, because the bookmark is what it accents.
 */
private val BookCover = addPathNodes(
    "M240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h480q33 0 56.5 " +
        "23.5T800-800v640q0 33-23.5 56.5T720-80H240Zm0-80h480v-640h-80v245q0 12-10 " +
        "17.5t-20-.5l-49-30q-10-6-20.5-6t-20.5 6l-49 30q-10 6-20.5.5T440-555v-245H240v640Z",
)

/**
 * The bookmark, as its own closed shape.
 *
 * The one glyph here that is not a subpath of its export: in `book` the bookmark is *carved out of
 * the hole* rather than drawn, so it exists only as cover colour showing through. Its outline is
 * therefore traced from the hole's own commands — the same curves in the same order, closed across
 * the top — which is why it lands exactly on the neutral it paints over instead of near it.
 */
private val BookMark = addPathNodes(
    "M640-800v245q0 12-10 17.5t-20-.5l-49-30q-10-6-20.5-6t-20.5 6l-49 30q-10 " +
        "6-20.5.5T440-555v-245Z",
)

/** Version History — the dial is a clock, the hands are the *time* part of it. */
fun versionHistoryGlyph(neutral: Color, accent: Color): ImageVector =
    materialGlyph("VersionHistory") {
        addPath(HistoryDial, fill = SolidColor(neutral))
        addPath(HistoryHands, fill = SolidColor(accent))
    }

/**
 * Deleted Items — a bin with the arrow accented in green.
 *
 * Green is [IconAccents.green], "what a glyph adds", and this is the one File command that gives
 * something back rather than taking it away. It is also the only thing distinguishing this bin from
 * Delete Notebook's, two commands sharing a tab.
 */
fun deletedItemsGlyph(neutral: Color, create: Color): ImageVector =
    materialGlyph("DeletedItems") {
        addPath(BinBody, fill = SolidColor(neutral))
        addPath(BinArrow, fill = SolidColor(create))
    }

/** Delete Notebook — the same bin, its contents in the warning colour. */
fun deleteNotebookGlyph(neutral: Color, warn: Color): ImageVector =
    materialGlyph("DeleteNotebook") {
        addPath(BinBody, fill = SolidColor(neutral))
        addPath(BinBars, fill = SolidColor(warn))
    }

/** Export Notebook — the bookmark accented, answering Import's accented arrow beside it. */
fun exportNotebookGlyph(neutral: Color, accent: Color): ImageVector =
    materialGlyph("ExportNotebook") {
        addPath(BookCover, fill = SolidColor(neutral))
        addPath(BookMark, fill = SolidColor(accent))
    }

/** Integrated — the chip's die accented, the package and its legs neutral. */
fun integratedGlyph(neutral: Color, accent: Color): ImageVector =
    materialGlyph("Integrated") {
        addPath(ChipBody, fill = SolidColor(neutral))
        addPath(ChipCore, fill = SolidColor(accent))
    }

/** Hardware — the keys accented inside a neutral case. */
fun hardwareGlyph(neutral: Color, accent: Color): ImageVector =
    materialGlyph("Hardware") {
        addPath(KeyboardFrame, fill = SolidColor(neutral))
        addPath(KeyboardKeys, fill = SolidColor(accent))
    }

/** About — the *i*, which is the whole message; the ring around it is packaging. */
fun aboutGlyph(neutral: Color, accent: Color): ImageVector =
    materialGlyph("About") {
        addPath(InfoRing, fill = SolidColor(neutral))
        addPath(InfoMark, fill = SolidColor(accent))
    }

/**
 * Link Previews — a card with a play mark in it, which is literally what the setting draws.
 *
 * Hand-drawn rather than traced from a Material Symbol: the symbol set's `smart_display` is a
 * filled card whose play triangle is a hole punched through it, so its two halves cannot be given
 * separate colours without redrawing the shape anyway. The accent goes on the triangle, because
 * the play mark is what distinguishes this from any other rectangle in the ribbon.
 */
fun linkPreviewGlyph(neutral: Color, accent: Color): ImageVector = glyph("LinkPreview") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.7f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(3.2f, 5.6f)
        lineTo(20.8f, 5.6f)
        lineTo(20.8f, 18.4f)
        lineTo(3.2f, 18.4f)
        close()
    }
    path(fill = SolidColor(accent)) {
        moveTo(10.1f, 8.6f)
        lineTo(15.6f, 12.0f)
        lineTo(10.1f, 15.4f)
        close()
    }
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
