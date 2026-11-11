package com.vivenotes.fileimport.pptx

import com.vivenotes.fileimport.docx.WordClosePath
import com.vivenotes.fileimport.docx.WordCubicTo
import com.vivenotes.fileimport.docx.WordLineTo
import com.vivenotes.fileimport.docx.WordMoveTo
import com.vivenotes.fileimport.docx.WordPathCommand
import com.vivenotes.fileimport.docx.parseXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PptxGeometryTest {
    @Test
    fun aPlainRectangleNeedsNoOutline() {
        assertNull(presetOutline("rect", 100f, 50f, emptyMap()))
    }

    @Test
    fun aShapeNotWorthDrawingExactlyFallsBackToARectangle() {
        assertNull(presetOutline("gear9", 100f, 50f, emptyMap()))
    }

    @Test
    fun aDiamondTouchesTheMiddleOfEachSide() {
        val path = assertNotNull(presetOutline("diamond", 100f, 50f, emptyMap())).let { presetOutline("diamond", 100f, 50f, emptyMap())!! }

        assertEquals(WordMoveTo(50f, 0f), path.commands[0])
        assertEquals(WordLineTo(100f, 25f), path.commands[1])
        assertEquals(WordClosePath, path.commands.last())
    }

    @Test
    fun aLineIsNeverFilled() {
        val path = presetOutline("straightConnector1", 100f, 50f, emptyMap())!!

        assertFalse(path.filled)
        assertEquals(listOf<WordPathCommand>(WordMoveTo(0f, 0f), WordLineTo(100f, 50f)), path.commands)
    }

    @Test
    fun anElbowConnectorTurnsWhereItsAdjustmentSays() {
        val path = presetOutline("bentConnector3", 100f, 50f, mapOf("adj1" to 25000f))!!

        assertEquals(WordLineTo(25f, 0f), path.commands[1])
        assertEquals(WordLineTo(25f, 50f), path.commands[2])
    }

    @Test
    fun aRoundedRectanglesCornersFollowItsAdjustment() {
        val path = presetOutline("roundRect", 100f, 50f, mapOf("adj" to 50000f))!!

        assertEquals(WordMoveTo(25f, 0f), path.commands[0])
    }

    @Test
    fun anArrowDownIsTheArrowRightTurned() {
        val path = presetOutline("downArrow", 40f, 100f, emptyMap())!!

        assertTrue(path.commands.contains(WordLineTo(20f, 100f)))
    }

    @Test
    fun aFreeformKeepsItsPathsInTheirOwnSpace() {
        val xml = """<a:custGeom xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:pathLst><a:path w="200" h="100" fill="none"><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="200" y="100"/></a:lnTo><a:close/></a:path></a:pathLst></a:custGeom>"""

        val path = customOutline(parseXml(xml.toByteArray()), 10f, 10f).single()

        assertEquals(200f, path.width)
        assertFalse(path.filled)
        assertEquals(WordLineTo(200f, 100f), path.commands[1])
    }

    @Test
    fun aQuadraticCurveBecomesACubicToTheSamePlace() {
        val xml = """<a:custGeom xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:pathLst><a:path w="10" h="10"><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:quadBezTo><a:pt x="5" y="10"/><a:pt x="10" y="0"/></a:quadBezTo></a:path></a:pathLst></a:custGeom>"""

        val curve = customOutline(parseXml(xml.toByteArray()), 10f, 10f).single().commands[1] as WordCubicTo

        assertEquals(10f, curve.x)
        assertEquals(0f, curve.y)
    }

    @Test
    fun aQuarterArcEndsAQuarterOfTheWayRound() {
        val out = ArrayList<WordPathCommand>()

        val (x, y) = arcCommands(10f, 0f, 10f, 10f, 0f, 90f, out)

        assertEquals(1, out.size)
        assertEquals(0f, x, 0.01f)
        assertEquals(10f, y, 0.01f)
    }

    @Test
    fun aLongArcIsCutIntoCurves() {
        val out = ArrayList<WordPathCommand>()

        arcCommands(10f, 0f, 10f, 10f, 0f, 270f, out)

        assertEquals(3, out.size)
    }

    /** DrawingML sweeps at most a turn either way; a file's 1e30 was a curve for every quarter turn of it. */
    @Test(timeout = 5_000)
    fun anArcPastAFullTurnIsOneTurnAndNoNumberIsNoArc() {
        val huge = ArrayList<WordPathCommand>()
        arcCommands(10f, 0f, 10f, 10f, 0f, 1e30f, huge)
        assertEquals(4, huge.size)

        val none = ArrayList<WordPathCommand>()
        arcCommands(10f, 0f, 10f, 10f, 0f, Float.NaN, none)
        assertTrue(none.all { (it as WordCubicTo).x.isFinite() && it.y.isFinite() })
    }
}
