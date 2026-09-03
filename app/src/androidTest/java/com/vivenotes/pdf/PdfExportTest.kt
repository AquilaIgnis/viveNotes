package com.vivenotes.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.InkPageLoader
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import com.vivenotes.data.PenPreset
import com.vivenotes.data.forCanvasTheme
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.InkCodec
import com.vivenotes.model.Block
import com.vivenotes.model.Orientation
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
import com.vivenotes.model.Run
import com.vivenotes.model.newId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Export as PDF, end to end — `memory/pdfExportPlan.md` PD10.
 *
 * The tiling and the fit are pinned by JVM tests (`PageTilingTest`); what needs a device is
 * everything they hand off to. A `PageStroke` is a native mesh, a `StaticLayout` needs a real
 * `Paint`, and `PdfDocument` is a platform encoder — so the only honest check that this produces a
 * *document* is to write one and open it with the platform's own reader.
 */
@RunWith(AndroidJUnit4::class)
class PdfExportTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var exporter: PdfExporter
    private lateinit var dbFile: File
    private lateinit var out: File
    private lateinit var sectionId: String
    private lateinit var pageId: String

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = runBlocking<Unit> {
        dbFile = File(context.cacheDir, "pdf-export.db")
        listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
        out = File(context.cacheDir, "pdf-export.pdf").apply { delete() }
        db = Room.databaseBuilder(context, NotesDatabase::class.java, dbFile.absolutePath).build()
        repository = NotesRepository(db)
        exporter = PdfExporter(context, repository)
        val notebookId = repository.createNotebook("Notebook")
        sectionId = repository.createSection(notebookId, "Section")
        pageId = repository.createPage(sectionId, "First page")
        repository.saveDoc(pageId, docWithText("The quick brown fox.", y = 120f))
        repository.addStroke(inkAt("stroke-1", 40f, 400f))
    }

    @After
    fun tearDown() {
        db.close()
        listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
        out.delete()
    }

    /** One page of writing is one sheet, and it is a PDF the platform can open. */
    @Test
    fun onePageExportsToOneReadableSheet() = runBlocking {
        val plan = exporter.plan(PdfExportRequest(pageId, sectionId, PdfExportOptions()))
        assertEquals(1, plan.sheetCount)
        assertEquals("First page.pdf", plan.suggestedName)

        assertEquals(1, write(plan))
        openPdf { renderer ->
            assertEquals(1, renderer.pageCount)
            renderer.openPage(0).use { page ->
                // A4 at 72 points to the inch, which is the size every other reader will report.
                assertEquals(595, page.width)
                assertEquals(842, page.height)
            }
        }
    }

    /**
     * The reference drawing's whole subject: content spread across the canvas comes out as several
     * sheets, in the order the drawing gives — down a column, then right.
     */
    @Test
    fun aSpreadOutCanvasIsCutIntoSeveralSheets() = runBlocking {
        repository.saveDoc(
            pageId,
            PageDoc(
                outlines = listOf(
                    textOutline("near the origin", x = 0f, y = 120f),
                    textOutline("far down the page", x = 0f, y = 2600f),
                    textOutline("off to the right", x = 1400f, y = 120f),
                ),
            ),
        )
        val plan = exporter.plan(PdfExportRequest(pageId, sectionId, PdfExportOptions()))
        assertEquals(3, plan.sheetCount)
        val tiles = plan.pages.single().plan.tiles
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0), tiles.map { it.column to it.row })
        assertEquals(3, write(plan))
    }

    /** A section is its pages in order, concatenated. */
    @Test
    fun aSectionExportsEveryPage() = runBlocking {
        val second = repository.createPage(sectionId, "Second page")
        repository.saveDoc(second, docWithText("Another page.", y = 120f))

        val plan = exporter.plan(
            PdfExportRequest(
                pageId = pageId,
                sectionId = sectionId,
                options = PdfExportOptions(scope = PdfExportScope.Section),
                name = "Section",
            ),
        )
        assertEquals(2, plan.pages.size)
        assertEquals(2, plan.sheetCount)
        assertEquals("Section.pdf", plan.suggestedName)
        assertEquals(2, write(plan))
    }

    /**
     * A page already bound to the paper it is exported onto is one sheet at its own origin — PD3's
     * exception, and the case where the writer's own layout must not be re-anchored.
     */
    @Test
    fun aBoundPageKeepsItsOwnLayout() = runBlocking {
        repository.saveDoc(
            pageId,
            PageDoc(
                outlines = listOf(textOutline("on an A4 sheet", x = 200f, y = 300f)),
                style = PageStyle(paper = PaperSize.A4),
            ),
        )
        val plan = exporter.plan(PdfExportRequest(pageId, sectionId, PdfExportOptions()))
        assertEquals(1, plan.sheetCount)
        assertTrue(plan.pages.single().plan.bound)
        assertEquals(0f, plan.pages.single().plan.tiles.single().area.left, 0.01f)
    }

    /**
     * A page names its parts the same way every time it is measured.
     *
     * **This is a regression test with a scar.** A plan is made against one measurement of a page
     * and drawn against the next, so a name that changes between them takes its group's shift with
     * it — and the failure is silent: the right number of sheets, the right tiles, and one formula
     * missing the half the fit was supposed to pull back onto the page. It was
     * `PageStroke.projection` in a group id, which that class documents as process-local and never
     * stored.
     */
    @Test
    fun measuringAPageTwiceNamesItsGroupsTheSame() = runBlocking {
        val paper = PdfPaper.of(PaperSize.A4, Orientation.Portrait)
        val measurer = PageMeasurer(context)
        val doc = (repository.loadDoc(pageId) as PageLoad.Loaded).doc

        fun ids(strokes: List<com.vivenotes.ink.PageStroke>) = runBlocking {
            measurer.measure(
                pageId = pageId,
                title = "First page",
                createdAt = 0L,
                doc = doc,
                strokes = strokes,
                paper = paper,
                loadPictures = false,
            ).groups.map { it.id }.sorted()
        }

        val loader = InkPageLoader(repository)
        val first = ids(loader.load(pageId).strokes)
        val second = ids(loader.load(pageId).strokes)
        assertTrue("the page has ink to name", first.isNotEmpty())
        assertEquals(first, second)
    }

    /** The preview and the file come off one renderer, so a rendered sheet has ink on it. */
    @Test
    fun aPreviewRendersTheSheet() = runBlocking {
        val plan = exporter.plan(PdfExportRequest(pageId, sectionId, PdfExportOptions()))
        val bitmap = requireNotNull(exporter.preview(plan, sheetIndex = 0, maxWidthPx = 600))
        assertEquals(600, bitmap.width)
        // Something was drawn: a sheet of nothing but background is one colour, and this one is not.
        val corner = bitmap.getPixel(2, 2)
        assertEquals(Color.WHITE, corner)
        val marked = (0 until bitmap.width step 3).any { x ->
            (0 until bitmap.height step 3).any { y -> bitmap.getPixel(x, y) != corner }
        }
        assertTrue("the preview drew nothing but its background", marked)
    }

    /**
     * Automatic ink captured on a dark canvas has white in its brush, but white is not its intent.
     * The flag must make the PDF renderer resolve it against its light paper and paint it black.
     */
    @Test
    fun automaticWhiteInkFromADarkCanvasExportsBlack() = runBlocking {
        val automaticPage = repository.createPage(sectionId, "Automatic ink")
        repository.saveDoc(automaticPage, PageDoc(style = PageStyle(hideTitle = true)))
        // Wide enough to leave fully covered pixels when the 160-dp page is rasterized at PDF's
        // 72 points per inch; the default 1.5 dp pen is intentionally sub-pixel at this scale.
        val darkCanvasPen = PenPreset.starting(0)
            .copy(thickness = 8f)
            .forCanvasTheme(isDark = true)
        repository.addStroke(
            inkAt(
                id = "automatic-white",
                x = 40f,
                y = 80f,
                targetPageId = automaticPage,
                pen = darkCanvasPen,
            ),
        )

        val plan = exporter.plan(
            PdfExportRequest(automaticPage, sectionId, PdfExportOptions()),
        )
        write(plan)

        openPdf { renderer ->
            renderer.openPage(0).use { page ->
                val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                rendered.eraseColor(Color.WHITE)
                page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val containsBlackInk = (0 until rendered.width).any { x ->
                    (0 until rendered.height).any { y ->
                        val pixel = rendered.getPixel(x, y)
                        Color.red(pixel) < 64 && Color.green(pixel) < 64 && Color.blue(pixel) < 64
                    }
                }
                assertTrue("automatic white ink disappeared on PDF paper", containsBlackInk)
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private suspend fun write(plan: PdfExportPlan): Int =
        exporter.write(plan, Uri.fromFile(out)).also { assertTrue(out.length() > 0L) }

    private fun openPdf(block: (PdfRenderer) -> Unit) {
        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use(block)
        }
    }

    private fun docWithText(text: String, y: Float) = PageDoc(outlines = listOf(textOutline(text, 0f, y)))

    private fun textOutline(text: String, x: Float, y: Float) = Outline.Text(
        id = newId(),
        x = x,
        y = y,
        blocks = listOf(Block(id = newId(), runs = listOf(Run(text)))),
    )

    private fun inkAt(
        id: String,
        x: Float,
        y: Float,
        targetPageId: String = pageId,
        pen: PenPreset = PEN,
    ) = InkCodec.encode(
        stroke = Stroke(
            brush = InkCodec.brushFor(pen),
            inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.UNKNOWN, x, y, 0L)
                add(InputToolType.UNKNOWN, x + 40f, y + 30f, 8L)
                add(InputToolType.UNKNOWN, x + 80f, y, 16L)
            }.toImmutable(),
        ),
        pageId = targetPageId,
        seq = 0,
        pen = pen,
        now = 1L,
    ).copy(id = id)

    private companion object {
        val PEN = PenPreset(colorArgb = 0xFF111111.toInt(), colorFollowsTheme = false)
    }
}
