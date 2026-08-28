package com.vivenotes.math

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SympyMathEngineTest {
    private lateinit var engine: SympyMathEngine

    @Before
    fun createEngine() {
        engine = SympyMathEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun equationOffersOnlyRelevantActionsAndSolvesExactly() = runBlocking {
        val analysis = engine.analyze("x^2-4=0")

        assertNull(analysis.error)
        assertEquals("Equation", analysis.summary)
        assertEquals(listOf("x"), analysis.variables)
        assertTrue(analysis.actions.any { it.id == "solve" })
        assertTrue(analysis.actions.any { it.id == "graph" })
        assertTrue(analysis.actions.none { it.id == "integrate" })

        val result = engine.execute("x^2-4=0", "solve")
        assertNull(result.error)
        assertTrue(result.latex.orEmpty().contains("-2"))
        assertTrue(result.latex.orEmpty().contains("2"))
    }

    @Test
    fun expressionCanIntegrateAndProduceNativeGraphSamples() = runBlocking {
        val analysis = engine.analyze("\\sin(x)")
        assertTrue(analysis.actions.any { it.id == "integrate" })
        assertTrue(analysis.actions.any { it.id == "differentiate" })

        val antiderivative = engine.execute("x^2", "integrate")
        assertNull(antiderivative.error)
        assertTrue(antiderivative.latex.orEmpty().contains("x^{3}"))
        assertTrue(antiderivative.latex.orEmpty().endsWith("+ C"))

        val graphResult = engine.execute("\\sin(x)", "graph")
        val graph = graphResult.graph
        assertNotNull(graph)
        assertEquals(321, graph!!.xValues.size)
        assertEquals(graph.xValues.size, graph.yValues.size)
        assertEquals("x", graph.xLabel)
        assertEquals("y", graph.yLabel)
        assertTrue(graph.yValues.any { it != null })
    }

    @Test
    fun formulaNetMatrixEnvironmentHasBoundedMatrixActions() = runBlocking {
        val latex = "\\begin{bmatrix}1 & 2 \\\\ 3 & 4\\end{bmatrix}"
        val analysis = engine.analyze(latex)

        assertNull(analysis.error)
        assertEquals("Matrix · 2 × 2", analysis.summary)
        assertTrue(analysis.actions.any { it.id == "matrix_rref" })
        assertTrue(analysis.actions.any { it.id == "matrix_normalize" })
        assertTrue(analysis.actions.any { it.id == "matrix_inverse" })

        val rref = engine.execute(latex, "matrix_rref")
        assertNull(rref.error)
        assertTrue(rref.latex.orEmpty().contains("1 & 0"))

        val normalized = engine.execute(latex, "matrix_normalize")
        assertNull(normalized.error)
        assertEquals("Normalized by the Frobenius norm", normalized.title)
    }

    @Test
    fun formulaNetLimitPlacementDirectivesDoNotBlockDefiniteIntegrals() = runBlocking {
        listOf("\\limits", "\\nolimits").forEach { directive ->
            val latex = "\\int $directive _ { 0 } ^ { 1 } x ^ { 2 } \\, d x"
            val analysis = engine.analyze(latex)

            assertNull("$directive prevented parsing: ${analysis.error}", analysis.error)
            assertEquals("Integral", analysis.summary)
            assertTrue(analysis.actions.any { it.id == "evaluate" })

            val result = engine.execute(latex, "evaluate")
            assertNull("$directive prevented evaluation: ${result.error}", result.error)
            assertTrue(result.latex.orEmpty().contains("\\frac{1}{3}"))
        }
    }

    @Test
    fun greekFunctionNamesEvaluateOnlyAtTheArityWhichMakesThemFunctions() = runBlocking {
        // parse_latex leaves every name outside its grammar as an undefined function, so before
        // greeks.py these evaluated to themselves and the Simplify button silently did nothing.
        assertEquals("24", engine.execute("\\Gamma(5)", "simplify").latex)
        assertEquals("\\sqrt{\\pi}", engine.execute("\\Gamma(\\frac{1}{2})", "simplify").latex)
        assertEquals("\\frac{1}{12}", engine.execute("\\beta(2,3)", "simplify").latex)

        // A lone Greek letter, and one applied at an arity where it is not that function, stay the
        // coefficient or unknown function the writer meant.
        assertEquals("\\beta", engine.execute("\\beta", "simplify").latex)
        assertTrue(engine.execute("\\beta(t)", "simplify").latex.orEmpty().contains("\\beta"))
    }

    @Test
    fun malformedLatexAndUnlistedOperationsFailClosed() = runBlocking {
        assertNotNull(engine.analyze("x -").error)
        assertNotNull(engine.execute("x^2", "matrix_inverse").error)
    }
}
