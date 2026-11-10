package com.vivenotes.fileimport.docx

import org.w3c.dom.Element
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.roundToInt

/** A colour in an overlay that says "automatic", which is different from saying nothing. */
internal const val AUTO_COLOR = 0

/**
 * Run formatting as one level of the hierarchy states it: null means this level does not say.
 *
 * Levels are layered with [over] and the result is [resolved]. Bold, italic and the other toggles
 * cannot go through [over] between *style* levels, because the format defines them to cancel: bold
 * in a paragraph style and bold in a character style is not bold. [xorToggles] is that rule.
 */
internal data class RunOverlay(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val script: WordScript? = null,
    val color: Int? = null,
    val highlight: Int? = null,
    val halfPoints: Int? = null,
    val font: String? = null,
    val caps: Boolean? = null,
    val smallCaps: Boolean? = null,
    val hidden: Boolean? = null,
    val spacing: Int? = null,
    val scale: Int? = null,
) {
    fun over(base: RunOverlay?): RunOverlay {
        if (base == null) return this
        return RunOverlay(
            bold = bold ?: base.bold,
            italic = italic ?: base.italic,
            underline = underline ?: base.underline,
            strike = strike ?: base.strike,
            script = script ?: base.script,
            color = color ?: base.color,
            highlight = highlight ?: base.highlight,
            halfPoints = halfPoints ?: base.halfPoints,
            font = font ?: base.font,
            caps = caps ?: base.caps,
            smallCaps = smallCaps ?: base.smallCaps,
            hidden = hidden ?: base.hidden,
            spacing = spacing ?: base.spacing,
            scale = scale ?: base.scale,
        )
    }

    /** [over], with the toggles of two *style* levels cancelling instead of the top one winning. */
    fun xorToggles(base: RunOverlay?): RunOverlay {
        if (base == null) return this
        fun toggle(top: Boolean?, under: Boolean?): Boolean? =
            if (top != null && under != null) top != under else top ?: under
        return over(base).copy(
            bold = toggle(bold, base.bold),
            italic = toggle(italic, base.italic),
            strike = toggle(strike, base.strike),
            caps = toggle(caps, base.caps),
            smallCaps = toggle(smallCaps, base.smallCaps),
            hidden = toggle(hidden, base.hidden),
        )
    }

    val isHidden: Boolean get() = hidden == true

    val isCaps: Boolean get() = caps == true

    fun resolved(): WordRunStyle = WordRunStyle(
        bold = bold == true,
        italic = italic == true,
        underline = underline == true,
        strike = strike == true,
        script = script ?: WordScript.Baseline,
        colorArgb = color?.takeIf { it != AUTO_COLOR },
        highlightArgb = highlight?.takeIf { it != AUTO_COLOR },
        halfPoints = halfPoints ?: 20,
        font = font,
        smallCaps = smallCaps == true,
        spacingTwips = spacing ?: 0,
        scalePercent = scale ?: 100,
    )
}

