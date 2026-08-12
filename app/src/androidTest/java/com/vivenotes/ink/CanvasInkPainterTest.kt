package com.vivenotes.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.data.AUTOMATIC_LIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Painting automatic ink for the canvas must not disturb what a stroke *is*.
 *
 * This pins a regression rather than a feature. The first version of Switch Background's ink fix
 * mapped the page's strokes into a themed list, which looked right and was: the ink flipped, on both
 * backgrounds. What it also did was mint a new [Stroke] for every automatic stroke, and
 * [projectionKey] is `System.identityHashCode(stroke)` — so every projection was silently
 * renumbered.
 *
 * Nothing on screen showed it. What broke was everything that resolves a selection back to strokes
 * by that key: `InkSelectionRenderer.renderInkSelection` filtered its input against a selection full
 * of keys that no longer existed, rendered an empty white square, and handed *that* to the formula
 * model — so recognition returned nothing on ink that was plainly there. The lasso's move preview
 * failed the same test for the same reason.
 *
 * Hence the invariant: paint is derived per draw, identity belongs to the stored stroke.
 */
@RunWith(AndroidJUnit4::class)
class CanvasInkPainterTest {

    private val canvasInk = 0xFF1B1B1B.toInt()

    private fun stroke(colorArgb: Int): Stroke = Stroke(
        brush = Brush.createWithColorIntArgb(
            family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
            colorIntArgb = colorArgb,
            size = 3f,
            epsilon = 0.1f,
        ),
        inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.UNKNOWN, 10f, 10f, 0L)
            add(InputToolType.UNKNOWN, 40f, 40f, 10L)
        }.toImmutable(),
    )

    @Test
    fun paintingAutomaticInkLeavesTheProjectionKeyAlone() {
        val pageStroke = PageStroke("s1", stroke(AUTOMATIC_LIGHT), colorFollowsTheme = true)
        val before = pageStroke.projectionKey

        val painted = CanvasInkPainter(canvasInk).paint(pageStroke)

        // The paint changed...
        assertEquals(canvasInk, painted.brush.colorIntArgb)
        assertNotEquals(
            "painting must produce a different stroke to draw",
            AUTOMATIC_LIGHT,
            painted.brush.colorIntArgb,
        )
        // ...and the stroke the page holds did not, which is what the selection is keyed on.
        assertEquals(AUTOMATIC_LIGHT, pageStroke.stroke.brush.colorIntArgb)
        assertEquals(
            "a themed draw must not renumber the projection a selection holds",
            before,
            pageStroke.projectionKey,
        )
    }

    /** The same [Stroke] must not produce a fresh object per frame — the draw runs at 60Hz. */
    @Test
    fun paintingIsCachedPerStroke() {
        val pageStroke = PageStroke("s1", stroke(AUTOMATIC_LIGHT), colorFollowsTheme = true)
        val painter = CanvasInkPainter(canvasInk)

        val first = painter.paint(pageStroke)
        val second = painter.paint(pageStroke)

        assertEquals(true, first === second)
    }

    /** A colour the user picked is returned as the very stroke it already was — no copy at all. */
    @Test
    fun deliberateColorIsPassedStraightThrough() {
        val red = 0xFFE53935.toInt()
        val pageStroke = PageStroke("s1", stroke(red), colorFollowsTheme = false)

        val painted = CanvasInkPainter(canvasInk).paint(pageStroke)

        assertEquals(true, painted === pageStroke.stroke)
    }

    /** Ink stored before the flag existed still follows the canvas — the back-fill path. */
    @Test
    fun legacyWhiteInkIsPaintedForTheCanvas() {
        val pageStroke = PageStroke("s1", stroke(AUTOMATIC_LIGHT), colorFollowsTheme = null)

        val painted = CanvasInkPainter(canvasInk).paint(pageStroke)

        assertEquals(canvasInk, painted.brush.colorIntArgb)
    }

    /**
     * Recolouring held ink must not throw the selection away.
     *
     * This was broken on its own, before Switch Background existed: `recolor` rebinds the mesh to a
     * new brush, and while the key was the stroke's identity hash that renumbered the projection —
     * so the toolkit's own colour swatch quietly invalidated the very selection it was acting on.
     * Recognition then read a blank page, and the next drag of that selection moved nothing.
     */
    @Test
    fun recolouringHeldInkKeepsTheSelectionPointingAtIt() {
        val strokes = listOf(PageStroke("s1", stroke(AUTOMATIC_LIGHT), colorFollowsTheme = true))
        val held = strokes.map { it.projectionKey }.toSet()

        val recoloured = strokes.recolor(setOf("s1"), 0xFFE53935.toInt())

        assertEquals(
            "the recoloured stroke fell out of the selection holding it",
            1,
            recoloured.count { it.projectionKey in held },
        )
        // And the recolour itself still happened.
        assertEquals(0xFFE53935.toInt(), recoloured.single().stroke.brush.colorIntArgb)
        // Picking a colour is a decision, so it stops following the canvas.
        assertEquals(false, recoloured.single().colorFollowsTheme)
    }

    /** An erase that splits a stroke has to hand each piece its own identity, though. */
    @Test
    fun erasingIntoPiecesGivesEachPieceItsOwnProjection() {
        val one = PageStroke("s1", stroke(AUTOMATIC_LIGHT))
        val two = one.copy(stroke = stroke(AUTOMATIC_LIGHT), projection = newProjection())

        assertNotEquals(
            "two pieces of one row must not share a projection key",
            one.projectionKey,
            two.projectionKey,
        )
        assertEquals("they do still share the stored row", one.id, two.id)
    }
}
