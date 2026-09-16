package com.vivenotes.ui.editor

import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.HapticFeedbackConstants
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
import com.vivenotes.ink.TAP_REACH
import com.vivenotes.ink.lineShape
import com.vivenotes.ink.withEndOnPage
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.InkLassoMove
import com.vivenotes.ink.InkLassoResize
import com.vivenotes.ink.InkLassoSelection
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageBounds
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.CanvasInkPainter
import com.vivenotes.ink.Ruler
import com.vivenotes.ink.RulerSide
import com.vivenotes.ink.TableBounds
import com.vivenotes.ink.pageBounds
import com.vivenotes.ink.projectionKey
import com.vivenotes.ink.selectByTap
import com.vivenotes.ink.selectWithLasso
import com.vivenotes.ink.targetsFor
import com.vivenotes.ink.subtract
import com.vivenotes.ink.eraseObjects
import com.vivenotes.model.Outline
import com.vivenotes.model.PageSpace
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.ink.ShapeEnd
import com.vivenotes.model.ink.StraightLineFit
import com.vivenotes.model.ink.endNear
import com.vivenotes.model.ink.ends
import com.vivenotes.model.ink.trace
import com.vivenotes.ui.theme.LocalCanvasColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.abs
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
 * Sits outside the zoom. `Zoomed` scales the page through a `graphicsLayer`, and scaling a
 * front-buffered surface renders wet ink at the layer's resolution and then stretches it — soft
 * while drawing, snapping sharp on release. So the overlay covers the viewport at 1:1 device scale
 * and is handed the page → view transform instead, which is what `InProgressStrokesView.startStroke`
 * takes two matrices for. Strokes are captured and stored in page units at any zoom.
 *
 * [pageToView] is a lambda called at draw and event time rather than a captured value: reading the
 * scroll position during composition would recompose this on every scrolled pixel.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun InkOverlay(
    strokes: List<PageStroke>,
    /** Shapes on the page, so a lasso loop can take one. */
    shapes: List<Outline.Shape> = emptyList(),
    /**
     * The tables, as rectangles the canvas measured — see [TableBounds] for why the model's own
     * height will not do here.
     */
    tables: List<TableBounds> = emptyList(),
    /** Equations on the page, which a loop takes by their box like a table. */
    equations: List<Outline.Equation> = emptyList(),
    /** Pictures, taken by their frame for the reason an equation is taken by its box. */
    images: List<Outline.Image> = emptyList(),
    /**
     * What is selected, across kinds. Owned by the page rather than this overlay: a shape can be in
     * it, and `ShapeLayer` has to draw the same selection. See [CanvasSelection].
     */
    selection: CanvasSelection? = null,
    /** Brief, non-interactive emphasis for a handwriting search result. */
    searchHighlight: InkBounds? = null,
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
     * Whether Insert Space is in hand.
     *
     * A plain flag rather than a settings object like [shaping], because the tool has nothing to
     * configure: the drag says where the line is, which way it runs and how far it goes.
     */
    insertingSpace: Boolean = false,
    /**
     * The ruler lying on the page, or null when it is away.
     *
     * Drawn here because this canvas is composed in every tool state, and applied here because the
     * snapping has to reach the wet stroke rather than the finished one. Moving it lives on an
     * ancestor — see `detectRulerDrag`.
     */
    ruler: Ruler? = null,
    eraser: EraserSettings,
    /** Whether a finger — or, on an emulator, a mouse — may draw as well as a stylus. */
    allowFinger: Boolean,
    /** Page units (dp) to view pixels: scale by zoom and density, then subtract the scroll. */
    pageToView: () -> Matrix,
    onStrokeFinished: (Stroke) -> Unit,
    /**
     * Whether the pen in hand turns a held, straight-enough stroke into a line —
     * `com.vivenotes.data.PenPreset.holdForStraightLine`.
     *
     * A flag rather than the pen itself: what the line is drawn like is read where the object is
     * made, so the overlay never needs to know about pen settings it would only forward.
     */
    straightenOnHold: Boolean = false,
    /**
     * One finished hold: the two ends of the line, in page units. The wet stroke has already been
     * cancelled by the time this is called, so a caller that ignores it loses the mark.
     */
    onStraightenStroke: (InkPoint, InkPoint) -> Unit = { _, _ -> },
    onInsertShape: (InkPoint, InkPoint) -> Unit = { _, _ -> },
    /** One completed Insert Space drag. Never fired for a tap — see [InsertSpaceGesture]. */
    onInsertSpace: (SpaceCut) -> Unit = {},
    onPartialErase: (Stroke) -> Unit,
    onObjectErase: (Stroke) -> Unit,
    onMoveSelection: (InkLassoMove) -> Unit,
    onResizeSelection: (InkLassoResize) -> Unit = {},
    onMoveShapes: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeShapes: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
    /** One end of a lassoed line, in place of the corner resize it has no corners for. */
    onMoveShapeEnd: (String, Boolean, Float, Float) -> Unit = { _, _, _, _ -> },
    onMoveTables: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeTables: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
    onMoveEquations: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeEquations: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
    onMoveImages: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    onResizeImages: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
    onDeleteSelection: (InkLassoSelection) -> Unit = {},
    hasClipboard: Boolean = false,
    onRequestPaste: (InkPoint) -> Unit = {},
    onRecolorSelection: (Set<String>, Int) -> Unit = { _, _ -> },
    onGroupSelection: (Set<String>) -> Unit = {},
    onUngroupSelection: (Set<String>) -> Unit = {},
    /**
     * Pans the page. The overlay owns this because it owns the gesture: a hit pointer node blocks
     * its siblings from seeing the event, so declining a touch is not enough to hand it to the
     * scroll container underneath.
     *
     * One finger only. Two are a pinch, owned by `detectPinchZoom` on an ancestor of this whole
     * pane, so a second contact ends the pan here rather than dragging from the first pointer alone.
     */
    pan: CanvasPan = NoPan,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    var wetView by remember { mutableStateOf<InProgressStrokesView?>(null) }

    // What automatic ink resolves to on this canvas. Strokes drawn with the automatic pen follow the
    // page they are on, as the text beside them does; Switch Background used to leave them at
    // whatever the canvas was when they were drawn, so a flip meant white ink on white paper.
    //
    // The painter is consulted at the draw and nowhere else: everything below holds the stored
    // strokes, because a stroke's identity is what selection, erase and recognition are keyed on.
    // See [CanvasInkPainter].
    val canvasInkArgb = LocalCanvasColors.current.text.toArgb()
    val inkPainter = remember(strokes, canvasInkArgb) { CanvasInkPainter(canvasInkArgb) }

    // Read inside callbacks that outlive the composition that created them.
    val currentBrush by rememberUpdatedState(brush)
    val currentStrokes by rememberUpdatedState(strokes)
    val currentErasing by rememberUpdatedState(erasing)
    val currentLassoing by rememberUpdatedState(lassoing)
    val currentShaping by rememberUpdatedState(shaping)
    val currentInsertingSpace by rememberUpdatedState(insertingSpace)
    val currentOnInsertSpace by rememberUpdatedState(onInsertSpace)
    val currentRuler by rememberUpdatedState(ruler)
    val currentEraser by rememberUpdatedState(eraser)
    val currentAllowFinger by rememberUpdatedState(allowFinger)
    val currentTransform by rememberUpdatedState(pageToView)
    val currentOnFinished by rememberUpdatedState(onStrokeFinished)
    val currentOnInsertShape by rememberUpdatedState(onInsertShape)
    val currentStraightenOnHold by rememberUpdatedState(straightenOnHold)
    val currentOnStraightenStroke by rememberUpdatedState(onStraightenStroke)
    val currentOnPartialErase by rememberUpdatedState(onPartialErase)
    val currentOnObjectErase by rememberUpdatedState(onObjectErase)
    val currentShapes by rememberUpdatedState(shapes)
    val currentSelection by rememberUpdatedState(selection)
    val currentSearchHighlight by rememberUpdatedState(searchHighlight)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentTables by rememberUpdatedState(tables)
    val currentEquations by rememberUpdatedState(equations)
    val currentImages by rememberUpdatedState(images)
    val currentOnMoveShapes by rememberUpdatedState(onMoveShapes)
    val currentOnResizeShapes by rememberUpdatedState(onResizeShapes)
    val currentOnMoveShapeEnd by rememberUpdatedState(onMoveShapeEnd)
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
     * Which side of the ruler the stroke in progress is ruled against, or null if it is not ruled.
     *
     * Decided on the down and held for the whole stroke, so a hand drifting off the ruler still
     * draws the line it started. The side is held for the same reason: asked afresh each sample, a
     * hand sweeping across the body would flip the line onto the far edge mid-stroke.
     */
    var ruledSide by remember { mutableStateOf<RulerSide?>(null) }
    val eraseGesture = remember { EraseGesture() }
    val shapeGesture = remember { ShapeGesture() }
    val straightenHold = remember { StraightenHold() }
    val insertSpaceGesture = remember { InsertSpaceGesture() }
    val viewConfiguration = LocalViewConfiguration.current
    val doubleTap = remember(viewConfiguration) {
        DoubleTapGesture(
            minimumIntervalMillis = viewConfiguration.doubleTapMinTimeMillis,
            maximumIntervalMillis = viewConfiguration.doubleTapTimeoutMillis,
            touchSlop = viewConfiguration.touchSlop,
        )
    }

    LaunchedEffect(lassoing) {
        if (!lassoing) lassoGesture.clear()
        // Switching the lasso on or off changes which pointers `doubleTap` admits, so a tap already
        // banked under the old rule would pair with one made under the new one.
        doubleTap.reset()
    }
    LaunchedEffect(shaping == null) {
        if (shaping == null) shapeGesture.clear()
    }
    LaunchedEffect(insertingSpace) {
        if (!insertingSpace) insertSpaceGesture.clear()
    }
    LaunchedEffect(strokes) {
        eraseGesture.reconcileCommittedStrokes()
    }
    LaunchedEffect(erasing) {
        if (!erasing) eraseGesture.clear()
    }
    LaunchedEffect(hasClipboard) {
        if (!hasClipboard) doubleTap.reset()
    }
    LaunchedEffect(straightenOnHold) {
        if (!straightenOnHold) straightenHold.clear()
    }

    /*
     * The dwell clock — the half of hold-for-straight-line that no motion event can supply.
     *
     * A pen resting on the glass produces no `ACTION_MOVE`, so a second of nothing happening has to
     * be waited for. [StraightenHold.dwell] takes a fresh value every time the pen moves far enough
     * to restart the wait, and `collectLatest` cancels the pending [delay] when it does.
     *
     * Watched through [snapshotFlow] rather than read in composition: reading it up there would
     * recompose the whole overlay on every significant sample of every stroke.
     */
    LaunchedEffect(Unit) {
        snapshotFlow { straightenHold.dwell }.collectLatest { dwell ->
            if (dwell == null) return@collectLatest
            delay(STRAIGHTEN_HOLD_MILLIS)
            // Asked after the wait, never before it: the trace is what the pen has drawn by *now*,
            // and a fit computed a second ago would straighten a line the hand had since left.
            val line = straightenHold.line() ?: return@collectLatest
            val id = liveStroke ?: return@collectLatest
            val view = wetView ?: return@collectLatest

            // The freehand stroke is taken back rather than finished, so it never reaches
            // `onStrokeFinished` and never becomes an ink row. That is what makes Undo one step:
            // there is only the shape to take back, because the stroke was never there.
            view.cancelStroke(id)
            liveStroke = null
            livePointer = -1
            ruledSide = null
            straightenHold.spend()
            // The one piece of feedback there is. The pen is still down and the user is looking at
            // their own hand rather than at the line under it, so a tick is how they learn the hold
            // took — without it the gesture is a second of silence that either worked or did not.
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            currentOnStraightenStroke(
                InkPoint(line.startX, line.startY),
                InkPoint(line.endX, line.endY),
            )
        }
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
    // in the window, so a stroke begun on the canvas and dragged over the ribbon painted straight
    // over it. Android delivers the whole gesture to whoever took the ACTION_DOWN, which is right,
    // so the fix belongs in what is drawn rather than in what is delivered.
    val lassoColor = MaterialTheme.colorScheme.primary.toArgb()
    val searchHighlightFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f).toArgb()
    val searchHighlightBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f).toArgb()
    // The disc inside each corner handle, in the app's surface rather than white: `ShapeLayer`,
    // `EquationLayer` and `ImageLayer` all fill theirs with `colorScheme.surface`, so a hardcoded
    // white here was one selection affordance in two colours depending on which tool made it.
    val lassoHandleFill = MaterialTheme.colorScheme.surface.toArgb()
    // The app's accent, not the canvas's ink: the Insert Space guide is a tool showing its work, the
    // same kind of thing the lasso's trace is, and it disappears the moment the pointer lifts. The
    // band is the same hue held well back, so the writing it lies over stays readable while it does.
    val insertSpaceColor = MaterialTheme.colorScheme.primary.toArgb()
    val insertSpaceBand = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f).toArgb()
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
    /** Whether anything in hand owns the page's gestures — the condition the touch filter is on. */
    val armed = brush != null || erasing || lassoing || shaping != null || insertingSpace

    Box(
        modifier
            .clipToBounds()
            // With nothing in hand the overlay has to be transparent to touch, and since the
            // authoring view below is composed whether or not a tool is armed, transparent is no
            // longer the same as absent: an `AndroidView` carries a pointer-input node of its own,
            // and Compose stops at the first sibling it hits. Left alone that killed scrolling and
            // tapping into a text container. See `sharingTouchesWithSiblings`.
            //
            // Only while nothing is armed. With a tool in hand the filter below is in front of the
            // authoring view and is meant to own the gesture outright, which is why `handleInk` pans
            // the page itself rather than declining to a sibling that would never be asked.
            .then(if (armed) Modifier else Modifier.sharingTouchesWithSiblings())
            .testTag(INK_OVERLAY_TAG)
            .onSizeChanged { viewportSize = it },
    ) {
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
            val spaceRevision = insertSpaceGesture.renderRevision
            val matrix = currentTransform()
            // The window in page units, so a stroke that cannot be seen is not drawn. On a densely
            // handwritten page this is the difference between drawing a screenful and drawing ten
            // thousand strokes every frame. Computed from the transform here in the draw scope, for
            // the same reason the transform is read here: scrolling re-runs the draw and nothing
            // above it.
            val visible = matrix.pageWindow(size.width, size.height)
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                currentSearchHighlight?.let { bounds ->
                    drawSearchHighlight(
                        native,
                        matrix,
                        bounds,
                        fillColor = searchHighlightFill,
                        borderColor = searchHighlightBorder,
                    )
                }
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
                    // The only place the canvas colour reaches the ink. The stroke itself is
                    // untouched, so its projection key still matches what the selection holds.
                    renderer.draw(native, inkPainter.paint(pageStroke), strokeMatrix)
                    native.restoreToCount(checkpoint)
                }
                if (currentLassoing && gestureRevision >= 0) {
                    drawLasso(
                        native, matrix, lassoGesture, currentSelection, currentShapes,
                        lassoColor, lassoHandleFill,
                    )
                }
                currentShaping?.takeIf { shapeRevision >= 0 }?.let { settings ->
                    drawShapePreview(native, matrix, shapeGesture, settings)
                }
                if (currentInsertingSpace && spaceRevision >= 0) {
                    insertSpaceGesture.preview?.let { cut ->
                        drawInsertSpaceGuide(
                            native,
                            matrix,
                            cut,
                            // The same window the cull above measured: the guide spans what can be
                            // seen, because a line drawn to the document's extent on an unbounded
                            // canvas has no end to draw to.
                            visible,
                            color = insertSpaceColor,
                            bandColor = insertSpaceBand,
                        )
                    }
                }
                eraseGesture.indicator?.let { indicator ->
                    drawEraserIndicator(native, matrix, indicator)
                }
                // Last, so it lies on top of the ink the way a ruler lies on top of the paper.
                currentRuler?.let { drawRuler(native, matrix, it, rulerPaint) }
            }
        }

        // Held for as long as the page is open, and never rebuilt when the tool changes.
        //
        // This used to be composed only while a tool was armed, since it is the expensive half — a
        // front-buffered surface and its render thread. It cost ink instead: arming a pen had to
        // build the whole surface at the moment the user was reaching for the page, and the first
        // stroke fell into the gap two ways.
        //
        //  - The touch filter below is composed on the same recomposition, so a pen that came down
        //    before that frame landed had its whole gesture taken by the scroll container.
        //  - Worse, a stroke that did start went nowhere. `InProgressStrokesView` renders into a
        //    `SurfaceView` whose viewport only exists after `surfaceChanged` reports a size, and
        //    `CanvasInProgressStrokesRenderHelperV33.requestStrokeCohortHandoffToHwui` drops the
        //    finished cohort on a null viewport. `onStrokeCohortHandoffToHwuiComplete` then never
        //    fires, the manager's `newCohortStartAwaitingHandoff` latch stays set, and its `onDraw`
        //    returns immediately from then on — no wet ink, and no stroke ever reaching
        //    [onStrokeFinished], until something rebuilt the view.
        //
        // Both are races against surface setup, so the setup happens once while the page is still
        // loading. It costs one surface and one render thread held while the pointer is the tool.
        //
        // The touch filter below stays conditional, because it has to be absent with nothing in
        // hand or it swallows the taps that belong to the canvas. This one cannot be absent and
        // cannot be silent either — an `AndroidView` brings a pointer-input node with it — so the
        // overlay shares its hit path with its siblings while nothing is armed. See the root `Box`.
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

        if (armed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { event ->
                        val pastePoint = if (
                            currentHasInkClipboard &&
                            doubleTap.observe(event, acceptStylus = currentLassoing)
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
                            ruledSide = ruledSide,
                            setLive = { id, pointer, side ->
                                liveStroke = id
                                livePointer = pointer
                                ruledSide = side
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
                            onMoveShapeEnd = currentOnMoveShapeEnd,
                            onMoveTables = currentOnMoveTables,
                            onResizeTables = currentOnResizeTables,
                            onMoveEquations = currentOnMoveEquations,
                            onResizeEquations = currentOnResizeEquations,
                            onMoveImages = currentOnMoveImages,
                            onResizeImages = currentOnResizeImages,
                            shapeGesture = shapeGesture,
                            onInsertShape = currentOnInsertShape,
                            straightenOnHold = currentStraightenOnHold,
                            straightenHold = straightenHold,
                            insertingSpace = currentInsertingSpace,
                            insertSpaceGesture = insertSpaceGesture,
                            onInsertSpace = currentOnInsertSpace,
                            touchSlop = viewConfiguration.touchSlop,
                        )
                        pastePoint?.let(currentOnRequestPaste)
                        handled
                    },
            )
        }

        // The object tooltip is raised by `EditorPane` from the page's selection, not here. A shape
        // can be in that selection, and a bar that appeared once per layer could not describe a loop
        // holding both.
    }

    DisposableEffect(Unit) {
        onDispose { wetView?.cancelUnfinishedStrokes() }
    }
}

