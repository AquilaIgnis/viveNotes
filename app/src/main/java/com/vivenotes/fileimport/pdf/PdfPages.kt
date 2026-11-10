package com.vivenotes.fileimport.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.ParcelFileDescriptor
import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.ImportedDocument
import com.vivenotes.fileimport.ImportedPageSize
import com.vivenotes.fileimport.docx.WordDocument
import com.vivenotes.fileimport.docx.WordObjects
import com.vivenotes.fileimport.docx.WordSettings
import com.vivenotes.model.Mark
import com.vivenotes.model.PageStyle
import com.vivenotes.model.normalizedLinkUrl
import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A PDF, held open for the import window.
 *
 * Android renders PDFs and will say what text is where, which between them cover everything here:
 * the page pictures are the renderer's own, and the objects are worked out from the text's positions
 * and the rendered pixels. Used from one coroutine at a time, as the renderer requires.
 */
internal class PdfPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    override val pages: List<ImportedPageSize>,
) : ImportedDocument {

    /** Found once, on the first insert: it takes every page, and most windows are only looked at. */
    private var furniture: PdfFurniture? = null

    override fun render(index: Int, widthPx: Int): Bitmap? {
        if (index !in pages.indices) return null
        renderer.openPage(index).use { page ->
            val scale = widthPx.toFloat() / page.width
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            return drawn(page, widthPx.coerceAtLeast(1), height, Matrix().apply { setScale(scale, scale) })
        }
    }

    /** A blank page's pixels are transparent, and what a page is drawn on is white paper. */
    private fun drawn(page: PdfRenderer.Page, width: Int, height: Int, matrix: Matrix): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    override suspend fun items(pages: List<Int>, marks: Set<Mark>): List<CanvasPasteItem> {
        val chosen = pages.sorted().filter { it in this.pages.indices }
        val found = furnitureOf()
        val figures = HashMap<String, Pair<Int, PdfRect>>()
        val inputs = chosen.map { index ->
            coroutineContext.ensureActive()
            readPage(index, found, figures)
        }
        val bodyEm = bodyEmOf(inputs)
        val pieces = joinPages(
            inputs.map { input ->
                coroutineContext.ensureActive()
                layoutPage(input, bodyEm)
            },
        )
        val document = WordDocument(emptyList(), emptyMap(), emptyMap(), WordSettings())
        return WordObjects(document, marks) { part -> figures[part]?.let { (page, box) -> figureBytes(page, box) } }.items(pieces)
    }

    /** A sample spread through the file: what repeats on every page shows up in any of them. */
    private fun furnitureOf(): PdfFurniture = furniture ?: PdfFurniture.find(
        (0 until pages.size).toList().sample(FURNITURE_SAMPLE_PAGES).map { index ->
            renderer.openPage(index).use { page -> wholeLines(readGlyphs(page)) to page.height.toFloat() }
        },
    ).also { furniture = it }

    private fun List<Int>.sample(count: Int): List<Int> =
        if (size <= count) this else List(count) { this[it * size / count] }

    /**
     * Every character on the page and where it is.
     *
     * The text and the selection that locates it do not count alike: a hyphen ending a line is
     * followed by a line end in one and not in the other, so an index good on the first line of a
     * page is two characters out by the second hyphenated one. What they share is the order of the
     * printing characters, so the two are walked together and only those are matched up.
     */
    private fun readGlyphs(page: PdfRenderer.Page): List<PdfGlyph> {
        val text = page.textContents.joinToString("") { it.text }
        val glyphs = ArrayList<PdfGlyph>(text.length)
        var position = 0
        fun copySpaces() {
            while (position < text.length && (text[position].isWhitespace() || text[position].isISOControl())) {
                glyphs += PdfGlyph(text[position], null)
                position++
            }
        }
        for (index in text.indices) {
            if (position >= text.length) break
            val found = page.selectContent(SelectionBoundary(index), SelectionBoundary(index + 1))
                ?.selectedTextContents
                ?.firstOrNull { it.bounds.isNotEmpty() && it.text.isNotEmpty() }
                ?: continue
            val char = found.text.first()
            // Some producers draw their spaces, and those are already covered by copying the text's.
            if (char.isWhitespace()) continue
            copySpaces()
            if (position >= text.length) break
            // A character one side has and the other lacks would throw off every space after it.
            val match = (position until min(position + RESYNC_WINDOW, text.length)).firstOrNull { text[it] == char }
            if (match != null) position = match
            glyphs += PdfGlyph(char, rectOf(found.bounds.first()))
            position++
        }
        copySpaces()
        return glyphs
    }

    private fun rectOf(rect: RectF): PdfRect = PdfRect(rect.left, rect.top, rect.right, rect.bottom)

    private fun readPage(index: Int, found: PdfFurniture, figures: MutableMap<String, Pair<Int, PdfRect>>): PdfPageInput =
        renderer.openPage(index).use { page ->
            val width = page.width.toFloat()
            val height = page.height.toFloat()
            val glyphs = readGlyphs(page)
            val everyLine = readLines(glyphs)
            val lines = readLines(glyphs) { line, box -> found.matches(line, box, height) }
                .filterNot { it.rotated && isInSideMargin(it.box, width) }
            val links = page.linkContents.mapNotNull { link ->
                normalizedLinkUrl(link.uri.toString())?.let { url -> url to link.bounds.map(::rectOf) }
            }
            lines.forEach { line ->
                line.words.forEach { word ->
                    word.link = links.firstOrNull { (_, boxes) -> boxes.any { it.expanded(2f).containsCenterOf(word.box) } }?.first
                }
            }
            settleMonospace(lines)
            val scale = LAYOUT_PIXELS_PER_POINT
            val bitmap = drawn(
                page,
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
                Matrix().apply { setScale(scale, scale) },
            )
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val raster = PdfRaster(pixels, bitmap.width, bitmap.height, scale)
            bitmap.recycle()
            readWordLooks(raster, lines)
            // Masked including what is left out of the text, or a margin stamp would read as a figure.
            PdfPageInput(width, height, lines, readInk(raster, everyLine.flatMap { it.words })) { figure ->
                val part = "page-$index-figure-${figures.size}"
                figures[part] = index to figure
                part
            }
        }

    private fun isInSideMargin(box: PdfRect, pageWidth: Float): Boolean =
        box.right < pageWidth * SIDE_MARGIN_SHARE || box.left > pageWidth * (1f - SIDE_MARGIN_SHARE)

    /** A figure cut out of its page, as sharp as is any use once it reaches the attachment store. */
    private fun figureBytes(index: Int, box: PdfRect): ByteArray? = renderer.openPage(index).use { page ->
        val longest = max(box.width, box.height).coerceAtLeast(1f)
        val scale = min(FIGURE_PIXELS_PER_POINT, FIGURE_LONGEST_PX / longest)
        val width = (box.width * scale).roundToInt().coerceAtLeast(1)
        val height = (box.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = drawn(page, width, height, Matrix().apply {
            setScale(scale, scale)
            postTranslate(-box.left * scale, -box.top * scale)
        })
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }

    companion object {
        /** `%PDF-` may sit a little way in, after junk some producers put first. */
        fun isPdf(file: File): Boolean = file.inputStream().use { input ->
            val head = ByteArray(1024)
            val read = input.read(head)
            read > 0 && String(head, 0, read, Charsets.ISO_8859_1).contains("%PDF-")
        }

        fun open(file: File): PdfPages {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = try {
                    PdfRenderer(descriptor)
                } catch (locked: SecurityException) {
                    throw ImportException(ImportFailure.PasswordProtected, locked)
                } catch (broken: IOException) {
                    throw ImportException(ImportFailure.Unreadable, broken)
                }
                if (renderer.pageCount > MAX_PAGES) {
                    renderer.close()
                    throw ImportException(ImportFailure.TooLarge)
                }
                val sizes = (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        ImportedPageSize(page.width.toDp(), page.height.toDp())
                    }
                }
                return PdfPages(descriptor, renderer, sizes)
            } catch (failure: Throwable) {
                descriptor.close()
                throw failure
            }
        }

        private fun Int.toDp(): Float = this / POINTS_PER_INCH * PageStyle.DP_PER_INCH

        private const val POINTS_PER_INCH = 72f
        private const val MAX_PAGES = 5000

        /** Two to the point: a hairline survives, and a page is still under 2 MP. */
        private const val LAYOUT_PIXELS_PER_POINT = 2f
        private const val FIGURE_PIXELS_PER_POINT = 4f
        private const val FIGURE_LONGEST_PX = 2048f
        private const val FURNITURE_SAMPLE_PAGES = 24
        private const val RESYNC_WINDOW = 4
        private const val SIDE_MARGIN_SHARE = 0.12f
    }
}
