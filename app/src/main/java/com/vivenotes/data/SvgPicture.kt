package com.vivenotes.data

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import kotlin.math.roundToInt

/** A parsed SVG drawn as vectors. The attachment always retains the original file bytes. */
class SvgPicture private constructor(private val svg: SVG, private val source: SvgPictureSource) {
    val width: Int get() = source.width
    val height: Int get() = source.height

    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        if (width <= 0 || height <= 0) return
        val checkpoint = canvas.save()
        try {
            canvas.clipRect(left, top, left + width, top + height)
            canvas.translate(left, top)
            canvas.scale(width / source.viewportWidth, height / source.viewportHeight)
            svg.renderToCanvas(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    /** Temporary pixels for OCR or a search preview; never replaces the stored SVG. */
    fun bitmap(maxDimension: Int): Bitmap {
        val scale = maxDimension.coerceIn(1, AttachmentStore.MAX_DIMENSION).toFloat() / maxOf(width, height)
        val bitmap = Bitmap.createBitmap(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        try {
            draw(Canvas(bitmap), 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            return bitmap
        } catch (failure: Exception) {
            bitmap.recycle()
            throw failure
        }
    }

    companion object {
        internal fun read(bytes: ByteArray): SvgPicture? = runCatching {
            val source = SvgPictureSource.read(bytes, AttachmentStore.MAX_DIMENSION) ?: return null
            val svg = SVG.getFromString(source.text).apply {
                setDocumentWidth(source.viewportWidth)
                setDocumentHeight(source.viewportHeight)
            }
            SvgPicture(svg, source)
        }.getOrNull()
    }
}
