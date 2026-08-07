package com.vivenotes.model.ink

import com.vivenotes.model.Outline

/**
 * The region a fill colour paints, or empty for a shape with no inside — `docs/inkPlan.md` §5.4 SD7.
 *
 * **Not the same thing as the border.** A cube's twelve edges are what it is *stroked* along; what it
 * *covers* is one hexagon, and painting the edges as a path would fill three faces in three
 * overlapping passes, or nothing at all, depending on the winding rule. [ShapeTracing.fill] made the
 * same distinction for a freshly traced shape; this makes it for a shape on the page, which by then
 * may have been resized or had its arms dragged.
 *
 * Two cases, and the split is the difference between a flat outline and a wireframe:
 *
 * - **A closed run of segments is its own region.** Rectangles, ellipses, polygons: whatever the
 *   contour encloses. Read off the geometry, so a shape that has been edited fills what it now looks
 *   like rather than what it was seeded as.
 * - **A solid fills its silhouette**, which is the convex hull of its vertices — exact for all six of
 *   them, because every one is a convex body seen head on, and unlike re-tracing at the current
 *   bounds it cannot drift: a cube stretched unevenly has a depth its own points still agree on,
 *   while a fresh trace of the same box would compute a different one and leave the fill standing off
 *   the edges.
 *
 * Everything else — a line, an arrow, an L, an L dragged out into a cross — reports nothing, which is
 * what makes [canFill] false and takes Fill out of the toolkit for it. An open figure has no inside,
 * and closing one on the user's behalf invents geometry they did not draw.
 */
fun Outline.Shape.fillRegion(): List<FloatArray> = if (kind.isSolid) {
    listOfNotNull(convexHull(segments.flatMap { it.polyline().asPoints() }))
} else {
    segments.contours()
        .filter { !it.hidden && it.isClosed }
        .map { it.polyline() }
}

/**
 * Whether this shape has an inside to colour.
 *
 * Cheaper than asking [fillRegion] — it stops at the first closed contour rather than sampling every
 * arc — because the toolkit asks it on every recomposition to decide whether Fill belongs on the bar.
 */
val Outline.Shape.canFill: Boolean
    get() = kind.isSolid || segments.contours().any { !it.hidden && it.isClosed }

/** Interleaved x/y as pairs. */
private fun FloatArray.asPoints(): List<Pair<Float, Float>> =
    (indices step 2).map { this[it] to this[it + 1] }

/**
 * The convex hull of the points, closed, or null when they do not enclose anything.
 *
 * Monotone chain: sort, then sweep once for the lower boundary and once for the upper, dropping any
 * point that would make a right turn. O(n log n) and exact, which matters because this runs on a
 * sphere's several hundred sampled points every time one is drawn filled.
 */
private fun convexHull(points: List<Pair<Float, Float>>): FloatArray? {
    if (points.size < 3) return null
    val sorted = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    if (sorted.size < 3) return null

    fun half(source: List<Pair<Float, Float>>): MutableList<Pair<Float, Float>> {
        val chain = mutableListOf<Pair<Float, Float>>()
        source.forEach { point ->
            while (chain.size >= 2 && cross(chain[chain.size - 2], chain[chain.size - 1], point) <= 0f) {
                chain.removeAt(chain.lastIndex)
            }
            chain.add(point)
        }
        return chain
    }

    val lower = half(sorted)
    val upper = half(sorted.asReversed())
    // Each chain ends where the other begins, so both endpoints would otherwise appear twice.
    val hull = lower.dropLast(1) + upper.dropLast(1)
    if (hull.size < 3) return null

    val closed = hull + hull.first()
    return FloatArray(closed.size * 2) { index ->
        val point = closed[index / 2]
        if (index % 2 == 0) point.first else point.second
    }
}

/** Twice the signed area of the triangle: positive when o → a → b turns left. */
private fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
    (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
