package com.vivenotes.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpSize
import com.vivenotes.graph.GraphAnalysis
import com.vivenotes.graph.GraphColors
import com.vivenotes.graph.GraphDrag
import com.vivenotes.graph.GraphExpression
import com.vivenotes.graph.GraphFrame
import com.vivenotes.graph.GraphPainter
import com.vivenotes.graph.GraphPoint
import com.vivenotes.graph.GraphSliders
import com.vivenotes.graph.SliderPart
import com.vivenotes.graph.GraphTangent
import com.vivenotes.graph.window
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageBounds
import com.vivenotes.ink.pageBounds
import com.vivenotes.R
import com.vivenotes.model.GraphInterval
import com.vivenotes.model.GraphParameter
import com.vivenotes.model.Outline
import com.vivenotes.richtext.createEquationRenderer
import io.ratex.RaTeXRenderer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

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
 *
 * A selected graph's sliders take a finger the same way, mutate or not: a drag previews every frame
 * and [onParameter] writes the value on the lift; play sweeps one until it is paused or the graph is
 * let go, and writes where it stopped. Each row is a TalkBack slider too.
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
    /** The tracer let go at a new x, in the function's units. */
    onTrace: (graphId: String, x: Double) -> Unit = { _, _ -> },
    /** The area's bounds after one of them was let go. */
    onArea: (graphId: String, area: GraphInterval) -> Unit = { _, _ -> },
    /** A slider let go, paused, or set by TalkBack, at a value not yet snapped. */
    onParameter: (graphId: String, index: Int, value: Double) -> Unit = { _, _, _ -> },
    /** A slider's name tapped: the Sliders dialog, for its range and an exact value. */
    onEditSliders: (graphId: String) -> Unit = {},
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
    val currentOnTrace = rememberUpdatedState(onTrace)
    val currentOnArea = rememberUpdatedState(onArea)
    val currentOnParameter = rememberUpdatedState(onParameter)
    val currentOnEditSliders = rememberUpdatedState(onEditSliders)

    val resize = remember { mutableStateOf<GraphResize?>(null) }
    val move = remember { mutableStateOf<GraphMove?>(null) }
    val pointDrag = remember { mutableStateOf<GraphPointDrag?>(null) }
    val traceDrag = remember { mutableStateOf<GraphTraceDrag?>(null) }
    val areaDrag = remember { mutableStateOf<GraphAreaDrag?>(null) }
    val sliderDrag = remember { mutableStateOf<GraphSliderValue?>(null) }
    val playing = remember { mutableStateOf<GraphSliderPlay?>(null) }
    val painter = remember { GraphPainter() }
    val functions = remember { GraphFunctions() }

    /** Writes where play has got to and stops it. */
    fun stopPlaying() {
        val play = playing.value ?: return
        playing.value = null
        currentOnParameter.value(play.graphId, play.index, play.value)
    }

    // Play: one slider at a time, swept a frame at a time. Nothing is written until it stops.
    // Read in composition only through this, so a frame of play redraws without recomposing.
    val playKey by remember { derivedStateOf { playing.value?.let { it.graphId to it.index } } }
    LaunchedEffect(playKey) {
        if (playKey == null) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val play = playing.value ?: break
            val parameter = currentGraphs.value.firstOrNull { it.id == play.graphId }?.parameters?.getOrNull(play.index)
            if (parameter == null) {
                playing.value = null
                break
            }
            val (value, direction) = GraphSliders.sweep(parameter, play.value, play.direction, now - last)
            playing.value = play.copy(value = value, direction = direction)
            last = now
        }
    }
    // Letting the graph go — selecting something else, or nothing — pauses it where it is.
    LaunchedEffect(selection, playKey) {
        val play = playing.value ?: return@LaunchedEffect
        val held = selection != null && selection.isGraphOnly && selection.holdsGraph(play.graphId)
        if (!held) stopPlaying()
    }

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

                    // A slider on a selected graph — locked or not, mutate or not, like the tracer.
                    val slider = selected?.takeIf { handle == null }?.let { graph ->
                        graph.frame(density).sliderAt(down.position.x, down.position.y)?.let { graph to it }
                    }
                    if (slider != null) {
                        down.consume()
                        val (graph, hit) = slider
                        val frame = graph.frame(density)
                        val parameter = graph.parameters[hit.index]
                        val slop = viewConfiguration.touchSlop
                        if (hit.part == SliderPart.Track) {
                            // Material's: the handle jumps to the finger, then follows it.
                            if (playing.value?.let { it.graphId == graph.id && it.index == hit.index } == true) {
                                playing.value = null
                            }
                            sliderDrag.value = GraphSliderValue(
                                graph.id,
                                hit.index,
                                parameter.snapped(frame.sliderValue(parameter, down.position.x)),
                            )
                        }
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if ((change.position - down.position).getDistance() > slop) moved = true
                            if (!change.pressed) {
                                when (hit.part) {
                                    SliderPart.Track -> sliderDrag.value?.let { drag ->
                                        if (!GraphSliders.same(drag.value, parameter.value)) {
                                            currentOnParameter.value(graph.id, hit.index, drag.value)
                                        }
                                    }
                                    SliderPart.Play -> if (!moved) {
                                        val now = playing.value
                                        if (now?.graphId == graph.id && now.index == hit.index) {
                                            stopPlaying()
                                        } else {
                                            stopPlaying()
                                            playing.value = GraphSliderPlay(graph.id, hit.index, parameter.value, 1)
                                        }
                                    }
                                    SliderPart.Label -> if (!moved) currentOnEditSliders.value(graph.id)
                                }
                                break
                            }
                            if (hit.part == SliderPart.Track) {
                                sliderDrag.value = GraphSliderValue(
                                    graph.id,
                                    hit.index,
                                    parameter.snapped(frame.sliderValue(parameter, change.position.x)),
                                )
                            }
                        }
                        sliderDrag.value = null
                        return@awaitEachGesture
                    }
                    // The tracer, on a selected graph — mutate or not, locked or not: sliding it reads
                    // the curve and changes neither the function nor where the graph sits. It wins
                    // over a point of interest under it, being the thing that was put there.
                    val tracing = selected?.takeIf { handle == null }?.let { graph ->
                        val at = graph.traceX ?: return@let null
                        val function = functions.of(graph) ?: return@let null
                        val frame = graph.frame(density)
                        val y = function.evaluate(at)
                        val near = y.isFinite() && hypot(
                            frame.canvasX(at) - down.position.x,
                            frame.canvasY(y) - down.position.y,
                        ) <= GraphFrame.POINT_REACH_DP * density
                        graph.takeIf { near }
                    }
                    // A point of interest under the finger, on a selected graph with mutate on.
                    val grabbed = selected?.takeIf { it.mutable && handle == null && tracing == null }?.let { graph ->
                        val frame = graph.frame(density)
                        val function = functions.of(graph) ?: return@let null
                        val points = GraphAnalysis.pointsOfInterest(function::evaluate, frame.window)
                        frame.pointNear(points, down.position.x, down.position.y)?.let { point ->
                            Triple(graph, function, point)
                        }
                    }

                    // One of the area's bounds: its dashed line, anywhere down the plot, or the tab at its
                    // foot. After the points, so with mutate on a zero a bound sits on still moves the
                    // root — the bound is reachable anywhere else along its line.
                    val bounding = selected
                        ?.takeIf { handle == null && tracing == null && grabbed == null }
                        ?.let { graph ->
                            val area = graph.area ?: return@let null
                            val frame = graph.frame(density)
                            if (!frame.plot.contains(down.position.x, down.position.y)) return@let null
                            listOf(true to area.from, false to area.to)
                                .filter { (_, x) -> x in frame.window.xMin..frame.window.xMax }
                                .minByOrNull { (_, x) -> abs(frame.canvasX(x) - down.position.x) }
                                ?.takeIf { (_, x) -> abs(frame.canvasX(x) - down.position.x) <= BOUND_REACH_DP * density }
                                ?.let { (isFrom, _) -> GraphBoundGrab(graph, isFrom) }
                        }

                    val target = when {
                        handle != null || grabbed != null || tracing != null || bounding != null -> selected
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
                            traceDrag.value?.let { drag ->
                                if (drag.x != tracing?.traceX) currentOnTrace.value(drag.graphId, drag.x)
                            }
                            areaDrag.value?.let { drag ->
                                if (drag.area != bounding?.graph?.area) currentOnArea.value(drag.graphId, drag.area)
                            }
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > slop) {
                            dragging = true
                            last = change.position
                            if (selected?.id != target.id) currentOnSelect.value(CanvasSelection.ofGraph(target))
                        }
                        if (!dragging) continue
                        if (bounding != null) {
                            // Sideways only, pulled onto a point of interest within reach as the
                            // tracer is — a zero, most often — and otherwise onto the fine grid. A
                            // bound may cross the other one; the integral then changes sign.
                            val graph = bounding.graph
                            val area = graph.area ?: continue
                            val frame = graph.frame(density)
                            val window = frame.window
                            val x = frame.graphX(change.position.x).coerceIn(window.xMin, window.xMax)
                            val magnet = TRACE_MAGNET_DP * density / frame.plot.width() * window.width
                            val snapped = GraphTangent.snap(x, functions.pointsOf(graph, window), magnet, window)
                            areaDrag.value = GraphAreaDrag(
                                graph.id,
                                if (bounding.isFrom) area.copy(from = snapped) else area.copy(to = snapped),
                            )
                        } else if (tracing != null) {
                            // Along the curve: the finger chooses x and the function says where on
                            // the curve that is. A point of interest within reach pulls the tracer
                            // onto it, so a maximum's slope reads exactly 0.
                            val frame = tracing.frame(density)
                            val window = frame.window
                            val x = frame.graphX(change.position.x).coerceIn(window.xMin, window.xMax)
                            val magnet = TRACE_MAGNET_DP * density / frame.plot.width() * window.width
                            val points = functions.pointsOf(tracing, window)
                            traceDrag.value = GraphTraceDrag(tracing.id, GraphTangent.snap(x, points, magnet, window))
                        } else if (grabbed != null) {
                            // The function changes under the finger; the box stays where it is, locked
                            // or not — mutate is its own permission.
                            val (graph, function, point) = grabbed
                            val frame = graph.frame(density)
                            pointDrag.value = GraphPointDrag(
                                graph = graph,
                                drag = GraphDrag.of(
                                    base = function::evaluate,
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
                    traceDrag.value = null
                    areaDrag.value = null
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
            val tracer = traceDrag.value
            val bounds = areaDrag.value
            val sliding = sliderDrag.value
            val play = playing.value

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
                // The sliders as they stand this frame: stored, else played, else under the finger.
                val live = graph.parameters
                    .withValue(play?.takeIf { it.graphId == graph.id }?.let { it.index to it.value })
                    .withValue(sliding?.takeIf { it.graphId == graph.id }?.let { it.index to it.value })
                val values = live.map { it.value }
                val base = functions.of(graph.expression)?.with(values) ?: return@forEach
                // The drag in hand, else one let go of and still with the engine, else the function.
                val change = dragging?.takeIf { it.graph.id == graph.id }?.drag
                    ?: rewrites[graph.id]?.takeIf { it.from == graph.expression }?.drag
                val current: (Double) -> Double = change?.let { it::evaluate } ?: base::evaluate
                val frame = drawn.frame(density)
                val original = when {
                    !graph.showOriginal -> null
                    change != null -> base::evaluate
                    graph.isMutated -> graph.originalExpression?.let(functions::of)?.with(values)?.let { it::evaluate }
                    else -> null
                }
                val points = if (change == null) {
                    functions.pointsOf(graph.expression, values, frame.window)
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
                        handles = graph.mutable && graph.id == single?.id && dragging == null && tracer == null &&
                            bounds == null,
                        active = dragging?.takeIf { it.graph.id == graph.id }?.drag?.let { it.pointX to it.pointY },
                        traceX = tracer?.takeIf { it.graphId == graph.id }?.x ?: graph.traceX,
                        traceHandle = graph.id == single?.id && dragging == null,
                        area = bounds?.takeIf { it.graphId == graph.id }?.area ?: graph.area,
                        areaHandles = graph.id == single?.id && dragging == null,
                        parameters = live,
                        sliderControls = graph.id == single?.id,
                        playing = play?.takeIf { it.graphId == graph.id }?.index,
                        pressedSlider = sliding?.takeIf { it.graphId == graph.id }?.index,
                    )
                }
            }

            single?.let { drawGraphSelection(previewOf(it), accent, handleFill, density) }
        }

        // The selected graph's sliders as TalkBack sees them: semantics only, so every touch still
        // reaches the gesture handler above.
        graphs.singleOrNull { selection != null && selection.isGraphOnly && selection.holdsGraph(it.id) }
            ?.takeIf { lassoGesture == null && interactive }
            ?.let { graph ->
                GraphSliderSemantics(
                    graph = graph,
                    frame = graph.frame(density),
                    playingIndex = playKey?.takeIf { it.first == graph.id }?.second,
                    onSet = { index, value -> currentOnParameter.value(graph.id, index, value) },
                    onPlay = { index ->
                        val now = playing.value
                        stopPlaying()
                        if (now?.graphId != graph.id || now.index != index) {
                            playing.value = GraphSliderPlay(graph.id, index, graph.parameters[index].value, 1)
                        }
                    },
                    onEdit = { currentOnEditSliders.value(graph.id) },
                )
            }
    }
}

