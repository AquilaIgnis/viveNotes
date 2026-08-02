package com.vivenotes.ui.editor

import android.graphics.Matrix
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.data.EraserMode
import com.vivenotes.data.EraserSettings
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.PageStroke

/** Test tag for the drawing surface, which has no text and no children of its own. */
internal const val INK_OVERLAY_TAG = "ink-overlay"

/**
 * Moving the page, for the gestures the overlay decides are not ink.
 *
 * An interface rather than two lambdas so the two halves cannot drift apart, and so a test can
 * record what a gesture asked the page to do without a scroll container behind it.
 */
internal interface CanvasPan {

    /** Drag, in view pixels, positive meaning the content moves up and left. */
    fun by(dx: Float, dy: Float)

    /** Release, in view pixels per second. */
    fun fling(vx: Float, vy: Float)
}

/** For a canvas that has nothing to scroll — a preview, or a test composing the overlay alone. */
internal object NoPan : CanvasPan {
    override fun by(dx: Float, dy: Float) = Unit

    override fun fling(vx: Float, vy: Float) = Unit
}

/**
 * The drawing surface: wet ink under the pen, finished ink behind it, in one layer over the page.
 *
 * **Why this sits outside the zoom.** `Zoomed` scales the page through a `graphicsLayer`, and
 * scaling a front-buffered surface renders the wet ink at the layer's resolution and then stretches
 * the result — ink would be soft while drawing and snap sharp on release. So the overlay covers the
 * viewport at 1:1 device scale and is handed the page → view transform instead, which is exactly
 * what `InProgressStrokesView.startStroke` takes two matrices for. Strokes are therefore captured
 * and stored in page units at any zoom, the same invariant `Zoomed` already keeps for text.
 *
 * [pageToView] is a lambda called at draw time and at event time rather than a captured value, for
 * the reason `PageRuling` takes its window as one: reading the scroll position during composition
 * would recompose this on every scrolled pixel, where reading it during the draw re-runs only the
 * draw.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun InkOverlay(
    strokes: List<PageStroke>,
    /** The brush to draw with, or null when the armed tool does not lay down ink. */
    brush: Brush?,
    erasing: Boolean,
    eraser: EraserSettings,
    /** Whether a finger — or, on an emulator, a mouse — may draw as well as a stylus. */
    allowFinger: Boolean,
    /** Page units (dp) to view pixels: scale by zoom and density, then subtract the scroll. */
    pageToView: () -> Matrix,
    onStrokeFinished: (Stroke) -> Unit,
    onPartialErase: (Stroke) -> Unit,
    onObjectErase: (Stroke) -> Unit,
    /**
     * Pans the page. The overlay owns this because it owns the gesture: a hit pointer node blocks
     * its siblings from seeing the event at all, so declining a touch is not enough to hand it to
     * the scroll container underneath — the page simply stopped panning while a pen was in hand.
     * A drawing surface that dispatches between ink and pan is also the shape this needs anyway,
     * once two-finger pan and pinch-zoom arrive.
     */
    pan: CanvasPan = NoPan,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    var wetView by remember { mutableStateOf<InProgressStrokesView?>(null) }

    // Read inside callbacks that outlive the composition that created them.
    val currentBrush by rememberUpdatedState(brush)
    val currentStrokes by rememberUpdatedState(strokes)
    val currentErasing by rememberUpdatedState(erasing)
    val currentEraser by rememberUpdatedState(eraser)
    val currentAllowFinger by rememberUpdatedState(allowFinger)
    val currentTransform by rememberUpdatedState(pageToView)
    val currentOnFinished by rememberUpdatedState(onStrokeFinished)
    val currentOnPartialErase by rememberUpdatedState(onPartialErase)
    val currentOnObjectErase by rememberUpdatedState(onObjectErase)

    val currentPan by rememberUpdatedState(pan)

    /** The stroke being drawn, and the pointer drawing it. One at a time: this is a pen, not a rake. */
    var liveStroke by remember { mutableStateOf<InProgressStrokeId?>(null) }
    var livePointer by remember { mutableStateOf(-1) }
    val eraseGesture = remember { EraseGesture() }

    // Velocity for the fling, measured from the same events the pan is driven by.
    val velocity = remember { VelocityTracker.obtain() }
    var panning by remember { mutableStateOf(false) }
    var lastPan by remember { mutableStateOf(0f to 0f) }

    // Clipped here rather than left to the caller, because nothing else stops it. Compose does not
    // clip children to their parent, and this draws through a matrix that can put a stroke anywhere
    // in the window — so a stroke begun on the canvas and dragged over the ribbon, the page list or
    // an open tool pane painted straight over them. Android delivers the whole gesture to whoever
    // took the ACTION_DOWN, which is right (a stroke must not break because the pen left the page),
    // so the fix belongs in what is drawn, not in what is delivered.
    Box(modifier.clipToBounds().testTag(INK_OVERLAY_TAG)) {
        // Finished ink, drawn by us rather than left in the authoring view: the authoring view
        // renders with the transform it was given when the stroke started, so it would not follow a
        // later scroll. Reading the transform here in the draw scope means scrolling re-runs this
        // lambda and nothing above it.
        Canvas(Modifier.fillMaxSize()) {
            val matrix = currentTransform()
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                // The matrix goes on the canvas, and is *also* passed to the renderer. Passing it
                // alone leaves the geometry untransformed — it draws at stroke coordinates, so ink
                // landed at page-units-as-pixels, ignoring zoom and scroll. The argument is what
                // the renderer measures to pick a mesh detail level for the scale it is drawn at,
                // not what moves it.
                val checkpoint = native.save()
                native.concat(matrix)
                currentStrokes.forEach { renderer.draw(native, it.stroke, matrix) }
                native.restoreToCount(checkpoint)
            }
        }

        // Composed only while a tool is armed: this is the expensive half — a front-buffered
        // surface and its render thread — and there is no reason to hold one while nobody is
        // drawing.
        if (brush != null || erasing) {
            AndroidView(
                factory = { context ->
                    // Wrapped rather than hosted directly. An AndroidView reports the gesture as
                    // consumed if its view hierarchy handles it, and Compose then stops hit-testing
                    // — which took every finger drag meant for the scroll container underneath, so
                    // the page could not be panned while a pen was in hand. This view needs no
                    // touch of its own: it is fed events by hand from the filter above it.
                    TouchTransparent(context).apply {
                        addView(
                            InProgressStrokesView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                // Sets up the renderer and its thread now rather than on the first
                                // stroke, where the cost lands as visible lag on the very first
                                // mark someone makes.
                                eagerInit()
                                addFinishedStrokesListener(
                                    object : InProgressStrokesFinishedListener {
                                        override fun onStrokesFinished(
                                            strokes: Map<InProgressStrokeId, Stroke>,
                                        ) {
                                            strokes.values.forEach(currentOnFinished)
                                            // Handed over, so the authoring view stops drawing
                                            // them. Held any longer and they would be drawn twice,
                                            // and would not follow a scroll.
                                            removeFinishedStrokes(strokes.keys)
                                        }
                                    },
                                )
                                wetView = this
                            },
                        )
                    }
                },
                onRelease = { wetView = null },
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { event ->
                        handleInk(
                            event = event,
                            view = wetView,
                            brush = currentBrush,
                            erasing = currentErasing,
                            eraser = currentEraser,
                            allowFinger = currentAllowFinger,
                            transform = currentTransform(),
                            onPartialErase = currentOnPartialErase,
                            onObjectErase = currentOnObjectErase,
                            liveStroke = liveStroke,
                            livePointer = livePointer,
                            setLive = { id, pointer ->
                                liveStroke = id
                                livePointer = pointer
                            },
                            pan = currentPan,
                            velocity = velocity,
                            panning = panning,
                            lastPan = lastPan,
                            setPanning = { on, at ->
                                panning = on
                                lastPan = at
                            },
                            eraseGesture = eraseGesture,
                        )
                    },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { wetView?.cancelUnfinishedStrokes() }
    }
}

