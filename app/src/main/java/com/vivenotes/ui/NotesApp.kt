package com.vivenotes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.IntOffset
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.SectionEntity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.ImageTextProgress
import com.vivenotes.data.InkTextProgress
import com.vivenotes.data.NotebookTransferManager
import com.vivenotes.data.PenPreset
import com.vivenotes.data.RulerSettings
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.StylusButtonMap
import com.vivenotes.data.TableSettings
import com.vivenotes.data.TabsLayout
import com.vivenotes.data.forCanvasTheme
import com.vivenotes.data.sync.CloudSignInResult
import com.vivenotes.data.sync.ServerConnection
import com.vivenotes.data.sync.SyncAccounts
import com.vivenotes.data.sync.DisconnectResult
import com.vivenotes.data.sync.SyncRunResult
import com.vivenotes.data.sync.ConnectFailure
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
import com.vivenotes.richtext.VideoThumbnails
import com.vivenotes.ui.editor.DrawActions
import com.vivenotes.ui.editor.AiActions
import com.vivenotes.ui.editor.EditorPane
import com.vivenotes.ui.editor.FileActions
import com.vivenotes.ui.editor.LocalVideoThumbnails
import com.vivenotes.ui.editor.Ribbon
import com.vivenotes.ui.editor.RibbonTab
import com.vivenotes.ui.editor.ViewActions
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.R
import com.vivenotes.data.sync.CloudArchiveResult
import com.vivenotes.ui.account.AccountScreen
import com.vivenotes.ui.closed.ClosedNotebooksScreen
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics

private val RAIL_WIDTH = 232.dp
private val PAGE_LIST_WIDTH = 260.dp

/** Below this the panes stack; above it they sit side by side. */
private val MEDIUM_BREAKPOINT = 720.dp

/** Above this the notebook rail is permanently visible alongside the other two panes. */
private val EXPANDED_BREAKPOINT = 1040.dp
private const val MATH_ANALYSIS_DEBOUNCE_MS = 350L

private enum class AppDestination {
    Workspace,
    Account,

