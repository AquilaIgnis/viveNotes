package com.vivenotes.ai

/**
 * Which graph turns a formula bitmap into LaTeX. Listed best first, which is the order the AI pane
 * shows them in and the order [startingFormulaEngine] falls back through.
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

/** The model a fresh install uses, and the one its first run fetches unasked. */
internal val DEFAULT_FORMULA_ENGINE = FormulaEngine.UniMerNetTiny

/**
 * The model the Math button starts on.
 *
 * That is the stored choice if that model is still installed. Otherwise it is the best installed
 * model, and otherwise the default, which the first run then fetches. Someone upgrading with
 * FormulaNet-S installed and no stored choice keeps reading with it.
 */
internal fun startingFormulaEngine(
    stored: FormulaEngine?,
    installed: Set<FormulaEngine>,
): FormulaEngine =
    stored?.takeIf { it in installed }
        ?: FormulaEngine.entries.firstOrNull { it in installed }
        ?: DEFAULT_FORMULA_ENGINE

/**
 * What a first run fetches without being asked: the default, and only while no formula model is
 * installed at all and the user has never deleted one.
 *
 * Installed-at-all because someone already reading formulas with FormulaNet-S has a working Math
 * button, and 113 MB they did not ask for is a cost. Never-deleted because a delete is the user
 * saying no. Fetching the model again on the next Wi-Fi launch would undo it.
 */
internal fun formulaEngineToFetch(
    installed: Set<FormulaEngine>,
    userDeletedOne: Boolean,
): FormulaEngine? = DEFAULT_FORMULA_ENGINE.takeIf { installed.isEmpty() && !userDeletedOne }

/** A model that finishes installing takes over only if the selected one is not installed. */
internal fun formulaEngineAfterInstall(
    selected: FormulaEngine,
    justInstalled: FormulaEngine,
    installed: Set<FormulaEngine>,
): FormulaEngine = if (selected in installed) selected else justInstalled

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
