package com.vivenotes.ui.editor

import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
import com.vivenotes.model.ink.LineType
import com.vivenotes.data.ShapeSettings
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.InkLassoMove
import com.vivenotes.ink.InkLassoResize
import com.vivenotes.ink.InkLassoSelection
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageBounds
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.Ruler
import com.vivenotes.ink.TableBounds
import com.vivenotes.ink.pageBounds
import com.vivenotes.ink.projectionKey
import com.vivenotes.ink.selectWithLasso
import com.vivenotes.ink.targetsFor
import com.vivenotes.ink.subtract
import com.vivenotes.ink.eraseObjects
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.trace
import com.vivenotes.ui.theme.LocalCanvasColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.hypot

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
    /** Shapes on the page, so a lasso loop can take one — AD7's first row. */
    shapes: List<Outline.Shape> = emptyList(),
    /**
     * The tables, as rectangles the *canvas* measured — `docs/tablePlan.md` TA4, and [TableBounds]
     * for why the model's own height will not do here.
     */
    tables: List<TableBounds> = emptyList(),
    /** Equations on the page, which a loop takes by their box like a table. */
    equations: List<Outline.Equation> = emptyList(),
    /** Pictures, taken by their frame for the reason an equation is taken by its box. */
    images: List<Outline.Image> = emptyList(),
    /**
     * What is selected, across kinds. Owned by the page rather than by this overlay: a shape can be in
     * it, and `ShapeLayer` has to draw the same selection this does. See [CanvasSelection].
     */
    selection: CanvasSelection? = null,
    onSelect: (CanvasSelection?) -> Unit = {},
    /** The lasso's live gesture, also owned by the page so both layers read one transform. */
    lassoGesture: LassoGesture,
    /** The brush to draw with, or null when the armed tool does not lay down ink. */
    brush: Brush?,
    erasing: Boolean,
    lassoing: Boolean,
    /** The armed shape's settings, or null when Insert Shape is not the tool in hand. */
    shaping: ShapeSettings?,
    /**
     * The ruler lying on the page, or null when it is away — `docs/rulerPlan.md`.
     *
     * Drawn here because this canvas is composed in every tool state, and applied here because RD5's
     * snapping has to reach the wet stroke rather than the finished one. Moving it is somebody
     * else's job: see `detectRulerDrag` for why that lives on an ancestor.
     */
    ruler: Ruler? = null,
    eraser: EraserSettings,
    /** Whether a finger — or, on an emulator, a mouse — may draw as well as a stylus. */
    allowFinger: Boolean,
    /** Page units (dp) to view pixels: scale by zoom and density, then subtract the scroll. */
    pageToView: () -> Matrix,
    onStrokeFinished: (Stroke) -> Unit,
    onInsertShape: (InkPoint, InkPoint) -> Unit = { _, _ -> },
    onPartialErase: (Stroke) -> Unit,
    onObjectErase: (Stroke) -> Unit,
    onMoveSelection: (InkLassoMove) -> Unit,
    onResizeSelection: (InkLassoResize) -> Unit = {},
    onMoveShapes: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeShapes: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
    onMoveTables: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeTables: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
    onMoveEquations: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeEquations: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
    onMoveImages: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeImages: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
    onDeleteSelection: (Set<String>) -> Unit = {},
    hasClipboard: Boolean = false,
    onRequestPaste: (InkPoint) -> Unit = {},
    onRecolorSelection: (Set<String>, Int) -> Unit = { _, _ -> },
    onGroupSelection: (Set<String>) -> Unit = {},
    onUngroupSelection: (Set<String>) -> Unit = {},
    /**
     * Pans the page. The overlay owns this because it owns the gesture: a hit pointer node blocks
     * its siblings from seeing the event at all, so declining a touch is not enough to hand it to
     * the scroll container underneath — the page simply stopped panning while a pen was in hand.
     * A drawing surface that dispatches between ink and pan is also the shape this needs anyway.
     *
     * One finger only. Two are a pinch, which is owned by `detectPinchZoom` on an ancestor of this
     * whole pane — nothing less than an ancestor can take a gesture off a hit pointer node — so a
     * second contact ends the pan here rather than dragging the page from the first pointer alone.
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
    val currentLassoing by rememberUpdatedState(lassoing)
    val currentShaping by rememberUpdatedState(shaping)
    val currentRuler by rememberUpdatedState(ruler)
    val currentEraser by rememberUpdatedState(eraser)
    val currentAllowFinger by rememberUpdatedState(allowFinger)
    val currentTransform by rememberUpdatedState(pageToView)
    val currentOnFinished by rememberUpdatedState(onStrokeFinished)
    val currentOnInsertShape by rememberUpdatedState(onInsertShape)
    val currentOnPartialErase by rememberUpdatedState(onPartialErase)
    val currentOnObjectErase by rememberUpdatedState(onObjectErase)
    val currentShapes by rememberUpdatedState(shapes)
    val currentSelection by rememberUpdatedState(selection)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentTables by rememberUpdatedState(tables)
    val currentEquations by rememberUpdatedState(equations)
    val currentImages by rememberUpdatedState(images)
    val currentOnMoveShapes by rememberUpdatedState(onMoveShapes)
    val currentOnResizeShapes by rememberUpdatedState(onResizeShapes)
    val currentOnMoveEquations by rememberUpdatedState(onMoveEquations)
    val currentOnResizeEquations by rememberUpdatedState(onResizeEquations)
    val currentOnMoveImages by rememberUpdatedState(onMoveImages)
    val currentOnResizeImages by rememberUpdatedState(onResizeImages)
    val currentOnMoveTables by rememberUpdatedState(onMoveTables)
    val currentOnResizeTables by rememberUpdatedState(onResizeTables)
    val currentOnMoveSelection by rememberUpdatedState(onMoveSelection)
    val currentOnResizeSelection by rememberUpdatedState(onResizeSelection)
    val currentOnDeleteSelection by rememberUpdatedState(onDeleteSelection)
    val currentHasInkClipboard by rememberUpdatedState(hasClipboard)
    val currentOnRequestPaste by rememberUpdatedState(onRequestPaste)
    val currentOnRecolorSelection by rememberUpdatedState(onRecolorSelection)
    val currentOnGroupSelection by rememberUpdatedState(onGroupSelection)
    val currentOnUngroupSelection by rememberUpdatedState(onUngroupSelection)

    val currentPan by rememberUpdatedState(pan)

    /** The stroke being drawn, and the pointer drawing it. One at a time: this is a pen, not a rake. */
    var liveStroke by remember { mutableStateOf<InProgressStrokeId?>(null) }
    var livePointer by remember { mutableStateOf(-1) }

    /**
     * Whether the stroke in progress is a ruled one — RD5.
     *
     * Decided on the down and held for the whole stroke, so a hand drifting off the ruler still
     * draws the line it started; the alternative is a stroke that changes character halfway.
     */
    var ruledStroke by remember { mutableStateOf(false) }
    val eraseGesture = remember { EraseGesture() }
    val shapeGesture = remember { ShapeGesture() }
    val viewConfiguration = LocalViewConfiguration.current
    val fingerDoubleTap = remember(viewConfiguration) {
        FingerDoubleTapGesture(
            minimumIntervalMillis = viewConfiguration.doubleTapMinTimeMillis,
            maximumIntervalMillis = viewConfiguration.doubleTapTimeoutMillis,
            touchSlop = viewConfiguration.touchSlop,
        )
    }

    LaunchedEffect(lassoing) {
        if (!lassoing) lassoGesture.clear()
    }
    LaunchedEffect(shaping == null) {
        if (shaping == null) shapeGesture.clear()
    }
    LaunchedEffect(strokes) {
        eraseGesture.reconcileCommittedStrokes()
    }
    LaunchedEffect(erasing) {
        if (!erasing) eraseGesture.clear()
    }
    LaunchedEffect(hasClipboard) {
        if (!hasClipboard) fingerDoubleTap.reset()
    }

    // Velocity for the fling, measured from the same events the pan is driven by.
    val velocity = remember { VelocityTracker.obtain() }
    var panning by remember { mutableStateOf(false) }
    var lastPan by remember { mutableStateOf(0f to 0f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var liveErasedStrokes by remember { mutableStateOf<List<PageStroke>?>(null) }
    LaunchedEffect(strokes, eraser.mode) {
        snapshotFlow { eraseGesture.previewMask }
            .conflate()
            .collect { mask ->
                liveErasedStrokes = if (mask == null) {
                    null
                } else {
                    withContext(Dispatchers.Default) {
                        strokes.previewErase(mask, eraser.mode)
                    }
                }
            }
    }

    // Clipped here rather than left to the caller, because nothing else stops it. Compose does not
    // clip children to their parent, and this draws through a matrix that can put a stroke anywhere
    // in the window — so a stroke begun on the canvas and dragged over the ribbon, the page list or
    // an open tool pane painted straight over them. Android delivers the whole gesture to whoever
    // took the ACTION_DOWN, which is right (a stroke must not break because the pen left the page),
    // so the fix belongs in what is drawn, not in what is delivered.
    val lassoColor = MaterialTheme.colorScheme.primary.toArgb()
    // The disc inside each corner handle, and the app's surface rather than white for the reason the
    // radius beside it is a shared dp: `ShapeLayer`, `EquationLayer` and `ImageLayer` all fill theirs
    // with `colorScheme.surface`, so a hardcoded white here was one selection affordance in two
    // colours — the same one, on the same page, depending only on which tool had made it (AD7).
    val lassoHandleFill = MaterialTheme.colorScheme.surface.toArgb()
    // Frosted plastic in the *canvas's* own ink, not the app's accent: a ruler is an object lying on
    // the paper, and one painted in the selection colour reads as a selection. See `RulerPaint`.
    val canvasInk = LocalCanvasColors.current.text
    val rulerPaint = remember(canvasInk) {
        RulerPaint(
            body = canvasInk.copy(alpha = 0.16f).toArgb(),
            edge = canvasInk.copy(alpha = 0.55f).toArgb(),
            mark = canvasInk.copy(alpha = 0.72f).toArgb(),
        )
    }
    Box(modifier.clipToBounds().testTag(INK_OVERLAY_TAG).onSizeChanged { viewportSize = it }) {
        // Finished ink, drawn by us rather than left in the authoring view: the authoring view
        // renders with the transform it was given when the stroke started, so it would not follow a
        // later scroll. Reading the transform here in the draw scope means scrolling re-runs this
        // lambda and nothing above it.
        Canvas(Modifier.fillMaxSize()) {
            // A pointerInteropFilter mutates gesture state outside Compose's pointer coroutine.
            // Reading this explicit revision in the draw phase guarantees every sample invalidates
            // the native canvas, including the free-form trace and the live move/resize preview.
            val gestureRevision = lassoGesture.renderRevision
            val shapeRevision = shapeGesture.renderRevision
            val matrix = currentTransform()
            // The window in page units, so a stroke that cannot be seen is not drawn. On a densely
            // handwritten page this is the difference between drawing a screenful and drawing ten
            // thousand strokes, every frame, for a scroll that shows a few dozen of them. Computed
            // from the transform *here* in the draw scope, for the same reason the transform is read
            // here: scrolling re-runs the draw and nothing above it. See `docs/inkPlan.md` §3.2.
            val visible = matrix.pageWindow(size.width, size.height)
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                // The matrix goes on the canvas, and is *also* passed to the renderer. Passing it
                // alone leaves the geometry untransformed — it draws at stroke coordinates, so ink
                // landed at page-units-as-pixels, ignoring zoom and scroll. The argument is what
                // the renderer measures to pick a mesh detail level for the scale it is drawn at,
                // not what moves it.
                val strokeMatrix = Matrix()
                (liveErasedStrokes ?: currentStrokes).forEach { pageStroke ->
                    val moving = pageStroke.projectionKey in currentSelection?.projections.orEmpty()
                    // A stroke being dragged is exempt: the preview transform moves it, so where it
                    // sits now says nothing about where this frame will put it.
                    if (!moving && visible != null && pageStroke.isOutside(visible)) return@forEach
                    strokeMatrix.set(matrix)
                    if (moving) lassoGesture.applyPreview(strokeMatrix)
                    strokeMatrix.preTranslate(pageStroke.offsetX, pageStroke.offsetY)
                    strokeMatrix.preScale(pageStroke.scaleX, pageStroke.scaleY)
                    val checkpoint = native.save()
                    native.concat(strokeMatrix)
                    renderer.draw(native, pageStroke.stroke, strokeMatrix)
                    native.restoreToCount(checkpoint)
                }
                if (currentLassoing && gestureRevision >= 0) {
                    drawLasso(
                        native, matrix, lassoGesture, currentSelection, lassoColor, lassoHandleFill,
                    )
                }
                currentShaping?.takeIf { shapeRevision >= 0 }?.let { settings ->
                    drawShapePreview(native, matrix, shapeGesture, settings)
                }
                eraseGesture.indicator?.let { indicator ->
                    drawEraserIndicator(native, matrix, indicator)
                }
                // Last, so it lies on top of the ink the way a ruler lies on top of the paper.
                currentRuler?.let { drawRuler(native, matrix, it, rulerPaint) }
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
        }

        if (brush != null || erasing || lassoing || shaping != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { event ->
                        val pastePoint = if (
                            currentHasInkClipboard && fingerDoubleTap.observe(event)
                        ) {
                            val toPage = Matrix().also { currentTransform().invert(it) }
                            event.pagePoint(event.actionIndex, toPage)
                        } else {
                            null
                        }
                        val handled = handleInk(
                            event = event,
                            view = wetView,
                            brush = currentBrush,
                            erasing = currentErasing,
                            lassoing = currentLassoing,
                            shaping = currentShaping,
                            ruler = currentRuler,
                            eraser = currentEraser,
                            strokes = currentStrokes,
                            allowFinger = currentAllowFinger,
                            transform = currentTransform(),
                            onPartialErase = currentOnPartialErase,
                            onObjectErase = currentOnObjectErase,
                            onMoveSelection = currentOnMoveSelection,
                            onResizeSelection = currentOnResizeSelection,
                            liveStroke = liveStroke,
                            livePointer = livePointer,
                            ruledStroke = ruledStroke,
                            setLive = { id, pointer, ruled ->
                                liveStroke = id
                                livePointer = pointer
                                ruledStroke = ruled
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
                            lassoGesture = lassoGesture,
                            shapes = currentShapes,
                            tables = currentTables,
                            equations = currentEquations,
                            images = currentImages,
                            selection = currentSelection,
                            onSelect = currentOnSelect,
                            onMoveShapes = currentOnMoveShapes,
                            onResizeShapes = currentOnResizeShapes,
                            onMoveTables = currentOnMoveTables,
                            onResizeTables = currentOnResizeTables,
                            onMoveEquations = currentOnMoveEquations,
                            onResizeEquations = currentOnResizeEquations,
                            onMoveImages = currentOnMoveImages,
                            onResizeImages = currentOnResizeImages,
                            shapeGesture = shapeGesture,
                            onInsertShape = currentOnInsertShape,
                            touchSlop = viewConfiguration.touchSlop,
                        )
                        pastePoint?.let(currentOnRequestPaste)
                        handled
                    },
            )
        }

        // The object tooltip is raised by `EditorPane` from the page's selection, not here. A shape
        // can be in that selection, and a bar that appeared twice — once per layer — could not
        // describe a loop holding both (AD7).
    }

    DisposableEffect(Unit) {
        onDispose { wetView?.cancelUnfinishedStrokes() }
    }
}

/** Recognises stationary, single-finger double taps without taking drag/pan ownership. */
internal class FingerDoubleTapGesture(
    private val minimumIntervalMillis: Long,
    private val maximumIntervalMillis: Long,
    private val touchSlop: Float,
) {
    private var tracking = false
    private var stayedStill = false
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var firstTapAt = -1L
    private var firstTapX = 0f
    private var firstTapY = 0f

    fun observe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_FINGER) {
                    tracking = false
                    return false
                }
                if (firstTapAt >= 0L && event.eventTime - firstTapAt > maximumIntervalMillis) {
                    firstTapAt = -1L
                }
                tracking = true
                stayedStill = true
                downAt = event.eventTime
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> stayedStill = false
            MotionEvent.ACTION_MOVE -> {
                if (tracking && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    stayedStill = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                if (!stayedStill || event.eventTime - downAt > maximumIntervalMillis) return false
                val interval = event.eventTime - firstTapAt
                val closeEnough = hypot(event.x - firstTapX, event.y - firstTapY) <= touchSlop * 2f
                if (firstTapAt >= 0L && interval in minimumIntervalMillis..maximumIntervalMillis && closeEnough) {
                    reset()
                    return true
                }
                firstTapAt = event.eventTime
                firstTapX = event.x
                firstTapY = event.y
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return false
    }

    fun reset() {
        tracking = false
        stayedStill = false
        firstTapAt = -1L
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
    lassoing: Boolean,
    shaping: ShapeSettings?,
    ruler: Ruler?,
    eraser: EraserSettings,
    strokes: List<PageStroke>,
    shapes: List<Outline.Shape>,
    tables: List<TableBounds>,
    equations: List<Outline.Equation>,
    images: List<Outline.Image>,
    selection: CanvasSelection?,
    onSelect: (CanvasSelection?) -> Unit,
    allowFinger: Boolean,
    transform: Matrix,
    onPartialErase: (Stroke) -> Unit,
    onObjectErase: (Stroke) -> Unit,
    onMoveSelection: (InkLassoMove) -> Unit,
    onResizeSelection: (InkLassoResize) -> Unit,
    onMoveShapes: (Set<String>, Float, Float) -> Unit,
    onResizeShapes: (Set<String>, InkPoint, Float, Float) -> Unit,
    onMoveTables: (Set<String>, Float, Float) -> Unit,
    onResizeTables: (Set<String>, InkPoint, Float, Float) -> Unit,
    onMoveEquations: (Set<String>, Float, Float) -> Unit,
    onResizeEquations: (Set<String>, InkPoint, Float, Float) -> Unit,
    onMoveImages: (Set<String>, Float, Float) -> Unit,
    onResizeImages: (Set<String>, InkPoint, Float, Float) -> Unit,
    liveStroke: InProgressStrokeId?,
    livePointer: Int,
    ruledStroke: Boolean,
    setLive: (InProgressStrokeId?, Int, Boolean) -> Unit,
    pan: CanvasPan,
    velocity: VelocityTracker,
    panning: Boolean,
    lastPan: Pair<Float, Float>,
    setPanning: (Boolean, Pair<Float, Float>) -> Unit,
    eraseGesture: EraseGesture,
    lassoGesture: LassoGesture,
    shapeGesture: ShapeGesture,
    onInsertShape: (InkPoint, InkPoint) -> Unit,
    touchSlop: Float,
): Boolean {
    val index = event.actionIndex
    val toolType = event.getToolType(index)
    // Both count as "a pointer with no pen behind it", which is what the finger setting is really
    // about. A mouse is here for the emulator, though **not** because the emulator reports one:
    // checked on the `Medium_Tablet` AVD 2026-08-06, and `dumpsys input` lists eleven
    // `virtio_input_multi_touch` devices and no mouse at all, so a host click arrives as a finger.
    // Other emulator configurations do deliver a real mouse, and either way the answer is the same.
    val isDirectTouch = toolType == MotionEvent.TOOL_TYPE_FINGER ||
        toolType == MotionEvent.TOOL_TYPE_MOUSE

    // Lasso used to claim every finger drag even in stylus-only mode. That made the page impossible
    // to pan with one hand while the lasso was armed. It follows the same finger setting as ink and
    // the eraser now: stylus always selects, while a disallowed finger moves the page.
    // Ahead of every other mode for the same reason lasso is ahead of ink: it is a tool that owns
    // the whole gesture, and a shape drag must not also start a stroke.
    if (shaping != null) {
        if (isDirectTouch && !allowFinger) {
            return panPage(event, pan, velocity, panning, lastPan, setPanning)
        }
        val toPage = Matrix().also { transform.invert(it) }
        return shapeGesture.handle(event, toPage, touchSlop, onInsertShape)
    }

    if (lassoing) {
        if (isDirectTouch && !allowFinger) {
            return panPage(event, pan, velocity, panning, lastPan, setPanning)
        }
        val toPage = Matrix().also { transform.invert(it) }
        return lassoGesture.handle(
            event = event,
            toPage = toPage,
            strokes = strokes,
            shapes = shapes,
            tables = tables,
            equations = equations,
            images = images,
            selection = selection,
            onSelect = onSelect,
            onMove = onMoveSelection,
            onResize = onResizeSelection,
            onMoveShapes = onMoveShapes,
            onResizeShapes = onResizeShapes,
            onMoveTables = onMoveTables,
            onResizeTables = onResizeTables,
            onMoveEquations = onMoveEquations,
            onResizeEquations = onResizeEquations,
            onMoveImages = onMoveImages,
            onResizeImages = onResizeImages,
        )
    }

    if (view == null) return false

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

    // A ruled stroke is fed rewritten coordinates rather than the ones the hand produced — RD5. The
    // substitution happens here, at the one seam every branch below passes through, so the stroke
    // lifecycle underneath is the same code whether the ruler is out or not.
    val ruled = ruledStroke ||
        (event.actionMasked == MotionEvent.ACTION_DOWN && ruler != null &&
            ruler.engages(event.pagePoint(index, toWorld), Ruler.SNAP_TOLERANCE_DP))
    val drawn = if (ruled && ruler != null) event.snappedTo(ruler, toWorld, transform) else null

    // After the ruler, because the ruler is what decides where a ruled stroke goes and this is what
    // decides where it may not — a straightedge laid across the top of the page must not be able to
    // put ink above it.
    val bounded = (drawn ?: event).clampedToPage(toWorld, transform)

    try {
        return handleInkStroke(
            event = bounded ?: drawn ?: event,
            view = view,
            brush = brush,
            toWorld = toWorld,
            index = index,
            liveStroke = liveStroke,
            livePointer = livePointer,
            ruled = ruled,
            setLive = setLive,
        )
    } finally {
        drawn?.recycle()
        bounded?.recycle()
    }
}

/**
 * The stroke lifecycle, once it is settled which points it is being drawn from.
 *
 * [event] is either the real one or a snapped copy of it; nothing below cares which, which is the
 * point of splitting it out.
 */
private fun handleInkStroke(
    event: MotionEvent,
    view: InProgressStrokesView,
    brush: Brush,
    toWorld: Matrix,
    index: Int,
    liveStroke: InProgressStrokeId?,
    livePointer: Int,
    ruled: Boolean,
    setLive: (InProgressStrokeId?, Int, Boolean) -> Unit,
): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            // A second pointer while drawing is a palm or a pinch, so the stroke is taken back and
            // the gesture handed to the scroll container behind — that is how the page still pans
            // while a pen is in hand.
            if (liveStroke != null) {
                view.cancelStroke(liveStroke, event)
                setLive(null, -1, false)
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
            setLive(view.startStroke(event, pointerId, brush, toWorld), pointerId, ruled)
        }
        MotionEvent.ACTION_MOVE -> {
            val id = liveStroke ?: return false
            view.addToStroke(event, livePointer, id)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            val id = liveStroke ?: return false
            if (event.getPointerId(index) != livePointer) return true
            view.finishStroke(event, livePointer, id)
            setLive(null, -1, false)
        }
        MotionEvent.ACTION_CANCEL -> {
            // Palm rejection lands here, and on a cancelled pointer post-Android 13 also as
            // FLAG_CANCELED on the up. Both mean: that was not a stroke, take it back.
            val id = liveStroke ?: return false
            view.cancelStroke(id, event)
            setLive(null, -1, false)
        }
        else -> return false
    }
    return true
}

