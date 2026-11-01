package com.vivenotes.graph

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.vivenotes.model.GraphBackground
import com.vivenotes.model.GraphInterval
import com.vivenotes.model.GraphParameter
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

    /**
     * The sliders' band at the foot of the box, one [SLIDER_ROW_DP] row per parameter; null when
     * there are none, or when the box is too small to keep a plot above them.
     */
    val sliders: RectF? = graph.parameters.size.takeIf { it > 0 }?.let { rows ->
        val band = SLIDER_ROW_DP * unit * rows
        val above = header?.bottom ?: box.top
        val fits = box.bottom - band - above >= MIN_PLOT_DP * unit && width >= MIN_SLIDER_WIDTH_DP * unit
        if (fits) RectF(box.left, box.bottom - band, box.right, box.bottom) else null
    }

    val plot = RectF(box.left, header?.bottom ?: box.top, box.right, sliders?.top ?: box.bottom)

    /** Row [index] of the band. */
    fun sliderRow(index: Int): RectF? = sliders?.let { band ->
        val top = band.top + index * SLIDER_ROW_DP * unit
        RectF(band.left, top, band.right, top + SLIDER_ROW_DP * unit)
    }

    /** Where every row's track starts and ends: the same for all, so their handles line up. */
    val trackLeft: Float get() = box.left + (SLIDER_PAD_DP + SLIDER_LABEL_DP + SLIDER_GAP_DP) * unit
    val trackRight: Float get() = box.right - (SLIDER_PLAY_DP + SLIDER_GAP_DP / 2) * unit

    /** The handle's x for [value] on [parameter]'s range. */
    fun sliderX(parameter: GraphParameter, value: Double): Float {
        if (!parameter.isUsable) return trackLeft
        val fraction = ((value - parameter.min) / (parameter.max - parameter.min)).coerceIn(0.0, 1.0)
        return (trackLeft + fraction * (trackRight - trackLeft)).toFloat()
    }

    /** The value under a canvas x on [parameter]'s track, unsnapped. */
    fun sliderValue(parameter: GraphParameter, canvasX: Float): Double {
        val fraction = ((canvasX - trackLeft) / (trackRight - trackLeft)).coerceIn(0f, 1f)
        return parameter.min + fraction * (parameter.max - parameter.min)
    }

    /** Which row, and which part of it, a canvas position falls on; null outside the band. */
    fun sliderAt(x: Float, y: Float): SliderHit? {
        val band = sliders ?: return null
        if (!band.contains(x, y)) return null
        val index = ((y - band.top) / (SLIDER_ROW_DP * unit)).toInt().coerceIn(0, graph.parameters.lastIndex)
        val part = when {
            x >= trackRight + SLIDER_GAP_DP / 2 * unit -> SliderPart.Play
            x >= trackLeft - SLIDER_GAP_DP * unit -> SliderPart.Track
            else -> SliderPart.Label
        }
        return SliderHit(index, part)
    }

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

        /** One slider: tall enough to take a finger, short enough that five leave a plot. */
        const val SLIDER_ROW_DP = 36f

        /** The plot the band always leaves above it; below this the sliders are not drawn. */
        const val MIN_PLOT_DP = 80f

        /** The name and value, `ω = −2.5`, ahead of the track. */
        const val SLIDER_LABEL_DP = 84f
        const val SLIDER_PAD_DP = 12f
        const val SLIDER_GAP_DP = 10f

        /** Play at the end of the row: its touch target. */
        const val SLIDER_PLAY_DP = 36f

        /** Label, a usable track and play: narrower than this the band is not drawn. */
        const val MIN_SLIDER_WIDTH_DP = 200f
    }
}

/** A position in the sliders' band: row [index], and whether it is the name, the track or play. */
data class SliderHit(val index: Int, val part: SliderPart)

