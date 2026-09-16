package com.vivenotes.ink

import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import kotlin.math.floor
import kotlin.math.hypot

/**
 * What is selected on the page, across every kind of object on it.
 *
 * Selection is a page-level concept, not a per-layer one: a lasso that returns only ink is one that
 * needs rewriting the first time a shape is inside it. Before this, ink's selection lived in
 * `LassoGesture` and a shape's was a bare id on `NotesUiState`, so the two could not describe one
 * loop drawn around both.
 *
 * Two id sets rather than a set of sealed object references. Everything downstream is already these
 * strokes plus these shapes — different repositories, different transforms, ink persisting a move
 * for replay where a shape translates its segments — so a sealed set would be unpacked into these
 * two at every use site to say the same thing.
 *
 * [bounds] and [path] are in page units, the space ink is stored in and the space an
 * `Outline.Shape`'s coordinates are already in, so no kind has to convert to be selected.
 *
 * The ink half is handed to the existing ink operations unchanged, through [inkHalf].
 */
data class CanvasSelection(
    /** The loop that made the selection. Empty when a tap made it: there was no loop. */
    val path: List<InkPoint> = emptyList(),
    val inkIds: Set<String> = emptySet(),
    val shapeIds: Set<String> = emptySet(),
    /** Tables. The third id set, added for the third kind. */
    val tableIds: Set<String> = emptySet(),
    /** Equations placed on the canvas — the fourth kind, and the fourth id set. */
    val equationIds: Set<String> = emptySet(),
    /** Pictures, and the fifth kind. One line here, exactly as [othersEmpty] promised. */
    val imageIds: Set<String> = emptySet(),
    /** Live ink projections, including pieces that share a row id after a partial erase. */
    val projections: Set<InkProjectionKey> = emptySet(),
    /**
     * The locked groups held, and empty when nothing held is locked.
     *
     * A set rather than a flag because a locked group is the unit: [reconcile] widens the selection
     * to every member of every group named here, which is what makes touching one of them hold all
     * of it — the same thing it does for an ink `groupId`.
     *
     * Non-empty means everything held is locked, and that is an invariant: [selectWithLasso] drops
     * locked objects from a loop that caught anything unlocked, so a selection is either free of
     * locked objects or made of nothing else. [isLocked] can only be all-or-nothing because of that.
     *
     * More than one group, because a loop can close around two of them.
     */
    val lockGroups: Set<String> = emptySet(),
    val bounds: InkBounds,
) {
    val isEmpty: Boolean
        get() = inkIds.isEmpty() && shapeIds.isEmpty() && tableIds.isEmpty() &&
            equationIds.isEmpty() && imageIds.isEmpty()

    /**
     * Whether what is held is locked, and so refuses to be moved or resized.
     *
     * Read by every gesture that would transform the selection and by the toolkit's lock button. Not
     * read by copy, recolour or delete: locked means "stays put", not "protected".
     */
    val isLocked: Boolean get() = lockGroups.isNotEmpty()

    /** True when only one kind is held, which is what decides the per-kind half of the toolkit. */
    val isInkOnly: Boolean get() = inkIds.isNotEmpty() && othersEmpty(inkIds)
    val isShapeOnly: Boolean get() = shapeIds.isNotEmpty() && othersEmpty(shapeIds)
    val isTableOnly: Boolean get() = tableIds.isNotEmpty() && othersEmpty(tableIds)
    val isEquationOnly: Boolean get() = equationIds.isNotEmpty() && othersEmpty(equationIds)
    val isImageOnly: Boolean get() = imageIds.isNotEmpty() && othersEmpty(imageIds)

    /**
     * Every id set except the one asked about is empty.
     *
     * Written once rather than four times: the four `is…Only` flags were hand-rolled conjunctions
     * that each had to name every other kind, so adding equations meant editing all three and
     * forgetting one would have claimed a mixed selection was pure. The next kind adds one line here.
     */
    private fun othersEmpty(own: Set<String>): Boolean =
        listOf(inkIds, shapeIds, tableIds, equationIds, imageIds).all { it === own || it.isEmpty() }

    fun holdsShape(shapeId: String): Boolean = shapeId in shapeIds

    fun holdsEquation(equationId: String): Boolean = equationId in equationIds

    fun holdsImage(imageId: String): Boolean = imageId in imageIds

    /** The ink half, in the shape the ink move/resize/replay path already takes. */
    fun inkHalf(): InkLassoSelection? = if (inkIds.isEmpty()) {
        null
    } else {
        InkLassoSelection(path = path, targetIds = inkIds, projections = projections, bounds = bounds)
    }

    fun holdsTable(tableId: String): Boolean = tableId in tableIds

    fun translated(dx: Float, dy: Float): CanvasSelection = copy(
        path = path.map { InkPoint(it.x + dx, it.y + dy) },
        bounds = bounds.translated(dx, dy),
    )

    fun scaled(anchor: InkPoint, scaleX: Float, scaleY: Float): CanvasSelection = copy(
        path = path.map {
            InkPoint(
                anchor.x + (it.x - anchor.x) * scaleX,
                anchor.y + (it.y - anchor.y) * scaleY,
            )
        },
        bounds = bounds.scaled(anchor, scaleX, scaleY),
    )

    /**
     * Re-reads the selection against the page it describes, dropping what is gone and re-measuring
     * what moved.
     *
     * Returns null when nothing it named survives, which is what makes a delete or an undo dismiss
     * the selection rather than leave a rectangle floating over nothing. Ink expands through
     * `groupId`, because touching any member of a group selects all of it; a shape is already one
     * object and has nothing to expand into.
     *
     * Ink survives by [projections], never by [inkIds], or this would undo what the loop decided:
     * `selectWithLasso` takes the projections the hand circled — one half of a cut line, not the row
     * both halves share — and this runs on every change to the page, so re-reading by row id would
     * put the other half back on the next recomposition.
     *
     * The cost of keying on a projection number is that a rebuild which renumbers drops the
     * selection instead of re-measuring it. That is not a new exposure — the move preview and
     * `InkSelectionRenderer` both already match on `projectionKey` — and losing the rectangle is the
     * better of the two failures.
     */
    fun reconcile(
        strokes: List<PageStroke>,
        shapes: List<Outline.Shape>,
        tables: List<TableBounds> = emptyList(),
        equations: List<Outline.Equation> = emptyList(),
        images: List<Outline.Image> = emptyList(),
    ): CanvasSelection? {
        val liveShapes = shapes.filter { it.id in shapeIds }
        val liveTables = tables.filter { it.id in tableIds }
        val liveEquations = equations.filter { it.id in equationIds }
        val liveImages = images.filter { it.id in imageIds }
        val directInk = strokes.filter { it.projectionKey in projections }
        if (liveShapes.isEmpty() && directInk.isEmpty() && liveTables.isEmpty() &&
            liveEquations.isEmpty() && liveImages.isEmpty()
        ) {
            return null
        }

        val groups = directInk.mapNotNull(PageStroke::groupId).toSet()
        val expandedInk = if (groups.isEmpty()) {
            directInk
        } else {
            strokes.filter { it.projectionKey in projections || it.groupId != null && it.groupId in groups }
        }
        // A locked group is one object, so holding any member holds all of it — the same widening
        // ink gets from `groupId` just above, and the reason this runs on a *fresh* selection too:
        // a tap on one picture of a locked pair, or a loop that closed around half of one, arrives
        // here naming only what it touched. Re-read rather than carried, so unlocking a group frees
        // the selection on the same pass that clears the field.
        val heldLocks = (
            liveShapes.mapNotNull(Outline.Shape::lockGroup) +
                liveTables.mapNotNull(TableBounds::lockGroup) +
                liveEquations.mapNotNull(Outline.Equation::lockGroup) +
                liveImages.mapNotNull(Outline.Image::lockGroup)
            ).toSet()
        val keptShapes = if (heldLocks.isEmpty()) {
            liveShapes
        } else {
            shapes.filter { it.id in shapeIds || it.lockGroup.belongsTo(heldLocks) }
        }
        val keptTables = if (heldLocks.isEmpty()) {
            liveTables
        } else {
            tables.filter { it.id in tableIds || it.lockGroup.belongsTo(heldLocks) }
        }
        val keptEquations = if (heldLocks.isEmpty()) {
            liveEquations
        } else {
            equations.filter { it.id in equationIds || it.lockGroup.belongsTo(heldLocks) }
        }
        val keptImages = if (heldLocks.isEmpty()) {
            liveImages
        } else {
            images.filter { it.id in imageIds || it.lockGroup.belongsTo(heldLocks) }
        }

        val measured = expandedInk.mapNotNull(PageStroke::pageBounds) +
            keptShapes.map(Outline.Shape::pageBounds) +
            keptTables.map(TableBounds::bounds) +
            keptEquations.map(Outline.Equation::pageBounds) +
            keptImages.map(Outline.Image::pageBounds)
        val union = measured.unionBounds() ?: return null

        return copy(
            inkIds = expandedInk.map(PageStroke::id).toSet(),
            shapeIds = keptShapes.map(Outline.Shape::id).toSet(),
            tableIds = keptTables.map(TableBounds::id).toSet(),
            equationIds = keptEquations.map(Outline.Equation::id).toSet(),
            imageIds = keptImages.map(Outline.Image::id).toSet(),
            projections = expandedInk.map(PageStroke::projectionKey).toSet(),
            lockGroups = heldLocks,
            bounds = union,
        )
    }

    companion object {
        /** One tapped shape. The whole of a tap selection: no loop, no ink. */
        fun ofShape(shape: Outline.Shape): CanvasSelection = CanvasSelection(
            shapeIds = setOf(shape.id),
            lockGroups = setOfNotNull(shape.lockGroup),
            bounds = shape.pageBounds(),
        )

        /** One table, selected by putting a caret in any of its cells. */
        fun ofTable(table: TableBounds): CanvasSelection = CanvasSelection(
            tableIds = setOf(table.id),
            lockGroups = setOfNotNull(table.lockGroup),
            bounds = table.bounds,
        )

        /** One tapped equation, measured from the box the document already knows. */
        fun ofEquation(equation: Outline.Equation): CanvasSelection = CanvasSelection(
            equationIds = setOf(equation.id),
            lockGroups = setOfNotNull(equation.lockGroup),
            bounds = equation.pageBounds(),
        )

        /** One tapped picture, measured from its frame rather than from its pixels. */
        fun ofImage(image: Outline.Image): CanvasSelection = CanvasSelection(
            imageIds = setOf(image.id),
            lockGroups = setOfNotNull(image.lockGroup),
            bounds = image.pageBounds(),
        )
    }
}

