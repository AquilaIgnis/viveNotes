package com.vivenotes.fileimport.odt

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.WordAlign
import com.vivenotes.fileimport.docx.WordAnchor
import com.vivenotes.fileimport.docx.WordBlock
import com.vivenotes.fileimport.docx.WordBorders
import com.vivenotes.fileimport.docx.WordBoxed
import com.vivenotes.fileimport.docx.WordBreakBefore
import com.vivenotes.fileimport.docx.WordCell
import com.vivenotes.fileimport.docx.WordCellAlign
import com.vivenotes.fileimport.docx.WordDocument
import com.vivenotes.fileimport.docx.WordEquation
import com.vivenotes.fileimport.docx.WordField
import com.vivenotes.fileimport.docx.WordFieldKind
import com.vivenotes.fileimport.docx.WordGroup
import com.vivenotes.fileimport.docx.WordGroupChild
import com.vivenotes.fileimport.docx.WordHeaderSet
import com.vivenotes.fileimport.docx.WordInline
import com.vivenotes.fileimport.docx.WordLineBreak
import com.vivenotes.fileimport.docx.WordListLabel
import com.vivenotes.fileimport.docx.WordNoteRef
import com.vivenotes.fileimport.docx.WordPageGeometry
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordParagraphStyle
import com.vivenotes.fileimport.docx.WordPicture
import com.vivenotes.fileimport.docx.WordRenderedBreak
import com.vivenotes.fileimport.docx.WordRow
import com.vivenotes.fileimport.docx.WordRunStyle
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
import com.vivenotes.fileimport.docx.NS_XML
import com.vivenotes.fileimport.docx.attr
import com.vivenotes.fileimport.docx.child
import com.vivenotes.fileimport.docx.childElements
import com.vivenotes.fileimport.docx.decodeSymbolFont
import com.vivenotes.fileimport.docx.formatNumber
import com.vivenotes.fileimport.docx.isIn
import com.vivenotes.fileimport.docx.isSymbolFont
import com.vivenotes.fileimport.docx.parseTwips
import com.vivenotes.model.normalizedLinkUrl
import org.w3c.dom.Element
import org.w3c.dom.Text
import kotlin.math.max

/** LibreOffice writes a table inside a text box in its own namespace, otherwise unchanged. */
private fun Element.isTablePart(name: String): Boolean = localName == name && (namespaceURI == NS_TABLE || namespaceURI == NS_LOEXT)

/** A document as read, and each font's generic family for choosing its stand-in. */
internal class OdtRead(val document: WordDocument, val fontFamilies: Map<String, String>)

/**
 * Reads `content.xml` and `styles.xml` into the same model a `.docx` becomes, so that pagination,
 * page pictures and objects need to know nothing about where a document came from.
 */
internal class OdtReader(private val pkg: OdtPackage) {
    private val content: Element = pkg.xml("content.xml") ?: throw ImportException(ImportFailure.Unreadable)
    private val stylesRoot: Element? = pkg.xml("styles.xml")
    private val shared = OdtSharedStyles(stylesRoot, content)
    private val bodyStyles = OdtStyleScope(shared, content)
    private val pageStyles = OdtStyleScope(shared, stylesRoot)

    private val masterRoot = stylesRoot?.child(NS_OFFICE, "master-styles")
    private val masters: Map<String, Element> = masterRoot?.childElements.orEmpty()
        .filter { it.isIn(NS_STYLE, "master-page") }
        .associateBy { it.attr(NS_STYLE, "name").orEmpty() }
    private val pageLayouts: Map<String, Element> = stylesRoot?.child(NS_OFFICE, "automatic-styles")?.childElements.orEmpty()
        .filter { it.isIn(NS_STYLE, "page-layout") }
        .associateBy { it.attr(NS_STYLE, "name").orEmpty() }

    private val footnotes = LinkedHashMap<Int, List<WordBlock>>()
    private val endnotes = LinkedHashMap<Int, List<WordBlock>>()
    private var notes = 0

    /** Numbering that carries on from one list to the next, by the list's id or its style. */
    private val counters = HashMap<String, ListCounters>()

    private class ListCounters {
        val values = IntArray(MAX_LIST_LEVELS)
        val started = BooleanArray(MAX_LIST_LEVELS)
    }

