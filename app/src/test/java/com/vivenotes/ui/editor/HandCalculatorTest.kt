package com.vivenotes.ui.editor

import com.vivenotes.math.MathAction
import com.vivenotes.math.MathAnalysis
import com.vivenotes.math.MathEngine
import com.vivenotes.math.MathOperationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The calculator's = once the handwriting is LaTeX: which action it runs, and what it hands SymPy.
 *
 * The engine is faked — `SympyMathEngineTest` covers SymPy itself. What can go wrong here is the
 * choice, and a trailing = reaching a parser that rejects it.
 */
class HandCalculatorTest {

    private class FakeEngine(private val actions: List<String>, private val error: String? = null) : MathEngine {
        val analyzed = mutableListOf<String>()
        val executed = mutableListOf<Pair<String, String>>()

        override suspend fun analyze(latex: String): MathAnalysis {
            analyzed += latex
            return MathAnalysis(actions = actions.map { MathAction(it, it) }, error = error)
        }

        override suspend fun execute(latex: String, actionId: String): MathOperationResult {
            executed += latex to actionId
            return MathOperationResult(title = actionId, latex = "answer")
        }
    }

    @Test
    fun `an equation is solved rather than simplified`() = runTest {
        val engine = FakeEngine(listOf("solve", "simplify", "factor"))

        val answer = calculate("x ^ 2 - 4 = 0", engine)

        assertEquals(listOf("x ^ 2 - 4 = 0" to "solve"), engine.executed)
        assertEquals("answer", answer.result?.latex)
        assertNull(answer.error)
    }

    @Test
    fun `an integral is evaluated`() = runTest {
        val engine = FakeEngine(listOf("evaluate"))

        calculate("\\int _ 0 ^ 1 x ^ 2 d x", engine)

        assertEquals("evaluate", engine.executed.single().second)
    }

    @Test
    fun `arithmetic is simplified`() = runTest {
        val engine = FakeEngine(listOf("simplify", "expand", "decimal"))

        calculate("1 2 \\times 4", engine)

        assertEquals("simplify", engine.executed.single().second)
    }

    /** `12 × 4 =` is how a sum asking for its answer is written, and strict parsing rejects it. */
    @Test
    fun `a trailing equals sign never reaches the engine`() = runTest {
        val engine = FakeEngine(listOf("simplify"))

        val answer = calculate("1 2 \\times 4 = ", engine)

        assertEquals(listOf("1 2 \\times 4"), engine.analyzed)
        assertEquals("1 2 \\times 4", answer.latex)
    }

    @Test
    fun `an equals sign with a right-hand side is kept`() {
        assertEquals("x + 1 = 3", withoutTrailingEquals("x + 1 = 3"))
    }

    /** A matrix offers only its own actions, and the panel picks none of them by itself either. */
    @Test
    fun `nothing is run when no automatic action applies`() = runTest {
        val engine = FakeEngine(listOf("matrix_transpose", "matrix_rank"))

        val answer = calculate("\\begin{bmatrix} 1 & 7 \\\\ 3 & 6 \\end{bmatrix}", engine)

        assertEquals(emptyList<Pair<String, String>>(), engine.executed)
        assertEquals(NOTHING_TO_RUN, answer.error)
    }

    @Test
    fun `a formula SymPy cannot read reports why and runs nothing`() = runTest {
        val engine = FakeEngine(emptyList(), error = "SymPy could not interpret this LaTeX.")

        val answer = calculate("x +", engine)

        assertEquals("SymPy could not interpret this LaTeX.", answer.error)
        assertEquals(emptyList<Pair<String, String>>(), engine.executed)
    }
}
