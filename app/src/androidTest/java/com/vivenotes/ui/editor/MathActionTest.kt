package com.vivenotes.ui.editor

import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The ink toolkit's **Math** button — the ∫ glyph and one tap.
 *
 * It replaced a *Recognize* button that opened a drop-down of "As text" and "As equation" when the text
 * option was removed on 2026-08-09. The tests worth having are the two things that change made true:
 * the action fires on the first tap with no menu in between, and it is absent rather than dead when
 * there is no formula model installed to hand the ink to.
 */
class MathActionTest {

    @get:Rule
    val compose = createComposeRule()

    private var recognized = false

    @Test
    fun oneTapHandsTheInkToTheMathEngine() {
        setToolkit()

        compose.onNodeWithTag(OBJECT_RECOGNIZE_TAG).performClick()

        assertEquals("Math must act on the first tap, with no menu in between", true, recognized)
    }

    @Test
    fun itIsLabelledMath() {
        setToolkit()

        compose.onNodeWithText("Math").assertIsDisplayed()
    }

    /** No menu means no menu items — the two former options must not be reachable at all. */
    @Test
    fun theOldRecognizeMenuIsGone() {
        setToolkit()

        compose.onNodeWithText("Recognize").assertDoesNotExist()

        compose.onNodeWithTag(OBJECT_RECOGNIZE_TAG).performClick()

        compose.onNodeWithText("As text").assertDoesNotExist()
        compose.onNodeWithText("As equation").assertDoesNotExist()
    }

    /** Absent, not disabled: with no model installed there is nothing to hand the ink to. */
    @Test
    fun withoutTheFormulaModelThereIsNoButton() {
        setToolkit(formulaAvailable = false)

        compose.onNodeWithTag(OBJECT_RECOGNIZE_TAG).assertDoesNotExist()
    }

    /** While a recognition is already running the button stays visible but cannot be pressed again. */
    @Test
    fun aRunningRecognitionDisablesIt() {
        setToolkit(enabled = false)

        compose.onNodeWithTag(OBJECT_RECOGNIZE_TAG).assertIsNotEnabled()

        compose.onNodeWithTag(OBJECT_RECOGNIZE_TAG).performClick()

        assertEquals(false, recognized)
    }

    private fun setToolkit(formulaAvailable: Boolean = true, enabled: Boolean = true) {
        recognized = false
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.size(400.dp)) {
                    ObjectTooltip(
                        swatch = null,
                        selectionBoundsInView = { RectF(60f, 200f, 360f, 300f) },
                        viewportSize = IntSize(1000, 1000),
                        onDelete = {},
                        onCopy = {},
                        onRecolor = {},
                        extras = {
                            RecognitionAction(
                                formulaAvailable = formulaAvailable,
                                enabled = enabled,
                                onFormula = { recognized = true },
                            )
                        },
                    )
                }
            }
        }
    }
}
