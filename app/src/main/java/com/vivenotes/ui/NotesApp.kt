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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PenPreset
import com.vivenotes.data.RulerSettings
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.TableSettings
import com.vivenotes.data.TabsLayout
import com.vivenotes.data.forCanvasTheme
import com.vivenotes.ai.AiModelStore
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelsState
import com.vivenotes.ai.InkRecognitionEngine
import com.vivenotes.ai.renderInkSelection
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.PageStroke
import com.vivenotes.model.PageStyle
import com.vivenotes.math.FormulaToolsState
import com.vivenotes.math.MathEngine
import com.vivenotes.ui.editor.DrawActions
import com.vivenotes.ui.editor.AiActions
import com.vivenotes.ui.editor.EditorPane
import com.vivenotes.ui.editor.Ribbon
import com.vivenotes.ui.editor.RibbonTab
import com.vivenotes.ui.editor.ViewActions
import com.vivenotes.ui.panel.AiModelsPanelContent
import com.vivenotes.ui.panel.HardwarePanelContent
import com.vivenotes.ui.panel.PaperSizePanelContent
import com.vivenotes.ui.panel.RecognitionOutputKind
import com.vivenotes.ui.panel.RecognitionPanelContent
import com.vivenotes.ui.panel.RecognitionPanelState
import com.vivenotes.ui.panel.TOOL_PANEL_WIDTH
import com.vivenotes.ui.panel.ToolPane
import com.vivenotes.ui.panel.ToolPanel
import com.vivenotes.ui.shell.NotebookRail
import com.vivenotes.ui.shell.PageListPane
import com.vivenotes.ui.shell.SectionTabsBar
import com.vivenotes.ui.theme.LocalCanvasColors
import com.vivenotes.ui.theme.canvasColorsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RAIL_WIDTH = 232.dp
private val PAGE_LIST_WIDTH = 260.dp

/** Below this the panes stack; above it they sit side by side. */
private val MEDIUM_BREAKPOINT = 720.dp

/** Above this the notebook rail is permanently visible alongside the other two panes. */
private val EXPANDED_BREAKPOINT = 1040.dp
private const val MATH_ANALYSIS_DEBOUNCE_MS = 350L

