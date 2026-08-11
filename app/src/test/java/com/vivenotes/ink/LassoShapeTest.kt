package com.vivenotes.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two shortcuts that let a page-sized lasso skip reading every mesh outline through JNI.
 *
 * Only the parts that are arithmetic are pinned here — the classification and the extent. Deciding
 * an actual stroke needs `androidx.ink`'s native geometry and belongs on a device; what is testable
 * on the JVM is the reasoning those decisions rest on, and it is the reasoning that would be wrong
 * silently. A lasso wrongly called convex selects ink the user did not loop, and nothing about that
 * looks like a crash.
 */
class LassoShapeTest {

    private fun path(vararg points: Pair<Float, Float>) = points.map { InkPoint(it.first, it.second) }

    // --- what counts as convex ----------------------------------------------------------------

    @Test
    fun `a rectangle is convex`() {
        // The shape the origin-corner repair uses, and the reason this fast path pays.
        val lasso = LassoShape(path(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f))
        assertTrue(lasso.acceptsWholeBox)
    }

    @Test
    fun `a rectangle wound the other way is still convex`() {
        val lasso = LassoShape(path(0f to 0f, 0f to 100f, 100f to 100f, 100f to 0f))
        assertTrue(lasso.acceptsWholeBox)
    }

    @Test
    fun `a triangle is convex`() {
        assertTrue(LassoShape(path(0f to 0f, 100f to 0f, 50f to 80f)).acceptsWholeBox)
    }

    @Test
    fun `an L shape is not convex`() {
        // One reflex corner is enough: a box inside the polygon's extent can still poke out of the
        // notch, so this lasso has to be decided the slow, exact way.
        val lasso = LassoShape(
            path(0f to 0f, 100f to 0f, 100f to 40f, 40f to 40f, 40f to 100f, 0f to 100f),
        )
        assertFalse(lasso.acceptsWholeBox)
    }

    @Test
    fun `a collinear vertex does not make a rectangle concave`() {
        // A point dropped part-way along an edge turns neither way. Treating "no turn" as a
        // disagreement would refuse the fast path for any lasso with a redundant vertex in it,
        // which a real gesture produces constantly.
        val lasso = LassoShape(path(0f to 0f, 50f to 0f, 100f to 0f, 100f to 100f, 0f to 100f))
        assertTrue(lasso.acceptsWholeBox)
    }

    @Test
    fun `a degenerate path that turns nowhere is not convex`() {
        // Three points on one line enclose nothing, so there is no interior to accept a box into.
        assertFalse(LassoShape(path(0f to 0f, 50f to 0f, 100f to 0f)).acceptsWholeBox)
    }

    // --- the extent used to reject ------------------------------------------------------------

    @Test
    fun `the rejection extent is the polygon grown by the tolerance`() {
        // Grown, never shrunk: the exact test accepts a vertex within the tolerance of an edge, so
        // an extent measured tightly would reject strokes the slow path would have kept.
        val lasso = LassoShape(path(10f to 20f, 110f to 20f, 110f to 120f, 10f to 120f), 4f)
        assertTrue(lasso.couldContain(InkBounds(6f, 16f, 114f, 124f)))
        assertFalse(lasso.couldContain(InkBounds(5.9f, 16f, 114f, 124f)))
        assertFalse(lasso.couldContain(InkBounds(6f, 16f, 114f, 124.1f)))
    }

    @Test
    fun `a stroke reaching past the lasso cannot be inside it`() {
        val lasso = LassoShape(path(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f), 0f)
        assertFalse(lasso.couldContain(InkBounds(-1f, 10f, 50f, 50f)))
        assertFalse(lasso.couldContain(InkBounds(50f, 10f, 101f, 50f)))
        assertTrue(lasso.couldContain(InkBounds(10f, 10f, 90f, 90f)))
    }

    @Test
    fun `a path with fewer than three points encloses nothing`() {
        assertFalse(LassoShape(path(0f to 0f, 10f to 10f)).usable)
    }
}