/**
 * The one line-like shape this selection holds by itself, or null.
 *
 * The question every piece of selection chrome has to ask before it draws a box with four corners,
 * because a line and an arrow have neither ([ShapeKind.hasEnds]): they carry a handle on each of
 * their own two ends instead. Asked here rather than at each drawing site so that a tapped line and
 * a lassoed one cannot end up with different chrome.
 *
 * Alone, and only alone: a loop holding a line and anything else is a group, and a group is moved
 * and scaled as a rectangle whatever is inside it.
 */
fun CanvasSelection?.lineShape(shapes: List<Outline.Shape>): Outline.Shape? = this
    ?.takeIf { it.isShapeOnly && it.shapeIds.size == 1 }
    ?.let { held -> shapes.firstOrNull { it.id in held.shapeIds } }
    ?.takeIf { it.kind.hasEnds }

/**
 * True when this selection is ink and rules: strokes, plus at most the shapes that are strokes in
 * all but storage — the line and the arrow ([ShapeKind.hasEnds]).
 *
 * The Math toolkit's gate. A fraction bar drawn with the Line tool is a fraction bar: it is part of
 * the formula on the page, and refusing to hand it to the recogniser meant `\frac` came back as two
 * numbers side by side. Same for a vector's arrow and the bar over a radical. What makes those kinds
 * admissible is that they are marks — a stroke the user chose to draw straight — so the test is the
 * kind, and a rectangle or a cube is still no part of an equation.
 *
 * Ink is still required: a lasso holding only lines is a diagram, not a formula, and handing it to a
 * formula model would produce confident nonsense.
 *
 * A shape id that resolves to nothing fails the test rather than being skipped: a selection naming
 * something the page no longer has is one whose contents cannot be vouched for.
 */