/** Paragraph formatting as one level states it — see [RunOverlay]. Tab stops accumulate by position. */
internal data class ParagraphOverlay(
    val outlineLevel: Int? = null,
    val align: WordAlign? = null,
    val left: Int? = null,
    val right: Int? = null,
    val firstLine: Int? = null,
    val before: Int? = null,
    val after: Int? = null,
    val beforeAuto: Boolean? = null,
    val afterAuto: Boolean? = null,
    val line: Int? = null,
    val lineRule: WordLineRule? = null,
    val contextual: Boolean? = null,
    val keepNext: Boolean? = null,
    val keepLines: Boolean? = null,
    val pageBreakBefore: Boolean? = null,
    val widowControl: Boolean? = null,
    val numId: Int? = null,
    val numLevel: Int? = null,
    val shading: Int? = null,
    val borders: WordBorders? = null,
    /** By position; a null value is a `clear` that removes what a lower level set there. */
    val tabs: Map<Int, WordTabStop?>? = null,
    val rightToLeft: Boolean? = null,
) {
    fun over(base: ParagraphOverlay?): ParagraphOverlay {
        if (base == null) return this
        return ParagraphOverlay(
            outlineLevel = outlineLevel ?: base.outlineLevel,
            align = align ?: base.align,
            left = left ?: base.left,
            right = right ?: base.right,
            firstLine = firstLine ?: base.firstLine,
            before = before ?: base.before,
            after = after ?: base.after,
            beforeAuto = beforeAuto ?: base.beforeAuto,
            afterAuto = afterAuto ?: base.afterAuto,
            line = line ?: base.line,
            lineRule = lineRule ?: base.lineRule,
            contextual = contextual ?: base.contextual,
            keepNext = keepNext ?: base.keepNext,
            keepLines = keepLines ?: base.keepLines,
            pageBreakBefore = pageBreakBefore ?: base.pageBreakBefore,
            widowControl = widowControl ?: base.widowControl,
            numId = numId ?: base.numId,
            numLevel = numLevel ?: base.numLevel,
            shading = shading ?: base.shading,
            borders = borders ?: base.borders,
            tabs = if (tabs == null) base.tabs else (base.tabs.orEmpty() + tabs),
            rightToLeft = rightToLeft ?: base.rightToLeft,
        )
    }

    fun resolved(name: String): WordParagraphStyle = WordParagraphStyle(
        name = name,
        outlineLevel = outlineLevel?.takeIf { it in 0..8 },
        align = align ?: WordAlign.Left,
        leftTwips = left ?: 0,
        rightTwips = right ?: 0,
        firstLineTwips = firstLine ?: 0,
        beforeTwips = if (beforeAuto == true) AUTO_SPACING_TWIPS else before ?: 0,
        afterTwips = if (afterAuto == true) AUTO_SPACING_TWIPS else after ?: 0,
        lineValue = line ?: 240,
        lineRule = lineRule ?: WordLineRule.Auto,
        contextualSpacing = contextual == true,
        keepNext = keepNext == true,
        keepLines = keepLines == true,
        pageBreakBefore = pageBreakBefore == true,
        widowControl = widowControl != false,
        shadingArgb = shading?.takeIf { it != AUTO_COLOR },
        borders = borders,
        tabs = tabs.orEmpty().values.filterNotNull().sortedBy { it.positionTwips },
        rightToLeft = rightToLeft == true,
    )
}

/** What Word gives a paragraph on "auto" spacing, which is a browser's 14 points. */
private const val AUTO_SPACING_TWIPS = 280

/** The table-level and cell-level things a table style can state, by level like the others. */
internal data class TableOverlay(
    val borders: WordBorders? = null,
    val indent: Int? = null,
    val align: WordAlign? = null,
    val cellMarginLeft: Int? = null,
    val cellMarginTop: Int? = null,
    val cellMarginRight: Int? = null,
    val cellMarginBottom: Int? = null,
    val rowBandSize: Int? = null,
    val columnBandSize: Int? = null,
) {
    fun over(base: TableOverlay?): TableOverlay {
        if (base == null) return this
        return TableOverlay(
            borders = borders ?: base.borders,
            indent = indent ?: base.indent,
            align = align ?: base.align,
            cellMarginLeft = cellMarginLeft ?: base.cellMarginLeft,
            cellMarginTop = cellMarginTop ?: base.cellMarginTop,
            cellMarginRight = cellMarginRight ?: base.cellMarginRight,
            cellMarginBottom = cellMarginBottom ?: base.cellMarginBottom,
            rowBandSize = rowBandSize ?: base.rowBandSize,
            columnBandSize = columnBandSize ?: base.columnBandSize,
        )
    }
}

internal data class CellOverlay(
    val shading: Int? = null,
    val borders: WordBorders? = null,
    val align: WordCellAlign? = null,
) {
    fun over(base: CellOverlay?): CellOverlay {
        if (base == null) return this
        return CellOverlay(
            shading = shading ?: base.shading,
            borders = borders ?: base.borders,
            align = align ?: base.align,
        )
    }
}

/** What a `w:tblStylePr` — a table style's first row, banded rows and so on — adds. */
internal class ConditionalFormat(
    val paragraph: ParagraphOverlay?,
    val run: RunOverlay?,
    val cell: CellOverlay?,
)

internal class WordStyle(
    val id: String,
    val name: String,
    val type: String,
    val basedOn: String?,
    val paragraph: ParagraphOverlay?,
    val run: RunOverlay?,
    val table: TableOverlay?,
    val cell: CellOverlay?,
    val conditional: Map<String, ConditionalFormat>,
    val isDefault: Boolean,
)