/** Material-coloured search emphasis, intentionally without handles or edit affordances. */
private fun drawSearchHighlight(
    canvas: android.graphics.Canvas,
    pageToView: Matrix,
    bounds: InkBounds,
    fillColor: Int,
    borderColor: Int,
) {
    val padding = SEARCH_HIGHLIGHT_PADDING_DP
    val rect = android.graphics.RectF(
        bounds.left - padding,
        bounds.top - padding,
        bounds.right + padding,
        bounds.bottom + padding,
    )
    val checkpoint = canvas.save()
    canvas.concat(pageToView)
    canvas.drawRoundRect(
        rect,
        SEARCH_HIGHLIGHT_RADIUS_DP,
        SEARCH_HIGHLIGHT_RADIUS_DP,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        },
    )
    canvas.drawRoundRect(
        rect,
        SEARCH_HIGHLIGHT_RADIUS_DP,
        SEARCH_HIGHLIGHT_RADIUS_DP,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = SEARCH_HIGHLIGHT_STROKE_DP
        },
    )
    canvas.restoreToCount(checkpoint)
}

private const val SEARCH_HIGHLIGHT_PADDING_DP = 5f
private const val SEARCH_HIGHLIGHT_RADIUS_DP = 7f
private const val SEARCH_HIGHLIGHT_STROKE_DP = 1.5f

