package com.vivenotes.fileimport.docx

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.model.normalizedLinkUrl
import org.w3c.dom.Element
import kotlin.math.roundToInt

/**
 * Reads a `.docx` into a [WordDocument].
 *
 * Formatting is resolved as it is read, so nothing past here needs to know that Word has styles. The
 * layers, weakest first, are document defaults, table style, paragraph style, numbering, character
 * style, and what the run itself says.
 */
internal class DocxReader(private val pkg: DocxPackage) {
    private val mainRelationships = pkg.relationships(pkg.mainPart)
    private val theme = WordTheme.read(relatedXml("theme"))
    private val styles = WordStyles(relatedXml("styles"), theme)
    private val numbering = WordNumbering(relatedXml("numbering"), styles)

    /** Field instructions and results run across runs, and a table of contents across paragraphs. */
    private val fields = ArrayList<OpenField>()

    /** How deep the blocks being read are nested; a file past [MAX_DEPTH] loses the excess. */
    private var depth = 0

    /** A header part is often shared by every section, so each is read once. */
    private val headerParts = HashMap<String, List<WordBlock>>()

    private class ReadContext(
        val relationships: Map<String, DocxRelationship>,
        /** What the table style around this cell says about its paragraphs and text. */
        val tableParagraph: ParagraphOverlay? = null,
        val tableRun: RunOverlay? = null,
    )

    private class OpenField {
        val instruction = StringBuilder()
        var separated = false
        var suppressResult = false
        var link: String? = null
        var emitted = false
    }

    fun read(): WordDocument {
        val root = pkg.xml(pkg.mainPart) ?: throw ImportException(ImportFailure.Unreadable)
        val body = root.wChild("body") ?: throw ImportException(ImportFailure.Unreadable)
        val sections = readSections(body)
        return WordDocument(
            sections = sections,
            footnotes = readNotes("footnotes"),
            endnotes = readNotes("endnotes"),
            settings = readSettings(relatedXml("settings")),
        )
    }

    private fun relatedPart(kind: String): String? = mainRelationships.values
        .firstOrNull { !it.external && it.type.endsWith("/$kind") }
        ?.target

    private fun relatedXml(kind: String): Element? = relatedPart(kind)?.let(pkg::xml)

    private fun readSettings(root: Element?): WordSettings {
        root ?: return WordSettings()
        val compatibility = root.wChild("compat")?.children(NS_W, "compatSetting")
            ?.firstOrNull { it.w("name") == "compatibilityMode" }
            ?.w("val")?.toIntOrNull()
        return WordSettings(
            defaultTabTwips = root.wVal("defaultTabStop")?.let(::parseTwips)?.takeIf { it > 0 } ?: 720,
            evenAndOddHeaders = root.wChild("evenAndOddHeaders")?.isOn() == true,
            compatibilityMode = compatibility ?: 12,
        )
    }

    private fun readNotes(kind: String): Map<Int, List<WordBlock>> {
        val part = relatedPart(kind) ?: return emptyMap()
        val root = pkg.xml(part) ?: return emptyMap()
        val context = ReadContext(pkg.relationships(part))
        fields.clear()
        val notes = HashMap<Int, List<WordBlock>>()
        root.children(NS_W, kind.dropLast(1)).forEach { note ->
            val id = note.w("id")?.toIntOrNull() ?: return@forEach
            if (note.w("type") in setOf("separator", "continuationSeparator", "continuationNotice")) return@forEach
            notes[id] = readBlocks(note, context)
        }
        return notes
    }

    // --- sections ------------------------------------------------------------------------------

    private fun readSections(body: Element): List<WordSection> {
        val context = ReadContext(mainRelationships)
        val sections = ArrayList<WordSection>()
        var blocks = ArrayList<WordBlock>()
        var headers = WordHeaderSet()
        var footers = WordHeaderSet()

        fun endSection(properties: Element?) {
            // A section that names no header of a kind keeps the previous section's.
            headers = readHeaderSet(properties, "headerReference", headers)
            footers = readHeaderSet(properties, "footerReference", footers)
            sections += readSection(properties, blocks, headers, footers)
            blocks = ArrayList()
        }

        fun walk(elements: List<Element>) {
            for (element in elements) {
                when {
                    element.isIn(NS_W, "p") -> {
                        blocks += readParagraph(element, context)
                        element.wChild("pPr")?.wChild("sectPr")?.let(::endSection)
                    }
                    element.isIn(NS_W, "tbl") -> readTable(element, context)?.let { blocks += it }
                    element.isIn(NS_W, "sectPr") -> endSection(element)
                    element.isIn(NS_W, "sdt") -> element.wChild("sdtContent")?.let { walk(it.childElements) }
                    element.isIn(NS_W, "customXml") || element.isIn(NS_W, "smartTag") -> walk(element.childElements)
                    element.isIn(NS_MC, "AlternateContent") -> chosenAlternate(element)?.let { walk(it.childElements) }
                }
            }
        }

        walk(body.childElements)
        if (blocks.isNotEmpty() || sections.isEmpty()) endSection(null)
        return sections
    }

    private fun readHeaderSet(properties: Element?, element: String, previous: WordHeaderSet): WordHeaderSet {
        var set = previous
        properties?.children(NS_W, element)?.forEach { reference ->
            val id = reference.attr(NS_R, "id") ?: return@forEach
            val part = mainRelationships[id]?.takeUnless { it.external }?.target ?: return@forEach
            val blocks = headerParts.getOrPut(part) {
                fields.clear()
                val root = pkg.xml(part)
                if (root == null) emptyList() else readBlocks(root, ReadContext(pkg.relationships(part)))
            }
            set = when (reference.w("type")) {
                "first" -> WordHeaderSet(set.default, blocks, set.even)
                "even" -> WordHeaderSet(set.default, set.first, blocks)
                else -> WordHeaderSet(blocks, set.first, set.even)
            }
        }
        return set
    }

