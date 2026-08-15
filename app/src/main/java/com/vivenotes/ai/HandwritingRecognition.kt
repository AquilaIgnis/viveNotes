package com.vivenotes.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.pageBounds
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class InkTextRegion(
    val id: String,
    val text: String,
    val confidence: Float,
    val alternateText: String? = null,
    val alternateConfidence: Float? = null,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val strokeIds: List<String>,
)

object InkTextRegionsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(InkTextRegion.serializer())

    fun encode(regions: List<InkTextRegion>): String = json.encodeToString(serializer, regions)

    fun decode(value: String): List<InkTextRegion> =
        runCatching { json.decodeFromString(serializer, value) }.getOrDefault(emptyList())
}

data class InkCellBounds(
    val id: String,
    val bounds: InkBounds,
)

/** The document geometry that changes how page ink is segmented. */
data class InkPageLayout(
    val hash: String,
    val cells: List<InkCellBounds>,
)

fun PageDoc.inkPageLayout(): InkPageLayout = inkPageLayout(
    outlines.filterIsInstance<Outline.Table>(),
)

fun inkPageLayout(tables: List<Outline.Table>): InkPageLayout {
    val cells = buildList {
        tables.filter(Outline.Table::inkOnly).forEach { table ->
            val xEdges = buildList {
                add(table.x)
                table.columns.forEach { width -> add(last() + width) }
            }
            var top = table.y
            table.rows.forEach { row ->
                val bottom = top + row.minHeight
                row.cells.forEachIndexed { column, cell ->
                    val right = xEdges.getOrNull(column + 1) ?: return@forEachIndexed
                    add(InkCellBounds(cell.id, InkBounds(xEdges[column], top, right, bottom)))
                }
                top = bottom
            }
        }
    }
    // Cell ids are deliberately absent. Recreating an identical grid changes ids but not the pixels
    // or segmentation; raw float bits keep the signature locale-independent and exact.
    val geometry = cells.joinToString(";") { cell ->
        with(cell.bounds) {
            listOf(left, top, right, bottom).joinToString(",") { it.toRawBits().toString(16) }
        }
    }
    return InkPageLayout(hash = geometry.sha256(), cells = cells)
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { "%02x".format(it) }

/**
 * Vector-first handwriting recognition selected by `simulations/handwriting-search`.
 *
 * The recognizer sees bounded phrases rather than a squeezed page. A 1.5 px primary rendering is
 * followed by a 1 px alternate only below the measured 0.88 confidence boundary.
 */
class HandwritingRecognizer(private val engine: InkRecognitionEngine) {

    suspend fun recognize(strokes: List<PageStroke>, layout: InkPageLayout): List<InkTextRegion> {
        val groups = segmentHandwriting(strokes, layout.cells)
        return buildList(groups.size) {
            groups.forEach { group ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val primary = read(group, PRIMARY_STEM_PX)
                val alternate = if (primary.confidence < FALLBACK_CONFIDENCE) {
                    read(group, ALTERNATE_STEM_PX)
                } else {
                    null
                }
                val primaryText = primary.text.trim()
                val alternateText = alternate?.text?.trim()
                    ?.takeIf { it.isNotBlank() && it != primaryText }
                if (primaryText.isNotBlank() || alternateText != null) {
                    add(
                        InkTextRegion(
                            id = group.id,
                            text = primaryText,
                            confidence = primary.confidence,
                            alternateText = alternateText,
                            alternateConfidence = alternate?.confidence?.takeIf { alternateText != null },
                            left = group.bounds.left,
                            top = group.bounds.top,
                            right = group.bounds.right,
                            bottom = group.bounds.bottom,
                            strokeIds = group.strokes.map(PageStroke::id).distinct(),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun read(group: HandwritingGroup, stem: Float): TextRecognitionResult {
        val bitmap = renderHandwriting(group, stem)
        return try {
            engine.recognizeText(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        const val PRIMARY_STEM_PX = 1.5f
        const val ALTERNATE_STEM_PX = 1f
        const val FALLBACK_CONFIDENCE = 0.88f
        const val MAX_CHUNK_ASPECT = 10f
        const val QUIET_MARGIN_FRACTION = 0.08f
    }
}

internal data class HandwritingGroup(
    val id: String,
    val strokes: List<PageStroke>,
    val bounds: InkBounds,
)

internal fun segmentHandwriting(
    strokes: List<PageStroke>,
    cells: List<InkCellBounds>,
): List<HandwritingGroup> {
    val visible = strokes.filter { it.pageBounds != null }
    if (visible.isEmpty()) return emptyList()
    val byCell = cells.associate { it.id to mutableListOf<PageStroke>() }
    val free = mutableListOf<PageStroke>()
    visible.forEach { stroke ->
        val cell = cellFor(stroke, cells)
        if (cell == null) free += stroke else byCell.getValue(cell.id) += stroke
    }

    val groups = mutableListOf<HandwritingGroup>()
    temporalLines(free).forEachIndexed { lineIndex, line ->
        chunkLine(line).forEachIndexed { chunkIndex, chunk ->
            group("line-${lineIndex + 1}.${chunkIndex + 1}", chunk)?.let(groups::add)
        }
    }
    cells.forEach { cell ->
        byCell[cell.id].orEmpty().takeIf(List<PageStroke>::isNotEmpty)?.let { strokesInCell ->
            group("cell-${cell.id}", strokesInCell)?.let(groups::add)
        }
    }
    return readingOrder(groups)
}

private fun cellFor(stroke: PageStroke, cells: List<InkCellBounds>): InkCellBounds? {
    val bounds = stroke.pageBounds ?: return null
    val x = (bounds.left + bounds.right) / 2f
    val y = (bounds.top + bounds.bottom) / 2f
    return cells.filter { cell ->
        val box = cell.bounds
        val xSlop = (box.right - box.left) * 0.08f
        val ySlop = (box.bottom - box.top) * 0.25f
        x in (box.left - xSlop)..(box.right + xSlop) &&
            y in (box.top - ySlop)..(box.bottom + ySlop)
    }.minByOrNull { cell ->
        val box = cell.bounds
        abs(x - (box.left + box.right) / 2f) + abs(y - (box.top + box.bottom) / 2f)
    }
}

private fun temporalLines(strokes: List<PageStroke>): List<List<PageStroke>> {
    if (strokes.isEmpty()) return emptyList()
    val heights = strokes.mapNotNull { it.pageBounds?.let { box -> box.bottom - box.top } }
    val middle = heights.median()
    val typical = heights.filter { it >= middle }.median().coerceAtLeast(1f)
    val downward = max(typical * 1.1f, 24f)
    val returnLeft = max(typical * 2f, 48f)

    val lines = mutableListOf(mutableListOf<PageStroke>())
    var baselineCenters = mutableListOf<Float>()
    var rightmost = Float.NEGATIVE_INFINITY
    strokes.forEach { stroke ->
        val box = stroke.pageBounds ?: return@forEach
        val x = (box.left + box.right) / 2f
        val y = (box.top + box.bottom) / 2f
        val current = lines.last()
        val baseline = if (baselineCenters.isEmpty()) y else baselineCenters.median()
        if (current.isNotEmpty() && abs(y - baseline) > downward && x < rightmost - returnLeft) {
            lines.add(mutableListOf())
            baselineCenters = mutableListOf()
            rightmost = Float.NEGATIVE_INFINITY
        }
        lines.last() += stroke
        if (box.bottom - box.top >= typical * 0.45f) baselineCenters += y
        rightmost = max(rightmost, box.right)
    }

    val stable = lines.filter { it.size > 2 }.map { it.toMutableList() }.toMutableList()
    val delayed = lines.filter { it.size <= 2 }.flatten()
    if (stable.isEmpty()) return lines.filter { it.isNotEmpty() }
    delayed.forEach { stroke ->
        val y = stroke.pageBounds?.let { (it.top + it.bottom) / 2f } ?: return@forEach
        stable.minBy { line ->
            abs(y - line.mapNotNull { item -> item.pageBounds?.let { (it.top + it.bottom) / 2f } }.median())
        } += stroke
    }
    return stable
}

private fun chunkLine(strokes: List<PageStroke>): List<List<PageStroke>> {
    val words = wordGroups(strokes)
    val chunks = mutableListOf<List<PageStroke>>()
    var current = mutableListOf<PageStroke>()
    words.forEach { word ->
        val candidate = current + word
        val bounds = candidate.boundsOrNull() ?: return@forEach
        val aspect = (bounds.right - bounds.left) / (bounds.bottom - bounds.top).coerceAtLeast(1f)
        if (current.isNotEmpty() && aspect > HandwritingRecognizer.MAX_CHUNK_ASPECT) {
            chunks += current
            current = word.toMutableList()
        } else {
            current.addAll(word)
        }
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

private fun wordGroups(strokes: List<PageStroke>): List<List<PageStroke>> {
    val lineBounds = strokes.boundsOrNull() ?: return emptyList()
    val threshold = max(6f, (lineBounds.bottom - lineBounds.top) * 0.28f)
    val words = mutableListOf<MutableList<PageStroke>>()
    var right = Float.NEGATIVE_INFINITY
    strokes.sortedBy { it.pageBounds?.left ?: Float.MAX_VALUE }.forEach { stroke ->
        val box = stroke.pageBounds ?: return@forEach
        if (words.isNotEmpty() && box.left - right <= threshold) {
            words.last() += stroke
            right = max(right, box.right)
        } else {
            words += mutableListOf(stroke)
            right = box.right
        }
    }
    return words
}

private fun readingOrder(groups: List<HandwritingGroup>): List<HandwritingGroup> {
    val rows = mutableListOf<MutableList<HandwritingGroup>>()
    groups.sortedWith(compareBy<HandwritingGroup> { it.bounds.top }.thenBy { it.bounds.left })
        .forEach { item ->
            val row = rows.firstOrNull { current ->
                val top = current.minOf { it.bounds.top }
                val bottom = current.maxOf { it.bounds.bottom }
                val overlap = minOf(item.bounds.bottom, bottom) - maxOf(item.bounds.top, top)
                val smaller = minOf(item.bounds.bottom - item.bounds.top, bottom - top)
                overlap > 0f && overlap >= smaller * 0.5f
            }
            if (row == null) rows += mutableListOf(item) else row += item
        }
    return rows.flatMap { row -> row.sortedBy { it.bounds.left } }
}

private fun group(id: String, strokes: List<PageStroke>): HandwritingGroup? =
    strokes.boundsOrNull()?.let { HandwritingGroup(id, strokes, it) }

private fun List<PageStroke>.boundsOrNull(): InkBounds? {
    val boxes = mapNotNull(PageStroke::pageBounds)
    if (boxes.isEmpty()) return null
    return InkBounds(
        boxes.minOf(InkBounds::left),
        boxes.minOf(InkBounds::top),
        boxes.maxOf(InkBounds::right),
        boxes.maxOf(InkBounds::bottom),
    )
}

private fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val ordered = sorted()
    val middle = ordered.size / 2
    return if (ordered.size % 2 == 1) ordered[middle] else (ordered[middle - 1] + ordered[middle]) / 2f
}

private fun renderHandwriting(group: HandwritingGroup, targetStemPx: Float): Bitmap {
    val bounds = group.bounds
    val contentWidth = (bounds.right - bounds.left).coerceAtLeast(1f)
    val contentHeight = (bounds.bottom - bounds.top).coerceAtLeast(1f)
    val padding = contentHeight * HandwritingRecognizer.QUIET_MARGIN_FRACTION
    val finalScale = TEXT_HEIGHT / (contentHeight + padding * 2f)
    val width = ceil((contentWidth + padding * 2f) * finalScale).toInt().coerceAtLeast(1)
    val sampledWidth = width * SUPERSAMPLE
    val sampledHeight = TEXT_HEIGHT.toInt() * SUPERSAMPLE
    val sampled = Bitmap.createBitmap(sampledWidth, sampledHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sampled)
    canvas.drawColor(Color.WHITE)
    val sampleScale = finalScale * SUPERSAMPLE
    val pageToBitmap = Matrix().apply {
        setScale(sampleScale, sampleScale)
        postTranslate(
            padding * sampleScale - bounds.left * sampleScale,
            padding * sampleScale - bounds.top * sampleScale,
        )
    }
    val renderer = CanvasStrokeRenderer.create()
    group.strokes.forEach { pageStroke ->
        val strokeMatrix = Matrix(pageToBitmap).apply {
            preTranslate(pageStroke.offsetX, pageStroke.offsetY)
            preScale(pageStroke.scaleX, pageStroke.scaleY)
        }
        val checkpoint = canvas.save()
        canvas.concat(strokeMatrix)
        val pageStem = targetStemPx / finalScale
        val highContrast = pageStroke.stroke.copy(
            pageStroke.stroke.brush.copyWithColorIntArgb(
                colorIntArgb = Color.BLACK,
                // Ink rejects a brush whose size is below its epsilon. Small marks can need a very
                // thin page-space stem after scaling to 48 px, so clamp at the brush's own legal
                // floor instead of letting one dot fail recognition for the whole page.
                size = (pageStem / pageStroke.averageScale())
                    .coerceAtLeast(pageStroke.stroke.brush.epsilon),
            ),
        )
        renderer.draw(canvas, highContrast, strokeMatrix)
        canvas.restoreToCount(checkpoint)
    }
    val output = Bitmap.createScaledBitmap(sampled, width, TEXT_HEIGHT.toInt(), true)
    if (output !== sampled) sampled.recycle()
    return output
}

private fun PageStroke.averageScale(): Float {
    val value = (abs(scaleX) + abs(scaleY)) / 2f
    return if (value > 0f) value else 1f
}

private const val TEXT_HEIGHT = 48f
private const val SUPERSAMPLE = 4