/**
 * A copy of this event with its point moved onto the ruler's edge — `docs/rulerPlan.md` RD5.
 *
 * A rewritten `MotionEvent` rather than the `StrokeInput` overloads, because this way the whole
 * stroke path underneath keeps the matrix arrangement that is already known to be right — the one
 * this file records getting wrong twice. Pressure, tilt, orientation, tool type and the pointer id
 * are copied wholesale; only x and y are the ruler's business.
 *
 * **Historical samples are dropped**, and lose nothing: a ruled stroke's path is decided by the
 * ruler rather than by what the hand did between frames, and the points that matter — where it
 * started and where it is now — are both still here.
 *
 * The caller recycles.
 */
private fun MotionEvent.snappedTo(ruler: Ruler, toPage: Matrix, toView: Matrix): MotionEvent {
    val index = actionIndex
    val snapped = ruler.snap(pagePoint(index, toPage))
    val viewPoint = floatArrayOf(snapped.x, snapped.y)
    toView.mapPoints(viewPoint)

    val properties = MotionEvent.PointerProperties().also { getPointerProperties(index, it) }
    val coords = MotionEvent.PointerCoords().also { getPointerCoords(index, it) }
    coords.x = viewPoint[0]
    coords.y = viewPoint[1]

    // One pointer, so the action carries no pointer index and `actionMasked` is the whole of it.
    // Safe because a stroke is single-pointer by construction: a second contact cancels it above.
    return MotionEvent.obtain(
        downTime,
        eventTime,
        actionMasked,
        1,
        arrayOf(properties),
        arrayOf(coords),
        metaState,
        buttonState,
        xPrecision,
        yPrecision,
        deviceId,
        edgeFlags,
        source,
        flags,
    )
}

