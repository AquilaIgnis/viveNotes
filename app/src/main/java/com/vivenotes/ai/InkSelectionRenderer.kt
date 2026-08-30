package com.vivenotes.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.projectionKey
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeContour
import com.vivenotes.model.ink.contours
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the lasso's selection to a high-contrast, tightly bounded bitmap: its ink projections, and
 * the **line and arrow shapes** it holds with them.
 *
 * A line drawn with the Line tool is part of the formula it sits in — a fraction bar, a minus, the
 * bar over a radical, a vector's arrow — and leaving it out was leaving the recogniser to read
 * `\frac{1}{2}` as two numbers with a gap. It is a mark that happens to be stored as a shape rather
 * than as a stroke, so it is inked here the same way a stroke is: black, at the recognition stem
 * width, ignoring the border width, colour and line type it carries on the page. The model is being
 * shown a formula, not a drawing, and a dashed rule means nothing to it that a solid one does not.
 *
 * [shapes] is the page's, filtered here rather than by the caller, so what is drawn is decided by the
 * same rule that decides whether the Math button appears at all — `CanvasSelection.isInkAndLines`.
 */
internal fun renderInkSelection(
    strokes: List<PageStroke>,
    shapes: List<Outline.Shape>,
    selection: CanvasSelection,
): Bitmap {
    require(selection.inkIds.isNotEmpty()) { "Recognition requires ink" }
    val bounds = selection.bounds
    val pageWidth = (bounds.right - bounds.left).coerceAtLeast(1f)
    val pageHeight = (bounds.bottom - bounds.top).coerceAtLeast(1f)
    val longest = max(pageWidth, pageHeight)
    val stemPageUnits = recognitionStemSize(longest)
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
            // Black, and re-drawn at the recognition width rather than the width it was written at
            // — see [recognitionStemSize]. `Stroke.copy` regenerates the mesh from the same inputs
            // when the new brush needs a different one, so this is the stroke the user drew, inked
            // by a different pen. A projection carrying a resize divides the width back out, or a
            // stroke shrunk to a quarter would come out a quarter as thick as everything beside it.
            val highContrast = pageStroke.stroke.copy(
                pageStroke.stroke.brush.copyWithColorIntArgb(
                    colorIntArgb = Color.BLACK,
                    size = (stemPageUnits / pageStroke.strokeScale()).coerceAtLeast(MIN_STEM_SIZE),
                ),
            )
            renderer.draw(canvas, highContrast, strokeMatrix)
            canvas.restoreToCount(checkpoint)
        }

    // The rules, at the same stem width as the ink beside them — which is the whole point of drawing
    // them here rather than leaving them out: a bar the model sees at a different weight from the
    // digits above and below it is a bar it reads as something else. Page units, because the canvas
    // carries the page transform.
    val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = stemPageUnits
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val ruled = shapes.filter { it.id in selection.shapeIds && it.kind.hasEnds }
    if (ruled.isNotEmpty()) {
        val checkpoint = canvas.save()
        canvas.concat(pageToBitmap)
        ruled.forEach { shape ->
            // By contour, so an arrow's head is one path with its joins mitred rather than two
            // segments meeting as a pair of caps — the same reason `ShapeLayer` strokes them this way.
            shape.segments.contours().forEach { canvas.drawPath(it.asPath(), rulePaint) }
        }
        canvas.restoreToCount(checkpoint)
    }
    return bitmap
}

/** One contour as a path to stroke. Straight throughout on the kinds that reach here. */
private fun ShapeContour.asPath(): Path = Path().apply {
    val points = polyline()
    if (points.size < 4) return@apply
    moveTo(points[0], points[1])
    for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
}

/**
 * How wide to ink a stroke so the stem lands at [RECOGNITION_STEM_PX] in the model's own square.
 *
 * **Thickness has to be stated in the frame the model sees, not in page units.**
 * `preprocessFormula` scales every crop to fit 384, so one width in page units becomes a different
 * width in the tensor for every selection: a formula written large is squeezed to a hairline and a
 * small one comes out fat. Measured on the three formulas of page 3 (`simulations/formula-render`),
 * that single confusion is worth more than everything else in the pipeline put together — mean
 * token accuracy 0.615 at the stored 2 dp, 0.898 once the stem is pinned here, and the first exact
 * readings the model has ever produced on that page.
 *
 * Solving `stem * 384 / (longest + stem) = target` for the width to draw with:
 *
 *     stem = target * longest / (384 - target)
 *
 * [longest] is the long edge of the selection in page units. It is the *mesh* bounds, so it already
 * carries the stored stroke's half-width at each end — about 1% on a formula this size, and a
 * rounding error next to the width being solved for.
 *
 * 10 px was measured, not chosen: 2 px reads as noise and scored 0.617, 24 px blots the counters
 * shut and scored 0.437, and the curve between them is not monotonic — 12 and 13 px both dip. Nudge
 * this only with the sweep in `simulations/formula-render` in front of you.
 */
internal fun recognitionStemSize(longest: Float): Float =
    RECOGNITION_STEM_PX * longest / (FORMULA_INPUT_PX - RECOGNITION_STEM_PX)

/** The page transform's average scale, for dividing a stem width back out of a resized stroke. */
private fun PageStroke.strokeScale(): Float {
    val average = (abs(scaleX) + abs(scaleY)) / 2f
    return if (average > 0f) average else 1f
}

private const val TARGET_LONG_EDGE = 1024f
private const val MIN_SCALE = 2f
private const val MAX_SCALE = 8f
private const val PADDING_PAGE_UNITS = 12f
private const val MAX_BITMAP_EDGE = 2048

/** Matches `InkRecognitionEngine`'s `FORMULA_SIZE`: the square PP-FormulaNet-S is fed. */
internal const val FORMULA_INPUT_PX = 384f
internal const val RECOGNITION_STEM_PX = 10f

/** A brush size must be greater than zero, and a hairline is not worth rendering anyway. */
private const val MIN_STEM_SIZE = 0.05f
