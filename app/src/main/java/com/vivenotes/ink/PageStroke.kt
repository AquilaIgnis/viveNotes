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
)

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

internal fun PageStroke.pageBounds(): InkBounds? = stroke.shape.computeBoundingBox()?.let {
    InkBounds(
        left = it.xMin * scaleX + offsetX,
        top = it.yMin * scaleY + offsetY,
        right = it.xMax * scaleX + offsetX,
        bottom = it.yMax * scaleY + offsetY,
    )
}

internal fun PageStroke.strokeToPageTransform(): AffineTransform =
    ImmutableAffineTransform(scaleX, 0f, offsetX, 0f, scaleY, offsetY)

private fun PageStroke.pageToStrokeTransform(): AffineTransform =
    strokeToPageTransform().computeInverse()

/** Whether this stroke's page-space geometry is touched at all by an eraser mask. */
internal fun PageStroke.touches(mask: Stroke): Boolean =
    stroke.shape.computeCoverageIsGreaterThan(
        other = mask.shape,
        coverageThreshold = 0f,
        otherShapeToThis = pageToStrokeTransform(),
    )

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
    if (targets.isEmpty()) return this
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
                listOf(pageStroke.copy(stroke = cut))
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
    if (targets.isEmpty()) return this
    return flatMap { pageStroke ->
        if (pageStroke.id !in targets) {
            listOf(pageStroke)
        } else if (pageStroke.stroke.isDrawnFromOutlines) {
            if (pageStroke.touches(mask)) emptyList() else listOf(pageStroke)
        } else {
            pageStroke.stroke
                .split(strokeToWorldTransform = pageStroke.strokeToPageTransform(), tolerance = 0f)
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
    val hits = filter { stroke ->
        stroke.isInsideLasso(path, edgeTolerance)
    }
    val hitIds = hits.map(PageStroke::id).toSet()
    val hitGroups = hits.mapNotNull(PageStroke::groupId).toSet()
    // A stored stroke remains one logical object after a partial erase, and touching any member of
    // a group selects the complete group. This keeps delete, colour and movement from affecting
    // geometry that was not represented by the selection rectangle.
    val selected = filter { it.id in hitIds || it.groupId != null && it.groupId in hitGroups }
    if (selected.isEmpty()) return null
    val bounds = selected.mapNotNull(PageStroke::pageBounds).union() ?: return null
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

/** Replays a persisted move against the projections that existed inside its original lasso. */
internal fun List<PageStroke>.replayMove(
    path: List<InkPoint>,
    targetIds: Collection<String>,
    dx: Float,
    dy: Float,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (path.size < 3 || targets.isEmpty()) return this
    return map { stroke ->
        val selected = stroke.id in targets &&
            stroke.isInsideLasso(path, DEFAULT_LASSO_EDGE_TOLERANCE)
        if (selected) {
            stroke.copy(offsetX = stroke.offsetX + dx, offsetY = stroke.offsetY + dy)
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
    return map { stroke ->
        val selected = stroke.id in targets &&
            stroke.isInsideLasso(path, DEFAULT_LASSO_EDGE_TOLERANCE)
        if (selected) stroke.scaledAround(anchor, scaleX, scaleY) else stroke
    }
}

private fun List<InkBounds>.union(): InkBounds? {
    val first = firstOrNull() ?: return null
    return drop(1).fold(first) { result, next ->
        InkBounds(
            left = minOf(result.left, next.left),
            top = minOf(result.top, next.top),
            right = maxOf(result.right, next.right),
            bottom = maxOf(result.bottom, next.bottom),
        )
    }
}

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
 * Tests the actual rendered outline instead of the object's bounding-box corners. Curved lassos
 * can closely enclose triangular or circular ink while naturally excluding the unused corners of
 * that rectangle. The small tolerance absorbs finger/stylus jitter right along a visible edge.
 */
private fun PageStroke.isInsideLasso(polygon: List<InkPoint>, edgeTolerance: Float): Boolean {
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

private fun pointInOrNearPolygon(
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

private const val DEFAULT_LASSO_EDGE_TOLERANCE = 4f