/**
 * A copy of this event with its point pulled back onto the page, or **null when it is already on it**
 * — [PageBounds].
 *
 * The pen is the one thing on this canvas that can put marks outside it, and it does not need to
 * leave the glass to do it: a stroke begun inside the window keeps receiving moves after the pointer
 * has left it, and those arrive with coordinates behind the origin. That is how a page ends up
 * carrying strokes at a negative y — invisible, unselectable, and unreachable, because neither
 * scroll state goes below zero.
 *
 * There is no fixing it afterwards. A stroke's points are what the pen produced, and translating one
 * back onto the page would move the half that was never off it. So it is stopped here, on the way
 * in: past the wall, the ink piles up against it, which is what a hard edge looks like.
 *
 * **Null for the common case**, and that is the whole reason for the return type: an in-bounds event
 * is passed through untouched and keeps its historical samples, which are most of a fast stroke's
 * fidelity. Only an event that has actually crossed the wall is rebuilt, and only that one loses its
 * history — the same trade [snappedTo] makes, and for the same reason: the samples given up are the
 * ones being pushed into a wall.
 *
 * Single-pointer events only. A second contact cancels the stroke a line later, so there is nothing
 * here worth rebuilding, and the caller's `index` is only valid against a one-pointer copy.
 *
 * The caller recycles.
 */
