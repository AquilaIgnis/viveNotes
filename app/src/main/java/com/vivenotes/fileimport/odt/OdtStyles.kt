package com.vivenotes.fileimport.odt

import com.vivenotes.fileimport.docx.WordAlign
import com.vivenotes.fileimport.docx.WordBorder
import com.vivenotes.fileimport.docx.WordBorderStyle
import com.vivenotes.fileimport.docx.WordBorders
import com.vivenotes.fileimport.docx.WordBreakBefore
import com.vivenotes.fileimport.docx.WordLineRule
import com.vivenotes.fileimport.docx.WordParagraphStyle
import com.vivenotes.fileimport.docx.WordRunStyle
import com.vivenotes.fileimport.docx.WordScript
import com.vivenotes.fileimport.docx.WordTabKind
import com.vivenotes.fileimport.docx.WordTabStop
import com.vivenotes.fileimport.docx.attr
import com.vivenotes.fileimport.docx.child
import com.vivenotes.fileimport.docx.childElements
import com.vivenotes.fileimport.docx.hexColor
import com.vivenotes.fileimport.docx.parseTwips
import org.w3c.dom.Element
import kotlin.math.roundToInt

internal const val NS_OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
internal const val NS_STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
internal const val NS_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
internal const val NS_TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
internal const val NS_DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
internal const val NS_FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
internal const val NS_SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
internal const val NS_XLINK = "http://www.w3.org/1999/xlink"
internal const val NS_LOEXT = "urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0"

internal class OdtFont(val family: String, val generic: String?)

/** What a paragraph style comes to once its parents have had their say. */
internal class OdtParagraphLook(
    val style: WordParagraphStyle,
    val run: WordRunStyle,
    /** Whether a left margin or indent was set along the way; a list's own then gives way to it. */
    val ownIndent: Boolean,
    val listStyle: String?,
    /** The page style this paragraph starts, when it starts one. */
    val masterPage: String?,
    val breakBefore: WordBreakBefore,
    val breakAfter: WordBreakBefore,
)

/** Everything in a file that a style name can refer to, and is the same in both of its XML parts. */
internal class OdtSharedStyles(root: Element?, contentRoot: Element) {
    private val roots = listOfNotNull(root, contentRoot)

    val fonts: Map<String, OdtFont> = roots.flatMap { part ->
        part.child(NS_OFFICE, "font-face-decls")?.childElements.orEmpty()
    }.associate { face ->
        face.attr(NS_STYLE, "name").orEmpty() to OdtFont(
            family = face.attr(NS_SVG, "font-family").orEmpty().trim('\'', '"', ' '),
            generic = face.attr(NS_STYLE, "font-family-generic"),
        )
    }

    private val officeStyles = root?.child(NS_OFFICE, "styles")

    /** The named styles: family, then name. */
    val named: Map<String, Element> = officeStyles?.childElements.orEmpty()
        .filter { it.namespaceURI == NS_STYLE && it.localName == "style" }
        .associateBy(::keyOf)

    val defaults: Map<String, Element> = officeStyles?.childElements.orEmpty()
        .filter { it.namespaceURI == NS_STYLE && it.localName == "default-style" }
        .associateBy { it.attr(NS_STYLE, "family").orEmpty() }

    /** List styles live with the other named things; a paragraph's automatic ones are in its own part. */
    val namedListStyles: Map<String, Element> = officeStyles?.childElements.orEmpty()
        .filter { it.namespaceURI == NS_TEXT && it.localName == "list-style" }
        .associateBy { it.attr(NS_STYLE, "name").orEmpty() }

    companion object {
        fun keyOf(style: Element): String =
            style.attr(NS_STYLE, "family").orEmpty() + "/" + style.attr(NS_STYLE, "name").orEmpty()
    }
}

/**
 * Style lookups for one XML part.
 *
 * Automatic styles are private to the part that declares them, and `content.xml` and `styles.xml`
 * routinely both have a `P1`. So the body and the page headers each get one of these.
 */