    /** The closed-notebook shelf — `memory/closedNotebooksPlan.md`. */
    ClosedNotebooks,
}

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
    videoThumbnails: VideoThumbnails,
    aiModelStore: AiModelStore,
    recognitionEngine: InkRecognitionEngine,
    mathEngine: MathEngine,
    syncAccounts: SyncAccounts,
) {
    // The app owns its small back stack, following Navigation 3's state model without taking on a
    // navigation dependency for two local destinations. Keeping the workspace composed preserves
    // its open page, selection and transient editing tools while Account is in front.
    val backStack = rememberSaveable(
        saver = listSaver(
            save = { stack -> stack.map(AppDestination::name) },
            restore = { names ->
                mutableStateListOf<AppDestination>().apply {
                    addAll(names.map(AppDestination::valueOf))
                }
            },
        ),
    ) {
        mutableStateListOf(AppDestination.Workspace)
    }
    val accountOpen = backStack.lastOrNull() == AppDestination.Account
    val closedNotebooksOpen = backStack.lastOrNull() == AppDestination.ClosedNotebooks
    val spatialMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    // Held here, one level above the Account destination, so closing the screen mid-request neither
    // cancels it nor loses its result. `POST /v1/devices` returns the device token exactly once and
    // the server cannot reissue it, so a scope that dies with the screen would turn a stray Back
    // press into a device registered on the server that nothing here can authenticate as.
    val connectScope = rememberCoroutineScope()
    var selfHostConnection by remember { mutableStateOf<ServerConnection>(ServerConnection.Idle) }
    // Only this screen's button, so the spinner belongs to the press that started it. The clock's
    // own runs report through `syncStatus` instead: at the debug interval a shared flag would put a
    // spinner on the button every five seconds without anyone having asked for one.
    var syncing by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    var disconnectFailure by remember { mutableStateOf<ConnectFailure?>(null) }

    // The Google route's own in-flight and failure state, kept apart from the self-host route's for
    // the reason the screen keeps its messages apart: the two fail independently, and one button's
    // spinner has no business appearing under the other.
    var signingInWithGoogle by remember { mutableStateOf(false) }
    var googleFailure by remember { mutableStateOf<ConnectFailure?>(null) }
    var linkRequired by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    // Session-only, deliberately. It answers "what did that button just do", which is news exactly
    // once; persisting it would have a tablet still announcing a new account weeks later.
    var accountCreated by remember { mutableStateOf(false) }

    // Credential Manager shows UI, so it needs the Activity rather than the application context —
    // there is no window to put a sheet in otherwise. Null in a preview or a test host with no
    // Activity, which is why the sign-in handler checks before launching.
    val activity = LocalActivity.current

    // A registration already on disk is shown as the connected state, so leaving the screen and
    // coming back does not read as never having connected. This attempt wins while there is one:
    // its result — including a failure — is news, and the stored account is only the background.
    val storedAccount by syncAccounts.account.collectAsStateWithLifecycle(initialValue = null)
    val syncStatus by syncAccounts.status.collectAsStateWithLifecycle()

    // A revocation found by the clock rather than by the button still has to reach the screen. The
    // token is already gone by the time this runs — `synchronize` drops it — so this is only about
    // saying why the form came back.
    LaunchedEffect(syncStatus.failure) {
        if (syncStatus.failure == SyncRunResult.Revoked) {
            selfHostConnection = ServerConnection.Failed(ConnectFailure.Revoked)
        }
    }

    // Revocation is one-sided: the operator removes this device from the dashboard and nothing tells
    // the app. Opening Account is when it is worth asking, because it is the only screen where the
    // answer changes what is shown — and a revoked token is dropped there rather than kept to fail
    // every later request identically. Only a `Failed` verdict is taken; anything else leaves the
    // stored account to speak for itself, so being offline cannot look like being revoked.
    LaunchedEffect(accountOpen) {
        if (accountOpen && selfHostConnection == ServerConnection.Idle) {
            val checked = syncAccounts.refresh()
            if (checked is ServerConnection.Failed) selfHostConnection = checked
        }
    }
    val displayedConnection = when (val attempt = selfHostConnection) {
        ServerConnection.Idle -> storedAccount
            ?.let { ServerConnection.Connected(it.serverUrl, it.deviceName) }
            ?: ServerConnection.Idle

        else -> attempt
    }
    val closeAccount = {
        if (backStack.lastOrNull() == AppDestination.Account) {
            backStack.removeLast()
        }
    }
    val closeShelf = {
        if (backStack.lastOrNull() == AppDestination.ClosedNotebooks) {
            backStack.removeLast()
        }
    }

    // Held beside the connect scope, one level above the destination, for the same reason it is:
    // moving a notebook to the cloud is a sync run, an eviction and a second sync run, and a Back
    // press part-way through must not cancel it — that is the window where the rows are deleted.
    val closedNotebooks by viewModel.closedNotebooks
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var cloudBusyNotebookId by remember { mutableStateOf<String?>(null) }
    var cloudMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun runCloudArchive(notebookId: String, move: Boolean) {
        if (cloudBusyNotebookId != null) return
        cloudBusyNotebookId = notebookId
        cloudMessage = null
        connectScope.launch {
            val result = if (move) {
                syncAccounts.moveNotebookToCloud(notebookId)
            } else {
                syncAccounts.bringNotebookBack(notebookId)
            }
            cloudMessage = cloudArchiveMessage(context, result, move)
            cloudBusyNotebookId = null
        }
    }

    val linkPreviews by remember(viewModel) {
        viewModel.viewSettings.map { it.linkPreviews }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = true)

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (accountOpen || closedNotebooksOpen) {
                        Modifier.semantics { hideFromAccessibility() }
                    } else {
                        Modifier
                    },
                ),
        ) {
            // Null while the Settings toggle is off, which is the whole of turning the feature
            // off: an editor with no source draws no cards and opens no connection. Collected as
            // just this one flag rather than as the whole of `ViewSettings`, because a static
            // local invalidates everything below it — reading zoom here would rebuild the
            // workspace on every frame of a pinch.
            CompositionLocalProvider(
                LocalVideoThumbnails provides videoThumbnails.takeIf { linkPreviews },
            ) {
                NotesWorkspace(
                    viewModel = viewModel,
                    attachments = attachments,
                    aiModelStore = aiModelStore,
                    recognitionEngine = recognitionEngine,
                    mathEngine = mathEngine,
                    onOpenAccount = {
                        if (backStack.lastOrNull() != AppDestination.Account) {
                            backStack.add(AppDestination.Account)
                        }
                    },
                    onOpenClosedNotebooks = {
                        if (backStack.lastOrNull() != AppDestination.ClosedNotebooks) {
                            backStack.add(AppDestination.ClosedNotebooks)
                        }
                    },
                    // The stored registration, not the in-flight attempt: the ribbon reports what
                    // this installation *has*, which outlives any one visit to the account screen.
                    accountConnected = storedAccount != null,
                    // Only meaningful with a registration behind it. A device that was never
                    // connected has no server to be cut off from, and a cloud with a line through
                    // it there would read as a feature that is broken rather than one not set up.
                    serverUnreachable = storedAccount != null && syncStatus.serverUnreachable,
                )
            }
        }

        AnimatedVisibility(
            visible = closedNotebooksOpen,
            enter = slideInHorizontally(
                animationSpec = spatialMotion,
                initialOffsetX = { it },
            ) + fadeIn(animationSpec = effectsMotion),
            exit = slideOutHorizontally(
                animationSpec = spatialMotion,
                targetOffsetX = { it },
            ) + fadeOut(animationSpec = effectsMotion),
        ) {
            ClosedNotebooksScreen(
                notebooks = closedNotebooks,
                onBack = closeShelf,
                onOpen = { id ->
                    viewModel.reopenNotebook(id)
                    // Straight back to the workspace: reopening puts the notebook in the rail and
                    // selects its first section, and staying here would leave the person looking at
                    // a list the notebook has just left.
                    closeShelf()
                },
                onMoveToCloud = { id -> runCloudArchive(id, move = true) },
                onBringBack = { id -> runCloudArchive(id, move = false) },
                accountConnected = storedAccount != null,
                busyNotebookId = cloudBusyNotebookId,
                message = cloudMessage,
            )
        }

        AnimatedVisibility(
            visible = accountOpen,
            enter = slideInHorizontally(
                animationSpec = spatialMotion,
                initialOffsetX = { it },
            ) + fadeIn(animationSpec = effectsMotion),
            exit = slideOutHorizontally(
                animationSpec = spatialMotion,
                targetOffsetX = { it },
            ) + fadeOut(animationSpec = effectsMotion),
        ) {
            AccountScreen(
                onBack = closeAccount,
                googleAvailable = syncAccounts.googleSignInAvailable,
                signingInWithGoogle = signingInWithGoogle,
                onSignInWithGoogle = {
                    if (!signingInWithGoogle && activity != null) {
                        signingInWithGoogle = true
                        googleFailure = null
                        // The whole flow — challenge, sheet, sign-in, token write — runs in the
                        // scope above this destination, exactly as `onConnect` does: it ends in a
                        // device token that is issued once, so a Back press must not cancel it.
                        connectScope.launch {
                            when (val result = syncAccounts.signInWithGoogle(activity)) {
                                is CloudSignInResult.Connected -> {
                                    accountCreated = result.createdAccount
                                    selfHostConnection = ServerConnection.Connected(
                                        result.serverUrl,
                                        result.deviceName,
                                    )
                                }

                                CloudSignInResult.LinkRequired -> linkRequired = true

                                // The person closed the sheet. Nothing to say about it.
                                CloudSignInResult.Dismissed -> Unit

                                is CloudSignInResult.Failed -> googleFailure = result.reason
                            }
                            signingInWithGoogle = false
                        }
                    }
                },
                googleFailure = googleFailure,
                linkRequired = linkRequired,
                linking = linking,
                onLinkAccount = { email, password ->
                    if (!linking) {
                        linking = true
                        googleFailure = null
                        connectScope.launch {
                            when (val result = syncAccounts.linkGoogleAccount(email, password)) {
                                is CloudSignInResult.Connected -> {
                                    accountCreated = result.createdAccount
                                    linkRequired = false
                                    selfHostConnection = ServerConnection.Connected(
                                        result.serverUrl,
                                        result.deviceName,
                                    )
                                }

                                // Neither can arise from the link endpoint; `SyncAccounts` maps its
                                // own impossible answer to a failure. Leaving the dialog open is the
                                // safe reading of a result that should not exist.
                                CloudSignInResult.LinkRequired,
                                CloudSignInResult.Dismissed,
                                -> Unit

                                // The dialog stays up with its message: a wrong password is usually
                                // one character, and closing it would cost a whole new sign-in.
                                is CloudSignInResult.Failed -> googleFailure = result.reason
                            }
                            linking = false
                        }
                    }
                },
                onCancelLink = {
                    if (!linking) {
                        linkRequired = false
                        googleFailure = null
                        // Not just closing the dialog: this drops the Google ID token that the
                        // pending link is holding in memory.
                        syncAccounts.cancelGoogleLink()
                    }
                },
                accountCreated = accountCreated,
                connection = displayedConnection,
                onConnect = { serverUrl, email, password ->
                    disconnectFailure = null
                    selfHostConnection = ServerConnection.Connecting
                    connectScope.launch {
                        selfHostConnection = syncAccounts.connect(serverUrl, email, password)
                    }
                },
                syncing = syncing,
                syncStatus = syncStatus,
                onSync = {
                    if (!syncing) {
                        syncing = true
                        connectScope.launch {
                            // The result reaches the screen through `syncStatus`, which every run
                            // reports to; this only has to put the button back.
                            syncAccounts.synchronize()
                            syncing = false
                        }
                    }
                },
                disconnecting = disconnecting,
                disconnectFailure = disconnectFailure,
                onDisconnect = {
                    if (!disconnecting) {
                        disconnecting = true
                        disconnectFailure = null
                        connectScope.launch {
                            when (val result = syncAccounts.disconnect()) {
                                DisconnectResult.Disconnected -> {
                                    // Back to Idle rather than to a "disconnected" result: with the
                                    // stored account gone the empty form is the screen saying so.
                                    // `disconnect` clears the sync status for the same reason.
                                    selfHostConnection = ServerConnection.Idle
                                    accountCreated = false
                                    googleFailure = null
                                }
                                is DisconnectResult.Failed -> {
                                    disconnectFailure = result.reason
                                }
                            }
                            disconnecting = false
                        }
                    }
                },
                // The offer that follows a failed revoke. Same local clean-up, no request in front
                // of it: a server that cannot be reached to be told is exactly the one this has to
                // work without, or the registration it refuses to give up keeps this installation
                // from ever reaching another server.
                onForceDisconnect = {
                    if (!disconnecting) {
                        disconnecting = true
                        connectScope.launch {
                            syncAccounts.forgetConnection()
                            disconnectFailure = null
                            // As above: with the stored account gone, the empty form says so.
                            selfHostConnection = ServerConnection.Idle
                            accountCreated = false
                            googleFailure = null
                            disconnecting = false
                        }
                    }
                },
            )
        }
    }
}