@Composable
fun NotesApp(
    viewModel: NotesViewModel,
    aiModelStore: AiModelStore,
    recognitionEngine: InkRecognitionEngine,
    mathEngine: MathEngine,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val compactPane by viewModel.compactPane.collectAsStateWithLifecycle()
    val navigationVisible by viewModel.navigationVisible.collectAsStateWithLifecycle()
    val notebookRailVisible by viewModel.notebookRailVisible.collectAsStateWithLifecycle()
    val defaults by viewModel.editorDefaults.collectAsStateWithLifecycle()
    val viewSettings by viewModel.viewSettings.collectAsStateWithLifecycle()
    val pens by viewModel.pens.collectAsStateWithLifecycle()
    val palette by viewModel.palette.collectAsStateWithLifecycle()
    val eraser by viewModel.eraser.collectAsStateWithLifecycle()
    val highlighter by viewModel.highlighter.collectAsStateWithLifecycle()
    val shape by viewModel.shape.collectAsStateWithLifecycle()
    val table by viewModel.table.collectAsStateWithLifecycle()
    val ruler by viewModel.ruler.collectAsStateWithLifecycle()
    val rulerOut by viewModel.rulerOut.collectAsStateWithLifecycle()
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val drawWithFinger by viewModel.drawWithFinger.collectAsStateWithLifecycle()
    val canvasUndoState by viewModel.canvasUndoState.collectAsStateWithLifecycle()
    val hasClipboard by viewModel.hasClipboard.collectAsStateWithLifecycle()
    val strokes by viewModel.strokes.collectAsStateWithLifecycle()
    val aiModels by aiModelStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val recognitionScope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(RibbonTab.Home) }
    var pendingDialog by remember { mutableStateOf<NameDialog?>(null) }
    /** The docked pane, if any. Deliberately not persisted: it is where you are, not what you have. */
    var openPane by remember { mutableStateOf<ToolPane?>(null) }
    var recognition by remember { mutableStateOf<RecognitionPanelState?>(null) }
    var recognitionRunning by remember { mutableStateOf(false) }
    var formulaTools by remember { mutableStateOf(FormulaToolsState()) }

    fun recognize(
        selection: com.vivenotes.ink.CanvasSelection,
        kind: RecognitionOutputKind,
    ) {
        if (recognitionRunning) return
        recognitionRunning = true
        recognition = RecognitionPanelState(kind = kind, running = true)
        openPane = ToolPane.Recognition
        val selectedStrokes = strokes
        recognitionScope.launch {
            var bitmap: android.graphics.Bitmap? = null
            try {
                val rendered = withContext(Dispatchers.Default) {
                    renderInkSelection(selectedStrokes, selection)
                }
                bitmap = rendered
                recognition = RecognitionPanelState(
                    kind = kind,
                    value = when (kind) {
                        RecognitionOutputKind.Text -> recognitionEngine.recognizeText(rendered).text
                        RecognitionOutputKind.Formula -> recognitionEngine.recognizeFormula(rendered).latex
                    },
                )
            } catch (failure: Exception) {
                recognition = RecognitionPanelState(
                    kind = kind,
                    error = failure.message ?: "The selected ink could not be recognized.",
                )
            } finally {
                bitmap?.recycle()
                recognitionRunning = false
            }
        }
    }

    val formulaLatex = recognition
        ?.takeIf { it.kind == RecognitionOutputKind.Formula && !it.running && it.error == null }
        ?.value
        .orEmpty()
    LaunchedEffect(formulaLatex) {
        formulaTools = FormulaToolsState(sourceLatex = formulaLatex)
        if (formulaLatex.isBlank()) return@LaunchedEffect
        delay(MATH_ANALYSIS_DEBOUNCE_MS)
        formulaTools = FormulaToolsState(sourceLatex = formulaLatex, analyzing = true)
        formulaTools = try {
            val analysis = mathEngine.analyze(formulaLatex)
            FormulaToolsState(
                sourceLatex = formulaLatex,
                analysis = analysis.takeIf { it.error == null },
                error = analysis.error,
            )
        } catch (failure: Exception) {
            FormulaToolsState(
                sourceLatex = formulaLatex,
                error = failure.message ?: "The local math engine could not start.",
            )
        }
    }

    fun executeMathAction(actionId: String) {
        val source = formulaLatex.takeIf { it.isNotBlank() } ?: return
        if (formulaTools.executingActionId != null) return
        formulaTools = formulaTools.copy(executingActionId = actionId, result = null, error = null)
        recognitionScope.launch {
            val result = try {
                mathEngine.execute(source, actionId)
            } catch (failure: Exception) {
                null.also {
                    if (formulaLatex == source) {
                        formulaTools = formulaTools.copy(
                            executingActionId = null,
                            error = failure.message ?: "The math operation failed.",
                        )
                    }
                }
            }
            if (result != null && formulaLatex == source) {
                formulaTools = formulaTools.copy(
                    executingActionId = null,
                    result = result.takeIf { it.error == null },
                    error = result.error,
                )
            }
        }
    }

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

    val drawActions = remember(viewModel, focusManager) {
        DrawActions(
            selectTool = { selected ->
                if (selected != DrawTool.Text) focusManager.clearFocus(force = true)
                viewModel.selectTool(selected)
            },
            updatePen = viewModel::updatePen,
            updateEraser = viewModel::updateEraser,
            updateHighlighter = viewModel::updateHighlighter,
            updateShape = viewModel::updateShape,
            updateTable = viewModel::updateTable,
            updateRuler = viewModel::updateRuler,
            toggleRuler = viewModel::toggleRuler,
            addPaletteColor = viewModel::addPaletteColor,
            setDrawWithFinger = viewModel::setDrawWithFinger,
            undo = viewModel::undoCanvas,
            redo = viewModel::redoCanvas,
        )
    }

    val aiActions = remember {
        AiActions(openIntegrated = { openPane = ToolPane.AiModels })
    }

    val horizontalTabs = viewSettings.tabsLayout == TabsLayout.Horizontal
    // Switch Background pins the canvas light or dark; until it is used it follows the theme.
    val canvas = viewSettings.canvasDark?.let { canvasColorsFor(it) } ?: LocalCanvasColors.current
    val themedPens = remember(pens, canvas.isDark) {
        pens.map { it.forCanvasTheme(canvas.isDark) }
    }
    // Same resolution the pens get: a black border on a dark page would be invisible, and a colour
    // the user actually picked must survive the theme changing under it.
    val themedShape = remember(shape, canvas.isDark) { shape.forCanvasTheme(canvas.isDark) }
    val themedTable = remember(table, canvas.isDark) { table.forCanvasTheme(canvas.isDark) }
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
                        ai = aiActions,
                        pens = themedPens,
                        palette = palette,
                        eraser = eraser,
                        highlighter = highlighter,
                        shape = themedShape,
                        table = themedTable,
                        ruler = ruler,
                        rulerOut = rulerOut,
                        tool = tool,
                        allowFinger = drawWithFinger,
                        draw = drawActions,
                        pageOpen = state.selectedPageId != null,
                        canUndoCanvas = canvasUndoState.canUndo,
                        canRedoCanvas = canvasUndoState.canRedo,
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
                                pens = themedPens,
                                eraser = eraser,
                                highlighter = highlighter,
                                shape = themedShape,
                                themedTable = themedTable,
                                ruler = ruler,
                                rulerOut = rulerOut,
                                allowFinger = drawWithFinger,
                                hasClipboard = hasClipboard,
                                strokes = strokes,
                                aiModels = aiModels,
                                recognitionRunning = recognitionRunning,
                                onRecognizeText = { recognize(it, RecognitionOutputKind.Text) },
                                onRecognizeFormula = { recognize(it, RecognitionOutputKind.Formula) },
                                modifier = Modifier.weight(1f),
                            )
                            openPane?.let { toolPane ->
                                VerticalHairline()
                                ToolPaneHost(
                                    pane = toolPane,
                                    style = state.pageStyle,
                                    allowFinger = drawWithFinger,
                                    aiModels = aiModels,
                                    onDownloadFormula = aiModelStore::downloadFormula,
                                    recognition = recognition,
                                    formulaTools = formulaTools,
                                    onRecognitionChange = { value ->
                                        recognition = recognition?.copy(value = value)
                                    },
                                    onCopyRecognition = { value ->
                                        val label = if (recognition?.kind == RecognitionOutputKind.Formula) {
                                            "Recognized LaTeX"
                                        } else {
                                            "Recognized text"
                                        }
                                        copyRecognizedText(context, label, value)
                                    },
                                    onMathAction = ::executeMathAction,
                                    onCopyMathResult = { value ->
                                        copyRecognizedText(context, "SymPy result", value)
                                    },
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
                                    allowFinger = drawWithFinger,
                                    aiModels = aiModels,
                                    onDownloadFormula = aiModelStore::downloadFormula,
                                    recognition = recognition,
                                    formulaTools = formulaTools,
                                    onRecognitionChange = { value ->
                                        recognition = recognition?.copy(value = value)
                                    },
                                    onCopyRecognition = { value ->
                                        val label = if (recognition?.kind == RecognitionOutputKind.Formula) {
                                            "Recognized LaTeX"
                                        } else {
                                            "Recognized text"
                                        }
                                        copyRecognizedText(context, label, value)
                                    },
                                    onMathAction = ::executeMathAction,
                                    onCopyMathResult = { value ->
                                        copyRecognizedText(context, "SymPy result", value)
                                    },
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
                                pens = themedPens,
                                eraser = eraser,
                                highlighter = highlighter,
                                shape = themedShape,
                                themedTable = themedTable,
                                ruler = ruler,
                                rulerOut = rulerOut,
                                allowFinger = drawWithFinger,
                                hasClipboard = hasClipboard,
                                strokes = strokes,
                                aiModels = aiModels,
                                recognitionRunning = recognitionRunning,
                                onRecognizeText = { recognize(it, RecognitionOutputKind.Text) },
                                onRecognizeFormula = { recognize(it, RecognitionOutputKind.Formula) },
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
    /** Hardware pane. A property of this device, which is the whole rule for what that pane holds. */
    allowFinger: Boolean,
    aiModels: AiModelsState,
    onDownloadFormula: () -> Unit,
    recognition: RecognitionPanelState?,
    formulaTools: FormulaToolsState,
    onRecognitionChange: (String) -> Unit,
    onCopyRecognition: (String) -> Unit,
    onMathAction: (String) -> Unit,
    onCopyMathResult: (String) -> Unit,
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
            ToolPane.AiModels -> AiModelsPanelContent(
                state = aiModels,
                onDownloadFormula = onDownloadFormula,
            )
            ToolPane.Hardware -> HardwarePanelContent(
                allowFinger = allowFinger,
                onSetDrawWithFinger = viewModel::setDrawWithFinger,
            )
            ToolPane.Recognition -> recognition?.let { result ->
                RecognitionPanelContent(
                    state = result,
                    formulaTools = formulaTools,
                    onValueChange = onRecognitionChange,
                    onCopy = onCopyRecognition,
                    onMathAction = onMathAction,
                    onCopyMathResult = onCopyMathResult,
                )
            } ?: Text(
                text = "Select ink and choose Recognize to see a result here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    highlighter: HighlighterSettings,
    shape: ShapeSettings,
    /** Themed for the canvas, like [shape] — a hairline grid the colour of the page is no grid. */
    themedTable: TableSettings,
    ruler: RulerSettings,
    rulerOut: Boolean,
    allowFinger: Boolean,
    hasClipboard: Boolean,
    strokes: List<PageStroke>,
    aiModels: AiModelsState,
    recognitionRunning: Boolean,
    onRecognizeText: (com.vivenotes.ink.CanvasSelection) -> Unit,
    onRecognizeFormula: (com.vivenotes.ink.CanvasSelection) -> Unit,
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
        onZoomPinched = viewModel::pinchZoom,
        onZoomCommitted = viewModel::commitZoom,
        onTitleChange = viewModel::setTitle,
        outlines = state.outlines,
        pageRevision = state.pageRevision,
        pageId = state.selectedPageId,
        initialBlocksFor = viewModel::initialBlocksFor,
        commands = viewModel.commands,
        onBlocksChanged = viewModel::onBlocksChanged,
        onSelectionChanged = viewModel::onSelectionChanged,
        onMarkArmed = viewModel::onMarkArmed,
        onCreateOutline = viewModel::createOutline,
        textArmed = tool == DrawTool.Text,
        onMoveOutline = viewModel::moveOutline,
        onResizeOutline = viewModel::resizeOutline,
        onSetOutlineMinHeight = viewModel::setOutlineMinHeight,
        onOutlineBlurred = viewModel::onOutlineBlurred,
        onCopyOutline = viewModel::copyOutline,
        onDeleteOutlines = viewModel::deleteOutlines,
        onCommand = viewModel::send,
        onCanvasMeasured = viewModel::onCanvasMeasured,
        strokes = strokes,
        // Rebuilt only when the pen actually changes, not on every recomposition: a Brush holds a
        // native handle, and the ribbon recomposes whenever the selection moves.
        brush = remember(tool, pens, highlighter) {
            when (tool) {
                is DrawTool.Pen -> pens.getOrNull(tool.index)?.let(InkCodec::brushFor)
                DrawTool.Highlighter -> InkCodec.brushFor(highlighter)
                else -> null
            }
        },
        erasing = tool == DrawTool.Eraser,
        lassoing = tool == DrawTool.Lasso,
        shaping = if (tool == DrawTool.Shape) shape else null,
        ruler = ruler.takeIf { rulerOut },
        tables = state.tables,
        // Both tools place a table on the next tap; which *kind* is decided here rather than in the
        // canvas, which has no business knowing there are two — `docs/tablePlan.md` TA15.
        tableArmed = tool == DrawTool.Table || tool == DrawTool.InkTable,
        onInsertTable = { x, y ->
            viewModel.insertTable(themedTable, x, y, inkOnly = tool == DrawTool.InkTable)
        },
        onMoveTables = viewModel::moveTables,
        onResizeTables = viewModel::resizeTables,
        onDeleteTables = viewModel::deleteTables,
        onRecolorTables = viewModel::recolorTables,
        onSetTableBorderWidth = viewModel::setTableBorderWidth,
        onSetTableFill = viewModel::setTableFill,
        onSetTableColumnWidth = viewModel::setTableColumnWidth,
        onSetTableRowMinHeight = viewModel::setTableRowMinHeight,
        onInsertTableRow = viewModel::insertTableRow,
        onDeleteTableRow = viewModel::deleteTableRow,
        onInsertTableColumn = viewModel::insertTableColumn,
        onDeleteTableColumn = viewModel::deleteTableColumn,
        shapes = state.shapes,
        onMoveShape = viewModel::moveShape,
        onResizeShape = viewModel::resizeShape,
        onResizeShapeArm = viewModel::resizeShapeArm,
        onMoveShapes = viewModel::moveShapes,
        onResizeShapes = viewModel::resizeShapes,
        onDeleteShapes = viewModel::deleteShapes,
        onRecolorShapes = viewModel::recolorShapes,
        onSetShapeBorderWidth = viewModel::setShapeBorderWidth,
        onSetShapeLineType = viewModel::setShapeLineType,
        onSetShapeFill = viewModel::setShapeFill,
        eraser = eraser,
        allowFinger = allowFinger,
        hasClipboard = hasClipboard,
        onStrokeFinished = viewModel::onStrokeFinished,
        onInsertShape = viewModel::insertShape,
        onPartialErase = viewModel::eraseStrokeParts,
        onObjectErase = viewModel::eraseStrokeObjects,
        onMoveSelection = viewModel::moveInk,
        onResizeSelection = viewModel::resizeInk,
        onDeleteInkSelection = { viewModel.eraseStrokes(it.toList()) },
        onCopySelection = viewModel::copySelection,
        onPaste = viewModel::pasteObjects,
        onRecolorInkSelection = viewModel::recolorInk,
        onGroupInkSelection = viewModel::groupInk,
        onUngroupInkSelection = viewModel::ungroupInk,
        textRecognitionAvailable = aiModels.handwritingText == AiModelInstallState.Installed,
        formulaRecognitionAvailable = aiModels.formulaLatex == AiModelInstallState.Installed,
        recognitionRunning = recognitionRunning,
        onRecognizeText = onRecognizeText,
        onRecognizeFormula = onRecognizeFormula,
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
