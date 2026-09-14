package com.vivenotes.pdf

import com.vivenotes.ink.InkBounds
import kotlin.math.ceil
import kotlin.math.floor

/** What an item on the page is, for the one decision that depends on it — see [PdfItemKind.Title]. */
enum class PdfItemKind {
    /**
     * The page's own header band. The one kind the fit never moves: it is not content placed on the
     * canvas, it is the top of the page, and sliding it to clear a page boundary would be moving
     * the letterhead.
     */
    Title,

    /**
     * One entity — a [PdfGroup], which may be an outline, a drawing, a paragraph of handwriting, or
     * a paragraph with a sum written beside it. Deliberately not one *kind* per outline type:
     * `groupContent` gathers objects and ink together, and what comes out is mixed by design.
     */
    Content,
}

/**
 * Something on the page with a rectangle, which is all the tiling and the fit ever need to know
 * about it.
 *
 * [id] is what the renderer looks a shift up by, so it has to name something the renderer can find:
 * an outline's id for the kinds the document holds, and the cluster's own id for ink.
 */
data class PdfItem(val id: String, val kind: PdfItemKind, val bounds: InkBounds)

/** One emitted sheet: which rectangle of the page canvas it shows, and where it sits in the grid. */
data class PdfTile(val column: Int, val row: Int, val area: InkBounds)

/** A translation applied for the export only. Nothing here is ever written to a document. */
data class PdfShift(val dx: Float, val dy: Float) {
    companion object {
        val NONE = PdfShift(0f, 0f)
    }
}

data class PdfPagePlan(
    val tiles: List<PdfTile>,
    /** Item id → the translation the fit gave it. Absent means none, which is the common case. */
    val shifts: Map<String, PdfShift>,
    /**
     * Whether this took PD3's single-sheet path: the page was already bound to the paper being
     * exported to, so its own corner is the sheet's corner and the margins are drawn *through*
     * rather than laid out around.
     */
    val bound: Boolean = false,
) {
    fun shiftFor(id: String): PdfShift = shifts[id] ?: PdfShift.NONE
}

/**
 * Cutting an infinite canvas into sheets — `memory/pdfExportPlan.md` PD3 and PD5, and
 * `memory/screenshots/canvastopdf.jpg`, which is what both are read off.
 *
 * Kept Android-free on purpose. The grid and the fit are the two parts of this feature that can be
 * wrong in ways no screenshot shows — a column emitted before the row below it, a box nudged half
 * off the sheet it was meant to be pulled onto — and they are the parts a JVM test can pin.
 */
object PageTiling {

    /**
     * How the canvas holding [items] is cut.
     *
     * [boundSheet] is the one page that is already a page: a document bound to a paper size whose
     * content still fits inside it. There is nothing to cut, and re-anchoring to the content's own
     * corner would shift a layout the user placed on a sheet deliberately, so it is returned whole.
     *
     * Everything else is tiled from the content's top-left corner, in tiles of
     * [tileWidthDp] × [tileHeightDp] — the printable area, not the sheet — **down a column and then
     * to the right**, which is the order written twice on the reference drawing. A tile nothing
     * overlaps is not emitted, because a canvas is mostly empty and the alternative is a dozen
     * blank sheets between two diagrams.
     *
     * The grid is measured off where the content **ended up**, not off where it started: the fit
     * breaks a page (see [Fitting]), and the sheet a break moves content onto has to exist.
     */
    fun plan(
        items: List<PdfItem>,
        tileWidthDp: Float,
        tileHeightDp: Float,
        fit: Boolean = true,
        boundSheet: InkBounds? = null,
    ): PdfPagePlan {
        if (boundSheet != null) {
            return PdfPagePlan(listOf(PdfTile(0, 0, boundSheet)), emptyMap(), bound = true)
        }

        val tileWidth = tileWidthDp.coerceAtLeast(PdfPaper.MIN_TILE_DP)
        val tileHeight = tileHeightDp.coerceAtLeast(PdfPaper.MIN_TILE_DP)
        val content = items.contentBounds()
            ?: return PdfPagePlan(listOf(PdfTile(0, 0, InkBounds(0f, 0f, tileWidth, tileHeight))), emptyMap())

        val shifts = if (fit) Fitting(items, content, tileWidth, tileHeight).run() else emptyMap()

        val placed = items.map { item ->
            val shift = shifts[item.id] ?: return@map item.bounds
            item.bounds.translated(shift.dx, shift.dy)
        }

        val columns = tileCount(placed.maxOf(InkBounds::right) - content.left, tileWidth)
        val rows = tileCount(placed.maxOf(InkBounds::bottom) - content.top, tileHeight)

        fun tileAt(column: Int, row: Int) = InkBounds(
            left = content.left + column * tileWidth,
            top = content.top + row * tileHeight,
            right = content.left + (column + 1) * tileWidth,
            bottom = content.top + (row + 1) * tileHeight,
        )

        val tiles = buildList {
            for (column in 0 until columns) {
                for (row in 0 until rows) {
                    val area = tileAt(column, row)
                    if (placed.any { it.overlaps(area) }) add(PdfTile(column, row, area))
                }
            }
        }
        // A page holding nothing but empty containers still exports as a page. A PDF with no pages
        // in it is not a document, and "I exported and got nothing" is a worse answer than a sheet.
        return PdfPagePlan(tiles.ifEmpty { listOf(PdfTile(0, 0, tileAt(0, 0))) }, shifts)
    }