/**
 * Turns one motion event into ink.
 *
 * Returns false to let the gesture through — a finger on a stylus-only canvas has to reach the
 * scroll container behind, or the page becomes unscrollable the moment a pen is picked up.
 */
private fun handleInk(
    event: MotionEvent,
    view: InProgressStrokesView?,
    brush: Brush?,
    erasing: Boolean,
    eraser: EraserSettings,
    allowFinger: Boolean,
    transform: Matrix,
    onPartialErase: (Stroke) -> Unit,
    onObjectErase: (Stroke) -> Unit,
    liveStroke: InProgressStrokeId?,
    livePointer: Int,
    setLive: (InProgressStrokeId?, Int) -> Unit,
    pan: CanvasPan,
    velocity: VelocityTracker,
    panning: Boolean,
    lastPan: Pair<Float, Float>,
    setPanning: (Boolean, Pair<Float, Float>) -> Unit,
    eraseGesture: EraseGesture,
): Boolean {
    if (view == null) return false
    val index = event.actionIndex
    val toolType = event.getToolType(index)

    // An emulator reports a mouse, and a mouse is the only pointing device it has — so it counts as
    // a finger here, which is what makes the finger toggle testable without a stylus.
    val isDirectTouch = toolType == MotionEvent.TOOL_TYPE_FINGER || toolType == MotionEvent.TOOL_TYPE_MOUSE

    // A touch that is not allowed to draw pans instead. Consumed rather than declined, because
    // declining leaves it with nobody: the scroll container is a sibling, and a sibling under a hit
    // pointer node never sees the gesture.
    if (isDirectTouch && !allowFinger) return panPage(event, pan, velocity, panning, lastPan, setPanning)

    // The eraser end of a stylus erases whatever tool is armed, because that is what the user just
    // turned the pen over to do.
    val erase = erasing || toolType == MotionEvent.TOOL_TYPE_ERASER

    if (erase) {
        val toWorld = Matrix().also { transform.invert(it) }
        return eraseGesture.handle(event, toWorld, eraser.size.toFloat()) { mask ->
            when (eraser.mode) {
                EraserMode.Normal -> onPartialErase(mask)
                EraserMode.Object -> onObjectErase(mask)
            }
        }
    }

    if (brush == null) return false
    val toWorld = Matrix().also { transform.invert(it) }

    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            // A second pointer while drawing is a palm or a pinch, so the stroke is taken back and
            // the gesture handed to the scroll container behind — that is how the page still pans
            // while a pen is in hand.
            if (liveStroke != null) {
                view.cancelStroke(liveStroke, event)
                setLive(null, -1)
                return false
            }
            val pointerId = event.getPointerId(index)
            // The fourth argument is `motionEventToWorldTransform` and the fifth is
            // `strokeToWorldTransform` — checked against the parameter names in the library's own
            // bytecode, because the guide describes them the other way round. Passing the page →
            // view matrix here instead stored every stroke at view pixels rather than page units,
            // which is roughly density times too far down the page.
            //
            // Stroke space and world space are the same thing here, so the fifth stays identity.
            // The view derives world → view for its own rendering from this matrix and from
            // `motionEventToViewTransform`, which is identity because the overlay is 1:1 with the
            // events it receives.
            setLive(view.startStroke(event, pointerId, brush, toWorld), pointerId)
        }
        MotionEvent.ACTION_MOVE -> {
            val id = liveStroke ?: return false
            view.addToStroke(event, livePointer, id)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            val id = liveStroke ?: return false
            if (event.getPointerId(index) != livePointer) return true
            view.finishStroke(event, livePointer, id)
            setLive(null, -1)
        }
        MotionEvent.ACTION_CANCEL -> {
            // Palm rejection lands here, and on a cancelled pointer post-Android 13 also as
            // FLAG_CANCELED on the up. Both mean: that was not a stroke, take it back.
            val id = liveStroke ?: return false
            view.cancelStroke(id, event)
            setLive(null, -1)
        }
        else -> return false
    }
    return true
}

