package com.vivenotes.fileimport.doc

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.WordAlign
import com.vivenotes.fileimport.docx.WordBorder
import com.vivenotes.fileimport.docx.WordBorderStyle
import com.vivenotes.fileimport.docx.WordLineRule
import com.vivenotes.fileimport.docx.WordTabKind
import com.vivenotes.fileimport.docx.WordTabStop

/**
 * The header of a Word 97-or-later file: where everything else is. Word 6 and 95 lay this out
 * differently and are refused before it is read.
 */
internal class DocFib(private val word: ByteArray) {
    init {
        if (word.u16(0) != WORD_97_IDENTIFIER || word.u16(VERSION) < WORD_97_VERSION) throw ImportException(ImportFailure.Unreadable)
    }

    private val flags = word.u16(FLAGS)
    val encrypted: Boolean = flags and FLAG_ENCRYPTED != 0
    val tableStream: String = if (flags and FLAG_TABLE1 != 0) "1Table" else "0Table"

    private val longs = LONGS_AT + word.u16(SHORT_COUNT) * 2 + 2 + 2
    private val pairs = longs + word.u16(longs - 2) * 4 + 2

    private fun long(index: Int): Int = word.i32(longs + index * 4)

    val ccpText: Int = long(3)
    val ccpFootnotes: Int = long(4)
    val ccpHeaders: Int = long(5)
    val ccpMacros: Int = long(6)
    val ccpAnnotations: Int = long(7)
    val ccpEndnotes: Int = long(8)
    val ccpTextBoxes: Int = long(9)

    fun offset(table: Int): Int = word.i32(pairs + table * 8)

    fun length(table: Int): Int = word.i32(pairs + table * 8 + 4)

    private companion object {
        const val WORD_97_IDENTIFIER = 0xA5EC
        const val WORD_97_VERSION = 0xC0
        const val VERSION = 2
        const val FLAGS = 10
        const val FLAG_ENCRYPTED = 0x0100
        const val FLAG_TABLE1 = 0x0200
        const val SHORT_COUNT = 32
        const val LONGS_AT = 32
    }
}

/** The numbers of the file's tables, in the order the header lists them. */
internal object DocTable {
    const val STYLESHEET = 1
    const val FOOTNOTE_REFS = 2
    const val FOOTNOTE_TEXT = 3
    const val SECTIONS = 6
    const val HEADERS = 11
    const val CHARACTER_PAGES = 12
    const val PARAGRAPH_PAGES = 13
    const val FONTS = 15
    const val DOCUMENT_OPTIONS = 31
    const val PIECES = 33
    const val SHAPES = 40
    const val ENDNOTE_REFS = 46
    const val ENDNOTE_TEXT = 47
    const val DRAWINGS = 50
    const val TEXT_BOXES = 56
    const val LISTS = 73
    const val LIST_OVERRIDES = 74
}

/** One stretch of the document's text, and where in the file its characters are. */
private class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean, val modifier: ByteArray?)

/**
 * The document's characters. A `.doc` isn't stored in reading order: after a fast save the text is
 * patches, and this table says which piece of the file holds which stretch of the document.
 */
internal class DocText(word: ByteArray, table: ByteArray, fib: DocFib) {
    private val pieces = ArrayList<Piece>()
    val chars: CharArray

    init {
        val modifiers = ArrayList<ByteArray>()
        var at = fib.offset(DocTable.PIECES)
        val end = at + fib.length(DocTable.PIECES)
        while (at < end && table.u8(at) == CLX_MODIFIER) {
            val size = table.u16(at + 1)
            modifiers += table.copyOfRange(at + 3, at + 3 + size)
            at += 3 + size
        }
        val count = (table.i32(at + 1) - 4) / (4 + PIECE_SIZE)
        val positions = at + 5
        val descriptors = positions + (count + 1) * 4
        for (index in 0 until count) {
            val raw = table.i32(descriptors + index * PIECE_SIZE + 2)
            val compressed = raw and COMPRESSED != 0
            val prm = table.u16(descriptors + index * PIECE_SIZE + 6)
            pieces += Piece(
                cpStart = table.i32(positions + index * 4),
                cpEnd = table.i32(positions + (index + 1) * 4),
                fc = if (compressed) (raw and COMPRESSED.inv()) / 2 else raw,
                compressed = compressed,
                modifier = if (prm and 1 != 0) modifiers.getOrNull(prm ushr 1) else null,
            )
        }
        val total = pieces.maxOfOrNull { it.cpEnd } ?: 0
        chars = CharArray(total)
        for (piece in pieces) {
            val length = piece.cpEnd - piece.cpStart
            val bytes = if (piece.compressed) length else length * 2
            val decoded = String(word, piece.fc, bytes, if (piece.compressed) WINDOWS_1252 else Charsets.UTF_16LE)
            decoded.toCharArray(chars, piece.cpStart, 0, minOf(decoded.length, length))
        }
    }

