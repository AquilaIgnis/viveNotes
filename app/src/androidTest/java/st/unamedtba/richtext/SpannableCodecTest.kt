package st.unamedtba.richtext

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import st.unamedtba.model.Align
import st.unamedtba.model.Block
import st.unamedtba.model.BlockType
import st.unamedtba.model.Mark
import st.unamedtba.model.Run
import st.unamedtba.model.TOGGLEABLE_MARKS

/**
 * The editor renders blocks to spans and parses them back on every keystroke. Any asymmetry here
 * silently rewrites the user's document as they type, so this is the gate on the editor.
 *
 * Instrumented rather than local because Android's span classes have no JVM implementation.
 */
@RunWith(AndroidJUnit4::class)
class SpannableCodecTest {

    private val style = EditorStyle(
        indentStepPx = 40,
        listGapPx = 40,
        bulletRadiusPx = 6,
        accentColor = 0xFF4CAF50.toInt(),
        codeBackgroundColor = 0x22FFFFFF,
        quoteColor = 0xFF4CAF50.toInt(),
    )

    private fun roundTrip(blocks: List<Block>): List<Block> =
        SpannableCodec.parse(SpannableCodec.render(blocks, style))

    @Test
    fun preservesInlineMarks() {
        val blocks = listOf(
            Block(
                id = "b1",
                runs = listOf(
                    Run("plain "),
                    Run("bold", setOf(Mark.Bold)),
                    Run(" and "),
                    Run("italic", setOf(Mark.Italic)),
                ),
            ),
        )

        val result = roundTrip(blocks)

        assertEquals(1, result.size)
        assertEquals("plain bold and italic", result.first().text)
        val boldRun = result.first().runs.first { it.text == "bold" }
        assertTrue(Mark.Bold in boldRun.marks)
        val italicRun = result.first().runs.first { it.text == "italic" }
        assertTrue(Mark.Italic in italicRun.marks)
    }

    @Test
    fun preservesEveryToggleableMark() {
        // Covers each on/off mark rather than a hand-picked few — strikethrough shipped broken
        // because the original tests only exercised bold, italic and underline.
        TOGGLEABLE_MARKS.forEach { mark ->
            val blocks = listOf(Block(id = "b", runs = listOf(Run("text", setOf(mark)))))
            val marks = roundTrip(blocks).first().runs.first().marks
            assertTrue("$mark did not survive the round trip, got $marks", mark in marks)
        }
    }

    @Test
    fun removesEveryToggleableMark() {
        TOGGLEABLE_MARKS.forEach { mark ->
            val rendered = SpannableCodec.render(
                listOf(Block(id = "b", runs = listOf(Run("text", setOf(mark))))),
                style,
            )
            SpannableCodec.removeMark(rendered, mark, 0, rendered.length)
            val marks = SpannableCodec.parse(rendered).first().runs.flatMap { it.marks }
            assertTrue("$mark was not removed, left $marks", mark !in marks)
        }
    }

    @Test
    fun preservesOverlappingMarks() {
        val blocks = listOf(
            Block(
                id = "b1",
                runs = listOf(Run("both", setOf(Mark.Bold, Mark.Underline, Mark.Highlight(0x66FFEB3B)))),
            ),
        )

        val marks = roundTrip(blocks).first().runs.first().marks

        assertTrue(Mark.Bold in marks)
        assertTrue(Mark.Underline in marks)
        assertTrue(marks.any { it is Mark.Highlight })
    }

    @Test
    fun preservesBlockAttributes() {
        val blocks = listOf(
            Block(id = "h", type = BlockType.Heading2, align = Align.Center, runs = listOf(Run("Heading"))),
            Block(id = "l", type = BlockType.Bullet, indent = 2, runs = listOf(Run("Bullet"))),
            Block(id = "n", type = BlockType.Numbered, runs = listOf(Run("One"))),
            Block(id = "t", type = BlockType.Todo, checked = true, runs = listOf(Run("Task"))),
            Block(id = "q", type = BlockType.Quote, runs = listOf(Run("Quoted"))),
            Block(id = "c", type = BlockType.Code, runs = listOf(Run("code()"))),
        )

        val result = roundTrip(blocks)

        assertEquals(blocks.size, result.size)
        assertEquals(BlockType.Heading2, result[0].type)
        assertEquals(Align.Center, result[0].align)
        assertEquals(BlockType.Bullet, result[1].type)
        assertEquals(2, result[1].indent)
        assertEquals(BlockType.Numbered, result[2].type)
        assertEquals(BlockType.Todo, result[3].type)
        assertEquals(true, result[3].checked)
        assertEquals(BlockType.Quote, result[4].type)
        assertEquals(BlockType.Code, result[5].type)
    }

