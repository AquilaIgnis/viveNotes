package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Editable recognition output, with a native RaTeX preview directly below formula source. */
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
            CircularProgressIndicator(
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
        OutlinedTextField(
            value = state.value,
            onValueChange = {
                copied = false
                onValueChange(it)
            },
            minLines = 3,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth().testTag(RecognitionPanelTags.SOURCE),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
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
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
            EquationPreview(analysis.normalizedLatex)
        }
    }

    PanelSection("Actions") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            analysis.actions.forEach { action ->
                OutlinedButton(
                    onClick = { onAction(action.id) },
                    enabled = state.executingActionId == null,
                    modifier = Modifier.testTag(RecognitionPanelTags.action(action.id)),
                ) {
                    if (state.executingActionId == action.id) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
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
                Button(
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
private fun EquationPreview(latex: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val fontSizePx = with(density) { 24.sp.toPx() }
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
            modifier = baseModifier.height(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Enter LaTeX to preview it",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is EquationPreviewState.Failed -> Box(
            modifier = baseModifier.height(96.dp).padding(12.dp),
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
                (contentHeightPx + 32.dp.toPx()).toDp().coerceAtLeast(96.dp)
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
