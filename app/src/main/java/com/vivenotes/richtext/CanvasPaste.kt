package com.vivenotes.richtext

import com.vivenotes.data.ClipboardContent
import com.vivenotes.data.PictureFileExtensions
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.newTable

internal sealed interface CanvasPasteItem {
    data class Text(val blocks: List<Block>) : CanvasPasteItem
    data class Table(val outline: Outline.Table) : CanvasPasteItem
    data class Image(val address: String? = null, val bytes: ByteArray? = null) : CanvasPasteItem
}

internal fun canvasPasteItems(content: ClipboardContent, marks: Set<Mark> = emptySet()): List<CanvasPasteItem> {
    content.html?.takeIf { it.length <= MAX_HTML_CHARS }?.let { source ->
        val items = htmlCanvasItems(source, marks)
        if (content.image != null && items.none { it is CanvasPasteItem.Table }) {
            return listOf(CanvasPasteItem.Image(bytes = content.image))
        }
        if (items.isNotEmpty()) return items
    }
    content.image?.let { return listOf(CanvasPasteItem.Image(bytes = it)) }
    content.imageUrl?.let(::imageAddress)?.let { return listOf(CanvasPasteItem.Image(address = it)) }
    val text = content.text?.replace("\r\n", "\n")?.replace('\r', '\n') ?: return emptyList()
    imageLink(text)?.let { return listOf(CanvasPasteItem.Image(address = it)) }
    return if (text.isEmpty()) emptyList() else listOf(CanvasPasteItem.Text(plainPasteBlocks(text, marks)))
}

internal fun imageAddress(value: String, base: String? = null): String? {
    val address = value.trim()
    if (address.startsWith("data:image/", ignoreCase = true)) return address
    return resolveLink(address, base)?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
}

internal fun imageLink(value: String, base: String? = null): String? = imageAddress(value, base)?.takeIf {
    it.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in PictureFileExtensions
}

private fun htmlCanvasItems(source: String, marks: Set<Mark>): List<CanvasPasteItem> {
    val sourceBase = Regex("(?im)^SourceURL:(.+)$").find(source)?.groupValues?.get(1)?.trim()
    val html = if (source.startsWith("Version:")) source.substring(source.indexOf('<').coerceAtLeast(0)) else source
    val tags = visibleTags(html)
    // Browsers may offer only selected rows, without their enclosing table.
    if (tags.none { it.second.name == "table" } && tags.any { it.second.name in setOf("tr", "td", "th") }) {
        return htmlCanvasItems("<table>$html</table>", marks)
    }
    val base = tags.firstOrNull { it.second.name == "base" }?.second?.attributes?.get("href") ?: sourceBase
    val items = mutableListOf<CanvasPasteItem>()
    var from = 0
    var index = 0
    fun text(until: Int) {
        pastedHtmlBlocks(html.substring(from, until), marks)?.let { items += CanvasPasteItem.Text(it) }
    }
    while (index < tags.size) {
        val (at, tag) = tags[index]
        if (!tag.closing && tag.name == "table") {
            var depth = 1
            var end = index + 1
            while (end < tags.size && depth > 0) {
                if (tags[end].second.name == "table") depth += if (tags[end].second.closing) -1 else 1
                if (depth > 0) end++
            }
            val until = tags.getOrNull(end)?.second?.end ?: html.length
            text(at)
            val fragment = html.substring(at, until)
            val table = pastedTable(fragment, marks, base)
            if (table != null) items += CanvasPasteItem.Table(table)
            else pastedHtmlBlocks(fragment, marks)?.let { items += CanvasPasteItem.Text(it) }
            from = until
            index = end
        } else if (!tag.closing && tag.name == "a" && tag.attributes["href"]?.let { imageLink(it, base) } != null) {
            var end = index + 1
            while (end < tags.size && !(tags[end].second.name == "a" && tags[end].second.closing)) end++
            if ((index + 1 until end).none { tags[it].second.name == "img" }) {
                text(at)
                items += CanvasPasteItem.Image(imageLink(tag.attributes.getValue("href"), base))
                from = tags.getOrNull(end)?.second?.end ?: html.length
                index = end
            }
        } else if (!tag.closing && tag.name == "img" &&
            tag.attributes["alt"]?.trim()?.startsWith("{\\displaystyle") != true) {
            val address = tag.attributes["src"]?.let { imageAddress(it, base) }
            if (address != null) {
                text(at)
                items += CanvasPasteItem.Image(address)
                from = tag.end
            }
        }
        index++
        if (items.size > MAX_ITEMS) return pastedHtmlBlocks(html, marks)?.let { listOf(CanvasPasteItem.Text(it)) }.orEmpty()
    }
    text(html.length)
    return items
}

private data class PastedCell(val blocks: List<Block>, val columns: Int, val rows: Int, val header: Boolean)

