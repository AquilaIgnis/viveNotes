package com.vivenotes.graph

import com.vivenotes.model.GraphParameter
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.decodePageDoc
import com.vivenotes.model.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** A graph's parameters: read from the grammar, bound to slider values, snapped, swept and stored. */
class GraphSlidersTest {

    // Verbatim from `math_engine.graph_source` for A sin(ωt + φ), parameters A, ω, φ in that order.
    private val wave = "(\$0*sin(((\$1*x)+\$2)))"

    private fun parameter(symbol: String, value: Double) = GraphParameter(symbol, symbol, value)

    @Test
    fun parametersAreBoundWhenEvaluated() {
        val parsed = GraphExpression.parse(wave)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.parameterCount)
        // Unbound, a parameter is NaN rather than a guess.
        assertTrue(parsed.evaluate(1.0).isNaN())

        val bound = parsed.with(listOf(2.0, 3.0, 0.5))!!
        assertEquals(2 * sin(3 * 0.7 + 0.5), bound.evaluate(0.7), 1e-12)
        // Rebinding shares the tree and changes only the values.
        assertEquals(sin(PI / 2), parsed.with(listOf(1.0, 1.0, 0.0))!!.evaluate(PI / 2), 1e-12)
    }

    @Test
    fun tooFewValuesIsNotDrawable() {
        assertNull(GraphExpression.parse(wave)!!.with(listOf(1.0, 1.0)))
    }

    @Test
    fun aParameterIsNotAPolynomialCoefficient() {
        // The engine cannot find a root of ax² − 4 among exact numbers, so its zeros shift instead.
        assertNull(GraphExpression.parse("((\$0*(x^2))+(-4))")!!.with(listOf(1.0))!!.polynomial())
    }

    @Test
    fun refusesMalformedParameters() {
        listOf("$", "\$x", "\$-1", "\$123", "\$ 0", "x\$0").forEach { assertNull(it, GraphExpression.parse(it)) }
    }

    @Test
    fun aGraphsFunctionCarriesItsSliderValues() {
        val graph = Outline.Graph(
            id = "g",
            expression = wave,
            parameters = listOf(parameter("A", 2.0), parameter("omega", 1.0), parameter("phi", 0.0)),
        )
        assertEquals(2.0, graph.function()!!.evaluate(PI / 2), 1e-12)
        assertEquals(-2.0, graph.withParameterValue(0, -2.0).function()!!.evaluate(PI / 2), 1e-12)
    }

    @Test
    fun valuesSnapOntoTheirStepsExactly() {
        val slider = GraphParameter("a", "a", 1.0)
        assertEquals(2.3, slider.snapped(2.2999999), 0.0)
        assertEquals(-0.1, slider.snapped(-0.09), 0.0)
        assertEquals(10.0, slider.snapped(42.0), 0.0)
        assertEquals(-10.0, slider.snapped(-42.0), 0.0)

        val quarters = GraphParameter("k", "k", 0.0, min = 0.5, max = 3.0, step = 0.25)
        assertEquals(1.25, quarters.snapped(1.3), 0.0)
        assertEquals(0.5, quarters.snapped(0.0), 0.0)
    }

    @Test
    fun anUnchangedValueIsNotAnEdit() {
        val graph = Outline.Graph(id = "g", expression = wave, parameters = listOf(parameter("A", 2.0)))
        assertSame(graph, graph.withParameterValue(0, 2.04))
        assertSame(graph, graph.withParameterValue(7, 1.0))
    }

    @Test
    fun theDialogCannotRenameOrReorderTheLetters() {
        val graph = Outline.Graph(
            id = "g",
            expression = wave,
            parameters = listOf(parameter("A", 1.0), parameter("omega", 1.0)),
        )
        assertSame(graph, graph.withParameters(graph.parameters.reversed()))
        assertSame(graph, graph.withParameters(listOf(parameter("A", 1.0))))
        assertSame(graph, graph.withParameters(graph.parameters.map { it.copy(min = 5.0, max = 5.0) }))

        val widened = graph.withParameters(graph.parameters.map { it.copy(min = 0.0, max = 100.0, step = 1.0, value = 42.4) })
        assertEquals(42.0, widened.parameters[0].value, 0.0)
        assertEquals(100.0, widened.parameters[1].max, 0.0)
    }

    @Test
    fun valuesReadToTheirStep() {
        val omega = GraphParameter("omega", "ω", 2.5)
        assertEquals("ω = 2.5", GraphSliders.text(omega))
        assertEquals("ω = −3.0", GraphSliders.text(omega, -3.0))
        assertEquals("ω = 0.0", GraphSliders.text(omega, -0.00001))
        assertEquals("12", GraphSliders.number(12.0, 1.0))
        assertEquals("0.25", GraphSliders.number(0.25, 0.05))
    }

    @Test
    fun playTurnsBackAtEitherEnd() {
        val slider = GraphParameter("a", "a", 0.0)
        // A quarter of the sweep time crosses a quarter of the range: 5 of −10…10.
        val (forward, heading) = GraphSliders.sweep(slider, 0.0, 1, GraphSliders.SWEEP_MILLIS / 4)
        assertEquals(5.0, forward, 1e-9)
        assertEquals(1, heading)

        val (back, turned) = GraphSliders.sweep(slider, 9.0, 1, GraphSliders.SWEEP_MILLIS / 10)
        assertEquals(9.0, back, 1e-9) // 9 + 2 = 11, folded back to 9
        assertEquals(-1, turned)

        val (low, up) = GraphSliders.sweep(slider, -9.0, -1, GraphSliders.SWEEP_MILLIS / 10)
        assertEquals(-9.0, low, 1e-9)
        assertEquals(1, up)
    }

    @Test
    fun slidersRoundTripAndStayOffDiskWhenAbsent() {
        val plain = Outline.Graph(id = "p", expression = "(x^2)")
        assertTrue(!PageDoc(outlines = listOf(plain)).encode().contains("parameters"))

        val omega = GraphParameter("omega", "ω", 2.5, min = 0.0, max = 20.0, step = 0.5)
        val graph = Outline.Graph(id = "g", expression = wave, parameters = listOf(omega))
        val doc = PageDoc(outlines = listOf(graph))
        assertEquals(doc, decodePageDoc(doc.encode()))
    }
}
