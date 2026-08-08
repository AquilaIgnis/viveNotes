package com.vivenotes.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.richtext.createEquationRenderer
import io.ratex.RaTeXRenderer

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
}

/** Editable recognition output, with a native RaTeX preview directly below formula source. */
@Composable
internal fun ColumnScope.RecognitionPanelContent(
    state: RecognitionPanelState,
    onValueChange: (String) -> Unit,
    onCopy: (String) -> Unit,
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
    }
}

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