private fun MotionEvent.clampedToPage(toPage: Matrix, toView: Matrix): MotionEvent? {
    if (pointerCount != 1) return null
    val index = actionIndex
    val point = pagePoint(index, toPage)
    if (point.x >= PageBounds.MIN_X && point.y >= PageBounds.MIN_Y) return null

    val viewPoint = floatArrayOf(PageBounds.clampX(point.x), PageBounds.clampY(point.y))
    toView.mapPoints(viewPoint)

    val properties = MotionEvent.PointerProperties().also { getPointerProperties(index, it) }
    val coords = MotionEvent.PointerCoords().also { getPointerCoords(index, it) }
    coords.x = viewPoint[0]
    coords.y = viewPoint[1]

    return MotionEvent.obtain(
        downTime,
        eventTime,
        actionMasked,
        1,
        arrayOf(properties),
        arrayOf(coords),
        metaState,
        buttonState,
        xPrecision,
        yPrecision,
        deviceId,
        edgeFlags,
        source,
        flags,
    )
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
        // A second contact is a pinch, the same reading every tool gesture here gives it. Left
        // running, this would keep dragging the page from the first pointer alone and fight the
        // zoom for it. Not a release, so no fling — the fingers have not gone anywhere.
        MotionEvent.ACTION_POINTER_DOWN -> {
            if (!panning) return false
            setPanning(false, 0f to 0f)
            return false
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

/**
 * The part of the page this view is showing, in page units, or null if the transform cannot be
 * inverted — a zoom of zero, which draws nothing anyway.
 *
 * Two objects a frame to decide the fate of thousands, rather than the reverse.
 */
private fun Matrix.pageWindow(viewWidth: Float, viewHeight: Float): android.graphics.RectF? {
    val inverse = Matrix()
    if (!invert(inverse)) return null
    val window = android.graphics.RectF(0f, 0f, viewWidth, viewHeight)
    inverse.mapRect(window)
    // The mesh's own box is tight, and the renderer feathers the edge of it. A dp of slack costs a
    // stroke that was going to be culled anyway and removes any question of a clipped edge.
    window.inset(-CULL_MARGIN_DP, -CULL_MARGIN_DP)
    return window
}

/**
 * Whether none of this stroke can be seen in [window].
 *
 * A stroke with no geometry left — cut away entirely by an eraser — reports no bounds, and there is
 * nothing to draw for it either way.
 */
private fun PageStroke.isOutside(window: android.graphics.RectF): Boolean {
    val bounds = pageBounds ?: return true
    return bounds.right < window.left || bounds.left > window.right ||
        bounds.bottom < window.top || bounds.top > window.bottom
}

private const val CULL_MARGIN_DP = 2f

/** Draws the free-form loop and the bounds of the objects it currently owns. */
private fun drawLasso(
    canvas: android.graphics.Canvas,
    pageToView: Matrix,
    gesture: LassoGesture,
    selection: CanvasSelection?,
    color: Int,
    /** The fill inside a corner handle — `colorScheme.surface`, as every object layer uses. */
    handleColor: Int,
) {
    val values = FloatArray(9)
    pageToView.getValues(values)
    val scale = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]).coerceAtLeast(0.001f)
    val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.5f / scale
        pathEffect = DashPathEffect(floatArrayOf(6f / scale, 4f / scale), 0f)
    }
    val checkpoint = canvas.save()
    canvas.concat(pageToView)
    val points = gesture.drawingPath()
    if (points.isNotEmpty()) {
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, tracePaint)
    }
    gesture.previewBounds(selection)?.let { bounds ->
        // **Page units are dp**, so the chrome is drawn from [SelectionChrome] as it stands, with no
        // division by [scale] — those are the same numbers `ShapeLayer`, `EquationLayer` and
        // `ImageLayer` hand to `Dp.toPx()`, and reading them here is what makes a lassoed object's
        // selection *the same* affordance as a tapped one rather than one that resembles it (AD7).
        //
        // Dividing by the scale was the bug: it pinned the chrome to a fixed number of **device
        // pixels**, which is the same number divided by the density — so a lassoed object's handles
        // came out at a third of the radius a tapped one's have on a 3× screen, and shrank further
        // against the page with every step of zoom, where a tapped object's grow with it.
        val padding = SelectionChrome.PADDING.value
        val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = SelectionChrome.STROKE.value
        }
        val left = bounds.left - padding
        val top = bounds.top - padding
        val right = bounds.right + padding
        val bottom = bounds.bottom + padding
        canvas.drawRect(left, top, right, bottom, selectionPaint)
        val handleRadius = SelectionChrome.HANDLE_RADIUS.value
        val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = handleColor
            style = Paint.Style.FILL
        }
        listOf(
            left to top,
            right to top,
            right to bottom,
            left to bottom,
        ).forEach { (x, y) ->
            canvas.drawCircle(x, y, handleRadius, handleFill)
            canvas.drawCircle(x, y, handleRadius, selectionPaint)
        }
    }
    canvas.restoreToCount(checkpoint)
}

