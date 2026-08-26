package com.vivenotes.ui.editor

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import com.vivenotes.data.RulerKind
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.Ruler
import com.vivenotes.model.PageStyle
import kotlin.math.hypot

/** Line weights in view pixels, so the ruler is drawn the same thickness at any zoom. */
private const val EDGE_PX = 2.4f
private const val TICK_PX = 1.6f
private const val FINE_TICK_PX = 1.1f

/**
 * Numeral height in page dp — part of the object, so it grows with it, like a printed scale.
 *
 * An eighth of the band. At a quarter, which is where this started, the numbers dominate the ruler
 * and crowd the graduations they are supposed to label.
 */
private const val NUMERAL_DP = 13f

/** Millimetres to the centimetre — the division count measured off the reference plate. */
private const val DIVISIONS = 10

/** A centimetre on a page laid out at 160dp to the inch. */
private const val DP_PER_CM = PageStyle.DP_PER_INCH / 2.54f

/**
 * Moves and turns the ruler — `memory/rulerPlan.md` RD4 and RD6.
 *
 * **One finger on it slides it; two twist it.** That is how every drawing app does this and it is
 * what the object affords: you hold a ruler, you do not operate a handle bolted to its end.
 *
 * The reason this can coexist with pinch-to-zoom is ordering, not cleverness. This detector is
 * mounted *before* [detectPinchZoom] on the same ancestor node, so on the `Initial` pass it is asked
 * first; if the gesture began on the ruler it consumes, and the pinch stands down. A gesture that
 * began anywhere else is never touched, and the page zooms exactly as it did.
 *
 * **However many fingers are on it, they are one hand** — RD4b. Everything below is measured
 * against the *centroid* of whatever is currently down, and the centroid is re-seeded rather than
 * carried whenever the set of pointers changes. Both halves matter: no single finger can be
 * nominated as the one that carries the ruler, because `changes` is ordered by pointer index and
 * the moment the hand adds or lifts one the "first" pressed pointer becomes a different finger — a
 * delta taken from it is then the span of the hand rather than the distance it moved, and the ruler
 * jumps an inch sideways. Repeated every time a finger settles, drifts off, or lands a frame late,
 * that is the flicker that made a second finger unusable while one worked.
 *
 * [rulerAt] is read per gesture rather than captured, because the ruler moves as the gesture runs.
 */
internal suspend fun PointerInputScope.detectRulerDrag(
    rulerAt: () -> Ruler?,
    /** View pixels to page units, so a hit test can be done where the ruler actually lives. */
    toPage: () -> Matrix,
    onMove: (dx: Float, dy: Float) -> Unit,
    onTurn: (radians: Float) -> Unit,
    /** A tap on the degree dial: step round to the next eighth of a turn. */
    onTapDial: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // **Touch only.** You hold a ruler with your hand and draw along it with the pen, and the
        // code has to say so: the body is inside the snap zone, so a stylus claimed here would move
        // the ruler instead of drawing the line it was put down to draw — which is the entire
        // feature. A pen on the ruler falls straight through to the ink overlay and comes out ruled.
        if (down.type != PointerType.Touch) return@awaitEachGesture
        val ruler = rulerAt() ?: return@awaitEachGesture
        val start = toPage().pagePointOf(down.position)
        if (!ruler.grabs(start)) return@awaitEachGesture

        down.consume()
        // A tap on the dial is a rotate, but only if it stays a tap — the dial sits in the middle of
        // the body, so a drag that begins there still has to slide the ruler.
        val onDial = ruler.grabsDial(start)
        var travelled = false
        // The hand, reduced to a point, and who it is made of. Both in view pixels: the anchor is
        // subtracted from the next centroid before anything reaches the page, so the conversion
        // happens once, on a delta.
        val origin = down.position
        var anchor = origin
        var hand = setOf(down.id)

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            val centroid = pressed.fold(Offset.Zero) { total, change -> total + change.position } /
                pressed.size.toFloat()
            val ids = pressed.mapTo(mutableSetOf()) { it.id }

            if (ids != hand) {
                // A finger arrived or left. The centroid moves by however far apart the hand is
                // spread and none of that is the hand moving, so this frame re-seeds and reports
                // nothing. One dropped frame of travel is invisible; the jump it replaces was not.
                hand = ids
                anchor = centroid
                // A pair that lands and twists by a degree is still a turn and never a tap, however
                // still it was held — so the dial is ruled out the moment a second finger arrives.
                if (ids.size >= 2) travelled = true
            } else {
                // Any two of them twisting turns it. `calculateRotation` averages over every pointer
                // that was down for both samples, so a third finger steadies the reading rather than
                // confusing it — which is the other half of being tolerant of a whole hand.
                if (ids.size >= 2) onTurn(event.calculateRotation() * DEGREES_TO_RADIANS)
                if (centroid != anchor) {
                    val page = toPage()
                    val from = page.pagePointOf(anchor)
                    val to = page.pagePointOf(centroid)
                    onMove(to.x - from.x, to.y - from.y)
                    anchor = centroid
                }
            }

            if (!travelled && (centroid - origin).getDistance() > viewConfiguration.touchSlop) {
                travelled = true
            }
            event.changes.forEach { if (it.pressed) it.consume() }
        }

        if (onDial && !travelled) onTapDial()
    }
}