/** Each slider row of [graph] as an adjustable TalkBack slider, with play as a button beside it. */
@Composable
private fun GraphSliderSemantics(
    graph: Outline.Graph,
    frame: GraphFrame,
    playingIndex: Int?,
    onSet: (index: Int, value: Double) -> Unit,
    onPlay: (index: Int) -> Unit,
    onEdit: () -> Unit,
) {
    if (frame.sliders == null) return
    val density = LocalDensity.current
    val editLabel = stringResource(R.string.graph_sliders_edit)
    val playLabel = stringResource(R.string.graph_slider_play)
    val pauseLabel = stringResource(R.string.graph_slider_pause)
    graph.parameters.forEachIndexed { index, parameter ->
        val row = frame.sliderRow(index) ?: return@forEachIndexed
        val trackEnd = frame.trackRight + GraphFrame.SLIDER_GAP_DP / 2 * frame.unit
        with(density) {
            Box(
                Modifier
                    .offset { IntOffset(row.left.roundToInt(), row.top.roundToInt()) }
                    .size((trackEnd - row.left).toDp(), row.height().toDp())
                    .testTag(GraphSliderTags.slider(index))
                    .semantics {
                        contentDescription = parameter.label
                        stateDescription = GraphSliders.number(parameter.value, parameter.step)
                        if (parameter.isUsable) {
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                parameter.value.toFloat(),
                                parameter.min.toFloat()..parameter.max.toFloat(),
                            )
                        }
                        setProgress { target ->
                            onSet(index, target.toDouble())
                            true
                        }
                        customActions = listOf(CustomAccessibilityAction(editLabel) { onEdit(); true })
                    },
            )
            Box(
                Modifier
                    .offset { IntOffset(trackEnd.roundToInt(), row.top.roundToInt()) }
                    .size((row.right - trackEnd).toDp(), row.height().toDp())
                    .testTag(GraphSliderTags.play(index))
                    .semantics {
                        role = Role.Button
                        contentDescription = "${if (playingIndex == index) pauseLabel else playLabel} ${parameter.label}"
                        onClick {
                            onPlay(index)
                            true
                        }
                    },
            )
        }
    }
}

