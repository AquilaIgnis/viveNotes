package com.vivenotes.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.ink.withVertex
import com.vivenotes.ui.theme.ViveNotesTheme
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CurveRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun stretchedCurvesRenderRoundedSides() {
        var id = 0
        fun curve(left: Float, top: Float, right: Float, bottom: Float) = Outline.Shape(
            id = "curve-${id++}", kind = ShapeKind.Curve, borderWidth = 3f,
            borderArgb = 0xFF182B49.toInt(), borderFollowsTheme = false,
            segments = seedSegments(ShapeKind.Curve, left, top, right, bottom) { "s-${id++}" },
        ).withRecomputedBounds()
        val original = curve(40f, 40f, 160f, 100f)
        val wide = curve(290f, 40f, 350f, 100f)
            .withVertex(0, 220f, 100f).withVertex(2, 420f, 100f)
        val tall = curve(40f, 220f, 160f, 280f)
            .withVertex(0, 40f, 440f).withVertex(2, 160f, 440f)
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.size(460.dp, 480.dp).background(Color.White).testTag("curve-gallery")) {
                    ShapeLayer(
                        shapes = listOf(original, wide, tall), selection = null, interactive = false,
                        visibleWindow = { Rect(0f, 0f, Float.MAX_VALUE, Float.MAX_VALUE) },
                        onSelect = {}, onMoveShape = { _, _, _ -> },
                    )
                }
            }
        }
        val captured = compose.onNodeWithTag("curve-gallery").captureToImage()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "stretched-curves.png")
            .outputStream().use { captured.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val pixels = captured.toPixelMap()
        val density = compose.density.density
        // Halfway along each side the curve must bow above the triangle's chord. These locations
        // are well away from where the old fixed handles rendered the almost straight sides.
        fun assertInk(x: Float, y: Float) {
            val pixel = pixels[(x * density).roundToInt(), (y * density).roundToInt()]
            assertTrue("Curve is missing its rounded side at ($x, $y)", pixel.red < 0.4f)
        }
        assertInk(270f, 55f)
        assertInk(370f, 55f)
        assertInk(70f, 275f)
        assertInk(130f, 275f)
    }
}