private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

private fun Matrix.pagePointOf(offset: Offset): InkPoint {
    val mapped = floatArrayOf(offset.x, offset.y)
    mapPoints(mapped)
    return InkPoint(mapped[0], mapped[1])
}

/**
 * How the ruler is painted.
 *
 * Taken from the *canvas* rather than from the app's accent, which was the first version's mistake:
 * a shape filled with the theme's primary reads as a selection, not as an object lying on the paper.
 * A ruler is a piece of frosted plastic — near-colourless, letting the page through, with one crisp
 * edge that says exactly where the line will land.
 */
internal data class RulerPaint(val body: Int, val edge: Int, val mark: Int)

/**
 * Draws the ruler over the page.
 *
 * Through the page → view matrix, like the lasso and the eraser cursor, so it sits where it was put
 * on the page. Line weights are divided back out by the matrix's scale so the edge stays a hair
 * thick at 400% instead of becoming a bar.
 *
 * The graduations are a real inch apart, which is true rather than decorative: the page is laid out
 * at [PageStyle.DP_PER_INCH] and the ruler is placed in those units (RD3). They run *in* from each
 * long edge with the numerals down the middle, which is the arrangement a ruler actually has — the
 * first version marched them in from both sides until they nearly met, and it read as a table.
 */
internal fun drawRuler(
    canvas: android.graphics.Canvas,
    pageToView: Matrix,
    ruler: Ruler,
    paint: RulerPaint,
) {
    val values = FloatArray(9)
    pageToView.getValues(values)
    val scale = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]).coerceAtLeast(0.001f)

    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paint.body
        style = Paint.Style.FILL
    }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paint.edge
        style = Paint.Style.STROKE
        strokeWidth = EDGE_PX / scale
    }
    val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paint.mark
        style = Paint.Style.STROKE
        strokeWidth = TICK_PX / scale
    }
    val fine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paint.mark
        style = Paint.Style.STROKE
        strokeWidth = FINE_TICK_PX / scale
    }
    val numeral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paint.mark
        style = Paint.Style.FILL
        textSize = NUMERAL_DP
        textAlign = Paint.Align.CENTER
    }

    val checkpoint = canvas.save()
    canvas.concat(pageToView)
    // Into the ruler's own frame, so everything below is drawn as though it were lying flat — and so
    // the numerals turn with it, the way printed ones do.
    canvas.rotate(Math.toDegrees(ruler.angleRadians.toDouble()).toFloat(), ruler.centerX, ruler.centerY)
    canvas.translate(ruler.centerX, ruler.centerY)

    when (ruler.kind) {
        RulerKind.Straight -> drawStraight(canvas, ruler, body, edge, tick, fine, numeral)
        RulerKind.Protractor -> drawProtractor(canvas, ruler, body, edge, tick, fine, numeral)
    }

    // One call site for both kinds — only where it sits differs, and the ruler decides that.
    val dial = ruler.dialCenter()
    val dialCheckpoint = canvas.save()
    canvas.translate(dial.x, dial.y)
    drawDial(canvas, ruler, body, edge, tick, numeral)
    canvas.restoreToCount(dialCheckpoint)

    canvas.restoreToCount(checkpoint)
}

/**
 * The straightedge, drawn to `memory/references/ruler.png`.
 *
 * Measured off that image rather than invented: a **centimetre** scale with millimetre graduations
 * (10 fine ticks to the unit, at 140.5px to a unit in a 2914px-wide plate — 62.7dp, and a centimetre
 * is 62.99dp on a page laid out at 160dp to the inch). The tick hierarchy is deliberately shallow —
 * 12.6%, 15.7% and 17.5% of the band — because that density with that little contrast is what makes
 * a scale read as a scale instead of as a row of dividers.
 *
 * Numbers count **outward from 0 at the middle**, on both edges, and the underside's are upside down:
 * the ruler is a physical thing and its far edge faces the other way.
 */
