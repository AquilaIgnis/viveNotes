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
        val erased = original.subtract(mask, listOf("stroke")).single().stroke

        assertTrue(erased.shape.overlaps(box(20f, 48f, 30f, 52f)))
        assertFalse(erased.shape.overlaps(box(48f, 48f, 52f, 52f)))
        assertTrue(erased.shape.overlaps(box(70f, 48f, 80f, 52f)))
    }

    @Test
    fun replayTargetsProtectInkDrawnAfterTheErase() {
        val oldInk = InkCodec.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 6f)
        val newInk = InkCodec.eraseMask(inputs(50f to 10f, 50f to 90f), sizeDp = 6f)
        val mask = InkCodec.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        val page = listOf(PageStroke("old", oldInk), PageStroke("new", newInk))

        val replayed = page.subtract(mask, targetIds = listOf("old"))

        assertFalse(replayed.first { it.id == "old" }.stroke.shape.overlaps(box(48f, 48f, 52f, 52f)))
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

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) ->
            add(InputToolType.UNKNOWN, x, y, index * 10L)
        }
    }.toImmutable()

    private fun box(left: Float, top: Float, right: Float, bottom: Float): ImmutableBox =
        ImmutableBox.fromTwoPoints(ImmutableVec(left, top), ImmutableVec(right, bottom))

    private fun androidx.ink.geometry.PartitionedMesh.overlaps(area: ImmutableBox): Boolean =
        computeCoverageIsGreaterThan(area, 0f)
}
