package com.vivenotes.model.ocr

import com.vivenotes.model.ocr.TextDetection.Point
import com.vivenotes.model.ocr.TextDetection.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The geometry that turns a probability map into text boxes — `memory/imageOcrPlan.md` IO4.
 *
 * The reference is `simulations/image-ocr/dbpost.py`, which was written first and run against the
 * real detector. What is guarded here is what silently produces *no boxes at all* rather than wrong
 * ones: a rotated rectangle scored against its bounding box, an ordering that mislabels corners past
 * 45 degrees, an unclip that leaves ascenders outside the crop.
 */
class TextDetectionTest {

    @Test
    fun `hull of a filled rectangle is its four corners`() {
        val points = buildList {
            for (y in 0..10) for (x in 0..20) add(Point(x.toFloat(), y.toFloat()))
        }
        val hull = TextDetection.convexHull(points)

        assertEquals(4, hull.size)
        assertEquals(0f, hull.minOf { it.x })
        assertEquals(20f, hull.maxOf { it.x })
        assertEquals(0f, hull.minOf { it.y })
        assertEquals(10f, hull.maxOf { it.y })
    }

    @Test
    fun `minimum area rectangle finds a rotated box's own sides, not its bounds`() {
        // A 40 x 10 rectangle turned 30 degrees. Its axis-aligned bounds are much larger than it is,
        // which is exactly the confusion the calipers exist to avoid.
        val angle = Math.toRadians(30.0)
        val corners = listOf(
            0f to 0f,
            40f to 0f,
            40f to 10f,
            0f to 10f,
        ).map { (x, y) ->
            Point(
                (x * cos(angle) - y * sin(angle)).toFloat(),
                (x * sin(angle) + y * cos(angle)).toFloat(),
            )
        }

        val rectangle = TextDetection.minAreaRect(TextDetection.convexHull(corners))

        assertEquals(10f, rectangle.shortSide, 0.01f)
        assertEquals(40f, rectangle.longSide, 0.01f)
    }

    @Test
    fun `a rotated line scores on its own pixels, not on the paper beside it`() {
        // The finding that made the feature work: with a bounding-box score, this map's only text
        // region falls under the 0.6 threshold and the picture returns nothing.
        val width = 64
        val height = 64
        val probability = FloatArray(width * height)
        val quad = rotatedBand(width, height, probability)

        val filled = TextDetection.boxScore(probability, width, height, quad)
        val bounds = boundingBoxScore(probability, width, height, quad)

        assertTrue("filled score $filled should pass the box threshold", filled >= TextDetection.BOX_THRESHOLD)
        assertTrue("bounding-box score $bounds should not", bounds < TextDetection.BOX_THRESHOLD)
    }

    @Test
    fun `unclip grows a box by Vatti's distance on every side`() {
        val corners = listOf(
            Point(10f, 10f),
            Point(50f, 10f),
            Point(50f, 20f),
            Point(10f, 20f),
        )
        // area 400, perimeter 100, ratio 1.5 -> 6 on each side.
        val grown = TextDetection.unclip(corners, 1.5f)

        assertEquals(4f, grown.minOf { it.x }, 0.01f)
        assertEquals(56f, grown.maxOf { it.x }, 0.01f)
        assertEquals(4f, grown.minOf { it.y }, 0.01f)
        assertEquals(26f, grown.maxOf { it.y }, 0.01f)
    }

    @Test
    fun `corner ordering survives a box past forty-five degrees`() {
        val angle = Math.toRadians(70.0)
        val centre = Point(50f, 50f)
        val corners = listOf(
            -20f to -5f,
            20f to -5f,
            20f to 5f,
            -20f to 5f,
        ).map { (x, y) ->
            Point(
                centre.x + (x * cos(angle) - y * sin(angle)).toFloat(),
                centre.y + (x * sin(angle) + y * cos(angle)).toFloat(),
            )
        }

        val ordered = TextDetection.clockwiseFromTopLeft(corners.shuffled())

        assertEquals(corners.toSet(), ordered.toSet())
        // Starts at the corner nearest the picture's top-left, which is where the crop's origin goes.
        assertEquals(corners.minByOrNull { it.x + it.y }, ordered.first())
        // Clockwise *on screen*. The shoelace sum is positive for that winding, because y grows
        // downwards here — the opposite sign to the same formula in ordinary maths axes.
        assertTrue("expected clockwise, got $ordered", signedArea(ordered) > 0f)
        // Adjacent corners are adjacent on the rectangle, so consecutive edges alternate long/short.
        val sides = ordered.indices.map { index ->
            ordered[index].distanceTo(ordered[(index + 1) % ordered.size])
        }
        assertEquals(sides[0], sides[2], 0.01f)
        assertEquals(sides[1], sides[3], 0.01f)
        assertTrue(abs(sides[0] - sides[1]) > 1f)
    }