internal object GraphSliderTags {
    fun slider(index: Int) = "graph-slider-$index"
    fun play(index: Int) = "graph-slider-play-$index"
}

/** A slider's value in flight — under the finger — before it is written. */
private data class GraphSliderValue(val graphId: String, val index: Int, val value: Double)

/** Play sweeping one slider: where it has got to, and which way it is going. */
private data class GraphSliderPlay(val graphId: String, val index: Int, val value: Double, val direction: Int)

/** The list with one slider at a value in flight, given as its index and the value. */
private fun List<GraphParameter>.withValue(change: Pair<Int, Double>?): List<GraphParameter> {
    if (change == null) return this
    val (index, value) = change
    val parameter = getOrNull(index) ?: return this
    return toMutableList().also { it[index] = parameter.copy(value = value) }
}

/**
 * The function behind each stored string, parsed once; and its points, found once per window and
 * set of slider values.
 */
private class GraphFunctions {
    private val parsed = HashMap<String, GraphExpression?>()
    private val polynomials = HashMap<String, DoubleArray?>()
    private val points = HashMap<Triple<String, List<Double>, com.vivenotes.graph.GraphWindow>, List<GraphPoint>>()

    /** Unbound: its parameters, if it has any, are not yet given values. */
    fun of(source: String): GraphExpression? = parsed.getOrPut(source) { GraphExpression.parse(source) }

