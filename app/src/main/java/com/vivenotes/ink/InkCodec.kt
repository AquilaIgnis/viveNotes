package com.vivenotes.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
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
import com.vivenotes.data.EraserMode
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.model.ink.LineType
import com.vivenotes.data.PenKind
import com.vivenotes.data.PenPreset
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.model.newId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

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
     * keeps: `memory/inkPlan.md` §7.3 argues for owning this encoding, because a content hash over
     * bytes a dependency controls turns a library upgrade into a full resync. Recording the encoder
     * per row is what makes that swap a rolling change instead of a migration — the same property
     * `page_content.format` gives documents.
     */
    const val ENCODING = "ink/androidx1"

    /** Little-endian count followed by x/y float pairs, all in page dp. */
    const val MOVE_ENCODING = "ink/lasso-f32le1"

    /** Stock brush versions are pinned, never `LATEST`; see `memory/inkPlan.md` ID6. */
    const val BRUSH_VERSION = 1

    private const val FAMILY_PRESSURE_PEN = "pressure-pen"
    private const val FAMILY_MARKER = "marker"
    private const val FAMILY_DASHED = "dashed-line"
    private const val FAMILY_HIGHLIGHTER = "highlighter"
    private const val FAMILY_CALLIGRAPHY_PREFIX = "calligraphy-v1-p"
    private const val CALLIGRAPHY_V1_MAX_PRESSURE = 5

    /** Stock, and measured to be irrelevant to smoothing — see [inputModelFor]. */
    private const val STABILIZATION_UPSAMPLING_HZ = 180

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
     * How a stabilization level smooths the input — `memory/inkPlan.md` §4, finally applied.
     *
     * **The library does the maths, not us.** `BrushFamily.inputModel` is Ink's own stroke modeller:
     * a sliding window over recent samples, run in native code *inside* the authoring pipeline. A
     * hand-rolled filter in front of `addToStroke` would fight the latency prediction and give a wet
     * stroke whose shape disagreed with the dry one.
     *
     * The numbers are measured rather than guessed, against a synthetic straight line with a sine
     * tremor on it (2026-08-10, tablet AVD, wobble = how far the centreline still strays):
     *
     * | window | 10 Hz ±3dp | 4 Hz ±4dp |
     * |---|---|---|
     * | passthrough | 2.97 | 3.98 |
     * | 20 ms | 2.66 | 3.94 |
     * | 40 ms | 2.32 | 3.80 |
     * | 60 ms | 2.26 | 3.60 |
     * | 90 ms | 2.26 | 3.17 |
     * | 140 ms | 2.26 | 2.91 |
     * | 200 ms | 2.26 | 2.91 |
     *
     * Two things that shaped the scale. It **saturates** — past about 60 ms nothing more happens to a
     * fast tremor and past about 140 ms nothing happens at all — so 120 ms is the top of the range
     * rather than an arbitrary stop, and levels beyond it would be steps that did nothing. And the
     * effect is **gentle**: a quarter of the wobble at best. This takes the edge off shake; it does
     * not turn unsteady handwriting into a ruled line, and a scale that implied otherwise would be a
     * lie told in five steps.
     *
     * `upsamplingFrequencyHz` is deliberately left at the stock 180. It was measured too, and it does
     * nothing for smoothing — 60, 180 and 400 gave the same wobble to three decimal places. It only
     * changes how many points the mesh is built from, so moving it would cost triangles and buy
     * nothing.
     *
     * **Level 1 is the library's own default**, which is what every stroke drawn before this existed
     * was already getting. That is what makes turning this on a no-op for ink already on the page.
     *
     * **This mapping is frozen once shipped**, for the reason the family ids above are: the *level*
     * is what a row stores, so the level → window table is what its meaning depends on. Changing a
     * number here restyles every stroke ever drawn at that level. A different curve is a new level,
     * or a new column — never an edit to this one.
     *
     * This reverses `memory/inkPlan.md` **ID4**, which located stabilization in a pre-filter over the
     * raw samples so the filtered points were what got persisted. The harm ID4 named — "changing the
     * smoothing slider silently rewrites every drawing ever made" — is answered instead by the
     * per-stroke column: a stroke replays at the level it was drawn at, not the level in hand. What
     * ID4 still has right is the residual risk, and it is accepted rather than solved: an input model
     * is the library's to define, so an Ink upgrade that changes what `SlidingWindowModel(40, 180)`
     * means will reshape old ink, and there is no equivalent of ID6's version pin for it.
     */
    internal fun inputModelFor(stabilization: Int): BrushFamily.InputModel =
        when (stabilization.coerceIn(0, PenPreset.MAX_STABILIZATION)) {
            // Off means off: the raw samples, with none of Ink's own smoothing. Visibly rougher than
            // the default, which is the honest meaning of a zero on this stepper.
            0 -> BrushFamily.InputModel.PASSTHROUGH_MODEL
            1 -> slidingWindow(20L)
            2 -> slidingWindow(40L)
            3 -> slidingWindow(60L)
            4 -> slidingWindow(90L)
            else -> slidingWindow(120L)
        }

    private fun slidingWindow(windowMillis: Long): BrushFamily.InputModel =
        BrushFamily.InputModel.SlidingWindowModel(
            windowDurationMillis = windowMillis,
            upsamplingFrequencyHz = STABILIZATION_UPSAMPLING_HZ,
        )

    /**
     * The family for an id, wearing the input model that stabilization level asks for.
     *
     * **The highlighter is exempt, and that is not an oversight.** It has no stabilization control
     * (see [brushFor] and `HighlighterPanel`), so its rows store 0 to mean *not applicable* rather
     * than *off* — and 0 maps to passthrough. Handing it that would quietly re-render every
     * highlighter stroke ever saved, rougher than it was drawn, on the next page load.
     *
     * Cached because a page load decodes every stroke on it, and each [BrushFamily.copy] is a native
     * allocation; the key space is a handful of ids across six levels. Concurrent because decoding
     * runs off the main thread.
     */
    private fun family(id: String, stabilization: Int): BrushFamily {
        val base = family(id)
        if (id == FAMILY_HIGHLIGHTER) return base
        val level = stabilization.coerceIn(0, PenPreset.MAX_STABILIZATION)
        return stabilizedFamilies.getOrPut("$id#$level") {
            base.copy(inputModel = inputModelFor(level))
        }
    }

    private val stabilizedFamilies = ConcurrentHashMap<String, BrushFamily>()

    /**
     * The family for an id. Legacy stock-family ids stay here permanently: changing their meaning
     * would restyle ink that was already saved.
     */
    private fun family(id: String): BrushFamily = when (id) {
        FAMILY_DASHED -> StockBrushes.dashedLine(StockBrushes.DashedLineVersion.V1)
        FAMILY_MARKER -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
        FAMILY_HIGHLIGHTER -> StockBrushes.highlighter(
            // A highlighter that doubles back over itself must not darken where it crosses, which
            // is exactly what ACCUMULATE does to a translucent colour — and on minSdk 35, ANY *is*
            // ACCUMULATE, since that is its default from Android U up. DISCARD is the only option
            // that draws one flat band per stroke, which is what a real highlighter leaves.
            selfOverlap = SelfOverlap.DISCARD,
            // Pinned, never LATEST: ID6. This id has never been written by any build — the mapping
            // existed before anything could produce it — so choosing its meaning now restyles no
            // saved ink.
            version = StockBrushes.HighlighterVersion.V1,
        )
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

    /** The brush a pen currently draws with, stabilizer included — see [inputModelFor]. */
    fun brushFor(pen: PenPreset): Brush = Brush.createWithColorIntArgb(
        family = family(familyId(pen), pen.stabilization),
        colorIntArgb = pen.colorArgb,
        // Thickness is in page units, so a stroke is the same width on the page at any zoom.
        size = pen.thickness,
        epsilon = EPSILON,
    )

    /**
     * The brush the highlighter draws with.
     *
     * The colour is passed through with its alpha intact, because the stock highlighter is a plain
     * chisel nib that is only a highlighter *when given a translucent colour* — the family supplies
     * the shape, the colour supplies the transparency. Handing it an opaque colour would produce a
     * wide marker that blots out whatever it is drawn over.
     */
    fun brushFor(highlighter: HighlighterSettings): Brush = Brush.createWithColorIntArgb(
        family = family(FAMILY_HIGHLIGHTER),
        colorIntArgb = highlighter.colorArgb,
        size = highlighter.thickness.toFloat(),
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
    ): InkStrokeEntity = encode(
        stroke,
        pageId,
        seq,
        familyId(pen),
        pen.stabilization,
        now,
        // The pen's own flag, not a reading of the colour: this is the one moment the intent is
        // actually known, and recording it here is what keeps Switch Background from having to
        // guess later.
        colorFollowsTheme = pen.colorFollowsTheme,
    )

    /**
     * A highlighter stroke.
     *
     * Stabilization is recorded as 0 because the highlighter has no such setting — a band that wide
     * hides the shake a pen would show, so the control would be one nobody could see the effect of.
     * The column is per-stroke, so this is a statement about the stroke rather than a placeholder.
     */
    fun encode(
        stroke: Stroke,
        pageId: String,
        seq: Int,
        highlighter: HighlighterSettings,
        now: Long = System.currentTimeMillis(),
    ): InkStrokeEntity = encode(
        stroke,
        pageId,
        seq,
        FAMILY_HIGHLIGHTER,
        stabilization = 0,
        now,
        // Never automatic. A highlighter is only a highlighter because its colour is translucent,
        // so there is no such thing as a highlight that follows the canvas — flipping one to the
        // page's ink colour would paint an opaque band over the writing it is marking.
        colorFollowsTheme = false,
    )

    private fun encode(
        stroke: Stroke,
        pageId: String,
        seq: Int,
        brushFamily: String,
        stabilization: Int,
        now: Long,
        colorFollowsTheme: Boolean?,
        groupId: String? = null,
    ): InkStrokeEntity {
        val box = stroke.shape.computeBoundingBox()
        val points = encodeInputs(stroke.inputs)
        return InkStrokeEntity(
            id = newId(),
            pageId = pageId,
            seq = seq,
            brushFamily = brushFamily,
            brushVersion = BRUSH_VERSION,
            sizeDp = stroke.brush.size,
            colorArgb = stroke.brush.colorIntArgb,
            colorFollowsTheme = colorFollowsTheme,
            epsilon = stroke.brush.epsilon,
            stabilization = stabilization,
            // An empty stroke has no bounding box. It also has nothing to draw, so zeroes are the
            // truth rather than a fallback.
            minX = box?.xMin ?: 0f,
            minY = box?.yMin ?: 0f,
            maxX = box?.xMax ?: 0f,
            maxY = box?.yMax ?: 0f,
            points = points,
            enc = ENCODING,
            createdAt = now,
            groupId = groupId,
        )
    }

    /** Encodes an already-styled clipboard stroke when it is pasted onto a page. */
    fun encodeCopy(
        source: PageStroke,
        stroke: Stroke,
        pageId: String,
        groupId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): InkStrokeEntity {
        val box = stroke.shape.computeBoundingBox()
        return InkStrokeEntity(
            id = newId(),
            pageId = pageId,
            seq = 0,
            brushFamily = source.brushFamily,
            brushVersion = source.brushVersion,
            sizeDp = stroke.brush.size,
            colorArgb = stroke.brush.colorIntArgb,
            // Copied from the source rather than re-derived: a duplicate of an automatic stroke is
            // still automatic, and a duplicate of a red one is still red.
            colorFollowsTheme = source.colorFollowsTheme,
            epsilon = stroke.brush.epsilon,
            stabilization = source.stabilization,
            minX = box?.xMin ?: 0f,
            minY = box?.yMin ?: 0f,
            maxX = box?.xMax ?: 0f,
            maxY = box?.yMax ?: 0f,
            points = encodeInputs(stroke.inputs),
            enc = ENCODING,
            createdAt = now,
            groupId = groupId,
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
                // The stored level, not the pen's current one: a stroke has to come back looking
                // like the stroke that was drawn. The mesh is derived from the inputs *through* the
                // input model, so replaying with the wrong one reshapes ink that is already on the
                // page — which is exactly what the column has been recorded for since the schema
                // was written.
                family = family(entity.brushFamily, entity.stabilization),
                colorIntArgb = entity.colorArgb,
                size = entity.sizeDp,
                epsilon = entity.epsilon,
            )
            Stroke(brush = brush, inputs = inputs)
        }.getOrNull()
    }

    /** Structural validation for an imported point blob without building its brush mesh. */
    fun hasValidInputData(points: ByteArray): Boolean =
        runCatching { decodeInputs(points) }.isSuccess

    fun encodeErase(
        mask: Stroke,
        pageId: String,
        mode: EraserMode = EraserMode.Normal,
        now: Long = System.currentTimeMillis(),
    ): InkEraseEntity = InkEraseEntity(
        id = newId(),
        pageId = pageId,
        mode = mode,
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

    /**
     * A mask as page-open replay will rebuild it: through the point codec and back.
     *
     * For [com.vivenotes.ink.planProjectionDelete], which has to *prove* that a mask it is about to
     * store takes the piece it means and no other. The mask it builds in memory is not the one that
     * decides that on the next open — `StrokeInputBatch.encode` is the library's own packing, and a
     * dot that clears the piece beside it by a hair in memory is a delete that could land differently
     * after a round trip. So the object being tested is the object that will be applied.
     */
    fun reloadedEraseMask(inputs: StrokeInputBatch, sizeDp: Float): Stroke? = runCatching {
        eraseMask(decodeInputs(encodeInputs(inputs)), sizeDp)
    }.getOrNull()

    fun encodeMove(
        path: List<InkPoint>,
        pageId: String,
        dx: Float,
        dy: Float,
        now: Long = System.currentTimeMillis(),
    ): InkMoveEntity {
        require(path.size >= 3) { "A lasso needs at least three points" }
        require(path.all { it.x.isFinite() && it.y.isFinite() }) { "Lasso points must be finite" }
        val bytes = ByteBuffer.allocate(Int.SIZE_BYTES + path.size * 2 * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(path.size)
            .apply { path.forEach { point -> putFloat(point.x).putFloat(point.y) } }
            .array()
        return InkMoveEntity(
            id = newId(),
            pageId = pageId,
            dxDp = dx,
            dyDp = dy,
            points = bytes,
            enc = MOVE_ENCODING,
            createdAt = now,
        )
    }

    fun encodeResize(
        resize: InkLassoResize,
        pageId: String,
        now: Long = System.currentTimeMillis(),
    ): InkMoveEntity = encodeMove(
        path = resize.path,
        pageId = pageId,
        dx = 0f,
        dy = 0f,
        now = now,
    ).copy(
        scaleX = resize.scaleX,
        scaleY = resize.scaleY,
        anchorX = resize.anchor.x,
        anchorY = resize.anchor.y,
    )

    fun decodeMove(entity: InkMoveEntity): List<InkPoint>? {
        if (entity.enc != MOVE_ENCODING) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(entity.points).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int
            require(count >= 3 && buffer.remaining() == count * 2 * Float.SIZE_BYTES)
            List(count) {
                InkPoint(buffer.float, buffer.float).also { point ->
                    require(point.x.isFinite() && point.y.isFinite())
                }
            }
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