/** Spanned cells occupy blank neighbours: the stored table format has no merged cells. */
private fun pastedTable(html: String, marks: Set<Mark>, base: String?): Outline.Table? {
    val rows = mutableListOf<MutableList<PastedCell>>()
    var row: MutableList<PastedCell>? = null
    var cell: HtmlTag? = null
    var cellStart = 0
    var depth = 0
    fun finishCell(until: Int) {
        val opened = cell ?: return
        val baseTag = base?.let { "<base href=\"${it.replace("&", "&amp;").replace("\"", "&quot;")}\">" }.orEmpty()
        val blocks = pastedHtmlBlocks(baseTag + html.substring(cellStart, until), marks) ?: listOf(Block.of(""))
        val columns = opened.attributes["colspan"]?.toIntOrNull() ?: 1
        val height = opened.attributes["rowspan"]?.toIntOrNull() ?: 1
        if (row == null) row = mutableListOf<PastedCell>().also { rows += it }
        row!!.add(PastedCell(blocks, columns.coerceAtLeast(1), height.coerceAtLeast(1), opened.name == "th"))
        cell = null
    }
    for ((at, tag) in visibleTags(html)) {
        if (tag.name == "table") {
            depth += if (tag.closing) -1 else 1
            if (depth == 0) finishCell(at)
            continue
        }
        if (depth != 1) continue
        when (tag.name) {
            "tr" -> {
                finishCell(at)
                row = if (tag.closing) null else mutableListOf<PastedCell>().also { rows += it }
            }
            "td", "th" -> {
                finishCell(at)
                if (!tag.closing) { cell = tag; cellStart = at }
            }
        }
        if (rows.size > Outline.Table.MAX_ROWS || rows.sumOf { it.size } > Outline.Table.MAX_CELLS) return null
    }
    finishCell(html.length)
    if (rows.isEmpty() || rows.all { it.isEmpty() }) return null
    val grid = mutableMapOf<Pair<Int, Int>, PastedCell?>()
    for ((r, cells) in rows.withIndex()) {
        var c = 0
        for (entry in cells) {
            while (grid.containsKey(r to c)) c++
            if (entry.columns > Outline.Table.MAX_COLUMNS - c || entry.rows > Outline.Table.MAX_ROWS - r) return null
            for (dy in 0 until entry.rows) for (dx in 0 until entry.columns) {
                val key = (r + dy) to (c + dx)
                if (grid.containsKey(key)) return null
                grid[key] = if (dy == 0 && dx == 0) entry else null
            }
            c += entry.columns
        }
    }
    val height = maxOf(rows.size, grid.keys.maxOf { it.first } + 1)
    val width = grid.keys.maxOf { it.second } + 1
    if (width * height > Outline.Table.MAX_CELLS) return null
    val table = newTable(width, height,
        headerRow = rows.first().isNotEmpty() && rows.first().all { it.header },
        headerColumn = rows.all { it.firstOrNull()?.header == true })
    return table.copy(rows = table.rows.mapIndexed { r, tableRow ->
        tableRow.copy(cells = tableRow.cells.mapIndexed { c, tableCell ->
            tableCell.copy(blocks = grid[r to c]?.blocks ?: listOf(Block.of("")))
        })
    })
}

private fun visibleTags(html: String): List<Pair<Int, HtmlTag>> {
    val tags = mutableListOf<Pair<Int, HtmlTag>>()
    val open = mutableListOf<Pair<String, Boolean>>()
    var index = 0
    while (index < html.length) {
        val at = html.indexOf('<', index).takeIf { it >= 0 } ?: break
        if (html.startsWith("<!--", at)) {
            index = html.indexOf("-->", at + 4).let { if (it < 0) html.length else it + 3 }
            continue
        }
        val tag = htmlTag(html, at)
        if (tag == null) { index = at + 1; continue }
        index = tag.end
        if (tag.name in setOf("script", "style", "title", "textarea", "template", "noscript")) {
            if (!tag.closing) index = html.indexOf("</${tag.name}", index, true).let {
                if (it < 0) html.length else (html.indexOf('>', it) + 1).takeIf { end -> end > 0 } ?: html.length
            }
            continue
        }
        if (tag.closing) {
            val position = open.indexOfLast { it.first == tag.name }
            val hidden = open.lastOrNull()?.second == true
            if (position >= 0) while (open.size > position) open.removeAt(open.lastIndex)
            if (!hidden) tags += at to tag
        } else {
            val hidden = open.lastOrNull()?.second == true || "hidden" in tag.attributes ||
                tag.attributes["aria-hidden"] == "true" ||
                Regex("display\\s*:\\s*none", RegexOption.IGNORE_CASE).containsMatchIn(tag.attributes["style"].orEmpty())
            if (!hidden || tag.name == "base") tags += at to tag
            if (!tag.selfClosing && tag.name !in setOf("img", "br", "hr", "base", "meta", "link", "input", "col", "source", "wbr")) {
                open += tag.name to hidden
            }
        }
    }
    return tags
}

private const val MAX_HTML_CHARS = 4 * 1024 * 1024
private const val MAX_ITEMS = 128

internal fun plainPasteBlocks(text: String, marks: Set<Mark>): List<Block> = text.split('\n').map { line ->
    Block.of(line).let { block -> block.copy(runs = block.runs.map { it.copy(marks = marks) }) }
}
