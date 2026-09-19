package com.vivenotes.ui.editor

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasConstraintsTest {

    @Test
    fun `five percent zoom remains representable on a dense tablet`() {
        val density = Density(2f)
        val zoom = 0.05f
        val size = with(density) {
            limitEmptyCanvas(
                requiredSize = DpSize(1_200.dp, 1_200.dp),
                requestedSize = DpSize(25_600.dp, 20_000.dp),
                zoom = zoom,
            )
        }

        assertBothMeasurementStagesAreRepresentable(density, size, zoom)
    }

    @Test
    fun `five percent zoom preserves a naturally narrow long page`() {
        val density = Density(2f)
        val zoom = 0.05f
        val size = with(density) {
            limitEmptyCanvas(
                requiredSize = DpSize(1_200.dp, 40_000.dp),
                requestedSize = DpSize(25_600.dp, 40_000.dp),
                zoom = zoom,
            )
        }

        assertEquals(true, size.width >= 1_200.dp)
        assertEquals(40_000f, size.height.value, 0f)
        assertBothMeasurementStagesAreRepresentable(density, size, zoom)
    }

    @Test
    fun `empty-area limit also preserves a long horizontal page`() {
        val density = Density(2f)
        val zoom = 0.05f
        val size = with(density) {
            limitEmptyCanvas(
                requiredSize = DpSize(40_000.dp, 1_200.dp),
                requestedSize = DpSize(40_000.dp, 25_600.dp),
                zoom = zoom,
            )
        }

        assertEquals(40_000f, size.width.value, 0f)
        assertEquals(true, size.height >= 1_200.dp)
        assertBothMeasurementStagesAreRepresentable(density, size, zoom)
    }

    @Test
    fun `maximum zoom remains representable on a dense tablet`() {
        val density = Density(2f)
        val zoom = 4f
        val size = with(density) {
            limitEmptyCanvas(
                requiredSize = DpSize(1_200.dp, 1_200.dp),
                requestedSize = DpSize(25_600.dp, 20_000.dp),
                zoom = zoom,
            )
        }

        assertBothMeasurementStagesAreRepresentable(density, size, zoom)
    }

    @Test
    fun `wide and tall document content is never clamped to the empty-area budget`() {
        val density = Density(2f)
        val required = DpSize(8_061.dp, 19_285.dp)
        val size = with(density) {
            limitEmptyCanvas(
                requiredSize = required,
                requestedSize = DpSize(required.width + 200.dp, required.height + 320.dp),
                zoom = 0.05f,
            )
        }

        assertEquals(required, size)
    }

    /** Constructing these is the regression assertion: Constraints throws when the pair cannot pack. */
    private fun assertBothMeasurementStagesAreRepresentable(
        density: Density,
        size: DpSize,
        zoom: Float,
    ) {
        val widthPx = with(density) { size.width.roundToPx() }
        val heightPx = with(density) { size.height.roundToPx() }
        Constraints.fixed(widthPx, heightPx)
        Constraints.fixed((widthPx * zoom).roundToInt(), (heightPx * zoom).roundToInt())
    }
}
