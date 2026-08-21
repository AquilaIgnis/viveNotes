package com.vivenotes.model

import com.vivenotes.model.ink.ShapeKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a document holding nothing — `memory/blankFlushPlan.md`.
 *
 * The stakes are why this is pinned kind by kind rather than by one happy case: a page this says
 * yes about is deleted outright, with no tombstone, no Deleted Items entry and no undo. The mistake
 * that would matter is a page holding a picture, a shape, a table or an equation and no text at all,
 * because every one of those flattens to an empty [plainText] — which is exactly why this predicate
 * is written against the outlines and not against that.
 */
class PageDocBlankTest {

    private fun textPage(vararg blocks: Block) = PageDoc(
        outlines = listOf(Outline.Text(id = "text", blocks = blocks.toList())),
    )

    @Test
    fun aNewlyCreatedPageIsBlank() {
        assertTrue(PageDoc.empty().isBlank())
    }

    @Test
    fun aPageWithNoOutlinesAtAllIsBlank() {
        assertTrue(PageDoc().isBlank())
    }

    @Test
    fun emptyContainersAndEmptyRunsAreBlank() {
        assertTrue(textPage(Block.empty(), Block.empty()).isBlank())
        assertTrue(textPage(Block.of("   "), Block.of("\n")).isBlank())
        assertTrue(
            PageDoc(
                outlines = listOf(
                    Outline.Text(id = "a", blocks = listOf(Block.empty())),
                    Outline.Text(id = "b", blocks = emptyList()),
                ),
            ).isBlank(),
        )
    }

    @Test
    fun oneTypedCharacterIsNotBlank() {
        assertFalse(textPage(Block.empty(), Block.of("a")).isBlank())
    }

    /**
     * A heading, a bullet or a ticked to-do with no text in it is still blank. The block type is a
     * shape the caret was left in, not something somebody wrote.
     */
    @Test
    fun anEmptyBlockIsBlankWhateverTypeItIs() {
        assertTrue(
            textPage(
                Block.of("", BlockType.Heading1),
                Block.of("", BlockType.Bullet),
                Block(id = "todo", type = BlockType.Todo, checked = true),
            ).isBlank(),
        )
    }

    /** An inline equation projects its source, so a run holding one is not empty text. */
    @Test
    fun anInlineEquationIsContent() {
        assertFalse(
            textPage(
                Block(
                    id = "b",
                    runs = listOf(Run(OBJECT_REPLACEMENT_CHARACTER.toString(), setOf(Mark.Equation("x^2")))),
                ),
            ).isBlank(),
        )
    }

    /**
     * The four kinds that carry no text. Every one of them would pass a `plainText().isBlank()` test
     * — a picture and a shape contribute nothing to it at all, and an empty table contributes
     * nothing either — and every one of them is a thing somebody placed on the page.
     */
    @Test
    fun anOutlineThatIsNotTextIsContentEvenWithNoTextInIt() {
        val page = Outline.Image(id = "img", attachmentId = "sha", height = 100f)
        val shape = Outline.Shape(
            id = "shape",
            kind = ShapeKind.Rectangle,
            height = 40f,
        )
        val table = Outline.Table(
            id = "table",
            columns = listOf(100f, 100f),
            rows = listOf(
                TableRow(
                    id = "row",
                    cells = listOf(TableCell.empty("a"), TableCell.empty("b")),
                ),
            ),
        )
        val equation = Outline.Equation(id = "eq", width = 80f, height = 40f, latex = "")
        listOf(page, shape, table, equation).forEach { outline ->
            assertTrue(
                "an outline of ${outline::class.simpleName} flattens to no text",
                PageDoc(outlines = listOf(outline)).plainText().isBlank(),
            )
            assertFalse(
                "${outline::class.simpleName} was treated as nothing",
                PageDoc(outlines = listOf(outline)).isBlank(),
            )
        }
    }

    @Test
    fun oneObjectAmongEmptyContainersIsStillContent() {
        assertFalse(
            PageDoc(
                outlines = listOf(
                    Outline.Text(id = "a", blocks = listOf(Block.empty())),
                    Outline.Image(id = "img", attachmentId = "sha", height = 10f),
                    Outline.Text(id = "b", blocks = listOf(Block.empty())),
                ),
            ).isBlank(),
        )
    }

    /** A paper size chosen for a page nobody wrote on is not content. */
    @Test
    fun thePageStyleIsNotConsulted() {
        assertTrue(PageDoc.empty().copy(style = PageStyle(hideTitle = true)).isBlank())
    }
}
