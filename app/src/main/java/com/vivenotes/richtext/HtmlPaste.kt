package com.vivenotes.richtext

import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Run
import com.vivenotes.model.newId
import com.vivenotes.model.normalizedLinkUrl
import kotlin.math.roundToInt

/**
 * Text another app copied as HTML — a web page, Google Docs, an office suite — as the blocks it
 * looks like there, for [OutlineEditText]'s Paste. Headings, bulleted and numbered lists
 * (nested ones as indents), to-dos, quotes and code keep their paragraph styles and alignment;
 * bold, italic, underline, strikethrough, sub- and superscript, links, inline code, colours and
 * highlights keep their marks, and Wikipedia's and KaTeX's formulas come as their LaTeX.
 *
 * Each block of the page starts a line, and blocks the page sets apart — by margins, its own or a
 * browser's default for paragraphs, headings, lists, quotes and code — are a blank line apart,
 * while Google Docs' marginless paragraphs and a list's items follow one another as on the page.
 * What belongs to the page rather than the text is left out: its fonts and sizes —
 * the text takes [textMarks], the font and size at the caret — and the greys, blacks and whites
 * of its text and background, so pasted text reads in the page's own ink, dark or light.
 *
 * Returns null when there is no text in [html], and the plain text should be pasted instead.
 */
fun pastedHtmlBlocks(html: String, textMarks: Set<Mark> = emptySet()): List<Block>? {
    val base = textMarks.filterTo(mutableSetOf()) { it is Mark.FontFamily || it is Mark.FontSize }
    return HtmlPaste(base).read(html).takeIf { blocks -> blocks.any { it.text.isNotBlank() } }
}

private class HtmlPaste(private val textMarks: Set<Mark>) {
    private val open = mutableListOf<Element>()
    private val lines = mutableListOf<Block>()
    private var line: LineStyle? = null
    private var runs = mutableListOf<Run>()
    /** The page's space before whatever comes next, in CSS pixels: block margins collapse to the larger. */
    private var gap = 0f
    private var space: Set<Mark>? = null
    private var skipNewline = false
    private var todoLine: Boolean? = null
    private var base: String? = null

    fun read(source: String): List<Block> {
        // Windows' "HTML Format" opens with a header of offsets, if a reader passes it on.
        val html = if (source.startsWith("Version:")) source.substring(source.indexOf('<').coerceAtLeast(0)) else source
        var index = 0
        val length = html.length
        while (index < length) {
            val tagStart = html.indexOf('<', index)
            if (tagStart < 0) {
                text(decodeEntities(html.substring(index)))
                break
            }
            if (tagStart > index) text(decodeEntities(html.substring(index, tagStart)))
            index = when {
                html.startsWith("<!--", tagStart) -> html.indexOf("-->", tagStart + 4).let { if (it < 0) length else it + 3 }
                html.startsWith("<![CDATA[", tagStart) -> html.indexOf("]]>", tagStart).let { if (it < 0) length else it + 3 }
                html.startsWith("<!", tagStart) || html.startsWith("<?", tagStart) ->
                    html.indexOf('>', tagStart).let { if (it < 0) length else it + 1 }
                tagStart + 1 < length && html[tagStart + 1] == '/' && html.getOrNull(tagStart + 2)?.isLetter() == true -> {
                    val end = html.indexOf('>', tagStart).let { if (it < 0) length else it }
                    endTag(html.substring(tagStart + 2, end).trim().substringBefore(' ').lowercase())
                    end + 1
                }
                tagStart + 1 < length && html[tagStart + 1].isLetter() -> startTag(html, tagStart)
                else -> {
                    text("<")
                    tagStart + 1
                }
            }
        }
        endLine()
        return lines.dropWhile { it.runs.isEmpty() }.dropLastWhile { it.runs.isEmpty() }
    }

