package com.vivenotes.model.ocr

import com.vivenotes.model.search.FuzzyMatcher
import com.vivenotes.model.search.MatchSpan

/**
 * Boxes on one picture, as fractions of it.
 *
 * Flat rather than a list of objects because the canvas walks it on every frame of a scroll: eight
 * values per box, x then y for each corner in turn around the box.
 */
class PictureHighlight internal constructor(val quads: FloatArray) {

    val count: Int get() = quads.size / QUAD_VALUES

    /** The leftmost and topmost a box reaches, which is where a scroll to it should aim. */
    val left: Float get() = (0 until count * 4).minOf { quads[it * 2] }
    val top: Float get() = (0 until count * 4).minOf { quads[it * 2 + 1] }

    companion object {
        const val QUAD_VALUES = 8
    }
}

/** Turns matches in a picture's recognized lines into boxes on the picture. */
object PictureHighlights {

    /**
     * A one-letter query on a wall of text would otherwise be thousands of boxes, each one a draw call
     * on every frame of a scroll, and past a couple of hundred they stop saying anything anyway.
     */
    const val MAX_HIGHLIGHT_BOXES = 256

    /**
     * Every place [query] lands in [lines], or null if it lands nowhere.
     *
     * [layouts] is parallel to [lines]. Anything that does not line up is a row this build did not
     * write, and showing nothing is better than showing a box on the wrong word.
     */
    fun forQuery(
        query: String,
        lines: List<String>,
        layouts: List<ImageLineLayout>,
        limit: Int = MAX_HIGHLIGHT_BOXES,
    ): PictureHighlight? {
        if (lines.size != layouts.size) return null
        val boxes = BoxBuffer()
        for (index in lines.indices) {
            val line = lines[index]
            val layout = layouts[index]
            if (layout.edges.size != line.length + 1) continue
            for (span in FuzzyMatcher.occurrences(query, line)) {
                if (boxes.count >= limit) return boxes.toHighlight()
                boxes.add(layout, span)
            }
        }
        return boxes.toHighlight()
    }

    /** Boxes for [spans] of one line, for the hit that was opened, or null if they do not line up. */
    fun forSpans(line: String, layout: ImageLineLayout, spans: List<MatchSpan>): PictureHighlight? {
        if (layout.edges.size != line.length + 1) return null
        val boxes = BoxBuffer()
        spans.forEach { boxes.add(layout, it) }
        return boxes.toHighlight()
    }

    private class BoxBuffer {
        private var values = FloatArray(PictureHighlight.QUAD_VALUES * 16)
        var count = 0
            private set

        fun add(layout: ImageLineLayout, span: MatchSpan) {
            val last = layout.edges.size - 1
            // Clamped because lowercasing a line can lengthen it, which moves a span past the end.
            val from = layout.edges[span.start.coerceIn(0, last)]
            val to = layout.edges[span.end.coerceIn(0, last)]
            if (to <= from) return

            val needed = (count + 1) * PictureHighlight.QUAD_VALUES
            if (needed > values.size) values = values.copyOf(values.size * 2)

            val corners = layout.corners
            var at = count * PictureHighlight.QUAD_VALUES
            values[at++] = lerp(corners[0], corners[2], from)
            values[at++] = lerp(corners[1], corners[3], from)
            values[at++] = lerp(corners[0], corners[2], to)
            values[at++] = lerp(corners[1], corners[3], to)
            values[at++] = lerp(corners[6], corners[4], to)
            values[at++] = lerp(corners[7], corners[5], to)
            values[at++] = lerp(corners[6], corners[4], from)
            values[at] = lerp(corners[7], corners[5], from)
            count++
        }

        fun toHighlight(): PictureHighlight? =
            if (count == 0) null else PictureHighlight(values.copyOf(count * PictureHighlight.QUAD_VALUES))

        private fun lerp(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount
    }
}
