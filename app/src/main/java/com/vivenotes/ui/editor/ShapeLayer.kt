package com.vivenotes.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeContour
import com.vivenotes.model.ink.ShapeSegment
import com.vivenotes.model.ink.contours

internal const val SHAPE_LAYER_TAG = "shape-layer"

/**
 * The shapes on the page — `docs/inkPlan.md` §5.4.
 *
 * Sits **inside** the zoomed page layer, unlike `InkOverlay`. Ink has to live outside the zoom
 * because a front-buffered surface cannot be scaled without going soft; a shape is an ordinary
 * vector redrawn every frame, so it can simply be part of the page and inherit its transform.
 *
 * Drawn beneath the text containers: a shape is usually something writing sits on top of, and it
 * keeps the containers' own hit targets in front where a tap expects to find them. It is composed as
 * a *child* of the bare-canvas tap target for the same reason in the other direction — see the call
 * site in `EditorPane`, where that nesting is what decides which of the two owns a gesture.
 *
 * A shape is selected and moved **whole**. Its segments are how it is stored and drawn — they are
 * what carries the occluded edges a solid needs dotted — but they are not separately editable, so
 * there are no per-end handles and no way to pull one corner out of shape.
 *
 * Selection, four-corner resize, drag-to-move and double-tap-to-select are `docs/plan.md` AD7: they
 * belong to *an object on the canvas*, not to shapes, and ink already has the same set. The corner
 * geometry deliberately matches `LassoGesture`'s — same hit radius, same anchor at the opposite
 * corner — because an affordance that behaves differently depending on what is underneath it is
 * worse than not having it.
 */
