package com.vivenotes.pdf

import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.PageStroke

/**
 * One indivisible thing on the page: an outline, a stroke, or a lasso group of strokes.
 *
 * The unit the grouping starts from, never the unit the fit moves — see [PdfGroup]. A lasso group
 * arrives already merged, because that grouping is a decision the user made and no amount of
 * whitespace may take it apart.
 */
class PdfAtom(
    val id: String,
    val bounds: InkBounds,
    /** The outline this is, when it is one. */
    val outlineId: String? = null,
    /** The strokes this is, when it is ink. More than one only for a lasso group. */
    val strokes: List<PageStroke> = emptyList(),
)

/** What the fit moves as one thing — the whole entity, whatever it is made of. */
class PdfGroup(
    val id: String,
    val bounds: InkBounds,
    val atoms: List<PdfAtom>,
) {
    val strokes: List<PageStroke> get() = atoms.flatMap { it.strokes }
}

/**
 * How much empty page separates two things that belong together, in page dp.
 *
 * **Per axis, because a page is not isotropic.** Two marks 20 dp apart across the line are letters
 * of a word; 20 dp down the page they are two lines of the same paragraph; 60 dp across is the
 * channel between two columns and 60 dp down is the space before the next heading. One radius
 * cannot tell those apart, and the first version of this used one — which is why a sum written
 * beside a paragraph was never part of it.
 *
 * Forty is chosen against the page's own ruling: the widest this app offers is
 * [com.vivenotes.model.RuleLines.GridLarge] at 38 dp of *pitch*, so consecutive lines of writing
 * leave far less than this and hold together, while the gap anyone leaves deliberately between two
 * blocks is larger.
 */
const val GROUP_GAP_X_DP: Float = 40f

/** The same distance down the page. Separate so the two can be tuned apart — see [GROUP_GAP_X_DP]. */
const val GROUP_GAP_Y_DP: Float = 40f

/**
 * Everything on the page, gathered into the entities the fit is allowed to move — the corrected
 * PD6, and what `memory/pdfExportPlan.md` now describes.
 *
 * A whitespace cut, on both axes, over **objects and ink together**. That last part is the point:
 * a diagram is a shape with handwriting on it, a working is a paragraph with a sum beside it, and
 * anything that treats those as separate things will pull one of them onto another sheet and leave
 * the other behind.
 */
fun groupContent(
    atoms: List<PdfAtom>,
    maxWidthDp: Float,
    maxHeightDp: Float,
    gapXDp: Float = GROUP_GAP_X_DP,
    gapYDp: Float = GROUP_GAP_Y_DP,
): List<PdfGroup> = segmentByWhitespace(
    items = atoms,
    bounds = PdfAtom::bounds,
    maxWidthDp = maxWidthDp,
    maxHeightDp = maxHeightDp,
    gapXDp = gapXDp,
    gapYDp = gapYDp,
).map { members ->
    PdfGroup(
        // Named after the *lowest* member id rather than the first, so the name does not depend on
        // the order the cutting happened to leave them in. See [inkAtoms] for why a name that is
        // not stable is not merely untidy: a plan is made against one measurement of a page and
        // drawn against the next, and a group the second measurement calls something else is a
        // group whose shift is silently dropped.
        id = "group:${members.minOf(PdfAtom::id)}",
        bounds = members.map(PdfAtom::bounds).union(),
        atoms = members,
    )
}

/**
 * A page's ink as atoms, with anything the lasso grouped kept whole.
 *
 * **The names must survive being asked for twice.** An export plans a page and then draws it, and
 * those are two separate loads of the same ink — so an atom whose name changes between them takes
 * its group's name with it, and the shift the fit worked out is then looked up under a name nothing
 * answers to. That is not a hypothetical: the first version of this named a stroke by its
 * [PageStroke.projection], which `PageStroke` documents as process-local and never stored, and every
 * ungrouped formula on a real page came out with its right-hand half sliced off, while the one that
 * happened to be a lasso group — named by an id the *document* holds — came out whole.
 *
 * So the name is the stored row id, plus an ordinal for the pieces an eraser has split one row into.
 * `InkPageLoader` decodes rows in a fixed order and replays operations in a fixed order, so those
 * pieces arrive in the same order every time.
 */
fun inkAtoms(strokes: List<PageStroke>): List<PdfAtom> {
    val measured = strokes.mapNotNull { stroke -> stroke.pageBounds?.let { stroke to it } }
    val (grouped, loose) = measured.partition { it.first.groupId != null }
    val fromGroups = grouped
        .groupBy { it.first.groupId!! }
        .map { (groupId, members) ->
            PdfAtom(
                id = "lasso:$groupId",
                bounds = members.map { it.second }.union(),
                strokes = members.map { it.first },
            )
        }
    val seen = mutableMapOf<String, Int>()
    val fromStrokes = loose.map { (stroke, bounds) ->
        val piece = seen.merge(stroke.id, 1, Int::plus)!! - 1
        PdfAtom(id = "ink:${stroke.id}:$piece", bounds = bounds, strokes = listOf(stroke))
    }
    return fromGroups + fromStrokes
}

