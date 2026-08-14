package com.vivenotes.model.ocr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.withSign

/**
 * PaddleOCR's DB post-processing, without OpenCV — `memory/imageOcrPlan.md` IO4.
 *
 * The detector emits a probability map the size of its input; turning that into text boxes is
 * `cv2.findContours`, `cv2.minAreaRect` and pyclipper upstream. None of those exist here, and
 * pulling OpenCV in for three geometry routines would cost several times what the 4.7 MB detector
 * costs. What replaces them is below: an 8-connected flood fill, boundary extraction, a
 * monotone-chain hull, rotating calipers, and a rectangle outset standing in for Vatti offsetting.
 *
 * **Android-free on purpose**, like the rest of `model/`: this is the half that can be tested on the
 * JVM, and it was written against the Python reference in `simulations/image-ocr/dbpost.py`.
 */
object TextDetection {

    /**
     * Everything the caller needs to crop one detected line out of the source picture.
     *
     * [corners] are four points in probability-map pixels, starting nearest the top-left and going
     * clockwise, which is the order the warp in `ai/ImageOcr.kt` expects.
     */
    data class Quad(val corners: List<Point>, val score: Float) {
        val width: Float
            get() = max(corners[0].distanceTo(corners[1]), corners[3].distanceTo(corners[2]))

        val height: Float
            get() = max(corners[0].distanceTo(corners[3]), corners[1].distanceTo(corners[2]))

        fun scaled(scaleX: Float, scaleY: Float): Quad =
            copy(corners = corners.map { Point(it.x * scaleX, it.y * scaleY) })

        val top: Float get() = corners.minOf { it.y }
        val left: Float get() = corners.minOf { it.x }
        val bottom: Float get() = corners.maxOf { it.y }
    }

    data class Point(val x: Float, val y: Float) {
        fun distanceTo(other: Point): Float = hypot(other.x - x, other.y - y)
    }

    /**
     * Text quads for one probability map, in no particular order.
     *
     * [probability] is row-major, [width] × [height], each value in `0..1`.
     */
    fun quads(
        probability: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = BINARIZE_THRESHOLD,
        boxThreshold: Float = BOX_THRESHOLD,
        unclipRatio: Float = UNCLIP_RATIO,
        minSide: Float = MIN_SIDE,
        maxQuads: Int = MAX_QUADS,
    ): List<Quad> {
        require(probability.size >= width * height) { "Probability map is smaller than its bounds" }
        val found = mutableListOf<Quad>()
        forEachComponent(probability, width, height, threshold) { boundary ->
            val hull = convexHull(boundary)
            val rectangle = minAreaRect(hull)
            if (rectangle.shortSide >= minSide) {
                val score = boxScore(probability, width, height, rectangle.corners)
                if (score >= boxThreshold) {
                    val grown = unclip(rectangle.corners, unclipRatio)
                    val quad = Quad(clockwiseFromTopLeft(grown), score)
                    if (min(quad.width, quad.height) >= minSide) found += quad
                }
            }
            found.size < maxQuads
        }
        return found
    }

