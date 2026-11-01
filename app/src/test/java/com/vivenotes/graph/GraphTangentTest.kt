package com.vivenotes.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp

class GraphTangentTest {

    private val window = GraphWindow(-5.0, 5.0, -5.0, 5.0)

    private fun f(source: String): (Double) -> Double = GraphExpression.parse(source)!!::evaluate

    @Test
    fun theSlopeOfAParabolaIsTwiceX() {
        val tangent = GraphTangent.at(f("(x^2)"), 1.5, window)!!
        assertEquals(2.25, tangent.y, 0.0)
        assertEquals(3.0, tangent.slope!!, 1e-9)
        assertEquals(-2.25, tangent.intercept!!, 1e-9)
        assertEquals(3.75, tangent.lineAt(2.0), 1e-9)
    }

    @Test
    fun aSteepSmoothCurveKeepsItsTangent() {
        val wide = GraphWindow(-10.0, 10.0, -10.0, 10.0)
        val tangent = GraphTangent.at(f("exp((10*x))"), 1.0, wide)!!
        assertEquals(10 * exp(10.0), tangent.slope!!, 10 * exp(10.0) * 1e-6)
    }

    @Test
    fun aCornerHasNoTangent() {
        val corner = GraphTangent.at(f("abs(x)"), 0.0, window)!!
        assertEquals(0.0, corner.y, 0.0)
        assertNull(corner.slope)
        assertNull(GraphTangent.lineText(corner, "x", "y"))
    }

    @Test
    fun anUndefinedPointHasNoTracer() {
        assertNull(GraphTangent.at(f("sqrt(x)"), -1.0, window))
        assertNull(GraphTangent.at(f("(1/x)"), 0.0, window))
    }

    @Test
    fun theTracerSnapsToAPointOfInterestThenToTheGrid() {
        val vertex = GraphPoint(PI / 2, 1.0, PointKind.Maximum)
        assertEquals(PI / 2, GraphTangent.snap(1.6, listOf(vertex), 0.1, window), 0.0)
        // The fine grid on a window 10 wide is 0.2.
        assertEquals(1.2, GraphTangent.snap(1.27, listOf(vertex), 0.1, window), 1e-12)
    }

    @Test
    fun theTracerStartsMidWindowOrNearestToItWhereTheFunctionExists() {
        assertEquals(0.0, GraphTangent.start(f("(x^2)"), window)!!, 0.0)
        val start = GraphTangent.start(f("sqrt((x+(-1)))"), window)!!
        assertEquals(1.0, start, 0.06)
    }

    @Test
    fun theLineReadsTheWayItIsWritten() {
        fun line(m: Double, b: Double): String? =
            GraphTangent.lineText(Tangent(0.0, b, m), "x", "y")
        assertEquals("y = 3x − 2.25", line(3.0, -2.25))
        assertEquals("y = −x + 1", line(-1.0, 1.0))
        assertEquals("y = x", line(1.0, 0.0))
        assertEquals("y = 4", line(0.0, 4.0))
        assertEquals("y = 0", line(0.0, 0.0))
        assertEquals("y = −0.5x + 0.333", line(-0.5, 1.0 / 3))
        assertEquals("v = 9.8t", GraphTangent.lineText(Tangent(0.0, 0.0, 9.8), "t", "v"))
    }

    @Test
    fun theSlopeIsNamedAfterTheAxes() {
        assertEquals("dy/dx = 3", GraphTangent.slopeText(3.0000000001, "x", "y"))
        assertEquals("dv/dt = −9.81", GraphTangent.slopeText(-9.81, "t", "v"))
        assertEquals("dy/dx = 0", GraphTangent.slopeText(-1e-12, "x", "y"))
    }
}