    @Test
    fun preservesBlockIdentity() {
        // Ids must survive editing so that per-block sync can attribute changes later.
        val blocks = listOf(
            Block(id = "stable-1", runs = listOf(Run("one"))),
            Block(id = "stable-2", runs = listOf(Run("two"))),
        )

        assertEquals(listOf("stable-1", "stable-2"), roundTrip(blocks).map { it.id })
    }

    @Test
    fun preservesEmptyBlocks() {
        val blocks = listOf(
            Block.of("first"),
            Block(id = "blank", runs = emptyList()),
            Block.of("third"),
        )

        val result = roundTrip(blocks)

        assertEquals(3, result.size)
        assertEquals("", result[1].text)
        assertEquals("third", result[2].text)
    }

    @Test
    fun headingStylingDoesNotLeakIntoInlineMarks() {
        // Headings are drawn with bold; that must not read back as a user-applied Bold mark,
        // or removing the heading would leave the text stuck bold.
        val blocks = listOf(Block(id = "h", type = BlockType.Heading1, runs = listOf(Run("Title"))))

        val marks = roundTrip(blocks).first().runs.flatMap { it.marks }

        assertTrue("heading bold leaked as an inline mark: $marks", Mark.Bold !in marks)
    }

    @Test
    fun codeStylingDoesNotLeakIntoInlineMarks() {
        val blocks = listOf(Block(id = "c", type = BlockType.Code, runs = listOf(Run("x"))))

        val marks = roundTrip(blocks).first().runs.flatMap { it.marks }

        assertTrue("code background leaked as a highlight: $marks", marks.none { it is Mark.Highlight })
        assertTrue("code typeface leaked as a font: $marks", marks.none { it is Mark.FontFamily })
    }

    @Test
    fun mergesAdjacentRunsWithIdenticalFormatting() {
        val blocks = listOf(
            Block(id = "b", runs = listOf(Run("aa"), Run("bb"), Run("cc"))),
        )

        val runs = roundTrip(blocks).first().runs

        assertEquals(1, runs.size)
        assertEquals("aabbcc", runs.first().text)
    }

    @Test
    fun normalizeGivesNewParagraphsTheirOwnBlock() {
        val rendered = SpannableCodec.render(listOf(Block.of("first")), style)
        rendered.append("\nsecond")

        SpannableCodec.normalize(rendered, style)
        val blocks = SpannableCodec.parse(rendered)

        assertEquals(2, blocks.size)
        assertEquals("first", blocks[0].text)
        assertEquals("second", blocks[1].text)
        assertTrue("new paragraph must get its own id", blocks[0].id != blocks[1].id)
    }

    @Test
    fun normalizeContinuesListFormattingOnNewParagraph() {
        val rendered = SpannableCodec.render(
            listOf(Block(id = "b", type = BlockType.Bullet, indent = 1, runs = listOf(Run("item")))),
            style,
        )
        rendered.append("\nnext")

        SpannableCodec.normalize(rendered, style)
        val blocks = SpannableCodec.parse(rendered)

        assertEquals(BlockType.Bullet, blocks[1].type)
        assertEquals(1, blocks[1].indent)
    }

    @Test
    fun normalizeStartsContinuedTodoUnchecked() {
        val rendered = SpannableCodec.render(
            listOf(Block(id = "b", type = BlockType.Todo, checked = true, runs = listOf(Run("done")))),
            style,
        )
        rendered.append("\nnext")

        SpannableCodec.normalize(rendered, style)
        val blocks = SpannableCodec.parse(rendered)

        assertEquals(BlockType.Todo, blocks[1].type)
        assertEquals(false, blocks[1].checked)
    }

    @Test
    fun clearMarksLeavesBlockAttributesIntact() {
        val rendered = SpannableCodec.render(
            listOf(Block(id = "b", type = BlockType.Heading2, runs = listOf(Run("Title", setOf(Mark.Italic))))),
            style,
        )

        SpannableCodec.clearMarks(rendered, 0, rendered.length)
        val block = SpannableCodec.parse(rendered).first()

        assertEquals(BlockType.Heading2, block.type)
        assertTrue(block.runs.flatMap { it.marks }.isEmpty())
    }

    @Test
    fun removeMarkSplitsAroundAPartialSelection() {
        val rendered = SpannableCodec.render(
            listOf(Block(id = "b", runs = listOf(Run("aaabbbccc", setOf(Mark.Bold))))),
            style,
        )

        SpannableCodec.removeMark(rendered, Mark.Bold, 3, 6)
        val runs = SpannableCodec.parse(rendered).first().runs

        assertEquals(3, runs.size)
        assertTrue(Mark.Bold in runs[0].marks)
        assertTrue(Mark.Bold !in runs[1].marks)
        assertTrue(Mark.Bold in runs[2].marks)
        assertEquals("aaabbbccc", runs.joinToString("") { it.text })
    }
}
