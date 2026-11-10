package com.vivenotes.fileimport.doc

/** A shape on the drawing layer, as far as the page needs to know. */
internal class DocShape(
    val picture: Int,
    val type: Int,
    val fill: Int?,
    val line: Int?,
    val insets: IntArray,
    /** Its place in the drawing's stacking order; later ones are painted over earlier ones. */
    val order: Int,
)

/**
 * The drawing layer: Office's own record format, shared with Excel and PowerPoint.
 *
 * It is read for pictures and for the look of text boxes. A picture is kept only if Android can decode
 * it; a metafile comes back as null, and the document shows the box where it would have been.
 */
internal class DocArt(private val table: ByteArray, private val word: ByteArray, fib: DocFib) {
    private val blips = ArrayList<ByteArray?>()
    private val shapes = HashMap<Int, DocShape>()

    init {
        val start = fib.offset(DocTable.DRAWINGS)
        val length = fib.length(DocTable.DRAWINGS)
        if (length > 8) runCatching { read(start, start + length) }
    }

    fun shape(id: Int): DocShape? = shapes[id]

    /** The picture numbered [index] in the file's store, counted from one. Null if it can't be decoded. */
    fun picture(index: Int): ByteArray? = blips.getOrNull(index - 1)

    private fun read(start: Int, end: Int) {
        val groupEnd = start + HEADER + table.i32(start + 4)
        records(table, start + HEADER, groupEnd) { type, _, at, recordEnd ->
            if (type == STORE) records(table, at, recordEnd) { entryType, instance, entryAt, entryEnd -> blips += storeEntry(table, entryType, instance, entryAt, entryEnd) }
        }
        var at = groupEnd
        while (at + 1 + HEADER <= end) {
            val recordEnd = at + 1 + HEADER + table.i32(at + 1 + 4)
            if (recordEnd > end) return
            drawing(at + 1 + HEADER, recordEnd)
            at = recordEnd
        }
    }

    private fun drawing(from: Int, to: Int) {
        records(table, from, to) { type, _, at, end ->
            when (type) {
                GROUP_CONTAINER -> drawing(at, end)
                SHAPE_CONTAINER -> shape(at, end)
            }
        }
    }

    private fun shape(from: Int, to: Int) {
        var id = -1
        var shapeType = 0
        var picture = 0
        var fill: Int? = null
        var line: Int? = null
        var filled = true
        var lined = true
        val insets = intArrayOf(DEFAULT_INSET_X, DEFAULT_INSET_Y, DEFAULT_INSET_X, DEFAULT_INSET_Y)
        records(table, from, to) { type, recordInstance, at, _ ->
            when (type) {
                SHAPE -> {
                    id = table.i32(at)
                    shapeType = recordInstance
                }
                PROPERTIES -> for (index in 0 until recordInstance) {
                    val property = table.u16(at + index * 6) and 0x3FFF
                    val value = table.i32(at + index * 6 + 2)
                    when (property) {
                        PROP_PICTURE -> picture = value
                        PROP_FILL_COLOR -> fill = colorOf(value)
                        PROP_LINE_COLOR -> line = colorOf(value)
                        PROP_FILL_FLAGS -> if (value and 0x00100000 != 0) filled = value and 0x10 != 0
                        PROP_LINE_FLAGS -> if (value and 0x00080000 != 0) lined = value and 0x08 != 0
                        PROP_INSET_LEFT -> insets[0] = value
                        PROP_INSET_TOP -> insets[1] = value
                        PROP_INSET_RIGHT -> insets[2] = value
                        PROP_INSET_BOTTOM -> insets[3] = value
                    }
                }
            }
        }
        if (id != -1) {
            val twips = IntArray(4) { insets[it] / EMU_PER_TWIP }
            shapes[id] = DocShape(picture, shapeType, fill.takeIf { filled }, line.takeIf { lined }, twips, shapes.size)
        }
    }

    /** One entry of the picture store: a picture inside it, or a reference out to where it was put. */
    private fun storeEntry(bytes: ByteArray, type: Int, instance: Int, at: Int, end: Int): ByteArray? {
        if (type != STORE_ENTRY) return blip(bytes, type, instance, at, end)
        val nameLength = bytes.u8(at + 33)
        val inline = at + ENTRY_NAME + nameLength
        if (inline + HEADER <= end) return nested(bytes, inline, end)
        val delayed = bytes.i32(at + ENTRY_DELAY)
        if (delayed < 0 || delayed + HEADER > word.size) return null
        return nested(word, delayed, word.size)
    }

