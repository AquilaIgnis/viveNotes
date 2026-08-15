package com.vivenotes.ui.panel

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.vivenotes.ui.copyRecognizedText
import com.vivenotes.math.FormulaToolsState
import com.vivenotes.math.MathAction
import com.vivenotes.math.MathAnalysis
import com.vivenotes.math.MathGraph
import com.vivenotes.math.MathOperationResult
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecognitionPanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun runningRecognitionUsesThePaneInsteadOfADialog() {
        setPanel(
            RecognitionPanelState(
                kind = RecognitionOutputKind.Formula,
                running = true,
            ),
        )

        compose.onNodeWithTag(RecognitionPanelTags.PROGRESS).assertIsDisplayed()
        compose.onNodeWithText("Processing selected ink on this device…").assertIsDisplayed()
    }

    @Test
    fun formulaSourceIsEditableAndItsPreviewIsDirectlyBelowIt() {
        var edited = ""
        setPanel(
            state = RecognitionPanelState(
                kind = RecognitionOutputKind.Formula,
                value = "x^2+y^2=z^2",
            ),
            onValueChange = { edited = it },
        )

        compose.onNodeWithText("LaTeX").assertIsDisplayed()
        compose.onNodeWithTag(RecognitionPanelTags.SOURCE).assertTextContains("x^2+y^2=z^2")
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithTag(RecognitionPanelTags.PREVIEW).assertIsDisplayed()

        compose.onNodeWithTag(RecognitionPanelTags.SOURCE).performTextReplacement("\\frac{1}{2}")
        assertEquals("\\frac{1}{2}", edited)
    }

    @Test
    fun copyPublishesPlainTextToTheAndroidSystemClipboard() {
        lateinit var context: Context
        val latex = "\\int_0^1 x^2\\,dx"
        compose.setContent {
            context = LocalContext.current
            ViveNotesTheme {
                Column {
                    RecognitionPanelContent(
                        state = RecognitionPanelState(
                            kind = RecognitionOutputKind.Formula,
                            value = latex,
                        ),
                        onValueChange = {},
                        onCopy = { copyRecognizedText(context, "Recognized LaTeX", it) },
                    )
                }
            }
        }

        compose.onNodeWithTag(RecognitionPanelTags.COPY).performClick()
        compose.onNodeWithTag(RecognitionPanelTags.COPIED).assertIsDisplayed()

        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertTrue(clipboard.hasPrimaryClip())
            assertEquals("Recognized LaTeX", clipboard.primaryClipDescription?.label)
            assertEquals(latex, clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
            clipboard.clearPrimaryClip()
        }
    }

    /**
     * The interpretation read-out is **debug-only** and the actions beside it are not.
     *
     * Instrumented tests run against the debug variant, so `BuildConfig.DEBUG` is true here and the
     * "Understood as" half of this still asserts what it always did. What it can no longer prove is
     * the release build, where that section is compiled out — which is why the actions are asserted
     * in the same test rather than a separate one: they are derived from the same `MathAnalysis`,
     * and the failure worth catching is an analysis that arrives and produces nothing usable.
     */
    @Test
    fun parsedEquationShowsInterpretationAndRelevantActions() {
        var action = ""
        setPanel(
            state = RecognitionPanelState(
                kind = RecognitionOutputKind.Formula,
                value = "x^2-4=0",
            ),
            formulaTools = FormulaToolsState(
                sourceLatex = "x^2-4=0",
                analysis = MathAnalysis(
                    normalizedLatex = "x^2 - 4 = 0",
                    summary = "Equation",
                    variables = listOf("x"),
                    actions = listOf(
                        MathAction("solve", "Solve"),
                        MathAction("graph", "Graph"),
                    ),
                ),
            ),
            onMathAction = { action = it },
        )

        compose.onNodeWithText("Understood as").assertExists()
        compose.onNodeWithText("Equation · Variables: x").assertExists()
        compose.onNodeWithTag(RecognitionPanelTags.INTERPRETATION).assertExists()
        compose.onNodeWithTag(RecognitionPanelTags.action("solve")).performClick()
        assertEquals("solve", action)
    }

    @Test
    fun operationResultShowsRenderedLatexAndNativeGraph() {
        setPanel(
            state = RecognitionPanelState(
                kind = RecognitionOutputKind.Formula,
                value = "x^2",
            ),
            formulaTools = FormulaToolsState(
                sourceLatex = "x^2",
                analysis = MathAnalysis(
                    normalizedLatex = "x^2",
                    summary = "Expression",
                    variables = listOf("x"),
                    actions = listOf(MathAction("graph", "Graph")),
                ),
                result = MathOperationResult(
                    title = "Graph",
                    latex = "x^2",
                    graph = MathGraph(
                        xLabel = "x",
                        yLabel = "y",
                        xValues = listOf(-1.0, 0.0, 1.0),
                        yValues = listOf(1.0, 0.0, 1.0),
                    ),
                ),
            ),
        )

        compose.onNodeWithTag(RecognitionPanelTags.RESULT).assertExists()
        compose.onNodeWithTag(RecognitionPanelTags.GRAPH).assertExists()
    }

    private fun setPanel(
        state: RecognitionPanelState,
        formulaTools: FormulaToolsState = FormulaToolsState(),
        onValueChange: (String) -> Unit = {},
        onMathAction: (String) -> Unit = {},
    ) {
        compose.setContent {
            ViveNotesTheme {
                Column {
                    RecognitionPanelContent(
                        state = state,
                        formulaTools = formulaTools,
                        onValueChange = onValueChange,
                        onCopy = {},
                        onMathAction = onMathAction,
                    )
                }
            }
        }
    }
}
