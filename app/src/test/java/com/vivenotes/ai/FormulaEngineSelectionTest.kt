package com.vivenotes.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which formula model the Math button runs, and when one is fetched unasked.
 *
 * The rules that decide what a person upgrading, deleting or downloading ends up with. Each case is
 * one of those people.
 */
class FormulaEngineSelectionTest {
    private val uniMerNet = FormulaEngine.UniMerNetTiny
    private val small = FormulaEngine.UniMerNetSmall
    private val formulaNet = FormulaEngine.FormulaNetS

    private fun toFetch(
        installed: Set<FormulaEngine>,
        deleted: Set<FormulaEngine> = emptySet(),
        fit: SmallModelFit? = null,
    ): List<FormulaEngine> = formulaEnginesToFetch(installed, deleted, fit)

    @Test
    fun aFreshInstallStartsOnTheDefaultAndFetchesIt() {
        assertEquals(uniMerNet, formulaEngineInUse(chosen = null, installed = emptySet()))
        assertEquals(listOf(uniMerNet), toFetch(installed = emptySet()))
    }

    /**
     * Someone who had FormulaNet-S before UniMERNet-T existed reads with it only until the default
     * arrives, then moves to it. FormulaNet-S stays installed, as the optional model.
     */
    @Test
    fun anUpgradeWithFormulaNetFetchesTheDefaultAndMovesToIt() {
        assertEquals(formulaNet, formulaEngineInUse(chosen = null, installed = setOf(formulaNet)))
        assertEquals(listOf(uniMerNet), toFetch(installed = setOf(formulaNet)))
        assertEquals(uniMerNet, formulaEngineInUse(chosen = null, installed = setOf(uniMerNet, formulaNet)))
    }

    @Test
    fun anInstalledDefaultIsNotFetchedAgain() {
        assertEquals(emptyList<FormulaEngine>(), toFetch(installed = setOf(uniMerNet)))
    }

    @Test
    fun aChoiceWinsOnlyWhileItsModelIsInstalled() {
        val both = setOf(uniMerNet, formulaNet)
        assertEquals(formulaNet, formulaEngineInUse(chosen = formulaNet, installed = both))
        assertEquals(uniMerNet, formulaEngineInUse(chosen = formulaNet, installed = setOf(uniMerNet)))
    }

    /** Deleting the default is a no, and the next Wi-Fi launch must not undo it. */
    @Test
    fun theDefaultIsNotFetchedAfterTheUserDeletesIt() {
        assertEquals(emptyList<FormulaEngine>(), toFetch(installed = emptySet(), deleted = setOf(uniMerNet)))
        assertEquals(emptyList<FormulaEngine>(), toFetch(installed = setOf(formulaNet), deleted = setOf(uniMerNet)))
    }

    /** FormulaNet-S is the optional model, so deleting it says nothing about the default. */
    @Test
    fun deletingFormulaNetDoesNotStopTheDefault() {
        assertEquals(listOf(uniMerNet), toFetch(installed = emptySet(), deleted = setOf(formulaNet)))
    }

    /** Only a tablet measured to run UniMERNet-S smoothly fetches it unasked. */
    @Test
    fun onlyASmoothTabletFetchesTheSmallModel() {
        assertEquals(listOf(small), toFetch(installed = setOf(uniMerNet), fit = SmallModelFit.Smooth))
        SmallModelFit.entries.filter { it != SmallModelFit.Smooth }.forEach { fit ->
            assertEquals(emptyList<FormulaEngine>(), toFetch(installed = setOf(uniMerNet), fit = fit))
        }
        assertEquals(emptyList<FormulaEngine>(), toFetch(installed = setOf(uniMerNet), fit = null))
    }

    /** Deleting UniMERNet-S is a no too, and installing it leaves UniMERNet-T where it is. */
    @Test
    fun theSmallModelIsNotFetchedAgainOnceDeletedOrInstalled() {
        assertEquals(
            emptyList<FormulaEngine>(),
            toFetch(installed = setOf(uniMerNet), deleted = setOf(small), fit = SmallModelFit.Smooth),
        )
        assertEquals(
            emptyList<FormulaEngine>(),
            toFetch(installed = setOf(uniMerNet, small), fit = SmallModelFit.Smooth),
        )
    }

    /**
     * UniMERNet-S is listed first, so once installed it takes over the Math button whichever way
     * it arrived, unless the user picked another model with Use.
     */
    @Test
    fun anInstalledSmallModelTakesOverUnlessTheUserPickedAnother() {
        assertEquals(small, formulaEngineInUse(chosen = null, installed = setOf(uniMerNet, small)))
        assertEquals(uniMerNet, formulaEngineInUse(chosen = uniMerNet, installed = setOf(uniMerNet, small)))
        assertEquals(
            uniMerNet,
            formulaEngineAfterDelete(selected = small, deleted = small, stillInstalled = setOf(uniMerNet, formulaNet)),
        )
    }

    @Test
    fun deletingTheModelInUseHandsMathToTheOtherOne() {
        assertEquals(
            formulaNet,
            formulaEngineAfterDelete(selected = uniMerNet, deleted = uniMerNet, stillInstalled = setOf(formulaNet)),
        )
        assertEquals(
            uniMerNet,
            formulaEngineAfterDelete(selected = uniMerNet, deleted = formulaNet, stillInstalled = setOf(uniMerNet)),
        )
        // With nothing left, the selection stays and the Math button goes, because nothing is ready.
        assertEquals(
            uniMerNet,
            formulaEngineAfterDelete(selected = uniMerNet, deleted = uniMerNet, stillInstalled = emptySet()),
        )
    }

    /** A model that finishes installing is in use unless the user picked the other one. */
    @Test
    fun aFinishedDownloadTakesOverUnlessTheUserPickedTheOther() {
        assertEquals(formulaNet, formulaEngineInUse(chosen = null, installed = setOf(formulaNet)))
        assertEquals(formulaNet, formulaEngineInUse(chosen = formulaNet, installed = setOf(uniMerNet, formulaNet)))
        assertEquals(uniMerNet, formulaEngineInUse(chosen = uniMerNet, installed = setOf(uniMerNet, formulaNet)))
    }

    @Test
    fun theMathButtonFollowsTheSelectedModelsInstall() {
        val state = AiModelsState(
            formulaLatex = AiModelInstallState.Installed,
            uniMerNet = AiModelInstallState.NotInstalled,
            formulaEngine = uniMerNet,
        )
        assertEquals(false, state.formulaReady)
        assertEquals(true, state.copy(formulaEngine = formulaNet).formulaReady)
        assertEquals(setOf(formulaNet), state.installedFormulaEngines)
    }
}
