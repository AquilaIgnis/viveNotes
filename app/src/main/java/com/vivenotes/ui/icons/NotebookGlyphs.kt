package com.vivenotes.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The two notebook glyphs the Closed Notebooks shelf lists its rows with.
 *
 * Traced verbatim from the project's own artwork — `viveCServer/assets/notebook_regular.svg` and
 * `cloud-notebook.svg` — after Inkscape had flattened every transform and turned the ellipse and
 * the rectangle into paths, so the coordinates below are the drawing's, not a hand re-derivation of
 * it. Both are authored in a 16x16 box with the origin at the top left, which is why these get
 * their own builder rather than borrowing [RibbonGlyphs]' 20-unit Fluent or 960-unit Material ones.
 *
 * **The body takes the notebook's own colour and the page tabs do not**, which is the whole reason
 * these are built here instead of shipping as `res/drawable` vectors. [androidx.compose.material3.Icon]
 * flattens a drawable to one `tint`, and a static multi-colour asset cannot say "azure, except when
 * this notebook is green" — the shelf's rows are the one place a closed notebook's colour is
 * visible, and losing it to a fixed blue would make seven shelved notebooks look alike. The rail
 * already tints `MaterialSymbols.Book` the same way (`NotebookRail.kt`), so a shelved notebook and
 * a railed one are the same colour and, now, nearly the same shape.
 *
 * The tabs stay as drawn because they are decoration rather than identity: they read as the little
 * plastic dividers of a real notebook, and a green notebook with green dividers would lose that.
 * Their orange is [androidx.compose.material3.ColorScheme.tertiary] to the digit — the exact
 * complement of the brand azure, see `theme/Theme.kt` — so the pairing is the theme's, not a
 * second palette smuggled in.
 */
private inline fun shelfGlyph(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 16f,
        viewportHeight = 16f,
    ).apply(block).build()

// The page dividers poking out of the spine-side edge, top to bottom. Fixed colours: see the header.
private val TabCyan = Color(0xFF00FEFF)
private val TabAmber = Color(0xFFCC6F00)
private val TabOrange = Color(0xFFFF8C00)
private val CloudTabRed = Color(0xFFFF0100)
private val CloudTabOrange = Color(0xFFFF8000)

private val NotebookTabTop = addPathNodes(
    "M14.625,4 H14 v2 h0.625 C14.8321,6 15,5.83211 15,5.625 V4.375 C15,4.16789 14.8321,4 14.625,4 Z",
)
private val NotebookTabMiddle = addPathNodes(
    "m14,7 h0.625 C14.8321,7 15,7.16789 15,7.375 v1.25 C15,8.83211 14.8321,9 14.625,9 H14 Z",
)
private val NotebookTabBottom = addPathNodes(
    "M14.625,10 H14 v2 h0.625 C14.8321,12 15,11.8321 15,11.625 v-1.25 C15,10.1679 14.8321,10 " +
        "14.625,10 Z",
)

/** The inner leaf: the page edge that shows the notebook is more than one sheet thick. */
private val NotebookLeaf = addPathNodes(
    "m3.55,1.6571429 c-0.4142072,-0.00152 -1.0104787,0.7358034 -1.0071429,1.15 L2.6285714,13.45 " +
        "c0.00334,0.414187 0.6786582,0.924457 1.0928572,0.921429 l7.8142854,-0.05714 " +
        "C11.949903,14.311257 12.4011,13.721341 12.4,13.307143 L12.371429,2.55 " +
        "C12.370328,2.1357915 11.749911,1.6872343 11.335714,1.6857143 Z",
)

/** The cover, rounded at all four corners. */
private val NotebookCoverOutline = addPathNodes(
    "M2,2.75 C2,1.7835 2.7835,1 3.75,1 h7.5 C12.2165,1 13,1.7835 13,2.75 v10.5 C13,14.2165 " +
        "12.2165,15 11.25,15 H3.75 C2.7835,15 2,14.2165 2,13.25 Z",
)

/** The title label on the cover, and the rule inside it. */
private val NotebookLabel = addPathNodes(
    "M4.75,3 C4.33579,3 4,3.33579 4,3.75 v1.5 C4,5.66421 4.33579,6 4.75,6 h5.5 C10.6642,6 " +
        "11,5.66421 11,5.25 V3.75 C11,3.33579 10.6642,3 10.25,3 Z",
)
private val NotebookLabelRule = addPathNodes("M5,5 V4 h5 v1 z")

private val CloudTabTopShape = addPathNodes(
    "m12.72679,8.0640394 h0.558687 c0.185127,0 0.335213,0.1455598 0.335213,0.3251232 " +
        "v1.0837438 c0,0.1795634 -0.150086,0.3251232 -0.335213,0.3251232 H12.72679 Z",
)
private val CloudTabBottomShape = addPathNodes(
    "M13.285477,10.665025 H12.72679 v1.73399 h0.558687 c0.185127,0 0.335213,-0.145569 " +
        "0.335213,-0.325123 v-1.083744 c0,-0.179555 -0.150086,-0.325123 -0.335213,-0.325123 z",
)

