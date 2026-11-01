package com.vivenotes.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.DpSize
import com.vivenotes.graph.GraphAnalysis
import com.vivenotes.graph.GraphColors
import com.vivenotes.graph.GraphDrag
import com.vivenotes.graph.GraphExpression
import com.vivenotes.graph.GraphFrame
import com.vivenotes.graph.GraphPainter
import com.vivenotes.graph.GraphPoint
import com.vivenotes.graph.window
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageBounds
import com.vivenotes.ink.pageBounds
import com.vivenotes.model.Outline
import com.vivenotes.richtext.createEquationRenderer
import io.ratex.RaTeXRenderer
import kotlin.math.hypot

internal const val GRAPH_LAYER_TAG = "graph-layer"

/**
 * A dragged point's change, sent to the engine and not yet back: the curve keeps showing it until
 * the graph's function is no longer [from], so letting go does not flash the old curve for the
 * moment Python takes.
 */
data class GraphRewriteInFlight(val from: String, val drag: GraphDrag)

/**
 * The graphs on the page.
 *
 * [EquationLayer]'s object, kind for kind — tap to select, drag to move, four corners to resize,
 * the lasso's live preview — plus the one thing a graph has that a formula does not: with *mutate*
 * on, its points of interest are handles. Dragging one previews the changed function every frame
 * from [GraphDrag]; letting go hands the drag to [onMutate], which has the engine write the new
 * function exactly.
 *
 * Point drags need the graph selected first, so a finger resting on a page cannot rewrite a
 * function by accident; and they work on a locked graph, which keeps still while its points move.
 */
