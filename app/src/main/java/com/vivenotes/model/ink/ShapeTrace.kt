package com.vivenotes.model.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.serialization.Serializable

/**
 * Solid, dashed or dotted.
 *
 * Lives in the model rather than beside the pen presets because a shape's border carries one, and a
 * shape is part of the document — it travels with the page, exports with it, and is read by the MCP
 * server without a device. `data/` may depend on `model/`; the reverse would block the `:core:model`
 * split (AD5). A pen's line type is the same enum used the other way round, as a user preference.
 */
@Serializable
enum class LineType(val label: String) {
    Solid("Solid"),
    Dashed("Dashed"),
    Dotted("Dotted"),
}

/** Page 1 of the picker: flat shapes. */
const val PAGE_BASIC = 0

/** Page 2: the solids, which are the only kinds with hidden edges. */
const val PAGE_SOLID = 1

/**
 * The shapes the Insert Shape picker offers, and the ideal path each one traces.
 *
 * Pure Kotlin with no Android types, in `model/ink/` so it can move into `:core:model` when that
 * split happens (AD5) — and, more immediately, so the geometry is covered by JVM tests rather than
 * by eyeballing a device.
 *
 * This is `docs/inkPlan.md` §5.3's `traceShape`, built early. §5.4 (Insert Shape) needs it to lay an
 * ideal shape down from nothing; §5.1–5.3 (hold to snap) will need the same function to replace a
 * freehand stroke with an ideal one. Building the picker first leaves 11f only its classifier to
 * write.
 *
 * Everything here is in page units. Nothing here knows about colour, border width or fill — a
 * tracing is geometry, and how it is painted belongs to the `Outline.Shape` that carries it.
 */
@Serializable
enum class ShapeKind(val label: String, val page: Int) {
    Line("Line", PAGE_BASIC),
    Arrow("Arrow", PAGE_BASIC),
    Rectangle("Rectangle", PAGE_BASIC),
    RoundedRectangle("Rounded rectangle", PAGE_BASIC),
    Ellipse("Ellipse", PAGE_BASIC),
    Triangle("Triangle", PAGE_BASIC),
    RightTriangle("Right triangle", PAGE_BASIC),
    Diamond("Diamond", PAGE_BASIC),
    Pentagon("Pentagon", PAGE_BASIC),
    Hexagon("Hexagon", PAGE_BASIC),
    L("L shape", PAGE_BASIC),

    Cube("Cube", PAGE_SOLID),
    Pyramid("Pyramid", PAGE_SOLID),
    Wedge("Wedge", PAGE_SOLID),
    Sphere("Sphere", PAGE_SOLID),
    Cone("Cone", PAGE_SOLID),
    Cylinder("Cylinder", PAGE_SOLID),
    ;

    val isSolid: Boolean get() = page == PAGE_SOLID

    /**
     * True for the kinds whose arms are resized one at a time, through the handles
     * [ShapeArm][com.vivenotes.model.ink.ShapeArm] describes, rather than only as a whole.
     *
     * A property of the kind rather than of the geometry, because "which free ends may be dragged"
     * is a question about the *affordance*, and an arrow's three polylines have free ends that mean
     * nothing to pull on. Naming the kinds keeps that judgement in one place.
     */
    val hasArms: Boolean get() = this == L

    companion object {
        const val PAGE_COUNT = 2

        /** The default, and what the reference screenshot has selected on the page it shows. */
        val DEFAULT = Rectangle

        fun onPage(page: Int): List<ShapeKind> = entries.filter { it.page == page }
    }
}

/**
 * One shape's geometry, as polylines of interleaved x/y in page units.
 *
 * `FloatArray` rather than a list of points because `docs/inkPlan.md` §5.2 already declares
 * `ShapeFit.classify(points: FloatArray, …)` — the two halves of the feature then speak one
 * language, and neither allocates an object per point.
 *
 * A closed polyline repeats its first point as its last, so every array here can be stroked
 * directly without the caller having to know which kinds close.
 *
 * [hidden] is the edges a solid occludes, drawn dotted. It is empty for every flat shape — see
 * `docs/inkPlan.md` §5.4 SD3 for why there is no toggle.
 *
 * [fill] is the region a fill colour paints, and it is **not** the same as [solid]. For a flat shape
 * the two are the same closed outline, but a wireframe cube has no single inside: filling its twelve
 * edges would paint three faces in three overlapping passes, or nothing at all depending on the
 * winding rule. So a solid reports its *silhouette* — the outer boundary of what it covers — which is
 * the region a real object of that shape would occupy. Open shapes (the line and the arrows) report
 * none, because a line has no inside to colour.
 *
 * Equality is inherited from `List<FloatArray>` and so compares arrays by identity. That is never
 * what a caller wants; compare the contents if you need to.
 */
