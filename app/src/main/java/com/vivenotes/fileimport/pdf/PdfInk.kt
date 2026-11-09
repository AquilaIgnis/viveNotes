package com.vivenotes.fileimport.pdf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A rectangle in page points, origin at the top left. */
internal data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = max(width, 0f) * max(height, 0f)

    fun union(other: PdfRect): PdfRect =
        PdfRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun expanded(by: Float): PdfRect = PdfRect(left - by, top - by, right + by, bottom + by)

    fun containsCenterOf(other: PdfRect): Boolean =
        other.centerX in left..right && other.centerY in top..bottom

    /** The empty space between the two, or zero if they touch. */
    fun distanceTo(other: PdfRect): Float {
        val dx = max(0f, max(other.left - right, left - other.right))
        val dy = max(0f, max(other.top - bottom, top - other.bottom))
        return max(dx, dy)
    }
}

/** A page drawn to pixels: `0xAARRGGBB`, row after row, on white. */
internal class PdfRaster(val pixels: IntArray, val width: Int, val height: Int, val pixelsPerPoint: Float) {
    fun luminance(): IntArray = IntArray(pixels.size) { index ->
        val pixel = pixels[index]
        (((pixel shr 16) and 0xFF) * 299 + ((pixel shr 8) and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000
    }
}

/** A long thin line on the page, ruled across or down. */
internal class PdfRule(val horizontal: Boolean, val at: Float, val from: Float, val to: Float)

/** A ruled table: the positions of its lines, and which of them are really drawn in each row and column. */
internal class PdfGrid(
    val xs: FloatArray,
    val ys: FloatArray,
    private val horizontals: List<PdfRule>,
    private val verticals: List<PdfRule>,
) {
    val columns: Int get() = xs.size - 1
    val rows: Int get() = ys.size - 1
    val box: PdfRect get() = PdfRect(xs.first(), ys.first(), xs.last(), ys.last())

    /** What each cell is painted, row after row, or [NO_FILL]. */
    var fills: IntArray = IntArray(0)

    private fun drawn(rules: List<PdfRule>, at: Float, from: Float, to: Float): Boolean = rules.any { rule ->
        abs(rule.at - at) <= GRID_TOLERANCE && overlap(rule.from, rule.to, from, to) >= (to - from) * SEPARATOR_MIN_COVER
    }

    /** Which merged cell each cell is part of, by the index of that cell's first; row after row. */
    fun cellGroups(): IntArray {
        val parent = IntArray(rows * columns) { it }
        fun find(cell: Int): Int {
            var at = cell
            while (parent[at] != at) {
                parent[at] = parent[parent[at]]
                at = parent[at]
            }
            return at
        }
        fun join(a: Int, b: Int) {
            val first = find(a)
            val second = find(b)
            if (first != second) parent[max(first, second)] = min(first, second)
        }
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val cell = row * columns + column
                if (column > 0 && !drawn(verticals, xs[column], ys[row], ys[row + 1])) join(cell, cell - 1)
                if (row > 0 && !drawn(horizontals, ys[row], xs[column], xs[column + 1])) join(cell, cell - columns)
            }
        }
        return IntArray(parent.size, ::find)
    }
}

/** What a page has on it besides its words. */
internal class PdfInk(val grids: List<PdfGrid>, val figures: List<PdfRect>)

/** How much of a table's rules must really be drawn where its grid says, against a diagram's boxes. */
private const val GRID_MIN_DRAWN = 0.6f

/** At least two rules must run nearly the whole way across or down: that is what makes it one table. */
private const val GRID_FULL_RULE = 0.9f
private const val GRID_MIN_FULL_RULES = 2

/** A filled table has no rule along its outside, where its colour simply stops. */
private const val GRID_MIN_FULL_RULES_FILLED = 1

/** Columns narrower than this are a line drawn twice, not a column. */
private const val GRID_MIN_COLUMN_PT = 8f

private fun overlap(aFrom: Float, aTo: Float, bFrom: Float, bTo: Float): Float = min(aTo, bTo) - max(aFrom, bFrom)

