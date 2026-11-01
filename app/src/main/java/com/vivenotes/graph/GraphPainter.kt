package com.vivenotes.graph

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.vivenotes.model.GraphBackground
import com.vivenotes.model.Outline
import io.ratex.RaTeXRenderer
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Where a graph's parts sit inside its box, and how its units map onto the canvas.
 *
 * [unit] is canvas units per dp: the display density on the page, 1 in a PDF, whose canvas is
 * already in dp. Every size below is in dp times [unit], so a tick label is the same size on paper
 * as on the screen.
 */
class GraphFrame(
    val graph: Outline.Graph,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    val unit: Float,
    val window: GraphWindow = graph.window(),
) {
    val box = RectF(left, top, left + width, top + height)

    /** The band the equation is drawn in, or null when it is hidden or the box is too short for it. */
    val header: RectF? = if (graph.showEquation && height >= HEADER_DP * unit * 2.5f) {
        RectF(box.left, box.top, box.right, box.top + HEADER_DP * unit)
    } else {
        null
    }

    val plot = RectF(box.left, header?.bottom ?: box.top, box.right, box.bottom)

    fun canvasX(x: Double): Float = (plot.left + (x - window.xMin) / window.width * plot.width()).toFloat()
    fun canvasY(y: Double): Float = (plot.bottom - (y - window.yMin) / window.height * plot.height()).toFloat()
    fun graphX(canvasX: Float): Double = window.xMin + (canvasX - plot.left) / plot.width() * window.width
    fun graphY(canvasY: Float): Double = window.yMin + (plot.bottom - canvasY) / plot.height() * window.height

    /** The point of interest within [reachDp] of a canvas position, nearest first. */
    fun pointNear(points: List<GraphPoint>, x: Float, y: Float, reachDp: Float = POINT_REACH_DP): GraphPoint? =
        points
            .filter { it.x in window.xMin..window.xMax && it.y in window.yMin..window.yMax }
            .minByOrNull { hypot(canvasX(it.x) - x, canvasY(it.y) - y) }
            ?.takeIf { hypot(canvasX(it.x) - x, canvasY(it.y) - y) <= reachDp * unit }

    companion object {
        const val HEADER_DP = 36f

        /** How far from a point a finger may land and still take it — a touch target, not the dot. */
        const val POINT_REACH_DP = 22f
    }
}

/** What a graph is drawn in. Plain ARGB, so the canvas and a PDF can both supply them. */
data class GraphColors(
    val paper: Int,
    val text: Int,
    val curve: Int,
    val accent: Int,
)

/**
 * Draws a graph onto any [Canvas] — the page's, through Compose's native canvas, and a PDF page's.
 *
 * One painter for both, so an exported graph is the graph on the screen: the same grid, the same
 * labels, the same points of interest.
 */
class GraphPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val path = Path()

    /**
     * @param function the curve as drawn now — the stored function, or a drag's preview.
     * @param original the function the graph was placed with, drawn faint beneath; null for none.
     * @param points the points of interest of [function] in the window.
     * @param handles whether the points are grabbable: drawn as handles rather than dots.
     * @param active the point being dragged, highlighted.
     */
    fun draw(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        function: (Double) -> Double,
        original: ((Double) -> Double)?,
        points: List<GraphPoint>,
        header: RaTeXRenderer?,
        handles: Boolean = false,
        active: Pair<Double, Double>? = null,
    ) {
        val graph = frame.graph
        val unit = frame.unit
        val checkpoint = canvas.save()
        canvas.clipRect(frame.box)

        // Its own sheet: the page's ruling under a grid of different spacing reads as one mess.
        fill.color = colors.paper
        canvas.drawRect(frame.box, fill)

        frame.header?.let { drawHeader(canvas, frame, it, colors, header) }

        canvas.save()
        canvas.clipRect(frame.plot)
        val xStep = GraphAnalysis.niceStep(frame.window.width, tickCount(frame.plot.width() / unit))
        val yStep = GraphAnalysis.niceStep(frame.window.height, tickCount(frame.plot.height() / unit))
        drawBackground(canvas, frame, colors, xStep, yStep)
        drawAxes(canvas, frame, colors, xStep, yStep)

        if (graph.showDerivative) {
            val h = frame.window.width * 1e-5
            drawCurve(canvas, frame, { x -> (function(x + h) - function(x - h)) / (2 * h) }, DERIVATIVE_ARGB, 1.8f, dashed = true)
        }
        if (original != null) {
            drawCurve(canvas, frame, original, withAlpha(colors.curve, 0.4f), 1.8f, dashed = true)
        }
        drawCurve(canvas, frame, function, colors.curve, 2.5f, dashed = false)
        canvas.restore()

        drawPoints(canvas, frame, colors, points, handles, active)

        // The box's edge, so a graph with no background still reads as one object.
        line.pathEffect = null
        line.strokeWidth = unit
        line.color = withAlpha(colors.text, 0.25f)
        canvas.drawRect(frame.box, line)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawHeader(canvas: Canvas, frame: GraphFrame, band: RectF, colors: GraphColors, header: RaTeXRenderer?) {
        val unit = frame.unit
        fill.color = withAlpha(colors.text, 0.05f)
        canvas.drawRect(band, fill)
        val padding = 8f * unit
        if (header == null || header.widthPx <= 0f || header.heightPx + header.depthPx <= 0f) {
            text.color = colors.text
            text.textSize = 13f * unit
            text.typeface = Typeface.DEFAULT
            val label = "${frame.graph.dependent} = ${frame.graph.latex}"
            canvas.drawText(label, band.left + padding, band.centerY() + text.textSize / 3, text)
            return
        }
        val naturalHeight = header.heightPx + header.depthPx
        val scale = minOf(band.height() * 0.72f / naturalHeight, (band.width() - padding * 2) / header.widthPx)
        canvas.save()
        canvas.translate(band.left + padding, band.centerY() - naturalHeight * scale / 2)
        canvas.scale(scale, scale)
        header.draw(canvas)
        canvas.restore()
    }

    private fun drawBackground(canvas: Canvas, frame: GraphFrame, colors: GraphColors, xStep: BigDecimal, yStep: BigDecimal) {
        val unit = frame.unit
        val xs = ticks(frame.window.xMin, frame.window.xMax, xStep)
        val ys = ticks(frame.window.yMin, frame.window.yMax, yStep)
        when (frame.graph.background) {
            GraphBackground.None -> Unit
            GraphBackground.Grid -> {
                // Minor lines at a fifth of a step, only where they stay apart enough to be lines.
                val minorX = xStep.divide(BigDecimal(5))
                val minorY = yStep.divide(BigDecimal(5))
                line.pathEffect = null
                line.strokeWidth = 0.6f * unit
                line.color = withAlpha(colors.text, 0.06f)
                if (frame.plot.width() / unit / (frame.window.width / minorX.toDouble()) >= 8f) {
                    ticks(frame.window.xMin, frame.window.xMax, minorX).forEach { x ->
                        val cx = frame.canvasX(x.toDouble())
                        canvas.drawLine(cx, frame.plot.top, cx, frame.plot.bottom, line)
                    }
                }
                if (frame.plot.height() / unit / (frame.window.height / minorY.toDouble()) >= 8f) {
                    ticks(frame.window.yMin, frame.window.yMax, minorY).forEach { y ->
                        val cy = frame.canvasY(y.toDouble())
                        canvas.drawLine(frame.plot.left, cy, frame.plot.right, cy, line)
                    }
                }
                line.strokeWidth = 0.8f * unit
                line.color = withAlpha(colors.text, 0.16f)
                xs.forEach { x ->
                    val cx = frame.canvasX(x.toDouble())
                    canvas.drawLine(cx, frame.plot.top, cx, frame.plot.bottom, line)
                }
                ys.forEach { y ->
                    val cy = frame.canvasY(y.toDouble())
                    canvas.drawLine(frame.plot.left, cy, frame.plot.right, cy, line)
                }
            }
            GraphBackground.Dotted -> {
                fill.color = withAlpha(colors.text, 0.35f)
                val radius = 1.3f * unit
                xs.forEach { x ->
                    val cx = frame.canvasX(x.toDouble())
                    ys.forEach { y -> canvas.drawCircle(cx, frame.canvasY(y.toDouble()), radius, fill) }
                }
            }
        }
    }

    private fun drawAxes(canvas: Canvas, frame: GraphFrame, colors: GraphColors, xStep: BigDecimal, yStep: BigDecimal) {
        val unit = frame.unit
        val window = frame.window
        val plot = frame.plot
        line.pathEffect = null
        line.strokeWidth = 1.3f * unit
        line.color = withAlpha(colors.text, 0.75f)
        // Where each axis is, or the edge it would be beyond: the labels follow it there, so the
        // numbers stay on screen when the origin does not.
        val axisY = frame.canvasY(0.0)
        val axisX = frame.canvasX(0.0)
        if (window.yMin <= 0.0 && window.yMax >= 0.0) canvas.drawLine(plot.left, axisY, plot.right, axisY, line)
        if (window.xMin <= 0.0 && window.xMax >= 0.0) canvas.drawLine(axisX, plot.top, axisX, plot.bottom, line)

        text.color = withAlpha(colors.text, 0.7f)
        text.textSize = 9.5f * unit
        text.typeface = Typeface.DEFAULT
        val gap = 3f * unit
        val labelY = (axisY + text.textSize + gap).coerceIn(plot.top + text.textSize + gap, plot.bottom - gap)
        ticks(window.xMin, window.xMax, xStep).forEach { x ->
            if (x.signum() == 0) return@forEach
            val label = x.toPlainString()
            val cx = frame.canvasX(x.toDouble())
            val half = text.measureText(label) / 2
            if (cx - half < plot.left + gap || cx + half > plot.right - gap) return@forEach
            canvas.drawText(label, cx - half, labelY, text)
        }
        ticks(window.yMin, window.yMax, yStep).forEach { y ->
            if (y.signum() == 0) return@forEach
            val label = y.toPlainString()
            val cy = frame.canvasY(y.toDouble())
            val width = text.measureText(label)
            if (cy - text.textSize < plot.top + gap || cy + gap > plot.bottom - gap) return@forEach
            val x = (axisX - width - gap).coerceIn(plot.left + gap, plot.right - width - gap)
            canvas.drawText(label, x, cy + text.textSize / 3, text)
        }

        // The axes' names, set as math: the variable italic at the right, the dependent at the top.
        text.color = colors.text
        text.textSize = 12f * unit
        text.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        val xName = frame.graph.variable
        val xNameY = (axisY - gap).coerceIn(plot.top + text.textSize, plot.bottom - gap)
        canvas.drawText(xName, plot.right - text.measureText(xName) - gap * 2, xNameY, text)
        val yName = frame.graph.dependent
        val yNameX = (axisX + gap * 2).coerceIn(plot.left + gap, plot.right - text.measureText(yName) - gap)
        canvas.drawText(yName, yNameX, plot.top + text.textSize + gap, text)
        text.typeface = Typeface.DEFAULT
    }

    private fun drawCurve(
        canvas: Canvas,
        frame: GraphFrame,
        function: (Double) -> Double,
        color: Int,
        widthDp: Float,
        dashed: Boolean,
    ) {
        val plot = frame.plot
        val unit = frame.unit
        val samples = (plot.width() / unit * 1.5f).toInt().coerceIn(200, 1500)
        val far = plot.height() * 4
        path.reset()
        var drawing = false
        var previous = 0f
        for (i in 0..samples) {
            val x = frame.window.xMin + frame.window.width * i / samples
            val y = function(x)
            if (!y.isFinite()) {
                drawing = false
                continue
            }
            val cx = frame.canvasX(x)
            val cy = frame.canvasY(y).coerceIn(plot.top - far, plot.bottom + far)
            // A jump of several plot heights between neighbouring samples is a pole, not a slope.
            if (drawing && abs(cy - previous) > far) drawing = false
            if (drawing) path.lineTo(cx, cy) else path.moveTo(cx, cy)
            drawing = true
            previous = cy
        }
        line.color = color
        line.strokeWidth = widthDp * unit
        line.pathEffect = if (dashed) DashPathEffect(floatArrayOf(6f * unit, 4f * unit), 0f) else null
        canvas.drawPath(path, line)
        line.pathEffect = null
    }

    private fun drawPoints(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        points: List<GraphPoint>,
        handles: Boolean,
        active: Pair<Double, Double>?,
    ) {
        val unit = frame.unit
        val window = frame.window
        val visible = points.filter { it.x in window.xMin..window.xMax && it.y in window.yMin..window.yMax }
        val taken = mutableListOf<RectF>()

        visible.forEach { point ->
            val cx = frame.canvasX(point.x)
            val cy = frame.canvasY(point.y)
            if (handles) {
                // A grab target says so: a halo the size of the finger that takes it.
                fill.color = withAlpha(colors.accent, 0.18f)
                canvas.drawCircle(cx, cy, 11f * unit, fill)
                line.color = withAlpha(colors.accent, 0.7f)
                line.strokeWidth = 1.2f * unit
                canvas.drawCircle(cx, cy, 11f * unit, line)
            }
            val radius = 4.5f * unit
            fill.color = colors.paper
            canvas.drawCircle(cx, cy, radius + 1.5f * unit, fill)
            if (point.kind == PointKind.Inflection) {
                line.color = colors.curve
                line.strokeWidth = 1.8f * unit
                canvas.drawCircle(cx, cy, radius - 0.6f * unit, line)
            } else {
                fill.color = colors.curve
                canvas.drawCircle(cx, cy, radius, fill)
            }
            taken += RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        }

        active?.let { (x, y) ->
            val cx = frame.canvasX(x)
            val cy = frame.canvasY(y)
            fill.color = withAlpha(colors.accent, 0.3f)
            canvas.drawCircle(cx, cy, 14f * unit, fill)
            fill.color = colors.accent
            canvas.drawCircle(cx, cy, 5f * unit, fill)
            drawLabel(canvas, frame, colors, x, y, taken, force = true)
        }

        if (!frame.graph.showLabels) return
        // Zeros first, so the points a student reads most get the free spots. Inflection points keep
        // their ring and go unlabelled: on a wave they double the labels and halve what can be read.
        // A place two kinds share — x³ − 2x's origin is a zero and an inflection — is labelled once.
        val labelled = mutableListOf<Pair<Float, Float>>()
        visible.filter { it.kind != PointKind.Inflection }.sortedByDescending { it.kind.ordinal }.forEach { point ->
            val cx = frame.canvasX(point.x)
            val cy = frame.canvasY(point.y)
            if (labelled.any { (x, y) -> hypot(x - cx, y - cy) < 2f * unit }) return@forEach
            labelled += cx to cy
            drawLabel(canvas, frame, colors, point.x, point.y, taken, force = false)
        }
    }

    /** `(x, y)` beside a point, on a chip of paper; skipped where it would cover another label. */
    private fun drawLabel(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        x: Double,
        y: Double,
        taken: MutableList<RectF>,
        force: Boolean,
    ) {
        val unit = frame.unit
        val label = "(${formatCoordinate(x)}, ${formatCoordinate(y)})"
        text.textSize = 10f * unit
        text.typeface = Typeface.DEFAULT
        val width = text.measureText(label)
        val height = text.textSize
        val pad = 3f * unit
        val cx = frame.canvasX(x)
        val cy = frame.canvasY(y)
        val candidates = listOf(
            RectF(cx + 7 * unit, cy - 7 * unit - height - pad * 2, cx + 7 * unit + width + pad * 2, cy - 7 * unit),
            RectF(cx - 7 * unit - width - pad * 2, cy - 7 * unit - height - pad * 2, cx - 7 * unit, cy - 7 * unit),
            RectF(cx + 7 * unit, cy + 7 * unit, cx + 7 * unit + width + pad * 2, cy + 7 * unit + height + pad * 2),
            RectF(cx - 7 * unit - width - pad * 2, cy + 7 * unit, cx - 7 * unit, cy + 7 * unit + height + pad * 2),
        )
        val chip = candidates.firstOrNull { rect ->
            frame.plot.contains(rect) && taken.none { RectF.intersects(it, rect) }
        } ?: if (force) candidates.first() else return
        taken += chip
        fill.color = withAlpha(colors.paper, 0.88f)
        canvas.drawRoundRect(chip, 4f * unit, 4f * unit, fill)
        text.color = colors.text
        canvas.drawText(label, chip.left + pad, chip.bottom - pad - height * 0.15f, text)
    }

    companion object {
        /** The default curve: Tailwind blue 500, legible on white paper and on the dark canvas. */
        const val DEFAULT_CURVE_ARGB: Int = 0xFF3B82F6.toInt()

        /** f′ in amber — the ink palette's orange, and far from the default blue. */
        const val DERIVATIVE_ARGB: Int = 0xFFF59E0B.toInt()

        /** Ticks every 60 dp or so. */
        private fun tickCount(lengthDp: Float): Int = (lengthDp / 60f).toInt().coerceIn(2, 12)

        /** The multiples of [step] inside [min]…[max], exactly. */
        fun ticks(min: Double, max: Double, step: BigDecimal): List<BigDecimal> {
            val size = step.toDouble()
            if (!(size > 0.0)) return emptyList()
            val first = ceil(min / size).toLong()
            val last = floor(max / size).toLong()
            if (last - first > 400) return emptyList()
            return (first..last).map { step.multiply(BigDecimal.valueOf(it)).stripTrailingZeros() }
        }

        /** Three decimals at most, no trailing zeros, never `-0`. */
        fun formatCoordinate(value: Double): String {
            if (!value.isFinite()) return "?"
            val rounded = BigDecimal(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
            val plain = if (rounded.signum() == 0) "0" else rounded.toPlainString()
            return plain.lowercase(Locale.ROOT)
        }

        fun withAlpha(argb: Int, alpha: Float): Int {
            val a = ((argb ushr 24) * alpha).toInt().coerceIn(0, 255)
            return (a shl 24) or (argb and 0x00FFFFFF)
        }
    }
}
