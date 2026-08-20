package com.vivenotes.ui.editor

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageBounds
import com.vivenotes.ink.pageBounds
import com.vivenotes.model.Outline
import com.vivenotes.richtext.createEquationRenderer
import io.ratex.RaTeXRenderer
import kotlin.math.hypot

internal const val EQUATION_LAYER_TAG = "equation-layer"

/**
 * The equations on the page — the Draw tab's ƒ, as objects rather than as marks in a sentence.
 *
 * Sits inside the zoomed page layer beside [ShapeLayer], drawn and hit-tested the same way and for
 * the same reasons: page units are dp, so the geometry scales by density while the selection chrome
 * does not, and the whole layer is a *child* of the bare-canvas tap target so that an equation takes
 * a gesture off the page while everything it declines falls through.
 *
 * **The picture is rebuilt from the source; only the source is stored.** RaTeX parses to a display
 * list on a background thread and bakes the colour into it, so a renderer is cached per formula *and*
 * colour and thrown away when either changes. Until one is ready — and after a parse this layer never
 * sees fail, because the panel refused to submit a formula that would — the LaTeX is drawn as plain
 * text, exactly as `RenderedEquationSpan` does inline: content that has not finished rendering must
 * never be content that has disappeared.
 *
 * **Scaled to its box rather than re-laid-out.** An equation is measured once, at the size RaTeX
 * gives it, and a corner drag stretches that box; the glyphs follow. A formula has no line breaks to
 * reflow, so there is nothing a re-layout would do differently, and this way the document's idea of
 * how big an equation is stays exact — see `Outline.Equation`, and contrast a table, whose height
 * only the canvas can know (TA3).
 *
 * Selection, four-corner resize and drag-to-move are `docs/plan.md` AD7, and the geometry deliberately
 * matches [ShapeLayer]'s to the dp: same hit radius, same anchor at the opposite corner, same dashed
 * box and same handles. An affordance that behaves differently depending on what is under it is worse
 * than not having one.
 */