/** Rules a whisker apart are one rule, the same rule or a double one. */
internal const val GRID_TOLERANCE = 3f

/** How much of the way along a cell's edge a line must run to count as separating it. */
private const val SEPARATOR_MIN_COVER = 0.6f

/** A pixel this much darker than its neighbours two sides away is on a thin line. */
private const val THIN_CONTRAST = 32

/** Thicker than this, a line is a shape. */
private const val MAX_RULE_THICKNESS_PT = 3f
private const val MIN_HORIZONTAL_RULE_PT = 20f
private const val MIN_VERTICAL_RULE_PT = 10f

/** How many pixels a rule may skip and still be one rule. */
private const val RULE_GAP_PX = 2

private const val RULE_SAME_LINE_PT = 1.5f
private const val RULE_JOIN_GAP_PT = 3.5f

/** A white line between two coloured cells: paler than the colour either side, on the palest tints too. */
private const val BRIGHT_CONTRAST = 6

/** White lines cross each other, and a line is not paler than itself where they do. */
private const val BRIGHT_RULE_GAP_PX = 4

/** What a cell with no colour of its own has for a fill. */
internal const val NO_FILL = 0

/** Keeps a cell's letters and its rules out of what is sampled for its colour. */
private const val CELL_FILL_INSET_PT = 2f
private const val CELL_FILL_STEP_PX = 3

/** Paler than this on every channel, a cell's colour is the paper's. */
private const val CELL_PAPER_MIN = 240

/** Lighter than this on every channel, a pixel is the paper. */
private const val PAPER_MIN = 250

/** Small enough for a single table cell's fill, which its rules have cut off from the rest. */
private const val SHADE_MIN_AREA_PT2 = 60

/** How rectangular a block of one colour must be, holes aside, to be a shaded background. */
private const val SHADE_MIN_OUTER_FILL = 0.92f

/** Figure parts this close together are one figure. */
private const val FIGURE_JOIN_RADIUS_PT = 4
private const val MIN_FIGURE_SIDE_PT = 6f
private const val MIN_FIGURE_AREA_PT2 = 100f

/**
 * Finds what is on a page that is not words: the rules of its tables and its figures.
 *
 * From the pixels and nothing else, because the platform says where text is but not where anything
 * else is, and because a rendered page is the one view of a PDF that no producer can get wrong.
 * [words] are known to be text, so that they are not mistaken for a drawing.
 */
internal fun readInk(raster: PdfRaster, words: List<PdfWord>): PdfInk {
    val luminance = raster.luminance()
    val glyphs = words.flatMap { it.located }
    val dashes = glyphs.filter { it.char in DASH_CHARACTERS }.map { it.box!! }
    val wordBoxes = words.map { it.box }
    val horizontals = findRules(luminance, raster, horizontal = true)
        .filterNot { rule -> isTypedDashes(rule, dashes) }
    // A tall stem can run a long way, and two lines of them can line up; a table's rules are between words.
    val verticals = findRules(luminance, raster, horizontal = false)
        .filterNot { rule -> isThroughText(rule, wordBoxes) }
    val ruled = buildGrids(horizontals, verticals, GRID_MIN_FULL_RULES)
    // A slide's table is coloured cells with white between them, which no dark-line search will see.
    val text = textMask(raster, wordBoxes)
    val filled = buildGrids(
        findRules(luminance, raster, horizontal = true, bright = true, text = text),
        findRules(luminance, raster, horizontal = false, bright = true, text = text),
        GRID_MIN_FULL_RULES_FILLED,
    ).filterNot { candidate -> ruled.any { it.grid.box.containsCenterOf(candidate.grid.box) } }
    val grids = ruled + filled
    grids.forEach { it.grid.fills = cellFills(it.grid, raster) }
    return PdfInk(grids = grids.map { it.grid }, figures = findFigures(raster, wordBoxes, grids.flatMap { it.rules }))
}