@Composable
internal fun GraphLayer(
    graphs: List<Outline.Graph>,
    selection: CanvasSelection?,
    canvasTextColor: Color,
    paperColor: Color,
    lassoGesture: LassoGesture? = null,
    interactive: Boolean,
    visibleWindow: () -> Rect,
    onSelect: (CanvasSelection?) -> Unit,
    onMove: (graphId: String, dx: Float, dy: Float) -> Unit,
    onResize: (graphId: String, anchorX: Float, anchorY: Float, scaleX: Float, scaleY: Float) -> Unit,
    onMutate: (Outline.Graph, GraphDrag) -> Unit,
    rewrites: Map<String, GraphRewriteInFlight> = emptyMap(),
    modifier: Modifier = Modifier,
    canvasExtent: DpSize? = null,
    beneath: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density

    val currentGraphs = rememberUpdatedState(graphs)
    val currentSelection = rememberUpdatedState(selection)
    val currentInteractive = rememberUpdatedState(interactive)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentOnMove = rememberUpdatedState(onMove)
    val currentOnResize = rememberUpdatedState(onResize)
    val currentOnMutate = rememberUpdatedState(onMutate)

    val resize = remember { mutableStateOf<GraphResize?>(null) }
    val move = remember { mutableStateOf<GraphMove?>(null) }
    val pointDrag = remember { mutableStateOf<GraphPointDrag?>(null) }
    val painter = remember { GraphPainter() }
    val functions = remember { GraphFunctions() }

    // Headers are formulas, rendered as an equation object's are and cached by text and colour.
    val renderers = remember { mutableStateMapOf<HeaderKey, RaTeXRenderer>() }
    val textArgb = canvasTextColor.toArgb()
    val keys = graphs.filter { it.showEquation && it.latex.isNotBlank() }
        .map { HeaderKey("${it.dependent} = ${it.latex}", textArgb) }
    LaunchedEffect(keys, density) {
        keys.distinct().forEach { key ->
            if (renderers.containsKey(key)) return@forEach
            runCatching { createEquationRenderer(context, key.latex, BASE_FONT_DP * density, key.colorArgb) }
                .onSuccess { renderers[key] = it }
        }
        val live = keys.toSet()
        renderers.keys.filterNot { it in live }.forEach(renderers::remove)
    }

    Box(
        modifier
            .testTag(GRAPH_LAYER_TAG)
            // Keyed on nothing — `EquationLayer` says why a keyed handler kills drags mid-gesture.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    if (!currentInteractive.value) return@awaitEachGesture
                    val all = currentGraphs.value
                    val held = currentSelection.value
                    val startX = down.position.x / density
                    val startY = down.position.y / density

                    val selected = all.singleOrNull { held != null && held.isGraphOnly && held.holdsGraph(it.id) }
                    val handle = selected?.takeIf { it.lockGroup == null }?.handleNear(startX, startY)
                    // A point of interest under the finger, on a selected graph with mutate on.
                    val grabbed = selected?.takeIf { it.mutable && handle == null }?.let { graph ->
                        val frame = graph.frame(density)
                        val function = functions.of(graph.expression) ?: return@let null
                        val points = GraphAnalysis.pointsOfInterest(function::evaluate, frame.window)
                        frame.pointNear(points, down.position.x, down.position.y)?.let { point ->
                            Triple(graph, function, point)
                        }
                    }

                    val target = when {
                        handle != null || grabbed != null -> selected
                        selected?.contains(startX, startY) == true -> selected
                        else -> all.topmostNear(startX, startY)
                    }
                    if (target == null) return@awaitEachGesture
                    down.consume()

                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var last = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            if (!dragging) currentOnSelect.value(CanvasSelection.ofGraph(target))
                            resize.value?.let {
                                currentOnResize.value(it.graphId, it.anchorX, it.anchorY, it.scaleX, it.scaleY)
                            }
                            move.value?.let { currentOnMove.value(it.graphId, it.dx, it.dy) }
                            pointDrag.value?.let { drag ->
                                if (drag.drag.changes) currentOnMutate.value(drag.graph, drag.drag)
                            }
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > slop) {
                            dragging = true
                            last = change.position
                            if (selected?.id != target.id) currentOnSelect.value(CanvasSelection.ofGraph(target))
                        }
                        if (!dragging) continue
                        if (grabbed != null) {
                            // The function changes under the finger; the box stays where it is, locked
                            // or not — mutate is its own permission.
                            val (graph, function, point) = grabbed
                            val frame = graph.frame(density)
                            pointDrag.value = GraphPointDrag(
                                graph = graph,
                                drag = GraphDrag.of(
                                    base = function,
                                    polynomial = functions.polynomialOf(graph.expression),
                                    point = point,
                                    x = frame.graphX(change.position.x),
                                    y = frame.graphY(change.position.y),
                                    window = frame.window,
                                ),
                            )
                        } else if (target.lockGroup == null) {
                            if (handle != null) {
                                target.scaleFor(handle, change.position.x / density, change.position.y / density)
                                    ?.let { (scaleX, scaleY) ->
                                        val (anchorX, anchorY) = target.anchorFor(handle)
                                        val clamped = PageBounds.clampScale(
                                            target.pageBounds(),
                                            InkPoint(anchorX, anchorY),
                                            scaleX,
                                            scaleY,
                                        )
                                        resize.value = GraphResize(target.id, anchorX, anchorY, clamped.x, clamped.y)
                                    }
                            } else {
                                val travel = change.position - last
                                val clamped = PageBounds.clampTranslation(
                                    target.pageBounds(),
                                    travel.x / density,
                                    travel.y / density,
                                )
                                move.value = GraphMove(target.id, clamped.x, clamped.y)
                            }
                        }
                    }
                    resize.value = null
                    move.value = null
                    pointDrag.value = null
                }
            }
            .fillDocument(canvasExtent),
    ) {
        beneath()

        DocumentCanvas(canvasExtent) {
            val revision = lassoGesture?.renderRevision ?: 0
            val moving = lassoGesture?.takeIf { it.isTransforming && revision >= 0 }
            val heldIds = selection?.graphIds.orEmpty()
            val resizing = resize.value
            val nudging = move.value
            val dragging = pointDrag.value

            fun previewOf(graph: Outline.Graph): Outline.Graph = when {
                resizing?.graphId == graph.id ->
                    graph.scaledAbout(resizing.anchorX, resizing.anchorY, resizing.scaleX, resizing.scaleY)
                nudging?.graphId == graph.id -> graph.translated(nudging.dx, nudging.dy)
                moving != null && graph.id in heldIds -> graph.withLassoPreview(moving)
                else -> graph
            }

            val window = visibleWindow()
            val single = graphs.takeIf { lassoGesture == null }
                ?.singleOrNull { selection?.isGraphOnly == true && it.id in heldIds }
            graphs.forEach { graph ->
                val drawn = previewOf(graph)
                if (!pageBoxIsVisible(drawn.x, drawn.y, drawn.width, drawn.height, window, density)) return@forEach
                val base = functions.of(graph.expression) ?: return@forEach
                // The drag in hand, else one let go of and still with the engine, else the function.
                val change = dragging?.takeIf { it.graph.id == graph.id }?.drag
                    ?: rewrites[graph.id]?.takeIf { it.from == graph.expression }?.drag
                val current: (Double) -> Double = change?.let { it::evaluate } ?: base::evaluate
                val frame = drawn.frame(density)
                val original = when {
                    !graph.showOriginal -> null
                    change != null -> base::evaluate
                    graph.isMutated -> graph.originalExpression?.let(functions::of)?.let { it::evaluate }
                    else -> null
                }
                val points = if (change == null) {
                    functions.pointsOf(graph.expression, frame.window)
                } else {
                    GraphAnalysis.pointsOfInterest(current, frame.window)
                }
                val header = renderers[HeaderKey("${graph.dependent} = ${graph.latex}", textArgb)]
                    .takeIf { change == null }
                drawIntoCanvas { canvas ->
                    painter.draw(
                        canvas = canvas.nativeCanvas,
                        frame = frame,
                        colors = GraphColors(
                            paper = paperColor.toArgb(),
                            text = textArgb,
                            curve = graph.colorArgb ?: GraphPainter.DEFAULT_CURVE_ARGB,
                            accent = accent.toArgb(),
                        ),
                        function = current,
                        original = original,
                        points = points,
                        header = header,
                        handles = graph.mutable && graph.id == single?.id && dragging == null,
                        active = dragging?.takeIf { it.graph.id == graph.id }?.drag?.let { it.pointX to it.pointY },
                    )
                }
            }

            single?.let { drawGraphSelection(previewOf(it), accent, handleFill, density) }
        }
    }
}