    /** Reads the tag at [at] and returns where the text after it starts. */
    private fun startTag(html: String, at: Int): Int {
        var index = at + 1
        val nameStart = index
        while (index < html.length && !html[index].isWhitespace() && html[index] != '>' && html[index] != '/') index++
        val name = html.substring(nameStart, index).lowercase()
        val attributes = mutableMapOf<String, String>()
        var selfClosing = false
        while (index < html.length) {
            while (index < html.length && html[index].isWhitespace()) index++
            if (index >= html.length) break
            if (html[index] == '>') { index++; break }
            if (html[index] == '/') {
                selfClosing = true
                index++
                continue
            }
            val attributeStart = index
            while (index < html.length && !html[index].isWhitespace() && html[index] !in "=>/") index++
            val attribute = html.substring(attributeStart, index).lowercase()
            while (index < html.length && html[index].isWhitespace()) index++
            var value = ""
            if (index < html.length && html[index] == '=') {
                index++
                while (index < html.length && html[index].isWhitespace()) index++
                if (index < html.length && (html[index] == '"' || html[index] == '\'')) {
                    val quote = html[index]
                    val close = html.indexOf(quote, index + 1).let { if (it < 0) html.length else it }
                    value = html.substring(index + 1, close)
                    index = close + 1
                } else {
                    val valueStart = index
                    while (index < html.length && !html[index].isWhitespace() && html[index] != '>') index++
                    value = html.substring(valueStart, index)
                }
            }
            if (attribute.isNotEmpty()) attributes.putIfAbsent(attribute, decodeEntities(value))
            if (attributeStart == index) index++
        }
        if (name in RawText) {
            // Their content is not markup, and none of it is text on the page.
            val close = html.indexOf("</$name", index, ignoreCase = true)
            return if (close < 0) html.length else html.indexOf('>', close).let { if (it < 0) html.length else it + 1 }
        }
        element(name, attributes)
        if (selfClosing && name !in Void) endTag(name)
        return index
    }

    private fun element(name: String, attributes: Map<String, String>) {
        when (name) {
            "br" -> { lineBreak(); return }
            "hr" -> { boundary(HR_MARGIN); return }
            "base" -> { base = attributes["href"]; return }
            "input" -> {
                if (attributes["type"]?.lowercase() == "checkbox") checkbox(attributes.containsKey("checked"))
                return
            }
            "img" -> {
                // Wikipedia draws a formula as a picture whose alternative text is its LaTeX.
                attributes["alt"]?.trim()?.takeIf { it.startsWith("{\\displaystyle") }?.let { text("\$$it\$", preserveText = true) }
                return
            }
        }
        if (name in Void) return
        closeImplied(name)
        val style = parseStyle(attributes["style"])
        val (top, bottom) = marginsOf(name, style,
            inItem = open.any { it.name == "li" || it.name == "td" || it.name == "th" },
            inList = open.any { it.name in Lists || it.name == "li" })
        val element = Element(name, Inline.of(name, attributes, style, base), name in Blocks, top, bottom,
            alignOf(name, attributes, style))
        if (name == "li") {
            element.ariaLevel = attributes["aria-level"]?.toIntOrNull()
            if (attributes["role"] == "checkbox") element.todo = attributes["aria-checked"] == "true"
        }
        if (name == "td" || name == "th") open.lastOrNull { it.name == "tr" }?.let { row ->
            if (row.cells++ > 0) separator('\t')
        }
        if (element.block) boundary(element.top)
        open += element
        if (name == "pre") skipNewline = true
        if (name == "annotation" && attributes["encoding"]?.lowercase() == "application/x-tex") element.tex = StringBuilder()
    }

    private fun endTag(name: String) {
        val at = open.indexOfLast { it.name == name }
        if (at < 0) return
        while (open.size > at) close(open.removeAt(open.lastIndex))
    }

    private fun close(element: Element) {
        element.tex?.let { tex ->
            // The formula a KaTeX or MathJax page shows, in the source it gives screen readers.
            open.lastOrNull { it.name == "math" }?.formula = tex.toString().trim()
        }
        if (element.name == "math") element.formula?.takeIf { it.isNotEmpty() }?.let { text("\$$it\$", preserveText = true) }
        if (element.block) boundary(element.bottom)
    }

    /** The end tags HTML leaves out: a new list item ends the last one, a block ends a paragraph. */
    private fun closeImplied(name: String) {
        val ends: Set<String> = when (name) {
            "li" -> setOf("li")
            "dt", "dd" -> setOf("dt", "dd")
            "tr" -> setOf("tr")
            "td", "th" -> setOf("td", "th")
            "p" -> setOf("p")
            else -> if (name in Blocks) setOf("p") else return
        }
        val scope = when (name) {
            "li" -> setOf("ul", "ol", "menu")
            "tr" -> setOf("table", "tbody", "thead", "tfoot")
            "td", "th" -> setOf("tr", "table", "tbody", "thead", "tfoot")
            else -> Blocks - "p"
        }
        val implied = open.indexOfLast { it.name in ends || it.name in scope }
        if (implied >= 0 && open[implied].name in ends) {
            while (open.size > implied) close(open.removeAt(open.lastIndex))
        }
    }

    private fun checkbox(checked: Boolean) {
        val item = open.lastOrNull { it.name == "li" }
        if (item != null && !item.started) item.todo = checked
        else if (line == null) todoLine = checked
    }

    // Lines

