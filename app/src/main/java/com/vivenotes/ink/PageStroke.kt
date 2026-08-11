package com.vivenotes.ink

import androidx.ink.brush.SelfOverlap
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke

/**
 * A finished stroke together with the row it came from.
 *
 * [Stroke] carries no identity of its own — it is brush plus inputs plus a derived mesh — so erasing
 * and syncing need the row id alongside it. Kept as a pair rather than pushed into the model, since
 * `Stroke` holds a native mesh and has no business in a type that must stay portable.
 */
data class PageStroke(
    val id: String,
    val stroke: Stroke,
    /** Translation from this projection's stroke coordinates into page coordinates. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Axis-aligned page transform. Resize operations compose into these values. */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** Stable codec metadata needed when a selected stroke is duplicated. */
    val brushFamily: String = "pressure-pen",
    val brushVersion: Int = InkCodec.BRUSH_VERSION,
    val stabilization: Int = 0,
    val groupId: String? = null,
) {
    /**
     * This projection's page-space rectangle, or null once a cut has left it with no geometry.
     *
     * Computed once per instance rather than per call. `computeBoundingBox` is a JNI hop into the
     * mesh, and the two callers that matter ask for it on *every* stroke: the draw pass, which culls
     * against the window on every frame, and lasso replay, which measures what an operation is about
     * to move. At page scale that was tens of thousands of crossings per frame.
     *
     * Outside the constructor on purpose — it is derived, so it stays out of `equals`, `hashCode` and
     * `copy`, and a `copy` that changes the offset, the scale or the stroke gets a fresh one.
     */
    internal val pageBounds: InkBounds? by lazy {
        stroke.shape.computeBoundingBox()?.let {
            InkBounds(
                left = it.xMin * scaleX + offsetX,
                top = it.yMin * scaleY + offsetY,
                right = it.xMax * scaleX + offsetX,
                bottom = it.yMax * scaleY + offsetY,
            )
        }
    }
}

/** One point in the page coordinate system, where persisted ink and lasso paths live. */
data class InkPoint(val x: Float, val y: Float)

/** Identifies one live projection, including a disconnected piece that shares its stored row id. */
data class InkProjectionKey(val strokeId: String, val strokeIdentity: Int)

/** A completed lasso drag, ready to apply immediately and persist for replay. */
data class InkLassoMove(
    val path: List<InkPoint>,
    val targetIds: Set<String>,
    val projections: Set<InkProjectionKey>,
    val dx: Float,
    val dy: Float,
)

/** A completed corner-handle drag, scaling selected projections around the opposite corner. */
data class InkLassoResize(
    val path: List<InkPoint>,
    val targetIds: Set<String>,
    val projections: Set<InkProjectionKey>,
    val anchor: InkPoint,
    val scaleX: Float,
    val scaleY: Float,
)

