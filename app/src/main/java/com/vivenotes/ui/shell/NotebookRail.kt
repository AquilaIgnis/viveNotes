package com.vivenotes.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.ui.theme.LocalIconAccents

private val SECTION_ROW_HEIGHT = 32.dp

internal object RailTags {
    fun notebook(notebookId: String) = "rail-notebook-$notebookId"
    fun section(sectionId: String) = "rail-section-$sectionId"
    fun sectionDrag(sectionId: String) = "rail-section-drag-$sectionId"
    fun sectionRename(sectionId: String) = "rail-section-rename-$sectionId"
    fun sectionDelete(sectionId: String) = "rail-section-delete-$sectionId"
    fun notebookRename(notebookId: String) = "rail-notebook-rename-$notebookId"
}

/**
 * Notebook and section tree — the leftmost pane of the reference UI.
 *
 * Notebooks expand to reveal their sections; a section is the unit of selection, since the page
 * list always shows one section's pages.
 *
 * **Sections reorder within their own notebook and nowhere else.** `sortIndex` is scoped to a
 * notebook, so a section dropped under a different one would need its parent rewritten as well —
 * a move, not a reorder, and a different feature. The draggable set is therefore narrowed to the
 * notebook whose handle was grabbed, which also stops a long drag from sweeping a section through
 * a neighbouring notebook's list on the way past.
 */
@Composable
fun NotebookRail(
    tree: List<NotebookWithSections>,
    selectedSectionId: String?,
    onSelectSection: (String) -> Unit,
    onToggleNotebook: (String, Boolean) -> Unit,
    onAddSection: (String) -> Unit,
    onAddNotebook: () -> Unit,
    onRenameNotebook: (NotebookEntity) -> Unit = {},
    onRenameSection: (SectionEntity) -> Unit = {},
    onDeleteSection: (SectionEntity) -> Unit = {},
    onReorderSections: (String, List<String>) -> Unit = { _, _ -> },
    onSwipeLeft: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    /** Which notebook's handle is being held. Set before the drag, so the key set is ready for it. */
    var grabbedNotebookId by remember { mutableStateOf<String?>(null) }

    /** That notebook's order as the finger has made it, leading the database until the write lands. */
    var pending by remember { mutableStateOf<Pair<String, List<SectionEntity>>?>(null) }

    fun sectionsOf(entry: NotebookWithSections): List<SectionEntity> =
        pending?.takeIf { it.first == entry.notebook.id }?.second ?: entry.liveSections

    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        keys = tree.firstOrNull { it.notebook.id == grabbedNotebookId }
            ?.let { entry -> sectionsOf(entry).map { it.id } }
            .orEmpty(),
        onMove = { from, to ->
            val notebookId = grabbedNotebookId
            val entry = tree.firstOrNull { it.notebook.id == notebookId }
            if (notebookId != null && entry != null) {
                val base = sectionsOf(entry).toMutableList()
                base.add(to, base.removeAt(from))
                pending = notebookId to base
            }
        },
        onSettle = {
            pending?.let { (notebookId, order) -> onReorderSections(notebookId, order.map { it.id }) }
            grabbedNotebookId = null
        },
    )

    LaunchedEffect(tree, reorder.dragging) {
        val (notebookId, optimistic) = pending ?: return@LaunchedEffect
        if (reorder.dragging) return@LaunchedEffect
        val live = tree.firstOrNull { it.notebook.id == notebookId }?.liveSections?.map { it.id }
        val ids = optimistic.map { it.id }
        // Either the write landed and the two agree, or the notebook changed underneath — a section
        // added or removed — and the table is the one to believe.
        if (live == null || live == ids || live.toSet() != ids.toSet()) pending = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .swipeLeft(onSwipeLeft),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            tree.forEach { entry ->
                item(key = entry.notebook.id) {
                    NotebookHeader(
                        notebook = entry.notebook,
                        onClick = { onToggleNotebook(entry.notebook.id, !entry.notebook.expanded) },
                        onRename = { onRenameNotebook(entry.notebook) },
                    )
                }
                if (entry.notebook.expanded) {
                    items(sectionsOf(entry), key = { it.id }) { section ->
                        SectionRow(
                            section = section,
                            selected = section.id == selectedSectionId,
                            dragging = reorder.draggedKey == section.id,
                            onClick = { onSelectSection(section.id) },
                            onRename = { onRenameSection(section) },
                            onDelete = { onDeleteSection(section) },
                            modifier = Modifier.reorderable(reorder, section.id),
                            handle = {
                                DragHandle(
                                    description = "Reorder ${section.name}",
                                    modifier = Modifier
                                        .testTag(RailTags.sectionDrag(section.id))
                                        .reorderHandle(reorder, section.id) {
                                            grabbedNotebookId = entry.notebook.id
                                        },
                                )
                            },
                        )
                    }
                    item(key = "add-${entry.notebook.id}") {
                        AddRow(label = "New Section") { onAddSection(entry.notebook.id) }
                    }
                }
            }
        }

        AddRow(label = "New Notebook", modifier = Modifier.padding(bottom = 8.dp), onClick = onAddNotebook)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookHeader(
    notebook: NotebookEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 10.dp)
                .testTag(RailTags.notebook(notebook.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (notebook.expanded) MaterialSymbols.ExpandLess else MaterialSymbols.ExpandMore,
                contentDescription = if (notebook.expanded) {
                    "Collapse ${notebook.name}"
                } else {
                    "Expand ${notebook.name}"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = MaterialSymbols.Book,
                contentDescription = null,
                tint = Color(notebook.colorArgb),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = notebook.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Rename notebook") },
                leadingIcon = { Icon(MaterialSymbols.Edit, contentDescription = null) },
                modifier = Modifier.testTag(RailTags.notebookRename(notebook.id)),
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionRow(
    section: SectionEntity,
    selected: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    handle: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // A lifted row is drawn over its neighbours, so it cannot keep the transparent background an
    // unselected row normally has — they would show through it.
    val background = if (selected || dragging) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color.Transparent
    }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .shadow(if (dragging) 6.dp else 0.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(background)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 2.dp)
                .height(SECTION_ROW_HEIGHT)
                .testTag(RailTags.section(section.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            handle()
            Spacer(Modifier.width(2.dp))
            // Colour chip, as in the reference UI, standing in for a section tab.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(section.colorArgb)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = section.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Held rather than docked beside the name: these are occasional commands, and a rail 232dp
        // wide spends more on two permanent buttons than the names they sit next to can afford.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Rename section") },
                leadingIcon = { Icon(MaterialSymbols.Edit, contentDescription = null) },
                modifier = Modifier.testTag(RailTags.sectionRename(section.id)),
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete section") },
                leadingIcon = {
                    Icon(
                        MaterialSymbols.Delete,
                        contentDescription = null,
                        tint = LocalIconAccents.current.red,
                    )
                },
                modifier = Modifier.testTag(RailTags.sectionDelete(section.id)),
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun AddRow(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 28.dp, end = 10.dp)
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = MaterialSymbols.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
