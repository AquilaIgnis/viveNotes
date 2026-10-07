package com.vivenotes.ui.editor

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.DecimalToggle
import com.vivenotes.ui.panel.EquationPreview
import com.vivenotes.ui.panel.PanelButton
import com.vivenotes.ui.theme.LocalCanvasColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object CalculatorTags {
    const val WINDOW = "calculator-window"
    const val PAD = "calculator-pad"
    const val UNDO = "calculator-undo"
    const val CLEAR = "calculator-clear"
    const val EQUALS = "calculator-equals"
    const val DECIMAL = "calculator-decimal"
    const val CLOSE = "calculator-close"
    const val PROGRESS = "calculator-progress"
    const val RESULT = "calculator-result"
    const val ERROR = "calculator-error"
    const val OPEN_IN_PANEL = "calculator-open-in-panel"
}

/**
 * The experimental hand calculator: a scratch pad floating over the workspace, and = to solve it.
 *
 * Everything written on the pad is the formula, so there is nothing to lasso. Its ink belongs to the
 * pad alone — never to the page, never synced, gone with Clear or with the window — which is why it
 * is drawn with plain `androidx.ink` strokes here rather than through `InkOverlay`, whose every
 * stroke is a row on a page.
 *
 * Placed by its own offset inside a box that fills the window. That box has no pointer input of its
 * own, so a touch outside the calculator falls through to whatever is under it.
 *
 * @param brush the pen in hand, so the pad writes in the ink the page would.
 * @param solve the pad's strokes, in dp, to an answer — see `NotesApp`.
 * @param onOpenInPanel hands the read formula to the recognition pane, where it can be corrected and
 *   every action is a button.
 */
@Composable
internal fun FloatingCalculator(
    brush: Brush,
    allowFinger: Boolean,
    solve: suspend (List<Stroke>) -> CalculatorAnswer,
    onOpenInPanel: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = min(maxWidth - WINDOW_MARGIN * 2, WINDOW_WIDTH)
        val maxX = with(density) { (maxWidth - width).toPx() }.coerceAtLeast(0f)
        var heightPx by remember { mutableIntStateOf(0) }
        val maxY = (constraints.maxHeight - heightPx).toFloat().coerceAtLeast(0f)
        // Null until dragged, so the first placement can follow the window's size: top right, clear
        // of the ribbon, where it covers the least of a page that is read from the left.
        var dragged by remember { mutableStateOf<Offset?>(null) }
        val origin = dragged ?: with(density) {
            Offset(maxX - WINDOW_MARGIN.toPx(), INITIAL_TOP.toPx())
        }
        val placed = Offset(origin.x.coerceIn(0f, maxX), origin.y.coerceIn(0f, maxY))
        val currentPlaced by rememberUpdatedState(placed)
        CalculatorWindow(
            brush = brush,
            allowFinger = allowFinger,
            solve = solve,
            onOpenInPanel = onOpenInPanel,
            onClose = onClose,
            onDrag = { delta ->
                dragged = Offset(
                    (currentPlaced.x + delta.x).coerceIn(0f, maxX),
                    (currentPlaced.y + delta.y).coerceIn(0f, maxY),
                )
            },
            modifier = Modifier
                .offset { IntOffset(placed.x.roundToInt(), placed.y.roundToInt()) }
                .width(width)
                .onSizeChanged { heightPx = it.height },
        )
    }
}

