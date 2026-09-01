package com.vivenotes.ui.editor

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vivenotes.model.Orientation
import com.vivenotes.model.PaperSize
import com.vivenotes.pdf.PdfExportOptions
import com.vivenotes.pdf.PdfExportPlan
import com.vivenotes.pdf.PdfExportRequest
import com.vivenotes.pdf.PdfExportScope
import com.vivenotes.pdf.PdfExporter
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.panel.PanelChoice
import com.vivenotes.ui.panel.PanelRow
import com.vivenotes.ui.panel.PanelSection
import com.vivenotes.ui.panel.PanelSetting
import com.vivenotes.ui.panel.PanelToggle
import kotlinx.coroutines.launch

internal object ExportPdfTags {
    const val DIALOG = "export-pdf-dialog"
    const val SHEETS = "export-pdf-sheets"
    const val EXPORT = "export-pdf-export"
    const val CLOSE = "export-pdf-close"
    const val MESSAGE = "export-pdf-message"

    /** One sheet in the scrolling stack. */
    fun sheet(index: Int): String = "export-pdf-sheet-$index"
}

/**
 * Export as PDF — `memory/pdfExportPlan.md` PD1.
 *
 * **A window rather than a docked pane, and the difference is the preview.** Everything in the
 * right-hand [com.vivenotes.ui.panel.ToolPane] is something kept open while working on the page
 * beside it; this is a decision taken once, and the only way to take it well is to see the sheets
 * the canvas has been cut into. So it takes the screen, and on anything wide enough the options sit
 * beside the sheet they are changing rather than under it — every one of them changes what the
 * preview shows, and a control whose effect is off screen is a control you have to guess at.
 *
 * The options themselves are the pane's own widgets ([PanelSection], [PanelRow], [PanelChoice],
 * [PanelToggle]), so a drop-down here is the same drop-down as the one in Paper Size.
 *
 * Nothing is written until the file picker comes back: the export is a Storage Access Framework
 * document the user chooses, the same door Export Notebook goes through, and it needs no storage
 * permission because choosing the file *is* the grant.
 *
 * The opt-in is for [LoadingIndicator], which is the one part of M3 Expressive still gated in
 * 1.5.0-alpha25 — the same one `AccountScreen` takes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExportPdfDialog(
    exporter: PdfExporter,
    pageId: String?,
    sectionId: String?,
    pageTitle: String,
    sectionName: String,
    onDismiss: () -> Unit,
) {
    var options by remember { mutableStateOf(PdfExportOptions()) }
    /** False until the page's own paper has been read, so nothing is planned against a guess. */
    var opened by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<PdfExportPlan?>(null) }
    var planning by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val name = if (options.scope == PdfExportScope.Section) sectionName else pageTitle

    // The page's own paper and orientation, so an A4 page opens on A4 rather than on a guess. Only
    // once, and only before the user has touched anything: re-reading it after a change would undo
    // the change.
    LaunchedEffect(pageId) {
        options = exporter.defaultOptionsFor(pageId)
        opened = true
    }

    LaunchedEffect(pageId, sectionId, options, opened) {
        if (!opened) return@LaunchedEffect
        planning = true
        message = null
        val next = runCatching {
            exporter.plan(PdfExportRequest(pageId, sectionId, options, name))
        }
        planning = false
        next.onSuccess {
            plan = it
        }.onFailure {
            plan = null
            message = it.message ?: "This page could not be laid out for printing."
        }
    }

    // The measured page the preview is holding is a page of decoded pictures and native ink; it has
    // no business outliving the window that asked for it.
    DisposableEffect(Unit) {
        onDispose { exporter.release() }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
        val current = plan
        if (uri == null || current == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val written = runCatching { exporter.write(current, uri) }
            busy = false
            written
                .onSuccess { onDismiss() }
                .onFailure { message = it.message ?: "The PDF could not be written." }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ExportPdfTags.DIALOG),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                ExportPdfBar(
                    sheetCount = plan?.sheetCount ?: 0,
                    ready = plan != null && !planning && !busy,
                    busy = busy,
                    onExport = { plan?.let { save.launch(it.suggestedName) } },
                    onClose = onDismiss,
                )
                HorizontalDivider()

                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag(ExportPdfTags.MESSAGE),
                    )
                }

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val sideBySide = maxWidth >= SIDE_BY_SIDE_BREAKPOINT
                    val sheetPane: @Composable (Modifier) -> Unit = { modifier ->
                        SheetStack(
                            exporter = exporter,
                            plan = plan,
                            planning = planning,
                            modifier = modifier,
                        )
                    }
                    val optionsPane: @Composable (Modifier) -> Unit = { modifier ->
                        ExportPdfOptions(
                            options = options,
                            sectionAvailable = sectionId != null,
                            onChange = { options = it },
                            modifier = modifier,
                        )
                    }
                    if (sideBySide) {
                        Row(Modifier.fillMaxSize()) {
                            sheetPane(Modifier.weight(1f).fillMaxHeight())
                            VerticalDivider()
                            optionsPane(Modifier.width(OPTIONS_WIDTH).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            sheetPane(Modifier.weight(1f).fillMaxWidth())
                            HorizontalDivider()
                            optionsPane(Modifier.fillMaxWidth().height(OPTIONS_HEIGHT))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExportPdfBar(
    sheetCount: Int,
    ready: Boolean,
    busy: Boolean,
    onExport: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag(ExportPdfTags.CLOSE)) {
            Icon(MaterialSymbols.Close, contentDescription = "Close")
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text("Export as PDF", style = MaterialTheme.typography.titleMedium)
            if (sheetCount > 0) {
                Text(
                    text = if (sheetCount == 1) "1 sheet" else "$sheetCount sheets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (busy) {
            LoadingIndicator(Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Button(
            onClick = onExport,
            enabled = ready && sheetCount > 0,
            modifier = Modifier.testTag(ExportPdfTags.EXPORT),
        ) {
            Text("Export")
        }
    }
}

/**
 * Every sheet of the export, stacked down the pane and scrolled — which is how a stack of paper
 * reads, and how every PDF viewer the user has ever opened shows one.
 *
 * A turner was the first version of this and it was the wrong shape: it made the second sheet a
 * thing you had to *discover*, when the whole reason this window exists is to show at a glance what
 * the canvas was cut into.
 *
 * Lazily, and that is not an optimisation: a sheet is a full render of a page, and a section can be
 * a hundred of them. [LazyColumn] composes what is on screen and disposes what is not, so a hundred
 * sheets cost what two do — and because the sheets of one page are contiguous, the exporter's
 * one-page measurement cache is hit by every one of them but the first.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SheetStack(
    exporter: PdfExporter,
    plan: PdfExportPlan?,
    planning: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (plan == null || plan.sheetCount == 0) {
            if (planning) {
                LoadingIndicator()
            } else {
                Text(
                    text = "Nothing to preview.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@BoxWithConstraints
        }

        // **A whole sheet, not a wide one.** Filling the pane's width makes a portrait page three
        // screens tall, so the thing this window exists to show — what one sheet came out like —
        // is the one thing you cannot see. The height is what is scarce, so the height is what is
        // fitted, and the width follows the paper's own ratio.
        val ratio = plan.paper.widthDp / plan.paper.heightDp
        val roomWide = (maxWidth - PREVIEW_PADDING * 2).coerceAtLeast(MIN_SHEET_WIDTH)
        val roomTall = (maxHeight - PREVIEW_PADDING * 2 - CAPTION_HEIGHT)
            .coerceAtLeast(MIN_SHEET_WIDTH / ratio)
        val sheetHeight = minOf(roomTall, roomWide / ratio)
        val sheetWidth = sheetHeight * ratio
        val widthPx = with(LocalDensity.current) { sheetWidth.roundToPx() }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ExportPdfTags.SHEETS),
            contentPadding = PaddingValues(PREVIEW_PADDING),
            verticalArrangement = Arrangement.spacedBy(PREVIEW_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(plan.sheetCount) { index ->
                Sheet(
                    exporter = exporter,
                    plan = plan,
                    index = index,
                    width = sheetWidth,
                    height = sheetHeight,
                    widthPx = widthPx,
                )
            }
        }
    }
}

/**
 * One sheet, rendered when it scrolls into view.
 *
 * The box takes its size before anything is drawn into it, so the stack has its full height from the
 * first frame and scrolling does not shuffle the sheets under the finger as each render arrives.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Sheet(
    exporter: PdfExporter,
    plan: PdfExportPlan,
    index: Int,
    width: Dp,
    height: Dp,
    widthPx: Int,
) {
    var bitmap by remember(plan, index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(plan, index, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        bitmap = runCatching { exporter.preview(plan, index, widthPx) }.getOrNull()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width, height)
                .background(SHEET_PAPER)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .testTag(ExportPdfTags.sheet(index)),
            contentAlignment = Alignment.Center,
        ) {
            val sheet = bitmap
            if (sheet == null) {
                LoadingIndicator()
            } else {
                Image(
                    bitmap = sheet.asImageBitmap(),
                    contentDescription = "Sheet ${index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${index + 1} / ${plan.sheetCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportPdfOptions(
    options: PdfExportOptions,
    sectionAvailable: Boolean,
    onChange: (PdfExportOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        PanelSection("What to export") {
            PanelRow("Range") {
                PanelChoice(
                    field = "Range",
                    current = options.scope,
                    options = if (sectionAvailable) {
                        PdfExportScope.entries
                    } else {
                        listOf(PdfExportScope.Page)
                    },
                    label = { scope ->
                        when (scope) {
                            PdfExportScope.Page -> "This page"
                            PdfExportScope.Section -> "Whole section"
                        }
                    },
                    onPick = { onChange(options.copy(scope = it)) },
                )
            }
        }

        PanelSection("Paper") {
            PanelRow("Size") {
                PanelChoice(
                    field = "Size",
                    current = options.paper,
                    // Auto has no size to cut to, and Custom's dimensions belong to a page rather
                    // than to an export — both are set in the View tab's Paper Size pane, and what
                    // a page chooses there is what this opens on.
                    options = EXPORT_PAPER_SIZES,
                    label = { it.name },
                    onPick = { onChange(options.copy(paper = it)) },
                )
            }
            PanelRow("Orientation") {
                PanelChoice(
                    field = "Orientation",
                    current = options.orientation,
                    options = Orientation.entries,
                    label = { it.name },
                    onPick = { onChange(options.copy(orientation = it)) },
                )
            }
        }

        PanelSection("Layout") {
            PanelSetting(
                label = "Fit content to pages",
                info = "Content that would be cut by a page edge is moved back onto the page it " +
                    "starts on. Anything larger than a page is left where it is.",
            ) {
                PanelToggle(
                    field = "Fit content to pages",
                    checked = options.fitContent,
                    onCheckedChange = { onChange(options.copy(fitContent = it)) },
                )
            }
            PanelSetting(
                label = "Page ruling",
                info = "Prints the lines, squares or dots the page is written on.",
            ) {
                PanelToggle(
                    field = "Page ruling",
                    checked = options.includeRuling,
                    onCheckedChange = { onChange(options.copy(includeRuling = it)) },
                )
            }
        }
    }
}

/**
 * The sizes an export can be cut to.
 *
 * Auto and Custom are left out because they mean "ask the page" rather than naming a sheet — see
 * the call site. The JIS B sheets and Billfold are left out because no printer this export is
 * aimed at is loaded with them; a page written on one still exports, through the size its own
 * View tab gives it or through a sheet chosen here.
 */
private val EXPORT_PAPER_SIZES = PaperSize.entries.filter {
    it !in setOf(
        PaperSize.Auto,
        PaperSize.Custom,
        PaperSize.B4,
        PaperSize.B5,
        PaperSize.B6,
        PaperSize.Billfold,
    )
}

private const val PDF_MIME = "application/pdf"

/** Wide enough for a portrait sheet and a pane of controls to sit side by side. */
private val SIDE_BY_SIDE_BREAKPOINT = 720.dp
private val OPTIONS_WIDTH = 320.dp
private val OPTIONS_HEIGHT = 260.dp

/** The margin around the stack and the space between sheets. */
private val PREVIEW_PADDING = 20.dp

/** Room kept under each sheet for its "3 / 12", so the sheet above it still fits whole. */
private val CAPTION_HEIGHT = 30.dp

/** A floor, for a pane too small to show a sheet properly. Scrolling beats a postage stamp. */
private val MIN_SHEET_WIDTH = 240.dp

/**
 * What an unrendered sheet is: paper.
 *
 * A fixed white rather than a theme colour, because the sheet under it is white — the export paints
 * the *light* canvas whatever the app's own theme is (`PdfCanvasColors`), so a placeholder that
 * followed the theme would flash dark and then turn white as the render landed.
 */
private val SHEET_PAPER = Color.White
