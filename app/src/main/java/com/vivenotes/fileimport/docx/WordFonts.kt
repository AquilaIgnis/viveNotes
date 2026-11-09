package com.vivenotes.fileimport.docx

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint

/**
 * A device typeface standing in for one of Word's, and what it takes to keep the document's shape.
 *
 * [sizeScale] makes a line of text as long as it was in Word, so lines break where Word broke them;
 * [lineHeightFactor] makes it as tall.
 */
internal class WordFace(val typeface: Typeface, val sizeScale: Float, val lineHeightFactor: Float)

/**
 * Word's fonts are not on Android, and a page count depends on nothing so much as the font.
 *
 * So each is drawn in the system family closest to it, at the size that makes a line the same length.
 * Sizes are chosen against the device's own font, measured here, because a phone from another maker
 * may not call Roboto "sans-serif". The numbers in [KNOWN] are measured from the metric-compatible
 * fonts — Carlito for Calibri, Caladea for Cambria, Liberation for Arial, Times New Roman and Courier
 * New — or else taken from the font's published metrics.
 */
internal class WordFonts(private val fontTable: Map<String, String> = emptyMap()) {
    private class Known(val generic: String, val widthPerEm: Float, val lineHeightPerEm: Float)

    private class GenericMetrics(val widthPerEm: Float, val lineHeightPerEm: Float)

    private val generics = HashMap<String, GenericMetrics>()
    private val faces = HashMap<Triple<String, Boolean, Boolean>, WordFace>()

    private fun metricsOf(generic: String): GenericMetrics = generics.getOrPut(generic) {
        val paint = TextPaint().apply {
            typeface = Typeface.create(generic, Typeface.NORMAL)
            textSize = MEASURE_SIZE
            isLinearText = true
            isSubpixelText = true
        }
        val width = paint.measureText(SAMPLE) / MEASURE_SIZE / SAMPLE.length
        val metrics = paint.fontMetrics
        GenericMetrics(width, (metrics.descent - metrics.ascent + metrics.leading) / MEASURE_SIZE)
    }

    private fun genericFor(name: String): String {
        val lower = name.lowercase()
        if (isMonospaceFontName(lower)) return "monospace"
        when (fontTable[lower]) {
            "modern" -> return "monospace"
            "roman" -> return "serif"
            "swiss" -> return "sans-serif"
        }
        return if (SERIF_NAME_PARTS.any { it in lower }) "serif" else "sans-serif"
    }

    fun face(name: String?, bold: Boolean, italic: Boolean): WordFace {
        val key = Triple(name?.lowercase().orEmpty(), bold, italic)
        return faces.getOrPut(key) {
            val known = name?.lowercase()?.let(KNOWN::get)
            val generic = known?.generic ?: name?.let(::genericFor) ?: "sans-serif"
            val metrics = metricsOf(generic)
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            val sizeScale = known?.let { (it.widthPerEm / metrics.widthPerEm).coerceIn(0.7f, 1.3f) } ?: 1f
            val lineHeight = known?.lineHeightPerEm ?: (metrics.lineHeightPerEm * sizeScale)
            WordFace(
                typeface = Typeface.create(generic, style),
                sizeScale = sizeScale,
                lineHeightFactor = lineHeight / (metrics.lineHeightPerEm * sizeScale),
            )
        }
    }

    private companion object {
        const val MEASURE_SIZE = 1000f

        /** A run of ordinary English, because a font's width is an average over the letters used. */
        const val SAMPLE =
            "The IEEE Floating Point Standard was adopted in 1985 and is the standard still used today. " +
                "We have learned how to represent binary integers and digits to the right would not be much " +
                "more difficult, but the question remains: how to store the decimal point in binary? " +
                "Scientific notation expresses a real value this way, with a single non-zero digit."

        val SERIF_NAME_PARTS = listOf(
            "serif", "times", "georgia", "garamond", "palatino", "book antiqua", "cambria", "minion", "baskerville",
            "bodoni", "constantia", "century", "roman", "didot",
        )

        val KNOWN: Map<String, Known> = mapOf(
            "calibri" to Known("sans-serif", 0.400f, 1.2207f),
            "calibri light" to Known("sans-serif", 0.395f, 1.2207f),
            "aptos" to Known("sans-serif", 0.440f, 1.221f),
            "aptos display" to Known("sans-serif", 0.430f, 1.221f),
            "aptos narrow" to Known("sans-serif", 0.380f, 1.221f),
            "arial" to Known("sans-serif", 0.4362f, 1.1499f),
            "helvetica" to Known("sans-serif", 0.4362f, 1.1499f),
            "helvetica neue" to Known("sans-serif", 0.4362f, 1.1499f),
            "arial narrow" to Known("sans-serif", 0.358f, 1.1499f),
            "tahoma" to Known("sans-serif", 0.449f, 1.207f),
            "verdana" to Known("sans-serif", 0.511f, 1.215f),
            "segoe ui" to Known("sans-serif", 0.441f, 1.33f),
            "trebuchet ms" to Known("sans-serif", 0.439f, 1.161f),
            "century gothic" to Known("sans-serif", 0.483f, 1.18f),
            "cambria" to Known("serif", 0.420f, 1.1724f),
            "times new roman" to Known("serif", 0.3966f, 1.1499f),
            "times" to Known("serif", 0.3966f, 1.1499f),
            "georgia" to Known("serif", 0.457f, 1.136f),
            "garamond" to Known("serif", 0.371f, 1.125f),
            "palatino linotype" to Known("serif", 0.420f, 1.35f),
            "book antiqua" to Known("serif", 0.420f, 1.24f),
            "courier new" to Known("monospace", 0.6001f, 1.1328f),
            "courier" to Known("monospace", 0.6001f, 1.1328f),
            "consolas" to Known("monospace", 0.5498f, 1.1709f),
            "lucida console" to Known("monospace", 0.6025f, 1.14f),
        )
    }
}

/** The text-drawing flags every paint in this package carries, so layout scales without rounding. */
internal const val DOCX_PAINT_FLAGS: Int =
    Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