@Composable
internal fun EquationLayer(
    equations: List<Outline.Equation>,
    /** The page's selection, which may hold other kinds. This layer reads only its equation half. */
    selection: CanvasSelection?,
    /** The colour a formula takes when it has not been given one of its own. */
    canvasTextColor: Color,
    /**
     * The live lasso transform, so an equation inside a lasso move follows the finger with the ink
     * rather than jumping when the drag ends. Null when there is no lasso in play.
     */
    lassoGesture: LassoGesture? = null,
    /** False while a tool is armed or the lasso owns the page — see [ShapeLayer.interactive]. */
    interactive: Boolean,
    /** What the page can currently show — see [ShapeLayer]'s own, and read in the draw for the same reason. */
    visibleWindow: () -> Rect,
    onSelect: (CanvasSelection?) -> Unit,
    onMove: (equationId: String, dx: Float, dy: Float) -> Unit,
    onResize: (
        equationId: String,
        anchorX: Float,
        anchorY: Float,
        scaleX: Float,
        scaleY: Float,
    ) -> Unit = { _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
    /**
     * The next layer **down**, composed as a child rather than a sibling — `ShapeLayer` goes here.
     *
     * A slot rather than a sibling because two full-page layers side by side means Compose gives
     * every touch to whichever is on top and the other goes silently dead — `docs/plan.md` entry 24.
     * Nesting is what orders them instead, and these layers claim a touch on the tunnelling pass,
     * where a parent is asked before its child: a formula takes a touch that lands on one, and the
     * shapes get what it declines.
     */
    beneath: @Composable BoxScope.() -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface
    val context = LocalContext.current

    // Read by the gesture rather than captured by it: the handler below runs for the lifetime of the
    // layer and is never rebuilt, so everything it needs has to sit behind a stable holder.
    val currentEquations = rememberUpdatedState(equations)
    val currentSelection = rememberUpdatedState(selection)
    val currentInteractive = rememberUpdatedState(interactive)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentOnMove = rememberUpdatedState(onMove)
    val currentOnResize = rememberUpdatedState(onResize)

    /** The drags in flight, drawn but not written until the lift — see [ShapeResize] for why. */
    val resize = remember { mutableStateOf<EquationResize?>(null) }
    val move = remember { mutableStateOf<EquationMove?>(null) }

    val renderers = remember { mutableStateMapOf<EquationRenderKey, RaTeXRenderer>() }
    val density = LocalContext.current.resources.displayMetrics.density
    val baseFontPx = remember(density) { BASE_FONT_DP * density }

    // One effect over the whole list rather than one per equation: the keys are what matters, and a
    // formula that two objects share is parsed once. Failures are swallowed on purpose — the source
    // keeps drawing as text, which is a better answer on a page than a blank rectangle.
    val keys = equations.map { EquationRenderKey(it.latex, it.resolvedColor(canvasTextColor)) }
    LaunchedEffect(keys, baseFontPx) {
        keys.distinct().forEach { key ->
            if (renderers.containsKey(key)) return@forEach
            runCatching { createEquationRenderer(context, key.latex, baseFontPx, key.colorArgb) }
                .onSuccess { renderers[key] = it }
        }
        // Anything no equation asks for any more, including every renderer of a formula that was
        // just edited or recoloured. Without this the map is a leak with a display list in it.
        val live = keys.toSet()
        renderers.keys.filterNot { it in live }.forEach(renderers::remove)
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag(EQUATION_LAYER_TAG)
            // Keyed on nothing, for the reason ShapeLayer spells out at length: `pointerInput(keys)`
            // cancels its coroutine when a key changes, and a restarted handler waits for a DOWN that
            // a finger already on the glass will never send — so keying this on the document would
            // kill every drag on its first applied frame.
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Tunnelling pass — see `ShapeLayer`, where the whole ordering is set out.
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    if (!currentInteractive.value) return@awaitEachGesture
                    val all = currentEquations.value
                    val held = currentSelection.value
                    val startX = down.position.x / density
                    val startY = down.position.y / density

                    // Handles belong to a lone equation. A selection holding more than one object is
                    // the lasso's to move, and the overlay owns that gesture.
                    val selected = all.singleOrNull {
                        held != null && held.isEquationOnly && held.holdsEquation(it.id)
                    }
                    // A handle wins over the body: they sit on the boundary, so each is also inside
                    // the move target.
                    val handle = selected?.handleNear(startX, startY)

                    val target = when {
                        handle != null -> selected
                        selected?.contains(startX, startY) == true -> selected
                        else -> all.topmostNear(startX, startY)
                    }

                    // Nothing of ours under the finger and nothing to deselect: leave the gesture for
                    // the layers underneath, whose tap on bare canvas opens a text container.
                    if (target == null) return@awaitEachGesture
                    down.consume()

                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var last = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Every sample, not only the ones past the slop: a single unconsumed sample
                        // with the finger down is what the scroll containers around the page are
                        // waiting for, and since Compose 1.9 they will pick a half-finished drag back
                        // up and pan with it.
                        change.consume()
                        if (!change.pressed) {
                            if (!dragging) {
                                currentOnSelect.value(CanvasSelection.ofEquation(target))
                            }
                            resize.value?.let {
                                currentOnResize.value(
                                    it.equationId, it.anchorX, it.anchorY, it.scaleX, it.scaleY,
                                )
                            }
                            move.value?.let { currentOnMove.value(it.equationId, it.dx, it.dy) }
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > slop) {
                            dragging = true
                            last = change.position
                            // The handles and the tooltip belong to whatever is being dragged, so
                            // grabbing an unselected equation selects and drags it in one motion.
                            if (selected?.id != target.id) {
                                currentOnSelect.value(CanvasSelection.ofEquation(target))
                            }
                        }
                        if (dragging) {
                            if (handle != null) {
                                target.scaleFor(
                                    handle.corner,
                                    change.position.x / density,
                                    change.position.y / density,
                                )?.let { (scaleX, scaleY) ->
                                    val (anchorX, anchorY) = target.anchorFor(handle.corner)
                                    // Stopped where the far edges reach the page's origin corner,
                                    // and stopped on the preview so the formula does not grow past
                                    // it and spring back on the lift — [PageBounds], and the same
                                    // clamp `ShapeLayer` puts on its own corners.
                                    val held = PageBounds.clampScale(
                                        target.pageBounds(),
                                        InkPoint(anchorX, anchorY),
                                        scaleX,
                                        scaleY,
                                    )
                                    resize.value =
                                        EquationResize(target.id, anchorX, anchorY, held.x, held.y)
                                }
                            } else {
                                // Measured from where the drag began rather than from the previous
                                // sample, so the travel is one number and the slop is not in it.
                                val travel = change.position - last
                                val held = PageBounds.clampTranslation(
                                    target.pageBounds(),
                                    travel.x / density,
                                    travel.y / density,
                                )
                                move.value = EquationMove(target.id, held.x, held.y)
                            }
                        }
                    }
                    // Whatever ended the gesture, including a cancel that took the pointer away
                    // mid-drag — which therefore discards the drag rather than committing it.
                    resize.value = null
                    move.value = null
                }
            },
    ) {
        // First, so the formulas paint over it and are offered the touch before it — see [beneath].
        beneath()

        Canvas(Modifier.fillMaxSize()) {
            val revision = lassoGesture?.renderRevision ?: 0
            val moving = lassoGesture?.takeIf { it.isTransforming && revision >= 0 }
            val heldIds = selection?.equationIds.orEmpty()
            val resizing = resize.value
            val nudging = move.value

            // Both drags are preview-only, so they are applied here rather than read back out of the
            // document — and to the chrome as well, or the handles would sit around the size the drag
            // started at.
            fun previewOf(equation: Outline.Equation): Outline.Equation = when {
                resizing?.equationId == equation.id -> equation.scaledAbout(
                    resizing.anchorX, resizing.anchorY, resizing.scaleX, resizing.scaleY,
                )
                nudging?.equationId == equation.id -> equation.translated(nudging.dx, nudging.dy)
                moving != null && equation.id in heldIds -> equation.withLassoPreview(moving)
                else -> equation
            }

            // Read here rather than captured, so scrolling re-runs the draw and not the composition.
            val window = visibleWindow()
            equations.forEach { equation ->
                val drawn = previewOf(equation)
                // A formula off the edge of the window is a display list nobody can see.
                if (!pageBoxIsVisible(drawn.x, drawn.y, drawn.width, drawn.height, window, density)) {
                    return@forEach
                }
                val key = EquationRenderKey(equation.latex, equation.resolvedColor(canvasTextColor))
                drawEquation(drawn, renderers[key], key.colorArgb, density)
            }

            // Not while the lasso is armed: the overlay draws the selection then, and owns the
            // gestures on it — a second box and four more discs here are chrome nothing services.
            // See `ShapeLayer`, where the same line carries the same condition.
            equations.takeIf { lassoGesture == null }
                ?.singleOrNull { selection?.isEquationOnly == true && it.id in heldIds }
                ?.let { drawEquationSelection(previewOf(it), accent, handleFill, density) }
        }
    }
}

