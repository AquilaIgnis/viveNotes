package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.sqrt

/** The two controls of one cubic span. Endpoints remain on [ShapeSegment]. */
@Serializable
data class ShapeCubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun translated(dx: Float, dy: Float) = ShapeCubic(x1 + dx, y1 + dy, x2 + dx, y2 + dy)

    fun scaledAbout(ax: Float, ay: Float, sx: Float, sy: Float) = ShapeCubic(
        ax + (x1 - ax) * sx, ay + (y1 - ay) * sy,
        ax + (x2 - ax) * sx, ay + (y2 - ay) * sy,
    )
}

/** A curve's on-path vertex, addressed independently of its coordinates. */
data class ShapeVertex(val index: Int, val x: Float, val y: Float)

/** A proposed on-path vertex between two existing vertices, addressed by the span to split. */
data class CurveInsertionPoint(val segmentId: String, val x: Float, val y: Float)

/** An existing vertex, addressed by its segment so a stale target cannot remove another point. */
data class CurveRemovalPoint(val segmentId: String, val atEnd: Boolean, val index: Int, val x: Float, val y: Float)

fun Outline.Shape.curveRemovalPoints(): List<CurveRemovalPoint> {
    val points = vertices()
    if (lockGroup != null || points.size <= 2) return emptyList()
    return points.map { point ->
        val atEnd = point.index == segments.size
        CurveRemovalPoint(
            segments[if (atEnd) segments.lastIndex else point.index].id,
            atEnd, point.index, point.x, point.y,
        )
    }
}

/** Remove the chosen vertex and refit through the remaining points, keeping at least two. */
fun Outline.Shape.withRemovedVertex(segmentId: String, atEnd: Boolean): Outline.Shape {
    if (kind != ShapeKind.Curve || lockGroup != null) return this
    val points = vertices()
    if (points.size <= 2) return this
    val span = segments.indexOfFirst { it.id == segmentId }
    if (span < 0 || (atEnd && span != segments.lastIndex)) return this
    val index = if (atEnd) span + 1 else span
    val remaining = points.filterIndexed { position, _ -> position != index }
        .mapIndexed { position, point -> point.copy(index = position) }
    // An interior removal joins the incoming span to the next vertex and drops the outgoing span.
    val edges = segments.filterIndexed { position, _ ->
        position != if (index == points.lastIndex) segments.lastIndex else index
    }
    return copy(segments = smoothCurve(remaining, edges)).withRecomputedBounds()
}

fun Outline.Shape.curveInsertionPoints(): List<CurveInsertionPoint> {
    if (kind != ShapeKind.Curve || lockGroup != null) return emptyList()
    return segments.mapNotNull { span ->
        span.cubic?.let { c ->
            CurveInsertionPoint(
                span.id,
                (span.x1 + 3f * c.x1 + 3f * c.x2 + span.x2) / 8f,
                (span.y1 + 3f * c.y1 + 3f * c.y2 + span.y2) / 8f,
            )
        }
    }
}

fun Outline.Shape.vertices(): List<ShapeVertex> {
    if (kind != ShapeKind.Curve || segments.isEmpty()) return emptyList()
    return segments.mapIndexed { index, segment -> ShapeVertex(index, segment.x1, segment.y1) } +
        segments.last().let { ShapeVertex(segments.size, it.x2, it.y2) }
}

fun Outline.Shape.vertexNear(x: Float, y: Float, reach: Float): ShapeVertex? = vertices()
    .minByOrNull { hypot(it.x - x, it.y - y) }
    ?.takeIf { hypot(it.x - x, it.y - y) <= reach }

/** Refit the smooth curve through its vertices, with tangent lengths following the new spacing. */
fun Outline.Shape.withVertex(index: Int, x: Float, y: Float): Outline.Shape {
    if (!x.isFinite() || !y.isFinite() || lockGroup != null) return this
    val points = vertices()
    val vertex = points.getOrNull(index) ?: return this
    val px = x.coerceAtLeast(0f)
    val py = y.coerceAtLeast(0f)
    if (px == vertex.x && py == vertex.y) return this
    val moved = points.toMutableList().apply { this[index] = vertex.copy(x = px, y = py) }
    return copy(segments = smoothCurve(moved, segments)).withRecomputedBounds()
}

/**
 * Centripetal Catmull–Rom interpolation expressed as Bézier controls. A shared tangent at each
 * vertex keeps the joins smooth, and square-root chord intervals accommodate unevenly spaced
 * vertices without giving a short span the handles of its much longer neighbor.
 */
