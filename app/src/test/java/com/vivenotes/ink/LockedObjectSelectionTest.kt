package com.vivenotes.ink

import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a lock does to selection — `memory/diagram.md`:
 *
 * > *Locked objects : cannot be re-sized or moved from current position, lasso selection will
 * > exclude locked objects from selection unless only locked objects are part of the selection.*
 *
 * On the JVM, beside [CanvasTapSelectionTest], because none of it needs a device: the rule is a
 * partition of what the loop caught, and the widening that follows is a filter on a list of
 * outlines. Whether a locked object then *refuses the drag* is the layers' half and needs
 * `MotionEvent`s — `PrimeObjectTest`.
 */
class LockedObjectSelectionTest {

    private fun square(
        id: String,
        left: Float,
        top: Float,
        side: Float = 30f,
        lockGroup: String? = null,
    ): Outline.Shape {
        var next = 0
        return Outline.Shape(
            id = id,
            kind = ShapeKind.Rectangle,
            lockGroup = lockGroup,
            segments = seedSegments(
                ShapeKind.Rectangle, left, top, left + side, top + side,
            ) { "$id-seg-${next++}" },
        ).withRecomputedBounds()
    }

    private fun picture(
        id: String,
        left: Float,
        top: Float,
        side: Float = 30f,
        lockGroup: String? = null,
    ) = Outline.Image(
        id = id,
        x = left,
        y = top,
        width = side,
        height = side,
        attachmentId = "$id-file",
        lockGroup = lockGroup,
    )

    /** The closed rectangle `LassoClosureTest` proves is a loop, big enough to hold everything below. */
    private val loop = listOf(
        InkPoint(40f, 40f), InkPoint(220f, 40f), InkPoint(220f, 160f), InkPoint(40f, 160f),
        InkPoint(40f, 40f),
    )

    private fun lasso(
        shapes: List<Outline.Shape> = emptyList(),
        images: List<Outline.Image> = emptyList(),
    ): CanvasSelection? = selectWithLasso(
        strokes = emptyList(),
        shapes = shapes,
        images = images,
        path = loop,
    )

    @Test
    fun `a loop holding one locked object and one free one takes only the free one`() {
        val held = lasso(
            shapes = listOf(
                square("free", left = 60f, top = 60f),
                square("pinned", left = 140f, top = 60f, lockGroup = "group"),
            ),
        )

        assertEquals(setOf("free"), held?.shapeIds)
        assertFalse("the selection is the free object's, so it is not locked", held?.isLocked == true)
    }

    @Test
    fun `the rule crosses kinds - a free picture excludes a locked shape`() {
        val held = lasso(
            shapes = listOf(square("pinned", left = 140f, top = 60f, lockGroup = "group")),
            images = listOf(picture("photo", left = 60f, top = 60f)),
        )

        assertEquals(setOf("photo"), held?.imageIds)
        assertEquals(emptySet<String>(), held?.shapeIds)
    }

    @Test
    fun `a loop holding nothing but locked objects takes them`() {
        // The "unless" half, and the only way back to the bar the unlock button is on.
        val held = lasso(
            shapes = listOf(
                square("one", left = 60f, top = 60f, lockGroup = "group"),
                square("two", left = 140f, top = 60f, lockGroup = "group"),
            ),
        )

        assertEquals(setOf("one", "two"), held?.shapeIds)
        assertTrue(held?.isLocked == true)
        assertEquals(setOf("group"), held?.lockGroups)
    }

    @Test
    fun `a tap on a locked object still takes it`() {
        val pinned = picture("photo", left = 60f, top = 60f, lockGroup = "group")

        val held = selectByTap(
            shapes = emptyList(),
            equations = emptyList(),
            images = listOf(pinned),
            point = InkPoint(70f, 70f),
        )

        assertEquals(setOf("photo"), held?.imageIds)
        assertTrue("a tap is how a locked object is reached to be unlocked", held?.isLocked == true)
    }

    @Test
    fun `holding one member of a locked group holds all of it`() {
        val one = square("one", left = 60f, top = 60f, lockGroup = "group")
        val two = square("two", left = 140f, top = 60f, lockGroup = "group")
        val elsewhere = square("loose", left = 300f, top = 300f)

        val widened = CanvasSelection.ofShape(one)
            .reconcile(strokes = emptyList(), shapes = listOf(one, two, elsewhere))

        assertEquals(setOf("one", "two"), widened?.shapeIds)
        // The rectangle is the group's, not the one member's: it is what the bar hangs off.
        assertEquals(60f, widened?.bounds?.left)
        assertEquals(170f, widened?.bounds?.right)
    }

    @Test
    fun `a group is not widened into a second one that happens to be on the page`() {
        val one = square("one", left = 60f, top = 60f, lockGroup = "first")
        val other = square("other", left = 140f, top = 60f, lockGroup = "second")

        val widened = CanvasSelection.ofShape(one)
            .reconcile(strokes = emptyList(), shapes = listOf(one, other))

        assertEquals(setOf("one"), widened?.shapeIds)
    }

    @Test
    fun `unlocking frees what was held without dropping it`() {
        val one = square("one", left = 60f, top = 60f, lockGroup = "group")
        val two = square("two", left = 140f, top = 60f, lockGroup = "group")
        val held = CanvasSelection.ofShape(one)
            .reconcile(strokes = emptyList(), shapes = listOf(one, two))

        // What the ViewModel writes when the button is tapped again: the group is the lock, so
        // clearing it unlocks and ungroups in one edit.
        val freed = held?.reconcile(
            strokes = emptyList(),
            shapes = listOf(one.copy(lockGroup = null), two.copy(lockGroup = null)),
        )

        assertEquals(setOf("one", "two"), freed?.shapeIds)
        assertFalse(freed?.isLocked == true)
    }
}
