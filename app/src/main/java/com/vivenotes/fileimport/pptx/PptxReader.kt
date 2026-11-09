package com.vivenotes.fileimport.pptx

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.DocxPackage
import com.vivenotes.fileimport.docx.DocxRelationship
import com.vivenotes.fileimport.docx.NS_A
import com.vivenotes.fileimport.docx.NS_M
import com.vivenotes.fileimport.docx.NS_MC
import com.vivenotes.fileimport.docx.NS_R
import com.vivenotes.fileimport.docx.WordAlign
import com.vivenotes.fileimport.docx.WordAnchor
import com.vivenotes.fileimport.docx.WordBlock
import com.vivenotes.fileimport.docx.WordBorder
import com.vivenotes.fileimport.docx.WordBorderStyle
import com.vivenotes.fileimport.docx.WordBorders
import com.vivenotes.fileimport.docx.WordBoxed
import com.vivenotes.fileimport.docx.WordCell
import com.vivenotes.fileimport.docx.WordCellAlign
import com.vivenotes.fileimport.docx.WordCrop
import com.vivenotes.fileimport.docx.WordDocument
import com.vivenotes.fileimport.docx.WordEquation
import com.vivenotes.fileimport.docx.WordGradient
import com.vivenotes.fileimport.docx.WordHeaderSet
import com.vivenotes.fileimport.docx.WordInline
import com.vivenotes.fileimport.docx.WordLineBreak
import com.vivenotes.fileimport.docx.WordLineRule
import com.vivenotes.fileimport.docx.WordListLabel
import com.vivenotes.fileimport.docx.WordPageGeometry
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordParagraphStyle
import com.vivenotes.fileimport.docx.WordPath
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
import com.vivenotes.fileimport.docx.WordTheme
import com.vivenotes.fileimport.docx.WordVerticalMerge
import com.vivenotes.fileimport.docx.WordWrap
import com.vivenotes.fileimport.docx.attr
import com.vivenotes.fileimport.docx.child
import com.vivenotes.fileimport.docx.childElements
import com.vivenotes.fileimport.docx.children
import com.vivenotes.fileimport.docx.decodeSymbolFont
import com.vivenotes.fileimport.docx.emu
import com.vivenotes.fileimport.docx.emuToTwips
import com.vivenotes.fileimport.docx.formatNumber
import com.vivenotes.fileimport.docx.isIn
import com.vivenotes.fileimport.docx.isSymbolFont
import com.vivenotes.fileimport.docx.ommlPlainText
import com.vivenotes.fileimport.docx.ommlToLatex
import com.vivenotes.fileimport.docx.plain
import com.vivenotes.model.normalizedLinkUrl
import com.vivenotes.richtext.isChromatic
import org.w3c.dom.Element
import kotlin.math.max
import kotlin.math.roundToInt

internal const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
internal const val NS_DSP = "http://schemas.microsoft.com/office/drawing/2008/diagram"
internal const val NS_DGM = "http://schemas.openxmlformats.org/drawingml/2006/diagram"
internal const val NS_CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart"
internal const val NS_A14 = "http://schemas.microsoft.com/office/drawing/2010/main"

/** A presentation as read: one page of shapes per slide, and what each slide comes to as objects. */
internal class PptxRead(
    val document: WordDocument,
    val slides: List<List<PptxObject>>,
    val slideWidthTwips: Int,
    val figures: List<PptxObject.Figure>,
    /** One page per drawing that has to be cut out as a picture. */
    val figureDocument: WordDocument?,
) {
    val figureParts: Map<PptxObject.Figure, String> = figures.withIndex().associate { (index, figure) -> figure to "$FIGURE_PREFIX$index" }

    fun figureIndex(part: String): Int? = part.takeIf { it.startsWith(FIGURE_PREFIX) }?.removePrefix(FIGURE_PREFIX)?.toIntOrNull()

    private companion object {
        const val FIGURE_PREFIX = "pptx-figure-"
    }
}

/**
 * Reads a `.pptx` into the model the Word formats use.
 *
 * Nearly everything about a slide can be left to its layout and master, so most of the work is
 * following those: where a placeholder is, what colour "text 1" is on this slide, and what size the
 * third level of a list is. One shape PowerPoint would draw that this can't read costs only itself.
 */
internal class PptxReader(private val pkg: DocxPackage) {
    private val presentation: Element = pkg.xml(pkg.mainPart) ?: throw ImportException(ImportFailure.Unreadable)
    private val presentationRels = pkg.relationships(pkg.mainPart)
    private val defaultTextStyle = presentation.child(NS_P, "defaultTextStyle")
    private val slideWidth: Int
    private val slideHeight: Int
    private val masters = HashMap<String, Master>()
    private val layouts = HashMap<String, Layout>()
    private val tableStyles: Map<String, Element>
    private var groups = 0

    init {
        val size = presentation.child(NS_P, "sldSz")
        slideWidth = emuToTwips(size?.emu("cx") ?: DEFAULT_SLIDE_WIDTH)
        slideHeight = emuToTwips(size?.emu("cy") ?: DEFAULT_SLIDE_HEIGHT)
        val stylesPart = presentationRels.values.firstOrNull { it.type.endsWith("/tableStyles") }?.target
        tableStyles = stylesPart?.let(pkg::xml)?.children(NS_A, "tblStyle").orEmpty().associateBy { it.plain("styleId").orEmpty() }
    }

    private class Placeholder(val type: String, val index: Int?, val shape: Element)

    private inner class Master(part: String) {
        val root: Element = pkg.xml(part) ?: throw ImportException(ImportFailure.Unreadable)
        val relationships = pkg.relationships(part)
        val theme: WordTheme = remapped(
            WordTheme.read(relationships.values.firstOrNull { it.type.endsWith("/theme") }?.target?.let(pkg::xml)),
            root.child(NS_P, "clrMap"),
        )
        val tree: Element? = root.child(NS_P, "cSld")?.child(NS_P, "spTree")
        val background: Element? = root.child(NS_P, "cSld")?.child(NS_P, "bg")
        val placeholders: List<Placeholder> = placeholdersOf(tree)
        val textStyles: Element? = root.child(NS_P, "txStyles")
    }

    private inner class Layout(part: String) {
        val root: Element = pkg.xml(part) ?: throw ImportException(ImportFailure.Unreadable)
        val relationships = pkg.relationships(part)
        val master: Master = masterAt(relationships.values.first { it.type.endsWith("/slideMaster") }.target)
        val tree: Element? = root.child(NS_P, "cSld")?.child(NS_P, "spTree")
        val background: Element? = root.child(NS_P, "cSld")?.child(NS_P, "bg")
        val placeholders: List<Placeholder> = placeholdersOf(tree)
        val showsMasterShapes: Boolean = root.plain("showMasterSp") != "0"
    }

    private fun masterAt(part: String): Master = masters.getOrPut(part) { Master(part) }

    private fun layoutAt(part: String): Layout = layouts.getOrPut(part) { Layout(part) }

    private fun placeholdersOf(tree: Element?): List<Placeholder> = tree?.children(NS_P, "sp").orEmpty().mapNotNull { shape ->
        phOf(shape)?.let { Placeholder(it.plain("type") ?: "obj", it.plain("idx")?.toIntOrNull(), shape) }
    }

    private fun phOf(shape: Element): Element? = shape.child(NS_P, "nvSpPr")?.child(NS_P, "nvPr")?.child(NS_P, "ph")

