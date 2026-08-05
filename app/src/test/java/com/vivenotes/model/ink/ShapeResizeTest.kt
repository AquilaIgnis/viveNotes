package com.vivenotes.model.ink

import com.vivenotes.model.Outline
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resizing a shape — the arithmetic behind the corner handles.
 *
 * Both bugs these guard against showed up as *shape*, not as numbers: a circle dragged by a corner
 * came apart into four separate arcs, and any shape dragged for more than a frame or two blew far
 * past the finger. Both are pure geometry, so both can be pinned here rather than on a device.
 */
class ShapeResizeTest {

    /** The unit circle at the origin, seeded the way [seedSegments] seeds an ellipse. */
    private fun circle(radius: Float = 1f): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = "circle",
            kind = ShapeKind.Ellipse,
            segments = seedSegments(
                ShapeKind.Ellipse, -radius, -radius, radius, radius,
            ) { "seg-${next++}" },
        ).withRecomputedBounds()
    }

    private fun square(side: Float = 80f): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = "square",
            kind = ShapeKind.Rectangle,
            segments = seedSegments(ShapeKind.Rectangle, 0f, 0f, side, side) { "seg-${next++}" },
        ).withRecomputedBounds()
    }

    // -------------------------------------------------------------------------------------------
    // The arcs: a stretched circle is an ellipse, not four loose arcs
    // -------------------------------------------------------------------------------------------

    @Test
    fun aQuarterArcKnowsWhereItsApexIs() {
        // The convention the whole of the fix rests on: a positive bulge bows to the left of the
        // direction of travel, and the apex of a quarter of the unit circle is the point at 45°.
        val quarter = ShapeSegment("q", x1 = 0f, y1 = -1f, x2 = 1f, y2 = 0f, bulge = ShapeSegment.QUARTER_ARC)

        val (apexX, apexY) = quarter.apex()

        assertEquals(0.70710678f, apexX, 1e-4f)
        assertEquals(-0.70710678f, apexY, 1e-4f)
        assertEquals("the apex is on the circle", 1f, hypot(apexX, apexY), 1e-4f)
    }

    @Test
    fun aUniformScaleLeavesTheBulgeAlone() {
        // The property the old code assumed held in general: it does hold here, and has to keep
        // holding, because a bulge is a fraction of the chord and both grew by the same factor.
        val quarter = ShapeSegment("q", x1 = 0f, y1 = -1f, x2 = 1f, y2 = 0f, bulge = ShapeSegment.QUARTER_ARC)

        val scaled = quarter.scaledAbout(anchorX = 0f, anchorY = 0f, scaleX = 7f, scaleY = 7f)

        assertEquals(ShapeSegment.QUARTER_ARC, scaled.bulge, 1e-5f)
    }

    /** The worst any drawn point strays off the ellipse of the given radii, as a fraction of it. */
    private fun Outline.Shape.strayFromEllipse(
        centreX: Float,
        centreY: Float,
        radiusX: Float,
        radiusY: Float,
    ): Float {
        var worst = 0f
        segments.forEach { segment ->
            val points = segment.polyline()
            for (index in points.indices step 2) {
                val offAxisX = (points[index] - centreX) / radiusX
                val offAxisY = (points[index + 1] - centreY) / radiusY
                worst = maxOf(worst, abs(hypot(offAxisX, offAxisY) - 1f))
            }
        }
        return worst
    }

    @Test
    fun stretchingACircleKeepsEveryArcOnTheEllipse() {
        // The reported bug: stretched 3:1, every quadrant kept a quarter circle's curvature against
        // a chord that was no longer a quarter circle's, bowed a third of the way past the outline
        // and crossed its neighbours — the circle "split into four arcs".
        val stretched = circle().scaledAbout(anchorX = 0f, anchorY = 0f, scaleX = 3f, scaleY = 1f)

        val stray = stretched.strayFromEllipse(0f, 0f, radiusX = 3f, radiusY = 1f)

        assertTrue("a drawn point sits ${stray * 100}% off the ellipse", stray < 0.01f)
    }

    @Test
    fun aWideEllipseIsAnEllipseTheMomentItIsDrawn() {
        // Not only a resize: four quarter circles are an ellipse when the box is square and nothing
        // else, so a wide one came out of the shape tool already looking like four arcs.
        var next = 0
        val wide = Outline.Shape(
            id = "wide",
            kind = ShapeKind.Ellipse,
            segments = seedSegments(ShapeKind.Ellipse, 0f, 0f, 300f, 100f) { "seg-${next++}" },
        ).withRecomputedBounds()

        val stray = wide.strayFromEllipse(150f, 50f, radiusX = 150f, radiusY = 50f)

        assertTrue("a drawn point sits ${stray * 100}% off the ellipse", stray < 0.01f)
        assertEquals(300f, wide.width, 0.5f)
        assertEquals(100f, wide.height, 0.5f)
    }

    @Test
    fun aStoredEllipseMatchesThePreviewItWasDraggedOutOf() {
        // The preview under the pen is [trace], which walks the ellipse parametrically and so is
        // smooth at any shape of box. What lands in the document is segments, and until those were
        // cut from the same curve the shape visibly changed the moment the pen came up.
        val box = floatArrayOf(0f, 0f, 300f, 100f)
        var next = 0
        val stored = Outline.Shape(
            id = "wide",
            kind = ShapeKind.Ellipse,
            segments = seedSegments(ShapeKind.Ellipse, box[0], box[1], box[2], box[3]) { "s${next++}" },
        )
        val preview = trace(ShapeKind.Ellipse, box[0], box[1], box[2], box[3]).solid.single()

        stored.segments.forEach { segment ->
            val points = segment.polyline()
            for (index in points.indices step 2) {
                // To the traced *outline*, not to its vertices: the preview is a polyline whose
                // vertices stand a couple of dp apart, so a point exactly on the curve is still a
                // couple of dp from the nearest of them.
                // A quarter of a page unit on a 300dp shape — the two approximations' own error,
                // and a couple of hundred times smaller than the four-quarter-circles this replaced.
                val gap = preview.distanceToPolyline(points[index], points[index + 1])
                assertTrue("the stored ellipse is ${gap}dp off the preview", gap < 0.25f)
            }
        }
    }

    /** Shortest distance from a point to a traced outline. */
    private fun FloatArray.distanceToPolyline(x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (index in 0 until size - 2 step 2) {
            val edgeX = this[index + 2] - this[index]
            val edgeY = this[index + 3] - this[index + 1]
            val lengthSquared = edgeX * edgeX + edgeY * edgeY
            val along = if (lengthSquared == 0f) {
                0f
            } else {
                (((x - this[index]) * edgeX + (y - this[index + 1]) * edgeY) / lengthSquared)
                    .coerceIn(0f, 1f)
            }
            best = minOf(
                best,
                hypot(x - (this[index] + along * edgeX), y - (this[index + 1] + along * edgeY)),
            )
        }
        return best
    }

    @Test
    fun anEllipseSurvivesBeingDraggedAboutRepeatedly() {
        // Each resize re-derives the arcs from where the last one left them, so the error has to
        // stay put rather than build up over a session's worth of dragging.
        var shape = circle(radius = 50f)
        repeat(6) {
            shape = shape.scaledAbout(-50f, -50f, scaleX = 2.5f, scaleY = 1f)
            shape = shape.scaledAbout(-50f, -50f, scaleX = 0.4f, scaleY = 1f)
        }

        val stray = shape.strayFromEllipse(0f, 0f, radiusX = 50f, radiusY = 50f)

        assertTrue("the ellipse drifted by ${stray * 100}% over a dozen resizes", stray < 0.01f)
        assertEquals(100f, shape.width, 1f)
    }

    @Test
    fun stretchingACircleGivesItTheBoundsItWasDraggedTo() {
        val stretched = circle(radius = 50f)
            .scaledAbout(anchorX = -50f, anchorY = -50f, scaleX = 3f, scaleY = 1f)

        // Anchored at the top-left corner of the box, so that corner stays and the box is 3x wide.
        assertEquals(-50f, stretched.x, 0.5f)
        assertEquals(-50f, stretched.y, 0.5f)
        assertEquals(300f, stretched.width, 1f)
        assertEquals(100f, stretched.height, 1f)
    }

    @Test
    fun aMirroredArcBowsTheOtherWay() {
        // Used to be a special case keyed on the sign of scaleX * scaleY. Scaling the apex gets it
        // for nothing, and gets the case that rule had backwards — a 180° turn — right as well.
        val quarter = ShapeSegment("q", x1 = 0f, y1 = -1f, x2 = 1f, y2 = 0f, bulge = ShapeSegment.QUARTER_ARC)

        val mirrored = quarter.scaledAbout(0f, 0f, scaleX = -1f, scaleY = 1f)
        val turned = quarter.scaledAbout(0f, 0f, scaleX = -1f, scaleY = -1f)

        assertEquals(-ShapeSegment.QUARTER_ARC, mirrored.bulge, 1e-5f)
        assertEquals(ShapeSegment.QUARTER_ARC, turned.bulge, 1e-5f)
    }

    @Test
    fun aStraightSegmentStaysStraight() {
        val edge = ShapeSegment.straight("e", 0f, 0f, 10f, 0f)

        val scaled = edge.scaledAbout(0f, 0f, scaleX = 4f, scaleY = 0.25f)

        assertEquals(0f, scaled.bulge, 0f)
        assertEquals(40f, scaled.x2, 1e-4f)
    }

    /** A shape as [seedSegments] lays it down, which is what the document keeps. */
    private fun seeded(kind: ShapeKind, left: Float, top: Float, right: Float, bottom: Float): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = kind.name,
            kind = kind,
            segments = seedSegments(kind, left, top, right, bottom) { "seg-${next++}" },
        ).withRecomputedBounds()
    }

    /** Every y a segment is drawn through. */
    private fun ShapeSegment.drawnY(): List<Float> =
        polyline().filterIndexed { index, _ -> index % 2 == 1 }

    @Test
    fun theOccludedHalfOfASphereIsTheFarOne() {
        // Reported as "the 3D lines on the sphere are on the opposite side". The viewer is above the
        // shape, so the *near* half of the equator bows down towards them and is solid; the far half
        // passes behind the body and is dotted. Seeding had the two the wrong way round, which drew
        // every sphere as if it were being looked at from underneath.
        val sphere = seeded(ShapeKind.Sphere, 0f, 0f, 100f, 100f)
        val centreY = 50f

        val hiddenY = sphere.segments.filter(ShapeSegment::hidden).flatMap { it.drawnY() }
        assertTrue("a sphere should have an occluded equator", hiddenY.isNotEmpty())
        // y grows downwards and the viewer is above, so the far half is the one with the smaller y.
        assertTrue(
            "the dotted half of the equator is the near one — the sphere is drawn from below",
            hiddenY.all { it <= centreY + 0.5f },
        )

        // And the same way round in the preview, which is what the pen shows before it commits.
        val traced = trace(ShapeKind.Sphere, 0f, 0f, 100f, 100f)
        val tracedHiddenY = traced.hidden.flatMap { arc ->
            arc.filterIndexed { index, _ -> index % 2 == 1 }
        }
        assertTrue(
            "the preview and the stored shape disagree about which half is occluded",
            tracedHiddenY.all { it <= centreY + 0.5f },
        )
    }

    @Test
    fun aSphereKeepsItsEquatorInsideItsSilhouetteAcrossAStretch() {
        val sphere = seeded(ShapeKind.Sphere, 0f, 0f, 100f, 100f)

        val stretched = sphere.scaledAbout(0f, 0f, scaleX = 2f, scaleY = 1f)

        // The equator is a rim of its own, so a stretch that flattens it must not let it swing out
        // past the body it belongs to — the shape's own height is unchanged and nothing exceeds it.
        assertEquals(100f, stretched.height, 1f)
        assertEquals(200f, stretched.width, 1f)
        val stray = stretched.strayFromEllipse(100f, 50f, radiusX = 100f, radiusY = 50f)
        assertTrue("part of the sphere left its silhouette by ${stray * 100}%", stray <= 1f)
    }

    @Test
    fun aConeStandsOnItsBaseAndACylinderOnBoth() {
        val cone = seeded(ShapeKind.Cone, 0f, 0f, 100f, 120f)
        val cylinder = seeded(ShapeKind.Cylinder, 0f, 0f, 100f, 120f)

        listOf(cone, cylinder).forEach { solid ->
            assertEquals("${solid.kind} does not fill the box it was dragged", 100f, solid.width, 1f)
            assertEquals("${solid.kind} does not fill the box it was dragged", 120f, solid.height, 1f)
            // Both have a rim at the foot whose far half is occluded, the sphere's rule again.
            val hiddenY = solid.segments.filter(ShapeSegment::hidden).flatMap { it.drawnY() }
            assertTrue("${solid.kind} has no occluded edge", hiddenY.isNotEmpty())
            // The base ellipse's centre: its far half is everything above that line.
            val baseCentreY = 120f - minOf(50f * 0.28f, 120f * 0.22f)
            assertTrue(
                "${solid.kind}'s dotted rim is the near side of its base, not the far one",
                hiddenY.all { it <= baseCentreY + 0.5f },
            )
        }

        // A cone's apex is a point at the top; a cylinder's top is a rim as wide as its body.
        val topOfCone = cone.segments.flatMap { it.polyline().toList().chunked(2) }
            .filter { it[1] <= 1f }
        assertTrue("a cone should meet the top of its box only at the apex", topOfCone.all { it[0] in 49f..51f })
        val topOfCylinder = cylinder.segments.flatMap { it.polyline().toList().chunked(2) }
            .filter { it[1] <= 1f }
        assertTrue("a cylinder's top should be a rim, not a point", topOfCylinder.size > 2)
    }

    // -------------------------------------------------------------------------------------------
    // Contours: what gets stroked in one pass
    // -------------------------------------------------------------------------------------------

    @Test
    fun anEllipseIsStrokedAsOneClosedRun() {
        // Reported as "some of the 3D dots seem to overlap": a dash pattern restarts on every path
        // it is applied to, so stroking sixteen arcs one at a time lays two dots at each joint.
        val contours = seeded(ShapeKind.Ellipse, 0f, 0f, 200f, 100f).segments.contours()

        assertEquals("an ellipse should be one stroke, not sixteen", 1, contours.size)
        assertTrue("the ring should close rather than butt two caps together", contours.single().isClosed)
    }

    @Test
    fun aRimSplitsWhereItsTreatmentChangesAndNowhereElse() {
        val contours = seeded(ShapeKind.Cylinder, 0f, 0f, 100f, 120f).segments.contours()

        // Solid runs and occluded runs never share a stroke, because they are drawn differently.
        contours.forEach { contour ->
            assertTrue(
                "a contour mixes visible and occluded segments",
                contour.segments.all { it.hidden == contour.hidden },
            )
        }
        assertEquals("the far half of the foot should be one dotted run", 1, contours.count { it.hidden })
        // Every joint inside a run really is a joint: the run is drawn as one unbroken polyline.
        contours.forEach { contour ->
            contour.segments.zipWithNext().forEach { (before, after) ->
                assertEquals("a run jumped between segments", before.x2, after.x1, 0.01f)
                assertEquals("a run jumped between segments", before.y2, after.y1, 0.01f)
            }
        }
    }

    @Test
    fun aRunIsDrawnAsOnePolylineWithNoRepeatedJoints() {
        val contour = seeded(ShapeKind.Ellipse, 0f, 0f, 200f, 100f).segments.contours().single()

        val joined = contour.polyline()
        val separate = contour.segments.sumOf { it.polyline().size }

        // One point per joint is dropped: fifteen internal joints across sixteen arcs.
        assertEquals(separate - 2 * (contour.segments.size - 1), joined.size)
        for (index in 2 until joined.size step 2) {
            assertTrue(
                "the run doubles back on itself at ${joined[index]}, ${joined[index + 1]}",
                hypot(joined[index] - joined[index - 2], joined[index + 1] - joined[index - 1]) > 1e-4f,
            )
        }
    }

    @Test
    fun segmentsThatDoNotMeetStayAsSeparateRuns() {
        // An arrow is a shaft and a head that do not touch, and must not be strung into one stroke.
        val contours = seeded(ShapeKind.Arrow, 0f, 0f, 100f, 0f).segments.contours()

        assertTrue("an arrow's head should not be joined to its shaft", contours.size > 1)
    }

    // -------------------------------------------------------------------------------------------
    // The scale is absolute: applying it twice is not applying it once
    // -------------------------------------------------------------------------------------------

    @Test
    fun aResizeIsMeasuredFromWhereTheDragBeganNotFromTheLastFrame() {
        // What the handles report, frame by frame, is the total scale since the finger went down.
        // Applying each frame's total to the previous frame's result is what sent shapes off the
        // page — 20 frames of a smooth drag to twice the size came out ~3200x instead.
        val start = square(side = 80f)
        val framesOfOneDrag = (1..20).map { 1f + 0.05f * it }

        val compounded = framesOfOneDrag.fold(start) { shape, scale ->
            shape.scaledAbout(0f, 0f, scale, scale)
        }
        val correct = start.scaledAbout(0f, 0f, framesOfOneDrag.last(), framesOfOneDrag.last())

        assertEquals("the last frame's scale is the whole drag", 160f, correct.width, 0.5f)
        assertTrue(
            "compounding a drag's frames should be nothing like applying its total: ${compounded.width}dp",
            compounded.width > 100_000f,
        )
    }

    @Test
    fun draggingACornerBackToWhereItStartedRestoresTheShape() {
        // Only true of an absolute scale applied to the starting geometry. It is the property that
        // makes a resize feel like dragging a corner rather than like winding a ratchet.
        val start = square(side = 80f)

        val out = start.scaledAbout(0f, 0f, 2.5f, 0.4f)
        val back = start.scaledAbout(0f, 0f, 1f, 1f)

        assertEquals(200f, out.width, 0.5f)
        assertEquals(80f, back.width, 1e-3f)
        assertEquals(80f, back.height, 1e-3f)
    }
}