/**
 * The cloud notebook's cover, which is a filled outline rather than a stroked one.
 *
 * It is drawn open at the top right — the stroke stops where the cloud badge crosses it — so it
 * cannot be a closed stroked rectangle the way [NotebookCoverOutline] is. The two stray coordinates
 * the path opens with are the artwork's own and are left in: they draw nothing, and removing them
 * would be the first hand edit to a traced path.
 */
private val CloudNotebookCover = addPathNodes(
    "M7.2960616,2.813303 7.2837066,2.832964 M8.2833731,3.7791731 7.2931047,3.729064 h-1.86439 " +
        "-1.8643899 c-0.370262,0 -0.6704244,0.2911283 -0.6704244,0.6502463 v9.1034487 " +
        "c0,0.359109 0.3001624,0.650246 0.6704244,0.650246 H10.26857 c0.370253,0 " +
        "0.670424,-0.291137 0.670424,-0.650246 l0.06897,-5.7413796 0.475426,0.030779 " +
        "0.366744,0.055428 -0.01724,5.6551721 C11.830338,14.320705 11.132521,15 10.268568,15 " +
        "H3.5643236 C2.70037,15 2,14.320709 2,13.482759 V4.3793103 C2,3.5413596 2.70037,2.862069 " +
        "3.5643236,2.862069 h1.890252 L8.28892,2.9066515",
)

/** The label on the cloud notebook's cover: open on the right, where the badge covers it. */
private val CloudNotebookLabel = addPathNodes(
    "M8.5654981,5.8381663 7.8155293,4.6930549 4.3256402,4.7065445 C3.955381,4.7079757 " +
        "3.6402709,4.993781 3.6434971,5.3528846 l0.011719,1.3043988 c0.00323,0.3591036 " +
        "0.3001647,0.6489586 0.6704244,0.6502463 l4.3358928,0.01508",
)
private val CloudNotebookLabelRule = addPathNodes(
    "m4.78125,5.453125 h2.578125 c0.2856562,0 0.515625,0.2299687 0.515625,0.515625 " +
        "0,0.2856562 -0.2299688,0.515625 -0.515625,0.515625 H4.78125 c-0.2856563,0 " +
        "-0.515625,-0.2299688 -0.515625,-0.515625 0,-0.2856563 0.2299687,-0.515625 " +
        "0.515625,-0.515625 z",
)

/** The badge's ring, and the cloud inside it — a filled silhouette under the outlined cloud. */
private val CloudBadgeRing = addPathNodes(
    "M15.068689,4.0110025 A3.218555,3.1575975 0 0 1 11.850134,7.1686001 3.218555,3.1575975 0 0 " +
        "1 8.6315789,4.0110025 3.218555,3.1575975 0 0 1 11.850134,0.853405 3.218555,3.1575975 0 " +
        "0 1 15.068689,4.0110025 Z",
)
private val CloudFill = addPathNodes(
    "M10.587405,4.9598722 C10.287481,4.9050793 10.030335,4.6495385 9.9659298,4.3422777 " +
        "9.9282493,4.1625099 9.9507178,3.9739649 10.029042,3.8126124 c0.07724,-0.1591271 " +
        "0.215778,-0.2976775 0.374622,-0.374676 0.102018,-0.049451 0.182659,-0.06738 " +
        "0.328093,-0.072944 l0.120437,-0.0046 0.0045,-0.0885 c0.0091,-0.1810884 " +
        "0.07246,-0.3842558 0.16376,-0.5259247 0.175181,-0.2718315 0.434402,-0.4572138 " +
        "0.726827,-0.5197914 0.09666,-0.020685 0.357939,-0.020657 0.454382,4.55e-5 " +
        "0.218089,0.046818 0.388712,0.1405969 0.553421,0.3041739 0.16551,0.1643749 " +
        "0.274556,0.3628566 0.319375,0.581313 0.01901,0.092636 0.02739,0.2412916 " +
        "0.02771,0.4909201 l2.82e-4,0.2201141 0.217227,0.00389 c0.234804,0.0042 " +
        "0.267073,0.00893 0.360717,0.052845 0.142814,0.066982 0.26082,0.2096357 " +
        "0.303785,0.3672327 0.0099,0.036135 0.01422,0.081841 0.01448,0.1513328 " +
        "4.21e-4,0.1173805 -0.01151,0.1707401 -0.06057,0.2708065 -0.02693,0.05495 " +
        "-0.04683,0.081327 -0.105582,0.1400412 -0.05855,0.058484 -0.08537,0.078725 " +
        "-0.14016,0.1056783 -0.123029,0.060532 -0.0138,0.056737 -1.614255,0.056089 " +
        "-1.129381,-4.572e-4 -1.446773,-0.00275 -1.490693,-0.010775 z",
)
private val CloudOutline = addPathNodes(
    "m10.782484,5.3695533 q-0.484576,0 -0.8280379,-0.3428661 Q9.6109833,4.683821 " +
        "9.6109833,4.18857 q0,-0.4245009 0.2502753,-0.7564824 0.2502744,-0.3319815 " +
        "0.6549744,-0.4245009 0.133126,-0.5006934 0.532501,-0.8109056 0.399375,-0.3102122 " +
        "0.90525,-0.3102122 0.623025,0 1.057013,0.443549 0.433988,0.443549 0.433988,1.0803004 " +
        "0.367425,0.043539 0.609712,0.323818 0.242288,0.2802794 0.242288,0.6557995 " +
        "0,0.4081739 -0.279563,0.6938957 -0.279563,0.2857218 -0.678938,0.2857218 z " +
        "m0,-0.4353856 h2.556 q0.223651,0 0.378075,-0.1578272 0.154426,-0.1578273 " +
        "0.154426,-0.3864047 0,-0.2285774 -0.154426,-0.3864047 -0.154424,-0.1578272 " +
        "-0.378075,-0.1578272 h-0.3195 V3.4103183 q0,-0.4517125 -0.311512,-0.7700882 " +
        "-0.311513,-0.3183756 -0.753488,-0.3183756 -0.441975,0 -0.753488,0.3183756 " +
        "-0.311512,0.3183757 -0.311512,0.7700882 h-0.1065 q-0.308851,0 -0.527176,0.2231351 " +
        "-0.218325,0.2231351 -0.218325,0.5387896 0,0.3156545 0.218325,0.5387896 " +
        "0.218325,0.2231351 0.527176,0.2231351 z m1.1715,-1.3061566 z",
)

