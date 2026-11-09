package com.vivenotes.fileimport.doc

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.WordAlign
import com.vivenotes.fileimport.docx.WordAnchor
import com.vivenotes.fileimport.docx.WordBlock
import com.vivenotes.fileimport.docx.WordBorder
import com.vivenotes.fileimport.docx.WordBorderStyle
import com.vivenotes.fileimport.docx.WordBorders
import com.vivenotes.fileimport.docx.WordBoxed
import com.vivenotes.fileimport.docx.WordBreak
import com.vivenotes.fileimport.docx.WordBreakBefore
import com.vivenotes.fileimport.docx.WordCell
import com.vivenotes.fileimport.docx.WordCellAlign
import com.vivenotes.fileimport.docx.WordDocument
import com.vivenotes.fileimport.docx.WordField
import com.vivenotes.fileimport.docx.WordFieldKind
import com.vivenotes.fileimport.docx.WordHeaderSet
import com.vivenotes.fileimport.docx.WordInline
import com.vivenotes.fileimport.docx.WordLineBreak
import com.vivenotes.fileimport.docx.WordListLabel
import com.vivenotes.fileimport.docx.WordNoteRef
import com.vivenotes.fileimport.docx.WordPageGeometry
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordParagraphStyle
import com.vivenotes.fileimport.docx.WordPicture
import com.vivenotes.fileimport.docx.WordPlaceholder
import com.vivenotes.fileimport.docx.WordRow
import com.vivenotes.fileimport.docx.WordRunStyle
import com.vivenotes.fileimport.docx.WordScript
import com.vivenotes.fileimport.docx.WordSection
import com.vivenotes.fileimport.docx.WordSectionStart
import com.vivenotes.fileimport.docx.WordSettings
import com.vivenotes.fileimport.docx.WordShape
import com.vivenotes.fileimport.docx.WordShapeForm
import com.vivenotes.fileimport.docx.WordTab
import com.vivenotes.fileimport.docx.WordTable
import com.vivenotes.fileimport.docx.WordText
import com.vivenotes.fileimport.docx.WordVerticalMerge
import com.vivenotes.fileimport.docx.WordWrap
import com.vivenotes.fileimport.docx.decodeSymbolFont
import com.vivenotes.fileimport.docx.formatNumber
import com.vivenotes.fileimport.docx.isSymbolFont
import com.vivenotes.model.normalizedLinkUrl
import kotlin.math.abs
import kotlin.math.min

/** A document as read, the pictures it names, and each font's generic family. */
internal class DocRead(
    val document: WordDocument,
    val pictures: Map<String, ByteArray>,
    val fontFamilies: Map<String, String>,
)

/**
 * Reads a Word 97-2003 `.doc` into the same model a `.docx` becomes, so that pagination, page pictures
 * and objects need to know nothing about where a document came from.
 *
 * Everything optional is read so that a problem in it costs only itself. Hostile and damaged files are
 * expected: bounds are not checked by hand, and whatever runs off the end of an array surfaces in
 * `FileImporter` as an unreadable file.
 */
internal class DocReader(compound: CompoundFile) {
    private val word = compound.stream(CompoundFile.WORD_DOCUMENT) ?: throw ImportException(ImportFailure.Unreadable)
    private val fib = DocFib(word)
    private val table: ByteArray
    private val data: ByteArray
    private val text: DocText
    private val characterSpans: DocSpans
    private val paragraphSpans: DocSpans
    private val styles: DocStyles
    private val fonts: List<DocFont>
    private val lists: DocLists?
    private val art: DocArt

    private val pictures = LinkedHashMap<String, ByteArray>()
    private val pictureNames = HashMap<Int, String>()
    private val runs = HashMap<DocChar, WordRunStyle>()
    private val paragraphCache = HashMap<Int, DocPara>()
    private val characterCache = HashMap<Long, DocChar>()
    private val listValues = HashMap<Int, IntArray>()
    private val listStarted = HashMap<Int, BooleanArray>()
    private val overridesUsed = HashSet<Long>()

    private val footnoteIds: Map<Int, Int>
    private val endnoteIds: Map<Int, Int>
    private val shapeAnchors: Map<Int, DocAnchor>
    private val textBoxes: Map<Int, IntRange>
    private val sectionMarks = HashSet<Int>()

    init {
        if (fib.encrypted) throw ImportException(ImportFailure.PasswordProtected)
        table = compound.stream(fib.tableStream) ?: throw ImportException(ImportFailure.Unreadable)
        data = compound.stream("Data") ?: ByteArray(0)
        text = DocText(word, table, fib)
        characterSpans = DocSpans.characters(word, table, fib.offset(DocTable.CHARACTER_PAGES), fib.length(DocTable.CHARACTER_PAGES))
        paragraphSpans = DocSpans.paragraphs(word, table, fib.offset(DocTable.PARAGRAPH_PAGES), fib.length(DocTable.PARAGRAPH_PAGES))
        styles = DocStyles(table, fib.offset(DocTable.STYLESHEET), fib.length(DocTable.STYLESHEET))
        fonts = runCatching { readDocFonts(table, fib.offset(DocTable.FONTS), fib.length(DocTable.FONTS)) }.getOrDefault(emptyList())
        lists = runCatching { DocLists(table, fib) }.getOrNull()
        art = DocArt(table, word, fib)
        footnoteIds = noteReferences(DocTable.FOOTNOTE_REFS)
        endnoteIds = noteReferences(DocTable.ENDNOTE_REFS)
        shapeAnchors = runCatching { readShapeAnchors() }.getOrDefault(emptyMap())
        textBoxes = runCatching { readTextBoxes() }.getOrDefault(emptyMap())
    }

    private class DocAnchor(val id: Int, val left: Int, val top: Int, val right: Int, val bottom: Int, val flags: Int)

