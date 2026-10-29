package com.vivenotes.ui.editor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.unit.Density
import com.vivenotes.ink.PasteFrames
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.richtext.EditorStyle
import com.vivenotes.richtext.OutlineEditText
import kotlin.math.roundToInt

/**
 * How tall the canvas will draw each of a paste's [outlines], measured with an editor styled as
 * every writing surface is, without drawing it, attaching it or touching the document: a text box's
 * frame is its text, its floor and its padding; a table's is its rows, each as tall as its tallest
 * cell. Insets say how far below an outline's y that frame starts — the grip strip, the gutter.
 */
internal fun measurePastedOutlines(
    context: Context,
    density: Density,
    editorStyle: EditorStyle,
    outlines: List<Outline>,
): PasteFrames {
    val editor = OutlineEditText(context).apply {
        // A `TextView` reads its layout params when its text changes after a layout, attached or not.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        useBaseStyle()
        this.editorStyle = editorStyle
    }

    fun Float.px(): Int = (this * density.density).roundToInt()

    /** The editor's height holding [blocks] at [width], in dp. */
    fun textHeight(blocks: List<Block>, width: Float, minHeight: Float = 0f): Float {
        editor.setBlocks(blocks)
        editor.minimumHeight = minHeight.px()
        editor.measure(
            View.MeasureSpec.makeMeasureSpec(width.px().coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return editor.measuredHeight / density.density
    }

    val padding = TEXT_BOX_PADDING.value * 2
    val cellPadding = CELL_PADDING.value * 2
    val heights = mutableMapOf<String, Float>()
    val insets = mutableMapOf<String, Float>()
    for (outline in outlines) {
        heights[outline.id] = when (outline) {
            is Outline.Text -> {
                insets[outline.id] = GRIP_HEIGHT.value
                textHeight(outline.blocks, outline.width - padding, outline.minHeight) + padding
            }
            is Outline.Table -> {
                insets[outline.id] = TABLE_GUTTER.value
                outline.rows.sumOf { row ->
                    val tallest = row.cells.mapIndexed { column, cell ->
                        if (outline.inkOnly) 0f
                        else textHeight(cell.blocks, outline.columns.getOrElse(column) { 0f } - cellPadding) + cellPadding
                    }.maxOrNull() ?: 0f
                    maxOf(row.minHeight, tallest).toDouble()
                }.toFloat()
            }
            is Outline.Image -> outline.height
            is Outline.Shape -> outline.height
            is Outline.Equation -> outline.height
            is Outline.Ink -> outline.height
        }
    }
    return PasteFrames(heights, insets)
}
