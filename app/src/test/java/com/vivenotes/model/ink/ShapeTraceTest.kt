package com.vivenotes.model.ink

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry behind Insert Shape (`docs/inkPlan.md` §5.4).
 *
 * Worth testing on the JVM rather than by eye on a device, because every one of these failures is
 * silent: a shape that is subtly off-centre, an ellipse that is a hexagon at chip size, or a solid
 * whose hidden edges are the wrong three still *draws*. It just draws the wrong thing.
 */
class ShapeTraceTest {

    private val box = Box(left = 100f, top = 200f, right = 340f, bottom = 360f)

    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun ShapeKind.traced(b: Box = box): ShapeTracing =
        trace(this, b.left, b.top, b.right, b.bottom)

    private fun ShapeTracing.all(): List<FloatArray> = solid + hidden

    private fun FloatArray.points(): List<Pair<Float, Float>> =
        indices.step(2).map { this[it] to this[it + 1] }

    // -----------------------------------------------------------------------------------------

    @Test
    fun `every kind traces something strokeable`() {
        ShapeKind.entries.forEach { kind ->
            val tracing = kind.traced()
            assertTrue("$kind traced no solid geometry", tracing.solid.isNotEmpty())
            tracing.all().forEach { polyline ->
                assertTrue(
                    "$kind produced a polyline with an odd float count",
                    polyline.size % 2 == 0,
                )
                assertTrue("$kind produced a polyline of under two points", polyline.size >= 4)
                polyline.forEach {
                    assertTrue("$kind produced a non-finite coordinate", it.isFinite())
                }
            }
        }
    }

    @Test
    fun `every kind stays inside the box it was dragged out`() {
        // The whole contract of the drag: what you dragged is what you get. A solid that grew its
        // depth outward instead of inward would land outside the preview the user was shown.
        ShapeKind.entries.forEach { kind ->
            kind.traced().all().forEach { polyline ->
                polyline.points().forEach { (x, y) ->
                    assertTrue(
                        "$kind escaped the box at ($x, $y)",
                        x >= box.left - TOLERANCE && x <= box.right + TOLERANCE &&
                            y >= box.top - TOLERANCE && y <= box.bottom + TOLERANCE,
                    )
                }
            }
        }
    }

    @Test
    fun `every kind fills the box it was dragged out`() {
        // The other half of the same contract. A shape that used only the middle of the drag would
        // satisfy the containment test above and still feel broken.
        ShapeKind.entries.forEach { kind ->
            val points = kind.traced().all().flatMap { it.points() }
            val width = points.maxOf { it.first } - points.minOf { it.first }
            val height = points.maxOf { it.second } - points.minOf { it.second }
            assertTrue(
                "$kind used only ${width}x${height} of a ${box.right - box.left}x${box.bottom - box.top} box",
                width >= (box.right - box.left) * 0.9f && height >= (box.bottom - box.top) * 0.9f,
            )
        }
    }

    @Test
    fun `closed shapes close and open ones do not`() {
        val open = setOf(ShapeKind.Line, ShapeKind.Arrow)
        val closed = setOf(
            ShapeKind.Rectangle, ShapeKind.RoundedRectangle, ShapeKind.Ellipse,
            ShapeKind.Triangle, ShapeKind.RightTriangle, ShapeKind.Diamond,
            ShapeKind.Pentagon, ShapeKind.Hexagon,
        )

        closed.forEach { kind ->
            val outline = kind.traced().solid.first().points()
            assertEquals("$kind did not close", outline.first(), outline.last())
        }
        open.forEach { kind ->
            val shaft = kind.traced().solid.first().points()
            assertNotEquals("$kind closed when it should not", shaft.first(), shaft.last())
        }
    }

    @Test
    fun `only the solids have hidden edges`() {
        ShapeKind.entries.forEach { kind ->
            if (kind.isSolid) {
                assertTrue("$kind should occlude something", kind.traced().hidden.isNotEmpty())
            } else {
                assertTrue("$kind should occlude nothing", kind.traced().hidden.isEmpty())
            }
        }
    }

    @Test
    fun `each polyhedron occludes exactly the three edges of its far corner`() {
        // The count is the check worth having: every one of these solids is seen from the front,
        // above and to the right, so exactly one vertex is behind the body and exactly its three
        // edges are dotted. A fourth or a second would mean the projection had been changed without
        // the occlusion being reworked to match.
        listOf(ShapeKind.Cube, ShapeKind.Pyramid, ShapeKind.Wedge).forEach { kind ->
            assertEquals("$kind", 3, kind.traced().hidden.size)
        }
    }

