package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ScrollingRow
import com.vivenotes.ui.icons.MaterialSymbols

@Immutable
data class FileActions(
    val openVersionHistory: () -> Unit,
    val exportNotebook: () -> Unit = {},
    val importNotebook: () -> Unit = {},
    val deleteNotebook: () -> Unit = {},
    val openDeletedItems: () -> Unit = {},
)

internal object FileTags {
    const val VERSION_HISTORY = "file-version-history"
    const val DELETED_ITEMS = "file-deleted-items"
    const val EXPORT_NOTEBOOK = "file-export-notebook"
    const val IMPORT_NOTEBOOK = "file-import-notebook"
    const val DELETE_NOTEBOOK = "file-delete-notebook"
}

/** Commands about the open file rather than its content or the device running the app. */
@Composable
internal fun FileTab(
    actions: FileActions,
    pageOpen: Boolean,
    notebookOpen: Boolean = pageOpen,
) {
    ScrollingRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Box(Modifier.testTag(FileTags.VERSION_HISTORY)) {
            RibbonCommand(
                label = "Version History",
                onClick = actions.openVersionHistory,
                enabled = pageOpen,
                icon = { MonoIcon(MaterialSymbols.History) },
            )
        }
        Box(Modifier.testTag(FileTags.DELETED_ITEMS)) {
            RibbonCommand(
                label = "Deleted Items",
                onClick = actions.openDeletedItems,
                icon = { MonoIcon(MaterialSymbols.RestoreFromTrash) },
            )
        }

        Divider()
        Box(Modifier.testTag(FileTags.EXPORT_NOTEBOOK)) {
            RibbonCommand(
                label = "Export Notebook",
                onClick = actions.exportNotebook,
                enabled = notebookOpen,
                icon = { MonoIcon(MaterialSymbols.Book) },
            )
        }
        Box(Modifier.testTag(FileTags.IMPORT_NOTEBOOK)) {
            RibbonCommand(
                label = "Import",
                onClick = actions.importNotebook,
                // The plain book beside it is Export; the arrow is the only thing telling the two
                // apart, so this one is two-tone — see `icons/RibbonGlyphs.kt`.
                icon = { active -> TwoToneIcon({ it.importNotebook }, active) },
            )
        }

        // Behind its own divider, and last: the only command on this tab that takes something away.
        // Export and Import sit a thumb's width from it, and a scrolling row moves under the finger,
        // so the gap is what stops a mistimed tap on Import from landing on this instead. The tap
        // still only opens the confirmation — see `NotesApp.DeleteNotebookDialog`.
        Divider()
        Box(Modifier.testTag(FileTags.DELETE_NOTEBOOK)) {
            RibbonCommand(
                label = "Delete Notebook",
                onClick = actions.deleteNotebook,
                enabled = notebookOpen,
                icon = { MonoIcon(MaterialSymbols.Delete) },
            )
        }
    }
}
