package com.vivenotes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.SectionEntity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.NotebookTransferManager
import com.vivenotes.data.PenPreset
import com.vivenotes.data.RulerSettings
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.StylusButtonMap
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
import com.vivenotes.model.search.ContentHit
import com.vivenotes.math.FormulaToolsState
import com.vivenotes.math.MathEngine
import com.vivenotes.ui.editor.DrawActions
import com.vivenotes.ui.editor.AiActions
import com.vivenotes.ui.editor.EditorPane
import com.vivenotes.ui.editor.FileActions
import com.vivenotes.ui.editor.Ribbon
import com.vivenotes.ui.editor.RibbonTab
import com.vivenotes.ui.editor.ViewActions
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.AiModelsPanelContent
import com.vivenotes.ui.panel.ContentPanelContent
import com.vivenotes.ui.panel.ContentPanelHeader
import com.vivenotes.ui.panel.DeletedItemsPanelContent
import com.vivenotes.ui.panel.HardwarePanelContent
import com.vivenotes.ui.panel.PaperSizePanelContent
import com.vivenotes.ui.panel.RecognitionOutputKind
import com.vivenotes.ui.panel.RecognitionPanelContent
import com.vivenotes.ui.panel.RecognitionPanelState
import com.vivenotes.ui.panel.TOOL_PANEL_WIDTH
import com.vivenotes.ui.panel.ToolPane
import com.vivenotes.ui.panel.ToolPanel
import com.vivenotes.ui.panel.VersionHistoryPanelContent
import com.vivenotes.ui.shell.NotebookRail
import com.vivenotes.ui.shell.PageListPane
import com.vivenotes.ui.shell.SectionTabsBar
import com.vivenotes.ui.theme.LocalCanvasColors
import com.vivenotes.ui.theme.canvasColorsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RAIL_WIDTH = 232.dp
private val PAGE_LIST_WIDTH = 260.dp

/** Below this the panes stack; above it they sit side by side. */
private val MEDIUM_BREAKPOINT = 720.dp

/** Above this the notebook rail is permanently visible alongside the other two panes. */
private val EXPANDED_BREAKPOINT = 1040.dp
private const val MATH_ANALYSIS_DEBOUNCE_MS = 350L

/**
 * What the recognition panel runs by itself once a formula is understood, in order of preference.
 *
 * The two that *answer* the formula come first — Solve for an equation, Evaluate for an integral,
 * derivative, sum, product or limit — and Simplify last, since it only restates what is already on
 * screen. See `docs/calculator.md` for which objects offer which.
 *
 * The order is currently belt and braces: no object offers more than one of these, because `_classify`
 * gives an unevaluated operation `evaluate` alone and gives nothing else `evaluate` at all. It is
 * written as a preference anyway so that adding a fourth entry cannot silently depend on that.
 *
 * Ids rather than labels: these are matched against the action list SymPy returns, and a label is
 * display text that may change.
 */
private val AUTOMATIC_MATH_ACTIONS = listOf("solve", "evaluate", "simplify")