/**
 * The shape under the pointer, mid-drag.
 *
 * Drawn from the same [trace] call the commit will run, so the preview cannot show one thing and
 * land another — that is the whole reason SD6 has the picker chips draw themselves too. Everything
 * here is in page space, so the border width is in page units and scales with zoom exactly as the
 * committed ink will.
 */
private fun drawShapePreview(
    canvas: android.graphics.Canvas,
    pageToView: Matrix,
    gesture: ShapeGesture,
    settings: ShapeSettings,
) {
    val start = gesture.start ?: return
    val end = gesture.current
    val tracing = trace(settings.kind, start.x, start.y, end.x, end.y)
    val width = settings.borderWidth.toFloat()

    fun paintFor(lineType: LineType) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = settings.borderColorArgb
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = when (lineType) {
            LineType.Solid -> null
            LineType.Dashed -> DashPathEffect(floatArrayOf(width * 2.6f, width * 1.8f), 0f)
            LineType.Dotted -> DashPathEffect(floatArrayOf(0.01f, width * 2f), 0f)
        }
    }

    val checkpoint = canvas.save()
    canvas.concat(pageToView)
    val visible = paintFor(settings.lineType)
    val occluded = paintFor(LineType.Dotted)
    tracing.solid.forEach { canvas.drawPath(it.toPath(), visible) }
    tracing.hidden.forEach { canvas.drawPath(it.toPath(), occluded) }
    canvas.restoreToCount(checkpoint)
}

private fun FloatArray.toPath(): Path = Path().apply {
    if (size < 4) return@apply
    moveTo(this@toPath[0], this@toPath[1])
    for (index in 2 until size step 2) lineTo(this@toPath[index], this@toPath[index + 1])
}

internal data class EraserIndicator(val center: InkPoint, val diameterDp: Float)

/** Drawn in page space so zoom changes the cursor by exactly the same amount as the erase mask. */
private fun drawEraserIndicator(
    canvas: android.graphics.Canvas,
    pageToView: Matrix,
    indicator: EraserIndicator,
) {
    val values = FloatArray(9)
    pageToView.getValues(values)
    val scale = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]).coerceAtLeast(0.001f)
    val radius = indicator.diameterDp / 2f
    val outlineWidth = 2f / scale
    val drawnRadius = (radius - outlineWidth / 2f).coerceAtLeast(outlineWidth / 2f)
    val checkpoint = canvas.save()
    canvas.concat(pageToView)
    canvas.drawCircle(
        indicator.center.x,
        indicator.center.y,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x28FFFFFF
            style = Paint.Style.FILL
        },
    )
    canvas.drawCircle(
        indicator.center.x,
        indicator.center.y,
        drawnRadius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = outlineWidth
        },
    )
    canvas.drawCircle(
        indicator.center.x,
        indicator.center.y,
        drawnRadius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF2FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f / scale
        },
    )
    canvas.restoreToCount(checkpoint)
}

/** Applies the in-progress mask for display only; persistence still happens once on pointer-up. */
internal fun List<PageStroke>.previewErase(mask: Stroke, mode: EraserMode): List<PageStroke> {
    val targets = targetsFor(mask)
    if (targets.isEmpty()) return this
    return when (mode) {
        EraserMode.Normal -> subtract(mask, targets)
        EraserMode.Object -> eraseObjects(mask, targets)
    }
}

/**
 * Owns one drag that becomes a shape — `docs/inkPlan.md` §5.4.
 *
 * Deliberately not routed through `InProgressStrokesView` like freehand ink is. A shape is not
 * captured, it is *constructed*: the front buffer renders one continuous stroke where a cube is
 * twelve, and there is nothing about a traced path that needs low-latency wet rendering. So this
 * owns two page-space points and the overlay draws the preview from them, the same way it draws the
 * lasso.
 */
internal class ShapeGesture {
    private var pointerId: Int = -1

    /** Null while idle, which is also what says whether there is a preview to draw. */
    var start by mutableStateOf<InkPoint?>(null)
        private set
    var current by mutableStateOf(InkPoint(0f, 0f))
        private set
    var renderRevision by mutableIntStateOf(0)
        private set