data class ShapeTracing(
    val solid: List<FloatArray>,
    val hidden: List<FloatArray> = emptyList(),
    val fill: List<FloatArray> = emptyList(),
)

/**
 * Traces [kind] across the drag from ([startX], [startY]) to ([endX], [endY]).
 *
 * **The corners are the drag's start and end, not a normalised rectangle.** A line and an arrow run
 * from the first point to the second, so an arrow dragged leftwards points left; normalising here
 * would throw that away and there is nowhere later to recover it. Every closed shape normalises
 * internally, so for those the drag direction genuinely does not matter.
 */
fun trace(
    kind: ShapeKind,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
): ShapeTracing {
    val left = min(startX, endX)
    val top = min(startY, endY)
    val right = max(startX, endX)
    val bottom = max(startY, endY)

    // A closed flat shape is its own fill region; an open one has no inside to colour. Solids
    // compute a silhouette instead — see [ShapeTracing.fill].
    fun closedShape(outline: FloatArray) = ShapeTracing(listOf(outline), fill = listOf(outline))

    return when (kind) {
        ShapeKind.Line -> ShapeTracing(listOf(floatArrayOf(startX, startY, endX, endY)))
        ShapeKind.Arrow -> arrow(startX, startY, endX, endY)
        ShapeKind.Rectangle -> closedShape(
            closed(left, top, right, top, right, bottom, left, bottom),
        )
        ShapeKind.RoundedRectangle -> closedShape(roundedRect(left, top, right, bottom))
        ShapeKind.Ellipse -> closedShape(ellipse(left, top, right, bottom))
        ShapeKind.Triangle -> closedShape(
            closed((left + right) / 2f, top, right, bottom, left, bottom),
        )
        ShapeKind.RightTriangle -> closedShape(closed(left, top, right, bottom, left, bottom))
        ShapeKind.Diamond -> closedShape(
            closed(
                (left + right) / 2f, top,
                right, (top + bottom) / 2f,
                (left + right) / 2f, bottom,
                left, (top + bottom) / 2f,
            ),
        )
        // Point up, so a pentagon reads as a pentagon rather than as a lopsided box.
        ShapeKind.Pentagon -> closedShape(regular(left, top, right, bottom, 5, -HALF_PI))
        // Points left and right, which is the orientation that gives the flat top a hexagon is
        // recognised by.
        ShapeKind.Hexagon -> closedShape(regular(left, top, right, bottom, 6, 0f))
        // Corner at the bottom left, one arm up and one to the right — the two axes, which is what
        // an L is for. Open, so like the line and the arrow it reports no fill region.
        //
        // Normalised like every closed kind rather than read off the drag like the line: the L
        // always opens the same way, so which corner of the box the drag began in says nothing. The
        // arms are adjusted one at a time afterwards, which is the affordance that replaces it.
        ShapeKind.L -> ShapeTracing(listOf(floatArrayOf(left, top, left, bottom, right, bottom)))

        ShapeKind.Cube -> cube(left, top, right, bottom)
        ShapeKind.Pyramid -> pyramid(left, top, right, bottom)
        ShapeKind.Wedge -> wedge(left, top, right, bottom)
        ShapeKind.Sphere -> sphere(left, top, right, bottom)
        ShapeKind.Cone -> cone(left, top, right, bottom)
        ShapeKind.Cylinder -> cylinder(left, top, right, bottom)
    }
}

// ---------------------------------------------------------------------------------------------
// Flat shapes
// ---------------------------------------------------------------------------------------------

/** Shaft plus a head. The head is its own polyline so it is not closed into the shaft. */
private fun arrow(startX: Float, startY: Float, endX: Float, endY: Float): ShapeTracing {
    val shaft = floatArrayOf(startX, startY, endX, endY)
    val length = hypot(endX - startX, endY - startY)
    if (length <= 0f) return ShapeTracing(listOf(shaft))
    val head = (length * HEAD_FRACTION).coerceAtMost(HEAD_MAX_DP)
    return ShapeTracing(listOf(shaft, arrowHead(startX, startY, endX, endY, head)))
}