    private fun pieceAt(cp: Int): Piece? {
        var low = 0
        var high = pieces.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val piece = pieces[middle]
            when {
                cp < piece.cpStart -> high = middle - 1
                cp >= piece.cpEnd -> low = middle + 1
                else -> return piece
            }
        }
        return null
    }

    /** Where in the file character [cp] is, which is how formatting is keyed. */
    fun fcOf(cp: Int): Int {
        val piece = pieceAt(cp) ?: return -1
        return piece.fc + (cp - piece.cpStart) * if (piece.compressed) 1 else 2
    }

    fun modifierOf(cp: Int): ByteArray? = pieceAt(cp)?.modifier

    private companion object {
        const val CLX_MODIFIER = 1
        const val PIECE_SIZE = 8
        const val COMPRESSED = 0x40000000
        val WINDOWS_1252 = charset("windows-1252")
    }
}

/** Formatting by position in the file: a sorted table of stretches and what each one is set to. */
internal class DocSpans(private val starts: IntArray, private val ends: IntArray, private val values: List<ByteArray?>) {
    fun at(fc: Int): ByteArray? {
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                fc < starts[middle] -> high = middle - 1
                fc >= ends[middle] -> low = middle + 1
                else -> return values[middle]
            }
        }
        return null
    }

    /** A stable key for what [at] answers, for caching what it works out to. */
    fun indexAt(fc: Int): Int {
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                fc < starts[middle] -> high = middle - 1
                fc >= ends[middle] -> low = middle + 1
                else -> return middle
            }
        }
        return -1
    }

    companion object {
        private const val PAGE = 512
        private const val PAGE_NUMBER_MASK = 0x3FFFFF
        private const val PARAGRAPH_ENTRY = 13

        private fun pages(table: ByteArray, offset: Int, length: Int, visit: (Int) -> Unit) {
            val count = (length - 4) / 8
            for (index in 0 until count) visit((table.i32(offset + (count + 1) * 4 + index * 4) and PAGE_NUMBER_MASK) * PAGE)
        }

        fun characters(word: ByteArray, table: ByteArray, offset: Int, length: Int): DocSpans {
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val values = ArrayList<ByteArray?>()
            pages(table, offset, length) { page ->
                val count = word.u8(page + PAGE - 1)
                for (index in 0 until count) {
                    starts += word.i32(page + index * 4)
                    ends += word.i32(page + (index + 1) * 4)
                    val at = word.u8(page + (count + 1) * 4 + index) * 2
                    values += if (at == 0) null else word.copyOfRange(page + at + 1, page + at + 1 + word.u8(page + at))
                }
            }
            return DocSpans(starts.toIntArray(), ends.toIntArray(), values)
        }

        /** Each value holds its style number and then its properties. */
        fun paragraphs(word: ByteArray, table: ByteArray, offset: Int, length: Int): DocSpans {
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val values = ArrayList<ByteArray?>()
            pages(table, offset, length) { page ->
                val count = word.u8(page + PAGE - 1)
                for (index in 0 until count) {
                    starts += word.i32(page + index * 4)
                    ends += word.i32(page + (index + 1) * 4)
                    val at = word.u8(page + (count + 1) * 4 + index * PARAGRAPH_ENTRY) * 2
                    values += if (at == 0) {
                        null
                    } else {
                        val first = word.u8(page + at)
                        if (first == 0) {
                            val size = word.u8(page + at + 1) * 2
                            word.copyOfRange(page + at + 2, page + at + 2 + size)
                        } else {
                            word.copyOfRange(page + at + 1, page + at + 2 * first)
                        }
                    }
                }
            }
            return DocSpans(starts.toIntArray(), ends.toIntArray(), values)
        }
    }
}

