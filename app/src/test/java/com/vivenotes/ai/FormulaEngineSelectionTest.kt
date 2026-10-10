package com.vivenotes.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which formula model the Math button runs, and when one is fetched unasked.
 *
 * The rules that decide what a person upgrading, deleting or downloading ends up with. Each case is
 * one of those people.
 */
class FormulaEngineSelectionTest {
    private val uniMerNet = FormulaEngine.UniMerNetTiny
    private val formulaNet = FormulaEngine.FormulaNetS

    @Test
    fun aFreshInstallStartsOnTheDefaultAndFetchesIt() {
        assertEquals(uniMerNet, formulaEngineInUse(chosen = null, installed = emptySet()))
        assertEquals(uniMerNet, formulaEngineToFetch(installed = emptySet(), deleted = emptySet()))
    }

    /**
     * Someone who had FormulaNet-S before UniMERNet-T existed reads with it only until the default
     * arrives, then moves to it. FormulaNet-S stays installed, as the optional model.
     */
    @Test
    fun anUpgradeWithFormulaNetFetchesTheDefaultAndMovesToIt() {
        assertEquals(formulaNet, formulaEngineInUse(chosen = null, installed = setOf(formulaNet)))
        assertEquals(uniMerNet, formulaEngineToFetch(installed = setOf(formulaNet), deleted = emptySet()))
        assertEquals(uniMerNet, formulaEngineInUse(chosen = null, installed = setOf(uniMerNet, formulaNet)))
    }

    @Test
    fun anInstalledDefaultIsNotFetchedAgain() {
        assertNull(formulaEngineToFetch(installed = setOf(uniMerNet), deleted = emptySet()))
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
        assertNull(formulaEngineToFetch(installed = emptySet(), deleted = setOf(uniMerNet)))
        assertNull(formulaEngineToFetch(installed = setOf(formulaNet), deleted = setOf(uniMerNet)))
    }

    /** FormulaNet-S is the optional model, so deleting it says nothing about the default. */
    @Test
    fun deletingFormulaNetDoesNotStopTheDefault() {
        assertEquals(uniMerNet, formulaEngineToFetch(installed = emptySet(), deleted = setOf(formulaNet)))
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
