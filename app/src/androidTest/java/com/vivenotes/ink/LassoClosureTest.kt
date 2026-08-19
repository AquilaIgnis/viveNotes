package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A lasso selects what a *closed* path enclosed, and nothing else is a lasso.
 *
 * Every containment test closes the path for itself — `pointInPolygon` walks back from the last vertex
 * to the first whether the hand did or not — so an elbow drawn beside ink selected the triangle the
 * app had imagined, and a C left open selected what its chord cut off. The rule is asked of the
 * gesture once, in [selectWithLasso]: the path has to run back into itself. How *nearly* an open path
 * closed is not a question the lasso asks.
 */
@RunWith(AndroidJUnit4::class)
class LassoClosureTest {

    /** A short horizontal stroke around (90..110, 100). */
    private fun ink(id: String = "stroke") =
        PageStroke(id, InkCodec.eraseMask(inputs(90f to 100f, 110f to 100f), 6f))

    /** The same stroke, cut in two, so both kinds of projection are held to one rule. */
    private fun erasedInk(): List<PageStroke> = listOf(
        PageStroke("stroke", InkCodec.eraseMask(inputs(60f to 100f, 140f to 100f), 6f)),
    ).subtract(InkCodec.eraseMask(inputs(100f to 85f, 100f to 115f), 18f), listOf("stroke"))

    private fun select(strokes: List<PageStroke>, path: List<InkPoint>) =
        selectWithLasso(strokes = strokes, shapes = emptyList(), path = path)

    /**
     * An arc around the ink, [degrees] of the way round. 360 comes back to its first point exactly;
     * anything less leaves a mouth of that many degrees, however small.
     */
    private fun arc(degrees: Int, radius: Float = 70f): List<InkPoint> =
        (0..degrees step 5).map {
            val radians = Math.toRadians(it.toDouble())
            InkPoint(100f + radius * cos(radians).toFloat(), 100f + radius * sin(radians).toFloat())
        }

    /** Two legs and a lift: the triangle the app would close this into holds the ink. */
    private val elbow = listOf(InkPoint(40f, 40f), InkPoint(40f, 160f), InkPoint(220f, 160f))

    private val loop = listOf(
        InkPoint(40f, 40f), InkPoint(220f, 40f), InkPoint(220f, 160f), InkPoint(40f, 160f),
        InkPoint(40f, 40f),
    )

    @Test
    fun anElbowSelectsNothing() {
        assertNull(select(listOf(ink()), elbow))
    }

    @Test
    fun anElbowSelectsNoPieceOfErasedInkEither() {
        assertNull(select(erasedInk(), elbow))
    }

    @Test
    fun threeSidesOfARectangleSelectNothing() {
        assertNull(select(listOf(ink()), loop.dropLast(1)))
    }

    /** The one that matters most: nearly closed is not closed. */
    @Test
    fun aCLeftOpenByFiveDegreesSelectsNothing() {
        val almost = arc(355)

        assertNull("the mouth is 6dp wide and it is still a mouth", select(listOf(ink()), almost))
        assertNull(select(erasedInk(), almost))
    }

    @Test
    fun aCircleClosedOnItsOwnStartSelects() {
        assertEquals(setOf("stroke"), select(listOf(ink()), arc(360))?.inkIds)
        assertEquals(setOf("stroke"), select(erasedInk(), arc(360))?.inkIds)
    }

    @Test
    fun aClosedRectangleSelects() {
        assertEquals(setOf("stroke"), select(listOf(ink()), loop)?.inkIds)
        assertEquals(setOf("stroke"), select(erasedInk(), loop)?.inkIds)
    }

    /** Closing and then running on past the start is how a loop is usually drawn. */
    @Test
    fun aLoopThatCarriesOnPastItsStartSelects() {
        assertNotNull(select(listOf(ink()), loop + InkPoint(130f, 40f)))
        assertNotNull(select(listOf(ink()), arc(400)))
    }

    /** Crossing over the start rather than landing on it closes it just as well. */
    @Test
    fun aLoopThatCrossesItsOwnTailSelects() {
        // The last leg runs up through the first one at (70, 60) rather than landing on its start.
        val crossed = listOf(
            InkPoint(60f, 60f), InkPoint(220f, 60f), InkPoint(220f, 160f), InkPoint(40f, 160f),
            InkPoint(40f, 40f), InkPoint(100f, 80f),
        )

        assertNotNull(select(listOf(ink()), crossed))
    }


    // The same questions again, asked of paths sampled the way a hand leaves them.

    @Test
    fun aDenselyDrawnElbowSelectsNothing() {
        assertNull(select(listOf(ink()), asDrawn(elbow)))
    }

    @Test
    fun aDenselyDrawnCLeftOpenSelectsNothing() {
        assertNull(select(listOf(ink()), asDrawn(arc(355))))
        assertNull(select(erasedInk(), asDrawn(arc(355))))
    }

    @Test
    fun aDenselyDrawnOpenRectangleSelectsNothing() {
        assertNull(select(listOf(ink()), asDrawn(loop.dropLast(1))))
    }

    @Test
    fun aDenselyDrawnClosedLoopSelects() {
        assertEquals(setOf("stroke"), select(listOf(ink()), asDrawn(loop))?.inkIds)
        assertEquals(setOf("stroke"), select(listOf(ink()), asDrawn(arc(360)))?.inkIds)
        assertEquals(setOf("stroke"), select(erasedInk(), asDrawn(loop))?.inkIds)
    }

    @Test
    fun aDenselyDrawnLoopThatCarriesOnPastItsStartSelects() {
        assertNotNull(select(listOf(ink()), asDrawn(arc(400))))
    }

    /**
     * The same path as a hand would leave it: [LassoGesture] records a point every half page unit,
     * so a drawn loop is hundreds of samples and consecutive segments are a fraction of a unit apart.
     * A closure rule that behaves differently on four corners than on the eight hundred samples
     * between them is a rule that only works in a test.
     */
    private fun asDrawn(path: List<InkPoint>): List<InkPoint> {
        val dense = mutableListOf(path.first())
        path.zipWithNext().forEach { (from, to) ->
            val length = hypot(to.x - from.x, to.y - from.y)
            val steps = ceil(length / 0.5f).toInt().coerceAtLeast(1)
            (1..steps).forEach { step ->
                val fraction = step.toFloat() / steps
                dense += InkPoint(
                    from.x + (to.x - from.x) * fraction,
                    from.y + (to.y - from.y) * fraction,
                )
            }
        }
        return dense
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) ->
            add(InputToolType.UNKNOWN, x, y, index * 10L)
        }
    }.toImmutable()
}
