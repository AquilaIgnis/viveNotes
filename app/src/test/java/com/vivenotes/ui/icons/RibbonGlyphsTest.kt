package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RibbonGlyphsTest {

    @Test
    fun insertTextAccentsTheFrameButKeepsTheLetterNeutral() {
        val neutral = Color(0xFF112233)
        val accent = Color(0xFF4477AA)
        val paths = insertTextGlyph(neutral, accent).root.filterIsInstance<VectorPath>()

        assertEquals("frame plus four handles", 5, paths.count { it.stroke == SolidColor(accent) })
        assertEquals("the T", 1, paths.count { it.fill == SolidColor(neutral) })
        assertEquals("the accent leaked into the T", 0, paths.count { it.fill == SolidColor(accent) })
    }

    @Test
    fun importNotebookAccentsOnlyTheArrow() {
        val neutral = Color(0xFF112233)
        val accent = Color(0xFF4477AA)

        val paths = importNotebookGlyph(neutral, accent).artwork()

        assertEquals("the cover", SolidColor(neutral), paths[0].fill)
        assertEquals("the arrow", SolidColor(accent), paths[1].fill)
        assertEquals("nothing else in the glyph", 2, paths.size)
    }

    /**
     * The one that earns its place. Both paths are pasted in as SVG text rather than built from
     * [PathNode]s, and a string that lost a character while being wrapped across source lines still
     * parses — it just draws something else. Measuring the result is what notices.
     */
    @Test
    fun importNotebookPointsAnArrowDownIntoTheCover() {
        val paths = importNotebookGlyph(Color.Black, Color.Blue).artwork()
        val cover = paths[0].pathData.endpoints()
        val arrow = paths[1].pathData.endpoints()

        // Material's 960 box with y measured up from the bottom edge, so the cover's own top is the
        // most negative number in it. The glyph's translating group is what puts this on screen.
        assertEquals("cover left", 160f, cover.minOf { it.x }, 0.5f)
        assertEquals("cover right", 800f, cover.maxOf { it.x }, 0.5f)
        assertEquals("cover top", -880f, cover.minOf { it.y }, 0.5f)
        assertEquals("cover bottom", -80f, cover.maxOf { it.y }, 0.5f)

        // The arrow enters flush with the inside of the cover's top edge and stops short of the
        // bottom one, so it reads as going *into* the book rather than through it.
        assertEquals("arrow enters at the cover's inner edge", -800f, arrow.minOf { it.y }, 0.5f)
        assertTrue("the tip must fall short of the bottom", arrow.maxOf { it.y } < -160f)
        assertTrue("arrow crosses the left edge", arrow.minOf { it.x } > 240f)
        assertTrue("arrow crosses the right edge", arrow.maxOf { it.x } < 720f)
    }

    @Test
    fun closeNotebookAccentsOnlyTheArrowBadge() {
        val neutral = Color(0xFF112233)
        val accent = Color(0xFF4477AA)

        // Fluent's box has its origin at the top left, so this glyph has no translating group and
        // its artwork sits straight in the root — the one place it differs from everything else here.
        val paths = closeNotebookGlyph(neutral, accent).root.filterIsInstance<VectorPath>()

        assertEquals("the cover and the badge", 2, paths.size)
        assertEquals("the notebook", SolidColor(neutral), paths[0].fill)
        assertEquals("the arrow badge", SolidColor(accent), paths[1].fill)
    }

    /**
     * The one that earns its place, as with Import: both paths are pasted in as SVG text, and a
     * string that lost a character while being wrapped across source lines still parses — it just
     * draws something else. Measuring the result is what notices.
     */
    @Test
    fun closeNotebookSitsTheBadgeOnTheNotebooksTopCorner() {
        val paths = closeNotebookGlyph(Color.Black, Color.Blue).root
            .filterIsInstance<VectorPath>()
        val cover = paths[0].pathData.endpoints()
        val badge = paths[1].pathData.endpoints()

        // Fluent's 20-unit box, y measured *down* from the top edge — the opposite of Material's.
        assertEquals("badge left", 1f, badge.minOf { it.x }, 0.05f)
        assertEquals("badge top", 1f, badge.minOf { it.y }, 0.05f)
        assertEquals("badge is 9 across at most", 10f, badge.maxOf { it.x }, 0.05f)

        // The notebook fills the rest: it starts below the badge's top and runs to the box's edges,
        // with the spiral rings furthest right.
        assertEquals("cover bottom", 18f, cover.maxOf { it.y }, 0.05f)
        assertEquals("the rings are the rightmost thing", 17f, cover.maxOf { it.x }, 0.05f)
        assertTrue("the badge overhangs the cover's left edge", badge.minOf { it.x } < cover.minOf { it.x })
    }

    /**
     * **Warning red belongs to Delete Notebook alone**, and this is the assertion that keeps it
     * there: the two commands sit in one row and the colour is what tells them apart before either
     * label is read. Passing `warn` into this glyph is the mistake worth failing a build over.
     */
    @Test
    fun closeNotebookNeverPaintsItsBadgeInTheDeleteColour() {
        val neutral = Color(0xFF112233)
        val accent = Color(0xFF4477AA)
        val warn = Color(0xFFB3261E)

        val icons = AppIcons(neutral = neutral, accent = accent, warn = warn, create = accent)
        val paths = icons.closeNotebook.root.filterIsInstance<VectorPath>()

        assertEquals(2, paths.size)
        assertEquals(SolidColor(accent), paths[1].fill)
        assertTrue("nothing in this glyph may be the delete colour", paths.none { it.fill == SolidColor(warn) })
    }

    @Test
    fun closedNotebooksAccentsTheRulesOnTheOpenPage() {
        val neutral = Color(0xFF112233)
        val accent = Color(0xFF4477AA)

        val paths = closedNotebooksGlyph(neutral, accent).artwork()
        assertEquals("the covers and the rules", 2, paths.size)
        assertEquals("the covers", SolidColor(neutral), paths[0].fill)
        assertEquals("the rules", SolidColor(accent), paths[1].fill)
    }

    /**
     * `menu_book`'s subpaths are chained with *relative* movetos — each one measured from where the
     * previous began — so lifting the rules out of the middle meant rewriting three of them as
     * absolute. Get one wrong and the whole subpath lands somewhere else while still parsing, which
     * is exactly what this measures.
     */
    @Test
    fun closedNotebooksPutsThreeRulesOnTheRightHandPage() {
        val paths = closedNotebooksGlyph(Color.Black, Color.Blue).artwork()
        val covers = paths[0].pathData.endpoints()
        val rules = paths[1].pathData.endpoints()

        // Material's 960 box with y measured up from the bottom, so the top of the book is the most
        // negative number in it. The glyph's translating group is what puts this on screen.
        assertEquals("book left", 40f, covers.minOf { it.x }, 0.5f)
        assertEquals("book right", 920f, covers.maxOf { it.x }, 0.5f)
        assertEquals("book top", -800f, covers.minOf { it.y }, 0.5f)

        // Three rules, at three heights, all of them past the spine at the centre of the 960 box.
        val starts = paths[1].pathData.filterIsInstance<PathNode.MoveTo>()
        assertEquals("three rules", 3, starts.size)
        assertEquals("at three different heights", 3, starts.map { it.y }.distinct().size)
        assertTrue("every rule is on the right-hand page", rules.all { it.x > 480f })

        // And inside the book rather than off the end of it.
        assertTrue(rules.maxOf { it.x } < covers.maxOf { it.x })
        assertTrue(rules.minOf { it.y } > covers.minOf { it.y })
    }

    /** The paths inside the translating group [materialGlyph] wraps its artwork in. */
    private fun androidx.compose.ui.graphics.vector.ImageVector.artwork(): List<VectorPath> =
        root.filterIsInstance<VectorGroup>().single().filterIsInstance<VectorPath>()

    private data class Point(val x: Float, val y: Float)

    /**
     * Where each command in [this] ends up, with the current point carried between them the way SVG
     * defines it, so the result can be measured.
     *
     * Only the commands these glyphs actually use are handled, and anything else fails the test
     * rather than being skipped — skipping quietly is exactly how a mis-parse would get past this.
     */
    private fun List<PathNode>.endpoints(): List<Point> {
        var x = 0f
        var y = 0f
        var subpathX = 0f
        var subpathY = 0f
        return map { node ->
            when (node) {
                is PathNode.MoveTo -> {
                    x = node.x; y = node.y; subpathX = x; subpathY = y
                }
                is PathNode.RelativeMoveTo -> {
                    x += node.dx; y += node.dy; subpathX = x; subpathY = y
                }
                is PathNode.CurveTo -> { x = node.x3; y = node.y3 }
                is PathNode.RelativeCurveTo -> { x += node.dx3; y += node.dy3 }
                // The rounded ends of the Close cross and the shelf, which Material's own exports
                // write as quadratics rather than cubics.
                is PathNode.QuadTo -> { x = node.x2; y = node.y2 }
                is PathNode.RelativeQuadTo -> { x += node.dx2; y += node.dy2 }
                is PathNode.ReflectiveQuadTo -> { x = node.x; y = node.y }
                is PathNode.RelativeReflectiveQuadTo -> { x += node.dx; y += node.dy }
                is PathNode.LineTo -> { x = node.x; y = node.y }
                is PathNode.RelativeLineTo -> { x += node.dx; y += node.dy }
                is PathNode.HorizontalTo -> x = node.x
                is PathNode.RelativeHorizontalTo -> x += node.dx
                is PathNode.VerticalTo -> y = node.y
                is PathNode.RelativeVerticalTo -> y += node.dy
                PathNode.Close -> { x = subpathX; y = subpathY }
                else -> fail("unhandled path command: $node")
            }
            Point(x, y)
        }
    }
}