/** Where words are. The white inside a letter's bowl looks just like a pale line between two darker strokes. */
private fun textMask(raster: PdfRaster, words: List<PdfRect>): BooleanArray {
    val mask = BooleanArray(raster.pixels.size)
    val scale = raster.pixelsPerPoint
    for (word in words) {
        val left = (word.left * scale).toInt().coerceIn(0, raster.width)
        val right = ceil(word.right * scale).toInt().coerceIn(0, raster.width)
        val top = (word.top * scale).toInt().coerceIn(0, raster.height)
        val bottom = ceil(word.bottom * scale).toInt().coerceIn(0, raster.height)
        for (y in top until bottom) mask.fill(true, y * raster.width + left, y * raster.width + right)
    }
    return mask
}

/** A cell's colour is whatever most of it is painted, since its letters only cover a part. */
private fun cellFills(grid: PdfGrid, raster: PdfRaster): IntArray {
    val scale = raster.pixelsPerPoint
    return IntArray(grid.rows * grid.columns) { index ->
        val row = index / grid.columns
        val column = index % grid.columns
        val left = ((grid.xs[column] + CELL_FILL_INSET_PT) * scale).toInt().coerceIn(0, raster.width)
        val right = ((grid.xs[column + 1] - CELL_FILL_INSET_PT) * scale).toInt().coerceIn(0, raster.width)
        val top = ((grid.ys[row] + CELL_FILL_INSET_PT) * scale).toInt().coerceIn(0, raster.height)
        val bottom = ((grid.ys[row + 1] - CELL_FILL_INSET_PT) * scale).toInt().coerceIn(0, raster.height)
        val counts = HashMap<Int, Int>()
        for (y in top until bottom step CELL_FILL_STEP_PX) {
            for (x in left until right step CELL_FILL_STEP_PX) {
                // Near colours are one colour, or the blur round every letter would be a candidate.
                val key = raster.pixels[y * raster.width + x] and 0xF8F8F8
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val color = counts.maxByOrNull { it.value }?.key ?: return@IntArray NO_FILL
        val isPaper = (color shr 16 and 0xFF) >= CELL_PAPER_MIN && (color shr 8 and 0xFF) >= CELL_PAPER_MIN && (color and 0xFF) >= CELL_PAPER_MIN
        if (isPaper) NO_FILL else OPAQUE or color
    }
}

private const val OPAQUE = 0xFF shl 24

private fun isThroughText(rule: PdfRule, words: List<PdfRect>): Boolean {
    var covered = 0f
    for (word in words) {
        if (rule.at < word.left || rule.at > word.right) continue
        covered += max(0f, overlap(rule.from, rule.to, word.top, word.bottom))
    }
    return covered >= (rule.to - rule.from) * 0.5f
}

/** `____` typed as a blank to fill in looks like a rule to the eye and is text in the file. */
private fun isTypedDashes(rule: PdfRule, dashes: List<PdfRect>): Boolean {
    var covered = 0f
    for (dash in dashes) {
        if (rule.at < dash.top - 2f || rule.at > dash.bottom + 2f) continue
        covered += max(0f, overlap(rule.from, rule.to, dash.left, dash.right))
    }
    return covered >= (rule.to - rule.from) * 0.4f
}

private class OpenRule(var near: Int, var far: Int, var from: Int, var to: Int)

/** With [bright] it looks for pale lines through something darker, leaving out what [text] marks. */
private fun findRules(
    luminance: IntArray,
    raster: PdfRaster,
    horizontal: Boolean,
    bright: Boolean = false,
    text: BooleanArray? = null,
): List<PdfRule> {
    val width = raster.width
    val height = raster.height
    val pixelsPerPoint = raster.pixelsPerPoint
    val radius = ceil(MAX_RULE_THICKNESS_PT * pixelsPerPoint).toInt()
    val across = if (horizontal) height else width
    val along = if (horizontal) width else height
    val acrossStep = if (horizontal) width else 1
    val alongStep = if (horizontal) 1 else width
    val minimum = ((if (horizontal) MIN_HORIZONTAL_RULE_PT else MIN_VERTICAL_RULE_PT) * pixelsPerPoint).toInt()
    val open = ArrayList<OpenRule>()
    val finished = ArrayList<OpenRule>()
    val allowedGap = if (bright) BRIGHT_RULE_GAP_PX else RULE_GAP_PX

    fun thin(index: Int): Boolean {
        if (bright) {
            if (text?.get(index) == true) return false
            val value = luminance[index] - BRIGHT_CONTRAST
            // A white letter close by is as bright as the line, so each side goes by the darker of two places.
            val nearer = radius * 2 / 3
            val before = min(luminance[index - radius * acrossStep], luminance[index - nearer * acrossStep])
            val after = min(luminance[index + radius * acrossStep], luminance[index + nearer * acrossStep])
            return value >= before && value >= after
        }
        val value = luminance[index] + THIN_CONTRAST
        return value <= luminance[index - radius * acrossStep] && value <= luminance[index + radius * acrossStep]
    }

    fun place(line: Int, from: Int, to: Int) {
        val existing = open.firstOrNull { rule ->
            line - rule.far <= 1 && min(rule.to, to) - max(rule.from, from) >= min(rule.to - rule.from, to - from) / 2
        }
        if (existing == null) {
            open += OpenRule(line, line, from, to)
        } else {
            existing.far = line
            existing.from = min(existing.from, from)
            existing.to = max(existing.to, to)
        }
    }

    for (line in radius until across - radius) {
        var at = 0
        while (at < along) {
            if (!thin(line * acrossStep + at * alongStep)) {
                at++
                continue
            }
            val start = at
            var end = at
            var skipped = 0
            at++
            while (at < along && skipped <= allowedGap) {
                if (thin(line * acrossStep + at * alongStep)) {
                    end = at
                    skipped = 0
                } else {
                    skipped++
                }
                at++
            }
            if (end - start + 1 >= minimum) place(line, start, end)
        }
        val iterator = open.iterator()
        while (iterator.hasNext()) {
            val rule = iterator.next()
            if (line - rule.far > 1) {
                finished += rule
                iterator.remove()
            }
        }
    }
    finished += open
    return finished
        .filter { it.far - it.near + 1 <= radius * 2 }
        .map { rule ->
            PdfRule(horizontal, (rule.near + rule.far + 1) / 2f / pixelsPerPoint, rule.from / pixelsPerPoint, (rule.to + 1) / pixelsPerPoint)
        }
}

private fun cluster(values: List<Float>): FloatArray {
    if (values.isEmpty()) return FloatArray(0)
    val sorted = values.sorted()
    val groups = ArrayList<MutableList<Float>>()
    groups += mutableListOf(sorted.first())
    for (value in sorted.drop(1)) {
        if (value - groups.last().last() <= GRID_TOLERANCE) groups.last() += value else groups += mutableListOf(value)
    }
    return FloatArray(groups.size) { groups[it].average().toFloat() }
}

/** A rule found in pieces is one rule: the wider band under a table's header cuts every line crossing it. */
private fun joinedRules(rules: List<PdfRule>): List<PdfRule> {
    val out = ArrayList<PdfRule>()
    val sorted = rules.sortedBy { it.at }
    var start = 0
    while (start < sorted.size) {
        var end = start + 1
        while (end < sorted.size && sorted[end].at - sorted[start].at <= RULE_SAME_LINE_PT) end++
        var joined: PdfRule? = null
        for (rule in sorted.subList(start, end).sortedBy { it.from }) {
            val last = joined
            joined = when {
                last == null -> rule
                rule.from - last.to <= RULE_JOIN_GAP_PT -> PdfRule(last.horizontal, last.at, last.from, max(last.to, rule.to))
                else -> {
                    out += last
                    rule
                }
            }
        }
        joined?.let { out += it }
        start = end
    }
    return out
}

/** An accepted table and the rules it was built from. */
internal class FoundGrid(val grid: PdfGrid, val rules: List<PdfRule>)

/** Rules that cross each other are one table; a line on its own is only a line. */
internal fun buildGrids(foundHorizontals: List<PdfRule>, foundVerticals: List<PdfRule>, minFullRules: Int): List<FoundGrid> {
    val horizontals = joinedRules(foundHorizontals)
    val verticals = joinedRules(foundVerticals)
    val total = horizontals.size + verticals.size
    val parent = IntArray(total) { it }
    fun find(item: Int): Int {
        var at = item
        while (parent[at] != at) {
            parent[at] = parent[parent[at]]
            at = parent[at]
        }
        return at
    }
    horizontals.forEachIndexed { h, across ->
        verticals.forEachIndexed { v, down ->
            val crosses = down.at >= across.from - GRID_TOLERANCE && down.at <= across.to + GRID_TOLERANCE &&
                across.at >= down.from - GRID_TOLERANCE && across.at <= down.to + GRID_TOLERANCE
            if (crosses) parent[find(h)] = find(horizontals.size + v)
        }
    }
    val members = HashMap<Int, MutableList<Int>>()
    for (item in 0 until total) members.getOrPut(find(item)) { ArrayList() } += item
    return members.values.mapNotNull { group ->
        val across = group.filter { it < horizontals.size }.map(horizontals::get)
        val down = group.filter { it >= horizontals.size }.map { verticals[it - horizontals.size] }
        if (across.size < minFullRules || down.size < minFullRules || across.isEmpty() || down.isEmpty()) return@mapNotNull null
        // The outer edge where a table has no border of its own is where its inner lines stop.
        val xs = dropSliverColumns(cluster(down.map { it.at } + across.minOf { it.from } + across.maxOf { it.to }))
        val ys = cluster(across.map { it.at } + down.minOf { it.from } + down.maxOf { it.to })
        val grid = PdfGrid(xs, ys, across, down)
        if (grid.rows < 1 || grid.columns < 1 || !looksLikeATable(grid, across, down, minFullRules)) return@mapNotNull null
        FoundGrid(grid, across + down)
    }
}

private fun dropSliverColumns(xs: FloatArray): FloatArray {
    val kept = ArrayList<Float>()
    xs.forEach { x -> if (kept.isEmpty() || x - kept.last() >= GRID_MIN_COLUMN_PT) kept += x }
    return kept.toFloatArray()
}

/**
 * A table's rules run edge to edge and meet where the grid says. A diagram's boxes also cross, but
 * they leave most of the grid they imply undrawn, and that is what tells the two apart.
 */
private fun looksLikeATable(grid: PdfGrid, across: List<PdfRule>, down: List<PdfRule>, minFullRules: Int): Boolean {
    val box = grid.box
    val wide = across.count { it.to - it.from >= box.width * GRID_FULL_RULE }
    val tall = down.count { it.to - it.from >= box.height * GRID_FULL_RULE }
    if (wide < minFullRules || tall < minFullRules) return false
    val cells = grid.rows * grid.columns
    val groups = grid.cellGroups().toSet().size
    // One big cell is a box around something, and a box is not a table.
    return groups >= 2 && groups >= cells * GRID_MIN_DRAWN
}

/** Marks and reads a page at one pixel per point, which is plenty to find where a figure is. */
private class CoarsePage(raster: PdfRaster, words: List<PdfRect>, rules: List<PdfRule>) {
    val step = max(1, raster.pixelsPerPoint.toInt())
    val width = raster.width / step
    val height = raster.height / step

    /** A colour key, or [PAPER] or [TEXT]. */
    val keys = IntArray(width * height)

    init {
        val pixels = raster.pixels
        for (y in 0 until height) {
            for (x in 0 until width) {
                // The darkest of the pixels this one stands for, so that a hairline survives the shrink.
                var pixel = -1
                var darkest = Int.MAX_VALUE
                for (dy in 0 until step) {
                    for (dx in 0 until step) {
                        val value = pixels[(y * step + dy) * raster.width + x * step + dx]
                        val sum = ((value shr 16) and 0xFF) + ((value shr 8) and 0xFF) + (value and 0xFF)
                        if (sum < darkest) {
                            darkest = sum
                            pixel = value
                        }
                    }
                }
                keys[y * width + x] = keyOf(pixel)
            }
        }
        // A whole word at a time: a letter's own box can leave a ligature or a kern's overhang outside it.
        words.forEach { word -> mark(PdfRect(word.left - 2f, word.top - 1.5f, word.right + 2f, word.bottom + 1.5f), TEXT) }
        rules.forEach { rule ->
            val box = if (rule.horizontal) {
                PdfRect(rule.from, rule.at - MAX_RULE_THICKNESS_PT, rule.to, rule.at + MAX_RULE_THICKNESS_PT)
            } else {
                PdfRect(rule.at - MAX_RULE_THICKNESS_PT, rule.from, rule.at + MAX_RULE_THICKNESS_PT, rule.to)
            }
            mark(box, PAPER)
        }
    }

    private fun keyOf(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return if (min(min(r, g), b) >= PAPER_MIN) PAPER else ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
    }

    private fun mark(box: PdfRect, value: Int) {
        val left = box.left.toInt().coerceIn(0, width)
        val right = ceil(box.right).toInt().coerceIn(0, width)
        val top = box.top.toInt().coerceIn(0, height)
        val bottom = ceil(box.bottom).toInt().coerceIn(0, height)
        for (y in top until bottom) {
            for (x in left until right) {
                // A rule never erases a character sitting on it.
                if (value == TEXT || keys[y * width + x] != TEXT) keys[y * width + x] = value
            }
        }
    }

    companion object {
        const val PAPER = -1
        const val TEXT = -2
    }
}

/**
 * A big rectangle of one colour with things on it is a background.
 *
 * "With things on it" is the condition that matters: a solid bar in a chart is also a rectangle of one
 * colour, and that one is the content.
 */
private fun eraseShades(page: CoarsePage) {
    val keys = page.keys
    val width = page.width
    val height = page.height
    val visited = BooleanArray(keys.size)
    val stack = IntArray(keys.size)
    val rowLeft = IntArray(height)
    val rowRight = IntArray(height)
    val members = IntArray(keys.size)
    for (start in keys.indices) {
        if (visited[start] || keys[start] < 0) continue
        val key = keys[start]
        var size = 0
        var count = 0
        var top = height
        var bottom = -1
        var left = width
        var right = -1
        stack[size++] = start
        visited[start] = true
        while (size > 0) {
            val at = stack[--size]
            members[count++] = at
            val x = at % width
            val y = at / width
            if (y < top) {
                for (row in y until top.coerceAtMost(height)) {
                    rowLeft[row] = width
                    rowRight[row] = -1
                }
                top = y
            }
            if (y > bottom) {
                for (row in bottom + 1..y) {
                    if (row >= top) {
                        rowLeft[row] = width
                        rowRight[row] = -1
                    }
                }
                bottom = y
            }
            rowLeft[y] = min(rowLeft[y], x)
            rowRight[y] = max(rowRight[y], x)
            left = min(left, x)
            right = max(right, x)
            if (x > 0 && !visited[at - 1] && keys[at - 1] == key) { visited[at - 1] = true; stack[size++] = at - 1 }
            if (x + 1 < width && !visited[at + 1] && keys[at + 1] == key) { visited[at + 1] = true; stack[size++] = at + 1 }
            if (y > 0 && !visited[at - width] && keys[at - width] == key) { visited[at - width] = true; stack[size++] = at - width }
            if (y + 1 < height && !visited[at + width] && keys[at + width] == key) { visited[at + width] = true; stack[size++] = at + width }
        }
        if (count < SHADE_MIN_AREA_PT2) continue
        var outer = 0L
        for (row in top..bottom) if (rowRight[row] >= rowLeft[row]) outer += rowRight[row] - rowLeft[row] + 1
        val box = (right - left + 1).toLong() * (bottom - top + 1)
        val holes = outer - count
        if (outer >= box * SHADE_MIN_OUTER_FILL && holes >= max(12L, outer / 50)) {
            for (index in 0 until count) keys[members[index]] = CoarsePage.PAPER
        }
    }
}

private fun findFigures(raster: PdfRaster, words: List<PdfRect>, tableRules: List<PdfRule>): List<PdfRect> {
    val page = CoarsePage(raster, words, tableRules)
    eraseShades(page)
    val width = page.width
    val height = page.height
    val keys = page.keys

    // Everything within a few points of ink joins it, so a chart's parts come out as one chart.
    val sums = IntArray((width + 1) * (height + 1))
    for (y in 0 until height) {
        var row = 0
        for (x in 0 until width) {
            if (keys[y * width + x] >= 0) row++
            sums[(y + 1) * (width + 1) + x + 1] = sums[y * (width + 1) + x + 1] + row
        }
    }
    fun near(x: Int, y: Int): Boolean {
        val left = (x - FIGURE_JOIN_RADIUS_PT).coerceAtLeast(0)
        val right = (x + FIGURE_JOIN_RADIUS_PT + 1).coerceAtMost(width)
        val top = (y - FIGURE_JOIN_RADIUS_PT).coerceAtLeast(0)
        val bottom = (y + FIGURE_JOIN_RADIUS_PT + 1).coerceAtMost(height)
        return sums[bottom * (width + 1) + right] - sums[top * (width + 1) + right] -
            sums[bottom * (width + 1) + left] + sums[top * (width + 1) + left] > 0
    }

    val visited = BooleanArray(keys.size)
    val stack = IntArray(keys.size)
    val figures = ArrayList<PdfRect>()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val start = y * width + x
            if (visited[start] || !near(x, y)) continue
            var size = 0
            var inked = 0
            val inkAt = ArrayList<Int>()
            var left = width
            var right = -1
            var top = height
            var bottom = -1
            stack[size++] = start
            visited[start] = true
            while (size > 0) {
                val at = stack[--size]
                val cx = at % width
                val cy = at / width
                if (keys[at] >= 0) {
                    inked++
                    inkAt += at
                    left = min(left, cx)
                    right = max(right, cx)
                    top = min(top, cy)
                    bottom = max(bottom, cy)
                }
                if (cx > 0 && !visited[at - 1] && near(cx - 1, cy)) { visited[at - 1] = true; stack[size++] = at - 1 }
                if (cx + 1 < width && !visited[at + 1] && near(cx + 1, cy)) { visited[at + 1] = true; stack[size++] = at + 1 }
                if (cy > 0 && !visited[at - width] && near(cx, cy - 1)) { visited[at - width] = true; stack[size++] = at - width }
                if (cy + 1 < height && !visited[at + width] && near(cx, cy + 1)) { visited[at + width] = true; stack[size++] = at + width }
            }
            if (right < 0) continue
            val figure = PdfRect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
            if (figure.width >= MIN_FIGURE_SIDE_PT && figure.height >= MIN_FIGURE_SIDE_PT &&
                figure.area >= MIN_FIGURE_AREA_PT2 && inked >= MIN_FIGURE_INK &&
                !isHollowFrame(inkAt, width, left, right, top, bottom)
            ) {
                figures += figure
            }
        }
    }
    return figures
}

/** Lines round the edge with nothing inside: a box or a page border, which is not a picture of anything. */
private fun isHollowFrame(ink: List<Int>, width: Int, left: Int, right: Int, top: Int, bottom: Int): Boolean {
    val onEdge = ink.count { at ->
        val x = at % width
        val y = at / width
        x - left <= FRAME_EDGE_PT || right - x <= FRAME_EDGE_PT || y - top <= FRAME_EDGE_PT || bottom - y <= FRAME_EDGE_PT
    }
    return onEdge >= ink.size * FRAME_MIN_SHARE
}

private const val FRAME_EDGE_PT = 4
private const val FRAME_MIN_SHARE = 0.9f
private const val MIN_FIGURE_INK = 30

/** Characters that draw a long flat stroke, which a row of them turns into something like a rule. */
internal const val DASH_CHARACTERS = "_-–—=~"
