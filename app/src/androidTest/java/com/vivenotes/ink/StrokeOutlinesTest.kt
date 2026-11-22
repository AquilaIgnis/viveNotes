package com.vivenotes.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.data.PenPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Erased ink keeps drawing where the renderer draws from outlines: on the screen before Android 14,
 * and in PDF export everywhere. See [StrokeOutlines].
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalInkEraserApi::class)
class StrokeOutlinesTest {

    private val pen = PenPreset(colorArgb = 0xFF111111.toInt(), colorFollowsTheme = false).copy(thickness = 8f)
    private val original = Stroke(InkCodec.brushFor(pen), inputs(20f to 50f, 100f to 50f, 180f to 50f))
    private val mask = InkCodec.eraseMask(inputs(100f to 20f, 100f to 80f), sizeDp = 24f)
    private val cut = original.subtract(mask.shape, AffineTransform.IDENTITY, AffineTransform.IDENTITY)
    private val pieces = listOf(PageStroke("s", original)).subtract(mask, listOf("s"))

    @Test
    fun theCopyOfAPieceHasOutlinesAndExactlyThePiecesShape() {
        assertEquals(2, pieces.size)
        pieces.map(PageStroke::stroke).forEach { piece ->
            val copy = outlinedCopy(piece)
            assertTrue("the copy has outlines to draw from", copy.hasOutlines())
            assertTrue("the copy covers the piece", copy.shape.computeCoverage(piece.shape) > 0.9999f)
            assertTrue("the piece covers the copy", piece.shape.computeCoverage(copy.shape) > 0.9999f)
            assertEquals(piece.shape.computeBoundingBox(), copy.shape.computeBoundingBox())
            assertEquals(piece.brush, copy.brush)
        }
    }

    /**
     * The copy is for drawing only. A piece keeps no outlines, so lasso and replay go on asking the
     * same question of it on every device; see the note on [StrokeOutlines].
     */
    @Test
    fun theGeometryStillHoldsThePiecesAsSplitReturnsThem() {
        pieces.forEach { assertFalse("a piece the geometry holds gained outlines", it.stroke.hasOutlines()) }
    }

    @Test
    fun aCopyIsMadeOnceAndAStrokeWithOutlinesIsItsOwn() {
        val piece = pieces.first().stroke
        val first = StrokeOutlines.drawable(piece)
        assertSame(first, StrokeOutlines.drawable(piece))
        assertSame(original, StrokeOutlines.drawable(original))
    }

    /** Both pieces together draw what the cut they came from draws: the erase is all that went. */
    @Test
    fun copiesDrawAsMuchInkAsTheCutTheyCameFrom() {
        val renderer = CanvasStrokeRenderer.create()
        val whole = inkOnHardwareCanvas { renderer.draw(it, cut, SCALE) }
        val copies = inkOnHardwareCanvas { canvas ->
            pieces.forEach { renderer.draw(canvas, StrokeOutlines.drawable(it.stroke), SCALE) }
        }
        assertTrue("the cut drew nothing to compare against", whole > 0)
        assertClose(whole, copies)
    }

    /** What the editor draws, on whatever renderer this device has. */
    @Test
    fun theEditorDrawsErasedInkOnThisDevice() {
        val renderer = CanvasStrokeRenderer.create()
        val painter = CanvasInkPainter(canvasInkArgb = Color.BLACK)
        val whole = inkOnHardwareCanvas { renderer.draw(it, cut, SCALE) }
        val painted = inkOnHardwareCanvas { canvas -> pieces.forEach { renderer.draw(canvas, painter.paint(it), SCALE) } }
        assertClose(whole, painted)
    }

    /** A pen stroke crossed out fifty times, which is what a page of corrections builds up to. */
    @Test
    fun copiesOfFiftyPiecesAreCheapAndAllExact() {
        var long = Stroke(InkCodec.brushFor(pen), inputs(*(0..50).map { (it * 20f) to 50f }.toTypedArray()))
        (1..50).forEach { index ->
            val x = index * 20f - 10f
            long = long.subtract(
                InkCodec.eraseMask(inputs(x to 30f, x to 70f), sizeDp = 6f).shape,
                AffineTransform.IDENTITY,
                AffineTransform.IDENTITY,
            )
        }
        val pieces = long.split(AffineTransform.IDENTITY, 0f).toList()
        val started = SystemClock.elapsedRealtime()
        val copies = pieces.map(::outlinedCopy)
        val elapsed = SystemClock.elapsedRealtime() - started

        assertEquals(51, pieces.size)
        assertTrue("every copy has outlines", copies.all { it.hasOutlines() })
        assertTrue(
            "every copy has its piece's shape",
            copies.zip(pieces).all { (copy, piece) ->
                copy.shape.computeCoverage(piece.shape) > 0.9999f && piece.shape.computeCoverage(copy.shape) > 0.9999f
            },
        )
        assertTrue("51 copies took $elapsed ms", elapsed < 500)
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun assertClose(expected: Int, actual: Int) {
        assertTrue("expected about $expected ink pixels, got $actual", abs(expected - actual) <= expected / 50)
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }.toImmutable()

    /**
     * Dark pixels [draw] leaves on a hardware-accelerated canvas, which is the canvas the editor
     * draws on, so ink picks the same renderer it picks on screen.
     */
    private fun inkOnHardwareCanvas(draw: (Canvas) -> Unit): Int {
        val node = RenderNode("ink").apply { setPosition(0, 0, WIDTH, HEIGHT) }
        val canvas = node.beginRecording(WIDTH, HEIGHT)
        assertTrue("not the canvas the editor draws on", canvas.isHardwareAccelerated)
        canvas.drawColor(Color.WHITE)
        draw(canvas)
        node.endRecording()
        val reader = ImageReader.newInstance(
            WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var image = reader.acquireLatestImage()
            var waited = 0
            while (image == null && waited++ < 50) {
                SystemClock.sleep(20)
                image = reader.acquireLatestImage()
            }
            assertNotNull("nothing was rendered", image)
            image!!.use {
                requireNotNull(it.hardwareBuffer).use { buffer ->
                    val wrapped = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                    val bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped.recycle()
                    val pixels = IntArray(WIDTH * HEIGHT)
                    bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    bitmap.recycle()
                    return pixels.count { pixel -> Color.red(pixel) < 128 }
                }
            }
        } finally {
            renderer.destroy()
            reader.close()
            node.discardDisplayList()
        }
    }

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 400
        val SCALE = Matrix().apply { setScale(4f, 4f) }
    }
}
