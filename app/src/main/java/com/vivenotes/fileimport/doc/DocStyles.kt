package com.vivenotes.fileimport.doc

/** One style of the stylesheet, with the formatting it adds to the style it is based on. */
private class DocStyle(val name: String, val builtIn: Int, val base: Int, val paragraph: ByteArray?, val character: ByteArray?)

/** The file's stylesheet. Lookups walk up through what a style is based on and remember the answer. */
internal class DocStyles(table: ByteArray, offset: Int, length: Int) {
    private val styles: List<DocStyle?> = read(table, offset, length)
    private val paragraphs = HashMap<Int, DocPara>()
    private val paragraphCharacters = HashMap<Int, DocChar>()
    private val ownCharacters = HashMap<Int, DocChar>()

    fun name(index: Int): String = styles.getOrNull(index)?.name.orEmpty().substringBefore(',')

    /** Which built-in style this is: 1 to 9 are the headings. */
    fun builtIn(index: Int): Int = styles.getOrNull(index)?.builtIn ?: -1

    private fun chainOf(index: Int): List<DocStyle> {
        val out = ArrayDeque<DocStyle>()
        var at = index
        while (out.size < MAX_CHAIN) {
            val style = styles.getOrNull(at) ?: break
            if (out.any { it === style }) break
            out.addFirst(style)
            at = style.base
        }
        return out
    }

    fun paragraph(index: Int): DocPara = paragraphs.getOrPut(index) {
        chainOf(index).fold(DocPara()) { para, style -> applyParagraph(para, style.paragraph, 2) }.copy(istd = index)
    }

    /** The character formatting that paragraph style [index] gives its text. */
    fun characters(index: Int): DocChar = paragraphCharacters.getOrPut(index) {
        chainOf(index).fold(DocChar()) { char, style -> applyCharacter(char, style.character, 0, char) }
    }

    /**
     * Character style [index] over a paragraph's formatting. Bold and the like are toggles when both
     * kinds of style set them, which is why a bold word in a bold heading comes out roman.
     */
    fun overCharacters(index: Int, base: DocChar): DocChar {
        val own = ownCharacters.getOrPut(index) {
            chainOf(index).fold(DocChar()) { char, style -> applyCharacter(char, style.character, 0, char) }
        }
        val applied = chainOf(index).fold(base) { char, style -> applyCharacter(char, style.character, 0, char) }
        return applied.copy(
            bold = base.bold xor own.bold,
            italic = base.italic xor own.italic,
            strike = base.strike xor own.strike,
            smallCaps = base.smallCaps xor own.smallCaps,
            caps = base.caps xor own.caps,
            vanish = base.vanish xor own.vanish,
        )
    }

    private companion object {
        const val MAX_CHAIN = 32
        const val PARAGRAPH_STYLE = 1
        const val CHARACTER_STYLE = 2

        fun read(table: ByteArray, offset: Int, length: Int): List<DocStyle?> {
            if (length < 4) return emptyList()
            val headerSize = table.u16(offset)
            val count = table.u16(offset + 2)
            val baseSize = table.u16(offset + 4)
            var at = offset + 2 + headerSize
            val end = offset + length
            val out = ArrayList<DocStyle?>(count)
            repeat(count) {
                if (at + 2 > end) return out
                val size = table.u16(at)
                out += if (size == 0) null else runCatching { readStyle(table, at + 2, size, baseSize) }.getOrNull()
                at += 2 + size
            }
            return out
        }

        private fun readStyle(table: ByteArray, start: Int, size: Int, baseSize: Int): DocStyle {
            val builtIn = table.u16(start) and 0x0FFF
            val kind = table.u16(start + 2) and 0x000F
            val base = table.u16(start + 2) ushr 4
            val nameAt = start + baseSize
            val nameLength = table.u16(nameAt)
            val name = String(table, nameAt + 2, nameLength * 2, Charsets.UTF_16LE)
            var at = nameAt + 2 + (nameLength + 1) * 2
            val limit = start + size
            fun nextBlock(): ByteArray? {
                if ((at - start) % 2 != 0) at++
                if (at + 2 > limit) return null
                val length = table.u16(at)
                val block = table.copyOfRange(at + 2, minOf(limit, at + 2 + length))
                at += 2 + length
                return block
            }
            val paragraph = if (kind == PARAGRAPH_STYLE) nextBlock() else null
            val character = if (kind == PARAGRAPH_STYLE || kind == CHARACTER_STYLE) nextBlock() else null
            return DocStyle(name, builtIn, base, paragraph, character)
        }
    }
}

internal class DocFont(val name: String, val family: String?)