internal class OdtStyleScope(private val shared: OdtSharedStyles, part: Element?) {
    private val automaticRoot = part?.child(NS_OFFICE, "automatic-styles")
    private val automatic: Map<String, Element> = automaticRoot?.childElements.orEmpty()
        .filter { it.namespaceURI == NS_STYLE && it.localName == "style" }
        .associateBy(OdtSharedStyles::keyOf)
    private val automaticListStyles: Map<String, Element> = automaticRoot?.childElements.orEmpty()
        .filter { it.namespaceURI == NS_TEXT && it.localName == "list-style" }
        .associateBy { it.attr(NS_STYLE, "name").orEmpty() }

    private val paragraphs = HashMap<String?, OdtParagraphLook>()
    private val runs = HashMap<Pair<WordRunStyle, String>, WordRunStyle>()

    fun listStyle(name: String?): Element? = name?.let { automaticListStyles[it] ?: shared.namedListStyles[it] }

    private fun find(family: String, name: String): Element? = automatic["$family/$name"] ?: shared.named["$family/$name"]

    /** [name]'s style and its ancestors, the root first, stopping at a loop if the file has one. */
    private fun chain(family: String, name: String?): List<Element> {
        val out = ArrayDeque<Element>()
        var current = name?.let { find(family, it) }
        while (current != null && out.size < MAX_CHAIN && out.none { it === current }) {
            out.addFirst(current)
            current = current.attr(NS_STYLE, "parent-style-name")?.let { find(family, it) }
        }
        return out
    }

    fun paragraph(name: String?): OdtParagraphLook = paragraphs.getOrPut(name) {
        val levels = listOfNotNull(shared.defaults["paragraph"]) + chain("paragraph", name)
        var style = WordParagraphStyle()
        var run = WordRunStyle()
        var ownIndent = false
        var breakBefore = WordBreakBefore.None
        var breakAfter = WordBreakBefore.None
        var listStyle: String? = null
        for (level in levels) {
            run = applyText(run, level.child(NS_STYLE, "text-properties"))
            val properties = level.child(NS_STYLE, "paragraph-properties")
            style = applyParagraph(style, properties)
            if (properties?.attr(NS_FO, "margin-left") != null || properties?.attr(NS_FO, "text-indent") != null) ownIndent = true
            properties?.attr(NS_FO, "break-before")?.let { breakBefore = breakOf(it) }
            properties?.attr(NS_FO, "break-after")?.let { breakAfter = breakOf(it) }
            level.attr(NS_STYLE, "list-style-name")?.let { listStyle = it.ifEmpty { null } }
            level.attr(NS_STYLE, "default-outline-level")?.toIntOrNull()?.let { style = style.copy(outlineLevel = it - 1) }
        }
        val leaf = levels.lastOrNull()
        if (breakBefore == WordBreakBefore.Page) style = style.copy(pageBreakBefore = true)
        OdtParagraphLook(
            style = style.copy(name = displayNameOf(leaf)),
            run = run,
            ownIndent = ownIndent,
            listStyle = listStyle,
            masterPage = leaf?.attr(NS_STYLE, "master-page-name")?.takeIf { it.isNotEmpty() },
            breakBefore = if (breakBefore == WordBreakBefore.Column) WordBreakBefore.Column else WordBreakBefore.None,
            breakAfter = breakAfter,
        )
    }

    /**
     * The name a person would know the style by. An automatic style only overrides a named one, so
     * it answers with its parent's. `Heading_20_1` is how the file spells `Heading 1`.
     */
    private fun displayNameOf(style: Element?): String {
        style ?: return ""
        val named = if (style.attr(NS_STYLE, "name")?.let { automatic["paragraph/$it"] } != null) {
            style.attr(NS_STYLE, "parent-style-name")?.let { find("paragraph", it) }
        } else {
            style
        }
        named ?: return ""
        return named.attr(NS_STYLE, "display-name")
            ?: named.attr(NS_STYLE, "name").orEmpty().replace("_20_", " ")
    }

    /** [base] with the text style [name] laid over it. Only what the style says changes. */
    fun run(name: String?, base: WordRunStyle): WordRunStyle {
        name ?: return base
        return runs.getOrPut(base to name) {
            chain("text", name).fold(base) { run, level -> applyText(run, level.child(NS_STYLE, "text-properties")) }
        }
    }

    /** The page style a [family] style [name] starts, when it says it starts one. */
    fun masterPage(family: String, name: String?): String? =
        name?.let { find(family, it) }?.attr(NS_STYLE, "master-page-name")?.takeIf { it.isNotEmpty() }

