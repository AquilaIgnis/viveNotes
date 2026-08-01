package st.unamedtba.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The View tab writes into the document, so a page's appearance has to survive storage and sync
 * exactly like its text does.
 */
class PageStyleTest {

    @Test
    fun `round trips a fully specified style`() {
        val doc = PageDoc(
            outlines = listOf(Outline.Text(id = "o", blocks = listOf(Block.of("hi")))),
            style = PageStyle(
                ruleLines = RuleLines.College,
                paper = PaperSize.A4,
                orientation = Orientation.Landscape,
                backgroundArgb = 0xFF102030.toInt(),
                hideTitle = true,
            ),
        )

        assertEquals(doc.style, decodePageDoc(doc.encode()).style)
    }

    @Test
    fun `a document written before the View tab existed keeps its appearance`() {
        // Every stored page predates PageStyle, so the defaults are not cosmetic — they are what
        // those pages will look like after this change.
        val json = """{"schema":1,"outlines":[{"t":"text","id":"o","blocks":[{"id":"b","runs":[{"text":"hi"}]}]}]}"""

        assertEquals(PageStyle(), decodePageDoc(json).style)
    }

    @Test
    fun `an unset background follows the theme rather than encoding a colour`() {
        // Null is a real state, not a missing one: it is what lets the page follow a light or dark
        // shell instead of pinning itself to whichever one was current when it was created.
        assertNull(decodePageDoc(PageDoc.empty().encode()).style.backgroundArgb)
    }

    @Test
    fun `paper sizes are physical, and orientation swaps them`() {
        val portrait = PageStyle(paper = PaperSize.Letter)
        val landscape = portrait.copy(orientation = Orientation.Landscape)

        // 8.5 x 11 inches at 160dp to the inch — dp is defined that way, so this is actual size.
        assertEquals(1360f to 1760f, portrait.pageSizeDp)
        assertEquals(1760f to 1360f, landscape.pageSizeDp)
    }

    @Test
    fun `auto paper has no bounds at all`() {
        assertNull(PageStyle(paper = PaperSize.Auto).pageSizeDp)
        assertNull(PageStyle(paper = PaperSize.Auto, orientation = Orientation.Landscape).pageSizeDp)
    }

    @Test
    fun `only ruled variants of rule lines are squared`() {
        assertEquals(0f, RuleLines.None.spacingDp, 0f)
        RuleLines.entries.filter { it.name.startsWith("Grid") }.forEach {
            assertEquals("${it.name} should be squared", true, it.squared)
        }
        listOf(RuleLines.Narrow, RuleLines.College, RuleLines.Standard, RuleLines.Wide).forEach {
            assertEquals("${it.name} should rule horizontally only", false, it.squared)
        }
    }
}
