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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivenotes.data.automaticColorOr
import com.vivenotes.ui.theme.LocalCanvasColors
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageBounds
import com.vivenotes.ink.TAP_REACH
import com.vivenotes.ink.pageBounds
import com.vivenotes.ink.withEndOnPage
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeArm
import com.vivenotes.model.ink.ShapeAxis
import com.vivenotes.model.ink.ShapeContour
import com.vivenotes.model.ink.ShapeEnd
import com.vivenotes.model.ink.ShapeSegment
import com.vivenotes.model.ink.arms
import com.vivenotes.model.ink.endNear
import com.vivenotes.model.ink.ends
import com.vivenotes.model.ink.fillRegion
import com.vivenotes.model.ink.contours
import com.vivenotes.model.ink.withArm
import com.vivenotes.ui.panel.pathEffect
import kotlin.math.hypot

internal const val SHAPE_LAYER_TAG = "shape-layer"

/**
 * The shapes on the page — `memory/inkPlan.md` §5.4.
 *
 * Sits **inside** the zoomed page layer, unlike `InkOverlay`. Ink has to live outside the zoom
 * because a front-buffered surface cannot be scaled without going soft; a shape is an ordinary
 * vector redrawn every frame, so it can simply be part of the page and inherit its transform.
 *
 * Drawn — and hit-tested — **in front of** the page's text containers. It used to be behind them, on
 * the reading that a shape is something writing sits on top of; what that actually bought was a
 * shape that could not be tapped at all once one was dragged over a container, because a container's
 * editor is a real Android View and is handed the DOWN as the event tunnels past it, before any
 * bubbling-pass handler runs. This layer claims its own DOWN on that same tunnelling pass, which is
 * what puts Prime Object in front of the writing. The cost is the mirror image: a shape deliberately
 * parked *behind* a container now takes the taps that land inside it, and has to be moved off to
 * type there.
 *
 * It is the innermost of the three object layers and so has no slot of its own — [EquationLayer]
 * holds it, [ImageLayer] holds that, and the call site in `EditorPane` sets out the whole order.
 *
 * A shape is selected and moved **whole**, and with two exceptions it is resized whole too. Its
 * segments are how it is stored and drawn — they are what carries the occluded edges a solid needs
 * dotted — but they are not separately editable, so there is no way to pull one corner of a hexagon
 * out of shape. The exceptions are both *ends*, and a kind declares which sort it has:
 *
 * - an **arm end** ([ShapeArm]), on a kind with arms: the L has four, head and tail of each arm, and
 *   each drags along its own axis alone. Pulling a tail back past the corner turns an L into a
 *   cross; pulling one back *through* the other arm is the one thing it may not do, since that is
 *   the L coming apart rather than changing shape. See SD9.
 * - a **line end** ([ShapeEnd]), on a kind that is a line: the line and the arrow carry one handle
 *   per end instead of four corners, and each drags in both directions at once, so moving one across
 *   the line turns the shape rather than stretching it. See SD12.
 *
 * Selection, four-corner resize, drag-to-move and double-tap-to-select are `memory/plan.md` AD7: they
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
    /**
     * What the page can currently show, in page units multiplied by density.
     *
     * Called *inside* the draw scope, never during composition — `PageRuling`'s idiom, and the reason
     * scrolling re-runs the draw and nothing above it.
     */
    visibleWindow: () -> Rect,
    onSelect: (CanvasSelection?) -> Unit,
    onMoveShape: (shapeId: String, dx: Float, dy: Float) -> Unit,
    onResizeShape: (shapeId: String, anchorX: Float, anchorY: Float, scaleX: Float, scaleY: Float) -> Unit =
        { _, _, _, _, _ -> },
    /** One arm's free end, moved to [along] on its own axis — see [ShapeArm]. */
    onResizeShapeArm: (shapeId: String, segmentId: String, atEnd: Boolean, along: Float) -> Unit =
        { _, _, _, _ -> },
    /** One end of a line or an arrow, moved to where the finger left it — see [ShapeEnd]. */
    onMoveShapeEnd: (shapeId: String, atEnd: Boolean, x: Float, y: Float) -> Unit =
        { _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface
    // What a border drawn with the automatic colour paints as on this page — the canvas's ink, the
    // same source the text and the ink use. See `automaticColorOr`.
    val canvasInkArgb = LocalCanvasColors.current.text.toArgb()

    // Read by the gesture rather than captured by it. The handler below runs for the lifetime of the
    // layer and is never rebuilt, so everything it needs has to be reachable through a holder whose
    // own identity never changes.
    val currentShapes = rememberUpdatedState(shapes)
    val currentSelection = rememberUpdatedState(selection)
    val currentInteractive = rememberUpdatedState(interactive)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentOnMove = rememberUpdatedState(onMoveShape)
    val currentOnResize = rememberUpdatedState(onResizeShape)
    val currentOnResizeArm = rememberUpdatedState(onResizeShapeArm)
    val currentOnMoveEnd = rememberUpdatedState(onMoveShapeEnd)

    // The corner drag in flight, drawn but not yet written — see [ShapeResize]. A holder rather than
    // a delegated `var` for the same reason as the states above: the gesture handler below outlives
    // every recomposition and can only reach what has a stable identity.
    val resize = remember { mutableStateOf<ShapeResize?>(null) }

    /** The arm drag in flight, held and committed the same way the corner drag is. */
    val armResize = remember { mutableStateOf<ShapeArmResize?>(null) }

    /** And a line's end, likewise — see [ShapeEndMove]. */
    val endMove = remember { mutableStateOf<ShapeEndMove?>(null) }

    /** And the move, for the reason [ShapeMove] gives — which is undo's, not arithmetic's. */
    val move = remember { mutableStateOf<ShapeMove?>(null) }

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
                    // Unconsumed only, and on the **tunnelling** pass — which is what puts this
                    // layer in front of the text containers rather than behind them.
                    // `sharingTouchesWithSiblings` and the call site in `EditorPane` are where that
                    // is argued out; the two layers wrapping this one are asked first, on the same
                    // pass, so a picture or a formula over a shape still takes its own touch. The
                    // rest of the gesture stays on the bubbling pass, where consuming a sample still
                    // beats the scroll containers around the page.
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
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
                    // A handle wins over the body: they sit on the boundary or just outside it, so
                    // every one of them is also inside the move target.
                    // No handles on a locked object — `memory/diagram.md`. Absent rather than
                    // present and dead, the rule the toolkit follows, and the chrome that draws them
                    // is gated on the same field.
                    val handle = selected?.takeIf { it.lockGroup == null }?.handleNear(startX, startY)

                    // An arm tab sits a little beyond the tip it belongs to, so the finger is never
                    // on the tip it is about to move. Holding that distance for the gesture keeps the
                    // arm following the finger instead of jumping to it on the first sample.
                    val armGrab = (handle as? Handle.Arm)?.arm?.let { arm ->
                        arm.along - if (arm.axis == ShapeAxis.Horizontal) startX else startY
                    } ?: 0f

                    // The same, in both directions at once: a line's end handle is drawn *on* its
                    // end, so the offset is only however far off centre the finger landed — but
                    // without it the end still jumps that far on the first sample, and on a short
                    // line that is most of its length.
                    val endGrab = (handle as? Handle.End)?.end?.let { end ->
                        Offset(end.x - startX, end.y - startY)
                    } ?: Offset.Zero

                    // The shape this gesture moves, chosen on the down and held for the whole of it:
                    // the selected one when the finger came down inside it, otherwise whatever it
                    // landed on. Grabbing an unselected shape therefore selects *and* drags it in one
                    // motion — demanding a separate tap first is indistinguishable from the drag not
                    // working, because what happens instead is that the page pans.
                    val target = when {
                        handle != null -> selected
                        selected?.contains(startX, startY) == true -> selected
                        else -> shapes.topmostNear(startX, startY)
                    }

                    // Nothing of ours under the finger: leave the gesture alone entirely. It falls
                    // to the text container under it if there is one, and then to the bare-canvas
                    // tap target, which is what clears the page's selection and what opens a new
                    // container. Clearing the selection used to be done here, by consuming a down
                    // with no target — that was affordable while this layer sat *behind* the
                    // containers and never heard a tap meant for one. In front of them it is not:
                    // it would eat every tap into a text box for as long as anything was selected.
                    if (target == null) return@awaitEachGesture
                    down.consume()

                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var last = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Every sample, moved or not — not only the ones past the slop. A single
                        // unconsumed sample with the finger still down is exactly what the scroll
                        // containers around the page are waiting for: since Compose 1.9 a scroll that
                        // lost the slop race keeps watching the final pass and picks the drag back up
                        // (`ComposeFoundationFlags.DragGesturePickUpEnabled`), so leaving the pre-slop
                        // samples free handed the rest of every shape drag to the page as a pan.
                        change.consume()
                        if (!change.pressed) {
                            // A press that never travelled is a tap, whatever it landed on. Reported
                            // as the target rather than as the shape under the finger, so that a tap
                            // on a handle keeps the selection the handle belongs to.
                            if (!dragging) {
                                currentOnSelect.value(CanvasSelection.ofShape(target))
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
                            armResize.value?.let { pending ->
                                currentOnResizeArm.value(
                                    pending.shapeId,
                                    pending.arm.segmentId,
                                    pending.arm.atEnd,
                                    pending.along,
                                )
                            }
                            endMove.value?.let { pending ->
                                currentOnMoveEnd.value(
                                    pending.shapeId,
                                    pending.atEnd,
                                    pending.x,
                                    pending.y,
                                )
                            }
                            move.value?.let { pending ->
                                currentOnMove.value(pending.shapeId, pending.dx, pending.dy)
                            }
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > slop) {
                            dragging = true
                            last = change.position
                            // The handles and the tooltip belong to whatever is being dragged.
                            if (selected?.id != target.id) {
                                currentOnSelect.value(CanvasSelection.ofShape(target))
                            }
                        }
                        // **A locked object refuses the transform, not the touch.** The press
                        // still selects it — that is the only way to reach the bar the unlock button
                        // is on — and the drag simply does nothing, rather than falling through to
                        // the canvas and drawing a lasso over the thing under the finger.
                        if (dragging && target.lockGroup == null) {
                            when (handle) {
                                // Measured against the shape as it was when the drag began, and only
                                // drawn: nothing is written until the finger lifts. See [ShapeResize].
                                is Handle.Box -> target.scaleFor(
                                    handle.corner,
                                    change.position.x / density,
                                    change.position.y / density,
                                )?.let { (scaleX, scaleY) ->
                                    val (anchorX, anchorY) = target.anchorFor(handle.corner)
                                    // A corner dragged outward takes the opposite edges towards the
                                    // origin, and the page has no left or top to scroll to — so the
                                    // scale stops where they reach it. Clamped on the preview rather
                                    // than on the lift, or the shape would grow past the wall under
                                    // the finger and shrink back when it is let go. [PageBounds]
                                    val held = PageBounds.clampScale(
                                        target.pageBounds(),
                                        InkPoint(anchorX, anchorY),
                                        scaleX,
                                        scaleY,
                                    )
                                    resize.value = ShapeResize(
                                        target.id, anchorX, anchorY, held.x, held.y,
                                    )
                                }

                                // One coordinate, because an arm has one direction to go in. The
                                // other axis of the finger's travel is deliberately thrown away:
                                // pulling an L's foot out to the right must not also drift it down.
                                is Handle.Arm -> {
                                    val along = if (handle.arm.axis == ShapeAxis.Horizontal) {
                                        change.position.x / density
                                    } else {
                                        change.position.y / density
                                    }
                                    // The tip is the only point this drag moves, and `along` is
                                    // where it lands on its own axis — so holding that one number
                                    // to the origin is the whole of the rule here. The rest of the
                                    // shape was already on the page. [PageBounds]
                                    armResize.value = ShapeArmResize(
                                        target.id,
                                        handle.arm,
                                        (along + armGrab).coerceAtLeast(0f),
                                    )
                                }

                                // Both coordinates, because a line's end has no axis of its own —
                                // which is the whole difference from an arm. Taking the finger's
                                // travel across the line as well as along it is what turns the
                                // shape: the end goes where the finger is, and the line follows.
                                is Handle.End -> {
                                    // Reported raw. The origin corner is still a wall — but the end
                                    // is aimed onto an eighth-turn *after* any clamp here would
                                    // have run, so holding it to the page is `withEndOnPage`'s, and
                                    // clamping the finger as well would only bend the angle it is
                                    // about to snap.
                                    val point = change.position / density + endGrab
                                    endMove.value =
                                        ShapeEndMove(target.id, handle.end.atEnd, point.x, point.y)
                                }

                                // Measured from where the drag began rather than from the last
                                // sample, so the travel is one number the whole way through and the
                                // touch slop is not folded into it.
                                null -> {
                                    val travel = change.position - last
                                    // Only as far as keeps the shape on the page: the origin corner
                                    // is a wall, not somewhere to be dropped — [PageBounds].
                                    val held = PageBounds.clampTranslation(
                                        target.pageBounds(),
                                        travel.x / density,
                                        travel.y / density,
                                    )
                                    move.value = ShapeMove(target.id, held.x, held.y)
                                }
                            }
                        }
                    }
                    // Whatever ended the gesture — the lift above, or a cancel that took the pointer
                    // away mid-drag. A cancel therefore discards the resize rather than committing a
                    // size the finger was still moving away from.
                    resize.value = null
                    armResize.value = null
                    endMove.value = null
                    move.value = null
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
            val arming = armResize.value
            val ending = endMove.value
            val nudging = move.value

            // The corner drag and the lasso are both preview-only, so both are applied here rather
            // than read back out of the document — and to the chrome as well as to the shape, or the
            // handles would sit around the size the drag started at. An arm drag is the same, which
            // is also what carries its tab along with the tip it is pulling.
            fun previewOf(shape: Outline.Shape): Outline.Shape = when {
                resizing?.shapeId == shape.id -> shape.scaledAbout(
                    resizing.anchorX, resizing.anchorY, resizing.scaleX, resizing.scaleY,
                )
                arming?.shapeId == shape.id -> shape.withArm(arming.arm, arming.along)
                ending?.shapeId == shape.id -> shape.ends()
                    .firstOrNull { it.atEnd == ending.atEnd }
                    ?.let { shape.withEndOnPage(it, ending.x, ending.y) } ?: shape
                nudging?.shapeId == shape.id -> shape.translated(nudging.dx, nudging.dy)
                // The lasso's transform is in page units, which is what a shape's coordinates
                // already are — so it applies with no conversion, unlike ink, which needs it
                // folded into a matrix.
                moving != null && shape.id in held -> shape.withLassoPreview(moving)
                else -> shape
            }

            // Page units are dp, the same units an outline's (x, y) is in, so the geometry is scaled
            // by density. The selection box is not: it is chrome, and chrome keeps its weight.
            val pageScale = density
            // Read here rather than captured, so scrolling re-runs the draw and not the composition.
            val window = visibleWindow()
            withTransform({ scale(pageScale, pageScale, Offset.Zero) }) {
                shapes.forEach { shape ->
                    val drawn = previewOf(shape)
                    // A shape off the edge of the window is a path per segment that nobody can see.
                    if (!pageBoxIsVisible(
                            drawn.x, drawn.y, drawn.width, drawn.height, window, pageScale,
                        )
                    ) {
                        return@forEach
                    }
                    drawShape(drawn, canvasInkArgb)
                }
            }
            // Handles only for a lone shape, and only while this layer is the one that would
            // service them. A selection holding more than one object draws its rectangle over in
            // the overlay, around everything it holds, ink included — and so does a selection of
            // *any* size once the lasso is armed, because the lasso owns every gesture on the page
            // then ([interactive]). Drawing a second set here in that state put two rectangles a
            // few dp apart around one shape, each with its own four corner discs, of which only the
            // overlay's answered a finger.
            shapes.takeIf { lassoGesture == null }
                ?.singleOrNull { selection?.isShapeOnly == true && it.id in held }
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
 * The move followed later and for a different reason — see [ShapeMove]. Every gesture on this layer
 * now previews and commits once, which is also why the tooltip holds still through a drag and lands
 * with it: the selection it anchors to is the committed geometry, and the preview is only drawn.
 */
private data class ShapeResize(
    val shapeId: String,
    val anchorX: Float,
    val anchorY: Float,
    val scaleX: Float,
    val scaleY: Float,
)

/**
 * An arm drag in flight: where along its own axis the finger has taken one free end.
 *
 * Held and committed exactly as [ShapeResize] is, though for a weaker reason — an arm drag reports
 * an absolute coordinate rather than a scale, so applying it every frame would not compound and
 * would not explode. It waits for the lift so that a drag is one document edit and one autosave
 * instead of sixty, and so that a cancelled gesture leaves nothing behind.
 */
private data class ShapeArmResize(
    val shapeId: String,
    val arm: ShapeArm,
    val along: Float,
)

/**
 * A line's end in flight: where the finger has taken one end of a line or an arrow.
 *
 * Two coordinates rather than [ShapeArmResize]'s one, because that is the difference between the two
 * gestures — an arm end runs on rails and a line's end does not. Held and committed the same way, and
 * for the same reasons: one document edit, one autosave, nothing left behind by a cancel.
 */
private data class ShapeEndMove(
    val shapeId: String,
    val atEnd: Boolean,
    val x: Float,
    val y: Float,
)

/**
 * A move in flight: how far the shape has travelled since the drag began.
 *
 * A move used to be the one gesture here that wrote every frame, which was safe arithmetic — deltas
 * compose — and the wrong *action*. Undo reverses actions, so a drag reported sixty times was sixty
 * presses of Undo to put back, and sixty autosaves on the way there. It reports the whole travel on
 * the lift now, like the other two, and the frames in between are drawn from here.
 */
private data class ShapeMove(
    val shapeId: String,
    val dx: Float,
    val dy: Float,
)

/**
 * The live lasso move, resize or end drag, applied for the draw only — nothing is written until the up.
 *
 * `internal` and shared with `InkOverlay`, which draws the lasso's own chrome: the handles have to
 * sit on the shape as it is being previewed, and a second copy of this rule is a second answer to
 * where the shape currently is.
 */
internal fun Outline.Shape.withLassoPreview(gesture: LassoGesture): Outline.Shape {
    gesture.lineEndPreview()?.takeIf { it.id == id }?.let { return it }
    val offset = gesture.previewOffset()
    val scale = gesture.previewScale()
    val anchor = gesture.previewAnchor()
    return when {
        offset.x != 0f || offset.y != 0f -> translated(offset.x, offset.y)
        scale.x != 1f || scale.y != 1f -> scaledAbout(anchor.x, anchor.y, scale.x, scale.y)
        else -> this
    }
}

/**
 * Fill first, then the border, so a stroke is never half-covered by the paint it surrounds.
 *
 * [canvasInkArgb] is what a border drawn with the automatic colour resolves to on this page — the
 * same treatment the ink beside it gets, so Switch Background does not leave a white outline on
 * white paper. Only the border follows: a fill is a colour that was chosen off a palette, never an
 * automatic one, so flipping it would be overriding a decision rather than completing one.
 */
private fun DrawScope.drawShape(shape: Outline.Shape, canvasInkArgb: Int) {
    // What a shape covers, which is not the path it is stroked along — see `fillRegion`. Walking the
    // visible segments as one path is what this did, and it was only ever right for a shape with a
    // single closed contour: it filled a cube's twelve edges as one self-crossing loop, and would
    // have closed an L into a triangle the moment fill became reachable.
    shape.fillArgb?.let { argb ->
        shape.fillRegion().forEach { region ->
            drawPath(region.asClosedPath(), Color(argb))
        }
    }

    val border = Color(
        automaticColorOr(shape.borderArgb, shape.borderFollowsTheme, canvasInkArgb),
    )
    // By contour rather than by segment: a dash pattern restarts on every path it is given, so
    // stroking a rim's arcs one at a time doubles the dots at each joint. See [ShapeContour].
    shape.segments.contours().forEach { contour ->
        val effect = if (contour.hidden) {
            LineType.Dotted.pathEffect(shape.borderWidth)
        } else {
            shape.lineType.pathEffect(shape.borderWidth)
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
 * A dashed box around the selected shape, with its handles on it.
 *
 * The box is most of the selection affordance, because the whole of the interaction is "this one, and
 * it moves": a wireframe is mostly empty, so without it there would be nothing to show a tap had
 * landed until the shape started moving.
 *
 * **A line and an arrow get no box** (SD12). Theirs would be a rectangle drawn around something that
 * is not a rectangle — degenerate on a straight line, and on a diagonal one a box whose corners the
 * shape does not go near — and it advertises a resize those kinds do not have. The two end handles
 * are the whole of it, and two discs on the line's own ends say "this one" clearly enough, which the
 * bare box never did for a shape it fits this badly.
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

    // The four corner discs are the box's, drawn the way the lasso draws its own — surface disc,
    // accent ring, and the radius off [SelectionChrome] — so the two selections are the same
    // affordance rather than two that merely do the same thing (AD7).
    //
    // A line has neither: two handles on its own two ends, and no box behind them. The same disc, in
    // a different place, because it is the same *kind* of grab — take this point and put it
    // somewhere — rather than four on a box, half of which have no side to pull on and none of which
    // can turn the line.
    // A locked line takes the box as well, though it has no box of its own: its whole chrome is
    // the two end handles, and dropping those without putting something in their place would leave a
    // selected shape with nothing drawn on it at all.
    val ends = shape.ends()
    val points = if (ends.isEmpty() || shape.lockGroup != null) {
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
        listOf(left to top, right to top, right to bottom, left to bottom)
    } else {
        ends.map { end -> (end.x * scale) to (end.y * scale) }
    }

    // **Locked: the rectangle stays, the grabs go** — `memory/diagram.md`. What is held still has to
    // be visible, or the bar would float over nothing; what must not be there is a handle that
    // cannot be dragged, which is the rule the toolkit follows for an action a kind cannot perform.
    if (shape.lockGroup != null) return

    val radius = HANDLE_RADIUS.toPx()
    points.forEach { (x, y) ->
        drawCircle(handleFill, radius, Offset(x, y))
        drawCircle(accent, radius, Offset(x, y), style = Stroke(width = SELECTION_STROKE.toPx()))
    }

    shape.arms().forEach { arm -> drawArmHandle(arm, accent, handleFill, scale) }
}

/**
 * The tab that moves one end of one arm, out beyond that end on the arm's own line.
 *
 * **A bar, not a disc, and not on the end itself.** An L's outer tips are two corners of its own
 * bounding box, so a handle drawn where an arm ends would sit on top of a corner handle that does
 * something else entirely — the same grab would scale the whole shape half the time. Pushing it out
 * along the axis separates the two targets by more than a finger, and the leader back to the end is
 * what says which arm the tab belongs to rather than leaving it floating beside the box.
 *
 * It is also what keeps the *two* tabs at an L's corner apart. They sit on the same point of the
 * shape, but each is offset along its own arm — one down the upright, one back along the foot — and
 * those directions are perpendicular, so the tabs land a comfortable distance from each other.
 *
 * Same materials as the corner handles — surface fill, accent ring — because it is the same
 * selection; a different *form*, because it is a different gesture (AD7). It lies across the
 * direction it drags in, the way a divider does.
 */
private fun DrawScope.drawArmHandle(arm: ShapeArm, accent: Color, handleFill: Color, scale: Float) {
    val horizontal = arm.axis == ShapeAxis.Horizontal
    val tipX = arm.x * scale
    val tipY = arm.y * scale
    val gap = ARM_HANDLE_GAP.toPx() * arm.outward
    val centreX = if (horizontal) tipX + gap else tipX
    val centreY = if (horizontal) tipY else tipY + gap

    drawLine(
        color = accent,
        start = Offset(tipX, tipY),
        end = Offset(centreX, centreY),
        strokeWidth = SELECTION_STROKE.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(SELECTION_DASH.toPx(), SELECTION_DASH.toPx()),
        ),
    )

    val halfLong = ARM_HANDLE_LENGTH.toPx() / 2f
    val halfThick = ARM_HANDLE_THICKNESS.toPx() / 2f
    val halfX = if (horizontal) halfThick else halfLong
    val halfY = if (horizontal) halfLong else halfThick
    val topLeft = Offset(centreX - halfX, centreY - halfY)
    val size = Size(halfX * 2f, halfY * 2f)
    val corner = CornerRadius(halfThick, halfThick)

    drawRoundRect(handleFill, topLeft, size, corner)
    drawRoundRect(
        color = accent,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
        style = Stroke(width = SELECTION_STROKE.toPx()),
    )
}

private enum class Corner { TopLeft, TopRight, BottomRight, BottomLeft }

/** What the finger came down on: one of the four corners, one arm's tab, or a line's own end. */
private sealed interface Handle {
    data class Box(val corner: Corner) : Handle
    data class Arm(val arm: ShapeArm) : Handle
    data class End(val end: ShapeEnd) : Handle
}

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

/**
 * The handle under the point, or null — corners and arm tabs judged together, nearest wins.
 *
 * Together rather than one before the other because on an L they are neighbours: three of its four
 * arm tabs sit out beyond an end that is itself a corner of the bounding box. Giving either kind
 * priority would mean a touch squarely on one of them sometimes taking the other, so the only honest
 * rule is distance — and it is what puts the boundary between the two exactly halfway along the gap
 * the tab is drawn across.
 *
 * A line's two ends are not in that contest: they are the *whole* handle set for their kinds, and the
 * corners are not offered at all (SD12).
 *
 * Page units, so [HANDLE_REACH] and [ARM_HANDLE_GAP] are read as plain dp — the same numbers the
 * draw scales by density, and the reason the two cannot disagree about where a handle is.
 */
private fun Outline.Shape.handleNear(x: Float, y: Float): Handle? {
    // A line's ends *replace* its corners rather than joining them: the two sets would sit on top of
    // each other on a diagonal line — its ends are two corners of its own bounding box — and the
    // grab that turned the line would half the time scale it instead. A kind has one or the other.
    if (kind.hasEnds) return endNear(x, y, HANDLE_REACH.value)?.let(Handle::End)

    val candidates = Corner.entries.map { corner ->
        val (cx, cy) = cornerPoint(corner)
        Handle.Box(corner) to hypot(x - cx, y - cy)
    } + arms().map { arm ->
        val (hx, hy) = arm.handlePoint()
        Handle.Arm(arm) to hypot(x - hx, y - hy)
    }

    return candidates
        .minByOrNull { (_, distance) -> distance }
        ?.takeIf { (_, distance) -> distance <= HANDLE_REACH.value }
        ?.first
}

/** Where an arm end's tab sits: out past that end, on the arm's own line. In page units. */
private fun ShapeArm.handlePoint(): Pair<Float, Float> {
    val gap = ARM_HANDLE_GAP.value * outward
    return if (axis == ShapeAxis.Horizontal) (x + gap) to y else x to (y + gap)
}

/**
 * How far this shape's chrome stands above and below its own bounds, in page units.
 *
 * For the object tooltip, which anchors to the selection and so has to know that a selection is
 * bigger than its geometry. A vertical arm's tab sits out past the top or the bottom of the shape,
 * which is exactly where the bar wants to go — and on an L the two coincide, because the arm ends at
 * the edge of the bounding box the tooltip is measuring from. The bar landed on top of the handle,
 * and a handle under a bar cannot be grabbed.
 *
 * Only the vertical arms, because the tooltip only ever sits above or below. A horizontal tab is out
 * to the side, where the bar never goes, and inflating sideways would shift the bar off centre for
 * nothing. Zero for every shape without arms, which is every shape but the L.
 */
internal fun Outline.Shape.armChromeExtent(): Pair<Float, Float> {
    var above = 0f
    var below = 0f
    arms().filter { it.axis == ShapeAxis.Vertical }.forEach { arm ->
        val reach = ARM_HANDLE_GAP.value + ARM_HANDLE_LENGTH.value / 2f
        if (arm.outward < 0f) {
            above = maxOf(above, reach - (arm.y - y))
        } else {
            below = maxOf(below, reach - ((y + height) - arm.y))
        }
    }
    return above.coerceAtLeast(0f) to below.coerceAtLeast(0f)
}

/**
 * How far the dragged corner has travelled from the anchor, as a scale factor.
 *
 * Null for a shape with no extent on an axis — one dragged out with no travel in y has zero height,
 * and dividing by it would send every point to infinity. Those resize on their long axis alone. The
 * line and the arrow, which are the kinds that are *always* flat, never reach this: they carry end
 * handles instead of corners (SD12), and this is only ever called for a corner.
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

/** A region as a closed path, for filling. */
private fun FloatArray.asClosedPath(): Path = Path().apply {
    moveTo(this@asClosedPath[0], this@asClosedPath[1])
    for (index in 2 until this@asClosedPath.size step 2) {
        lineTo(this@asClosedPath[index], this@asClosedPath[index + 1])
    }
    close()
}

private fun ShapeContour.path(): Path = Path().apply {
    val points = polyline()
    moveTo(points[0], points[1])
    for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
    // A ring closed by the stroker joins at the seam instead of butting two caps together.
    if (isClosed) close()
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

/**
 * A line is thin; the target for one is not.
 *
 * Read from `ink/CanvasSelection.kt` rather than written here, because the lasso's tap hit-tests the
 * same shapes by the same rule (`selectByTap`) and two numbers that merely happen to agree are what
 * [SelectionChrome] exists to stop.
 */
private val TOUCH_REACH: Dp = TAP_REACH.dp

private val SELECTION_PADDING: Dp = SelectionChrome.PADDING
private val SELECTION_STROKE: Dp = SelectionChrome.STROKE
private val SELECTION_DASH: Dp = SelectionChrome.DASH
private val HANDLE_RADIUS: Dp = SelectionChrome.HANDLE_RADIUS
private val HANDLE_REACH: Dp = SelectionChrome.HANDLE_REACH

/**
 * What a selected object on the canvas looks like, and how near a finger has to come to grab it.
 *
 * **One set of numbers rather than several that happen to agree.** AD7 makes selection a page-level
 * idea, so a handle that a finger misses on an equation but catches on a shape is exactly the
 * inconsistency it argues against — and these are drawn by two layers now ([ShapeLayer] and
 * [EquationLayer]) with a third kind certain to follow.
 *
 * `TableContainer` keeps its own radius on purpose: its handle is a composed target with its own hit
 * area, not a disc painted into a canvas, so it is a different thing that happens to look similar.
 */
/**
 * Whether a page-space box is worth drawing — the object half of `memory/inkPlan.md` §3.2.
 *
 * Ink got this first, and for the obvious reason: a page can hold ten thousand strokes. The document
 * kinds got it late and are *worse* per element, not better — a shape is a path per segment, a formula
 * is a display list, a picture is a decoded bitmap and a texture upload — so a page of them off the
 * edge of the window costs more each than a stroke does.
 *
 * [x], [y], [width] and [height] are page units; [window] is what `EditorPane` computes for
 * `PageRuling`, which is page units multiplied by density. Both are converted here rather than at
 * three call sites, because a culling test that disagrees with itself between layers shows up as one
 * kind of object vanishing and nothing else.
 */
internal fun pageBoxIsVisible(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    window: Rect,
    density: Float,
): Boolean {
    val left = x * density
    val top = y * density
    return left + width * density >= window.left && left <= window.right &&
        top + height * density >= window.top && top <= window.bottom
}

internal object SelectionChrome {
    val PADDING: Dp = 6.dp
    val STROKE: Dp = 1.5.dp
    val DASH: Dp = 4.dp
    val HANDLE_RADIUS: Dp = 5.5.dp

    /**
     * Matches `LassoGesture`'s own handle reach, so every selection grabs the same way.
     *
     * It did not, until the lasso was made to read this: it carried 10 device pixels of its own,
     * which is a quarter of this on a dense screen. That is the failure this object exists to
     * prevent, and it survived here as a comment claiming otherwise — so the number is now shared
     * rather than described.
     */
    val HANDLE_REACH: Dp = 14.dp
}

/**
 * How far past its tip an arm's tab sits.
 *
 * Set against [HANDLE_REACH] rather than by eye: an L's tip *is* a corner of its bounding box, so
 * this gap is the whole distance between two handles that do different things. At 20dp the split
 * between them lands 10dp from each, which is about a finger's width of margin either way — closer
 * and a deliberate grab starts landing on the wrong one, further and the tab stops reading as part
 * of the shape it belongs to.
 */
private val ARM_HANDLE_GAP: Dp = 20.dp

private val ARM_HANDLE_LENGTH: Dp = 18.dp
private val ARM_HANDLE_THICKNESS: Dp = 7.dp

/** Never through zero: a shape flipped inside out by a fast drag cannot be dragged back. */
private const val MIN_SCALE = 0.12f