@Composable
fun NotesApp(
    viewModel: NotesViewModel,
    attachments: AttachmentStore,
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
    val pendingEquation by viewModel.pendingEquation.collectAsStateWithLifecycle()
    val drawWithFinger by viewModel.drawWithFinger.collectAsStateWithLifecycle()
    val stylusButtons by viewModel.stylusButtons.collectAsStateWithLifecycle()
    val canvasUndoState by viewModel.canvasUndoState.collectAsStateWithLifecycle()
    val hasClipboard by viewModel.hasClipboard.collectAsStateWithLifecycle()
    val contentSearch by viewModel.contentSearch.collectAsStateWithLifecycle()
    val versionHistory by viewModel.versionHistory.collectAsStateWithLifecycle()
    val deletedItems by viewModel.deletedItems.collectAsStateWithLifecycle()
    val notebookTransfer by viewModel.notebookTransfer.collectAsStateWithLifecycle()
    val reveal by viewModel.reveal.collectAsStateWithLifecycle()

    // The system photo picker — feature E6. Chosen over `GetContent` and over `READ_MEDIA_IMAGES`
    // deliberately: it needs **no runtime permission at all**, because the user picking a file *is*
    // the grant, and it shows the same picker whether the photo is local or in the cloud. Asking for
    // storage permission to insert one picture is the thing this API exists to stop.
    val pickPicture = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::insertImage) }
    // Storage Access Framework pickers grant access to exactly the document the user chose. No
    // broad storage permission is requested, and providers may be local, removable, or cloud-backed.
    val exportNotebook = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(NotebookTransferManager.MIME_TYPE),
    ) { uri -> uri?.let(viewModel::exportCurrentNotebook) }
    val importNotebook = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importNotebook) }
    val strokes by viewModel.strokes.collectAsStateWithLifecycle()
    val aiModels by aiModelStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val recognitionScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Immediate Undo is a convenience over the durable Deleted Items pane. collectLatest keeps the
    // newest destructive action visible; older ones remain recoverable from the pane even when a
    // burst of deletes replaces their transient message.
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.deletionNotices.collectLatest { notice ->
            val result = snackbarHostState.showSnackbar(
                message = notice.message,
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreDeletedItem(notice.key)
            }
        }
    }

    // Hoisted into the view model, because a stylus button can change it — see `ui/StylusButtons.kt`.
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    var pendingDialog by remember { mutableStateOf<NameDialog?>(null) }
    var pendingSectionDelete by remember { mutableStateOf<SectionEntity?>(null) }
    /** Null until the count has been read, which is what the dialog's vaguer wording covers. */
    var pendingSectionPages by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingSectionDelete) {
        pendingSectionPages = pendingSectionDelete?.let { viewModel.pageCount(it.id) }
    }
    var pendingNotebookDelete by remember { mutableStateOf<NotebookEntity?>(null) }
    /** As above: null until the counts have been read. */
    var pendingNotebookContents by remember { mutableStateOf<NotebookContents?>(null) }
    LaunchedEffect(pendingNotebookDelete) {
        pendingNotebookContents = pendingNotebookDelete?.let { viewModel.notebookContents(it.id) }
    }
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

    /**
     * Run the obvious operation without waiting to be asked — Solve where the formula is a question,
     * Simplify where it is a mess.
     *
     * A recognised equation almost always wants solving, and making the user tap Solve to find that
     * out spends a tap on a foregone conclusion. Anything with neither action — a matrix, an integral
     * — is left alone rather than given an arbitrary default.
     *
     * Keyed on the analysis rather than on the LaTeX, so it fires once when a new analysis lands and
     * not again: `executeMathAction` only ever `copy`s the state, which leaves `analysis` the same
     * instance. Tapping a different action afterwards therefore sticks.
     */
    LaunchedEffect(formulaTools.analysis) {
        val available = formulaTools.analysis?.actions?.map { it.id }.orEmpty()
        val automatic = AUTOMATIC_MATH_ACTIONS.firstOrNull { it in available }
        if (automatic != null) executeMathAction(automatic)
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
            armEquation = { latex, measured ->
                viewModel.armEquation(
                    PendingEquation(latex = latex, width = measured.width, height = measured.height),
                )
            },
            addPaletteColor = viewModel::addPaletteColor,
            setDrawWithFinger = viewModel::setDrawWithFinger,
            undo = viewModel::undoCanvas,
            redo = viewModel::redoCanvas,
        )
    }

    val aiActions = remember {
        AiActions(openIntegrated = { openPane = ToolPane.AiModels })
    }
    val exportFileName = viewModel.selectedNotebookName()?.viveFileName()
    // The notebook the ribbon acts on is the one holding the open section — the same rule the export
    // name follows, read from the tree here because the dialog needs the row, not just the name.
    val currentNotebook = state.tree
        .firstOrNull { entry -> entry.liveSections.any { it.id == state.selectedSectionId } }
        ?.notebook
    val fileActions = remember(viewModel, exportFileName, currentNotebook) {
        FileActions(
            openVersionHistory = {
                openPane = ToolPane.VersionHistory
                viewModel.loadVersionHistory()
            },
            openDeletedItems = {
                openPane = ToolPane.DeletedItems
            },
            exportNotebook = {
                exportFileName?.let(exportNotebook::launch)
            },
            importNotebook = {
                importNotebook.launch(NotebookTransferManager.importMimeTypes())
            },
            deleteNotebook = {
                pendingNotebookDelete = currentNotebook
            },
        )
    }

    // Keep a docked history pane in step when the user chooses another page behind it.
    LaunchedEffect(state.selectedPageId) {
        if (openPane == ToolPane.VersionHistory) viewModel.loadVersionHistory()
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

            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Ribbon(
                        selection = selection,
                        activeTab = activeTab,
                        onTabChange = viewModel::selectRibbonTab,
                        onCommand = viewModel::send,
                        defaults = defaults,
                        onSetDefault = viewModel::setDefaultFont,
                        onInsertPicture = {
                            pickPicture.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        pageStyle = state.pageStyle,
                        viewSettings = viewSettings,
                        view = viewActions,
                        file = fileActions,
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
                        notebookOpen = state.selectedSectionId != null,
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
                                        onRenameNotebook = {
                                            pendingDialog = NameDialog.RenameNotebook(it.id, it.name)
                                        },
                                        onRenameSection = {
                                            pendingDialog = NameDialog.RenameSection(it.id, it.name)
                                        },
                                        onDeleteSection = { pendingSectionDelete = it },
                                        onReorderSections = viewModel::reorderSections,
                                        onSwipeLeft = viewModel::hideNotebookRail,
                                        modifier = Modifier.width(RAIL_WIDTH),
                                    )
                                    VerticalHairline()
                                }
                                PageListPane(
                                    pages = state.pages,
                                    selectedPageId = state.selectedPageId,
                                    sectionOpen = state.selectedSectionId != null,
                                    onSelectPage = viewModel::openPage,
                                    onAddPage = viewModel::addPage,
                                    onDeletePage = viewModel::deletePage,
                                    onReorderPages = viewModel::reorderPages,
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
                                searchOpen = openPane == ToolPane.Content,
                                onToggleSearch = {
                                    openPane = if (openPane == ToolPane.Content) null else ToolPane.Content
                                },
                                reveal = reveal,
                                onRevealHandled = viewModel::onRevealHandled,
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
                                attachments = attachments,
                                aiModels = aiModels,
                                recognitionRunning = recognitionRunning,
                                onRecognizeFormula = { recognize(it, RecognitionOutputKind.Formula) },
                                pendingEquation = pendingEquation,
                                modifier = Modifier.weight(1f),
                            )
                            openPane?.let { toolPane ->
                                VerticalHairline()
                                ToolPaneHost(
                                    pane = toolPane,
                                    style = state.pageStyle,
                                    allowFinger = drawWithFinger,
                                    stylusButtons = stylusButtons,
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
                                    contentSearch = contentSearch,
                                    versionHistory = versionHistory,
                                    deletedItems = deletedItems,
                                    onSearchQueryChange = viewModel::setSearchQuery,
                                    onOpenHit = viewModel::openSearchHit,
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
                                onRenameNotebook = {
                                    pendingDialog = NameDialog.RenameNotebook(it.id, it.name)
                                },
                                onRenameSection = {
                                    pendingDialog = NameDialog.RenameSection(it.id, it.name)
                                },
                                onDeleteSection = { pendingSectionDelete = it },
                                onReorderSections = viewModel::reorderSections,
                                onSwipeLeft = { viewModel.showCompactPane(CompactPane.Pages) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            CompactPane.Pages -> PageListPane(
                                pages = state.pages,
                                selectedPageId = state.selectedPageId,
                                sectionOpen = state.selectedSectionId != null,
                                onSelectPage = viewModel::openPage,
                                onAddPage = viewModel::addPage,
                                onDeletePage = viewModel::deletePage,
                                onReorderPages = viewModel::reorderPages,
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
                                    stylusButtons = stylusButtons,
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
                                    contentSearch = contentSearch,
                                    versionHistory = versionHistory,
                                    deletedItems = deletedItems,
                                    onSearchQueryChange = viewModel::setSearchQuery,
                                    // Compact windows show the pane *instead of* the page, so going
                                    // to a result has to put the page back or it reveals it behind
                                    // the panel that asked for it.
                                    onOpenHit = { hit ->
                                        viewModel.openSearchHit(hit)
                                        openPane = null
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
                                searchOpen = false,
                                onToggleSearch = { openPane = ToolPane.Content },
                                reveal = reveal,
                                onRevealHandled = viewModel::onRevealHandled,
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
                                attachments = attachments,
                                aiModels = aiModels,
                                recognitionRunning = recognitionRunning,
                                onRecognizeFormula = { recognize(it, RecognitionOutputKind.Formula) },
                                pendingEquation = pendingEquation,
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
                is NameDialog.RenameNotebook -> "Rename notebook"
                is NameDialog.RenameSection -> "Rename section"
            },
            confirmLabel = when (dialog) {
                is NameDialog.Notebook, is NameDialog.Section -> "Create"
                is NameDialog.RenameNotebook, is NameDialog.RenameSection -> "Rename"
            },
            initial = when (dialog) {
                is NameDialog.RenameNotebook -> dialog.current
                is NameDialog.RenameSection -> dialog.current
                else -> ""
            },
            onDismiss = { pendingDialog = null },
            onConfirm = { name ->
                when (dialog) {
                    is NameDialog.Notebook -> viewModel.createNotebook(name)
                    is NameDialog.Section -> viewModel.createSection(dialog.notebookId, name)
                    // A blank rename is a cancel, not an erasure: creating falls back to a default
                    // name, but there is no sensible default for something that already has one.
                    is NameDialog.RenameNotebook -> name.trim().takeIf { it.isNotEmpty() }
                        ?.let { viewModel.renameNotebook(dialog.id, it) }
                    is NameDialog.RenameSection -> name.trim().takeIf { it.isNotEmpty() }
                        ?.let { viewModel.renameSection(dialog.id, it) }
                }
                pendingDialog = null
            },
        )
    }
    pendingSectionDelete?.let { section ->
        DeleteSectionDialog(
            section = section,
            pageCount = pendingSectionPages,
            onDismiss = { pendingSectionDelete = null },
            onConfirm = {
                viewModel.deleteSection(section.id)
                pendingSectionDelete = null
            },
        )
    }
    pendingNotebookDelete?.let { notebook ->
        DeleteNotebookDialog(
            notebook = notebook,
            contents = pendingNotebookContents,
            onDismiss = { pendingNotebookDelete = null },
            onConfirm = {
                viewModel.deleteNotebook(notebook.id)
                pendingNotebookDelete = null
            },
        )
    }
    if (notebookTransfer.running || notebookTransfer.message != null || notebookTransfer.error != null) {
        NotebookTransferDialog(
            state = notebookTransfer,
            onDismiss = viewModel::clearNotebookTransferStatus,
        )
    }
}

private fun String.viveFileName(): String {
    val safe = replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .trim()
        .trim('.')
        .take(100)
        .ifBlank { "Notebook" }
    return "$safe${NotebookTransferManager.EXTENSION}"
}

@Composable
private fun NotebookTransferDialog(
    state: NotebookTransferState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = {
            Text(
                when {
                    state.running -> "Working with notebook"
                    state.error != null -> "Notebook transfer failed"
                    else -> "Notebook transfer complete"
                },
            )
        },
        text = {
            if (state.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Checking and preparing the notebook…", Modifier.padding(start = 12.dp))
                }
            } else {
                Text(state.error ?: state.message.orEmpty())
            }
        },
        confirmButton = {
            if (!state.running) TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
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
    /** Hardware pane, a property of this device — see `HardwarePanelContent` on the pane's two scopes. */
    allowFinger: Boolean,
    /** Hardware pane, a property of the user — `docs/stylusPlan.md` SB3. */
    stylusButtons: StylusButtonMap,
    aiModels: AiModelsState,
    onDownloadFormula: () -> Unit,
    recognition: RecognitionPanelState?,
    formulaTools: FormulaToolsState,
    onRecognitionChange: (String) -> Unit,
    onCopyRecognition: (String) -> Unit,
    onMathAction: (String) -> Unit,
    onCopyMathResult: (String) -> Unit,
    /** Content pane — the query, and what it found across the notebook (`docs/searchPlan.md`). */
    contentSearch: ContentSearchState,
    versionHistory: VersionHistoryState,
    deletedItems: DeletedItemsState,
    onSearchQueryChange: (String) -> Unit,
    onOpenHit: (ContentHit) -> Unit,
    viewModel: NotesViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The query field is the one control that must survive its own results scrolling past it.
    val header: (@Composable ColumnScope.() -> Unit)? = if (pane == ToolPane.Content) {
        { ContentPanelHeader(state = contentSearch, onQueryChange = onSearchQueryChange) }
    } else {
        null
    }
    ToolPanel(pane = pane, onClose = onClose, modifier = modifier, header = header) {
        when (pane) {
            ToolPane.VersionHistory -> VersionHistoryPanelContent(
                state = versionHistory,
                onSelect = viewModel::selectVersionRevision,
                onRestore = viewModel::restoreSelectedVersion,
            )
            ToolPane.DeletedItems -> DeletedItemsPanelContent(
                state = deletedItems,
                onRestore = { viewModel.restoreDeletedItem(it.key) },
                onClearStatus = viewModel::clearDeletedItemsStatus,
            )
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
                buttons = stylusButtons,
                onSetButtons = viewModel::setStylusButtons,
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
            ToolPane.Content -> ContentPanelContent(
                state = contentSearch,
                onOpenHit = onOpenHit,
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
    /** Whether the Content pane is docked, which the magnifier shows as its own pressed state. */
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    /** A search result the canvas has been asked to scroll to and put the caret on — CS9. */
    reveal: ContentReveal?,
    onRevealHandled: () -> Unit,
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
    /** Turns a picture's id into its pixels — feature E6. */
    attachments: AttachmentStore,
    aiModels: AiModelsState,
    recognitionRunning: Boolean,
    onRecognizeFormula: (com.vivenotes.ink.CanvasSelection) -> Unit,
    /** The formula the Draw tab's ƒ is holding, or null when it is holding none. */
    pendingEquation: PendingEquation?,
    modifier: Modifier = Modifier,
) {
    // The magnifier is a sibling of the page rather than part of it, and composed after it, so it
    // sits above the ink overlay and takes the tap the overlay would otherwise swallow. It stays on
    // a canvas with no page open, because a notebook-wide search does not need one (CS10).
    Box(modifier.fillMaxSize()) {
        if (state.selectedPageId == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.loading) "" else "No page selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            PageEditor(
                state = state,
                viewModel = viewModel,
                defaults = defaults,
                zoom = zoom,
                showPrintMargins = showPrintMargins,
                reveal = reveal,
                onRevealHandled = onRevealHandled,
                tool = tool,
                pens = pens,
                eraser = eraser,
                highlighter = highlighter,
                shape = shape,
                themedTable = themedTable,
                ruler = ruler,
                rulerOut = rulerOut,
                allowFinger = allowFinger,
                hasClipboard = hasClipboard,
                strokes = strokes,
                attachments = attachments,
                aiModels = aiModels,
                recognitionRunning = recognitionRunning,
                onRecognizeFormula = onRecognizeFormula,
                pendingEquation = pendingEquation,
            )
        }

        SearchAffordance(
            open = searchOpen,
            onClick = onToggleSearch,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )
    }
}

/** Test tag for the magnifier that opens the Content pane. */
internal const val SEARCH_AFFORDANCE_TAG = "canvas-search"

/**
 * The magnifier floating at the canvas's top-right — feature C7, `docs/searchPlan.md` CS10.
 *
 * Over the page rather than in the ribbon so it is reachable from every tab, Draw included, and so
 * that finding something does not cost a tab switch in the middle of a thought.
 */
@Composable
private fun SearchAffordance(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = if (open) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .testTag(SEARCH_AFFORDANCE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Search,
            contentDescription = if (open) "Close search" else "Search this notebook",
            tint = if (open) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The page itself, split out so [EditorSurface] can float the magnifier over it. */
@Composable
private fun PageEditor(
    state: NotesUiState,
    viewModel: NotesViewModel,
    defaults: EditorDefaults,
    zoom: Float,
    showPrintMargins: Boolean,
    reveal: ContentReveal?,
    onRevealHandled: () -> Unit,
    tool: DrawTool,
    pens: List<PenPreset>,
    eraser: EraserSettings,
    highlighter: HighlighterSettings,
    shape: ShapeSettings,
    themedTable: TableSettings,
    ruler: RulerSettings,
    rulerOut: Boolean,
    allowFinger: Boolean,
    hasClipboard: Boolean,
    strokes: List<PageStroke>,
    attachments: AttachmentStore,
    aiModels: AiModelsState,
    recognitionRunning: Boolean,
    onRecognizeFormula: (com.vivenotes.ink.CanvasSelection) -> Unit,
    pendingEquation: PendingEquation?,
) {
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
        // One tool places a table on the next tap; which *kind* rides in on the settings, so the
        // canvas never learns there are two — `docs/tablePlan.md` TA15.
        tableArmed = tool == DrawTool.Table,
        onInsertTable = { x, y -> viewModel.insertTable(themedTable, x, y) },
        equations = state.equations,
        // Armed only while it is actually holding something. Losing the formula — a tab switch, a
        // different tool, an undo — must not leave a tool in hand that would place nothing.
        equationArmed = tool == DrawTool.Equation && pendingEquation != null,
        onInsertEquation = { x, y ->
            pendingEquation?.let {
                viewModel.insertEquation(it.latex, x, y, it.width, it.height)
            }
        },
        onMoveEquations = viewModel::moveEquations,
        onResizeEquations = viewModel::resizeEquations,
        onDeleteEquations = viewModel::deleteEquations,
        images = state.images,
        attachments = attachments,
        onMoveImages = viewModel::moveImages,
        onResizeImages = viewModel::resizeImages,
        onDeleteImages = viewModel::deleteImages,
        onViewport = viewModel::reportViewport,
        onRecolorEquations = viewModel::recolorEquations,
        onEditEquation = viewModel::setEquationLatex,
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
        formulaRecognitionAvailable = aiModels.formulaLatex == AiModelInstallState.Installed,
        recognitionRunning = recognitionRunning,
        onRecognizeFormula = onRecognizeFormula,
        showPrintMargins = showPrintMargins,
        reveal = reveal,
        onRevealHandled = onRevealHandled,
        modifier = Modifier.fillMaxSize(),
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
    data class RenameNotebook(val id: String, val current: String) : NameDialog
    data class RenameSection(val id: String, val current: String) : NameDialog
}

/**
 * Naming something, whether or not it already has a name.
 *
 * [initial] is what makes it serve renaming too: the field opens on the current name with it
 * selected, so replacing is one keystroke and correcting a typo does not mean retyping the rest.
 */
@Composable
private fun NameEntryDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
) {
    var value by remember {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
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
                            if (value.text.isEmpty()) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Confirms a section deletion, which the page list's own delete does not do for a single page.
 *
 * The asymmetry is the point: a section takes every page in it out of reach in one tap, and the
 * count is the part worth reading before agreeing to it. The rows are only tombstoned and remain
 * available from the app-wide Deleted Items pane.
 */
@Composable
private fun DeleteSectionDialog(
    section: SectionEntity,
    pageCount: Int?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${section.name}?") },
        text = {
            Text(
                buildString {
                    append(
                        when (pageCount) {
                            null, 0 -> "This section will be removed."
                            1 -> "Its 1 page will go with it."
                            else -> "Its $pageCount pages will go with it."
                        },
                    )
                    append(" You can restore it later from Deleted Items.")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Confirms deleting the whole notebook the ribbon's File tab is pointed at.
 *
 * The same asymmetry as [DeleteSectionDialog], one level up, and for stronger reasons: this is the
 * largest thing the app can remove in a single tap, and it is reached from a toolbar rather than
 * from the row of the thing being deleted, so the name in the title is doing real work — it is the
 * only place the user can check that the ribbon was pointed where they thought. The body names the
 * durable recovery route so the confirmation does not imply that this is a hard delete.
 */
@Composable
private fun DeleteNotebookDialog(
    notebook: NotebookEntity,
    contents: NotebookContents?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${notebook.name}?") },
        text = {
            Text(
                buildString {
                    append(
                        when {
                            contents == null || (contents.sections == 0 && contents.pages == 0) ->
                                "This notebook will be removed."
                            else ->
                                "Its ${countOf(contents.sections, "section")} and " +
                                    "${countOf(contents.pages, "page")} will go with it."
                        },
                    )
                    append(" You can restore it later from Deleted Items.")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** "1 section" / "3 sections" — the counts in a delete confirmation are read, so they are spelled. */
private fun countOf(value: Int, noun: String): String =
    if (value == 1) "1 $noun" else "$value ${noun}s"
