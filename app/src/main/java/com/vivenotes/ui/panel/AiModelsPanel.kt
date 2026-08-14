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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelsState
import com.vivenotes.data.ImageTextProgress
import com.vivenotes.ui.icons.MaterialSymbols
import kotlin.math.roundToInt

internal object AiPanelTags {
    const val TEXT_MODEL = "ai-model-text"
    const val FORMULA_MODEL = "ai-model-formula"
    const val DOWNLOAD_FORMULA = "ai-download-formula"
    const val PICTURE_TEXT = "ai-picture-text"
    const val PICTURE_TEXT_SWITCH = "ai-picture-text-switch"
    const val PICTURE_TEXT_REBUILD = "ai-picture-text-rebuild"
}

@Composable
fun ColumnScope.AiModelsPanelContent(
    state: AiModelsState,
    onDownloadFormula: () -> Unit,
    pictureText: ImageTextProgress = ImageTextProgress(enabled = false),
    picturesRead: Int = 0,
    onSetPictureText: (Boolean) -> Unit = {},
    onRebuildPictureText: () -> Unit = {},
) {
    Text(
        text = "Recognition runs on this device. Ink and results are not uploaded.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PanelSection("On-device models") {
        ModelCard(
            name = "PP-OCRv5 Mobile",
            purpose = "Handwriting and pictures to searchable text",
            size = "12.7 MB · included with viveNotes",
            state = state.handwritingText,
            modifier = Modifier.testTag(AiPanelTags.TEXT_MODEL),
        )
        Spacer(Modifier.height(10.dp))
        ModelCard(
            name = "PP-FormulaNet-S",
            purpose = "Handwritten formulas to LaTeX",
            size = "224 MB optional download",
            state = state.formulaLatex,
            onDownload = onDownloadFormula,
            modifier = Modifier.testTag(AiPanelTags.FORMULA_MODEL),
        )
    }

    PanelSection("Text in pictures") {
        PictureTextCard(
            progress = pictureText,
            picturesRead = picturesRead,
            onSetEnabled = onSetPictureText,
            onRebuild = onRebuildPictureText,
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

/**
 * The switch, the count and the rebuild — `memory/imageOcrPlan.md` IO9.
 *
 * On this tab rather than in View settings because what it describes is *this device spending its
 * own CPU*, which is the rule for what belongs here. Rebuild is offered beside it because the table
 * is derived: throwing it away costs the time to rebuild it and nothing else, so it is the honest
 * answer both to a new engine version and to a suspicion that a picture was read wrongly.
 */
@Composable
private fun PictureTextCard(
    progress: ImageTextProgress,
    picturesRead: Int,
    onSetEnabled: (Boolean) -> Unit,
    onRebuild: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag(AiPanelTags.PICTURE_TEXT),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Search text in pictures",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Reads pasted screenshots and photos so the Content panel can find them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = progress.enabled,
                onCheckedChange = onSetEnabled,
                modifier = Modifier.testTag(AiPanelTags.PICTURE_TEXT_SWITCH),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = progress.summaryLine(picturesRead),
            style = MaterialTheme.typography.labelMedium,
            color = if (progress.failed > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (progress.running) {
            Spacer(Modifier.height(6.dp))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onRebuild,
            enabled = progress.enabled && !progress.running,
            modifier = Modifier.testTag(AiPanelTags.PICTURE_TEXT_REBUILD),
        ) {
            Text("Read every picture again")
        }
    }
}

private fun ImageTextProgress.summaryLine(picturesRead: Int): String = when {
    !enabled -> "Off. Pictures are not read and nothing is stored."
    running -> "Reading… ${pending.coerceAtLeast(0)} to go"
    failed > 0 -> "$picturesRead read · $failed could not be read"
    picturesRead == 1 -> "1 picture read"
    else -> "$picturesRead pictures read"
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