fun CanvasSelection?.isInkAndLines(shapes: List<Outline.Shape>): Boolean {
    val held = this ?: return false
    if (held.inkIds.isEmpty()) return false
    if (held.tableIds.isNotEmpty() || held.equationIds.isNotEmpty() || held.imageIds.isNotEmpty()) {
        return false
    }
    return held.shapeIds.all { id -> shapes.firstOrNull { it.id == id }?.kind?.hasEnds == true }
}

/**
 * A table as the selection sees it: an id, and the rectangle the canvas measured for it.
 *
 * Not `Outline.Table`, deliberately. A table's height is whatever its cells' text wraps to and the
 * document only stores each row's floor, so the model's idea of how tall a table is runs short the
 * moment a cell overflows — and a selection rectangle that runs short is a lasso that misses and a
 * toolbar that sits on top of the thing it belongs to. The canvas laid the table out, so it says.
 *
 * Bounds are in page units, like everything else here.
 */
data class TableBounds(
    val id: String,
    val bounds: InkBounds,
    /** The table's `Outline.Table.lockGroup`, carried because the lasso never sees the outline. */
    val lockGroup: String? = null,
)

/**
 * Membership of a locked group, written once because four kinds ask it the same way — and because a
 * nullable id cannot be handed to `Set<String>.contains` without saying so somewhere.
 */