    private fun text(source: String, preserveText: Boolean = false) {
        val raw = source.replace("\uFEFF", "")
        if (raw.isEmpty() || open.any { it.inline.hidden }) return
        open.lastOrNull { it.tex != null }?.let { it.tex!!.append(raw); return }
        if (open.any { it.name == "math" }) return
        val marks = marks()
        if (preserveText) {
            emit(raw, marks)
            return
        }
        if (open.lastOrNull { it.inline.preserve != null }?.inline?.preserve == true) {
            var piece = raw.replace("\r\n", "\n").replace('\r', '\n')
            if (skipNewline && piece.startsWith('\n')) piece = piece.drop(1)
            skipNewline = false
            piece.split('\n').forEachIndexed { index, part ->
                if (index > 0) lineBreak()
                if (part.isNotEmpty()) emit(part, marks)
            }
            return
        }
        skipNewline = false
        var index = 0
        while (index < raw.length) {
            if (raw[index].isHtmlSpace()) {
                if (line != null && space == null) space = marks
                while (index < raw.length && raw[index].isHtmlSpace()) index++
                continue
            }
            val wordStart = index
            while (index < raw.length && !raw[index].isHtmlSpace()) index++
            emit(raw.substring(wordStart, index).replace('\u00A0', ' '), marks)
        }
    }

    /** A cell's tab: text the page shows between cells, kept as it is. */
    private fun separator(char: Char) {
        if (line == null) return
        space = null
        runs += Run(char.toString(), marks())
    }

    private fun emit(piece: String, marks: Set<Mark>) {
        if (line == null) beginLine()
        space?.let { runs += Run(" ", it) }
        space = null
        runs += Run(piece, marks)
    }

    private fun beginLine() {
        spaceOut()
        line = lineStyle(consumeItem = true)
    }

    /** The blank line a page's margin leaves between two blocks, where it is more than a sliver. */
    private fun spaceOut() {
        if (lines.isNotEmpty() && gap > BLANK_LINE_GAP) lines += blankLine()
        gap = 0f
    }

    private fun endLine() {
        val style = line ?: return
        lines += Block(id = newId(), type = style.type, indent = style.indent, align = style.align,
            runs = merge(runs), checked = style.checked)
        runs = mutableListOf()
        line = null
        space = null
    }

    /** A `<br>`: the end of the line, or an empty line where none was open. */
    private fun lineBreak() {
        if (open.any { it.inline.hidden }) return
        if (open.any { it.name == "td" || it.name == "th" }) {
            if (line != null && space == null) space = marks()
            return
        }
        if (line != null) {
            endLine()
            return
        }
        spaceOut()
        val style = lineStyle(consumeItem = false)
        lines += Block(id = newId(), type = if (style.type.isListType()) BlockType.Paragraph else style.type,
            indent = style.indent, align = style.align)
    }

    /** Where a block starts or ends, with [margin] pixels of the page's space on that side of it. */
    private fun boundary(margin: Float) {
        if (open.any { it.name == "td" || it.name == "th" }) {
            if (line != null && space == null) space = marks()
            return
        }
        endLine()
        gap = maxOf(gap, margin)
    }

    /** A blank line, which carries a quote's stripe on between two of its paragraphs. */
    private fun blankLine(): Block = Block(id = newId(), type =
        if (lines.lastOrNull()?.type == BlockType.Quote && open.any { it.name == "blockquote" }) BlockType.Quote
        else BlockType.Paragraph)

    private fun lineStyle(consumeItem: Boolean): LineStyle {
        val align = open.lastOrNull { it.align != null }?.align ?: Align.Start
        val pending = todoLine.also { if (consumeItem) todoLine = null }
        val typed = open.indexOfLast { it.name == "li" || it.name in Headings || it.name == "blockquote" || it.name == "pre" }
        val item = open.lastOrNull { it.name == "li" }
        val itemIndent = item?.let { itemIndentOf(it) }
        val continuation = itemIndent?.let { (it + 1).coerceAtMost(MAX_INDENT) } ?: 0
        if (typed < 0) {
            return if (pending != null) LineStyle(BlockType.Todo, 0, align, pending) else LineStyle(BlockType.Paragraph, 0, align)
        }
        val element = open[typed]
        return when (element.name) {
            "li" -> if (element.started) LineStyle(BlockType.Paragraph, continuation, align) else {
                if (consumeItem) element.started = true
                val list = open.subList(0, typed).lastOrNull { it.name == "ul" || it.name == "ol" || it.name == "menu" }
                val checked = element.todo ?: pending
                val type = when {
                    checked != null -> BlockType.Todo
                    list?.name == "ol" -> BlockType.Numbered
                    else -> BlockType.Bullet
                }
                LineStyle(type, itemIndent ?: 0, align, checked.takeIf { type == BlockType.Todo })
            }
            "h1" -> LineStyle(BlockType.Heading1, if (item != null) continuation else 0, align)
            "h2" -> LineStyle(BlockType.Heading2, if (item != null) continuation else 0, align)
            in Headings -> LineStyle(BlockType.Heading3, if (item != null) continuation else 0, align)
            "blockquote" -> LineStyle(BlockType.Quote, if (item != null) continuation else 0, align)
            else -> LineStyle(BlockType.Code, if (item != null) continuation else 0, align)
        }
    }