/**
 * What to say after a move to the cloud, or a restore, that did not simply work.
 *
 * A plain function taking a [android.content.Context] rather than a composable reading
 * [androidx.compose.ui.res.stringResource]: it is called from a coroutine that outlives the screen,
 * which is the whole reason the operation runs where it does.
 *
 * Success returns null. There is nothing to say — the list the person is looking at has already
 * moved the notebook from one section to the other, which is a better report than a sentence.
 */
private fun cloudArchiveMessage(
    context: android.content.Context,
    result: CloudArchiveResult,
    move: Boolean,
): String? = when (result) {
    CloudArchiveResult.Moved, CloudArchiveResult.BroughtBack, CloudArchiveResult.AlreadyDone -> null
    CloudArchiveResult.NoAccount -> context.getString(R.string.closed_notebook_needs_account)
    is CloudArchiveResult.NotUploaded ->
        context.getString(R.string.closed_notebook_not_uploaded, result.pending)
    CloudArchiveResult.UnknownNotebook -> context.getString(R.string.closed_notebook_gone)
    is CloudArchiveResult.Failed -> context.getString(
        if (move) R.string.closed_notebook_failed else R.string.closed_notebook_restore_failed,
    )
}

@Composable
private fun NotesWorkspace(
    viewModel: NotesViewModel,
    attachments: AttachmentStore,
    aiModelStore: AiModelStore,
    recognitionEngine: InkRecognitionEngine,
    mathEngine: MathEngine,
    onOpenAccount: () -> Unit,
    onOpenClosedNotebooks: () -> Unit,
    accountConnected: Boolean,
    serverUnreachable: Boolean,
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
    val imageTextProgress by viewModel.imageTextProgress.collectAsStateWithLifecycle()
    val inkTextProgress by viewModel.inkTextProgress.collectAsStateWithLifecycle()
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
    val inkReadyPageId by viewModel.inkReadyPageId.collectAsStateWithLifecycle()
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
            // No key means the delete was a flush: it held nothing, so nothing was kept and there is
            // nothing to put back. `memory/blankFlushPlan.md`.
            val result = snackbarHostState.showSnackbar(
                message = notice.message,
                actionLabel = notice.key?.let { "Undo" },
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            val key = notice.key
            if (key != null && result == SnackbarResult.ActionPerformed) {
                viewModel.restoreDeletedItem(key)
            }
        }
    }

    // Hoisted into the view model, because a stylus button can change it — see `ui/StylusButtons.kt`.
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    var pendingDialog by remember { mutableStateOf<NameDialog?>(null) }
    var pendingSectionDelete by remember { mutableStateOf<SectionEntity?>(null) }
    /** Null until the count has been read, which is what the dialog's vaguer wording covers. */
    var pendingSectionContents by remember { mutableStateOf<SectionContents?>(null) }
    LaunchedEffect(pendingSectionDelete) {
        pendingSectionContents = pendingSectionDelete?.let { viewModel.sectionContents(it.id) }
    }
    var pendingNotebookDelete by remember { mutableStateOf<NotebookEntity?>(null) }
    var pendingNotebookClose by remember { mutableStateOf<NotebookEntity?>(null) }
    /** As above: null until the counts have been read. */
    var pendingNotebookContents by remember { mutableStateOf<NotebookContents?>(null) }
    // One slot for both confirmations, because only one of them can ever be open: both are reached
    // from the ribbon, and an `AlertDialog` is modal, so the row that would set the other is not
    // reachable while either is up.
    LaunchedEffect(pendingNotebookDelete, pendingNotebookClose) {
        val subject = pendingNotebookDelete ?: pendingNotebookClose
        pendingNotebookContents = subject?.let { viewModel.notebookContents(it.id) }
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
        // The rules the selection holds travel with the ink — see `renderInkSelection`. Read here
        // with the strokes so both are the page as it was when Math was pressed.
        val selectedShapes = state.shapes
        recognitionScope.launch {
            var bitmap: android.graphics.Bitmap? = null
            try {
                val rendered = withContext(Dispatchers.Default) {
                    renderInkSelection(selectedStrokes, selectedShapes, selection)
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
            setLinkPreviews = viewModel::setLinkPreviews,
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
    val fileActions = remember(viewModel, exportFileName, currentNotebook, onOpenClosedNotebooks) {
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
            closeNotebook = {
                pendingNotebookClose = currentNotebook
            },
            openClosedNotebooks = onOpenClosedNotebooks,
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
                        onOpenAccount = onOpenAccount,
                        accountConnected = accountConnected,
                        serverUnreachable = serverUnreachable,
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
                                inkReady = inkReadyPageId == state.selectedPageId,
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
                                    imageTextProgress = imageTextProgress,
                                    inkTextProgress = inkTextProgress,
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
                                    imageTextProgress = imageTextProgress,
                                    inkTextProgress = inkTextProgress,
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
                                inkReady = inkReadyPageId == state.selectedPageId,
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
            contents = pendingSectionContents,
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
    pendingNotebookClose?.let { notebook ->
        CloseNotebookDialog(
            notebook = notebook,
            contents = pendingNotebookContents,
            onDismiss = { pendingNotebookClose = null },
            onConfirm = {
                viewModel.closeNotebook(notebook.id)
                pendingNotebookClose = null
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
    /** Hardware pane, a property of the user — `memory/stylusPlan.md` SB3. */
    stylusButtons: StylusButtonMap,
    aiModels: AiModelsState,
    onDownloadFormula: () -> Unit,
    recognition: RecognitionPanelState?,
    formulaTools: FormulaToolsState,
    onRecognitionChange: (String) -> Unit,
    onCopyRecognition: (String) -> Unit,
    onMathAction: (String) -> Unit,
    onCopyMathResult: (String) -> Unit,
    /** Content pane — the query, and what it found across the notebook (`memory/searchPlan.md`). */
    contentSearch: ContentSearchState,
    /** How far reading this notebook's pictures has got — `memory/imageOcrPlan.md` IO6. */
    imageTextProgress: ImageTextProgress,
    inkTextProgress: InkTextProgress,
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
        {
            ContentPanelHeader(
                state = contentSearch,
                onQueryChange = onSearchQueryChange,
                imageProgress = imageTextProgress,
            )
        }
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
            ToolPane.AiModels -> {
                val picturesRead by viewModel.picturesRead.collectAsStateWithLifecycle()
                val inkPagesRead by viewModel.pagesWithHandwritingText.collectAsStateWithLifecycle()
                AiModelsPanelContent(
                    state = aiModels,
                    onDownloadFormula = onDownloadFormula,
                    pictureText = imageTextProgress,
                    picturesRead = picturesRead,
                    onSetPictureText = viewModel::setImageTextEnabled,
                    onRebuildPictureText = viewModel::rebuildImageText,
                    inkText = inkTextProgress,
                    inkPagesRead = inkPagesRead,
                    onSetInkText = viewModel::setInkTextEnabled,
                    onRebuildInkText = viewModel::rebuildInkText,
                )
            }
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
    inkReady: Boolean,
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
                inkReady = inkReady,
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
 * The magnifier floating at the canvas's top-right — feature C7, `memory/searchPlan.md` CS10.
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
    inkReady: Boolean,
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
        inkReady = inkReady,
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
        // No settings to pass with it: the drag is the whole of what Insert Space knows — E2.
        insertingSpace = tool == DrawTool.InsertSpace,
        onInsertSpace = viewModel::insertSpace,
        ruler = ruler.takeIf { rulerOut },
        tables = state.tables,
        // One tool places a table on the next tap; which *kind* rides in on the settings, so the
        // canvas never learns there are two — `memory/tablePlan.md` TA15.
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
        onMoveShapeEnd = viewModel::moveShapeEnd,
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
        // Read off the pen in hand, not off a shared switch: this is a per-pen setting (ID5), so a
        // fine pen kept for handwriting and a thick one kept for ruling can answer differently.
        straightenOnHold = (tool as? DrawTool.Pen)
            ?.let { pens.getOrNull(it.index)?.holdForStraightLine } == true,
        onStraightenStroke = { startX, startY, endX, endY ->
            viewModel.straightenStrokeToLine(startX, startY, endX, endY)
        },
        onInsertShape = viewModel::insertShape,
        onPartialErase = viewModel::eraseStrokeParts,
        onObjectErase = viewModel::eraseStrokeObjects,
        onMoveSelection = viewModel::moveInk,
        onResizeSelection = viewModel::resizeInk,
        onDeleteInkSelection = viewModel::deleteInkSelection,
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
 * available from the app-wide Deleted Items pane — unless the section holds nothing at all, which
 * is deleted outright and has to say so instead. `memory/blankFlushPlan.md`.
 */
@Composable
private fun DeleteSectionDialog(
    section: SectionEntity,
    contents: SectionContents?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${section.name}?") },
        text = {
            Text(
                if (contents?.blank == true) {
                    NOTHING_TO_KEEP
                } else {
                    buildString {
                        append(
                            when (contents?.pages) {
                                null, 0 -> "This section will be removed."
                                1 -> "Its 1 page will go with it."
                                else -> "Its ${contents.pages} pages will go with it."
                            },
                        )
                        append(" You can restore it later from Deleted Items.")
                    }
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
                if (contents?.blank == true) {
                    NOTHING_TO_KEEP
                } else {
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
                    }
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
 * What both delete confirmations say instead when there is nothing in the thing being deleted.
 *
 * A blank notebook or section is flushed rather than tombstoned, so the usual promise of Deleted
 * Items would be a lie — and it is the last sentence somebody reads before pressing Delete, which
 * makes it the only place the difference can still change their mind. It replaces the count as well
 * as the promise: "its 2 pages will go with it" is true and useless when both pages are empty.
 *
 * A notebook whose contents have not been read yet keeps the ordinary wording, because a delete that
 * turns out to be recoverable after a dialog said nothing about recovery disappoints nobody.
 * `memory/blankFlushPlan.md`.
 */
private const val NOTHING_TO_KEEP =
    "There is nothing in it, so it will be deleted for good rather than kept in Deleted Items."

/**
 * Confirms closing the notebook the ribbon's File tab is pointed at.
 *
 * A confirmation for something that deletes nothing looks like ceremony, and is not. Closing is
 * reached from a toolbar rather than from the notebook's own row, sits two buttons from Delete
 * Notebook in a row that scrolls under the finger, and its whole effect is that a notebook *stops
 * being on screen* — which is also what deleting looks like from the rail. Somebody who meant one
 * and got the other has no way to tell which they got except by finding out where it went. So the
 * title names the notebook, which is the only place the ribbon's aim can be checked, and the body
 * says the two things that separate this from the button beside it: nothing is deleted, and here is
 * where it went.
 *
 * The counts are the same ones [DeleteNotebookDialog] reads, and they are doing a different job
 * here — not "this is how much you are about to lose" but "this is how much is going with it", so
 * the notebook can be recognised by its size when it is looked for again.
 */
@Composable
private fun CloseNotebookDialog(
    notebook: NotebookEntity,
    contents: NotebookContents?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close ${notebook.name}?") },
        text = {
            Text(
                buildString {
                    append("Nothing is deleted. ")
                    if (contents != null && (contents.sections > 0 || contents.pages > 0)) {
                        append(
                            "Its ${countOf(contents.sections, "section")} and " +
                                "${countOf(contents.pages, "page")} stay where they are, and the ",
                        )
                    } else {
                        append("The ")
                    }
                    append("notebook leaves the panel until you open it again from Closed Notebooks.")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** "1 section" / "3 sections" — the counts in a delete confirmation are read, so they are spelled. */
private fun countOf(value: Int, noun: String): String =
    if (value == 1) "1 $noun" else "$value ${noun}s"
