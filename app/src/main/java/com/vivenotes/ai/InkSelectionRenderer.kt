package com.vivenotes.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.projectionKey
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Renders only the lasso's selected ink projections to a high-contrast, tightly bounded bitmap. */
internal fun renderInkSelection(
    strokes: List<PageStroke>,
    selection: CanvasSelection,
): Bitmap {
    require(selection.isInkOnly && selection.inkIds.isNotEmpty()) { "Recognition requires ink" }
    val bounds = selection.bounds
    val pageWidth = (bounds.right - bounds.left).coerceAtLeast(1f)
    val pageHeight = (bounds.bottom - bounds.top).coerceAtLeast(1f)
    val longest = max(pageWidth, pageHeight)
    val scale = min(MAX_SCALE, max(MIN_SCALE, TARGET_LONG_EDGE / longest))
    val padding = PADDING_PAGE_UNITS * scale
    val width = ceil(pageWidth * scale + padding * 2).toInt().coerceIn(1, MAX_BITMAP_EDGE)
    val height = ceil(pageHeight * scale + padding * 2).toInt().coerceIn(1, MAX_BITMAP_EDGE)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val pageToBitmap = Matrix().apply {
        setScale(scale, scale)
        postTranslate(padding - bounds.left * scale, padding - bounds.top * scale)
    }
    val renderer = CanvasStrokeRenderer.create()
    strokes
        .filter { it.projectionKey in selection.projections }
        .forEach { pageStroke ->
            val strokeMatrix = Matrix(pageToBitmap).apply {
                preTranslate(pageStroke.offsetX, pageStroke.offsetY)
                preScale(pageStroke.scaleX, pageStroke.scaleY)
            }
            val checkpoint = canvas.save()
            canvas.concat(strokeMatrix)
            val highContrast = pageStroke.stroke.copy(
                pageStroke.stroke.brush.copyWithColorIntArgb(Color.BLACK),
            )
            renderer.draw(canvas, highContrast, strokeMatrix)
            canvas.restoreToCount(checkpoint)
        }
    return bitmap
}

private const val TARGET_LONG_EDGE = 1024f
private const val MIN_SCALE = 2f
private const val MAX_SCALE = 8f
private const val PADDING_PAGE_UNITS = 12f
private const val MAX_BITMAP_EDGE = 2048
