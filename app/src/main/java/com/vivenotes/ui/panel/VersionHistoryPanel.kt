package com.vivenotes.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.model.PageDoc
import com.vivenotes.model.plainText
import com.vivenotes.ui.VersionHistoryState
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

internal object VersionHistoryPanelTags {
    const val STATUS = "version-history-status"
    const val PREVIEW = "version-history-preview"
    const val RESTORE = "version-history-restore"
    const val CONFIRM = "version-history-confirm"
    fun revision(id: String) = "version-history-revision-$id"
}

/** Page checkpoints from SQLite. The outer [ToolPanel] supplies this pane's scrolling container. */
@Composable
internal fun VersionHistoryPanelContent(
    state: VersionHistoryState,
    onSelect: (String) -> Unit,
    onRestore: () -> Unit,
) {
    var confirmingRestore by remember { mutableStateOf(false) }

    Text(
        text = "Earlier saved versions of this page, newest first.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "A restore returns the whole page—including ink—to that saved point.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    when {
        state.loading -> Status("Loading versions…", progress = true)
        state.revisions.isEmpty() && state.error == null -> Status(
            "No earlier versions yet. A checkpoint appears after this page is edited and saved.",
        )
        else -> state.revisions.forEach { revision ->
            RevisionRow(
                revision = revision,
                selected = revision.id == state.selectedRevision?.id,
                enabled = !state.restoring,
                onClick = { onSelect(revision.id) },
            )
            Spacer(Modifier.height(6.dp))
        }
    }

    state.error?.let {
        Text(
            text = it,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .testTag(VersionHistoryPanelTags.STATUS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    state.message?.let {
        Text(
            text = it,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .testTag(VersionHistoryPanelTags.STATUS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (state.selectedRevision != null) {
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text(
            text = "Preview",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        when {
            state.previewLoading -> Status("Loading preview…", progress = true)
            state.preview != null -> {
                VersionPreview(state.preview)
                if (!state.previewIncludesInk) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "This version predates ink-aware history, so it cannot restore the complete page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { confirmingRestore = true },
            enabled = state.preview != null && state.previewIncludesInk &&
                !state.previewLoading && !state.restoring,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VersionHistoryPanelTags.RESTORE),
        ) {
            Text(if (state.restoring) "Restoring…" else "Restore this version")
        }
    }

    if (confirmingRestore) {
        AlertDialog(
            onDismissRequest = { if (!state.restoring) confirmingRestore = false },
            title = { Text("Restore this version?") },
            text = {
                Text(
                    "The selected version will replace the page's saved text, layout, placed " +
                        "objects, and ink. Your current version will be kept in history so you can " +
                        "return to it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRestore = false
                        onRestore()
                    },
                    enabled = !state.restoring,
                    modifier = Modifier.testTag(VersionHistoryPanelTags.CONFIRM),
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingRestore = false },
                    enabled = !state.restoring,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun RevisionRow(
    revision: PageRevisionSummary,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(VersionHistoryPanelTags.revision(revision.id)),
    ) {
        Text(
            text = formatter.format(Date(revision.createdAt)),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = formatBytes(revision.byteCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VersionPreview(doc: PageDoc) {
    val text = doc.plainText().trim()
    val objectCount = doc.outlines.count { it !is com.vivenotes.model.Outline.Text }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp)
            .testTag(VersionHistoryPanelTags.PREVIEW),
    ) {
        Text(
            text = if (text.isBlank()) "No text in this version." else text.take(600),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 10,
        )
        if (objectCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$objectCount placed ${if (objectCount == 1) "object" else "objects"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Status(text: String, progress: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag(VersionHistoryPanelTags.STATUS),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "${(bytes / 100.0).roundToInt() / 10.0} KB"
    else -> "${(bytes / 100_000.0).roundToInt() / 10.0} MB"
}
