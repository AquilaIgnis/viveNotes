package com.vivenotes.ink

import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.ExperimentalInkEraserApi
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

/** Page-space rectangle used by lasso hit-testing and its selection affordance. */
data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val center: InkPoint get() = InkPoint((left + right) / 2f, (top + bottom) / 2f)

    fun contains(point: InkPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun translated(dx: Float, dy: Float): InkBounds =
        InkBounds(left + dx, top + dy, right + dx, bottom + dy)
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
        left = it.xMin + offsetX,
        top = it.yMin + offsetY,
        right = it.xMax + offsetX,
        bottom = it.yMax + offsetY,
    )
}

internal fun PageStroke.strokeToPageTransform(): AffineTransform =
    ImmutableAffineTransform.translate(ImmutableVec(offsetX, offsetY))

private fun PageStroke.pageToStrokeTransform(): AffineTransform =
    ImmutableAffineTransform.translate(ImmutableVec(-offsetX, -offsetY))

/** The strokes that existed at erase time and actually overlap this mask. */
internal fun List<PageStroke>.targetsFor(mask: Stroke): List<String> =
    filter {
        it.stroke.shape.computeCoverageIsGreaterThan(
            other = mask.shape,
            coverageThreshold = 0f,
            otherShapeToThis = it.pageToStrokeTransform(),
        )
    }.map(PageStroke::id).distinct()

/** Replays one persisted normal-eraser operation without changing stroke identity or inputs. */
@OptIn(ExperimentalInkEraserApi::class)
internal fun List<PageStroke>.subtract(mask: Stroke, targetIds: Collection<String>): List<PageStroke> {
    val targets = targetIds.toSet()
    if (targets.isEmpty()) return this
    return flatMap { pageStroke ->
        if (pageStroke.id !in targets) {
            listOf(pageStroke)
        } else {
            pageStroke.stroke
                .subtract(
                    maskShape = mask.shape,
                    maskToWorldTransform = AffineTransform.IDENTITY,
                    strokeToWorldTransform = pageStroke.strokeToPageTransform(),
                )
                .split(strokeToWorldTransform = pageStroke.strokeToPageTransform(), tolerance = 0f)
                .map { component -> pageStroke.copy(stroke = component) }
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

/** Selects every live object whose visual centre falls inside the closed free-form lasso. */
internal fun List<PageStroke>.selectWithLasso(path: List<InkPoint>): InkLassoSelection? {
    if (path.size < 3) return null
    val selected = filter { stroke ->
        stroke.pageBounds()?.center?.let { pointInPolygon(it, path) } == true
    }
    if (selected.isEmpty()) return null
    val bounds = selected.mapNotNull(PageStroke::pageBounds).union() ?: return null
    return InkLassoSelection(
        path = path,
        targetIds = selected.map(PageStroke::id).toSet(),
        projections = selected.map(PageStroke::projectionKey).toSet(),
        bounds = bounds,
    )
}

/** Applies the exact live projection set captured when the gesture began. */
internal fun List<PageStroke>.moveSelected(move: InkLassoMove): List<PageStroke> = map { stroke ->
    if (stroke.projectionKey in move.projections) {
        stroke.copy(offsetX = stroke.offsetX + move.dx, offsetY = stroke.offsetY + move.dy)
    } else {
        stroke
    }
}

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
            stroke.pageBounds()?.center?.let { pointInPolygon(it, path) } == true
        if (selected) {
            stroke.copy(offsetX = stroke.offsetX + dx, offsetY = stroke.offsetY + dy)
        } else {
            stroke
        }
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