    fun read(): OdtRead {
        val body = content.child(NS_OFFICE, "body")?.child(NS_OFFICE, "text")
            ?: throw ImportException(ImportFailure.NotWord)
        val sections = readSections(body)
        val evenHeaders = masters.values.any { it.child(NS_STYLE, "header-left") != null || it.child(NS_STYLE, "footer-left") != null }
        return OdtRead(
            WordDocument(sections, footnotes, endnotes, WordSettings(evenAndOddHeaders = evenHeaders)),
            shared.fonts.values.filter { it.generic != null }.associate { it.family.lowercase() to it.generic.orEmpty() },
        )
    }

    private fun readSections(body: Element): List<WordSection> {
        val sections = ArrayList<WordSection>()
        var master: String? = null
        var blocks = ArrayList<WordBlock>()
        val reader = BlockReader(bodyStyles) { next ->
            // A paragraph that starts another page style starts another section of the document.
            if (blocks.isNotEmpty()) {
                sections += sectionOf(master, blocks, sections.isEmpty())
                blocks = ArrayList()
            }
            master = next
        }
        reader.read(body) { blocks }
        reader.finish(blocks)
        sections += sectionOf(master, blocks, sections.isEmpty())
        return sections
    }

    private fun sectionOf(masterName: String?, blocks: List<WordBlock>, first: Boolean): WordSection {
        val master = masterName?.let(masters::get) ?: masters["Standard"] ?: masters.values.firstOrNull()
        val layout = master?.attr(NS_STYLE, "page-layout-name")?.let(pageLayouts::get)
        val properties = layout?.child(NS_STYLE, "page-layout-properties")
        fun length(name: String, fallback: Int): Int = parseTwips(properties?.attr(NS_FO, name)) ?: fallback
        fun reserved(style: String): Int {
            val band = layout?.child(NS_STYLE, style)?.child(NS_STYLE, "header-footer-properties") ?: return 0
            return (parseTwips(band.attr(NS_FO, "min-height")) ?: parseTwips(band.attr(NS_SVG, "height")) ?: 0) +
                (parseTwips(band.attr(NS_FO, if (style == "header-style") "margin-bottom" else "margin-top")) ?: 0)
        }
        val top = length("margin-top", DEFAULT_MARGIN)
        val bottom = length("margin-bottom", DEFAULT_MARGIN)
        val hasHeader = master?.child(NS_STYLE, "header") != null
        val hasFooter = master?.child(NS_STYLE, "footer") != null
        val columns = properties?.child(NS_STYLE, "columns")
        return WordSection(
            page = WordPageGeometry(
                widthTwips = length("page-width", LETTER_WIDTH),
                heightTwips = length("page-height", LETTER_HEIGHT),
                // The page margin is where a header starts; the text starts below that header.
                topTwips = top + if (hasHeader) reserved("header-style") else 0,
                bottomTwips = bottom + if (hasFooter) reserved("footer-style") else 0,
                leftTwips = length("margin-left", DEFAULT_MARGIN),
                rightTwips = length("margin-right", DEFAULT_MARGIN),
                headerTwips = top,
                footerTwips = bottom,
            ),
            columns = columns?.attr(NS_FO, "column-count")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            columnGapTwips = parseTwips(columns?.attr(NS_FO, "column-gap")) ?: 0,
            start = if (first) WordSectionStart.Continuous else WordSectionStart.NextPage,
            titlePage = master?.child(NS_STYLE, "header-first") != null || master?.child(NS_STYLE, "footer-first") != null,
            pageNumberStart = null,
            pageNumberFormat = null,
            headers = headerSet(master, "header"),
            footers = headerSet(master, "footer"),
            blocks = blocks,
        )
    }

    private fun headerSet(master: Element?, name: String): WordHeaderSet {
        fun band(element: String): List<WordBlock>? = master?.child(NS_STYLE, element)?.let { readBlocksOf(it, pageStyles) }
        return WordHeaderSet(default = band(name), first = band("$name-first"), even = band("$name-left"))
    }

    private fun readBlocksOf(parent: Element, styles: OdtStyleScope): List<WordBlock> {
        val out = ArrayList<WordBlock>()
        val reader = BlockReader(styles, null)
        reader.read(parent) { out }
        reader.finish(out)
        return out
    }

