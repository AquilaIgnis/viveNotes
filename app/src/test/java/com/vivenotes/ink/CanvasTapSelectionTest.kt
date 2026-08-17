package com.vivenotes.ink

import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What one tap on the canvas picks up, and what it deliberately does not.
 *
 * On the JVM because none of it needs a device: the three kinds a tap can take are plain document
 * geometry, and the rule each is judged by is arithmetic. Whether the *gesture* decides it tapped at
 * all — travel against the touch slop, a loop winning over a tap — is `InkOverlayTest`'s, since that
 * one needs `MotionEvent`s.
 */
class CanvasTapSelectionTest {

    private fun square(id: String, left: Float, top: Float, side: Float): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = id,
            kind = ShapeKind.Rectangle,
            segments = seedSegments(
                ShapeKind.Rectangle, left, top, left + side, top + side,
            ) { "$id-seg-${next++}" },
        ).withRecomputedBounds()
    }

    private fun picture(id: String, left: Float, top: Float, side: Float) = Outline.Image(
        id = id,
        x = left,
        y = top,
        width = side,
        height = side,
        attachmentId = "$id-file",
    )

    private fun formula(id: String, left: Float, top: Float, side: Float) = Outline.Equation(
        id = id,
        x = left,
        y = top,
        width = side,
        height = side,
        latex = "x^2",
    )

    private fun tap(
        x: Float,
        y: Float,
        shapes: List<Outline.Shape> = emptyList(),
        equations: List<Outline.Equation> = emptyList(),
        images: List<Outline.Image> = emptyList(),
    ): CanvasSelection? = selectByTap(shapes, equations, images, InkPoint(x, y))

    @Test
    fun `a tap on a picture takes it`() {
        val held = tap(60f, 60f, images = listOf(picture("photo", 40f, 40f, 60f)))

        assertEquals(setOf("photo"), held?.imageIds)
    }

    @Test
    fun `a tap on a formula takes it`() {
        val held = tap(60f, 60f, equations = listOf(formula("eq", 40f, 40f, 60f)))

        assertEquals(setOf("eq"), held?.equationIds)
    }

    @Test
    fun `a tap on a shape's outline takes it`() {
        val held = tap(40f, 100f, shapes = listOf(square("box", 40f, 40f, 120f)))

        assertEquals(setOf("box"), held?.shapeIds)
    }

    @Test
    fun `the empty middle of a shape is the page, not the shape`() {
        // `ShapeLayer.topmostNear`'s rule, and the reason it is the one being matched: pointing at
        // the middle of a large circle is pointing at whatever is drawn inside it.
        assertNull(tap(100f, 100f, shapes = listOf(square("box", 40f, 40f, 120f))))
    }

    @Test
    fun `a tap just off a thin line still finds it`() {
        // A line is thin and a finger is not — TAP_REACH, the same reach the shape layer allows.
        assertEquals(
            setOf("box"),
            tap(40f - TAP_REACH + 1f, 100f, shapes = listOf(square("box", 40f, 40f, 120f)))?.shapeIds,
        )
        assertNull(tap(40f - TAP_REACH - 4f, 100f, shapes = listOf(square("box", 40f, 40f, 120f))))
    }

    @Test
    fun `the last drawn of two overlapping pictures wins`() {
        val held = tap(
            60f,
            60f,
            images = listOf(picture("under", 40f, 40f, 60f), picture("over", 50f, 50f, 60f)),
        )

        assertEquals(setOf("over"), held?.imageIds)
    }

    @Test
    fun `a picture over a shape wins the tap, as the nested layers decide it`() {
        val held = tap(
            40f,
            100f,
            shapes = listOf(square("box", 40f, 40f, 120f)),
            images = listOf(picture("photo", 20f, 80f, 60f)),
        )

        assertEquals(setOf("photo"), held?.imageIds)
        assertEquals(emptySet<String>(), held?.shapeIds)
    }

    @Test
    fun `a tap on bare canvas takes nothing`() {
        assertNull(
            tap(
                400f,
                400f,
                shapes = listOf(square("box", 40f, 40f, 120f)),
                equations = listOf(formula("eq", 40f, 40f, 60f)),
                images = listOf(picture("photo", 40f, 40f, 60f)),
            ),
        )
    }
}
