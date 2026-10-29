package com.vivenotes.richtext

import com.vivenotes.data.ClipboardContent
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasPasteTest {
    @Test
    fun plainTextNormalizesLineEndingsAndUsesTheChosenFont() {
        val marks = setOf(Mark.FontSize(20))
        val item = assertIs<CanvasPasteItem.Text>(canvasPasteItems(ClipboardContent(text = "Hello\r\n世界\r😀"), marks).single())
        assertEquals(listOf("Hello", "世界", "😀"), item.blocks.map { it.text })
        assertTrue(item.blocks.flatMap { it.runs }.all { it.marks == marks })
        assertTrue(canvasPasteItems(ClipboardContent()).isEmpty())
    }

    @Test
    fun formattedHtmlWithoutPlainTextMakesATextBox() {
        val item = assertIs<CanvasPasteItem.Text>(paste("<h2>Title</h2><p><b>Bold</b> &amp; text</p>").single())
        assertEquals(BlockType.Heading2, item.blocks.first().type)
        assertTrue(item.blocks.flatMap { it.runs }.any { Mark.Bold in it.marks })
        assertTrue(item.blocks.any { it.text == "Bold & text" })
    }

    @Test
    fun browserTableBecomesAnEditableTableWithHeadersAndRichCells() {
        val table = assertIs<CanvasPasteItem.Table>(paste("""
            <table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody>
            <tr><td><b>Café</b></td><td><a href="https://example.org">日本 😀</a></td></tr>
            <tr><td>Empty neighbour</td><td></td></tr></tbody></table>
        """).single()).outline
        assertEquals(2, table.columnCount)
        assertEquals(3, table.rowCount)
        assertTrue(table.headerRow)
        assertFalse(table.headerColumn)
        assertFalse(table.inkOnly)
        assertEquals("Café", table.cellAt(1, 0)!!.plainText)
        assertTrue(table.cellAt(1, 0)!!.blocks.flatMap { it.runs }.all { Mark.Bold in it.marks })
        assertTrue(table.cellAt(1, 1)!!.blocks.flatMap { it.runs }.any { Mark.Link("https://example.org") in it.marks })
        assertEquals("", table.cellAt(2, 1)!!.plainText)
    }

    @Test
    fun selectedRowsAndOmittedCellEndTagsStillMakeATable() {
        val table = assertIs<CanvasPasteItem.Table>(paste("<TR><TD>One<TD>Two<TR><TD>Three<TD>Four").single()).outline
        assertEquals(listOf(listOf("One", "Two"), listOf("Three", "Four")), table.rows.map { it.cells.map { cell -> cell.plainText } })
    }

    @Test
    fun spansReserveEmptyCellsAndRaggedRowsArePadded() {
        val table = assertIs<CanvasPasteItem.Table>(paste("""
            <table><tr><th rowspan="2">A</th><th colspan="2">B</th></tr>
            <tr><td>C</td><td>D</td></tr><tr><td>E</td></tr></table>
        """).single()).outline
        assertEquals(listOf(listOf("A", "B", ""), listOf("", "C", "D"), listOf("E", "", "")),
            table.rows.map { it.cells.map { cell -> cell.plainText } })
    }

    @Test
    fun oversizedTablesFallBackToTextWithoutDroppingCells() {
        val html = "<table><tr>" + (1..13).joinToString("") { "<td>value$it</td>" } + "</tr></table>"
        val item = assertIs<CanvasPasteItem.Text>(paste(html).single())
        assertTrue(item.blocks.joinToString { it.text }.contains("value13"))
        assertIs<CanvasPasteItem.Text>(paste("<table><tr><td colspan='999999'>wide</td></tr></table>").single())
        assertIs<CanvasPasteItem.Text>(paste("<table><tr><td>First</td><td colspan='2147483647'>wide</td></tr></table>").single())
        assertIs<CanvasPasteItem.Text>(paste("<table><tr><td>First</td></tr><tr><td rowspan='2147483647'>tall</td></tr></table>").single())
    }

    @Test
    fun textTablesAndPicturesKeepTheirOrder() {
        val items = paste("<p>Before</p><table><tr><td>Cell</td></tr></table><p>After</p><a href='https://example.org/page'><img src='https://example.org/photo.png'></a>")
        assertEquals(4, items.size)
        assertEquals("Before", assertIs<CanvasPasteItem.Text>(items[0]).blocks.single().text)
        assertIs<CanvasPasteItem.Table>(items[1])
        assertEquals("After", assertIs<CanvasPasteItem.Text>(items[2]).blocks.single().text)
        assertEquals("https://example.org/photo.png", assertIs<CanvasPasteItem.Image>(items[3]).address)
    }

    @Test
    fun imageReferencesDecodeEntitiesAndResolveRelativeAddresses() {
        val item = assertIs<CanvasPasteItem.Image>(paste("<base href='https://example.org/path/page'><IMG SRC='../photo.png?a=1&amp;b=2' src='ignored'>").single())
        assertEquals("https://example.org/path/../photo.png?a=1&b=2", item.address)
        val windows = "Version:1.0\r\nSourceURL:https://example.org/path/page\r\n<html><img src='/photo.png'></html>"
        assertEquals("https://example.org/photo.png", assertIs<CanvasPasteItem.Image>(paste(windows).single()).address)
    }

    @Test
    fun aCopiedImageAddressMakesAPictureAndOrdinaryLinksRemainText() {
        assertIs<CanvasPasteItem.Image>(canvasPasteItems(ClipboardContent(text = "https://example.org/photo.JPG?size=large")).single())
        assertIs<CanvasPasteItem.Image>(canvasPasteItems(ClipboardContent(imageUrl = "https://example.org/media?id=1")).single())
        assertIs<CanvasPasteItem.Text>(canvasPasteItems(ClipboardContent(text = "https://example.org/page")).single())
        assertNull(imageAddress("javascript:alert(1)"))
        assertNull(imageAddress("file:///etc/passwd"))
    }

    @Test
    fun nativePicturesTakePrecedenceOverBrowserFallbacks() {
        val bytes = byteArrayOf(1, 2, 3)
        val item = assertIs<CanvasPasteItem.Image>(canvasPasteItems(ClipboardContent(text = "fallback", html = "<b>fallback</b>", image = bytes)).single())
        assertTrue(item.bytes === bytes)
    }

    @Test
    fun tableHtmlTakesPrecedenceOverAnOfficeSuitesBitmapPreview() {
        assertIs<CanvasPasteItem.Table>(canvasPasteItems(ClipboardContent(html = "<table><tr><td>Editable</td></tr></table>",
            image = byteArrayOf(1))).single())
    }

    @Test
    fun aLinkedImageAddressUsesItsHrefAndLinkedImagesUseTheirSrc() {
        assertEquals("https://example.org/photo.png", assertIs<CanvasPasteItem.Image>(
            paste("<a href='https://example.org/photo.png'>Download photo</a>").single()).address)
        assertEquals("https://example.org/thumbnail.png", assertIs<CanvasPasteItem.Image>(
            paste("<a href='https://example.org/photo.png'><img src='https://example.org/thumbnail.png'></a>").single()).address)
    }

    @Test
    fun hiddenMarkupCommentsAndScriptsDoNotInsertObjects() {
        val items = paste("""<!--<img src='https://example.org/comment.png'>-->
            <script>const example = "<table><tr><td>script</td></tr></table>";</script>
            <div hidden><img src='https://example.org/hidden.png'><table><tr><td>hidden</td></tr></table></div>
            <p>Visible</p>""")
        assertEquals("Visible", assertIs<CanvasPasteItem.Text>(items.single()).blocks.single().text)
    }

    @Test
    fun nestedTablesKeepTheirTextInTheOuterCell() {
        val table = assertIs<CanvasPasteItem.Table>(paste("<table><tr><td>Outer<table><tr><td>Inner</td></tr></table></td><td>Next</td></tr></table>").single()).outline
        assertEquals(2, table.columnCount)
        assertTrue(table.cellAt(0, 0)!!.plainText.contains("Inner"))
        assertEquals("Next", table.cellAt(0, 1)!!.plainText)
    }

    private fun paste(html: String) = canvasPasteItems(ClipboardContent(html = html))

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue("expected a ${T::class.simpleName}, was $value", value is T)
        return value as T
    }
}