    /** A property of [family] style [name], from the nearest of its ancestors to set one. */
    fun property(family: String, name: String?, properties: String, namespace: String, attribute: String): String? =
        chain(family, name).asReversed().firstNotNullOfOrNull { it.child(NS_STYLE, properties)?.attr(namespace, attribute) }

    fun applyText(run: WordRunStyle, properties: Element?): WordRunStyle {
        properties ?: return run
        var out = run
        properties.attr(NS_FO, "font-weight")?.let { out = out.copy(bold = it == "bold" || (it.toIntOrNull() ?: 0) >= BOLD_WEIGHT) }
        properties.attr(NS_FO, "font-style")?.let { out = out.copy(italic = it == "italic" || it == "oblique") }
        properties.attr(NS_STYLE, "text-underline-style")?.let { out = out.copy(underline = it != "none") }
        properties.attr(NS_STYLE, "text-line-through-style")?.let { out = out.copy(strike = it != "none") }
        properties.attr(NS_STYLE, "text-position")?.let { out = out.copy(script = scriptOf(it)) }
        properties.attr(NS_FO, "color")?.let { out = out.copy(colorArgb = colorOf(it)) }
        if (properties.attr(NS_STYLE, "use-window-font-color") == "true") out = out.copy(colorArgb = null)
        properties.attr(NS_FO, "background-color")?.let { out = out.copy(highlightArgb = colorOf(it)) }
        properties.attr(NS_FO, "font-size")?.let { out = out.copy(halfPoints = sizeOf(it, out.halfPoints)) }
        properties.attr(NS_FO, "font-family")?.let { out = out.copy(font = it.trim('\'', '"', ' ')) }
        properties.attr(NS_STYLE, "font-name")?.let { out = out.copy(font = shared.fonts[it]?.family?.ifEmpty { null } ?: it) }
        properties.attr(NS_FO, "font-variant")?.let { out = out.copy(smallCaps = it == "small-caps") }
        properties.attr(NS_FO, "letter-spacing")?.let { out = out.copy(spacingTwips = parseTwips(it) ?: 0) }
        properties.attr(NS_STYLE, "text-scale")?.removeSuffix("%")?.toIntOrNull()?.let { out = out.copy(scalePercent = it) }
        return out
    }

    private fun applyParagraph(style: WordParagraphStyle, properties: Element?): WordParagraphStyle {
        properties ?: return style
        var out = style
        fun length(name: String): Int? = parseTwips(properties.attr(NS_FO, name))
        properties.attr(NS_FO, "text-align")?.let { out = out.copy(align = alignOf(it)) }
        length("margin")?.let { out = out.copy(leftTwips = it, rightTwips = it, beforeTwips = it, afterTwips = it) }
        length("margin-left")?.let { out = out.copy(leftTwips = it) }
        length("margin-right")?.let { out = out.copy(rightTwips = it) }
        length("margin-top")?.let { out = out.copy(beforeTwips = it) }
        length("margin-bottom")?.let { out = out.copy(afterTwips = it) }
        length("text-indent")?.let { out = out.copy(firstLineTwips = it) }
        properties.attr(NS_FO, "line-height")?.let { value ->
            out = when {
                value == "normal" -> out.copy(lineValue = SINGLE_LINE, lineRule = WordLineRule.Auto)
                value.endsWith("%") -> out.copy(
                    lineValue = ((value.removeSuffix("%").toFloatOrNull() ?: 100f) * SINGLE_LINE / 100f).roundToInt(),
                    lineRule = WordLineRule.Auto,
                )
                else -> parseTwips(value)?.let { out.copy(lineValue = it, lineRule = WordLineRule.Exact) } ?: out
            }
        }
        parseTwips(properties.attr(NS_STYLE, "line-height-at-least"))?.let { out = out.copy(lineValue = it, lineRule = WordLineRule.AtLeast) }
        properties.attr(NS_FO, "keep-with-next")?.let { out = out.copy(keepNext = it == "always") }
        properties.attr(NS_FO, "keep-together")?.let { out = out.copy(keepLines = it == "always") }
        properties.attr(NS_FO, "widows")?.let { out = out.copy(widowControl = it != "0") }
        properties.attr(NS_FO, "background-color")?.let { out = out.copy(shadingArgb = colorOf(it)) }
        properties.attr(NS_STYLE, "contextual-spacing")?.let { out = out.copy(contextualSpacing = it == "true") }
        properties.attr(NS_STYLE, "writing-mode")?.let { out = out.copy(rightToLeft = it.startsWith("rl")) }
        borderSides(properties)?.let { out = out.copy(borders = it) }
        properties.child(NS_STYLE, "tab-stops")?.let { stops -> out = out.copy(tabs = stops.childElements.mapNotNull(::tabOf)) }
        return out
    }

