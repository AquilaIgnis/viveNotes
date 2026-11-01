package com.vivenotes.graph

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/** The grammar `math_engine.py` writes, read and evaluated; and what the canvas finds in it. */
class GraphMathTest {

    private fun parse(source: String): GraphExpression {
        val parsed = GraphExpression.parse(source)
        assertNotNull("$source should parse", parsed)
        return parsed!!
    }

    @Test
    fun evaluatesWhatTheEngineWrites() {
        // Each string is verbatim from `math_engine.graph_source` for the formula in the comment.
        assertEquals(5.0, parse("((x^2)+(-4))").evaluate(3.0), 1e-12) // x^2 - 4
        assertEquals(1.0, parse("(sin(x)+(2^(-1)))").evaluate(PI / 6) - 0.0, 1e-12) // sin x + 1/2
        assertEquals(2.0, parse("sqrt(x)").evaluate(4.0), 1e-12)
        assertEquals(3.0, parse("abs((x+(-3)))").evaluate(0.0), 1e-12)
        assertEquals(2.0, parse("(ln(x)/ln(10))").evaluate(100.0), 1e-12)
        assertEquals(-0.5, parse("((x+(-1))^(-1))").evaluate(-1.0), 1e-12)
        assertEquals(1.5e-5, parse("(1.5e-05*x)").evaluate(1.0), 1e-18)
    }

    @Test
    fun undefinedValuesAreNaNNotErrors() {
        assertTrue(parse("sqrt(x)").evaluate(-1.0).isNaN())
        assertTrue(parse("ln(x)").evaluate(0.0).isNaN())
        assertTrue(parse("(1/x)").evaluate(0.0).isNaN())
    }

    @Test
    fun minusBindsLooserThanPower() {
        assertEquals(-4.0, parse("-x^2").evaluate(2.0), 0.0)
        assertEquals(0.125, parse("2^-3").evaluate(0.0), 0.0)
        assertEquals(512.0, parse("2^3^2").evaluate(0.0), 0.0)
    }

    @Test
    fun refusesWhatIsNotTheGrammar() {
        listOf("", "y", "x +", "sin x", "__import__(os)", "(x", "x)", "foo(x)", "1..2", "(".repeat(500) + "x" + ")".repeat(500))
            .forEach { assertNull(it.take(40), GraphExpression.parse(it)) }
    }

    @Test
    fun readsPolynomialCoefficients() {
        assertArrayEquals(doubleArrayOf(-4.0, 0.0, 1.0), parse("((x^2)+(-4))").polynomial(), 1e-12)
        assertArrayEquals(
            doubleArrayOf(3.0, -1.0, -3.0, 1.0),
            parse("(((x+(-1))*(x+1))*(x+(-3)))").polynomial(),
            1e-12,
        )
        assertArrayEquals(doubleArrayOf(0.0, 0.5), parse("(x/2)").polynomial(), 1e-12)
        assertNull(parse("(1/x)").polynomial())
        assertNull(parse("sqrt(x)").polynomial())
        assertNull(parse("(x^(1/2))").polynomial())
        assertNull(parse("sin(x)").polynomial())
    }

    @Test
    fun findsZerosInterceptAndVertexOfAParabola() {
        val f = parse("((x^2)+(-4))")
        val points = GraphAnalysis.pointsOfInterest(f::evaluate, GraphWindow(-10.0, 10.0, -10.0, 10.0))
        val zeros = points.filter { it.kind == PointKind.Zero }.map { it.x }.sorted()
        assertEquals(listOf(-2.0, 2.0), zeros.map { Math.round(it * 1e9) / 1e9 })
        val minimum = points.single { it.kind == PointKind.Minimum }
        assertEquals(0.0, minimum.x, 1e-6)
        assertEquals(-4.0, minimum.y, 1e-9)
        // The vertex sits on the y-axis, so the intercept is the same place; both are reported.
        assertEquals(-4.0, points.single { it.kind == PointKind.YIntercept }.y, 1e-12)
        assertTrue(points.none { it.kind == PointKind.Inflection })
    }

    @Test
    fun aTouchingZeroIsAZeroAndNotAlsoAnExtremum() {
        val f = parse("(((x+(-1))^2)*(x+3))") // (x − 1)²(x + 3)
        val points = GraphAnalysis.pointsOfInterest(f::evaluate, GraphWindow(-10.0, 10.0, -50.0, 50.0))
        val zeros = points.filter { it.kind == PointKind.Zero }.map { it.x }.sorted()
        assertEquals(2, zeros.size)
        assertEquals(-3.0, zeros[0], 1e-6)
        assertEquals(1.0, zeros[1], 1e-4)
        assertTrue(points.none { it.kind == PointKind.Minimum })
        assertEquals(1, points.count { it.kind == PointKind.Maximum })
        assertEquals(1, points.count { it.kind == PointKind.Inflection })
    }

