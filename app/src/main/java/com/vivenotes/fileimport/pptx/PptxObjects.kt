package com.vivenotes.fileimport.pptx

import com.vivenotes.fileimport.docx.ParagraphPiece
import com.vivenotes.fileimport.docx.PagePiece
import com.vivenotes.fileimport.docx.SeparatorPiece
import com.vivenotes.fileimport.docx.TablePiece
import com.vivenotes.fileimport.docx.WordBoxed
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordParagraphStyle
import com.vivenotes.fileimport.docx.WordPicture
import com.vivenotes.fileimport.docx.WordRunStyle
import com.vivenotes.fileimport.docx.WordTable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a thing on a slide is, as far as turning a slide into objects is concerned. */
internal enum class PptxRole { Title, Body, Furniture, TextBox, Drawing, Picture, Table }

/** One thing on a slide, in page twips, with whatever can be made of it. */
internal class PptxElement(
    val role: PptxRole,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Which group shape it was drawn in, since a group is one thing whatever is in it. */
    val group: Int?,
    val paragraphs: List<WordParagraph> = emptyList(),
    val table: WordTable? = null,
    val tableEm: Float = 0f,
    val picture: WordPicture? = null,
    /** Whether this is a line or arrow, which is only ever part of a drawing. */
    val connector: Boolean = false,
    /** Something with no picture to show, like a chart: it keeps its place on the slide and no more. */
    val missing: Boolean = false,
    /** Draws it with its top-left at the given place on whichever page it is on. */
    val draw: (x: Int, y: Int) -> WordBoxed,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val wordCount: Int get() = paragraphs.sumOf { paragraph -> paragraph.plainText().split(' ').count { it.isNotBlank() } }
}

private fun WordParagraph.plainText(): String =
    inlines.filterIsInstance<com.vivenotes.fileimport.docx.WordText>().joinToString("") { it.text }

/** A slide's contents as they will be read: in order, with its drawings each made into one picture. */
internal sealed interface PptxObject {
    class Text(val paragraphs: List<WordParagraph>) : PptxObject

    class Table(val table: WordTable, val em: Float) : PptxObject

    class Picture(val picture: WordPicture) : PptxObject