    /** A slide's "text 1" is whichever theme colour its master's map says; a dark template swaps them. */
    private fun remapped(theme: WordTheme, map: Element?): WordTheme {
        map ?: return theme
        fun slot(name: String): Pair<String, Int>? {
            val target = map.plain(name) ?: return null
            val argb = theme.colors[target] ?: return null
            return name to argb
        }
        val colors = HashMap(theme.colors)
        listOfNotNull(slot("bg1")?.second?.let { "lt1" to it }, slot("tx1")?.second?.let { "dk1" to it }, slot("bg2")?.second?.let { "lt2" to it }, slot("tx2")?.second?.let { "dk2" to it }).forEach { colors[it.first] = it.second }
        return WordTheme(colors, theme.majorFont, theme.minorFont)
    }

    fun read(): PptxRead {
        val parts = presentation.child(NS_P, "sldIdLst")?.children(NS_P, "sldId").orEmpty()
            .mapNotNull { presentationRels[it.attr(NS_R, "id")]?.target }
        val sections = ArrayList<WordSection>(parts.size)
        val objects = ArrayList<List<PptxObject>>(parts.size)
        parts.take(MAX_SLIDES).forEachIndexed { index, part ->
            val (painted, own) = readSlide(part, index + 1)
            sections += pageOf(painted, slideWidth, slideHeight, 0, 0, index == 0)
            objects += arrangeSlide(own, slideWidth, slideHeight)
        }
        val figures = objects.flatten().filterIsInstance<PptxObject.Figure>()
        val figureSections = figures.mapIndexed { index, figure ->
            pageOf(figure.members, figure.width.coerceAtLeast(1), figure.height.coerceAtLeast(1), figure.left, figure.top, index == 0)
        }
        fun documentOf(pages: List<WordSection>) = WordDocument(pages, emptyMap(), emptyMap(), WordSettings())
        return PptxRead(documentOf(sections), objects, slideWidth, figures, if (figureSections.isEmpty()) null else documentOf(figureSections))
    }

    /** A page of floating shapes. The page is never empty, so that there is always one per slide. */
    private fun pageOf(elements: List<PptxElement>, width: Int, height: Int, originX: Int, originY: Int, first: Boolean): WordSection {
        val backdrop = WordShape(
            emptyList(), width, height, WHITE, null, WordShapeForm.Rectangle, 0, 0, 0, 0, floatingAt(0, 0),
        )
        val inlines: List<WordInline> = listOf(backdrop) + elements.map { it.draw(it.left - originX, it.top - originY) }
        return WordSection(
            page = WordPageGeometry(width, height, 0, 0, 0, 0, 0, 0),
            columns = 1,
            columnGapTwips = 0,
            start = if (first) WordSectionStart.Continuous else WordSectionStart.NextPage,
            titlePage = false,
            pageNumberStart = null,
            pageNumberFormat = null,
            headers = WordHeaderSet(),
            footers = WordHeaderSet(),
            blocks = listOf(WordParagraph(WordParagraphStyle(), WordRunStyle(halfPoints = 2), inlines, null)),
        )
    }

    private fun floatingAt(x: Int, y: Int, rotation: Float = 0f): WordAnchor =
        WordAnchor(false, WordWrap.None, "page", x, null, "page", y, null, rotation)

    // --- slides ----------------------------------------------------------------------------------

    /** What a shape on this slide is able to refer to. */
    private inner class Context(
        val relationships: Map<String, DocxRelationship>,
        val theme: WordTheme,
        val master: Master,
        val layout: Layout?,
        val slideNumber: Int,
        /** Whether this is the slide itself rather than what it is laid over, whose placeholders only lend. */
        val own: Boolean,
    )

    /** How a group's children map onto the slide. */
    private class Transform(val offsetX: Double, val offsetY: Double, val scaleX: Double, val scaleY: Double) {
        fun x(value: Long): Int = emuToTwips((offsetX + value * scaleX).toLong())

        fun y(value: Long): Int = emuToTwips((offsetY + value * scaleY).toLong())

        fun width(value: Long): Int = emuToTwips((value * scaleX).toLong())

        fun height(value: Long): Int = emuToTwips((value * scaleY).toLong())

        companion object {
            val IDENTITY = Transform(0.0, 0.0, 1.0, 1.0)
        }
    }

    /** Everything to draw on the slide, and the part of that which is the slide's own. */
    private fun readSlide(part: String, number: Int): Pair<List<PptxElement>, List<PptxElement>> {
        val root = pkg.xml(part) ?: throw ImportException(ImportFailure.Unreadable)
        val relationships = pkg.relationships(part)
        val layout = relationships.values.firstOrNull { it.type.endsWith("/slideLayout") }?.let { layoutAt(it.target) }
            ?: throw ImportException(ImportFailure.Unreadable)
        val master = layout.master
        val override = root.child(NS_P, "clrMapOvr")?.child(NS_A, "overrideClrMapping")
        val theme = if (override != null) remapped(master.theme, override) else master.theme
        val painted = ArrayList<PptxElement>()
        val slideData = root.child(NS_P, "cSld")

        val backgroundOwners = listOf(
            slideData?.child(NS_P, "bg") to relationships,
            layout.background to layout.relationships,
            master.background to master.relationships,
        )
        backgroundOwners.firstOrNull { it.first != null }?.let { (background, owner) ->
            backgroundOf(background!!, owner, theme)?.let { painted += it }
        }
        if (layout.showsMasterShapes && root.plain("showMasterSp") != "0") {
            walk(master.tree, Context(master.relationships, theme, master, null, number, false), Transform.IDENTITY, null, painted, 0)
        }
        if (root.plain("showMasterSp") != "0") {
            walk(layout.tree, Context(layout.relationships, theme, master, layout, number, false), Transform.IDENTITY, null, painted, 0)
        }
        val own = ArrayList<PptxElement>()
        walk(slideData?.child(NS_P, "spTree"), Context(relationships, theme, master, layout, number, true), Transform.IDENTITY, null, own, 0)
        return (painted + own) to own
    }

    private fun backgroundOf(background: Element, relationships: Map<String, DocxRelationship>, theme: WordTheme): PptxElement? {
        val properties = background.child(NS_P, "bgPr")
        val blip = properties?.child(NS_A, "blipFill")?.child(NS_A, "blip")?.attr(NS_R, "embed")?.let { relationships[it]?.target }
        if (blip != null) {
            return PptxElement(PptxRole.Furniture, 0, 0, slideWidth, slideHeight, null) { x, y ->
                WordPicture(blip, slideWidth, slideHeight, null, floatingAt(x, y))
            }
        }
        val reference = background.child(NS_P, "bgRef")
        val color = when {
            properties != null -> fillOf(properties, theme)
            reference != null -> theme.drawingColor(reference.childElements.firstOrNull())
            else -> null
        } ?: return null
        val gradient = gradientOf(properties?.child(NS_A, "gradFill"), theme)
        return PptxElement(PptxRole.Furniture, 0, 0, slideWidth, slideHeight, null) { x, y ->
            WordShape(emptyList(), slideWidth, slideHeight, color, null, WordShapeForm.Rectangle, 0, 0, 0, 0, floatingAt(x, y), gradient = gradient)
        }
    }

    private fun walk(tree: Element?, context: Context, transform: Transform, group: Int?, out: MutableList<PptxElement>, depth: Int) {
        tree ?: return
        if (depth > MAX_GROUP_DEPTH) return
        for (child in tree.childElements) {
            if (out.size >= MAX_SHAPES) return
            // One odd shape costs itself and not the slide.
            runCatching { element(child, context, transform, group, out, depth) }
        }
    }