/**
 * Recognises stationary, single-pointer double taps without taking drag/pan ownership.
 *
 * The pen is admitted per gesture, not per tool — [observe]'s `acceptStylus`. A finger double tap
 * is always safe: when a drawing tool owns touch the finger is not drawing. A stylus double tap is
 * not, because with a brush or the eraser active those two taps are two marks on the page, so
 * dotting an "i" twice would offer to paste. The lasso is the one tool where a tap deposits
 * nothing, so it is the one that passes `acceptStylus = true`.
 *
 * Unlike `StylusButtons`, where the firmware counts clicks and a software timer would double-count
 * them: nothing counts screen taps, so the interval test below is the only way to see this one, and
 * it uses [ViewConfiguration]'s own double-tap window rather than a constant of our own.
 */
internal class DoubleTapGesture(
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

    fun observe(event: MotionEvent, acceptStylus: Boolean = false): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the tip. A flipped pen reports TOOL_TYPE_ERASER and is erasing, not gesturing.
                val toolType = event.getToolType(event.actionIndex)
                val admitted = toolType == MotionEvent.TOOL_TYPE_FINGER ||
                    (acceptStylus && toolType == MotionEvent.TOOL_TYPE_STYLUS)
                if (!admitted) {
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
    onMoveShapeEnd: (String, Boolean, Float, Float) -> Unit,
    onMoveTables: (Set<String>, Float, Float) -> Unit,
    onResizeTables: (Set<String>, InkPoint, Float, Float) -> Unit,
    onMoveEquations: (Set<String>, Float, Float) -> Unit,
    onResizeEquations: (Set<String>, InkPoint, Float, Float) -> Unit,
    onMoveImages: (Set<String>, Float, Float) -> Unit,
    onResizeImages: (Set<String>, InkPoint, Float, Float) -> Unit,
    liveStroke: InProgressStrokeId?,
    livePointer: Int,
    ruledSide: RulerSide?,
    setLive: (InProgressStrokeId?, Int, RulerSide?) -> Unit,
    pan: CanvasPan,
    velocity: VelocityTracker,
    panning: Boolean,
    lastPan: Pair<Float, Float>,
    setPanning: (Boolean, Pair<Float, Float>) -> Unit,
    eraseGesture: EraseGesture,
    lassoGesture: LassoGesture,
    shapeGesture: ShapeGesture,
    onInsertShape: (InkPoint, InkPoint) -> Unit,
    straightenOnHold: Boolean,
    straightenHold: StraightenHold,
    insertingSpace: Boolean,
    insertSpaceGesture: InsertSpaceGesture,
    onInsertSpace: (SpaceCut) -> Unit,
    touchSlop: Float,
): Boolean {
    val index = event.actionIndex
    val toolType = event.getToolType(index)
    // Both count as "a pointer with no pen behind it", which is what the finger setting is about.
    // A mouse is here for the emulator, though not because the emulator reports one: on the
    // `Medium_Tablet` AVD `dumpsys input` lists only `virtio_input_multi_touch` devices, so a host
    // click arrives as a finger. Other configurations do deliver a real mouse; either way the
    // answer is the same.
    val isDirectTouch = toolType == MotionEvent.TOOL_TYPE_FINGER ||
        toolType == MotionEvent.TOOL_TYPE_MOUSE

    // Lasso follows the same finger setting as ink and the eraser: stylus always selects, while a
    // disallowed finger moves the page. It used to claim every finger drag even in stylus-only
    // mode, which made the page impossible to pan one-handed with the lasso armed. Ahead of every
    // other mode because it owns the whole gesture, and a shape drag must not also start a stroke.
    if (shaping != null) {
        if (isDirectTouch && !allowFinger) {
            return panPage(event, pan, velocity, panning, lastPan, setPanning)
        }
        val toPage = Matrix().also { transform.invert(it) }
        return shapeGesture.handle(event, toPage, touchSlop, onInsertShape)
    }

    // Ahead of the lasso for the same reason the shape tool is ahead of both: a tool that owns the
    // whole gesture must be asked before anything that would also like part of it. A disallowed
    // finger pans, so the page can still be moved one-handed with the tool in hand.
    if (insertingSpace) {
        if (isDirectTouch && !allowFinger) {
            return panPage(event, pan, velocity, panning, lastPan, setPanning)
        }
        val toPage = Matrix().also { transform.invert(it) }
        return insertSpaceGesture.handle(event, toPage, touchSlop, onInsertSpace)
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
            touchSlop = touchSlop,
            selection = selection,
            onSelect = onSelect,
            onMove = onMoveSelection,
            onResize = onResizeSelection,
            onMoveShapes = onMoveShapes,
            onResizeShapes = onResizeShapes,
            onMoveShapeEnd = onMoveShapeEnd,
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

    // A press is always the start of a new gesture, whatever became of the last one. Android has to
    // work quite hard to lose an up — a disposed filter, a torn-down window — but the failure if it
    // ever does is that the *next* stroke is swallowed as the tail of the previous one, and a mark
    // that silently does not appear is the worst thing this feature could do.
    if (event.actionMasked == MotionEvent.ACTION_DOWN) straightenHold.clear()

    // This gesture already became a line. What is left of it is a hand coming to rest and lifting,
    // not a second mark — and it is swallowed rather than declined, because declining mid-gesture
    // hands the remainder to the scroll container and pans the page out from under a pen that is
    // still touching it.
    if (straightenHold.spent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL,
            -> straightenHold.clear()
        }
        return true
    }

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

    // A ruled stroke is fed rewritten coordinates rather than the ones the hand produced. The
    // substitution happens here, at the one seam every branch below passes through, so the stroke
    // lifecycle underneath is the same code whether the ruler is out or not. What is carried across
    // samples is not merely that the stroke is ruled but which side of the ruler it is ruled
    // against; one nullable value carries both.
    val ruledTo = ruledSide ?: run {
        if (ruler == null || event.actionMasked != MotionEvent.ACTION_DOWN) return@run null
        val landed = event.pagePoint(index, toWorld)
        if (ruler.engages(landed, Ruler.SNAP_TOLERANCE_DP)) ruler.sideOf(landed) else null
    }
    val drawn = if (ruledTo != null && ruler != null) {
        event.snappedTo(ruler, ruledTo, toWorld, transform)
    } else {
        null
    }

    // After the ruler, because the ruler is what decides where a ruled stroke goes and this is what
    // decides where it may not — a straightedge laid across the top of the page must not be able to
    // put ink above it.
    val bounded = (drawn ?: event).clampedToPage(toWorld, transform)
    val inkEvent = bounded ?: drawn ?: event

    // Fed the same points the stroke is — after the ruler and after the wall — so the fit judges
    // the mark that would land on the page rather than the raw path of the hand.
    //
    // Never for a ruled stroke: that one is already straight, drawn against an object the user
    // deliberately laid there, so turning it into a shape would swap out the mark being made.
    if (straightenOnHold && ruledTo == null) {
        straightenHold.observe(inkEvent, toWorld, touchSlop)
    } else {
        straightenHold.clear()
    }

    try {
        return handleInkStroke(
            event = inkEvent,
            view = view,
            brush = brush,
            toWorld = toWorld,
            index = index,
            liveStroke = liveStroke,
            livePointer = livePointer,
            ruledTo = ruledTo,
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
    ruledTo: RulerSide?,
    setLive: (InProgressStrokeId?, Int, RulerSide?) -> Unit,
): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            // A second pointer while drawing is a palm or a pinch, so the stroke is taken back and
            // the gesture handed to the scroll container behind — that is how the page still pans
            // while a pen is in hand.
            if (liveStroke != null) {
                view.cancelStroke(liveStroke, event)
                setLive(null, -1, null)
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
            setLive(view.startStroke(event, pointerId, brush, toWorld), pointerId, ruledTo)
        }
        MotionEvent.ACTION_MOVE -> {
            val id = liveStroke ?: return false
            view.addToStroke(event, livePointer, id)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            val id = liveStroke ?: return false
            if (event.getPointerId(index) != livePointer) return true
            view.finishStroke(event, livePointer, id)
            setLive(null, -1, null)
        }
        MotionEvent.ACTION_CANCEL -> {
            // Palm rejection lands here, and on a cancelled pointer post-Android 13 also as
            // FLAG_CANCELED on the up. Both mean: that was not a stroke, take it back.
            val id = liveStroke ?: return false
            view.cancelStroke(id, event)
            setLive(null, -1, null)
        }
        else -> return false
    }
    return true
}

/**
 * A copy of this event with its point moved onto the ruler's edge.
 *
 * A rewritten `MotionEvent` rather than the `StrokeInput` overloads, so the stroke path underneath
 * keeps the matrix arrangement already known to be right. Pressure, tilt, orientation, tool type
 * and the pointer id are copied wholesale; only x and y are the ruler's business.
 *
 * Historical samples are dropped and lose nothing: a ruled stroke's path is decided by the ruler,
 * and where it started and where it is now are both still here.
 *
 * [side] is the stroke's, not this sample's: see [Ruler.snap]. The caller recycles.
 */
private fun MotionEvent.snappedTo(
    ruler: Ruler,
    side: RulerSide,
    toPage: Matrix,
    toView: Matrix,
): MotionEvent {
    val index = actionIndex
    val snapped = ruler.snap(pagePoint(index, toPage), side)
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
 * A copy of this event with its point pulled back onto the page, or null when it is already on it —
 * [PageBounds].
 *
 * A stroke begun inside the window keeps receiving moves after the pointer has left it, and those
 * arrive with coordinates behind the origin. That is how a page ends up carrying strokes at a
 * negative y: invisible, unselectable and unreachable, because neither scroll state goes below zero.
 *
 * There is no fixing it afterwards — translating a stroke back onto the page would move the half
 * that was never off it — so it is stopped here on the way in, and the ink piles up against the wall.
 *
 * Null for the common case: an in-bounds event passes through untouched and keeps its historical
 * samples, which are most of a fast stroke's fidelity. Only an event that has crossed the wall is
 * rebuilt, and only that one loses its history.
 *
 * Single-pointer events only; the caller's `index` is only valid against a one-pointer copy. The
 * caller recycles.
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
    /** Read only to answer [lineShape]: whether what is held is one line, which has no box. */
    shapes: List<Outline.Shape>,
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
    val handleRadius = SelectionChrome.HANDLE_RADIUS.value
    val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = handleColor
        style = Paint.Style.FILL
    }

    // A lassoed line gets what a tapped one gets: two handles on its own ends, and no box. It used
    // to get the generic rectangle and four corners, which pointed at a resize the kind does not
    // have. A locked line takes the rectangle instead of its end handles, since those are its whole
    // chrome and dropping them alone would leave a selected shape with nothing drawn on it.
    val locked = selection?.isLocked == true
    val line = if (locked) null else selection.lineShape(shapes)
    if (line != null) {
        val drawn = line.withLassoPreview(gesture)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = SelectionChrome.STROKE.value
        }
        drawn.ends().forEach { end ->
            canvas.drawCircle(end.x, end.y, handleRadius, handleFill)
            canvas.drawCircle(end.x, end.y, handleRadius, linePaint)
        }
        canvas.restoreToCount(checkpoint)
        return
    }

    gesture.previewBounds(selection)?.let { bounds ->
        // Page units are dp, so the chrome is drawn from [SelectionChrome] as it stands, with no
        // division by [scale] — those are the same numbers `ShapeLayer`, `EquationLayer` and
        // `ImageLayer` hand to `Dp.toPx()`, which is what makes a lassoed object's selection the
        // same affordance as a tapped one.
        //
        // Dividing by the scale pinned the chrome to a fixed number of device pixels, so a lassoed
        // object's handles came out at a third of a tapped one's radius on a 3× screen and shrank
        // further with every step of zoom.
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
        // The rectangle stays, the corners go: a locked selection cannot be scaled, and an
        // affordance that cannot act is absent rather than present and dead. The same gate the
        // object layers apply to their own handles.
        if (!locked) {
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
    }
    canvas.restoreToCount(checkpoint)
}

