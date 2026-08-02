package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.data.EraserMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exact geometry and storage behavior behind the normal eraser. */
@RunWith(AndroidJUnit4::class)
class InkEraserTest {

    @Test
    fun normalEraserRemovesOnlyTheCrossedPartOfAStroke() {
        val ink = InkCodec.eraseMask(
            inputs = inputs(10f to 50f, 90f to 50f),
            sizeDp = 6f,
        )
        val mask = InkCodec.eraseMask(
            inputs = inputs(50f to 35f, 50f to 65f),
            sizeDp = 18f,
        )
        val original = listOf(PageStroke("stroke", ink))

        assertEquals(listOf("stroke"), original.targetsFor(mask))
        val erased = original.subtract(mask, listOf("stroke")).map(PageStroke::stroke)

        assertTrue(erased.any { it.shape.overlaps(box(20f, 48f, 30f, 52f)) })
        assertFalse(erased.any { it.shape.overlaps(box(48f, 48f, 52f, 52f)) })
        assertTrue(erased.any { it.shape.overlaps(box(70f, 48f, 80f, 52f)) })
    }

    @Test
    fun replayTargetsProtectInkDrawnAfterTheErase() {
        val oldInk = InkCodec.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 6f)
        val newInk = InkCodec.eraseMask(inputs(50f to 10f, 50f to 90f), sizeDp = 6f)
        val mask = InkCodec.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        val page = listOf(PageStroke("old", oldInk), PageStroke("new", newInk))

        val replayed = page.subtract(mask, targetIds = listOf("old"))