    private fun element(child: Element, context: Context, transform: Transform, group: Int?, out: MutableList<PptxElement>, depth: Int) {
        when {
            child.isIn(NS_P, "sp") || child.isIn(NS_P, "cxnSp") -> shape(child, context, transform, group, NS_P)?.let { out += it }
            child.isIn(NS_P, "pic") -> picture(child, context, transform, group)?.let { out += it }
            child.isIn(NS_P, "graphicFrame") -> frame(child, context, transform, group, out)
            child.isIn(NS_P, "grpSp") -> groupOf(child, context, transform, group, out, depth)
            child.isIn(NS_MC, "AlternateContent") -> alternative(child)?.let { walk(it, context, transform, group, out, depth + 1) }
        }
    }

    /** The branch of an alternative that this can read, going by what its `Requires` names. */
    private fun alternative(content: Element): Element? {
        val choice = content.children(NS_MC, "Choice").firstOrNull { branch ->
            branch.plain("Requires").orEmpty().split(' ').all { it in UNDERSTOOD }
        }
        return choice ?: content.child(NS_MC, "Fallback")
    }

    private fun groupOf(group: Element, context: Context, parent: Transform, outerGroup: Int?, out: MutableList<PptxElement>, depth: Int) {
        val frame = group.child(NS_P, "grpSpPr")?.child(NS_A, "xfrm")
        val offset = frame?.child(NS_A, "off")
        val size = frame?.child(NS_A, "ext")
        val childOffset = frame?.child(NS_A, "chOff")
        val childSize = frame?.child(NS_A, "chExt")
        val scaleX = ratio(size?.emu("cx"), childSize?.emu("cx"))
        val scaleY = ratio(size?.emu("cy"), childSize?.emu("cy"))
        val transform = Transform(
            parent.offsetX + ((offset?.emu("x") ?: 0L) - (childOffset?.emu("x") ?: 0L) * scaleX) * parent.scaleX,
            parent.offsetY + ((offset?.emu("y") ?: 0L) - (childOffset?.emu("y") ?: 0L) * scaleY) * parent.scaleY,
            parent.scaleX * scaleX,
            parent.scaleY * scaleY,
        )
        walk(group, context, transform, outerGroup ?: ++groups, out, depth + 1)
    }

    private fun ratio(size: Long?, child: Long?): Double = if (size != null && child != null && child != 0L) size.toDouble() / child else 1.0

    private class Box(val left: Int, val top: Int, val width: Int, val height: Int, val rotation: Float, val flipH: Boolean, val flipV: Boolean)

    private fun boxOf(transform: Transform, frames: List<Element?>): Box? {
        val frame = frames.firstOrNull { it?.child(NS_A, "off") != null } ?: return null
        val offset = frame.child(NS_A, "off") ?: return null
        val size = frame.child(NS_A, "ext") ?: return null
        return Box(
            transform.x(offset.emu("x") ?: 0L),
            transform.y(offset.emu("y") ?: 0L),
            transform.width(size.emu("cx") ?: 0L),
            transform.height(size.emu("cy") ?: 0L),
            (frame.plain("rot")?.toFloatOrNull() ?: 0f) / ROTATION_UNITS,
            frame.plain("flipH") == "1",
            frame.plain("flipV") == "1",
        )
    }

    // --- shapes ----------------------------------------------------------------------------------

    private fun layoutPlaceholderFor(ph: Element, context: Context): Placeholder? {
        val layout = context.layout ?: return null
        val index = ph.plain("idx")?.toIntOrNull()
        val type = ph.plain("type")
        return layout.placeholders.firstOrNull { index != null && it.index == index }
            ?: layout.placeholders.firstOrNull { type != null && it.type == type }
    }

    private fun masterPlaceholderFor(type: String, context: Context): Placeholder? {
        val wanted = when (type) {
            "ctrTitle" -> "title"
            "subTitle", "obj", "chart", "tbl", "clipArt", "dgm", "media", "pic" -> "body"
            else -> type
        }
        return context.master.placeholders.firstOrNull { it.type == wanted }
    }

    private fun shape(shape: Element, context: Context, transform: Transform, group: Int?, namespace: String): PptxElement? {
        val properties = shape.child(namespace, "spPr")
        val nonVisual = shape.child(NS_P, "nvSpPr") ?: shape.child(NS_P, "nvCxnSpPr") ?: shape.child(NS_DSP, "nvSpPr")
        val ph = phOf(shape)
        // What a layout or master has for a placeholder is only ever lent to the slides using it.
        if (ph != null && !context.own) return null
        val isTextBox = nonVisual?.child(NS_P, "cNvSpPr")?.plain("txBox") == "1"
        val type = ph?.let { it.plain("type") ?: "obj" }
        val inLayout = ph?.let { layoutPlaceholderFor(it, context) }
        val inMaster = type?.let { masterPlaceholderFor(inLayout?.type ?: it, context) }
        val borrowed = listOfNotNull(inLayout?.shape, inMaster?.shape)
        val spPrChain = listOf(properties) + borrowed.map { it.child(NS_P, "spPr") }
        val box = boxOf(transform, spPrChain.map { it?.child(NS_A, "xfrm") }) ?: return null

        val style = shape.child(namespace, "style")
        val fill = spPrChain.filterNotNull().firstNotNullOfOrNull { fillResult(it, context) } ?: FillResult(fillRefOf(style, context.theme))
        val line = lineOf(spPrChain.filterNotNull(), style, context.theme)
        val body = shape.child(namespace, "txBody")
        val bodyProperties = listOf(body?.child(NS_A, "bodyPr")) + borrowed.map { it.child(NS_P, "txBody")?.child(NS_A, "bodyPr") }
        fun bodyAttribute(name: String): String? = bodyProperties.firstNotNullOfOrNull { it?.plain(name) }
        val autofit = bodyProperties.firstNotNullOfOrNull { it?.child(NS_A, "normAutofit") }
        val scope = TextScope(
            layers = textLayers(type, ph != null, inLayout, inMaster, context),
            own = body?.child(NS_A, "lstStyle"),
            baseColor = fontReferenceColor(style, context.theme),
            baseFont = fontReferenceName(style, context.theme),
            fontScale = (autofit?.plain("fontScale")?.toFloatOrNull() ?: FULL_SCALE) / FULL_SCALE,
            lineReduction = (autofit?.plain("lnSpcReduction")?.toFloatOrNull() ?: 0f) / FULL_SCALE,
            isTitle = type == "title" || type == "ctrTitle",
            isSubtitle = type == "subTitle",
        )
        val paragraphs = body?.let { paragraphsOf(it, scope, context) }.orEmpty()
        val hasText = paragraphs.any { it.inlines.isNotEmpty() }
        val geometry = properties ?: borrowed.firstNotNullOfOrNull { it.child(NS_P, "spPr") }
        val preset = geometry?.child(NS_A, "prstGeom")?.plain("prst") ?: "rect"
        val custom = geometry?.child(NS_A, "custGeom")
        val outline: List<WordPath> = when {
            custom != null -> customOutline(custom, box.width.toFloat(), box.height.toFloat())
            else -> listOfNotNull(presetOutline(preset, box.width.toFloat(), box.height.toFloat(), adjustments(geometry)))
        }
        val form = if (preset in ELLIPSES) WordShapeForm.Ellipse else WordShapeForm.Rectangle
        val isConnector = shape.isIn(NS_P, "cxnSp") || preset == "line" || preset.endsWith("Connector1") || "Connector" in preset
        val picture = fill.picture
        val invisible = fill.color == null && picture == null && line == null
        if (invisible && !hasText) return null
        val ends = spPrChain.filterNotNull().firstNotNullOfOrNull { it.child(NS_A, "ln") }

        fun bodyInset(name: String, fallback: Long): Int = emuToTwips(bodyAttribute(name)?.toLongOrNull() ?: fallback)
        val draw: (Int, Int) -> WordBoxed = { x, y ->
            val anchor = floatingAt(x, y, box.rotation)
            if (picture != null && !hasText) {
                WordPicture(picture, box.width, box.height, null, anchor)
            } else {
                WordShape(
                    blocks = paragraphs.filter { it.inlines.isNotEmpty() || paragraphs.size > 1 },
                    widthTwips = box.width,
                    heightTwips = box.height,
                    fillArgb = fill.color,
                    line = line,
                    form = form,
                    insetLeftTwips = bodyInset("lIns", DEFAULT_SIDE_INSET),
                    insetTopTwips = bodyInset("tIns", DEFAULT_TOP_INSET),
                    insetRightTwips = bodyInset("rIns", DEFAULT_SIDE_INSET),
                    insetBottomTwips = bodyInset("bIns", DEFAULT_TOP_INSET),
                    anchor = anchor,
                    gradient = fill.gradient,
                    textAlign = when (bodyAttribute("anchor")) {
                        "ctr" -> WordCellAlign.Center
                        "b" -> WordCellAlign.Bottom
                        else -> WordCellAlign.Top
                    },
                    outline = outline,
                    flipHorizontal = box.flipH,
                    flipVertical = box.flipV,
                    arrowAtStart = arrowOf(ends, "headEnd"),
                    arrowAtEnd = arrowOf(ends, "tailEnd"),
                    noWrap = bodyAttribute("wrap") == "none",
                )
            }
        }
        val role = when {
            type == "title" || type == "ctrTitle" -> PptxRole.Title
            type == "dt" || type == "ftr" || type == "sldNum" -> PptxRole.Furniture
            type != null -> PptxRole.Body
            isTextBox && fill.color == null && line == null -> PptxRole.TextBox
            else -> PptxRole.Drawing
        }
        return PptxElement(
            role = role,
            left = box.left,
            top = box.top,
            right = box.left + box.width,
            bottom = box.top + box.height,
            group = group,
            paragraphs = if (hasText) paragraphs.filter { it.inlines.isNotEmpty() } else emptyList(),
            connector = isConnector,
            draw = draw,
        )
    }

