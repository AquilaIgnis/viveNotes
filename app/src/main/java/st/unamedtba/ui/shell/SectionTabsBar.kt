package st.unamedtba.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import st.unamedtba.data.db.NotebookWithSections
import st.unamedtba.data.db.SectionEntity
import st.unamedtba.ui.ScrollingRow
import st.unamedtba.ui.theme.LocalIconAccents

private val BAR_HEIGHT = 40.dp
private val TAB_HEIGHT = 30.dp
private val SELECTED_TAB_HEIGHT = 36.dp

/**
 * Sections as a strip of coloured tabs across the top — the View tab's horizontal Tabs Layout,
 * matching `docs/references/views-horizontal.png`.
 *
 * The same selection the [NotebookRail] makes, arranged for a wide window rather than a tall one:
 * the notebook becomes a chooser instead of a tree, and its sections become tabs. Nothing below
 * this bar knows which layout is in use, because both write to the same selected section.
 */
@Composable
fun SectionTabsBar(
    tree: List<NotebookWithSections>,
    selectedSectionId: String?,
    onSelectSection: (String) -> Unit,
    onAddSection: (String) -> Unit,
    onAddNotebook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The notebook shown is the one holding the selection, so switching sections from anywhere —
    // the page list, a link, a restored session — brings its tabs up without extra bookkeeping.
    val notebook = tree.firstOrNull { entry -> entry.liveSections.any { it.id == selectedSectionId } }
        ?: tree.firstOrNull()
    val sections = notebook?.liveSections.orEmpty()
    val accent = sections.firstOrNull { it.id == selectedSectionId }
        ?.let { Color(it.colorArgb) }
        ?: MaterialTheme.colorScheme.outlineVariant

    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        ScrollingRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT),
            verticalAlignment = Alignment.Bottom,
        ) {
            NotebookChooser(
                tree = tree,
                current = notebook,
                onSelectSection = onSelectSection,
                onAddNotebook = onAddNotebook,
            )

            sections.forEach { section ->
                SectionTab(
                    section = section,
                    selected = section.id == selectedSectionId,
                    onClick = { onSelectSection(section.id) },
                )
            }

            if (notebook != null) {
                AddTab { onAddSection(notebook.notebook.id) }
            }
        }

        // The selected section's colour continues under the whole strip, which is what ties the
        // page below to the tab above it in the reference.
        HorizontalDivider(thickness = 2.dp, color = accent)
    }
}

@Composable
private fun NotebookChooser(
    tree: List<NotebookWithSections>,
    current: NotebookWithSections?,
    onSelectSection: (String) -> Unit,
    onAddNotebook: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .height(BAR_HEIGHT)
                .clickable { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Book,
                contentDescription = null,
                tint = current?.notebook?.colorArgb?.let { Color(it) }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = current?.notebook?.name ?: "No notebook",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Choose notebook",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tree.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.notebook.name) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Book,
                            contentDescription = null,
                            tint = Color(entry.notebook.colorArgb),
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = {
                        open = false
                        // A notebook is not itself selectable — the page list always shows one
                        // section — so choosing one opens the section the user would land on.
                        entry.liveSections.firstOrNull()?.let { onSelectSection(it.id) }
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("New Notebook") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = LocalIconAccents.current.green,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    open = false
                    onAddNotebook()
                },
            )
        }
    }
}

@Composable
private fun SectionTab(
    section: SectionEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = Color(section.colorArgb)
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .height(if (selected) SELECTED_TAB_HEIGHT else TAB_HEIGHT)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            // Unselected tabs are dimmed rather than greyed: the colour is how a section is
            // recognised, so it has to survive not being the current one.
            .background(if (selected) color else color.copy(alpha = 0.62f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = section.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = inkOn(color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddTab(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp, end = 6.dp)
            .height(TAB_HEIGHT)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "New section",
            tint = LocalIconAccents.current.green,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Legible text on a tab of an arbitrary colour. The palette in `NotesRepository` runs from amber to
 * purple, so a single fixed ink would fail on one end of it.
 */
private fun inkOn(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White