    /** Google Docs flattens nested lists and says each item's level; pages nest the lists. */
    private fun itemIndentOf(item: Element): Int {
        item.ariaLevel?.let { return (it - 1).coerceIn(0, MAX_INDENT) }
        val at = open.indexOf(item)
        val lists = open.subList(0, at).count { it.name == "ul" || it.name == "ol" || it.name == "menu" }
        return (lists - 1).coerceIn(0, MAX_INDENT)
    }

    private fun marks(): Set<Mark> {
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var script = Script.Baseline
        var link: String? = null
        var code = false
        var color: Int? = null
        var highlight: Int? = null
        for (element in open) {
            val inline = element.inline
            inline.bold?.let { bold = it }
            inline.italic?.let { italic = it }
            if (inline.underline) underline = true
            if (inline.strike) strike = true
            inline.script?.let { script = it }
            inline.link?.let { link = it }
            inline.code?.let { code = it }
            inline.color?.let { color = it.takeIf { argb -> argb != NO_COLOR } }
            inline.highlight?.let { highlight = it.takeIf { argb -> argb != NO_COLOR } }
        }
        val heading = open.any { it.name in Headings }
        return buildSet {
            if (code) {
                addAll(textMarks.filter { it !is Mark.FontFamily })
                add(Mark.FontFamily("monospace"))
            } else addAll(textMarks)
            // A heading is bold by being one; marking it so would outlast the heading.
            if (bold && !heading) add(Mark.Bold)
            if (italic) add(Mark.Italic)
            // A link is drawn underlined in the link colour whatever the page did with it.
            if (underline && link == null) add(Mark.Underline)
            if (strike) add(Mark.Strikethrough)
            when (script) {
                Script.Sub -> add(Mark.Subscript)
                Script.Super -> add(Mark.Superscript)
                Script.Baseline -> Unit
            }
            link?.let { add(Mark.Link(it)) }
            if (link == null) color?.let { add(Mark.TextColor(it)) }
            highlight?.let { add(Mark.Highlight(it)) }
        }
    }
}

private data class LineStyle(val type: BlockType, val indent: Int, val align: Align, val checked: Boolean? = null)

private class Element(
    val name: String,
    val inline: Inline,
    /** Whether the element is a block of the page, starting and ending a line. */
    val block: Boolean,
    /** The block's own margins above and below it, in CSS pixels. */
    val top: Float,
    val bottom: Float,
    val align: Align?,
) {
    /** A list item whose own line has been made; later lines in it are its paragraphs. */
    var started = false
    var todo: Boolean? = null
    var ariaLevel: Int? = null
    var cells = 0
    var tex: StringBuilder? = null
    var formula: String? = null
}

private enum class Script { Sub, Super, Baseline }

/** What an element does to the text inside it; null leaves what its parents said. */
private class Inline(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val script: Script? = null,
    val link: String? = null,
    val code: Boolean? = null,
    val color: Int? = null,
    val highlight: Int? = null,
    val preserve: Boolean? = null,
    val hidden: Boolean = false,
) {
    companion object {
        fun of(name: String, attributes: Map<String, String>, style: Map<String, String>, base: String?): Inline {
            val weight = style["font-weight"]
            val decoration = (style["text-decoration-line"] ?: style["text-decoration"]).orEmpty()
            val whiteSpace = style["white-space"]
            val family = style["font-family"]?.lowercase()
            val vertical = style["vertical-align"]?.lowercase()
            return Inline(
                bold = when {
                    weight != null -> weight.isBoldWeight()
                    name == "b" || name == "strong" -> true
                    else -> null
                },
                italic = when (style["font-style"]?.lowercase()) {
                    "italic", "oblique" -> true
                    "normal" -> false
                    else -> if (name in setOf("i", "em", "cite", "var", "dfn")) true else null
                },
                underline = name == "u" || name == "ins" || "underline" in decoration,
                strike = name == "s" || name == "strike" || name == "del" || "line-through" in decoration,
                script = when {
                    vertical == "super" || vertical == "sup" -> Script.Super
                    vertical == "sub" -> Script.Sub
                    vertical == "baseline" -> Script.Baseline
                    name == "sup" -> Script.Super
                    name == "sub" -> Script.Sub
                    else -> null
                },
                link = if (name == "a") attributes["href"]?.let { resolveLink(it, base) } else null,
                code = when {
                    name in setOf("code", "kbd", "samp", "tt", "pre") -> true
                    family != null && Monospaced.any { it in family } -> true
                    else -> null
                },
                color = (style["color"] ?: attributes["color"].takeIf { name == "font" })?.let(::cssColor)?.let { argb ->
                    if (argb.isChromatic()) argb else NO_COLOR
                },
                highlight = when (name) {
                    "mark" -> HIGHLIGHT_ALPHA or 0xFFEB3B
                    else -> (style["background-color"] ?: style["background"])?.let(::backgroundColor)
                },
                preserve = when {
                    name == "pre" -> true
                    whiteSpace == null -> null
                    else -> whiteSpace.startsWith("pre") || whiteSpace == "break-spaces"
                },
                hidden = name in Hidden || style["display"]?.lowercase() == "none" ||
                    attributes.containsKey("hidden") || attributes["aria-hidden"] == "true",
            )
        }
    }
}

