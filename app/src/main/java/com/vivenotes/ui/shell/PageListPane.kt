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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.data.db.PageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PageSort(val label: String) {
    Manual("Section order"),
    Alphabetical("By title"),
    Recent("By date modified"),
}

internal object PageListTags {
    fun row(pageId: String) = "page-row-$pageId"
    fun dragHandle(pageId: String) = "page-drag-$pageId"
}

/**
 * The page list — middle pane of the reference UI, with its Add Page button and sort control.
 *
 * **Dragging is offered only under Section order.** The other two sorts are derived from the pages
 * themselves, so a row dropped somewhere under them would be sorted straight back out of it; a
 * handle that visibly does nothing is worse than no handle, so it is simply absent.
 */
@Composable
fun PageListPane(
    pages: List<PageEntity>,
    selectedPageId: String?,
    onSelectPage: (String) -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: (String) -> Unit,
    onReorderPages: (List<String>) -> Unit = {},
    onSwipeLeft: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var sort by remember { mutableStateOf(PageSort.Manual) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val ordered = remember(pages, sort) {
        when (sort) {
            PageSort.Manual -> pages
            PageSort.Alphabetical -> pages.sortedBy { it.title.ifBlank { "Untitled" }.lowercase() }
            PageSort.Recent -> pages.sortedByDescending { it.updatedAt }
        }
    }

    /**
     * The order the finger has made, which leads the database. Held past the drop, because the
     * write is asynchronous and clearing it the moment the finger lifts snaps the row back to where
     * it started for the frame or two before the query re-emits.
     */
    var dragOrder by remember { mutableStateOf<List<PageEntity>?>(null) }
    val shown = dragOrder ?: ordered
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        keys = if (sort == PageSort.Manual) shown.map { it.id } else emptyList(),
        onMove = { from, to ->
            dragOrder = (dragOrder ?: ordered).toMutableList().apply { add(to, removeAt(from)) }
        },
        onSettle = { dragOrder?.let { order -> onReorderPages(order.map { it.id }) } },
    )

    LaunchedEffect(pages, sort, reorder.dragging) {
        val pending = dragOrder
        if (pending == null || reorder.dragging) return@LaunchedEffect
        val live = pages.map { it.id }
        val optimistic = pending.map { it.id }
        // Either the write landed and the two agree, or the section changed under it — a page
        // added, deleted or imported — and the table is the one to believe.
        if (sort != PageSort.Manual || live == optimistic || live.toSet() != optimistic.toSet()) {
            dragOrder = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .swipeLeft(onSwipeLeft),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onAddPage)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MaterialSymbols.NoteAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Add Page",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Box {
                IconButton(onClick = { sortMenuOpen = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = MaterialSymbols.Sort,
                        contentDescription = "Sort pages",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    PageSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                sort = option
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
            items(shown, key = { it.id }) { page ->
                PageRow(
                    page = page,
                    selected = page.id == selectedPageId,
                    dragging = reorder.draggedKey == page.id,
                    onClick = { onSelectPage(page.id) },
                    onDelete = { onDeletePage(page.id) },
                    modifier = Modifier.reorderable(reorder, page.id),
                    handle = if (sort == PageSort.Manual) {
                        {
                            DragHandle(
                                description = "Reorder ${page.title.ifBlank { "Untitled page" }}",
                                modifier = Modifier
                                    .testTag(PageListTags.dragHandle(page.id))
                                    .reorderHandle(reorder, page.id),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * The grip drawn in a reorderable row. Shared so the two lists cannot drift apart visually.
 *
 * The glyph is smaller than the area that grabs it. It is the only chrome left in a row once the
 * commands moved to a long press, so it should read as a texture rather than a control — but
 * shrinking what the finger has to land on along with it would make the one gesture it exists for
 * harder than it already is.
 */
@Composable
internal fun DragHandle(description: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(HANDLE_TOUCH)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.DragIndicator,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(HANDLE_GLYPH),
        )
    }
}

private val HANDLE_TOUCH = 20.dp
private val HANDLE_GLYPH = 15.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageRow(
    page: PageEntity,
    selected: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    handle: @Composable (RowScope.() -> Unit)? = null,
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
                .padding(start = if (handle == null) 12.dp else 4.dp, end = 12.dp)
                .padding(vertical = 8.dp)
                .testTag(PageListTags.row(page.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (handle != null) {
                handle()
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = page.title.ifBlank { "Untitled page" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (page.title.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (page.preview.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = page.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = relativeDate(page.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Long-press opens the per-page menu; the ribbon owns the primary actions.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Delete page") },
                leadingIcon = { Icon(MaterialSymbols.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

private val dayFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

private fun relativeDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val elapsed = now - timestamp
    return when {
        elapsed < 60_000 -> "Just now"
        elapsed < 3_600_000 -> "${elapsed / 60_000} min ago"
        elapsed < 86_400_000 -> timeFormat.format(Date(timestamp))
        else -> dayFormat.format(Date(timestamp))
    }
}