/**
 * The shape under the pointer, mid-drag.
 *
 * Drawn from the same [trace] call the commit will run, so the preview cannot show one thing and
 * land another. Everything here is in page space, so the border width is in page units and scales
 * with zoom exactly as the committed ink will.
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
 * Owns one drag that becomes a shape.
 *
 * Deliberately not routed through `InProgressStrokesView` like freehand ink is. A shape is not
 * captured, it is constructed: the front buffer renders one continuous stroke where a cube is
 * twelve, and a traced path needs no low-latency wet rendering. So this owns two page-space points
 * and the overlay draws the preview from them, as it draws the lasso.
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
}

/**
 * A view-pixel distance expressed in page units, so a threshold measured against the hand does not
 * change meaning with zoom.
 *
 * The hand's wobble happens on the glass, not on the page: at 25% zoom a 6 dp page threshold is a
 * pixel and a half of screen and nothing can hold that still, while at 400% it is a wide enough
 * margin to stop meaning anything. Anything asking "has this pointer moved" therefore states its
 * threshold in view pixels and converts it here.
 */
private fun toPageLength(toPage: Matrix, pixels: Float): Float {
    val values = FloatArray(9)
    toPage.getValues(values)
    return pixels * hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
}

/**
 * Watches one freehand stroke for the pause that turns it into a straight line.
 *
 * This holds a trace and a stopwatch's starting gun, not a timer. A stationary pen emits no events,
 * so nothing here can notice a second going by; instead it publishes [dwell], a value that changes
 * every time the pen moves far enough to restart the wait, and the overlay turns that into an actual
 * wait with `collectLatest` and [delay]. Keeping the clock outside lets this be driven straight from
 * `MotionEvent`s in a test.
 *
 * The trace is accumulated rather than read back from `InProgressStrokesView`, which does not offer
 * the points of an unfinished stroke — and could not offer them in page units if it did.
 */
