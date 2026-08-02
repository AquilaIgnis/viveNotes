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