/** Page-space rectangle used by the selection affordance and its move/resize hit targets. */
data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val center: InkPoint get() = InkPoint((left + right) / 2f, (top + bottom) / 2f)

    fun contains(point: InkPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun translated(dx: Float, dy: Float): InkBounds =
        InkBounds(left + dx, top + dy, right + dx, bottom + dy)

    fun scaled(anchor: InkPoint, scaleX: Float, scaleY: Float): InkBounds {
        val x1 = anchor.x + (left - anchor.x) * scaleX
        val x2 = anchor.x + (right - anchor.x) * scaleX
        val y1 = anchor.y + (top - anchor.y) * scaleY
        val y2 = anchor.y + (bottom - anchor.y) * scaleY
        return InkBounds(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }
}

data class InkLassoSelection(
    val path: List<InkPoint>,
    val targetIds: Set<String>,
    val projections: Set<InkProjectionKey>,
    val bounds: InkBounds,
)

internal val PageStroke.projectionKey: InkProjectionKey
    get() = InkProjectionKey(id, System.identityHashCode(stroke))

internal fun PageStroke.strokeToPageTransform(): AffineTransform =
    ImmutableAffineTransform(scaleX, 0f, offsetX, 0f, scaleY, offsetY)

private fun PageStroke.pageToStrokeTransform(): AffineTransform =
    strokeToPageTransform().computeInverse()

/**
 * Whether this stroke still has any geometry.
 *
 * [Stroke.subtract] can cut a mesh away entirely, and Ink's shape comparisons do not tolerate the
 * result: `computeCoverageIsGreaterThan` reaches a native `CHECK failed: !meshes_.empty()` and
 * **aborts the process** rather than returning false. So an empty mesh has to be caught here, in
 * Kotlin, before it is ever handed to one.
 *
 * `computeBoundingBox` is the safe test — it returns null for an empty mesh, which is what
 * [pageBounds] already relies on.
 */
private val Stroke.hasGeometry: Boolean
    get() = shape.computeBoundingBox() != null

/**
 * Whether this stroke's page-space geometry is touched at all by an eraser mask.
 *
 * Guarded on both sides because this runs over *every* stroke on the page: one empty mesh anywhere
 * in the list took the whole app down on the next eraser touch, wherever that touch landed.
 */
internal fun PageStroke.touches(mask: Stroke): Boolean {
    if (!stroke.hasGeometry || !mask.hasGeometry) return false
    return stroke.shape.computeCoverageIsGreaterThan(
        other = mask.shape,
        coverageThreshold = 0f,
        otherShapeToThis = pageToStrokeTransform(),
    )
}

/** The strokes that existed at erase time and actually overlap this mask. */
internal fun List<PageStroke>.targetsFor(mask: Stroke): List<String> =
    filter { it.touches(mask) }.map(PageStroke::id).distinct()

/**
 * Whether this stroke can only be drawn from the outlines of its mesh.
 *
 * `SelfOverlap.DISCARD` forces the path renderer — the mesh renderer refuses the mode, and that is
 * the documented trade for a highlighter that does not double its opacity where it crosses itself.
 * The path renderer draws from outlines, and draws *nothing* for a mesh that has none.
 *
 * That matters because [Stroke.split] returns pieces with no outlines, while [Stroke.subtract]
 * keeps them. Splitting one of these strokes therefore does not cut it — it erases it from the
 * screen entirely, while leaving its geometry intact and its row in the database.
 */
private val Stroke.isDrawnFromOutlines: Boolean
    get() = brush.family.coats.any { coat ->
        coat.paintPreferences.all { it.selfOverlap == SelfOverlap.DISCARD }
    }

/**
 * Replays one persisted normal-eraser operation without changing stroke identity or inputs.
 *
 * Splitting the cut into its disconnected regions is what gives each piece its own projection, so
 * that a later Object erase or lasso can take one of them. A stroke that is drawn from its outlines
 * cannot afford that (see [isDrawnFromOutlines]) and stays a single projection: one highlighter is
 * one object however many times it has been cut.
 */
@OptIn(ExperimentalInkEraserApi::class)
internal fun List<PageStroke>.subtract(mask: Stroke, targetIds: Collection<String>): List<PageStroke> {
    val targets = targetIds.toSet()
    if (targets.isEmpty() || !mask.hasGeometry) return this
    return flatMap { pageStroke ->
        if (pageStroke.id !in targets) {
            listOf(pageStroke)
        } else {
            val cut = pageStroke.stroke.subtract(
                maskShape = mask.shape,
                maskToWorldTransform = AffineTransform.IDENTITY,
                strokeToWorldTransform = pageStroke.strokeToPageTransform(),
            )
            if (cut.isDrawnFromOutlines) {
                // Erased down to nothing is erased. The other branch gets this for free — an empty
                // mesh splits into no components — but this one would keep a stroke with no
                // geometry, invisible on the page and fatal to the next comparison it meets.
                if (cut.hasGeometry) listOf(pageStroke.copy(stroke = cut)) else emptyList()
            } else {
                cut.split(strokeToWorldTransform = pageStroke.strokeToPageTransform(), tolerance = 0f)
                    .map { component -> pageStroke.copy(stroke = component) }
            }
        }
    }
}

/**
 * Removes only the disconnected regions touched by an Object-mode eraser mask.
 *
 * A partially erased stroke still has one database id and one input batch, but its mesh may have
 * several disconnected regions. Splitting at zero tolerance makes those regions independent; each
 * survivor becomes its own [PageStroke] projection while retaining the shared storage id needed to
 * replay later operations.
 *
 * A stroke drawn from its outlines is not split — splitting would blank it (see
 * [isDrawnFromOutlines]) — so it is the object, whole, and touching it removes all of it.
 */
@OptIn(ExperimentalInkEraserApi::class)
internal fun List<PageStroke>.eraseObjects(
    mask: Stroke,
    targetIds: Collection<String>,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (targets.isEmpty() || !mask.hasGeometry) return this
    return flatMap { pageStroke ->
        if (pageStroke.id !in targets) {
            listOf(pageStroke)
        } else if (pageStroke.stroke.isDrawnFromOutlines) {
            if (pageStroke.touches(mask)) emptyList() else listOf(pageStroke)
        } else {
            pageStroke.stroke
                .split(strokeToWorldTransform = pageStroke.strokeToPageTransform(), tolerance = 0f)
                // A component with no geometry is nothing to keep, and nothing safe to compare.
                .filter { it.hasGeometry }
                .filterNot { component ->
                    component.shape.computeCoverageIsGreaterThan(
                        other = mask.shape,
                        coverageThreshold = 0f,
                        otherShapeToThis = pageStroke.pageToStrokeTransform(),
                    )
                }
                .map { component -> pageStroke.copy(stroke = component) }
        }
    }
}

/** Selects objects whose visible ink outline is enclosed by the free-form lasso. */
internal fun List<PageStroke>.selectWithLasso(
    path: List<InkPoint>,
    edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE,
): InkLassoSelection? {
    if (path.size < 3) return null
    val lasso = LassoShape(path, edgeTolerance)
    val hits = filter { stroke -> lasso.contains(stroke) }
    val hitIds = hits.map(PageStroke::id).toSet()
    val hitGroups = hits.mapNotNull(PageStroke::groupId).toSet()
    // A stored stroke remains one logical object after a partial erase, and touching any member of
    // a group selects the complete group. This keeps delete, colour and movement from affecting
    // geometry that was not represented by the selection rectangle.
    val selected = filter { it.id in hitIds || it.groupId != null && it.groupId in hitGroups }
    if (selected.isEmpty()) return null
    val bounds = selected.mapNotNull(PageStroke::pageBounds).unionBounds() ?: return null
    return InkLassoSelection(
        path = path,
        targetIds = selected.map(PageStroke::id).toSet(),
        projections = selected.map(PageStroke::projectionKey).toSet(),
        bounds = bounds,
    )
}

/** Rebinds selected immutable meshes to a brush carrying the requested colour. */
internal fun List<PageStroke>.recolor(ids: Collection<String>, colorArgb: Int): List<PageStroke> {
    val targets = ids.toSet()
    return map { pageStroke ->
        if (pageStroke.id in targets) {
            pageStroke.copy(
                stroke = pageStroke.stroke.copy(
                    pageStroke.stroke.brush.copyWithColorIntArgb(colorArgb),
                ),
            )
        } else {
            pageStroke
        }
    }
}

internal fun List<PageStroke>.regroup(groups: Map<String, String?>): List<PageStroke> = map { stroke ->
    if (stroke.id in groups) stroke.copy(groupId = groups[stroke.id]) else stroke
}

/** Makes a self-contained translated copy, baking the live page offset into its input coordinates. */
internal fun PageStroke.translatedCopy(dx: Float, dy: Float): Stroke {
    val moved = MutableStrokeInputBatch()
    val source = stroke.inputs
    repeat(source.size) { index ->
        val input = source[index]
        moved.add(
            type = input.toolType,
            x = input.x * scaleX + offsetX + dx,
            y = input.y * scaleY + offsetY + dy,
            elapsedTimeMillis = input.elapsedTimeMillis,
            strokeUnitLengthCm = input.strokeUnitLengthCm,
            pressure = input.pressure,
            tiltRadians = input.tiltRadians,
            orientationRadians = input.orientationRadians,
        )
    }
    moved.setNoiseSeed(source.getNoiseSeed())
    return Stroke(stroke.brush, moved.toImmutable())
}

/** Applies the exact live projection set captured when the gesture began. */
internal fun List<PageStroke>.moveSelected(move: InkLassoMove): List<PageStroke> = map { stroke ->
    if (stroke.projectionKey in move.projections) {
        stroke.copy(offsetX = stroke.offsetX + move.dx, offsetY = stroke.offsetY + move.dy)
    } else {
        stroke
    }
}

/** Applies a world-space corner resize after each selected stroke's existing page transform. */
internal fun List<PageStroke>.resizeSelected(resize: InkLassoResize): List<PageStroke> = map { stroke ->
    if (stroke.projectionKey in resize.projections) {
        stroke.scaledAround(resize.anchor, resize.scaleX, resize.scaleY)
    } else {
        stroke
    }
}

private fun PageStroke.scaledAround(anchor: InkPoint, x: Float, y: Float): PageStroke = copy(
    scaleX = scaleX * x,
    scaleY = scaleY * y,
    offsetX = anchor.x + (offsetX - anchor.x) * x,
    offsetY = anchor.y + (offsetY - anchor.y) * y,
)

/**
 * Replays a persisted move against the projections that existed inside its original lasso.
 *
 * **This is where the page's origin corner is enforced for ink** — [PageBounds]. A shape, a table or
 * an equation is a position in the document and can be held to the rule where it is written down;
 * ink is a stroke plus every operation ever replayed over it, so the only place its final position
 * is known is here, at the end of the fold that produces it.
 *
 * For a move recorded by a build that has the rule, this changes nothing: the gesture had already
 * clamped the delta against a selection the ink was part of, so the same limit computed again is the
 * same limit. It fires only for the pages that were dragged off the corner before the rule existed,
 * and it brings the whole moved set back **together**, by the one delta it shares, rather than
 * shuffling each stroke to the wall on its own.
 */
internal fun List<PageStroke>.replayMove(
    path: List<InkPoint>,
    targetIds: Collection<String>,
    dx: Float,
    dy: Float,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (path.size < 3 || targets.isEmpty()) return this
    // Decided once and remembered: the lasso test walks every outline vertex of every stroke, and
    // the clamp below needs to know the answer before it can measure what is moving.
    val lasso = LassoShape(path)
    val selected = map { it.id in targets && lasso.contains(it) }
    val delta = movingBounds(selected)
        ?.let { PageBounds.clampTranslation(it, dx, dy) }
        ?: InkPoint(dx, dy)
    return mapIndexed { index, stroke ->
        if (selected[index]) {
            stroke.copy(offsetX = stroke.offsetX + delta.x, offsetY = stroke.offsetY + delta.y)
        } else {
            stroke
        }
    }
}

/** Replays a persisted resize against the live projections inside its original lasso. */
internal fun List<PageStroke>.replayResize(
    path: List<InkPoint>,
    targetIds: Collection<String>,
    anchor: InkPoint,
    scaleX: Float,
    scaleY: Float,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (path.size < 3 || targets.isEmpty()) return this
    // A plain move stores no scale, and every stored move is replayed through here — so on most
    // pages this is the whole of the work and none of it changes anything. Provably nothing:
    // `clampScale` only ever *lowers* a scale towards 1 and never below it, so an identity scale
    // survives the clamp, and `scaledAround` by 1 returns the stroke it was given. On the page this
    // was found on it was 228 ms of a 3.8 s open, spent to arrive back where it started.
    if (scaleX == 1f && scaleY == 1f) return this
    val lasso = LassoShape(path)
    val selected = map { it.id in targets && lasso.contains(it) }
    // Held to the origin corner for the reason [replayMove] gives, and by the same one measurement:
    // a resize about a far corner drags the near edges towards it.
    val scale = movingBounds(selected)
        ?.let { PageBounds.clampScale(it, anchor, scaleX, scaleY) }
        ?: InkPoint(scaleX, scaleY)
    return mapIndexed { index, stroke ->
        if (selected[index]) stroke.scaledAround(anchor, scale.x, scale.y) else stroke
    }
}

/** The rectangle around everything a replayed operation is about to move, in page units. */
private fun List<PageStroke>.movingBounds(selected: List<Boolean>): InkBounds? =
    filterIndexed { index, _ -> selected[index] }
        .mapNotNull(PageStroke::pageBounds)
        .unionBounds()

private fun pointInPolygon(point: InkPoint, polygon: List<InkPoint>): Boolean {
    var inside = false
    var previous = polygon.last()
    polygon.forEach { current ->
        val crosses = (current.y > point.y) != (previous.y > point.y)
        if (crosses) {
            val crossingX = (previous.x - current.x) * (point.y - current.y) /
                (previous.y - current.y) + current.x
            if (point.x < crossingX) inside = !inside
        }
        previous = current
    }
    return inside
}

/**
 * A lasso polygon, prepared once so that testing a whole page against it does not walk every mesh.
 *
 * The exact test in [containsExactly] reads every outline vertex of every stroke through JNI, which
 * is the right answer and far too much of it: replaying the page-sized lasso that repairs a drawing's
 * position cost 253 ms of a page open, and an interactive lasso pays the same per gesture. Both
 * shortcuts below are *decisions*, never approximations — each one reaches a conclusion the exact
 * walk would have reached, using the bounding box the stroke already cached.
 */
internal class LassoShape(
    val path: List<InkPoint>,
    private val edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE,
) {
    val usable: Boolean = path.size >= 3

    // The polygon's own extent, grown by the tolerance, so "outside this box" means "outside the
    // tolerant test" and not merely "outside the polygon".
    private val minX = path.minOf { it.x } - edgeTolerance
    private val minY = path.minOf { it.y } - edgeTolerance
    private val maxX = path.maxOf { it.x } + edgeTolerance
    private val maxY = path.maxOf { it.y } + edgeTolerance

    /**
     * Whether every turn goes the same way, and so whether a box may be accepted whole.
     *
     * A convex polygon contains the convex hull of any points it contains, so four corners inside it
     * put the entire rectangle inside it — and every outline vertex lies within that rectangle. A
     * lasso drawn by hand usually is not convex, and then only [couldContain] applies.
     */
    val acceptsWholeBox: Boolean = isConvex(path)

    /**
     * Whether any part of a stroke this size could be inside the lasso at all.
     *
     * False is a decision, not a guess: the bounding box is tight, so each of its edges is touched by
     * some outline vertex, and a box reaching past the grown extent therefore has a vertex the exact
     * walk would have failed on.
     */
    fun couldContain(bounds: InkBounds): Boolean =
        bounds.left >= minX && bounds.top >= minY && bounds.right <= maxX && bounds.bottom <= maxY

    fun contains(stroke: PageStroke): Boolean {
        if (!usable) return false
        val bounds = stroke.pageBounds ?: return false
        if (!couldContain(bounds)) return false
        // Tested without the tolerance, so this only ever concludes what the tolerant walk would
        // also have concluded.
        if (acceptsWholeBox && corners(bounds).all { pointInPolygon(it, path) }) return true
        return stroke.containsExactly(path, edgeTolerance)
    }

    private fun corners(bounds: InkBounds): List<InkPoint> = listOf(
        InkPoint(bounds.left, bounds.top),
        InkPoint(bounds.right, bounds.top),
        InkPoint(bounds.right, bounds.bottom),
        InkPoint(bounds.left, bounds.bottom),
    )
}

private fun isConvex(polygon: List<InkPoint>): Boolean {
    if (polygon.size < 3) return false
    var sign = 0
    polygon.indices.forEach { index ->
        val a = polygon[index]
        val b = polygon[(index + 1) % polygon.size]
        val c = polygon[(index + 2) % polygon.size]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        // Collinear vertices turn neither way and constrain nothing.
        if (cross != 0f) {
            val turn = if (cross > 0f) 1 else -1
            if (sign == 0) sign = turn else if (sign != turn) return false
        }
    }
    return sign != 0
}

/**
 * Tests the actual rendered outline instead of the object's bounding-box corners. Curved lassos
 * can closely enclose triangular or circular ink while naturally excluding the unused corners of
 * that rectangle. The small tolerance absorbs finger/stylus jitter right along a visible edge.
 */
private fun PageStroke.containsExactly(polygon: List<InkPoint>, edgeTolerance: Float): Boolean {
    val position = MutableVec()
    var outlineVertexCount = 0
    val shape = stroke.shape
    repeat(shape.getRenderGroupCount()) { groupIndex ->
        repeat(shape.getOutlineCount(groupIndex)) { outlineIndex ->
            repeat(shape.getOutlineVertexCount(groupIndex, outlineIndex)) { vertexIndex ->
                shape.populateOutlinePosition(groupIndex, outlineIndex, vertexIndex, position)
                outlineVertexCount++
                val pagePoint = InkPoint(
                    x = position.x * scaleX + offsetX,
                    y = position.y * scaleY + offsetY,
                )
                if (!pointInOrNearPolygon(pagePoint, polygon, edgeTolerance)) return false
            }
        }
    }
    return outlineVertexCount > 0
}

internal fun pointInOrNearPolygon(
    point: InkPoint,
    polygon: List<InkPoint>,
    edgeTolerance: Float,
): Boolean {
    if (pointInPolygon(point, polygon)) return true
    val toleranceSquared = edgeTolerance.coerceAtLeast(0f).let { it * it }
    if (toleranceSquared == 0f) return false
    var previous = polygon.last()
    polygon.forEach { current ->
        if (point.distanceSquaredToSegment(previous, current) <= toleranceSquared) return true
        previous = current
    }
    return false
}

private fun InkPoint.distanceSquaredToSegment(start: InkPoint, end: InkPoint): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) {
        val pointDx = x - start.x
        val pointDy = y - start.y
        return pointDx * pointDx + pointDy * pointDy
    }
    val fraction = (((x - start.x) * dx + (y - start.y) * dy) / lengthSquared)
        .coerceIn(0f, 1f)
    val nearestX = start.x + fraction * dx
    val nearestY = start.y + fraction * dy
    val pointDx = x - nearestX
    val pointDy = y - nearestY
    return pointDx * pointDx + pointDy * pointDy
}

internal const val DEFAULT_LASSO_EDGE_TOLERANCE = 4f
