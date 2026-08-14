package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelsState
import com.vivenotes.data.ImageTextProgress
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AiModelsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    // Held in state and driven, because a Compose test may set its content only once. A second
    // `setContent` throws, so a test that wants to see two states has to move between them.
    private val modelsState = mutableStateOf(AiModelsState())
    private val pictureTextState = mutableStateOf(ImageTextProgress(enabled = true))
    private val picturesReadState = mutableStateOf(0)

    private fun setPanel(
        state: AiModelsState,
        onDownload: () -> Unit = {},
        pictureText: ImageTextProgress = ImageTextProgress(enabled = true),
        picturesRead: Int = 0,
        onSetPictureText: (Boolean) -> Unit = {},
        onRebuild: () -> Unit = {},
    ) {
        modelsState.value = state
        pictureTextState.value = pictureText
        picturesReadState.value = picturesRead
        compose.setContent {
            ViveNotesTheme {
                Column {
                    AiModelsPanelContent(
                        state = modelsState.value,
                        onDownloadFormula = onDownload,
                        pictureText = pictureTextState.value,
                        picturesRead = picturesReadState.value,
                        onSetPictureText = onSetPictureText,
                        onRebuildPictureText = onRebuild,
                    )
                }
            }
        }
    }

    private fun showPictureText(progress: ImageTextProgress, read: Int = 0) {
        compose.runOnUiThread {
            pictureTextState.value = progress
            picturesReadState.value = read
        }
    }

    private val installed = AiModelsState(
        handwritingText = AiModelInstallState.Installed,
        formulaLatex = AiModelInstallState.Installed,
    )

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

    // --- text in pictures — `memory/imageOcrPlan.md` IO9 ----------------------------------------

    @Test
    fun theSwitchReportsBeingTurnedOff() {
        var enabled: Boolean? = null
        setPanel(installed, onSetPictureText = { enabled = it })

        compose.onNodeWithTag(AiPanelTags.PICTURE_TEXT_SWITCH).performClick()

        assertEquals(false, enabled)
    }

    @Test
    fun theCardCountsWhatHasBeenReadAndSaysWhenItIsOff() {
        setPanel(installed, picturesRead = 4)
        compose.onNodeWithText("4 pictures read").assertIsDisplayed()

        showPictureText(ImageTextProgress(enabled = false))

        compose.onNodeWithText("Off. Pictures are not read and nothing is stored.").assertIsDisplayed()
    }

    @Test
    fun rebuildingIsOfferedOnlyWhenThereIsNothingInFlight() {
        var rebuilt = false
        setPanel(
            installed,
            pictureText = ImageTextProgress(enabled = true, running = true, pending = 3),
            onRebuild = { rebuilt = true },
        )
        compose.onNodeWithText("Reading… 3 to go").assertIsDisplayed()
        compose.onNodeWithTag(AiPanelTags.PICTURE_TEXT_REBUILD).assertIsNotEnabled()

        showPictureText(ImageTextProgress(enabled = true))
        compose.onNodeWithTag(AiPanelTags.PICTURE_TEXT_REBUILD).performClick()

        assertTrue(rebuilt)
    }

    /**
     * The switch is drawn smaller, and its target shrinks with it — which is the cost, recorded.
     *
     * `Modifier.scale` is a `graphicsLayer`, so it does not change measurement — but Compose applies
     * the layer transform when hit-testing, so the touchable region scales too. At 0.9 the 52 × 32 dp
     * track reports 46.8 × 28.8. This asserts the floor rather than the exact number: shrinking the
     * switch further is a decision to take deliberately, not one to arrive at by nudging a constant.
     */
    @Test
    fun theSwitchStaysAboveTheSizeAFingerCanFind() {
        setPanel(installed)

        val bounds = compose.onNodeWithTag(AiPanelTags.PICTURE_TEXT_SWITCH).getBoundsInRoot()

        assertTrue("switch is only ${bounds.width} wide", bounds.width >= MIN_SWITCH_WIDTH)
        assertTrue("switch is only ${bounds.height} tall", bounds.height >= MIN_SWITCH_HEIGHT)
    }

    private companion object {
        /** Material's 52 dp track at the 0.9 this pane draws it, with a little slack. */
        val MIN_SWITCH_WIDTH = 44.dp

        /** Material's 32 dp track at the same scale. The switch's own hit slop sits outside this. */
        val MIN_SWITCH_HEIGHT = 26.dp
    }
}