internal class StraightenHold {

    private var trace = FloatArray(INITIAL_CAPACITY)
    private var traceSize = 0
    private var pointerId = -1

    /** Where the current wait is being measured from. Not the stroke's start — the pause's. */
    private var anchorX = 0f
    private var anchorY = 0f

    /**
     * Refused outright once a stroke gets absurdly long, rather than growing without bound.
     *
     * [MAX_POINTS] is around half a minute of continuous drawing at a tablet's sample rate. Nothing
     * that long is a line somebody is about to straighten, so the cap costs nothing real — and
     * refusing is the same answer every other threshold gives when it cannot tell.
     */
    private var overflowed = false

    /**
     * Non-null while a pause is being waited out, and a *different* value each time the wait
     * restarts. The value itself means nothing; only that it changed does.
     */
    var dwell by mutableStateOf<Int?>(null)
        private set

    /**
     * True once this gesture has become a line, so the rest of it is not also a stroke.
     *
     * A plain field rather than snapshot state, unlike [dwell]: nothing reads it in composition, and
     * a state write from the input callback that nobody observes is an apply notification for
     * nothing on every stroke that fires.
     */
    var spent = false
        private set

    fun observe(event: MotionEvent, toPage: Matrix, touchSlop: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clear()
                pointerId = event.getPointerId(event.actionIndex)
                val point = event.pagePoint(event.actionIndex, toPage)
                push(point.x, point.y)
                restartDwell(point.x, point.y)
            }

