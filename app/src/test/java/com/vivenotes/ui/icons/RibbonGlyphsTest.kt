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