    @Test
    fun `the sphere hides half its equator and nothing else`() {
        val tracing = ShapeKind.Sphere.traced()
        // The sphere is the one solid whose hidden edge is an arc rather than three straight ones.
        assertEquals(1, tracing.hidden.size)
        val far = tracing.hidden.first().points()
        val centreY = (box.top + box.bottom) / 2f
        assertTrue(
            "the hidden half of the equator should run above the centre line",
            far.all { it.second <= centreY + TOLERANCE },
        )
        val near = tracing.solid.last().points()
        assertTrue(
            "the visible half of the equator should run below the centre line",
            near.all { it.second >= centreY - TOLERANCE },
        )
    }

    @Test
    fun `an ellipse stays inscribed rather than becoming a polygon`() {
        val tracing = ShapeKind.Ellipse.traced()
        val centreX = (box.left + box.right) / 2f
        val centreY = (box.top + box.bottom) / 2f
        val radiusX = (box.right - box.left) / 2f
        val radiusY = (box.bottom - box.top) / 2f

        tracing.solid.first().points().forEach { (x, y) ->
            val normalized = hypot((x - centreX) / radiusX, (y - centreY) / radiusY)
            assertTrue("a sample sat at $normalized of the radius", abs(normalized - 1f) < 0.01f)
        }
    }

    @Test
    fun `sampling scales with size, so a chip is cheap and a page shape is smooth`() {
        val chip = ShapeKind.Ellipse.traced(Box(0f, 0f, 28f, 28f)).solid.first().size / 2
        val page = ShapeKind.Ellipse.traced(Box(0f, 0f, 400f, 400f)).solid.first().size / 2

        assertTrue("a 28dp chip took $chip samples", chip in 12..40)
        assertTrue("a 400dp shape took $page samples", page in 300..361)
    }

    @Test
    fun `an arrow points at the end of the drag, not at a corner of its box`() {
        // The reason trace() takes a drag rather than a normalised rectangle. Dragging right-to-left
        // has to give an arrow pointing left, and there is nowhere downstream to recover that from.
        val rightwards = trace(ShapeKind.Arrow, 0f, 0f, 100f, 0f)
        val leftwards = trace(ShapeKind.Arrow, 100f, 0f, 0f, 0f)

        assertEquals(100f, tipOf(rightwards), TOLERANCE)
        assertEquals(0f, tipOf(leftwards), TOLERANCE)
    }

    /** The head is `[wing, tip, wing]`, so the tip is its middle point. */
    private fun tipOf(tracing: ShapeTracing): Float = tracing.solid[1][2]

    @Test
    fun `an arrow is a shaft plus a head`() {
        val single = trace(ShapeKind.Arrow, 0f, 0f, 100f, 40f).solid

        assertEquals(2, single.size)
    }

    @Test
    fun `a degenerate drag traces without dividing by zero`() {
        // Reachable: a tap that the gesture has not yet given a default size to, or a drag along a
        // single axis. None of these need to look like anything, they need to not crash.
        val degenerate = listOf(
            Box(50f, 50f, 50f, 50f),
            Box(50f, 50f, 250f, 50f),
            Box(50f, 50f, 50f, 250f),
        )
        degenerate.forEach { b ->
            ShapeKind.entries.forEach { kind ->
                kind.traced(b).all().forEach { polyline ->
                    polyline.forEach {
                        assertTrue("$kind produced $it on a degenerate drag", it.isFinite())
                    }
                }
            }
        }
    }

    @Test
    fun `an inverted drag traces the same closed shape as an upright one`() {
        // Closed shapes normalise; only the line and the arrows read the drag's direction.
        ShapeKind.entries.filterNot { it in setOf(ShapeKind.Line, ShapeKind.Arrow) }
            .forEach { kind ->
                val upright = trace(kind, 10f, 20f, 210f, 180f)
                val inverted = trace(kind, 210f, 180f, 10f, 20f)
                upright.all().zip(inverted.all()).forEach { (a, b) ->
                    assertArrayAlmostEquals("$kind changed with drag direction", a, b)
                }
            }
    }

    @Test
    fun `the picker's two pages account for every kind`() {
        val paged = (0 until ShapeKind.PAGE_COUNT).flatMap(ShapeKind::onPage)

        assertEquals(ShapeKind.entries.toSet(), paged.toSet())
        assertEquals("a kind is on two pages at once", ShapeKind.entries.size, paged.size)
        assertEquals(10, ShapeKind.onPage(PAGE_BASIC).size)
        // Six, which is exactly one row of the picker's grid.
        assertEquals(6, ShapeKind.onPage(PAGE_SOLID).size)
        assertTrue(ShapeKind.onPage(PAGE_SOLID).all(ShapeKind::isSolid))
    }

    private fun assertArrayAlmostEquals(message: String, expected: FloatArray, actual: FloatArray) {
        assertEquals(message, expected.size, actual.size)
        expected.indices.forEach { assertEquals(message, expected[it], actual[it], TOLERANCE) }
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
