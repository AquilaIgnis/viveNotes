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

    private fun setPanel(
        state: RecognitionPanelState,
        onValueChange: (String) -> Unit = {},
    ) {
        compose.setContent {
            ViveNotesTheme {
                Column {
                    RecognitionPanelContent(
                        state = state,
                        onValueChange = onValueChange,
                        onCopy = {},
                    )
                }
            }
        }
    }
}