    private fun arrowOf(line: Element?, end: String): Boolean = line?.child(NS_A, end)?.plain("type").let { it != null && it != "none" }

    private fun adjustments(geometry: Element?): Map<String, Float> =
        geometry?.child(NS_A, "prstGeom")?.child(NS_A, "avLst")?.children(NS_A, "gd").orEmpty().mapNotNull { guide ->
            val value = guide.plain("fmla")?.removePrefix("val ")?.toFloatOrNull() ?: return@mapNotNull null
            guide.plain("name").orEmpty() to value
        }.toMap()

    private class FillResult(val color: Int? = null, val picture: String? = null, val gradient: WordGradient? = null)

    /** The fill a shape's properties set, or null when they leave that to something else. */
    private fun fillResult(properties: Element, context: Context): FillResult? {
        if (properties.child(NS_A, "noFill") != null) return FillResult()
        properties.child(NS_A, "blipFill")?.let { blip ->
            return FillResult(picture = blip.child(NS_A, "blip")?.attr(NS_R, "embed")?.let { id -> context.relationships[id]?.target })
        }
        return fillOf(properties, context.theme)?.let { FillResult(it, gradient = gradientOf(properties.child(NS_A, "gradFill"), context.theme)) }
    }

    private fun gradientOf(fill: Element?, theme: WordTheme): WordGradient? {
        fill ?: return null
        val stops = fill.child(NS_A, "gsLst")?.children(NS_A, "gs").orEmpty()
            .mapNotNull { stop ->
                val color = theme.drawingColor(stop.childElements.firstOrNull()) ?: return@mapNotNull null
                ((stop.plain("pos")?.toFloatOrNull() ?: 0f) / FULL_SCALE) to color
            }
            .sortedBy { it.first }
        if (stops.size < 2) return null
        val path = fill.child(NS_A, "path")
        val box = path?.child(NS_A, "fillToRect")
        fun side(name: String): Float = (box?.plain(name)?.toFloatOrNull() ?: 0f) / FULL_SCALE
        return WordGradient(
            colors = IntArray(stops.size) { stops[it].second },
            positions = FloatArray(stops.size) { stops[it].first },
            angleDegrees = (fill.child(NS_A, "lin")?.plain("ang")?.toFloatOrNull() ?: 0f) / ROTATION_UNITS,
            radial = path != null,
            centerX = (side("l") + 1f - side("r")) / 2f,
            centerY = (side("t") + 1f - side("b")) / 2f,
        )
    }

    private fun fillOf(properties: Element, theme: WordTheme): Int? {
        properties.child(NS_A, "solidFill")?.let { return theme.drawingColor(it.childElements.firstOrNull()) }
        properties.child(NS_A, "gradFill")?.let { gradient ->
            val stops = gradient.child(NS_A, "gsLst")?.children(NS_A, "gs").orEmpty()
            return theme.drawingColor(stops.getOrNull(stops.size / 2)?.childElements?.firstOrNull())
        }
        properties.child(NS_A, "pattFill")?.let { return theme.drawingColor(it.child(NS_A, "fgClr")?.childElements?.firstOrNull()) }
        return null
    }

    private fun fillRefOf(style: Element?, theme: WordTheme): Int? {
        val reference = style?.child(NS_A, "fillRef") ?: return null
        if ((reference.plain("idx")?.toIntOrNull() ?: 0) == 0) return null
        return theme.drawingColor(reference.childElements.firstOrNull())
    }

    private fun lineOf(chain: List<Element>, style: Element?, theme: WordTheme): WordBorder? {
        val line = chain.firstNotNullOfOrNull { it.child(NS_A, "ln") }
        if (line?.child(NS_A, "noFill") != null) return null
        val explicit = line?.child(NS_A, "solidFill")?.childElements?.firstOrNull()
        val reference = style?.child(NS_A, "lnRef")?.takeIf { (it.plain("idx")?.toIntOrNull() ?: 0) > 0 }
        val color = theme.drawingColor(explicit ?: reference?.childElements?.firstOrNull()) ?: return null
        return WordBorder(dashOf(line), ((line?.emu("w") ?: DEFAULT_LINE_EMU) * 8 / EMU_PER_POINT).toInt().coerceAtLeast(1), color)
    }

    private fun dashOf(line: Element?): WordBorderStyle = when (line?.child(NS_A, "prstDash")?.plain("val")) {
        "dash", "lgDash", "sysDash", "dashDot", "lgDashDot", "lgDashDotDot", "sysDashDot", "sysDashDotDot" -> WordBorderStyle.Dashed
        "dot", "sysDot" -> WordBorderStyle.Dotted
        else -> WordBorderStyle.Single
    }

    private fun fontReferenceColor(style: Element?, theme: WordTheme): Int? =
        style?.child(NS_A, "fontRef")?.childElements?.firstOrNull()?.let(theme::drawingColor)

