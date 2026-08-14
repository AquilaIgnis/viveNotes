package com.vivenotes.ai

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecognitionPreprocessTest {
    @Test
    fun textInputUsesPaddleShapeAndBgrNormalization() {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.BLACK)
            setPixel(1, 0, Color.WHITE)
        }

        val tensor = preprocessText(bitmap)

        assertEquals(320, tensor.width)
        assertEquals(3 * 48 * 320, tensor.values.size)
        assertTrue(tensor.values.first() <= -0.99f)
    }

    /**
     * A padded line keeps its rows aligned — the defect described on `preprocessText`.
     *
     * A vertical black bar down the left of the crop must stay vertical in the tensor. Walking the
     * pixels with one running index shifts each row by `width - resizedWidth`, so by row 48 the bar
     * has walked thousands of pixels to the right and the line is unreadable. The model gives no
     * sign of this beyond returning worse text, which is why it survived from the day recognition
     * shipped until a picture with three lines on it read only the widest one.
     */
    @Test
    fun aPaddedLineIsNotShearedRowByRow() {
        // 96 x 48 is 2:1, well under the 320-wide tensor, so every row is padded.
        val bitmap = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 0 until 48) for (x in 0 until 8) setPixel(x, y, Color.BLACK)
        }

        val tensor = preprocessText(bitmap)

        assertEquals(320, tensor.width)
        for (y in 0 until 48) {
            val row = y * tensor.width
            assertTrue("row $y lost the bar at its left edge", tensor.values[row] <= -0.99f)
            // The bar is eight pixels wide and nothing else is dark, so column 40 is paper on every
            // row. Under the shear it was ink on most of them.
            assertTrue("row $y has ink where the page should be", tensor.values[row + 40] >= 0.99f)
            // Everything past the picture is padding, and padding is zero.
            assertEquals(0f, tensor.values[row + 200], 1e-6f)
        }
    }

    @Test
    fun formulaInputIsCroppedAndPaddedTo384Square() {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 30 until 90) {
                setPixel(x, 20, Color.BLACK)
                setPixel(x, 39, Color.BLACK)
            }
            for (y in 20 until 40) {
                setPixel(30, y, Color.BLACK)
                setPixel(89, y, Color.BLACK)
            }
        }

        val tensor = preprocessFormula(bitmap)

        assertEquals(384 * 384, tensor.size)
        assertTrue(tensor.all(Float::isFinite))

        // The square is padded with paper, not with black. This used to assert that the middle was
        // *brighter* than the corner, which only held because the corner was the darkest value in
        // the tensor — the whole defect. The corner is now the same paper the crop sits on, so what
        // is worth asserting is that the padding is paper and that the ink survived the resize.
        val paper = (1f - 0.7931f) / 0.1738f
        assertEquals(paper, tensor.first(), 1e-4f)
        assertEquals(paper, tensor.last(), 1e-4f)
        assertTrue("no ink survived the crop and resize", tensor.min() < paper - 1f)

        // A 60x20 rectangle is 3:1, so it fits to 384x128 and is centred: the middle row crosses
        // both of its uprights, and the rows a third of the way down the frame are still padding.
        val middleRow = tensor.asList().subList(192 * 384, 193 * 384)
        assertTrue("the crop did not land in the middle of the frame", middleRow.min()!! < paper - 1f)
        assertEquals(paper, tensor[20 * 384 + 192], 1e-4f)
    }

    @Test
    fun byteLevelTokenizerDecodesOnlyRequestedFormulaTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokenizer = File(context.cacheDir, "minimal-formula-tokenizer.json")
        tokenizer.writeText(
            """{"added_tokens":[{"id":0,"special":true},{"id":2,"special":true}],"model":{"vocab":{"<s>":0,"</s>":2,"Ġx":4}}}""",
        )

        val decoded = FormulaTokenizerDecoder.decode(tokenizer, listOf(0, 4, 2))

        assertEquals("x", decoded)
    }
}
