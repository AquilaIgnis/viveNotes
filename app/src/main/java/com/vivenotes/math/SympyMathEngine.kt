package com.vivenotes.math

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vivenotes.model.GraphParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MathAction(
    val id: String,
    val label: String,
)

@Serializable
data class MathAnalysis(
    val normalizedLatex: String = "",
    val summary: String = "",
    val variables: List<String> = emptyList(),
    val actions: List<MathAction> = emptyList(),
    val error: String? = null,
)

@Serializable
data class MathGraph(
    val xLabel: String,
    val yLabel: String,
    val xValues: List<Double>,
    val yValues: List<Double?>,
    /**
     * The function in the graph grammar `math_engine.py` and `graph/GraphExpression.kt` share — what
     * a graph placed on the page stores and draws. Null when the function uses something outside
     * that grammar: it still previews in the pane, but cannot be placed.
     */
    val expression: String? = null,
    /** The same function as LaTeX, written in [xLabel], for the placed graph's header. */
    val functionLatex: String? = null,
    /**
     * The formula's other letters, each a slider once placed, at the values the preview was drawn
     * with: the `$0`, `$1`… of [expression], in order.
     */
    val parameters: List<GraphParameter> = emptyList(),
)

/** A placed graph's function after a dragged point was let go: `mutate_graph`'s answer. */
@Serializable
data class GraphRewrite(
    val expression: String? = null,
    val latex: String? = null,
    val error: String? = null,
)

@Serializable
data class MathOperationResult(
    val title: String = "Result",
    val latex: String? = null,
    val message: String? = null,
    val graph: MathGraph? = null,
    val error: String? = null,
    /** [latex] with every number evaluated to 12 significant digits; null where that reads the same. */
    val decimal: String? = null,
)

data class FormulaToolsState(
    val sourceLatex: String = "",
    val analyzing: Boolean = false,
    val analysis: MathAnalysis? = null,
    val executingActionId: String? = null,
    val result: MathOperationResult? = null,
    val error: String? = null,
)

interface MathEngine {
    suspend fun analyze(latex: String): MathAnalysis
    suspend fun execute(latex: String, actionId: String): MathOperationResult

    /**
     * Runs the first of [preferred] that applies to [latex], or returns null when none does.
     *
     * [analyze] then [execute], which is all this does unless an engine can do both in one pass.
     */
    suspend fun calculate(latex: String, preferred: List<String>): MathOperationResult? {
        val analysis = analyze(latex)
        analysis.error?.let { return MathOperationResult(error = it) }
        val action = preferred.firstOrNull { id -> analysis.actions.any { it.id == id } } ?: return null
        return execute(latex, action)
    }

    /**
     * Rewrites a placed graph's function exactly after one of its points was dragged.
     *
     * [mutation] is the JSON `GraphDrag.request` writes. The canvas already drew the new curve
     * numerically while the finger moved; this is what makes the header read `x^2 - 3x + 1.5` rather
     * than the decimals the drag produced. [parameters] are the graph's slider letters in order —
     * SymPy's names, what `$0`, `$1`… are printed as.
     */
    suspend fun mutateGraph(
        expression: String,
        variable: String,
        mutation: String,
        parameters: List<String> = emptyList(),
    ): GraphRewrite = GraphRewrite(error = "This engine cannot rewrite graphs.")

    /**
     * Pays the engine's first-use costs ahead of the first formula. Best effort: an engine that
     * fails here fails again on the formula, where the error is shown.
     */
    suspend fun warmUp() {}
}

/** Lazy, serialized bridge to the allow-listed JSON API in `math_engine.py`. */
class SympyMathEngine(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MathEngine {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun analyze(latex: String): MathAnalysis = withContext(dispatcher) {
        val payload = module().callAttr("analyze", latex).toString()
        json.decodeFromString<MathAnalysis>(payload)
    }

    override suspend fun execute(latex: String, actionId: String): MathOperationResult =
        withContext(dispatcher) {
            val payload = module().callAttr("execute", latex, actionId).toString()
            json.decodeFromString<MathOperationResult>(payload)
        }

    /** One Python call, so the formula is parsed and classified once rather than once per step. */
    override suspend fun calculate(latex: String, preferred: List<String>): MathOperationResult? =
        withContext(dispatcher) {
            val payload = module().callAttr("calculate", latex, *preferred.toTypedArray()).toString()
            json.decodeFromString<MathOperationResult?>(payload)
        }

    override suspend fun mutateGraph(
        expression: String,
        variable: String,
        mutation: String,
        parameters: List<String>,
    ): GraphRewrite =
        withContext(dispatcher) {
            val names = json.encodeToString(parameters)
            val payload = module().callAttr("mutate_graph", expression, variable, mutation, names).toString()
            json.decodeFromString<GraphRewrite>(payload)
        }

    /** Starts Python, imports SymPy and parses one throwaway formula — `warm_up` in the module. */
    override suspend fun warmUp() {
        withContext(dispatcher) {
            try {
                module().callAttr("warm_up")
            } catch (e: Exception) {
                Log.w(TAG, "SymPy warm-up failed", e)
            }
        }
    }

    private fun module() = python().getModule(PYTHON_MODULE)

    private fun python(): Python {
        if (!Python.isStarted()) {
            synchronized(Python::class.java) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            }
        }
        return Python.getInstance()
    }

    private companion object {
        const val PYTHON_MODULE = "math_engine"
        const val TAG = "SympyMathEngine"
    }
}