/** What a formula is drawn in when it has not been given a colour of its own. */
private fun Outline.Equation.resolvedColor(canvasTextColor: Color): Int =
    colorArgb ?: canvasTextColor.toArgb()

/**
 * The size RaTeX is asked to lay a formula out at, before the box scales it.
 *
 * Large enough that the display list has real metrics to scale from — a formula measured at 8dp and
 * blown up to 80 would carry its rounding with it — and the same value the panel's own preview and
 * the inline mark both use, which is what makes an equation dragged onto the canvas the size it
 * looked in the preview.
 */
internal const val BASE_FONT_DP = 22f

/** A parsed formula is a formula *and* a colour: RaTeX bakes the colour into the display list. */
private data class EquationRenderKey(val latex: String, val colorArgb: Int)

/** A corner drag in flight — preview only, committed once on the lift. See [ShapeResize]. */
private data class EquationResize(
    val equationId: String,
    val anchorX: Float,
    val anchorY: Float,
    val scaleX: Float,
    val scaleY: Float,
)

/** A move in flight: the whole travel since the drag began, reported once on the lift. */
private data class EquationMove(val equationId: String, val dx: Float, val dy: Float)

/** The live lasso move or resize, applied for the draw only — nothing is written until the up. */
private fun Outline.Equation.withLassoPreview(gesture: LassoGesture): Outline.Equation {
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
 * The formula, stretched from its natural metrics into the box the document stores.
 *
 * [RaTeXRenderer.draw] paints from the top-left of the ink once the canvas is translated there, which
 * is what the inline span does with the text baseline; here the box *is* the destination, so the two
 * scale factors are simply what maps one rectangle onto the other.
 *
 * Falls back to the source as plain text, which is [RenderedEquationSpan]'s own fallback and is there
 * for the same reason: the parse runs off the main thread, and a formula that has not finished
 * rendering must not be a formula that vanished.
 */
private fun DrawScope.drawEquation(
    equation: Outline.Equation,
    renderer: RaTeXRenderer?,
    colorArgb: Int,
    scale: Float,
) {
    val left = equation.x * scale
    val top = equation.y * scale
    val boxWidth = equation.width * scale
    val boxHeight = equation.height * scale

    if (renderer == null) {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = colorArgb
                textSize = BASE_FONT_DP * scale
                isAntiAlias = true
            }
            canvas.nativeCanvas.drawText(equation.latex, left, top + boxHeight, paint)
        }
        return
    }

    val naturalWidth = renderer.widthPx
    val naturalHeight = renderer.heightPx + renderer.depthPx
    if (naturalWidth <= 0f || naturalHeight <= 0f) return

    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.translate(left, top)
        canvas.nativeCanvas.scale(boxWidth / naturalWidth, boxHeight / naturalHeight)
        renderer.draw(canvas.nativeCanvas)
        canvas.nativeCanvas.restore()
    }
}