    fun handle(
        event: MotionEvent,
        toPage: Matrix,
        touchSlop: Float,
        onInsert: (InkPoint, InkPoint) -> Unit,
    ): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(event.actionIndex)
                // Clamped as it is captured, so the preview drawn from these two points and the
                // shape inserted from them are held to the origin corner by one line rather than
                // two that could disagree — [PageBounds].
                val point = PageBounds.clamp(event.pagePoint(event.actionIndex, toPage))
                start = point
                current = point
                invalidateDraw()
                true
            }
            // A second contact is a palm or a pinch, never a wider shape.
            MotionEvent.ACTION_POINTER_DOWN -> {
                clear()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0 || start == null) return false
                current = PageBounds.clamp(event.pagePoint(index, toPage))
                invalidateDraw()
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val began = start ?: return false
                if (event.getPointerId(event.actionIndex) != pointerId) return false
                val ended = PageBounds.clamp(event.pagePoint(event.actionIndex, toPage))
                clear()
                // A tap is not a mis-drag. Dropping a default-sized shape where it landed is what
                // keeps the tool from feeling dead when someone taps instead of dragging, and the
                // corner handles are there to resize it.
                if (hypot(ended.x - began.x, ended.y - began.y) <= toPageLength(toPage, touchSlop)) {
                    // Nudged onto the page rather than clipped against it: a drag says how big the
                    // shape is and stops at the wall, but a tap only says *where*, so a tap near
                    // the corner should still get a whole shape.
                    val corner = PageBounds.correctionFor(
                        began.x - DEFAULT_SHAPE_WIDTH / 2f,
                        began.y - DEFAULT_SHAPE_HEIGHT / 2f,
                    )
                    val centre = InkPoint(began.x + corner.x, began.y + corner.y)
                    onInsert(
                        InkPoint(centre.x - DEFAULT_SHAPE_WIDTH / 2f, centre.y - DEFAULT_SHAPE_HEIGHT / 2f),
                        InkPoint(centre.x + DEFAULT_SHAPE_WIDTH / 2f, centre.y + DEFAULT_SHAPE_HEIGHT / 2f),
                    )
                } else {
                    onInsert(began, ended)
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                clear()
                true
            }
            else -> false
        }
    }

    fun clear() {
        pointerId = -1
        start = null
        invalidateDraw()
    }

    private fun invalidateDraw() {
        renderRevision = if (renderRevision == Int.MAX_VALUE) 0 else renderRevision + 1
    }

    /** View-pixel slop expressed in page units, so the tap threshold does not change with zoom. */
    private fun toPageLength(toPage: Matrix, pixels: Float): Float {
        val values = FloatArray(9)
        toPage.getValues(values)
        return pixels * hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
    }
}

/** What a tap drops, in page units. A shape you can see and grab, not a speck. */
private const val DEFAULT_SHAPE_WIDTH = 120f
private const val DEFAULT_SHAPE_HEIGHT = 80f

/**
 * Owns one lasso loop and the live move or corner-resize that follows it — but **not** the selection.
 *
 * What is selected is a page-level fact ([CanvasSelection], AD7) held by `EditorPane`, because a
 * shape can be in it and a shape is not ink. This class holds only what is true for the length of one
 * gesture: which corner is being dragged, how far, and the loop being traced. It reads the current
 * selection as an argument and reports a new one through a callback.
 *
 * The live transform is therefore readable by every layer that draws a selected object — the ink
 * canvas here and `ShapeLayer` inside the zoom — from one place, in page units, rather than each
 * keeping its own idea of how far the finger has travelled.
 */
internal class LassoGesture {
    private enum class Mode { Idle, Drawing, Moving, Resizing }
    private enum class Corner { TopLeft, TopRight, BottomRight, BottomLeft }

    private var mode by mutableStateOf(Mode.Idle)
    private var pointerId: Int = -1
    private var start = InkPoint(0f, 0f)
    private val path = mutableStateListOf<InkPoint>()
    var preview by mutableStateOf(InkPoint(0f, 0f))
        private set
    private var resizeAnchor = InkPoint(0f, 0f)
    private var resizeStart = InkPoint(0f, 0f)
    private var resizeScale by mutableStateOf(InkPoint(1f, 1f))

    /**
     * What the selection occupied when the gesture began, held so the origin corner can be enforced
     * against it — [PageBounds].
     *
     * Captured on the down rather than read from the live selection, because a resize measures a
     * scale against the geometry it *started* from: reading the selection as it is being previewed
     * would compound the limit frame by frame and stall the drag short of the wall.
     */
    private var startBounds: InkBounds? = null
    var renderRevision by mutableIntStateOf(0)
        private set

    /** True while a move or resize is being dragged, so a draw knows to apply [applyPreview]. */
    val isTransforming: Boolean get() = mode == Mode.Moving || mode == Mode.Resizing

    fun drawingPath(): List<InkPoint> = if (mode == Mode.Drawing) path else emptyList()

    /** The selection's rectangle with the live gesture folded in, which is what gets drawn. */
    fun previewBounds(selection: CanvasSelection?): InkBounds? = selection?.bounds?.let { bounds ->
        when (mode) {
            Mode.Moving -> bounds.translated(preview.x, preview.y)
            Mode.Resizing -> bounds.scaled(resizeAnchor, resizeScale.x, resizeScale.y)
            else -> bounds
        }
    }

    fun previewBoundsInView(selection: CanvasSelection?, pageToView: Matrix): android.graphics.RectF? =
        previewBounds(selection)?.let { bounds ->
            android.graphics.RectF(bounds.left, bounds.top, bounds.right, bounds.bottom).also {
                pageToView.mapRect(it)
            }
        }

    /** Appends the live gesture transform before an object's own committed page transform. */
    fun applyPreview(matrix: Matrix) {
        when (mode) {
            Mode.Moving -> matrix.preTranslate(preview.x, preview.y)
            Mode.Resizing -> {
                matrix.preTranslate(resizeAnchor.x, resizeAnchor.y)
                matrix.preScale(resizeScale.x, resizeScale.y)
                matrix.preTranslate(-resizeAnchor.x, -resizeAnchor.y)
            }
            else -> Unit
        }
    }

    /** The same transform for a layer that works in page units directly rather than in a matrix. */
    fun previewOffset(): InkPoint = if (mode == Mode.Moving) preview else InkPoint(0f, 0f)
    fun previewScale(): InkPoint = if (mode == Mode.Resizing) resizeScale else InkPoint(1f, 1f)
    fun previewAnchor(): InkPoint = resizeAnchor

    /** Abandons the live gesture. The selection is the caller's to clear, not this class's. */
    fun clear() {
        mode = Mode.Idle
        pointerId = -1
        path.clear()
        preview = InkPoint(0f, 0f)
        resizeScale = InkPoint(1f, 1f)
        startBounds = null
        invalidateDraw()
    }