/** Runs [action] on each property in [bytes], with where its operand starts. */
internal inline fun forEachSprm(bytes: ByteArray, from: Int, action: (sprm: Int, operand: Int) -> Unit) {
    var at = from
    while (at + 2 <= bytes.size) {
        val sprm = bytes.u16(at)
        val operand = at + 2
        val length = when (sprm ushr 13) {
            0, 1 -> 1
            2, 4, 5 -> 2
            3 -> 4
            7 -> 3
            else -> variableSprmLength(bytes, sprm, operand)
        }
        if (operand + length > bytes.size) return
        action(sprm, operand)
        at = operand + length
    }
}

/** A variable operand opens with its own size, apart from the two that needed more room than a byte. */
@PublishedApi
internal fun variableSprmLength(bytes: ByteArray, sprm: Int, operand: Int): Int {
    if (operand >= bytes.size) return Int.MAX_VALUE / 2
    return when (sprm) {
        // Its two-byte size counts what follows, plus one.
        Sprm.T_DEF_TABLE -> 1 + bytes.u16(operand)
        Sprm.P_CHG_TABS -> if (bytes.u8(operand) != 255) {
            1 + bytes.u8(operand)
        } else {
            val deleted = bytes.u8(operand + 1)
            2 + deleted * 4 + 1 + bytes.u8(operand + 2 + deleted * 4) * 3
        }
        else -> 1 + bytes.u8(operand)
    }
}

/** The property numbers this reader acts on; the file format has several hundred. */
internal object Sprm {
    const val C_RMARK_DEL = 0x0800
    const val C_DATA = 0x0806
    const val C_OLE2 = 0x080A
    const val C_PIC_LOCATION = 0x6A03
    const val C_SYMBOL = 0x6A09
    const val C_HIGHLIGHT = 0x2A0C
    const val C_ISTD = 0x4A30
    const val C_PLAIN = 0x2A33
    const val C_BOLD = 0x0835
    const val C_ITALIC = 0x0836
    const val C_STRIKE = 0x0837
    const val C_SMALL_CAPS = 0x083A
    const val C_CAPS = 0x083B
    const val C_VANISH = 0x083C
    const val C_UNDERLINE = 0x2A3E
    const val C_SPACING = 0x8840
    const val C_ICO = 0x2A42
    const val C_SIZE = 0x4A43
    const val C_SCRIPT = 0x2A48
    const val C_FONT = 0x4A4F
    const val C_SCALE = 0x4852
    const val C_DOUBLE_STRIKE = 0x2A53
    const val C_SPECIAL = 0x0855
    const val C_COLOR = 0x6870

    const val P_ISTD = 0x4600
    const val P_JC_80 = 0x2403
    const val P_KEEP = 0x2405
    const val P_KEEP_NEXT = 0x2406
    const val P_PAGE_BREAK = 0x2407
    const val P_ILVL = 0x260A
    const val P_ILFO = 0x460B
    const val P_CHG_TABS_PAPX = 0xC60D
    const val P_RIGHT_80 = 0x840E
    const val P_LEFT_80 = 0x840F
    const val P_FIRST_80 = 0x8411
    const val P_LINE = 0x6412
    const val P_BEFORE = 0xA413
    const val P_AFTER = 0xA414
    const val P_CHG_TABS = 0xC615
    const val P_IN_TABLE = 0x2416
    const val P_TTP = 0x2417
    const val P_SHADE_80 = 0x442D
    const val P_WIDOW = 0x2431
    const val P_OUTLINE = 0x2640
    const val P_BIDI = 0x2441
    const val P_HUGE = 0x6646
    const val P_ITAP = 0x6649
    const val P_INNER_CELL = 0x244B
    const val P_INNER_TTP = 0x244C
    const val P_SHADE = 0xC64D
    const val P_RIGHT = 0x845D
    const val P_LEFT = 0x845E
    const val P_FIRST = 0x8460
    const val P_JC = 0x2461
    const val P_CONTEXTUAL = 0x246D