            // A palm or a pinch. The stroke itself is cancelled a moment later by `handleInkStroke`,
            // and a trace that outlived it would straighten whatever the *next* stroke drew.
            MotionEvent.ACTION_POINTER_DOWN -> clear()

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0 || traceSize == 0) return
                // Historical samples included: they are most of a fast stroke's shape, and a fit
                // run over frame-boundary points alone would call a wobbly line straight.
                for (history in 0 until event.historySize) {
                    val past = event.pagePoint(index, toPage, history)
                    push(past.x, past.y)
                }
                val point = event.pagePoint(index, toPage)
                push(point.x, point.y)

                // The wait restarts only when the pen leaves the radius it was resting in. Every
                // sample *inside* it leaves the wait running, which is what stops a hand that is
                // holding still — but not perfectly still — from resetting the clock for ever.
                if (hypot(point.x - anchorX, point.y - anchorY) > toPageLength(toPage, touchSlop)) {
                    restartDwell(point.x, point.y)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                clear()
        }
    }

    /** The line this trace is, or null to leave the freehand stroke alone. */
    fun line() = if (overflowed || traceSize < 4) {
        null
    } else {
        StraightLineFit.of(trace.copyOf(traceSize))
    }

    /**
     * Marks the gesture as having produced its line.
     *
     * Deliberately not [clear]: the pen is still down, and everything left of the gesture has to be
     * recognisable as belonging to a stroke that is over. [clear] happens on the lift.
     */
    fun spend() {
        dwell = null
        traceSize = 0
        spent = true
    }

    fun clear() {
        pointerId = -1
        traceSize = 0
        overflowed = false
        dwell = null
        spent = false
    }

    private fun restartDwell(x: Float, y: Float) {
        anchorX = x
        anchorY = y
        val previous = dwell ?: 0
        dwell = if (previous == Int.MAX_VALUE) 0 else previous + 1
    }

    private fun push(x: Float, y: Float) {
        if (overflowed) return
        if (traceSize + 2 > trace.size) {
            if (trace.size >= MAX_POINTS * 2) {
                overflowed = true
                return
            }
            trace = trace.copyOf(trace.size * 2)
        }
        trace[traceSize++] = x
        trace[traceSize++] = y
    }

    private companion object {
        const val INITIAL_CAPACITY = 512
        const val MAX_POINTS = 4096
    }
}

/**
 * How long the pen has to rest before a straight-enough stroke becomes a line.
 *
 * One second. This gesture has no preview and replaces a mark already on the page, so it is worth
 * being sure the pause was meant; it is also long enough not to fire on the pause people make
 * mid-word.
 */
private const val STRAIGHTEN_HOLD_MILLIS = 1_000L

/** What a tap drops, in page units. A shape you can see and grab, not a speck. */
private const val DEFAULT_SHAPE_WIDTH = 120f
private const val DEFAULT_SHAPE_HEIGHT = 80f

/**
 * Owns one Insert Space drag — see `com.vivenotes.model.PageSpace` for what it means.
 *
 * Like [ShapeGesture], this holds two page-space facts and lets the overlay draw from them: no ink
 * is being laid down, and what the user needs to see is a line and a band.
 *
 * The axis is chosen by the drag, not by where the pointer is. OneNote decides it from proximity to
 * the sheet's edges and shows the guide on hover, and neither half survives here: this canvas is
 * unbounded by default, so there is no edge to be near, and a finger has no hover. So the first
 * unambiguous direction of travel decides and then locks — a gesture that could still change its
 * mind at 300 dp would flip the whole page sideways on a wobble. Until it locks the guide is drawn
 * horizontal, which is the common case.
 */
internal class InsertSpaceGesture {

    var renderRevision by mutableIntStateOf(0)
        private set

    /** Where the line was drawn, or null while idle — which is also what says there is nothing to draw. */
    private var origin by mutableStateOf<InkPoint?>(null)
    private var axis by mutableStateOf(PageSpace.Axis.Vertical)

    /** How far the drag has travelled along [axis]. Signed: negative is closing the gap. */
    private var amount by mutableStateOf(0f)
    private var pointerId: Int = -1
    private var axisLocked = false

    /**
     * The cut as it currently stands, for the preview to draw, or null while idle.
     *
     * Deliberately the same value the commit will send, so the preview cannot disagree with it.
     */
    val preview: SpaceCut?
        get() = origin?.let { SpaceCut(axis, axis.coordinateOf(it), amount) }

    fun handle(
        event: MotionEvent,
        toPage: Matrix,
        touchSlop: Float,
        onCommit: (SpaceCut) -> Unit,
    ): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(event.actionIndex)
                // Clamped to the page's own corner, so the line can never be drawn at a negative
                // coordinate — which is what lets `SpaceCut.limitedTo` be the only limit a closing
                // drag needs. See [PageBounds].
                origin = PageBounds.clamp(event.pagePoint(event.actionIndex, toPage))
                axis = PageSpace.Axis.Vertical
                axisLocked = false
                amount = 0f
                invalidateDraw()
                true
            }
            // A second contact is a palm or a pinch. Never more space.
            MotionEvent.ACTION_POINTER_DOWN -> {
                clear()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val began = origin ?: return false
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return false
                val point = PageBounds.clamp(event.pagePoint(index, toPage))
                val dx = point.x - began.x
                val dy = point.y - began.y
                if (!axisLocked) {
                    val slop = toPageLength(toPage, touchSlop)
                    if (maxOf(abs(dx), abs(dy)) > slop) {
                        axis = if (abs(dx) > abs(dy)) {
                            PageSpace.Axis.Horizontal
                        } else {
                            PageSpace.Axis.Vertical
                        }
                        axisLocked = true
                    }
                }
                amount = if (axis == PageSpace.Axis.Horizontal) dx else dy
                invalidateDraw()
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != pointerId) return false
                val cut = preview
                clear()
                // A tap commits nothing, and needs no special case to say so: an unmoved pointer has
                // travelled zero, and a cut of zero is not a gesture. Unlike a shape, there is no
                // sensible default amount to drop — how much space is the entire question.
                cut?.takeIf { !it.isEmpty }?.let(onCommit)
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
        origin = null
        axisLocked = false
        amount = 0f
        invalidateDraw()
    }

    private fun invalidateDraw() {
        renderRevision = if (renderRevision == Int.MAX_VALUE) 0 else renderRevision + 1
    }

    /** View-pixel slop in page units, so the axis is decided at the same finger travel at any zoom. */
    private fun toPageLength(toPage: Matrix, pixels: Float): Float {
        val values = FloatArray(9)
        toPage.getValues(values)
        return pixels * hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
    }

    private fun PageSpace.Axis.coordinateOf(point: InkPoint): Float =
        if (this == PageSpace.Axis.Horizontal) point.x else point.y
}

/**
 * The Insert Space guide: the line, and the space it is making.
 *
 * **The band is the point.** A line and an arrow say which way the content is going; the filled band
 * between the line and the pointer says *how much*, in the place where it will actually appear — so
 * a drag that is about to open a two-inch gap looks like a two-inch gap before it is committed. On a
 * closing drag the band falls on the other side of the line and shows the gap being consumed
 * instead, which is why it is drawn from the sorted pair rather than from the sign.
 *
 * Everything is drawn in page space, so the guide zooms with the content it is measuring — the same
 * decision [drawEraserIndicator] makes, and for the same reason: this is a measurement of the page,
 * not a piece of window chrome. The line's own weight is the exception and is divided back out, as
 * the lasso's trace is, because a hairline is a hairline at every zoom.
 */
