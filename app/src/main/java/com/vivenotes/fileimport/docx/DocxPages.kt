package com.vivenotes.fileimport.docx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import com.vivenotes.fileimport.FileImporter
import com.vivenotes.fileimport.ImportedDocument
import com.vivenotes.fileimport.ImportedPageSize
import com.vivenotes.model.Mark
import com.vivenotes.richtext.CanvasPasteItem
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt
import java.nio.ByteBuffer

/**
 * [bytes] with [crop] cut away, as a PNG; null when they will not decode, which is EMF and WMF, or
 * when the crop leaves nothing.
 *
 * What the page shows is the cropped picture at the crop's shape. Handed over whole, the canvas
 * would stretch all of it to that shape, cut-off parts and all. No sharper than the attachment
 * store keeps, since it would only be scaled down again on the way in.
 */
internal fun croppedPicture(bytes: ByteArray, crop: WordCrop): ByteArray? {
    // A negative side is padding around the picture; there is nothing there to keep.
    val left = crop.left.coerceIn(0f, 1f)
    val top = crop.top.coerceIn(0f, 1f)
    val right = crop.right.coerceIn(0f, 1f)
    val bottom = crop.bottom.coerceIn(0f, 1f)
    if (left + right >= 1f || top + bottom >= 1f) return null
    return runCatching { decodeCropped(bytes, left, top, right, bottom) }.getOrNull()
}

private fun decodeCropped(bytes: ByteArray, left: Float, top: Float, right: Float, bottom: Float): ByteArray {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val shownWidth = info.size.width * (1f - left - right)
        val shownHeight = info.size.height * (1f - top - bottom)
        val scale = minOf(1f, FileImporter.PICTURE_LONGEST_PX / max(shownWidth, shownHeight))
        val width = (info.size.width * scale).roundToInt().coerceAtLeast(1)
        val height = (info.size.height * scale).roundToInt().coerceAtLeast(1)
        if (scale < 1f) decoder.setTargetSize(width, height)
        val area = android.graphics.Rect(
            (width * left).roundToInt(),
            (height * top).roundToInt(),
            (width * (1f - right)).roundToInt(),
            (height * (1f - bottom)).roundToInt(),
        )
        // Never empty, and never past the picture, which the decoder refuses.
        area.right = area.right.coerceIn(area.left + 1, width)
        area.bottom = area.bottom.coerceIn(area.top + 1, height)
        area.left = area.left.coerceAtMost(area.right - 1)
        area.top = area.top.coerceAtMost(area.bottom - 1)
        decoder.crop = area
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return java.io.ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }
}

/** The pictures inside the document, decoded when a page first needs them. */
internal class DocxMedia(private val read: (String) -> ByteArray?) {
    private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var cachedBytes = 0L

    /** Parts that would not decode — EMF and WMF, typically — so they are not tried per draw. */
    private val undecodable = HashSet<String>()

    fun bytes(part: String): ByteArray? = runCatching { read(part) }.getOrNull()

    /** [part] at no less than [longestPx] on its long side, if it is that big; null if unreadable. */
    fun bitmap(part: String, longestPx: Int): Bitmap? {
        if (part in undecodable) return null
        val target = longestPx.coerceIn(1, MAX_DECODE_PX)
        cache[part]?.let { cached ->
            if (max(cached.width, cached.height) >= target * ENOUGH) return cached
        }
        val decoded = decode(part, target)
        if (decoded == null) {
            undecodable += part
            return null
        }
        cache.put(part, decoded)?.let { cachedBytes -= it.allocationByteCount }
        cachedBytes += decoded.allocationByteCount
        val eldest = cache.entries.iterator()
        while (cachedBytes > MAX_CACHE_BYTES && cache.size > 1 && eldest.hasNext()) {
            val entry = eldest.next()
            if (entry.key == part) continue
            cachedBytes -= entry.value.allocationByteCount
            eldest.remove()
        }
        return decoded
    }

    private fun decode(part: String, target: Int): Bitmap? {
        val data = bytes(part) ?: return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > target) {
                    val scale = target.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_DECODE_PX = 4096
        const val MAX_CACHE_BYTES = 48L * 1024 * 1024

        /** Close enough to the size asked for that decoding again would not be worth it. */
        const val ENOUGH = 0.75f
    }
}

/**
 * An opened word-processor document, cut into sheets. Used from one coroutine at a time.
 *
 * Every such format arrives here as a [WordDocument], so they share one layout and one set of rules
 * for what becomes an object.
 */