    const val T_LEFT = 0x9601
    const val T_GAP = 0x9602
    const val T_CANT_SPLIT_90 = 0x3403
    const val T_HEADER = 0x3404
    const val T_BORDERS_80 = 0xD605
    const val T_ROW_HEIGHT = 0x9407
    const val T_DEF_TABLE = 0xD608
    const val T_SHADE_80 = 0xD609
    const val T_SHADE_3 = 0xD60C
    const val T_SHADE = 0xD612
    const val T_BORDERS = 0xD613
    const val T_SHADE_2 = 0xD616
    const val T_CANT_SPLIT = 0x3466
    const val T_JC_90 = 0x5400
    const val T_JC = 0x5487
    const val T_VERT_MERGE = 0xD62B

    const val S_BREAK = 0x3009
    const val S_TITLE_PAGE = 0x300A
    const val S_COLUMNS = 0x500B
    const val S_COLUMN_GAP = 0x900C
    const val S_HEADER = 0xB017
    const val S_FOOTER = 0xB018
    const val S_WIDTH = 0xB01F
    const val S_HEIGHT = 0xB020
    const val S_LEFT = 0xB021
    const val S_RIGHT = 0xB022
    const val S_TOP = 0x9023
    const val S_BOTTOM = 0x9024
}

/** Word's sixteen colours, which older properties name by number. */
internal val DOC_PALETTE: IntArray = intArrayOf(
    0, 0xFF000000.toInt(), 0xFF0000FF.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt(),
    0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt(), 0xFF000080.toInt(), 0xFF008080.toInt(), 0xFF008000.toInt(), 0xFF800080.toInt(),
    0xFF800000.toInt(), 0xFF808000.toInt(), 0xFF808080.toInt(), 0xFFC0C0C0.toInt(),
)

internal fun paletteColor(index: Int): Int? = DOC_PALETTE.getOrNull(index)?.takeIf { it != 0 }

/** A colour stored as red, green, blue and a flag for "automatic". */
internal fun colorRefAt(bytes: ByteArray, at: Int): Int? {
    if (bytes.u8(at + 3) == 0xFF) return null
    return (0xFF shl 24) or (bytes.u8(at) shl 16) or (bytes.u8(at + 1) shl 8) or bytes.u8(at + 2)
}

/** The eight-byte border of recent files. */
internal fun borderAt(bytes: ByteArray, at: Int): WordBorder? = borderOf(bytes.u8(at + 5), bytes.u8(at + 4), colorRefAt(bytes, at))

/** The four-byte border of Word 97: width, kind, colour number. */
internal fun border80At(bytes: ByteArray, at: Int): WordBorder? = borderOf(bytes.u8(at + 1), bytes.u8(at), paletteColor(bytes.u8(at + 2)))

private fun borderOf(kind: Int, eighths: Int, color: Int?): WordBorder? = when (kind) {
    0 -> null
    255 -> WordBorder(WordBorderStyle.None, 0, null)
    else -> WordBorder(
        when (kind) {
            3, 10, 11, 12 -> WordBorderStyle.Double
            6, 8, 9 -> WordBorderStyle.Dotted
            7, 22, 23 -> WordBorderStyle.Dashed
            2 -> WordBorderStyle.Thick
            else -> WordBorderStyle.Single
        },
        eighths.coerceAtLeast(1),
        color,
    )
}

/** The formatting of a run of characters: a style's, with direct formatting to go on top. */
internal data class DocChar(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val smallCaps: Boolean = false,
    val caps: Boolean = false,
    val vanish: Boolean = false,
    val deleted: Boolean = false,
    val underline: Boolean = false,
    val halfPoints: Int = 20,
    val script: Int = 0,
    val color: Int? = null,
    val highlight: Int? = null,
    val font: Int = -1,
    val spacing: Int = 0,
    val scale: Int = 100,
    val special: Boolean = false,
    val picture: Int = -1,
    val data: Boolean = false,
    val ole: Boolean = false,
    val symbolFont: Int = -1,
    val symbol: Int = -1,
)