/**
 * Recursive whitespace cutting: split a block wherever a lane of empty page runs right through it,
 * and stop when none does.
 *
 * This is the classic XY cut, and it is the right shape for the question because **it only ever
 * divides at emptiness**. A rule like "these two rectangles are near each other" is transitive in a
 * way page layout is not: one stray stroke bridging two columns joins them for ever. A cut that has
 * to find a clear lane across the whole block cannot be bridged by proximity, and what it leaves is
 * what a reader would circle with a finger.
 *
 * Two things make a cut:
 *
 *  1. **A gap wider than its axis's tolerance.** The ordinary case, and what decides where one
 *     entity stops and the next begins.
 *  2. **A block too large for a sheet, at whatever gap it has.** Without this, a densely written
 *     page is one entity that fits nowhere and the fit gives up on it entirely. With it, the page
 *     breaks fall in the widest channel available — between paragraphs rather than through a line —
 *     which is the whole point of the option. The over-size axis is tried first, because that is the
 *     one that has to shrink.
 *
 * Iterative rather than recursive so a page of thousands of strokes cannot exhaust the stack. It
 * terminates because every cut strictly shrinks a block and a block of one is a leaf.
 *
 * Generic over its item type and taking geometry through a lambda, so this — the part that decides
 * what a page is *made of* — is covered by JVM tests. A [PageStroke] carries a native mesh and
 * cannot be built off a device.
 */
internal fun <T> segmentByWhitespace(
    items: List<T>,
    bounds: (T) -> InkBounds,
    maxWidthDp: Float,
    maxHeightDp: Float,
    gapXDp: Float = GROUP_GAP_X_DP,
    gapYDp: Float = GROUP_GAP_Y_DP,
): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    val leaves = mutableListOf<List<T>>()
    val pending = ArrayDeque<List<T>>()
    pending += items

    while (pending.isNotEmpty()) {
        val block = pending.removeFirst()
        if (block.size == 1) {
            leaves += block
            continue
        }
        val box = block.map(bounds).union()
        val horizontal = block.widestGap(bounds, horizontal = true)
        val vertical = block.widestGap(bounds, horizontal = false)

        val cut = chooseCut(
            horizontal = horizontal,
            vertical = vertical,
            gapXDp = gapXDp,
            gapYDp = gapYDp,
            tooWide = box.right - box.left > maxWidthDp,
            tooTall = box.bottom - box.top > maxHeightDp,
        )
        if (cut == null) {
            leaves += block
            continue
        }
        val (near, far) = block.partition {
            val edge = bounds(it)
            (if (cut.horizontal) edge.left else edge.top) < cut.at
        }
        // Cannot happen — the gap came from between two sorted, non-overlapping runs — but a block
        // that failed to divide would be requeued unchanged for ever, and that is not a risk worth
        // taking for the sake of an assertion.
        if (near.isEmpty() || far.isEmpty()) {
            leaves += block
            continue
        }
        pending += near
        pending += far
    }
    return leaves
}

/** A lane of empty page: how wide it is, where to cut it, and which way it runs. */
private class Lane(val size: Float, val at: Float, val horizontal: Boolean)

/**
 * Which lane to cut, or null to stop.
 *
 * The tolerances come first and are compared as *ratios* of their own axis's tolerance, so the two
 * axes stay comparable however differently they are tuned. Only when neither clears its tolerance
 * does size get a say, and then only because the block does not fit a sheet.
 */
private fun chooseCut(
    horizontal: Lane?,
    vertical: Lane?,
    gapXDp: Float,
    gapYDp: Float,
    tooWide: Boolean,
    tooTall: Boolean,
): Lane? {
    val horizontalScore = horizontal?.let { it.size / gapXDp } ?: 0f
    val verticalScore = vertical?.let { it.size / gapYDp } ?: 0f
    if (horizontalScore >= 1f || verticalScore >= 1f) {
        return if (horizontalScore >= verticalScore) horizontal else vertical
    }
    // Too big for any sheet: cut at whatever lane there is, on the axis that has to shrink first.
    if (tooWide && horizontal != null) return horizontal
    if (tooTall && vertical != null) return vertical
    if ((tooWide || tooTall)) return horizontal ?: vertical
    return null
}

/**
 * The widest lane of empty page crossing this block on one axis, or null when the items overlap
 * end to end.
 *
 * A sweep over the items' intervals on that axis: sorted by their near edge, a run of overlapping
 * intervals is one band and the space to the next band is a lane. The cut is placed in the middle of
 * the lane, which is a coordinate no item can straddle — that is what makes the partition below safe.
 */
private fun <T> List<T>.widestGap(bounds: (T) -> InkBounds, horizontal: Boolean): Lane? {
    val intervals = map {
        val box = bounds(it)
        if (horizontal) box.left to box.right else box.top to box.bottom
    }.sortedBy { it.first }

    var reach = intervals.first().second
    var widest: Lane? = null
    for (index in 1 until intervals.size) {
        val (start, end) = intervals[index]
        val gap = start - reach
        if (gap > 0f && (widest == null || gap > widest.size)) {
            widest = Lane(gap, reach + gap / 2f, horizontal)
        }
        reach = maxOf(reach, end)
    }
    return widest
}

/** The box enclosing all of them, or null when there are none. */
internal fun List<InkBounds>.unionOrNull(): InkBounds? = if (isEmpty()) null else union()

internal fun List<InkBounds>.union(): InkBounds = reduce { a, b ->
    InkBounds(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom),
    )
}