    private fun fontReferenceName(style: Element?, theme: WordTheme): String? = when (style?.child(NS_A, "fontRef")?.plain("idx")) {
        "major" -> theme.majorFont
        "minor" -> theme.minorFont
        else -> null
    }

    private fun picture(picture: Element, context: Context, transform: Transform, group: Int?): PptxElement? {
        val ph = picture.child(NS_P, "nvPicPr")?.child(NS_P, "nvPr")?.child(NS_P, "ph")
        val inLayout = ph?.let { layoutPlaceholderFor(it, context) }
        val properties = picture.child(NS_P, "spPr")
        val box = boxOf(transform, listOf(properties?.child(NS_A, "xfrm"), inLayout?.shape?.child(NS_P, "spPr")?.child(NS_A, "xfrm"))) ?: return null
        val fill = picture.child(NS_P, "blipFill")
        val part = fill?.child(NS_A, "blip")?.attr(NS_R, "embed")?.let { context.relationships[it]?.target }
        val crop = fill?.child(NS_A, "srcRect")?.let { rect ->
            fun side(name: String): Float = (rect.plain(name)?.toFloatOrNull() ?: 0f) / FULL_SCALE
            WordCrop(side("l"), side("t"), side("r"), side("b"))
        }
        val shown = part?.takeUnless(::cannotBeDrawn)
        val draw: (Int, Int) -> WordBoxed = { x, y ->
            val anchor = floatingAt(x, y, box.rotation)
            if (shown != null) WordPicture(shown, box.width, box.height, crop, anchor) else WordPlaceholder(box.width, box.height, anchor)
        }
        return PptxElement(
            role = PptxRole.Picture,
            left = box.left,
            top = box.top,
            right = box.left + box.width,
            bottom = box.top + box.height,
            group = group,
            picture = shown?.let { WordPicture(it, box.width, box.height, crop, null) },
            draw = draw,
        )
    }

    /** Vector pictures from Windows, which Android has no decoder for. */
    private fun cannotBeDrawn(part: String): Boolean = part.substringAfterLast('.').lowercase() in UNDRAWABLE_PICTURES

    private fun frame(frame: Element, context: Context, transform: Transform, group: Int?, out: MutableList<PptxElement>) {
        val box = boxOf(transform, listOf(frame.child(NS_P, "xfrm"))) ?: return
        val data = frame.child(NS_A, "graphic")?.child(NS_A, "graphicData") ?: return
        val table = data.child(NS_A, "tbl")
        val diagram = data.child(NS_DGM, "relIds")
        when {
            table != null -> out += tableElement(table, box, context, group)
            diagram != null -> diagramInto(diagram, box, context, transform, group ?: ++groups, out)
            else -> {
                // Whatever else a frame holds keeps its place on the slide and has no picture to show.
                data.child(NS_MC, "AlternateContent")?.let { alternative(it) }?.child(NS_P, "pic")?.let { fallback ->
                    picture(fallback, context, transform, group)?.let { out += it }
                    return
                }
                data.childElements.firstOrNull { it.localName == "oleObj" }?.child(NS_P, "pic")?.let { fallback ->
                    picture(fallback, context, transform, group)?.let { out += it }
                    return
                }
                out += missingElement(box, group)
            }
        }
    }

    private fun missingElement(box: Box, group: Int?): PptxElement =
        PptxElement(PptxRole.Drawing, box.left, box.top, box.left + box.width, box.top + box.height, group, missing = true) { x, y ->
            WordPlaceholder(box.width, box.height, floatingAt(x, y))
        }

    /** SmartArt keeps a ready-made drawing next to its data, in the same terms as a slide's shapes. */
    private fun diagramInto(diagram: Element, box: Box, context: Context, transform: Transform, group: Int, out: MutableList<PptxElement>) {
        val dataId = diagram.attr(NS_R, "dm")
        val dataPart = context.relationships[dataId]?.target?.let(pkg::xml)
        val drawingId = (dataPart?.getElementsByTagNameNS(NS_DSP, "dataModelExt")?.item(0) as? Element)?.plain("relId")
        val drawing = (drawingId?.let { context.relationships[it]?.target } ?: context.relationships.values.firstOrNull { it.type.endsWith("/diagramDrawing") }?.target)
            ?.let(pkg::xml)
        val tree = drawing?.child(NS_DSP, "spTree")
        if (tree == null) {
            out += missingElement(box, group)
            return
        }
        // Its shapes are placed relative to the frame.
        val inner = Transform(transform.offsetX + box.left * EMU_PER_TWIP, transform.offsetY + box.top * EMU_PER_TWIP, transform.scaleX, transform.scaleY)
        for (child in tree.children(NS_DSP, "sp")) {
            runCatching { shape(child, context, inner, group, NS_DSP) }.getOrNull()?.let { element ->
                out += PptxElement(PptxRole.Drawing, element.left, element.top, element.right, element.bottom, group, element.paragraphs, connector = false, draw = element.draw)
            }
        }
    }

    // --- text ------------------------------------------------------------------------------------

    private class TextScope(
        /** List-style containers this shape inherits, lowest priority first. */
        val layers: List<Element>,
        /** The shape's own list style. */
        val own: Element?,
        val baseColor: Int?,
        val baseFont: String?,
        val fontScale: Float,
        val lineReduction: Float,
        val isTitle: Boolean,
        val isSubtitle: Boolean,
    )

    private fun textLayers(type: String?, isPlaceholder: Boolean, inLayout: Placeholder?, inMaster: Placeholder?, context: Context): List<Element> {
        val masterStyles = context.master.textStyles
        val styleName = when {
            !isPlaceholder -> "otherStyle"
            type == "title" || type == "ctrTitle" -> "titleStyle"
            type == "dt" || type == "ftr" || type == "sldNum" -> "otherStyle"
            else -> "bodyStyle"
        }
        return listOfNotNull(
            defaultTextStyle.takeIf { !isPlaceholder || styleName == "otherStyle" },
            masterStyles?.child(NS_P, styleName),
            inMaster?.shape?.child(NS_P, "txBody")?.child(NS_A, "lstStyle"),
            inLayout?.shape?.child(NS_P, "txBody")?.child(NS_A, "lstStyle"),
        )
    }

    private class ParagraphLook(
        var align: WordAlign = WordAlign.Left,
        var left: Int = 0,
        var first: Int = 0,
        var line: Element? = null,
        var before: Element? = null,
        var after: Element? = null,
        var bullet: Element? = null,
        var bulletFont: String? = null,
        var bulletColor: Int? = null,
        var run: WordRunStyle = WordRunStyle(halfPoints = DEFAULT_HALF_POINTS),
    )

    private fun applyParagraphProperties(look: ParagraphLook, properties: Element?, context: Context) {
        properties ?: return
        properties.plain("algn")?.let { look.align = alignOf(it) }
        properties.emu("marL")?.let { look.left = emuToTwips(it) }
        properties.emu("indent")?.let { look.first = emuToTwips(it) }
        properties.child(NS_A, "lnSpc")?.let { look.line = it }
        properties.child(NS_A, "spcBef")?.let { look.before = it }
        properties.child(NS_A, "spcAft")?.let { look.after = it }
        for (name in BULLET_KINDS) properties.child(NS_A, name)?.let { look.bullet = it }
        properties.child(NS_A, "buFont")?.plain("typeface")?.let { look.bulletFont = it }
        properties.child(NS_A, "buClr")?.childElements?.firstOrNull()?.let { look.bulletColor = context.theme.drawingColor(it) }
        look.run = applyRunProperties(look.run, properties.child(NS_A, "defRPr"), context)
    }