/** The theme part: the colours and the two fonts "Headings" and "Body" stand for. */
internal class WordTheme(val colors: Map<String, Int>, val majorFont: String?, val minorFont: String?) {
    companion object {
        /** Office's own defaults, for a document with no theme part. */
        val DEFAULT = WordTheme(
            colors = mapOf(
                "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(), "dk2" to 0xFF44546A.toInt(),
                "lt2" to 0xFFE7E6E6.toInt(), "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(),
                "accent3" to 0xFFA5A5A5.toInt(), "accent4" to 0xFFFFC000.toInt(), "accent5" to 0xFF5B9BD5.toInt(),
                "accent6" to 0xFF70AD47.toInt(), "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt(),
            ),
            majorFont = "Calibri Light",
            minorFont = "Calibri",
        )

        fun read(root: Element?): WordTheme {
            val elements = root?.child(NS_A, "themeElements") ?: return DEFAULT
            val colors = HashMap<String, Int>()
            elements.child(NS_A, "clrScheme")?.childElements?.forEach { slot ->
                val argb = slot.childElements.firstNotNullOfOrNull { plainColor(it) }
                if (argb != null) colors[slot.localName] = argb
            }
            val fonts = elements.child(NS_A, "fontScheme")
            fun latin(name: String) = fonts?.child(NS_A, name)?.child(NS_A, "latin")?.plain("typeface")?.takeIf { it.isNotBlank() }
            return WordTheme(DEFAULT.colors + colors, latin("majorFont"), latin("minorFont"))
        }

        private fun plainColor(element: Element): Int? = when (element.localName) {
            "srgbClr" -> element.plain("val")?.let(::hexColor)
            "sysClr" -> element.plain("lastClr")?.let(::hexColor)
            else -> null
        }
    }

    fun color(name: String): Int? = colors[
        when (name) {
            "tx1" -> "dk1"
            "bg1" -> "lt1"
            "tx2" -> "dk2"
            "bg2" -> "lt2"
            else -> name
        },
    ]

    /** A `w:rFonts` theme reference, e.g. `minorHAnsi`. */
    fun font(reference: String?): String? = when {
        reference == null -> null
        reference.startsWith("major") -> majorFont
        reference.startsWith("minor") -> minorFont
        else -> null
    }

    /**
     * A DrawingML colour element: `srgbClr`, `schemeClr` and the rest, with the tints it carries.
     * Null for one that cannot be read, including a fully transparent one.
     */
    fun drawingColor(element: Element?): Int? {
        val color = element ?: return null
        val base = when (color.localName) {
            "srgbClr" -> color.plain("val")?.let(::hexColor)
            "sysClr" -> color.plain("lastClr")?.let(::hexColor)
            "schemeClr" -> color.plain("val")?.let(::color)
            "prstClr" -> color.plain("val")?.let(PRESET_COLORS::get)
            else -> null
        } ?: return null
        var argb = base
        for (modifier in color.childElements) {
            val amount = modifier.plain("val")?.toFloatOrNull()?.div(100_000f) ?: continue
            argb = when (modifier.localName) {
                "lumMod" -> withLightness(argb) { it * amount }
                "lumOff" -> withLightness(argb) { it + amount }
                "tint" -> mixLinear(argb, 1f, 1f - amount)
                "shade" -> mixLinear(argb, 0f, 1f - amount)
                "alpha" -> (argb and 0xFFFFFF) or ((amount * 255).roundToInt().coerceIn(0, 255) shl 24)
                else -> argb
            }
        }
        return argb.takeIf { (it ushr 24) != 0 }
    }
}

private val PRESET_COLORS = mapOf(
    "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(),
    "green" to 0xFF008000.toInt(), "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
    "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
)

