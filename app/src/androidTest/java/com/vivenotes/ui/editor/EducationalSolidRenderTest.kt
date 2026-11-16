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
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class EducationalSolidRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun filledSolidsLeaveTheirOpeningsTransparentOnTheCanvas() {
        val paper = Color(0xFFEDE3D5)
        val fill = Color(0xFF55AACB)
        val kinds = listOf(ShapeKind.HollowCylinder, ShapeKind.Hemisphere, ShapeKind.Frustum)
        var id = 0
        val shapes = kinds.mapIndexed { index, kind ->
            val x = 20f + index % 2 * 280f
            val y = 20f + index / 2 * 220f
            Outline.Shape(
                id = kind.name, kind = kind,
                segments = seedSegments(kind, x, y, x + 240f, y + 180f) { "edge-${id++}" },
                fillArgb = 0xFF55AACB.toInt(), borderWidth = 1.5f,
            ).withRecomputedBounds()
        }
        compose.setContent {
            ViveNotesTheme {
                Box(Modifier.size(560.dp, 440.dp).background(paper).testTag("solid-gallery")) {
                    ShapeLayer(
                        shapes = shapes, selection = null, interactive = false,
                        visibleWindow = { Rect(0f, 0f, Float.MAX_VALUE, Float.MAX_VALUE) },
                        onSelect = {}, onMoveShape = { _, _, _ -> },
                    )
                }
            }
        }
        val captured = compose.onNodeWithTag("solid-gallery").captureToImage()
        // Keep the rendered gallery available for visual review alongside the pixel assertions.
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "educational-solids.png")
            .outputStream().use { captured.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val pixels = captured.toPixelMap()
        val density = compose.density.density
        fun pixel(x: Float, y: Float) = pixels[(x * density).roundToInt(), (y * density).roundToInt()]
        assertEquals("tube bore is filled", paper, pixel(140f, 53.6f))
        assertEquals("tube annulus is missing", fill, pixel(245f, 53.6f))
        assertEquals("hemisphere dome is missing", fill, pixel(420f, 60f))
        assertEquals("frustum body is missing", fill, pixel(140f, 330f))
    }
}
