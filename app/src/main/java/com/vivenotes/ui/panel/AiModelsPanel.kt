package com.vivenotes.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelsState
import com.vivenotes.ui.icons.MaterialSymbols
import kotlin.math.roundToInt

internal object AiPanelTags {
    const val TEXT_MODEL = "ai-model-text"
    const val FORMULA_MODEL = "ai-model-formula"
    const val DOWNLOAD_FORMULA = "ai-download-formula"
}

@Composable
fun ColumnScope.AiModelsPanelContent(
    state: AiModelsState,
    onDownloadFormula: () -> Unit,
) {
    Text(
        text = "Recognition runs on this device. Ink and results are not uploaded.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PanelSection("On-device models") {
        ModelCard(
            name = "PP-OCRv5 Mobile",
            purpose = "English handwriting to searchable text",
            size = "7.5 MB · included with viveNotes",
            state = state.handwritingText,
            modifier = Modifier.testTag(AiPanelTags.TEXT_MODEL),
        )
        Spacer(Modifier.height(10.dp))
        ModelCard(
            name = "PP-FormulaNet_plus-S",
            purpose = "Handwritten formulas to LaTeX",
            // The ONNX graph's own size, which is what is actually fetched — not PaddleOCR's
            // "248 MB", which measures their checkpoint format and would overstate the download.
            size = "221 MB optional download",
            state = state.formulaLatex,
            onDownload = onDownloadFormula,
            modifier = Modifier.testTag(AiPanelTags.FORMULA_MODEL),
        )
    }

    Text(
        text = "Formula recognition loads only when requested and can require substantially more " +
            "working memory than its download size.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun ModelCard(
    name: String,
    purpose: String,
    size: String,
    state: AiModelInstallState,
    modifier: Modifier = Modifier,
    onDownload: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = purpose,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = size,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        when (state) {
            AiModelInstallState.Installed -> InstalledRow()
            AiModelInstallState.NotInstalled -> DownloadButton("Download", onDownload)
            AiModelInstallState.Verifying -> {
                Text("Verifying…", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is AiModelInstallState.Downloading -> {
                val fraction = if (state.totalBytes == 0L) {
                    0f
                } else {
                    (state.downloadedBytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f)
                }
                Text(
                    text = "Downloading ${(fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is AiModelInstallState.Failed -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                DownloadButton("Retry", onDownload)
            }
        }
    }
}

@Composable
private fun InstalledRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = MaterialSymbols.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Installed",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DownloadButton(label: String, onDownload: (() -> Unit)?) {
    Button(
        onClick = { onDownload?.invoke() },
        enabled = onDownload != null,
        modifier = Modifier.testTag(AiPanelTags.DOWNLOAD_FORMULA),
    ) {
        Text(label)
    }
}