/**
 * A notebook that is still on this device: a stroked cover, three page tabs, a title label.
 *
 * Every line is one stroke width in the artwork and they are deliberately unequal — the leaf is the
 * heaviest at 1, the label 0.5, the cover 0.4, the rule 0.3 — so the cover reads as card and the
 * leaf as paper. Rendered at the 32dp the shelf uses, the thinnest of those is about half a dp: it
 * is meant to be a hairline, not a line that failed to draw.
 */
fun notebookGlyph(body: Color): ImageVector = shelfGlyph("Notebook") {
    addPath(NotebookTabTop, fill = SolidColor(TabCyan))
    addPath(NotebookTabMiddle, fill = SolidColor(TabAmber))
    addPath(NotebookTabBottom, fill = SolidColor(TabOrange))
    addPath(NotebookLeaf, stroke = SolidColor(body), strokeLineWidth = 1f)
    addPath(NotebookCoverOutline, stroke = SolidColor(body), strokeLineWidth = 0.4f)
    addPath(NotebookLabel, stroke = SolidColor(body), strokeLineWidth = 0.5f)
    addPath(NotebookLabelRule, stroke = SolidColor(body), strokeLineWidth = 0.3f)
}

/**
 * A notebook whose pages now live on the server: the same cover, badged with a cloud.
 *
 * The cover is filled rather than stroked here because the badge overlaps it — a stroked rectangle
 * would have to be drawn behind the badge and would show through it. Two tabs instead of three: the
 * badge sits where the third would be.
 */
fun cloudNotebookGlyph(body: Color): ImageVector = shelfGlyph("CloudNotebook") {
    addPath(CloudTabBottomShape, fill = SolidColor(CloudTabOrange))
    addPath(CloudTabTopShape, fill = SolidColor(CloudTabRed))
    addPath(CloudNotebookCover, fill = SolidColor(body))
    addPath(CloudNotebookLabel, stroke = SolidColor(body), strokeLineWidth = 0.28f)
    addPath(CloudNotebookLabelRule, stroke = SolidColor(body), strokeLineWidth = 0.28f)
    addPath(CloudFill, fill = SolidColor(body))
    addPath(CloudOutline, fill = SolidColor(body))
    addPath(CloudBadgeRing, stroke = SolidColor(body), strokeLineWidth = 0.6f)
}

/**
 * The glyph for one shelf row, rebuilt only when its colour or its whereabouts change.
 *
 * Construction is a few dozen [androidx.compose.ui.graphics.vector.VectorPath]s and no
 * rasterisation, but the shelf recomposes whenever a cloud operation starts or finishes, and every
 * row would rebuild both glyphs each time. [AppIcons] hoists the ribbon's glyphs to the theme for
 * the same reason; these cannot be hoisted, because their colour is per notebook.
 */
@Composable
fun rememberNotebookGlyph(body: Color, inCloud: Boolean): ImageVector =
    remember(body, inCloud) { if (inCloud) cloudNotebookGlyph(body) else notebookGlyph(body) }
