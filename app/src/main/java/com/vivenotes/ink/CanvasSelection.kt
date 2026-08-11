package com.vivenotes.ink

import com.vivenotes.model.Outline

/**
 * What is selected on the page, across every kind of object on it — `docs/plan.md` AD7.
 *
 * AD7's first consequence, made a type: *"selection is a page-level concept, not a per-layer one. A
 * lasso that returns only ink is a lasso that will need rewriting the first time a shape is inside
 * it."* Before this, ink's selection lived in `LassoGesture` and a shape's was a bare id on
 * `NotesUiState`, so the two could not describe one loop drawn around both — and each new object kind
 * would have arrived with a third.
 *
 * **Two id sets rather than a set of sealed object references.** Everything downstream is already
 * *these strokes* plus *these shapes* — different repositories, different transforms, ink persisting
 * a move for replay where a shape simply translates its segments — so a sealed set would be unpacked
 * into these two at every use site to say the same thing.
 *
 * [bounds] and [path] are in **page units**, the space ink is stored in and the space an
 * `Outline.Shape`'s coordinates are already in, so no kind has to convert to be selected.
 *
 * The ink half is handed to the existing ink operations unchanged, through [inkHalf] — the history,
 * repository and replay paths know [InkLassoSelection] and have no reason to learn this.
 */
data class CanvasSelection(
    /** The loop that made the selection. Empty when a tap made it: there was no loop. */
    val path: List<InkPoint> = emptyList(),
    val inkIds: Set<String> = emptySet(),
    val shapeIds: Set<String> = emptySet(),
    /** Tables — `docs/tablePlan.md` TA4. The third id set, added for the third kind. */
    val tableIds: Set<String> = emptySet(),
    /** Equations placed on the canvas — the fourth kind, and the fourth id set. */
    val equationIds: Set<String> = emptySet(),
    /** Pictures — feature E6, and the fifth kind. One line here, exactly as [othersEmpty] promised. */
    val imageIds: Set<String> = emptySet(),
    /** Live ink projections, including pieces that share a row id after a partial erase. */
    val projections: Set<InkProjectionKey> = emptySet(),
    val bounds: InkBounds,
) {
    val isEmpty: Boolean
        get() = inkIds.isEmpty() && shapeIds.isEmpty() && tableIds.isEmpty() &&
            equationIds.isEmpty() && imageIds.isEmpty()

    /** True when only one kind is held, which is what decides the per-kind half of the toolkit. */
    val isInkOnly: Boolean get() = inkIds.isNotEmpty() && othersEmpty(inkIds)
    val isShapeOnly: Boolean get() = shapeIds.isNotEmpty() && othersEmpty(shapeIds)
    val isTableOnly: Boolean get() = tableIds.isNotEmpty() && othersEmpty(tableIds)
    val isEquationOnly: Boolean get() = equationIds.isNotEmpty() && othersEmpty(equationIds)
    val isImageOnly: Boolean get() = imageIds.isNotEmpty() && othersEmpty(imageIds)

    /**
     * Every id set except the one asked about is empty.
     *
     * Written once rather than four times, because the four `is…Only` flags were four hand-rolled
     * conjunctions that each had to name every *other* kind — so adding equations meant editing all
     * three of the existing ones, and forgetting one would have quietly claimed a mixed selection was
     * pure. The next kind adds one line here and one flag, and cannot break the others.
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
     * Returns null when nothing it named survives, which is what makes a delete or an undo dismiss the
     * selection rather than leave a rectangle floating over nothing. Ink expands through `groupId`,
     * because touching any member of a group selects all of it (`selectWithLasso`); a shape is already
     * one object and has nothing to expand into.
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
        val directInk = strokes.filter { it.id in inkIds }
        if (liveShapes.isEmpty() && directInk.isEmpty() && liveTables.isEmpty() &&
            liveEquations.isEmpty() && liveImages.isEmpty()
        ) {
            return null
        }

        val groups = directInk.mapNotNull(PageStroke::groupId).toSet()
        val expandedInk = strokes.filter { it.id in inkIds || it.groupId != null && it.groupId in groups }
        val measured = expandedInk.mapNotNull(PageStroke::pageBounds) +
            liveShapes.map(Outline.Shape::pageBounds) +
            liveTables.map(TableBounds::bounds) +
            liveEquations.map(Outline.Equation::pageBounds) +
            liveImages.map(Outline.Image::pageBounds)
        val union = measured.unionBounds() ?: return null

        return copy(
            inkIds = expandedInk.map(PageStroke::id).toSet(),
            shapeIds = liveShapes.map(Outline.Shape::id).toSet(),
            tableIds = liveTables.map(TableBounds::id).toSet(),
            equationIds = liveEquations.map(Outline.Equation::id).toSet(),
            imageIds = liveImages.map(Outline.Image::id).toSet(),
            projections = expandedInk.map(PageStroke::projectionKey).toSet(),
            bounds = union,
        )
    }

    companion object {
        /** One tapped shape. The whole of a tap selection: no loop, no ink. */
        fun ofShape(shape: Outline.Shape): CanvasSelection = CanvasSelection(
            shapeIds = setOf(shape.id),
            bounds = shape.pageBounds(),
        )

        /** One table, selected by putting a caret in any of its cells — `docs/tablePlan.md` TA11. */
        fun ofTable(table: TableBounds): CanvasSelection = CanvasSelection(
            tableIds = setOf(table.id),
            bounds = table.bounds,
        )

        /** One tapped equation, measured from the box the document already knows. */
        fun ofEquation(equation: Outline.Equation): CanvasSelection = CanvasSelection(
            equationIds = setOf(equation.id),
            bounds = equation.pageBounds(),
        )

        /** One tapped picture, measured from its frame rather than from its pixels. */
        fun ofImage(image: Outline.Image): CanvasSelection = CanvasSelection(
            imageIds = setOf(image.id),
            bounds = image.pageBounds(),
        )
    }
}