    private val footnotesStart: Int get() = fib.ccpText
    private val headersStart: Int get() = footnotesStart + fib.ccpFootnotes
    private val endnotesStart: Int get() = headersStart + fib.ccpHeaders + fib.ccpMacros + fib.ccpAnnotations
    private val textBoxesStart: Int get() = endnotesStart + fib.ccpEndnotes

    fun read(): DocRead {
        val sections = readSections()
        val settings = WordSettings(evenAndOddHeaders = isFacingPages(), compatibilityMode = OLD_COMPATIBILITY)
        val document = WordDocument(
            sections,
            runCatching { readNotes(DocTable.FOOTNOTE_REFS, DocTable.FOOTNOTE_TEXT, footnotesStart) }.getOrDefault(emptyMap()),
            runCatching { readNotes(DocTable.ENDNOTE_REFS, DocTable.ENDNOTE_TEXT, endnotesStart) }.getOrDefault(emptyMap()),
            settings,
        )
        return DocRead(document, pictures, fonts.filter { it.family != null }.associate { it.name.lowercase() to it.family.orEmpty() })
    }

    private fun isFacingPages(): Boolean =
        fib.length(DocTable.DOCUMENT_OPTIONS) > 0 && table.u8(fib.offset(DocTable.DOCUMENT_OPTIONS)) and 1 != 0

    // --- sections --------------------------------------------------------------------------------

    private class SectionFormat(
        val start: WordSectionStart,
        val titlePage: Boolean,
        val columns: Int,
        val columnGap: Int,
        val page: WordPageGeometry,
    )

    private fun readSectionFormat(fc: Int): SectionFormat {
        var start = WordSectionStart.NextPage
        var titlePage = false
        var columns = 1
        var gap = 720
        var width = 12240
        var height = 15840
        var left = 1800
        var right = 1800
        var top = 1440
        var bottom = 1440
        var header = 720
        var footer = 720
        if (fc >= 0 && fc + 2 <= word.size) {
            val size = word.u16(fc)
            val grpprl = word.copyOfRange(fc + 2, min(word.size, fc + 2 + size))
            forEachSprm(grpprl, 0) { sprm, at ->
                when (sprm) {
                    Sprm.S_BREAK -> start = when (grpprl.u8(at)) {
                        0 -> WordSectionStart.Continuous
                        1 -> WordSectionStart.NextColumn
                        3 -> WordSectionStart.EvenPage
                        4 -> WordSectionStart.OddPage
                        else -> WordSectionStart.NextPage
                    }
                    Sprm.S_TITLE_PAGE -> titlePage = grpprl.u8(at) != 0
                    Sprm.S_COLUMNS -> columns = grpprl.u16(at) + 1
                    Sprm.S_COLUMN_GAP -> gap = grpprl.u16(at)
                    Sprm.S_WIDTH -> width = grpprl.u16(at)
                    Sprm.S_HEIGHT -> height = grpprl.u16(at)
                    Sprm.S_LEFT -> left = grpprl.u16(at)
                    Sprm.S_RIGHT -> right = grpprl.u16(at)
                    Sprm.S_TOP -> top = abs(grpprl.s16(at))
                    Sprm.S_BOTTOM -> bottom = abs(grpprl.s16(at))
                    Sprm.S_HEADER -> header = grpprl.u16(at)
                    Sprm.S_FOOTER -> footer = grpprl.u16(at)
                }
            }
        }
        return SectionFormat(start, titlePage, columns, gap, WordPageGeometry(width, height, top, bottom, left, right, header, footer))
    }

    private fun readSections(): List<WordSection> {
        val offset = fib.offset(DocTable.SECTIONS)
        val count = (fib.length(DocTable.SECTIONS) - 4) / (4 + SECTION_ENTRY)
        val bounds = if (count > 0) IntArray(count + 1) { table.i32(offset + it * 4) } else intArrayOf(0, fib.ccpText)
        val formats = List(maxOf(count, 1)) { index ->
            readSectionFormat(if (count > 0) table.i32(offset + (count + 1) * 4 + index * SECTION_ENTRY + 2) else -1)
        }
        bounds.forEach { if (it > 0) sectionMarks += it }
        val headerBounds = readPositions(DocTable.HEADERS)
        var headers = WordHeaderSet()
        var footers = WordHeaderSet()
        return formats.mapIndexed { index, format ->
            val blocks = ArrayList<WordBlock>()
            val from = bounds[index]
            val to = min(bounds[index + 1], fib.ccpText)
            if (from < to) readBlocks(parasOf(from, to), 0, blocks, Story(StoryKind.Main))
            val bands = HEADER_STORIES_PER_SECTION * index + HEADER_SEPARATORS
            fun story(offsetInSection: Int): List<WordBlock>? = runCatching { bandBlocks(headerBounds, bands + offsetInSection) }.getOrNull()
            headers = WordHeaderSet(default = story(1) ?: headers.default, first = story(4) ?: headers.first, even = story(0) ?: headers.even)
            footers = WordHeaderSet(default = story(3) ?: footers.default, first = story(5) ?: footers.first, even = story(2) ?: footers.even)
            WordSection(
                page = format.page,
                columns = format.columns,
                columnGapTwips = format.columnGap,
                start = if (index == 0) WordSectionStart.Continuous else format.start,
                titlePage = format.titlePage,
                pageNumberStart = null,
                pageNumberFormat = null,
                headers = headers,
                footers = footers,
                blocks = blocks,
            )
        }
    }

    private fun readPositions(entry: Int): IntArray {
        val length = fib.length(entry)
        if (length < 4) return IntArray(0)
        val offset = fib.offset(entry)
        return IntArray(length / 4) { table.i32(offset + it * 4) }
    }