private fun drawStraight(
    canvas: android.graphics.Canvas,
    ruler: Ruler,
    body: Paint,
    edge: Paint,
    tick: Paint,
    fine: Paint,
    numeral: Paint,
) {
    val half = Ruler.BAND_DP / 2f
    canvas.drawRect(-ruler.reach, -half, ruler.reach, half, body)
    canvas.drawLine(-ruler.reach, -half, ruler.reach, -half, edge)
    canvas.drawLine(-ruler.reach, half, ruler.reach, half, edge)

    val fineLength = Ruler.BAND_DP * 0.126f
    val mediumLength = Ruler.BAND_DP * 0.157f
    val majorLength = Ruler.BAND_DP * 0.175f
    val step = DP_PER_CM / DIVISIONS

    // Out from the centre in both directions, because that is where the scale is zeroed.
    var index = 0
    while (index * step <= ruler.reach) {
        val offset = index * step
        val onUnit = index % DIVISIONS == 0
        val onHalf = index % DIVISIONS == DIVISIONS / 2
        val length = if (onUnit) majorLength else if (onHalf) mediumLength else fineLength
        val paint = if (onUnit) tick else fine

        listOf(offset, -offset).distinct().forEach { x ->
            canvas.drawLine(x, -half, x, -half + length, paint)
            canvas.drawLine(x, half, x, half - length, paint)
            if (onUnit) {
                val label = (index / DIVISIONS).toString()
                canvas.drawText(label, x + NUMERAL_DP * 0.62f, -half + length + NUMERAL_DP, numeral)
                // The far edge reads from the other side, so its numerals are turned over. Rotating
                // about the point they are drawn at keeps them beside their own tick.
                val checkpoint = canvas.save()
                canvas.rotate(180f, x, half - length - NUMERAL_DP)
                canvas.drawText(label, x + NUMERAL_DP * 0.62f, half - length - NUMERAL_DP, numeral)
                canvas.restoreToCount(checkpoint)
            }
        }
        index++
    }
}

/**
 * The degree readout — the circle in the reference plate.
 *
 * The ring of ticks turns with the ruler, which is what makes it a gauge rather than a badge; the
 * number inside is counter-rotated so it stays the right way up at any angle. Tapping it steps the
 * ruler round an eighth of a turn — see [Ruler.turnedToNextEighth].
 *
 * Both rulers carry one. On the semicircle it says something different from the numbers around the
 * arc, and the difference is worth holding on to: **the arc measures what you draw, the dial
 * measures the protractor itself.** Drawn at the origin — the caller translates to
 * [Ruler.dialCenter].
 */
private fun drawDial(
    canvas: android.graphics.Canvas,
    ruler: Ruler,
    body: Paint,
    edge: Paint,
    tick: Paint,
    numeral: Paint,
) {
    val r = Ruler.DIAL_RADIUS_DP
    canvas.drawCircle(0f, 0f, r, body)

    var degrees = 0
    while (degrees < 360) {
        val radians = Math.toRadians(degrees.toDouble())
        val dx = kotlin.math.cos(radians).toFloat()
        val dy = kotlin.math.sin(radians).toFloat()
        canvas.drawLine(dx * r, dy * r, dx * r * 0.78f, dy * r * 0.78f, tick)
        degrees += 15
    }

    val readout = Paint(numeral).apply { textSize = NUMERAL_DP * 0.92f }
    val checkpoint = canvas.save()
    canvas.rotate(-Math.toDegrees(ruler.angleRadians.toDouble()).toFloat())
    canvas.drawText("${ruler.degrees()}\u00B0", 0f, readout.textSize * 0.36f, readout)
    canvas.restoreToCount(checkpoint)
}

private fun drawProtractor(
    canvas: android.graphics.Canvas,
    ruler: Ruler,
    body: Paint,
    edge: Paint,
    tick: Paint,
    fine: Paint,
    numeral: Paint,
) {
    val box = RectF(-ruler.reach, -ruler.reach, ruler.reach, ruler.reach)
    canvas.drawArc(box, 180f, 180f, true, body)
    canvas.drawArc(box, 180f, 180f, true, edge)

    var degrees = 0
    while (degrees <= 180) {
        val radians = Math.toRadians(degrees.toDouble())
        val dx = -kotlin.math.cos(radians).toFloat()
        val dy = -kotlin.math.sin(radians).toFloat()
        val major = degrees % 30 == 0
        val medium = degrees % 10 == 0
        val inner = if (major) 0.86f else if (medium) 0.90f else 0.94f
        canvas.drawLine(
            dx * ruler.reach,
            dy * ruler.reach,
            dx * ruler.reach * inner,
            dy * ruler.reach * inner,
            if (major) tick else fine,
        )
        // Every thirty degrees, which is as many numbers as fit without crowding the arc.
        if (major && degrees != 0 && degrees != 180) {
            canvas.drawText(
                degrees.toString(),
                dx * ruler.reach * 0.78f,
                dy * ruler.reach * 0.78f + NUMERAL_DP * 0.36f,
                numeral,
            )
        }
        degrees += 5
    }

    // The centre mark, which is what a protractor is lined up by.
    val cross = ruler.reach * 0.05f
    canvas.drawLine(-cross, 0f, cross, 0f, tick)
    canvas.drawLine(0f, -cross, 0f, cross, tick)
}
