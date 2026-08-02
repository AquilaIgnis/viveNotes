package com.vivenotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
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
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.PenPreset
import com.vivenotes.data.TabsLayout
import com.vivenotes.model.PageStyle
import com.vivenotes.ink.InkCodec
import com.vivenotes.ui.editor.DrawActions
import com.vivenotes.ui.editor.EditorPane
import com.vivenotes.ui.editor.Ribbon
import com.vivenotes.ui.editor.RibbonTab
import com.vivenotes.ui.editor.ViewActions
import com.vivenotes.ui.panel.PaperSizePanelContent
import com.vivenotes.ui.panel.TOOL_PANEL_WIDTH
import com.vivenotes.ui.panel.ToolPane
import com.vivenotes.ui.panel.ToolPanel
import com.vivenotes.ui.shell.NotebookRail
import com.vivenotes.ui.shell.PageListPane
import com.vivenotes.ui.shell.SectionTabsBar
import com.vivenotes.ui.theme.LocalCanvasColors
import com.vivenotes.ui.theme.canvasColorsFor

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
    val notebookRailVisible by viewModel.notebookRailVisible.collectAsStateWithLifecycle()
    val defaults by viewModel.editorDefaults.collectAsStateWithLifecycle()
    val viewSettings by viewModel.viewSettings.collectAsStateWithLifecycle()
    val pens by viewModel.pens.collectAsStateWithLifecycle()
    val eraser by viewModel.eraser.collectAsStateWithLifecycle()
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val drawWithFinger by viewModel.drawWithFinger.collectAsStateWithLifecycle()

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

    val drawActions = remember(viewModel) {
        DrawActions(
            selectTool = viewModel::selectTool,
            updatePen = viewModel::updatePen,
            updateEraser = viewModel::updateEraser,
            setDrawWithFinger = viewModel::setDrawWithFinger,
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
                    Ribbon(
                        selection = selection,
                        activeTab = activeTab,
                        onTabChange = { activeTab = it },
                        onCommand = viewModel::send,
                        defaults = defaults,
                        onSetDefault = viewModel::setDefaultFont,
                        pageStyle = state.pageStyle,
                        viewSettings = viewSettings,
                        view = viewActions,
                        pens = pens,
                        eraser = eraser,
                        tool = tool,
                        allowFinger = drawWithFinger,
                        draw = drawActions,
                        pageOpen = state.selectedPageId != null,
                        showBack = !medium && pane != rootPane,
                        onBack = { viewModel.showCompactPane(paneBehind(pane)) },
                        // Only where there is something to collapse: a compact window shows one
                        // pane at a time, and hiding it would leave nothing behind.
                        showNavigationToggle = medium,
                        navigationVisible = navigationVisible,
                        onToggleNavigation = viewModel::toggleNavigation,
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
                                if (!horizontalTabs && expanded && notebookRailVisible) {
                                    NotebookRail(
                                        tree = state.tree,
                                        selectedSectionId = state.selectedSectionId,
                                        onSelectSection = viewModel::selectSection,
                                        onToggleNotebook = viewModel::toggleNotebookExpanded,
                                        onAddSection = { pendingDialog = NameDialog.Section(it) },
                                        onAddNotebook = { pendingDialog = NameDialog.Notebook },
                                        onSwipeLeft = viewModel::hideNotebookRail,
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
                                    onSwipeLeft = viewModel::hideNavigation,
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
                                tool = tool,
                                pens = pens,
                                eraser = eraser,
                                allowFinger = drawWithFinger,
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
                                onSwipeLeft = { viewModel.showCompactPane(CompactPane.Pages) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            CompactPane.Pages -> PageListPane(
                                pages = state.pages,
                                selectedPageId = state.selectedPageId,
                                onSelectPage = viewModel::openPage,
                                onAddPage = viewModel::addPage,
                                onDeletePage = viewModel::deletePage,
                                onSwipeLeft = { viewModel.showCompactPane(CompactPane.Editor) },
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
                                tool = tool,
                                pens = pens,
                                eraser = eraser,
                                allowFinger = drawWithFinger,
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
    tool: DrawTool,
    pens: List<PenPreset>,
    eraser: EraserSettings,
    allowFinger: Boolean,
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

    val strokes by viewModel.strokes.collectAsStateWithLifecycle()

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
        onMarkArmed = viewModel::onMarkArmed,
        onCreateOutline = viewModel::createOutline,
        onMoveOutline = viewModel::moveOutline,
        onResizeOutline = viewModel::resizeOutline,
        onSetOutlineMinHeight = viewModel::setOutlineMinHeight,
        onOutlineBlurred = viewModel::onOutlineBlurred,
        onCanvasMeasured = viewModel::onCanvasMeasured,
        strokes = strokes,
        // Rebuilt only when the pen actually changes, not on every recomposition: a Brush holds a
        // native handle, and the ribbon recomposes whenever the selection moves.
        brush = remember(tool, pens) {
            (tool as? DrawTool.Pen)?.let { pens.getOrNull(it.index) }?.let(InkCodec::brushFor)
        },
        erasing = tool == DrawTool.Eraser,
        lassoing = tool == DrawTool.Lasso,
        eraser = eraser,
        allowFinger = allowFinger,
        onStrokeFinished = viewModel::onStrokeFinished,
        onPartialErase = viewModel::eraseStrokeParts,
        onObjectErase = viewModel::eraseStrokeObjects,
        onMoveSelection = viewModel::moveInk,
        showPrintMargins = showPrintMargins,
        modifier = modifier,
    )
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