    private fun tabOf(stop: Element): WordTabStop? {
        val position = parseTwips(stop.attr(NS_STYLE, "position")) ?: return null
        val kind = when (stop.attr(NS_STYLE, "type")) {
            "center" -> WordTabKind.Center
            "right" -> WordTabKind.Right
            "char" -> WordTabKind.Decimal
            else -> WordTabKind.Left
        }
        val leader = stop.attr(NS_STYLE, "leader-text")?.firstOrNull()?.takeIf { it != ' ' }
        return WordTabStop(position, kind, leader)
    }

    private companion object {
        const val MAX_CHAIN = 32
        const val BOLD_WEIGHT = 600
        const val SINGLE_LINE = 240

        fun alignOf(value: String): WordAlign = when (value) {
            "center" -> WordAlign.Center
            "end", "right" -> WordAlign.Right
            "justify" -> WordAlign.Justify
            else -> WordAlign.Left
        }

        fun breakOf(value: String): WordBreakBefore = when (value) {
            "page" -> WordBreakBefore.Page
            "column" -> WordBreakBefore.Column
            else -> WordBreakBefore.None
        }

        /** `super 58%`, `sub 58%`, or a signed percentage, of which only the sign matters here. */
        fun scriptOf(value: String): WordScript {
            val first = value.substringBefore(' ')
            val amount = first.removeSuffix("%").toFloatOrNull()
            return when {
                first == "super" || (amount != null && amount > 0f) -> WordScript.Super
                first == "sub" || (amount != null && amount < 0f) -> WordScript.Sub
                else -> WordScript.Baseline
            }
        }

        fun sizeOf(value: String, inherited: Int): Int =
            if (value.endsWith("%")) {
                (inherited * (value.removeSuffix("%").toFloatOrNull() ?: 100f) / 100f).roundToInt()
            } else {
                parseTwips(value)?.let { it / TWIPS_PER_HALF_POINT } ?: inherited
            }

        const val TWIPS_PER_HALF_POINT = 10
    }
}

/** `#ff0000`, or null for `transparent` and anything else that is no colour. */
internal fun colorOf(value: String): Int? = value.takeIf { it.startsWith("#") }?.let { hexColor(it.drop(1)) }

/** `0.5pt solid #000000`, in whichever order the producer wrote those. */
internal fun borderOf(value: String?): WordBorder? {
    value ?: return null
    val parts = value.trim().split(Regex("\\s+"))
    if (parts.any { it == "none" || it == "hidden" }) return WordBorder(WordBorderStyle.None, 0, null)
    val style = when {
        "double" in parts -> WordBorderStyle.Double
        "dashed" in parts -> WordBorderStyle.Dashed
        "dotted" in parts -> WordBorderStyle.Dotted
        else -> WordBorderStyle.Single
    }
    val twips = parts.firstNotNullOfOrNull { part -> part.takeIf { it.firstOrNull()?.isDigit() == true }?.let(::parseTwips) } ?: 0
    return WordBorder(style, (twips * EIGHTHS_PER_TWIP).roundToInt().coerceAtLeast(1), parts.firstNotNullOfOrNull { colorOf(it) })
}

private const val EIGHTHS_PER_TWIP = 0.4f

/** The borders an element sets, one side at a time or all of them at once; null when it sets none. */
internal fun borderSides(properties: Element): WordBorders? {
    val all = borderOf(properties.attr(NS_FO, "border"))
    val top = borderOf(properties.attr(NS_FO, "border-top")) ?: all
    val left = borderOf(properties.attr(NS_FO, "border-left")) ?: all
    val bottom = borderOf(properties.attr(NS_FO, "border-bottom")) ?: all
    val right = borderOf(properties.attr(NS_FO, "border-right")) ?: all
    if (top == null && left == null && bottom == null && right == null) return null
    return WordBorders(top = top, left = left, bottom = bottom, right = right)
}