@Composable
internal fun ShapeLayer(
    shapes: List<Outline.Shape>,
    /** The page's selection, which may hold ink as well. This layer reads only its shape half. */
    selection: CanvasSelection?,
    /**
     * The live lasso transform, so a shape inside a lasso move follows the finger with the ink rather
     * than jumping when the drag ends. Null when there is no lasso in play.
     */
    lassoGesture: LassoGesture? = null,
    /**
     * False when the shape tool is armed — a drag then draws a new shape — or when the lasso is, in
     * which case the overlay owns every gesture on the page and this layer must not also claim taps.
     */
    interactive: Boolean,
    onSelect: (CanvasSelection?) -> Unit,
    onMoveShape: (shapeId: String, dx: Float, dy: Float) -> Unit,
    onResizeShape: (shapeId: String, anchorX: Float, anchorY: Float, scaleX: Float, scaleY: Float) -> Unit =
        { _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface

    // Read by the gesture rather than captured by it. The handler below runs for the lifetime of the
    // layer and is never rebuilt, so everything it needs has to be reachable through a holder whose
    // own identity never changes.
    val currentShapes = rememberUpdatedState(shapes)
    val currentSelection = rememberUpdatedState(selection)
    val currentInteractive = rememberUpdatedState(interactive)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentOnMove = rememberUpdatedState(onMoveShape)
    val currentOnResize = rememberUpdatedState(onResizeShape)

    // The corner drag in flight, drawn but not yet written — see [ShapeResize]. A holder rather than
    // a delegated `var` for the same reason as the states above: the gesture handler below outlives
    // every recomposition and can only reach what has a stable identity.
    val resize = remember { mutableStateOf<ShapeResize?>(null) }

    Box(
        modifier
            .fillMaxSize()
            .testTag(SHAPE_LAYER_TAG)
            // Keyed on nothing, deliberately. `pointerInput(keys)` cancels its coroutine the moment
            // a key changes, and the restarted handler waits for a DOWN that a finger already on the
            // glass will never send — so keying this on the shapes was fatal: moving a shape rewrites
            // the list, the first applied delta killed the gesture that had asked for it, and the
            // scroll containers around the page picked the half-finished drag up and panned instead.
            // Nothing here may be keyed on document state; the current values are read above.
            .pointerInput(Unit) {
                // One gesture handler, not a tap detector plus a drag detector. Two `pointerInput`s
                // cannot share a gesture here: the tap arm has to consume the DOWN to stop it
                // reaching the canvas underneath, and `detectDragGestures` waits for an *unconsumed*
                // down — so consuming for the tap silently meant no drag ever began, and neither
                // move nor resize worked at all. Deciding once, on the down, is what makes both
                // possible.
                awaitEachGesture {
                    // Unconsumed only: anything nested deeper than this layer is dispatched to first
                    // and has already had its say.
                    val down = awaitFirstDown()
                    if (!currentInteractive.value) return@awaitEachGesture
                    val shapes = currentShapes.value
                    val held = currentSelection.value
                    val startX = down.position.x / density
                    val startY = down.position.y / density
                    // Only a lone shape gets handles here. A selection holding several objects — or any
                    // ink — is the lasso's to move, and the overlay owns that gesture.
                    val selected = shapes.singleOrNull {
                        held != null && held.isShapeOnly && held.holdsShape(it.id)
                    }
                    // A corner wins over the body: the handles sit on the boundary, so every one of
                    // them is also inside the move target.
                    val corner = selected?.cornerNear(startX, startY)

                    // The shape this gesture moves, chosen on the down and held for the whole of it:
                    // the selected one when the finger came down inside it, otherwise whatever it
                    // landed on. Grabbing an unselected shape therefore selects *and* drags it in one
                    // motion — demanding a separate tap first is indistinguishable from the drag not
                    // working, because what happens instead is that the page pans.
                    val target = when {
                        corner != null -> selected
                        selected?.contains(startX, startY) == true -> selected
                        else -> shapes.topmostNear(startX, startY)
                    }

                    // Nothing of ours under the finger, and no selection to clear: leave the gesture
                    // unconsumed for the tap target this layer sits inside, whose tap on bare canvas
                    // is what opens a text container.
                    if (target == null && held == null) return@awaitEachGesture
                    down.consume()

                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var last = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Every sample of a gesture that is ours to drag, moved or not — not only the
                        // ones past the slop. A single unconsumed sample with the finger still down is
                        // exactly what the scroll containers around the page are waiting for: since
                        // Compose 1.9 a scroll that lost the slop race keeps watching the final pass
                        // and picks the drag back up (`ComposeFoundationFlags.DragGesturePickUpEnabled`),
                        // so leaving the pre-slop samples free handed the rest of every shape drag to
                        // the page as a pan. When the gesture is *not* ours — a press held only so
                        // that a tap can clear the selection — the samples stay free on purpose,
                        // because that press is still a pan.
                        if (target != null) change.consume()
                        if (!change.pressed) {
                            change.consume()
                            // A press that never travelled is a tap, whatever it landed on. Reported
                            // as the target rather than as the shape under the finger, so that a tap
                            // on a handle keeps the selection the handle belongs to.
                            if (!dragging) {
                                currentOnSelect.value(target?.let(CanvasSelection::ofShape))
                            }
                            // The resize lands here, in one write, against the geometry it was
                            // measured from — see [ShapeResize].
                            resize.value?.let { pending ->
                                currentOnResize.value(
                                    pending.shapeId,
                                    pending.anchorX,
                                    pending.anchorY,
                                    pending.scaleX,
                                    pending.scaleY,
                                )
                            }
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > slop) {
                            dragging = true
                            last = change.position
                            // The handles and the tooltip belong to whatever is being dragged.
                            if (target != null && selected?.id != target.id) {
                                currentOnSelect.value(CanvasSelection.ofShape(target))
                            }
                        }
                        if (dragging && target != null) {
                            if (corner != null) {
                                // Measured against the shape as it was when the drag began, and only
                                // drawn: nothing is written until the finger lifts. See [ShapeResize].
                                target.scaleFor(
                                    corner,
                                    change.position.x / density,
                                    change.position.y / density,
                                )?.let { (scaleX, scaleY) ->
                                    val (anchorX, anchorY) = target.anchorFor(corner)
                                    resize.value = ShapeResize(
                                        target.id, anchorX, anchorY, scaleX, scaleY,
                                    )
                                }
                            } else {
                                val delta = change.position - last
                                currentOnMove.value(
                                    target.id, delta.x / density, delta.y / density,
                                )
                                last = change.position
                            }
                        }
                    }
                    // Whatever ended the gesture — the lift above, or a cancel that took the pointer
                    // away mid-drag. A cancel therefore discards the resize rather than committing a
                    // size the finger was still moving away from.
                    resize.value = null
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Read in the draw so a live lasso drag re-runs this and nothing above it, the same
            // reason the ruling takes its window as a lambda.
            val revision = lassoGesture?.renderRevision ?: 0
            val moving = lassoGesture?.takeIf { it.isTransforming && revision >= 0 }
            val held = selection?.shapeIds.orEmpty()
            val resizing = resize.value

            // The corner drag and the lasso are both preview-only, so both are applied here rather
            // than read back out of the document — and to the chrome as well as to the shape, or the
            // handles would sit around the size the drag started at.
            fun previewOf(shape: Outline.Shape): Outline.Shape = when {
                resizing?.shapeId == shape.id -> shape.scaledAbout(
                    resizing.anchorX, resizing.anchorY, resizing.scaleX, resizing.scaleY,
                )
                // The lasso's transform is in page units, which is what a shape's coordinates
                // already are — so it applies with no conversion, unlike ink, which needs it
                // folded into a matrix.
                moving != null && shape.id in held -> shape.withLassoPreview(moving)
                else -> shape
            }

            // Page units are dp, the same units an outline's (x, y) is in, so the geometry is scaled
            // by density. The selection box is not: it is chrome, and chrome keeps its weight.
            val pageScale = density
            withTransform({ scale(pageScale, pageScale, Offset.Zero) }) {
                shapes.forEach { shape -> drawShape(previewOf(shape)) }
            }
            // Handles only for a lone shape. A selection holding more than one object draws its
            // rectangle over in the overlay, around everything it holds, ink included.
            shapes.singleOrNull { selection?.isShapeOnly == true && it.id in held }
                ?.let { drawSelection(previewOf(it), accent, handleFill, pageScale) }
        }
    }
}

/**
 * A corner drag in flight: where the finger has taken the shape, measured from the geometry the drag
 * began with.
 *
 * **Preview only, committed once on the lift** — which is the whole point of it existing, and the
 * same way the lasso's own resize works. Applying it every frame instead was the bug: each frame
 * reported an absolute scale against the *starting* size, `resizeShape` applied it to the *current*
 * one, and a drag's frames multiplied together. Twenty frames of a smooth drag out to twice the size
 * came to roughly three thousand times it, and because the two axes compound at different rates the
 * shape lost its proportions on the way.
 *
 * A move stays per-frame, because a move reports a delta from the last frame rather than a total,
 * and deltas may safely be applied one after another.
 */
private data class ShapeResize(
    val shapeId: String,
    val anchorX: Float,
    val anchorY: Float,
    val scaleX: Float,
    val scaleY: Float,
)

/** The live lasso move or resize, applied for the draw only — nothing is written until the up. */
private fun Outline.Shape.withLassoPreview(gesture: LassoGesture): Outline.Shape {
    val offset = gesture.previewOffset()
    val scale = gesture.previewScale()
    val anchor = gesture.previewAnchor()
    return when {
        offset.x != 0f || offset.y != 0f -> translated(offset.x, offset.y)
        scale.x != 1f || scale.y != 1f -> scaledAbout(anchor.x, anchor.y, scale.x, scale.y)
        else -> this
    }
}

/** Fill first, then the border, so a stroke is never half-covered by the paint it surrounds. */
private fun DrawScope.drawShape(shape: Outline.Shape) {
    shape.fillArgb?.let { argb ->
        val region = Path()
        shape.segments.filterNot(ShapeSegment::hidden).forEachIndexed { index, segment ->
            val points = segment.polyline()
            if (index == 0) region.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) region.lineTo(points[i], points[i + 1])
        }
        region.close()
        drawPath(region, Color(argb))
    }

    val border = Color(shape.borderArgb)
    // By contour rather than by segment: a dash pattern restarts on every path it is given, so
    // stroking a rim's arcs one at a time doubles the dots at each joint. See [ShapeContour].
    shape.segments.contours().forEach { contour ->
        val effect = if (contour.hidden) {
            LineType.Dotted.effect(shape.borderWidth)
        } else {
            shape.lineType.effect(shape.borderWidth)
        }
        drawPath(
            path = contour.path(),
            color = border,
            style = Stroke(
                width = shape.borderWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = effect,
            ),
        )
    }
}