    @Test
    fun `two bands of text become two quads`() {
        val width = 96
        val height = 64
        val probability = FloatArray(width * height)
        band(probability, width, left = 8, top = 8, right = 80, bottom = 18)
        band(probability, width, left = 8, top = 36, right = 60, bottom = 46)

        val quads = TextDetection.quads(probability, width, height)

        assertEquals(2, quads.size)
        val ordered = TextDetection.readingOrder(quads) { it }
        assertTrue(ordered[0].top < ordered[1].top)
    }

    @Test
    fun `a map with no text produces no quads`() {
        val probability = FloatArray(64 * 64) { 0.05f }

        assertTrue(TextDetection.quads(probability, 64, 64).isEmpty())
    }

    @Test
    fun `reading order groups a row before it sorts it`() {
        // Two columns: the right column's first line sits beside the left column's first line, so it
        // must be read second rather than after the whole left column.
        val quads = listOf(
            "left-top" to boxAt(10f, 10f),
            "left-bottom" to boxAt(10f, 60f),
            "right-top" to boxAt(200f, 12f),
        )

        val ordered = TextDetection.readingOrder(quads) { it.second }

        assertEquals(listOf("left-top", "right-top", "left-bottom"), ordered.map { it.first })
    }

    private fun boxAt(x: Float, y: Float) = Quad(
        corners = listOf(
            Point(x, y),
            Point(x + 120f, y),
            Point(x + 120f, y + 20f),
            Point(x, y + 20f),
        ),
        score = 1f,
    )

    private fun band(probability: FloatArray, width: Int, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top..bottom) for (x in left..right) probability[y * width + x] = 0.95f
    }

    /** Paints a 30-degree band and returns the quad covering it. */
    private fun rotatedBand(width: Int, height: Int, probability: FloatArray): List<Point> {
        val angle = Math.toRadians(30.0)
        val centreX = width / 2f
        val centreY = height / 2f
        val halfLong = 24f
        val halfShort = 4f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centreX
                val dy = y - centreY
                val along = dx * cos(angle) + dy * sin(angle)
                val across = -dx * sin(angle) + dy * cos(angle)
                if (abs(along) <= halfLong && abs(across) <= halfShort) {
                    probability[y * width + x] = 0.95f
                }
            }
        }
        return listOf(
            -halfLong to -halfShort,
            halfLong to -halfShort,
            halfLong to halfShort,
            -halfLong to halfShort,
        ).map { (along, across) ->
            Point(
                (centreX + along * cos(angle) - across * sin(angle)).toFloat(),
                (centreY + along * sin(angle) + across * cos(angle)).toFloat(),
            )
        }
    }

    /** What PaddleOCR's score would be if it averaged the bounding box. Kept only to contrast. */
    private fun boundingBoxScore(
        probability: FloatArray,
        width: Int,
        height: Int,
        corners: List<Point>,
    ): Float {
        val left = corners.minOf { it.x }.toInt().coerceIn(0, width - 1)
        val right = corners.maxOf { it.x }.toInt().coerceIn(0, width - 1)
        val top = corners.minOf { it.y }.toInt().coerceIn(0, height - 1)
        val bottom = corners.maxOf { it.y }.toInt().coerceIn(0, height - 1)
        var total = 0.0
        var count = 0
        for (y in top..bottom) for (x in left..right) {
            total += probability[y * width + x]
            count++
        }
        return (total / count).toFloat()
    }

    private fun signedArea(points: List<Point>): Float {
        var total = 0f
        points.indices.forEach { index ->
            val from = points[index]
            val to = points[(index + 1) % points.size]
            total += from.x * to.y - to.x * from.y
        }
        return total / 2f
    }

    private fun Point.distanceTo(other: Point) = hypot(other.x - x, other.y - y)
}
