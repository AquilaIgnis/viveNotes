package com.vivenotes.richtext

import android.content.Context
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Builds a native RaTeX display list and renderer without doing parser work on the main thread. */
internal suspend fun createEquationRenderer(
    context: Context,
    latex: String,
    fontSizePx: Float,
    color: Int,
): RaTeXRenderer = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    RaTeXFontLoader.ensureLoaded(appContext)
    val displayList = RaTeXEngine.parse(latex, false, color)
    RaTeXRenderer(displayList, fontSizePx) { name -> RaTeXFontLoader.getTypeface(name) }
}

/**
 * The same formula at a different size, without parsing it again.
 *
 * A `RaTeXRenderer` is a display list in em units plus the font size those units are multiplied by,
 * so resizing is arithmetic: the layout is already done and only the multiplier changes. That is why
 * a caller which has to try a size — fitting a result to the width of a pane, say — can do it here
 * rather than paying for `RaTeXEngine.parse` a second time. Fonts are loaded by whoever built the
 * original, so this needs no suspension.
 */
internal fun RaTeXRenderer.resizedTo(fontSizePx: Float): RaTeXRenderer =
    RaTeXRenderer(displayList, fontSizePx) { name -> RaTeXFontLoader.getTypeface(name) }

/**
 * The same formula at the largest size that fits [availableWidthPx], never smaller than
 * [minFontSizePx]. Already narrow enough, and it is returned untouched.
 *
 * This is what keeps a long result inside the pane it is drawn in. A rendered formula is as wide as
 * it turns out to be — a cubic's three roots, an antiderivative, a 4x4 inverse — and a preview drawn
 * at one fixed size puts the tail of a wide one past the edge of its box. Shrinking beats wrapping:
 * a renderer that does not know where the terms end breaks the line in the wrong place.
 *
 * The floor is the other half. Below some size the answer is on screen without being legible, so a
 * formula that still does not fit stops at the floor and stays wide, leaving the caller to scroll it.
 */
internal fun RaTeXRenderer.fittedTo(availableWidthPx: Float, minFontSizePx: Float): RaTeXRenderer {
    if (widthPx <= 0f || widthPx <= availableWidthPx) return this
    val fitted = (fontSize * availableWidthPx / widthPx).coerceAtLeast(minFontSizePx)
    return if (fitted < fontSize) resizedTo(fitted) else this
}