/**
 * A dashed box around the selected shape.
 *
 * The whole of the selection affordance, because the whole of the interaction is "this one, and it
 * moves". A line is thin and a wireframe is mostly empty, so without a box there would be nothing to
 * show a tap had landed until the shape started moving.
 */
private fun DrawScope.drawSelection(
    shape: Outline.Shape,
    accent: Color,
    handleFill: Color,
    scale: Float,
) {
    val padding = SELECTION_PADDING.toPx()
    val left = shape.x * scale - padding
    val top = shape.y * scale - padding
    val right = left + shape.width * scale + padding * 2
    val bottom = top + shape.height * scale + padding * 2

    drawRect(
        color = accent,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        style = Stroke(
            width = SELECTION_STROKE.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(SELECTION_DASH.toPx(), SELECTION_DASH.toPx()),
            ),
        ),
    )

    // Four corners, drawn the way the lasso draws its own — white disc, accent ring — so the two
    // selections are the same affordance rather than two that merely do the same thing (AD7).
    val radius = HANDLE_RADIUS.toPx()
    listOf(left to top, right to top, right to bottom, left to bottom).forEach { (x, y) ->
        drawCircle(handleFill, radius, Offset(x, y))
        drawCircle(accent, radius, Offset(x, y), style = Stroke(width = SELECTION_STROKE.toPx()))
    }
}

