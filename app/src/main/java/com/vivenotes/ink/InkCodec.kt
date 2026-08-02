package com.vivenotes.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ToolTypeFilterNode
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import com.vivenotes.data.LineType
import com.vivenotes.data.PenKind
import com.vivenotes.data.PenPreset
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.model.newId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The boundary between the stored ink and `androidx.ink`, exactly as `richtext/SpannableCodec.kt` is
 * the boundary between [com.vivenotes.model.PageDoc] and `Spannable`.
 *
 * A [Stroke] is entirely determined by its brush and its inputs — the mesh is derived — so those two
 * are all that is stored, and the geometry is rebuilt on load. That is also why the brush version
 * and the input model are recorded rather than re-derived from the current pen: a stroke has to come
 * back looking like the stroke the user drew, not like whatever the pen is set to today.
 *
 * Strokes are in page units (dp), the same units [com.vivenotes.model.Outline] positions use, so
 * zoom and scroll stay a transform applied at draw time and never touch what is stored.
 */
object InkCodec {

    /**
     * Who wrote the point blob.
     *
     * `androidx.ink`'s own delta-compressed protobuf, chosen to get ink working rather than for
     * keeps: `docs/inkPlan.md` §7.3 argues for owning this encoding, because a content hash over
     * bytes a dependency controls turns a library upgrade into a full resync. Recording the encoder
     * per row is what makes that swap a rolling change instead of a migration — the same property
     * `page_content.format` gives documents.
     */
    const val ENCODING = "ink/androidx1"

    /** Stock brush versions are pinned, never `LATEST`; see `docs/inkPlan.md` ID6. */
    const val BRUSH_VERSION = 1

    private const val FAMILY_PRESSURE_PEN = "pressure-pen"
    private const val FAMILY_MARKER = "marker"
    private const val FAMILY_DASHED = "dashed-line"
    private const val FAMILY_HIGHLIGHTER = "highlighter"
    private const val FAMILY_CALLIGRAPHY_PREFIX = "calligraphy-v1-p"
    private const val CALLIGRAPHY_V1_MAX_PRESSURE = 5

    private fun familyId(pen: PenPreset): String = when {
        pen.lineType != LineType.Solid -> FAMILY_DASHED
        // Fountain is the plain pen: one width for the whole stroke, however hard you press. That
        // is why it has no pressure setting at all — see PenPanelContent.
        pen.kind == PenKind.Fountain -> FAMILY_MARKER
        // The pressure level is part of the family id because it changes the shape of every point.
        // Keeping it here makes the rendered stroke independent of future pen-setting changes.
        else -> FAMILY_CALLIGRAPHY_PREFIX + pen.pressure.coerceIn(0, CALLIGRAPHY_V1_MAX_PRESSURE)
    }

    /**
     * The family for an id. Legacy stock-family ids stay here permanently: changing their meaning
     * would restyle ink that was already saved.
     */
    private fun family(id: String): BrushFamily = when (id) {
        FAMILY_DASHED -> StockBrushes.dashedLine(StockBrushes.DashedLineVersion.V1)
        FAMILY_MARKER -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
        FAMILY_HIGHLIGHTER -> StockBrushes.highlighter()
        FAMILY_PRESSURE_PEN -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        else -> if (id.startsWith(FAMILY_CALLIGRAPHY_PREFIX)) {
            val pressure = id.removePrefix(FAMILY_CALLIGRAPHY_PREFIX).toIntOrNull()
                ?.coerceIn(0, CALLIGRAPHY_V1_MAX_PRESSURE)
                ?: PenPreset().pressure
            calligraphyFamilies[pressure]
        } else {
            // `pressure-pen` is the id written by builds before the chisel nib existed. Unknown ids
            // have historically taken this fallback too, so retain that recovery behaviour.
            StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        }
    }

    /**
     * A broad-edge nib held at a fixed page angle. The flattened tip produces thick downstrokes and
     * thin cross-strokes even when pressure response is off; pressure or speed then scales that
     * shape without changing its aspect ratio.
     */
    private val calligraphyFamilies: List<BrushFamily> by lazy {
        (0..CALLIGRAPHY_V1_MAX_PRESSURE).map(::createCalligraphyFamily)
    }

    private fun createCalligraphyFamily(pressureLevel: Int): BrushFamily {
        val response = pressureLevel.toFloat() / CALLIGRAPHY_V1_MAX_PRESSURE
        val minSize = lerp(1f, 0.45f, response)
        val maxSize = lerp(1f, 1.6f, response)
        val behaviors = if (pressureLevel == 0) {
            emptyList()
        } else {
            listOf(
                sizeBehavior(
                    source = SourceNode.Source.NORMALIZED_PRESSURE,
                    sourceRangeEnd = 1f,
                    targetRangeStart = minSize,
                    targetRangeEnd = maxSize,
                    enabledTools = setOf(InputToolType.STYLUS),
                    comment = "Stylus pressure flex for calligraphy level $pressureLevel.",
                ),
                sizeBehavior(
                    source = SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND,
                    sourceRangeEnd = 20f,
                    // Touch and mouse have no useful pressure signal: slow is thick, fast is thin.
                    targetRangeStart = maxSize,
                    targetRangeEnd = minSize,
                    enabledTools = setOf(
                        InputToolType.UNKNOWN,
                        InputToolType.MOUSE,
                        InputToolType.TOUCH,
                    ),
                    comment = "Speed fallback for calligraphy level $pressureLevel.",
                ),
            )
        }
        return BrushFamily(
            tip = BrushTip(
                scaleX = 1f,
                scaleY = 0.22f,
                cornerRounding = 0.2f,
                rotationDegrees = 45f,
                behaviors = behaviors,
            ),
            developerComment =
                "ViveNotes calligraphy v1: fixed 45-degree broad nib, pressure level $pressureLevel.",
        )
    }

