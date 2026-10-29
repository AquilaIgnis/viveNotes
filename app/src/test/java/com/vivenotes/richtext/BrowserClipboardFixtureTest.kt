package com.vivenotes.richtext

import com.vivenotes.data.ClipboardContent
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * One page selected whole and copied in Firefox and in Chromium on Linux, the clipboard's
 * `text/html` saved as the desktop port's AWT reader got it. Firefox copies the page's markup with
 * its classes; Chromium inlines every computed style.
 */
class BrowserClipboardFixtureTest {
    private val caret = setOf(Mark.FontFamily("sans-serif"), Mark.FontSize(15))

    private fun fixture(name: String) = BrowserClipboardFixtureTest::class.java
        .getResource("/clipboard/$name")!!.readText()

    private fun List<Block>.run(text: String) = flatMap { it.runs }.single { it.text == text }

    /** The page as it looks: its blocks, with a blank line where it puts space between them. */
    private val page = listOf(
        BlockType.Heading1 to "Derivative",
        BlockType.Paragraph to "",
        BlockType.Paragraph to "In mathematics, the derivative of a function measures its rate of change. Café — naïve ✓ 日本 😀",
        BlockType.Paragraph to "",
        BlockType.Heading2 to "Rules",
        BlockType.Paragraph to "",
        BlockType.Bullet to "Power rule: xn",
        BlockType.Bullet to "Sum rule",
        BlockType.Bullet to "nested item",
        BlockType.Paragraph to "",
        BlockType.Numbered to "First",
        BlockType.Numbered to "Second",
        BlockType.Paragraph to "",
        BlockType.Todo to "Done task",
        BlockType.Todo to "Open task",
        BlockType.Paragraph to "",
        BlockType.Heading3 to "Notes",
        BlockType.Paragraph to "",
        BlockType.Quote to "Quoted wisdom.",
        BlockType.Paragraph to "",
        BlockType.Code to "fun main() {",
        BlockType.Code to "    println(\"hi\")",
        BlockType.Code to "}",
        BlockType.Paragraph to "",
        BlockType.Paragraph to "Water is H2O, red note, highlight, struck, under, inline code.",
        BlockType.Paragraph to "",
        BlockType.Paragraph to "Name\tValue",
        BlockType.Paragraph to "x\t1",
    )

    private fun assertThePage(blocks: List<Block>) {
        assertEquals(page, blocks.map { it.type to it.text })
        assertEquals(1, blocks.single { it.text == "nested item" }.indent)
        assertEquals(listOf(true, false), blocks.filter { it.type == BlockType.Todo }.map { it.checked })
        assertEquals(caret + Mark.Link("https://en.wikipedia.org/wiki/Mathematics"), blocks.run("mathematics").marks)
        assertEquals(caret + Mark.Bold, blocks.run("derivative").marks)
        assertEquals(caret + Mark.Italic, blocks.run("rate of change").marks)
        assertEquals(caret + Mark.Superscript, blocks.run("n").marks)
        assertEquals(caret + Mark.Subscript, blocks.run("2").marks)
        assertEquals(caret + Mark.Highlight(0x66FFEB3B), blocks.run("highlight").marks)
        assertEquals(caret + Mark.Strikethrough, blocks.run("struck").marks)
        assertEquals(caret + Mark.Underline, blocks.run("under").marks)
        assertEquals(setOf(Mark.FontFamily("monospace"), Mark.FontSize(15)), blocks.run("inline code").marks)
        assertEquals(setOf(Mark.FontFamily("monospace"), Mark.FontSize(15)), blocks.run("    println(\"hi\")").marks)
        assertEquals("the page's near-black text takes the box's ink", caret, blocks.run("Derivative").marks)
    }

    @Test
    fun firefoxsCopyKeepsThePagesShape() {
        val blocks = pastedHtmlBlocks(fixture("firefox-linux.html"), caret)!!
        assertThePage(blocks)
        assertTrue("Firefox leaves the colour in the page's stylesheet",
            blocks.flatMap { it.runs }.none { run -> run.marks.any { it is Mark.TextColor } })
    }

    @Test
    fun chromiumsCopyKeepsThePagesShapeAndItsColour() {
        val blocks = pastedHtmlBlocks(fixture("chromium-linux.html"), caret)!!
        assertThePage(blocks)
        assertEquals(caret + Mark.TextColor(0xFFC62828.toInt()), blocks.run("red note").marks)
    }

    @Test
    fun bothBrowsersPasteTheirTablesAsEditableCanvasObjects() {
        for (name in listOf("firefox-linux.html", "chromium-linux.html")) {
            val items = canvasPasteItems(ClipboardContent(html = fixture(name)), caret)
            val table = items.filterIsInstance<CanvasPasteItem.Table>().single().outline
            assertEquals(name, listOf(listOf("Name", "Value"), listOf("x", "1")),
                table.rows.map { it.cells.map { cell -> cell.plainText } })
            assertTrue(name, items.filterIsInstance<CanvasPasteItem.Text>().flatMap { it.blocks }.any { it.type == BlockType.Heading1 })
        }
    }
}