    /** [graph]'s function at its stored slider values. */
    fun of(graph: Outline.Graph): GraphExpression? = of(graph.expression)?.with(graph.parameters.map { it.value })

    fun polynomialOf(source: String): DoubleArray? = polynomials.getOrPut(source) { of(source)?.polynomial() }

    fun pointsOf(graph: Outline.Graph, window: com.vivenotes.graph.GraphWindow): List<GraphPoint> =
        pointsOf(graph.expression, graph.parameters.map { it.value }, window)

    fun pointsOf(source: String, values: List<Double>, window: com.vivenotes.graph.GraphWindow): List<GraphPoint> {
        if (points.size > 64) points.clear()
        return points.getOrPut(Triple(source, values, window)) {
            of(source)?.with(values)?.let { GraphAnalysis.pointsOfInterest(it::evaluate, window) }.orEmpty()
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

/** The tracer in flight: preview only, written once on the lift. */
private data class GraphTraceDrag(val graphId: String, val x: Double)

/** One end of an area taken hold of. */
private data class GraphBoundGrab(val graph: Outline.Graph, val isFrom: Boolean)

/** An area's bounds in flight: preview only, written once on the lift. */
private data class GraphAreaDrag(val graphId: String, val area: GraphInterval)

/** How far either side of a bound's line a finger may land and still take it. */
private const val BOUND_REACH_DP = 14f

/** How near a point of interest the tracer has to come to be pulled onto it. */
private const val TRACE_MAGNET_DP = 12f

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
