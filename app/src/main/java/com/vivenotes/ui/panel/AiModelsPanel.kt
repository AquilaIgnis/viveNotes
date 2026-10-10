package com.vivenotes.ui.panel

import android.text.format.Formatter
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelStore
import com.vivenotes.ai.AiModelsState
import com.vivenotes.ai.FormulaEngine
import com.vivenotes.data.ImageTextProgress
import com.vivenotes.data.InkTextProgress
import com.vivenotes.ui.icons.MaterialSymbols
import kotlin.math.roundToInt

internal object AiPanelTags {
    const val TEXT_MODEL = "ai-model-text"
    fun formulaCard(engine: FormulaEngine) = "ai-formula-${engine.name}"
    fun formulaDownload(engine: FormulaEngine) = "ai-formula-download-${engine.name}"
    fun formulaDelete(engine: FormulaEngine) = "ai-formula-delete-${engine.name}"
    fun formulaUse(engine: FormulaEngine) = "ai-formula-use-${engine.name}"
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
    onDownloadFormula: (FormulaEngine) -> Unit,
    onDeleteFormula: (FormulaEngine) -> Unit = {},
    onSelectFormulaEngine: (FormulaEngine) -> Unit = {},
    pictureText: ImageTextProgress = ImageTextProgress(enabled = false),
    picturesRead: Int = 0,
    onSetPictureText: (Boolean) -> Unit = {},
    onRebuildPictureText: () -> Unit = {},
    inkText: InkTextProgress = InkTextProgress(enabled = false),
    inkPagesRead: Int = 0,
    onSetInkText: (Boolean) -> Unit = {},
    onRebuildInkText: () -> Unit = {},
) {
    PanelSection(stringResource(R.string.ai_section_math)) {
        FormulaEngine.entries.forEachIndexed { index, engine ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            val install = state.formula(engine)
            MathModelCard(
                engine = engine,
                install = install,
                inUse = engine == state.formulaEngine && install == AiModelInstallState.Installed,
                onDownload = { onDownloadFormula(engine) },
                onDelete = { onDeleteFormula(engine) },
                onUse = { onSelectFormulaEngine(engine) },
            )
        }
    }

    PanelSection(stringResource(R.string.ai_section_text)) {
        TextModelCard(state.handwritingText)
    }

    PanelSection(stringResource(R.string.ai_section_text_in_pictures)) {
        PictureTextCard(
            progress = pictureText,
            picturesRead = picturesRead,
            onSetEnabled = onSetPictureText,
            onRebuild = onRebuildPictureText,
        )
    }

    PanelSection(stringResource(R.string.ai_section_handwriting_in_search)) {
        HandwritingTextCard(
            progress = inkText,
            pagesRead = inkPagesRead,
            onSetEnabled = onSetInkText,
            onRebuild = onRebuildInkText,
        )
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * One formula model: what it is good at and its size, then what can be done with it.
 *
 * Not installed, it offers Download, and while downloading it shows how far. Installed, it offers
 * Use and Delete. The model the Math button runs shows a checked "In use" in place of Use.
 *
 * Use is a [ToggleButton], as the Hardware pane picks its device: the two cards are one mutually
 * exclusive choice, so pressing the model already in use is dropped rather than leaving none. The
 * check and "In use" are the selected state itself, so they read as selected to TalkBack as well as
 * on screen.
 */
@Composable
private fun MathModelCard(
    engine: FormulaEngine,
    install: AiModelInstallState,
    inUse: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
) {
    val size = Formatter.formatShortFileSize(LocalContext.current, AiModelStore.downloadBytes(engine))
    ModelCardFrame(Modifier.testTag(AiPanelTags.formulaCard(engine))) {
        Text(
            text = stringResource(engine.displayName),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.panel_separated, stringResource(engine.strength), size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        when (install) {
            AiModelInstallState.Installed -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToggleButton(
                    checked = inUse,
                    onCheckedChange = { if (it) onUse() },
                    modifier = Modifier.testTag(AiPanelTags.formulaUse(engine)),
                ) {
                    if (inUse) {
                        Icon(
                            imageVector = MaterialSymbols.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ai_model_in_use))
                    } else {
                        Text(stringResource(R.string.ai_model_use))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag(AiPanelTags.formulaDelete(engine)),
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_model_delete))
                }
            }
            AiModelInstallState.NotInstalled -> DownloadButton(stringResource(R.string.ai_model_download), engine, onDownload)
            AiModelInstallState.Verifying -> {
                Text(stringResource(R.string.ai_model_verifying), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is AiModelInstallState.Downloading -> {
                val fraction = if (install.totalBytes == 0L) {
                    0f
                } else {
                    (install.downloadedBytes.toDouble() / install.totalBytes).toFloat().coerceIn(0f, 1f)
                }
                Text(
                    text = stringResource(R.string.ai_model_downloading, (fraction * 100).roundToInt()),
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
                    text = install.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                DownloadButton(stringResource(R.string.ai_model_retry), engine, onDownload)
            }
        }
    }
}

/** The bundled OCR model. Nothing to download, delete or choose, so it only says what it is. */
@Composable
private fun TextModelCard(install: AiModelInstallState) {
    ModelCardFrame(Modifier.testTag(AiPanelTags.TEXT_MODEL)) {
        Text(
            text = stringResource(R.string.ai_model_ocr_name),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.ai_model_ocr_detail),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (install is AiModelInstallState.Failed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = install.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ModelCardFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        content = content,
    )
}

@get:StringRes
private val FormulaEngine.displayName: Int
    get() = when (this) {
        FormulaEngine.UniMerNetTiny -> R.string.ai_model_unimernet_name
        FormulaEngine.FormulaNetS -> R.string.ai_model_formulanet_name
    }

/** What each is measurably better at — `simulations/formula-models`. */
@get:StringRes
private val FormulaEngine.strength: Int
    get() = when (this) {
        FormulaEngine.UniMerNetTiny -> R.string.ai_model_unimernet_strength
        FormulaEngine.FormulaNetS -> R.string.ai_model_formulanet_strength
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
                    text = stringResource(R.string.ai_ink_text_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.ai_ink_text_description),
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
            Text(stringResource(R.string.ai_ink_text_rebuild))
        }
    }
}

/**
 * The switch, the count and the rebuild.
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
                    text = stringResource(R.string.ai_picture_text_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.ai_picture_text_description),
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
            Text(stringResource(R.string.ai_picture_text_rebuild))
        }
    }
}

/** Keeps the description clear of the switch instead of letting the two meet in the middle. */
private val SWITCH_GAP = 16.dp

/**
 * How much smaller the switch is drawn than Material's own size.
 *
 * `scale` rather than a size modifier because it leaves measurement alone, so the row's layout and
 * the text beside it are unaffected.
 *
 * It is not free: the cost is the touch target. `scale` is a `graphicsLayer`, and Compose applies
 * the layer transform when hit-testing as well as when drawing — measured at 46.8 × 28.8 dp against
 * Material's 52 × 32. `AiModelsPanelTest.theSwitchStaysAboveTheSizeAFingerCanFind` holds the floor.
 */
private const val SWITCH_SCALE = 0.9f

@Composable
private fun ImageTextProgress.summaryLine(picturesRead: Int): String = when {
    !enabled -> stringResource(R.string.ai_picture_text_off)
    running -> stringResource(R.string.ai_picture_text_to_go, pending.coerceAtLeast(0))
    failed > 0 -> stringResource(R.string.ai_picture_text_failed, picturesRead, failed)
    else -> pluralStringResource(R.plurals.ai_picture_text_read, picturesRead, picturesRead)
}

@Composable
private fun InkTextProgress.summaryLine(pagesRead: Int): String = when {
    !enabled -> stringResource(R.string.ai_ink_text_off)
    running -> pending.coerceAtLeast(0).let { pluralStringResource(R.plurals.ai_ink_text_to_go, it, it) }
    failed > 0 -> stringResource(R.string.ai_ink_text_failed, pagesRead, failed)
    else -> pluralStringResource(R.plurals.ai_ink_text_read, pagesRead, pagesRead)
}

@Composable
private fun DownloadButton(label: String, engine: FormulaEngine, onDownload: () -> Unit) {
    Button(
        onClick = onDownload,
        modifier = Modifier.testTag(AiPanelTags.formulaDownload(engine)),
    ) {
        Icon(
            imageVector = MaterialSymbols.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}