private fun smoothCurve(points: List<ShapeVertex>, segments: List<ShapeSegment>): List<ShapeSegment> {
    val intervals = points.zipWithNext { a, b ->
        sqrt(hypot(b.x.toDouble() - a.x, b.y.toDouble() - a.y)).coerceAtLeast(0.001)
    }
    val rawTangents = points.mapIndexed { index, point ->
        val before = points.getOrNull(index - 1)
        val after = points.getOrNull(index + 1)
        val incoming = intervals.getOrNull(index - 1)
        val outgoing = intervals.getOrNull(index)
        when {
            before == null && after != null && outgoing != null ->
                (after.x - point.x) / outgoing to (after.y - point.y) / outgoing
            after == null && before != null && incoming != null ->
                (point.x - before.x) / incoming to (point.y - before.y) / incoming
            before != null && after != null && incoming != null && outgoing != null -> {
                val sum = incoming + outgoing
                ((point.x - before.x) / incoming * outgoing +
                    (after.x - point.x) / outgoing * incoming) / sum to
                    ((point.y - before.y) / incoming * outgoing +
                        (after.y - point.y) / outgoing * incoming) / sum
            }
            else -> 0.0 to 0.0
        }
    }.toMutableList()
    if (points.size > 2) {
        // Continue the end spans quadratically. A chord-aligned end tangent leaves a tall curve
        // looking like two straight sides with a small rounded peak. Extrapolating the adjacent
        // tangent distributes the bend along each end span; three vertices describe one parabola.
        val firstStep = intervals.first()
        val lastStep = intervals.last()
        rawTangents[0] =
            2.0 * (points[1].x - points[0].x) / firstStep - rawTangents[1].first to
                2.0 * (points[1].y - points[0].y) / firstStep - rawTangents[1].second
        val last = points.lastIndex
        rawTangents[last] =
            2.0 * (points[last].x - points[last - 1].x) / lastStep - rawTangents[last - 1].first to
                2.0 * (points[last].y - points[last - 1].y) / lastStep - rawTangents[last - 1].second
    }
    val tangents = points.mapIndexed { index, point ->
        val tangent = rawTangents[index]
        val incoming = intervals.getOrNull(index - 1)
        val outgoing = intervals.getOrNull(index)
        // Shorten both arms together at the page origin so the common tangent survives the clamp.
        var factor = 1.0
        listOfNotNull(incoming?.let { -it / 3.0 }, outgoing?.let { it / 3.0 }).forEach { arm ->
            val dx = tangent.first * arm
            val dy = tangent.second * arm
            if (dx < 0.0) factor = minOf(factor, point.x / -dx)
            if (dy < 0.0) factor = minOf(factor, point.y / -dy)
        }
        tangent.first * factor to tangent.second * factor
    }
    return segments.mapIndexed { index, segment ->
        val a = points[index]
        val b = points[index + 1]
        val step = intervals[index] / 3.0
        val from = tangents[index]
        val to = tangents[index + 1]
        segment.copy(
            x1 = a.x, y1 = a.y, x2 = b.x, y2 = b.y,
            cubic = ShapeCubic(
                (a.x + from.first * step).toFloat().coerceAtLeast(0f),
                (a.y + from.second * step).toFloat().coerceAtLeast(0f),
                (b.x - to.first * step).toFloat().coerceAtLeast(0f),
                (b.y - to.second * step).toFloat().coerceAtLeast(0f),
            ),
        )
    }
}

/** Split the chosen span at its midpoint with de Casteljau subdivision: the path stays identical. */
fun Outline.Shape.withAddedVertex(segmentId: String, nextId: () -> String): Outline.Shape {
    if (kind != ShapeKind.Curve || lockGroup != null) return this
    val index = segments.indexOfFirst { it.id == segmentId }
    if (index < 0) return this
    val segment = segments[index]
    val c = segment.cubic ?: return this
    val ax = (segment.x1 + c.x1) / 2f
    val ay = (segment.y1 + c.y1) / 2f
    val bx = (c.x1 + c.x2) / 2f
    val by = (c.y1 + c.y2) / 2f
    val cx = (c.x2 + segment.x2) / 2f
    val cy = (c.y2 + segment.y2) / 2f
    val dx = (ax + bx) / 2f
    val dy = (ay + by) / 2f
    val ex = (bx + cx) / 2f
    val ey = (by + cy) / 2f
    val mx = (dx + ex) / 2f
    val my = (dy + ey) / 2f
    return copy(segments = segments.take(index) + listOf(
        segment.copy(x2 = mx, y2 = my, cubic = ShapeCubic(ax, ay, dx, dy)),
        segment.copy(id = nextId(), x1 = mx, y1 = my, cubic = ShapeCubic(ex, ey, cx, cy)),
    ) + segments.drop(index + 1)).withRecomputedBounds()
}

/** Three on-path points, two smooth spans, using the same geometry for picker and insertion. */
internal fun seedCurve(left: Float, top: Float, right: Float, bottom: Float, nextId: () -> String): List<ShapeSegment> {
    val mid = (left + right) / 2f
    return smoothCurve(
        listOf(ShapeVertex(0, left, bottom), ShapeVertex(1, mid, top), ShapeVertex(2, right, bottom)),
        listOf(
            ShapeSegment(nextId(), left, bottom, mid, top),
            ShapeSegment(nextId(), mid, top, right, bottom),
        ),
    )
}