private const val NO_COLOR = 0
private const val HIGHLIGHT_ALPHA = 0x66000000
private const val MAX_INDENT = 8

/** Browsers' default margins, in CSS pixels at their 16 px text: a paragraph's 1em, about a heading's. */
private const val BLOCK_MARGIN = 16f
private const val HEADING_MARGIN = 20f
private const val HR_MARGIN = 8f

/** More space than this between two blocks is a blank line; Google Docs' 6pt under a heading is not. */
private const val BLANK_LINE_GAP = 8f

private val Headings = setOf("h1", "h2", "h3", "h4", "h5", "h6")

private val Lists = setOf("ul", "ol", "menu", "dl")

/** Elements that are lines of their own, and end an open paragraph when they start. */
private val Blocks = setOf(
    "address", "article", "aside", "blockquote", "center", "dd", "details", "dialog", "div", "dl", "dt",
    "fieldset", "figcaption", "figure", "footer", "form", "header", "hgroup", "li", "main", "menu", "nav",
    "ol", "p", "pre", "section", "summary", "table", "tr", "ul", "caption", "legend", "body", "html",
) + Headings

private val Void = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
    "source", "track", "wbr")

private val RawText = setOf("script", "style", "textarea", "title", "xmp", "noembed", "noframes", "noscript",
    "template", "iframe")

private val Hidden = setOf("head", "svg", "select", "option", "datalist", "object", "video", "audio", "canvas", "button")

private val Monospaced = listOf("monospace", "mono", "courier", "consolas", "menlo")

/**
 * A block's margins above and below, in CSS pixels: its `style`'s, or a browser's defaults for
 * paragraphs, headings, top-level lists, quotes and code. A paragraph is a line of a list item or
 * a cell, and nested lists sit in their item, without space of their own.
 */
private fun marginsOf(name: String, style: Map<String, String>, inItem: Boolean, inList: Boolean): Pair<Float, Float> {
    if (name !in Blocks || (name == "p" && inItem)) return 0f to 0f
    val default = when {
        name in Headings -> HEADING_MARGIN
        name in Lists -> if (inList) 0f else BLOCK_MARGIN
        name in setOf("p", "blockquote", "pre", "figure", "dl") -> BLOCK_MARGIN
        else -> 0f
    }
    val all = style["margin"]?.trim()?.split(Regex("\\s+"))
    val top = style["margin-top"] ?: style["margin-block-start"] ?: all?.getOrNull(0)
    val bottom = style["margin-bottom"] ?: style["margin-block-end"] ?: all?.let { it.getOrNull(2) ?: it.getOrNull(0) }
    return (top?.let(::cssPixels) ?: default) to (bottom?.let(::cssPixels) ?: default)
}

/** A CSS length in pixels, an em taken as 16; null for what cannot be read, 0 for `auto` and percentages. */
private fun cssPixels(value: String): Float? {
    val length = value.trim().lowercase()
    if (length == "auto") return 0f
    val number = length.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }
    val amount = number.toFloatOrNull() ?: return null
    return when (length.substring(number.length).trim()) {
        "", "px" -> amount
        "pt" -> amount * 4f / 3f
        "em", "rem" -> amount * 16f
        "pc" -> amount * 16f
        "cm" -> amount * 96f / 2.54f
        "mm" -> amount * 96f / 25.4f
        "in" -> amount * 96f
        else -> 0f
    }
}

