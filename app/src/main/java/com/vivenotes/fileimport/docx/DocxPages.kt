package com.vivenotes.fileimport.docx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import com.vivenotes.fileimport.ImportedDocument
import com.vivenotes.fileimport.ImportedPageSize
import com.vivenotes.model.Mark
import com.vivenotes.richtext.CanvasPasteItem
import kotlin.math.max
import kotlin.math.roundToInt
import java.nio.ByteBuffer

/** The pictures inside the package, decoded when a page first needs them. */
internal class DocxMedia(private val pkg: DocxPackage) {
    private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var cachedBytes = 0L

    /** Parts that would not decode — EMF and WMF, typically — so they are not tried per draw. */
    private val undecodable = HashSet<String>()

    fun bytes(part: String): ByteArray? = runCatching { pkg.bytes(part) }.getOrNull()

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

/** An opened document, cut into sheets. Used from one coroutine at a time. */
internal class DocxPages private constructor(
    private val pkg: DocxPackage,
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
        return WordObjects(document, marks, media::bytes).items(pieces)
    }

    override fun close() = pkg.close()

    companion object {
        /** Reads and lays out [pkg]; takes ownership of it. */
        suspend fun open(context: Context, pkg: DocxPackage): DocxPages {
            val document = DocxReader(pkg).read()
            val media = DocxMedia(pkg)
            val engine = WordLayoutEngine(context, document, media, readFontFamilies(pkg))
            return DocxPages(pkg, document, engine, media, engine.layout())
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
