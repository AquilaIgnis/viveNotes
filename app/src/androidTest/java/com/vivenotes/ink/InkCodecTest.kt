package com.vivenotes.ink

import android.graphics.Color as AndroidColor
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ToolTypeFilterNode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.data.HighlighterSettings
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

    // --- the highlighter -------------------------------------------------------------------------

    /**
     * The stock highlighter is only a highlighter when it is given a translucent colour — the family
     * supplies the chisel shape and the colour supplies the transparency. Handing it an opaque ARGB
     * would produce a wide marker that blots out whatever it was drawn over, so the alpha surviving
     * into the brush is the property worth guarding.
     */
    @Test
    fun theHighlighterDrawsWithATranslucentInk() {
        val settings = HighlighterSettings()
        val brush = InkCodec.brushFor(settings)

        assertEquals(settings.colorArgb, brush.colorIntArgb)
        assertTrue("the ink is opaque", AndroidColor.alpha(brush.colorIntArgb) in 1..254)
        assertEquals(settings.thickness.toFloat(), brush.size, 0.001f)
    }

    /**
     * A highlighter that doubles back over itself must not darken where it crosses. On minSdk 35
     * `SelfOverlap.ANY` resolves to ACCUMULATE, which does exactly that to a translucent colour, so
     * DISCARD is not a preference here — it is the difference between one flat band and a blotch.
     */
    @Test
    fun theHighlighterDiscardsItsOwnOverlap() {
        assertEquals(SelfOverlap.DISCARD, selfOverlapOf(InkCodec.brushFor(HighlighterSettings())))
    }

    @Test
    fun aHighlighterStrokeIsStoredAsOneAndComesBack() {
        val settings = HighlighterSettings()
        val stroke = Stroke(InkCodec.brushFor(settings), MutableStrokeInputBatch())

        val row = InkCodec.encode(stroke, pageId = "page", seq = 0, highlighter = settings, now = 1L)

        assertEquals("highlighter", row.brushFamily)
        assertEquals(settings.colorArgb, row.colorArgb)
        // The highlighter has no stabilization setting, so 0 is the truth about the stroke rather
        // than a value copied off a pen that was not in hand.
        assertEquals(0, row.stabilization)

        val restored = InkCodec.decode(row)
        assertNotNull(restored)
        assertEquals(settings.colorArgb, restored!!.brush.colorIntArgb)
        assertEquals(SelfOverlap.DISCARD, selfOverlapOf(restored.brush))
    }

    private fun selfOverlapOf(brush: Brush) =
        brush.family.coats.single().paintPreferences.first().selfOverlap

    private fun tipFor(pressure: Int) =
        InkCodec.brushFor(calligraphyPen(pressure)).family.coats.single().tip

    private fun calligraphyPen(pressure: Int) =
        PenPreset.starting(0).copy(kind = PenKind.Calligraphy, pressure = pressure)
}
