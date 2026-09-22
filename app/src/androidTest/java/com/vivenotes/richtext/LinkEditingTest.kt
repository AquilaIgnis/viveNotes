package com.vivenotes.richtext

import android.text.style.URLSpan
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LinkEditingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun linksSelectedTextAndEditsTheStoredDestination() {
        var editor: OutlineEditText? = null
        compose.setContent {
            ViveNotesTheme {
                AndroidView(factory = { context ->
                    OutlineEditText(context).apply {
                        setBlocks(listOf(Block.of("read this now")))
                        editor = this
                    }
                })
            }
        }
        compose.waitUntil(5_000) { (editor?.width ?: 0) > 0 }
        compose.runOnIdle {
            val view = checkNotNull(editor)
            view.requestFocus()
            view.setSelection(5, 9)
            view.apply(FormatCommand.InsertLink("this", "https://example.com"))
            assertEquals("read this now", view.text.toString())
            assertTrue(view.blocks().single().runs.any { Mark.Link("https://example.com") in it.marks })

            view.setSelection(7)
            view.apply(FormatCommand.InsertLink("that page", "https://example.org"))
            assertEquals("read that page now", view.text.toString())
            assertEquals("https://example.org", view.text.getSpans(5, 14, URLSpan::class.java).single().url)
            assertTrue(view.blocks().single().runs.any { Mark.Link("https://example.org") in it.marks })
        }
    }
}
