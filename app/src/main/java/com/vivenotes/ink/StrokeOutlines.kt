package com.vivenotes.ink

import android.os.Build
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import java.util.WeakHashMap

/**
 * Outlined copies of the strokes that have no outlines, for whatever draws from outlines.
 *
 * `Stroke.split` returns its pieces without outlines, so a piece of erased ink has nothing for an
 * outline-based draw to fill. Two kinds of drawing work that way:
 * - androidx.ink's path renderer. `CanvasStrokeRenderer` has nothing else before Android 14, where
 *   its mesh renderer arrives, so there every partly erased pen stroke drew as nothing.
 * - PDF export, which fills `outlinesToPath` on every version and so left those strokes out of every
 *   PDF.
 *
 * A copy is the piece put through `subtract` with a mask that cannot touch it. That rebuilds the
 * outlines and keeps the geometry: same coverage both ways, same bounding box.
 *
 * **Only drawing may use a copy.** Lasso and replay must keep each piece exactly as `split` returns
 * it. `LassoShape.contains` walks a stroke's outlines when it has some and falls back to
 * `encloses` when it has none, so a copy that reached the geometry would let an Android 12 device
 * replay a stored move differently from a tablet that never made one.
 */
internal object StrokeOutlines {

    /** Whether the screen's renderer draws from outlines, which it does before Android 14. */
    val screenDrawsFromOutlines: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Keyed by the piece, so a copy lasts exactly as long as the piece it stands for. */
    private val copies = WeakHashMap<Stroke, Stroke>()

    /** [stroke] itself when it has outlines; otherwise its outlined copy, made once and kept. */
    fun drawable(stroke: Stroke): Stroke {
        if (stroke.hasOutlines()) return stroke
        synchronized(copies) { copies[stroke] }?.let { return it }
        val copy = outlinedCopy(stroke)
        // Not kept when there was nothing to copy: a value holding its own key is never let go.
        if (copy === stroke) return stroke
        return synchronized(copies) { copies.getOrPut(stroke) { copy } }
    }

    /**
     * Makes the copies the screen will ask for, so drawing a page full of erased ink does not make
     * them all inside one frame. Called where pieces are made, which is off the main thread when a
     * page loads. Nothing to do where the screen draws meshes.
     */
    fun prepareForScreen(stroke: Stroke) {
        if (screenDrawsFromOutlines) drawable(stroke)
    }
}

/** [stroke] cut by a dot of eraser just past its bounding box: nothing removed, outlines rebuilt. */
@OptIn(ExperimentalInkEraserApi::class)
internal fun outlinedCopy(stroke: Stroke): Stroke {
    val box = stroke.shape.computeBoundingBox() ?: return stroke
    val x = box.xMax + MASK_GAP
    val y = box.yMax + MASK_GAP
    val dot = MutableStrokeInputBatch().apply {
        add(InputToolType.UNKNOWN, x, y, 0L)
        add(InputToolType.UNKNOWN, x + MASK_SIZE, y, 10L)
    }.toImmutable()
    return stroke.subtract(
        maskShape = InkCodec.eraseMask(dot, sizeDp = MASK_SIZE).shape,
        maskToWorldTransform = AffineTransform.IDENTITY,
        strokeToWorldTransform = AffineTransform.IDENTITY,
    )
}

internal fun Stroke.hasOutlines(): Boolean =
    (0 until shape.getRenderGroupCount()).any { group -> shape.getOutlineCount(group) > 0 }

/** In stroke units, which are page dp: well clear of anti-aliasing and of float error at any size. */
private const val MASK_GAP = 16f
private const val MASK_SIZE = 1f
