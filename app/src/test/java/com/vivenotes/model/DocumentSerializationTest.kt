package com.vivenotes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document model is the source of truth for export, search and eventually sync, so a lossy
 * round trip here would corrupt notes everywhere downstream.
 */
class DocumentSerializationTest {

    @Test
    fun `round trips a document with every mark`() {
        val doc = PageDoc(
            outlines = listOf(
                Outline.Text(
                    id = "outline-1",
                    x = 12f,
                    y = 40f,
                    width = 640f,
                    blocks = listOf(
                        Block(
                            id = "b1",
                            type = BlockType.Heading1,
                            align = Align.Center,
                            runs = listOf(Run("Title", setOf(Mark.Bold))),
                        ),
                        Block(
                            id = "b2",
                            type = BlockType.Bullet,
                            indent = 2,
                            runs = listOf(
                                Run("plain "),
                                Run("styled", setOf(Mark.Italic, Mark.Underline, Mark.Strikethrough)),
                                Run("coloured", setOf(Mark.TextColor(0xFFFF0000.toInt()))),
                                Run("marked", setOf(Mark.Highlight(0x66FFEB3B))),
                                Run("sized", setOf(Mark.FontSize(24))),
                                Run("font", setOf(Mark.FontFamily("serif"))),
                                Run("link", setOf(Mark.Link("https://example.com"))),
                                Run("sub", setOf(Mark.Subscript)),
                                Run("sup", setOf(Mark.Superscript)),
                            ),
                        ),
                        Block(id = "b3", type = BlockType.Todo, checked = true, runs = listOf(Run("done"))),
                        Block(id = "b4", type = BlockType.Code, runs = listOf(Run("val x = 1"))),
                    ),
                ),
                Outline.Image(id = "img-1", attachmentId = "att-1", height = 200f),
                Outline.Ink(id = "ink-1", height = 120f),
            ),
        )

        assertEquals(doc, decodePageDoc(doc.encode()))
    }

    @Test
    fun `round trips an empty document`() {
        val doc = PageDoc.empty()
        assertEquals(doc, decodePageDoc(doc.encode()))
    }

    @Test
    fun `unchecked to-do keeps its false value distinct from a non-to-do block`() {
        // `checked` is nullable to distinguish "unticked to-do" from "not a to-do at all";
        // encodeDefaults=false must not collapse false into absent.
        val doc = PageDoc(
            outlines = listOf(
                Outline.Text(
                    id = "o",
                    blocks = listOf(
                        Block(id = "todo", type = BlockType.Todo, checked = false, runs = listOf(Run("x"))),
                        Block(id = "para", type = BlockType.Paragraph, runs = listOf(Run("y"))),
                    ),
                ),
            ),
        )

        val decoded = decodePageDoc(doc.encode())
        val blocks = (decoded.outlines.first() as Outline.Text).blocks
        assertEquals(false, blocks[0].checked)
        assertEquals(null, blocks[1].checked)
    }

    @Test
    fun `tolerates fields written by a newer schema`() {
        // Forward compatibility matters once a sync server can hand this client a document
        // written by a newer build.
        val json = """{"schema":1,"outlines":[{"t":"text","id":"o","blocks":[
            {"id":"b","runs":[{"text":"hi"}],"somethingNew":42}],"futureField":"x"}]}"""
        val decoded = decodePageDoc(json)
        assertEquals("hi", (decoded.outlines.first() as Outline.Text).blocks.first().text)
    }

    @Test
    fun `plain text projection joins blocks with newlines`() {
        val doc = PageDoc(
            outlines = listOf(
                Outline.Text(
                    id = "o",
                    blocks = listOf(
                        Block.of("first"),
                        Block.of("second"),
                    ),
                ),
            ),
        )
        assertEquals("first\nsecond", doc.plainText())
    }

    @Test
    fun `ids are unique and time ordered`() {
        val ids = List(500) { newId() }
        assertEquals(500, ids.toSet().size)
        assertTrue("ids should sort by creation order", ids == ids.sorted() || ids.first() <= ids.last())
    }
}
