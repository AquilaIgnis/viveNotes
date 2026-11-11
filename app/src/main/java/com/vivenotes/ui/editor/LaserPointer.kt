package com.vivenotes.ui.editor

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkPoint
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The laser pointer: a lit dot under the pen, and the trace it draws, which stays for a second and
 * then goes.
 *
 * It leaves nothing on the page. Nothing here reaches a stroke row, a history step or the database:
 * it is for pointing at what is already written, while presenting or explaining, and once it has
 * been seen it is gone. That is also why it has no settings.
 *
 * **Every point of the trace lives [HOLD_MILLIS], then thins away over [VANISH_MILLIS].** By the
 * point, not by the gesture: lifting the pen does not take the trace with it, and neither does the
 * next press — circling something twice leaves both circles up until each has had its second. So
 * there can be several traces at once, each one a gesture, and only the newest is still being drawn.
 *
 * **A trace is Ink's, smoothed the way a pen is.** Each one is an [InProgressStroke] whose brush
 * wears stabilization level 1 — `InkCodec.inputModelFor(1)`, the same sliding window a pen at level
 * 1 draws through — so it is modelled by the library's own stroke modeller rather than by a filter
 * written here. The vanishing is part of the brush too: a behaviour shrinks each point to nothing as
 * it ages, so the trace is a function of time that Ink re-evaluates every frame.
 *
 * **It looks like a lightsaber blade.** The films' blades were composited by hand: a white core,
 * then copies of the same shape blurred at growing radii and tinted, each wider and fainter than
 * the last. The trace is built the same way. Its brush has two coats, a red edge with a white-hot
 * core on top. Under that sit two blurred copies of the stroke: a tight bright-red glow and a wide
 * deep-crimson one. Both glows waver slightly, the way a blade hums. See [drawTraces].
 *
 * **The dot is drawn here, at the raw pen position.** That is where the trace's head is: Ink's
 * sliding window is centred and clamps to the inputs it has, so the newest input is modelled where
 * it was reported, exactly as a pen's wet tip is. A soft glow is also beyond what a brush tip can
 * draw. The dot goes out over [FADE_MILLIS] once the pen lifts, leaving the trace behind it.
 *
 * **Held in page units, drawn in view pixels.** The marks are where the pen pointed *on the page*,
 * so a scroll while a trace is up carries it along with the writing it was pointing at. Its width is
 * window chrome, though: each trace's brush is sized for the zoom its press landed at, and the dot
 * is the same size at every zoom.
 *
 * With animations removed — Remove animations in the accessibility settings, or an animator scale
 * of zero — there is no trace, no fade and no waver: the dot is under the pen while it touches,
 * steady, and gone the moment it lifts.
 */
internal class LaserPointer {

    /** Bumped on every change to what is drawn, for the overlay's draw scope to read. */
    var renderRevision by mutableIntStateOf(0)
        private set

    /**
     * Bumped each time the picture starts changing with time alone, which is what the overlay's
     * frame clock is keyed on.
     *
     * A counter rather than a flag: a flag that went false and back to true between two reads would
     * look unchanged, and the clock would never be restarted.
     */
    var wakeups by mutableIntStateOf(0)
        private set

    /**
     * Whether the picture still changes with no input arriving — a trace still up, or a lifted dot
     * still fading. A pen resting still with its trace spent needs no frames, so this is false then
     * even though the dot is showing.
     */
    val animating: Boolean get() = traces.any { it.marks.isNotEmpty() } || liftedAt >= 0L

    /** Where the dot is, in page units, or null when there is none. */
    var dot: InkPoint? = null
        private set

    /** Every trace still showing, oldest first. The newest is [live] while the pen is down. */
    private val traces = ArrayList<Trace>()
    private var live: Trace? = null

    /** Strokes whose traces have gone, kept to be started again rather than reallocated. */
    private val spare = ArrayDeque<InProgressStroke>()

    private var pointerId = -1

    /** When the pen lifted, in uptime milliseconds, or -1 while it is down or the dot is out. */
    private var liftedAt = -1L

    /** Whether this gesture gets a trace and a fade. Asked on each press — see [handle]. */
    private var motion = true

    /** Inputs gathered from one motion event, handed to [live]'s stroke together. */
    private val pending = MutableStrokeInputBatch()
    private val noPrediction = MutableStrokeInputBatch()

    private val scratch = FloatArray(2)

