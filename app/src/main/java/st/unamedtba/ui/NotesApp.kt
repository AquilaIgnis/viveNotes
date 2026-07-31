package st.unamedtba.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import st.unamedtba.data.EditorDefaults
import st.unamedtba.ui.editor.EditorPane
import st.unamedtba.ui.editor.Ribbon
import st.unamedtba.ui.editor.RibbonTab
import st.unamedtba.ui.shell.NotebookRail
import st.unamedtba.ui.shell.PageListPane

private val RAIL_WIDTH = 232.dp
private val PAGE_LIST_WIDTH = 260.dp

/** Below this the panes stack; above it they sit side by side. */
private val MEDIUM_BREAKPOINT = 720.dp

/** Above this the notebook rail is permanently visible alongside the other two panes. */
private val EXPANDED_BREAKPOINT = 1040.dp

@Composable
fun NotesApp(viewModel: NotesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val compactPane by viewModel.compactPane.collectAsStateWithLifecycle()
    val railVisible by viewModel.railVisible.collectAsStateWithLifecycle()
    val defaults by viewModel.editorDefaults.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(RibbonTab.Home) }
    var pendingDialog by remember { mutableStateOf<NameDialog?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= EXPANDED_BREAKPOINT
        val medium = maxWidth >= MEDIUM_BREAKPOINT

        Scaffold { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                TitleBar(
                    title = state.title.ifBlank { "Untitled page" },
                    showBack = !medium && compactPane != CompactPane.Notebooks,
                    onBack = {
                        viewModel.showCompactPane(
                            when (compactPane) {
                                CompactPane.Editor -> CompactPane.Pages
                                else -> CompactPane.Notebooks
                            },
                        )
                    },
                    onToggleRail = { viewModel.toggleRail() },
                )

                Ribbon(
                    selection = selection,
                    activeTab = activeTab,
                    onTabChange = { activeTab = it },
                    onCommand = viewModel::send,
                )

                HorizontalHairline()

                if (medium) {
                    Row(Modifier.fillMaxSize()) {
                        if (expanded && railVisible) {
                            NotebookRail(
                                tree = state.tree,
                                selectedSectionId = state.selectedSectionId,
                                onSelectSection = viewModel::selectSection,
                                onToggleNotebook = viewModel::toggleNotebookExpanded,
                                onAddSection = { pendingDialog = NameDialog.Section(it) },
                                onAddNotebook = { pendingDialog = NameDialog.Notebook },
                                modifier = Modifier.width(RAIL_WIDTH),
                            )
                            VerticalHairline()
                        }
                        PageListPane(
                            pages = state.pages,
                            selectedPageId = state.selectedPageId,
                            onSelectPage = viewModel::openPage,
                            onAddPage = viewModel::addPage,
                            onDeletePage = viewModel::deletePage,
                            modifier = Modifier.width(PAGE_LIST_WIDTH),
                        )
                        VerticalHairline()
                        EditorSurface(state, viewModel, defaults, Modifier.weight(1f))
                    }
                } else {
                    BackHandler(enabled = compactPane != CompactPane.Notebooks) {
                        viewModel.showCompactPane(
                            when (compactPane) {
                                CompactPane.Editor -> CompactPane.Pages
                                else -> CompactPane.Notebooks
                            },
                        )
                    }
                    when (compactPane) {
                        CompactPane.Notebooks -> NotebookRail(
                            tree = state.tree,
                            selectedSectionId = state.selectedSectionId,
                            onSelectSection = viewModel::selectSection,
                            onToggleNotebook = viewModel::toggleNotebookExpanded,
                            onAddSection = { pendingDialog = NameDialog.Section(it) },
                            onAddNotebook = { pendingDialog = NameDialog.Notebook },
                            modifier = Modifier.fillMaxSize(),
                        )
                        CompactPane.Pages -> PageListPane(
                            pages = state.pages,
                            selectedPageId = state.selectedPageId,
                            onSelectPage = viewModel::openPage,
                            onAddPage = viewModel::addPage,
                            onDeletePage = viewModel::deletePage,
                            modifier = Modifier.fillMaxSize(),
                        )
                        CompactPane.Editor -> EditorSurface(state, viewModel, defaults, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    pendingDialog?.let { dialog ->
        NameEntryDialog(
            title = when (dialog) {
                is NameDialog.Notebook -> "New notebook"
                is NameDialog.Section -> "New section"
            },
            onDismiss = { pendingDialog = null },
            onConfirm = { name ->
                when (dialog) {
                    is NameDialog.Notebook -> viewModel.createNotebook(name)
                    is NameDialog.Section -> viewModel.createSection(dialog.notebookId, name)
                }
                pendingDialog = null
            },
        )
    }
}

@Composable
private fun EditorSurface(
    state: NotesUiState,
    viewModel: NotesViewModel,
    defaults: EditorDefaults,
    modifier: Modifier = Modifier,
) {
    if (state.selectedPageId == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (state.loading) "" else "No page selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    EditorPane(
        title = state.title,
        createdAt = state.createdAt,
        defaults = defaults,
        onTitleChange = viewModel::setTitle,
        outlines = state.outlines,
        pageRevision = state.pageRevision,
        initialBlocksFor = viewModel::initialBlocksFor,
        commands = viewModel.commands,
        onBlocksChanged = viewModel::onBlocksChanged,
        onSelectionChanged = viewModel::onSelectionChanged,
        onMarkArmed = viewModel::rememberDefaultMark,
        onCreateOutline = viewModel::createOutline,
        onMoveOutline = viewModel::moveOutline,
        onResizeOutline = viewModel::resizeOutline,
        onSetOutlineMinHeight = viewModel::setOutlineMinHeight,
        onOutlineBlurred = viewModel::onOutlineBlurred,
        showRuleLines = true,
        modifier = modifier,
    )
}

@Composable
private fun TitleBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onToggleRail: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            IconButton(onClick = onToggleRail, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Toggle notebook list",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HorizontalHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun VerticalHairline() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

private sealed interface NameDialog {
    data object Notebook : NameDialog
    data class Section(val notebookId: String) : NameDialog
}

@Composable
private fun NameEntryDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Column {
                        Box(Modifier.padding(vertical = 6.dp)) {
                            if (value.isEmpty()) {
                                Text(
                                    "Name",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outline),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
