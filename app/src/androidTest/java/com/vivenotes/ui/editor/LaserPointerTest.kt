package com.vivenotes.ui.editor

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The laser's trace drawn straight onto a bitmap, with the clock it is stamped and drawn on held
 * by the test rather than read from the device.
 *
 * `InkOverlayTest` drives the same pointer through injected touches. There, how far apart two
 * samples land depends on how busy the device is, so the case below came up only in full suite runs.
 */
@RunWith(AndroidJUnit4::class)
class LaserPointerTest {

    /**
     * A down and a move handled within one millisecond still draw the line between them. Stamped
     * alike, the two are one instant to Ink's sliding window, and the live trace was modelled at
     * the pen alone, so nothing showed between the samples until a later one arrived.
     */
    @Test
    fun aMoveInTheSameMillisecondAsTheDownStillDrawsBetweenThem() {
        assumeTrue("with animations removed there is no trace to draw", ValueAnimator.areAnimatorsEnabled())
        assertTrue("nothing was drawn between the two samples", inkBetween(gapMillis = 0) > 0)
        assertTrue("the control case drew nothing either", inkBetween(gapMillis = 16) > 0)
    }

    /** Ink pixels halfway along a live trace from (200, 400) to (400, 400), [gapMillis] apart. */
    private fun inkBetween(gapMillis: Long): Int {
        var count = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val laser = LaserPointer()
            val identity = Matrix()
            val start = 10_000L
            val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 200f, 400f, 0)
            val move = MotionEvent.obtain(start, start + gapMillis, MotionEvent.ACTION_MOVE, 400f, 400f, 0)
            try {
                laser.handle(down, identity, TRACE_WIDTH, now = start)
                laser.handle(move, identity, TRACE_WIDTH, now = start + gapMillis)
            } finally {
                down.recycle()
                move.recycle()
            }

            // A software canvas, which draws the blade without its glows: what is counted is the
            // trace itself. The dot sits at the pen, 100 px from the window counted here.
            val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            laser.draw(
                Canvas(bitmap),
                identity,
                start + gapMillis + FRAME_MILLIS,
                LaserPaint(density = 2f),
                CanvasStrokeRenderer.create(),
            )
            for (x in 290 until 310) {
                for (y in 390 until 410) {
                    if (bitmap.getPixel(x, y) != Color.WHITE) count++
                }
            }
            bitmap.recycle()
        }
        return count
    }

    private companion object {
        const val TRACE_WIDTH = 12f
        const val FRAME_MILLIS = 16L
    }
}