internal fun String?.belongsTo(groups: Set<String>): Boolean = this != null && this in groups

/**
 * The shared prime object clipboard's contents.
 *
 * One clipboard holding every kind, rather than one per kind, so a loop drawn round a stroke and a
 * shape copies both and pastes both. Shallow by design: a native `Stroke` is immutable and an
 * `Outline.Shape` is a data class.
 */
data class CanvasClipboard(
    val strokes: List<PageStroke> = emptyList(),
    val shapes: List<Outline.Shape> = emptyList(),
    /**
     * Text containers, carried whole.
     *
     * `Outline.Text` rather than the `OutlineBox` the canvas lays out with, because that one is
     * geometry alone and a copied text box without its text is a rectangle. This is the one place
     * the ViewModel's two halves of a container are put back together outside of a save.
     */
    val texts: List<Outline.Text> = emptyList(),
    /**
     * Tables, carried with every cell's blocks.
     *
     * Read out of the ViewModel's block map on the way in rather than off `uiState.tables`, whose
     * cells hold what the page was loaded with and go stale the moment anything is typed. A table
     * copied without what is in it is a grid of lines.
     */
    val tables: List<Outline.Table> = emptyList(),
    /** Equations, which carry their whole selves: the source is the object. */
    val equations: List<Outline.Equation> = emptyList(),
) {
    val isEmpty: Boolean
        get() = strokes.isEmpty() && shapes.isEmpty() && texts.isEmpty() && tables.isEmpty() &&
            equations.isEmpty()
}

/**
 * One loop, one selection, however many kinds of thing are inside it.
 *
 * The ink half is the existing [selectWithLasso] on strokes, untouched. The shape half applies the
 * same rule ink uses ([isInsideLasso]): every point of the object must be in or near the loop, so a
 * shape that is only half circled is left alone exactly as a half-circled stroke is.
 *
 * The gesture has to be a closed path ([closesIntoALoop]). Every containment test here closes the
 * path for itself — `pointInPolygon` walks back from the last vertex to the first whether the hand
 * did or not — so an L drawn beside a drawing used to select everything inside the triangle the app
 * had imagined. Asking the gesture whether it ran back into itself, once and before any of that, is
 * what makes the drawn shape and the tested shape the same shape.
 */