    private fun readSection(
        properties: Element?,
        blocks: List<WordBlock>,
        headers: WordHeaderSet,
        footers: WordHeaderSet,
    ): WordSection {
        val size = properties?.wChild("pgSz")
        val margins = properties?.wChild("pgMar")
        fun margin(name: String, fallback: Int): Int = parseTwips(margins?.w(name)) ?: fallback
        val gutter = margin("gutter", 0)
        val page = WordPageGeometry(
            widthTwips = parseTwips(size?.w("w"))?.takeIf { it > 0 } ?: 12240,
            heightTwips = parseTwips(size?.w("h"))?.takeIf { it > 0 } ?: 15840,
            // A negative top margin is Word's way of saying "do not move for the header".
            topTwips = kotlin.math.abs(margin("top", 1440)),
            bottomTwips = kotlin.math.abs(margin("bottom", 1440)),
            leftTwips = margin("left", 1440) + gutter,
            rightTwips = margin("right", 1440),
            headerTwips = margin("header", 720),
            footerTwips = margin("footer", 720),
        )
        val columns = properties?.wChild("cols")
        return WordSection(
            page = page,
            columns = columns?.w("num")?.toIntOrNull()?.coerceIn(1, MAX_COLUMNS) ?: 1,
            columnGapTwips = parseTwips(columns?.w("space")) ?: 720,
            start = when (properties?.wVal("type")?.lowercase()) {
                "continuous" -> WordSectionStart.Continuous
                "oddpage" -> WordSectionStart.OddPage
                "evenpage" -> WordSectionStart.EvenPage
                "nextcolumn" -> WordSectionStart.NextColumn
                else -> WordSectionStart.NextPage
            },
            titlePage = properties?.wChild("titlePg")?.isOn() == true,
            pageNumberStart = properties?.wChild("pgNumType")?.w("start")?.toIntOrNull(),
            pageNumberFormat = properties?.wChild("pgNumType")?.w("fmt"),
            headers = headers,
            footers = footers,
            blocks = blocks,
        )
    }

    // --- blocks --------------------------------------------------------------------------------

    private fun readBlocks(container: Element, context: ReadContext): List<WordBlock> {
        if (depth >= MAX_DEPTH) return emptyList()
        depth++
        try {
            val blocks = ArrayList<WordBlock>()
            fun walk(elements: List<Element>) {
                for (element in elements) {
                    when {
                        element.isIn(NS_W, "p") -> blocks += readParagraph(element, context)
                        element.isIn(NS_W, "tbl") -> readTable(element, context)?.let { blocks += it }
                        element.isIn(NS_W, "sdt") -> element.wChild("sdtContent")?.let { walk(it.childElements) }
                        element.isIn(NS_W, "customXml") -> walk(element.childElements)
                        element.isIn(NS_MC, "AlternateContent") -> chosenAlternate(element)?.let { walk(it.childElements) }
                    }
                }
            }
            walk(container.childElements)
            return blocks
        } finally {
            depth--
        }
    }

    private fun readParagraph(paragraph: Element, context: ReadContext): List<WordBlock> {
        val properties = paragraph.wChild("pPr")
        val direct = properties?.let(styles::readParagraph)
        val styleId = properties?.wVal("pStyle")

        val underStyle = context.tableParagraph?.over(styles.defaultParagraph) ?: styles.defaultParagraph
        val styled = styles.paragraphOverlay(styleId)?.over(underStyle) ?: underStyle ?: ParagraphOverlay()
        val numId = direct?.numId ?: styled.numId
        val numLevel = (direct?.numLevel ?: styled.numLevel ?: 0).coerceIn(0, 8)
        val level = numbering.level(numId, numLevel)
        val withNumbering = level?.paragraph?.over(styled) ?: styled
        val merged = direct?.over(withNumbering) ?: withNumbering
        val style = merged.resolved(styles.paragraphStyleName(styleId))

        val paragraphRun = styles.paragraphRunOverlay(styleId)
        val styleRun = paragraphRun?.xorToggles(context.tableRun) ?: context.tableRun
        val base = styleRun?.over(styles.defaultRun) ?: styles.defaultRun ?: RunOverlay()
        val mark = properties?.wChild("rPr")?.let { runOverlay(it, base) } ?: base

        val label = if (level != null && numId != null) {
            numbering.nextLabel(numId, numLevel)
                ?.let { text -> labelFor(text, level, mark, numLevel) }
                ?.takeIf { it.text.isNotEmpty() }
        } else {
            null
        }

        val reader = ParagraphReader(base, context)
        reader.walk(paragraph.childElements)
        return reader.finish(style, mark.resolved(), label)
    }

    private fun labelFor(text: String, level: NumberingLevel, mark: RunOverlay, levelIndex: Int): WordListLabel {
        val overlay = level.run?.over(mark) ?: mark
        val style = overlay.resolved()
        return WordListLabel(
            text = decodeSymbolFont(style.font, text) ?: text,
            style = if (isSymbolFont(style.font)) style.copy(font = null) else style,
            followedByTab = level.suffix == "tab",
            followedBySpace = level.suffix == "space",
            bullet = level.format == "bullet",
            level = levelIndex,
        )
    }

    private fun runOverlay(properties: Element?, base: RunOverlay): RunOverlay {
        properties ?: return base
        val character = properties.wVal("rStyle")?.let(styles::characterRunOverlay)
        val styled = character?.xorToggles(base) ?: base
        return styles.readRun(properties).over(styled)
    }