    /** One run of paragraphs and tables, with what has to carry over from one block to the next. */
    private inner class BlockReader(val styles: OdtStyleScope, val onMaster: ((String) -> Unit)?) {
        private var softBreak = false
        private var carriedBreak = WordBreakBefore.None

        /** Frames anchored to the page, which sit between paragraphs and need one to hang off. */
        private val waiting = ArrayList<WordInline>()

        fun read(parent: Element, out: () -> MutableList<WordBlock>) = parent.childElements.forEach { block(it, null, out) }

        fun finish(out: MutableList<WordBlock>) {
            if (waiting.isEmpty()) return
            out += WordParagraph(WordParagraphStyle(), WordRunStyle(), waiting.toList(), null)
            waiting.clear()
        }

        private fun block(element: Element, item: ListItem?, out: () -> MutableList<WordBlock>) {
            when {
                element.isIn(NS_TEXT, "p") || element.isIn(NS_TEXT, "h") -> paragraph(element, item, out)
                element.isIn(NS_TEXT, "list") -> list(element, item?.level?.plus(1) ?: 0, item?.counters, item?.listStyle, out)
                element.isIn(NS_TEXT, "soft-page-break") -> softBreak = true
                element.isIn(NS_TEXT, "section") -> element.childElements.forEach { block(it, item, out) }
                element.namespaceURI == NS_TEXT && element.localName in INDEXES ->
                    element.child(NS_TEXT, "index-body")?.childElements?.forEach { block(it, item, out) }
                element.isIn(NS_TEXT, "index-title") -> element.childElements.forEach { block(it, item, out) }
                element.isTablePart("table") -> table(element, out)
                element.namespaceURI == NS_DRAW -> Inlines(WordRunStyle()).drawn(element, WordRunStyle())?.let { waiting += it }
            }
        }

        private fun paragraph(element: Element, item: ListItem?, out: () -> MutableList<WordBlock>) {
            val look = styles.paragraph(element.attr(NS_TEXT, "style-name"))
            look.masterPage?.let { onMaster?.invoke(it) }
            var style = look.style
            if (element.isIn(NS_TEXT, "h")) {
                val level = (element.attr(NS_TEXT, "outline-level")?.toIntOrNull() ?: 1) - 1
                style = style.copy(outlineLevel = level.coerceAtLeast(0))
            }
            var label: WordListLabel? = null
            if (item != null) {
                item.levelStyle?.let { levelStyle ->
                    if (!look.ownIndent) indentOf(levelStyle)?.let { (left, first) -> style = style.copy(leftTwips = left, firstLineTwips = first) }
                }
                if (item.numbered) {
                    label = labelOf(item, look.run)
                    item.numbered = false
                }
            }
            val inlines = Inlines(look.run)
            if (softBreak) inlines.out += WordRenderedBreak
            softBreak = false
            inlines.out += waiting
            waiting.clear()
            inlines.walk(element, look.run, null)
            out() += WordParagraph(
                style = style,
                markStyle = look.run,
                inlines = inlines.finished(),
                label = label,
                breakBefore = if (carriedBreak != WordBreakBefore.None) carriedBreak else look.breakBefore,
            )
            carriedBreak = look.breakAfter
        }

        private fun list(
            element: Element,
            level: Int,
            parent: ListCounters?,
            parentStyle: String?,
            out: () -> MutableList<WordBlock>,
        ) {
            val styleName = element.attr(NS_TEXT, "style-name") ?: parentStyle
            val state = parent ?: stateOf(element, styleName)
            val listStyle = styles.listStyle(styleName)
            val capped = level.coerceAtMost(MAX_LIST_LEVELS - 1)
            val levelStyle = listStyle?.childElements?.firstOrNull { it.attr(NS_TEXT, "level")?.toIntOrNull() == capped + 1 }
            for (child in element.childElements) {
                val isItem = child.isIn(NS_TEXT, "list-item")
                if (!isItem && !child.isIn(NS_TEXT, "list-header")) continue
                val item = ListItem(capped, state, styleName, levelStyle, isItem, child.attr(NS_TEXT, "start-value")?.toIntOrNull())
                child.childElements.forEach { block(it, item, out) }
            }
        }

        private fun stateOf(list: Element, styleName: String?): ListCounters {
            val continued = list.attr(NS_TEXT, "continue-list")
                ?: styleName.takeIf { list.attr(NS_TEXT, "continue-numbering") == "true" }
            val state = continued?.let(counters::get) ?: ListCounters()
            list.attr(NS_XML, "id")?.let { counters[it] = state }
            styleName?.let { counters[it] = state }
            return state
        }

        private fun labelOf(item: ListItem, base: WordRunStyle): WordListLabel? {
            val levelStyle = item.levelStyle ?: return null
            val run = styles.applyText(styles.run(levelStyle.attr(NS_TEXT, "style-name"), base), levelStyle.child(NS_STYLE, "text-properties"))
            val alignment = levelStyle.child(NS_STYLE, "list-level-properties")?.child(NS_STYLE, "list-level-label-alignment")
            val follow = alignment?.attr(NS_TEXT, "label-followed-by") ?: "listtab"
            fun label(text: String, bullet: Boolean): WordListLabel = WordListLabel(
                text = decodeSymbolFont(run.font, text) ?: text,
                style = if (isSymbolFont(run.font)) run.copy(font = null) else run,
                followedByTab = follow == "listtab",
                followedBySpace = follow == "space",
                bullet = bullet,
                level = item.level,
            )
            return when (levelStyle.localName) {
                "list-level-style-bullet" -> label(bulletOf(levelStyle.attr(NS_TEXT, "bullet-char")), bullet = true)
                "list-level-style-image" -> label("•", bullet = true)
                "list-level-style-number" -> {
                    val format = levelStyle.attr(NS_STYLE, "num-format").orEmpty()
                    val value = item.next(levelStyle.attr(NS_TEXT, "start-value")?.toIntOrNull() ?: 1)
                    if (format.isEmpty()) return null
                    val shown = (levelStyle.attr(NS_TEXT, "display-levels")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val levels = styles.listStyle(item.listStyle)?.childElements.orEmpty()
                    val numbers = (max(0, item.level - shown + 1)..item.level).joinToString(".") { index ->
                        // Each level shown is in its own format: "2.a)", not "b.a)".
                        val own = levels.firstOrNull { it.attr(NS_TEXT, "level")?.toIntOrNull() == index + 1 }?.attr(NS_STYLE, "num-format")
                        formatNumber(if (index == item.level) value else item.counters.values[index], numberFormat(own ?: format))
                    }
                    label(levelStyle.attr(NS_STYLE, "num-prefix").orEmpty() + numbers + levelStyle.attr(NS_STYLE, "num-suffix").orEmpty(), bullet = false)
                }
                else -> null
            }
        }

        private fun bulletOf(char: String?): String = when {
            char.isNullOrEmpty() -> "•"
            char[0] in ''..'' -> decodeSymbolFont("Symbol", char) ?: "•"
            else -> char
        }

        /** The list level's own margin and hanging indent, in whichever of ODF's two ways it says so. */
        private fun indentOf(levelStyle: Element): Pair<Int, Int>? {
            val properties = levelStyle.child(NS_STYLE, "list-level-properties") ?: return null
            properties.child(NS_STYLE, "list-level-label-alignment")?.let { alignment ->
                return (parseTwips(alignment.attr(NS_FO, "margin-left")) ?: 0) to (parseTwips(alignment.attr(NS_FO, "text-indent")) ?: 0)
            }
            val before = parseTwips(properties.attr(NS_TEXT, "space-before")) ?: 0
            val width = parseTwips(properties.attr(NS_TEXT, "min-label-width")) ?: 0
            return (before + width) to -width
        }

        private fun table(element: Element, out: () -> MutableList<WordBlock>) {
            val name = element.attr(NS_TABLE, "style-name")
            styles.masterPage("table", name)?.let { onMaster?.invoke(it) }
            out() += readTable(element, styles)
        }

        /** The text and objects inside one paragraph, with ODF's rule for what a space is. */
        inner class Inlines(private val base: WordRunStyle) {
            val out = ArrayList<WordInline>()

            /** ODF collapses runs of spaces across span boundaries, and drops those a paragraph opens with. */
            private var afterSpace = true

            fun finished(): List<WordInline> {
                val merged = ArrayList<WordInline>(out.size)
                for (inline in out) {
                    val last = merged.lastOrNull()
                    if (inline is WordText && last is WordText && last.style == inline.style && last.link == inline.link) {
                        merged[merged.lastIndex] = WordText(last.text + inline.text, inline.style, inline.link)
                    } else {
                        merged += inline
                    }
                }
                return merged
            }

            fun walk(parent: Element, style: WordRunStyle, link: String?) {
                var node = parent.firstChild
                while (node != null) {
                    when (node) {
                        is Text -> text(node.data, style, link)
                        is Element -> element(node, style, link)
                    }
                    node = node.nextSibling
                }
            }

            private fun text(raw: String, style: WordRunStyle, link: String?) {
                val kept = StringBuilder(raw.length)
                for (char in raw) {
                    if (char == ' ' || char == '\t' || char == '\n' || char == '\r') {
                        if (!afterSpace) kept.append(' ')
                        afterSpace = true
                    } else {
                        kept.append(char)
                        afterSpace = false
                    }
                }
                if (kept.isNotEmpty()) out += WordText(kept.toString(), style, link)
            }

            private fun element(element: Element, style: WordRunStyle, link: String?) {
                when (element.namespaceURI) {
                    NS_DRAW -> drawn(element, style)?.let { out += it }
                    NS_TEXT -> when (element.localName) {
                        "span" -> walk(element, styles.run(element.attr(NS_TEXT, "style-name"), style), link)
                        "a" -> walk(
                            element,
                            styles.run(element.attr(NS_TEXT, "style-name"), style),
                            element.attr(NS_XLINK, "href")?.let(::normalizedLinkUrl) ?: link,
                        )
                        "s" -> {
                            val count = (element.attr(NS_TEXT, "c")?.toIntOrNull() ?: 1).coerceIn(1, MAX_SPACES)
                            out += WordText(" ".repeat(count), style, link)
                            afterSpace = false
                        }
                        "tab" -> {
                            out += WordTab(style)
                            afterSpace = false
                        }
                        "line-break" -> {
                            out += WordLineBreak(style)
                            afterSpace = true
                        }
                        "soft-page-break" -> if (out.isEmpty()) out += WordRenderedBreak
                        "note" -> note(element, style)
                        "page-number" -> out += WordField(WordFieldKind.PageNumber, style)
                        "page-count" -> out += WordField(WordFieldKind.PageCount, style)
                        "ruby" -> element.child(NS_TEXT, "ruby-base")?.let { walk(it, style, link) }
                        in NOT_SHOWN -> Unit
                        // Whatever else is a field, and its text is what it last showed.
                        else -> walk(element, style, link)
                    }
                }
            }

            private fun note(element: Element, style: WordRunStyle) {
                val body = element.child(NS_TEXT, "note-body") ?: return
                val id = ++notes
                val endnote = element.attr(NS_TEXT, "note-class") == "endnote"
                (if (endnote) endnotes else footnotes)[id] = readBlocksOf(body, styles)
                out += WordNoteRef(id, endnote, style)
            }

            fun drawn(element: Element, style: WordRunStyle): WordInline? = when (element.localName) {
                "frame" -> frame(element, style)
                "custom-shape", "rect", "ellipse" -> shape(element, element)
                "g" -> group(element)
                else -> null
            }

            private fun frame(frame: Element, style: WordRunStyle): WordInline? {
                frame.child(NS_DRAW, "object")?.let { formula(it, style) }?.let { return it }
                frame.child(NS_DRAW, "text-box")?.let { return shape(frame, it) }
                val image = frame.child(NS_DRAW, "image") ?: return null
                val href = image.attr(NS_XLINK, "href").orEmpty()
                return WordPicture(
                    part = href.removePrefix("./").takeIf { it.isNotEmpty() && "://" !in it },
                    widthTwips = widthOf(frame),
                    heightTwips = heightOf(frame),
                    crop = null,
                    anchor = anchorOf(frame),
                )
            }

            private fun formula(object_: Element, style: WordRunStyle): WordInline? {
                val href = object_.attr(NS_XLINK, "href")?.removePrefix("./")?.trimEnd('/') ?: return null
                val math = pkg.xml("$href/content.xml")?.takeIf { it.localName == "math" } ?: return null
                return WordEquation(mathMlToLatex(math), mathMlPlainText(math), style, display = false)
            }

            private fun widthOf(frame: Element): Int =
                parseTwips(frame.attr(NS_SVG, "width")) ?: parseTwips(frame.attr(NS_FO, "min-width")) ?: 0

            private fun heightOf(frame: Element): Int =
                parseTwips(frame.attr(NS_SVG, "height")) ?: parseTwips(frame.attr(NS_FO, "min-height")) ?: 0

            private fun graphic(element: Element, namespace: String, attribute: String): String? =
                styles.property("graphic", element.attr(NS_DRAW, "style-name"), "graphic-properties", namespace, attribute)

            /** A drawn rectangle or ellipse, with whatever text was set inside it. */
            private fun shape(frame: Element, holder: Element): WordShape {
                val blocks = ArrayList<WordBlock>()
                val reader = BlockReader(styles, null)
                holder.childElements.filter { it.namespaceURI == NS_TEXT || it.isTablePart(it.localName) }
                    .forEach { reader.block(it, null) { blocks } }
                val form = when {
                    frame.localName == "ellipse" -> WordShapeForm.Ellipse
                    frame.child(NS_DRAW, "enhanced-geometry")?.attr(NS_DRAW, "type") == "ellipse" -> WordShapeForm.Ellipse
                    frame.attr(NS_DRAW, "corner-radius") != null -> WordShapeForm.RoundedRectangle
                    else -> WordShapeForm.Rectangle
                }
                val filled = graphic(frame, NS_DRAW, "fill") != "none" && graphic(frame, NS_DRAW, "fill-color") != null
                val stroked = graphic(frame, NS_DRAW, "stroke")?.let { it != "none" } == true
                val fill = if (filled) graphic(frame, NS_DRAW, "fill-color")?.let(::colorOf) else null
                val padding = parseTwips(graphic(frame, NS_FO, "padding")) ?: 0
                return WordShape(
                    blocks = blocks,
                    widthTwips = widthOf(frame),
                    heightTwips = heightOf(frame),
                    fillArgb = fill ?: graphic(frame, NS_FO, "background-color")?.let(::colorOf),
                    line = if (stroked) {
                        borderOf(
                            listOfNotNull(graphic(frame, NS_SVG, "stroke-width") ?: "0.75pt", "solid", graphic(frame, NS_SVG, "stroke-color") ?: "#000000")
                                .joinToString(" "),
                        )
                    } else {
                        borderOf(graphic(frame, NS_FO, "border"))
                    },
                    form = form,
                    insetLeftTwips = parseTwips(graphic(frame, NS_FO, "padding-left")) ?: padding,
                    insetTopTwips = parseTwips(graphic(frame, NS_FO, "padding-top")) ?: padding,
                    insetRightTwips = parseTwips(graphic(frame, NS_FO, "padding-right")) ?: padding,
                    insetBottomTwips = parseTwips(graphic(frame, NS_FO, "padding-bottom")) ?: padding,
                    anchor = anchorOf(frame),
                )
            }

            private fun group(group: Element): WordGroup? {
                val parts = group.childElements.mapNotNull { child ->
                    val drawnChild = drawn(child, WordRunStyle()) as? WordBoxed ?: return@mapNotNull null
                    Triple(drawnChild, parseTwips(child.attr(NS_SVG, "x")) ?: 0, parseTwips(child.attr(NS_SVG, "y")) ?: 0)
                }
                if (parts.isEmpty()) return null
                val left = parts.minOf { it.second }
                val top = parts.minOf { it.third }
                return WordGroup(
                    children = parts.map { (boxed, x, y) -> WordGroupChild(boxed, x - left, y - top) },
                    widthTwips = parts.maxOf { (boxed, x, _) -> x + boxed.widthTwips } - left,
                    heightTwips = parts.maxOf { (boxed, _, y) -> y + boxed.heightTwips } - top,
                    anchor = anchorOf(group),
                )
            }

            /** Null for one set in the text like a character, which is what `as-char` means. */
            private fun anchorOf(frame: Element): WordAnchor? {
                val type = frame.attr(NS_TEXT, "anchor-type") ?: "paragraph"
                if (type == "as-char") return null
                val wrap = when (graphic(frame, NS_STYLE, "wrap")) {
                    "none" -> WordWrap.TopAndBottom
                    "run-through" -> WordWrap.None
                    else -> WordWrap.Square
                }
                val onPage = type == "page"
                val horizontalRelative = graphic(frame, NS_STYLE, "horizontal-rel") ?: if (onPage) "page" else "paragraph"
                val verticalRelative = graphic(frame, NS_STYLE, "vertical-rel") ?: if (onPage) "page" else "paragraph"
                return WordAnchor(
                    behindText = graphic(frame, NS_STYLE, "run-through") == "background",
                    wrap = wrap,
                    horizontalFrom = anchorBase(horizontalRelative),
                    horizontalOffsetTwips = parseTwips(frame.attr(NS_SVG, "x")),
                    horizontalAlign = when (graphic(frame, NS_STYLE, "horizontal-pos")) {
                        "center" -> "center"
                        "left", "inside" -> "left"
                        "right", "outside" -> "right"
                        else -> null
                    },
                    verticalFrom = anchorBase(verticalRelative),
                    verticalOffsetTwips = parseTwips(frame.attr(NS_SVG, "y")),
                    verticalAlign = when (graphic(frame, NS_STYLE, "vertical-pos")) {
                        "top" -> "top"
                        "middle" -> "center"
                        "bottom" -> "bottom"
                        else -> null
                    },
                )
            }

            private fun anchorBase(relative: String): String = when (relative) {
                "page", "page-start-margin", "page-end-margin" -> "page"
                "page-content" -> "margin"
                "char" -> "character"
                "line", "baseline", "text" -> "line"
                else -> "paragraph"
            }
        }

        private fun readTable(table: Element, styles: OdtStyleScope): WordTable {
            val styleName = table.attr(NS_TABLE, "style-name")
            val columns = ArrayList<Int>()
            fun walkColumns(parent: Element) {
                for (child in parent.childElements) {
                    when {
                        child.isTablePart("table-column") -> {
                            val width = parseTwips(styles.property("table-column", child.attr(NS_TABLE, "style-name"), "table-column-properties", NS_STYLE, "column-width")) ?: 0
                            repeat((child.attr(NS_TABLE, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MAX_TABLE_COLUMNS - columns.size)) { columns += width }
                        }
                        child.localName in COLUMN_GROUPS && child.isTablePart(child.localName) -> walkColumns(child)
                    }
                }
            }
            walkColumns(table)

            val rows = ArrayList<WordRow>()
            val spanning = Array(max(columns.size, 1)) { IntArray(2) }
            fun addRow(row: Element, header: Boolean) {
                val built = readRow(row, styles, columns, spanning, header)
                repeat((row.attr(NS_TABLE, "number-rows-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ROW_REPEATS)) { rows += built }
            }
            fun walkRows(parent: Element, header: Boolean) {
                for (child in parent.childElements) {
                    when {
                        child.isTablePart("table-row") -> addRow(child, header)
                        child.isTablePart("table-header-rows") -> walkRows(child, true)
                        child.isTablePart("table-rows") || child.isTablePart("table-row-group") -> walkRows(child, header)
                    }
                }
            }
            walkRows(table, false)

            fun tableAttr(namespace: String, attribute: String): String? =
                styles.property("table", styleName, "table-properties", namespace, attribute)
            return WordTable(
                gridTwips = columns.toIntArray(),
                rows = rows,
                borders = null,
                indentTwips = parseTwips(tableAttr(NS_FO, "margin-left")) ?: 0,
                align = when (tableAttr(NS_TABLE, "align")) {
                    "center" -> WordAlign.Center
                    "right" -> WordAlign.Right
                    else -> WordAlign.Left
                },
                widthTwips = parseTwips(tableAttr(NS_STYLE, "width")),
                widthPercent = tableAttr(NS_STYLE, "rel-width")?.removeSuffix("%")?.toFloatOrNull(),
            )
        }

        private fun readRow(row: Element, styles: OdtStyleScope, columns: List<Int>, spanning: Array<IntArray>, header: Boolean): WordRow {
            val styleName = row.attr(NS_TABLE, "style-name")
            fun rowAttr(attribute: String): String? = styles.property("table-row", styleName, "table-row-properties", NS_STYLE, attribute)
            val fixed = parseTwips(rowAttr("row-height"))
            val minimum = parseTwips(rowAttr("min-row-height"))
            val cells = ArrayList<WordCell>()
            var column = 0
            for (child in row.childElements) {
                val covered = child.isTablePart("covered-table-cell")
                if (!covered && !child.isTablePart("table-cell")) continue
                val repeats = (child.attr(NS_TABLE, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                repeat(repeats.coerceAtMost(MAX_TABLE_COLUMNS)) {
                    if (covered) {
                        // A covered cell under a spanning one continues it. One beside it is part of it already.
                        val origin = spanning.getOrNull(column)
                        if (origin != null && origin[ROWS_LEFT] > 0) {
                            origin[ROWS_LEFT]--
                            cells += readCell(child, styles, columns, column, origin[ACROSS], WordVerticalMerge.Continue)
                        }
                        column++
                    } else {
                        val across = (child.attr(NS_TABLE, "number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                        val down = (child.attr(NS_TABLE, "number-rows-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                        spanning.getOrNull(column)?.let {
                            it[ROWS_LEFT] = down - 1
                            it[ACROSS] = across
                        }
                        val merge = if (down > 1) WordVerticalMerge.Restart else WordVerticalMerge.None
                        cells += readCell(child, styles, columns, column, across, merge)
                        column += across
                    }
                }
            }
            return WordRow(
                cells = cells,
                heightTwips = fixed ?: minimum,
                exactHeight = fixed != null,
                isHeader = header,
                cantSplit = styles.property("table-row", styleName, "table-row-properties", NS_FO, "keep-together") == "always",
                gridBefore = 0,
                gridAfter = 0,
            )
        }

        private fun readCell(cell: Element, styles: OdtStyleScope, columns: List<Int>, column: Int, span: Int, merge: WordVerticalMerge): WordCell {
            val styleName = cell.attr(NS_TABLE, "style-name")
            fun foAttr(attribute: String): String? = styles.property("table-cell", styleName, "table-cell-properties", NS_FO, attribute)
            val padding = parseTwips(foAttr("padding")) ?: DEFAULT_CELL_PADDING
            val sides = WordBorders(
                top = borderOf(foAttr("border-top") ?: foAttr("border")),
                left = borderOf(foAttr("border-left") ?: foAttr("border")),
                bottom = borderOf(foAttr("border-bottom") ?: foAttr("border")),
                right = borderOf(foAttr("border-right") ?: foAttr("border")),
            )
            return WordCell(
                blocks = if (merge == WordVerticalMerge.Continue) emptyList() else readBlocksOf(cell, styles),
                gridSpan = span,
                verticalMerge = merge,
                widthTwips = (column until column + span).sumOf { columns.getOrElse(it) { 0 } }.takeIf { it > 0 },
                shadingArgb = foAttr("background-color")?.let(::colorOf),
                borders = sides,
                marginLeftTwips = parseTwips(foAttr("padding-left")) ?: padding,
                marginTopTwips = parseTwips(foAttr("padding-top")) ?: padding,
                marginRightTwips = parseTwips(foAttr("padding-right")) ?: padding,
                marginBottomTwips = parseTwips(foAttr("padding-bottom")) ?: padding,
                align = when (styles.property("table-cell", styleName, "table-cell-properties", NS_STYLE, "vertical-align")) {
                    "middle" -> WordCellAlign.Center
                    "bottom" -> WordCellAlign.Bottom
                    else -> WordCellAlign.Top
                },
            )
        }
    }

    /** What the paragraphs of one list item share. [numbered] is spent by the first of them. */
    private class ListItem(
        val level: Int,
        val counters: ListCounters,
        val listStyle: String?,
        val levelStyle: Element?,
        var numbered: Boolean,
        private val startValue: Int?,
    ) {
        /** Counts this item at its level and starts the levels below it over. */
        fun next(styleStart: Int): Int {
            val values = counters.values
            values[level] = startValue ?: if (counters.started[level]) values[level] + 1 else styleStart
            counters.started[level] = true
            for (deeper in level + 1 until MAX_LIST_LEVELS) counters.started[deeper] = false
            return values[level]
        }
    }

    private companion object {
        const val MAX_LIST_LEVELS = 10
        const val ROWS_LEFT = 0
        const val ACROSS = 1
        const val MAX_SPACES = 4096
        const val MAX_TABLE_COLUMNS = 1024
        const val MAX_ROW_REPEATS = 1024
        const val LETTER_WIDTH = 12240
        const val LETTER_HEIGHT = 15840
        const val DEFAULT_MARGIN = 1440
        const val DEFAULT_CELL_PADDING = 0

        val INDEXES = setOf("table-of-content", "illustration-index", "table-index", "object-index", "user-index", "alphabetical-index", "bibliography")
        val COLUMN_GROUPS = setOf("table-columns", "table-header-columns", "table-column-group")
        val NOT_SHOWN = setOf(
            "bookmark", "bookmark-start", "bookmark-end", "reference-mark", "reference-mark-start", "reference-mark-end",
            "toc-mark", "toc-mark-start", "toc-mark-end", "alphabetical-index-mark", "alphabetical-index-mark-start",
            "alphabetical-index-mark-end", "user-index-mark", "user-index-mark-start", "user-index-mark-end",
            "change", "change-start", "change-end", "note-citation", "hidden-text", "hidden-paragraph", "number",
        )
    }

    private fun numberFormat(format: String): String = when (format) {
        "a" -> "lowerLetter"
        "A" -> "upperLetter"
        "i" -> "lowerRoman"
        "I" -> "upperRoman"
        else -> "decimal"
    }
}
