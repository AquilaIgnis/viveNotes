package com.vivenotes.richtext

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.Run
import com.vivenotes.model.TOGGLEABLE_MARKS

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
    fun preservesAnEquationAsOneAtomicEditorCharacter() {
        val latex = "{\\displaystyle \\int _{a}^{b}f'(t)\\,dt=f(b)-f(a)}"
        val blocks = listOf(
            Block(
                id = "equation-block",
                runs = listOf(
                    Run("before "),
                    Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(Mark.Equation(latex))),
                    Run(" after"),
                ),
            ),
        )

        val rendered = SpannableCodec.render(blocks, style)
        val equationStart = "before ".length
        assertEquals(OBJECT_REPLACEMENT_CHARACTER, rendered[equationStart])
        assertEquals(equationStart + 1, rendered.getSpanEnd(rendered.getSpans(
            0,
            rendered.length,
            EquationSpan::class.java,
        ).single()))

        assertEquals(blocks, SpannableCodec.parse(rendered))
        assertEquals("before $latex after", SpannableCodec.parse(rendered).single().text)
    }

    /**
     * The invariant the Content panel's offsets stand on — `memory/searchPlan.md` CS5.
     *
     * `Block.editorText` is the model's claim about what [SpannableCodec.render] writes, and a search
     * hit's position is handed straight to `setSelection` on the strength of it. This is the only
     * place that can hold the two strings up against each other, since one of them needs Android.
     *
     * An equation is the case that matters: `Block.text` substitutes the LaTeX source, which is a
     * different length, so a search that used it would select the wrong characters — by the length of
     * every formula before the match.
     */
    @Test
    fun editorTextIsWhatTheEditorActuallyHolds() {
        val latex = "\\frac{1}{2}"
        val blocks = listOf(
            Block(
                id = "first",
                runs = listOf(
                    Run("before "),
                    Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(Mark.Equation(latex))),
                    Run(" after"),
                ),
            ),
            Block(id = "second", runs = listOf(Run("plain line"))),
        )

        val rendered = SpannableCodec.render(blocks, style)
        assertEquals(rendered.toString(), blocks.joinToString("\n") { it.editorText })
        // And the two projections really do differ, so the assertion above is not vacuous.
        assertTrue(blocks.first().text.length > blocks.first().editorText.length)
    }

    @Test
    fun findsAnEquationOnItsSelectionAndEitherCaretBoundary() {
        val latex = "x^2"
        val rendered = SpannableCodec.render(
            listOf(
                Block(
                    id = "b",
                    runs = listOf(
                        Run("a"),
                        Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(Mark.Equation(latex))),
                        Run("b"),
                    ),
                ),
            ),
            style,
        )

        assertEquals(latex, SpannableCodec.equationAt(rendered, 1, 2)?.latex)
        assertEquals(latex, SpannableCodec.equationAt(rendered, 1, 1)?.latex)
        assertEquals(latex, SpannableCodec.equationAt(rendered, 2, 2)?.latex)
    }

    @Test
    fun clearingFormattingDoesNotDeleteEquationSource() {
        val equation = Mark.Equation("x+y")
        val rendered = SpannableCodec.render(
            listOf(
                Block(
                    id = "b",
                    runs = listOf(
                        Run(
                            OBJECT_REPLACEMENT_CHARACTER.toString(),
                            setOf<Mark>(equation, Mark.Bold, Mark.TextColor(0xFFFF0000.toInt())),
                        ),
                    ),
                ),
            ),
            style,
        )

        SpannableCodec.clearMarks(rendered, 0, 1)

        assertEquals(setOf<Mark>(equation), SpannableCodec.parse(rendered).single().runs.single().marks)
    }

    @Test
    fun liveEquationPreviewDoesNotReplaceItsEditableSourceInTheModel() {
        val source = "Solve \\(x^2+y^2=z^2\\) here"
        val rendered = SpannableCodec.render(listOf(Block.of(source)), style)
        val candidate = findAutoEquationCandidates(source).single()
        rendered.setSpan(
            LiveEquationSpan(candidate.latex, renderSizePx = 24f, renderColor = 0xFF000000.toInt()),
            candidate.start,
            candidate.end,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        val parsed = SpannableCodec.parse(rendered).single()

        assertEquals(source, parsed.text)
        assertTrue(parsed.runs.all { it.marks.isEmpty() })
    }

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
    fun preservesBundledFontFamiliesAndKeepsBoldAlongside() {
        // The font id is what lands in the stored document, so it has to survive the round trip
        // intact — and applying a font must not wipe out bold applied over the same text.
        FontRegistry.init(InstrumentationRegistry.getInstrumentation().targetContext)

        FontRegistry.families.forEach { family ->
            val blocks = listOf(
                Block(
                    id = "b",
                    runs = listOf(Run("styled", setOf(Mark.FontFamily(family.id), Mark.Bold))),
                ),
            )

            val marks = roundTrip(blocks).first().runs.first().marks

            assertTrue("${family.id} was lost, got $marks", Mark.FontFamily(family.id) in marks)
            assertTrue("bold was lost alongside ${family.id}, got $marks", Mark.Bold in marks)
        }
    }

    @Test
    fun keepsAnUnknownFontFamilyRatherThanRewritingIt() {
        // A document written on a build that bundles a font this one does not must keep its
        // family name, so opening it elsewhere does not silently restyle the text.
        val blocks = listOf(Block(id = "b", runs = listOf(Run("x", setOf(Mark.FontFamily("some-unbundled-font"))))))

        val marks = roundTrip(blocks).first().runs.first().marks

        assertTrue("unknown family was rewritten, got $marks", Mark.FontFamily("some-unbundled-font") in marks)
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

    // --- sub- and superscript --------------------------------------------------------------------

    private fun plain(text: String = "text") =
        SpannableCodec.render(listOf(Block(id = "b", runs = listOf(Run(text)))), style)

    private fun scriptSizes(text: android.text.Spannable) =
        text.getSpans(0, text.length, ScriptSizeSpan::class.java)

    /**
     * Each script mark is two spans — the baseline shift and the 0.75 size reduction — and only the
     * shift is a [Mark]. Removing has to take the pair, or the reduction is invisible to every
     * assertion that reads marks back while still being on the text.
     */
    @Test
    fun removingAScriptTakesItsSizeReductionWithIt() {
        listOf(Mark.Subscript, Mark.Superscript).forEach { mark ->
            val text = plain()
            SpannableCodec.applyMark(text, mark, 0, text.length)
            SpannableCodec.removeMark(text, mark, 0, text.length)

            assertEquals("$mark left its size behind", 0, scriptSizes(text).size)
        }
    }

    /**
     * The bug this guards: toggling off left the reduction in place, so toggling back on added a
     * second one and the text came back smaller than it went. Four cycles compounded 0.75 to under
     * a third of the base size, which reads as the button stacking rather than toggling.
     */
    @Test
    fun togglingAScriptOffAndOnDoesNotCompoundItsSize() {
        listOf(Mark.Subscript, Mark.Superscript).forEach { mark ->
            val text = plain()
            repeat(4) {
                SpannableCodec.applyMark(text, mark, 0, text.length)
                SpannableCodec.removeMark(text, mark, 0, text.length)
            }
            SpannableCodec.applyMark(text, mark, 0, text.length)

            assertEquals("$mark stacked", 1, scriptSizes(text).size)
        }
    }

    /**
     * Nothing is both raised and lowered. Applying one script over the other replaces it, rather
     * than leaving two baseline shifts and two size reductions fighting over the same characters.
     */
    @Test
    fun oneScriptReplacesTheOther() {
        val text = plain()
        SpannableCodec.applyMark(text, Mark.Subscript, 0, text.length)
        SpannableCodec.applyMark(text, Mark.Superscript, 0, text.length)

        val marks = SpannableCodec.marksAt(text, 0, text.length)
        assertTrue("superscript did not take", Mark.Superscript in marks)
        assertTrue("subscript survived underneath", Mark.Subscript !in marks)
        assertEquals("and left its size behind", 1, scriptSizes(text).size)
    }
}