/** The two wings at ([tipX], [tipY]), opening back along the line from ([fromX], [fromY]). */
private fun arrowHead(
    fromX: Float,
    fromY: Float,
    tipX: Float,
    tipY: Float,
    length: Float,
): FloatArray {
    val back = atan2(tipY - fromY, tipX - fromX) + PI.toFloat()
    return floatArrayOf(
        tipX + length * cos(back - HEAD_SPREAD), tipY + length * sin(back - HEAD_SPREAD),
        tipX, tipY,
        tipX + length * cos(back + HEAD_SPREAD), tipY + length * sin(back + HEAD_SPREAD),
    )
}

private fun roundedRect(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
    val radius = (min(right - left, bottom - top) * CORNER_FRACTION).coerceAtLeast(0f)
    if (radius <= 0f) return closed(left, top, right, top, right, bottom, left, bottom)
    val points = mutableListOf<Float>()
    points.add(left + radius); points.add(top)
    points.add(right - radius); points.add(top)
    appendArc(points, right - radius, top + radius, radius, radius, -HALF_PI, 0f)
    points.add(right); points.add(bottom - radius)
    appendArc(points, right - radius, bottom - radius, radius, radius, 0f, HALF_PI)
    points.add(left + radius); points.add(bottom)
    appendArc(points, left + radius, bottom - radius, radius, radius, HALF_PI, PI.toFloat())
    points.add(left); points.add(top + radius)
    appendArc(points, left + radius, top + radius, radius, radius, PI.toFloat(), 3f * HALF_PI)
    points.add(left + radius); points.add(top)
    return points.toFloatArray()
}

private fun ellipse(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
    val points = mutableListOf<Float>()
    appendArc(
        points,
        (left + right) / 2f,
        (top + bottom) / 2f,
        (right - left) / 2f,
        (bottom - top) / 2f,
        0f,
        TWO_PI,
    )
    return points.toFloatArray()
}

/** The vertices of a regular polygon fitted to the box, open (first point not repeated). */
internal fun regularPoints(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    sides: Int,
    startAngle: Float,
): FloatArray {
    val unit = mutableListOf<Float>()
    repeat(sides) { index ->
        val angle = startAngle + TWO_PI * index / sides
        unit.add(cos(angle))
        unit.add(sin(angle))
    }
    return fitted(unit.toFloatArray(), left, top, right, bottom)
}


private fun FloatArray.closedLoop(): FloatArray = this + floatArrayOf(this[0], this[1])

/** A regular polygon, fitted to the box. */
private fun regular(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    sides: Int,
    startAngle: Float,
): FloatArray = regularPoints(left, top, right, bottom, sides, startAngle).closedLoop()

/**
 * Stretches vertices given on the unit circle so their own bounding box becomes the drag box.
 *
 * Placing them on the inscribed *ellipse* instead is the obvious thing and is wrong: a hexagon's
 * vertices sit at ±60°, so it would reach only 86.6% of the way down a box the user dragged, and a
 * a pentagon leaves slack of its own. Every shape here fills what was dragged, so this
 * normalises each polygon against its own extent rather than against the circle it came from.
 */