private fun alignOf(name: String, attributes: Map<String, String>, style: Map<String, String>): Align? {
    if (name == "center") return Align.Center
    if (name !in Blocks && name != "td" && name != "th") return null
    return when ((style["text-align"] ?: attributes["align"])?.lowercase()?.trim()) {
        "center" -> Align.Center
        "right", "end" -> Align.End
        "left", "start", "justify" -> Align.Start
        else -> null
    }
}

private fun String.isBoldWeight(): Boolean = when (val weight = lowercase().trim()) {
    "bold", "bolder" -> true
    else -> (weight.toIntOrNull() ?: 400) >= 600
}

private fun Char.isHtmlSpace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'

private fun BlockType.isListType(): Boolean = this == BlockType.Bullet || this == BlockType.Numbered || this == BlockType.Todo

/** An address the page linked to, made absolute by its `<base>` if it gave a relative one. */
private fun resolveLink(href: String, base: String?): String? {
    val link = href.trim()
    if (link.isEmpty() || link.startsWith("#")) return null
    if (link.startsWith("//")) return normalizedLinkUrl("https:$link")
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link)) return normalizedLinkUrl(link)
    val root = base?.let { normalizedLinkUrl(it) } ?: return null
    val authorityEnd = root.indexOf('/', root.indexOf("://") + 3).let { if (it < 0) root.length else it }
    val origin = root.substring(0, authorityEnd)
    val directory = root.substring(authorityEnd).substringBefore('?').substringBefore('#').substringBeforeLast('/', "")
    return normalizedLinkUrl(if (link.startsWith("/")) origin + link else "$origin$directory/$link")
}

private fun merge(runs: List<Run>): List<Run> = buildList {
    for (run in runs) {
        if (run.text.isEmpty()) continue
        val previous = lastOrNull()
        if (previous != null && previous.marks == run.marks) this[lastIndex] = previous.copy(text = previous.text + run.text)
        else add(run)
    }
}

/** A `style` attribute's declarations, by lower-case property, `!important` dropped. */
private fun parseStyle(style: String?): Map<String, String> {
    if (style.isNullOrBlank()) return emptyMap()
    val declarations = mutableMapOf<String, String>()
    var depth = 0
    var quote: Char? = null
    var start = 0
    fun take(end: Int) {
        val declaration = style.substring(start, end)
        val colon = declaration.indexOf(':')
        if (colon > 0) {
            declarations[declaration.substring(0, colon).trim().lowercase()] =
                declaration.substring(colon + 1).replace("!important", "", ignoreCase = true).trim()
        }
        start = end + 1
    }
    style.forEachIndexed { index, char ->
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '(' -> depth++
            char == ')' -> depth--
            char == ';' && depth <= 0 -> take(index)
        }
    }
    if (start < style.length) take(style.length)
    return declarations
}

/** A background worth keeping as a highlight, translucent like the highlighter's; [NO_COLOR] for a plain one. */
private fun backgroundColor(value: String): Int? {
    // The `background` shorthand names its colour among images, positions and repeats.
    val argb = cssColor(value) ?: ColorToken.findAll(value).firstNotNullOfOrNull { cssColor(it.value) } ?: return null
    if ((argb ushr 24) == 0) return null
    return if (argb.isChromatic()) HIGHLIGHT_ALPHA or (argb and 0xFFFFFF) else NO_COLOR
}

private val ColorToken = Regex("""(?:rgba?|hsla?)\([^)]*\)|#[0-9a-fA-F]{3,8}|[a-zA-Z]+""")

/** A colour that is a colour — not a grey, black or white — and mostly opaque. */
private fun Int.isChromatic(): Boolean {
    val alpha = this ushr 24
    val red = (this shr 16) and 0xFF
    val green = (this shr 8) and 0xFF
    val blue = this and 0xFF
    return alpha >= 0x80 && maxOf(red, green, blue) - minOf(red, green, blue) >= 51
}

