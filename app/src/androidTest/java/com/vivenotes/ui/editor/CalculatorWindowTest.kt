package com.vivenotes.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.ShapeSettings
import com.vivenotes.ink.InkCodec
import com.vivenotes.math.MathOperationResult
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The experimental hand calculator: what reaches = and what comes back from it.
 *
 * Recognition and SymPy are faked by [solve] — `HandCalculatorTest` covers the action choice and
 * `SympyMathEngineTest` the engine. What is left is the pad: that a stroke written on it is the one
 * handed over, and that the finger rule, Undo and Clear decide which strokes those are.
 */
class CalculatorWindowTest {

    @get:Rule
    val compose = createComposeRule()

    private val handedOver = mutableListOf<Int>()
    private var openedInPanel: String? = null
    private var closed = 0
    private var toggles = 0

    private fun setCalculator(
        allowFinger: Boolean = true,
        answer: CalculatorAnswer = CalculatorAnswer(
            latex = "1 + 2",
            result = MathOperationResult(title = "Simplified", latex = "3"),
        ),
    ) {
        compose.setContent {
            ViveNotesTheme {
                FloatingCalculator(
                    brush = InkCodec.brushFor(PenPreset.starting(0)),
                    allowFinger = allowFinger,
                    solve = { strokes ->
                        handedOver += strokes.size
                        answer
                    },
                    onOpenInPanel = { openedInPanel = it },
                    onClose = { closed++ },
                )
            }
        }
    }

    /** Across the middle of the pad, in its own coordinates. */
    private fun write(heightFraction: Float = 0.5f) {
        compose.onNodeWithTag(CalculatorTags.PAD).performTouchInput {
            swipe(
                start = Offset(width * 0.2f, height * heightFraction),
                end = Offset(width * 0.8f, height * heightFraction),
                durationMillis = 200,
            )
        }
    }

    private fun pressEquals() {
        compose.onNodeWithTag(CalculatorTags.EQUALS).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { handedOver.isNotEmpty() }
    }

    @Test
    fun anEmptyPadHasNothingToCalculate() {
        setCalculator()

        compose.onNodeWithTag(CalculatorTags.EQUALS).assertIsNotEnabled()
    }

    @Test
    fun whatIsWrittenIsHandedToTheEngineAndItsAnswerShown() {
        setCalculator()

        write()
        pressEquals()

        assertEquals(listOf(1), handedOver)
        compose.onNodeWithTag(CalculatorTags.RESULT).assertIsDisplayed()
    }

    /** The page's rule: with finger drawing off, a finger on the pad is a palm, not a stroke. */
    @Test
    fun aFingerDoesNotWriteWhenFingerDrawingIsOff() {
        setCalculator(allowFinger = false)

        write()

        compose.onNodeWithTag(CalculatorTags.EQUALS).assertIsNotEnabled()
    }

    @Test
    fun undoTakesBackOnlyTheLastStroke() {
        setCalculator()

        write(heightFraction = 0.3f)
        write(heightFraction = 0.7f)
        compose.onNodeWithTag(CalculatorTags.UNDO).performClick()
        pressEquals()

        assertEquals(listOf(1), handedOver)
    }

    @Test
    fun clearEmptiesThePad() {
        setCalculator()

        write()
        compose.onNodeWithTag(CalculatorTags.EQUALS).assertIsEnabled()
        compose.onNodeWithTag(CalculatorTags.CLEAR).performClick()

        compose.onNodeWithTag(CalculatorTags.EQUALS).assertIsNotEnabled()
    }

    /** The pane is where a misread gets corrected, so it is handed what SymPy was handed. */
    @Test
    fun openInPanelHandsOverTheFormulaAsRead() {
        setCalculator()

        write()
        pressEquals()
        compose.onNodeWithTag(CalculatorTags.OPEN_IN_PANEL).performClick()

        assertEquals("1 + 2", openedInPanel)
    }

    @Test
    fun aFormulaWithNoAnswerSaysWhy() {
        setCalculator(answer = CalculatorAnswer(latex = "x +", error = "SymPy could not interpret this LaTeX."))

        write()
        pressEquals()

        compose.onNodeWithTag(CalculatorTags.ERROR).assertIsDisplayed()
        compose.onNodeWithTag(CalculatorTags.RESULT).assertDoesNotExist()
    }

    /** ≈ is a mode beside =, so it can be set before there is an answer and stays set after one. */
    @Test
    fun theDecimalToggleCanBeSetBeforeAndKeptAfterAnAnswer() {
        setCalculator(
            answer = CalculatorAnswer(
                latex = "\\frac{1}{3}",
                result = MathOperationResult(title = "Simplified", latex = "\\frac{1}{3}", decimal = "0.333333333333"),
            ),
        )

        compose.onNodeWithTag(CalculatorTags.DECIMAL).assertIsOff().performClick()
        write()
        pressEquals()

        compose.onNodeWithTag(CalculatorTags.RESULT).assertIsDisplayed()
        compose.onNodeWithTag(CalculatorTags.DECIMAL).assertIsOn()
    }

    @Test
    fun closeClosesIt() {
        setCalculator()

        compose.onNodeWithTag(CalculatorTags.CLOSE).performClick()

        assertEquals(1, closed)
    }

    /** Absent, not disabled, without the formula model — the lasso's Math button's rule. */
    @Test
    fun theRibbonOffersItOnlyWithTheFormulaModel() {
        var available by mutableStateOf(false)
        compose.setContent {
            ViveNotesTheme {
                DrawTab(
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    tool = DrawTool.None,
                    actions = DrawActions(
                        selectTool = {},
                        updatePen = { _, _ -> },
                        updateEraser = {},
                        setDrawWithFinger = {},
                        toggleCalculator = { toggles++ },
                    ),
                    calculatorAvailable = available,
                )
            }
        }

        compose.onNodeWithTag(DrawTags.CALCULATOR).assertDoesNotExist()

        available = true
        compose.waitForIdle()
        // Last in a row that scrolls, so past the edge of a narrow window.
        compose.onNodeWithTag(DrawTags.CALCULATOR).performScrollTo().performClick()

        assertEquals(1, toggles)
    }
}