private fun drawInsertSpaceGuide(
    canvas: android.graphics.Canvas,
    pageToView: Matrix,
    cut: SpaceCut,
    /** The page rectangle currently on screen, so the line spans the view rather than the document. */
    window: android.graphics.RectF?,
    color: Int,
    bandColor: Int,
) {
    val values = FloatArray(9)
    pageToView.getValues(values)
    val scale = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]).coerceAtLeast(0.001f)
    // Falls back to the page's own corner and a generous run when the transform will not invert,
    // which is a zoom of zero and draws nothing anyway.
    val across = window ?: android.graphics.RectF(0f, 0f, GUIDE_FALLBACK_SPAN, GUIDE_FALLBACK_SPAN)
    val near = minOf(cut.at, cut.at + cut.amount)
    val far = maxOf(cut.at, cut.at + cut.amount)

    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = GUIDE_STROKE_DP / scale
    }
    val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = bandColor
        style = Paint.Style.FILL
    }
    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    val checkpoint = canvas.save()
    canvas.concat(pageToView)
    when (cut.axis) {
        PageSpace.Axis.Vertical -> {
            canvas.drawRect(across.left, near, across.right, far, bandPaint)
            canvas.drawLine(across.left, cut.at, across.right, cut.at, linePaint)
        }
        PageSpace.Axis.Horizontal -> {
            canvas.drawRect(near, across.top, far, across.bottom, bandPaint)
            canvas.drawLine(cut.at, across.top, cut.at, across.bottom, linePaint)
        }
    }
    // Centred in the part of the band that is on screen, so it stays visible however far the page is
    // scrolled from where the drag began — and left off entirely once the band is too thin to hold
    // it, because an arrowhead poking out of a 3 dp gap describes something other than that gap.
    if (far - near >= GUIDE_ARROW_DP * 2f) {
        val centre = when (cut.axis) {
            PageSpace.Axis.Vertical -> InkPoint((across.left + across.right) / 2f, (near + far) / 2f)
            PageSpace.Axis.Horizontal -> InkPoint((near + far) / 2f, (across.top + across.bottom) / 2f)
        }
        canvas.drawPath(arrowHead(cut, centre), arrowPaint)
    }
    canvas.restoreToCount(checkpoint)
}

/** A triangle pointing the way the content is travelling, in page units. */
private fun arrowHead(cut: SpaceCut, centre: InkPoint): Path {
    val reach = GUIDE_ARROW_DP
    val half = GUIDE_ARROW_DP * 0.75f
    val forward = if (cut.amount >= 0f) 1f else -1f
    return Path().apply {
        when (cut.axis) {
            PageSpace.Axis.Vertical -> {
                moveTo(centre.x, centre.y + reach * forward)
                lineTo(centre.x - half, centre.y - reach * forward)
                lineTo(centre.x + half, centre.y - reach * forward)
            }
            PageSpace.Axis.Horizontal -> {
                moveTo(centre.x + reach * forward, centre.y)
                lineTo(centre.x - reach * forward, centre.y - half)
                lineTo(centre.x - reach * forward, centre.y + half)
            }
        }
        close()
    }
}

/** In view pixels: a guide line is chrome, so it keeps one weight at every zoom. */
private const val GUIDE_STROKE_DP = 2f

/** In page units, so the arrow scales with the gap it is describing rather than the window. */
private const val GUIDE_ARROW_DP = 9f

/** Only reached when the page transform will not invert, which draws nothing worth measuring. */
private const val GUIDE_FALLBACK_SPAN = 2000f

/**
 * Owns one lasso loop and the live move or corner-resize that follows it — but not the selection.
 *
 * What is selected is a page-level fact ([CanvasSelection]) held by `EditorPane`, because a shape
 * can be in it and a shape is not ink. This class holds only what is true for one gesture: which
 * corner is being dragged, how far, and the loop being traced. It reads the current selection as an
 * argument and reports a new one through a callback.
 *
 * The live transform is therefore readable from one place, in page units, by every layer that draws
 * a selected object — the ink canvas here and `ShapeLayer` inside the zoom.
 */
internal class LassoGesture {
    /**
     * [Held] is a press on a locked selection: ours, and inert.
     *
     * Not [Idle], which would hand the rest of the gesture back and let the scroll containers pan
     * the page out from under a finger dragging an object; and not [Drawing], which would clear the
     * selection and lasso over the thing under the finger. A locked object refuses the transform,
     * not the touch.
     */
    private enum class Mode { Idle, Drawing, Moving, Resizing, LineEnd, Held }
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
     * The line being end-dragged, as it was on the down; which of its two ends was grabbed; how far
     * off centre the finger landed on it; and where that end now is.
     *
     * [endPreview] is the shape the drag has made, rebuilt from [endShape] every sample rather than
     * from the last one — the same absolute contract `withEnd` keeps for the tap path, so the frames
     * of one drag cannot accumulate. It is what both the chrome here and `ShapeLayer` inside the zoom
     * draw, so the handles and the line they belong to can never disagree.
     */
    private var endShape: Outline.Shape? = null
    private var endHandle: ShapeEnd? = null
    private var endGrab = InkPoint(0f, 0f)
    private var endAt = InkPoint(0f, 0f)
    private var endPreview by mutableStateOf<Outline.Shape?>(null)

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

    /** True while a move, resize or end drag is in flight, so a draw knows to apply the preview. */
    val isTransforming: Boolean
        get() = mode == Mode.Moving || mode == Mode.Resizing || mode == Mode.LineEnd

    /** The line an end drag has made, or null — read by every layer that draws the shape. */
    fun lineEndPreview(): Outline.Shape? = endPreview.takeIf { mode == Mode.LineEnd }

    fun drawingPath(): List<InkPoint> = if (mode == Mode.Drawing) path else emptyList()

