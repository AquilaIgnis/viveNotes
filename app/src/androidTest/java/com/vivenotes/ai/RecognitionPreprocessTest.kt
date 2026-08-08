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
        assertTrue(tensor[192 * 384 + 192] > tensor.first())
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
