package com.vivenotes.model.ocr

import com.vivenotes.ai.ImageTextLine
import com.vivenotes.ai.ImageTextResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImageTextLayoutTest {

    private val tolerance = 1f / 65_535f

    private fun line(vararg edges: Float) = ImageLineLayout(
        corners = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.3f, 0.1f, 0.3f),
        edges = edges,
    )

    @Test
    fun aLayoutComesBackAsItWentIn() {
        val written = listOf(line(0f, 0.25f, 0.5f, 1f), line(0f, 1f))

        val read = ImageTextLayout.decode(ImageTextLayout.encode(written))!!

        assertEquals(2, read.size)
        read.zip(written).forEach { (got, want) ->
            assertArrayEquals(want.corners, got.corners, tolerance)
            assertArrayEquals(want.edges, got.edges, tolerance)
        }
    }

    @Test
    fun anEmptyLayoutIsValid() {
        assertEquals(emptyList<ImageLineLayout>(), ImageTextLayout.decode(ImageTextLayout.encode(emptyList())))
    }

    @Test
    fun valuesOutsideThePictureAreClampedToIt() {
        // A detected box can reach past the edge of the picture.
        val written = ImageLineLayout(
            corners = floatArrayOf(-0.1f, -0.2f, 1.3f, 0f, 1.1f, 1.5f, 0f, 1f),
            edges = floatArrayOf(-1f, 2f),
        )

        val read = ImageTextLayout.decode(ImageTextLayout.encode(listOf(written)))!!.single()

        read.corners.forEach { assert(it in 0f..1f) { "corner value $it is off the picture" } }
        assertArrayEquals(floatArrayOf(0f, 1f), read.edges, tolerance)
    }

    @Test
    fun aBlobThatStopsShortIsRefusedNotCrashedOn() {
        val bytes = ImageTextLayout.encode(listOf(line(0f, 0.5f, 1f)))

        assertNull(ImageTextLayout.decode(bytes.copyOf(bytes.size - 2)))
        assertNull(ImageTextLayout.decode(ByteArray(0)))
    }

    @Test
    fun aFormatFromSomeOtherBuildIsRefused() {
        val bytes = ImageTextLayout.encode(listOf(line(0f, 1f)))
        bytes[0] = 99

        assertNull(ImageTextLayout.decode(bytes))
    }

    @Test
    fun aScreenshotCostsAFewKilobytes() {
        val forty = List(40) { line(*FloatArray(61) { index -> index / 60f }) }

        val size = ImageTextLayout.encode(forty).size

        assert(size < 8_000) { "$size bytes for a 40-line screenshot" }
    }

    @Test
    fun theLargestPossibleRowIsNowhereNearTheCursorWindow() {
        // The recognizer's widest tensor gives 400 columns, and the detector stops at 400 lines.
        val ceiling = List(400) { line(*FloatArray(401) { index -> index / 400f }) }

        val bytes = ImageTextLayout.encode(ceiling)

        assert(bytes.size < 512_000) { "${bytes.size} bytes at the ceiling" }
        assertNotNull(ImageTextLayout.decode(bytes))
    }

    @Test
    fun pixelCornersBecomeFractionsOfThePicture() {
        val result = ImageTextResult(
            lines = listOf(
                ImageTextLine(
                    text = "hi",
                    confidence = 0.9f,
                    corners = listOf(
                        TextDetection.Point(100f, 50f),
                        TextDetection.Point(300f, 50f),
                        TextDetection.Point(300f, 75f),
                        TextDetection.Point(100f, 75f),
                    ),
                    edges = listOf(0f, 0.5f, 1f),
                ),
            ),
            meanConfidence = 0.9f,
        )

        val read = ImageTextLayout.decode(result.layout(1000, 500)!!)!!.single()

        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.3f, 0.1f, 0.3f, 0.15f, 0.1f, 0.15f), read.corners, 0.0001f)
    }

    @Test
    fun aLineWrittenDownThePageIsLaidOutAlongItsOwnLength() {
        // Tall and thin: the recognizer read it down the long side, so the layout has to run that way.
        val result = ImageTextResult(
            lines = listOf(
                ImageTextLine(
                    text = "v",
                    confidence = 0.9f,
                    corners = listOf(
                        TextDetection.Point(0f, 0f),
                        TextDetection.Point(10f, 0f),
                        TextDetection.Point(10f, 100f),
                        TextDetection.Point(0f, 100f),
                    ),
                    edges = listOf(0f, 1f),
                ),
            ),
            meanConfidence = 0.9f,
        )

        val read = ImageTextLayout.decode(result.layout(100, 100)!!)!!.single()

        assertArrayEquals(floatArrayOf(0.1f, 0f, 0.1f, 1f, 0f, 1f, 0f, 0f), read.corners, 0.0001f)
    }

    @Test
    fun aResultThatDoesNotSayWhereItsLinesAreHasNoLayout() {
        val unplaced = ImageTextResult(listOf(ImageTextLine("read", 0.9f, emptyList())), 0.9f)

        assertNull(unplaced.layout(100, 100))
        assertNull(ImageTextResult.Empty.layout(100, 100))
    }
}
