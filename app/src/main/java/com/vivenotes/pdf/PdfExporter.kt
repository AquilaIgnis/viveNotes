package com.vivenotes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.InkPageLoader
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import com.vivenotes.data.db.PageEntity
import com.vivenotes.ink.PageStroke
import com.vivenotes.model.Orientation
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins
import com.vivenotes.model.migrated
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Whether the export covers the open page or every page of its section. */
enum class PdfExportScope { Page, Section }

/**
 * Everything the dialog can change — `memory/pdfExportPlan.md` PD1.
 *
 * [fitContent] defaults on, which is the option the reference drawing is mostly about: content that
 * straddles a page boundary is pulled back onto one page rather than cut in half. [includeRuling]
 * defaults on because the ruling lives in the document, not in preferences — a page written on
 * squared paper *is* squared paper — and one tap says otherwise.
 */
data class PdfExportOptions(
    val scope: PdfExportScope = PdfExportScope.Page,
    val paper: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.Portrait,
    val fitContent: Boolean = true,
    val includeRuling: Boolean = true,
)

/** What the export covers, resolved to page ids before anything is measured. */
data class PdfExportRequest(
    val pageId: String?,
    val sectionId: String?,
    val options: PdfExportOptions,
    /** The page's title or the section's name, whichever the scope names. Only the file name uses it. */
    val name: String? = null,
)

/** One note page's contribution: the sheets it was cut into, and how to find it again. */
class PlannedPage(
    val pageId: String,
    val title: String,
    val plan: PdfPagePlan,
)

/** Which sheet of which page, in the order they are written. */
data class PdfSheetRef(val pageIndex: Int, val tileIndex: Int)

/**
 * A complete export, planned but not drawn.
 *
 * **Deliberately holds no page content.** A section's documents and — far more to the point — its
 * ink meshes are native-backed and there can be a great many of them; keeping fifty pages of them
 * alive to render one preview is how an export runs a tablet out of memory. What is kept is the
 * tiling, which is a handful of rectangles per page, and the exporter loads a page again when it is
 * actually asked to draw it. [PdfExporter] caches the last page it measured, so walking the sheets
 * in order — which is what both the preview and the write do — measures each page once.
 */
class PdfExportPlan(
    val options: PdfExportOptions,
    val paper: PdfPaper,
    val pages: List<PlannedPage>,
    val sheets: List<PdfSheetRef>,
    /** What the file should be called, before the user renames it in the picker. */
    val suggestedName: String,
) {
    val sheetCount: Int get() = sheets.size
}

/**
 * Turns pages into a PDF — `memory/pdfExportPlan.md`.
 *
 * The public shape is three steps, and they are separate because the dialog needs them separately:
 * [plan] answers "how many sheets, and where do they cut", [preview] draws one of them into a
 * bitmap, and [write] draws all of them into a document. All three share one [PageRenderer], so
 * what the preview shows is what the file gets.
 */