/** Where the calculator is in its one round trip. */
private sealed interface CalculatorState {
    data object Idle : CalculatorState
    data object Working : CalculatorState
    data class Done(val answer: CalculatorAnswer) : CalculatorState
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CalculatorWindow(
    brush: Brush,
    allowFinger: Boolean,
    solve: suspend (List<Stroke>) -> CalculatorAnswer,
    onOpenInPanel: (String) -> Unit,
    onClose: () -> Unit,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strokes = remember { mutableStateListOf<Stroke>() }
    var state by remember { mutableStateOf<CalculatorState>(CalculatorState.Idle) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    /** Kept across answers and Clear: a run of sums is usually wanted in one form. */
    var showDecimal by remember { mutableStateOf(false) }
    val currentOnDrag by rememberUpdatedState(onDrag)

    fun clear() {
        job?.cancel()
        strokes.clear()
        state = CalculatorState.Idle
    }

    fun calculate() {
        if (strokes.isEmpty() || state == CalculatorState.Working) return
        val written = strokes.toList()
        state = CalculatorState.Working
        job = scope.launch {
            state = try {
                CalculatorState.Done(solve(written))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                CalculatorState.Done(
                    CalculatorAnswer(
                        latex = "",
                        error = failure.message ?: "The handwriting could not be recognized.",
                    ),
                )
            }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.testTag(CalculatorTags.WINDOW),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            // The title bar is the handle. Dragging the pad itself would be writing on it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            currentOnDrag(amount)
                        }
                    }
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MaterialSymbols.DragIndicator,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Calculator",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                ExperimentalLabel()
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.testTag(CalculatorTags.CLOSE)) {
                    Icon(
                        imageVector = MaterialSymbols.Close,
                        contentDescription = "Close calculator",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            WritingPad(
                strokes = strokes,
                brush = brush,
                allowFinger = allowFinger,
                modifier = Modifier.fillMaxWidth().height(PAD_HEIGHT),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = { strokes.removeAt(strokes.lastIndex) },
                    enabled = strokes.isNotEmpty() && state != CalculatorState.Working,
                    modifier = Modifier.testTag(CalculatorTags.UNDO),
                ) {
                    Icon(MaterialSymbols.Undo, contentDescription = "Undo last stroke")
                }
                TextButton(
                    onClick = ::clear,
                    enabled = strokes.isNotEmpty() || state != CalculatorState.Idle,
                    modifier = Modifier.testTag(CalculatorTags.CLEAR),
                ) {
                    Text("Clear")
                }
                Spacer(Modifier.weight(1f))
                // Before = as well as after it, so the form can be chosen ahead of the answer.
                DecimalToggle(
                    checked = showDecimal,
                    onCheckedChange = { showDecimal = it },
                    modifier = Modifier.testTag(CalculatorTags.DECIMAL),
                )
                Spacer(Modifier.width(4.dp))
                // `tertiary`, as the recognition pane's math actions are: this is one of them.
                PanelButton(
                    onClick = ::calculate,
                    enabled = strokes.isNotEmpty() && state != CalculatorState.Working,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                    modifier = Modifier
                        .testTag(CalculatorTags.EQUALS)
                        .semantics { contentDescription = "Calculate" },
                ) {
                    Text("=", style = MaterialTheme.typography.titleMedium)
                }
            }

            when (val current = state) {
                CalculatorState.Idle -> Unit
                CalculatorState.Working -> Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LoadingIndicator(Modifier.size(32.dp).testTag(CalculatorTags.PROGRESS))
                    Text(
                        text = "Reading your handwriting…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is CalculatorState.Done -> AnswerContent(current.answer, showDecimal, onOpenInPanel)
            }
        }
    }
}

/** A small tertiary pill, so nobody mistakes a trial for a finished feature. */
@Composable
private fun ExperimentalLabel() {
    Text(
        text = "Experimental",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun AnswerContent(
    answer: CalculatorAnswer,
    showDecimal: Boolean,
    onOpenInPanel: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        // What was read, smaller, above what it came to — a wrong answer is usually a misread, and
        // this is where that shows.
        if (answer.latex.isNotBlank()) {
            EquationPreview(answer.latex, scale = QUESTION_SCALE)
        }
        answer.result?.let { result ->
            Text(
                text = result.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            result.latex?.takeIf(String::isNotBlank)?.let { latex ->
                // An answer with no decimal form reads the same either way: 48 is 48.
                val shown = if (showDecimal) result.decimal ?: latex else latex
                Box(Modifier.testTag(CalculatorTags.RESULT)) { EquationPreview(shown) }
            }
            result.message?.takeIf(String::isNotBlank)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        answer.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp).testTag(CalculatorTags.ERROR),
            )
        }
        if (answer.latex.isNotBlank()) {
            TextButton(
                onClick = { onOpenInPanel(answer.latex) },
                modifier = Modifier.testTag(CalculatorTags.OPEN_IN_PANEL),
            ) {
                Text("Open in panel")
            }
        }
    }
}

/**
 * Where the formula is written.
 *
 * Strokes are kept in dp, the pad's stand-in for page units: `renderAllInk` sizes its bitmap and its
 * stem from the ink's extent, and page units are what that arithmetic was tuned on. The same finger
 * rule as the page — a stylus always writes, a finger only when *Let a finger draw* is on — so a palm
 * resting on the pad while the pen writes is not a stroke.
 */
@Composable
private fun WritingPad(
    strokes: MutableList<Stroke>,
    brush: Brush,
    allowFinger: Boolean,
    modifier: Modifier = Modifier,
) {
    val canvas = LocalCanvasColors.current
    val renderer = remember { CanvasStrokeRenderer.create() }
    var live by remember { mutableStateOf<Stroke?>(null) }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(canvas.background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .semantics { contentDescription = "Calculator writing area" }
            .testTag(CalculatorTags.PAD),
        contentAlignment = Alignment.Center,
    ) {
        if (strokes.isEmpty() && live == null) {
            Text(
                text = "Write a calculation, then press =",
                style = MaterialTheme.typography.bodyMedium,
                color = canvas.secondaryText,
            )
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(brush, allowFinger) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (down.type == PointerType.Touch && !allowFinger) return@awaitEachGesture
                        down.consume()
                        val writing = PadStroke(brush, down.type, down.uptimeMillis)
                        writing.add(down.position / density, down.uptimeMillis, down.pressure)
                        live = writing.stroke()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.historical.forEach {
                                writing.add(it.position / density, it.uptimeMillis, change.pressure)
                            }
                            writing.add(change.position / density, change.uptimeMillis, change.pressure)
                            change.consume()
                            live = writing.stroke()
                            if (!change.pressed) break
                        }
                        writing.stroke()?.let(strokes::add)
                        live = null
                    }
                },
        ) {
            val matrix = Matrix().apply { setScale(density, density) }
            drawIntoCanvas { composeCanvas ->
                val native = composeCanvas.nativeCanvas
                val checkpoint = native.save()
                // On the canvas *and* to the renderer, as `InkOverlay` does: the canvas moves the
                // geometry, the argument only picks the mesh detail for the scale it is drawn at.
                native.concat(matrix)
                strokes.forEach { renderer.draw(native, it, matrix) }
                live?.let { renderer.draw(native, it, matrix) }
                native.restoreToCount(checkpoint)
            }
        }
    }
}

