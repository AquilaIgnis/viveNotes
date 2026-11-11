package com.vivenotes.fileimport.pptx

import com.vivenotes.fileimport.docx.NS_A
import com.vivenotes.fileimport.docx.WordClosePath
import com.vivenotes.fileimport.docx.WordCubicTo
import com.vivenotes.fileimport.docx.WordLineTo
import com.vivenotes.fileimport.docx.WordMoveTo
import com.vivenotes.fileimport.docx.WordPath
import com.vivenotes.fileimport.docx.WordPathCommand
import com.vivenotes.fileimport.docx.child
import com.vivenotes.fileimport.docx.children
import com.vivenotes.fileimport.docx.childElements
import com.vivenotes.fileimport.docx.plain
import org.w3c.dom.Element
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Builds an outline in a space [w] by [h]. */
private class Outline(val w: Float, val h: Float, private val filled: Boolean = true) {
    private val commands = ArrayList<WordPathCommand>()

    fun move(x: Float, y: Float) = apply { commands += WordMoveTo(x, y) }

    fun line(x: Float, y: Float) = apply { commands += WordLineTo(x, y) }

    fun curve(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = apply { commands += WordCubicTo(x1, y1, x2, y2, x, y) }

    fun close() = apply { commands += WordClosePath }

    fun polygon(vararg points: Float): Outline {
        move(points[0], points[1])
        for (index in 2 until points.size step 2) line(points[index], points[index + 1])
        return close()
    }

    /** A rectangle with quarter-circle corners of radius [r]. */
    fun rounded(r: Float): Outline {
        val k = r * CIRCLE_KAPPA
        move(r, 0f)
        line(w - r, 0f)
        curve(w - r + k, 0f, w, r - k, w, r)
        line(w, h - r)
        curve(w, h - r + k, w - r + k, h, w - r, h)
        line(r, h)
        curve(r - k, h, 0f, h - r + k, 0f, h - r)
        line(0f, r)
        curve(0f, r - k, r - k, 0f, r, 0f)
        return close()
    }

    fun build(): WordPath = WordPath(w, h, commands, filled = filled)
}

private const val CIRCLE_KAPPA = 0.5523f
private const val FULL = 100_000f

/**
 * The outline of one of DrawingML's preset shapes, or null for a plain rectangle or an ellipse, which
 * need no outline of their own, and for any shape not worth drawing exactly: those fall back to a
 * rectangle. [adjust] holds the file's adjustment values by name.
 */
internal fun presetOutline(name: String, w: Float, h: Float, adjust: Map<String, Float>): WordPath? {
    val short = min(w, h)
    fun value(key: String, fallback: Float): Float = adjust[key] ?: fallback
    return when (name) {
        "roundRect", "flowChartAlternateProcess", "wedgeRoundRectCallout" ->
            Outline(w, h).rounded(short * value("adj", 16667f) / FULL).build()
        "flowChartTerminator" -> Outline(w, h).rounded(short / 2f).build()
        "diamond", "flowChartDecision" -> Outline(w, h).polygon(w / 2f, 0f, w, h / 2f, w / 2f, h, 0f, h / 2f).build()
        "triangle", "flowChartExtract" -> Outline(w, h).polygon(w * value("adj", 50000f) / FULL, 0f, w, h, 0f, h).build()
        "flowChartMerge" -> Outline(w, h).polygon(0f, 0f, w, 0f, w / 2f, h).build()
        "rtTriangle" -> Outline(w, h).polygon(0f, 0f, w, h, 0f, h).build()
        "parallelogram", "flowChartInputOutput" -> {
            val cut = short * value("adj", 25000f) / FULL
            Outline(w, h).polygon(cut, 0f, w, 0f, w - cut, h, 0f, h).build()
        }
        "trapezoid", "flowChartManualOperation" -> {
            val cut = short * value("adj", 25000f) / FULL
            Outline(w, h).polygon(cut, 0f, w - cut, 0f, w, h, 0f, h).build()
        }
        "pentagon" -> Outline(w, h).polygon(w / 2f, 0f, w, h * 0.382f, w * 0.81f, h, w * 0.19f, h, 0f, h * 0.382f).build()
        "hexagon", "flowChartPreparation" -> {
            val cut = short * value("adj", 25000f) / FULL
            Outline(w, h).polygon(cut, 0f, w - cut, 0f, w, h / 2f, w - cut, h, cut, h, 0f, h / 2f).build()
        }
        "octagon" -> {
            val cut = short * value("adj", 29289f) / FULL
            Outline(w, h).polygon(cut, 0f, w - cut, 0f, w, cut, w, h - cut, w - cut, h, cut, h, 0f, h - cut, 0f, cut).build()
        }
        "plus", "mathPlus" -> {
            val cut = short * value("adj", 25000f) / FULL
            Outline(w, h).polygon(cut, 0f, w - cut, 0f, w - cut, cut, w, cut, w, h - cut, w - cut, h - cut, w - cut, h, cut, h, cut, h - cut, 0f, h - cut, 0f, cut, cut, cut).build()
        }
        "star5" -> star(w, h, 5, 0.382f)
        "star4" -> star(w, h, 4, 0.25f)
        "star6" -> star(w, h, 6, 0.5f)
        "rightArrow" -> arrow(w, h, value("adj1", 50000f), value("adj2", 50000f), turns = 0)
        "downArrow" -> arrow(w, h, value("adj1", 50000f), value("adj2", 50000f), turns = 1)
        "leftArrow" -> arrow(w, h, value("adj1", 50000f), value("adj2", 50000f), turns = 2)
        "upArrow" -> arrow(w, h, value("adj1", 50000f), value("adj2", 50000f), turns = 3)
        "leftRightArrow" -> {
            val head = short * value("adj2", 50000f) / FULL
            val inset = h * (1f - value("adj1", 50000f) / FULL) / 2f
            Outline(w, h).polygon(0f, h / 2f, head, 0f, head, inset, w - head, inset, w - head, 0f, w, h / 2f, w - head, h, w - head, h - inset, head, h - inset, head, h).build()
        }
        "homePlate" -> {
            val head = short * value("adj", 50000f) / FULL
            Outline(w, h).polygon(0f, 0f, w - head, 0f, w, h / 2f, w - head, h, 0f, h).build()
        }
        "chevron" -> {
            val head = short * value("adj", 50000f) / FULL
            Outline(w, h).polygon(0f, 0f, w - head, 0f, w, h / 2f, w - head, h, 0f, h, head, h / 2f).build()
        }
        "line", "straightConnector1" -> Outline(w, h, filled = false).move(0f, 0f).line(w, h).build()
        "lineInv" -> Outline(w, h, filled = false).move(0f, h).line(w, 0f).build()
        "bentConnector2" -> Outline(w, h, filled = false).move(0f, 0f).line(w, 0f).line(w, h).build()
        "bentConnector3", "bentConnector4", "bentConnector5" -> {
            val middle = w * value("adj1", 50000f) / FULL
            Outline(w, h, filled = false).move(0f, 0f).line(middle, 0f).line(middle, h).line(w, h).build()
        }
        "curvedConnector2" -> Outline(w, h, filled = false).move(0f, 0f).curve(w / 2f, 0f, w, h / 2f, w, h).build()
        "curvedConnector3", "curvedConnector4", "curvedConnector5" ->
            Outline(w, h, filled = false).move(0f, 0f).curve(w / 2f, 0f, w / 2f, h, w, h).build()
        else -> null
    }
}

private fun star(w: Float, h: Float, points: Int, inner: Float): WordPath {
    val outline = Outline(w, h)
    val steps = points * 2
    for (index in 0 until steps) {
        val radius = if (index % 2 == 0) 0.5f else 0.5f * inner
        val angle = -PI / 2.0 + index * PI / points
        val x = w * (0.5f + radius * cos(angle).toFloat())
        val y = h * (0.5f + radius * sin(angle).toFloat())
        if (index == 0) outline.move(x, y) else outline.line(x, y)
    }
    return outline.close().build()
}

/** A block arrow pointing right, then turned clockwise by [turns] quarter turns. */
private fun arrow(w: Float, h: Float, shaftAdjust: Float, headAdjust: Float, turns: Int): WordPath {
    val horizontal = turns % 2 == 0
    val along = if (horizontal) w else h
    val across = if (horizontal) h else w
    val head = min(w, h) * headAdjust / FULL
    val inset = across * (1f - shaftAdjust / FULL) / 2f
    val points = listOf(
        0f to inset, along - head to inset, along - head to 0f, along to across / 2f,
        along - head to across, along - head to across - inset, 0f to across - inset,
    )
    val outline = Outline(w, h)
    points.forEachIndexed { index, (x, y) ->
        val (px, py) = when (turns) {
            1 -> across - y to x
            2 -> along - x to across - y
            3 -> y to along - x
            else -> x to y
        }
        if (index == 0) outline.move(px, py) else outline.line(px, py)
    }
    return outline.close().build()
}

/** The paths of a freeform shape (`custGeom`), with arcs turned into curves. */
internal fun customOutline(geometry: Element, boxWidth: Float, boxHeight: Float): List<WordPath> =
    geometry.child(NS_A, "pathLst")?.children(NS_A, "path").orEmpty().mapNotNull { path ->
        val width = path.plain("w")?.toFloatOrNull() ?: boxWidth
        val height = path.plain("h")?.toFloatOrNull() ?: boxHeight
        val commands = ArrayList<WordPathCommand>()
        var x = 0f
        var y = 0f
        fun point(element: Element?): Pair<Float, Float>? {
            val px = element?.plain("x")?.toFloatOrNull() ?: return null
            val py = element.plain("y")?.toFloatOrNull() ?: return null
            return px to py
        }
        for (command in path.childElements) {
            val points = command.children(NS_A, "pt").map(::point)
            when (command.localName) {
                "moveTo" -> points.firstOrNull()?.let { (px, py) ->
                    commands += WordMoveTo(px, py)
                    x = px
                    y = py
                }
                "lnTo" -> points.firstOrNull()?.let { (px, py) ->
                    commands += WordLineTo(px, py)
                    x = px
                    y = py
                }
                "cubicBezTo" -> if (points.size == 3 && points.all { it != null }) {
                    val (p1, p2, p3) = points.map { it!! }
                    commands += WordCubicTo(p1.first, p1.second, p2.first, p2.second, p3.first, p3.second)
                    x = p3.first
                    y = p3.second
                }
                "quadBezTo" -> if (points.size == 2 && points.all { it != null }) {
                    val (control, end) = points.map { it!! }
                    commands += WordCubicTo(
                        x + (control.first - x) * 2f / 3f, y + (control.second - y) * 2f / 3f,
                        end.first + (control.first - end.first) * 2f / 3f, end.second + (control.second - end.second) * 2f / 3f,
                        end.first, end.second,
                    )
                    x = end.first
                    y = end.second
                }
                "arcTo" -> {
                    val end = arcCommands(
                        x, y,
                        command.plain("wR")?.toFloatOrNull() ?: 0f,
                        command.plain("hR")?.toFloatOrNull() ?: 0f,
                        (command.plain("stAng")?.toFloatOrNull() ?: 0f) / ANGLE_UNITS,
                        (command.plain("swAng")?.toFloatOrNull() ?: 0f) / ANGLE_UNITS,
                        commands,
                    )
                    x = end.first
                    y = end.second
                }
                "close" -> commands += WordClosePath
            }
        }
        if (commands.isEmpty()) {
            null
        } else {
            WordPath(width, height, commands, filled = path.plain("fill") != "none", stroked = path.plain("stroke") != "0")
        }
    }

private const val ANGLE_UNITS = 60_000f
private const val FULL_TURN = 360f

/** An arc from ([x], [y]) in up-to-quarter-turn curves; DrawingML gives angles in degrees here. */
internal fun arcCommands(
    x: Float,
    y: Float,
    radiusX: Float,
    radiusY: Float,
    startDegrees: Float,
    sweepDegrees: Float,
    out: MutableList<WordPathCommand>,
): Pair<Float, Float> {
    val start = Math.toRadians(startDegrees.toDouble())
    val centerX = x - radiusX * cos(start).toFloat()
    val centerY = y - radiusY * sin(start).toFloat()
    // DrawingML allows a sweep of one turn either way. A file's 1e30 would otherwise be a curve per
    // quarter turn of it, and its NaN no number of curves at all.
    val sweep = if (sweepDegrees.isNaN()) 0f else sweepDegrees.coerceIn(-FULL_TURN, FULL_TURN)
    val pieces = ceil(abs(sweep) / 90f).toInt().coerceAtLeast(1)
    val step = Math.toRadians((sweep / pieces).toDouble())
    val handle = (4.0 / 3.0 * tan(step / 4.0)).toFloat()
    var angle = start
    var endX = x
    var endY = y
    repeat(pieces) {
        val next = angle + step
        val x0 = centerX + radiusX * cos(angle).toFloat()
        val y0 = centerY + radiusY * sin(angle).toFloat()
        endX = centerX + radiusX * cos(next).toFloat()
        endY = centerY + radiusY * sin(next).toFloat()
        out += WordCubicTo(
            x0 - radiusX * handle * sin(angle).toFloat(), y0 + radiusY * handle * cos(angle).toFloat(),
            endX + radiusX * handle * sin(next).toFloat(), endY - radiusY * handle * cos(next).toFloat(),
            endX, endY,
        )
        angle = next
    }
    return endX to endY
}
