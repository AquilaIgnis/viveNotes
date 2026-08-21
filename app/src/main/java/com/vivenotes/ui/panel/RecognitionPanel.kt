package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.BuildConfig
import com.vivenotes.math.FormulaToolsState
import com.vivenotes.math.MathGraph
import com.vivenotes.richtext.createEquationRenderer
import io.ratex.RaTeXRenderer
import kotlin.math.abs

internal enum class RecognitionOutputKind { Text, Formula }

internal data class RecognitionPanelState(
    val kind: RecognitionOutputKind,
    val value: String = "",
    val running: Boolean = false,
    val error: String? = null,
)

internal object RecognitionPanelTags {
    const val PROGRESS = "recognition-progress"
    const val SOURCE = "recognition-source"
    const val PREVIEW = "recognition-preview"
    const val COPY = "recognition-copy"
    const val COPIED = "recognition-copied"
    const val MATH_ANALYZING = "recognition-math-analyzing"
    const val INTERPRETATION = "recognition-interpretation"
    const val MATH_ERROR = "recognition-math-error"
    const val RESULT = "recognition-math-result"
    const val GRAPH = "recognition-math-graph"
    fun action(id: String) = "recognition-math-action-$id"
}

/**
 * How big the in-button indicator is drawn.
 *
 * Not `LoadingIndicatorDefaults.IndicatorSize`, which is sized for a wait that owns its own space.
 * Inside a button it stands in for a text label, so it matches roughly what that label occupied — a
 * full-size indicator would resize the button the moment an action started running.
 *
 * 16dp rather than the 20 it started at: the action buttons are now [PanelButton], whose container is
 * `ButtonDefaults.ExtraSmallContainerHeight`, and 20dp left the indicator touching the padding at top
 * and bottom.
 */
private val IN_BUTTON_INDICATOR = 16.dp

