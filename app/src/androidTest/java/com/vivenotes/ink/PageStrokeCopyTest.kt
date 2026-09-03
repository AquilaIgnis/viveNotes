package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.data.PenPreset
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageStrokeCopyTest {

    @Test
    fun aPasteDropsSamplesThatStorageQuantizedToTheSamePositionAndTime() {
        val sourceInputs = MutableStrokeInputBatch().apply {
            add(InputToolType.STYLUS, 741f, 2099f, 0L, pressure = 0.2f)
            add(InputToolType.STYLUS, 742.0588f, 2108.933f, 55L, pressure = 0.7f)
            // Valid before storage: stationary samples may have distinct elapsed times.
            add(InputToolType.STYLUS, 742.0588f, 2108.933f, 56L, pressure = 0.8f)
            add(InputToolType.STYLUS, 751f, 2124f, 100L, pressure = 0.9f)
            setNoiseSeed(73)
        }.toImmutable()
        assertNotEquals(sourceInputs[1].elapsedTimeMillis, sourceInputs[2].elapsedTimeMillis)

        val encoded = ByteArrayOutputStream().use { output ->
            sourceInputs.encode(output)
            output.toByteArray()
        }
        val reloaded = ByteArrayInputStream(encoded).use(StrokeInputBatch::decode)
        assertEquals(reloaded[1].x, reloaded[2].x, 0f)
        assertEquals(reloaded[1].y, reloaded[2].y, 0f)
        assertEquals(reloaded[1].elapsedTimeMillis, reloaded[2].elapsedTimeMillis)
        val source = PageStroke(
            id = "stroke",
            stroke = Stroke(InkCodec.brushFor(PenPreset()), reloaded),
        )

        val copied = source.translatedCopy(dx = 12f, dy = 34f)

        assertEquals(reloaded.size - 1, copied.inputs.size)
        assertEquals(55L, copied.inputs[1].elapsedTimeMillis)
        assertEquals(reloaded[1].pressure, copied.inputs[1].pressure, 0f)
        assertEquals(sourceInputs.getNoiseSeed(), copied.inputs.getNoiseSeed())
    }

    @Test
    fun aPasteDropsSamplesThatCollapseToTheSamePositionAndTime() {
        val sourceInputs = MutableStrokeInputBatch().apply {
            add(InputToolType.STYLUS, 0f, 0f, 55L, pressure = 0.2f)
            add(InputToolType.STYLUS, 1f, 0f, 55L, pressure = 0.7f)
            add(InputToolType.STYLUS, 2f, 0f, 56L, pressure = 0.9f)
            setNoiseSeed(73)
        }.toImmutable()
        val source = PageStroke(
            id = "stroke",
            stroke = Stroke(InkCodec.brushFor(PenPreset()), sourceInputs),
            // At a page-sized offset, this composed scale is below one float unit of precision.
            scaleX = 0.000001f,
            scaleY = 0.000001f,
            offsetX = 1895.26f,
            offsetY = 2006.06f,
        )

        val copied = source.translatedCopy(dx = 0f, dy = 0f)

        assertEquals(2, copied.inputs.size)
        assertEquals(55L, copied.inputs[0].elapsedTimeMillis)
        assertEquals(0.2f, copied.inputs[0].pressure, 0f)
        assertEquals(56L, copied.inputs[1].elapsedTimeMillis)
        assertEquals(0.9f, copied.inputs[1].pressure, 0f)
        assertEquals(sourceInputs.getNoiseSeed(), copied.inputs.getNoiseSeed())
    }
}