internal fun selectWithLasso(
    strokes: List<PageStroke>,
    shapes: List<Outline.Shape>,
    tables: List<TableBounds> = emptyList(),
    equations: List<Outline.Equation> = emptyList(),
    images: List<Outline.Image> = emptyList(),
    path: List<InkPoint>,
    edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE,
): CanvasSelection? {
    if (path.size < 3 || !path.closesIntoALoop(edgeTolerance)) return null
    val ink = strokes.selectWithLasso(path, edgeTolerance)
    val caughtShapes = shapes.filter { it.isInsideLasso(path, edgeTolerance) }
    val caughtTables = tables.filter { it.bounds.isInsideLasso(path, edgeTolerance) }
    // A rectangle, so the same four-corner rule a table is held to — see [isInsideLasso].
    val caughtEquations = equations.filter { it.pageBounds().isInsideLasso(path, edgeTolerance) }
    // A picture is a rectangle too, and is caught by its frame rather than by what is in it: a
    // photograph of a circle is still a photograph, and half-circling one leaves it alone.
    val caughtImages = images.filter { it.pageBounds().isInsideLasso(path, edgeTolerance) }
    // The loop passes over what is locked, unless locked is all it caught, which is how a locked
    // group is picked up again to be unlocked. Applied to the catch rather than to the hit test, so
    // the rule is stated once for every kind, and so the "unless" can be answered at all: whether a
    // locked object is dropped depends on what else the loop took.
    //
    // Ink settles the question by being present. It has no lock of its own, so a loop that caught
    // ink has caught something unlocked by definition.
    val caughtUnlocked = ink != null ||
        caughtShapes.any { it.lockGroup == null } ||
        caughtTables.any { it.lockGroup == null } ||
        caughtEquations.any { it.lockGroup == null } ||
        caughtImages.any { it.lockGroup == null }
    val heldShapes = if (caughtUnlocked) caughtShapes.filter { it.lockGroup == null } else caughtShapes
    val heldTables = if (caughtUnlocked) caughtTables.filter { it.lockGroup == null } else caughtTables
    val heldEquations =
        if (caughtUnlocked) caughtEquations.filter { it.lockGroup == null } else caughtEquations
    val heldImages = if (caughtUnlocked) caughtImages.filter { it.lockGroup == null } else caughtImages

    if (ink == null && heldShapes.isEmpty() && heldTables.isEmpty() &&
        heldEquations.isEmpty() && heldImages.isEmpty()
    ) {
        return null
    }

    val measured = (ink?.let { listOf(it.bounds) } ?: emptyList()) +
        heldShapes.map(Outline.Shape::pageBounds) +
        heldTables.map(TableBounds::bounds) +
        heldEquations.map(Outline.Equation::pageBounds) +
        heldImages.map(Outline.Image::pageBounds)
    val union = measured.unionBounds() ?: return null
    return CanvasSelection(
        path = path,
        inkIds = ink?.targetIds.orEmpty(),
        shapeIds = heldShapes.map(Outline.Shape::id).toSet(),
        tableIds = heldTables.map(TableBounds::id).toSet(),
        equationIds = heldEquations.map(Outline.Equation::id).toSet(),
        imageIds = heldImages.map(Outline.Image::id).toSet(),
        projections = ink?.projections.orEmpty(),
        // Whatever survived the rule above is either all unlocked or all locked, so this is empty or
        // it is the groups the loop closed around. `reconcile` widens it to whole groups.
        lockGroups = (
            heldShapes.mapNotNull(Outline.Shape::lockGroup) +
                heldTables.mapNotNull(TableBounds::lockGroup) +
                heldEquations.mapNotNull(Outline.Equation::lockGroup) +
                heldImages.mapNotNull(Outline.Image::lockGroup)
            ).toSet(),
        bounds = union,
    )
}

/**
 * Whether the gesture is a closed path: somewhere it runs back into itself.
 *
 * The test is that the stroke meets the stroke, not that it ends near where it began. A C left a
 * hair open is a curve, and how nearly it closed is not the question — a lasso encloses, and an
 * unclosed path encloses nothing. The only allowance is [touch], the reach the lasso already judges
 * its edges by: a hand that lifts on its own line lands a sample short of it, which is a missing
 * point rather than a gap. Reading it off the same number makes the allowance a constant on screen,
 * so closing a loop is neither harder nor easier for being drawn zoomed out.
 *
 * Asked of the whole path rather than of the last point, which admits the loop that carries on past
 * its own start — closing and then running on is how a loop is usually drawn.
 *
 * Two segments only count as meeting if the pen travelled [CLOSING_TRAVEL] between them, and without
 * that clause this rule does nothing: `LassoGesture` records a point every half page unit, so the
 * segment two along is inside the touch reach and every gesture would be declared closed at its
 * third sample. Distance along the path, never a count of samples, since how many samples a stretch
 * holds depends on how fast the hand was moving.
 *
 * Cost: segments are dropped into a coarse grid and only compared with segments already in a cell
 * they touch — two segments that meet always share one — and the travel between them is a
 * subtraction that settles most of those.
 *
 * Deliberately not asked in [LassoShape], which is where a replayed move re-identifies the ink it
 * moved. A move stored before this rule existed can name a path that does not satisfy it, and a
 * replay that declined to apply would put that ink back where it started on the next page open.
 */
