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
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import st.unamedtba.data.TabsLayout
import st.unamedtba.model.PageStyle
import st.unamedtba.ui.editor.EditorPane
import st.unamedtba.ui.editor.Ribbon
import st.unamedtba.ui.editor.RibbonTab
import st.unamedtba.ui.editor.ViewActions
import st.unamedtba.ui.panel.PaperSizePanelContent
import st.unamedtba.ui.panel.TOOL_PANEL_WIDTH
import st.unamedtba.ui.panel.ToolPane
import st.unamedtba.ui.panel.ToolPanel
import st.unamedtba.ui.shell.NotebookRail
import st.unamedtba.ui.shell.PageListPane
import st.unamedtba.ui.shell.SectionTabsBar
import st.unamedtba.ui.theme.LocalCanvasColors
import st.unamedtba.ui.theme.canvasColorsFor

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
    val navigationVisible by viewModel.navigationVisible.collectAsStateWithLifecycle()
    val defaults by viewModel.editorDefaults.collectAsStateWithLifecycle()
    val viewSettings by viewModel.viewSettings.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(RibbonTab.Home) }
    var pendingDialog by remember { mutableStateOf<NameDialog?>(null) }
    /** The docked pane, if any. Deliberately not persisted: it is where you are, not what you have. */
    var openPane by remember { mutableStateOf<ToolPane?>(null) }

    val viewActions = remember(viewModel) {
        ViewActions(
            setRuleLines = viewModel::setRuleLines,
            setPageColor = viewModel::setPageColor,
            setHideTitle = viewModel::setHideTitle,
            setZoom = viewModel::setZoom,
            zoomIn = viewModel::zoomIn,
            zoomOut = viewModel::zoomOut,
            zoomToPageWidth = viewModel::zoomToPageWidth,
            setTabsLayout = viewModel::setTabsLayout,
            setCanvasDark = viewModel::setCanvasDark,
            openPane = { openPane = it },
        )
    }

    val horizontalTabs = viewSettings.tabsLayout == TabsLayout.Horizontal
    // Switch Background pins the canvas light or dark; until it is used it follows the theme.
    val canvas = viewSettings.canvasDark?.let { canvasColorsFor(it) } ?: LocalCanvasColors.current
    // Where "back" stops. With the sections on screen as tabs there is no notebook pane behind the
    // page list to return to.
    val rootPane = if (horizontalTabs) CompactPane.Pages else CompactPane.Notebooks
    val pane = if (horizontalTabs && compactPane == CompactPane.Notebooks) CompactPane.Pages else compactPane

    CompositionLocalProvider(LocalCanvasColors provides canvas) {
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
                        showBack = !medium && pane != rootPane,
                        onBack = { viewModel.showCompactPane(paneBehind(pane)) },
                        // Only where there is something to collapse: a compact window shows one
                        // pane at a time, and hiding it would leave nothing behind.
                        showNavToggle = medium,
                        navigationVisible = navigationVisible,
                        onToggleNavigation = { viewModel.toggleNavigation() },
                    )

                    Ribbon(
                        selection = selection,
                        activeTab = activeTab,
                        onTabChange = { activeTab = it },
                        onCommand = viewModel::send,
                        pageStyle = state.pageStyle,
                        viewSettings = viewSettings,
                        view = viewActions,
                        pageOpen = state.selectedPageId != null,
                    )

                    HorizontalHairline()

                    if (horizontalTabs) {
                        SectionTabsBar(
                            tree = state.tree,
                            selectedSectionId = state.selectedSectionId,
                            onSelectSection = viewModel::selectSection,
                            onAddSection = { pendingDialog = NameDialog.Section(it) },
                            onAddNotebook = { pendingDialog = NameDialog.Notebook },
                        )
                    }

                    if (medium) {
                        Row(Modifier.fillMaxSize()) {
                            if (navigationVisible) {
                                if (!horizontalTabs && expanded) {
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
                            }
                            EditorSurface(
                                state,
                                viewModel,
                                defaults,
                                viewSettings.zoom,
                                showPrintMargins = openPane == ToolPane.PaperSize,
                                modifier = Modifier.weight(1f),
                            )
                            openPane?.let { toolPane ->
                                VerticalHairline()
                                ToolPaneHost(
                                    pane = toolPane,
                                    style = state.pageStyle,
                                    viewModel = viewModel,
                                    onClose = { openPane = null },
                                    modifier = Modifier.width(TOOL_PANEL_WIDTH),
                                )
                            }
                        }
                    } else {
                        // A pane is a step deeper than the editor, so back closes it first.
                        BackHandler(enabled = openPane != null) { openPane = null }
                        BackHandler(enabled = openPane == null && pane != rootPane) {
                            viewModel.showCompactPane(paneBehind(pane))
                        }
                        when (pane) {
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
                            // Too narrow to dock beside the page, so the pane takes the pane slot
                            // it would otherwise sit next to.
                            CompactPane.Editor -> openPane?.let { toolPane ->
                                ToolPaneHost(
                                    pane = toolPane,
                                    style = state.pageStyle,
                                    viewModel = viewModel,
                                    onClose = { openPane = null },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } ?: EditorSurface(
                                state,
                                viewModel,
                                defaults,
                                viewSettings.zoom,
                                showPrintMargins = false,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
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

/** The pane one step back from [pane] in the compact flow. */
private fun paneBehind(pane: CompactPane): CompactPane = when (pane) {
    CompactPane.Editor -> CompactPane.Pages
    else -> CompactPane.Notebooks
}

/** Wires an open [ToolPane] to the page it edits. */
@Composable
private fun ToolPaneHost(
    pane: ToolPane,
    style: PageStyle,
    viewModel: NotesViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolPanel(pane = pane, onClose = onClose, modifier = modifier) {
        when (pane) {
            ToolPane.PaperSize -> PaperSizePanelContent(
                style = style,
                onPickSize = viewModel::setPaperSize,
                onPickOrientation = viewModel::setOrientation,
                onSetCustomPaper = viewModel::setCustomPaper,
                onSetMargins = viewModel::setMargins,
            )
        }
    }
}

@Composable
private fun EditorSurface(
    state: NotesUiState,
    viewModel: NotesViewModel,
    defaults: EditorDefaults,
    zoom: Float,
    showPrintMargins: Boolean,
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
        style = state.pageStyle,
        zoom = zoom,
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
        onCanvasMeasured = viewModel::onCanvasMeasured,
        showPrintMargins = showPrintMargins,
        modifier = modifier,
    )
}

@Composable
private fun TitleBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    showNavToggle: Boolean,
    navigationVisible: Boolean,
    onToggleNavigation: () -> Unit,
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
        } else if (showNavToggle) {
            IconButton(onClick = onToggleNavigation, modifier = Modifier.size(34.dp)) {
                Icon(
                    // The open/closed glyph is the only thing that says the panes are hidden
                    // rather than absent, once they are gone and the canvas has taken their place.
                    imageVector = if (navigationVisible) Icons.Default.Menu else Icons.AutoMirrored.Filled.MenuOpen,
                    contentDescription = if (navigationVisible) {
                        "Hide notebooks and pages"
                    } else {
                        "Show notebooks and pages"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.width(8.dp))
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
