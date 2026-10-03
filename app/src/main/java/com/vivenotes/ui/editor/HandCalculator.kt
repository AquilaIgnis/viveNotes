package com.vivenotes.ui.editor

import com.vivenotes.math.MathEngine
import com.vivenotes.math.MathOperationResult
import com.vivenotes.ui.AUTOMATIC_MATH_ACTIONS

/** What the calculator shows after =: the formula it read, and either an answer or why there is none. */
internal data class CalculatorAnswer(
    /** The LaTeX handed to SymPy — what was recognised, less any = written after it. */
    val latex: String,
    val result: MathOperationResult? = null,
    val error: String? = null,
)

/**
 * Runs the one action the recognition panel would have run by itself on the same formula.
 *
 * The panel's choice rather than a calculator-specific one — solve an equation, evaluate an integral
 * or a sum, simplify an expression — so the pad and a lasso never give two answers to one formula.
 * A formula with none of those (a matrix) gets no arbitrary default here either; the panel, where
 * every action is a button, is one tap away.
 */
internal suspend fun calculate(recognized: String, engine: MathEngine): CalculatorAnswer {
    val latex = withoutTrailingEquals(recognized)
    val analysis = engine.analyze(latex)
    analysis.error?.let { return CalculatorAnswer(latex, error = it) }
    val action = AUTOMATIC_MATH_ACTIONS.firstOrNull { id -> analysis.actions.any { it.id == id } }
        ?: return CalculatorAnswer(latex, error = NOTHING_TO_RUN)
    val result = engine.execute(latex, action)
    return CalculatorAnswer(latex, result = result.takeIf { it.error == null }, error = result.error)
}

/**
 * Drops an = at the very end.
 *
 * `12 × 4 =` is how a sum is written down by hand when the answer is wanted, and SymPy's strict
 * parser rejects the dangling relation outright. Only a trailing one: an = with a right-hand side is
 * an equation, and solving it is the point.
 */
internal fun withoutTrailingEquals(latex: String): String = latex.trimEnd().removeSuffix("=").trimEnd()

internal const val NOTHING_TO_RUN = "Nothing to calculate automatically. Open it in the panel for every action."