private enum class Corner { TopLeft, TopRight, BottomRight, BottomLeft }

private fun Outline.Shape.cornerPoint(corner: Corner): Pair<Float, Float> = when (corner) {
    Corner.TopLeft -> x to y
    Corner.TopRight -> (x + width) to y
    Corner.BottomRight -> (x + width) to (y + height)
    Corner.BottomLeft -> x to (y + height)
}

/** The corner that stays put: the one opposite the one being dragged. */
private fun Outline.Shape.anchorFor(corner: Corner): Pair<Float, Float> = cornerPoint(
    when (corner) {
        Corner.TopLeft -> Corner.BottomRight
        Corner.TopRight -> Corner.BottomLeft
        Corner.BottomRight -> Corner.TopLeft
        Corner.BottomLeft -> Corner.TopRight
    },
)

private fun Outline.Shape.cornerNear(x: Float, y: Float): Corner? = Corner.entries
    .minByOrNull { corner ->
        val (cx, cy) = cornerPoint(corner)
        kotlin.math.hypot(x - cx, y - cy)
    }
    ?.takeIf { corner ->
        val (cx, cy) = cornerPoint(corner)
        kotlin.math.hypot(x - cx, y - cy) <= HANDLE_REACH.value
    }

/**
 * How far the dragged corner has travelled from the anchor, as a scale factor.
 *
 * Null for a shape with no extent on an axis — a horizontal line has zero height, and dividing by it
 * would send every point to infinity. Those resize on their long axis alone.
 */
private fun Outline.Shape.scaleFor(corner: Corner, x: Float, y: Float): Pair<Float, Float>? {
    val (anchorX, anchorY) = anchorFor(corner)
    val (originX, originY) = cornerPoint(corner)
    val spanX = originX - anchorX
    val spanY = originY - anchorY
    val scaleX = if (spanX == 0f) 1f else ((x - anchorX) / spanX)
    val scaleY = if (spanY == 0f) 1f else ((y - anchorY) / spanY)
    if (!scaleX.isFinite() || !scaleY.isFinite()) return null
    return scaleX.coerceAtLeast(MIN_SCALE) to scaleY.coerceAtLeast(MIN_SCALE)
}

private fun ShapeContour.path(): Path = Path().apply {
    val points = polyline()
    moveTo(points[0], points[1])
    for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
    // A ring closed by the stroker joins at the seam instead of butting two caps together.
    if (isClosed) close()
}

private fun LineType.effect(width: Float): PathEffect? = when (this) {
    LineType.Solid -> null
    LineType.Dashed -> PathEffect.dashPathEffect(floatArrayOf(width * 2.6f, width * 1.8f))
    LineType.Dotted -> PathEffect.dashPathEffect(floatArrayOf(0.01f, width * 2f))
}

/** The topmost shape within reach of the point, or null. Drawing order decides ties. */
private fun List<Outline.Shape>.topmostNear(x: Float, y: Float): Outline.Shape? = asReversed()
    .firstOrNull { shape ->
        shape.segments.any { it.distanceTo(x, y) <= TOUCH_REACH.value + shape.borderWidth / 2f }
    }

private fun Outline.Shape.contains(x: Float, y: Float): Boolean {
    val slack = TOUCH_REACH.value
    return x >= this.x - slack && x <= this.x + width + slack &&
        y >= this.y - slack && y <= this.y + height + slack
}

/** A line is thin; the target for one is not. */
private val TOUCH_REACH: Dp = 12.dp
private val SELECTION_PADDING: Dp = 6.dp
private val SELECTION_STROKE: Dp = 1.5.dp
private val SELECTION_DASH: Dp = 4.dp
private val HANDLE_RADIUS: Dp = 5.5.dp

/** Matches LassoGesture's own handle reach, so the two selections grab the same way. */
private val HANDLE_REACH: Dp = 14.dp

/** Never through zero: a shape flipped inside out by a fast drag cannot be dragged back. */
private const val MIN_SCALE = 0.12f