    /**
     * Follows the pen.
     *
     * [traceWidth] is the trace's width at the pen, in view pixels; the brush is sized from it for
     * the zoom this press lands at.
     *
     * [now] is the uptime clock [tick] and [draw] are read on, and the marks are stamped from it
     * rather than from `MotionEvent.eventTime`. On a device the two are the same clock, but injected
     * events need not be — a Compose test stamps them with its own virtual time — and a trace aged
     * against the wrong clock is gone before it is drawn. The samples a move batches keep their
     * spacing, measured back from [now].
     */
    fun handle(
        event: MotionEvent,
        toPage: Matrix,
        traceWidth: Float,
        now: Long = SystemClock.uptimeMillis(),
    ): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            // A new gesture, but the traces already up are left to run out their second.
            endGesture()
            pointerId = event.getPointerId(event.actionIndex)
            // Per gesture, so a change to the system setting takes effect on the next press with
            // nothing to recompose.
            motion = ValueAnimator.areAnimatorsEnabled()
            if (motion) startTrace(brushFor(toPage, traceWidth), now)
            mark(event.getX(event.actionIndex), event.getY(event.actionIndex), now, toPage)
            flush()
            true
        }
        // A second contact is a pinch or a palm. The dot lets go and the gesture is passed on, as a
        // stroke in progress does.
        MotionEvent.ACTION_POINTER_DOWN -> {
            if (pointerId >= 0) lift(now)
            false
        }
        MotionEvent.ACTION_MOVE -> {
            val index = event.findPointerIndex(pointerId)
            if (index < 0) {
                false
            } else {
                // The batched samples as well, or a quick flick reaches the modeller as a few long
                // chords and the smoothing has nothing between them to work with.
                for (sample in 0 until event.historySize) {
                    mark(
                        event.getHistoricalX(index, sample),
                        event.getHistoricalY(index, sample),
                        now - (event.eventTime - event.getHistoricalEventTime(sample)),
                        toPage,
                    )
                }
                mark(event.getX(index), event.getY(index), now, toPage)
                flush()
                true
            }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            if (event.getPointerId(event.actionIndex) == pointerId) {
                // A cancelled pointer (palm rejection, Android 13 and later) arrives as an up with
                // this flag. That was never a point being made, so its trace goes at once.
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) cancelGesture() else lift(now)
            }
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            cancelGesture()
            true
        }
        else -> false
    }

    /**
     * Ages the picture to [now], in uptime milliseconds: drops the marks that have run out their
     * lifetime and the traces left with none, puts the dot out once its fade has run, and restarts
     * the live stroke when it has grown long. Called once a frame for as long as [animating] holds.
     */
    fun tick(now: Long) {
        traces.forEach { it.dropMarksBefore(now - TRAIL_MILLIS) }
        traces.removeAll { trace ->
            (trace !== live && trace.marks.isEmpty()).also { gone -> if (gone) retire(trace) }
        }
        if (liftedAt >= 0L && now - liftedAt >= FADE_MILLIS) {
            liftedAt = -1L
            dot = null
        }
        live?.takeIf { now - it.startedAt > RESEED_MILLIS }?.reseed(now)
        invalidateDraw()
    }

    /** Puts the pointer away: no dot, no traces. */
    fun clear() {
        endGesture()
        traces.forEach(::retire)
        traces.clear()
        dot = null
        liftedAt = -1L
        invalidateDraw()
    }

    /** Draws the traces and the dot as they stand at [now], in uptime milliseconds. */
    fun draw(
        canvas: Canvas,
        pageToView: Matrix,
        now: Long,
        paint: LaserPaint,
        renderer: CanvasStrokeRenderer,
    ) {
        if (traces.any { it.marks.isNotEmpty() }) drawTraces(canvas, pageToView, now, paint, renderer)

        val spot = dot ?: return
        val fade = if (liftedAt < 0L) {
            1f
        } else {
            (1f - (now - liftedAt).toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
        }
        if (fade <= 0f) return
        scratch[0] = spot.x
        scratch[1] = spot.y
        pageToView.mapPoints(scratch)
        val alpha = (fade * 255f).roundToInt()
        val hum = if (motion) hum(now) else 1f
        val checkpoint = canvas.save()
        canvas.translate(scratch[0], scratch[1])
        paint.glow.alpha = (fade * hum * 255f).roundToInt()
        canvas.drawCircle(0f, 0f, paint.glowRadius, paint.glow)
        paint.core.alpha = alpha
        canvas.drawCircle(0f, 0f, paint.coreRadius, paint.core)
        paint.centre.alpha = alpha
        canvas.drawCircle(0f, 0f, paint.centreRadius, paint.centre)
        canvas.restoreToCount(checkpoint)
    }

    /**
     * The traces as lightsaber blades: two blurred glows, then the blade itself on top.
     *
     * The blade is opaque, laid into one layer that is then put down translucent, so the writing
     * under it still reads and two traces crossing do not darken where they cross. Each glow is one
     * layer for the same reason: every trace is blurred together, and the blur is tinted and faded
     * as a whole.
     *
     * The glows are a [RenderEffect], which only a hardware canvas runs. On a software canvas the
     * blade is drawn without them.
     */
    private fun drawTraces(
        canvas: Canvas,
        pageToView: Matrix,
        now: Long,
        paint: LaserPaint,
        renderer: CanvasStrokeRenderer,
    ) {
        // The layer only has to hold what is still visible. A moving average stays inside the hull
        // of the points it averages, so the live marks plus the trace's width bound it.
        val bounds = paint.bounds
        bounds.setEmpty()
        traces.forEach { trace ->
            val count = trace.marks.size
            if (count == 0) return@forEach
            val points = paint.points(count)
            trace.marks.forEachIndexed { i, mark ->
                points[2 * i] = mark.x
                points[2 * i + 1] = mark.y
            }
            pageToView.mapPoints(points, 0, points, 0, count)
            for (i in 0 until count) {
                val x = points[2 * i]
                val y = points[2 * i + 1]
                if (bounds.isEmpty) bounds.set(x, y, x + 1f, y + 1f) else bounds.union(x, y)
            }
        }
        bounds.inset(-paint.traceWidth, -paint.traceWidth)

        // Once a frame, before any of the three passes reads it.
        traces.forEach { trace ->
            if (trace.marks.isNotEmpty()) trace.stroke.updateShape(now - trace.startedAt)
        }

        if (canvas.isHardwareAccelerated) {
            val hum = hum(now)
            drawGlow(canvas, paint.outerGlow, OUTER_GLOW_ALPHA * hum, bounds, paint, pageToView, renderer)
            drawGlow(canvas, paint.innerGlow, INNER_GLOW_ALPHA * hum, bounds, paint, pageToView, renderer)
        }

        val layer = canvas.saveLayerAlpha(bounds, (TRACE_ALPHA * 255f).roundToInt())
        // On the canvas *and* to the renderer, as the finished ink is drawn: the renderer reads the
        // matrix only to pick a level of detail.
        canvas.concat(pageToView)
        drawStrokes(canvas, pageToView, renderer)
        canvas.restoreToCount(layer)
    }

    private fun drawStrokes(canvas: Canvas, pageToView: Matrix, renderer: CanvasStrokeRenderer) {
        traces.forEach { trace ->
            if (trace.marks.isNotEmpty()) renderer.draw(canvas, trace.stroke, pageToView)
        }
    }

    /**
     * One glow: the traces recorded into [node], whose effect blurs and tints them, put down at
     * [alpha]. The node is sized to [blade] plus the distance the blur spreads.
     */
    private fun drawGlow(
        canvas: Canvas,
        node: RenderNode,
        alpha: Float,
        blade: RectF,
        paint: LaserPaint,
        pageToView: Matrix,
        renderer: CanvasStrokeRenderer,
    ) {
        val left = floor(blade.left - paint.glowReach).toInt()
        val top = floor(blade.top - paint.glowReach).toInt()
        node.setPosition(
            left,
            top,
            ceil(blade.right + paint.glowReach).toInt(),
            ceil(blade.bottom + paint.glowReach).toInt(),
        )
        node.setAlpha(alpha)
        val recording = node.beginRecording()
        try {
            recording.translate(-left.toFloat(), -top.toFloat())
            recording.concat(pageToView)
            drawStrokes(recording, pageToView, renderer)
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
    }

    private fun mark(x: Float, y: Float, time: Long, toPage: Matrix) {
        val wasAnimating = animating
        scratch[0] = x
        scratch[1] = y
        toPage.mapPoints(scratch)
        val point = InkPoint(scratch[0], scratch[1])
        dot = point
        live?.add(point, time, pending)
        if (!wasAnimating && animating) wakeups++
        invalidateDraw()
    }

    /** Hands the inputs gathered from one event to the live stroke. */
    private fun flush() {
        val trace = live
        if (trace != null && !pending.isEmpty()) trace.stroke.enqueueInputs(pending, noPrediction)
        pending.clear()
    }

    private fun startTrace(brush: Brush, at: Long) {
        val stroke = spare.removeFirstOrNull() ?: InProgressStroke()
        stroke.start(brush)
        live = Trace(stroke, brush, at).also { traces += it }
    }

    private fun lift(time: Long) {
        pointerId = -1
        if (!motion) {
            dot = null
            invalidateDraw()
            return
        }
        endGesture()
        val wasAnimating = animating
        liftedAt = time
        if (!wasAnimating) wakeups++
        invalidateDraw()
    }

    /** The gesture in progress stops taking input; its trace stays up to run out its time. */
    private fun endGesture() {
        pointerId = -1
        pending.clear()
        live?.stroke?.finishInput()
        live = null
    }

    /** The gesture in progress is taken back whole — its trace and its dot — the others are left. */
    private fun cancelGesture() {
        val trace = live
        endGesture()
        if (trace != null) {
            traces.remove(trace)
            retire(trace)
        }
        dot = null
        liftedAt = -1L
        invalidateDraw()
    }

    private fun retire(trace: Trace) {
        trace.stroke.clear()
        if (spare.size < SPARE_STROKES) spare.addLast(trace.stroke)
    }

    private fun invalidateDraw() {
        renderRevision = if (renderRevision == Int.MAX_VALUE) 0 else renderRevision + 1
    }

    /** The trace's brush for this press: [traceWidth] view pixels wide at the zoom [toPage] is for. */
    private fun brushFor(toPage: Matrix, traceWidth: Float): Brush {
        val values = FloatArray(9)
        toPage.getValues(values)
        val pagePerPixel = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]).coerceAtLeast(1e-4f)
        return Brush.createWithColorIntArgb(
            family = traceFamily,
            colorIntArgb = LASER_RED,
            size = traceWidth * pagePerPixel,
            epsilon = EPSILON_PIXELS * pagePerPixel,
        )
    }

    /**
     * One gesture's trace: its stroke, and the inputs still alive in it.
     *
     * The marks are kept beside the stroke because the stroke cannot be asked for them cheaply: they
     * bound the layer the trace is drawn into, they say when the trace has gone, and they are what a
     * reseed starts the stroke again from.
     */
    private class Trace(val stroke: InProgressStroke, val brush: Brush, var startedAt: Long) {
        val marks = ArrayDeque<Mark>()

        /**
         * The last input handed to the stroke, which the next one must come after. A millisecond
         * before [startedAt] to begin with, so the first input is the stroke's time zero.
         */
        private var lastX = Float.NaN
        private var lastY = Float.NaN
        private var lastTime = startedAt - 1

        fun add(point: InkPoint, time: Long, into: MutableStrokeInputBatch) {
            // Ink takes inputs in order. Stamps measured back from two different events can cross
            // by a millisecond or two, and a down and a move handled within one millisecond share
            // a stamp. Inputs sharing a stamp are one instant to the sliding window: a live trace
            // made of them is modelled at the pen, and nothing is drawn between its samples until
            // a later one arrives. So a stamp that does not come after the last one goes a
            // millisecond past it, unless the input is the last one repeated.
            if (time <= lastTime && point.x == lastX && point.y == lastY) return
            val at = maxOf(time, lastTime + 1)
            dropMarksBefore(at - TRAIL_MILLIS)
            marks.addLast(Mark(point.x, point.y, at))
            into.add(InputToolType.UNKNOWN, point.x, point.y, at - startedAt)
            lastX = point.x
            lastY = point.y
            lastTime = at
        }

        fun dropMarksBefore(time: Long) {
            while (marks.isNotEmpty() && marks.first().time < time) marks.removeFirst()
        }

        /**
         * Starts the stroke again from the marks still alive.
         *
         * A time behaviour changes every input on every frame, so Ink re-extrudes the whole stroke
         * each time it is drawn. Held for a minute of presenting, that is every input of the minute,
         * almost all of it long gone. Restarting from the live marks keeps the work to what can be
         * seen; the seam falls at the trace's oldest end, where it has already thinned to nothing.
         */
        fun reseed(now: Long) {
            startedAt = marks.firstOrNull()?.time ?: now
            stroke.start(brush)
            lastX = Float.NaN
            lastY = Float.NaN
            lastTime = startedAt - 1
            val alive = marks.toList()
            marks.clear()
            val batch = MutableStrokeInputBatch()
            alive.forEach { add(InkPoint(it.x, it.y), it.time, batch) }
            if (!batch.isEmpty()) stroke.enqueueInputs(batch, MutableStrokeInputBatch())
        }
    }

    private class Mark(val x: Float, val y: Float, val time: Long)

    companion object {
        /** How long a point of the trace stays at full width after the pen passed it. */
        const val HOLD_MILLIS = 1_000L

        /** How long it then takes to thin away to nothing. */
        const val VANISH_MILLIS = 300L

        /** A point's whole lifetime: held, then vanishing. */
        const val TRAIL_MILLIS = HOLD_MILLIS + VANISH_MILLIS

        /** How long the dot takes to go out once the pen lifts. */
        const val FADE_MILLIS = 300L

        /** How old the live stroke may grow before it is restarted from its marks — see reseed. */
        private const val RESEED_MILLIS = 3_000L

        /** Strokes kept for reuse. A few traces up at once is the most anyone points. */
        private const val SPARE_STROKES = 4

        /** The trace is laid down slightly translucent, so writing under it still reads. */
        private const val TRACE_ALPHA = 0.85f

        /** How strongly the wide crimson glow and the tight red one are put down. */
        private const val OUTER_GLOW_ALPHA = 0.8f
        private const val INNER_GLOW_ALPHA = 0.9f

        /** How far the hum dims the glow at its lowest. */
        private const val HUM_DEPTH = 0.12f

        /**
         * The blade's hum: a slight, uneven waver in the glow, between `1 - HUM_DEPTH` and 1.
         *
         * Two slow sines, a few times a second, that never fall into step, so it reads as a hum
         * rather than a blink. Wrapped to a minute first, since uptime in milliseconds outgrows
         * the precision a sine needs within a day.
         */
        private fun hum(now: Long): Float {
            val seconds = (now % 60_000L) / 1_000.0
            val wave = 0.5 + 0.3 * sin(2.0 * PI * 4.3 * seconds) + 0.2 * sin(2.0 * PI * 7.1 * seconds)
            return (1.0 - HUM_DEPTH * wave).toFloat()
        }

        /** How wide the white-hot core is, as a share of the blade. */
        private const val CORE_SHARE = 0.5f

        /** The blade's core: white, with the faintest warmth so it reads as heat and not paper. */
        private const val LASER_CORE = 0xFFFFF4F4.toInt()

        /** A quarter pixel, the finest detail worth meshing at the zoom the press landed at. */
        private const val EPSILON_PIXELS = 0.25f

        /** Pen stabilization level 1 — the library's own default window. */
        private const val TRACE_STABILIZATION = 1

        /** Red A400 — the most saturated red in the Material palette. */
        internal const val LASER_RED = 0xFFFF1744.toInt()

        /** The outer glow's deep crimson, the dark red a blade's light fades into. */
        internal const val LASER_CRIMSON = 0xFF8E0010.toInt()

        /**
         * Two round coats, a red edge and the white core on top of it. Each is held at full size
         * for [HOLD_MILLIS] since each input, then shrunk to nothing by [TRAIL_MILLIS]; below the
         * range the source clamps, which is what holds it.
         *
         * Built once: a family is a native allocation, and nothing in it varies between presses.
         */
        private val traceFamily: BrushFamily by lazy {
            val vanish = BrushBehavior(
                terminalNode = TargetNode(
                    target = TargetNode.Target.SIZE_MULTIPLIER,
                    targetModifierRangeStart = 1f,
                    targetModifierRangeEnd = 0f,
                    input = SourceNode(
                        source = SourceNode.Source.TIME_SINCE_INPUT_IN_SECONDS,
                        sourceValueRangeStart = HOLD_MILLIS / 1_000f,
                        sourceValueRangeEnd = TRAIL_MILLIS / 1_000f,
                    ),
                ),
                developerComment = "Laser trace: held for a second, then thins away.",
            )
            BrushFamily(
                coats = listOf(
                    BrushCoat(tip = BrushTip(behaviors = listOf(vanish)), paint = BrushPaint()),
                    BrushCoat(
                        tip = BrushTip(
                            scaleX = CORE_SHARE,
                            scaleY = CORE_SHARE,
                            behaviors = listOf(vanish),
                        ),
                        paint = BrushPaint(
                            colorFunctions = listOf(
                                BrushPaint.ColorFunction.ReplaceColor.withColorIntArgb(LASER_CORE),
                            ),
                        ),
                    ),
                ),
                inputModel = InkCodec.inputModelFor(TRACE_STABILIZATION),
                developerComment = "ViveNotes laser pointer trace. Never stored.",
            )
        }
    }
}

