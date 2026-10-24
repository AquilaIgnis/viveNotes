package com.vivenotes.richtext

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Run
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * What a browser or Google Docs copies as HTML, pasted through the system clipboard and the same
 * Paste the ribbon, the selection toolbar and Ctrl+V use. `HtmlPasteTest` reads the HTML; this is
 * where its blocks land in the editor.
 *
 * Hosted in an activity rather than as a bare view: Android lets only the focused app read the
 * clipboard.
 */
class HtmlPasteEditingTest {
    @get:Rule val compose = createComposeRule()

    private val webPage = "<h2>Steps</h2><ul><li>Open <b>it</b></li><li>Close it</li></ul>"

    @Test
    fun aBrowsersCopyKeepsItsHeadingsListsAndMarksInTheCaretsFont() = withEditor(Block(id = "e"), Block.of("after")) { view ->
        val caretMarks = setOf(Mark.FontFamily("serif"), Mark.FontSize(24))
        view.defaultMarks = caretMarks
        view.setSelection(0)

        view.paste(webPage, "Steps\nOpen it\nClose it")

        val blocks = view.blocks()
        assertEquals("the page's space between the heading and the list is a blank line",
            listOf("Steps", "", "Open it", "Close it", "after"), blocks.map { it.text })
        assertEquals(listOf(BlockType.Heading2, BlockType.Paragraph, BlockType.Bullet, BlockType.Bullet, BlockType.Paragraph),
            blocks.map { it.type })
        assertEquals("the line pasted onto keeps its id", "e", blocks.first().id)
        assertEquals(caretMarks, blocks[0].runs.single().marks)
        assertEquals(caretMarks + Mark.Bold, blocks[2].runs.last().marks)
        assertEquals("Steps\n\nOpen it\nClose it".length, view.selectionStart)
    }

    /** What another app copied as one heading or item is that paragraph, pasted where it fills one. */
    @Test
    fun aSingleParagraphKeepsItsStyleOnlyWhereItFillsTheLine() = withEditor(Block.of("before"), Block(id = "e"), Block.of("after")) { view ->
        val heading = "<h2>Title</h2>"

        view.setSelection(7)
        view.paste(heading, "Title")
        assertEquals("before\nTitle\nafter", view.text.toString())
        assertEquals(listOf(BlockType.Paragraph, BlockType.Heading2, BlockType.Paragraph), view.blocks().map { it.type })
        assertEquals("e", view.blocks()[1].id)

        view.setBlocks(listOf(Block.of("replace me")))
        view.setSelection(0, 10)
        view.paste(heading, "Title")
        assertEquals(BlockType.Heading2, view.blocks().single().type)

        view.setBlocks(listOf(Block.of("see here")))
        view.setSelection(4)
        view.paste(heading, "Title")
        assertEquals("see Titlehere", view.text.toString())
        assertEquals("words in a sentence take the sentence's style", BlockType.Paragraph, view.blocks().single().type)

        view.setBlocks(listOf(Block(id = "b", type = BlockType.Bullet)))
        view.setSelection(0)
        view.paste("<p>words</p>", "words")
        assertEquals("a plain paragraph leaves the item its bullet", BlockType.Bullet, view.blocks().single().type)

        view.setBlocks(listOf(Block(id = "e")))
        view.setSelection(0)
        view.paste("<h1 style=\"margin:0\">Head</h1><ul style=\"margin:0\"><li>item</li></ul>", "Head\nitem")
        assertEquals(listOf(BlockType.Heading1, BlockType.Bullet), view.blocks().map { it.type })
    }

    /** Pasted into the middle of a line, the line keeps its style on both sides, as Enter splits it. */
    @Test
    fun aPasteThatSplitsALineLeavesBothHalvesItsStyle() = withEditor(
        Block(id = "t", type = BlockType.Todo, checked = true, runs = listOf(Run("ab"))),
    ) { view ->
        view.setSelection(1)

        view.paste("<h3 style=\"margin:0\">X</h3><p style=\"margin:0\">Y</p>", "X\nY")

        val blocks = view.blocks()
        assertEquals(listOf("aX", "Yb"), blocks.map { it.text })
        assertEquals(listOf(BlockType.Todo, BlockType.Todo), blocks.map { it.type })
        assertEquals("the first half keeps its tick and the second starts unticked", listOf(true, false), blocks.map { it.checked })
        assertEquals("t", blocks.first().id)
    }

    @Test
    fun plainPastesAndHtmlWithoutTextPasteThePlainText() = withEditor(Block(id = "e")) { view ->
        view.setSelection(0)
        view.paste(webPage, "Steps", plain = true)
        assertEquals(listOf(Block(id = "e", runs = listOf(Run("Steps")))), view.blocks())

        view.setBlocks(listOf(Block(id = "e")))
        view.setSelection(0)
        view.paste("<img src=\"x.png\">", "only text")
        assertEquals("HTML without text leaves the text, not the platform's picture placeholder",
            "only text", view.text.toString())
    }

    private fun withEditor(vararg blocks: Block, body: (OutlineEditText) -> Unit) {
        var editor: OutlineEditText? = null
        compose.setContent {
            ViveNotesTheme {
                AndroidView(factory = { context ->
                    OutlineEditText(context).apply {
                        setBlocks(blocks.toList())
                        editor = this
                    }
                })
            }
        }
        compose.waitUntil(5_000) { (editor?.width ?: 0) > 0 }
        compose.runOnIdle {
            val view = checkNotNull(editor)
            view.requestFocus()
            body(view)
            view.context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
    }

    /**
     * Puts [html] on the clipboard beside [text] and pastes it, in one go: the emulator's host
     * clipboard sharing may replace the clip as soon as this yields.
     */
    private fun OutlineEditText.paste(html: String, text: String, plain: Boolean = false) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newHtmlText("page", text, html))
        apply(FormatCommand.Clipboard(if (plain) ClipboardAction.PasteAsPlainText else ClipboardAction.Paste))
    }
}