/**
 * Drags the page under the finger, and lets go of it with a fling.
 *
 * Deliberately raw deltas rather than an animation per move: the page has to keep up with the
 * finger exactly, and only the release is animated.
 */
private fun panPage(
    event: MotionEvent,
    pan: CanvasPan,
    velocity: VelocityTracker,
    panning: Boolean,
    lastPan: Pair<Float, Float>,
    setPanning: (Boolean, Pair<Float, Float>) -> Unit,
): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            velocity.clear()
            velocity.addMovement(event)
            setPanning(true, event.x to event.y)
        }
        MotionEvent.ACTION_MOVE -> {
            if (!panning) return false
            velocity.addMovement(event)
            val (lastX, lastY) = lastPan
            pan.by(lastX - event.x, lastY - event.y)
            setPanning(true, event.x to event.y)
        }
        MotionEvent.ACTION_UP -> {
            if (!panning) return false
            velocity.addMovement(event)
            velocity.computeCurrentVelocity(1000)
            pan.fling(-velocity.xVelocity, -velocity.yVelocity)
            setPanning(false, 0f to 0f)
        }
        MotionEvent.ACTION_CANCEL -> setPanning(false, 0f to 0f)
        else -> return false
    }
    return true
}

/** Accumulates one eraser drag into a round Ink mask in page coordinates. */
private class EraseGesture {
    private var inputs: MutableStrokeInputBatch? = null
    private var pointerId: Int = -1
    private var startTimeMillis: Long = 0L
    private var lastElapsedMillis: Long = -1L
    private var lastX: Float = Float.NaN
    private var lastY: Float = Float.NaN