class PdfExporter(
    context: Context,
    private val repository: NotesRepository,
    private val attachments: AttachmentStore? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val measurer = PageMeasurer(appContext, attachments)
    private val renderer = PageRenderer(appContext.resources.displayMetrics)
    private val ink = InkPageLoader(repository, dispatcher)

    /**
     * One job at a time.
     *
     * [PageRenderer] holds its `Paint`s and its scratch `Matrix` between calls — that is what keeps a
     * hundred-sheet export from allocating a hundred of each — so two renders at once would paint
     * each other's colours. The dialog can ask for one easily enough: a preview render is still in
     * flight when the file picker comes back and the write begins. This is also what makes [cached]
     * safe to hold without a lock of its own.
     */
    private val mutex = Mutex()

    /** The last page measured with its pictures, so consecutive sheets of it are free. */
    private var cached: RenderTarget? = null

    /** What the dialog opens on: the page's own paper, or A4 where it has chosen none. */
    suspend fun defaultOptionsFor(pageId: String?): PdfExportOptions = withContext(dispatcher) {
        val style = pageId?.let { (repository.loadDoc(it) as? PageLoad.Loaded)?.doc?.style }
            ?: return@withContext PdfExportOptions()
        PdfExportOptions(
            paper = if (style.paper == PaperSize.Auto) PaperSize.A4 else style.paper,
            orientation = style.orientation,
        )
    }

    suspend fun plan(request: PdfExportRequest): PdfExportPlan = withContext(dispatcher) {
        mutex.withLock {
            val sources = sourcesFor(request)
            val paper = paperFor(request.options, sources.firstOrNull()?.style)
            val planned = sources.map { source ->
                coroutineContext.ensureActive()
                val page = measure(source, paper, loadPictures = false)
                PlannedPage(
                    pageId = source.id,
                    title = source.title,
                    plan = PageTiling.plan(
                        items = page.items,
                        tileWidthDp = paper.tileWidthDp,
                        tileHeightDp = paper.tileHeightDp,
                        fit = request.options.fitContent,
                        boundSheet = page.boundSheet,
                    ),
                )
            }
            val name = (request.name ?: sources.firstOrNull()?.title).orEmpty()
            PdfExportPlan(
                options = request.options,
                paper = paper,
                pages = planned,
                sheets = planned.flatMapIndexed { pageIndex: Int, page: PlannedPage ->
                    page.plan.tiles.indices.map { PdfSheetRef(pageIndex, it) }
                },
                suggestedName = "${name.sanitisedForFileName()}.pdf",
            )
        }
    }

    /**
     * One sheet as a bitmap, at most [maxWidthPx] across.
     *
     * The same renderer the file gets, at a different scale — which is the whole reason there is one
     * renderer. Returns null when the sheet index is out of range, which is what a preview asked for
     * a plan that has since been replaced looks like.
     */
    suspend fun preview(plan: PdfExportPlan, sheetIndex: Int, maxWidthPx: Int): Bitmap? =
        withContext(dispatcher) {
            mutex.withLock {
                val ref = plan.sheets.getOrNull(sheetIndex) ?: return@withLock null
                val planned = plan.pages[ref.pageIndex]
                val stored = planned.plan.tiles.getOrNull(ref.tileIndex) ?: return@withLock null
                val target = measureCached(planned.pageId, plan)
                val tile = target.plan.tiles.getOrNull(ref.tileIndex) ?: stored
                val scale = (maxWidthPx / plan.paper.widthDp)
                    .coerceIn(MIN_PREVIEW_SCALE, MAX_PREVIEW_SCALE)
                val width = (plan.paper.widthDp * scale).roundToInt().coerceAtLeast(1)
                val height = (plan.paper.heightDp * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                renderer.drawSheet(
                    canvas = Canvas(bitmap),
                    page = target.page,
                    tile = tile,
                    plan = target.plan,
                    paper = plan.paper,
                    ruling = plan.options.includeRuling,
                    scale = scale,
                )
                bitmap
            }
        }

    /**
     * Writes the whole export to [destination], reporting each sheet as it is finished.
     *
     * The stream is the user's chosen document — a Storage Access Framework grant for exactly that
     * file, which is the same door Export Notebook goes through and needs no storage permission.
     */
    suspend fun write(
        plan: PdfExportPlan,
        destination: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(dispatcher) {
        mutex.withLock {
            val document = PdfDocument()
            try {
                plan.sheets.forEachIndexed { index, ref ->
                    coroutineContext.ensureActive()
                    val planned = plan.pages[ref.pageIndex]
                    val target = measureCached(planned.pageId, plan)
                    val tile = target.plan.tiles.getOrNull(ref.tileIndex)
                        ?: planned.plan.tiles[ref.tileIndex]
                    val info = PdfDocument.PageInfo
                        .Builder(plan.paper.widthPoints, plan.paper.heightPoints, index + 1)
                        .create()
                    val sheet = document.startPage(info)
                    renderer.drawSheet(
                        canvas = sheet.canvas,
                        page = target.page,
                        tile = tile,
                        plan = target.plan,
                        paper = plan.paper,
                        ruling = plan.options.includeRuling,
                        scale = PdfPaper.POINTS_PER_DP,
                    )
                    document.finishPage(sheet)
                    onProgress(index + 1, plan.sheets.size)
                }
                val stream = contentResolver.openOutputStream(destination)
                    ?: throw PdfExportException("Nothing could be written to the chosen file.")
                stream.use(document::writeTo)
            } finally {
                document.close()
                cached = null
            }
            plan.sheets.size
        }
    }

    /** Frees the measured page held for the preview. Called when the dialog closes. */
    fun release() {
        cached = null
    }

    // --- loading -----------------------------------------------------------------------------

    private class SourcePage(
        val id: String,
        val title: String,
        val createdAt: Long,
        val doc: PageDoc,
        val strokes: List<PageStroke>,
    ) {
        val style: PageStyle get() = doc.style
    }

    private suspend fun sourcesFor(request: PdfExportRequest): List<SourcePage> {
        val ids = when (request.options.scope) {
            PdfExportScope.Page -> listOfNotNull(request.pageId)
            PdfExportScope.Section -> request.sectionId
                ?.let { repository.pagesInSection(it).map(PageEntity::id) }
                ?: listOfNotNull(request.pageId)
        }
        return ids.mapNotNull { load(it) }
    }

    private suspend fun load(pageId: String): SourcePage? {
        val entity = repository.pageById(pageId) ?: return null
        // An unreadable page exports as the page it can be read as — empty — rather than failing the
        // whole section. Nothing here writes, so nothing can make the damage worse.
        val doc = (repository.loadDoc(pageId) as? PageLoad.Loaded)?.doc?.migrated() ?: PageDoc()
        return SourcePage(
            id = pageId,
            title = entity.title,
            createdAt = entity.createdAt,
            doc = doc,
            strokes = ink.load(pageId).strokes,
        )
    }

    private suspend fun measure(source: SourcePage, paper: PdfPaper, loadPictures: Boolean) =
        measurer.measure(
            pageId = source.id,
            title = source.title,
            createdAt = source.createdAt,
            doc = source.doc,
            strokes = source.strokes,
            paper = paper,
            loadPictures = loadPictures,
        )

    /**
     * A page measured with its pictures, and the fit worked out against *that* measurement.
     *
     * [options] and [paper] are what it was measured *for*, and they are half of the cache key. A
     * cache keyed on the page alone is a cache that answers a question nobody asked: toggling Fit
     * content re-plans the export and re-renders the preview, and the render would be handed back
     * the page as it was laid out under the old setting — the sheet count changing while the sheets
     * themselves did not.
     */
    private class RenderTarget(
        val page: MeasuredPage,
        val plan: PdfPagePlan,
        val options: PdfExportOptions,
        val paper: PdfPaper,
    )

    /**
     * The page to draw, and where its parts go.
     *
     * **The fit is worked out again here, against the measurement about to be drawn**, rather than
     * carried over from [plan]. A page is measured twice in an export — once to decide how many
     * sheets it takes, and once per page to draw it — and those are two separate loads of the same
     * ink. Carrying a map keyed by anything those two loads might name differently is a trap that
     * fails *silently*: the sheet count is right, the tiles are right, and one formula quietly loses
     * the half of itself that was supposed to be pulled back onto the page.
     *
     * The names are stable now (`ContentGroups.inkAtoms` says how, and why), so the two agree — but
     * they agree because the geometry is the same, which is a much smaller thing to have to trust
     * than a naming scheme. The stored tile is still what decides *which* sheet this is; only the
     * shifts are local.
     */
    private suspend fun measureCached(pageId: String, exportPlan: PdfExportPlan): RenderTarget {
        cached
            ?.takeIf {
                it.page.pageId == pageId &&
                    it.options == exportPlan.options &&
                    it.paper == exportPlan.paper
            }
            ?.let { return it }
        val source = load(pageId) ?: SourcePage(pageId, "", 0L, PageDoc(), emptyList())
        val page = measure(source, exportPlan.paper, loadPictures = true)
        val target = RenderTarget(
            page = page,
            plan = PageTiling.plan(
                items = page.items,
                tileWidthDp = exportPlan.paper.tileWidthDp,
                tileHeightDp = exportPlan.paper.tileHeightDp,
                fit = exportPlan.options.fitContent,
                boundSheet = page.boundSheet,
            ),
            options = exportPlan.options,
            paper = exportPlan.paper,
        )
        cached = target
        return target
    }

    /**
     * The sheet the export goes onto.
     *
     * Margins come from the *document*, never from the dialog: they are the band the writer already
     * sees drawn while the Paper Size pane is open, and this is the first thing in the app that has
     * ever consumed them. A section takes its first page's, because one export is one stack of
     * paper and a stack whose printable area changed halfway down is not one.
     */
    private fun paperFor(options: PdfExportOptions, style: PageStyle?): PdfPaper = PdfPaper.of(
        size = options.paper,
        orientation = options.orientation,
        custom = style?.customPaper,
        margins = style?.margins ?: PrintMargins(),
    )

    private companion object {
        /** Below this a preview is not a preview; above it, it is a second render nobody looks at. */
        const val MIN_PREVIEW_SCALE = 0.2f
        const val MAX_PREVIEW_SCALE = 3f
    }
}

class PdfExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A title is whatever the writer typed, and a file name is not. */
internal fun String.sanitisedForFileName(): String =
    trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-").take(80).ifBlank { "Notes" }