    /**
     * @param selection what is currently selected, across kinds. Read, never written.
     * @param onSelect a new selection, or null when the loop caught nothing. Called on the up.
     * @param onMove the ink half of a finished move. [onMoveShapes] is the shape half.
     */
    fun handle(
        event: MotionEvent,
        toPage: Matrix,
        strokes: List<PageStroke>,
        shapes: List<Outline.Shape>,
        tables: List<TableBounds> = emptyList(),
        equations: List<Outline.Equation> = emptyList(),
        images: List<Outline.Image> = emptyList(),
        selection: CanvasSelection?,
        onSelect: (CanvasSelection?) -> Unit,
        onMove: (InkLassoMove) -> Unit,
        onResize: (InkLassoResize) -> Unit,
        onMoveShapes: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
        onResizeShapes: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
        onMoveTables: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
        onResizeTables: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
        onMoveEquations: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
        onResizeEquations: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
        onMoveImages: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
        onResizeImages: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
    ): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(event.actionIndex)
                val point = event.pagePoint(event.actionIndex, toPage)
                val corner = selection?.bounds?.cornerNear(point)
                startBounds = selection?.bounds
                if (selection != null && corner != null) {
                    mode = Mode.Resizing
                    resizeStart = corner.point(selection.bounds)
                    resizeAnchor = corner.opposite().point(selection.bounds)
                    resizeScale = InkPoint(1f, 1f)
                    preview = InkPoint(0f, 0f)
                } else if (selection != null && selection.bounds.contains(point)) {
                    mode = Mode.Moving
                    start = point
                    preview = InkPoint(0f, 0f)
                } else {
                    mode = Mode.Drawing
                    onSelect(null)
                    path.clear()
                    path += point
                    preview = InkPoint(0f, 0f)
                }
                invalidateDraw()
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelActiveGesture()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0 || mode == Mode.Idle) return false
                when (mode) {
                    Mode.Drawing -> appendSamples(event, index, toPage)
                    Mode.Moving -> {
                        preview = travelTo(event.pagePoint(index, toPage))
                        invalidateDraw()
                    }
                    Mode.Resizing -> updateResize(event.pagePoint(index, toPage))
                    Mode.Idle -> Unit
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != pointerId || mode == Mode.Idle) return false
                when (mode) {
                    Mode.Drawing -> {
                        appendSamples(event, event.actionIndex, toPage)
                        onSelect(
                            selectWithLasso(
                                strokes = strokes,
                                shapes = shapes,
                                tables = tables,
                                equations = equations,
                                images = images,
                                path = path.toList(),
                                edgeTolerance = lassoEdgeTolerance(toPage),
                            ),
                        )
                        path.clear()
                    }
                    Mode.Moving -> {
                        // The same clamp the preview was drawn with, so what is written down is
                        // what the finger was shown — see [travelTo].
                        val delta = travelTo(event.pagePoint(event.actionIndex, toPage))
                        if (selection != null && (delta.x != 0f || delta.y != 0f)) {
                            // One gesture, one payload per kind: ink persists a move for replay,
                            // a shape translates its own segments. AD7's second consequence —
                            // "move and resize are transforms on an object, not on its
                            // representation" — is why the gesture states the delta once and lets
                            // each kind apply it its own way.
                            selection.inkHalf()?.let { ink ->
                                onMove(
                                    InkLassoMove(
                                        path = ink.path,
                                        targetIds = ink.targetIds,
                                        projections = ink.projections,
                                        dx = delta.x,
                                        dy = delta.y,
                                    ),
                                )
                            }
                            if (selection.shapeIds.isNotEmpty()) {
                                onMoveShapes(selection.shapeIds, delta.x, delta.y)
                            }
                            if (selection.tableIds.isNotEmpty()) {
                                onMoveTables(selection.tableIds, delta.x, delta.y)
                            }
                            if (selection.equationIds.isNotEmpty()) {
                                onMoveEquations(selection.equationIds, delta.x, delta.y)
                            }
                            if (selection.imageIds.isNotEmpty()) {
                                onMoveImages(selection.imageIds, delta.x, delta.y)
                            }
                            onSelect(selection.translated(delta.x, delta.y))
                        }
                    }
                    Mode.Resizing -> {
                        updateResize(event.pagePoint(event.actionIndex, toPage))
                        val scale = resizeScale
                        if (selection != null && (scale.x != 1f || scale.y != 1f)) {
                            selection.inkHalf()?.let { ink ->
                                onResize(
                                    InkLassoResize(
                                        path = ink.path,
                                        targetIds = ink.targetIds,
                                        projections = ink.projections,
                                        anchor = resizeAnchor,
                                        scaleX = scale.x,
                                        scaleY = scale.y,
                                    ),
                                )
                            }
                            if (selection.shapeIds.isNotEmpty()) {
                                onResizeShapes(selection.shapeIds, resizeAnchor, scale.x, scale.y)
                            }
                            if (selection.tableIds.isNotEmpty()) {
                                onResizeTables(selection.tableIds, resizeAnchor, scale.x, scale.y)
                            }
                            if (selection.equationIds.isNotEmpty()) {
                                onResizeEquations(
                                    selection.equationIds, resizeAnchor, scale.x, scale.y,
                                )
                            }
                            if (selection.imageIds.isNotEmpty()) {
                                onResizeImages(selection.imageIds, resizeAnchor, scale.x, scale.y)
                            }
                            onSelect(selection.scaled(resizeAnchor, scale.x, scale.y))
                        }
                    }
                    Mode.Idle -> Unit
                }
                mode = Mode.Idle
                pointerId = -1
                preview = InkPoint(0f, 0f)
                resizeScale = InkPoint(1f, 1f)
                startBounds = null
                invalidateDraw()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelActiveGesture()
                true
            }
            else -> false
        }
    }

    private fun appendSamples(event: MotionEvent, index: Int, toPage: Matrix) {
        repeat(event.historySize) { history ->
            append(event.pagePoint(index, toPage, history))
        }
        append(event.pagePoint(index, toPage))
    }

    private fun append(point: InkPoint) {
        val last = path.lastOrNull()
        if (last == null || hypot(point.x - last.x, point.y - last.y) >= 0.5f) {
            path += point
            invalidateDraw()
        }
    }

    /**
     * How far the selection may follow the finger, which is not always how far the finger has gone.
     *
     * The page has no left or top edge to scroll to, so a selection dragged past the origin corner
     * would be put somewhere it could never be reached again — [PageBounds]. Clamping here rather
     * than on the lift is what keeps the preview honest: the ink under the finger stops at the wall
     * instead of following it out and snapping back when it is let go.
     */
    private fun travelTo(point: InkPoint): InkPoint {
        val raw = InkPoint(point.x - start.x, point.y - start.y)
        val bounds = startBounds ?: return raw
        return PageBounds.clampTranslation(bounds, raw.x, raw.y)
    }

    private fun updateResize(point: InkPoint) {
        val width = resizeStart.x - resizeAnchor.x
        val height = resizeStart.y - resizeAnchor.y
        if (width == 0f || height == 0f) return
        val wanted = InkPoint(
            ((point.x - resizeAnchor.x) / width).coerceAtLeast(MIN_RESIZE_SCALE),
            ((point.y - resizeAnchor.y) / height).coerceAtLeast(MIN_RESIZE_SCALE),
        )
        // A corner dragged outward takes the opposite edges towards the origin, so the same wall
        // applies here as to a move — see [travelTo].
        resizeScale = startBounds
            ?.let { PageBounds.clampScale(it, resizeAnchor, wanted.x, wanted.y) }
            ?: wanted
        invalidateDraw()
    }

    private fun cancelActiveGesture() {
        if (mode == Mode.Drawing) path.clear()
        mode = Mode.Idle
        pointerId = -1
        preview = InkPoint(0f, 0f)
        resizeScale = InkPoint(1f, 1f)
        startBounds = null
        invalidateDraw()
    }

    private fun invalidateDraw() {
        renderRevision = if (renderRevision == Int.MAX_VALUE) 0 else renderRevision + 1
    }

    private fun InkPoint.scaled(anchor: InkPoint, x: Float, y: Float): InkPoint = InkPoint(
        anchor.x + (this.x - anchor.x) * x,
        anchor.y + (this.y - anchor.y) * y,
    )

    private fun lassoEdgeTolerance(toPage: Matrix): Float {
        val values = FloatArray(9)
        toPage.getValues(values)
        return LASSO_EDGE_TOLERANCE_PX * hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
    }

    /**
     * The corner handle under the point, or null.
     *
     * Measured from where the handle is **drawn** — a corner of the padded rectangle, not of the
     * bounds themselves — because that is the disc a finger aims at. The two are only
     * [SelectionChrome.PADDING] apart, but a reach measured from the wrong one of them is
     * off-centre by that much in both axes at once, and every dp of that comes off the far side of
     * the target. The resize itself still measures against the raw bounds: see [handle], where
     * [Corner.point] is called without an outset for the anchor.
     *
     * [SelectionChrome.HANDLE_REACH] is a dp value read as page units, which is what page units
     * are — the same reading `ShapeLayer.handleNear` makes of it, and the reason a handle grabs the
     * same way whichever layer drew it (AD7). It was 10 *device pixels* before, so a selection was
     * markedly harder to grab on a dense screen than on a coarse one, and harder again the further
     * the page was zoomed out.
     */
    private fun InkBounds.cornerNear(point: InkPoint): Corner? {
        val outset = SelectionChrome.PADDING.value
        return Corner.entries.minByOrNull { corner ->
            val handle = corner.point(this, outset)
            hypot(point.x - handle.x, point.y - handle.y)
        }?.takeIf { corner ->
            val handle = corner.point(this, outset)
            hypot(point.x - handle.x, point.y - handle.y) <= SelectionChrome.HANDLE_REACH.value
        }
    }

    /** A corner of the bounds, or of the chrome around them when [outset] is the chrome's padding. */
    private fun Corner.point(bounds: InkBounds, outset: Float = 0f): InkPoint = when (this) {
        Corner.TopLeft -> InkPoint(bounds.left - outset, bounds.top - outset)
        Corner.TopRight -> InkPoint(bounds.right + outset, bounds.top - outset)
        Corner.BottomRight -> InkPoint(bounds.right + outset, bounds.bottom + outset)
        Corner.BottomLeft -> InkPoint(bounds.left - outset, bounds.bottom + outset)
    }

    private fun Corner.opposite(): Corner = when (this) {
        Corner.TopLeft -> Corner.BottomRight
        Corner.TopRight -> Corner.BottomLeft
        Corner.BottomRight -> Corner.TopLeft
        Corner.BottomLeft -> Corner.TopRight
    }

    private companion object {
        const val LASSO_EDGE_TOLERANCE_PX = 5f
        const val MIN_RESIZE_SCALE = 0.12f
    }
}