private fun List<InkPoint>.closesIntoALoop(touch: Float): Boolean {
    if (size < 4) return false
    val reach = maxOf(touch, CLOSING_TOUCH_FLOOR)
    val travelled = travelledTo()
    val apart = reach * CLOSING_TRAVEL
    val buckets = HashMap<Long, MutableList<Int>>()
    for (index in 0 until size - 1) {
        val from = this[index]
        val to = this[index + 1]
        val minColumn = cellOf(minOf(from.x, to.x) - reach, reach)
        val maxColumn = cellOf(maxOf(from.x, to.x) + reach, reach)
        val minRow = cellOf(minOf(from.y, to.y) - reach, reach)
        val maxRow = cellOf(maxOf(from.y, to.y) + reach, reach)
        for (column in minColumn..maxColumn) {
            for (row in minRow..maxRow) {
                val cell = (column.toLong() shl 32) or (row.toLong() and 0xFFFF_FFFFL)
                val sharing = buckets.getOrPut(cell) { mutableListOf() }
                val met = sharing.any { earlier ->
                    travelled[index] - travelled[earlier + 1] > apart && meets(earlier, index, reach)
                }
                if (met) return true
                sharing += index
            }
        }
    }
    return false
}

/** How far the pen had travelled by each sample, so two segments can be told how far apart they are. */
private fun List<InkPoint>.travelledTo(): FloatArray {
    val distances = FloatArray(size)
    for (index in 1 until size) {
        val from = this[index - 1]
        val to = this[index]
        distances[index] = distances[index - 1] + hypot(to.x - from.x, to.y - from.y)
    }
    return distances
}

/** Whether the segments starting at [first] and [second] cross, or run into one another. */
private fun List<InkPoint>.meets(first: Int, second: Int, reach: Float): Boolean {
    val a = this[first]
    val b = this[first + 1]
    val c = this[second]
    val d = this[second + 1]
    if (crosses(a, b, c, d)) return true
    // Touching without crossing is the ordinary way a hand closes a loop: it stops on the line.
    val touching = reach * reach
    return a.distanceSquaredToSegment(c, d) <= touching ||
        b.distanceSquaredToSegment(c, d) <= touching ||
        c.distanceSquaredToSegment(a, b) <= touching ||
        d.distanceSquaredToSegment(a, b) <= touching
}

/** Proper crossing: each segment has one end on either side of the other's line. */
private fun crosses(a: InkPoint, b: InkPoint, c: InkPoint, d: InkPoint): Boolean {
    val first = side(a, b, c)
    val second = side(a, b, d)
    val third = side(c, d, a)
    val fourth = side(c, d, b)
    return first * second < 0f && third * fourth < 0f
}

private fun side(from: InkPoint, to: InkPoint, point: InkPoint): Float =
    (to.x - from.x) * (point.y - from.y) - (to.y - from.y) * (point.x - from.x)

private fun cellOf(coordinate: Float, reach: Float): Int = floor(coordinate / (reach * 4f)).toInt()

/**
 * The least a gesture's closing allowance may be, in page units, however far it is zoomed in.
 *
 * Samples are recorded half a page unit apart, so a couple of them is the finest a lift can be judged
 * by at all — below this, closing would be asked to be more exact than the path is recorded.
 */
private const val CLOSING_TOUCH_FLOOR = 2f

/**
 * How far the pen must travel between two segments before they are allowed to be the same place, as
 * a multiple of the touch reach.
 *
 * Below this the two are the same stroke of the hand rather than a return to it: a wobble that comes
 * back within its own width is a wobble, and every path revisits itself at that scale. Above it, ink
 * a hand has genuinely come back around to is caught.
 */
private const val CLOSING_TRAVEL = 8f