/** CSS colour syntax as ARGB: hex, rgb(), rgba(), hsl(), hsla() and the basic names. */
internal fun cssColor(value: String): Int? {
    val color = value.trim().lowercase()
    if (color == "transparent") return 0
    NamedColors[color]?.let { return it }
    if (color.startsWith("#")) {
        val hex = color.drop(1)
        if (hex.any { it !in "0123456789abcdef" }) return null
        val digits = when (hex.length) {
            3, 4 -> hex.map { "$it$it" }.joinToString("")
            6, 8 -> hex
            else -> return null
        }
        val rgb = digits.substring(0, 6).toInt(16)
        val alpha = if (digits.length == 8) digits.substring(6, 8).toInt(16) else 0xFF
        return (alpha shl 24) or rgb
    }
    val open = color.indexOf('(')
    if (open < 0 || !color.endsWith(")")) return null
    val function = color.substring(0, open).trim()
    val parts = color.substring(open + 1, color.length - 1).split(',', ' ', '/').filter { it.isNotBlank() }
    if (parts.size < 3) return null
    fun channel(part: String): Int? = if (part.endsWith("%")) part.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).roundToInt() }
        else part.toFloatOrNull()?.roundToInt()
    fun alpha(part: String?): Int? = when {
        part == null -> 0xFF
        part.endsWith("%") -> part.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).roundToInt() }
        else -> part.toFloatOrNull()?.let { (it * 255f).roundToInt() }
    }
    val a = alpha(parts.getOrNull(3))?.coerceIn(0, 255) ?: return null
    val (r, g, b) = when (function) {
        "rgb", "rgba" -> Triple(channel(parts[0]) ?: return null, channel(parts[1]) ?: return null, channel(parts[2]) ?: return null)
        "hsl", "hsla" -> {
            val hue = parts[0].removeSuffix("deg").toFloatOrNull() ?: return null
            val saturation = (parts[1].removeSuffix("%").toFloatOrNull() ?: return null) / 100f
            val lightness = (parts[2].removeSuffix("%").toFloatOrNull() ?: return null) / 100f
            hslToRgb(hue, saturation, lightness)
        }
        else -> return null
    }
    return (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}

private fun hslToRgb(hue: Float, saturation: Float, lightness: Float): Triple<Int, Int, Int> {
    val chroma = (1 - kotlin.math.abs(2 * lightness - 1)) * saturation
    val h = ((hue % 360) + 360) % 360 / 60f
    val x = chroma * (1 - kotlin.math.abs(h % 2 - 1))
    val (r, g, b) = when {
        h < 1 -> Triple(chroma, x, 0f)
        h < 2 -> Triple(x, chroma, 0f)
        h < 3 -> Triple(0f, chroma, x)
        h < 4 -> Triple(0f, x, chroma)
        h < 5 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val m = lightness - chroma / 2
    return Triple(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
}

private val NamedColors: Map<String, Int> = mapOf(
    "black" to 0xFF000000.toInt(), "silver" to 0xFFC0C0C0.toInt(), "gray" to 0xFF808080.toInt(),
    "grey" to 0xFF808080.toInt(), "white" to 0xFFFFFFFF.toInt(), "maroon" to 0xFF800000.toInt(),
    "red" to 0xFFFF0000.toInt(), "purple" to 0xFF800080.toInt(), "fuchsia" to 0xFFFF00FF.toInt(),
    "magenta" to 0xFFFF00FF.toInt(), "green" to 0xFF008000.toInt(), "lime" to 0xFF00FF00.toInt(),
    "olive" to 0xFF808000.toInt(), "yellow" to 0xFFFFFF00.toInt(), "navy" to 0xFF000080.toInt(),
    "blue" to 0xFF0000FF.toInt(), "teal" to 0xFF008080.toInt(), "aqua" to 0xFF00FFFF.toInt(),
    "cyan" to 0xFF00FFFF.toInt(), "orange" to 0xFFFFA500.toInt(), "brown" to 0xFFA52A2A.toInt(),
    "pink" to 0xFFFFC0CB.toInt(), "gold" to 0xFFFFD700.toInt(), "indigo" to 0xFF4B0082.toInt(),
    "violet" to 0xFFEE82EE.toInt(), "crimson" to 0xFFDC143C.toInt(), "darkred" to 0xFF8B0000.toInt(),
    "darkgreen" to 0xFF006400.toInt(), "darkblue" to 0xFF00008B.toInt(), "orangered" to 0xFFFF4500.toInt(),
)

/** Character references in HTML text and attribute values. */
internal fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char != '&') {
            out.append(char)
            index++
            continue
        }
        val semicolon = text.indexOf(';', index + 1).takeIf { it in index + 2..index + 33 }
        val reference = semicolon?.let { text.substring(index + 1, it) }
        val decoded: String? = when {
            reference == null -> null
            reference.startsWith("#x") || reference.startsWith("#X") -> reference.drop(2).toIntOrNull(16)?.let(::codePoint)
            reference.startsWith("#") -> reference.drop(1).toIntOrNull()?.let(::codePoint)
            else -> Entities[reference]?.let(::codePoint)
        }
        if (decoded != null) {
            out.append(decoded)
            index = semicolon!! + 1
            continue
        }
        // Browsers also read the commonest references without their semicolon.
        val bare = BareEntities.firstOrNull { text.startsWith(it, index + 1) }
        if (bare != null) {
            out.append(codePoint(Entities.getValue(bare)))
            index += bare.length + 1
        } else {
            out.append(char)
            index++
        }
    }
    return out.toString()
}

