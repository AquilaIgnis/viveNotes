package com.vivenotes.ink

import android.graphics.Color as AndroidColor
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
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

    // ---------------------------------------------------------------------------------------
    // Stabilization — `docs/inkPlan.md` §4, applied 2026-08-10
    // ---------------------------------------------------------------------------------------

    /** A straight line with a 10 Hz tremor on it, sampled at 120 Hz — the shake a stabilizer is for. */
    private fun shakyLine(): androidx.ink.strokes.StrokeInputBatch =
        MutableStrokeInputBatch().apply {
            for (i in 0 until 120) {
                val seconds = i / 120f
                add(
                    InputToolType.STYLUS,
                    10f + i * 1.5f,
                    100f + 3f * kotlin.math.sin(2f * Math.PI.toFloat() * 10f * seconds),
                    (seconds * 1000f).toLong(),
                )
            }
        }.toImmutable()

    /** How far the drawn centreline still strays from the straight line it was meant to be. */
    private fun wobbleOf(stroke: Stroke): Float {
        val box = stroke.shape.computeBoundingBox()!!
        return ((box.yMax - box.yMin) - stroke.brush.size) / 2f
    }

    private fun strokeAt(level: Int): Stroke = Stroke(
        brush = InkCodec.brushFor(PenPreset(stabilization = level, thickness = 2f)),
        inputs = shakyLine(),
    )

    @Test
    fun stabilizationActuallyReachesTheStroke() {
        // The whole point: the stepper was stored and never applied. Off must be rougher than on,
        // measured on the mesh rather than on the setting that produced it.
        val off = wobbleOf(strokeAt(0))
        val most = wobbleOf(strokeAt(PenPreset.MAX_STABILIZATION))

        assertTrue(
            "stabilization did nothing: off=$off max=$most",
            most < off - 0.2f,
        )
    }

    @Test
    fun theScaleIsMonotonicAndNeverReversesItself() {
        // A stepper whose middle settings undid each other would be worse than none. Equal is
        // allowed — the window saturates against a fast tremor — but rougher is not.
        val wobbles = (0..PenPreset.MAX_STABILIZATION).map { wobbleOf(strokeAt(it)) }
        wobbles.zipWithNext().forEachIndexed { index, (lower, higher) ->
            assertTrue(
                "level ${index + 1} is shakier than level $index: $wobbles",
                higher <= lower + 0.01f,
            )
        }
    }

    @Test
    fun levelOneIsTheLibraryDefaultSoOldInkIsUnchanged() {
        // Every stroke drawn before this existed was already getting the stock model, and every one
        // of those rows stored the default level. If 1 were anything else, turning stabilization on
        // would silently reshape every page in the notebook.
        assertEquals(
            BrushFamily.InputModel.DEFAULT_INPUT_MODEL,
            InkCodec.inputModelFor(1),
        )
        assertEquals(1, PenPreset().stabilization)
    }

    @Test
    fun offMeansTheRawSamples() {
        assertEquals(BrushFamily.InputModel.PASSTHROUGH_MODEL, InkCodec.inputModelFor(0))
    }

    @Test
    fun aStoredStrokeIsRebuiltWithTheLevelItWasDrawnAt() {
        // Not the pen's current level: the pen moves on, the ink must not.
        val pen = PenPreset(stabilization = PenPreset.MAX_STABILIZATION, thickness = 2f)
        val drawn = Stroke(InkCodec.brushFor(pen), shakyLine())
        val row = InkCodec.encode(drawn, pageId = "page", seq = 0, pen = pen, now = 1L)

        assertEquals(PenPreset.MAX_STABILIZATION, row.stabilization)
        val restored = InkCodec.decode(row)
        assertNotNull(restored)
        assertEquals(
            "a reloaded stroke changed shape",
            wobbleOf(drawn),
            wobbleOf(restored!!),
            0.01f,
        )

        // And a row that says "off" comes back rough, rather than taking whatever is in hand now.
        val rough = InkCodec.decode(row.copy(stabilization = 0))
        assertNotNull(rough)
        assertTrue(
            "the stored level was ignored on reload",
            wobbleOf(rough!!) > wobbleOf(restored) + 0.2f,
        )
    }

    @Test
    fun aHighlighterIgnoresTheStabilizationColumnEntirely() {
        // Its rows store 0 meaning *not applicable*, not *off*. Reading that as passthrough would
        // re-render every highlighter stroke ever saved, rougher than it was drawn.
        val settings = HighlighterSettings()
        val drawn = Stroke(InkCodec.brushFor(settings), shakyLine())
        val row = InkCodec.encode(drawn, pageId = "page", seq = 0, highlighter = settings, now = 1L)

        assertEquals(0, row.stabilization)
        val restored = InkCodec.decode(row)
        assertNotNull(restored)
        assertEquals(
            "the highlighter was re-rendered through the stabilizer",
            wobbleOf(drawn),
            wobbleOf(restored!!),
            0.01f,
        )
    }
}