internal class DocxPages private constructor(
    private val source: Closeable,
    private val document: WordDocument,
    private val engine: WordLayoutEngine,
    private val media: DocxMedia,
    val sheets: List<DocxSheet>,
) : ImportedDocument {

    override val pages: List<ImportedPageSize> = sheets.map { ImportedPageSize(it.widthDp, it.heightDp) }

    override fun render(index: Int, widthPx: Int): Bitmap? {
        val sheet = sheets.getOrNull(index) ?: return null
        val scale = widthPx.toFloat() / sheet.widthUnits
        val heightPx = (sheet.heightUnits * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        engine.state.scale = scale
        drawSheet(canvas, sheet)
        canvas.restore()
        return bitmap
    }

    private fun drawSheet(canvas: Canvas, sheet: DocxSheet) {
        sheet.floats.filter { it.behind }.forEach { drawFloat(canvas, it) }
        sheet.header?.let { drawFlow(canvas, it) }
        sheet.footer?.let { drawFlow(canvas, it) }
        sheet.pieces.forEach { drawPiece(canvas, it) }
        sheet.floats.filterNot { it.behind }.forEach { drawFloat(canvas, it) }
    }

    private fun drawFloat(canvas: Canvas, float: SheetFloat) {
        val count = canvas.save()
        canvas.translate(float.x.toFloat(), float.y.toFloat())
        if (float.rotationDegrees != 0f) canvas.rotate(float.rotationDegrees, float.box.width / 2f, float.box.height / 2f)
        float.box.draw(canvas)
        canvas.restoreToCount(count)
    }

    private fun drawFlow(canvas: Canvas, placed: PlacedFlow) {
        val count = canvas.save()
        canvas.translate(placed.x.toFloat(), placed.y.toFloat())
        placed.flow.draw(canvas)
        canvas.restoreToCount(count)
    }

    private fun drawPiece(canvas: Canvas, piece: SheetPiece) {
        val count = canvas.save()
        canvas.translate(piece.x.toFloat(), piece.y.toFloat())
        val repeat = if (piece.from > 0) piece.block.repeatHeight else 0
        if (repeat > 0) {
            piece.block.drawRepeat(canvas)
            canvas.translate(0f, repeat.toFloat())
        }
        canvas.clipRect(-CLIP_MARGIN, 0f, CLIP_MARGIN + 100_000f, (piece.to - piece.from).toFloat())
        canvas.translate(0f, -piece.from.toFloat())
        piece.block.draw(canvas, piece.from, piece.to)
        canvas.restoreToCount(count)
    }

    /** What the chosen [pages] hold as canvas objects; see [WordObjects]. */
    override suspend fun items(pages: List<Int>, marks: Set<Mark>): List<CanvasPasteItem> {
        val pieces = pages.sorted().flatMap { index -> sheets.getOrNull(index)?.objectPieces().orEmpty() }
        return WordObjects(document, marks, media::bytes, ::croppedPicture).items(pieces)
    }

    override fun close() = source.close()

    companion object {
        /** Reads and lays out [pkg]; takes ownership of it. */
        suspend fun open(context: Context, pkg: DocxPackage): DocxPages =
            of(context, DocxReader(pkg).read(), { pkg.bytes(it) }, readFontFamilies(pkg), pkg)

        /**
         * Lays out a [document] read from any format. [parts] finds a picture by the name the document
         * gives it, [fontFamilies] is each font's generic family, lowercased, and [source] is closed
         * along with this.
         */
        suspend fun of(
            context: Context,
            document: WordDocument,
            parts: (String) -> ByteArray?,
            fontFamilies: Map<String, String>,
            source: Closeable,
        ): DocxPages {
            val media = DocxMedia(parts)
            val engine = WordLayoutEngine(context, document, media, fontFamilies)
            return DocxPages(source, document, engine, media, engine.layout())
        }

        /** `fontTable.xml`'s family for each font, used to pick a stand-in for a font we have not met. */
        private fun readFontFamilies(pkg: DocxPackage): Map<String, String> {
            val root = pkg.relationships(pkg.mainPart).values
                .firstOrNull { !it.external && it.type.endsWith("/fontTable") }
                ?.let { pkg.xml(it.target) } ?: return emptyMap()
            return root.children(NS_W, "font").associate { font ->
                font.w("name").orEmpty().lowercase() to font.wVal("family").orEmpty().lowercase()
            }
        }

        private const val CLIP_MARGIN = 20_000f
    }
}