    @Test
    fun aPoleIsNeitherAZeroNorAnExtremum() {
        val reciprocal = parse("(1/x)")
        val points = GraphAnalysis.pointsOfInterest(reciprocal::evaluate, GraphWindow(-5.0, 5.0, -5.0, 5.0))
        assertTrue(points.toString(), points.isEmpty())

        val square = parse("(1/(x^2))")
        val again = GraphAnalysis.pointsOfInterest(square::evaluate, GraphWindow(-5.0, 5.0, -5.0, 5.0))
        assertTrue(again.toString(), again.isEmpty())
    }

    @Test
    fun aCrowdedKindIsLeftOut() {
        val f = parse("sin((50*x))")
        val points = GraphAnalysis.pointsOfInterest(f::evaluate, GraphWindow(-10.0, 10.0, -2.0, 2.0))
        assertTrue(points.none { it.kind == PointKind.Zero })
    }

    @Test
    fun fitShowsTheInterestingPartAndTheAxis() {
        val f = parse("((x^2)+(-4))")
        val window = GraphAnalysis.fit(f::evaluate)
        assertTrue(window.toString(), window.xMin < -2.0 && window.xMax > 2.0)
        assertTrue(window.toString(), window.yMin < -4.0 && window.yMax > 0.0)
    }

    @Test
    fun stepsAreRound() {
        assertEquals("0.5", GraphAnalysis.niceStep(20.0, 40).toPlainString())
        assertEquals("2", GraphAnalysis.niceStep(20.0, 8).toPlainString())
        assertEquals("1.5", GraphAnalysis.snap(1.4, GraphAnalysis.niceStep(20.0, 40)).toPlainString())
        assertEquals("-0.3", GraphAnalysis.snap(-0.31, java.math.BigDecimal("0.1")).toPlainString())
    }

    @Test
    fun draggingAPolynomialZeroMovesOnlyThatRoot() {
        val f = parse("((x^2)+(-4))")
        val zero = GraphPoint(2.0, 0.0, PointKind.Zero)
        val drag = GraphDrag.of(f::evaluate, f.polynomial(), zero, 3.4, 0.7, GraphWindow(-10.0, 10.0, -10.0, 10.0))
        drag as GraphDrag.Root
        assertEquals(3.5, drag.pointX, 0.0)
        assertEquals(0.0, drag.pointY, 0.0)
        // (x + 2)(x − 3.5): the other root stays, the leading coefficient stays.
        assertEquals(0.0, drag.evaluate(-2.0), 1e-9)
        assertEquals(0.0, drag.evaluate(3.5), 1e-9)
        assertEquals(-7.0, drag.evaluate(0.0), 1e-9)
        assertEquals("""{"kind":"root","root":"2.0","to":"3.5"}""", drag.request())
    }

    @Test
    fun aDoubleRootMovesWithItsMultiplicity() {
        val f = parse("(((x+(-1))^2)*(x+3))")
        val zero = GraphPoint(1.0000003, 0.0, PointKind.Zero)
        val drag = GraphDrag.of(f::evaluate, f.polynomial(), zero, 2.0, 0.0, GraphWindow(-10.0, 10.0, -50.0, 50.0))
        drag as GraphDrag.Root
        // Still tangent at its new place: (x − 2)²(x + 3).
        assertEquals(0.0, drag.evaluate(2.0), 1e-6)
        assertEquals(12.0, drag.evaluate(0.0), 1e-4)
    }

    @Test
    fun draggingAVertexTranslates() {
        val f = parse("((x^2)+(-4))")
        val vertex = GraphPoint(0.0, -4.0, PointKind.Minimum)
        val drag = GraphDrag.of(f::evaluate, f.polynomial(), vertex, 1.1, -2.9, GraphWindow(-10.0, 10.0, -10.0, 10.0))
        drag as GraphDrag.Shift
        assertEquals("1", drag.h.toPlainString())
        assertEquals("1", drag.k.toPlainString())
        assertEquals(-3.0, drag.evaluate(1.0), 1e-12)
        assertEquals("""{"kind":"shift","h":"1","k":"1"}""", drag.request())
    }

    @Test
    fun aNonPolynomialZeroShiftsSideways() {
        val f = parse("sin(x)")
        val zero = GraphPoint(PI, 0.0, PointKind.Zero)
        val drag = GraphDrag.of(f::evaluate, f.polynomial(), zero, PI + 0.52, 3.0, GraphWindow(-10.0, 10.0, -2.0, 2.0))
        drag as GraphDrag.Shift
        assertEquals("0.5", drag.h.toPlainString())
        assertEquals(0, drag.k.signum())
        assertEquals(0.0, drag.evaluate(0.5), 1e-12)
    }

    @Test
    fun theInterceptMovesVertically() {
        val f = parse("(x+1)")
        val intercept = GraphPoint(0.0, 1.0, PointKind.YIntercept)
        val drag = GraphDrag.of(f::evaluate, f.polynomial(), intercept, 4.0, 3.0, GraphWindow(-10.0, 10.0, -10.0, 10.0))
        drag as GraphDrag.Shift
        assertEquals(0, drag.h.signum())
        assertEquals(3.0, drag.evaluate(0.0), 1e-12)
        assertTrue(abs(drag.pointX) < 1e-12)
        assertEquals(sqrt(9.0), drag.pointY, 1e-12)
    }
}