/** Tints and shades are defined on light as it adds up, which is not how sRGB numbers do. */
private fun mixLinear(from: Int, toward: Float, amount: Float): Int {
    fun channel(shift: Int): Int {
        val value = ((from ushr shift) and 0xFF) / 255f
        val linear = if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
        val mixed = linear + (toward - linear) * amount
        val encoded = if (mixed <= 0.0031308f) mixed * 12.92f else 1.055f * mixed.pow(1f / 2.4f) - 0.055f
        return (encoded * 255f).roundToInt().coerceIn(0, 255)
    }
    return (from and (0xFF shl 24)) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun mix(from: Int, to: Int, amount: Float): Int {
    fun channel(shift: Int): Int {
        val start = (from ushr shift) and 0xFF
        val end = (to ushr shift) and 0xFF
        return (start + (end - start) * amount).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun withLightness(argb: Int, change: (Float) -> Float): Int {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val high = max(max(r, g), b)
    val low = min(min(r, g), b)
    val chroma = high - low
    val lightness = (high + low) / 2f
    val saturation = if (chroma == 0f) 0f else chroma / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when {
        chroma == 0f -> 0f
        high == r -> 60f * (((g - b) / chroma) % 6f)
        high == g -> 60f * ((b - r) / chroma + 2f)
        else -> 60f * ((r - g) / chroma + 4f)
    }
    val target = change(lightness).coerceIn(0f, 1f)
    val c = (1f - kotlin.math.abs(2f * target - 1f)) * saturation
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
    val (rp, gp, bp) = when {
        h < 1f -> Triple(c, x, 0f)
        h < 2f -> Triple(x, c, 0f)
        h < 3f -> Triple(0f, c, x)
        h < 4f -> Triple(0f, x, c)
        h < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = target - c / 2f
    fun channel(value: Float) = ((value + m) * 255f).roundToInt().coerceIn(0, 255)
    return (argb and 0xFF000000.toInt()) or (channel(rp) shl 16) or (channel(gp) shl 8) or channel(bp)
}

internal fun hexColor(value: String): Int? {
    val hex = value.trim()
    if (hex.length != 6 || hex.any { it !in "0123456789abcdefABCDEF" }) return null
    return 0xFF000000.toInt() or hex.toInt(16)
}

/** Parses the formatting elements, and knows what a chain of styles comes to. */
internal class WordStyles(root: Element?, private val theme: WordTheme) {
    private val styles = HashMap<String, WordStyle>()
    val defaultRun: RunOverlay?
    val defaultParagraph: ParagraphOverlay?

    /** Style ids Word treats as default: `Normal` by convention, but the file names its own. */
    private var defaultParagraphStyle: String? = null
    private var defaultTableStyle: String? = null

    init {
        val defaults = root?.wChild("docDefaults")
        defaultRun = defaults?.wChild("rPrDefault")?.wChild("rPr")?.let(::readRun)
        defaultParagraph = defaults?.wChild("pPrDefault")?.wChild("pPr")?.let(::readParagraph)
        root?.children(NS_W, "style")?.forEach { element ->
            val style = readStyle(element) ?: return@forEach
            styles[style.id] = style
            if (style.isDefault) {
                when (style.type) {
                    "paragraph" -> defaultParagraphStyle = style.id
                    "table" -> defaultTableStyle = style.id
                }
            }
        }
    }

    private fun readStyle(element: Element): WordStyle? {
        val id = element.w("styleId") ?: return null
        val conditional = HashMap<String, ConditionalFormat>()
        element.children(NS_W, "tblStylePr").forEach { format ->
            val type = format.w("type") ?: return@forEach
            conditional[type] = ConditionalFormat(
                paragraph = format.wChild("pPr")?.let(::readParagraph),
                run = format.wChild("rPr")?.let(::readRun),
                cell = format.wChild("tcPr")?.let(::readCell),
            )
        }
        return WordStyle(
            id = id,
            name = element.wVal("name").orEmpty(),
            type = element.w("type") ?: "paragraph",
            basedOn = element.wVal("basedOn"),
            paragraph = element.wChild("pPr")?.let(::readParagraph),
            run = element.wChild("rPr")?.let(::readRun),
            table = element.wChild("tblPr")?.let(::readTable),
            cell = element.wChild("tcPr")?.let(::readCell),
            conditional = conditional,
            isDefault = element.w("default")?.let { it == "1" || it.equals("true", true) } == true,
        )
    }

    /** [id]'s chain from the root down; a cycle in a hostile file ends the walk. */
    private fun chain(id: String?): List<WordStyle> {
        val out = ArrayList<WordStyle>()
        var current = id?.let(styles::get)
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            out += current
            current = current.basedOn?.let(styles::get)
        }
        return out.asReversed()
    }

    /** The paragraph style [id] names, or the default one. */
    private fun paragraphChain(id: String?): List<WordStyle> =
        chain(id?.takeIf(styles::containsKey) ?: defaultParagraphStyle)

    fun paragraphOverlay(id: String?): ParagraphOverlay? =
        paragraphChain(id).fold(null as ParagraphOverlay?) { base, style -> style.paragraph?.over(base) ?: base }

    fun paragraphRunOverlay(id: String?): RunOverlay? =
        paragraphChain(id).fold(null as RunOverlay?) { base, style -> style.run?.over(base) ?: base }

    fun paragraphStyleName(id: String?): String = paragraphChain(id).lastOrNull()?.name.orEmpty()

    fun characterRunOverlay(id: String?): RunOverlay? =
        chain(id).fold(null as RunOverlay?) { base, style -> style.run?.over(base) ?: base }

    /** The table style [id] names, or the default one; root first. */
    fun tableChain(id: String?): List<WordStyle> =
        chain(id?.takeIf(styles::containsKey) ?: defaultTableStyle)

    // --- the formatting elements ---------------------------------------------------------------

    fun readRun(rPr: Element): RunOverlay {
        fun toggle(name: String): Boolean? = rPr.wChild(name)?.isOn()
        val fonts = rPr.wChild("rFonts")
        val font = fonts?.let {
            it.w("ascii") ?: it.w("hAnsi")
                ?: theme.font(it.w("asciiTheme") ?: it.w("hAnsiTheme"))
                ?: it.w("cs") ?: it.w("eastAsia")
        }
        val underline = rPr.wChild("u")?.let { it.w("val")?.lowercase() != "none" }
        val strike = rPr.wChild("strike")?.isOn()
        val doubleStrike = rPr.wChild("dstrike")?.isOn()
        val shading = rPr.wChild("shd")?.let(::shadingOf)
        return RunOverlay(
            bold = toggle("b"),
            italic = toggle("i"),
            underline = underline,
            strike = if (strike == null && doubleStrike == null) null else strike == true || doubleStrike == true,
            script = when (rPr.wVal("vertAlign")?.lowercase()) {
                "superscript" -> WordScript.Super
                "subscript" -> WordScript.Sub
                "baseline" -> WordScript.Baseline
                else -> null
            },
            color = rPr.wVal("color")?.let { if (it.equals("auto", true)) AUTO_COLOR else hexColor(it) },
            highlight = rPr.wVal("highlight")?.let(::highlightColor) ?: shading,
            halfPoints = rPr.wVal("sz")?.toIntOrNull(),
            font = font,
            caps = toggle("caps"),
            smallCaps = toggle("smallCaps"),
            hidden = toggle("vanish"),
            spacing = rPr.wVal("spacing")?.let(::parseTwips),
            scale = rPr.wVal("w")?.toIntOrNull(),
        )
    }

    fun readParagraph(pPr: Element): ParagraphOverlay {
        fun toggle(name: String): Boolean? = pPr.wChild(name)?.isOn()
        val indent = pPr.wChild("ind")
        val spacing = pPr.wChild("spacing")
        val numbering = pPr.wChild("numPr")
        val hanging = parseTwips(indent?.w("hanging"))
        val rightToLeft = toggle("bidi")
        return ParagraphOverlay(
            outlineLevel = pPr.wVal("outlineLvl")?.toIntOrNull(),
            align = pPr.wVal("jc")?.let { alignmentOf(it, rightToLeft == true) },
            left = parseTwips(indent?.w("left") ?: indent?.w("start")),
            right = parseTwips(indent?.w("right") ?: indent?.w("end")),
            firstLine = hanging?.let { -it } ?: parseTwips(indent?.w("firstLine")),
            before = parseTwips(spacing?.w("before")),
            after = parseTwips(spacing?.w("after")),
            beforeAuto = spacing?.w("beforeAutospacing")?.let { it == "1" || it.equals("true", true) },
            afterAuto = spacing?.w("afterAutospacing")?.let { it == "1" || it.equals("true", true) },
            line = spacing?.w("line")?.toIntOrNull(),
            lineRule = when (spacing?.w("lineRule")?.lowercase()) {
                "exact" -> WordLineRule.Exact
                "atleast" -> WordLineRule.AtLeast
                "auto" -> WordLineRule.Auto
                else -> null
            },
            contextual = toggle("contextualSpacing"),
            keepNext = toggle("keepNext"),
            keepLines = toggle("keepLines"),
            pageBreakBefore = toggle("pageBreakBefore"),
            widowControl = toggle("widowControl"),
            numId = numbering?.wVal("numId")?.toIntOrNull(),
            numLevel = numbering?.wVal("ilvl")?.toIntOrNull(),
            shading = pPr.wChild("shd")?.let(::shadingOf),
            borders = pPr.wChild("pBdr")?.let(::readBorders),
            tabs = pPr.wChild("tabs")?.let(::readTabs),
            rightToLeft = rightToLeft,
        )
    }

    private fun readTabs(tabs: Element): Map<Int, WordTabStop?> {
        val out = LinkedHashMap<Int, WordTabStop?>()
        tabs.children(NS_W, "tab").forEach { tab ->
            val position = parseTwips(tab.w("pos")) ?: return@forEach
            val value = tab.w("val")?.lowercase()
            out[position] = if (value == "clear") {
                null
            } else {
                WordTabStop(
                    positionTwips = position,
                    kind = when (value) {
                        "center" -> WordTabKind.Center
                        "right", "end" -> WordTabKind.Right
                        "decimal" -> WordTabKind.Decimal
                        "bar" -> WordTabKind.Bar
                        else -> WordTabKind.Left
                    },
                    leader = when (tab.w("leader")?.lowercase()) {
                        "dot" -> '.'
                        "hyphen" -> '-'
                        "underscore" -> '_'
                        "heavy" -> '_'
                        "middledot" -> '·'
                        else -> null
                    },
                )
            }
        }
        return out
    }

    fun readTable(tblPr: Element): TableOverlay {
        val margins = tblPr.wChild("tblCellMar")
        fun margin(name: String, alternate: String): Int? =
            (margins?.wChild(name) ?: margins?.wChild(alternate))?.let(::tableWidthTwips)
        return TableOverlay(
            borders = tblPr.wChild("tblBorders")?.let(::readBorders),
            indent = tblPr.wChild("tblInd")?.let(::tableWidthTwips),
            align = tblPr.wVal("jc")?.let { alignmentOf(it, false) },
            cellMarginLeft = margin("left", "start"),
            cellMarginTop = margin("top", "top"),
            cellMarginRight = margin("right", "end"),
            cellMarginBottom = margin("bottom", "bottom"),
            rowBandSize = tblPr.wVal("tblStyleRowBandSize")?.toIntOrNull(),
            columnBandSize = tblPr.wVal("tblStyleColBandSize")?.toIntOrNull(),
        )
    }

    fun readCell(tcPr: Element): CellOverlay = CellOverlay(
        shading = tcPr.wChild("shd")?.let(::shadingOf),
        borders = tcPr.wChild("tcBorders")?.let(::readBorders),
        align = when (tcPr.wVal("vAlign")?.lowercase()) {
            "center" -> WordCellAlign.Center
            "bottom" -> WordCellAlign.Bottom
            "top" -> WordCellAlign.Top
            else -> null
        },
    )

    fun readBorders(borders: Element): WordBorders {
        fun edge(name: String, alternate: String): WordBorder? =
            (borders.wChild(name) ?: borders.wChild(alternate))?.let(::readBorder)
        return WordBorders(
            top = edge("top", "top"),
            left = edge("left", "start"),
            bottom = edge("bottom", "bottom"),
            right = edge("right", "end"),
            insideHorizontal = edge("insideH", "insideH"),
            insideVertical = edge("insideV", "insideV"),
        )
    }

    private fun readBorder(border: Element): WordBorder {
        val style = when (border.w("val")?.lowercase()) {
            null, "nil", "none" -> WordBorderStyle.None
            "double" -> WordBorderStyle.Double
            "dashed", "dashsmallgap", "dotdash", "dotdotdash", "dashdotstroked" -> WordBorderStyle.Dashed
            "dotted" -> WordBorderStyle.Dotted
            "thick" -> WordBorderStyle.Thick
            "thinthicksmallgap", "thickthinsmallgap", "thinthickmediumgap", "thickthinmediumgap",
            "thinthicklargegap", "thickthinlargegap", -> WordBorderStyle.ThinThick
            else -> WordBorderStyle.Single
        }
        return WordBorder(
            style = style,
            widthEighths = border.w("sz")?.toIntOrNull() ?: 4,
            colorArgb = border.w("color")?.takeUnless { it.equals("auto", true) }?.let(::hexColor),
        )
    }
}

/** A `w:tblW`-shaped value in twips; a share of a page has no twips to give here. */
internal fun tableWidthTwips(element: Element): Int? = when (element.w("type")?.lowercase()) {
    "pct", "auto", "nil" -> null
    else -> parseTwips(element.w("w"))
}

/** `w:shd`: what shows is the fill, or the pattern colour for a solid fill. */
internal fun shadingOf(shading: Element): Int? {
    val fill = shading.w("fill")?.takeUnless { it.equals("auto", true) }?.let(::hexColor)
    if (shading.w("val")?.lowercase() == "solid") {
        return shading.w("color")?.takeUnless { it.equals("auto", true) }?.let(::hexColor) ?: fill
    }
    return fill ?: AUTO_COLOR
}

private fun alignmentOf(value: String, rightToLeft: Boolean): WordAlign = when (value.lowercase()) {
    "center" -> WordAlign.Center
    "both", "distribute", "thaidistribute", "lowkashida", "mediumkashida", "highkashida" -> WordAlign.Justify
    "right", "end" -> if (rightToLeft) WordAlign.Left else WordAlign.Right
    else -> if (rightToLeft) WordAlign.Right else WordAlign.Left
}

private fun highlightColor(name: String): Int? = when (name.lowercase()) {
    "none" -> AUTO_COLOR
    "yellow" -> 0xFFFFFF00.toInt()
    "green" -> 0xFF00FF00.toInt()
    "cyan" -> 0xFF00FFFF.toInt()
    "magenta" -> 0xFFFF00FF.toInt()
    "blue" -> 0xFF0000FF.toInt()
    "red" -> 0xFFFF0000.toInt()
    "darkblue" -> 0xFF000080.toInt()
    "darkcyan" -> 0xFF008080.toInt()
    "darkgreen" -> 0xFF008000.toInt()
    "darkmagenta" -> 0xFF800080.toInt()
    "darkred" -> 0xFF800000.toInt()
    "darkyellow" -> 0xFF808000.toInt()
    "darkgray" -> 0xFF808080.toInt()
    "lightgray" -> 0xFFC0C0C0.toInt()
    "black" -> 0xFF000000.toInt()
    "white" -> 0xFFFFFFFF.toInt()
    else -> null
}

internal class NumberingLevel(
    val start: Int,
    val format: String,
    val text: String,
    val suffix: String,
    val paragraph: ParagraphOverlay?,
    val run: RunOverlay?,
    val restartAfter: Int?,
    val legal: Boolean,
)

/** `numbering.xml`, and the counters a document's lists advance as they are read. */
internal class WordNumbering(root: Element?, private val styles: WordStyles) {
    private class Instance(val abstractId: Int, val levels: Map<Int, NumberingLevel>, val starts: Map<Int, Int>)

    private val abstracts = HashMap<Int, Map<Int, NumberingLevel>>()
    private val instances = HashMap<Int, Instance>()
    private val counters = HashMap<Int, IntArray>()
    private val startsConsumed = HashSet<Int>()

    init {
        root?.children(NS_W, "abstractNum")?.forEach { element ->
            val id = element.w("abstractNumId")?.toIntOrNull() ?: return@forEach
            abstracts[id] = readLevels(element)
        }
        root?.children(NS_W, "num")?.forEach { element ->
            val id = element.w("numId")?.toIntOrNull() ?: return@forEach
            val abstractId = element.wVal("abstractNumId")?.toIntOrNull() ?: return@forEach
            val overrides = HashMap<Int, NumberingLevel>()
            val starts = HashMap<Int, Int>()
            element.children(NS_W, "lvlOverride").forEach { override ->
                val level = override.w("ilvl")?.toIntOrNull() ?: return@forEach
                override.wVal("startOverride")?.toIntOrNull()?.let { starts[level] = it }
                override.wChild("lvl")?.let { overrides[level] = readLevel(it) }
            }
            instances[id] = Instance(abstractId, abstracts[abstractId].orEmpty() + overrides, starts)
        }
    }

    private fun readLevels(abstract: Element): Map<Int, NumberingLevel> {
        val levels = HashMap<Int, NumberingLevel>()
        abstract.children(NS_W, "lvl").forEach { element ->
            val level = element.w("ilvl")?.toIntOrNull() ?: return@forEach
            levels[level] = readLevel(element)
        }
        return levels
    }

    private fun readLevel(element: Element) = NumberingLevel(
        start = element.wVal("start")?.toIntOrNull() ?: 1,
        format = element.wVal("numFmt")?.lowercase() ?: "decimal",
        text = element.wVal("lvlText").orEmpty(),
        suffix = element.wVal("suff")?.lowercase() ?: "tab",
        paragraph = element.wChild("pPr")?.let(styles::readParagraph),
        run = element.wChild("rPr")?.let(styles::readRun),
        restartAfter = element.wVal("lvlRestart")?.toIntOrNull(),
        legal = element.wChild("isLgl")?.isOn() == true,
    )

    fun level(numId: Int?, level: Int): NumberingLevel? =
        numId?.takeIf { it != 0 }?.let(instances::get)?.levels?.get(level)

    /**
     * Counts one paragraph at [level] of list [numId] and returns its marker text, or null when
     * [numId] names no list. Must be called once per paragraph, in document order.
     */
    fun nextLabel(numId: Int, level: Int): String? {
        val instance = instances[numId] ?: return null
        val definition = instance.levels[level] ?: return null
        val counts = counters.getOrPut(instance.abstractId) { IntArray(MAX_LEVELS) { UNSTARTED } }
        val startOverride = instance.starts[level]
        val freshInstance = startOverride != null && startsConsumed.add(numId * MAX_LEVELS + level)
        counts[level] = when {
            freshInstance -> startOverride!!
            counts[level] == UNSTARTED -> definition.start
            else -> counts[level] + 1
        }
        for (deeper in level + 1 until MAX_LEVELS) {
            val restart = instance.levels[deeper]?.restartAfter
            // `w:lvlRestart` says after which level this one starts over; 0 means never.
            if (restart == null || restart in 1..level + 1) counts[deeper] = UNSTARTED
        }
        if (definition.format == "bullet" || definition.format == "none") return definition.text
        return PLACEHOLDER.replace(definition.text) { match ->
            val index = match.groupValues[1].toInt() - 1
            val placeholder = instance.levels[index] ?: return@replace ""
            val value = counts.getOrElse(index) { UNSTARTED }.takeIf { it != UNSTARTED } ?: placeholder.start
            formatNumber(value, if (definition.legal) "decimal" else placeholder.format)
        }
    }

    private companion object {
        const val MAX_LEVELS = 9
        const val UNSTARTED = Int.MIN_VALUE
        val PLACEHOLDER = Regex("%([1-9])")
    }
}

/** A list or page number in one of Word's numbering formats. */
internal fun formatNumber(value: Int, format: String?): String = when (format?.lowercase()) {
    "decimalzero" -> if (value in 0..9) "0$value" else "$value"
    "lowerletter" -> letters(value)
    "upperletter" -> letters(value).uppercase()
    "lowerroman" -> roman(value).lowercase()
    "upperroman" -> roman(value)
    "ordinal" -> "$value" + ordinalSuffix(value)
    else -> "$value"
}

private fun ordinalSuffix(value: Int): String = when {
    value % 100 in 11..13 -> "th"
    value % 10 == 1 -> "st"
    value % 10 == 2 -> "nd"
    value % 10 == 3 -> "rd"
    else -> "th"
}

private fun letters(value: Int): String {
    if (value < 1) return "$value"
    val letter = 'a' + (value - 1) % 26
    return letter.toString().repeat((value - 1) / 26 + 1)
}

private fun roman(value: Int): String {
    if (value !in 1..3999) return "$value"
    val numerals = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
        50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
    )
    var remaining = value
    return buildString {
        for ((amount, numeral) in numerals) {
            while (remaining >= amount) {
                append(numeral)
                remaining -= amount
            }
        }
    }
}