    /**
     * Calls [onComponent] with each 8-connected run of pixels above [threshold], as its boundary.
     *
     * Only boundary pixels are handed on: the convex hull of a filled blob is the hull of its
     * outline, and a paragraph-sized blob carries thousands of interior pixels that can never be a
     * hull vertex. Iterative rather than recursive — a component can be a megapixel, and that is a
     * stack overflow waiting for the first full-page screenshot.
     *
     * Return false from [onComponent] to stop early.
     */
    private inline fun forEachComponent(
        probability: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
        onComponent: (List<Point>) -> Boolean,
    ) {
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val pixels = IntArray(width * height)
        for (start in 0 until width * height) {
            if (visited[start] || probability[start] <= threshold) continue
            visited[start] = true
            var top = 0
            var count = 0
            stack[top++] = start
            while (top > 0) {
                val index = stack[--top]
                pixels[count++] = index
                val x = index % width
                val y = index / width
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val neighbour = ny * width + nx
                        if (!visited[neighbour] && probability[neighbour] > threshold) {
                            visited[neighbour] = true
                            stack[top++] = neighbour
                        }
                    }
                }
            }
            if (count < MIN_COMPONENT_PIXELS) continue
            val boundary = ArrayList<Point>(min(count, 512))
            for (position in 0 until count) {
                val index = pixels[position]
                val x = index % width
                val y = index / width
                val interior = x > 0 && y > 0 && x < width - 1 && y < height - 1 &&
                    probability[index - 1] > threshold &&
                    probability[index + 1] > threshold &&
                    probability[index - width] > threshold &&
                    probability[index + width] > threshold
                if (!interior) boundary += Point(x.toFloat(), y.toFloat())
            }
            if (boundary.isEmpty()) continue
            if (!onComponent(boundary)) return
        }
    }

    /** Andrew's monotone chain. Returns the hull counter-clockwise in screen coordinates. */
    fun convexHull(points: List<Point>): List<Point> {
        if (points.size <= 2) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = ArrayList<Point>(sorted.size)
        for (point in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0f) {
                lower.removeAt(lower.size - 1)
            }
            lower += point
        }
        val upper = ArrayList<Point>(sorted.size)
        for (index in sorted.indices.reversed()) {
            val point = sorted[index]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0f) {
                upper.removeAt(upper.size - 1)
            }
            upper += point
        }
        val hull = ArrayList<Point>(lower.size + upper.size)
        hull += lower.subList(0, lower.size - 1)
        hull += upper.subList(0, upper.size - 1)
        return if (hull.isEmpty()) sorted else hull
    }

    data class Rect(val corners: List<Point>, val shortSide: Float, val longSide: Float)

    /**
     * The smallest enclosing rectangle, by trying each hull edge as its direction.
     *
     * Exact rather than a search: a minimum-area rectangle always shares an edge with the hull, so
     * the candidate set is the hull's own edges. O(h²) on the hull, which for a text component is a
     * few dozen points.
     */
    fun minAreaRect(hull: List<Point>): Rect {
        if (hull.size < 3) {
            val left = hull.minOf { it.x }
            val right = hull.maxOf { it.x }
            val top = hull.minOf { it.y }
            val bottom = hull.maxOf { it.y }
            val corners = listOf(
                Point(left, top),
                Point(right, top),
                Point(right, bottom),
                Point(left, bottom),
            )
            return Rect(corners, min(right - left, bottom - top), max(right - left, bottom - top))
        }

        var best: Rect? = null
        var bestArea = Float.MAX_VALUE
        for (index in hull.indices) {
            val start = hull[index]
            val end = hull[(index + 1) % hull.size]
            val length = start.distanceTo(end)
            if (length < 1e-6f) continue
            val ux = (end.x - start.x) / length
            val uy = (end.y - start.y) / length
            var minAlong = Float.MAX_VALUE
            var maxAlong = -Float.MAX_VALUE
            var minAcross = Float.MAX_VALUE
            var maxAcross = -Float.MAX_VALUE
            for (point in hull) {
                val along = point.x * ux + point.y * uy
                val across = -point.x * uy + point.y * ux
                minAlong = min(minAlong, along)
                maxAlong = max(maxAlong, along)
                minAcross = min(minAcross, across)
                maxAcross = max(maxAcross, across)
            }
            val spanAlong = maxAlong - minAlong
            val spanAcross = maxAcross - minAcross
            val area = spanAlong * spanAcross
            if (area >= bestArea) continue
            bestArea = area
            best = Rect(
                corners = listOf(
                    unrotate(minAlong, minAcross, ux, uy),
                    unrotate(maxAlong, minAcross, ux, uy),
                    unrotate(maxAlong, maxAcross, ux, uy),
                    unrotate(minAlong, maxAcross, ux, uy),
                ),
                shortSide = min(spanAlong, spanAcross),
                longSide = max(spanAlong, spanAcross),
            )
        }
        return best ?: Rect(hull.take(4), 0f, 0f)
    }

    /**
     * Grows a rectangle by Vatti's offset distance for its own area and perimeter.
     *
     * `distance = area * ratio / perimeter`, which for a rectangle is a uniform outset of all four
     * sides. Detection is trained to predict a *shrunk* text region, so a box that is never
     * unclipped clips the ascenders and descenders off every line it finds.
     *
     * PaddleOCR offsets the contour and then takes its minimum rectangle; this takes the rectangle
     * first and offsets that. For a text line the two agree closely, and the second is forty lines
     * of Kotlin rather than a clipping library.
     */
    fun unclip(corners: List<Point>, ratio: Float): List<Point> {
        val width = corners[0].distanceTo(corners[1])
        val height = corners[1].distanceTo(corners[2])
        val perimeter = 2f * (width + height)
        if (perimeter < 1e-6f) return corners
        val distance = width * height * ratio / perimeter
        val centerX = corners.sumOf { it.x.toDouble() }.toFloat() / corners.size
        val centerY = corners.sumOf { it.y.toDouble() }.toFloat() / corners.size
        val ux = if (width > 1e-6f) (corners[1].x - corners[0].x) / width else 1f
        val uy = if (width > 1e-6f) (corners[1].y - corners[0].y) / width else 0f
        val vx = if (height > 1e-6f) (corners[2].x - corners[1].x) / height else 0f
        val vy = if (height > 1e-6f) (corners[2].y - corners[1].y) / height else 1f
        return corners.map { corner ->
            val along = (corner.x - centerX) * ux + (corner.y - centerY) * uy
            val across = (corner.x - centerX) * vx + (corner.y - centerY) * vy
            val grownAlong = along + distance.withSign(if (along == 0f) 1f else along)
            val grownAcross = across + distance.withSign(if (across == 0f) 1f else across)
            Point(
                centerX + grownAlong * ux + grownAcross * vx,
                centerY + grownAlong * uy + grownAcross * vy,
            )
        }
    }

    /**
     * Mean probability **inside the quadrilateral** — PaddleOCR's `box_score_fast`.
     *
     * **The polygon has to be filled; its bounding box will not do.** A line of text photographed at
     * an angle fills barely half of its own axis-aligned bounds, so scoring the bounds averages the
     * line together with the paper beside it and drags every rotated line under the box threshold.
     * Measured over `simulations/image-ocr/`: with the bounding box a photographed page returned
     * *no lines at all* and mean character error rate was 0.343; filling the quad returns all four
     * lines and 0.010.
     */
    fun boxScore(probability: FloatArray, width: Int, height: Int, corners: List<Point>): Float {
        val top = floor(corners.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val bottom = ceil(corners.maxOf { it.y }).toInt().coerceIn(0, height - 1)
        val left = floor(corners.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val right = ceil(corners.maxOf { it.x }).toInt().coerceIn(0, width - 1)
        if (right < left || bottom < top) return 0f

        var total = 0.0
        var count = 0
        val crossings = FloatArray(corners.size)
        for (y in top..bottom) {
            var found = 0
            for (index in corners.indices) {
                val from = corners[index]
                val to = corners[(index + 1) % corners.size]
                val within = (from.y <= y && y < to.y) || (to.y <= y && y < from.y)
                if (within) {
                    crossings[found++] = from.x + (y - from.y) * (to.x - from.x) / (to.y - from.y)
                }
            }
            if (found < 2) continue
            var spanLeft = Float.MAX_VALUE
            var spanRight = -Float.MAX_VALUE
            for (index in 0 until found) {
                spanLeft = min(spanLeft, crossings[index])
                spanRight = max(spanRight, crossings[index])
            }
            val from = max(left, floor(spanLeft).toInt())
            val to = min(right, ceil(spanRight).toInt())
            if (to < from) continue
            val row = y * width
            for (x in from..to) {
                total += probability[row + x]
                count++
            }
        }
        if (count == 0) {
            // Degenerate: a box thinner than one scanline. Fall back to its bounds rather than
            // rejecting it, so a single-pixel rule is scored as what it is instead of as zero.
            var sum = 0.0
            var cells = 0
            for (y in top..bottom) {
                for (x in left..right) {
                    sum += probability[y * width + x]
                    cells++
                }
            }
            return if (cells == 0) 0f else (sum / cells).toFloat()
        }
        return (total / count).toFloat()
    }

    /**
     * Corners starting nearest the top-left, going clockwise.
     *
     * Sorted by angle about the centroid rather than by splitting on x. The x-split recipe is the
     * common one and it mislabels corners once a box passes about 45 degrees, which is exactly the
     * case an ordering step exists to handle.
     */
    fun clockwiseFromTopLeft(corners: List<Point>): List<Point> {
        val centerX = corners.sumOf { it.x.toDouble() }.toFloat() / corners.size
        val centerY = corners.sumOf { it.y.toDouble() }.toFloat() / corners.size
        // Screen y grows downwards, so increasing atan2 walks clockwise on screen.
        val ordered = corners.sortedBy { atan2(it.y - centerY, it.x - centerX) }
        var start = 0
        var bestCorner = Float.MAX_VALUE
        ordered.forEachIndexed { index, point ->
            val distance = point.x + point.y
            if (distance < bestCorner) {
                bestCorner = distance
                start = index
            }
        }
        return List(ordered.size) { offset -> ordered[(start + offset) % ordered.size] }
    }

    /**
     * Quads in reading order: down the page, and left to right within a row.
     *
     * Boxes whose tops are within half a line height of each other are one row, so two columns of a
     * slide read across rather than interleaving down.
     */
    fun <T> readingOrder(items: List<T>, quadOf: (T) -> Quad): List<T> {
        if (items.size <= 1) return items
        val sorted = items.sortedWith(compareBy({ quadOf(it).top }, { quadOf(it).left }))
        val rows = mutableListOf<MutableList<T>>()
        sorted.forEach { item ->
            val quad = quadOf(item)
            val row = rows.firstOrNull { existing ->
                val reference = quadOf(existing.first())
                val tolerance = min(reference.height, quad.height) * ROW_TOLERANCE
                abs(quad.top - reference.top) <= tolerance
            }
            if (row == null) rows += mutableListOf(item) else row += item
        }
        return rows.flatMap { row -> row.sortedBy { quadOf(it).left } }
    }

    private fun unrotate(along: Float, across: Float, ux: Float, uy: Float): Point =
        Point(along * ux - across * uy, along * uy + across * ux)

    private fun cross(origin: Point, first: Point, second: Point): Float =
        (first.x - origin.x) * (second.y - origin.y) - (first.y - origin.y) * (second.x - origin.x)

    /** PaddleOCR's own binarization threshold. */
    const val BINARIZE_THRESHOLD = 0.3f

    /** PaddleOCR's own box threshold, applied to the score of [boxScore]. */
    const val BOX_THRESHOLD = 0.6f

    /** PaddleOCR's own unclip ratio. See [unclip] for what it does. */
    const val UNCLIP_RATIO = 1.5f

    /** A box thinner than this is a rule, a border or noise, not a line of text. */
    const val MIN_SIDE = 3f

    /** A picture that produces more regions than this is being read as texture, not as text. */
    const val MAX_QUADS = 400

    /** Below this a component cannot hold a readable glyph, and hulling it is wasted work. */
    private const val MIN_COMPONENT_PIXELS = 9

    /** How far two boxes' tops may differ, in line heights, and still count as the same row. */
    private const val ROW_TOLERANCE = 0.5f
}
