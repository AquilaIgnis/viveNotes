package com.vivenotes.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxRecognitionSmokeTest {
    @Test
    fun bundledOcrGraphRunsInsideAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = AiModelStore(context)
        withTimeout(10_000) {
            models.state.first { it.handwritingText !is AiModelInstallState.Verifying }
        }
        assertTrue(models.state.value.handwritingText == AiModelInstallState.Installed)

        val bitmap = Bitmap.createBitmap(320, 64, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                "hello",
                16f,
                48f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 44f
                },
            )
        }
        val engine = OnnxInkRecognitionEngine(models)
        try {
            val result = withTimeout(30_000) { engine.recognizeText(bitmap) }
            assertTrue("OCR returned no text", result.text.isNotBlank())
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }

    /** A clean debug install must hydrate and run its bundled FormulaNet package without a download. */
    @Test
    fun debugBundledFormulaGraphRunsInsideAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = AiModelStore(context)
        withTimeout(60_000) {
            models.state.first { it.formulaLatex !is AiModelInstallState.Verifying }
        }
        assertEquals(AiModelInstallState.Installed, models.state.value.formulaLatex)

        val bitmap = Bitmap.createBitmap(900, 180, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                "x² + y² = z²",
                24f,
                130f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 112f
                },
            )
        }
        val engine = OnnxInkRecognitionEngine(models)
        try {
            val result = withTimeout(60_000) { engine.recognizeFormula(bitmap) }
            assertTrue("Formula recognition returned no LaTeX", result.latex.isNotBlank())
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }
}
