package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.data.EraserMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deleting a lassoed piece of ink without taking the rest of its row with it.
 *
 * Once the lasso stopped widening a hit out to its stored row, delete had to stop widening too — a
 * stroke erased in two, one piece circled, the other going with it is the one place the narrowing
 * would have cost something irreversible. A piece has no row of its own to tombstone, so what is
 * stored is an Object-mode erase per piece; these tests are about that erase being *provably* the
 * one it claims to be. `memory/lassoProjectionPlan.md` §5.
 */
@RunWith(AndroidJUnit4::class)
class DeleteProjectionTest {

    /** A stroke straight across the page, cut in two by an eraser through its middle. */
    private fun cutInHalf(): List<PageStroke> {
        val ink = PageStroke("stroke", InkCodec.eraseMask(inputs(10f to 50f, 90f to 50f), 6f))
        val mask = InkCodec.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        return listOf(ink).subtract(mask, listOf("stroke"))
    }

    private fun List<PageStroke>.left() = minByOrNull { it.pageBounds!!.left }!!

    private fun List<PageStroke>.right() = maxByOrNull { it.pageBounds!!.left }!!

    @Test
    fun deletingOnePieceStoresOneEraseAndKeepsTheOther() {
        val pieces = cutInHalf()

        val plan = pieces.planProjectionDelete(setOf(pieces.left().projectionKey))

        assertEquals("the row still holds ink, so nothing may be tombstoned", emptyList<String>(), plan.wholeRows)
        assertEquals(1, plan.erases.size)
        assertEquals("stroke", plan.erases.single().rowId)
        assertEquals(1, plan.after.size)
        assertEquals(
            "the surviving piece is not where the deleted one was",
            pieces.right().pageBounds!!.left,
            plan.after.single().pageBounds!!.left,
            0.001f,
        )
    }

    /** The mask is the claim; this is the proof of it, put to the mesh rather than to the eye. */
    @Test
    fun theStoredMaskTakesTheCircledPieceAndNotItsNeighbour() {
        val pieces = cutInHalf()

        val plan = pieces.planProjectionDelete(setOf(pieces.left().projectionKey))
        val mask = plan.erases.single().mask

        assertTrue("the mask missed the piece it was built for", pieces.left().touches(mask))
        assertFalse("the mask reached across the gap", pieces.right().touches(mask))
    }

    /**
     * What page-open replay will do with the stored row, done here: the operation has to produce the
     * page the gesture produced, or the piece comes back on the next open.
     */
    @Test
    fun replayingTheStoredEraseReachesTheSamePage() {
        val pieces = cutInHalf()
        val plan = pieces.planProjectionDelete(setOf(pieces.left().projectionKey))
        val stored = InkCodec.encodeErase(plan.erases.single().mask, "page", EraserMode.Object)

        val decoded = InkCodec.decodeErase(stored)
        assertNotNull("the mask did not survive the codec", decoded)
        val replayed = pieces.eraseObjects(decoded!!, listOf("stroke"))

        assertEquals(1, replayed.size)
        assertEquals(
            pieces.right().pageBounds!!.left,
            replayed.single().pageBounds!!.left,
            0.001f,
        )
    }

    /** Every piece of the row held: there is nothing left to erase around, so the row itself goes. */
    @Test
    fun deletingEveryPieceTombstonesTheRow() {
        val pieces = cutInHalf()

        val plan = pieces.planProjectionDelete(pieces.map { it.projectionKey }.toSet())

        assertEquals(listOf("stroke"), plan.wholeRows)
        assertEquals("a tombstone says it; an erase would say it twice", 0, plan.erases.size)
        assertEquals(emptyList<PageStroke>(), plan.after)
    }

    /** Ink the eraser never touched is one projection, so deleting it is the ordinary tombstone. */
    @Test
    fun deletingAnUncutStrokeTombstonesIt() {
        val whole = listOf(PageStroke("stroke", InkCodec.eraseMask(inputs(10f to 50f, 38f to 50f), 6f)))

        val plan = whole.planProjectionDelete(setOf(whole.single().projectionKey))

        assertEquals(listOf("stroke"), plan.wholeRows)
        assertEquals(0, plan.erases.size)
        assertEquals(emptyList<PageStroke>(), plan.after)
    }

    /** Ink nobody selected is not touched, whatever else the page is losing. */
    @Test
    fun aRowNothingIsHeldOfIsLeftAlone() {
        val pieces = cutInHalf()
        val bystander = PageStroke("other", InkCodec.eraseMask(inputs(10f to 200f, 90f to 200f), 6f))
        val page = pieces + bystander

        val plan = page.planProjectionDelete(setOf(pieces.left().projectionKey))

        assertEquals("only the cut row is operated on", listOf("stroke"), plan.erases.map { it.rowId })
        assertEquals(
            "a row nobody circled was re-projected anyway",
            bystander.projectionKey,
            plan.after.single { it.id == "other" }.projectionKey,
        )
    }

    /** A piece with a mesh always yields a point on it — the dot mask has nowhere else to start. */
    @Test
    fun everyLivePieceOffersAPointOnItsInk() {
        cutInHalf().forEach { piece ->
            val point = piece.pointOnInk()
            assertNotNull("a live projection had no vertex to place a mask on", point)
            val bounds = piece.pageBounds!!
            assertTrue(point!!.x >= bounds.left - 0.5f && point.x <= bounds.right + 0.5f)
            assertTrue(point.y >= bounds.top - 0.5f && point.y <= bounds.bottom + 0.5f)
        }
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) ->
            add(InputToolType.UNKNOWN, x, y, index * 10L)
        }
    }.toImmutable()
}