/**
 * What the laser's dot and its trace's glows are drawn with, sized for one display density.
 *
 * Built once and reused every frame: the overlay redraws for as long as a trace is up, and a shader,
 * three paints and two blur layers a frame would be garbage at the display's refresh rate.
 *
 * Fixed reds rather than theme colours. A laser pointer is red the world over, and it has to stand
 * out from ink of every colour on paper of either shade — the app's accent is what selection and the
 * Insert Space guide are drawn in, and a dot in that colour would read as one of them.
 */
internal class LaserPaint(density: Float) {
    val coreRadius = CORE_RADIUS_DP * density
    val centreRadius = CENTRE_RADIUS_DP * density
    val glowRadius = GLOW_RADIUS_DP * density

    /** The trace's width, in view pixels — see [LaserPointer.handle]. */
    val traceWidth = TRACE_WIDTH_DP * density

    /**
     * How far past the blade the glows spread, which the layers they are blurred in must hold. A
     * blur's radius is about 1.7 standard deviations, and a Gaussian has all but run out by three.
     */
    val glowReach = OUTER_BLUR_DP * density * 2f

    /** The wide, deep-crimson glow, under everything else. Thickened so it reads on dark paper. */
    val outerGlow = glowLayer("laser outer glow", OUTER_BLUR_DP * density, LaserPointer.LASER_CRIMSON, 1.8f)

