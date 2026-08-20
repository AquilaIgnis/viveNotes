package com.vivenotes.ui.closed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.data.db.ClosedNotebook
import com.vivenotes.ui.icons.MaterialSymbols

internal object ClosedNotebooksTags {
    const val SCREEN = "closed-notebooks-screen"
    const val BACK = "closed-notebooks-back"
    const val EMPTY = "closed-notebooks-empty"
    const val ON_DEVICE_HEADING = "closed-notebooks-on-device-heading"
    const val IN_CLOUD_HEADING = "closed-notebooks-in-cloud-heading"
    const val MESSAGE = "closed-notebooks-message"

    fun row(notebookId: String) = "closed-notebook-$notebookId"
    fun open(notebookId: String) = "closed-notebook-open-$notebookId"
    fun moveToCloud(notebookId: String) = "closed-notebook-to-cloud-$notebookId"
    fun bringBack(notebookId: String) = "closed-notebook-back-$notebookId"
    fun busy(notebookId: String) = "closed-notebook-busy-$notebookId"
}

/**
 * The shelf: notebooks that are not in the rail, and what can be done with them.
 *
 * Two sections rather than one list with a badge, because the two states differ in what they can
 * *do* and not merely in where their bytes are. A notebook on the device opens instantly and can be
 * exported; one in the cloud has to be downloaded first and cannot be searched until it is. A single
 * list would have to say that in prose under every row.
 *
 * Presentational, like `AccountScreen` and for the same reason: moving a notebook to the cloud is a
 * multi-request operation that must not be cancelled by somebody pressing Back, so it runs in a
 * scope one level up and arrives back here as [busyNotebookId] and [message].
 *
 * `memory/closedNotebooksPlan.md`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClosedNotebooksScreen(
    notebooks: List<ClosedNotebook>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit = {},
    onMoveToCloud: (String) -> Unit = {},
    onBringBack: (String) -> Unit = {},
    /** Whether a server is connected. Without one there is no cloud to move anything to. */
    accountConnected: Boolean = false,
    /** The one notebook a cloud operation is running for, so only its row shows a spinner. */
    busyNotebookId: String? = null,
    /** The last outcome worth saying out loud — a refusal, or a failure. */
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val onDevice = notebooks.filter { it.notebook.cloudOnlyAt == null }
    val inCloud = notebooks.filter { it.notebook.cloudOnlyAt != null }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(ClosedNotebooksTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.closed_notebooks)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(ClosedNotebooksTags.BACK),
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                message?.let { text ->
                    item(key = "message") {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                                .testTag(ClosedNotebooksTags.MESSAGE),
                        )
                    }
                }

                if (notebooks.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.closed_notebooks_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp)
                                .testTag(ClosedNotebooksTags.EMPTY),
                        )
                    }
                }

                if (onDevice.isNotEmpty()) {
                    item(key = "on-device-heading") {
                        SectionHeading(
                            text = stringResource(R.string.closed_notebooks_on_device),
                            tag = ClosedNotebooksTags.ON_DEVICE_HEADING,
                        )
                    }
                    items(onDevice, key = { it.notebook.id }) { entry ->
                        ClosedNotebookRow(
                            entry = entry,
                            busy = busyNotebookId == entry.notebook.id,
                            anyBusy = busyNotebookId != null,
                            accountConnected = accountConnected,
                            onOpen = onOpen,
                            onMoveToCloud = onMoveToCloud,
                            onBringBack = onBringBack,
                        )
                    }
                }

                if (inCloud.isNotEmpty()) {
                    item(key = "in-cloud-heading") {
                        Spacer(Modifier.height(6.dp))
                        SectionHeading(
                            text = stringResource(R.string.closed_notebooks_in_cloud),
                            tag = ClosedNotebooksTags.IN_CLOUD_HEADING,
                            // The one place the screen explains itself, and only when there is
                            // something under it to explain. Without a server the sentence changes
                            // rather than the buttons quietly doing nothing.
                            supporting = if (accountConnected) {
                                stringResource(R.string.closed_notebooks_in_cloud_supporting)
                            } else {
                                stringResource(R.string.closed_notebooks_no_account)
                            },
                        )
                    }
                    items(inCloud, key = { it.notebook.id }) { entry ->
                        ClosedNotebookRow(
                            entry = entry,
                            busy = busyNotebookId == entry.notebook.id,
                            anyBusy = busyNotebookId != null,
                            accountConnected = accountConnected,
                            onOpen = onOpen,
                            onMoveToCloud = onMoveToCloud,
                            onBringBack = onBringBack,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, tag: String, supporting: String? = null) {
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(tag),
        )
        supporting?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * One shelved notebook.
 *
 * [anyBusy] disables every other row's buttons while one operation runs. A move and a restore both
 * take the sync mutex and would queue behind each other anyway, and two spinners with one of them
 * silently waiting is a worse account of what is happening than one spinner and a still list.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClosedNotebookRow(
    entry: ClosedNotebook,
    busy: Boolean,
    anyBusy: Boolean,
    accountConnected: Boolean,
    onOpen: (String) -> Unit,
    onMoveToCloud: (String) -> Unit,
    onBringBack: (String) -> Unit,
) {
    val notebook = entry.notebook
    val inCloud = notebook.cloudOnlyAt != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ClosedNotebooksTags.row(notebook.id)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(notebook.colorArgb)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = notebook.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // Counts, not bytes. A cloud-only notebook keeps its sections and pages, so
                    // these stay true after a move and say what would come back.
                    text = stringResource(
                        R.string.closed_notebook_contents,
                        entry.sectionCount,
                        entry.pageCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))

            if (busy) {
                LoadingIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .testTag(ClosedNotebooksTags.busy(notebook.id)),
                )
            } else if (inCloud) {
                Button(
                    onClick = { onBringBack(notebook.id) },
                    enabled = accountConnected && !anyBusy,
                    modifier = Modifier.testTag(ClosedNotebooksTags.bringBack(notebook.id)),
                ) {
                    Text(stringResource(R.string.closed_notebook_bring_back))
                }
            } else {
                OutlinedButton(
                    onClick = { onMoveToCloud(notebook.id) },
                    enabled = accountConnected && !anyBusy,
                    modifier = Modifier.testTag(ClosedNotebooksTags.moveToCloud(notebook.id)),
                ) {
                    Text(stringResource(R.string.closed_notebook_move_to_cloud))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onOpen(notebook.id) },
                    enabled = !anyBusy,
                    modifier = Modifier.testTag(ClosedNotebooksTags.open(notebook.id)),
                ) {
                    Text(stringResource(R.string.closed_notebook_open))
                }
            }
        }
    }
}
