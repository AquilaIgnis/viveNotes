package com.vivenotes.ai

/**
 * Which graph turns a formula bitmap into LaTeX. Listed best first, which is the order the AI pane
 * shows them in and the order [formulaEngineInUse] falls back through.
 *
 * `simulations/formula-models` measured both on the Recognition Test page. UniMERNet-T read
 * ordinary handwriting exactly more often, 66 of 88 rasters against 54. It never read the
 * handwritten matrix, which FormulaNet-S read 9 times in 11. Both stay installable, and the user
 * picks.
 *
 * [stemPx] is the stroke width each model was measured best at, stated in the 384-square frame
 * [recognitionStemSize] uses for every width. That frame is only the yardstick — UniMERNet's own
 * input is 192×672 — so the two numbers compare directly. FormulaNet-S wants 10 px; UniMERNet-T was
 * exact on 66/88 at 3–4 px, 65 at 6 and 55 at 10, so drawing for one model starves the other.
 */
enum class FormulaEngine(val stemPx: Float) {
    UniMerNetTiny(UNIMERNET_STEM_PX),
    FormulaNetS(RECOGNITION_STEM_PX),
}

/** UniMERNet-T's measured best stem; see [FormulaEngine]. */
internal const val UNIMERNET_STEM_PX = 4f

/** The model everyone ends up on unless they pick another, and the one a launch fetches unasked. */
internal val DEFAULT_FORMULA_ENGINE = FormulaEngine.UniMerNetTiny

/**
 * The model the Math button runs, at launch and again after every install.
 *
 * That is the user's own choice while its model is installed. A choice is made only by tapping Use,
 * never by a default. Otherwise it is the best installed model, and otherwise the default, which the
 * launch then fetches. So someone upgrading with only FormulaNet-S keeps reading with it until
 * UniMERNet-T arrives, then moves to it. Someone who picked FormulaNet-S stays on it.
 */
internal fun formulaEngineInUse(
    chosen: FormulaEngine?,
    installed: Set<FormulaEngine>,
): FormulaEngine =
    chosen?.takeIf { it in installed }
        ?: FormulaEngine.entries.firstOrNull { it in installed }
        ?: DEFAULT_FORMULA_ENGINE

/**
 * What a launch fetches without being asked: the default, whenever it is not installed and the
 * user has never deleted it.
 *
 * FormulaNet-S being installed does not stop it. FormulaNet-S is the optional model, and someone
 * who had it from before UniMERNet-T existed belongs on the default like everyone else. Only
 * deleting the default itself is a no — deleting FormulaNet-S says nothing about UniMERNet-T — and
 * fetching it again on the next Wi-Fi launch would undo that no.
 */
internal fun formulaEngineToFetch(
    installed: Set<FormulaEngine>,
    deleted: Set<FormulaEngine>,
): FormulaEngine? = DEFAULT_FORMULA_ENGINE.takeIf { it !in installed && it !in deleted }

/**
 * Deleting the model in use hands the Math button to the best one still installed. With none
 * left, the selection stays where it is and the Math button disappears.
 */
internal fun formulaEngineAfterDelete(
    selected: FormulaEngine,
    deleted: FormulaEngine,
    stillInstalled: Set<FormulaEngine>,
): FormulaEngine =
    if (selected != deleted) selected
    else FormulaEngine.entries.firstOrNull { it in stillInstalled } ?: selected
