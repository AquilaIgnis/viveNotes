package com.vivenotes.model.ink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What hold-for-straight-line will and will not replace.
 *
 * Every case here is one the device cannot check for you. A wrong accept destroys a mark somebody
 * made and looks, on screen, exactly like a mark they made badly; a wrong refuse looks like nothing
 * at all. Both are invisible in a screenshot, which is why the whole judgement lives in a pure
 * function and is exercised here rather than by drawing on a tablet.
 *
 * The traces are synthesised rather than recorded, deliberately: a recorded trace proves the fit
 * accepts *that* line, while a generated one lets the wobble, the length and the angle be dialled up
 * until the threshold is found — which is what says where the boundary actually is.
 */
class StraightLineFitTest {

    /** Points along a straight line, with a sine wobble of [wobble] page units across it. */
    private fun line(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        samples: Int = 64,
        wobble: Float = 0f,
    ): FloatArray {
        val spanX = toX - fromX
        val spanY = toY - fromY
        val length = hypot(spanX, spanY)
        val acrossX = -spanY / length
        val acrossY = spanX / length
        val points = FloatArray(samples * 2)
        for (index in 0 until samples) {
            val t = index / (samples - 1f)
            // Two full periods, so the excursion is unmistakably a wobble rather than a bow.
            val offset = (wobble * sin(2.0 * PI * 2.0 * t)).toFloat()
            points[index * 2] = fromX + spanX * t + acrossX * offset
            points[index * 2 + 1] = fromY + spanY * t + acrossY * offset
        }
        return points
    }

    @Test
    fun aCleanDiagonalIsALine() {
        val fitted = StraightLineFit.of(line(100f, 100f, 400f, 260f))

        assertNotNull(fitted)
        assertEquals(100f, fitted!!.startX, 0.01f)
        assertEquals(100f, fitted.startY, 0.01f)
        assertEquals(400f, fitted.endX, 0.01f)
        assertEquals(260f, fitted.endY, 0.01f)
    }

    /** The ends are the pen's ends. Nothing may quietly extend or shorten what was drawn. */
    @Test
    fun theLineKeepsTheEndsTheStrokeHad() {
        val fitted = StraightLineFit.of(line(320f, 480f, 120f, 300f))!!

        assertEquals(320f, fitted.startX, 0.01f)
        assertEquals(480f, fitted.startY, 0.01f)
        assertEquals(120f, fitted.endX, 0.01f)
        assertEquals(300f, fitted.endY, 0.01f)
    }

    @Test
    fun anUnsteadyHandStillDrawsALine() {
        // 4 dp of wobble on a 300 dp line: well inside 3.5%, and about what writing at speed looks
        // like. Refusing this would mean the feature never fires for anyone.
        assertNotNull(StraightLineFit.of(line(100f, 100f, 400f, 100f, wobble = 4f)))
    }

    @Test
    fun aWobbleTooLargeToBeALineIsRefused() {
        // 30 dp across a 300 dp span is a wave, not a rule.
        assertNull(StraightLineFit.of(line(100f, 100f, 400f, 100f, wobble = 30f)))
    }

    @Test
    fun aShortTickIsNotALine() {
        // Under MIN_TRAVEL_DP: perfectly straight, and still not something anyone meant to rule.
        assertNull(StraightLineFit.of(line(100f, 100f, 118f, 100f)))
    }

    @Test
    fun aDotIsNotALine() {
        assertNull(StraightLineFit.of(floatArrayOf(100f, 100f, 100.2f, 100.1f, 100f, 100f)))
    }

    /**
     * The case a perpendicular test cannot see on its own: out and straight back sits *on* the line
     * between its ends, and its ends are practically the same point.
     */
    @Test
    fun aStrokeThatDoublesBackIsNotALine() {
        val out = line(100f, 100f, 400f, 100f, samples = 32)
        val back = line(400f, 100f, 104f, 100f, samples = 32)
        assertNull(StraightLineFit.of(out + back))
    }

    /** Halfway back is still back: the ends are far apart, so only the reversal itself gives it away. */
    @Test
    fun aStrokeThatTurnsRoundHalfwayIsNotALine() {
        val out = line(100f, 100f, 400f, 100f, samples = 32)
        val back = line(400f, 100f, 250f, 100f, samples = 16)
        assertNull(StraightLineFit.of(out + back))
    }