    fun handle(
        event: MotionEvent,
        toWorld: Matrix,
        sizeDp: Float,
        onFinished: (Stroke) -> Unit,
    ): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                pointerId = event.getPointerId(event.actionIndex)
                startTimeMillis = event.eventTime
                inputs = MutableStrokeInputBatch()
                addSamples(event, toWorld)
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second contact is a palm or pan gesture, not a wider eraser.
                reset()
                false
            }
            MotionEvent.ACTION_MOVE -> {
                if (inputs == null) return false
                addSamples(event, toWorld)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (inputs == null || event.getPointerId(event.actionIndex) != pointerId) return false
                addSamples(event, toWorld)
                val finished = inputs!!.toImmutable()
                reset()
                if (!finished.isEmpty()) onFinished(InkCodec.eraseMask(finished, sizeDp))
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
                true
            }
            else -> false
        }
    }

    private fun addSamples(event: MotionEvent, toWorld: Matrix) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        repeat(event.historySize) { history ->
            addPoint(
                event.getHistoricalX(index, history),
                event.getHistoricalY(index, history),
                event.getHistoricalEventTime(history),
                toWorld,
            )
        }
        addPoint(event.getX(index), event.getY(index), event.eventTime, toWorld)
    }

    private fun addPoint(viewX: Float, viewY: Float, eventTime: Long, toWorld: Matrix) {
        val point = floatArrayOf(viewX, viewY)
        toWorld.mapPoints(point)
        if (point[0] == lastX && point[1] == lastY) return
        val elapsed = (eventTime - startTimeMillis).coerceAtLeast(lastElapsedMillis + 1)
        inputs?.add(InputToolType.UNKNOWN, point[0], point[1], elapsed)
        lastElapsedMillis = elapsed
        lastX = point[0]
        lastY = point[1]
    }

    private fun reset() {
        inputs = null
        pointerId = -1
        startTimeMillis = 0L
        lastElapsedMillis = -1L
        lastX = Float.NaN
        lastY = Float.NaN
    }
}

/**
 * A container that never claims a touch.
 *
 * `InProgressStrokesView` is driven by hand — [handleInk] calls `startStroke` and friends with the
 * events it decides belong to ink — so it has no need to receive touch itself, and every event it
 * absorbs is one the page cannot scroll with.
 */
private class TouchTransparent(context: android.content.Context) : android.widget.FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

/**
 * Page units to view pixels: scale by zoom and density, then subtract the scroll.
 *
 * Extracted so it can be asserted rather than eyeballed. Getting it wrong is not a visible crash —
 * ink simply lands somewhere else, and it did twice while this was built: once because the stroke
 * was captured through this matrix instead of its inverse, and once because the renderer was handed
 * the matrix without it being applied to the canvas.
 */
internal fun inkPageToView(zoom: Float, density: Float, scrollX: Float, scrollY: Float): Matrix {
    val scale = zoom * density
    return Matrix().apply {
        setScale(scale, scale)
        postTranslate(-scrollX, -scrollY)
    }
}