/** The function behind each stored string, parsed once; and its points, found once per window. */
private class GraphFunctions {
    private val parsed = HashMap<String, GraphExpression?>()
    private val polynomials = HashMap<String, DoubleArray?>()
    private val points = HashMap<Pair<String, com.vivenotes.graph.GraphWindow>, List<GraphPoint>>()

    fun of(source: String): GraphExpression? = parsed.getOrPut(source) { GraphExpression.parse(source) }

    fun polynomialOf(source: String): DoubleArray? = polynomials.getOrPut(source) { of(source)?.polynomial() }

    fun pointsOf(source: String, window: com.vivenotes.graph.GraphWindow): List<GraphPoint> {
        if (points.size > 64) points.clear()
        return points.getOrPut(source to window) {
            of(source)?.let { GraphAnalysis.pointsOfInterest(it::evaluate, window) }.orEmpty()
        }
    }
}

private fun Outline.Graph.frame(scale: Float): GraphFrame =
    GraphFrame(this, x * scale, y * scale, width * scale, height * scale, scale, window())

private data class HeaderKey(val latex: String, val colorArgb: Int)

private data class GraphResize(
    val graphId: String,
    val anchorX: Float,
    val anchorY: Float,
    val scaleX: Float,
    val scaleY: Float,
)

private data class GraphMove(val graphId: String, val dx: Float, val dy: Float)