/** One stroke being written on the pad. */
private class PadStroke(
    private val brush: Brush,
    pointer: PointerType,
    private val startMillis: Long,
) {
    private val tool = when (pointer) {
        PointerType.Stylus -> InputToolType.STYLUS
        PointerType.Mouse -> InputToolType.MOUSE
        PointerType.Touch -> InputToolType.TOUCH
        else -> InputToolType.UNKNOWN
    }

    /** Only a stylus reports pressure worth having, and a batch must carry it on every input or none. */
    private val withPressure = pointer == PointerType.Stylus
    private val inputs = MutableStrokeInputBatch()
    private var lastElapsed = -1L
    private var last: Offset? = null

    fun add(position: Offset, uptimeMillis: Long, pressure: Float) {
        // A repeated point adds nothing, and the batch's own validation is stricter than this pad.
        if (position == last) return
        val elapsed = (uptimeMillis - startMillis).coerceAtLeast(lastElapsed + 1)
        if (withPressure) {
            inputs.add(
                tool,
                position.x,
                position.y,
                elapsed,
                StrokeInput.NO_STROKE_UNIT_LENGTH,
                pressure.coerceIn(0f, 1f),
            )
        } else {
            inputs.add(tool, position.x, position.y, elapsed)
        }
        lastElapsed = elapsed
        last = position
    }

    fun stroke(): Stroke? = if (inputs.isEmpty()) null else Stroke(brush, inputs.toImmutable())
}

private val WINDOW_WIDTH = 380.dp
private val WINDOW_MARGIN = 16.dp

/** Below the ribbon on a tablet, so the first thing the window covers is page and not toolbar. */
private val INITIAL_TOP = 140.dp
private val PAD_HEIGHT = 200.dp

/** The formula as read, beneath which its answer is drawn at full size. */
private const val QUESTION_SCALE = 0.75f
