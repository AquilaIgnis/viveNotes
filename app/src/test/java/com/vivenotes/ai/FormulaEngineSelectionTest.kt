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
        assertEquals(uniMerNet, startingFormulaEngine(stored = null, installed = emptySet()))
        assertEquals(uniMerNet, formulaEngineToFetch(installed = emptySet(), userDeletedOne = false))
    }

    /** Someone who had FormulaNet-S before UniMERNet-T existed keeps reading with it. */
    @Test
    fun anUpgradeWithFormulaNetKeepsItAndFetchesNothing() {
        assertEquals(formulaNet, startingFormulaEngine(stored = null, installed = setOf(formulaNet)))
        assertNull(formulaEngineToFetch(installed = setOf(formulaNet), userDeletedOne = false))
    }

    @Test
    fun aStoredChoiceWinsOnlyWhileItsModelIsInstalled() {
        val both = setOf(uniMerNet, formulaNet)
        assertEquals(formulaNet, startingFormulaEngine(stored = formulaNet, installed = both))
        assertEquals(uniMerNet, startingFormulaEngine(stored = formulaNet, installed = setOf(uniMerNet)))
    }

    /** A delete is a no, and the next Wi-Fi launch must not undo it. */
    @Test
    fun nothingIsFetchedAfterTheUserDeletesAModel() {
        assertNull(formulaEngineToFetch(installed = emptySet(), userDeletedOne = true))
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

    @Test
    fun aFinishedDownloadTakesOverOnlyWhenTheSelectionHasNothingBehindIt() {
        assertEquals(
            formulaNet,
            formulaEngineAfterInstall(selected = uniMerNet, justInstalled = formulaNet, installed = setOf(formulaNet)),
        )
        assertEquals(
            uniMerNet,
            formulaEngineAfterInstall(
                selected = uniMerNet,
                justInstalled = formulaNet,
                installed = setOf(uniMerNet, formulaNet),
            ),
        )
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