    private fun sizeBehavior(
        source: SourceNode.Source,
        sourceRangeEnd: Float,
        targetRangeStart: Float,
        targetRangeEnd: Float,
        enabledTools: Set<InputToolType>,
        comment: String,
    ): BrushBehavior = BrushBehavior(
        terminalNode = TargetNode(
            target = TargetNode.Target.SIZE_MULTIPLIER,
            targetModifierRangeStart = targetRangeStart,
            targetModifierRangeEnd = targetRangeEnd,
            input = ToolTypeFilterNode(
                enabledToolTypes = enabledTools,
                input = DampingNode(
                    dampingSource = ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE,
                    dampingGap = 0.75f,
                    input = SourceNode(
                        source = source,
                        sourceValueRangeStart = 0f,
                        sourceValueRangeEnd = sourceRangeEnd,
                    ),
                ),
            ),
        ),
        developerComment = comment,
    )

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount

    /** The brush a pen currently draws with. */
    fun brushFor(pen: PenPreset): Brush = Brush.createWithColorIntArgb(
        family = family(familyId(pen)),
        colorIntArgb = pen.colorArgb,
        // Thickness is in page units, so a stroke is the same width on the page at any zoom.
        size = pen.thickness.toFloat(),
        epsilon = EPSILON,
    )

    /** A round, opaque stroke used only as the geometric mask for a normal eraser gesture. */
    fun eraseMask(inputs: StrokeInputBatch, sizeDp: Float): Stroke = Stroke(
        brush = Brush.createWithColorIntArgb(
            family = StockBrushes.marker(StockBrushes.MarkerVersion.V1),
            colorIntArgb = 0xFF000000.toInt(),
            size = sizeDp,
            epsilon = EPSILON,
        ),
        inputs = inputs,
    )

    fun encode(
        stroke: Stroke,
        pageId: String,
        seq: Int,
        pen: PenPreset,
        now: Long = System.currentTimeMillis(),
    ): InkStrokeEntity {
        val box = stroke.shape.computeBoundingBox()
        val points = encodeInputs(stroke.inputs)
        return InkStrokeEntity(
            id = newId(),
            pageId = pageId,
            seq = seq,
            brushFamily = familyId(pen),
            brushVersion = BRUSH_VERSION,
            sizeDp = stroke.brush.size,
            colorArgb = stroke.brush.colorIntArgb,
            epsilon = stroke.brush.epsilon,
            stabilization = pen.stabilization,
            // An empty stroke has no bounding box. It also has nothing to draw, so zeroes are the
            // truth rather than a fallback.
            minX = box?.xMin ?: 0f,
            minY = box?.yMin ?: 0f,
            maxX = box?.xMax ?: 0f,
            maxY = box?.yMax ?: 0f,
            points = points,
            enc = ENCODING,
            createdAt = now,
        )
    }

    /**
     * Rebuilds a stroke, or null if the row cannot be read.
     *
     * Null rather than throwing, and null rather than an empty stroke: one unreadable stroke must
     * cost that stroke, not the page it is on. A row written by a build using an encoder this one
     * does not have takes the same path.
     */
    fun decode(entity: InkStrokeEntity): Stroke? {
        if (entity.enc != ENCODING) return null
        return runCatching {
            val inputs = decodeInputs(entity.points)
            val brush = Brush.createWithColorIntArgb(
                family = family(entity.brushFamily),
                colorIntArgb = entity.colorArgb,
                size = entity.sizeDp,
                epsilon = entity.epsilon,
            )
            Stroke(brush = brush, inputs = inputs)
        }.getOrNull()
    }

    fun encodeErase(
        mask: Stroke,
        pageId: String,
        now: Long = System.currentTimeMillis(),
    ): InkEraseEntity = InkEraseEntity(
        id = newId(),
        pageId = pageId,
        sizeDp = mask.brush.size,
        points = encodeInputs(mask.inputs),
        enc = ENCODING,
        createdAt = now,
    )

    fun decodeErase(entity: InkEraseEntity): Stroke? {
        if (entity.enc != ENCODING) return null
        return runCatching {
            val inputs = decodeInputs(entity.points)
            eraseMask(inputs, entity.sizeDp)
        }.getOrNull()
    }

    private fun encodeInputs(inputs: StrokeInputBatch): ByteArray =
        ByteArrayOutputStream().use { out ->
            inputs.encode(out)
            out.toByteArray()
        }

    private fun decodeInputs(points: ByteArray): StrokeInputBatch =
        ByteArrayInputStream(points).use { input -> StrokeInputBatch.decode(input) }

    /**
     * Mesh tolerance in page units. Smaller means more triangles for the same stroke; a quarter of a
     * dp is already finer than anything a screen can show at sane zoom.
     */
    private const val EPSILON = 0.25f

}