/** Applies [grpprl]'s character properties to [start]; a toggle's "as the style" means [style]. */
internal fun applyCharacter(start: DocChar, grpprl: ByteArray?, from: Int, style: DocChar): DocChar {
    grpprl ?: return start
    var out = start
    fun toggle(operand: Int, current: Boolean): Boolean = when (grpprl.u8(operand)) {
        0 -> false
        1 -> true
        0x80 -> current
        0x81 -> !current
        else -> current
    }
    forEachSprm(grpprl, from) { sprm, at ->
        out = when (sprm) {
            Sprm.C_PLAIN -> style.copy(special = out.special, picture = out.picture)
            Sprm.C_BOLD -> out.copy(bold = toggle(at, style.bold))
            Sprm.C_ITALIC -> out.copy(italic = toggle(at, style.italic))
            Sprm.C_STRIKE -> out.copy(strike = toggle(at, style.strike))
            Sprm.C_DOUBLE_STRIKE -> out.copy(strike = grpprl.u8(at) != 0)
            Sprm.C_SMALL_CAPS -> out.copy(smallCaps = toggle(at, style.smallCaps))
            Sprm.C_CAPS -> out.copy(caps = toggle(at, style.caps))
            Sprm.C_VANISH -> out.copy(vanish = toggle(at, style.vanish))
            Sprm.C_RMARK_DEL -> out.copy(deleted = grpprl.u8(at) != 0)
            Sprm.C_UNDERLINE -> out.copy(underline = grpprl.u8(at) != 0)
            Sprm.C_SIZE -> out.copy(halfPoints = grpprl.u16(at))
            Sprm.C_SCRIPT -> out.copy(script = grpprl.u8(at))
            Sprm.C_ICO -> out.copy(color = paletteColor(grpprl.u8(at)))
            Sprm.C_COLOR -> out.copy(color = colorRefAt(grpprl, at))
            Sprm.C_HIGHLIGHT -> out.copy(highlight = paletteColor(grpprl.u8(at)))
            Sprm.C_FONT -> out.copy(font = grpprl.u16(at))
            Sprm.C_SPACING -> out.copy(spacing = grpprl.s16(at))
            Sprm.C_SCALE -> out.copy(scale = grpprl.u16(at))
            Sprm.C_SPECIAL -> out.copy(special = grpprl.u8(at) != 0)
            Sprm.C_PIC_LOCATION -> out.copy(picture = grpprl.i32(at))
            Sprm.C_DATA -> out.copy(data = grpprl.u8(at) != 0)
            Sprm.C_OLE2 -> out.copy(ole = grpprl.u8(at) != 0)
            Sprm.C_SYMBOL -> out.copy(symbolFont = grpprl.u16(at), symbol = grpprl.u16(at + 2))
            else -> out
        }
    }
    return out
}

/** The formatting of a paragraph. */
internal data class DocPara(
    val istd: Int = 0,
    val align: WordAlign = WordAlign.Left,
    val left: Int = 0,
    val right: Int = 0,
    val first: Int = 0,
    val before: Int = 0,
    val after: Int = 0,
    val lineValue: Int = 240,
    val lineRule: WordLineRule = WordLineRule.Auto,
    val keepNext: Boolean = false,
    val keepLines: Boolean = false,
    val pageBreak: Boolean = false,
    val widow: Boolean = true,
    val inTable: Boolean = false,
    val rowEnd: Boolean = false,
    val depth: Int = 0,
    val innerCell: Boolean = false,
    val innerRowEnd: Boolean = false,
    val listLevel: Int = 0,
    val listOverride: Int = 0,
    val outline: Int = NO_OUTLINE,
    val shade: Int? = null,
    val tabs: List<WordTabStop> = emptyList(),
    val rightToLeft: Boolean = false,
    val contextual: Boolean = false,
) {
    /** How deep in tables this paragraph is; an old file says only "in a table". */
    val tableDepth: Int get() = if (inTable && depth == 0) 1 else depth

    companion object {
        const val NO_OUTLINE = 9
    }
}