private fun codePoint(code: Int): String = when {
    code == 0 || code in 0xD800..0xDFFF || code > 0x10FFFF -> "\uFFFD"
    code < 0x10000 -> code.toChar().toString()
    else -> {
        val offset = code - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }
}

private val BareEntities = listOf("nbsp", "amp", "lt", "gt", "quot", "copy", "reg")

private val Entities: Map<String, Int> = buildMap {
    """
    quot 34 amp 38 apos 39 lt 60 gt 62 nbsp 160 iexcl 161 cent 162 pound 163 curren 164 yen 165 brvbar 166
    sect 167 uml 168 copy 169 ordf 170 laquo 171 not 172 shy 173 reg 174 macr 175 deg 176 plusmn 177 sup2 178
    sup3 179 acute 180 micro 181 para 182 middot 183 cedil 184 sup1 185 ordm 186 raquo 187 frac14 188
    frac12 189 frac34 190 iquest 191 Agrave 192 Aacute 193 Acirc 194 Atilde 195 Auml 196 Aring 197 AElig 198
    Ccedil 199 Egrave 200 Eacute 201 Ecirc 202 Euml 203 Igrave 204 Iacute 205 Icirc 206 Iuml 207 ETH 208
    Ntilde 209 Ograve 210 Oacute 211 Ocirc 212 Otilde 213 Ouml 214 times 215 Oslash 216 Ugrave 217
    Uacute 218 Ucirc 219 Uuml 220 Yacute 221 THORN 222 szlig 223 agrave 224 aacute 225 acirc 226 atilde 227
    auml 228 aring 229 aelig 230 ccedil 231 egrave 232 eacute 233 ecirc 234 euml 235 igrave 236 iacute 237
    icirc 238 iuml 239 eth 240 ntilde 241 ograve 242 oacute 243 ocirc 244 otilde 245 ouml 246 divide 247
    oslash 248 ugrave 249 uacute 250 ucirc 251 uuml 252 yacute 253 thorn 254 yuml 255 OElig 338 oelig 339
    Scaron 352 scaron 353 Yuml 376 fnof 402 circ 710 tilde 732 Alpha 913 Beta 914 Gamma 915 Delta 916
    Epsilon 917 Zeta 918 Eta 919 Theta 920 Iota 921 Kappa 922 Lambda 923 Mu 924 Nu 925 Xi 926 Omicron 927
    Pi 928 Rho 929 Sigma 931 Tau 932 Upsilon 933 Phi 934 Chi 935 Psi 936 Omega 937 alpha 945 beta 946
    gamma 947 delta 948 epsilon 949 zeta 950 eta 951 theta 952 iota 953 kappa 954 lambda 955 mu 956 nu 957
    xi 958 omicron 959 pi 960 rho 961 sigmaf 962 sigma 963 tau 964 upsilon 965 phi 966 chi 967 psi 968
    omega 969 thetasym 977 upsih 978 piv 982 ensp 8194 emsp 8195 thinsp 8201 zwnj 8204 zwj 8205 lrm 8206
    rlm 8207 ndash 8211 mdash 8212 lsquo 8216 rsquo 8217 sbquo 8218 ldquo 8220 rdquo 8221 bdquo 8222
    dagger 8224 Dagger 8225 bull 8226 hellip 8230 permil 8240 prime 8242 Prime 8243 lsaquo 8249 rsaquo 8250
    oline 8254 frasl 8260 euro 8364 image 8465 weierp 8472 real 8476 trade 8482 alefsym 8501 larr 8592
    uarr 8593 rarr 8594 darr 8595 harr 8596 crarr 8629 lArr 8656 uArr 8657 rArr 8658 dArr 8659 hArr 8660
    forall 8704 part 8706 exist 8707 empty 8709 nabla 8711 isin 8712 notin 8713 ni 8715 prod 8719 sum 8721
    minus 8722 lowast 8727 radic 8730 prop 8733 infin 8734 ang 8736 and 8743 or 8744 cap 8745 cup 8746
    int 8747 there4 8756 sim 8764 cong 8773 asymp 8776 ne 8800 equiv 8801 le 8804 ge 8805 sub 8834 sup 8835
    nsub 8836 sube 8838 supe 8839 oplus 8853 otimes 8855 perp 8869 sdot 8901 lceil 8968 rceil 8969
    lfloor 8970 rfloor 8971 lang 9001 rang 9002 loz 9674 spades 9824 clubs 9827 hearts 9829 diams 9830
    """.trim().split(Regex("\\s+")).chunked(2).forEach { (name, code) -> put(name, code.toInt()) }
}
