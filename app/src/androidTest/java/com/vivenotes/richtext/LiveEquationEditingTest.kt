package com.vivenotes.richtext

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.vivenotes.model.Block
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveEquationEditingTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersAwayFromTheCaretAndRevealsSourceWhileEditing() {
        val source = "before \\(x^2+y^2=z^2\\) after"
        val candidate = findAutoEquationCandidates(source).single()
        var editor: OutlineEditText? = null

        compose.setContent {
            ViveNotesTheme {
                AndroidView(
                    factory = { context ->
                        OutlineEditText(context).apply {
                            setBlocks(listOf(Block.of(source)))
                            editor = this
                        }
                    },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            editor?.liveEquations()?.isNotEmpty() == true
        }
        compose.runOnIdle {
            assertEquals(
                "the preview must not replace stored source",
                source,
                checkNotNull(editor).blocks().single().text,
            )
        }

        compose.runOnIdle {
            val view = checkNotNull(editor)
            assertTrue(view.requestFocus())
            view.setSelection(candidate.start + 3)
        }
        compose.waitUntil(timeoutMillis = 2_000) { editor?.liveEquations()?.isEmpty() == true }
        compose.runOnIdle { assertEquals(source, checkNotNull(editor).text.toString()) }

        compose.runOnIdle { checkNotNull(editor).setSelection(0) }
        compose.waitUntil(timeoutMillis = 2_000) { editor?.liveEquations()?.isNotEmpty() == true }
        compose.runOnIdle { assertEquals(source, checkNotNull(editor).blocks().single().text) }
    }

    private fun OutlineEditText.liveEquations(): Array<LiveEquationSpan> =
        text.getSpans(0, text.length, LiveEquationSpan::class.java)
}