private data class GraphPointDrag(val graph: Outline.Graph, val drag: GraphDrag)

private fun Outline.Graph.withLassoPreview(gesture: LassoGesture): Outline.Graph {
    val offset = gesture.previewOffset()
    val scale = gesture.previewScale()
    val anchor = gesture.previewAnchor()
    return when {
        offset.x != 0f || offset.y != 0f -> translated(offset.x, offset.y)
        scale.x != 1f || scale.y != 1f -> scaledAbout(anchor.x, anchor.y, scale.x, scale.y)
        else -> this
    }
}

/** [EquationLayer]'s chrome, by the same shared numbers. */
private fun DrawScope.drawGraphSelection(graph: Outline.Graph, accent: Color, handleFill: Color, scale: Float) {
    val padding = SelectionChrome.PADDING.toPx()
    val left = graph.x * scale - padding
    val top = graph.y * scale - padding
    val right = left + graph.width * scale + padding * 2
    val bottom = top + graph.height * scale + padding * 2
    drawRect(
        color = accent,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        style = Stroke(
            width = SelectionChrome.STROKE.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(SelectionChrome.DASH.toPx(), SelectionChrome.DASH.toPx())),
        ),
    )
    if (graph.lockGroup != null) return
    val radius = SelectionChrome.HANDLE_RADIUS.toPx()
    listOf(left to top, right to top, right to bottom, left to bottom).forEach { (x, y) ->
        drawCircle(handleFill, radius, Offset(x, y))
        drawCircle(accent, radius, Offset(x, y), style = Stroke(width = SelectionChrome.STROKE.toPx()))
    }
}

private enum class GraphCorner { TopLeft, TopRight, BottomRight, BottomLeft }

private fun Outline.Graph.cornerPoint(corner: GraphCorner): Pair<Float, Float> = when (corner) {
    GraphCorner.TopLeft -> x to y
    GraphCorner.TopRight -> (x + width) to y
    GraphCorner.BottomRight -> (x + width) to (y + height)
    GraphCorner.BottomLeft -> x to (y + height)
}

private fun Outline.Graph.anchorFor(corner: GraphCorner): Pair<Float, Float> = cornerPoint(
    when (corner) {
        GraphCorner.TopLeft -> GraphCorner.BottomRight
        GraphCorner.TopRight -> GraphCorner.BottomLeft
        GraphCorner.BottomRight -> GraphCorner.TopLeft
        GraphCorner.BottomLeft -> GraphCorner.TopRight
    },
)

private fun Outline.Graph.handleNear(x: Float, y: Float): GraphCorner? =
    GraphCorner.entries
        .map { corner ->
            val (cx, cy) = cornerPoint(corner)
            corner to hypot(x - cx, y - cy)
        }
        .minByOrNull { it.second }
        ?.takeIf { it.second <= SelectionChrome.HANDLE_REACH.value }
        ?.first

private fun Outline.Graph.scaleFor(corner: GraphCorner, x: Float, y: Float): Pair<Float, Float>? {
    val (anchorX, anchorY) = anchorFor(corner)
    if (width <= 0f || height <= 0f) return null
    val spanX = when (corner) {
        GraphCorner.TopLeft, GraphCorner.BottomLeft -> anchorX - x
        GraphCorner.TopRight, GraphCorner.BottomRight -> x - anchorX
    }
    val spanY = when (corner) {
        GraphCorner.TopLeft, GraphCorner.TopRight -> anchorY - y
        GraphCorner.BottomLeft, GraphCorner.BottomRight -> y - anchorY
    }
    if (spanX <= 0f || spanY <= 0f) return null
    return (spanX / width) to (spanY / height)
}

private fun List<Outline.Graph>.topmostNear(pointX: Float, pointY: Float): Outline.Graph? =
    asReversed().firstOrNull { it.contains(pointX, pointY) }

private fun Outline.Graph.contains(pointX: Float, pointY: Float): Boolean =
    pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height
