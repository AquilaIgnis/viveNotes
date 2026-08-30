package com.vivenotes.ai

import android.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.data.AUTOMATIC_LIGHT
import com.vivenotes.ink.CanvasInkPainter
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.projectionKey
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What recognition is actually handed, end to end from a selection.
 *
 * The failure this guards is not a wrong reading, it is an *empty* one: the renderer filters its
 * strokes by `projectionKey in selection.projections`, so if anything upstream hands the page a
 * different [Stroke] instance than the one the selection was captured against, the filter matches
 * nothing, the bitmap stays white, and the model dutifully reads a blank page. Nothing throws and
 * nothing looks wrong on screen — the panel simply comes back with nothing on ink that is plainly
 * there.
 *
 * So this asserts the whole path in the arrangement the app uses it in: the ink is *painted* for the
 * canvas, the selection is built from the stored strokes, and the renderer is given the stored
 * strokes — the split that [CanvasInkPainter] exists to keep straight.
 */
@RunWith(AndroidJUnit4::class)
class InkSelectionRenderTest {

    private fun stroke(colorArgb: Int): Stroke = Stroke(
        brush = Brush.createWithColorIntArgb(
            family = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
            colorIntArgb = colorArgb,
            size = 4f,
            epsilon = 0.1f,
        ),
        inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.UNKNOWN, 20f, 20f, 0L)
            add(InputToolType.UNKNOWN, 60f, 60f, 10L)
            add(InputToolType.UNKNOWN, 100f, 20f, 20L)
        }.toImmutable(),
    )

    private fun inkPixels(bitmap: android.graphics.Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != Color.WHITE) count++
            }
        }
        return count
    }

    @Test
    fun automaticInkStillRendersForRecognitionAfterItIsPaintedForTheCanvas() {
        // A page of automatic ink, exactly what the pen produces and what every stroke already on
        // this project's test device is.
        val strokes = listOf(PageStroke("s1", stroke(AUTOMATIC_LIGHT), colorFollowsTheme = true))

        // The overlay paints it for a dark canvas. This must not disturb the strokes themselves.
        val painter = CanvasInkPainter(0xFFE6E6E6.toInt())
        strokes.forEach { painter.paint(it) }

        // The selection the lasso reports, keyed on the stored strokes.
        val selection = CanvasSelection(
            inkIds = setOf("s1"),
            projections = strokes.map { it.projectionKey }.toSet(),
            bounds = InkBounds(left = 0f, top = 0f, right = 120f, bottom = 80f),
        )

        // And what the view model hands recognition: the stored strokes, not the painted ones.
        val bitmap = renderInkSelection(strokes, emptyList(), selection)
        try {
            assertTrue(
                "the selection resolved to no strokes, so recognition would read a blank page",
                inkPixels(bitmap) > 0,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun aFractionBarDrawnWithTheLineToolIsInkedForTheModelToo() {
        // The reason the renderer takes shapes at all. A bar laid down with the Line tool is part of
        // the formula around it, and a formula handed over without it reads as two numbers with a
        // gap. The stroke is up at y = 20..60; the bar is well below it, so the pixels it adds can
        // be counted on their own rather than inferred from a total.
        val strokes = listOf(PageStroke("s1", stroke(AUTOMATIC_LIGHT), colorFollowsTheme = true))
        var next = 0
        val bar = Outline.Shape(
            id = "bar",
            kind = ShapeKind.Line,
            segments = seedSegments(ShapeKind.Line, 20f, 90f, 100f, 90f) { "seg-${next++}" },
        ).withRecomputedBounds()
        val selection = CanvasSelection(
            inkIds = setOf("s1"),
            shapeIds = setOf("bar"),
            projections = strokes.map { it.projectionKey }.toSet(),
            bounds = InkBounds(left = 0f, top = 0f, right = 120f, bottom = 110f),
        )

        val withBar = renderInkSelection(strokes, listOf(bar), selection)
        val withoutBar = renderInkSelection(strokes, emptyList(), selection)
        try {
            assertTrue(
                "the line was left out of what the model is shown",
                inkPixels(withBar) > inkPixels(withoutBar),
            )
        } finally {
            withBar.recycle()
            withoutBar.recycle()
        }
    }

    @Test
    fun aShapeTheSelectionDoesNotHoldIsNotDrawnIn() {
        // The crop is the selection's, so a line lying elsewhere on the page must not appear in it —
        // the filter is by id, not by whether the geometry happens to fall inside the bounds.
        val strokes = listOf(PageStroke("s1", stroke(AUTOMATIC_LIGHT), colorFollowsTheme = true))
        var next = 0
        val elsewhere = Outline.Shape(
            id = "not-held",
            kind = ShapeKind.Line,
            segments = seedSegments(ShapeKind.Line, 20f, 90f, 100f, 90f) { "seg-${next++}" },
        ).withRecomputedBounds()
        val selection = CanvasSelection(
            inkIds = setOf("s1"),
            projections = strokes.map { it.projectionKey }.toSet(),
            bounds = InkBounds(left = 0f, top = 0f, right = 120f, bottom = 110f),
        )

        val rendered = renderInkSelection(strokes, listOf(elsewhere), selection)
        val inkAlone = renderInkSelection(strokes, emptyList(), selection)
        try {
            assertEquals(inkPixels(inkAlone), inkPixels(rendered))
        } finally {
            rendered.recycle()
            inkAlone.recycle()
        }
    }
}
