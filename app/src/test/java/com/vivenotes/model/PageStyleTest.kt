package com.vivenotes.model

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
                paper = PaperSize.Custom,
                orientation = Orientation.Landscape,
                customPaper = PaperDimensions(7.5f, 9.25f),
                margins = PrintMargins(topInches = 0.5f, rightInches = 0.25f),
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

    /**
     * A page must never become unreadable over a setting. Letter, Legal, Statement, Tabloid,
     * Postcard and Index Card were offered before and are not any more; a page saved with one of
     * them opens as an unbounded page rather than failing to open at all. The same protection
     * covers the reverse case — a document written by a newer build with a size this one lacks.
     */
    @Test
    fun `a page naming a size this build does not have still opens`() {
        val json = """{"schema":1,"outlines":[{"t":"text","id":"o","blocks":[{"id":"b","runs":[{"text":"hi"}]}]}],
            "style":{"paper":"Letter","ruleLines":"Dotted","hideTitle":true}}"""

        val decoded = decodePageDoc(json)

        assertEquals(PaperSize.Auto, decoded.style.paper)
        assertEquals(RuleLines.GridMedium, decoded.style.ruleLines)
        // Everything the build *does* understand still has to survive the fallback.
        assertEquals(true, decoded.style.hideTitle)
        assertEquals("hi", (decoded.outlines.first() as Outline.Text).blocks.first().text)
    }

    @Test
    fun `an unset background follows the theme rather than encoding a colour`() {
        // Null is a real state, not a missing one: it is what lets the page follow a light or dark
        // shell instead of pinning itself to whichever one was current when it was created.
        assertNull(decodePageDoc(PageDoc.empty().encode()).style.backgroundArgb)
    }

    @Test
    fun `paper sizes are physical, and orientation swaps them`() {
        val portrait = PageStyle(paper = PaperSize.A5)
        val landscape = portrait.copy(orientation = Orientation.Landscape)

        // 5.83 x 8.27 inches at 160dp to the inch — dp is defined that way, so this is actual size.
        // Compared with a tolerance because inches are floats and 8.27 * 160 does not land on 1323.2.
        assertPage(932.8f, 1323.2f, portrait)
        assertPage(1323.2f, 932.8f, landscape)
    }

    @Test
    fun `a custom page is measured by what was typed into it`() {
        assertPage(640f, 960f, PageStyle(paper = PaperSize.Custom, customPaper = PaperDimensions(4f, 6f)))
    }

    private fun assertPage(width: Float, height: Float, style: PageStyle) {
        val size = style.pageSizeDp ?: error("expected ${style.paper} to have bounds")
        assertEquals(width, size.first, 0.1f)
        assertEquals(height, size.second, 0.1f)
    }

    /** Choosing Custom before typing anything is a page being set up, not an unbounded one. */
    @Test
    fun `a custom page with nothing entered still has bounds`() {
        val bare = PageStyle(paper = PaperSize.Custom)

        assertEquals(PaperDimensions.DEFAULT, bare.paperInches)
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
