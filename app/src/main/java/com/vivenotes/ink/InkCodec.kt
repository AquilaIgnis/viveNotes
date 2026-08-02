package com.vivenotes.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import com.vivenotes.data.LineType
import com.vivenotes.data.PenKind
import com.vivenotes.data.PenPreset
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

    private fun familyId(pen: PenPreset): String = when {
        pen.lineType != LineType.Solid -> FAMILY_DASHED
        // Fountain is the plain pen: one width for the whole stroke, however hard you press. That
        // is why it has no pressure setting at all — see PenPanelContent.
        pen.kind == PenKind.Fountain -> FAMILY_MARKER
        // Calligraphy with the response turned off is the same plain line.
        pen.pressure == 0 -> FAMILY_MARKER
        // Calligraphy is the expressive one. The stock pressure pen is a stand-in for its width
        // response until the real chisel nib exists — `docs/inkPlan.md` §6a — and is recorded under
        // its own id so those strokes keep their shape when the nib lands.
        else -> FAMILY_PRESSURE_PEN
    }

    /**
     * The stock family for an id.
     *
     * The smoothing each family applies is **not** configurable from here. `BrushFamily.InputModel`
     * and its `SlidingWindowModel` are `@RestrictTo(LIBRARY_GROUP)` — restricted to `androidx.ink`
     * itself, not merely experimental — so a stroke is modelled by whatever the stock family does.
     * The pen's Stabilization setting is therefore recorded with the stroke but does not yet change
     * it; making it real means the pre-filter in `docs/inkPlan.md` §4.3, which is ours and needs no
     * library support.
     */
    private fun family(id: String): BrushFamily = when (id) {
        FAMILY_DASHED -> StockBrushes.dashedLine(StockBrushes.DashedLineVersion.V1)
        FAMILY_MARKER -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
        FAMILY_HIGHLIGHTER -> StockBrushes.highlighter()
        else -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
    }

    /** The brush a pen currently draws with. */
    fun brushFor(pen: PenPreset): Brush = Brush.createWithColorIntArgb(
        family = family(familyId(pen)),
        colorIntArgb = pen.colorArgb,
        // Thickness is in page units, so a stroke is the same width on the page at any zoom.
        size = pen.thickness.toFloat(),
        epsilon = EPSILON,
    )

    fun encode(
        stroke: Stroke,
        pageId: String,
        seq: Int,
        pen: PenPreset,
        now: Long = System.currentTimeMillis(),
    ): InkStrokeEntity {
        val box = stroke.shape.computeBoundingBox()
        val points = ByteArrayOutputStream().use { out ->
            stroke.inputs.encode(out)
            out.toByteArray()
        }
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
            val inputs: StrokeInputBatch = ByteArrayInputStream(entity.points).use { input ->
                StrokeInputBatch.decode(input)
            }
            val brush = Brush.createWithColorIntArgb(
                family = family(entity.brushFamily),
                colorIntArgb = entity.colorArgb,
                size = entity.sizeDp,
                epsilon = entity.epsilon,
            )
            Stroke(brush = brush, inputs = inputs)
        }.getOrNull()
    }

    /**
     * Mesh tolerance in page units. Smaller means more triangles for the same stroke; a quarter of a
     * dp is already finer than anything a screen can show at sane zoom.
     */
    private const val EPSILON = 0.25f

}
