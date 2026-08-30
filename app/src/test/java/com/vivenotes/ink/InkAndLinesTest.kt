package com.vivenotes.ink

import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Math toolkit will act on — `memory/inkPlan.md` §5.4 SD12.
 *
 * The gate widened from "ink and nothing else" to "ink, plus the shapes that are marks": a fraction
 * bar drawn with the Line tool belongs to the formula it sits in. What this pins is the *edge* of
 * that widening, because the failure it invites is quiet — a rectangle let through would be inked
 * into the bitmap and read as part of an equation.
 */
class InkAndLinesTest {

    private fun shape(id: String, kind: ShapeKind): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = id,
            kind = kind,
            segments = seedSegments(kind, 0f, 0f, 80f, 40f) { "$id-${next++}" },
        ).withRecomputedBounds()
    }

    private val page = listOf(
        shape("line", ShapeKind.Line),
        shape("arrow", ShapeKind.Arrow),
        shape("box", ShapeKind.Rectangle),
        shape("ell", ShapeKind.L),
    )

    private fun selection(
        ink: Set<String> = setOf("s1"),
        shapes: Set<String> = emptySet(),
        tables: Set<String> = emptySet(),
        equations: Set<String> = emptySet(),
        images: Set<String> = emptySet(),
    ) = CanvasSelection(
        inkIds = ink,
        shapeIds = shapes,
        tableIds = tables,
        equationIds = equations,
        imageIds = images,
        bounds = InkBounds(0f, 0f, 100f, 100f),
    )

    @Test
    fun `ink alone still qualifies`() {
        // The behaviour this replaced, which has to survive the widening: it is the common case.
        assertTrue(selection().isInkAndLines(page))
    }

    @Test
    fun `ink with a line or an arrow qualifies`() {
        assertTrue(selection(shapes = setOf("line")).isInkAndLines(page))
        assertTrue(selection(shapes = setOf("arrow")).isInkAndLines(page))
        assertTrue(selection(shapes = setOf("line", "arrow")).isInkAndLines(page))
    }

    @Test
    fun `a shape that is not a mark does not`() {
        // A rectangle is no part of an equation, and an L is a drawing however open it is. The test
        // is the kind, not whether the shape happens to be open.
        assertFalse(selection(shapes = setOf("box")).isInkAndLines(page))
        assertFalse(selection(shapes = setOf("ell")).isInkAndLines(page))
        assertFalse("one bad shape spoils it", selection(shapes = setOf("line", "box")).isInkAndLines(page))
    }

    @Test
    fun `lines without ink are a diagram, not a formula`() {
        // Handing a formula model a couple of rules would produce confident nonsense, so ink is
        // still required and the shapes are only ever along for the ride.
        assertFalse(selection(ink = emptySet(), shapes = setOf("line")).isInkAndLines(page))
    }

    @Test
    fun `every other kind still disqualifies the selection`() {
        assertFalse(selection(tables = setOf("t")).isInkAndLines(page))
        assertFalse(selection(equations = setOf("e")).isInkAndLines(page))
        assertFalse(selection(images = setOf("i")).isInkAndLines(page))
    }

    @Test
    fun `a shape id the page no longer has fails rather than being skipped`() {
        // A selection naming something that is gone is one whose contents cannot be vouched for —
        // and skipping it would silently narrow the crop the bounds were measured for.
        assertFalse(selection(shapes = setOf("deleted")).isInkAndLines(page))
    }

    @Test
    fun `an empty selection is not a formula`() {
        assertFalse(null.isInkAndLines(page))
        assertFalse(selection(ink = emptySet()).isInkAndLines(page))
    }
}