    private fun alignOf(value: String): WordAlign = when (value) {
        "ctr" -> WordAlign.Center
        "r" -> WordAlign.Right
        "just", "justLow", "dist", "thaiDist" -> WordAlign.Justify
        else -> WordAlign.Left
    }

    private fun applyRunProperties(run: WordRunStyle, properties: Element?, context: Context): WordRunStyle {
        properties ?: return run
        var out = run
        properties.plain("sz")?.toIntOrNull()?.let { out = out.copy(halfPoints = it / HUNDREDTHS_PER_HALF_POINT) }
        properties.plain("b")?.let { out = out.copy(bold = it == "1" || it == "true") }
        properties.plain("i")?.let { out = out.copy(italic = it == "1" || it == "true") }
        properties.plain("u")?.let { out = out.copy(underline = it != "none") }
        properties.plain("strike")?.let { out = out.copy(strike = it != "noStrike") }
        properties.plain("baseline")?.toIntOrNull()?.let { value ->
            out = out.copy(script = if (value > 0) WordScript.Super else if (value < 0) WordScript.Sub else WordScript.Baseline)
        }
        properties.plain("spc")?.toIntOrNull()?.let { out = out.copy(spacingTwips = it / 5) }
        properties.child(NS_A, "solidFill")?.childElements?.firstOrNull()?.let { color -> context.theme.drawingColor(color)?.let { out = out.copy(colorArgb = it) } }
        properties.child(NS_A, "highlight")?.childElements?.firstOrNull()?.let { color -> context.theme.drawingColor(color)?.let { out = out.copy(highlightArgb = it) } }
        properties.child(NS_A, "latin")?.plain("typeface")?.let { face -> out = out.copy(font = fontName(face, context)) }
        return out
    }

    private fun fontName(face: String, context: Context): String? = when (face) {
        "+mj-lt", "+mj-ea", "+mj-cs" -> context.theme.majorFont
        "+mn-lt", "+mn-ea", "+mn-cs" -> context.theme.minorFont
        else -> face
    }

    private fun paragraphsOf(body: Element, scope: TextScope, context: Context): List<WordParagraph> {
        val counters = IntArray(MAX_LEVELS)
        val numbered = BooleanArray(MAX_LEVELS)
        return body.children(NS_A, "p").map { paragraph ->
            val properties = paragraph.child(NS_A, "pPr")
            val level = (properties?.plain("lvl")?.toIntOrNull() ?: 0).coerceIn(0, MAX_LEVELS - 1)
            val look = ParagraphLook()
            look.run = look.run.copy(colorArgb = context.theme.color("tx1"), font = context.theme.minorFont)
            for (layer in scope.layers) applyParagraphProperties(look, layer.child(NS_A, "lvl${level + 1}pPr"), context)
            // A shape's style colours its text over the defaults, and what it says itself goes over that.
            look.run = look.run.copy(colorArgb = scope.baseColor ?: look.run.colorArgb, font = scope.baseFont ?: look.run.font)
            applyParagraphProperties(look, scope.own?.child(NS_A, "lvl${level + 1}pPr"), context)
            applyParagraphProperties(look, properties, context)
            val runBase = look.run.copy(halfPoints = (look.run.halfPoints * scope.fontScale).roundToInt().coerceAtLeast(MIN_HALF_POINTS))
            val inlines = inlinesOf(paragraph, runBase, scope, context)
            val end = paragraph.child(NS_A, "endParaRPr")
            val mark = applyRunProperties(runBase, end, context).let { it.copy(halfPoints = (it.halfPoints.coerceAtMost(runBase.halfPoints)).coerceAtLeast(MIN_HALF_POINTS)) }
            val firstRun = (inlines.firstOrNull { it is WordText } as? WordText)?.style ?: mark
            val label = labelOf(look, level, counters, numbered, inlines.isNotEmpty(), firstRun)
            WordParagraph(
                style = WordParagraphStyle(
                    name = if (scope.isSubtitle) "Subtitle" else "",
                    outlineLevel = if (scope.isTitle) 0 else null,
                    align = look.align,
                    leftTwips = look.left,
                    firstLineTwips = look.first,
                    beforeTwips = spacing(look.before, firstRun),
                    afterTwips = spacing(look.after, firstRun),
                    lineValue = lineValue(look.line, scope),
                    lineRule = if (look.line?.child(NS_A, "spcPts") != null) WordLineRule.Exact else WordLineRule.Auto,
                ),
                markStyle = mark,
                inlines = inlines,
                label = label,
            )
        }
    }

    private fun lineValue(line: Element?, scope: TextScope): Int {
        val points = line?.child(NS_A, "spcPts")?.plain("val")?.toFloatOrNull()
        if (points != null) return (points / HUNDREDTHS_PER_TWIP).roundToInt()
        val percent = line?.child(NS_A, "spcPct")?.plain("val")?.toFloatOrNull()?.div(FULL_SCALE) ?: 1f
        return ((percent - scope.lineReduction).coerceAtLeast(MIN_LINE_SHARE) * SINGLE_LINE).roundToInt()
    }

    private fun spacing(element: Element?, run: WordRunStyle): Int {
        val points = element?.child(NS_A, "spcPts")?.plain("val")?.toFloatOrNull()
        if (points != null) return (points / HUNDREDTHS_PER_TWIP).roundToInt()
        val percent = element?.child(NS_A, "spcPct")?.plain("val")?.toFloatOrNull()?.div(FULL_SCALE) ?: return 0
        return (run.halfPoints * TWIPS_PER_HALF_POINT * percent * LINE_HEIGHT).roundToInt()
    }

    private fun labelOf(look: ParagraphLook, level: Int, counters: IntArray, numbered: BooleanArray, hasText: Boolean, run: WordRunStyle): WordListLabel? {
        val bullet = look.bullet
        val style = run.copy(colorArgb = look.bulletColor ?: run.colorArgb, underline = false)
        val isCounted = bullet?.localName == "buAutoNum" && hasText
        if (!isCounted) {
            numbered[level] = false
        }
        for (deeper in level + 1 until MAX_LEVELS) numbered[deeper] = false
        if (!hasText || bullet == null) return null
        return when (bullet.localName) {
            "buChar" -> {
                val font = look.bulletFont
                val text = bullet.plain("char").orEmpty().ifEmpty { "•" }
                WordListLabel(decodeSymbolFont(font, text) ?: text, if (isSymbolFont(font)) style else style.copy(font = font), true, false, true, level)
            }
            "buBlip" -> WordListLabel("•", style, true, false, true, level)
            "buAutoNum" -> {
                val start = bullet.plain("startAt")?.toIntOrNull() ?: 1
                counters[level] = if (numbered[level]) counters[level] + 1 else start
                numbered[level] = true
                WordListLabel(autoNumber(bullet.plain("type").orEmpty(), counters[level]), style, true, false, false, level)
            }
            else -> null
        }
    }

    private fun autoNumber(type: String, value: Int): String {
        val format = when {
            type.startsWith("romanUc") -> "upperRoman"
            type.startsWith("romanLc") -> "lowerRoman"
            type.startsWith("alphaUc") -> "upperLetter"
            type.startsWith("alphaLc") -> "lowerLetter"
            else -> "decimal"
        }
        val number = formatNumber(value, format)
        return when {
            type.endsWith("ParenBoth") -> "($number)"
            type.endsWith("ParenR") -> "$number)"
            type.endsWith("Period") -> "$number."
            type.endsWith("Minus") -> "- $number -"
            else -> number
        }
    }

