package com.vivenotes.ui.panel

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.data.DeletedItem
import com.vivenotes.data.DeletedItemKind
import com.vivenotes.ui.DeletedItemsState
import java.text.DateFormat
import java.util.Date

internal object DeletedItemsPanelTags {
    const val STATUS = "deleted-items-status"
    const val EMPTY = "deleted-items-empty"
    fun item(id: String) = "deleted-item-$id"
    fun restore(id: String) = "deleted-item-restore-$id"
}

/** Durable recovery for deleted hierarchy roots; the outer [ToolPanel] owns scrolling. */
@Composable
internal fun DeletedItemsPanelContent(
    state: DeletedItemsState,
    onRestore: (DeletedItem) -> Unit,
    onClearStatus: () -> Unit,
) {
    Text(
        text = stringResource(R.string.deleted_items_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    val status = state.error ?: state.message
    if (status != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DeletedItemsPanelTags.STATUS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = status,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onClearStatus) { Text(stringResource(R.string.deleted_items_dismiss)) }
        }
        Spacer(Modifier.height(8.dp))
    }

    when {
        state.loading -> RecoveryStatus(stringResource(R.string.deleted_items_loading), progress = true)
        state.items.isEmpty() -> RecoveryStatus(
            text = stringResource(R.string.deleted_items_empty),
            modifier = Modifier.testTag(DeletedItemsPanelTags.EMPTY),
        )
        else -> state.items.forEach { item ->
            DeletedItemCard(
                item = item,
                restoring = state.restoring == item.key,
                enabled = state.restoring == null,
                onRestore = { onRestore(item) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeletedItemCard(
    item: DeletedItem,
    restoring: Boolean,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DeletedItemsPanelTags.item(item.key.id)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(item.key.kind.label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = item.details(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.deleted_items_deleted_at, formatter.format(Date(item.deletedAt))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRestore,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DeletedItemsPanelTags.restore(item.key.id)),
            ) {
                if (restoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.deleted_items_restoring))
                } else {
                    Text(stringResource(R.string.deleted_items_restore))
                }
            }
        }
    }
}

@get:StringRes
private val DeletedItemKind.label: Int
    get() = when (this) {
        DeletedItemKind.Notebook -> R.string.deleted_items_kind_notebook
        DeletedItemKind.Section -> R.string.deleted_items_kind_section
        DeletedItemKind.Page -> R.string.deleted_items_kind_page
    }

@Composable
private fun DeletedItem.details(): String {
    val notebook = notebookName ?: stringResource(R.string.deleted_items_unknown_notebook)
    val pages = pluralStringResource(R.plurals.deleted_items_pages, pageCount, pageCount)
    return when (key.kind) {
        DeletedItemKind.Notebook -> stringResource(
            R.string.panel_separated,
            pluralStringResource(R.plurals.deleted_items_sections, sectionCount, sectionCount),
            pages,
        )
        DeletedItemKind.Section -> stringResource(R.string.deleted_items_section_details, notebook, pages)
        DeletedItemKind.Page -> stringResource(
            R.string.deleted_items_page_details,
            sectionName ?: stringResource(R.string.deleted_items_unknown_section),
            notebook,
        )
    }
}

@Composable
private fun RecoveryStatus(
    text: String,
    modifier: Modifier = Modifier,
    progress: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
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