    @Test
    fun anArcIsNotALine() {
        val samples = 64
        val points = FloatArray(samples * 2)
        for (index in 0 until samples) {
            val angle = PI * index / (samples - 1)
            points[index * 2] = (300f + 150.0 * cos(angle)).toFloat()
            points[index * 2 + 1] = (300f + 150.0 * sin(angle)).toFloat()
        }
        assertNull(StraightLineFit.of(points))
    }

    /** An L drawn without lifting: mostly straight, and the part that is not is the whole point. */
    @Test
    fun anElbowIsNotALine() {
        val down = line(100f, 100f, 100f, 300f, samples = 32)
        val across = line(100f, 300f, 300f, 300f, samples = 32)
        assertNull(StraightLineFit.of(down + across))
    }

    @Test
    fun aNearlyHorizontalLineIsLevelled() {
        // 300 dp across and 12 dp down — about 2.3°, inside the snap.
        val fitted = StraightLineFit.of(line(100f, 200f, 400f, 212f))!!

        assertEquals(fitted.startY, fitted.endY, 0.01f)
        assertTrue("it should still run left to right", fitted.endX > fitted.startX)
    }

    @Test
    fun aNearlyVerticalLineIsPlumbed() {
        val fitted = StraightLineFit.of(line(200f, 100f, 188f, 400f))!!

        assertEquals(fitted.startX, fitted.endX, 0.01f)
        assertTrue("it should still run downwards", fitted.endY > fitted.startY)
    }

    /** Snapping rotates about the start, so the line is the length it was drawn at. */
    @Test
    fun levellingKeepsTheLengthTheStrokeHad() {
        val drawn = hypot(300f, 12f)
        val fitted = StraightLineFit.of(line(100f, 200f, 400f, 212f))!!

        assertEquals(drawn, hypot(fitted.endX - fitted.startX, fitted.endY - fitted.startY), 0.05f)
    }

    /**
     * The other half of the snap, and the half that matters more: a diagonal somebody meant stays
     * the diagonal they drew. Help that silently rewrites a deliberate angle is worse than none.
     */
    @Test
    fun aDeliberateDiagonalIsLeftAlone() {
        val fitted = StraightLineFit.of(line(100f, 100f, 400f, 250f))!!

        assertEquals(400f, fitted.endX, 0.01f)
        assertEquals(250f, fitted.endY, 0.01f)
    }

    /** Every quadrant, so the snap cannot be right in one direction and reversed in another. */
    @Test
    fun levellingPointsTheSameWayTheStrokeDid() {
        val cases = listOf(
            floatArrayOf(400f, 208f) to floatArrayOf(1f, 0f),
            floatArrayOf(-200f, 208f) to floatArrayOf(-1f, 0f),
            floatArrayOf(208f, 400f) to floatArrayOf(0f, 1f),
            floatArrayOf(208f, -200f) to floatArrayOf(0f, -1f),
        )
        cases.forEach { (end, direction) ->
            val fitted = StraightLineFit.of(line(200f, 200f, end[0], end[1]))!!
            val runX = fitted.endX - fitted.startX
            val runY = fitted.endY - fitted.startY
            assertTrue(
                "a stroke towards (${direction[0]}, ${direction[1]}) became ($runX, $runY)",
                runX * direction[0] + runY * direction[1] > 0f,
            )
            // The off-axis component is gone entirely, not merely reduced.
            assertEquals(0f, runX * direction[1] - runY * direction[0], 0.01f)
        }
    }

    /** Digitiser noise is not a wobble: a jittery device must not stop the feature working. */
    @Test
    fun perSampleJitterDoesNotRefuseALine() {
        val random = Random(20260819)
        val points = line(100f, 400f, 500f, 400f, samples = 200)
        for (index in points.indices) {
            points[index] += (random.nextFloat() - 0.5f) * 1.2f
        }
        assertNotNull(StraightLineFit.of(points))
    }

    @Test
    fun malformedInputIsRefusedRatherThanCrashing() {
        assertNull(StraightLineFit.of(FloatArray(0)))
        assertNull(StraightLineFit.of(floatArrayOf(1f, 2f)))
        assertNull(StraightLineFit.of(floatArrayOf(1f, 2f, 3f)))
    }
}