    private fun inlinesOf(paragraph: Element, base: WordRunStyle, scope: TextScope, context: Context): List<WordInline> {
        val out = ArrayList<WordInline>()
        fun addText(value: String, style: WordRunStyle, link: String?) {
            val pieces = value.split('\t')
            pieces.forEachIndexed { index, piece ->
                if (index > 0) out += WordTab(style)
                if (piece.isNotEmpty()) out += WordText(piece, style, link)
            }
        }
        fun runStyleOf(properties: Element?): WordRunStyle {
            val applied = applyRunProperties(base, properties, context)
            return if (properties?.plain("sz") != null) applied.copy(halfPoints = (applied.halfPoints * scope.fontScale).roundToInt().coerceAtLeast(MIN_HALF_POINTS)) else applied
        }
        fun linkOf(properties: Element?): String? =
            properties?.child(NS_A, "hlinkClick")?.attr(NS_R, "id")?.let { context.relationships[it] }?.takeIf { it.external }?.target?.let(::normalizedLinkUrl)
        fun visit(element: Element) {
            when {
                element.isIn(NS_A, "r") -> {
                    val properties = element.child(NS_A, "rPr")
                    val style = runStyleOf(properties)
                    var text = element.child(NS_A, "t")?.textContent.orEmpty()
                    if (properties?.plain("cap") == "all") text = text.uppercase()
                    addText(text, style, linkOf(properties))
                }
                element.isIn(NS_A, "br") -> out += WordLineBreak(runStyleOf(element.child(NS_A, "rPr")))
                element.isIn(NS_A, "fld") -> {
                    val value = if (element.plain("type") == "slidenum") context.slideNumber.toString() else element.child(NS_A, "t")?.textContent.orEmpty()
                    addText(value, runStyleOf(element.child(NS_A, "rPr")), null)
                }
                element.namespaceURI == NS_A14 && element.localName == "m" -> equationOf(element, base)?.let { out += it }
                element.isIn(NS_MC, "AlternateContent") -> alternative(element)?.childElements?.forEach(::visit)
            }
        }
        paragraph.childElements.forEach(::visit)
        return out
    }

    private fun equationOf(holder: Element, style: WordRunStyle): WordEquation? {
        val math = holder.child(NS_M, "oMathPara")?.child(NS_M, "oMath") ?: holder.child(NS_M, "oMath") ?: return null
        return WordEquation(ommlToLatex(math), ommlPlainText(math), style, display = holder.child(NS_M, "oMathPara") != null)
    }

    // --- tables ----------------------------------------------------------------------------------

    private class CellLook(var fill: Int? = null, var bold: Boolean = false, var italic: Boolean = false, var color: Int? = null)

    private fun tableElement(table: Element, box: Box, context: Context, group: Int?): PptxElement {
        val properties = table.child(NS_A, "tblPr")
        val style = properties?.child(NS_A, "tableStyleId")?.textContent?.let(tableStyles::get)
        val grid = table.child(NS_A, "tblGrid")?.children(NS_A, "gridCol").orEmpty().map { emuToTwips(it.emu("w") ?: 0L) }
        val rows = table.children(NS_A, "tr")
        fun flag(name: String): Boolean = properties?.plain(name) == "1"
        val hasHeader = flag("firstRow")
        val hasLastRow = flag("lastRow")
        val firstColumn = flag("firstCol")
        val lastColumn = flag("lastCol")
        val bandRows = flag("bandRow")
        val bandColumns = flag("bandCol")

        /** The table-style parts that apply to a cell, in the order they override each other. */
        fun partsFor(row: Int, column: Int): List<Element> {
            style ?: return emptyList()
            val names = ArrayList<String>()
            names += "wholeTbl"
            val inBody = row > (if (hasHeader) 0 else -1) && !(hasLastRow && row == rows.lastIndex)
            if (bandRows && inBody) names += if ((row - if (hasHeader) 1 else 0) % 2 == 0) "band1H" else "band2H"
            if (bandColumns) names += if ((column - if (firstColumn) 1 else 0) % 2 == 0) "band1V" else "band2V"
            if (firstColumn && column == 0) names += "firstCol"
            if (lastColumn && column == grid.lastIndex) names += "lastCol"
            if (hasLastRow && row == rows.lastIndex) names += "lastRow"
            if (hasHeader && row == 0) names += "firstRow"
            return names.mapNotNull { style.child(NS_A, it) }
        }

        var sizes = 0f
        var sizeCount = 0
        val builtRows = rows.mapIndexed { rowIndex, row ->
            val cells = ArrayList<WordCell>()
            row.children(NS_A, "tc").forEachIndexed { columnIndex, cell ->
                if (cell.plain("hMerge") == "1") return@forEachIndexed
                val parts = partsFor(rowIndex, columnIndex)
                val look = CellLook()
                for (part in parts) applyCellPart(look, part, context.theme)
                val tcPr = cell.child(NS_A, "tcPr")
                tcPr?.let { direct ->
                    if (direct.child(NS_A, "noFill") != null) look.fill = null
                    fillOf(direct, context.theme)?.let { look.fill = it }
                }
                val span = cell.plain("gridSpan")?.toIntOrNull() ?: 1
                val body = cell.child(NS_A, "txBody")
                val scope = TextScope(
                    layers = listOfNotNull(defaultTextStyle, context.master.textStyles?.child(NS_P, "otherStyle")),
                    own = body?.child(NS_A, "lstStyle"),
                    baseColor = look.color,
                    baseFont = null,
                    fontScale = 1f,
                    lineReduction = 0f,
                    isTitle = false,
                    isSubtitle = false,
                )
                val paragraphs = body?.let { paragraphsOf(it, scope, context) }.orEmpty().map { paragraph ->
                    val inlines = paragraph.inlines.map { inline ->
                        if (inline is WordText && (look.bold || look.italic)) {
                            WordText(inline.text, inline.style.copy(bold = inline.style.bold || look.bold, italic = inline.style.italic || look.italic), inline.link)
                        } else {
                            inline
                        }
                    }
                    inlines.filterIsInstance<WordText>().forEach {
                        sizes += it.style.halfPoints / 2f
                        sizeCount++
                    }
                    WordParagraph(paragraph.style, paragraph.markStyle, inlines, paragraph.label)
                }
                val merge = when {
                    cell.plain("vMerge") == "1" -> WordVerticalMerge.Continue
                    (cell.plain("rowSpan")?.toIntOrNull() ?: 1) > 1 -> WordVerticalMerge.Restart
                    else -> WordVerticalMerge.None
                }
                cells += WordCell(
                    blocks = if (merge == WordVerticalMerge.Continue) emptyList() else paragraphs,
                    gridSpan = span.coerceAtLeast(1),
                    verticalMerge = merge,
                    widthTwips = (columnIndex until columnIndex + span).sumOf { grid.getOrElse(it) { 0 } }.takeIf { it > 0 },
                    shadingArgb = look.fill,
                    borders = cellBorders(tcPr, parts, rowIndex, columnIndex, rows.size, grid.size, context.theme),
                    marginLeftTwips = emuToTwips(tcPr?.emu("marL") ?: DEFAULT_SIDE_INSET),
                    marginTopTwips = emuToTwips(tcPr?.emu("marT") ?: DEFAULT_TOP_INSET),
                    marginRightTwips = emuToTwips(tcPr?.emu("marR") ?: DEFAULT_SIDE_INSET),
                    marginBottomTwips = emuToTwips(tcPr?.emu("marB") ?: DEFAULT_TOP_INSET),
                    align = when (tcPr?.plain("anchor")) {
                        "ctr" -> WordCellAlign.Center
                        "b" -> WordCellAlign.Bottom
                        else -> WordCellAlign.Top
                    },
                )
            }
            WordRow(
                cells = cells,
                heightTwips = row.emu("h")?.let(::emuToTwips)?.takeIf { it > 0 },
                exactHeight = false,
                isHeader = hasHeader && rowIndex == 0,
                cantSplit = false,
                gridBefore = 0,
                gridAfter = 0,
            )
        }
        val model = WordTable(grid.toIntArray(), builtRows, null, 0, WordAlign.Left, null, null)
        val em = if (sizeCount > 0) sizes / sizeCount else DEFAULT_TABLE_EM
        return PptxElement(
            role = PptxRole.Table,
            left = box.left,
            top = box.top,
            right = box.left + box.width,
            bottom = box.top + box.height,
            group = group,
            table = withHighlights(model),
            tableEm = em,
            draw = { x, y ->
                WordShape(listOf<WordBlock>(model), box.width, box.height, null, null, WordShapeForm.Rectangle, 0, 0, 0, 0, floatingAt(x, y))
            },
        )
    }

