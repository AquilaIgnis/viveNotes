package com.vivenotes.richtext

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the shipped native parser handles the feature's representative equation. */
@RunWith(AndroidJUnit4::class)
class EquationRenderingTest {

    @Test
    fun rendersTheFundamentalTheoremIntegral() = runBlocking {
        val renderer = createEquationRenderer(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            latex = "{\\displaystyle \\int _{a}^{b}f'(t)\\,dt=f(b)-f(a)}",
            fontSizePx = 30f,
            color = Color.BLACK,
        )

        assertTrue(renderer.widthPx > 0f)
        assertTrue(renderer.totalHeightPx > 0f)
    }
}
