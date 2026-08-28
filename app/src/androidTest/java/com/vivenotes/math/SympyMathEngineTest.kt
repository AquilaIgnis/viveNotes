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
    fun aQuadraticPairOfRootsSolvesToOnePlusMinusRoot() = runBlocking {
        // A rational centre and an irrational spread — the shape the quadratic formula produces, and
        // the only shape that folds. Surd and imaginary spreads fold alike.
        val surds = engine.execute("x^2+10x+5=0", "solve")
        assertNull(surds.error)
        assertEquals("x = -5 \\pm 2 \\sqrt{5}", surds.latex)
        assertTrue(surds.message.orEmpty().contains("Two roots"))

        val imaginary = engine.execute("x^2+2x+5=0", "solve")
        assertEquals("x = -1 \\pm 2 i", imaginary.latex)

        val centredOnZero = engine.execute("x^2-5=0", "solve")
        assertEquals("x = \\pm \\sqrt{5}", centredOnZero.latex)
    }

    @Test
    fun rootsNotWorthAPlusMinusStayAList() = runBlocking {
        // Rational roots: ± would turn 1 and 2 into the arithmetic puzzle 3/2 ± 1/2.
        val rational = engine.execute("x^2-3x+2=0", "solve")
        assertNull(rational.error)
        assertTrue(rational.latex.orEmpty().startsWith("\\left["))
        assertNull(rational.message)

        // An irrational centre: 0 and √2 share no half, and folding would invent a √2/2 that appears
        // in neither root.
        val unshared = engine.execute("x^2-\\sqrt{2}x=0", "solve")
        assertTrue(unshared.latex.orEmpty().startsWith("\\left["))

        // Three roots, one of them a conjugate pair: ± across three would misstate the answer.
        val cubic = engine.execute("x^3+1=0", "solve")
        assertTrue(cubic.latex.orEmpty().startsWith("\\left["))
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
    fun degreeMarksBecomeRadianMultiplesOrAreRefusedOutright() = runBlocking {
        // SymPy trigonometry is radians-only and the mark is outside the LaTeX grammar, so before
        // degrees.py "^\\circ" parsed as exponentiation by a variable named circ, and 30 degrees
        // was evaluated as 30 radians.
        assertEquals("\\frac{1}{2}", engine.execute("\\sin(30^\\circ)", "simplify").latex)
        assertEquals("\\frac{1}{2}", engine.execute("\\sin(30\u00B0)", "simplify").latex)
        assertEquals("1", engine.execute("\\tan(45^\\circ)", "simplify").latex)

        // FormulaNet emits one token at a time, so 36 reaches the engine as "3 6". Attaching the
        // mark to the trailing digit alone answered sin(3 * 6 degrees) = 0.309 for a written 36.
        assertEquals("0.587785252293", engine.execute("\\sin ( 3 6 ^ { \\circ } )", "decimal").latex)

        // A mark the rewrite cannot attach to a whole token fails closed. Rewriting it anyway would
        // drop the factor inside the subscript and answer a different question without saying so.
        assertNotNull(engine.analyze("\\theta_{2}^\\circ").error)
        assertNotNull(engine.analyze("\\theta _ { 2 } ^ { \\circ }").error)
    }

    @Test
    fun malformedLatexAndUnlistedOperationsFailClosed() = runBlocking {
        assertNotNull(engine.analyze("x -").error)
        assertNotNull(engine.execute("x^2", "matrix_inverse").error)
    }
}