    private fun applyCellPart(look: CellLook, part: Element, theme: WordTheme) {
        part.child(NS_A, "tcTxStyle")?.let { text ->
            text.plain("b")?.let { look.bold = it == "on" }
            text.plain("i")?.let { look.italic = it == "on" }
            // The font reference's colour is only for when no colour is named beside it.
            text.child(NS_A, "fontRef")?.childElements?.firstOrNull()?.let { look.color = theme.drawingColor(it) }
            text.childElements.firstOrNull { it.localName in COLOR_KINDS }?.let { look.color = theme.drawingColor(it) }
        }
        val style = part.child(NS_A, "tcStyle") ?: return
        val fill = style.child(NS_A, "fill")
        if (fill?.child(NS_A, "noFill") != null) look.fill = null
        fill?.let { fillOf(it, theme) }?.let { look.fill = it }
    }

    private fun cellBorders(tcPr: Element?, parts: List<Element>, row: Int, column: Int, rows: Int, columns: Int, theme: WordTheme): WordBorders {
        fun styled(name: String): WordBorder? = parts.asReversed().firstNotNullOfOrNull { part ->
            part.child(NS_A, "tcStyle")?.child(NS_A, "tcBdr")?.child(NS_A, name)?.let { border -> borderFrom(border.child(NS_A, "ln"), border.child(NS_A, "lnRef"), theme) }
        }
        fun side(direct: String, outer: String, inner: String, atEdge: Boolean): WordBorder? {
            val line = tcPr?.child(NS_A, direct)
            if (line != null) {
                return if (line.child(NS_A, "noFill") != null) WordBorder(WordBorderStyle.None, 0, null) else borderFrom(line, null, theme)
            }
            return styled(if (atEdge) outer else inner)
        }
        return WordBorders(
            top = side("lnT", "top", "insideH", row == 0),
            left = side("lnL", "left", "insideV", column == 0),
            bottom = side("lnB", "bottom", "insideH", row == rows - 1),
            right = side("lnR", "right", "insideV", column == columns - 1),
        )
    }

    private fun borderFrom(line: Element?, reference: Element?, theme: WordTheme): WordBorder? {
        if (line?.child(NS_A, "noFill") != null) return WordBorder(WordBorderStyle.None, 0, null)
        val color = theme.drawingColor(line?.child(NS_A, "solidFill")?.childElements?.firstOrNull() ?: reference?.childElements?.firstOrNull()) ?: return null
        val width = line?.emu("w") ?: DEFAULT_LINE_EMU
        return WordBorder(dashOf(line), (width * 8 / EMU_PER_POINT).toInt().coerceAtLeast(1), color)
    }

    /**
     * The table as an object. The stored table has one fill for all its cells, so a cell that stands
     * out in a colour keeps it as a highlight on its text. Not a dark one, whose letters are pale and
     * are coming in as the page's ink.
     */
    private fun withHighlights(table: WordTable): WordTable {
        val rows = table.rows.map { row ->
            val cells = row.cells.map { cell ->
                val fill = cell.shadingArgb?.takeIf { it.isChromatic() && !isDark(it) }
                val blocks = if (fill == null) cell.blocks else cell.blocks.map { block ->
                    if (block !is WordParagraph) return@map block
                    WordParagraph(
                        block.style,
                        block.markStyle,
                        block.inlines.map { if (it is WordText) WordText(it.text, it.style.copy(highlightArgb = fill), it.link) else it },
                        block.label,
                    )
                }
                WordCell(
                    blocks, cell.gridSpan, cell.verticalMerge, cell.widthTwips, cell.shadingArgb, cell.borders,
                    cell.marginLeftTwips, cell.marginTopTwips, cell.marginRightTwips, cell.marginBottomTwips, cell.align,
                )
            }
            WordRow(cells, row.heightTwips, row.exactHeight, row.isHeader, row.cantSplit, row.gridBefore, row.gridAfter)
        }
        return WordTable(table.gridTwips, rows, table.borders, table.indentTwips, table.align, table.widthTwips, table.widthPercent)
    }

    private fun isDark(argb: Int): Boolean =
        (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000 < DARK_LUMINANCE

    private companion object {
        const val MAX_SLIDES = 5000
        const val MAX_SHAPES = 20_000
        const val MAX_GROUP_DEPTH = 32
        const val MAX_LEVELS = 9
        const val DEFAULT_SLIDE_WIDTH = 9_144_000L
        const val DEFAULT_SLIDE_HEIGHT = 6_858_000L
        const val DEFAULT_SIDE_INSET = 91_440L
        const val DEFAULT_TOP_INSET = 45_720L
        const val DEFAULT_LINE_EMU = 9_525L
        const val EMU_PER_POINT = 12_700L
        const val EMU_PER_TWIP = 635.0
        const val ROTATION_UNITS = 60_000f
        const val FULL_SCALE = 100_000f
        const val DEFAULT_HALF_POINTS = 36
        const val MIN_HALF_POINTS = 2
        const val HUNDREDTHS_PER_HALF_POINT = 50
        const val HUNDREDTHS_PER_TWIP = 5f
        const val TWIPS_PER_HALF_POINT = 10
        const val SINGLE_LINE = 240
        const val MIN_LINE_SHARE = 0.5f
        const val LINE_HEIGHT = 1.2f
        const val DEFAULT_TABLE_EM = 18f
        const val DARK_LUMINANCE = 150
        const val WHITE = -1

        val BULLET_KINDS = listOf("buNone", "buAutoNum", "buChar", "buBlip")
        val COLOR_KINDS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")
        val ELLIPSES = setOf("ellipse", "flowChartConnector", "wedgeEllipseCallout", "cloud", "cloudCallout", "flowChartOr", "flowChartSummingJunction")
        val UNDRAWABLE_PICTURES = setOf("emf", "wmf", "tif", "tiff", "pict", "wdp", "jxr")
        val UNDERSTOOD = setOf("a14", "p14", "p15", "a15", "a16", "p16", "wps", "wpg", "a")
    }
}