private fun MotionEvent.pagePoint(index: Int, toPage: Matrix, history: Int? = null): InkPoint {
    val point = if (history == null) {
        floatArrayOf(getX(index), getY(index))
    } else {
        floatArrayOf(getHistoricalX(index, history), getHistoricalY(index, history))
    }
    toPage.mapPoints(point)
    return InkPoint(point[0], point[1])
}

/** Accumulates one eraser drag while exposing its mask and size cursor for live rendering. */
internal class EraseGesture {
    private var inputs: MutableStrokeInputBatch? = null
    private var pointerId: Int = -1
    private var startTimeMillis: Long = 0L
    private var lastElapsedMillis: Long = -1L
    private var lastX: Float = Float.NaN
    private var lastY: Float = Float.NaN
    private var awaitingCommit = false
    var previewMask by mutableStateOf<Stroke?>(null)
        private set
    var indicator by mutableStateOf<EraserIndicator?>(null)
        private set

    fun handle(
        event: MotionEvent,
        toWorld: Matrix,
        sizeDp: Float,
        onFinished: (Stroke) -> Unit,
    ): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetInput()
                awaitingCommit = false
                previewMask = null
                pointerId = event.getPointerId(event.actionIndex)
                startTimeMillis = event.eventTime
                inputs = MutableStrokeInputBatch()
                addSamples(event, toWorld, sizeDp)
                updatePreview(sizeDp)
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second contact is a palm or pan gesture, not a wider eraser.
                clear()
                false
            }
            MotionEvent.ACTION_MOVE -> {
                if (inputs == null) return false
                addSamples(event, toWorld, sizeDp)
                updatePreview(sizeDp)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (inputs == null || event.getPointerId(event.actionIndex) != pointerId) return false
                addSamples(event, toWorld, sizeDp)
                updatePreview(sizeDp)
                val finished = previewMask
                awaitingCommit = finished != null
                // Touch has left the surface. A hovering stylus/mouse will immediately send a hover
                // event and restore the cursor at its real position.
                resetInput()
                if (finished != null) onFinished(finished)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                clear()
                true
            }
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                indicator = EraserIndicator(event.pagePoint(event.actionIndex, toWorld), sizeDp)
                true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                indicator = null
                true
            }
            else -> false
        }
    }

    /** Drops a released preview once the committed stroke list arrives from the view model. */
    fun reconcileCommittedStrokes() {
        if (!awaitingCommit) return
        awaitingCommit = false
        previewMask = null
    }

    fun clear() {
        awaitingCommit = false
        previewMask = null
        resetInput()
    }

    private fun updatePreview(sizeDp: Float) {
        val batch = inputs?.toImmutable() ?: return
        if (!batch.isEmpty()) previewMask = InkCodec.eraseMask(batch, sizeDp)
    }

    private fun addSamples(event: MotionEvent, toWorld: Matrix, sizeDp: Float) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        repeat(event.historySize) { history ->
            addPoint(
                event.getHistoricalX(index, history),
                event.getHistoricalY(index, history),
                event.getHistoricalEventTime(history),
                toWorld,
                sizeDp,
            )
        }
        addPoint(event.getX(index), event.getY(index), event.eventTime, toWorld, sizeDp)
    }

    private fun addPoint(
        viewX: Float,
        viewY: Float,
        eventTime: Long,
        toWorld: Matrix,
        sizeDp: Float,
    ) {
        val point = floatArrayOf(viewX, viewY)
        toWorld.mapPoints(point)
        indicator = EraserIndicator(InkPoint(point[0], point[1]), sizeDp)
        if (point[0] == lastX && point[1] == lastY) return
        val elapsed = (eventTime - startTimeMillis).coerceAtLeast(lastElapsedMillis + 1)
        inputs?.add(InputToolType.UNKNOWN, point[0], point[1], elapsed)
        lastElapsedMillis = elapsed
        lastX = point[0]
        lastY = point[1]
    }

    private fun resetInput(hideIndicator: Boolean = true) {
        inputs = null
        pointerId = -1
        startTimeMillis = 0L
        lastElapsedMillis = -1L
        lastX = Float.NaN
        lastY = Float.NaN
        if (hideIndicator) indicator = null
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