/**
 * Editable recognition output, with a native RaTeX preview directly below formula source.
 *
 * **The waits are M3 Expressive loading indicators**, which is why this file opts in to
 * [ExperimentalMaterial3ExpressiveApi] and why `gradle/libs.versions.toml` pins material3 to a
 * pre-release: `ContainedLoadingIndicator` ships in no stable material3 and in no Compose BOM. The
 * catalog comment has the evidence and the exit condition; the opt-in is per-composable rather than a
 * module-wide compiler flag so the blast radius of the experimental API is visible at each use.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.RecognitionPanelContent(
    state: RecognitionPanelState,
    formulaTools: FormulaToolsState = FormulaToolsState(),
    onValueChange: (String) -> Unit,
    onCopy: (String) -> Unit,
    onMathAction: (String) -> Unit = {},
    onCopyMathResult: (String) -> Unit = {},
) {
    if (state.running) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ContainedLoadingIndicator(
                modifier = Modifier.testTag(RecognitionPanelTags.PROGRESS),
            )
            Text("Processing selected ink on this device…")
        }
        return
    }

    state.error?.let { message ->
        PanelSection("Recognition failed") {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }

    var copied by remember(state.kind) { mutableStateOf(false) }
    val sourceLabel = if (state.kind == RecognitionOutputKind.Formula) "LaTeX" else "Text"
    PanelSection(sourceLabel) {
        SourceField(
            value = state.value,
            onValueChange = {
                copied = false
                onValueChange(it)
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PanelButton(
                onClick = {
                    onCopy(state.value)
                    copied = true
                },
                enabled = state.value.isNotBlank(),
                modifier = Modifier.testTag(RecognitionPanelTags.COPY),
            ) {
                Text("Copy")
            }
            if (copied) {
                Text(
                    text = "Copied to clipboard",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(RecognitionPanelTags.COPIED),
                )
            }
        }
    }

    if (state.kind == RecognitionOutputKind.Formula) {
        PanelSection("Preview") {
            EquationPreview(state.value)
        }
        FormulaToolsContent(
            state = formulaTools,
            onAction = onMathAction,
            onCopyResult = onCopyMathResult,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.FormulaToolsContent(
    state: FormulaToolsState,
    onAction: (String) -> Unit,
    onCopyResult: (String) -> Unit,
) {
    if (state.analyzing) {
        PanelSection("Math actions") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.testTag(RecognitionPanelTags.MATH_ANALYZING),
            ) {
                ContainedLoadingIndicator()
                Text("Understanding the LaTeX with SymPy…")
            }
        }
        return
    }

    state.error?.takeIf { state.analysis == null }?.let { message ->
        PanelSection("Math actions") {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(RecognitionPanelTags.MATH_ERROR),
            )
        }
        return
    }

    val analysis = state.analysis ?: return

    // **Debug builds only.** This section is a read-out of the SymPy round-trip — the parsed
    // summary, the free variables, and the LaTeX the engine normalised the formula to — and it was
    // written to debug the bridge, not for someone doing arithmetic on a page. It answers a question
    // a user never asks and shows the same formula they are already looking at, one section above,
    // in Preview.
    //
    // `BuildConfig.DEBUG` is a `static final` constant, so the block below is not merely hidden in
    // release: the compiler folds the condition before R8 processes the release artifact. Verified
    // rather than assumed: "Understood as" appears in the debug APK's dex and in none of the
    // release APK's.
    //
    // `analysis.summary`, `variables` and `normalizedLatex` stay in the model regardless — the
    // actions are derived from the same analysis, and a debug read-out is worth keeping the moment
    // the bridge misbehaves again.
    if (BuildConfig.DEBUG) {
        PanelSection("Understood as") {
            Text(
                text = buildString {
                    append(analysis.summary)
                    if (analysis.variables.isNotEmpty()) {
                        append(" · Variables: ")
                        append(analysis.variables.joinToString())
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.testTag(RecognitionPanelTags.INTERPRETATION)) {
                EquationPreview(analysis.normalizedLatex, scale = INTERPRETATION_SCALE)
            }
        }
    }

    PanelSection("Actions") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            analysis.actions.forEach { action ->
                PanelButton(
                    onClick = { onAction(action.id) },
                    enabled = state.executingActionId == null,
                    // Filled with the complement of the brand azure — `tertiary`, the user's #FF8000.
                    // These were outlined and drawn in `primary`, which is what read as washed out.
                    // Filled also separates them from Copy above, which stays azure: the math actions
                    // are a family of their own, not more of the same button.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                    modifier = Modifier.testTag(RecognitionPanelTags.action(action.id)),
                ) {
                    if (state.executingActionId == action.id) {
                        // The uncontained one, deliberately: this sits *inside* a button, which is
                        // already a container, and the contained form would be a box in a box. Sized
                        // down to the label it replaces so the button does not jump when it appears.
                        LoadingIndicator(Modifier.size(IN_BUTTON_INDICATOR))
                    } else {
                        Text(action.label)
                    }
                }
            }
        }
    }

    state.error?.let { message ->
        PanelSection("Operation failed") {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(RecognitionPanelTags.MATH_ERROR),
            )
        }
    }

    state.result?.let { result ->
        PanelSection(result.title) {
            result.latex?.takeIf(String::isNotBlank)?.let { latex ->
                EquationPreview(latex)
                PanelButton(
                    onClick = { onCopyResult(latex) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Copy result")
                }
            }
            result.message?.takeIf(String::isNotBlank)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            result.graph?.let { graph -> MathGraphPreview(graph) }
            Box(Modifier.testTag(RecognitionPanelTags.RESULT))
        }
    }
}

/**
 * The recognised source, editable — LaTeX or prose.
 *
 * **Not an `OutlinedTextField`, and the reason is vertical space.** Material's field is built for a
 * form: 16dp of padding above and below, `bodyLarge` inside, and a container tall enough to hold a
 * floating label this one never shows. In a 320dp pane the field is followed by a preview, an
 * interpretation, a row of actions and often a graph, and the field was eating the room they need.
 * This is the same construction [PanelMeasure] uses — a `BasicTextField` inside a bordered box — so
 * the panel keeps one field idiom rather than two.
 *
 * **Monospaced, because LaTeX is code.** It is read for its backslashes and braces, where a
 * proportional face closes up `\\,` and `{}` into mush; monospace also fits more characters per line,
 * which is the other half of making the box smaller.
 *
 * **One line minimum, so the box is the size of what is in it.** This is the part that was actually
 * wrong: `minLines` held the field open at three lines and then two, so a one-line formula — which is
 * most of them — was followed by a band of empty field. A minimum is for a box you expect to type a
 * lot into; this one usually holds a correction. Six maximum, so a long expression still scrolls
 * inside the field rather than pushing the preview off the pane.
 */
@Composable
private fun SourceField(value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        minLines = 1,
        maxLines = 6,
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = SOURCE_TEXT_SIZE,
            lineHeight = SOURCE_LINE_HEIGHT,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(RecognitionPanelTags.SOURCE),
    )
}

/**
 * 12sp — tried at 7.5 and brought back up, because that was too small to read on the device.
 *
 * The box is still much shorter than it was, and `minLines = 1` is where nearly all of that came from:
 * holding the field open for three lines cost more room than the type size ever did. Padding stays
 * tight at 6/4dp.
 */
private val SOURCE_TEXT_SIZE = 12.sp
private val SOURCE_LINE_HEIGHT = 15.sp

/** What a rendered equation is drawn at, and the box it is drawn in, before any scaling. */
private val PREVIEW_FONT_SIZE = 24.sp
private val PREVIEW_MIN_HEIGHT = 96.dp
private val PREVIEW_PADDING = 32.dp