    /** The header or footer stored at [index], or null if that one is left to inherit. */
    private fun bandBlocks(bounds: IntArray, index: Int): List<WordBlock>? {
        if (index + 1 >= bounds.size) return null
        val from = headersStart + bounds[index]
        val to = headersStart + bounds[index + 1]
        if (from >= to) return null
        val blocks = ArrayList<WordBlock>()
        readBlocks(withoutGuard(parasOf(from, to)), 0, blocks, Story(StoryKind.Band))
        return blocks
    }

    /** A story ends in one last paragraph mark of its own, there to hold the end of the text. */
    private fun withoutGuard(paras: List<Para>): List<Para> =
        if (paras.size > 1 && paras.last().end - paras.last().start == 1) paras.dropLast(1) else paras

    // --- notes -----------------------------------------------------------------------------------

    private fun noteReferences(entry: Int): Map<Int, Int> {
        val length = fib.length(entry)
        if (length < 4) return emptyMap()
        val count = (length - 4) / 6
        val offset = fib.offset(entry)
        return (0 until count).associate { table.i32(offset + it * 4) to it + 1 }
    }

    private fun readNotes(references: Int, texts: Int, storyStart: Int): Map<Int, List<WordBlock>> {
        val count = (fib.length(references) - 4) / 6
        if (count <= 0) return emptyMap()
        val bounds = readPositions(texts)
        val out = LinkedHashMap<Int, List<WordBlock>>()
        for (index in 0 until count) {
            if (index + 1 >= bounds.size) break
            val blocks = ArrayList<WordBlock>()
            readBlocks(parasOf(storyStart + bounds[index], storyStart + bounds[index + 1]), 0, blocks, Story(StoryKind.Note))
            out[index + 1] = withoutLeadingSpace(blocks)
        }
        return out
    }

    /** What follows a note's number is its separator from the number, which the app sets for itself. */
    private fun withoutLeadingSpace(blocks: List<WordBlock>): List<WordBlock> {
        val first = blocks.firstOrNull() as? WordParagraph ?: return blocks
        val inlines = first.inlines.dropWhile { it is WordTab || (it is WordText && it.text.isBlank()) }.toMutableList()
        (inlines.firstOrNull() as? WordText)?.let { inlines[0] = WordText(it.text.trimStart(), it.style, it.link) }
        return listOf(WordParagraph(first.style, first.markStyle, inlines, first.label, first.breakBefore)) + blocks.drop(1)
    }

    // --- paragraphs ------------------------------------------------------------------------------

    private class Para(val start: Int, val end: Int, val terminator: Char, val props: DocPara, val grpprl: ByteArray?)

    private fun parasOf(from: Int, to: Int): List<Para> {
        val out = ArrayList<Para>()
        var start = from
        val chars = text.chars
        for (cp in from until min(to, chars.size)) {
            val char = chars[cp]
            val ends = char == '\r' || char == CELL_MARK || (char == '\u000C' && cp + 1 in sectionMarks)
            if (ends) {
                out += paraOf(start, cp + 1, char)
                start = cp + 1
            }
        }
        if (start < to && to <= chars.size) out += paraOf(start, to, chars[to - 1])
        return out
    }

    private fun paraOf(start: Int, end: Int, terminator: Char): Para {
        val fc = text.fcOf(end - 1)
        val index = paragraphSpans.indexAt(fc)
        val grpprl = expandHuge(paragraphSpans.at(fc))
        val modifier = text.modifierOf(end - 1)
        var props = if (index >= 0) paragraphCache.getOrPut(index) { paragraphProps(grpprl) } else styles.paragraph(0)
        if (modifier != null) props = applyParagraph(props, modifier, 0)
        return Para(start, end, terminator, props, grpprl)
    }

    /** A paragraph too big for its page has its properties in the Data stream and a pointer here. */
    private fun expandHuge(grpprl: ByteArray?): ByteArray? {
        grpprl ?: return null
        var huge = -1
        forEachSprm(grpprl, 2) { sprm, at -> if (sprm == Sprm.P_HUGE) huge = grpprl.i32(at) }
        if (huge < 0 || huge + 2 > data.size) return grpprl
        val size = data.u16(huge)
        return grpprl.copyOfRange(0, 2) + data.copyOfRange(huge + 2, min(data.size, huge + 2 + size))
    }

    private fun paragraphProps(grpprl: ByteArray?): DocPara {
        val istd = grpprl?.takeIf { it.size >= 2 }?.u16(0) ?: 0
        val base = styles.paragraph(istd)
        val direct = applyParagraph(base, grpprl, 2)
        val withList = direct.takeIf { it.listOverride > 0 }?.let { lists?.level(it.listOverride, it.listLevel) }
            ?.let { applyParagraph(base, it.paragraph, 0) } ?: return direct.copy(istd = istd)
        return applyParagraph(withList, grpprl, 2).copy(istd = istd)
    }

    private fun readBlocks(paras: List<Para>, depth: Int, out: MutableList<WordBlock>, story: Story) {
        var index = 0
        while (index < paras.size) {
            val para = paras[index]
            if (para.props.tableDepth > depth) {
                var end = index
                while (end < paras.size && paras[end].props.tableDepth > depth) end++
                tableOf(paras.subList(index, end), depth + 1, story)?.let { out += it }
                index = end
            } else {
                // Only the section's mark, when nothing shares its paragraph: not a line of the page.
                val onlyMark = para.terminator == '\u000C' && para.end - para.start == 1
                if (!onlyMark) out += ParagraphBuilder(para, story).build()
                index++
            }
        }
    }

    // --- text ------------------------------------------------------------------------------------

    private enum class StoryKind { Main, Note, Band, TextBox }

