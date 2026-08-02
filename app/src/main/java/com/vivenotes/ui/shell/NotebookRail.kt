package com.vivenotes.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.SectionEntity

/**
 * Notebook and section tree — the leftmost pane of the reference UI.
 *
 * Notebooks expand to reveal their sections; a section is the unit of selection, since the page
 * list always shows one section's pages.
 */
@Composable
fun NotebookRail(
    tree: List<NotebookWithSections>,
    selectedSectionId: String?,
    onSelectSection: (String) -> Unit,
    onToggleNotebook: (String, Boolean) -> Unit,
    onAddSection: (String) -> Unit,
    onAddNotebook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            tree.forEach { entry ->
                item(key = entry.notebook.id) {
                    NotebookHeader(
                        name = entry.notebook.name,
                        color = Color(entry.notebook.colorArgb),
                        expanded = entry.notebook.expanded,
                        onClick = { onToggleNotebook(entry.notebook.id, !entry.notebook.expanded) },
                    )
                }
                if (entry.notebook.expanded) {
                    items(entry.liveSections, key = { it.id }) { section ->
                        SectionRow(
                            section = section,
                            selected = section.id == selectedSectionId,
                            onClick = { onSelectSection(section.id) },
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

@Composable
private fun NotebookHeader(
    name: String,
    color: Color,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) MaterialSymbols.ExpandLess else MaterialSymbols.ExpandMore,
            contentDescription = if (expanded) "Collapse $name" else "Expand $name",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = MaterialSymbols.Book,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionRow(
    section: SectionEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 10.dp)
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