    /** The tight, bright-red glow hugging the blade. Thickened so it stays dense right at the edge. */
    val innerGlow = glowLayer("laser inner glow", INNER_BLUR_DP * density, LaserPointer.LASER_RED, 1.5f)

    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Centred on the origin: the dot is drawn after translating the canvas to it, so one shader
        // serves every frame. Bright red at the spot, fading through crimson, like the blade.
        shader = RadialGradient(
            0f,
            0f,
            glowRadius,
            intArrayOf(GLOW_INNER, GLOW_MIDDLE, GLOW_DARK, GLOW_OUTER),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = LaserPointer.LASER_RED
    }
    val centre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = LASER_CENTRE
    }
    val bounds = RectF()

    private var buffer = FloatArray(128)

    /** A scratch array that holds [count] points, grown when a long trace needs more. */
    fun points(count: Int): FloatArray {
        if (buffer.size < count * 2) buffer = FloatArray(count * 4)
        return buffer
    }

    private companion object {
        const val CORE_RADIUS_DP = 4.5f
        const val CENTRE_RADIUS_DP = 2.5f
        const val GLOW_RADIUS_DP = 16f
        const val TRACE_WIDTH_DP = 6f

        /** Blur radii of the two glows. */
        const val INNER_BLUR_DP = 4f
        const val OUTER_BLUR_DP = 12f

        /** The hot centre of the spot, nearly white the way the blade's core is. */
        const val LASER_CENTRE = 0xFFFFF4F4.toInt()
        const val GLOW_INNER = 0xCCFF1744.toInt()
        const val GLOW_MIDDLE = 0x80FF1744.toInt()
        const val GLOW_DARK = 0x408E0010
        const val GLOW_OUTER = 0x008E0010

        /**
         * A layer whose content comes out blurred by [radius] pixels and recoloured to [color],
         * keeping only its shape, with that shape's coverage scaled by [thicken].
         *
         * Recoloured after the blur rather than before, so the glow is one even colour all the way
         * out instead of whitening where it passes over the blade's core.
         */
        fun glowLayer(name: String, radius: Float, color: Int, thicken: Float): RenderNode =
            RenderNode(name).apply {
                val tint = ColorMatrixColorFilter(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, Color.red(color).toFloat(),
                        0f, 0f, 0f, 0f, Color.green(color).toFloat(),
                        0f, 0f, 0f, 0f, Color.blue(color).toFloat(),
                        0f, 0f, 0f, thicken, 0f,
                    ),
                )
                setRenderEffect(
                    RenderEffect.createColorFilterEffect(
                        tint,
                        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL),
                    ),
                )
            }
    }
}
