package com.vivenotes.model.search

import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.Run
import com.vivenotes.model.TableCell
import com.vivenotes.model.TableRow
import com.vivenotes.model.newId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Content panel searches, and where a hit says it is — `docs/searchPlan.md` CS3–CS5.
 *
 * The offsets are the part worth guarding: they are handed straight to `setSelection`, so a block
 * counted wrong does not fail here — it silently highlights the wrong words on the page.
 */
class ContentSearchTest {

    private val page = "page-1"
    private val section = "section-1"

    private fun doc(vararg outlines: Outline) = PageDoc(outlines = outlines.toList())

    private fun textBox(id: String, vararg lines: String) = Outline.Text(
        id = id,
        blocks = lines.map { Block.of(it) },
    )

    private fun cell(id: String, text: String) =
        TableCell(id = id, blocks = listOf(Block.of(text)))

    private fun table(id: String, inkOnly: Boolean = false, vararg cells: TableCell) = Outline.Table(
        id = id,
        columns = List(cells.size) { 100f },
        rows = listOf(TableRow(id = newId(), cells = cells.toList())),
        inkOnly = inkOnly,
    )

    @Test
    fun `text containers, table cells and the title are searchable`() {
        val units = doc(
            textBox("box", "the container line"),
            table("grid", cells = arrayOf(cell("cell", "the cell line"))),
        ).contentUnits(page, section, "The Title")

        assertEquals(
            listOf(ContentKind.Title, ContentKind.Text, ContentKind.Cell),
            units.map { it.kind },
        )
        assertEquals(listOf(page, "box", "cell"), units.map { it.boxId })
        assertEquals("grid", units.first { it.kind == ContentKind.Cell }.tableId)
    }

    @Test
    fun `an untitled page contributes no title unit`() {
        val units = doc(textBox("box", "written")).contentUnits(page, section, "")
        assertEquals(listOf(ContentKind.Text), units.map { it.kind })
    }

    @Test
    fun `ink, shapes, pictures and equation objects are not searched`() {
        val units = doc(
            Outline.Ink(id = "ink"),
            Outline.Shape(id = "shape"),
            Outline.Image(id = "image", attachmentId = "sha", height = 10f),
            Outline.Equation(id = "equation", latex = "\\int_0^1 x"),
        ).contentUnits(page, section, "")
        assertTrue(units.toString(), units.isEmpty())
    }

    @Test
    fun `an ink table has no text to search`() {
        val units = doc(
            table("grid", inkOnly = true, cells = arrayOf(TableCell(id = "cell"))),
        ).contentUnits(page, section, "")
        assertTrue(units.toString(), units.isEmpty())
    }

    @Test
    fun `a block's offset is where the editor holds it`() {
        val units = doc(textBox("box", "first line", "", "third line"))
            .contentUnits(page, section, "")

        // "first line" is 10 characters, then a newline; the blank block is one more newline. The
        // blank contributes no result of its own but must still be counted, or every later match
        // lands short by one per empty line.
        assertEquals(listOf(0, 12), units.map { it.blockStart })
    }

    @Test
    fun `a hit's editor range is the match inside the whole box`() {
        val units = doc(textBox("box", "first line", "second line")).contentUnits(page, section, "")
        val hit = searchContent(units, "second").single()
        assertEquals(11, hit.unit.blockStart)
        assertEquals(11, hit.editorStart)
        assertEquals(17, hit.editorEnd)
    }

    @Test
    fun `an inline equation is one character wide, exactly as the editor writes it`() {
        val block = Block(
            id = newId(),
            runs = listOf(
                Run("x = "),
                Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(Mark.Equation("\\frac{1}{2}"))),
                Run(" here"),
            ),
        )
        assertEquals("x = ${OBJECT_REPLACEMENT_CHARACTER} here", block.editorText)

        val units = doc(Outline.Text(id = "box", blocks = listOf(block, Block.of("after"))))
            .contentUnits(page, section, "")
        // Ten characters of block — "x = " and " here" either side of the one the formula occupies —
        // then the newline. The LaTeX source is eleven characters long and is in none of them: it is
        // not in the editor, so it is not in the offsets either (CS5).
        assertEquals(10, block.editorText.length)
        assertEquals(listOf(0, 11), units.map { it.blockStart })

