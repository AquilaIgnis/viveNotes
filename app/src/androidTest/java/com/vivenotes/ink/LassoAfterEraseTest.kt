package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether ink that has been erased can still be picked up by the lasso — it could not.
 *
 * `subtract` splits a cut pen stroke into one projection per surviving region, and `Stroke.split`
 * returns pieces carrying no outlines. The lasso's exact test walks outlines, so it walked nothing
 * and every piece of erased ink answered "not inside" to every loop drawn around it. The gap was
 * invisible to the existing suites because a rectangular lasso is convex and convex loops are
 * answered by the bounding-box shortcut, which never reaches the outline walk; a loop drawn by hand
 * never is convex. Both shapes are therefore tested here, on either side of the eraser.
 */
@RunWith(AndroidJUnit4::class)
class LassoAfterEraseTest {

    /** A stroke straight across the page, cut in two by an eraser through its middle. */
    private fun cutInHalf(): List<PageStroke> {
        val ink = PageStroke("stroke", InkCodec.eraseMask(inputs(10f to 50f, 90f to 50f), 6f))
        val mask = InkCodec.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        return listOf(ink).subtract(mask, listOf("stroke"))
    }

    /** Pins the library fact the fallback is built on: a split piece has no outline to walk. */
    @Test
    fun aPieceOfAnErasedStrokeHasNoOutlines() {
        val pieces = cutInHalf()
        assertEquals("the cut made two pieces", 2, pieces.size)
        pieces.forEach { piece ->
            val shape = piece.stroke.shape
            val vertices = (0 until shape.getRenderGroupCount()).sumOf { group ->
                (0 until shape.getOutlineCount(group)).sumOf { outline ->
                    shape.getOutlineVertexCount(group, outline)
                }
            }
            assertEquals("a split piece carries no outlines", 0, vertices)
        }
    }

    @Test
    fun aHandDrawnLassoSelectsAPieceOfAnErasedStroke() {
        val pieces = cutInHalf()
        val left = pieces.minByOrNull { it.pageBounds!!.left }!!

        val selection = pieces.selectWithLasso(handDrawnLoop(right = 45f))

        assertNotNull("a loop around the left piece selected nothing", selection)
        // One piece, not the row both pieces share. The row id is still what the stored operation
        // names — that is what `ink_erases` and `ink_moves` can refer to — but what is *held* is the
        // projection the hand circled. `memory/lassoProjectionPlan.md` §4.
        assertEquals(setOf("stroke"), selection?.targetIds)
        assertEquals(setOf(left.projectionKey), selection?.projections)
    }

    /**
     * The reported bug, put to the geometry: a loop round one half of a cut line must not reach the
     * other half, and the rectangle it hands the tooltip must not span the gap between them.
     */
    @Test
    fun aHandDrawnLassoLeavesThePieceItDidNotCircle() {
        val pieces = cutInHalf()
        val right = pieces.maxByOrNull { it.pageBounds!!.left }!!

        val selection = pieces.selectWithLasso(handDrawnLoop(right = 45f))

        assertFalse(
            "the piece outside the loop was selected too",
            right.projectionKey in selection!!.projections,
        )
        assertTrue(
            "the selection rectangle reached across the erased gap to the other piece",
            selection.bounds.right < right.pageBounds!!.left,
        )
    }

    /** And the loop that does circle both still takes both — narrowing is not a cap of one. */
    @Test
    fun aLassoRoundTheWholeCutLineTakesBothPieces() {
        val pieces = cutInHalf()

        val selection = pieces.selectWithLasso(handDrawnLoop(right = 99f))

        assertEquals(pieces.map { it.projectionKey }.toSet(), selection?.projections)
    }

    /** The half that must not regress: a loop over part of a piece still leaves it alone. */
    @Test
    fun aHandDrawnLassoLeavesAPieceItOnlyHalfCoversAlone() {
        // The left piece runs from x=7 to x=41; this loop reaches x=25, cutting it in the middle.
        assertNull(cutInHalf().selectWithLasso(handDrawnLoop(right = 25f)))
    }

    /** And a loop over empty paper beside the ink stays empty. */
    @Test
    fun aHandDrawnLassoOverThePaperBetweenTwoPiecesSelectsNothing() {
        assertNull(
            cutInHalf().selectWithLasso(
                listOf(
                    InkPoint(44f, 30f),
                    InkPoint(56f, 30f),
                    InkPoint(55f, 50f),
                    InkPoint(56f, 70f),
                    InkPoint(44f, 70f),
                ),
            ),
        )
    }

    /** The convex shortcut answered this one correctly all along, and still does. */
    @Test
    fun aConvexLassoSelectsAPieceOfAnErasedStroke() {
        val selection = cutInHalf().selectWithLasso(
            listOf(
                InkPoint(0f, 30f),
                InkPoint(45f, 30f),
                InkPoint(45f, 70f),
                InkPoint(0f, 70f),
            ),
        )

        assertEquals(setOf("stroke"), selection?.targetIds)
        assertEquals(1, selection?.projections?.size)
    }

    /** The control: the same hand-drawn loop over ink the eraser never touched. */
    @Test
    fun aHandDrawnLassoSelectsAStrokeThatWasNeverErased() {
        val whole = listOf(
            PageStroke("stroke", InkCodec.eraseMask(inputs(10f to 50f, 38f to 50f), 6f)),
        )

        assertNotNull(whole.selectWithLasso(handDrawnLoop(right = 45f)))
    }

    /**
     * A stored move replays through the same test, so a page whose ink was erased and then moved
     * came back on the next open with the pieces left where they had been.
     *
     * This is also the half that used to *contradict* the selection above: replay re-identifies its
     * targets geometrically and so moved one piece, while the widened gesture had moved both on
     * screen. The live page and the reloaded page now agree because both ask the same question.
     */
    @Test
    fun aReplayedMoveReachesAPieceOfAnErasedStroke() {
        val path = handDrawnLoop(right = 45f)

        val moved = cutInHalf().replayMove(path, targetIds = listOf("stroke"), dx = 5f, dy = 7f)

        val left = moved.minByOrNull { it.pageBounds!!.left }!!
        val right = moved.maxByOrNull { it.pageBounds!!.left }!!
        assertEquals(5f, left.offsetX, 0.001f)
        assertEquals(7f, left.offsetY, 0.001f)
        assertEquals("the piece outside the loop stayed put", 0f, right.offsetX, 0.001f)
    }

    /**
     * Concave — one dent on the right edge, clear of the ink — because only a non-convex loop
     * reaches the exact test, and a loop drawn by hand is never convex.
     */
    private fun handDrawnLoop(right: Float): List<InkPoint> = listOf(
        InkPoint(0f, 30f),
        InkPoint(right, 30f),
        InkPoint(right - 1f, 45f),
        InkPoint(right, 50f),
        InkPoint(right - 1f, 55f),
        InkPoint(right, 70f),
        InkPoint(0f, 70f),
    )

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) ->
            add(InputToolType.UNKNOWN, x, y, index * 10L)
        }
    }.toImmutable()
}
