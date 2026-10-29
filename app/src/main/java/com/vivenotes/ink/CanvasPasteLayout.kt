package com.vivenotes.ink

import com.vivenotes.model.Outline

internal const val CANVAS_PASTE_MARGIN = 24f

/**
 * How tall each pasted outline is drawn, by id, and how far below its y what is drawn begins: a
 * text box keeps a strip for its move grip above its frame, a table a gutter for its handles.
 */
data class PasteFrames(val heights: Map<String, Float>, val insets: Map<String, Float> = emptyMap())

/**
 * Stacks [outlines] down from the first one's y, leaving [CANVAS_PASTE_MARGIN] between each one's
 * frame and the next. Heights include text padding, minimum box sizes, and the table's rows.
 */
internal fun stackPastedOutlines(
    outlines: List<Outline>,
    heights: Map<String, Float>,
    insets: Map<String, Float> = emptyMap(),
): List<Outline> {
    var top = 0f
    return outlines.mapIndexed { index, outline ->
        val height = requireNotNull(heights[outline.id])
        require(height.isFinite() && height >= 0f)
        val inset = insets[outline.id] ?: 0f
        val y = if (index == 0) 0f else top - inset
        val placed = when (outline) {
            is Outline.Text -> outline.copy(y = y)
            is Outline.Table -> outline.copy(y = y)
            is Outline.Image -> outline.copy(y = y)
            // Its segments are placed on the page as well, so the whole shape moves.
            is Outline.Shape -> outline.translated(0f, y - outline.y)
            is Outline.Equation -> outline.copy(y = y)
            is Outline.Ink -> outline.copy(y = y)
        }
        top = y + inset + height + CANVAS_PASTE_MARGIN
        placed
    }
}

/** A paste of several objects, waiting for the canvas to say how tall each one is drawn. */
class CanvasPasteLayoutRequest internal constructor(val outlines: List<Outline>) {
    internal val result = kotlinx.coroutines.CompletableDeferred<List<Outline>>()
}

/**
 * How tall the document alone says an outline is, for a paste no canvas measured: a text box's
 * floor or a line a paragraph, a table's row floors.
 */
internal fun Outline.documentHeight(): Float = when (this) {
    is Outline.Text -> maxOf(minHeight, blocks.size * ESTIMATED_LINE_HEIGHT)
    is Outline.Table -> height
    is Outline.Image -> height
    is Outline.Shape -> height
    is Outline.Equation -> height
    is Outline.Ink -> height
}

private const val ESTIMATED_LINE_HEIGHT = 24f
