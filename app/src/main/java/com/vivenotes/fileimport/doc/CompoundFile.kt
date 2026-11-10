package com.vivenotes.fileimport.doc

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import java.io.File

/**
 * An OLE compound file: a small file system inside one file, which is what a `.doc` is and what Office
 * wraps an encrypted `.docx` in.
 *
 * It is a file the user picked and every number in it is the file's word for it, so sector chains are
 * bounded by how many sectors could exist, which also stops a chain that loops.
 */
internal class CompoundFile private constructor(private val bytes: ByteArray) {
    private val sectorShift = bytes.u16(SECTOR_SHIFT)
    private val miniShift = bytes.u16(MINI_SHIFT)
    private val sectorSize = 1 shl sectorShift
    private val miniSize = 1 shl miniShift
    private val miniCutoff = bytes.i32(MINI_CUTOFF)
    private val sectorCount = (bytes.size ushr sectorShift).coerceAtLeast(1)

    private val fat: IntArray = readFat()
    private val entries: List<Entry> = readDirectory()
    private val miniFat: IntArray = toInts(chainBytes(bytes.i32(FIRST_MINI_FAT), fat, Int.MAX_VALUE))
    private val miniStream: ByteArray = entries.firstOrNull()?.let { chainBytes(it.start, fat, it.size) } ?: ByteArray(0)

    /** The streams directly in the root, by lowercased name; a `.doc`'s are all there. */
    private val root: Map<String, Entry> = readRoot()

    private class Entry(val name: String, val type: Int, val left: Int, val right: Int, val child: Int, val start: Int, val size: Int)

    fun has(name: String): Boolean = name.lowercase() in root

    /** The named stream's bytes, or null when there is none. */
    fun stream(name: String): ByteArray? {
        val entry = root[name.lowercase()] ?: return null
        return if (entry.size < miniCutoff) miniBytes(entry.start, entry.size) else chainBytes(entry.start, fat, entry.size)
    }

    private fun sectorOffset(sector: Int): Int = (sector + 1) shl sectorShift

    private fun toInts(data: ByteArray): IntArray = IntArray(data.size / 4) { data.i32(it * 4) }

    private fun readFat(): IntArray {
        val fatCount = bytes.i32(FAT_COUNT)
        if (fatCount <= 0 || fatCount > sectorCount) throw ImportException(ImportFailure.Unreadable)
        val entriesPerSector = sectorSize / 4
        val sectors = ArrayList<Int>(fatCount)
        for (index in 0 until minOf(HEADER_DIFAT, fatCount)) sectors += bytes.i32(DIFAT + index * 4)
        var next = bytes.i32(FIRST_DIFAT)
        var guard = 0
        while (next >= 0 && sectors.size < fatCount && guard++ < sectorCount) {
            val offset = sectorOffset(next)
            for (index in 0 until entriesPerSector - 1) {
                if (sectors.size < fatCount) sectors += bytes.i32(offset + index * 4)
            }
            next = bytes.i32(offset + (entriesPerSector - 1) * 4)
        }
        val table = IntArray(sectors.size * entriesPerSector) { FREE }
        sectors.forEachIndexed { sector, location ->
            if (location < 0) return@forEachIndexed
            val offset = sectorOffset(location)
            for (index in 0 until entriesPerSector) table[sector * entriesPerSector + index] = bytes.i32(offset + index * 4)
        }
        return table
    }