    /** The selection's rectangle with the live gesture folded in, which is what gets drawn. */
    fun previewBounds(selection: CanvasSelection?): InkBounds? = selection?.bounds?.let { bounds ->
        when (mode) {
            Mode.Moving -> bounds.translated(preview.x, preview.y)
            Mode.Resizing -> bounds.scaled(resizeAnchor, resizeScale.x, resizeScale.y)
            // Not a transform of the old rectangle: an end drag turns the line, so the box around it
            // is whatever the new geometry measures. The tooltip anchors to this.
            Mode.LineEnd -> endPreview?.pageBounds() ?: bounds
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
        forgetLineEnd()
        invalidateDraw()
    }

    /**
     * @param selection what is currently selected, across kinds. Read, never written.
     * @param onSelect a new selection, or null when the loop caught nothing. Called on the up.
     * @param onMove the ink half of a finished move. [onMoveShapes] is the shape half.
     * @param touchSlop how far, in view pixels, a press may travel and still be a tap — [selectByTap].
     */
    fun handle(
        event: MotionEvent,
        toPage: Matrix,
        strokes: List<PageStroke>,
        shapes: List<Outline.Shape>,
        tables: List<TableBounds> = emptyList(),
        equations: List<Outline.Equation> = emptyList(),
        images: List<Outline.Image> = emptyList(),
        touchSlop: Float = 0f,
        selection: CanvasSelection?,
        onSelect: (CanvasSelection?) -> Unit,
        onMove: (InkLassoMove) -> Unit,
        onResize: (InkLassoResize) -> Unit,
        onMoveShapes: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
        onResizeShapes: (Set<String>, InkPoint, Float, Float) -> Unit = { _, _, _, _ -> },
        onMoveShapeEnd: (String, Boolean, Float, Float) -> Unit = { _, _, _, _ -> },
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
                // One line held alone has ends where everything else has corners, and never both —
                // the same question `ShapeLayer.handleNear` asks of a tapped one.
                val line = selection.lineShape(shapes)
                // Locked: no handle answers, because none is drawn. Both grabs are refused here
                // rather than at the point of applying them, so the gesture never enters a mode
                // whose whole job is to preview an edit that will not happen.
                val locked = selection?.isLocked == true
                val grabbedEnd = if (locked) {
                    null
                } else {
                    line?.endNear(point.x, point.y, SelectionChrome.HANDLE_REACH.value)
                }
                val corner = if (line == null && !locked) {
                    selection?.bounds?.cornerNear(point)
                } else {
                    null
                }
                startBounds = selection?.bounds
                if (line != null && grabbedEnd != null) {
                    mode = Mode.LineEnd
                    endShape = line
                    endHandle = grabbedEnd
                    endGrab = InkPoint(grabbedEnd.x - point.x, grabbedEnd.y - point.y)
                    endAt = InkPoint(grabbedEnd.x, grabbedEnd.y)
                    endPreview = line
                    preview = InkPoint(0f, 0f)
                } else if (selection != null && corner != null) {
                    mode = Mode.Resizing
                    resizeStart = corner.point(selection.bounds)
                    resizeAnchor = corner.opposite().point(selection.bounds)
                    resizeScale = InkPoint(1f, 1f)
                    preview = InkPoint(0f, 0f)
                } else if (
                    selection != null &&
                    selection.bounds.holdsBody(point, if (line != null) TAP_REACH else 0f)
                ) {
                    mode = if (locked) Mode.Held else Mode.Moving
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
                    Mode.LineEnd -> updateLineEnd(event.pagePoint(index, toPage))
                    Mode.Held, Mode.Idle -> Unit
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != pointerId || mode == Mode.Idle) return false
                when (mode) {
                    Mode.Drawing -> {
                        appendSamples(event, event.actionIndex, toPage)
                        val drawn = path.toList()
                        // A loop first, and a tap only when there was no loop. Read the other way
                        // round, a deliberate circle drawn around empty paper next to a picture
                        // would end by selecting the picture the finger happened to lift over.
                        onSelect(
                            selectWithLasso(
                                strokes = strokes,
                                shapes = shapes,
                                tables = tables,
                                equations = equations,
                                images = images,
                                path = drawn,
                                edgeTolerance = lassoEdgeTolerance(toPage),
                            ) ?: drawn.tapPoint(toPage.pageSpan(touchSlop))?.let { tap ->
                                selectByTap(
                                    shapes = shapes,
                                    equations = equations,
                                    images = images,
                                    point = tap,
                                )
                            },
                        )
                        path.clear()
                    }
                    Mode.Moving -> {
                        // The same clamp the preview was drawn with, so what is written down is
                        // what the finger was shown — see [travelTo].
                        val delta = travelTo(event.pagePoint(event.actionIndex, toPage))
                        if (selection != null && (delta.x != 0f || delta.y != 0f)) {
                            // One gesture, one payload per kind: ink persists a move for replay, a
                            // shape translates its own segments. Move and resize are transforms on
                            // an object rather than on its representation, so the gesture states
                            // the delta once and lets each kind apply it its own way.
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
                    Mode.LineEnd -> {
                        updateLineEnd(event.pagePoint(event.actionIndex, toPage))
                        val shape = endShape
                        val handle = endHandle
                        val moved = endPreview
                        // A press on the handle that never travelled is not an edit — and the
                        // selection it would rewrite is the one already on screen.
                        if (shape != null && handle != null && moved != null && moved != shape) {
                            onMoveShapeEnd(shape.id, handle.atEnd, endAt.x, endAt.y)
                            // The bounds are the new geometry's; the loop that made the selection is
                            // kept, as the move and resize arms keep theirs.
                            onSelect(selection?.copy(bounds = moved.pageBounds()))
                        }
                    }
                    // A press on something locked: the selection it landed on is already the
                    // one on screen, so the lift has nothing to report.
                    Mode.Held, Mode.Idle -> Unit
                }
                mode = Mode.Idle
                pointerId = -1
                preview = InkPoint(0f, 0f)
                resizeScale = InkPoint(1f, 1f)
                startBounds = null
                forgetLineEnd()
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
        forgetLineEnd()
        invalidateDraw()
    }

    private fun forgetLineEnd() {
        endShape = null
        endHandle = null
        endGrab = InkPoint(0f, 0f)
        endPreview = null
    }

    /**
     * The line with its grabbed end taken to where the finger is, rebuilt from the shape the drag
     * began with — never from the previous frame, for the reason `withEnd` is absolute.
     *
     * The origin corner is a wall for an endpoint as it is for everything else, and it is
     * `withEndOnPage` that holds it there — the snap turns the line after a clamp on the finger
     * would have run, so clamping here would only bend the angle it is about to snap to.
     */
    private fun updateLineEnd(point: InkPoint) {
        val shape = endShape ?: return
        val handle = endHandle ?: return
        endAt = InkPoint(point.x + endGrab.x, point.y + endGrab.y)
        endPreview = shape.withEndOnPage(handle, endAt.x, endAt.y)
        invalidateDraw()
    }

    /**
     * Whether the point is on the selection's body, with [slack] of room around it.
     *
     * Exact for a rectangle, and not for a line: a horizontal line's bounds have no height, so a
     * body test against them demands the one row of pixels the line occupies. That was affordable
     * while a lassoed line also carried four corner handles 6dp outside those bounds.
     */
    private fun InkBounds.holdsBody(point: InkPoint, slack: Float): Boolean =
        point.x >= left - slack && point.x <= right + slack &&
            point.y >= top - slack && point.y <= bottom + slack

    private fun invalidateDraw() {
        renderRevision = if (renderRevision == Int.MAX_VALUE) 0 else renderRevision + 1
    }

    private fun InkPoint.scaled(anchor: InkPoint, x: Float, y: Float): InkPoint = InkPoint(
        anchor.x + (this.x - anchor.x) * x,
        anchor.y + (this.y - anchor.y) * y,
    )

    private fun lassoEdgeTolerance(toPage: Matrix): Float =
        toPage.pageSpan(LASSO_EDGE_TOLERANCE_PX)

    /**
     * [px] view pixels as page units, at whatever zoom the page is being drawn at.
     *
     * Both of the distances this gesture measures against are physical — how far a finger may wander
     * and still have tapped, how close to the edge of the loop still counts as inside it — so both are
     * stated in pixels and converted here rather than carried as page units that mean something
     * different at every zoom level.
     */
    private fun Matrix.pageSpan(px: Float): Float {
        val values = FloatArray(9)
        getValues(values)
        return px * hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
    }

    /**
     * Where this gesture tapped, or null when it travelled far enough to have meant a loop.
     *
     * Measured from the down point rather than to the up point: a tap is aimed, and the few pixels a
     * pen slides during the press are jitter, not a change of mind about what was being pointed at.
     */
    private fun List<InkPoint>.tapPoint(slop: Float): InkPoint? {
        val start = firstOrNull() ?: return null
        return start.takeIf { all { hypot(it.x - start.x, it.y - start.y) <= slop } }
    }

    /**
     * The corner handle under the point, or null.
     *
     * Measured from where the handle is drawn — a corner of the padded rectangle, not of the bounds
     * themselves — because that is the disc a finger aims at. The two are only
     * [SelectionChrome.PADDING] apart, but measuring from the wrong one is off-centre by that much
     * in both axes at once. The resize itself still measures against the raw bounds: see [handle],
     * where [Corner.point] is called without an outset for the anchor.
     *
     * [SelectionChrome.HANDLE_REACH] is a dp value read as page units, the same reading
     * `ShapeLayer.handleNear` makes of it. It was 10 device pixels before, so a selection was
     * markedly harder to grab on a dense screen and harder again the further the page was zoomed out.
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