/**
 * A table as the selection sees it: an id, and the rectangle the **canvas** measured for it.
 *
 * Not `Outline.Table`, deliberately. A table's height is whatever its cells' text wraps to, and the
 * document only stores each row's floor (`docs/tablePlan.md` TA3) — so the model's idea of how tall a
 * table is runs short the moment a cell overflows, and a selection rectangle that runs short is a
 * lasso that misses and a toolbar that sits on top of the thing it belongs to. The canvas knows the
 * true height because it laid the table out, so it is the canvas that says.
 *
 * Bounds are in page units, like everything else here.
 */
data class TableBounds(val id: String, val bounds: InkBounds)

/**
 * The shared prime object clipboard's contents — `docs/diagram.md`.
 *
 * One clipboard holding every kind, rather than one per kind, so that a loop drawn round a stroke and
 * a shape copies both and pastes both. Shallow by design: a native `Stroke` is immutable and an
 * `Outline.Shape` is a data class, so nothing here can be mutated behind the clipboard's back.
 */
data class CanvasClipboard(
    val strokes: List<PageStroke> = emptyList(),
    val shapes: List<Outline.Shape> = emptyList(),
    /**
     * Text containers, carried whole — `docs/textBoxPlan.md` TD5.
     *
     * `Outline.Text` rather than the `OutlineBox` the canvas lays out with, because that one is
     * geometry alone and a copied text box without its text is a rectangle. This is the one place the
     * ViewModel's two halves of a container — the box in `uiState` and the blocks in
     * `blocksById` — are put back together outside of a save.
     */
    val texts: List<Outline.Text> = emptyList(),
    /**
     * Tables, carried with every cell's blocks — `docs/tablePlan.md` TA4.
     *
     * Read out of the ViewModel's block map on the way in rather than off `uiState.tables`, whose
     * cells hold what the page was *loaded* with and go stale the moment anything is typed. A table
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
 * One loop, one selection, however many kinds of thing are inside it — AD7's first row.
 *
 * The ink half is the existing [selectWithLasso] on strokes, untouched. The shape half applies the
 * *same* rule ink uses ([isInsideLasso]): every point of the object must be in or near the loop, so a
 * shape that is only half circled is left alone exactly as a half-circled stroke is. Anything else and
 * the lasso would feel like two different tools depending on what was under it.
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
    if (path.size < 3) return null
    val ink = strokes.selectWithLasso(path, edgeTolerance)
    val caughtShapes = shapes.filter { it.isInsideLasso(path, edgeTolerance) }
    val caughtTables = tables.filter { it.bounds.isInsideLasso(path, edgeTolerance) }
    // A rectangle, so the same four-corner rule a table is held to — see [isInsideLasso].
    val caughtEquations = equations.filter { it.pageBounds().isInsideLasso(path, edgeTolerance) }
    // A picture is a rectangle too, and is caught by its frame rather than by what is in it: a
    // photograph of a circle is still a photograph, and half-circling one leaves it alone.
    val caughtImages = images.filter { it.pageBounds().isInsideLasso(path, edgeTolerance) }
    if (ink == null && caughtShapes.isEmpty() && caughtTables.isEmpty() &&
        caughtEquations.isEmpty() && caughtImages.isEmpty()
    ) {
        return null
    }

    val measured = (ink?.let { listOf(it.bounds) } ?: emptyList()) +
        caughtShapes.map(Outline.Shape::pageBounds) +
        caughtTables.map(TableBounds::bounds) +
        caughtEquations.map(Outline.Equation::pageBounds) +
        caughtImages.map(Outline.Image::pageBounds)
    val union = measured.unionBounds() ?: return null
    return CanvasSelection(
        path = path,
        inkIds = ink?.targetIds.orEmpty(),
        shapeIds = caughtShapes.map(Outline.Shape::id).toSet(),
        tableIds = caughtTables.map(TableBounds::id).toSet(),
        equationIds = caughtEquations.map(Outline.Equation::id).toSet(),
        imageIds = caughtImages.map(Outline.Image::id).toSet(),
        projections = ink?.projections.orEmpty(),
        bounds = union,
    )
}

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