/** The file's font table: runs name their font by its place in this list. */
internal fun readDocFonts(table: ByteArray, offset: Int, length: Int): List<DocFont> {
    if (length < 4) return emptyList()
    var at = offset
    val extended = table.u16(at) == 0xFFFF
    if (extended) at += 2
    val count = table.u16(at)
    at += 4
    val end = offset + length
    val out = ArrayList<DocFont>(count)
    repeat(count) {
        if (at >= end) return out
        val size = table.u8(at) + 1
        val name = StringBuilder()
        var char = at + FONT_NAME
        while (char + 2 <= minOf(at + size, end) && table.u16(char) != 0) {
            name.append(table.u16(char).toChar())
            char += 2
        }
        out += DocFont(name.toString(), FONT_FAMILIES.getOrNull((table.u8(at + 1) ushr 4) and 7))
        at += size
    }
    return out
}

private const val FONT_NAME = 40
private val FONT_FAMILIES = listOf(null, "roman", "swiss", "modern", "script", "decorative")

/** One level of a list: how it counts and what its label looks like. */
internal class DocListLevel(
    val start: Int,
    val format: Int,
    val legal: Boolean,
    val noRestart: Boolean,
    /** 0 is a tab after the label, 1 a space, 2 nothing. */
    val follow: Int,
    /** The label, with the characters below 9 standing for the number at that level. */
    val text: String,
    val paragraph: ByteArray,
    val character: ByteArray,
)

/** The lists of the file, and the overrides through which paragraphs refer to them. */
internal class DocLists(table: ByteArray, fib: DocFib) {
    private val lists = HashMap<Int, Array<DocListLevel?>>()
    private val overrideList: List<Int>
    private val startOverrides = HashMap<Long, Int>()

    init {
        readLists(table, fib.offset(DocTable.LISTS), fib.length(DocTable.LISTS))
        overrideList = readOverrides(table, fib.offset(DocTable.LIST_OVERRIDES), fib.length(DocTable.LIST_OVERRIDES))
    }

    /** The list an override number stands for. They count from one, and zero means no list. */
    fun listId(override: Int): Int? = overrideList.getOrNull(override - 1)

    fun level(override: Int, level: Int): DocListLevel? = listId(override)?.let { lists[it]?.getOrNull(level) }

    fun levels(override: Int): Array<DocListLevel?>? = listId(override)?.let(lists::get)

    fun startOverride(override: Int, level: Int): Int? = startOverrides[override.toLong() shl 8 or level.toLong()]

    private fun readLists(table: ByteArray, offset: Int, length: Int) {
        if (length < 2) return
        val count = table.s16(offset)
        var at = offset + 2 + count * LIST_SIZE
        for (index in 0 until count) {
            val entry = offset + 2 + index * LIST_SIZE
            val simple = table.u8(entry + LIST_FLAGS) and 1 != 0
            val levels = arrayOfNulls<DocListLevel>(if (simple) 1 else MAX_LEVELS)
            for (level in levels.indices) {
                val (read, next) = readLevel(table, at)
                levels[level] = read
                at = next
            }
            lists[table.i32(entry)] = levels
        }
    }

    private fun readLevel(table: ByteArray, at: Int): Pair<DocListLevel, Int> {
        val characterSize = table.u8(at + 24)
        val paragraphSize = table.u8(at + 25)
        val flags = table.u8(at + 5)
        val paragraph = table.copyOfRange(at + LEVEL_SIZE, at + LEVEL_SIZE + paragraphSize)
        val character = table.copyOfRange(at + LEVEL_SIZE + paragraphSize, at + LEVEL_SIZE + paragraphSize + characterSize)
        val textAt = at + LEVEL_SIZE + paragraphSize + characterSize
        val textLength = table.u16(textAt)
        return DocListLevel(
            start = table.i32(at),
            format = table.u8(at + 4),
            legal = flags and 0x04 != 0,
            noRestart = flags and 0x08 != 0,
            follow = table.u8(at + 15),
            text = String(table, textAt + 2, textLength * 2, Charsets.UTF_16LE),
            paragraph = paragraph,
            character = character,
        ) to (textAt + 2 + textLength * 2)
    }

    private fun readOverrides(table: ByteArray, offset: Int, length: Int): List<Int> {
        if (length < 4) return emptyList()
        val count = table.i32(offset)
        val out = ArrayList<Int>(count.coerceIn(0, MAX_OVERRIDES))
        val entries = offset + 4
        for (index in 0 until count.coerceIn(0, MAX_OVERRIDES)) out += table.i32(entries + index * OVERRIDE_SIZE)
        var at = entries + count.coerceIn(0, MAX_OVERRIDES) * OVERRIDE_SIZE + 0
        for (index in 0 until out.size) {
            val levels = table.u8(entries + index * OVERRIDE_SIZE + 12)
            at += 4
            repeat(levels) {
                val flags = table.i32(at + 4)
                val level = flags and 0x0F
                if (flags and 0x10 != 0) startOverrides[(index + 1).toLong() shl 8 or level.toLong()] = table.i32(at)
                at += 8
                if (flags and 0x20 != 0) at = readLevel(table, at).second
            }
        }
        return out
    }

    private companion object {
        const val LIST_SIZE = 28
        const val LIST_FLAGS = 26
        const val MAX_LEVELS = 9
        const val LEVEL_SIZE = 28
        const val OVERRIDE_SIZE = 16
        const val MAX_OVERRIDES = 65_536
    }
}