internal fun applyParagraph(start: DocPara, grpprl: ByteArray?, from: Int): DocPara {
    grpprl ?: return start
    var out = start
    fun flag(at: Int): Boolean = grpprl.u8(at) != 0
    forEachSprm(grpprl, from) { sprm, at ->
        out = when (sprm) {
            Sprm.P_ISTD -> out.copy(istd = grpprl.u16(at))
            Sprm.P_JC_80, Sprm.P_JC -> out.copy(align = alignOf(grpprl.u8(at)))
            Sprm.P_KEEP -> out.copy(keepLines = flag(at))
            Sprm.P_KEEP_NEXT -> out.copy(keepNext = flag(at))
            Sprm.P_PAGE_BREAK -> out.copy(pageBreak = flag(at))
            Sprm.P_ILVL -> out.copy(listLevel = grpprl.u8(at))
            Sprm.P_ILFO -> out.copy(listOverride = grpprl.s16(at))
            Sprm.P_RIGHT_80, Sprm.P_RIGHT -> out.copy(right = grpprl.s16(at))
            Sprm.P_LEFT_80, Sprm.P_LEFT -> out.copy(left = grpprl.s16(at))
            Sprm.P_FIRST_80, Sprm.P_FIRST -> out.copy(first = grpprl.s16(at))
            Sprm.P_LINE -> {
                val value = grpprl.s16(at)
                when {
                    grpprl.s16(at + 2) != 0 -> out.copy(lineValue = value, lineRule = WordLineRule.Auto)
                    value < 0 -> out.copy(lineValue = -value, lineRule = WordLineRule.Exact)
                    else -> out.copy(lineValue = value, lineRule = WordLineRule.AtLeast)
                }
            }
            Sprm.P_BEFORE -> out.copy(before = grpprl.u16(at))
            Sprm.P_AFTER -> out.copy(after = grpprl.u16(at))
            Sprm.P_CHG_TABS, Sprm.P_CHG_TABS_PAPX -> out.copy(tabs = changedTabs(out.tabs, grpprl, at))
            Sprm.P_IN_TABLE -> out.copy(inTable = flag(at))
            Sprm.P_TTP -> out.copy(rowEnd = flag(at))
            Sprm.P_ITAP -> out.copy(depth = grpprl.i32(at))
            Sprm.P_INNER_CELL -> out.copy(innerCell = flag(at))
            Sprm.P_INNER_TTP -> out.copy(innerRowEnd = flag(at))
            Sprm.P_WIDOW -> out.copy(widow = flag(at))
            Sprm.P_OUTLINE -> out.copy(outline = grpprl.u8(at))
            Sprm.P_BIDI -> out.copy(rightToLeft = flag(at))
            Sprm.P_CONTEXTUAL -> out.copy(contextual = flag(at))
            Sprm.P_SHADE -> out.copy(shade = colorRefAt(grpprl, at + 5))
            Sprm.P_SHADE_80 -> out.copy(shade = paletteColor((grpprl.u16(at) ushr 5) and 0x1F))
            else -> out
        }
    }
    return out
}

private fun alignOf(value: Int): WordAlign = when (value) {
    1 -> WordAlign.Center
    2 -> WordAlign.Right
    3, 4 -> WordAlign.Justify
    else -> WordAlign.Left
}

private fun changedTabs(tabs: List<WordTabStop>, bytes: ByteArray, operand: Int): List<WordTabStop> {
    var at = operand + 1
    val deletedCount = bytes.u8(at)
    at += 1
    val deleted = (0 until deletedCount).map { bytes.s16(at + it * 2) }.toSet()
    at += deletedCount * 2
    val addedCount = bytes.u8(at)
    at += 1
    val kept = tabs.filter { it.positionTwips !in deleted }.toMutableList()
    for (index in 0 until addedCount) {
        val position = bytes.s16(at + index * 2)
        val descriptor = bytes.u8(at + addedCount * 2 + index)
        kept.removeAll { it.positionTwips == position }
        val leader = when ((descriptor ushr 3) and 7) {
            1 -> '.'
            2 -> '-'
            3 -> '_'
            else -> null
        }
        when (descriptor and 7) {
            1 -> kept += WordTabStop(position, WordTabKind.Center, leader)
            2 -> kept += WordTabStop(position, WordTabKind.Right, leader)
            3 -> kept += WordTabStop(position, WordTabKind.Decimal, leader)
            4 -> kept += WordTabStop(position, WordTabKind.Bar, leader)
            else -> kept += WordTabStop(position, WordTabKind.Left, leader)
        }
    }
    return kept.sortedBy { it.positionTwips }
}