/**
 * A dashed box and four corner handles — [ShapeLayer]'s selection, drawn by the same numbers.
 *
 * Shared constants rather than a shared function because the two draw different things inside the
 * box; what must not differ is where the corners are and how far a finger may miss one by, which is
 * why both read [SelectionChrome.PADDING], [SelectionChrome.HANDLE_RADIUS] and [SelectionChrome.HANDLE_REACH] rather than their own.
 */
private fun DrawScope.drawEquationSelection(
    equation: Outline.Equation,
    accent: Color,
    handleFill: Color,
    scale: Float,
) {
    val padding = SelectionChrome.PADDING.toPx()
    val left = equation.x * scale - padding
    val top = equation.y * scale - padding
    val right = left + equation.width * scale + padding * 2
    val bottom = top + equation.height * scale + padding * 2

    drawRect(
        color = accent,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        style = Stroke(
            width = SelectionChrome.STROKE.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(SelectionChrome.DASH.toPx(), SelectionChrome.DASH.toPx()),
            ),
        ),
    )

    val radius = SelectionChrome.HANDLE_RADIUS.toPx()
    listOf(left to top, right to top, right to bottom, left to bottom).forEach { (x, y) ->
        drawCircle(handleFill, radius, Offset(x, y))
        drawCircle(accent, radius, Offset(x, y), style = Stroke(width = SelectionChrome.STROKE.toPx()))
    }
}

private enum class EquationCorner { TopLeft, TopRight, BottomRight, BottomLeft }

/** What the finger came down on. An equation is a box, so a corner is the only kind there is. */
private data class EquationHandle(val corner: EquationCorner)

private fun Outline.Equation.cornerPoint(corner: EquationCorner): Pair<Float, Float> = when (corner) {
    EquationCorner.TopLeft -> x to y
    EquationCorner.TopRight -> (x + width) to y
    EquationCorner.BottomRight -> (x + width) to (y + height)
    EquationCorner.BottomLeft -> x to (y + height)
}

/** The corner that stays put: the one opposite the one being dragged. */
private fun Outline.Equation.anchorFor(corner: EquationCorner): Pair<Float, Float> = cornerPoint(
    when (corner) {
        EquationCorner.TopLeft -> EquationCorner.BottomRight
        EquationCorner.TopRight -> EquationCorner.BottomLeft
        EquationCorner.BottomRight -> EquationCorner.TopLeft
        EquationCorner.BottomLeft -> EquationCorner.TopRight
    },
)

/** The corner under the point, or null. Page units, so [SelectionChrome.HANDLE_REACH] is read as plain dp. */
private fun Outline.Equation.handleNear(x: Float, y: Float): EquationHandle? =
    EquationCorner.entries
        .map { corner ->
            val (cx, cy) = cornerPoint(corner)
            EquationHandle(corner) to hypot(x - cx, y - cy)
        }
        .minByOrNull { (_, distance) -> distance }
        ?.takeIf { (_, distance) -> distance <= SelectionChrome.HANDLE_REACH.value }
        ?.first

/**
 * How far the dragged corner has taken the box, measured from the geometry the drag started with.
 *
 * Null when the anchor and the finger are on the same line, which would be a scale of zero on one
 * axis and a box that can never be grabbed again.
 */
private fun Outline.Equation.scaleFor(
    corner: EquationCorner,
    x: Float,
    y: Float,
): Pair<Float, Float>? {
    val (anchorX, anchorY) = anchorFor(corner)
    if (width <= 0f || height <= 0f) return null
    val spanX = when (corner) {
        EquationCorner.TopLeft, EquationCorner.BottomLeft -> anchorX - x
        EquationCorner.TopRight, EquationCorner.BottomRight -> x - anchorX
    }
    val spanY = when (corner) {
        EquationCorner.TopLeft, EquationCorner.TopRight -> anchorY - y
        EquationCorner.BottomLeft, EquationCorner.BottomRight -> y - anchorY
    }
    if (spanX <= 0f || spanY <= 0f) return null
    return (spanX / width) to (spanY / height)
}

/** Last drawn wins a tap, which is the one on top. */
private fun List<Outline.Equation>.topmostNear(pointX: Float, pointY: Float): Outline.Equation? =
    asReversed().firstOrNull { it.contains(pointX, pointY) }

/**
 * The whole box, not the ink in it.
 *
 * A formula is mostly whitespace — an integral sign and a fraction bar with air around them — and
 * hit-testing the glyphs would mean taps that land squarely on an equation and do nothing. The box is
 * what the handles surround and what the selection draws, so it is what a tap should mean.
 */
private fun Outline.Equation.contains(pointX: Float, pointY: Float): Boolean =
    pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height
