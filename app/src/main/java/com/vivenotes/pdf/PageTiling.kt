package com.vivenotes.pdf

import com.vivenotes.ink.InkBounds
import kotlin.math.ceil

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

        val columns = tileCount(content.right - content.left, tileWidth)
        val rows = tileCount(content.bottom - content.top, tileHeight)

        fun tileAt(column: Int, row: Int) = InkBounds(
            left = content.left + column * tileWidth,
            top = content.top + row * tileHeight,
            right = content.left + (column + 1) * tileWidth,
            bottom = content.top + (row + 1) * tileHeight,
        )

        val shifts = if (!fit) {
            emptyMap()
        } else {
            buildMap {
                items.forEach { item ->
                    val shift = item.fittedInto(content, tileWidth, tileHeight, columns, rows, ::tileAt)
                    if (shift != PdfShift.NONE) put(item.id, shift)
                }
            }
        }

        val placed = items.map { item ->
            val shift = shifts[item.id] ?: return@map item.bounds
            item.bounds.translated(shift.dx, shift.dy)
        }

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
     * The least translation that puts the whole of [this] inside the tile its corner is in, or
     * [PdfShift.NONE] when it is already there or cannot be helped.
     *
     * Only ever left and up: the corner is inside the tile by construction, so the only edges that
     * can hang over are the right and the bottom. An item **wider or taller than a tile** is left
     * exactly where it is — there is nowhere to put a drawing bigger than the paper, and shrinking
     * it would be redrawing the user's work rather than laying it out.
     */
    private fun PdfItem.fittedInto(
        content: InkBounds,
        tileWidth: Float,
        tileHeight: Float,
        columns: Int,
        rows: Int,
        tileAt: (Int, Int) -> InkBounds,
    ): PdfShift {
        if (kind == PdfItemKind.Title) return PdfShift.NONE
        if (bounds.right - bounds.left > tileWidth) return PdfShift.NONE
        if (bounds.bottom - bounds.top > tileHeight) return PdfShift.NONE

        val column = ((bounds.left - content.left) / tileWidth).toInt().coerceIn(0, columns - 1)
        val row = ((bounds.top - content.top) / tileHeight).toInt().coerceIn(0, rows - 1)
        val tile = tileAt(column, row)
        // Written out rather than as `-maxOf(0f, overhang)`, which yields **negative zero** on the
        // axis that does not move. It translates identically and reads identically, and it is not
        // equal to `0f` under `Float.equals` — so a `PdfShift` carrying one silently stops matching
        // [PdfShift.NONE] and every comparison downstream of it becomes a coin toss.
        val overRight = bounds.right - tile.right
        val overBottom = bounds.bottom - tile.bottom
        val dx = if (overRight > 0f) -overRight else 0f
        val dy = if (overBottom > 0f) -overBottom else 0f
        return if (dx == 0f && dy == 0f) PdfShift.NONE else PdfShift(dx, dy)
    }

    /**
     * How many tiles an extent needs, never fewer than one.
     *
     * Capped at [MAX_TILES_PER_AXIS], which is a guard rather than a limit anybody will meet: a
     * hundred A4 tiles is 116 feet of paper on one axis, and the only way to ask for more is a
     * coordinate that should not exist.
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

/** Shares area, rather than merely touching: an edge exactly on a tile boundary is not on the tile. */
internal fun InkBounds.overlaps(other: InkBounds): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

internal val InkBounds.width: Float get() = right - left

internal val InkBounds.height: Float get() = bottom - top