enum class SliderPart { Label, Track, Play }

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
     * @param traceX where the tracer is — the stored one, or a drag's; null draws none.
     * @param traceHandle whether the tracer can be grabbed, which only a selected graph's can.
     * @param area the shaded interval — the stored one, or a drag's; null draws none.
     * @param areaHandles whether its bounds can be grabbed.
     * @param parameters the sliders as drawn now — the stored values, or a drag's or play's.
     * @param sliderControls whether play is offered, which only a selected graph's sliders are.
     * @param playing the slider play is sweeping, drawn with pause instead.
     * @param pressedSlider the slider under the finger, whose handle narrows as Material's does.
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
        traceX: Double? = frame.graph.traceX,
        traceHandle: Boolean = false,
        area: GraphInterval? = frame.graph.area,
        areaHandles: Boolean = false,
        parameters: List<GraphParameter> = frame.graph.parameters,
        sliderControls: Boolean = false,
        playing: Int? = null,
        pressedSlider: Int? = null,
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
        // Under every curve, so the shading never hides the line it is the area of.
        val integral = area?.let { GraphArea.integrate(function, it.from, it.to) }
        if (area != null && integral != null) drawArea(canvas, frame, colors, function, area)

        if (graph.showDerivative) {
            val h = frame.window.width * 1e-5
            drawCurve(canvas, frame, { x -> (function(x + h) - function(x - h)) / (2 * h) }, DERIVATIVE_ARGB, 1.8f, dashed = true)
        }
        if (original != null) {
            drawCurve(canvas, frame, original, withAlpha(colors.curve, 0.4f), 1.8f, dashed = true)
        }
        drawCurve(canvas, frame, function, colors.curve, 2.5f, dashed = false)
        val tangent = traceX
            ?.takeIf { it in frame.window.xMin..frame.window.xMax }
            ?.let { GraphTangent.at(function, it, frame.window) }
        tangent?.let { drawTangentLine(canvas, frame, it) }
        area?.let { drawBounds(canvas, frame, colors, it) }
        canvas.restore()

        // Every dot first, so no label is placed over one; then the tracer, above the dots it may
        // sit on and labelled before the points so they make room for it; then the points' labels.
        val taken = mutableListOf<RectF>()
        val visible = drawDots(canvas, frame, colors, points, handles, taken)
        area?.let { drawBoundTabs(canvas, frame, colors, it, areaHandles, taken) }
        tangent?.let { drawTracer(canvas, frame, colors, it, traceHandle, taken) }
        area?.let { drawAreaLabel(canvas, frame, colors, function, it, integral, taken) }
        drawLabels(canvas, frame, colors, visible, active, taken)
        frame.sliders?.let { drawSliders(canvas, frame, colors, it, parameters, sliderControls, playing, pressedSlider) }

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

    /**
     * One row per parameter: `ω = 2.5`, then a slider after Material 3 Expressive's — the active
     * track in the curve's colour up to a gap, a narrow upright handle, the inactive track beyond it
     * ending in a stop dot — then play, on a selected graph.
     *
     * In the curve's colour rather than the theme's, so on a page of several graphs each slider is
     * plainly its own curve's. The name and value keep the page's text colour, for contrast.
     */
    private fun drawSliders(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        band: RectF,
        parameters: List<GraphParameter>,
        controls: Boolean,
        playing: Int?,
        pressed: Int?,
    ) {
        val unit = frame.unit
        line.pathEffect = null
        line.strokeWidth = unit
        line.color = withAlpha(colors.text, 0.12f)
        canvas.drawLine(band.left, band.top, band.right, band.top, line)

        val labelLeft = band.left + GraphFrame.SLIDER_PAD_DP * unit
        val labelWidth = GraphFrame.SLIDER_LABEL_DP * unit
        val trackHeight = SLIDER_TRACK_DP * unit
        val gap = SLIDER_HANDLE_GAP_DP * unit
        val outer = trackHeight / 2
        val inner = 2f * unit
        parameters.forEachIndexed { index, parameter ->
            val row = frame.sliderRow(index) ?: return@forEachIndexed
            val cy = row.centerY()

            // The name in math italic, then the value upright, shrunk together to fit the column.
            val name = parameter.label
            val rest = GraphSliders.text(parameter).removePrefix(name)
            text.textSize = 12f * unit
            text.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            val nameWidth = text.measureText(name)
            text.typeface = Typeface.DEFAULT
            val restWidth = text.measureText(rest)
            val fit = (labelWidth / (nameWidth + restWidth)).coerceAtMost(1f)
            text.textSize = 12f * unit * fit
            text.color = colors.text
            val baseline = cy + text.textSize / 3
            text.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            canvas.drawText(name, labelLeft, baseline, text)
            text.typeface = Typeface.DEFAULT
            canvas.drawText(rest, labelLeft + nameWidth * fit, baseline, text)

            val left = frame.trackLeft
            val right = frame.trackRight
            val handleX = frame.sliderX(parameter, parameter.value)
            val top = cy - trackHeight / 2
            val bottom = cy + trackHeight / 2
            fill.color = colors.curve
            if (handleX - gap > left + inner) {
                path.reset()
                path.addRoundRect(
                    RectF(left, top, handleX - gap, bottom),
                    floatArrayOf(outer, outer, inner, inner, inner, inner, outer, outer),
                    Path.Direction.CW,
                )
                canvas.drawPath(path, fill)
            }
            if (handleX + gap < right - inner) {
                fill.color = withAlpha(colors.curve, 0.24f)
                path.reset()
                path.addRoundRect(
                    RectF(handleX + gap, top, right, bottom),
                    floatArrayOf(inner, inner, outer, outer, outer, outer, inner, inner),
                    Path.Direction.CW,
                )
                canvas.drawPath(path, fill)
                // The stop indicator: where the range ends, once the handle has left it.
                fill.color = colors.curve
                canvas.drawCircle(right - outer, cy, SLIDER_STOP_DP / 2 * unit, fill)
            }
            val handleWidth = (if (pressed == index) 2f else 4f) * unit
            val handleHeight = SLIDER_HANDLE_DP * unit
            fill.color = colors.curve
            canvas.drawRoundRect(
                handleX - handleWidth / 2,
                cy - handleHeight / 2,
                handleX + handleWidth / 2,
                cy + handleHeight / 2,
                handleWidth / 2,
                handleWidth / 2,
                fill,
            )

            if (controls) drawPlay(canvas, frame, colors, row, playing == index)
        }
        text.typeface = Typeface.DEFAULT
    }

    /** Play, or pause while this slider is sweeping, centred in its touch target at the row's end. */
    private fun drawPlay(canvas: Canvas, frame: GraphFrame, colors: GraphColors, row: RectF, playing: Boolean) {
        val unit = frame.unit
        val cx = row.right - GraphFrame.SLIDER_PLAY_DP / 2 * unit
        val cy = row.centerY()
        val size = 10f * unit
        if (playing) {
            fill.color = withAlpha(colors.curve, 0.16f)
            canvas.drawCircle(cx, cy, 14f * unit, fill)
            fill.color = colors.text
            val bar = size * 0.32f
            canvas.drawRoundRect(cx - size / 2, cy - size / 2, cx - size / 2 + bar, cy + size / 2, unit, unit, fill)
            canvas.drawRoundRect(cx + size / 2 - bar, cy - size / 2, cx + size / 2, cy + size / 2, unit, unit, fill)
        } else {
            fill.color = withAlpha(colors.text, 0.72f)
            path.reset()
            path.moveTo(cx - size * 0.4f, cy - size / 2)
            path.lineTo(cx + size * 0.55f, cy)
            path.lineTo(cx - size * 0.4f, cy + size / 2)
            path.close()
            canvas.drawPath(path, fill)
        }
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

    /** The points of interest's dots, each reserved in [taken]; returns the ones in view. */
    private fun drawDots(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        points: List<GraphPoint>,
        handles: Boolean,
        taken: MutableList<RectF>,
    ): List<GraphPoint> {
        val unit = frame.unit
        val window = frame.window
        val visible = points.filter { it.x in window.xMin..window.xMax && it.y in window.yMin..window.yMax }

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
        return visible
    }

    /** The dragged point, then a label for each point of interest that has room for one. */
    private fun drawLabels(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        visible: List<GraphPoint>,
        active: Pair<Double, Double>?,
        taken: MutableList<RectF>,
    ) {
        val unit = frame.unit
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
        val label = "(${formatCoordinate(x)}, ${formatCoordinate(y)})"
        drawChip(canvas, frame, colors, frame.canvasX(x), frame.canvasY(y), listOf(label), taken, force)
    }

    /**
     * Lines of text on a chip of paper beside a canvas point, in the first corner around it that is
     * inside the plot and clear of [taken]; skipped when none is, unless [force]d.
     *
     * [stripe] colours the chip's leading edge, tying it to a line drawn in that colour. The text
     * itself stays the page's text colour: pink type on white paper is below a readable contrast.
     */
    private fun drawChip(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        cx: Float,
        cy: Float,
        lines: List<String>,
        taken: MutableList<RectF>,
        force: Boolean,
        stripe: Int? = null,
        /** How far the chip stands off the point — clear of the dot drawn there, whatever its size. */
        gapDp: Float = 7f,
    ) {
        val unit = frame.unit
        text.textSize = 10f * unit
        text.typeface = Typeface.DEFAULT
        val inset = if (stripe != null) 3f * unit else 0f
        val width = lines.maxOf { text.measureText(it) } + inset
        val lineHeight = text.textSize * 1.25f
        val height = lineHeight * lines.size - (lineHeight - text.textSize)
        val pad = 3f * unit
        val gap = gapDp * unit
        val w = width + pad * 2
        val h = height + pad * 2
        val candidates = listOf(
            RectF(cx + gap, cy - gap - h, cx + gap + w, cy - gap),
            RectF(cx - gap - w, cy - gap - h, cx - gap, cy - gap),
            RectF(cx + gap, cy + gap, cx + gap + w, cy + gap + h),
            RectF(cx - gap - w, cy + gap, cx - gap, cy + gap + h),
        )
        val chip = candidates.firstOrNull { rect ->
            frame.plot.contains(rect) && taken.none { RectF.intersects(it, rect) }
        } ?: if (force) candidates.firstOrNull { frame.plot.contains(it) } ?: candidates.first() else return
        taken += chip
        fill.color = withAlpha(colors.paper, 0.88f)
        canvas.drawRoundRect(chip, 4f * unit, 4f * unit, fill)
        if (stripe != null) {
            fill.color = stripe
            canvas.drawRoundRect(chip.left, chip.top, chip.left + 2.5f * unit, chip.bottom, unit, unit, fill)
        }
        text.color = colors.text
        lines.forEachIndexed { index, line ->
            val baseline = chip.top + pad + text.textSize * 0.85f + index * lineHeight
            canvas.drawText(line, chip.left + pad + inset, baseline, text)
        }
    }

    /**
     * The region between the curve and the axis, one tint above the axis and another below: the
     * part an integral counts as negative looks it.
     */
    private fun drawArea(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        function: (Double) -> Double,
        area: GraphInterval,
    ) {
        val window = frame.window
        val lo = minOf(area.from, area.to).coerceIn(window.xMin, window.xMax)
        val hi = maxOf(area.from, area.to).coerceIn(window.xMin, window.xMax)
        if (hi <= lo) return
        val plot = frame.plot
        val far = plot.height() * 4
        val axis = frame.canvasY(0.0).coerceIn(plot.top - far, plot.bottom + far)
        val samples = ((frame.canvasX(hi) - frame.canvasX(lo)) / frame.unit).toInt().coerceIn(16, 1200)
        path.reset()
        path.moveTo(frame.canvasX(lo), axis)
        for (i in 0..samples) {
            val x = lo + (hi - lo) * i / samples
            val y = function(x)
            if (!y.isFinite()) continue
            path.lineTo(frame.canvasX(x), frame.canvasY(y).coerceIn(plot.top - far, plot.bottom + far))
        }
        path.lineTo(frame.canvasX(hi), axis)
        path.close()

        val split = axis.coerceIn(plot.top, plot.bottom)
        canvas.save()
        canvas.clipRect(plot.left, plot.top, plot.right, split)
        fill.color = withAlpha(colors.curve, 0.24f)
        canvas.drawPath(path, fill)
        canvas.restore()
        canvas.save()
        canvas.clipRect(plot.left, split, plot.right, plot.bottom)
        fill.color = withAlpha(AREA_BELOW_ARGB, 0.28f)
        canvas.drawPath(path, fill)
        canvas.restore()
    }

    /** Each bound, dashed from the top of the plot to the bottom. */
    private fun drawBounds(canvas: Canvas, frame: GraphFrame, colors: GraphColors, area: GraphInterval) {
        line.color = withAlpha(colors.text, 0.55f)
        line.strokeWidth = 1.2f * frame.unit
        line.pathEffect = DashPathEffect(floatArrayOf(4f * frame.unit, 3f * frame.unit), 0f)
        listOf(area.from, area.to).forEach { x ->
            if (x !in frame.window.xMin..frame.window.xMax) return@forEach
            val cx = frame.canvasX(x)
            canvas.drawLine(cx, frame.plot.top, cx, frame.plot.bottom, line)
        }
        line.pathEffect = null
    }

    /**
     * A tab at the foot of each bound with the bound's value: the number a student reads off, and,
     * on a selected graph, what a finger takes hold of.
     */
    private fun drawBoundTabs(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        area: GraphInterval,
        handles: Boolean,
        taken: MutableList<RectF>,
    ) {
        val unit = frame.unit
        text.textSize = 10f * unit
        text.typeface = Typeface.DEFAULT
        listOf(area.from, area.to).forEach { x ->
            if (x !in frame.window.xMin..frame.window.xMax) return@forEach
            val label = GraphTangent.number(x)
            val width = text.measureText(label) + 10f * unit
            val height = text.textSize + 8f * unit
            val cx = frame.canvasX(x)
            val tab = RectF(cx - width / 2, frame.plot.bottom - height - 3f * unit, cx + width / 2, frame.plot.bottom - 3f * unit)
            if (tab.left < frame.plot.left) tab.offset(frame.plot.left - tab.left, 0f)
            if (tab.right > frame.plot.right) tab.offset(frame.plot.right - tab.right, 0f)
            fill.color = if (handles) colors.curve else withAlpha(colors.paper, 0.92f)
            canvas.drawRoundRect(tab, height / 2, height / 2, fill)
            line.pathEffect = null
            line.color = colors.curve
            line.strokeWidth = 1.2f * unit
            canvas.drawRoundRect(tab, height / 2, height / 2, line)
            text.color = if (handles) colors.paper else colors.text
            canvas.drawText(label, tab.centerX() - text.measureText(label) / 2, tab.bottom - 4.5f * unit, text)
            taken += tab
        }
    }

    /**
     * The integral's value inside the shaded region — and, when the curve crosses the axis between
     * the bounds, the area counting both sides as well: displacement and distance on a velocity
     * graph. A dash where the integral has no value, across a pole.
     */
    private fun drawAreaLabel(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        function: (Double) -> Double,
        area: GraphInterval,
        integral: AreaValue?,
        taken: MutableList<RectF>,
    ) {
        val graph = frame.graph
        val window = frame.window
        val lo = maxOf(minOf(area.from, area.to), window.xMin)
        val hi = minOf(maxOf(area.from, area.to), window.xMax)
        if (hi < lo) return
        val lines = if (integral == null) {
            listOf("∫ ${graph.dependent} d${graph.variable} = —")
        } else {
            listOfNotNull(
                GraphArea.netText(integral, graph.variable, graph.dependent),
                GraphArea.totalText(integral, graph.variable, graph.dependent).takeIf { integral.crosses },
            )
        }
        val middle = (lo + hi) / 2
        val height = function(middle).takeIf { it.isFinite() }?.div(2) ?: 0.0
        val cx = frame.canvasX(middle)
        val cy = frame.canvasY(height).coerceIn(frame.plot.top, frame.plot.bottom)
        drawChip(canvas, frame, colors, cx, cy, lines, taken, force = true, stripe = colors.curve, gapDp = 2f)
    }

    /** The tangent across the whole plot, in its own colour so its equation can be matched to it. */
    private fun drawTangentLine(canvas: Canvas, frame: GraphFrame, tangent: Tangent) {
        if (tangent.slope == null) return
        val left = frame.window.xMin
        val right = frame.window.xMax
        line.pathEffect = null
        line.color = TANGENT_ARGB
        line.strokeWidth = 1.6f * frame.unit
        canvas.drawLine(
            frame.canvasX(left),
            frame.canvasY(tangent.lineAt(left)).coerceIn(-1e6f, 1e6f),
            frame.canvasX(right),
            frame.canvasY(tangent.lineAt(right)).coerceIn(-1e6f, 1e6f),
            line,
        )
    }

    /**
     * The tracer: a dot on the curve, and beside it the point, the slope there and the tangent's
     * equation — the slope named after the axes, so a velocity graph reads `dv/dt`.
     */
    private fun drawTracer(
        canvas: Canvas,
        frame: GraphFrame,
        colors: GraphColors,
        tangent: Tangent,
        handle: Boolean,
        taken: MutableList<RectF>,
    ) {
        val unit = frame.unit
        if (tangent.y !in frame.window.yMin..frame.window.yMax) return
        val cx = frame.canvasX(tangent.x)
        val cy = frame.canvasY(tangent.y)
        if (handle) {
            fill.color = withAlpha(TANGENT_ARGB, 0.2f)
            canvas.drawCircle(cx, cy, 13f * unit, fill)
            line.pathEffect = null
            line.color = withAlpha(TANGENT_ARGB, 0.8f)
            line.strokeWidth = 1.2f * unit
            canvas.drawCircle(cx, cy, 13f * unit, line)
        }
        fill.color = colors.paper
        canvas.drawCircle(cx, cy, TRACER_RADIUS_DP * unit, fill)
        fill.color = TANGENT_ARGB
        canvas.drawCircle(cx, cy, (TRACER_RADIUS_DP - 1.5f) * unit, fill)
        val reach = TRACER_RADIUS_DP * unit
        taken += RectF(cx - reach, cy - reach, cx + reach, cy + reach)

        val graph = frame.graph
        val lines = buildList {
            add("(${formatCoordinate(tangent.x)}, ${formatCoordinate(tangent.y)})")
            tangent.slope?.let { add(GraphTangent.slopeText(it, graph.variable, graph.dependent)) }
            GraphTangent.lineText(tangent, graph.variable, graph.dependent)?.let(::add)
        }
        drawChip(canvas, frame, colors, cx, cy, lines, taken, force = true, stripe = TANGENT_ARGB, gapDp = TRACER_RADIUS_DP + 2f)
    }

    companion object {
        /** The default curve: Tailwind blue 500, legible on white paper and on the dark canvas. */
        const val DEFAULT_CURVE_ARGB: Int = 0xFF3B82F6.toInt()

        /** f′ in amber — the ink palette's orange, and far from the default blue. */
        const val DERIVATIVE_ARGB: Int = 0xFFF59E0B.toInt()

        /** The tangent and its tracer in pink — the ink palette's, clear of the blue and the amber. */
        const val TANGENT_ARGB: Int = 0xFFEC4899.toInt()

        /** The part of an area below the axis, counted negative: Tailwind violet 500. */
        const val AREA_BELOW_ARGB: Int = 0xFF8B5CF6.toInt()

        /** The tracer's dot with its ring of paper: larger than a point's, being the one you hold. */
        private const val TRACER_RADIUS_DP = 7.5f

        /** Material's Expressive slider at the page's scale: its proportions, half its size. */
        private const val SLIDER_TRACK_DP = 8f
        private const val SLIDER_HANDLE_DP = 22f
        private const val SLIDER_HANDLE_GAP_DP = 3f
        private const val SLIDER_STOP_DP = 3f

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
