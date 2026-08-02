package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ToolTypeFilterNode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.data.PenKind
import com.vivenotes.data.PenPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkCodecTest {

    @Test
    fun calligraphyUsesAFixedBroadEdgeNibEvenWithPressureOff() {
        val tip = tipFor(pressure = 0)

        assertEquals(1f, tip.scaleX, 0.001f)
        assertEquals(0.22f, tip.scaleY, 0.001f)
        assertEquals(0.2f, tip.cornerRounding, 0.001f)
        assertEquals(45f, tip.rotationDegrees, 0.001f)
        assertTrue(tip.behaviors.isEmpty())
    }

    @Test
    fun maximumSensitivityMapsStylusPressureAcrossTheFullFlexRange() {
        val behavior = tipFor(PenPreset.MAX_PRESSURE).behaviors.first()
        val target = behavior.terminalNodes.single() as TargetNode
        val tools = target.input as ToolTypeFilterNode
        val damping = tools.input as DampingNode
        val source = damping.input as SourceNode

        assertEquals(TargetNode.Target.SIZE_MULTIPLIER, target.target)
        assertEquals(0.45f, target.targetModifierRangeStart, 0.001f)
        assertEquals(1.6f, target.targetModifierRangeEnd, 0.001f)
        assertEquals(setOf(InputToolType.STYLUS), tools.enabledToolTypes)
        assertEquals(SourceNode.Source.NORMALIZED_PRESSURE, source.source)
    }

    @Test
    fun touchAndMouseUseSpeedAsThePressureFallback() {
        val behavior = tipFor(PenPreset.MAX_PRESSURE).behaviors[1]
        val target = behavior.terminalNodes.single() as TargetNode
        val tools = target.input as ToolTypeFilterNode
        val source = (tools.input as DampingNode).input as SourceNode

        assertEquals(1.6f, target.targetModifierRangeStart, 0.001f)
        assertEquals(0.45f, target.targetModifierRangeEnd, 0.001f)
        assertEquals(
            setOf(InputToolType.UNKNOWN, InputToolType.MOUSE, InputToolType.TOUCH),
            tools.enabledToolTypes,
        )
        assertEquals(
            SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND,
            source.source,
        )
    }

    @Test
    fun storedCalligraphyFamilyKeepsItsPressureLevel() {
        val pen = calligraphyPen(pressure = 4)
        val stroke = Stroke(InkCodec.brushFor(pen), MutableStrokeInputBatch())

        val row = InkCodec.encode(stroke, pageId = "page", seq = 0, pen = pen, now = 1L)

        assertEquals("calligraphy-v1-p4", row.brushFamily)
        val restored = InkCodec.decode(row)
        assertNotNull(restored)
        assertEquals(45f, restored!!.brush.family.coats.single().tip.rotationDegrees, 0.001f)
    }

    private fun tipFor(pressure: Int) =
        InkCodec.brushFor(calligraphyPen(pressure)).family.coats.single().tip

    private fun calligraphyPen(pressure: Int) =
        PenPreset.starting(0).copy(kind = PenKind.Calligraphy, pressure = pressure)
}