        assertFalse(replayed.filter { it.id == "old" }.any { it.stroke.shape.overlaps(box(48f, 48f, 52f, 52f)) })
        assertTrue(replayed.first { it.id == "new" }.stroke.shape.overlaps(box(48f, 48f, 52f, 52f)))
    }

    @Test
    fun objectEraserRemovesOnlyTheDisconnectedRegionItTouches() {
        val ink = InkCodec.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 6f)
        val separatingMask = InkCodec.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        val separated = listOf(PageStroke("stroke", ink))
            .subtract(separatingMask, targetIds = listOf("stroke"))
        val objectMask = InkCodec.eraseMask(inputs(20f to 45f, 20f to 55f), sizeDp = 12f)

        val erased = separated.eraseObjects(objectMask, targetIds = listOf("stroke")).single().stroke

        assertFalse(erased.shape.overlaps(box(20f, 48f, 30f, 52f)))
        assertFalse(erased.shape.overlaps(box(48f, 48f, 52f, 52f)))
        assertTrue(erased.shape.overlaps(box(70f, 48f, 80f, 52f)))
    }

    @Test
    fun eraseMaskRoundTripsForPageReload() {
        val mask = InkCodec.eraseMask(
            inputs = inputs(12f to 34f, 56f to 78f),
            sizeDp = 23f,
        )

        val row = InkCodec.encodeErase(
            mask,
            pageId = "page",
            mode = EraserMode.Object,
            now = 42L,
        )
        val restored = InkCodec.decodeErase(row)

        assertNotNull(restored)
        assertEquals(23f, restored!!.brush.size, 0.001f)
        assertEquals(mask.inputs.size, restored.inputs.size)
        assertEquals(EraserMode.Object, row.mode)
        assertEquals(42L, row.createdAt)
    }

    @Test
    fun lassoSelectsAndMovesOnlyTheObjectWhoseCenterItEncloses() {
        val left = PageStroke("left", InkCodec.eraseMask(inputs(20f to 50f, 40f to 50f), 6f))
        val right = PageStroke("right", InkCodec.eraseMask(inputs(120f to 50f, 140f to 50f), 6f))
        val page = listOf(left, right)
        val path = rectangle(5f, 30f, 60f, 70f)

        val selection = page.selectWithLasso(path)!!
        val moved = page.moveSelected(
            InkLassoMove(
                path = path,
                targetIds = selection.targetIds,
                projections = selection.projections,
                dx = 50f,
                dy = 20f,
            ),
        )

        assertEquals(setOf("left"), selection.targetIds)
        assertEquals(50f, moved.first { it.id == "left" }.offsetX, 0.001f)
        assertEquals(20f, moved.first { it.id == "left" }.offsetY, 0.001f)
        assertEquals(0f, moved.first { it.id == "right" }.offsetX, 0.001f)
    }

    @Test
    fun touchingOneGroupedStrokeSelectsTheWholeGroupAndUsesOneUnionBounds() {
        val left = PageStroke(
            "left",
            InkCodec.eraseMask(inputs(20f to 50f, 40f to 50f), 6f),
            groupId = "group",
        )
        val right = PageStroke(
            "right",
            InkCodec.eraseMask(inputs(120f to 50f, 140f to 50f), 6f),
            groupId = "group",
        )

        val selection = listOf(left, right).selectWithLasso(rectangle(5f, 30f, 60f, 70f))!!

        assertEquals(setOf("left", "right"), selection.targetIds)
        assertTrue(selection.bounds.left < 20f)
        assertTrue(selection.bounds.right > 140f)
    }

    @Test
    fun aTightLassoSelectsAnInnerObjectWithoutItsLargerSurround() {
        val outer = PageStroke(
            "outer",
            InkCodec.eraseMask(inputs(10f to 10f, 100f to 100f), 6f),
        )
        val inner = PageStroke(
            "inner",
            InkCodec.eraseMask(inputs(47f to 47f, 57f to 57f), 6f),
        )

        val selection = listOf(outer, inner).selectWithLasso(rectangle(40f, 40f, 65f, 65f))

        assertEquals(setOf("inner"), selection?.targetIds)
    }

    @Test
    fun aTightCurvedLassoUsesTheInkOutlineInsteadOfEmptyBoundingBoxCorners() {
        val triangle = PageStroke(
            "triangle",
            InkCodec.eraseMask(
                inputs(50f to 20f, 40f to 50f, 60f to 50f, 50f to 20f),
                4f,
            ),
        )
        // This encloses the rendered triangle closely. The top-left and top-right corners of the
        // triangle's rectangular bounds are deliberately outside this triangular lasso.
        val tightLasso = listOf(
            InkPoint(50f, 14f),
            InkPoint(34f, 56f),
            InkPoint(66f, 56f),
        )

        val selection = listOf(triangle).selectWithLasso(tightLasso, edgeTolerance = 3f)

        assertEquals(setOf("triangle"), selection?.targetIds)
    }

    @Test
    fun replayedLassoMoveCanMoveOneDisconnectedProjectionWithASharedRowId() {
        val left = PageStroke("stroke", InkCodec.eraseMask(inputs(20f to 50f, 40f to 50f), 6f))
        val right = PageStroke("stroke", InkCodec.eraseMask(inputs(120f to 50f, 140f to 50f), 6f))

        val moved = listOf(left, right).replayMove(
            path = rectangle(5f, 30f, 60f, 70f),
            targetIds = listOf("stroke"),
            dx = 80f,
            dy = 0f,
        )

        assertEquals(80f, moved[0].offsetX, 0.001f)
        assertEquals(0f, moved[1].offsetX, 0.001f)
    }

    @Test
    fun cornerResizeScalesSelectedInkAroundTheOppositeCorner() {
        val stroke = PageStroke("stroke", InkCodec.eraseMask(inputs(20f to 20f, 40f to 40f), 6f))
        val selection = listOf(stroke).selectWithLasso(rectangle(10f, 10f, 50f, 50f))!!

        val resized = listOf(stroke).resizeSelected(
            InkLassoResize(
                path = selection.path,
                targetIds = selection.targetIds,
                projections = selection.projections,
                anchor = InkPoint(10f, 10f),
                scaleX = 2f,
                scaleY = 1.5f,
            ),
        ).single()

        assertEquals(2f, resized.scaleX, 0.001f)
        assertEquals(1.5f, resized.scaleY, 0.001f)
        assertEquals(-10f, resized.offsetX, 0.001f)
        assertEquals(-5f, resized.offsetY, 0.001f)
    }

    @Test
    fun eraserGeometryFollowsAMovedStroke() {
        val ink = PageStroke(
            id = "stroke",
            stroke = InkCodec.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 6f),
            offsetX = 100f,
        )
        val movedMask = InkCodec.eraseMask(inputs(150f to 35f, 150f to 65f), sizeDp = 18f)
        val oldMask = InkCodec.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)

        assertEquals(listOf("stroke"), listOf(ink).targetsFor(movedMask))
        assertTrue(listOf(ink).targetsFor(oldMask).isEmpty())
        val erased = listOf(ink).subtract(movedMask, listOf("stroke"))
        assertTrue(erased.all { it.offsetX == 100f })
        assertFalse(erased.any { it.stroke.shape.overlaps(box(48f, 48f, 52f, 52f)) })
    }

    @Test
    fun lassoPathRoundTripsForPageReload() {
        val path = rectangle(10f, 20f, 80f, 90f)
        val row = InkCodec.encodeMove(path, "page", dx = 25f, dy = -5f, now = 42L)

        assertEquals(path, InkCodec.decodeMove(row))
        assertEquals(25f, row.dxDp, 0.001f)
        assertEquals(-5f, row.dyDp, 0.001f)
        assertEquals(42L, row.createdAt)
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) ->
            add(InputToolType.UNKNOWN, x, y, index * 10L)
        }
    }.toImmutable()

    private fun box(left: Float, top: Float, right: Float, bottom: Float): ImmutableBox =
        ImmutableBox.fromTwoPoints(ImmutableVec(left, top), ImmutableVec(right, bottom))

    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float): List<InkPoint> =
        listOf(
            InkPoint(left, top),
            InkPoint(right, top),
            InkPoint(right, bottom),
            InkPoint(left, bottom),
        )

    private fun androidx.ink.geometry.PartitionedMesh.overlaps(area: ImmutableBox): Boolean =
        computeCoverageIsGreaterThan(area, 0f)
}