        val hit = searchContent(units, "here").single { it.unit.blockIndex == 0 }
        assertEquals(6, hit.editorStart)
    }

    @Test
    fun `a title outranks a body line matching the same word`() {
        val units = doc(textBox("box", "invoices are here")).contentUnits(page, section, "Invoices")
        val hits = searchContent(units, "invoices")
        assertEquals(ContentKind.Title, hits.first().unit.kind)
        assertEquals(2, hits.size)
    }

    @Test
    fun `results are capped, and the cap is what the panel is told about`() {
        val lines = List(MAX_HITS + 20) { "match number $it" }
        val units = doc(textBox("box", *lines.toTypedArray())).contentUnits(page, section, "")
        assertEquals(MAX_HITS, searchContent(units, "match").size)
    }

    @Test
    fun `a blank query finds nothing rather than everything`() {
        val units = doc(textBox("box", "written")).contentUnits(page, section, "Title")
        assertTrue(searchContent(units, "  ").isEmpty())
    }

    @Test
    fun `a snippet keeps its emphasis over the characters it was about`() {
        val text = "x".repeat(300) + " needle " + "y".repeat(300)
        val match = FuzzyMatcher.match("needle", text)
        assertNotNull(match)
        val snippet = snippetOf(text, match!!.spans)

        assertTrue(snippet.text.length < text.length)
        val span = snippet.spans.single()
        assertEquals("needle", snippet.text.substring(span.start, span.end))
    }

    @Test
    fun `a short line is shown whole, with its spans untouched`() {
        val match = FuzzyMatcher.match("needle", "a needle here")!!
        val snippet = snippetOf("a needle here", match.spans)
        assertEquals("a needle here", snippet.text)
        assertEquals(match.spans, snippet.spans)
    }

    @Test
    fun `an inline equation shows as a space in a snippet rather than a tofu box`() {
        val snippet = snippetOf("x = ${OBJECT_REPLACEMENT_CHARACTER} here", emptyList())
        assertEquals("x =   here", snippet.text)
    }

    // --- pictures — `memory/imageOcrPlan.md` IO5 ------------------------------------------------

    private fun picture(id: String, attachmentId: String) =
        Outline.Image(id = id, attachmentId = attachmentId, height = 100f)

    @Test
    fun `a page placing the same picture twice contributes it once`() {
        val placements = doc(
            picture("frame-a", "sha-one"),
            picture("frame-b", "sha-one"),
            picture("frame-c", "sha-two"),
        ).imagePlacements(page, section)

        // Two placements of one screenshot are one answer to "where is this written", with two
        // frames around it. The *first* in document order is the one a result opens.
        assertEquals(listOf("sha-one", "sha-two"), placements.map { it.attachmentId })
        assertEquals(listOf("frame-a", "frame-c"), placements.map { it.outlineId })
    }

    @Test
    fun `two pages placing the same picture each contribute it`() {
        val one = doc(picture("frame-a", "sha-one")).imagePlacements("page-1", section)
        val two = doc(picture("frame-b", "sha-one")).imagePlacements("page-2", section)

        val units = (one + two).flatMap { imageUnits(it, listOf("shared caption")) }
        // Different pages are genuinely different places to go, so both are findable — and both
        // read the same single row of stored text.
        assertEquals(listOf("page-1", "page-2"), units.map { it.pageId })
        assertEquals(setOf("sha-one"), units.mapTo(mutableSetOf()) { it.attachmentId })
    }

    @Test
    fun `a picture with no stored text contributes nothing`() {
        val placement = doc(picture("frame", "sha-one")).imagePlacements(page, section).single()

        assertTrue(imageUnits(placement, emptyList()).isEmpty())
        assertTrue(imageUnits(placement, listOf("", "   ")).isEmpty())
    }

    @Test
    fun `each recognized line is its own result, pointing at the picture`() {
        val placement = doc(picture("frame", "sha-one")).imagePlacements(page, section).single()
        val units = imageUnits(placement, listOf("first line", "second line"))

        assertEquals(listOf(ContentKind.Image, ContentKind.Image), units.map { it.kind })
        assertEquals(listOf("frame", "frame"), units.map { it.boxId })
        assertEquals(listOf(0, 1), units.map { it.blockIndex })
        // Offsets into the joined text, the way blocks are offsets into an editor's text.
        assertEquals(listOf(0, "first line".length + 1), units.map { it.blockStart })
    }

    @Test
    fun `a typed line outranks a picture's line for the same word`() {
        val typed = doc(textBox("box", "quarterly revenue")).contentUnits(page, section, "")
        val placement = doc(picture("frame", "sha-one")).imagePlacements(page, section).single()
        val read = imageUnits(placement, listOf("quarterly revenue"))

        val hits = searchContent(typed + read, "quarterly")
        assertEquals(2, hits.size)
        // Typed text is what someone wrote; a reading is what a model thinks it can see.
        assertEquals(ContentKind.Text, hits.first().unit.kind)
        assertEquals(ContentKind.Image, hits.last().unit.kind)
    }
}