    /** The chain from [start], at most [limit] bytes of it; short if the file runs out first. */
    private fun chainBytes(start: Int, table: IntArray, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit.toLong(), MAX_INITIAL).toInt())
        var sector = start
        var count = 0
        var remaining = limit
        while (sector >= 0 && sector < table.size && remaining > 0 && count++ <= sectorCount) {
            val offset = sectorOffset(sector)
            val length = minOf(sectorSize, remaining, bytes.size - offset)
            if (length <= 0) break
            out.write(bytes, offset, length)
            remaining -= length
            sector = table[sector]
        }
        return out.toByteArray()
    }

    private fun miniBytes(start: Int, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(size.coerceAtLeast(0))
        var sector = start
        var count = 0
        var remaining = size
        while (sector >= 0 && sector < miniFat.size && remaining > 0 && count++ <= miniFat.size) {
            val offset = sector shl miniShift
            val length = minOf(miniSize, remaining, miniStream.size - offset)
            if (length <= 0) break
            out.write(miniStream, offset, length)
            remaining -= length
            sector = miniFat[sector]
        }
        return out.toByteArray()
    }

    private fun readDirectory(): List<Entry> {
        val data = chainBytes(bytes.i32(FIRST_DIRECTORY), fat, Int.MAX_VALUE)
        return List(data.size / ENTRY_SIZE) { index ->
            val at = index * ENTRY_SIZE
            val nameBytes = data.u16(at + NAME_LENGTH).coerceIn(2, MAX_NAME_BYTES)
            Entry(
                name = String(data, at, nameBytes - 2, Charsets.UTF_16LE),
                type = data.u8(at + ENTRY_TYPE),
                left = data.i32(at + LEFT),
                right = data.i32(at + RIGHT),
                child = data.i32(at + CHILD),
                start = data.i32(at + START),
                size = data.i32(at + SIZE).coerceAtLeast(0),
            )
        }
    }

    /** The root's children, which are a tree sorted by name; only the membership matters here. */
    private fun readRoot(): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        val first = entries.firstOrNull()?.child ?: return out
        val visited = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        pending.addLast(first)
        while (pending.isNotEmpty()) {
            val index = pending.removeLast()
            if (index < 0 || index >= entries.size || !visited.add(index)) continue
            val entry = entries[index]
            if (entry.type == STREAM || entry.type == STORAGE) out[entry.name.lowercase()] = entry
            pending.addLast(entry.left)
            pending.addLast(entry.right)
        }
        return out
    }

    companion object {
        const val WORD_DOCUMENT = "WordDocument"
        const val ENCRYPTED_PACKAGE = "EncryptedPackage"

        private val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        private const val MAX_FILE_BYTES = 256L * 1024 * 1024
        private const val MAX_INITIAL = 1L shl 20
        private const val HEADER_DIFAT = 109
        private const val FREE = -1
        private const val STORAGE = 1
        private const val STREAM = 2

        private const val SECTOR_SHIFT = 30
        private const val MINI_SHIFT = 32
        private const val FAT_COUNT = 44
        private const val FIRST_DIRECTORY = 48
        private const val MINI_CUTOFF = 56
        private const val FIRST_MINI_FAT = 60
        private const val FIRST_DIFAT = 68
        private const val DIFAT = 76

        private const val ENTRY_SIZE = 128
        private const val MAX_NAME_BYTES = 64
        private const val NAME_LENGTH = 64
        private const val ENTRY_TYPE = 66
        private const val LEFT = 68
        private const val RIGHT = 72
        private const val CHILD = 76
        private const val START = 116
        private const val SIZE = 120

        fun isCompoundFile(file: File): Boolean = file.inputStream().use { input ->
            val head = ByteArray(SIGNATURE.size)
            input.read(head) == SIGNATURE.size && head.contentEquals(SIGNATURE)
        }

        fun open(file: File): CompoundFile {
            if (file.length() > MAX_FILE_BYTES) throw ImportException(ImportFailure.TooLarge)
            return parse(file.readBytes())
        }

        /** Throws [ImportException] for a file whose structure can't be made out. */
        fun parse(bytes: ByteArray): CompoundFile = try {
            val shift = bytes.u16(SECTOR_SHIFT)
            if (shift != 9 && shift != 12) throw ImportException(ImportFailure.Unreadable)
            CompoundFile(bytes)
        } catch (outside: IndexOutOfBoundsException) {
            throw ImportException(ImportFailure.Unreadable, outside)
        }
    }
}

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

internal fun ByteArray.s16(at: Int): Int = u16(at).toShort().toInt()

internal fun ByteArray.i32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)