    private fun nested(bytes: ByteArray, at: Int, end: Int): ByteArray? {
        val head = bytes.u16(at)
        val length = bytes.i32(at + 4)
        return blip(bytes, bytes.u16(at + 2), head ushr 4, at + HEADER, minOf(end, at + HEADER + length))
    }

    /** The first picture among the records in [from]..[to]; what an in-line picture carries. */
    fun inlineBlip(bytes: ByteArray, from: Int, to: Int): ByteArray? {
        var found: ByteArray? = null
        var seen = false
        records(bytes, from, to) { type, instance, at, end ->
            if (seen) return@records
            when {
                type == STORE_ENTRY -> {
                    found = storeEntry(bytes, type, instance, at, end)
                    seen = true
                }
                type in BLIP_TYPES -> {
                    found = blip(bytes, type, instance, at, end)
                    seen = true
                }
            }
        }
        return found
    }

    private fun blip(bytes: ByteArray, type: Int, instance: Int, at: Int, end: Int): ByteArray? {
        val identifiers = if (instance in TWO_IDENTIFIERS) 32 else 16
        return when (type) {
            BLIP_JPEG, BLIP_JPEG_CMYK, BLIP_PNG -> bytes.copyOfRange(at + identifiers + 1, end)
            BLIP_DIB -> withBitmapHeader(bytes.copyOfRange(at + identifiers + 1, end))
            else -> null
        }
    }

    /** A DIB is a bitmap file without its first fourteen bytes, and a decoder wants those. */
    private fun withBitmapHeader(dib: ByteArray): ByteArray? {
        if (dib.size < 40) return null
        val headerSize = dib.i32(0)
        val bits = dib.u16(14)
        val used = dib.i32(32)
        val palette = if (bits <= 8) (if (used != 0) used else 1 shl bits) * 4 else 0
        val pixels = 14 + headerSize + palette
        val out = ByteArray(14 + dib.size)
        out[0] = 'B'.code.toByte()
        out[1] = 'M'.code.toByte()
        putInt(out, 2, out.size)
        putInt(out, 10, pixels)
        dib.copyInto(out, 14)
        return out
    }

    private fun putInt(bytes: ByteArray, at: Int, value: Int) {
        for (index in 0 until 4) bytes[at + index] = (value ushr (index * 8)).toByte()
    }

    private fun colorOf(value: Int): Int {
        val red = value and 0xFF
        val green = (value ushr 8) and 0xFF
        val blue = (value ushr 16) and 0xFF
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private companion object {
        const val HEADER = 8
        const val STORE = 0xF001
        const val GROUP_CONTAINER = 0xF003
        const val SHAPE_CONTAINER = 0xF004
        const val STORE_ENTRY = 0xF007
        const val SHAPE = 0xF00A
        const val PROPERTIES = 0xF00B

        const val BLIP_JPEG = 0xF01D
        const val BLIP_PNG = 0xF01E
        const val BLIP_DIB = 0xF01F
        const val BLIP_JPEG_CMYK = 0xF02A
        val BLIP_TYPES = setOf(0xF01A, 0xF01B, 0xF01C, BLIP_JPEG, BLIP_PNG, BLIP_DIB, 0xF029, BLIP_JPEG_CMYK)

        /** Instances that mark a picture stored with a second identifier ahead of its bytes. */
        val TWO_IDENTIFIERS = setOf(0x46B, 0x6E3, 0x6E1, 0x7A9, 0x6E5)

        const val ENTRY_NAME = 36
        const val ENTRY_DELAY = 28

        const val PROP_INSET_LEFT = 0x0081
        const val PROP_INSET_TOP = 0x0082
        const val PROP_INSET_RIGHT = 0x0083
        const val PROP_INSET_BOTTOM = 0x0084
        const val PROP_PICTURE = 0x0104
        const val PROP_FILL_COLOR = 0x0181
        const val PROP_FILL_FLAGS = 0x01BF
        const val PROP_LINE_COLOR = 0x01C0
        const val PROP_LINE_FLAGS = 0x01FF
        const val DEFAULT_INSET_X = 91440
        const val DEFAULT_INSET_Y = 45720
        const val EMU_PER_TWIP = 635

        /** Visits records, handing each its type, instance, where its body starts and where it ends. */
        inline fun records(bytes: ByteArray, from: Int, to: Int, visit: (Int, Int, Int, Int) -> Unit) {
            var at = from
            while (at + HEADER <= to) {
                val instance = bytes.u16(at) ushr 4
                val type = bytes.u16(at + 2)
                val length = bytes.i32(at + 4)
                val body = at + HEADER
                if (length < 0 || body + length > to) return
                visit(type, instance, body, body + length)
                at = body + length
            }
        }
    }
}
