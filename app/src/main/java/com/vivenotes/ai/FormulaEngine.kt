package com.vivenotes.ai

/**
 * Which graph turns a formula bitmap into LaTeX. Listed best first, which is the order the AI pane
 * shows them in and the order [formulaEngineInUse] falls back through.
 *
 * `simulations/formula-models` measured them on the Recognition Test page. UniMERNet-S read 77 of
 * 88 rasters exactly, UniMERNet-T 66 and FormulaNet-S 54. Neither UniMERNet reads the handwritten
 * matrix, which FormulaNet-S read 9 times in 11. All three stay installable, and the user picks.
 *
 * UniMERNet-S costs about 1.7× UniMERNet-T's time per formula, so what its card offers depends on
 * the tablet's chip — [SmallModelFit].
 *
 * [stemPx] is the stroke width each model was measured best at, stated in the 384-square frame
 * [recognitionStemSize] uses for every width. That frame is only the yardstick — UniMERNet's own
 * input is 192×672 — so the two numbers compare directly. FormulaNet-S wants 10 px; UniMERNet-T was
 * exact on 66/88 at 3–4 px, 65 at 6 and 55 at 10, so drawing for one model starves the other.
 * UniMERNet-S is exact on 77/88 at both 3 and 4 px and 75 at 6, so it shares UniMERNet-T's width.
 */
enum class FormulaEngine(val stemPx: Float) {
    UniMerNetSmall(UNIMERNET_STEM_PX),
    UniMerNetTiny(UNIMERNET_STEM_PX),
    FormulaNetS(RECOGNITION_STEM_PX),
}

/** UniMERNet's measured best stem, T and S alike; see [FormulaEngine]. */
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
 * What a launch fetches without being asked: the default, and UniMERNet-S on a chip listed as running
 * it smoothly — each only when it is not installed and the user has never deleted it.
 *
 * FormulaNet-S being installed does not stop either. FormulaNet-S is the optional model, and someone
 * who had it from before UniMERNet-T existed belongs on the default like everyone else. Only
 * deleting a model itself is a no — deleting FormulaNet-S says nothing about UniMERNet — and
 * fetching it again on the next Wi-Fi launch would undo that no.
 *
 * UniMERNet-T stays when UniMERNet-S arrives, as the fallback if the user deletes the larger model.
 */
internal fun formulaEnginesToFetch(
    installed: Set<FormulaEngine>,
    deleted: Set<FormulaEngine>,
    smallModelFit: SmallModelFit?,
): List<FormulaEngine> =
    listOfNotNull(
        DEFAULT_FORMULA_ENGINE,
        FormulaEngine.UniMerNetSmall.takeIf { smallModelFit == SmallModelFit.Smooth },
    ).filter { it !in installed && it !in deleted }

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
