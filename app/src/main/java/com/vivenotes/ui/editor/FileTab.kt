package com.vivenotes.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ScrollingRow

@Immutable
data class FileActions(
    val openVersionHistory: () -> Unit,
    val exportNotebook: () -> Unit = {},
    val importNotebook: () -> Unit = {},
    val deleteNotebook: () -> Unit = {},
    val openDeletedItems: () -> Unit = {},
    val closeNotebook: () -> Unit = {},
    val openClosedNotebooks: () -> Unit = {},
)

internal object FileTags {
    const val VERSION_HISTORY = "file-version-history"
    const val DELETED_ITEMS = "file-deleted-items"
    const val EXPORT_NOTEBOOK = "file-export-notebook"
    const val IMPORT_NOTEBOOK = "file-import-notebook"
    const val DELETE_NOTEBOOK = "file-delete-notebook"
    const val CLOSE_NOTEBOOK = "file-close-notebook"
    const val CLOSED_NOTEBOOKS = "file-closed-notebooks"
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
                icon = { active -> TwoToneIcon({ it.versionHistory }, active) },
            )
        }
        Box(Modifier.testTag(FileTags.DELETED_ITEMS)) {
            RibbonCommand(
                label = "Deleted Items",
                onClick = actions.openDeletedItems,
                icon = { active -> TwoToneIcon({ it.deletedItems }, active) },
            )
        }
        // Beside Deleted Items rather than beside Close Notebook, because the two shelves are the
        // same idea — places things go that are not in the panel — and somebody hunting for a
        // notebook they cannot see will look at whichever of them they find first.
        Box(Modifier.testTag(FileTags.CLOSED_NOTEBOOKS)) {
            RibbonCommand(
                label = "Closed Notebooks",
                onClick = actions.openClosedNotebooks,
                icon = { active -> TwoToneIcon({ it.closedNotebooks }, active) },
            )
        }

        Divider()
        // With Export and Import, not with Delete: all three act on the notebook that is open, and
        // none of them takes anything away. The divider below is what separates the one that does.
        Box(Modifier.testTag(FileTags.CLOSE_NOTEBOOK)) {
            RibbonCommand(
                label = "Close Notebook",
                onClick = actions.closeNotebook,
                enabled = notebookOpen,
                // The cross in the accent, not the warning red — see `closeNotebookGlyph`. Closing
                // removes nothing, and the colour is what says so before the label is read.
                icon = { active -> TwoToneIcon({ it.closeNotebook }, active) },
            )
        }
        Box(Modifier.testTag(FileTags.EXPORT_NOTEBOOK)) {
            RibbonCommand(
                label = "Export Notebook",
                onClick = actions.exportNotebook,
                enabled = notebookOpen,
                // The bookmark takes the accent, answering the accented arrow inside Import's glyph
                // beside it: the pair is one idea in two directions, and Import is why Export's
                // book keeps the bookmark that Import's has removed.
                icon = { active -> TwoToneIcon({ it.exportNotebook }, active) },
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
                // The bin's contents in the warning red — `IconAccents.red` is "what a glyph
                // removes", and this is the only command here that does. It says at a glance what
                // the divider above says by position.
                icon = { active -> TwoToneIcon({ it.deleteNotebook }, active) },
            )
        }
    }
}