private fun fitted(
    unit: FloatArray,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): FloatArray {
    val xs = unit.filterIndexed { index, _ -> index % 2 == 0 }
    val ys = unit.filterIndexed { index, _ -> index % 2 == 1 }
    val spanX = xs.max() - xs.min()
    val spanY = ys.max() - ys.min()
    val scaleX = if (spanX == 0f) 0f else (right - left) / spanX
    val scaleY = if (spanY == 0f) 0f else (bottom - top) / spanY
    return FloatArray(unit.size) { index ->
        if (index % 2 == 0) {
            left + (unit[index] - xs.min()) * scaleX
        } else {
            top + (unit[index] - ys.min()) * scaleY
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Solids
// ---------------------------------------------------------------------------------------------

/**
 * The axonometric offset every solid is built from: back faces sit up and to the right of front
 * ones by this much.
 *
 * Derived from the box rather than a constant, so a solid scales with the drag instead of needing a
 * depth control the reference does not have. The front face then occupies what is left, which is
 * what keeps the whole solid inside the box the user dragged.
 */
private fun depth(width: Float, height: Float): Pair<Float, Float> {
    val d = min(abs(width), abs(height)) * DEPTH_FRACTION
    return d * cos(PROJECTION_ANGLE) to d * sin(PROJECTION_ANGLE)
}

/**
 * A cube seen from the front, above and to the right.
 *
 * Exactly one corner is occluded — the back-bottom-left — so exactly its three edges are hidden.
 * That is the dotted L the reference glyph shows.
 */
private fun cube(left: Float, top: Float, right: Float, bottom: Float): ShapeTracing {
    val (dx, dy) = depth(right - left, bottom - top)
    val frontRight = right - dx
    val frontTop = top + dy
    // Front face corners, then the same four translated onto the back face.
    val ftlX = left; val ftlY = frontTop
    val ftrX = frontRight; val ftrY = frontTop
    val fbrX = frontRight; val fbrY = bottom
    val fblX = left; val fblY = bottom
    val btlX = ftlX + dx; val btlY = ftlY - dy
    val btrX = ftrX + dx; val btrY = ftrY - dy
    val bbrX = fbrX + dx; val bbrY = fbrY - dy
    val bblX = fblX + dx; val bblY = fblY - dy

    return ShapeTracing(
        solid = listOf(
            closed(ftlX, ftlY, ftrX, ftrY, fbrX, fbrY, fblX, fblY),
            floatArrayOf(btlX, btlY, btrX, btrY),
            floatArrayOf(btrX, btrY, bbrX, bbrY),
            floatArrayOf(ftlX, ftlY, btlX, btlY),
            floatArrayOf(ftrX, ftrY, btrX, btrY),
            floatArrayOf(fbrX, fbrY, bbrX, bbrY),
        ),
        hidden = listOf(
            floatArrayOf(btlX, btlY, bblX, bblY),
            floatArrayOf(bblX, bblY, bbrX, bbrY),
            floatArrayOf(fblX, fblY, bblX, bblY),
        ),
        // The hexagon a cube covers: every corner but the occluded one.
        fill = listOf(closed(fblX, fblY, ftlX, ftlY, btlX, btlY, btrX, btrY, bbrX, bbrY, fbrX, fbrY)),
    )
}

/** A square pyramid. The far base corner is occluded, so its three edges are the hidden ones. */
private fun pyramid(left: Float, top: Float, right: Float, bottom: Float): ShapeTracing {
    val (dx, dy) = depth(right - left, bottom - top)
    val frontRight = right - dx
    val flX = left; val flY = bottom
    val frX = frontRight; val frY = bottom
    val brX = frX + dx; val brY = frY - dy
    val blX = flX + dx; val blY = flY - dy
    val apexX = (flX + brX) / 2f
    val apexY = top

    return ShapeTracing(
        solid = listOf(
            floatArrayOf(flX, flY, frX, frY),
            floatArrayOf(frX, frY, brX, brY),
            floatArrayOf(apexX, apexY, flX, flY),
            floatArrayOf(apexX, apexY, frX, frY),
            floatArrayOf(apexX, apexY, brX, brY),
        ),
        hidden = listOf(
            floatArrayOf(blX, blY, flX, flY),
            floatArrayOf(blX, blY, brX, brY),
            floatArrayOf(apexX, apexY, blX, blY),
        ),
        fill = listOf(closed(apexX, apexY, brX, brY, frX, frY, flX, flY)),
    )
}

/** A right-triangular prism: a front triangle extruded back, hypotenuse rising to the left. */
private fun wedge(left: Float, top: Float, right: Float, bottom: Float): ShapeTracing {
    val (dx, dy) = depth(right - left, bottom - top)
    val frontRight = right - dx
    val frontTop = top + dy
    // Right angle at the bottom-left, hypotenuse from that top corner down to the bottom-right.
    val faX = left; val faY = bottom
    val fbX = frontRight; val fbY = bottom
    val fcX = left; val fcY = frontTop
    val baX = faX + dx; val baY = faY - dy
    val bbX = fbX + dx; val bbY = fbY - dy
    val bcX = fcX + dx; val bcY = fcY - dy

    return ShapeTracing(
        solid = listOf(
            closed(faX, faY, fbX, fbY, fcX, fcY),
            floatArrayOf(bcX, bcY, bbX, bbY),
            floatArrayOf(fcX, fcY, bcX, bcY),
            floatArrayOf(fbX, fbY, bbX, bbY),
        ),
        hidden = listOf(
            floatArrayOf(baX, baY, bcX, bcY),
            floatArrayOf(baX, baY, bbX, bbY),
            floatArrayOf(faX, faY, baX, baY),
        ),
        fill = listOf(closed(faX, faY, fcX, fcY, bcX, bcY, bbX, bbY, fbX, fbY)),
    )
}

/**
 * A sphere: its silhouette, plus the equator that is what makes it read as a solid rather than a
 * circle.
 *
 * Seen from slightly above, the near half of the equator is the lower arc — so that half is solid
 * and the upper half, running behind the body, is the dotted one.
 */
private fun sphere(left: Float, top: Float, right: Float, bottom: Float): ShapeTracing {
    val centreX = (left + right) / 2f
    val centreY = (top + bottom) / 2f
    val radiusX = (right - left) / 2f
    val radiusY = (bottom - top) / 2f
    val equatorY = radiusY * EQUATOR_FLATTEN

    val near = mutableListOf<Float>()
    appendArc(near, centreX, centreY, radiusX, equatorY, 0f, PI.toFloat())
    val far = mutableListOf<Float>()
    appendArc(far, centreX, centreY, radiusX, equatorY, PI.toFloat(), TWO_PI)

    val silhouette = ellipse(left, top, right, bottom)
    return ShapeTracing(
        solid = listOf(silhouette, near.toFloatArray()),
        hidden = listOf(far.toFloatArray()),
        fill = listOf(silhouette),
    )
}

/**
 * A cone: apex at the top of the box, an elliptical base at the bottom of it.
 *
 * The base reads as a circle seen from the same angle everything else here is seen from, so its
 * flattening is [EQUATOR_FLATTEN] against the *base's* own radius rather than the box's height — a
 * cone drawn tall must not get a base ellipse as deep as it is high.
 *
 * The sides run from the apex to the base's widest points, which for an ellipse are its ends, so
 * they meet it tangentially and the silhouette closes without a notch.
 */
private fun cone(left: Float, top: Float, right: Float, bottom: Float): ShapeTracing {
    val centreX = (left + right) / 2f
    val radiusX = (right - left) / 2f
    val baseY = baseDepth(radiusX, bottom - top)
    val centreY = bottom - baseY

    val near = mutableListOf<Float>()
    appendArc(near, centreX, centreY, radiusX, baseY, 0f, PI.toFloat())
    val far = mutableListOf<Float>()
    appendArc(far, centreX, centreY, radiusX, baseY, PI.toFloat(), TWO_PI)

    // Apex, down the right side, round the front of the base, back up the left to the apex.
    val silhouette = mutableListOf(centreX, top, right, centreY)
    appendArc(silhouette, centreX, centreY, radiusX, baseY, 0f, PI.toFloat())
    silhouette.add(centreX); silhouette.add(top)

    return ShapeTracing(
        solid = listOf(
            floatArrayOf(centreX, top, left, centreY),
            floatArrayOf(centreX, top, right, centreY),
            near.toFloatArray(),
        ),
        hidden = listOf(far.toFloatArray()),
        fill = listOf(silhouette.toFloatArray()),
    )
}

/**
 * A cylinder: two elliptical caps and the two sides between them.
 *
 * The top cap is drawn whole and solid — from above it is a rim you see all of. The bottom is the
 * sphere's equator again: near half solid, far half occluded by the body and so dotted. That
 * asymmetry is the entire cue that it is a tube rather than a rectangle with lens ends.
 */
private fun cylinder(left: Float, top: Float, right: Float, bottom: Float): ShapeTracing {
    val centreX = (left + right) / 2f
    val radiusX = (right - left) / 2f
    val capY = baseDepth(radiusX, bottom - top)
    val topY = top + capY
    val bottomY = bottom - capY

    val cap = mutableListOf<Float>()
    appendArc(cap, centreX, topY, radiusX, capY, 0f, TWO_PI)
    val near = mutableListOf<Float>()
    appendArc(near, centreX, bottomY, radiusX, capY, 0f, PI.toFloat())
    val far = mutableListOf<Float>()
    appendArc(far, centreX, bottomY, radiusX, capY, PI.toFloat(), TWO_PI)

    // Over the top cap, down the right side, round the front of the base, back up the left.
    val silhouette = mutableListOf<Float>()
    appendArc(silhouette, centreX, topY, radiusX, capY, PI.toFloat(), TWO_PI)
    silhouette.add(right); silhouette.add(bottomY)
    appendArc(silhouette, centreX, bottomY, radiusX, capY, 0f, PI.toFloat())
    silhouette.add(left); silhouette.add(topY)

    return ShapeTracing(
        solid = listOf(
            cap.toFloatArray(),
            floatArrayOf(left, topY, left, bottomY),
            floatArrayOf(right, topY, right, bottomY),
            near.toFloatArray(),
        ),
        hidden = listOf(far.toFloatArray()),
        fill = listOf(silhouette.toFloatArray()),
    )
}

/**
 * How deep the elliptical base of a cone or cylinder sits, in page units.
 *
 * Measured against the base's own radius so the viewing angle stays the same whatever the box's
 * proportions, then capped at a share of the height so a wide flat box cannot hand a cylinder two
 * caps taller than the body between them.
 */
private fun baseDepth(radiusX: Float, height: Float): Float =
    (radiusX * EQUATOR_FLATTEN).coerceAtMost(height * MAX_CAP_FRACTION)

// ---------------------------------------------------------------------------------------------
// Sampling
// ---------------------------------------------------------------------------------------------

/** Appends an arc from [from] to [to] radians, including both endpoints. */
private fun appendArc(
    into: MutableList<Float>,
    centreX: Float,
    centreY: Float,
    radiusX: Float,
    radiusY: Float,
    from: Float,
    to: Float,
) {
    val samples = arcSamples(radiusX, radiusY, abs(to - from))
    repeat(samples + 1) { index ->
        val angle = from + (to - from) * index / samples
        into.add(centreX + radiusX * cos(angle))
        into.add(centreY + radiusY * sin(angle))
    }
}

/**
 * How many segments an arc of [sweep] radians is drawn with.
 *
 * One sample per [SEGMENT_DP] of arc length. The error that leaves is the chord's sagitta, `s²/8r`,
 * which at any size a page shape reaches is under a hundredth of a page unit — far below what any
 * zoom can show. Because it is driven by *length*, the same function serves a 28dp picker chip and
 * a 400dp shape on the page: the chip gets about two dozen segments, the page shape gets the 1°
 * step `docs/inkPlan.md` §5.3 asks for, and neither is a special case.
 *
 * The floor matters more than it looks: without it a chip-sized ellipse would be a hexagon.
 */
private fun arcSamples(radiusX: Float, radiusY: Float, sweep: Float): Int {
    val meanRadius = (abs(radiusX) + abs(radiusY)) / 2f
    val length = meanRadius * sweep
    val samples = ceil(length / SEGMENT_DP).toInt()
    return samples.coerceIn(MIN_ARC_SAMPLES, MAX_ARC_SAMPLES)
}

/** Repeats the first point at the end, which is what lets every polyline be stroked the same way. */
private fun closed(vararg points: Float): FloatArray =
    points + floatArrayOf(points[0], points[1])

// Not `const`: a const initializer cannot call toFloat(), and spelling the digits out by hand is
// how a wrong one gets in.
internal val HALF_PI = (PI / 2.0).toFloat()
private val TWO_PI = (PI * 2.0).toFloat()

/** Arrowhead wings open this far off the shaft — 24°, which reads as a head without looking barbed. */
private const val HEAD_SPREAD = 0.42f
private const val HEAD_FRACTION = 0.22f

/** Past this, a long arrow's head stops growing; proportion alone makes it absurd at page scale. */
private const val HEAD_MAX_DP = 26f

private const val CORNER_FRACTION = 0.18f

private const val DEPTH_FRACTION = 0.28f
private const val PROJECTION_ANGLE = 0.5236f

/**
 * How flat a rim ellipse sits, i.e. how far above the shape the viewer is.
 *
 * Shared with `ShapeSeed`, which has to place the same rim in segments: a preview and a committed
 * shape that disagree about the viewing angle are two different drawings of the same thing.
 */
internal const val EQUATOR_FLATTEN = 0.28f

/** Ceiling on a cone or cylinder cap, so a wide short box still leaves a body between its ends. */
internal const val MAX_CAP_FRACTION = 0.22f

private const val SEGMENT_DP = 3.5f
private const val MIN_ARC_SAMPLES = 12
private const val MAX_ARC_SAMPLES = 360
