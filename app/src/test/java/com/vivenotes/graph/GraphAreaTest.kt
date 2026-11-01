package com.vivenotes.graph

import com.vivenotes.model.GraphInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.PI

class GraphAreaTest {

    private fun f(source: String): (Double) -> Double = GraphExpression.parse(source)!!::evaluate

    @Test
    fun aParabolaBelowTheAxisHasANegativeIntegral() {
        val area = GraphArea.integrate(f("((x^2)+(-4))"), -2.0, 2.0)!!
        assertEquals(-32.0 / 3, area.net, 1e-9)
        assertEquals(32.0 / 3, area.total, 1e-9)
        assertFalse(area.crosses)
    }

    @Test
    fun aWaveCancelsInTheNetButNotInTheTotal() {
        val area = GraphArea.integrate(f("sin(x)"), -PI, PI)!!
        assertEquals(0.0, area.net, 1e-9)
        assertEquals(4.0, area.total, 1e-9)
        assertTrue(area.crosses)
    }

    @Test
    fun swappedBoundsChangeTheSign() {
        val forward = GraphArea.integrate(f("sin(x)"), 0.0, PI)!!
        val backward = GraphArea.integrate(f("sin(x)"), PI, 0.0)!!
        assertEquals(2.0, forward.net, 1e-9)
        assertEquals(-2.0, backward.net, 1e-9)
        assertEquals(2.0, backward.total, 1e-9)
    }

    @Test
    fun kinksAndJumpsAreStillIntegrated() {
        // 2 from −1 to the corner, 0.5 after it.
        assertEquals(2.5, GraphArea.integrate(f("abs((x+(-1)))"), -1.0, 2.0)!!.net, 1e-6)
        assertEquals(E - 1, GraphArea.integrate(f("exp(x)"), 0.0, 1.0)!!.net, 1e-9)
        // ⌊x⌋ on 0…2.5 is 0 + 1 + 2·0.5.
        assertEquals(2.0, GraphArea.integrate(f("floor(x)"), 0.0, 2.5)!!.net, 1e-4)
    }

    @Test
    fun aPoleInsideHasNoArea() {
        assertNull(GraphArea.integrate(f("(1/x)"), -1.0, 1.0))
        assertNull(GraphArea.integrate(f("(1/x)"), -1.0, 1.013))
        assertNull(GraphArea.integrate(f("(1/(x^2))"), -1.0, 1.013))
        assertNull(GraphArea.integrate(f("tan(x)"), 0.0, 3.0))
        assertNull(GraphArea.integrate(f("sqrt(x)"), -1.0, 1.0))
    }

    @Test
    fun aTallSmoothPeakIsNotAPole() {
        // 1/(x² + 0.0001) peaks at 10 000 and is finite everywhere: its integral is 200·atan(100).
        val area = GraphArea.integrate(f("(1/((x^2)+0.0001))"), -1.0, 1.0)!!
        assertEquals(200 * kotlin.math.atan(100.0), area.net, 1e-3)
    }

    @Test
    fun itStartsBetweenTheZerosNearestTheMiddle() {
        val window = GraphWindow(-5.0, 5.0, -5.0, 5.0)
        val parabola = GraphArea.start(f("((x^2)+(-4))"), window)
        assertEquals(-2.0, parabola.from, 1e-6)
        assertEquals(2.0, parabola.to, 1e-6)

        val wave = GraphArea.start(f("sin(x)"), GraphWindow(-7.0, 7.0, -2.0, 2.0))
        assertEquals(0.0, wave.from, 1e-6)
        assertEquals(PI, wave.to, 1e-6)

        val line = GraphArea.start(f("(x+10)"), window)
        assertEquals(GraphInterval(-1.6, 1.6), line)
    }

    @Test
    fun theValueIsNamedAfterTheAxes() {
        val area = AreaValue(net = -10.6666, total = 10.6666, crosses = false)
        assertEquals("∫ y dx = −10.667", GraphArea.netText(area, "x", "y"))
        assertEquals("∫ |v| dt = 10.667", GraphArea.totalText(area, "t", "v"))
    }
}
