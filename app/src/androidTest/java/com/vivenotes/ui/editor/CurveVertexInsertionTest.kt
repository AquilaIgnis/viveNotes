package com.vivenotes.ui.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.ink.withAddedVertex
import com.vivenotes.model.ink.withRemovedVertex
import com.vivenotes.ui.theme.ViveNotesTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CurveVertexInsertionTest {
    @get:Rule val compose = createComposeRule()
    private var inserted: String? = null
    private var dismissed = 0
    private val curve: Outline.Shape = run {
        var next = 0
        Outline.Shape(id = "curve", kind = ShapeKind.Curve,
            segments = seedSegments(ShapeKind.Curve, 40f, 100f, 280f, 260f) { "s-${next++}" },
        ).withRecomputedBounds()
    }

    private fun setOverlay(
        transformed: Boolean = false,
        insertOnClick: Boolean = false,
        mode: CurveVertexMode = CurveVertexMode.Add,
        removeOnClick: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            var displayedCurve by remember { mutableStateOf(curve) }
            var visible by remember { mutableStateOf(true) }
            var displayedMode by remember { mutableStateOf(mode) }
            ViveNotesTheme {
                Box(Modifier.size(500.dp, 400.dp).background(Color.White).testTag("curve-ghost-gallery")) {
                    if (!transformed) {
                        ShapeLayer(
                            shapes = listOf(displayedCurve), selection = CanvasSelection.ofShape(displayedCurve), interactive = false,
                            visibleWindow = { Rect(0f, 0f, Float.MAX_VALUE, Float.MAX_VALUE) },
                            onSelect = {}, onMoveShape = { _, _, _ -> },
                        )
                    }
                    CurveVertexInsertionOverlay(
                        curve = displayedCurve, visible = visible,
                        mode = displayedMode,
                        pageToView = {
                            Matrix().apply {
                                val scale = if (transformed) 2f else 1f
                                setScale(density * scale, density * scale)
                                if (transformed) postTranslate(-40f * density, -60f * density)
                            }
                        },
                        onInsert = { segmentId ->
                            inserted = segmentId
                            if (insertOnClick) {
                                displayedCurve = displayedCurve.withAddedVertex(segmentId) { "inserted" }
                                visible = false
                            }
                        },
                        onDismiss = { dismissed++ },
                        onRemove = { segmentId, atEnd ->
                            if (removeOnClick) {
                                displayedCurve = displayedCurve.withRemovedVertex(segmentId, atEnd)
                                visible = false
                                displayedMode = CurveVertexMode.Add
                            }
                        },
                    )
                }
            }
        }
    }

    @Test
    fun ghostsHaveAccessibleLabelsAndFullSizeTouchTargets() {
        setOverlay()
        for (index in 0..1) {
            compose.onNodeWithTag(curveInsertionTag(index)).assertIsDisplayed()
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                .assertContentDescriptionEquals("Add vertex between points ${index + 1} and ${index + 2}")
        }
        val captured = compose.onNodeWithTag("curve-ghost-gallery").captureToImage()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "curve-ghost-points.png")
            .outputStream().use { captured.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun ghostTargetsFollowZoomAndScrollWhileKeepingTheirTouchSize() {
        setOverlay(transformed = true)
        val root = compose.onNodeWithTag("curve-ghost-gallery").getUnclippedBoundsInRoot()
        // The path midpoints (100, 140) and (220, 140), zoomed by two then scrolled by (40, 60).
        for ((index, x) in listOf(160f, 400f).withIndex()) {
            val node = compose.onNodeWithTag(curveInsertionTag(index))
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            val bounds = node.getUnclippedBoundsInRoot()
            assertEquals(x, (bounds.left.value + bounds.right.value) / 2f - root.left.value, 0.5f)
            assertEquals(220f, (bounds.top.value + bounds.bottom.value) / 2f - root.top.value, 0.5f)
        }
        compose.onNodeWithTag(curveInsertionTag(1)).performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(curve.segments.last().id, inserted)
        assertEquals("Tapping a ghost was mistaken for a dismissal", 0, dismissed)
    }

    @Test
    fun insertionFadesTheExistingGhostsWithoutFlashingNewChoices() {
        setOverlay(insertOnClick = true)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val before = compose.onNodeWithTag(curveInsertionTag(0)).getUnclippedBoundsInRoot()
        compose.onNodeWithTag(curveInsertionTag(0)).performTouchInput { click(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(curveInsertionTag(2)).assertDoesNotExist()
        val fading = compose.onNodeWithTag(curveInsertionTag(0)).assertIsNotEnabled()
            .getUnclippedBoundsInRoot()
        assertEquals(before, fading)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
    }

    @Test
    fun removingAPointFadesTheOldTargetsWithoutFlashingAddMode() {
        setOverlay(mode = CurveVertexMode.Remove, removeOnClick = true)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val before = compose.onNodeWithTag(curveRemovalTag(1)).getUnclippedBoundsInRoot()
        compose.onNodeWithTag(curveRemovalTag(1)).performTouchInput { click(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
        val fading = compose.onNodeWithTag(curveRemovalTag(1)).assertIsNotEnabled()
            .getUnclippedBoundsInRoot()
        assertEquals(before, fading)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag(CURVE_REMOVAL_OVERLAY_TAG).assertDoesNotExist()
    }
}