    /** A stretch of the document read in one go; a field can open in one paragraph and close in another. */
    private class Story(val kind: StoryKind) {
        val fields = ArrayList<OpenField>()

        val hidingCode: Boolean get() = fields.any { !it.inResult }
        val link: String? get() = fields.lastOrNull { it.link != null }?.link
    }

    private class OpenField {
        val code = StringBuilder()
        var inResult = false
        var link: String? = null
        var kind: WordFieldKind? = null
    }

    private fun characterAt(cp: Int, paragraphStyle: Int): DocChar {
        val fc = text.fcOf(cp)
        val index = characterSpans.indexAt(fc)
        val modifier = text.modifierOf(cp)
        val key = (index.toLong() shl 20) or paragraphStyle.toLong()
        val plain = characterCache.getOrPut(key) { resolveCharacter(characterSpans.at(fc), paragraphStyle) }
        return if (modifier == null) plain else applyCharacter(plain, modifier, 0, plain)
    }

    private fun resolveCharacter(grpprl: ByteArray?, paragraphStyle: Int): DocChar {
        val paragraph = styles.characters(paragraphStyle)
        var characterStyle = -1
        grpprl?.let { bytes -> forEachSprm(bytes, 0) { sprm, at -> if (sprm == Sprm.C_ISTD) characterStyle = bytes.u16(at) } }
        val styled = if (characterStyle >= 0) styles.overCharacters(characterStyle, paragraph) else paragraph
        return applyCharacter(styled, grpprl, 0, styled)
    }

    private fun fontName(index: Int): String? = fonts.getOrNull(index)?.name?.takeIf { it.isNotEmpty() }

    private fun runOf(char: DocChar): WordRunStyle = runs.getOrPut(char) {
        WordRunStyle(
            bold = char.bold,
            italic = char.italic,
            underline = char.underline,
            strike = char.strike,
            script = when (char.script) {
                1 -> WordScript.Super
                2 -> WordScript.Sub
                else -> WordScript.Baseline
            },
            colorArgb = char.color,
            highlightArgb = char.highlight,
            halfPoints = char.halfPoints,
            font = fontName(char.font),
            smallCaps = char.smallCaps,
            spacingTwips = char.spacing,
            scalePercent = char.scale,
        )
    }

