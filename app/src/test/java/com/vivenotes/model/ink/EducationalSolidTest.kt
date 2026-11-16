package com.vivenotes.model.ink

import com.vivenotes.model.CborDocumentCodec
import com.vivenotes.model.DocumentJson
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EducationalSolidTest {
    private fun shape(kind: ShapeKind, thickness: Int = 25, width: Float = 240f, height: Float = 180f): Outline.Shape {
        var id = 0
        return Outline.Shape(
            id = kind.name,
            kind = kind,
            segments = seedSegments(kind, 0f, 0f, width, height, thickness) { "edge-${id++}" },
        ).withRecomputedBounds()
    }

    @Test
    fun `a filled tube has an open bore and solid sides at tall and washer proportions`() {
        listOf(240f to 180f, 320f to 40f, 60f to 320f).forEach { (width, height) ->
            val tube = shape(ShapeKind.HollowCylinder, width = width, height = height)
            val cap = minOf(width / 2f * EQUATOR_FLATTEN, height * MAX_CAP_FRACTION)
            assertFalse("the bore was filled at ${width}x$height", tube.fills(width / 2f, cap))
            assertTrue("the top annulus is missing", tube.fills(width * 0.94f, cap))
            assertTrue("the tube wall is missing", tube.fills(width * 0.94f, height / 2f))
        }
    }

    @Test
    fun `wall thickness changes the opening without changing the outer dimensions`() {
        val thin = shape(ShapeKind.HollowCylinder, thickness = 5)
        val thick = shape(ShapeKind.HollowCylinder, thickness = 80)
        assertEquals(thin.width, thick.width, 0.02f)
        assertEquals(thin.height, thick.height, 0.02f)
        val thinHole = thin.fillRegion()[1]
        val thickHole = thick.fillRegion()[1]
        assertTrue("thickness did not reduce the bore", thinHole.area() > thickHole.area() * 10f)
    }

    @Test
    fun `openings follow translated stretched and mirrored geometry`() {
        val original = shape(ShapeKind.HollowCylinder)
        val hole = original.fillRegion()[1]
        val centreX = hole.indices.step(2).map { hole[it] }.average().toFloat()
        val centreY = (1 until hole.size step 2).map { hole[it] }.average().toFloat()
        listOf(3f to 0.5f, -1.5f to 2f).forEach { (sx, sy) ->
            val changed = original.scaledAbout(0f, 0f, sx, sy).translated(400f, 50f)
            assertFalse("tube lost its opening", changed.fills(centreX * sx + 400f, centreY * sy + 50f))
            assertTrue("tube lost its exterior", changed.fills(10f * sx + 400f, 90f * sy + 50f))
        }
    }

    @Test
    fun `a hemisphere has a dome and hides the far half of its base`() {
        val hemisphere = shape(ShapeKind.Hemisphere)
        val baseY = 180f - 120f * EQUATOR_FLATTEN
        assertTrue(hemisphere.fills(120f, 20f))
        assertFalse(hemisphere.fills(10f, 10f))
        hemisphere.segments.filter { it.hidden }.forEach { edge ->
            val points = edge.polyline()
            (1 until points.size step 2).forEach { assertTrue(points[it] <= baseY + 0.02f) }
        }
    }

    @Test
    fun `a frustum has two finite unequal rims and tapered sides`() {
        val frustum = shape(ShapeKind.Frustum)
        val sides = frustum.segments.filter { it.bulge == 0f && !it.hidden }
        assertEquals(2, sides.size)
        sides.forEach { side ->
            val topX = if (side.y1 < side.y2) side.x1 else side.x2
            val bottomX = if (side.y1 > side.y2) side.x1 else side.x2
            assertEquals(66f, kotlin.math.abs(topX - 120f), 0.02f)
            assertEquals(120f, kotlin.math.abs(bottomX - 120f), 0.02f)
        }
        assertFalse(frustum.fills(10f, 10f))
        assertTrue(frustum.fills(120f, 90f))
    }

    @Test
    fun `all three solids and their openings survive JSON and CBOR round trips`() {
        val original = PageDoc(outlines = kinds.map { shape(it, thickness = 40) })
        listOf(JsonDocumentCodec, CborDocumentCodec).forEach { codec ->
            val restored = codec.decode(codec.encode(original))
            assertEquals(original, restored)
            restored.outlines.filterIsInstance<Outline.Shape>().forEach { shape ->
                assertEquals(if (shape.kind.hasWallThickness) 2 else 1, shape.fillRegion().size)
            }
        }
    }

    @Test
    fun `older segments still decode without an explicit fill boundary`() {
        val old = """{"id":"old","x1":0.0,"y1":0.0,"x2":10.0,"y2":10.0}"""
        val segment = DocumentJson.decodeFromString(ShapeSegment.serializer(), old)
        assertEquals(null, segment.fillContour)
    }

    @Test
    fun `invalid thickness settings are clamped and degenerate drags stay finite`() {
        kinds.forEach { kind ->
            listOf(-100, 200).forEach { thickness ->
                listOf(0f to 0f, 0f to 180f, 240f to 0f).forEach { (width, height) ->
                    shape(kind, thickness, width, height).segments.forEach { edge ->
                        assertTrue(edge.bulge.isFinite())
                        assertTrue(edge.polyline().all { it.isFinite() })
                    }
                }
            }
        }
    }

    private fun Outline.Shape.fills(x: Float, y: Float): Boolean =
        fillRegion().count { it.contains(x, y) } % 2 == 1

    /** Ray crossing, including non-convex outlines and holes under the even-odd rule. */
    private fun FloatArray.contains(x: Float, y: Float): Boolean {
        var inside = false
        for (i in 0 until size - 2 step 2) {
            val ax = this[i]; val ay = this[i + 1]
            val bx = this[i + 2]; val by = this[i + 3]
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
        }
        return inside
    }

    private fun FloatArray.area(): Float = kotlin.math.abs(
        (0 until size - 2 step 2).sumOf { i ->
            (this[i] * this[i + 3] - this[i + 2] * this[i + 1]).toDouble()
        }.toFloat() / 2f,
    )

    private val kinds = listOf(ShapeKind.HollowCylinder, ShapeKind.Hemisphere, ShapeKind.Frustum)
}
