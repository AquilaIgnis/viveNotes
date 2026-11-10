package com.vivenotes.model.ocr

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where one recognized line sits on its picture.
 *
 * Everything is a fraction of the picture rather than a pixel, so it stays right whichever size the
 * picture is decoded at and however its frame has been stretched on the page.
 */
class ImageLineLayout(
    /**
     * x then y for each of the four corners. The first two corners run along the line's top in
     * reading order and the last two come back along its bottom, so `[0]→[1]` is the text's direction.
     */
    val corners: FloatArray,
    /** Character boundaries along the line, one more than there are characters. See `CtcReading.edges`. */
    val edges: FloatArray,
)

/**
 * The binary form stored in `attachment_text.layout`: 16-bit fixed point, one header byte.
 *
 * Binary rather than JSON because one number per character boundary is the bulk of it. A 40-line
 * screenshot is about 5 KB this way, and decoding it is a single pass over a buffer.
 *
 * 16 bits is a thirty-second of a pixel on the largest picture the app keeps.
 */
object ImageTextLayout {

    private const val FORMAT = 1
    private const val SCALE = 65_535f
    private const val CORNER_VALUES = 8

    fun encode(lines: List<ImageLineLayout>): ByteArray {
        var size = 3
        lines.forEach { line -> size += (CORNER_VALUES + 1 + line.edges.size) * 2 }
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(FORMAT.toByte())
        out.putShort(lines.size.toShort())
        lines.forEach { line ->
            line.corners.forEach { value -> out.putFraction(value) }
            out.putShort(line.edges.size.toShort())
            line.edges.forEach { value -> out.putFraction(value) }
        }
        return out.array()
    }

    /** Null for a blob this build cannot read, which then highlights nothing rather than crashing. */
    fun decode(bytes: ByteArray): List<ImageLineLayout>? {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            if (input.get().toInt() != FORMAT) return null
            val lineCount = input.getShort().toInt() and 0xFFFF
            List(lineCount) {
                val corners = FloatArray(CORNER_VALUES) { input.getFraction() }
                val edgeCount = input.getShort().toInt() and 0xFFFF
                ImageLineLayout(corners, FloatArray(edgeCount) { input.getFraction() })
            }
        } catch (_: BufferUnderflowException) {
            null
        }
    }

    private fun ByteBuffer.putFraction(value: Float) {
        putShort((value.coerceIn(0f, 1f) * SCALE + 0.5f).toInt().toShort())
    }

    private fun ByteBuffer.getFraction(): Float = (getShort().toInt() and 0xFFFF) / SCALE
}