    /**
     * PD5's fit: what a box hanging over a sheet edge does about it.
     *
     * The reference drawing shows a pull — the box slides back inside the tile its corner is in, by
     * exactly its overhang and no more, and lands beside what was already there. That is still the
     * first thing tried, and on an airy canvas it is the right answer: it keeps the box on the sheet
     * its neighbours are on, and costs no paper.
     *
     * **But a page of writing has nothing to slide back into.** Pulling a paragraph up by its
     * overhang puts it on top of the two lines above it, which is what a reader of the first version
     * of this saw at the foot of every full page. So when the pulled-back box would land on
     * something, the item does the other thing instead: it **starts on the next sheet**, at the top
     * of the strip below (or the left of the strip across), and *what follows it in that strip comes
     * with it* — the carry below. That is a page break, and it is the only version of this that does
     * not simply move the overlap somewhere else: closing the gap up behind the thing that moved
     * would put the next paragraph where this one used to be, on top of the one after it.
     *
     * Two axes, one procedure, run twice — across the columns first so that an item's column is
     * settled before the columns are walked down. An item **larger than a tile** is left exactly
     * where it is and cut: there is nowhere to put a drawing bigger than the paper, and shrinking it
     * would be redrawing the user's work rather than laying it out. So is the title band, which is
     * not content placed on the canvas but the top of the page.
     *
     * Nothing here is written to the document — it is a view of one export, and it dies with it.
     */
    private class Fitting(
        private val items: List<PdfItem>,
        private val content: InkBounds,
        private val tileWidth: Float,
        private val tileHeight: Float,
    ) {
        private val moved = mutableMapOf<String, PdfShift>()

        private val across = Axis(content.left, tileWidth, InkBounds::left, InkBounds::right) {
            PdfShift(it, 0f)
        }
        private val down = Axis(content.top, tileHeight, InkBounds::top, InkBounds::bottom) {
            PdfShift(0f, it)
        }

        fun run(): Map<String, PdfShift> {
            val movable = items.filter { it.kind != PdfItemKind.Title }
            flow(movable, along = across, strips = down)
            flow(movable, along = down, strips = across)
            return moved.filterValues { it != PdfShift.NONE }
        }

        /**
         * One pass: each strip of the grid walked in order, carrying every page break forward.
         *
         * [strips] is the axis the grid is divided on for this pass — the rows when going across,
         * the columns when going down — because a break belongs to one strip of the page and must
         * not move the strip beside it, which is a different sheet entirely.
         */
        private fun flow(movable: List<PdfItem>, along: Axis, strips: Axis) {
            movable
                .groupBy { strips.stripOf(strips.near(placed(it))) }
                .toSortedMap()
                .forEach { (_, strip) ->
                    var carry = 0f
                    strip
                        .sortedWith(compareBy({ along.near(it.bounds) }, PdfItem::id))
                        .forEach { item -> carry = place(item, carry, along) }
                }
        }

        /**
         * One item's turn on one axis, and the carry the rest of the strip inherits from it.
         *
         * The carry is applied before anything is measured, so the decision is made about where the
         * item actually is now rather than where the document put it.
         */
        private fun place(item: PdfItem, carry: Float, axis: Axis): Float {
            if (carry != 0f) move(item, axis.shift(carry))
            if (item.bounds.width > tileWidth || item.bounds.height > tileHeight) return carry

            val box = placed(item)
            val strip = axis.stripOf(axis.near(box))
            val edge = axis.origin + (strip + 1) * axis.size
            val over = axis.far(box) - edge
            if (over <= 0f) return carry

            val pulled = box.translated(axis.shift(-over))
            if (isClear(pulled, item)) {
                move(item, axis.shift(-over))
                return carry
            }
            // The grid's own guard, honoured here rather than only when it is drawn: a break past
            // the last strip would put the item on a sheet that is never emitted, and content that
            // silently is not in the file is the worst failure this has.
            if (strip + 1 >= MAX_TILES_PER_AXIS) return carry

            val onward = edge - axis.near(box)
            move(item, axis.shift(onward))
            return carry + onward
        }

        /** Whether [candidate] can be put down without landing on anything already placed. */
        private fun isClear(candidate: InkBounds, moving: PdfItem): Boolean =
            items.none { it.id != moving.id && candidate.overlaps(placed(it)) }

        private fun placed(item: PdfItem): InkBounds {
            val shift = moved[item.id] ?: return item.bounds
            return item.bounds.translated(shift.dx, shift.dy)
        }

        private fun move(item: PdfItem, by: PdfShift) {
            val shift = moved[item.id] ?: PdfShift.NONE
            moved[item.id] = PdfShift(shift.dx + by.dx, shift.dy + by.dy)
        }

        /**
         * One axis of the grid, so the fit above is written once and run twice.
         *
         * [shift] builds the whole `PdfShift` rather than letting the caller assemble one out of a
         * distance and a zero, because that zero has to be a **literal** `0f`. Arithmetic such as
         * `-maxOf(0f, overhang)` yields *negative* zero on the axis that does not move; it
         * translates identically and reads identically, and it is not equal to `0f` under
         * `Float.equals` — so a shift carrying one silently stops matching [PdfShift.NONE] and
         * every comparison downstream of it becomes a coin toss.
         */
        private class Axis(
            val origin: Float,
            val size: Float,
            val near: (InkBounds) -> Float,
            val far: (InkBounds) -> Float,
            val shift: (Float) -> PdfShift,
        ) {
            /** Which strip of the grid a coordinate falls in, counted from the content's corner. */
            fun stripOf(at: Float): Int = floor((at - origin) / size).toInt().coerceAtLeast(0)
        }
    }

