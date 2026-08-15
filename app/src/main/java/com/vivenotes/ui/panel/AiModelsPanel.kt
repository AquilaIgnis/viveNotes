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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelsState
import com.vivenotes.data.ImageTextProgress
import com.vivenotes.data.InkTextProgress
import com.vivenotes.ui.icons.MaterialSymbols
import kotlin.math.roundToInt

internal object AiPanelTags {
    const val TEXT_MODEL = "ai-model-text"
    const val FORMULA_MODEL = "ai-model-formula"
    const val DOWNLOAD_FORMULA = "ai-download-formula"
    const val PICTURE_TEXT = "ai-picture-text"
    const val PICTURE_TEXT_SWITCH = "ai-picture-text-switch"
    const val PICTURE_TEXT_REBUILD = "ai-picture-text-rebuild"
    const val INK_TEXT = "ai-ink-text"
    const val INK_TEXT_SWITCH = "ai-ink-text-switch"
    const val INK_TEXT_REBUILD = "ai-ink-text-rebuild"
}

@Composable
fun ColumnScope.AiModelsPanelContent(
    state: AiModelsState,
    onDownloadFormula: () -> Unit,
    pictureText: ImageTextProgress = ImageTextProgress(enabled = false),
    picturesRead: Int = 0,
    onSetPictureText: (Boolean) -> Unit = {},
    onRebuildPictureText: () -> Unit = {},
    inkText: InkTextProgress = InkTextProgress(enabled = false),
    inkPagesRead: Int = 0,
    onSetInkText: (Boolean) -> Unit = {},
    onRebuildInkText: () -> Unit = {},
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
            // Says *when* rather than "optional", because a first run on Wi-Fi fetches this by
            // itself — and because on mobile data the Download button below is the whole
            // explanation of why nothing has happened yet.
            size = "224 MB · downloads by itself on Wi-Fi",
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

    PanelSection("Handwriting in search") {
        HandwritingTextCard(
            progress = inkText,
            pagesRead = inkPagesRead,
            onSetEnabled = onSetInkText,
            onRebuild = onRebuildInkText,
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
private fun HandwritingTextCard(
    progress: InkTextProgress,
    pagesRead: Int,
    onSetEnabled: (Boolean) -> Unit,
    onRebuild: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag(AiPanelTags.INK_TEXT),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SWITCH_GAP),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Search handwriting",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Reads drawn words in the background and keeps recognition on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = progress.enabled,
                onCheckedChange = onSetEnabled,
                modifier = Modifier.scale(SWITCH_SCALE).testTag(AiPanelTags.INK_TEXT_SWITCH),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = progress.summaryLine(pagesRead),
            style = MaterialTheme.typography.labelMedium,
            color = if (progress.failed > 0) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress.running) {
            Spacer(Modifier.height(6.dp))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onRebuild,
            enabled = progress.enabled && !progress.running,
            modifier = Modifier.testTag(AiPanelTags.INK_TEXT_REBUILD),
        ) {
            Text("Read every handwritten page again")
        }
    }
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
            horizontalArrangement = Arrangement.spacedBy(SWITCH_GAP),
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
                modifier = Modifier
                    .scale(SWITCH_SCALE)
                    .testTag(AiPanelTags.PICTURE_TEXT_SWITCH),
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

/** Keeps the description clear of the switch instead of letting the two meet in the middle. */
private val SWITCH_GAP = 16.dp

/**
 * How much smaller the switch is drawn than Material's own size.
 *
 * `scale` rather than a size modifier because it leaves measurement alone, so the row's layout and
 * the text beside it are unaffected by the change.
 *
 * **It is not free, though, and the cost is the touch target.** `scale` is a `graphicsLayer`, and
 * Compose applies the layer transform when hit-testing as well as when drawing — measured at
 * 46.8 × 28.8 dp against Material's 52 × 32. That is still a comfortable target on the tablet this
 * app is built for, and it is the reason not to take this any lower without deciding to:
 * `AiModelsPanelTest.theSwitchStaysAboveTheSizeAFingerCanFind` holds the floor.
 */
private const val SWITCH_SCALE = 0.9f

private fun ImageTextProgress.summaryLine(picturesRead: Int): String = when {
    !enabled -> "Off. Pictures are not read and nothing is stored."
    running -> "Reading… ${pending.coerceAtLeast(0)} to go"
    failed > 0 -> "$picturesRead read · $failed could not be read"
    picturesRead == 1 -> "1 picture read"
    else -> "$picturesRead pictures read"
}

private fun InkTextProgress.summaryLine(pagesRead: Int): String = when {
    !enabled -> "Off. Handwriting is not read and nothing is stored."
    running -> "Reading… ${pending.coerceAtLeast(0)} pages to go"
    failed > 0 -> "$pagesRead pages read · $failed could not be read"
    pagesRead == 1 -> "1 page read"
    else -> "$pagesRead pages read"
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