    /** One paragraph's contents. A page break inside it ends one part and starts the next. */
    private inner class ParagraphReader(val base: RunOverlay, val context: ReadContext) {
        private inner class Part(val breakBefore: WordBreakBefore) {
            val inlines = ArrayList<WordInline>()
        }

        private val parts = ArrayList<Part>()
        private var current = Part(WordBreakBefore.None).also { parts += it }

        fun finish(style: WordParagraphStyle, mark: WordRunStyle, label: WordListLabel?): List<WordBlock> {
            // A break that ends the paragraph leaves no text to carry over, and no empty line
            // either: the mark stays behind, and only the break itself goes on.
            val trailing = parts.last().takeIf { parts.size > 1 && it.inlines.isEmpty() }
            if (trailing != null) parts.removeAt(parts.lastIndex)
            val paragraphs: List<WordBlock> = parts.mapIndexed { index, part ->
                var partStyle = style
                if (index != parts.lastIndex) partStyle = partStyle.copy(afterTwips = 0)
                if (index != 0) partStyle = partStyle.copy(beforeTwips = 0, pageBreakBefore = false)
                WordParagraph(
                    style = partStyle,
                    markStyle = mark,
                    inlines = part.inlines,
                    label = label.takeIf { index == 0 },
                    breakBefore = part.breakBefore,
                )
            }
            return if (trailing == null) paragraphs else paragraphs + WordBreak(trailing.breakBefore)
        }

        private fun add(inline: WordInline) {
            current.inlines += inline
        }

        private fun addText(text: String, style: WordRunStyle, link: String?) {
            if (text.isEmpty()) return
            val last = current.inlines.lastOrNull()
            if (last is WordText && last.link == link && last.style == style) {
                current.inlines[current.inlines.lastIndex] = WordText(last.text + text, style, link)
            } else {
                add(WordText(text, style, link))
            }
        }

        fun walk(elements: List<Element>, link: String? = null) {
            for (element in elements) {
                when {
                    element.isIn(NS_W, "r") -> readRun(element, link)
                    element.isIn(NS_W, "hyperlink") -> walk(element.childElements, hyperlinkOf(element))
                    element.isIn(NS_W, "fldSimple") -> readSimpleField(element, link)
                    element.isIn(NS_W, "sdt") -> element.wChild("sdtContent")?.let { walk(it.childElements, link) }
                    element.isIn(NS_W, "smartTag") || element.isIn(NS_W, "customXml") ||
                        element.isIn(NS_W, "ins") || element.isIn(NS_W, "moveTo") ||
                        element.isIn(NS_W, "dir") || element.isIn(NS_W, "bdo") -> walk(element.childElements, link)
                    element.isIn(NS_M, "oMath") -> readEquation(element, display = false)
                    element.isIn(NS_M, "oMathPara") ->
                        element.children(NS_M, "oMath").forEach { readEquation(it, display = true) }
                    element.isIn(NS_MC, "AlternateContent") ->
                        chosenAlternate(element)?.let { walk(it.childElements, link) }
                }
            }
        }

        private fun hyperlinkOf(element: Element): String? {
            val id = element.attr(NS_R, "id") ?: return null
            val target = context.relationships[id]?.takeIf { it.external }?.target ?: return null
            return normalizedLinkUrl(target)
        }

        private fun readEquation(math: Element, display: Boolean) {
            val latex = ommlToLatex(math)
            val plain = ommlPlainText(math)
            if (latex.isEmpty() && plain.isEmpty()) return
            add(WordEquation(latex, plain, base.resolved(), display))
        }

        private fun readSimpleField(element: Element, link: String?) {
            val instruction = element.w("instr").orEmpty()
            when (fieldKind(instruction)) {
                "PAGE" -> add(WordField(WordFieldKind.PageNumber, base.resolved()))
                "NUMPAGES", "SECTIONPAGES" -> add(WordField(WordFieldKind.PageCount, base.resolved()))
                "HYPERLINK" -> walk(element.childElements, hyperlinkFromInstruction(instruction) ?: link)
                else -> walk(element.childElements, link)
            }
        }

        private fun fieldKind(instruction: String): String =
            instruction.trim().substringBefore(' ').uppercase()

        private fun hyperlinkFromInstruction(instruction: String): String? =
            Regex("""HYPERLINK\s+"([^"]+)"""").find(instruction)?.groupValues?.get(1)?.let(::normalizedLinkUrl)

        private val insideInstruction: Boolean get() = fields.any { !it.separated }

        private val resultSuppressed: Boolean get() = fields.any { it.suppressResult }

        private fun fieldCharacter(character: Element) {
            when (character.w("fldCharType")) {
                "begin" -> if (fields.size < MAX_DEPTH) fields += OpenField()
                "separate" -> fields.lastOrNull()?.let { field ->
                    field.separated = true
                    openFieldResult(field)
                }
                "end" -> fields.removeLastOrNull()?.let { field ->
                    // A field with no result to show, such as a bare PAGE, still shows its value.
                    if (!field.separated) openFieldResult(field)
                }
            }
        }

        private fun openFieldResult(field: OpenField) {
            if (field.emitted) return
            field.emitted = true
            val instruction = field.instruction.toString()
            when (fieldKind(instruction)) {
                "PAGE" -> {
                    field.suppressResult = true
                    add(WordField(WordFieldKind.PageNumber, base.resolved()))
                }
                "NUMPAGES", "SECTIONPAGES" -> {
                    field.suppressResult = true
                    add(WordField(WordFieldKind.PageCount, base.resolved()))
                }
                "HYPERLINK" -> field.link = hyperlinkFromInstruction(instruction)
            }
        }

        private fun readRun(run: Element, link: String?) {
            val overlay = runOverlay(run.wChild("rPr"), base)
            val style = overlay.resolved()
            val effectiveLink = fields.lastOrNull { it.link != null }?.link ?: link
            for (child in run.childElements) {
                if (child.namespaceURI == NS_W && child.localName == "fldChar") {
                    fieldCharacter(child)
                    continue
                }
                if (child.namespaceURI == NS_W && child.localName == "instrText") {
                    if (insideInstruction) fields.last().instruction.append(child.textContent.orEmpty())
                    continue
                }
                if (insideInstruction || resultSuppressed) continue
                if (overlay.isHidden) continue
                readRunChild(child, style, overlay, effectiveLink)
            }
        }

        private fun readRunChild(child: Element, style: WordRunStyle, overlay: RunOverlay, link: String?) {
            when {
                child.isIn(NS_W, "t") -> {
                    val raw = child.textContent.orEmpty()
                    val text = if (child.hasPreservedSpace) raw else raw.trim(' ', '\t', '\r', '\n')
                    val decoded = decodeSymbolFont(style.font, text)
                    when {
                        decoded != null -> addText(decoded, style.copy(font = null), link)
                        overlay.isCaps -> addText(text.uppercase(), style, link)
                        else -> addText(text, style, link)
                    }
                }
                child.isIn(NS_W, "tab") -> add(WordTab(style))
                child.isIn(NS_W, "ptab") -> add(
                    WordTab(
                        style,
                        absolute = when (child.w("alignment")?.lowercase()) {
                            "center" -> WordAlign.Center
                            "right" -> WordAlign.Right
                            else -> WordAlign.Left
                        },
                    ),
                )
                child.isIn(NS_W, "br") -> when (child.w("type")?.lowercase()) {
                    "page" -> current = Part(WordBreakBefore.Page).also { parts += it }
                    "column" -> current = Part(WordBreakBefore.Column).also { parts += it }
                    else -> add(WordLineBreak(style))
                }
                child.isIn(NS_W, "cr") -> add(WordLineBreak(style))
                child.isIn(NS_W, "noBreakHyphen") -> addText("‑", style, link)
                child.isIn(NS_W, "softHyphen") -> addText("­", style, link)
                child.isIn(NS_W, "sym") -> readSymbol(child, style, link)
                child.isIn(NS_W, "footnoteReference") ->
                    child.w("id")?.toIntOrNull()?.let { add(WordNoteRef(it, endnote = false, style)) }
                child.isIn(NS_W, "endnoteReference") ->
                    child.w("id")?.toIntOrNull()?.let { add(WordNoteRef(it, endnote = true, style)) }
                child.isIn(NS_W, "lastRenderedPageBreak") -> add(WordRenderedBreak)
                child.isIn(NS_W, "drawing") -> readDrawing(child, context)?.let(::add)
                child.isIn(NS_W, "pict") || child.isIn(NS_W, "object") -> readVml(child, context)?.let(::add)
                child.isIn(NS_MC, "AlternateContent") -> chosenAlternate(child)?.childElements?.forEach {
                    readRunChild(it, style, overlay, link)
                }
            }
        }

        private fun readSymbol(symbol: Element, style: WordRunStyle, link: String?) {
            val code = symbol.w("char")?.toIntOrNull(16) ?: return
            val font = symbol.w("font") ?: style.font
            val text = decodeSymbolFont(font, code.toChar().toString()) ?: code.toChar().toString()
            addText(text, style.copy(font = null), link)
        }
    }

    // --- tables --------------------------------------------------------------------------------

    private class TableLook(
        val firstRow: Boolean,
        val lastRow: Boolean,
        val firstColumn: Boolean,
        val lastColumn: Boolean,
        val noHorizontalBands: Boolean,
        val noVerticalBands: Boolean,
    )

    private fun readLook(look: Element?): TableLook {
        val bits = look?.w("val")?.toIntOrNull(16) ?: 0
        fun flag(name: String, mask: Int): Boolean =
            look?.w(name)?.let { it == "1" || it.equals("true", true) } ?: (bits and mask != 0)
        return TableLook(
            firstRow = flag("firstRow", 0x0020),
            lastRow = flag("lastRow", 0x0040),
            firstColumn = flag("firstColumn", 0x0080),
            lastColumn = flag("lastColumn", 0x0100),
            noHorizontalBands = flag("noHBand", 0x0200),
            noVerticalBands = flag("noVBand", 0x0400),
        )
    }

    private fun tableRows(table: Element): List<Element> = buildList {
        fun walk(elements: List<Element>) {
            for (element in elements) {
                when {
                    element.isIn(NS_W, "tr") -> add(element)
                    element.isIn(NS_W, "sdt") -> element.wChild("sdtContent")?.let { walk(it.childElements) }
                    element.isIn(NS_W, "customXml") -> walk(element.childElements)
                }
            }
        }
        walk(table.childElements)
    }

    private fun rowCells(row: Element): List<Element> = buildList {
        fun walk(elements: List<Element>) {
            for (element in elements) {
                when {
                    element.isIn(NS_W, "tc") -> add(element)
                    element.isIn(NS_W, "sdt") -> element.wChild("sdtContent")?.let { walk(it.childElements) }
                    element.isIn(NS_W, "customXml") -> walk(element.childElements)
                }
            }
        }
        walk(row.childElements)
    }

    private fun readTable(table: Element, context: ReadContext): WordTable? {
        if (depth >= MAX_DEPTH) return null
        depth++
        try {
            val properties = table.wChild("tblPr")
            val chain = styles.tableChain(properties?.wVal("tblStyle"))
            val fromStyle = chain.fold(null as TableOverlay?) { base, style -> style.table?.over(base) ?: base }
            val direct = properties?.let(styles::readTable)
            val overlay = direct?.over(fromStyle) ?: fromStyle ?: TableOverlay()
            val look = readLook(properties?.wChild("tblLook"))
            val grid = table.wChild("tblGrid")?.children(NS_W, "gridCol")
                ?.map { parseTwips(it.w("w")) ?: 0 }?.toIntArray() ?: IntArray(0)

            val rowElements = tableRows(table)
            if (rowElements.isEmpty()) return null
            val columnCount = maxOf(
                grid.size,
                rowElements.maxOf { row -> rowCells(row).sumOf { it.wChild("tcPr")?.wVal("gridSpan")?.toIntOrNull() ?: 1 } },
            )

            val shared = SharedTableFormat(chain, look, overlay, columnCount, rowElements.size, context)
            val rows = rowElements.mapIndexed { index, row -> readRow(row, index, shared) }

            val width = properties?.wChild("tblW")
            val percent = width?.takeIf { it.w("type")?.lowercase() == "pct" }?.w("w")
                ?.trim()?.removeSuffix("%")?.toFloatOrNull()
                ?.let { if (width.w("w")?.contains('%') == true) it / 100f else it / 5000f }
            return WordTable(
                gridTwips = grid,
                rows = rows,
                borders = overlay.borders,
                indentTwips = overlay.indent ?: 0,
                align = overlay.align ?: WordAlign.Left,
                widthTwips = width?.let(::tableWidthTwips)?.takeIf { it > 0 },
                widthPercent = percent?.takeIf { it > 0f },
            )
        } finally {
            depth--
        }
    }

    private inner class SharedTableFormat(
        val chain: List<WordStyle>,
        val look: TableLook,
        val overlay: TableOverlay,
        val columnCount: Int,
        val rowCount: Int,
        val context: ReadContext,
    ) {
        /** Style-level formatting for every cell, including the "whole table" conditional. */
        val wholeParagraph: ParagraphOverlay? = chain.fold(null as ParagraphOverlay?) { base, style ->
            val own = style.paragraph?.over(base) ?: base
            style.conditional["wholeTable"]?.paragraph?.over(own) ?: own
        }
        val wholeRun: RunOverlay? = chain.fold(null as RunOverlay?) { base, style ->
            val own = style.run?.over(base) ?: base
            style.conditional["wholeTable"]?.run?.over(own) ?: own
        }
        val wholeCell: CellOverlay? = chain.fold(null as CellOverlay?) { base, style ->
            val own = style.cell?.over(base) ?: base
            style.conditional["wholeTable"]?.cell?.over(own) ?: own
        }

        fun conditional(type: String): ConditionalFormat? =
            chain.asReversed().firstNotNullOfOrNull { it.conditional[type] }

        /** The conditional formats a cell falls under, weakest first. */
        fun applicable(row: Int, column: Int, span: Int): List<ConditionalFormat> {
            val types = ArrayList<String>()
            val firstRow = look.firstRow && row == 0
            val lastRow = look.lastRow && row == rowCount - 1
            val firstColumn = look.firstColumn && column == 0
            val lastColumn = look.lastColumn && column + span == columnCount
            if (!look.noVerticalBands) {
                val index = (column - if (look.firstColumn) 1 else 0).coerceAtLeast(0) / (overlay.columnBandSize ?: 1).coerceAtLeast(1)
                types += if (index % 2 == 0) "band1Vert" else "band2Vert"
            }
            if (!look.noHorizontalBands) {
                val index = (row - if (look.firstRow) 1 else 0).coerceAtLeast(0) / (overlay.rowBandSize ?: 1).coerceAtLeast(1)
                types += if (index % 2 == 0) "band1Horz" else "band2Horz"
            }
            if (firstColumn) types += "firstCol"
            if (lastColumn) types += "lastCol"
            if (firstRow) types += "firstRow"
            if (lastRow) types += "lastRow"
            if (firstRow && firstColumn) types += "nwCell"
            if (firstRow && lastColumn) types += "neCell"
            if (lastRow && firstColumn) types += "swCell"
            if (lastRow && lastColumn) types += "seCell"
            return types.mapNotNull(::conditional)
        }
    }

    private fun readRow(row: Element, index: Int, shared: SharedTableFormat): WordRow {
        val properties = row.wChild("trPr")
        val height = properties?.wChild("trHeight")
        val gridBefore = properties?.wVal("gridBefore")?.toIntOrNull() ?: 0
        var column = gridBefore
        val cells = rowCells(row).map { cell ->
            val span = cell.wChild("tcPr")?.wVal("gridSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            readCell(cell, index, column, span, shared).also { column += span }
        }
        return WordRow(
            cells = cells,
            heightTwips = parseTwips(height?.w("val"))?.takeIf { it > 0 },
            exactHeight = height?.w("hRule")?.lowercase() == "exact",
            isHeader = properties?.wChild("tblHeader")?.isOn() == true,
            cantSplit = properties?.wChild("cantSplit")?.isOn() == true,
            gridBefore = gridBefore,
            gridAfter = properties?.wVal("gridAfter")?.toIntOrNull() ?: 0,
        )
    }

    private fun readCell(cell: Element, row: Int, column: Int, span: Int, shared: SharedTableFormat): WordCell {
        val properties = cell.wChild("tcPr")
        val formats = shared.applicable(row, column, span)
        var paragraph = shared.wholeParagraph
        var run = shared.wholeRun
        var format = shared.wholeCell
        formats.forEach { conditional ->
            paragraph = conditional.paragraph?.over(paragraph) ?: paragraph
            run = conditional.run?.over(run) ?: run
            format = conditional.cell?.over(format) ?: format
        }
        val direct = properties?.let(styles::readCell)
        val merged = direct?.over(format) ?: format ?: CellOverlay()
        val margins = properties?.wChild("tcMar")
        fun margin(name: String, alternate: String, fromTable: Int?): Int =
            (margins?.wChild(name) ?: margins?.wChild(alternate))?.let(::tableWidthTwips) ?: fromTable ?: 0
        val merge = properties?.wChild("vMerge")
        val context = ReadContext(shared.context.relationships, paragraph, run)
        return WordCell(
            blocks = readBlocks(cell, context),
            gridSpan = span,
            verticalMerge = when {
                merge == null -> WordVerticalMerge.None
                merge.w("val")?.lowercase() == "restart" -> WordVerticalMerge.Restart
                else -> WordVerticalMerge.Continue
            },
            widthTwips = properties?.wChild("tcW")?.let(::tableWidthTwips),
            shadingArgb = merged.shading?.takeIf { it != AUTO_COLOR },
            borders = merged.borders,
            marginLeftTwips = margin("left", "start", shared.overlay.cellMarginLeft ?: DEFAULT_CELL_MARGIN),
            marginTopTwips = margin("top", "top", shared.overlay.cellMarginTop),
            marginRightTwips = margin("right", "end", shared.overlay.cellMarginRight ?: DEFAULT_CELL_MARGIN),
            marginBottomTwips = margin("bottom", "bottom", shared.overlay.cellMarginBottom),
            align = merged.align ?: WordCellAlign.Top,
        )
    }

    // --- drawings ------------------------------------------------------------------------------

    /** The branch of an `mc:AlternateContent` this reader understands, or null when neither is. */
    private fun chosenAlternate(alternate: Element): Element? {
        val choice = alternate.children(NS_MC, "Choice").firstOrNull { choice ->
            choice.plain("Requires").orEmpty().trim().split(Regex("\\s+")).all { prefix ->
                prefix.isNotEmpty() && choice.lookupNamespaceURI(prefix) in UNDERSTOOD_NAMESPACES
            }
        }
        return choice ?: alternate.child(NS_MC, "Fallback")
    }

    private fun readDrawing(drawing: Element, context: ReadContext): WordBoxed? {
        val holder = drawing.child(NS_WP, "inline") ?: drawing.child(NS_WP, "anchor") ?: return null
        val anchor = if (holder.localName == "anchor") readAnchor(holder) else null
        val extent = holder.child(NS_WP, "extent")
        val width = emuToTwips(extent?.emu("cx") ?: 0)
        val height = emuToTwips(extent?.emu("cy") ?: 0)
        val data = holder.child(NS_A, "graphic")?.child(NS_A, "graphicData")
            ?: return WordPlaceholder(width, height, anchor)
        return readGraphic(data, width, height, anchor, context)
    }

    private fun readGraphic(
        data: Element,
        width: Int,
        height: Int,
        anchor: WordAnchor?,
        context: ReadContext,
    ): WordBoxed {
        data.child(NS_PIC, "pic")?.let { return readPicture(it, width, height, anchor, context) }
        data.child(NS_WPS, "wsp")?.let { return readShape(it, width, height, anchor, context) }
        data.child(NS_WPG, "wgp")?.let { return readGroup(it, NS_WPG, width, height, anchor, context) }
        data.child(NS_WPC, "wpc")?.let { return readGroup(it, NS_WPC, width, height, anchor, context) }
        return WordPlaceholder(width, height, anchor)
    }

    private fun readPicture(
        picture: Element,
        width: Int,
        height: Int,
        anchor: WordAnchor?,
        context: ReadContext,
    ): WordPicture {
        val fill = picture.child(NS_PIC, "blipFill")
        val embed = fill?.child(NS_A, "blip")?.attr(NS_R, "embed")
        val crop = fill?.child(NS_A, "srcRect")?.let { rect ->
            fun side(name: String) = (rect.plain(name)?.toFloatOrNull() ?: 0f) / 100_000f
            WordCrop(side("l"), side("t"), side("r"), side("b")).takeIf {
                it.left != 0f || it.top != 0f || it.right != 0f || it.bottom != 0f
            }
        }
        return WordPicture(
            part = embed?.let { context.relationships[it] }?.takeUnless { it.external }?.target,
            widthTwips = width,
            heightTwips = height,
            crop = crop,
            anchor = anchor,
        )
    }

    private fun readShape(
        shape: Element,
        width: Int,
        height: Int,
        anchor: WordAnchor?,
        context: ReadContext,
    ): WordShape {
        val properties = shape.child(NS_WPS, "spPr")
        val style = shape.child(NS_WPS, "style")
        val body = shape.child(NS_WPS, "bodyPr")
        fun inset(name: String, fallback: Long): Int = emuToTwips(body?.emu(name) ?: fallback)
        val text = shape.child(NS_WPS, "txbx")?.wChild("txbxContent")
        return WordShape(
            blocks = text?.let { readBlocks(it, ReadContext(context.relationships)) }.orEmpty(),
            widthTwips = width,
            heightTwips = height,
            fillArgb = shapeFill(properties, style),
            line = shapeLine(properties, style),
            form = when (properties?.child(NS_A, "prstGeom")?.plain("prst")) {
                "roundRect", "round1Rect", "round2SameRect", "round2DiagRect" -> WordShapeForm.RoundedRectangle
                "ellipse" -> WordShapeForm.Ellipse
                else -> WordShapeForm.Rectangle
            },
            insetLeftTwips = inset("lIns", DEFAULT_SIDE_INSET),
            insetTopTwips = inset("tIns", DEFAULT_TOP_INSET),
            insetRightTwips = inset("rIns", DEFAULT_SIDE_INSET),
            insetBottomTwips = inset("bIns", DEFAULT_TOP_INSET),
            anchor = anchor,
        )
    }

    private fun shapeFill(properties: Element?, style: Element?): Int? {
        if (properties?.child(NS_A, "noFill") != null) return null
        properties?.child(NS_A, "solidFill")?.let { return theme.drawingColor(it.childElements.firstOrNull()) }
        properties?.child(NS_A, "gradFill")?.let { gradient ->
            val first = gradient.child(NS_A, "gsLst")?.children(NS_A, "gs")?.firstOrNull()
            return theme.drawingColor(first?.childElements?.firstOrNull())
        }
        val reference = style?.child(NS_A, "fillRef") ?: return null
        if ((reference.plain("idx")?.toIntOrNull() ?: 0) == 0) return null
        return theme.drawingColor(reference.childElements.firstOrNull())
    }

    private fun shapeLine(properties: Element?, style: Element?): WordBorder? {
        val line = properties?.child(NS_A, "ln")
        if (line?.child(NS_A, "noFill") != null) return null
        val explicit = line?.child(NS_A, "solidFill")?.childElements?.firstOrNull()
        val reference = style?.child(NS_A, "lnRef")?.takeIf { (it.plain("idx")?.toIntOrNull() ?: 0) > 0 }
        val color = theme.drawingColor(explicit ?: reference?.childElements?.firstOrNull()) ?: return null
        val widthEmu = line?.emu("w") ?: DEFAULT_LINE_EMU
        return WordBorder(WordBorderStyle.Single, (widthEmu * 8 / 12700).toInt().coerceAtLeast(1), color)
    }

    private fun readGroup(
        group: Element,
        namespace: String,
        width: Int,
        height: Int,
        anchor: WordAnchor?,
        context: ReadContext,
    ): WordGroup {
        if (depth >= MAX_DEPTH) return WordGroup(emptyList(), width, height, anchor)
        depth++
        try {
            val transform = group.child(namespace, "grpSpPr")?.child(NS_A, "xfrm")
            val childOffset = transform?.child(NS_A, "chOff")
            val childExtent = transform?.child(NS_A, "chExt")
            val originX = childOffset?.emu("x") ?: 0L
            val originY = childOffset?.emu("y") ?: 0L
            val scaleX = childExtent?.emu("cx")?.takeIf { it > 0 }?.let { width / emuToTwips(it).toFloat() } ?: 1f
            val scaleY = childExtent?.emu("cy")?.takeIf { it > 0 }?.let { height / emuToTwips(it).toFloat() } ?: 1f
            val children = group.childElements.mapNotNull { child ->
                val own = when {
                    child.isIn(NS_WPS, "wsp") -> child.child(NS_WPS, "spPr")
                    child.isIn(NS_PIC, "pic") -> child.child(NS_PIC, "spPr")
                    child.isIn(NS_WPG, "grpSp") -> child.child(NS_WPG, "grpSpPr")
                    else -> null
                }?.child(NS_A, "xfrm") ?: return@mapNotNull null
                val offset = own.child(NS_A, "off")
                val extent = own.child(NS_A, "ext")
                val childWidth = (emuToTwips(extent?.emu("cx") ?: 0) * scaleX).roundToInt()
                val childHeight = (emuToTwips(extent?.emu("cy") ?: 0) * scaleY).roundToInt()
                val boxed: WordBoxed = when {
                    child.isIn(NS_WPS, "wsp") -> readShape(child, childWidth, childHeight, null, context)
                    child.isIn(NS_PIC, "pic") -> readPicture(child, childWidth, childHeight, null, context)
                    else -> readGroup(child, NS_WPG, childWidth, childHeight, null, context)
                }
                WordGroupChild(
                    boxed = boxed,
                    xTwips = (emuToTwips((offset?.emu("x") ?: 0L) - originX) * scaleX).roundToInt(),
                    yTwips = (emuToTwips((offset?.emu("y") ?: 0L) - originY) * scaleY).roundToInt(),
                )
            }
            return WordGroup(children, width, height, anchor)
        } finally {
            depth--
        }
    }

    private fun readAnchor(anchor: Element): WordAnchor {
        val horizontal = anchor.child(NS_WP, "positionH")
        val vertical = anchor.child(NS_WP, "positionV")
        fun offset(position: Element?): Int? =
            position?.child(NS_WP, "posOffset")?.textContent?.trim()?.toLongOrNull()?.let(::emuToTwips)
        fun align(position: Element?): String? = position?.child(NS_WP, "align")?.textContent?.trim()
        return WordAnchor(
            behindText = anchor.plain("behindDoc") == "1",
            wrap = when {
                anchor.child(NS_WP, "wrapNone") != null -> WordWrap.None
                anchor.child(NS_WP, "wrapTopAndBottom") != null -> WordWrap.TopAndBottom
                else -> WordWrap.Square
            },
            horizontalFrom = horizontal?.plain("relativeFrom") ?: "column",
            horizontalOffsetTwips = offset(horizontal),
            horizontalAlign = align(horizontal),
            verticalFrom = vertical?.plain("relativeFrom") ?: "paragraph",
            verticalOffsetTwips = offset(vertical),
            verticalAlign = align(vertical),
        )
    }

    /** The legacy `w:pict` / `w:object` form: a picture or a text box in VML. */
    private fun readVml(holder: Element, context: ReadContext): WordBoxed? {
        val shape = VML_SHAPES.firstNotNullOfOrNull { holder.descendants(NS_V, it).firstOrNull() }
        val (width, height) = vmlSize(shape?.plain("style"))
        val image = holder.descendants(NS_V, "imagedata").firstOrNull()?.attr(NS_R, "id")
        if (image != null) {
            val part = context.relationships[image]?.takeUnless { it.external }?.target
            return WordPicture(part, width, height, crop = null, anchor = null)
        }
        val text = holder.descendants(NS_V, "textbox").firstOrNull()?.wChild("txbxContent") ?: return null
        return WordShape(
            blocks = readBlocks(text, ReadContext(context.relationships)),
            widthTwips = width,
            heightTwips = height,
            fillArgb = null,
            line = null,
            form = WordShapeForm.Rectangle,
            insetLeftTwips = emuToTwips(DEFAULT_SIDE_INSET),
            insetTopTwips = emuToTwips(DEFAULT_TOP_INSET),
            insetRightTwips = emuToTwips(DEFAULT_SIDE_INSET),
            insetBottomTwips = emuToTwips(DEFAULT_TOP_INSET),
            anchor = null,
        )
    }

    private fun vmlSize(style: String?): Pair<Int, Int> {
        var width = 0
        var height = 0
        style.orEmpty().split(';').forEach { declaration ->
            val name = declaration.substringBefore(':').trim().lowercase()
            val value = declaration.substringAfter(':', "")
            when (name) {
                "width" -> width = parseTwips(value.trim()) ?: width
                "height" -> height = parseTwips(value.trim()) ?: height
            }
        }
        return width.takeIf { it > 0 }.let { it ?: DEFAULT_VML_WIDTH } to height.takeIf { it > 0 }.let { it ?: DEFAULT_VML_HEIGHT }
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_COLUMNS = 8

        /** Word's built-in cell padding, 0.075 inch, for a table style that does not state one. */
        const val DEFAULT_CELL_MARGIN = 108

        const val DEFAULT_SIDE_INSET = 91_440L
        const val DEFAULT_TOP_INSET = 45_720L
        const val DEFAULT_LINE_EMU = 9_525L
        const val DEFAULT_VML_WIDTH = 4320
        const val DEFAULT_VML_HEIGHT = 2880

        val VML_SHAPES = listOf("shape", "rect", "roundrect", "oval", "image")

        val UNDERSTOOD_NAMESPACES = setOf(
            NS_WPS,
            NS_WPG,
            NS_WPC,
            "http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing",
            "http://schemas.microsoft.com/office/word/2010/wordml",
            "http://schemas.microsoft.com/office/word/2012/wordml",
            "http://schemas.microsoft.com/office/drawing/2010/main",
        )
    }
}

// --- symbol fonts -------------------------------------------------------------------------------

internal fun isSymbolFont(font: String?): Boolean = when (font?.lowercase()) {
    "symbol", "wingdings", "wingdings 2", "wingdings 3", "webdings", "zapf dingbats", "zapfdingbats" -> true
    else -> false
}

/**
 * [text] as Unicode if it is set in a symbol font, else null.
 *
 * Symbol and Wingdings are not Unicode fonts: their letters are other pictures, and Word stores
 * either the raw byte or the byte moved into the private-use area. Android has no such fonts, so left
 * alone a `p` set in Symbol reads `p` instead of `π`.
 */
internal fun decodeSymbolFont(font: String?, text: String): String? {
    if (!isSymbolFont(font)) return null
    val symbol = font.equals("Symbol", ignoreCase = true)
    return buildString {
        text.forEach { char ->
            val code = char.code and 0xFF
            when {
                symbol && code in 0x20..0x7E -> append(SYMBOL_LOW[code - 0x20])
                symbol && code in 0xA0..0xFE -> append(SYMBOL_HIGH[code - 0xA0])
                symbol -> append(char)
                else -> append(WINGDINGS[code] ?: '•')
            }
        }
    }
}

/** Adobe's Symbol encoding, 0x20 to 0x7E. */
internal const val SYMBOL_LOW: String =
    " !∀#∃%&∋()∗+,−./0123456789:;<=>?≅ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ[∴]⊥_‾αβχδεφγηιϕκλμνοπθρστυϖωξψζ{|}∼"

/** Adobe's Symbol encoding, 0xA0 to 0xFE. */
internal const val SYMBOL_HIGH: String =
    "€ϒ′≤⁄∞ƒ♣♦♥♠↔←↑→↓°±″≥×∝∂•÷≠≡≈…|⎯↵ℵℑℜ℘⊗⊕∅∩∪⊃⊇⊄⊂⊆∈∉∠∇®©™∏√⋅¬∧∨⇔⇐⇑⇒⇓◊〈®©™∑⎛⎜⎝⎡⎢⎣⎧⎨⎩⎪ 〉∫⌠⎮⌡⎞⎟⎠⎤⎥⎦⎫⎬⎭"

/** The Wingdings a document is likely to use: its bullets, checks and arrows. */
private val WINGDINGS: Map<Int, Char> = mapOf(
    0x4A to '☺', 0x4C to '☹', 0x6C to '●', 0x6D to '❍', 0x6E to '■', 0x6F to '□', 0x71 to '❑', 0x72 to '❒',
    0x75 to '◆', 0x76 to '❖', 0x77 to '⬥', 0x78 to '⌧', 0x9F to '•', 0xA0 to '▪', 0xA7 to '▪', 0xA8 to '◻',
    0xD8 to '➢', 0xDF to '←', 0xE0 to '→', 0xE1 to '↑', 0xE2 to '↓', 0xE8 to '➔', 0xF0 to '⇨',
    0xFB to '✗', 0xFC to '✓', 0xFD to '☒', 0xFE to '☑',
)
