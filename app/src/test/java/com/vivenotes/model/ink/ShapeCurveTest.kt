package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class ShapeCurveTest {
    private var serial = 0
    private fun curve() = Outline.Shape(
        id = "curve", kind = ShapeKind.Curve,
        segments = seedSegments(ShapeKind.Curve, 40f, 60f, 280f, 180f) { "s-${serial++}" },
    ).withRecomputedBounds()

    @Test fun `curve starts with three on-path vertices and matches its preview`() {
        val shape = curve()
        assertEquals(listOf(40f to 180f, 160f to 60f, 280f to 180f), shape.vertices().map { it.x to it.y })
        assertArrayEquals(trace(ShapeKind.Curve, 40f, 60f, 280f, 180f).solid.single(),
            shape.segments.contours().single().polyline(), 0f)
        assertFalse(shape.canFill)
        assertTrue(shape.fillRegion().isEmpty())
    }

    @Test fun `adding a vertex preserves every point of the original cubic`() {
        val original = curve()
        val added = original.withAddedVertex(original.segments.first().id) { "new" }
        assertEquals(4, added.vertices().size)
        val split = original.segments.indexOfFirst { old -> added.segments.first { it.id == old.id } != old }
        assertTrue(split >= 0)
        for (step in 0..100) {
            val t = step / 100f
            val before = original.segments[split].at(t)
            val after = if (t <= 0.5f) added.segments[split].at(t * 2f)
                else added.segments[split + 1].at((t - 0.5f) * 2f)
            assertEquals(before.first, after.first, 0.0001f)
            assertEquals(before.second, after.second, 0.0001f)
        }
        assertTrue(added.segments.map { it.id }.containsAll(original.segments.map { it.id }))
    }

    @Test fun `ghost points are on every span between existing vertices`() {
        val original = curve()
        val points = original.curveInsertionPoints()
        assertEquals(2, points.size)
        points.zip(original.segments).forEach { (point, span) ->
            assertEquals(span.id, point.segmentId)
            assertEquals(span.at(0.5f).first, point.x, 0.0001f)
            assertEquals(span.at(0.5f).second, point.y, 0.0001f)
        }
        assertTrue(original.copy(lockGroup = "locked").curveInsertionPoints().isEmpty())
        assertTrue(original.copy(kind = ShapeKind.Line).curveInsertionPoints().isEmpty())
    }

    @Test fun `choosing a shorter span inserts there and leaves the other span untouched`() {
        val original = curve().withVertex(2, 1200f, 180f)
        val chosen = original.curveInsertionPoints().first()
        val added = original.withAddedVertex(chosen.segmentId) { "new" }
        assertEquals(4, added.vertices().size)
        assertEquals(chosen.x, added.vertices()[1].x, 0.0001f)
        assertEquals(chosen.y, added.vertices()[1].y, 0.0001f)
        assertEquals(original.segments.last(), added.segments.last())
    }

    @Test fun `choosing the last span preserves its exact path too`() {
        val original = curve()
        val added = original.withAddedVertex(original.segments.last().id) { "new" }
        assertEquals(original.segments.first(), added.segments.first())
        for (step in 0..100) {
            val t = step / 100f
            val before = original.segments.last().at(t)
            val after = if (t <= 0.5f) added.segments[1].at(t * 2f)
                else added.segments[2].at((t - 0.5f) * 2f)
            assertEquals(before.first, after.first, 0.0001f)
            assertEquals(before.second, after.second, 0.0001f)
        }
    }

    @Test fun `a stale chosen span cannot add a vertex`() {
        val original = curve()
        assertEquals(original, original.withAddedVertex("deleted") { error("stale target allocated an id") })
    }

    @Test fun `moving an interior vertex keeps its neighbors fixed and its tangent smooth`() {
        val original = curve()
        val moved = original.withVertex(1, 180f, 90f)
        assertEquals(original.vertices().first(), moved.vertices().first())
        assertEquals(original.vertices().last(), moved.vertices().last())
        assertEquals(ShapeVertex(1, 180f, 90f), moved.vertices()[1])
        val a = moved.segments[0].cubic!!
        val b = moved.segments[1].cubic!!
        assertSameDirection(180f - a.x2, 90f - a.y2, b.x1 - 180f, b.y1 - 90f)
        assertEquals(original.segments.map { it.id }, moved.segments.map { it.id })
        assertEquals(moved, moved.withVertex(1, 180f, 90f))
    }

    @Test fun `each endpoint can move independently`() {
        for (index in listOf(0, 2)) {
            val original = curve()
            val moved = original.withVertex(index, 100f, 200f)
            assertEquals(ShapeVertex(index, 100f, 200f), moved.vertices()[index])
            original.vertices().filter { it.index != index }.forEach { assertEquals(it, moved.vertices()[it.index]) }
        }
    }

    @Test fun `stretching both endpoints sideways keeps the arch rounded`() {
        val original = curve().translated(1960f, 0f)
        val stretched = original.withVertex(0, 100f, 180f).withVertex(2, 4140f, 180f)
        assertEquals(original.vertices()[1], stretched.vertices()[1])
        assertEquals(3, stretched.vertices().size)
        assertRoundedArch(stretched)
    }

    @Test fun `stretching both endpoints down keeps the arch rounded`() {
        val original = curve()
        val stretched = original.withVertex(0, 40f, 2000f).withVertex(2, 280f, 2000f)
        assertEquals(original.vertices()[1], stretched.vertices()[1])
        assertRoundedArch(stretched)
    }

    @Test fun `stretching an endpoint after adding vertices keeps every join smooth`() {
        val original = curve().withExtraVertex()
        val moved = original.withVertex(original.vertices().lastIndex, 3000f, 900f)
        assertEquals(original.vertices().dropLast(1), moved.vertices().dropLast(1))
        moved.segments.zipWithNext().forEach { (a, b) ->
            val c = a.cubic!!
            val d = b.cubic!!
            assertSameDirection(a.x2 - c.x2, a.y2 - c.y2, d.x1 - b.x1, d.y1 - b.y1)
        }
    }

    @Test fun `coincident vertices remain finite and can be pulled apart again`() {
        val collapsed = curve().withVertex(1, 40f, 180f).withVertex(2, 40f, 180f)
        assertTrue(collapsed.segments.flatMap { it.polyline().toList() }.all { it.isFinite() })
        val reopened = collapsed.withVertex(1, 160f, 60f).withVertex(2, 280f, 180f)
        assertRoundedArch(reopened)
    }

    @Test fun `page boundary holds both the vertex and its controls`() {
        val moved = curve().withVertex(1, -200f, -200f)
        assertEquals(ShapeVertex(1, 0f, 0f), moved.vertices()[1])
        assertTrue(moved.segments.flatMap { it.polyline().toList() }.all { it >= 0f && it.isFinite() })
    }

    @Test fun `unequal resize and reflection transform the entire curve exactly`() {
        val original = curve().withExtraVertex()
        val transformed = original.scaledAbout(300f, 250f, -1.7f, 0.45f).translated(20f, 50f)
        original.segments.zip(transformed.segments).forEach { (a, b) ->
            for (step in 0..20) {
                val p = a.at(step / 20f)
                val q = b.at(step / 20f)
                assertEquals(300f + (p.first - 300f) * -1.7f + 20f, q.first, 0.0002f)
                assertEquals(250f + (p.second - 250f) * 0.45f + 50f, q.second, 0.0002f)
            }
        }
    }

    @Test fun `edited curves round trip through JSON and CBOR`() {
        val shape = curve().withExtraVertex().withVertex(1, 90f, 100f)
        assertEquals(shape, Json.decodeFromString<Outline.Shape>(Json.encodeToString(shape)))
        assertEquals(shape, Cbor.decodeFromByteArray<Outline.Shape>(Cbor.encodeToByteArray(shape)))
        val old = Json.decodeFromString<ShapeSegment>("""{"id":"old","x1":0,"y1":0,"x2":1,"y2":1}""")
        assertNull(old.cubic)
    }

    @Test fun `locked shapes and invalid edits leave geometry alone`() {
        val locked = curve().copy(lockGroup = "lock")
        assertEquals(locked, locked.withVertex(1, 60f, 60f))
        assertEquals(locked, locked.withAddedVertex(locked.segments.first().id) { error("locked shape allocated an id") })
        val original = curve()
        assertEquals(original, original.withVertex(-1, 60f, 60f))
        assertEquals(original, original.withVertex(1, Float.NaN, 60f))
        assertEquals(original, original.withVertex(1, 60f, Float.POSITIVE_INFINITY))
    }

    @Test fun `repeated additions stay connected and every new vertex is editable`() {
        var shape = curve()
        repeat(12) { shape = shape.withExtraVertex() }
        assertEquals(15, shape.vertices().size)
        shape = shape.withVertex(7, 200f, 220f)
        assertEquals(ShapeVertex(7, 200f, 220f), shape.vertices()[7])
        shape.segments.zipWithNext().forEach { (a, b) ->
            assertEquals(a.x2, b.x1, 0f)
            assertEquals(a.y2, b.y1, 0f)
        }
    }

    @Test fun `removing an inserted point restores the original three point curve`() {
        val original = curve()
        original.segments.forEachIndexed { index, segment ->
            val added = original.withAddedVertex(segment.id) { "new" }
            val point = added.curveRemovalPoints().single { it.index == index + 1 }
            assertEquals(original, added.withRemovedVertex(point.segmentId, point.atEnd))
        }
    }

    @Test fun `removing the middle point leaves an editable two point line`() {
        val original = curve()
        val point = original.curveRemovalPoints()[1]
        val reduced = original.withRemovedVertex(point.segmentId, point.atEnd)
        assertEquals(listOf(40f to 180f, 280f to 180f), reduced.vertices().map { it.x to it.y })
        reduced.segments.single().polyline().toList().chunked(2).forEach { assertEquals(180f, it[1], 0.001f) }
        assertTrue(reduced.curveRemovalPoints().isEmpty())
        assertEquals(1, reduced.curveInsertionPoints().size)
        assertEquals(3, reduced.withAddedVertex(reduced.segments.single().id) { "again" }.vertices().size)
    }

    @Test fun `either endpoint can be removed without moving the other vertices`() {
        val original = curve()
        for (index in listOf(0, 2)) {
            val target = original.curveRemovalPoints()[index]
            val reduced = original.withRemovedVertex(target.segmentId, target.atEnd)
            assertEquals(original.vertices().filter { it.index != index }.map { it.x to it.y },
                reduced.vertices().map { it.x to it.y })
            assertEquals(1, reduced.segments.size)
            assertTrue(reduced.segments.single().polyline().all { it.isFinite() && it >= 0f })
        }
    }

    @Test fun `interior removal reconnects the remaining spans and preserves their IDs`() {
        val original = curve().withExtraVertex().withExtraVertex()
        val target = original.curveRemovalPoints()[2]
        val reduced = original.withRemovedVertex(target.segmentId, target.atEnd)
        assertEquals(original.segments.filter { it.id != target.segmentId }.map { it.id }, reduced.segments.map { it.id })
        assertEquals(original.vertices().filter { it.index != 2 }.map { it.x to it.y }, reduced.vertices().map { it.x to it.y })
        reduced.segments.zipWithNext().forEach { (a, b) ->
            assertEquals(a.x2, b.x1, 0f)
            assertEquals(a.y2, b.y1, 0f)
            val c = a.cubic!!
            val d = b.cubic!!
            assertSameDirection(a.x2 - c.x2, a.y2 - c.y2, d.x1 - b.x1, d.y1 - b.y1)
        }
    }

    @Test fun `removal rejects locked stale invalid and last two point targets`() {
        val original = curve()
        val id = original.segments.first().id
        val locked = original.copy(lockGroup = "locked")
        assertTrue(locked.curveRemovalPoints().isEmpty())
        assertEquals(locked, locked.withRemovedVertex(id, false))
        assertEquals(original, original.withRemovedVertex("stale", false))
        assertEquals(original, original.withRemovedVertex(id, true))
        val line = original.withRemovedVertex(original.segments.last().id, false)
        assertEquals(line, line.withRemovedVertex(line.segments.single().id, false))
        assertEquals(line, line.withRemovedVertex(line.segments.single().id, true))
        val otherKind = original.copy(kind = ShapeKind.Line)
        assertTrue(otherKind.curveRemovalPoints().isEmpty())
        assertEquals(otherKind, otherKind.withRemovedVertex(id, false))
    }

    @Test fun `removed vertices and rebuilt controls round trip through JSON and CBOR`() {
        val original = curve().withExtraVertex().copy(borderWidth = 4f, lineType = LineType.Dashed)
        val target = original.curveRemovalPoints().last()
        val reduced = original.withRemovedVertex(target.segmentId, target.atEnd)
        assertEquals(4f, reduced.borderWidth, 0f)
        assertEquals(LineType.Dashed, reduced.lineType)
        assertEquals(reduced, Json.decodeFromString<Outline.Shape>(Json.encodeToString(reduced)))
        assertEquals(reduced, Cbor.decodeFromByteArray<Outline.Shape>(Cbor.encodeToByteArray(reduced)))
    }

    private fun Outline.Shape.withExtraVertex(): Outline.Shape =
        withAddedVertex(segments[segments.size / 2].id) { "s-${serial++}" }

    private fun ShapeSegment.at(t: Float): Pair<Float, Float> {
        val c = cubic!!
        val u = 1f - t
        return (u*u*u*x1 + 3*u*u*t*c.x1 + 3*u*t*t*c.x2 + t*t*t*x2) to
            (u*u*u*y1 + 3*u*u*t*c.y1 + 3*u*t*t*c.y2 + t*t*t*y2)
    }

    private fun assertRoundedArch(shape: Outline.Shape) {
        shape.segments.forEach { span ->
            val chordMidY = (span.y1 + span.y2) / 2f
            val archHeight = kotlin.math.abs(span.y2 - span.y1)
            assertTrue("The stretched curve follows the triangle's straight side",
                span.at(0.5f).second < chordMidY - archHeight * 0.20f)
        }
    }

    private fun assertSameDirection(ax: Float, ay: Float, bx: Float, by: Float) {
        val a = hypot(ax, ay)
        val b = hypot(bx, by)
        assertTrue("A smooth join lost its tangent", a > 0f && b > 0f)
        assertEquals(ax / a, bx / b, 0.0001f)
        assertEquals(ay / a, by / b, 0.0001f)
    }
}