    /** One paragraph's contents. A page break inside it ends one part and starts the next. */
    private inner class ParagraphBuilder(private val para: Para, private val story: Story) {
        private inner class Part(val breakBefore: WordBreakBefore) {
            val inlines = ArrayList<WordInline>()
        }

        private val parts = arrayListOf(Part(WordBreakBefore.None))
        private val floatOrder = HashMap<WordInline, Int>()
        private val pending = StringBuilder()
        private var pendingStyle: WordRunStyle? = null
        private var pendingLink: String? = null

        private fun flush() {
            val style = pendingStyle ?: return
            if (pending.isNotEmpty()) parts.last().inlines += WordText(pending.toString(), style, pendingLink)
            pending.clear()
            pendingStyle = null
            pendingLink = null
        }

        private fun append(value: String, style: WordRunStyle, link: String?) {
            if (pendingStyle != style || pendingLink != link) flush()
            pendingStyle = style
            pendingLink = link
            pending.append(value)
        }

        private fun add(inline: WordInline) {
            flush()
            parts.last().inlines += inline
        }

        private fun cut(kind: WordBreakBefore) {
            flush()
            parts += Part(kind)
        }

        fun build(): List<WordBlock> {
            val chars = text.chars
            val istd = para.props.istd
            for (cp in para.start until para.end - 1) character(chars[cp], cp, istd)
            flush()
            val mark = characterAt(para.end - 1, istd)
            val style = paragraphStyle()
            val trailing = parts.last().takeIf { parts.size > 1 && it.inlines.isEmpty() }
            if (trailing != null) parts.removeAt(parts.lastIndex)
            parts.forEach { arrangeFloats(it.inlines) }
            val label = labelOf(mark)
            val blocks: List<WordBlock> = parts.mapIndexed { index, part ->
                var partStyle = style
                if (index != parts.lastIndex) partStyle = partStyle.copy(afterTwips = 0)
                if (index != 0) partStyle = partStyle.copy(beforeTwips = 0, pageBreakBefore = false)
                WordParagraph(partStyle, runOf(mark), part.inlines, label.takeIf { index == 0 }, part.breakBefore)
            }
            return if (trailing == null) blocks else blocks + WordBreak(trailing.breakBefore)
        }

        /**
         * Floats in the order they are painted, which the file keeps with the drawing and not with
         * the text. A picture that can't be shown and sits exactly on a real shape is that shape's
         * fallback for older software, so it goes.
         */
        private fun arrangeFloats(inlines: MutableList<WordInline>) {
            val places = inlines.indices.filter { inlines[it] in floatOrder }
            if (places.size < 2) return
            val shapes = places.map { inlines[it] as WordBoxed }.filterIsInstance<WordShape>()
            val sorted = places.map { inlines[it] }.sortedBy { floatOrder[it] ?: 0 }
            places.forEachIndexed { index, place -> inlines[place] = sorted[index] }
            val duplicates = places.map { inlines[it] }.filter { inline -> inline is WordPlaceholder && shapes.any { sameBox(it, inline) } }
            inlines.removeAll { it in duplicates }
        }

        private fun sameBox(shape: WordBoxed, other: WordBoxed): Boolean =
            abs(shape.widthTwips - other.widthTwips) <= SAME_BOX_TWIPS && abs(shape.heightTwips - other.heightTwips) <= SAME_BOX_TWIPS &&
                abs((shape.anchor?.horizontalOffsetTwips ?: 0) - (other.anchor?.horizontalOffsetTwips ?: 0)) <= SAME_BOX_TWIPS &&
                abs((shape.anchor?.verticalOffsetTwips ?: 0) - (other.anchor?.verticalOffsetTwips ?: 0)) <= SAME_BOX_TWIPS

        private fun character(char: Char, cp: Int, istd: Int) {
            when (char) {
                FIELD_BEGIN -> {
                    story.fields += OpenField()
                    return
                }
                FIELD_SEPARATOR -> {
                    story.fields.lastOrNull()?.let(::separate)
                    return
                }
                FIELD_END -> {
                    val field = story.fields.removeLastOrNull() ?: return
                    if (!field.inResult) separate(field)
                    return
                }
            }
            if (story.hidingCode) {
                story.fields.last().code.append(char)
                return
            }
            if (story.fields.lastOrNull()?.kind != null) return
            val look = characterAt(cp, istd)
            if (look.vanish || look.deleted) return
            when {
                char == '\t' -> add(WordTab(runOf(look)))
                char == '\u000B' -> add(WordLineBreak(runOf(look)))
                char == '\u000C' -> cut(WordBreakBefore.Page)
                char == '\u000E' -> cut(WordBreakBefore.Column)
                char == '\u0001' && look.special -> inlinePicture(look)?.let(::add)
                char == '\u0008' && look.special -> floating(cp)?.let { boxed ->
                    add(boxed)
                    floatOrder[boxed] = shapeAnchors[cp]?.let { art.shape(it.id)?.order } ?: 0
                }
                char == '\u0002' && look.special -> noteMark(cp, look)
                char < ' ' && look.special -> Unit
                look.symbol >= 0 -> symbol(look)
                char == '\u001E' -> append("\u2011", runOf(look), story.link)
                char == '\u001F' -> Unit
                char < ' ' -> Unit
                else -> append(if (look.caps) char.uppercase() else char.toString(), runOf(look), story.link)
            }
        }

        /** A field's code is finished: it decides what, if anything, stands in for the field. */
        private fun separate(field: OpenField) {
            field.inResult = true
            val code = field.code.toString().trim()
            val keyword = code.substringBefore(' ').uppercase()
            when (keyword) {
                "HYPERLINK" -> field.link = hyperlinkOf(code)
                "PAGE" -> {
                    field.kind = WordFieldKind.PageNumber
                    add(WordField(WordFieldKind.PageNumber, runOf(DocChar())))
                }
                "NUMPAGES" -> {
                    field.kind = WordFieldKind.PageCount
                    add(WordField(WordFieldKind.PageCount, runOf(DocChar())))
                }
            }
        }

        private fun hyperlinkOf(code: String): String? {
            val arguments = code.substringAfter(' ', "").trim()
            if (arguments.startsWith("\\l")) return null
            val target = arguments.substringAfter('"', "").substringBefore('"').ifEmpty { arguments.substringBefore(' ') }
            return normalizedLinkUrl(target)
        }

        private fun noteMark(cp: Int, look: DocChar) {
            if (story.kind == StoryKind.Note) return
            footnoteIds[cp]?.let { add(WordNoteRef(it, false, runOf(look))) }
            endnoteIds[cp]?.let { add(WordNoteRef(it, true, runOf(look))) }
        }

        private fun symbol(look: DocChar) {
            val font = fontName(look.symbolFont)
            val char = look.symbol.toChar().toString()
            append(decodeSymbolFont(font, char) ?: char, runOf(look).copy(font = null), story.link)
        }

        private fun paragraphStyle(): WordParagraphStyle {
            val props = para.props
            val builtIn = styles.builtIn(props.istd)
            val outline = when {
                props.outline < DocPara.NO_OUTLINE -> props.outline
                builtIn in 1..9 -> builtIn - 1
                else -> null
            }
            return WordParagraphStyle(
                name = styles.name(props.istd),
                outlineLevel = outline,
                align = props.align,
                leftTwips = props.left,
                rightTwips = props.right,
                firstLineTwips = props.first,
                beforeTwips = props.before,
                afterTwips = props.after,
                lineValue = props.lineValue,
                lineRule = props.lineRule,
                contextualSpacing = props.contextual,
                keepNext = props.keepNext,
                keepLines = props.keepLines,
                pageBreakBefore = props.pageBreak,
                widowControl = props.widow,
                shadingArgb = props.shade,
                tabs = props.tabs,
                rightToLeft = props.rightToLeft,
            )
        }

        private fun labelOf(mark: DocChar): WordListLabel? {
            val props = para.props
            if (props.listOverride <= 0) return null
            val allLists = lists ?: return null
            val levels = allLists.levels(props.listOverride) ?: return null
            val level = props.listLevel.coerceIn(0, levels.lastIndex)
            val format = levels[level] ?: return null
            val id = allLists.listId(props.listOverride) ?: return null
            val values = listValues.getOrPut(id) { IntArray(MAX_LIST_LEVELS) }
            val started = listStarted.getOrPut(id) { BooleanArray(MAX_LIST_LEVELS) }
            val override = allLists.startOverride(props.listOverride, level)
                ?.takeIf { overridesUsed.add(props.listOverride.toLong() shl 8 or level.toLong()) }
            values[level] = override ?: if (started[level]) values[level] + 1 else format.start
            started[level] = true
            for (deeper in level + 1 until MAX_LIST_LEVELS) if (levels.getOrNull(deeper)?.noRestart != true) started[deeper] = false
            if (format.format == NO_NUMBER) return null
            val look = applyCharacter(mark, format.character, 0, mark)
            val run = runOf(look)
            val text = if (format.format == BULLET) {
                decodeSymbolFont(run.font, format.text) ?: format.text.ifEmpty { "•" }
            } else {
                buildString {
                    for (char in format.text) {
                        if (char.code < MAX_LIST_LEVELS) {
                            val source = levels.getOrNull(char.code)
                            val shown = if (started[char.code]) values[char.code] else source?.start ?: 1
                            append(formatNumber(shown, if (format.legal) "decimal" else numberFormat(source?.format ?: 0)))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            return WordListLabel(
                text = text,
                style = if (isSymbolFont(run.font)) run.copy(font = null) else run,
                followedByTab = format.follow == 0,
                followedBySpace = format.follow == 1,
                bullet = format.format == BULLET,
                level = level,
            ).takeIf { it.text.isNotEmpty() }
        }

        private fun inlinePicture(look: DocChar): WordBoxed? {
            if (look.data || look.ole || look.picture < 0) return null
            val at = look.picture
            if (at + PICTURE_HEADER > data.size) return null
            val width = data.s16(at + 28) * data.u16(at + 32) / 1000
            val height = data.s16(at + 30) * data.u16(at + 34) / 1000
            var blob = at + data.u16(at + 4)
            if (data.u16(at + 6) == PICTURE_WITH_NAME) blob += 1 + data.u8(blob)
            val bytes = art.inlineBlip(data, blob, min(data.size, at + data.i32(at)))
            return pictureOf(bytes, at, width, height, null)
        }

        private fun pictureOf(bytes: ByteArray?, key: Int, width: Int, height: Int, anchor: WordAnchor?): WordBoxed {
            bytes ?: return WordPlaceholder(width, height, anchor)
            val name = pictureNames.getOrPut(key) {
                "doc-picture-${pictures.size}".also { pictures[it] = bytes }
            }
            return WordPicture(name, width, height, null, anchor)
        }

        private fun floating(cp: Int): WordBoxed? {
            val spa = shapeAnchors[cp] ?: return null
            val anchor = anchorOf(spa)
            val width = spa.right - spa.left
            val height = spa.bottom - spa.top
            val shape = art.shape(spa.id)
            textBoxes[spa.id]?.let { range ->
                val blocks = ArrayList<WordBlock>()
                readBlocks(withoutGuard(parasOf(range.first, range.last + 1)), 0, blocks, Story(StoryKind.TextBox))
                val insets = shape?.insets ?: IntArray(4)
                return WordShape(
                    blocks = blocks,
                    widthTwips = width,
                    heightTwips = height,
                    fillArgb = shape?.fill,
                    line = shape?.line?.let { WordBorder(WordBorderStyle.Single, SHAPE_LINE_EIGHTHS, it) },
                    form = formOf(shape),
                    insetLeftTwips = insets[0],
                    insetTopTwips = insets[1],
                    insetRightTwips = insets[2],
                    insetBottomTwips = insets[3],
                    anchor = anchor,
                )
            }
            shape ?: return null
            if (shape.picture > 0) return pictureOf(art.picture(shape.picture), -shape.picture, width, height, anchor)
            return WordShape(
                blocks = emptyList(),
                widthTwips = width,
                heightTwips = height,
                fillArgb = shape.fill,
                line = shape.line?.let { WordBorder(WordBorderStyle.Single, SHAPE_LINE_EIGHTHS, it) },
                form = formOf(shape),
                insetLeftTwips = 0,
                insetTopTwips = 0,
                insetRightTwips = 0,
                insetBottomTwips = 0,
                anchor = anchor,
            )
        }

        private fun formOf(shape: DocShape?): WordShapeForm = when (shape?.type) {
            SHAPE_ELLIPSE -> WordShapeForm.Ellipse
            SHAPE_ROUND_RECT -> WordShapeForm.RoundedRectangle
            else -> WordShapeForm.Rectangle
        }

        private fun anchorOf(spa: DocAnchor): WordAnchor {
            val flags = spa.flags
            return WordAnchor(
                behindText = flags and ANCHOR_BELOW_TEXT != 0,
                wrap = when ((flags ushr 5) and 0xF) {
                    1 -> WordWrap.TopAndBottom
                    3 -> WordWrap.None
                    else -> WordWrap.Square
                },
                horizontalFrom = when ((flags ushr 1) and 3) {
                    0 -> "margin"
                    1 -> "page"
                    else -> "column"
                },
                horizontalOffsetTwips = spa.left,
                horizontalAlign = null,
                verticalFrom = when ((flags ushr 3) and 3) {
                    0 -> "margin"
                    1 -> "page"
                    else -> "paragraph"
                },
                verticalOffsetTwips = spa.top,
                verticalAlign = null,
            )
        }
    }

    private fun numberFormat(code: Int): String = when (code) {
        1 -> "upperRoman"
        2 -> "lowerRoman"
        3 -> "upperLetter"
        4 -> "lowerLetter"
        5 -> "ordinal"
        22 -> "decimalZero"
        else -> "decimal"
    }

    // --- floating objects ------------------------------------------------------------------------

    private fun readShapeAnchors(): Map<Int, DocAnchor> {
        val length = fib.length(DocTable.SHAPES)
        if (length < 4) return emptyMap()
        val offset = fib.offset(DocTable.SHAPES)
        val count = (length - 4) / (4 + SHAPE_ENTRY)
        val positions = offset + (count + 1) * 4
        return (0 until count).associate { index ->
            val at = positions + index * SHAPE_ENTRY
            table.i32(offset + index * 4) to DocAnchor(
                id = table.i32(at),
                left = table.i32(at + 4),
                top = table.i32(at + 8),
                right = table.i32(at + 12),
                bottom = table.i32(at + 16),
                flags = table.u16(at + 20),
            )
        }
    }

    /** Which stretch of the text-box story each shape holds, by the shape's number. */
    private fun readTextBoxes(): Map<Int, IntRange> {
        val length = fib.length(DocTable.TEXT_BOXES)
        if (length < 4) return emptyMap()
        val offset = fib.offset(DocTable.TEXT_BOXES)
        val count = (length - 4) / (4 + TEXT_BOX_ENTRY)
        val entries = offset + (count + 1) * 4
        return (0 until count).mapNotNull { index ->
            val id = table.i32(entries + index * TEXT_BOX_ENTRY + 14)
            val from = textBoxesStart + table.i32(offset + index * 4)
            val to = textBoxesStart + table.i32(offset + (index + 1) * 4)
            if (from < to) id to (from until to) else null
        }.toMap()
    }

    // --- tables ----------------------------------------------------------------------------------

    private class CellFormat(val flags: Int, val borders: WordBorders?)

    private class RowFormat(
        val centers: IntArray,
        val cells: List<CellFormat?>,
        val gap: Int,
        val height: Int,
        val header: Boolean,
        val cantSplit: Boolean,
        val align: WordAlign,
        val shades: Map<Int, Int?>,
        val merges: Map<Int, Int>,
        val tableBorders: WordBorders?,
    )

    private fun rowFormat(grpprl: ByteArray?): RowFormat? {
        grpprl ?: return null
        var centers: IntArray? = null
        var cells: List<CellFormat?> = emptyList()
        var gap = 0
        var left: Int? = null
        var height = 0
        var header = false
        var cantSplit = false
        var align = WordAlign.Left
        var borders: WordBorders? = null
        val shades = HashMap<Int, Int?>()
        val merges = HashMap<Int, Int>()
        fun shade(at: Int, firstCell: Int) {
            val count = grpprl.u8(at) / SHADE_SIZE
            for (index in 0 until count) {
                val entry = at + 1 + index * SHADE_SIZE
                shades[firstCell + index] = colorRefAt(grpprl, entry + 4) ?: colorRefAt(grpprl, entry).takeIf { grpprl.u16(entry + 8) == 1 }
            }
        }
        forEachSprm(grpprl, 2) { sprm, at ->
            when (sprm) {
                Sprm.T_DEF_TABLE -> {
                    val size = grpprl.u16(at)
                    val count = grpprl.u8(at + 2)
                    centers = IntArray(count + 1) { grpprl.s16(at + 3 + it * 2) }
                    val first = at + 3 + (count + 1) * 2
                    val stored = min(count, (size - 1 - (count + 1) * 2) / CELL_SIZE)
                    cells = List(stored) { index ->
                        val entry = first + index * CELL_SIZE
                        CellFormat(
                            flags = grpprl.u16(entry),
                            borders = WordBorders(
                                top = border80At(grpprl, entry + 4),
                                left = border80At(grpprl, entry + 8),
                                bottom = border80At(grpprl, entry + 12),
                                right = border80At(grpprl, entry + 16),
                            ),
                        )
                    }
                }
                Sprm.T_GAP -> gap = grpprl.s16(at)
                Sprm.T_LEFT -> left = grpprl.s16(at)
                Sprm.T_ROW_HEIGHT -> height = grpprl.s16(at)
                Sprm.T_HEADER -> header = grpprl.u8(at) != 0
                Sprm.T_CANT_SPLIT, Sprm.T_CANT_SPLIT_90 -> cantSplit = grpprl.u8(at) != 0
                Sprm.T_JC, Sprm.T_JC_90 -> align = when (grpprl.u16(at)) {
                    1 -> WordAlign.Center
                    2 -> WordAlign.Right
                    else -> WordAlign.Left
                }
                Sprm.T_SHADE -> shade(at, 0)
                Sprm.T_SHADE_2 -> shade(at, SHADES_PER_SPRM)
                Sprm.T_SHADE_3 -> shade(at, SHADES_PER_SPRM * 2)
                Sprm.T_SHADE_80 -> for (index in 0 until grpprl.u8(at) / 2) {
                    shades[index] = paletteColor((grpprl.u16(at + 1 + index * 2) ushr 5) and 0x1F)
                }
                Sprm.T_BORDERS_80 -> borders = WordBorders(
                    top = border80At(grpprl, at + 1),
                    left = border80At(grpprl, at + 5),
                    bottom = border80At(grpprl, at + 9),
                    right = border80At(grpprl, at + 13),
                    insideHorizontal = border80At(grpprl, at + 17),
                    insideVertical = border80At(grpprl, at + 21),
                )
                Sprm.T_BORDERS -> borders = WordBorders(
                    top = borderAt(grpprl, at + 1),
                    left = borderAt(grpprl, at + 9),
                    bottom = borderAt(grpprl, at + 17),
                    right = borderAt(grpprl, at + 25),
                    insideHorizontal = borderAt(grpprl, at + 33),
                    insideVertical = borderAt(grpprl, at + 41),
                )
                Sprm.T_VERT_MERGE -> merges[grpprl.u8(at + 1)] = grpprl.u8(at + 2)
            }
        }
        var edges = centers ?: return null
        left?.let { wanted ->
            val shift = wanted - gap - edges[0]
            edges = IntArray(edges.size) { edges[it] + shift }
        }
        return RowFormat(edges, cells, gap, height, header, cantSplit, align, shades, merges, borders)
    }

    private fun tableOf(paras: List<Para>, level: Int, story: Story): WordTable? {
        class Draft(val cells: List<List<Para>>, val format: RowFormat?)
        val drafts = ArrayList<Draft>()
        var cells = ArrayList<List<Para>>()
        var cell = ArrayList<Para>()
        for (para in paras) {
            val props = para.props
            val endsRow = if (level == 1) props.rowEnd else props.innerRowEnd && props.tableDepth == level
            if (endsRow) {
                drafts += Draft(cells, runCatching { rowFormat(para.grpprl) }.getOrNull())
                cells = ArrayList()
                cell = ArrayList()
                continue
            }
            cell += para
            val endsCell = if (level == 1) para.terminator == CELL_MARK && props.tableDepth == 1 else props.innerCell && props.tableDepth == level
            if (endsCell) {
                cells += cell
                cell = ArrayList()
            }
        }
        if (cell.isNotEmpty()) cells += cell
        if (cells.isNotEmpty()) drafts += Draft(cells, null)
        if (drafts.isEmpty()) return null

        val edges = drafts.flatMap { it.format?.centers?.toList().orEmpty() }.toSortedSet().toIntArray()
        val index = edges.withIndex().associate { (position, value) -> value to position }
        val rows = drafts.map { draft -> rowOf(draft.cells, draft.format, index, edges.size, level, story) }
        val first = drafts.first().format
        val gap = first?.gap ?: 0
        return WordTable(
            gridTwips = IntArray((edges.size - 1).coerceAtLeast(0)) { edges[it + 1] - edges[it] },
            rows = rows,
            borders = first?.tableBorders,
            // Measured to the text, as the layout expects of a file this old, and stored to the cell edge.
            indentTwips = (edges.firstOrNull() ?: 0) + gap,
            align = first?.align ?: WordAlign.Left,
            widthTwips = null,
            widthPercent = null,
        )
    }

    private fun rowOf(cells: List<List<Para>>, format: RowFormat?, index: Map<Int, Int>, edgeCount: Int, level: Int, story: Story): WordRow {
        val out = ArrayList<WordCell>()
        val centers = format?.centers
        for (column in cells.indices) {
            val flags = format?.cells?.getOrNull(column)?.flags ?: 0
            val mergedAcross = (flags and H_MERGE_MASK) >= H_MERGE_CONTINUE
            val left = centers?.getOrNull(column)
            val right = centers?.getOrNull(column + 1)
            val span = if (left != null && right != null) ((index[right] ?: 0) - (index[left] ?: 0)).coerceAtLeast(1) else 1
            if (mergedAcross && out.isNotEmpty()) {
                val previous = out.removeAt(out.lastIndex)
                out += copyWithSpan(previous, previous.gridSpan + span, (previous.widthTwips ?: 0) + ((right ?: 0) - (left ?: 0)))
                continue
            }
            val merge = verticalMerge(flags, format?.merges?.get(column))
            val blocks = ArrayList<WordBlock>()
            if (merge != WordVerticalMerge.Continue) readBlocks(cells[column], level, blocks, story)
            out += WordCell(
                blocks = blocks,
                gridSpan = span,
                verticalMerge = merge,
                widthTwips = if (left != null && right != null) right - left else null,
                shadingArgb = format?.shades?.get(column),
                borders = format?.cells?.getOrNull(column)?.borders?.takeIf(::hasAny),
                marginLeftTwips = format?.gap ?: 0,
                marginTopTwips = 0,
                marginRightTwips = format?.gap ?: 0,
                marginBottomTwips = 0,
                align = when ((flags ushr 7) and 3) {
                    1 -> WordCellAlign.Center
                    2 -> WordCellAlign.Bottom
                    else -> WordCellAlign.Top
                },
            )
        }
        val before = centers?.firstOrNull()?.let { index[it] } ?: 0
        val after = centers?.lastOrNull()?.let { (edgeCount - 1) - (index[it] ?: 0) } ?: 0
        val height = format?.height ?: 0
        return WordRow(
            cells = out,
            heightTwips = abs(height).takeIf { it != 0 },
            exactHeight = height < 0,
            isHeader = format?.header == true,
            cantSplit = format?.cantSplit == true,
            gridBefore = before,
            gridAfter = after,
        )
    }

    private fun hasAny(borders: WordBorders): Boolean =
        borders.top != null || borders.left != null || borders.bottom != null || borders.right != null

    private fun copyWithSpan(cell: WordCell, span: Int, width: Int): WordCell = WordCell(
        cell.blocks, span, cell.verticalMerge, width, cell.shadingArgb, cell.borders,
        cell.marginLeftTwips, cell.marginTopTwips, cell.marginRightTwips, cell.marginBottomTwips, cell.align,
    )

    private fun verticalMerge(flags: Int, override: Int?): WordVerticalMerge {
        val value = override ?: ((flags ushr 5) and 3)
        return when (value) {
            0 -> WordVerticalMerge.None
            1 -> WordVerticalMerge.Continue
            else -> WordVerticalMerge.Restart
        }
    }

    private companion object {
        /** Word from before 2013 measures a table from its text; this format never left those rules. */
        const val OLD_COMPATIBILITY = 12
        const val SECTION_ENTRY = 12
        const val SHAPE_ENTRY = 26
        const val TEXT_BOX_ENTRY = 22
        const val HEADER_SEPARATORS = 6
        const val HEADER_STORIES_PER_SECTION = 6
        const val MAX_LIST_LEVELS = 9
        const val BULLET = 23
        const val NO_NUMBER = 255
        const val PICTURE_HEADER = 68
        const val PICTURE_WITH_NAME = 0x66
        const val CELL_SIZE = 20
        const val SHADE_SIZE = 10
        const val SHADES_PER_SPRM = 22
        const val H_MERGE_MASK = 3
        const val H_MERGE_CONTINUE = 2
        const val ANCHOR_BELOW_TEXT = 0x4000
        const val SHAPE_LINE_EIGHTHS = 4
        const val SAME_BOX_TWIPS = 40
        const val SHAPE_ELLIPSE = 3
        const val SHAPE_ROUND_RECT = 2

        const val CELL_MARK = '\u0007'
        const val FIELD_BEGIN = '\u0013'
        const val FIELD_SEPARATOR = '\u0014'
        const val FIELD_END = '\u0015'
    }
}
