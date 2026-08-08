package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelsState
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AiModelsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setPanel(state: AiModelsState, onDownload: () -> Unit = {}) {
        compose.setContent {
            ViveNotesTheme {
                Column {
                    AiModelsPanelContent(state = state, onDownloadFormula = onDownload)
                }
            }
        }
    }

    @Test
    fun bundledOcrIsShownAsInstalledAndFormulaCanDownload() {
        var download = false
        setPanel(
            state = AiModelsState(
                handwritingText = AiModelInstallState.Installed,
                formulaLatex = AiModelInstallState.NotInstalled,
            ),
            onDownload = { download = true },
        )

        compose.onNodeWithTag(AiPanelTags.TEXT_MODEL).assertIsDisplayed()
        compose.onNodeWithText("Download").performClick()

        assertTrue(download)
    }

    @Test
    fun downloadProgressUsesTheWholeFormulaPackage() {
        setPanel(
            AiModelsState(
                handwritingText = AiModelInstallState.Installed,
                formulaLatex = AiModelInstallState.Downloading(50, 100),
            ),
        )

        compose.onNodeWithText("Downloading 50%").assertIsDisplayed()
    }

    @Test
    fun failedDownloadOffersRetry() {
        var retried = false
        setPanel(
            state = AiModelsState(
                handwritingText = AiModelInstallState.Installed,
                formulaLatex = AiModelInstallState.Failed("Network unavailable"),
            ),
            onDownload = { retried = true },
        )

        compose.onNodeWithText("Network unavailable").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        assertTrue(retried)
    }
}
