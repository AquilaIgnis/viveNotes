package com.vivenotes.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.vivenotes.model.ocr.TextDetection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One line read out of a picture, with where it was and how sure the recognizer was. */
data class ImageTextLine(
    val text: String,
    val confidence: Float,
    /** Corners in source-picture pixels, clockwise from the top-left. */
    val corners: List<TextDetection.Point>,
)

/** Everything read out of one picture — `memory/imageOcrPlan.md` IO4. */
data class ImageTextResult(
    val lines: List<ImageTextLine>,
    val meanConfidence: Float,
) {
    val text: String get() = lines.joinToString("\n") { it.text }

    companion object {
        val Empty = ImageTextResult(emptyList(), 0f)
    }
}

/** The detector's input tensor and the size it was built at, which the boxes come back in. */
internal data class DetectionTensor(val values: FloatArray, val width: Int, val height: Int)

/**
 * PP-OCRv5 detection preprocessing: fit a side limit, round to a multiple of 32, ImageNet-normalize.
 *
 * The multiple of 32 is the network's own requirement — it downsamples by 32 and upsamples back —
 * and `limit_side_len` is what keeps a 12-megapixel photograph from being fed in whole. 960 is
 * PaddleOCR's default and was kept: 640 was equally accurate and a third faster over
 * `simulations/image-ocr/`, but every sample in that corpus is comfortably large type, so the corpus
 * cannot see the case 960 exists for.
 */
internal fun preprocessDetection(image: Bitmap, limit: Int = DETECTION_LIMIT): DetectionTensor {
    require(image.width > 0 && image.height > 0) { "Detection image is empty" }
    val longest = max(image.width, image.height)
    val ratio = if (longest > limit) limit.toFloat() / longest else 1f
    val width = alignTo32(image.width * ratio)
    val height = alignTo32(image.height * ratio)
    val resized = Bitmap.createScaledBitmap(image, width, height, true)
    val pixels = IntArray(width * height)
    resized.getPixels(pixels, 0, width, 0, 0, width, height)
    if (resized !== image) resized.recycle()

    val plane = width * height
    val values = FloatArray(plane * 3)
    pixels.forEachIndexed { index, color ->
        values[index] = (Color.red(color) / 255f - DETECTION_MEAN_R) / DETECTION_STD_R
        values[plane + index] = (Color.green(color) / 255f - DETECTION_MEAN_G) / DETECTION_STD_G
        values[plane * 2 + index] = (Color.blue(color) / 255f - DETECTION_MEAN_B) / DETECTION_STD_B
    }
    return DetectionTensor(values, width, height)
}

/**
 * Warps one detected quad to a horizontal strip 48 pixels tall — what the recognizer eats.
 *
 * `setPolyToPoly` with four points is a full perspective transform, which is what a photographed
 * page needs: its lines are not merely rotated, they are foreshortened.
 *
 * **The quad's long side becomes the strip's width**, so a line written down the page is read along
 * its own axis rather than squeezed across it. Which *end* of that axis comes first cannot be known
 * without an orientation classifier, so it is fixed rather than guessed: the corner after the
 * top-left leads. For the English dictionary this app bundles, sideways text is a curiosity and
 * upside-down text is not worth a second model.
 */
internal fun cropQuad(image: Bitmap, quad: TextDetection.Quad, height: Int = CROP_HEIGHT): Bitmap? {
    val longSide = max(quad.width, quad.height)
    val shortSide = min(quad.width, quad.height)
    if (longSide < 2f || shortSide < 1f) return null
    val width = (height * longSide / shortSide).roundToInt().coerceIn(MIN_CROP_WIDTH, MAX_CROP_WIDTH)

    // Rotate the source corners so the long side is the one that maps to the strip's width.
    val ordered = if (quad.width >= quad.height) quad.corners else {
        listOf(quad.corners[1], quad.corners[2], quad.corners[3], quad.corners[0])
    }
    val source = FloatArray(8)
    ordered.forEachIndexed { index, point ->
        source[index * 2] = point.x
        source[index * 2 + 1] = point.y
    }
    val destination = floatArrayOf(
        0f, 0f,
        width.toFloat(), 0f,
        width.toFloat(), height.toFloat(),
        0f, height.toFloat(),
    )
    val matrix = Matrix()
    if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return null

    val strip = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(strip)
    // Paper-coloured, so a quad that reaches past the edge of the picture is padded with something
    // the recognizer reads as background rather than as a black bar down the line.
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(image, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return strip
}

/**
 * Whether a reading is worth storing at all.
 *
 * A bullet glyph, a rule or a stray tick is a real text region and recognizes as one or two marks.
 * It is a true reading and it is useless to search — three of the seven "lines" the slide sample
 * produced were its bullet ellipses — so it is dropped here rather than written to the database.
 */
internal fun isSearchableReading(text: String): Boolean = text.any { it.isLetterOrDigit() }

private fun alignTo32(value: Float): Int =
    max(32, (value / 32f).roundToInt() * 32)

/** PaddleOCR's `limit_side_len` for detection. See [preprocessDetection]. */
internal const val DETECTION_LIMIT = 960

/** The recognizer's fixed input height, shared with `preprocessText`. */
internal const val CROP_HEIGHT = 48

private const val MIN_CROP_WIDTH = 8
private const val MAX_CROP_WIDTH = 3200

// ImageNet statistics, which is what the detector was trained with — not the [-1, 1] the recognizer
// uses. Feeding one model the other's normalization is silent and produces an empty page.
private const val DETECTION_MEAN_R = 0.485f
private const val DETECTION_MEAN_G = 0.456f
private const val DETECTION_MEAN_B = 0.406f
private const val DETECTION_STD_R = 0.229f
private const val DETECTION_STD_G = 0.224f
private const val DETECTION_STD_B = 0.225f