    /**
     * How many tiles an extent needs, never fewer than one.
     *
     * Capped at [MAX_TILES_PER_AXIS], which is a guard rather than a limit anybody will meet: a
     * hundred A4 tiles is 116 feet of paper on one axis, and asking for more takes a coordinate
     * that should not exist. [Fitting] honours the same cap rather than breaking a page past it,
     * so the guard can never be the thing that drops content off the end of the grid.
     */
    private fun tileCount(extent: Float, tile: Float): Int =
        ceil(extent / tile).toInt().coerceIn(1, MAX_TILES_PER_AXIS)

    private const val MAX_TILES_PER_AXIS = 100
}

/** The box enclosing everything on the page — the cyan rectangle on the reference drawing. */
fun List<PdfItem>.contentBounds(): InkBounds? {
    if (isEmpty()) return null
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    forEach { item ->
        left = minOf(left, item.bounds.left)
        top = minOf(top, item.bounds.top)
        right = maxOf(right, item.bounds.right)
        bottom = maxOf(bottom, item.bounds.bottom)
    }
    return InkBounds(left, top, right, bottom)
}

private fun InkBounds.translated(shift: PdfShift): InkBounds = translated(shift.dx, shift.dy)

/** Shares area, rather than merely touching: an edge exactly on a tile boundary is not on the tile. */
internal fun InkBounds.overlaps(other: InkBounds): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

internal val InkBounds.width: Float get() = right - left

internal val InkBounds.height: Float get() = bottom - top