/**
 * The object under a tap, or null — one tap, one object, with the lasso in hand.
 *
 * The lasso used to answer a tap with nothing: one point is not a loop, so [selectWithLasso]
 * returned null and the tap only cleared whatever was held. Every other pointer on the canvas
 * already selected by tap, so the lasso was the one tool in which pointing at a thing did not pick
 * it up.
 *
 * Each kind is judged by exactly the rule its own layer judges a tap by, so what a tap means does
 * not depend on which tool is in hand: a picture and a formula by their frame, a shape by its
 * outline within [TAP_REACH] rather than by the empty rectangle around it — pointing at the middle
 * of a large circle is pointing at the page it encloses. Last drawn wins within a kind, and the
 * kinds are asked in the order the layers are nested: pictures, then formulas, then shapes.
 *
 * Tables are deliberately not here, and are missing from the signature rather than filtered out of
 * it, so putting them back has to be a decision rather than an oversight. A table is a large
 * rectangle of mostly empty cells, and an ink-only one is a ruling drawn to be written inside: a tap
 * in it is how the ink there is reached, so letting the grid answer for that area would put every
 * stroke inside a table out of reach. A loop still takes a table as it always did.
 *
 * Ink is absent for a different reason: a tap on a stroke selects nothing today with any tool, so
 * there is no existing rule here to match.
 */
internal fun selectByTap(
    shapes: List<Outline.Shape>,
    equations: List<Outline.Equation>,
    images: List<Outline.Image>,
    point: InkPoint,
): CanvasSelection? {
    images.asReversed().firstOrNull { it.pageBounds().contains(point) }
        ?.let { return CanvasSelection.ofImage(it) }
    equations.asReversed().firstOrNull { it.pageBounds().contains(point) }
        ?.let { return CanvasSelection.ofEquation(it) }
    return shapes.asReversed()
        .firstOrNull { shape -> shape.isUnderTap(point) }
        ?.let(CanvasSelection::ofShape)
}

/** A shape is its outline and not the rectangle around it — `ShapeLayer.topmostNear`'s rule. */
private fun Outline.Shape.isUnderTap(point: InkPoint): Boolean =
    segments.any { it.distanceTo(point.x, point.y) <= TAP_REACH + borderWidth / 2f }

/**
 * How near a tap has to land on a thin line to have hit it, in page units.
 *
 * A dp value read as page units, the same reading `SelectionChrome.HANDLE_REACH` gets. It lives here
 * rather than in `ShapeLayer` because two different gestures hit-test a tap against the same shapes,
 * and a reach that differs between them is a shape measurably easier to tap with one tool than
 * with another.
 */
internal const val TAP_REACH: Float = 12f

/**
 * All four corners inside the loop — the rule a stroke and a shape are both held to.
 *
 * A table is a rectangle, so its corners are the whole of it: nothing between them can be outside a
 * loop that contains all four. Half-circling one leaves it alone, exactly as half-circling anything
 * else does.
 */
private fun InkBounds.isInsideLasso(polygon: List<InkPoint>, edgeTolerance: Float): Boolean =
    listOf(
        InkPoint(left, top),
        InkPoint(right, top),
        InkPoint(right, bottom),
        InkPoint(left, bottom),
    ).all { pointInOrNearPolygon(it, polygon, edgeTolerance) }

/** Every vertex of every segment inside the loop, the rule a stroke is held to. */
private fun Outline.Shape.isInsideLasso(polygon: List<InkPoint>, edgeTolerance: Float): Boolean {
    if (segments.isEmpty()) return false
    segments.forEach { segment ->
        val points = segment.polyline()
        for (index in points.indices step 2) {
            val point = InkPoint(points[index], points[index + 1])
            if (!pointInOrNearPolygon(point, polygon, edgeTolerance)) return false
        }
    }
    return true
}

/** A shape's stored bounds, in the page units the selection works in. */
internal fun Outline.Shape.pageBounds(): InkBounds =
    InkBounds(left = x, top = y, right = x + width, bottom = y + height)

/**
 * An equation's box, in the same units.
 *
 * Read straight off the document, unlike a table's — a formula's box is exact by construction, so
 * there is nothing here for the canvas to know better (see `Outline.Equation`).
 */
internal fun Outline.Equation.pageBounds(): InkBounds =
    InkBounds(left = x, top = y, right = x + width, bottom = y + height)

/** A picture's frame on the page. Its pixel size is a different fact and lives in `attachments`. */
internal fun Outline.Image.pageBounds(): InkBounds =
    InkBounds(left = x, top = y, right = x + width, bottom = y + height)

internal fun List<InkBounds>.unionBounds(): InkBounds? {
    val first = firstOrNull() ?: return null
    return drop(1).fold(first) { result, next ->
        InkBounds(
            left = minOf(result.left, next.left),
            top = minOf(result.top, next.top),
            right = maxOf(result.right, next.right),
            bottom = maxOf(result.bottom, next.bottom),
        )
    }
}