/**
 * The interpretation renders at 85%.
 *
 * It is the same expression as the Preview above it, re-rendered from SymPy's normalisation — a
 * confirmation that the engine read it correctly, not the thing you are reading. Drawing it at full
 * size gave one formula two equal-weight appearances in a pane already short of height. The Preview
 * and the operation result keep 100%: those are the answers.
 */
private const val INTERPRETATION_SCALE = 0.85f

@Composable
private fun MathGraphPreview(graph: MathGraph) {
    val samples = remember(graph) {
        graph.xValues.zip(graph.yValues).filter { (_, y) -> y != null && y.isFinite() }
    }
    if (samples.isEmpty()) return
    val xMin = graph.xValues.minOrNull() ?: return
    val xMax = graph.xValues.maxOrNull() ?: return
    val rawYMin = samples.minOf { it.second!! }
    val rawYMax = samples.maxOf { it.second!! }
    val yPadding = ((rawYMax - rawYMin) * 0.08).takeIf { it > 0.0 } ?: 1.0
    val yMin = rawYMin - yPadding
    val yMax = rawYMax + yPadding
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val graphColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(top = 10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag(RecognitionPanelTags.GRAPH),
    ) {
        fun mapX(value: Double): Float =
            ((value - xMin) / (xMax - xMin) * size.width).toFloat()
        fun mapY(value: Double): Float =
            (size.height - (value - yMin) / (yMax - yMin) * size.height).toFloat()

        for (step in 1 until 4) {
            val x = size.width * step / 4f
            val y = size.height * step / 4f
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
        }
        if (xMin <= 0.0 && xMax >= 0.0) {
            val x = mapX(0.0)
            drawLine(axisColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
        if (yMin <= 0.0 && yMax >= 0.0) {
            val y = mapY(0.0)
            drawLine(axisColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
        }

        val path = Path()
        var previousY: Float? = null
        graph.xValues.zip(graph.yValues).forEach { (xValue, yValue) ->
            if (yValue == null || !yValue.isFinite()) {
                previousY = null
            } else {
                val x = mapX(xValue)
                val y = mapY(yValue)
                if (previousY == null || abs(y - previousY!!) > size.height * 1.5f) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
                previousY = y
            }
        }
        drawPath(path, graphColor, style = Stroke(width = 3f))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("${graph.xLabel}: ${xMin.compact()}…${xMax.compact()}", style = MaterialTheme.typography.labelSmall)
        Text(graph.yLabel, style = MaterialTheme.typography.labelSmall)
    }
}

private fun Double.compact(): String = if (this == toLong().toDouble()) toLong().toString() else "%.2f".format(this)

@Composable
private fun EquationPreview(latex: String, scale: Float = 1f) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    // Scaled together, so the block shrinks rather than the glyphs rattling inside a box that did
    // not: the type, the floor height and the breathing room above and below all take the same factor.
    val fontSizePx = with(density) { (PREVIEW_FONT_SIZE * scale).toPx() }
    val minHeight = PREVIEW_MIN_HEIGHT * scale
    val verticalPadding = PREVIEW_PADDING * scale
    var preview by remember { mutableStateOf<EquationPreviewState>(EquationPreviewState.Empty) }

    LaunchedEffect(latex, color, fontSizePx) {
        preview = if (latex.isBlank()) {
            EquationPreviewState.Empty
        } else {
            try {
                EquationPreviewState.Ready(
                    createEquationRenderer(context, latex, fontSizePx, color),
                )
            } catch (failure: Exception) {
                EquationPreviewState.Failed(
                    failure.message?.takeIf(String::isNotBlank) ?: "LaTeX could not be rendered",
                )
            }
        }
    }

    val baseModifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .testTag(RecognitionPanelTags.PREVIEW)
    when (val current = preview) {
        EquationPreviewState.Empty -> Box(
            modifier = baseModifier.height(minHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Enter LaTeX to preview it",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is EquationPreviewState.Failed -> Box(
            modifier = baseModifier.height(minHeight).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = current.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is EquationPreviewState.Ready -> {
            val renderer = current.renderer
            val contentHeightPx = renderer.heightPx + renderer.depthPx
            val previewHeight = with(density) {
                (contentHeightPx + verticalPadding.toPx()).toDp().coerceAtLeast(minHeight)
            }
            Canvas(modifier = baseModifier.height(previewHeight)) {
                val x = ((size.width - renderer.widthPx) / 2f).coerceAtLeast(0f)
                val y = ((size.height - contentHeightPx) / 2f).coerceAtLeast(0f)
                drawContext.canvas.nativeCanvas.apply {
                    save()
                    translate(x, y)
                    renderer.draw(this)
                    restore()
                }
            }
        }
    }
}

private sealed interface EquationPreviewState {
    data object Empty : EquationPreviewState
    data class Ready(val renderer: RaTeXRenderer) : EquationPreviewState
    data class Failed(val message: String) : EquationPreviewState
}