    /** Elements that will be drawn together and cut out as one picture. */
    class Figure(val members: List<PptxElement>, val left: Int, val top: Int, val right: Int, val bottom: Int) : PptxObject {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
}

/**
 * Works out what a slide's shapes come to as objects.
 *
 * Text and tables arrive as themselves. A drawing is many shapes that only mean something together,
 * so whatever touches, by being grouped or by being close, becomes one picture of the drawing. What a
 * slide only decorates itself with, a thin bar or a panel behind the text, comes to nothing.
 */
internal fun arrangeSlide(elements: List<PptxElement>, slideWidth: Int, slideHeight: Int): List<PptxObject> {
    val present = elements.filter { it.role != PptxRole.Furniture && !it.missing && !isDecoration(it, slideWidth, slideHeight) }
    val drawings = present.filter { it.role == PptxRole.Drawing }
    val pictures = present.filter { it.role == PptxRole.Picture }
    val annotated = pictures.filter { picture -> drawings.any { touching(picture, it, 0) } }
    val pool = drawings + annotated
    val parent = IntArray(pool.size) { it }
    fun find(item: Int): Int {
        var at = item
        while (parent[at] != at) {
            parent[at] = parent[parent[at]]
            at = parent[at]
        }
        return at
    }
    for (first in pool.indices) {
        for (second in first + 1 until pool.size) {
            val a = pool[first]
            val b = pool[second]
            if ((a.group != null && a.group == b.group) || touching(a, b, FIGURE_JOIN_TWIPS)) parent[find(first)] = find(second)
        }
    }
    val clusters = pool.indices.groupBy(::find).values.map { indices -> indices.map(pool::get) }

    val out = ArrayList<Pair<PptxElement?, PptxObject>>()
    val absorbed = HashSet<PptxElement>()
    for (cluster in clusters) {
        val only = cluster.singleOrNull()
        when {
            // A lone shape with words in it is a callout: what it says is what matters.
            only != null && only.paragraphs.isNotEmpty() && !only.connector -> out += only to PptxObject.Text(only.paragraphs)
            else -> {
                var left = cluster.minOf { it.left }
                var top = cluster.minOf { it.top }
                var right = cluster.maxOf { it.right }
                var bottom = cluster.maxOf { it.bottom }
                // Labels laid over a drawing are part of it, and are in the picture anyway.
                val labels = present.filter { element ->
                    element.role == PptxRole.TextBox && element.wordCount <= MAX_LABEL_WORDS &&
                        (element.left + element.right) / 2 in left..right && (element.top + element.bottom) / 2 in top..bottom
                }
                absorbed += labels
                labels.forEach { label ->
                    left = min(left, label.left)
                    top = min(top, label.top)
                    right = max(right, label.right)
                    bottom = max(bottom, label.bottom)
                }
                val members = (cluster + labels).sortedBy { elements.indexOf(it) }
                out += cluster.first() to PptxObject.Figure(members, left, top, right, bottom)
            }
        }
    }
    for (element in present) {
        if (element in absorbed || element in pool) continue
        when (element.role) {
            PptxRole.Title, PptxRole.Body, PptxRole.TextBox -> if (element.paragraphs.isNotEmpty()) out += element to PptxObject.Text(element.paragraphs)
            PptxRole.Table -> element.table?.let { out += element to PptxObject.Table(it, element.tableEm) }
            PptxRole.Picture -> element.picture?.let { out += element to PptxObject.Picture(it) }
            else -> Unit
        }
    }
    return readingOrder(out, slideHeight)
}

/** Top to bottom, the title first, and left to right among things that start level with each other. */
private fun readingOrder(items: List<Pair<PptxElement?, PptxObject>>, slideHeight: Int): List<PptxObject> {
    val tolerance = slideHeight * ROW_TOLERANCE
    val titles = items.filter { it.first?.role == PptxRole.Title }
    val sorted = (items - titles.toSet()).sortedBy { topOf(it) }
    val rows = ArrayList<MutableList<Pair<PptxElement?, PptxObject>>>()
    for (item in sorted) {
        val row = rows.lastOrNull()?.takeIf { topOf(item) - topOf(it.first()) <= tolerance }
        if (row != null) row += item else rows += arrayListOf(item)
    }
    return titles.map { it.second } + rows.flatMap { row -> row.sortedBy { it.first?.left ?: 0 }.map { it.second } }
}

private fun topOf(item: Pair<PptxElement?, PptxObject>): Int = (item.second as? PptxObject.Figure)?.top ?: item.first?.top ?: 0

private fun touching(a: PptxElement, b: PptxElement, reach: Int): Boolean {
    val across = max(a.left - b.right, b.left - a.right)
    val down = max(a.top - b.bottom, b.top - a.bottom)
    return across <= reach && down <= reach
}

/** A shape with nothing to say that is either a thin rule or a panel the size of the slide. */
private fun isDecoration(element: PptxElement, slideWidth: Int, slideHeight: Int): Boolean {
    if (element.role != PptxRole.Drawing || element.paragraphs.isNotEmpty() || element.connector) return false
    val panel = element.width * element.height >= slideWidth * slideHeight * PANEL_SHARE
    val rule = min(element.width, element.height) < MIN_DRAWING_SIDE_TWIPS
    return panel || rule
}

/** The pieces a slide's objects make, in the model the other formats' objects are made from. */
internal fun piecesOf(objects: List<PptxObject>, slideWidth: Int, figureParts: Map<PptxObject.Figure, String>): List<PagePiece> {
    val pieces = ArrayList<PagePiece>()
    objects.forEachIndexed { index, item ->
        when (item) {
            is PptxObject.Text -> {
                val last = item.paragraphs.lastIndex
                item.paragraphs.forEachIndexed { at, paragraph ->
                    // A gap between one shape's text and the next, which the slide showed by distance.
                    val spaced = at == last && index != objects.lastIndex
                    pieces += ParagraphPiece(if (spaced) spacedBelow(paragraph) else paragraph, slideWidth)
                }
            }
            is PptxObject.Table -> pieces += TablePiece(item.table, 0, item.table.rows.size, (NOMINAL_BODY_TWIPS * item.em / NOMINAL_BODY_EM).roundToInt())
            is PptxObject.Picture -> pieces += pictureOnItsOwn(item.picture, slideWidth)
            is PptxObject.Figure -> figureParts[item]?.let { part ->
                pieces += pictureOnItsOwn(WordPicture(part, item.width, item.height, null, null), slideWidth)
            }
        }
    }
    return pieces
}

private fun spacedBelow(paragraph: WordParagraph): WordParagraph =
    WordParagraph(paragraph.style.copy(afterTwips = max(paragraph.style.afterTwips, SHAPE_GAP_TWIPS)), paragraph.markStyle, paragraph.inlines, paragraph.label)

private fun pictureOnItsOwn(picture: WordPicture, bodyWidth: Int): ParagraphPiece =
    ParagraphPiece(WordParagraph(WordParagraphStyle(), WordRunStyle(), listOf(picture), null), bodyWidth)

/** One text box to a slide, so that each can be moved or kept without the rest. */
internal fun joinSlides(slides: List<List<PagePiece>>): List<PagePiece> {
    val out = ArrayList<PagePiece>()
    slides.forEachIndexed { index, pieces ->
        if (index > 0) out += SeparatorPiece
        out += pieces
    }
    return out
}

private const val FIGURE_JOIN_TWIPS = 360
private const val MAX_LABEL_WORDS = 8
private const val ROW_TOLERANCE = 0.04f
private const val PANEL_SHARE = 0.5f
private const val MIN_DRAWING_SIDE_TWIPS = 240
private const val SHAPE_GAP_TWIPS = 240
private const val NOMINAL_BODY_TWIPS = 9360
private const val NOMINAL_BODY_EM = 11f
